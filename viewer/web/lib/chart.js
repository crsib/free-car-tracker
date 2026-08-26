// A canvas time-series chart built for OBD traces: many series, wildly different units,
// gaps where the adapter did not answer, and hundreds of thousands of samples behind a
// decimated view. Written by hand rather than pulled from a library so the server binary
// stays self-contained and the interactions can be wired straight into the map.

export const SERIES_COLORS = [
  '#4c8dff', '#ff8f3f', '#3fcf8e', '#e2566d', '#b07cff',
  '#2fb8c9', '#e0b33c', '#7f8fa6', '#ff6fae', '#66c15a',
];

const PAD = { top: 14, right: 58, bottom: 26, left: 58 };

/** Round a span up to a human-friendly tick step (1, 2, 5, 10 x 10^n). */
function niceStep(span, targetTicks) {
  if (!(span > 0)) return 1;
  const raw = span / Math.max(1, targetTicks);
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const norm = raw / mag;
  const step = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
  return step * mag;
}

function extent(values) {
  let min = Infinity;
  let max = -Infinity;
  for (const v of values) {
    if (v === null || v === undefined || !Number.isFinite(v)) continue;
    if (v < min) min = v;
    if (v > max) max = v;
  }
  if (min === Infinity) return null;
  if (min === max) {
    // A flat series would otherwise collapse to a zero-height scale and vanish.
    const pad = Math.abs(min) > 1 ? Math.abs(min) * 0.05 : 0.5;
    return [min - pad, max + pad];
  }
  const pad = (max - min) * 0.06;
  return [min - pad, max + pad];
}

/** Format an axis value compactly; OBD ranges run from 0.02 L/100km to 6000 rpm. */
function axisLabel(v) {
  const abs = Math.abs(v);
  if (abs >= 10000) return `${Math.round(v / 1000)}k`;
  if (abs >= 100) return String(Math.round(v));
  if (abs >= 10) return v.toFixed(1);
  if (abs >= 1) return v.toFixed(1);
  if (abs === 0) return '0';
  return v.toFixed(2);
}

function timeTicks(from, to, width) {
  const span = to - from;
  if (!(span > 0)) return [];
  const target = Math.max(2, Math.floor(width / 110));
  const steps = [
    1000, 5000, 15000, 30000, 60000, 300000, 900000, 1800000, 3600000,
    7200000, 21600000, 43200000, 86400000,
  ];
  let step = steps[steps.length - 1];
  for (const s of steps) {
    if (span / s <= target) { step = s; break; }
  }
  const ticks = [];
  // Anchor ticks on the local-time grid rather than on the trip start, so labels land on
  // round clock values instead of drifting by the trip's arbitrary start second.
  const offset = new Date(from).getTimezoneOffset() * 60000;
  let t = Math.ceil((from - offset) / step) * step + offset;
  while (t <= to && ticks.length < 64) {
    ticks.push(t);
    t += step;
  }
  return { ticks, step };
}

