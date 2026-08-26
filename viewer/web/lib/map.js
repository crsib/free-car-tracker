// A small slippy map: Web Mercator tiles under a canvas-drawn route. Written here rather
// than pulled from a mapping library for the same reason as the chart - no vendored assets,
// no CDN, so the binary is the whole application. Tiles are the one thing that needs the
// internet, and the map degrades to a plain route drawing when they cannot be reached.

const TILE = 256;

export const TILE_SOURCES = {
  osm: {
    label: 'OpenStreetMap',
    url: (x, y, z) => `https://tile.openstreetmap.org/${z}/${x}/${y}.png`,
    attribution: '© OpenStreetMap contributors',
    maxZoom: 19,
  },
  none: { label: 'No tiles (offline)', url: null, attribution: '', maxZoom: 20 },
};

const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

/** Latitude/longitude to world pixels at a given zoom. */
function project(lat, lon, z) {
  const size = TILE * Math.pow(2, z);
  const x = ((lon + 180) / 360) * size;
  const s = Math.sin((clamp(lat, -85.05112878, 85.05112878) * Math.PI) / 180);
  const y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * size;
  return { x, y };
}

/** Speed to colour: a cool-to-warm ramp so a route reads as a speed profile at a glance. */
function speedColor(kmh, maxSpeed) {
  if (kmh === null || kmh === undefined || !Number.isFinite(kmh)) return '#7f8fa6';
  const f = clamp(kmh / Math.max(30, maxSpeed || 120), 0, 1);
  const stops = [
    [0.0, [58, 110, 200]],
    [0.35, [63, 207, 142]],
    [0.65, [224, 179, 60]],
    [1.0, [226, 86, 109]],
  ];
  for (let i = 1; i < stops.length; i += 1) {
    if (f <= stops[i][0]) {
      const [f0, c0] = stops[i - 1];
      const [f1, c1] = stops[i];
      const k = (f - f0) / (f1 - f0);
      const c = c0.map((v, j) => Math.round(v + (c1[j] - v) * k));
      return `rgb(${c[0]},${c[1]},${c[2]})`;
    }
  }
  return 'rgb(226,86,109)';
}

export class RouteMap {
  constructor(container, opts = {}) {
    this.container = container;
    this.opts = opts;
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'map-canvas';
    container.classList.add('map-host');
    container.appendChild(this.canvas);

    this.attribution = document.createElement('div');
    this.attribution.className = 'map-attribution';
    container.appendChild(this.attribution);

    this.ctx = this.canvas.getContext('2d');
    this.tiles = new Map();     // key -> Image | 'failed'
    this.source = TILE_SOURCES[opts.source || 'osm'];
    this.track = null;
    this.zoom = 12;
    this.center = { lat: 0, lon: 0 };
    this.hoverIndex = null;
    this.tileFailures = 0;

    this.bind();
    this.resizeObserver = new ResizeObserver(() => this.render());
    this.resizeObserver.observe(container);
  }

  destroy() {
    this.resizeObserver.disconnect();
    this.container.classList.remove('map-host');
    this.container.innerHTML = '';
  }

  setSource(key) {
    this.source = TILE_SOURCES[key] || TILE_SOURCES.osm;
    this.tileFailures = 0;
    this.render();
  }

  /** @param {{lat: number[], lon: number[], t: number[], speedKmh: (number|null)[]}} track */
  setTrack(track) {
    this.track = track;
    this.maxSpeed = 0;
    if (track && track.speedKmh) {
      for (const v of track.speedKmh) {
        if (Number.isFinite(v) && v > this.maxSpeed) this.maxSpeed = v;
      }
    }
    this.fit();
  }

  /** Frame the whole route with a little margin. */
  fit() {
    const track = this.track;
    if (!track || !track.lat || !track.lat.length) { this.render(); return; }
    let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
    for (let i = 0; i < track.lat.length; i += 1) {
      minLat = Math.min(minLat, track.lat[i]); maxLat = Math.max(maxLat, track.lat[i]);
      minLon = Math.min(minLon, track.lon[i]); maxLon = Math.max(maxLon, track.lon[i]);
    }
    this.center = { lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2 };

    const w = this.container.clientWidth || 600;
    const h = this.container.clientHeight || 360;
    let best = 3;
    for (let z = this.source.maxZoom; z >= 2; z -= 1) {
      const a = project(maxLat, minLon, z);
      const b = project(minLat, maxLon, z);
      if (Math.abs(b.x - a.x) <= w * 0.86 && Math.abs(b.y - a.y) <= h * 0.86) { best = z; break; }
    }
    this.zoom = best;
    this.render();
  }

