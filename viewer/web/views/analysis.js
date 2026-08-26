// Cross-trip analysis: every trip as one row, and any two of its numbers on a scatter plot.
// The question this answers is the one a phone screen cannot - "does cold weather actually
// cost me range, and by how much" - so the axis list is built from whatever the cars really
// recorded rather than from a fixed menu.

import { el, mount, card, empty, spinner } from '../lib/dom.js';
import { api, urls } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { scatterPlot } from '../lib/plots.js';
import { selectedVehicle, refreshStatus } from '../app.js';

/** Fields present on every row, independent of what the OBD adapter answered. */
const BASE_AXES = [
  { key: 'startedAtMillis', label: 'Trip date' },
  { key: 'distanceKm', label: 'Distance (km)' },
  { key: 'gpsDistanceKm', label: 'GPS distance (km)' },
  { key: 'durationSec', label: 'Duration (s)' },
  { key: 'avgSpeedKmh', label: 'Average speed (km/h)' },
  { key: 'maxSpeedKmh', label: 'Max speed (km/h)' },
  { key: 'avgConsumptionL100', label: 'Consumption (L/100km)' },
  { key: 'kwhPer100Km', label: 'Electric economy (kWh/100km)' },
  { key: 'fuelUsedL', label: 'Fuel used (L)' },
  { key: 'energyUsedKwh', label: 'Energy used (kWh)' },
  { key: 'ambientTempC', label: 'Ambient temperature (°C)' },
  { key: 'elevationGainM', label: 'Elevation gain (m)' },
  { key: 'socUsedPct', label: 'SoC used (%)' },
  { key: 'socStartPct', label: 'SoC at start (%)' },
];

const settings = {
  x: 'ambientTempC',
  y: 'kwhPer100Km',
  allCars: false,
  minKm: 1,
};

