// Ledger: one sortable, filterable table over fuel fills, charges and expenses, with CSV out.

import { el, mount, card, empty } from '../lib/dom.js';
import { api, urls } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { selectedVehicle } from '../app.js';

const KINDS = [
  { key: 'FUEL', label: 'Fuel' },
  { key: 'CHARGE', label: 'Charging' },
  { key: 'EXPENSE', label: 'Expenses' },
];

const COLUMNS = [
  { key: 'dateMillis', label: 'Date', numeric: true },
  { key: 'kind', label: 'Kind' },
  { key: 'category', label: 'Category' },
  { key: 'odometerKm', label: 'Odometer', numeric: true },
  { key: 'quantity', label: 'Quantity', numeric: true },
  { key: 'unitPriceMinor', label: 'Unit price', numeric: true },
  { key: 'totalCostMinor', label: 'Total', numeric: true },
  { key: 'detail', label: 'Where' },
  { key: 'note', label: 'Note' },
];

// Filters live on the module so switching to a trip and back keeps the view you set up.
const filters = {
  kinds: new Set(KINDS.map((k) => k.key)),
  allCars: false,
  search: '',
  sortKey: 'dateMillis',
  sortAsc: false,
};

export async function renderLedger(view) {
  const vehicle = selectedVehicle();
  const vehicleParam = filters.allCars || !vehicle ? '' : vehicle.syncId;
  const rows = await api.ledger({ vehicle: vehicleParam });
  const units = vehicle ? vehicle.units : null;

  const searchInput = el('input', {
    class: 'input input-sm', type: 'search', placeholder: 'Search station, vendor, note…',
    value: filters.search,
    oninput: (e) => { filters.search = e.target.value; draw(); },
  });

  const controls = el('div', { class: 'filter-bar' },
    el('div', { class: 'segmented' }, ...KINDS.map((k) => el('button', {
      class: `seg${filters.kinds.has(k.key) ? ' active' : ''}`,
      onclick: () => {
        if (filters.kinds.has(k.key)) filters.kinds.delete(k.key);
        else filters.kinds.add(k.key);
        if (!filters.kinds.size) KINDS.forEach((kind) => filters.kinds.add(kind.key));
        renderLedger(view);
      },
    }, k.label))),
    el('label', { class: 'check' },
      el('input', {
        type: 'checkbox', checked: filters.allCars,
        onchange: (e) => { filters.allCars = e.target.checked; renderLedger(view); },
      }), ' All cars'),
    searchInput,
    el('a', {
      class: 'btn', href: urls.ledgerCsv({ vehicle: vehicleParam, kind: [...filters.kinds].join(',') }),
    }, 'Export CSV'));

  const tbody = el('tbody', {});
  const summary = el('div', { class: 'page-sub' });

  const head = el('tr', {}, ...COLUMNS.map((c) => el('th', {
    class: `sortable${filters.sortKey === c.key ? ' sorted' : ''}${c.numeric ? ' num' : ''}`,
    onclick: () => {
      if (filters.sortKey === c.key) filters.sortAsc = !filters.sortAsc;
      else { filters.sortKey = c.key; filters.sortAsc = !c.numeric; }
      draw();
    },
  }, c.label, filters.sortKey === c.key ? el('span', { class: 'sort-arrow' }, filters.sortAsc ? '▲' : '▼') : null)));

  function visibleRows() {
    const needle = filters.search.trim().toLowerCase();
    let out = rows.filter((r) => filters.kinds.has(r.kind));
    if (needle) {
      out = out.filter((r) => [r.detail, r.note, r.category, r.vehicleName]
        .some((v) => v && String(v).toLowerCase().includes(needle)));
    }
    const key = filters.sortKey;
    const dir = filters.sortAsc ? 1 : -1;
    return out.sort((a, b) => {
      const av = a[key];
      const bv = b[key];
      if (av === bv) return 0;
      if (av === null || av === undefined) return 1;   // blanks last, whichever way it sorts
      if (bv === null || bv === undefined) return -1;
      return (typeof av === 'number' && typeof bv === 'number' ? av - bv : String(av).localeCompare(String(bv))) * dir;
    });
  }

  function draw() {
    const list = visibleRows();
    const total = list.reduce((sum, r) => sum + r.totalCostMinor, 0);
    const digits = list.length ? list[0].fractionDigits : 2;
    const currency = list.length ? list[0].currency : (units && units.currency);

    mount(summary, `${list.length} entries · ${fmt.money(total, currency, digits)} total`);
    mount(tbody, ...list.map((r) => el('tr', {},
      el('td', { class: 'nowrap' }, fmt.date(r.dateMillis)),
      el('td', {}, el('span', { class: `badge badge-${r.kind.toLowerCase()}` }, KINDS.find((k) => k.key === r.kind).label)),
      el('td', {},
        fmt.label(r.category),
        r.isFullTank === true ? el('span', { class: 'badge badge-dim', title: 'Full tank' }, 'full') : null,
        r.recurrence && r.recurrence !== 'NONE'
          ? el('span', { class: 'badge badge-dim', title: 'Recurring cost' }, fmt.label(r.recurrence)) : null),
      el('td', { class: 'num' }, fmt.isNum(r.odometerKm) ? fmt.distance(r.odometerKm, units) : '—'),
      el('td', { class: 'num' }, r.quantity === null || r.quantity === undefined
        ? '—'
        : (r.quantityUnit === 'kWh' ? fmt.energy(r.quantity) : fmt.volume(r.quantity, units))),
      el('td', { class: 'num muted' }, r.unitPriceMinor === null || r.unitPriceMinor === undefined
        ? '—'
        : `${fmt.moneyPrecise(r.unitPriceMinor, r.currency, r.fractionDigits)}/${r.quantityUnit}`),
      el('td', { class: 'num strong' }, fmt.money(r.totalCostMinor, r.currency, r.fractionDigits)),
      el('td', {}, r.detail || '—'),
      el('td', { class: 'muted' }, r.note || ''))));
  }

  mount(view, el('div', { class: 'page' },
    el('div', { class: 'page-head' },
      el('div', {}, el('h1', { class: 'page-title' }, 'Ledger'), summary)),
    controls,
    rows.length
      ? el('div', { class: 'table-wrap tall' },
        el('table', { class: 'table table-sm sticky' }, el('thead', {}, head), tbody))
      : card(null, empty('No ledger entries in this WebDAV folder.'))));

  draw();
  return null;
}
