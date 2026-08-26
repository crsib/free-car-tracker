// Small SVG plots for the dashboard and analysis views. SVG rather than canvas here: these
// are low-density (a dozen bars, a few hundred scatter points), and SVG gives crisp text,
// free hit-testing and CSS theming without a redraw loop.

import { el } from './dom.js';
import { SERIES_COLORS } from './chart.js';

const NS = 'http://www.w3.org/2000/svg';

function svgEl(tag, attrs = {}, ...children) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** Horizontal bars for a category breakdown, biggest first, each labelled with its value. */
export function barList(items, { format = String, color } = {}) {
  const max = items.reduce((m, i) => Math.max(m, i.value), 0) || 1;
  return el('div', { class: 'bars' },
    ...items.map((item, i) => el('div', { class: 'bar-row' },
      el('div', { class: 'bar-label' }, item.label),
      el('div', { class: 'bar-track' },
        el('div', {
          class: 'bar-fill',
          style: {
            width: `${Math.max(1.5, (item.value / max) * 100)}%`,
            background: color || item.color || SERIES_COLORS[i % SERIES_COLORS.length],
          },
        })),
      el('div', { class: 'bar-value' }, format(item.value)))));
}

function niceScale(values, padFraction = 0.1) {
  const finite = values.filter((v) => Number.isFinite(v));
  if (!finite.length) return [0, 1];
  let min = Math.min(...finite);
  let max = Math.max(...finite);
  if (min === max) { min -= 1; max += 1; }
  const pad = (max - min) * padFraction;
  return [min - pad, max + pad];
}

/**
 * A time-ordered line with points. Used for the consumption trend, where each point is one
 * completed full-tank interval - so the dots matter as much as the line.
 */