export async function renderAnalysis(view) {
  const vehicle = selectedVehicle();
  const vehicleParam = settings.allCars || !vehicle ? '' : vehicle.syncId;

  mount(view, spinner('Reading trip summaries'));
  const data = await api.analysis({ vehicle: vehicleParam, minKm: settings.minKm });
  const rows = data.rows || [];

  if (!rows.length) {
    mount(view, el('div', { class: 'page' },
      el('h1', { class: 'page-title' }, 'Cross-trip analysis'),
      empty('No trips match. Try lowering the minimum distance, or index the folder first.')));
    return null;
  }

  // Metric means become axes too, so a car reporting motor temperature can be plotted
  // against consumption without this file knowing that metric exists.
  const axes = [
    ...BASE_AXES,
    ...(data.metrics || []).map((m) => ({ key: `metric:${m}`, label: `${fmt.metricLabel(m)} (mean)` })),
  ];

  const valueOf = (row, key) => {
    if (key.startsWith('metric:')) {
      const name = key.slice('metric:'.length);
      return row.metrics ? row.metrics[name] : undefined;
    }
    return row[key];
  };
  const labelOf = (key) => (axes.find((a) => a.key === key) || { label: key }).label;

  const axisSelect = (which) => el('select', {
    class: 'select',
    onchange: (e) => { settings[which] = e.target.value; draw(); },
  }, ...axes.map((a) => el('option', { value: a.key, selected: settings[which] === a.key }, a.label)));

  const plotHost = el('div', {});
  const xSel = axisSelect('x');
  const ySel = axisSelect('y');

  const incomplete = data.total - data.summarised;
  const coverage = incomplete > 0
    ? el('div', { class: 'notice' },
      el('strong', {}, `${incomplete} of ${data.total} trips have no OBD-derived data yet.`),
      el('span', {},
        ' Ambient temperature, elevation gain, state of charge and per-metric means come from the '
        + 'trip files, which are downloaded on demand. Until those are fetched, those axes are '
        + 'blank for the trips in question.'),
      el('button', {
        class: 'btn btn-sm',
        onclick: async () => {
          try { await api.prefetch(vehicleParam); refreshStatus(); } catch (e) { alert(e.message); }
        },
      }, 'Download the rest'))
    : null;

  const controls = el('div', { class: 'filter-bar' },
    el('label', { class: 'field' }, el('span', {}, 'Y axis'), ySel),
    el('label', { class: 'field' }, el('span', {}, 'X axis'), xSel),
    el('label', { class: 'field' }, el('span', {}, 'Min distance'),
      el('input', {
        class: 'input input-sm', type: 'number', min: '0', step: '1', value: String(settings.minKm),
        onchange: (e) => { settings.minKm = Number(e.target.value) || 0; renderAnalysis(view); },
      })),
    el('label', { class: 'check' },
      el('input', {
        type: 'checkbox', checked: settings.allCars,
        onchange: (e) => { settings.allCars = e.target.checked; renderAnalysis(view); },
      }), ' All cars'),
    el('a', {
      class: 'btn', href: urls.analysisCsv({ vehicle: vehicleParam, minKm: settings.minKm }),
    }, 'Export CSV'));

  function draw() {
    const points = rows.map((r) => ({
      x: valueOf(r, settings.x),
      y: valueOf(r, settings.y),
      label: `${fmt.date(r.startedAtMillis)} · ${fmt.num(r.distanceKm, 1)} km`,
      tripId: r.tripSyncId,
    })).filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y));

    mount(plotHost, scatterPlot(points, {
      xLabel: labelOf(settings.x),
      yLabel: labelOf(settings.y),
      onSelect: (p) => { location.hash = `#/trip/${encodeURIComponent(p.tripId)}`; },
      format: (v) => (Math.abs(v) >= 1e11
        ? fmt.date(v)                       // a date axis, shown as a date rather than millis
        : Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1)),
    }));
  }

  // A monthly rollup answers the other common question - "is this getting better or worse".
  const monthly = rollupByMonth(rows);

  mount(view, el('div', { class: 'page' },
    el('div', { class: 'page-head' },
      el('div', {},
        el('h1', { class: 'page-title' }, 'Cross-trip analysis'),
        el('div', { class: 'page-sub' },
          `${data.total} trips · ${data.summarised} with full telemetry`))),
    coverage,
    controls,
    card(null, plotHost),
    card('By month',
      el('div', { class: 'table-wrap' },
        el('table', { class: 'table table-sm' },
          el('thead', {}, el('tr', {},
            el('th', {}, 'Month'), el('th', { class: 'num' }, 'Trips'),
            el('th', { class: 'num' }, 'Distance (km)'), el('th', { class: 'num' }, 'Driving time'),
            el('th', { class: 'num' }, 'Avg speed'), el('th', { class: 'num' }, 'L/100km'),
            el('th', { class: 'num' }, 'kWh/100km'), el('th', { class: 'num' }, 'Avg ambient'))),
          el('tbody', {}, ...monthly.map((m) => el('tr', {},
            el('td', {}, m.month),
            el('td', { class: 'num' }, String(m.trips)),
            el('td', { class: 'num' }, fmt.num(m.distanceKm, 0)),
            el('td', { class: 'num' }, fmt.duration(m.durationSec)),
            el('td', { class: 'num' }, m.durationSec > 0 ? fmt.num((m.distanceKm / m.durationSec) * 3600, 0) : '—'),
            el('td', { class: 'num' }, m.fuelL > 0 && m.distanceKm > 0 ? fmt.num((m.fuelL / m.distanceKm) * 100, 1) : '—'),
            el('td', { class: 'num' }, m.kwh > 0 && m.distanceKm > 0 ? fmt.num((m.kwh / m.distanceKm) * 100, 1) : '—'),
            el('td', { class: 'num' }, m.tempCount ? `${fmt.num(m.tempSum / m.tempCount, 1)} °C` : '—')))))))));

  draw();
  return null;
}

/**
 * Aggregate per calendar month. Consumption is recomputed from the summed fuel and distance
 * rather than averaged across trips - averaging per-trip ratios would weight a 2 km errand
 * the same as a 300 km motorway run.
 */
function rollupByMonth(rows) {
  const buckets = new Map();
  for (const r of rows) {
    const d = new Date(r.startedAtMillis);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    if (!buckets.has(key)) {
      buckets.set(key, {
        month: d.toLocaleDateString(undefined, { year: 'numeric', month: 'short' }),
        sort: key, trips: 0, distanceKm: 0, durationSec: 0, fuelL: 0, kwh: 0, tempSum: 0, tempCount: 0,
      });
    }
    const b = buckets.get(key);
    b.trips += 1;
    if (Number.isFinite(r.distanceKm)) b.distanceKm += r.distanceKm;
    if (Number.isFinite(r.durationSec)) b.durationSec += r.durationSec;
    if (Number.isFinite(r.fuelUsedL)) b.fuelL += r.fuelUsedL;
    if (Number.isFinite(r.energyUsedKwh)) b.kwh += r.energyUsedKwh;
    if (Number.isFinite(r.ambientTempC)) { b.tempSum += r.ambientTempC; b.tempCount += 1; }
  }
  return Array.from(buckets.values()).sort((a, b) => b.sort.localeCompare(a.sort));
}