function tickText(millis, step) {
  const d = new Date(millis);
  if (step >= 86400000) return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  if (step >= 60000) return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export class TimeChart {
  /**
   * @param {HTMLElement} container
   * @param {{height?: number, onHover?: (info: object|null) => void, onZoom?: (range: object|null) => void}} opts
   */
  constructor(container, opts = {}) {
    this.container = container;
    this.opts = opts;
    this.height = opts.height || 260;

    this.canvas = document.createElement('canvas');
    this.canvas.className = 'chart-canvas';
    this.tooltip = document.createElement('div');
    this.tooltip.className = 'chart-tooltip';
    this.tooltip.hidden = true;

    container.classList.add('chart-host');
    container.style.height = `${this.height}px`;
    container.appendChild(this.canvas);
    container.appendChild(this.tooltip);

    this.data = { t: [], series: [] };
    this.view = null;      // [from, to] when zoomed
    this.hoverIndex = null;
    this.drag = null;

    this.ctx = this.canvas.getContext('2d');
    this.bind();

    this.resizeObserver = new ResizeObserver(() => this.render());
    this.resizeObserver.observe(container);
  }

  destroy() {
    this.resizeObserver.disconnect();
    this.container.classList.remove('chart-host');
    this.container.innerHTML = '';
  }

  setData(data) {
    this.data = { t: data.t || [], series: data.series || [] };
    this.hoverIndex = null;
    this.render();
  }

  /** Reset the zoom without touching the data. */
  resetView() {
    this.view = null;
    if (this.opts.onZoom) this.opts.onZoom(null);
    this.render();
  }

  /** Move the crosshair from outside (used to follow the map cursor). */
  highlightTime(millis) {
    if (millis === null || millis === undefined) {
      this.hoverIndex = null;
    } else {
      this.hoverIndex = this.nearestIndex(millis);
    }
    this.render();
  }

  nearestIndex(millis) {
    const t = this.data.t;
    if (!t.length) return null;
    let lo = 0;
    let hi = t.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (t[mid] < millis) lo = mid + 1; else hi = mid;
    }
    if (lo > 0 && Math.abs(t[lo - 1] - millis) < Math.abs(t[lo] - millis)) return lo - 1;
    return lo;
  }

  // --- geometry ---

  plotRect() {
    const w = this.canvas.clientWidth || this.container.clientWidth;
    const h = this.height;
    return {
      x: PAD.left, y: PAD.top,
      w: Math.max(10, w - PAD.left - PAD.right),
      h: Math.max(10, h - PAD.top - PAD.bottom),
    };
  }

  xRange() {
    const t = this.data.t;
    if (!t.length) return [0, 1];
    if (this.view) return this.view;
    return [t[0], t[t.length - 1]];
  }

  /**
   * Series sharing a unit share a scale, which is the only way "coolant 90 C" and "intake
   * 40 C" stay comparable on one plot. The first two unit groups get the left and right
   * axes; anything beyond that is drawn on its own normalised scale, with the real range
   * shown in the legend so the line is never mistaken for an absolute reading.
   */
  scales() {
    const groups = new Map();
    for (const s of this.data.series) {
      const key = s.unit || s.name;
      if (!groups.has(key)) groups.set(key, { unit: s.unit || '', series: [], range: null });
      groups.get(key).series.push(s);
    }
    const [from, to] = this.xRange();
    const t = this.data.t;
    for (const g of groups.values()) {
      const visible = [];
      for (const s of g.series) {
        for (let i = 0; i < t.length; i += 1) {
          if (t[i] < from || t[i] > to) continue;
          const v = s.values[i];
          if (v !== null && v !== undefined) visible.push(v);
        }
      }
      g.range = extent(visible) || [0, 1];
    }
    return Array.from(groups.values());
  }

  // --- interaction ---

  bind() {
    const canvas = this.canvas;

    canvas.addEventListener('mousemove', (e) => {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const plot = this.plotRect();
      if (this.drag) {
        this.drag.to = x;
        this.render();
        return;
      }
      if (x < plot.x || x > plot.x + plot.w || !this.data.t.length) {
        this.setHover(null);
        return;
      }
      const [from, to] = this.xRange();
      const millis = from + ((x - plot.x) / plot.w) * (to - from);
      this.setHover(this.nearestIndex(millis), e.clientX - rect.left, e.clientY - rect.top);
    });

    canvas.addEventListener('mouseleave', () => {
      if (!this.drag) this.setHover(null);
    });

    canvas.addEventListener('mousedown', (e) => {
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const plot = this.plotRect();
      if (x < plot.x || x > plot.x + plot.w) return;
      this.drag = { from: x, to: x };
      e.preventDefault();
    });

    window.addEventListener('mouseup', () => {
      if (!this.drag) return;
      const { from, to } = this.drag;
      this.drag = null;
      // A click is a drag of a few pixels; treat it as "no selection" rather than zooming
      // to a one-sample window the user cannot get out of.
      if (Math.abs(to - from) < 8) { this.render(); return; }
      const plot = this.plotRect();
      const [xa, xb] = this.xRange();
      const toTime = (px) => xa + ((Math.min(Math.max(px, plot.x), plot.x + plot.w) - plot.x) / plot.w) * (xb - xa);
      const a = toTime(Math.min(from, to));
      const b = toTime(Math.max(from, to));
      this.view = [a, b];
      if (this.opts.onZoom) this.opts.onZoom({ from: Math.round(a), to: Math.round(b) });
      this.render();
    });

    canvas.addEventListener('dblclick', () => this.resetView());

    canvas.addEventListener('wheel', (e) => {
      if (!this.data.t.length) return;
      e.preventDefault();
      const plot = this.plotRect();
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const [from, to] = this.xRange();
      const frac = Math.min(Math.max((x - plot.x) / plot.w, 0), 1);
      const anchor = from + frac * (to - from);
      const factor = e.deltaY > 0 ? 1.25 : 0.8;
      const span = (to - from) * factor;
      const t = this.data.t;
      const fullSpan = t[t.length - 1] - t[0];
      if (span >= fullSpan) { this.resetView(); return; }
      let a = anchor - frac * span;
      let b = a + span;
      if (a < t[0]) { a = t[0]; b = a + span; }
      if (b > t[t.length - 1]) { b = t[t.length - 1]; a = b - span; }
      this.view = [a, b];
      if (this.opts.onZoom) this.opts.onZoom({ from: Math.round(a), to: Math.round(b) });
      this.render();
    }, { passive: false });
  }

  setHover(index, mouseX, mouseY) {
    this.hoverIndex = index;
    this.mouse = index === null ? null : { x: mouseX, y: mouseY };
    if (this.opts.onHover) {
      this.opts.onHover(index === null ? null : { index, t: this.data.t[index] });
    }
    this.render();
  }

  // --- rendering ---

  render() {
    const dpr = window.devicePixelRatio || 1;
    const cssWidth = this.container.clientWidth;
    if (!cssWidth) return;
    const cssHeight = this.height;
    if (this.canvas.width !== Math.round(cssWidth * dpr) || this.canvas.height !== Math.round(cssHeight * dpr)) {
      this.canvas.width = Math.round(cssWidth * dpr);
      this.canvas.height = Math.round(cssHeight * dpr);
      this.canvas.style.width = `${cssWidth}px`;
      this.canvas.style.height = `${cssHeight}px`;
    }
    const ctx = this.ctx;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssWidth, cssHeight);

    const style = getComputedStyle(this.container);
    const gridColor = style.getPropertyValue('--chart-grid').trim() || 'rgba(128,128,128,0.18)';
    const textColor = style.getPropertyValue('--chart-text').trim() || '#8a94a6';

    const plot = this.plotRect();
    const t = this.data.t;
    if (!t.length) {
      ctx.fillStyle = textColor;
      ctx.font = '13px system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('No data for this trip', cssWidth / 2, cssHeight / 2);
      this.tooltip.hidden = true;
      return;
    }

    const [from, to] = this.xRange();
    const groups = this.scales();
    const xOf = (millis) => plot.x + ((millis - from) / (to - from)) * plot.w;

    // --- grid + axes ---
    ctx.font = '11px system-ui, sans-serif';
    ctx.strokeStyle = gridColor;
    ctx.fillStyle = textColor;
    ctx.lineWidth = 1;

    const primary = groups[0];
    const secondary = groups[1];

    if (primary) {
      const [lo, hi] = primary.range;
      const step = niceStep(hi - lo, 5);
      ctx.textAlign = 'right';
      ctx.textBaseline = 'middle';
      for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) {
        const y = plot.y + plot.h - ((v - lo) / (hi - lo)) * plot.h;
        ctx.beginPath();
        ctx.moveTo(plot.x, Math.round(y) + 0.5);
        ctx.lineTo(plot.x + plot.w, Math.round(y) + 0.5);
        ctx.stroke();
        ctx.fillText(axisLabel(v), plot.x - 6, y);
      }
      ctx.save();
      ctx.translate(12, plot.y + plot.h / 2);
      ctx.rotate(-Math.PI / 2);
      ctx.textAlign = 'center';
      ctx.fillText(primary.unit || primary.series[0].name, 0, 0);
      ctx.restore();
    }

    if (secondary) {
      const [lo, hi] = secondary.range;
      const step = niceStep(hi - lo, 5);
      ctx.textAlign = 'left';
      for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) {
        const y = plot.y + plot.h - ((v - lo) / (hi - lo)) * plot.h;
        ctx.fillText(axisLabel(v), plot.x + plot.w + 6, y);
      }
      ctx.save();
      ctx.translate(cssWidth - 12, plot.y + plot.h / 2);
      ctx.rotate(Math.PI / 2);
      ctx.textAlign = 'center';
      ctx.fillText(secondary.unit || secondary.series[0].name, 0, 0);
      ctx.restore();
    }

    const { ticks, step } = timeTicks(from, to, plot.w);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    for (const tick of ticks) {
      const x = Math.round(xOf(tick)) + 0.5;
      ctx.beginPath();
      ctx.moveTo(x, plot.y);
      ctx.lineTo(x, plot.y + plot.h);
      ctx.stroke();
      ctx.fillText(tickText(tick, step), x, plot.y + plot.h + 6);
    }

    // --- series ---
    let colorIndex = 0;
    for (const group of groups) {
      const [lo, hi] = group.range;
      for (const s of group.series) {
        const color = s.color || SERIES_COLORS[colorIndex % SERIES_COLORS.length];
        s._color = color;
        colorIndex += 1;
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.6;
        ctx.lineJoin = 'round';
        ctx.beginPath();
        let drawing = false;
        for (let i = 0; i < t.length; i += 1) {
          if (t[i] < from || t[i] > to) continue;
          const v = s.values[i];
          if (v === null || v === undefined) {
            // A gap is a gap: lift the pen rather than interpolating across silence.
            drawing = false;
            continue;
          }
          const x = xOf(t[i]);
          const y = plot.y + plot.h - ((v - lo) / (hi - lo)) * plot.h;
          if (!drawing) { ctx.moveTo(x, y); drawing = true; } else { ctx.lineTo(x, y); }
        }
        ctx.stroke();
      }
    }

    // --- drag selection ---
    if (this.drag) {
      const a = Math.min(this.drag.from, this.drag.to);
      const b = Math.max(this.drag.from, this.drag.to);
      ctx.fillStyle = 'rgba(76, 141, 255, 0.16)';
      ctx.fillRect(a, plot.y, b - a, plot.h);
      ctx.strokeStyle = 'rgba(76, 141, 255, 0.6)';
      ctx.strokeRect(a + 0.5, plot.y + 0.5, b - a, plot.h);
    }

    // --- crosshair + tooltip ---
    const idx = this.hoverIndex;
    if (idx !== null && idx >= 0 && idx < t.length && t[idx] >= from && t[idx] <= to) {
      const x = Math.round(xOf(t[idx])) + 0.5;
      ctx.strokeStyle = textColor;
      ctx.globalAlpha = 0.5;
      ctx.beginPath();
      ctx.moveTo(x, plot.y);
      ctx.lineTo(x, plot.y + plot.h);
      ctx.stroke();
      ctx.globalAlpha = 1;

      for (const group of groups) {
        const [lo, hi] = group.range;
        for (const s of group.series) {
          const v = s.values[idx];
          if (v === null || v === undefined) continue;
          const y = plot.y + plot.h - ((v - lo) / (hi - lo)) * plot.h;
          ctx.fillStyle = s._color;
          ctx.beginPath();
          ctx.arc(x, y, 3, 0, Math.PI * 2);
          ctx.fill();
        }
      }
      this.showTooltip(idx, x, plot, cssWidth);
    } else {
      this.tooltip.hidden = true;
    }
  }

  showTooltip(idx, x, plot, cssWidth) {
    const t = this.data.t[idx];
    const rows = this.data.series.map((s) => {
      const v = s.values[idx];
      if (v === null || v === undefined) return null;
      const digits = Math.abs(v) >= 100 ? 0 : Math.abs(v) >= 1 ? 1 : 3;
      return `<div class="tt-row"><span class="tt-dot" style="background:${s._color}"></span>` +
        `<span class="tt-name">${s.label || s.name}</span>` +
        `<span class="tt-val">${v.toFixed(digits)}${s.unit ? ` ${s.unit}` : ''}</span></div>`;
    }).filter(Boolean);

    if (!rows.length) { this.tooltip.hidden = true; return; }
    const time = new Date(t).toLocaleTimeString(undefined, {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
    this.tooltip.innerHTML = `<div class="tt-time">${time}</div>${rows.join('')}`;
    this.tooltip.hidden = false;

    // Flip the tooltip to the other side of the crosshair near the right edge so it never
    // hangs off the plot.
    const width = this.tooltip.offsetWidth;
    const left = x + 14 + width > cssWidth ? x - width - 14 : x + 14;
    this.tooltip.style.left = `${Math.max(4, left)}px`;
    this.tooltip.style.top = `${plot.y + 4}px`;
  }
}