  highlightTime(millis) {
    if (millis === null || millis === undefined || !this.track) {
      this.hoverIndex = null;
    } else {
      const t = this.track.t;
      let lo = 0, hi = t.length - 1;
      while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (t[mid] < millis) lo = mid + 1; else hi = mid;
      }
      this.hoverIndex = lo;
    }
    this.render();
  }

  bind() {
    const canvas = this.canvas;
    let dragging = null;

    canvas.addEventListener('mousedown', (e) => {
      dragging = { x: e.clientX, y: e.clientY, center: { ...this.center } };
      canvas.classList.add('grabbing');
    });

    window.addEventListener('mouseup', () => {
      dragging = null;
      canvas.classList.remove('grabbing');
    });

    canvas.addEventListener('mousemove', (e) => {
      if (dragging) {
        const size = TILE * Math.pow(2, this.zoom);
        const dx = e.clientX - dragging.x;
        const dy = e.clientY - dragging.y;
        const origin = project(dragging.center.lat, dragging.center.lon, this.zoom);
        this.center = this.unproject(origin.x - dx, origin.y - dy, this.zoom, size);
        this.render();
        return;
      }
      if (this.opts.onHover && this.track) {
        const near = this.nearestPoint(e);
        this.hoverIndex = near;
        this.opts.onHover(near === null ? null : { index: near, t: this.track.t[near] });
        this.render();
      }
    });

    canvas.addEventListener('mouseleave', () => {
      if (this.opts.onHover) this.opts.onHover(null);
      this.hoverIndex = null;
      this.render();
    });

    canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      const next = clamp(this.zoom + (e.deltaY > 0 ? -1 : 1), 2, this.source.maxZoom);
      if (next === this.zoom) return;
      // Zoom about the cursor, so the feature under the pointer stays put.
      const rect = canvas.getBoundingClientRect();
      const px = e.clientX - rect.left - rect.width / 2;
      const py = e.clientY - rect.top - rect.height / 2;
      const before = project(this.center.lat, this.center.lon, this.zoom);
      const target = { x: before.x + px, y: before.y + py };
      const scale = Math.pow(2, next - this.zoom);
      const after = { x: target.x * scale - px, y: target.y * scale - py };
      this.zoom = next;
      this.center = this.unproject(after.x, after.y, next, TILE * Math.pow(2, next));
      this.render();
    }, { passive: false });

    canvas.addEventListener('dblclick', () => this.fit());
  }

  unproject(x, y, z, size) {
    const lon = (x / size) * 360 - 180;
    const n = Math.PI - (2 * Math.PI * y) / size;
    const lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
    return { lat: clamp(lat, -85, 85), lon: ((lon + 540) % 360) - 180 };
  }

  nearestPoint(e) {
    const rect = this.canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const origin = project(this.center.lat, this.center.lon, this.zoom);
    const ox = origin.x - rect.width / 2;
    const oy = origin.y - rect.height / 2;
    let best = null;
    let bestDist = 30 * 30; // only snap within ~30px, else report no hover
    const track = this.track;
    // Stride over long tracks: at 40k points a per-pixel scan on every mousemove would
    // visibly lag, and a coarse pass is more than accurate enough for a 30px radius.
    const stride = Math.max(1, Math.floor(track.lat.length / 4000));
    for (let i = 0; i < track.lat.length; i += stride) {
      const p = project(track.lat[i], track.lon[i], this.zoom);
      const dx = p.x - ox - mx;
      const dy = p.y - oy - my;
      const d = dx * dx + dy * dy;
      if (d < bestDist) { bestDist = d; best = i; }
    }
    return best;
  }

  tile(x, y, z) {
    if (!this.source.url) return null;
    const max = Math.pow(2, z);
    if (y < 0 || y >= max) return null;
    const wrappedX = ((x % max) + max) % max;
    const key = `${z}/${wrappedX}/${y}`;
    const cached = this.tiles.get(key);
    if (cached) return cached === 'failed' ? null : cached;

    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => this.render();
    img.onerror = () => {
      this.tiles.set(key, 'failed');
      this.tileFailures += 1;
      this.render();
    };
    img.src = this.source.url(wrappedX, y, z);
    this.tiles.set(key, img);
    return img;
  }

  render() {
    const dpr = window.devicePixelRatio || 1;
    const w = this.container.clientWidth;
    const h = this.container.clientHeight;
    if (!w || !h) return;
    if (this.canvas.width !== Math.round(w * dpr) || this.canvas.height !== Math.round(h * dpr)) {
      this.canvas.width = Math.round(w * dpr);
      this.canvas.height = Math.round(h * dpr);
      this.canvas.style.width = `${w}px`;
      this.canvas.style.height = `${h}px`;
    }
    const ctx = this.ctx;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const style = getComputedStyle(this.container);
    ctx.fillStyle = style.getPropertyValue('--map-bg').trim() || '#10141c';
    ctx.fillRect(0, 0, w, h);

    const z = Math.round(this.zoom);
    const origin = project(this.center.lat, this.center.lon, z);
    const ox = origin.x - w / 2;
    const oy = origin.y - h / 2;

    if (this.source.url) {
      const x0 = Math.floor(ox / TILE);
      const y0 = Math.floor(oy / TILE);
      const x1 = Math.floor((ox + w) / TILE);
      const y1 = Math.floor((oy + h) / TILE);
      for (let ty = y0; ty <= y1; ty += 1) {
        for (let tx = x0; tx <= x1; tx += 1) {
          const img = this.tile(tx, ty, z);
          if (img && img.complete && img.naturalWidth) {
            ctx.drawImage(img, Math.round(tx * TILE - ox), Math.round(ty * TILE - oy), TILE, TILE);
          }
        }
      }
    }

    const track = this.track;
    if (track && track.lat && track.lat.length > 1) {
      ctx.lineWidth = 3.5;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      // A dark casing under the route keeps it legible over both a pale street map and a
      // dark satellite-ish background.
      ctx.strokeStyle = 'rgba(0,0,0,0.45)';
      ctx.lineWidth = 6;
      ctx.beginPath();
      for (let i = 0; i < track.lat.length; i += 1) {
        const p = project(track.lat[i], track.lon[i], z);
        const x = p.x - ox;
        const y = p.y - oy;
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      }
      ctx.stroke();

      ctx.lineWidth = 3.5;
      const speeds = track.speedKmh || [];
      let prev = null;
      for (let i = 0; i < track.lat.length; i += 1) {
        const p = project(track.lat[i], track.lon[i], z);
        const pt = { x: p.x - ox, y: p.y - oy };
        if (prev) {
          ctx.strokeStyle = speedColor(speeds[i], this.maxSpeed);
          ctx.beginPath();
          ctx.moveTo(prev.x, prev.y);
          ctx.lineTo(pt.x, pt.y);
          ctx.stroke();
        }
        prev = pt;
      }

      const marker = (index, fill, radius = 6) => {
        if (index === null || index === undefined || index < 0 || index >= track.lat.length) return;
        const p = project(track.lat[index], track.lon[index], z);
        ctx.beginPath();
        ctx.arc(p.x - ox, p.y - oy, radius, 0, Math.PI * 2);
        ctx.fillStyle = fill;
        ctx.fill();
        ctx.lineWidth = 2;
        ctx.strokeStyle = '#fff';
        ctx.stroke();
      };
      marker(0, '#3fcf8e');
      marker(track.lat.length - 1, '#e2566d');
      if (this.hoverIndex !== null) marker(this.hoverIndex, '#4c8dff', 7);
    }

    let note = this.source.attribution;
    if (this.source.url && this.tileFailures > 4) {
      note = 'Map tiles unavailable (offline?) — route shown without a basemap';
    }
    this.attribution.textContent = note;
  }
}

export { speedColor };