export function trendPlot(points, { format = (v) => v.toFixed(1), height = 170, color = SERIES_COLORS[0] } = {}) {
  if (!points.length) return el('div', { class: 'empty' }, 'Not enough full-tank fills yet');

  const width = 640;
  const pad = { top: 12, right: 14, bottom: 26, left: 44 };
  const w = width - pad.left - pad.right;
  const h = height - pad.top - pad.bottom;

  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  const [x0, x1] = points.length > 1 ? [Math.min(...xs), Math.max(...xs)] : [xs[0] - 1, xs[0] + 1];
  const [y0, y1] = niceScale(ys);

  const px = (v) => pad.left + ((v - x0) / (x1 - x0)) * w;
  const py = (v) => pad.top + h - ((v - y0) / (y1 - y0)) * h;

  const gridLines = [];
  const labels = [];
  for (let i = 0; i <= 3; i += 1) {
    const v = y0 + ((y1 - y0) * i) / 3;
    const y = py(v);
    gridLines.push(svgEl('line', { x1: pad.left, y1: y, x2: pad.left + w, y2: y, class: 'plot-grid' }));
    labels.push(svgEl('text', { x: pad.left - 8, y: y + 4, class: 'plot-tick', 'text-anchor': 'end' }, format(v)));
  }

  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${px(p.x).toFixed(1)},${py(p.y).toFixed(1)}`).join(' ');

  return el('div', { class: 'plot' },
    svgEl('svg', { viewBox: `0 0 ${width} ${height}`, preserveAspectRatio: 'none', class: 'plot-svg' },
      ...gridLines,
      ...labels,
      svgEl('path', { d: path, fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-linejoin': 'round' }),
      ...points.map((p) => svgEl('circle', {
        cx: px(p.x), cy: py(p.y), r: 3.5, fill: color,
      }, svgEl('title', {}, `${p.title || ''}${p.title ? ' — ' : ''}${format(p.y)}`))),
      points.length > 1 ? svgEl('text', {
        x: pad.left, y: height - 6, class: 'plot-tick', 'text-anchor': 'start',
      }, new Date(x0).toLocaleDateString(undefined, { month: 'short', year: '2-digit' })) : null,
      points.length > 1 ? svgEl('text', {
        x: pad.left + w, y: height - 6, class: 'plot-tick', 'text-anchor': 'end',
      }, new Date(x1).toLocaleDateString(undefined, { month: 'short', year: '2-digit' })) : null));
}

/**
 * A scatter of one trip per dot. This is the cross-trip view's whole point: put consumption
 * against ambient temperature, or SoC used against elevation gain, and the relationship is
 * either there or it is not.
 */
export function scatterPlot(points, {
  xLabel = '', yLabel = '', height = 380, onSelect = null, format = (v) => v.toFixed(1),
} = {}) {
  const usable = points.filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y));
  if (!usable.length) {
    return el('div', { class: 'empty' },
      'No trips have both of those metrics. Try different axes, or run "Download all trip data" so '
      + 'the OBD-derived metrics are available.');
  }

  const width = 900;
  const pad = { top: 16, right: 18, bottom: 46, left: 64 };
  const w = width - pad.left - pad.right;
  const h = height - pad.top - pad.bottom;

  const [x0, x1] = niceScale(usable.map((p) => p.x));
  const [y0, y1] = niceScale(usable.map((p) => p.y));
  const px = (v) => pad.left + ((v - x0) / (x1 - x0)) * w;
  const py = (v) => pad.top + h - ((v - y0) / (y1 - y0)) * h;

  const grid = [];
  for (let i = 0; i <= 4; i += 1) {
    const yv = y0 + ((y1 - y0) * i) / 4;
    const y = py(yv);
    grid.push(svgEl('line', { x1: pad.left, y1: y, x2: pad.left + w, y2: y, class: 'plot-grid' }));
    grid.push(svgEl('text', { x: pad.left - 8, y: y + 4, class: 'plot-tick', 'text-anchor': 'end' }, format(yv)));

    const xv = x0 + ((x1 - x0) * i) / 4;
    const x = px(xv);
    grid.push(svgEl('line', { x1: x, y1: pad.top, x2: x, y2: pad.top + h, class: 'plot-grid' }));
    grid.push(svgEl('text', { x, y: pad.top + h + 18, class: 'plot-tick', 'text-anchor': 'middle' }, format(xv)));
  }

  // A least-squares fit, drawn only when there is enough data for it to mean anything.
  let trend = null;
  let r2Text = null;
  if (usable.length >= 4) {
    const n = usable.length;
    const sx = usable.reduce((a, p) => a + p.x, 0);
    const sy = usable.reduce((a, p) => a + p.y, 0);
    const sxx = usable.reduce((a, p) => a + p.x * p.x, 0);
    const sxy = usable.reduce((a, p) => a + p.x * p.y, 0);
    const denom = n * sxx - sx * sx;
    if (Math.abs(denom) > 1e-9) {
      const slope = (n * sxy - sx * sy) / denom;
      const intercept = (sy - slope * sx) / n;
      const meanY = sy / n;
      const ssTot = usable.reduce((a, p) => a + (p.y - meanY) ** 2, 0);
      const ssRes = usable.reduce((a, p) => a + (p.y - (slope * p.x + intercept)) ** 2, 0);
      const r2 = ssTot > 0 ? 1 - ssRes / ssTot : 0;
      trend = svgEl('line', {
        x1: px(x0), y1: py(slope * x0 + intercept),
        x2: px(x1), y2: py(slope * x1 + intercept),
        class: 'plot-trend',
      });
      r2Text = `R² ${r2.toFixed(2)} · slope ${slope.toPrecision(3)}`;
    }
  }

  const dots = usable.map((p) => svgEl('circle', {
    cx: px(p.x), cy: py(p.y), r: 4.5, class: 'plot-dot',
    fill: p.color || SERIES_COLORS[0],
    onclick: onSelect ? () => onSelect(p) : null,
    style: onSelect ? 'cursor:pointer' : null,
  }, svgEl('title', {}, `${p.label || ''}\n${xLabel}: ${format(p.x)}\n${yLabel}: ${format(p.y)}`)));

  return el('div', { class: 'plot' },
    svgEl('svg', { viewBox: `0 0 ${width} ${height}`, class: 'plot-svg scatter' },
      ...grid,
      trend,
      ...dots,
      svgEl('text', { x: pad.left + w / 2, y: height - 6, class: 'plot-axis-label', 'text-anchor': 'middle' }, xLabel),
      svgEl('text', {
        x: -(pad.top + h / 2), y: 16, class: 'plot-axis-label',
        'text-anchor': 'middle', transform: 'rotate(-90)',
      }, yLabel)),
    r2Text ? el('div', { class: 'plot-note' }, r2Text, ' · ', `${usable.length} trips`)
      : el('div', { class: 'plot-note' }, `${usable.length} trips`));
}

/** Buckets a set of values into a histogram - useful for "how spread out is my economy". */
export function histogram(values, { bins = 12, format = (v) => v.toFixed(1), height = 150, color = SERIES_COLORS[2] } = {}) {
  const finite = values.filter((v) => Number.isFinite(v));
  if (finite.length < 2) return el('div', { class: 'empty' }, 'Not enough data');
  const min = Math.min(...finite);
  const max = Math.max(...finite);
  const width = (max - min) / bins || 1;
  const counts = new Array(bins).fill(0);
  for (const v of finite) {
    counts[Math.min(bins - 1, Math.floor((v - min) / width))] += 1;
  }
  const peak = Math.max(...counts);
  return el('div', { class: 'histogram', style: { height: `${height}px` } },
    ...counts.map((c, i) => el('div', {
      class: 'histogram-bar',
      title: `${format(min + i * width)} – ${format(min + (i + 1) * width)}: ${c}`,
    },
    el('div', {
      class: 'histogram-fill',
      style: { height: `${(c / peak) * 100}%`, background: color },
    }))));
}
