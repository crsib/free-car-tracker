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

// Same choices as the dashboard, so a ledger filtered to a window can be read against the
// spend total for that window - which is the point of showing projected bills at all.
const WINDOWS = [
  { days: 0, label: 'All time' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
  { days: 365, label: '1 year' },
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
  // Repeats of recurring expenses, derived by the server. On by default: leaving them out is
  // what made a monthly cost anchored years ago show up in no recent window at all.
  bills: true,
  windowDays: 0,
  search: '',
  sortKey: 'dateMillis',
  sortAsc: false,
};

export async function renderLedger(view) {
  const vehicle = selectedVehicle();
  const vehicleParam = filters.allCars || !vehicle ? '' : vehicle.syncId;
  const billsParam = filters.bills ? null : 0;
  const now = Date.now();
  const from = filters.windowDays ? now - filters.windowDays * 86400000 : null;
  const query = { vehicle: vehicleParam, bills: billsParam, from, to: from ? now : null };
  const rows = await api.ledger(query);
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
    el('select', {
      class: 'select select-sm', title: 'Date range',
      onchange: (e) => { filters.windowDays = Number(e.target.value); renderLedger(view); },
    }, ...WINDOWS.map((w) => el('option', { value: String(w.days), selected: w.days === filters.windowDays }, w.label))),
    el('label', { class: 'check', title: 'Show each billing date of a recurring expense, not just the entry that defines it' },
      el('input', {
        type: 'checkbox', checked: filters.bills,
        onchange: (e) => { filters.bills = e.target.checked; renderLedger(view); },
      }), ' Recurring bills'),
    searchInput,
    el('a', {
      class: 'btn',
      href: urls.ledgerCsv({ ...query, kind: [...filters.kinds].join(',') }),
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

    const projected = list.filter((r) => r.projected).length;
    mount(summary,
      `${list.length - projected} entries${projected ? ` · ${projected} projected bills` : ''}`
      + ` · ${fmt.money(total, currency, digits)} total`);
    mount(tbody, ...list.map((r) => el('tr', { class: r.projected ? 'row-projected' : null },
      el('td', { class: 'nowrap' }, fmt.date(r.dateMillis)),
      el('td', {}, el('span', { class: `badge badge-${r.kind.toLowerCase()}` }, KINDS.find((k) => k.key === r.kind).label)),
      el('td', {},
        fmt.label(r.category),
        r.isFullTank === true ? el('span', { class: 'badge badge-dim', title: 'Full tank' }, 'full') : null,
        r.recurrence && r.recurrence !== 'NONE'
          ? el('span', { class: 'badge badge-dim', title: 'Recurring cost' }, fmt.label(r.recurrence)) : null,
        r.projected
          ? el('span', {
            class: 'badge badge-dim',
            title: `Billed on this date by the entry logged ${fmt.date(r.anchorDateMillis)}`,
          }, 'projected') : null),
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
