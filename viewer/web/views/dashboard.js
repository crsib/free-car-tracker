// Dashboard: the phone's home screen, with room to breathe. Every number here is computed
// server-side by the Go port of EconomyCalculator, so it matches what the app shows.

import { el, mount, card, stat, empty } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { barList, trendPlot, histogram } from '../lib/plots.js';
import { state, selectedVehicle, setWindowDays, navigate } from '../app.js';

const WINDOWS = [
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
  { days: 180, label: '6 months' },
  { days: 365, label: '1 year' },
  { days: 0, label: 'All time' },
];

export async function renderDashboard(view) {
  const vehicle = selectedVehicle();
  if (!vehicle) {
    mount(view, el('div', { class: 'page' },
      empty('No cars found in that WebDAV folder yet. Check Settings, then run a refresh.')));
    return null;
  }

  const d = await api.dashboard(vehicle.syncId, state.windowDays);
  const units = d.units;
  const currency = units.currency;
  const digits = units.fractionDigits;
  const v = d.vehicle;

  const windowPicker = el('div', { class: 'segmented' },
    ...WINDOWS.map((wnd) => el('button', {
      class: `seg${wnd.days === d.windowDays ? ' active' : ''}`,
      onclick: () => { setWindowDays(wnd.days); navigate('#/'); },
    }, wnd.label)));

  const subtitle = [
    fmt.label(v.powertrain),
    v.make && v.model ? `${v.make} ${v.model}` : v.make || v.model,
    v.year,
    v.plate,
  ].filter(Boolean).join(' · ');

  // --- headline numbers ---
  const kpis = el('div', { class: 'stat-grid' },
    stat('Last full-tank economy',
      fmt.fuelEconomy(d.latestFuelEconomyL100, units),
      'Most recent completed fill-to-fill interval'),
    v.powertrain !== 'ICE'
      ? stat('Electric economy',
        fmt.electricEconomy(d.electricEconomyKwh100, units),
        `${fmt.energy(d.windowEnergyKwh)} charged in this window`)
      : null,
    stat('Distance', fmt.distance(d.windowDistanceKm, units), 'Odometer span across the window'),
    stat('Spend', fmt.money(d.totalSpendMinor, currency, digits),
      d.costPerKmMinor !== null ? `${fmt.costPerDistance(d.costPerKmMinor, units)} all in` : 'No distance to divide by'),
    stat('Running cost',
      d.energyCostPerKmMinor !== null ? fmt.costPerDistance(d.energyCostPerKmMinor, units) : '—',
      'Fuel and electricity only'),
    stat('Window economy', fmt.fuelEconomy(d.windowFuelEconomyL100, units),
      `${fmt.volume(d.windowFuelVolumeL, units)} over the window`));

  // --- spend breakdown ---
  const spendCard = card('Where the money went',
    d.buckets.length
      ? barList(d.buckets.map((b) => ({ label: fmt.label(b.category), value: b.totalMinor })),
        { format: (value) => fmt.money(value, currency, digits) })
      : empty('No spending recorded in this window'));

  // --- per-source running cost ---
  const shares = d.energyCostShares || [];
  const sourceCard = shares.length
    ? card('Cost per distance, by energy source',
      el('p', { class: 'muted small' },
        'Each source is spread over the whole distance driven — a car cannot say which '
        + 'kilometres ran on which fuel. Read these as what each source adds per '
        + `${units.distance}; they sum to the running cost above.`),
      el('table', { class: 'table' },
        el('thead', {}, el('tr', {},
          el('th', {}, 'Source'), el('th', {}, 'Quantity'),
          el('th', {}, 'Spent'), el('th', {}, `Per ${units.distance}`))),
        el('tbody', {}, ...shares.map((s) => el('tr', {},
          el('td', {}, s.source.kind === 'CHARGE' ? 'Electricity' : fmt.label(s.source.fuelType) || 'Fuel'),
          el('td', {}, s.source.kind === 'CHARGE' ? fmt.energy(s.quantity) : fmt.volume(s.quantity, units)),
          el('td', {}, fmt.money(s.spentMinor, currency, digits)),
          el('td', {}, s.costPerKmMinor !== null ? fmt.costPerDistance(s.costPerKmMinor, units) : '—'))))))
    : null;

  // --- consumption trend ---
  const trendPoints = (d.trend || []).map((p) => ({
    x: p.dateMillis,
    y: fmt.isNum(p.litresPer100Km)
      ? (units.distance === 'mi' ? 235.214583 / p.litresPer100Km : p.litresPer100Km)
      : NaN,
    title: fmt.date(p.dateMillis),
  }));
  const trendCard = card(`Consumption trend (${units.fuelEconomy})`,
    trendPlot(trendPoints, { format: (value) => value.toFixed(1) }));

  // --- spread ---
  const segEconomies = (d.windowSegmentEconomiesL100 || []).map((value) => (
    units.distance === 'mi' ? 235.214583 / value : value));
  const spreadCard = segEconomies.length >= 2
    ? card('How consistent is it?',
      el('p', { class: 'muted small' },
        `${segEconomies.length} completed full-tank intervals in this window, `
        + `from ${Math.min(...segEconomies).toFixed(1)} to ${Math.max(...segEconomies).toFixed(1)} ${units.fuelEconomy}.`),
      histogram(segEconomies, { format: (value) => value.toFixed(1) }))
    : null;

  // --- charge intervals ---
  const chargeSegs = d.windowSegmentElectricKwh100 || [];
  const chargeCard = chargeSegs.length
    ? card('Electric economy per charge interval',
      histogram(chargeSegs, { format: (value) => value.toFixed(1) }),
      el('p', { class: 'muted small' },
        `${chargeSegs.length} intervals between odometer-bearing charges, `
        + `median ${median(chargeSegs).toFixed(1)} kWh/100km.`))
    : null;

  mount(view, el('div', { class: 'page' },
    el('div', { class: 'page-head' },
      el('div', {},
        el('h1', { class: 'page-title' }, v.name),
        subtitle ? el('div', { class: 'page-sub' }, subtitle) : null),
      windowPicker),
    kpis,
    el('div', { class: 'grid-2' }, spendCard, trendCard),
    sourceCard,
    el('div', { class: 'grid-2' }, spreadCard, chargeCard),
    el('div', { class: 'card-actions' },
      el('a', { class: 'btn', href: '#/trips' }, `Trips (${vehicle.tripCount})`),
      el('a', { class: 'btn', href: '#/ledger' },
        `Ledger (${vehicle.fuelCount + vehicle.chargeCount + vehicle.expenseCount} entries)`),
      el('a', { class: 'btn', href: '#/analysis' }, 'Cross-trip analysis'))));

  return null;
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}
