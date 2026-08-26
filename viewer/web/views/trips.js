// Trip list: every synced recording for the selected car, with a bulk raw-data export.

import { el, mount, card, empty } from '../lib/dom.js';
import { api, urls } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { selectedVehicle, refreshStatus } from '../app.js';

const PAGE = 100;

export async function renderTrips(view) {
  const vehicle = selectedVehicle();
  const params = { vehicle: vehicle ? vehicle.syncId : '', limit: PAGE };
  const data = await api.trips(params);
  const units = vehicle ? vehicle.units : null;

  if (!data.trips.length) {
    mount(view, el('div', { class: 'page' },
      el('h1', { class: 'page-title' }, 'Trips'),
      empty('No trips synced for this car yet.')));
    return null;
  }

  const exportBar = card('Export raw data',
    el('p', { class: 'muted small' },
      'Downloads a zip of the files exactly as the phone uploaded them, one folder per car. '
      + 'GPX and CSV are decompressed so they open directly; tick "keep .gz" for the untouched bytes.'),
    (() => {
      const wantGpx = el('input', { type: 'checkbox', checked: true });
      const wantObd = el('input', { type: 'checkbox' });
      const wantJson = el('input', { type: 'checkbox' });
      const keepGz = el('input', { type: 'checkbox' });
      const link = el('a', { class: 'btn btn-primary', href: '#' }, 'Download zip');
      const update = () => {
        const files = [
          wantGpx.checked && 'gpx',
          wantObd.checked && 'obd',
          wantJson.checked && 'json',
        ].filter(Boolean);
        link.href = urls.exportZip({
          vehicle: params.vehicle,
          files: files.join(','),
          raw: keepGz.checked ? 1 : null,
        });
        link.classList.toggle('disabled', files.length === 0);
      };
      [wantGpx, wantObd, wantJson, keepGz].forEach((input) => input.addEventListener('change', update));
      update();
      return el('div', { class: 'export-row' },
        el('label', { class: 'check' }, wantGpx, ' GPX tracks'),
        el('label', { class: 'check' }, wantObd, ' OBD CSV'),
        el('label', { class: 'check' }, wantJson, ' trip.json'),
        el('label', { class: 'check' }, keepGz, ' keep .gz'),
        link);
    })());

  const rows = data.trips.map((t) => {
    const trip = t.trip;
    return el('tr', {
      class: 'row-link',
      onclick: () => { location.hash = `#/trip/${encodeURIComponent(t.tripSyncId)}`; },
    },
    el('td', {},
      el('div', { class: 'strong' }, fmt.dateTime(trip.startedAtMillis)),
      el('div', { class: 'muted small' }, t.vehicleName)),
    el('td', {}, fmt.distance(trip.distanceKm, units)),
    el('td', {}, fmt.duration(trip.durationSec)),
    el('td', {}, fmt.speed(trip.avgSpeedKmh, units, 1)),
    el('td', {}, trip.avgConsumptionL100 !== null ? fmt.fuelEconomy(trip.avgConsumptionL100, units) : '—'),
    el('td', {}, fmt.isNum(trip.energyUsedKwh) ? fmt.energy(trip.energyUsedKwh) : '—'),
    el('td', { class: 'nowrap' },
      t.hasTrack ? badge('GPS', `${trip.gpsPointCount.toLocaleString()} fixes`) : null,
      t.hasObd ? badge('OBD', `${trip.obdSampleCount.toLocaleString()} samples`
        + (t.obdSize > 0 ? ` · ${fmt.bytes(t.obdSize)}` : '')) : null,
      t.dtcCount ? badge('DTC', `${t.dtcCount} codes`, 'badge-warn') : null,
      trip.status !== 'COMPLETED' ? badge(fmt.label(trip.status), 'Recording did not stop cleanly', 'badge-warn') : null,
      t.cached ? null : badge('·', 'Full data not downloaded yet', 'badge-dim')),
    el('td', { class: 'nowrap', onclick: (e) => e.stopPropagation() },
      t.hasTrack ? el('a', {
        class: 'link-sm', href: urls.tripFile(t.tripSyncId, 'track.gpx.gz'), title: 'Download GPX',
      }, 'GPX') : null,
      t.hasObd ? el('a', {
        class: 'link-sm', href: urls.tripFile(t.tripSyncId, 'obd.csv.gz'), title: 'Download OBD CSV',
      }, 'CSV') : null));
  });

  const notCached = data.trips.filter((t) => !t.cached && (t.hasObd || t.hasTrack)).length;

  mount(view, el('div', { class: 'page' },
    el('div', { class: 'page-head' },
      el('div', {},
        el('h1', { class: 'page-title' }, 'Trips'),
        el('div', { class: 'page-sub' },
          `${data.total} synced${data.total > data.trips.length ? `, showing the ${data.trips.length} most recent` : ''}`)),
      notCached
        ? el('button', {
          class: 'btn',
          title: 'Download every trip’s OBD and GPS data so analysis and charts are complete',
          onclick: async () => {
            try { await api.prefetch(params.vehicle); refreshStatus(); } catch (e) { alert(e.message); }
          },
        }, `Download all trip data (${notCached})`)
        : null),
    exportBar,
    el('div', { class: 'table-wrap' },
      el('table', { class: 'table table-hover' },
        el('thead', {}, el('tr', {},
          el('th', {}, 'Started'), el('th', {}, 'Distance'), el('th', {}, 'Duration'),
          el('th', {}, 'Avg speed'), el('th', {}, 'Fuel'), el('th', {}, 'Energy'),
          el('th', {}, 'Data'), el('th', {}, 'Files'))),
        el('tbody', {}, ...rows)))));

  return null;
}

function badge(text, title, extra = '') {
  return el('span', { class: `badge ${extra}`, title: title || '' }, text);
}
