// Trip detail: the view that justifies having a desktop client at all. The route and the OBD
// traces are shown side by side and cross-linked - hover the map to move the chart crosshair,
// hover the chart to move the map marker - and zooming the chart re-fetches that window at
// full resolution, so a three-hour drive can be inspected down to individual samples.

import { el, mount, card, stat, empty, spinner } from '../lib/dom.js';
import { api, urls } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { TimeChart, SERIES_COLORS } from '../lib/chart.js';
import { RouteMap, TILE_SOURCES } from '../lib/map.js';

/** Metrics worth showing first, in order of preference, per powertrain. */
const PREFERRED = {
  EV: ['speedKmh', 'EV_SOC', 'hvBatteryPowerKw', 'hvBatteryCurrent'],
  REV: ['speedKmh', 'EV_SOC', 'hvBatteryPowerKw', 'rpm'],
  HYBRID: ['speedKmh', 'rpm', 'hybridBatteryLifePct', 'coolantTempC'],
  ICE: ['speedKmh', 'rpm', 'coolantTempC', 'engineLoadPct'],
};

const MAX_SERIES = 6;

export async function renderTrip(view, tripId) {
  mount(view, spinner('Reading trip'));
  const detail = await api.trip(tripId);
  const trip = detail.trip;
  const units = detail.units || null;
  const stats = detail.stats || [];
  // Existence comes from the manifest the app uploaded; size is display-only and may not be
  // known yet for a trip whose side files have never been fetched.
  const files = detail.files || {};
  const hasGpx = !!files.gpxGz;
  const hasObd = !!files.obdCsvGz;

  // --- pick the initial metrics ---
  const available = stats.map((s) => s.name);
  const preferred = PREFERRED[(detail.vehicle && detail.vehicle.powertrain) || 'ICE'] || PREFERRED.ICE;
  let selected = preferred.filter((m) => available.includes(m)).slice(0, 3);
  if (!selected.length) selected = available.slice(0, 2);

  // --- header ---
  const header = el('div', { class: 'page-head' },
    el('div', {},
      el('a', { class: 'back-link', href: '#/trips' }, '← All trips'),
      el('h1', { class: 'page-title' }, fmt.dateTime(trip.startedAtMillis)),
      el('div', { class: 'page-sub' },
        [detail.vehicleName,
          trip.endedAtMillis ? `ends ${fmt.timeOnly(trip.endedAtMillis)}` : 'no end time',
          trip.transport && trip.transport !== 'NONE' ? fmt.label(trip.transport) : 'GPS only',
          trip.profileKey ? `profile ${trip.profileKey}` : null,
        ].filter(Boolean).join(' · '))),
    // Whether a download is offered depends on whether the file EXISTS, which the manifest
    // states outright. Sizes are only known once a file has been fetched, so gating on size
    // silently hid the GPX and CSV buttons on every trip that had not been downloaded yet.
    el('div', { class: 'dl-group' },
      hasGpx ? el('a', { class: 'btn', href: urls.tripFile(tripId, 'track.gpx.gz') }, 'GPX') : null,
      hasObd ? el('a', { class: 'btn', href: urls.tripFile(tripId, 'obd.csv.gz') }, 'OBD CSV') : null,
      el('a', { class: 'btn', href: urls.tripFile(tripId, 'trip.json') }, 'trip.json'),
      el('details', { class: 'dl-more' },
        el('summary', { class: 'btn btn-ghost' }, 'Raw'),
        el('div', { class: 'dl-menu' },
          hasGpx
            ? el('a', { href: urls.tripFile(tripId, 'track.gpx.gz', true) },
              `track.gpx.gz${sizeSuffix(detail.gpxSize)}`) : null,
          hasObd
            ? el('a', { href: urls.tripFile(tripId, 'obd.csv.gz', true) },
              `obd.csv.gz${sizeSuffix(detail.obdSize)}`) : null,
          el('div', { class: 'dl-path' }, detail.remoteDir)))));

  // --- headline stats ---
  const statRow = el('div', { class: 'stat-grid compact' },
    stat('Distance', fmt.distance(trip.distanceKm, units)),
    stat('Duration', fmt.duration(trip.durationSec),
      fmt.isNum(trip.movingSec) ? `${fmt.duration(trip.movingSec)} moving` : null),
    stat('Avg speed', fmt.speed(trip.avgSpeedKmh, units)),
    stat('Max speed', fmt.speed(trip.maxSpeedKmh, units)),
    fmt.isNum(trip.fuelUsedL) ? stat('Fuel used', fmt.volume(trip.fuelUsedL, units)) : null,
    fmt.isNum(trip.avgConsumptionL100) ? stat('Consumption', fmt.fuelEconomy(trip.avgConsumptionL100, units)) : null,
    fmt.isNum(trip.energyUsedKwh) ? stat('Energy used', fmt.energy(trip.energyUsedKwh)) : null,
    fmt.isNum(trip.energyUsedKwh) && fmt.isNum(trip.distanceKm) && trip.distanceKm > 0
      ? stat('Electric economy', fmt.electricEconomy((trip.energyUsedKwh / trip.distanceKm) * 100, units)) : null,
    stat('Samples', `${(trip.obdSampleCount || 0).toLocaleString()} OBD`,
      `${(trip.gpsPointCount || 0).toLocaleString()} GPS fixes`));

  // --- map + chart hosts ---
  const mapHost = el('div', { class: 'map-frame' });
  const chartHost = el('div', {});
  const chartInfo = el('div', { class: 'chart-info' });
  const metricHost = el('div', { class: 'metric-picker' });

  const tileSelect = el('select', {
    class: 'select select-sm',
    onchange: (e) => { routeMap.setSource(e.target.value); localStorage.setItem('fct.tiles', e.target.value); },
  }, ...Object.entries(TILE_SOURCES).map(([key, src]) => el('option', {
    value: key, selected: key === (localStorage.getItem('fct.tiles') || 'osm'),
  }, src.label)));

  const mapCard = card(null,
    el('div', { class: 'card-head' },
      el('h2', { class: 'card-title' }, 'Route'),
      el('div', { class: 'card-tools' },
        tileSelect,
        el('button', { class: 'btn btn-sm', onclick: () => routeMap.fit() }, 'Fit'))),
    mapHost,
    el('div', { class: 'legend-speed' },
      el('span', {}, 'slow'),
      el('span', { class: 'legend-ramp' }),
      el('span', {}, 'fast')));

  const chartCard = card(null,
    el('div', { class: 'card-head' },
      el('h2', { class: 'card-title' }, 'Telemetry'),
      el('div', { class: 'card-tools' },
        chartInfo,
        // resetView fires onZoom(null), which is what reloads the full-trip series - calling
        // reload here too would just issue the same request twice.
        el('button', { class: 'btn btn-sm', onclick: () => chart.resetView() }, 'Reset zoom'))),
    metricHost,
    chartHost,
    el('p', { class: 'muted small' },
      'Drag to zoom, scroll to zoom around the cursor, double-click to reset. Zooming re-reads '
      + 'that window at full resolution.'));

  // --- DTCs ---
  const dtcCard = (detail.dtcs || []).length
    ? card('Diagnostic trouble codes',
      el('table', { class: 'table' },
        el('thead', {}, el('tr', {}, el('th', {}, 'Code'), el('th', {}, 'Kind'), el('th', {}, 'Seen at'))),
        el('tbody', {}, ...detail.dtcs.map((d) => el('tr', {},
          el('td', {}, el('code', {}, d.code)),
          el('td', {}, d.pending ? 'Pending (Mode 07)' : 'Stored (Mode 03)'),
          el('td', {}, fmt.dateTime(d.timestampMillis)))))))
    : null;

  // --- metric summary table ---
  const statsCard = stats.length
    ? card('Recorded metrics',
      el('div', { class: 'table-wrap short' },
        el('table', { class: 'table table-sm' },
          el('thead', {}, el('tr', {},
            el('th', {}, 'Metric'), el('th', {}, 'Unit'), el('th', {}, 'Min'),
            el('th', {}, 'Mean'), el('th', {}, 'Max'), el('th', {}, 'Samples'))),
          el('tbody', {}, ...stats.map((s) => el('tr', {},
            el('td', {},
              el('span', { class: 'metric-name' }, fmt.metricLabel(s.name)),
              s.extended ? el('span', { class: 'badge badge-dim', title: 'Manufacturer-specific (Mode 22)' }, 'ext') : null),
            el('td', { class: 'muted' }, s.unit || '—'),
            el('td', {}, sig(s.min)), el('td', {}, sig(s.mean)), el('td', {}, sig(s.max)),
            el('td', { class: 'muted' }, s.count.toLocaleString())))))))
    : null;

  mount(view, el('div', { class: 'page' },
    header,
    statRow,
    detail.seriesError ? el('div', { class: 'error-inline' }, `Telemetry unavailable: ${detail.seriesError}`) : null,
    el('div', { class: 'grid-map' }, mapCard, chartCard),
    dtcCard,
    statsCard));

  // --- wire up map and chart ---
  const routeMap = new RouteMap(mapHost, {
    source: localStorage.getItem('fct.tiles') || 'osm',
    onHover: (info) => chart.highlightTime(info ? info.t : null),
  });

  const chart = new TimeChart(chartHost, {
    height: 300,
    onHover: (info) => routeMap.highlightTime(info ? info.t : null),
    onZoom: (range) => reload(range),
  });

  // Track and series load in parallel: neither depends on the other, and the map appearing
  // while the OBD file is still downloading is better than a blank pane.
  const trackPromise = api.track(tripId, { points: 6000 })
    .then((track) => {
      if (track && track.lat && track.lat.length) routeMap.setTrack(track);
      else mount(mapHost, empty('This trip has no GPS track'));
      return track;
    })
    .catch((err) => { mount(mapHost, empty(`Track unavailable: ${err.message}`)); return null; });

  let zoomRange = null;

  async function reload(range) {
    zoomRange = range;
    if (!selected.length) {
      chart.setData({ t: [], series: [] });
      mount(chartInfo, el('span', { class: 'muted small' }, 'Pick a metric'));
      return;
    }
    mount(chartInfo, el('span', { class: 'muted small' }, 'Loading…'));
    try {
      const data = await api.series(tripId, {
        metrics: selected,
        points: 3000,
        from: range ? range.from : null,
        to: range ? range.to : null,
      });
      chart.setData({
        t: data.t,
        series: (data.series || []).map((s, i) => ({
          ...s,
          label: fmt.metricLabel(s.name),
          color: SERIES_COLORS[selected.indexOf(s.name) % SERIES_COLORS.length] || SERIES_COLORS[i % SERIES_COLORS.length],
        })),
      });
      const shown = data.returned || 0;
      const source = range ? data.windowCount : data.sampleCount;
      mount(chartInfo, el('span', { class: 'muted small' },
        source > shown
          ? `${shown.toLocaleString()} of ${source.toLocaleString()} samples (min/max per bucket)`
          : `${shown.toLocaleString()} samples`));
    } catch (err) {
      mount(chartInfo, el('span', { class: 'msg msg-error' }, err.message));
    }
  }

  function renderMetricPicker() {
    mount(metricHost, ...stats.map((s) => {
      const on = selected.includes(s.name);
      const index = selected.indexOf(s.name);
      return el('button', {
        class: `chip${on ? ' on' : ''}`,
        style: on ? { '--chip-color': SERIES_COLORS[index % SERIES_COLORS.length] } : null,
        title: `${s.unit || 'no unit'} · ${s.count.toLocaleString()} samples`,
        onclick: () => {
          if (on) selected = selected.filter((m) => m !== s.name);
          else if (selected.length < MAX_SERIES) selected = [...selected, s.name];
          else return;
          renderMetricPicker();
          reload(zoomRange);
        },
      }, fmt.metricLabel(s.name));
    }));
  }

  renderMetricPicker();
  await Promise.all([reload(null), trackPromise]);

  return {
    destroy() {
      chart.destroy();
      routeMap.destroy();
    },
  };
}

/** " (28 KB)" once the size is known, and nothing at all before that. */
function sizeSuffix(bytes) {
  return fmt.isNum(bytes) && bytes > 0 ? ` (${fmt.bytes(bytes)})` : '';
}

function sig(v) {
  if (!fmt.isNum(v)) return '—';
  const abs = Math.abs(v);
  return v.toFixed(abs >= 100 ? 0 : abs >= 1 ? 1 : 3);
}
