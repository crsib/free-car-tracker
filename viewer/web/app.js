// Application shell: hash router, the shared bits of state every view needs (which car is
// selected, what the server is doing), and the header that exposes them.

import { el, mount, clear, spinner, errorBox } from './lib/dom.js';
import { api } from './lib/api.js';
import * as fmt from './lib/fmt.js';

import { renderSetup } from './views/setup.js';
import { renderDashboard } from './views/dashboard.js';
import { renderTrips } from './views/trips.js';
import { renderTrip } from './views/trip.js';
import { renderLedger } from './views/ledger.js';
import { renderAnalysis } from './views/analysis.js';

export const state = {
  status: null,
  vehicles: [],
  vehicleId: localStorage.getItem('fct.vehicle') || '',
  windowDays: Number(localStorage.getItem('fct.windowDays') || 90),
  /** The view currently mounted, so it can be torn down (canvas observers, timers). */
  active: null,
};

const listeners = new Set();
export const onStatus = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };

export function selectedVehicle() {
  if (!state.vehicles.length) return null;
  return state.vehicles.find((v) => v.syncId === state.vehicleId) || state.vehicles[0];
}

export function setVehicle(syncId) {
  state.vehicleId = syncId;
  localStorage.setItem('fct.vehicle', syncId);
  route();
}

export function setWindowDays(days) {
  state.windowDays = days;
  localStorage.setItem('fct.windowDays', String(days));
}

// --- status polling ---

let pollTimer = null;

async function refreshStatus() {
  try {
    const status = await api.status();
    const wasRunning = state.status && state.status.progress && state.status.progress.running;
    const nowRunning = status.progress && status.progress.running;
    state.status = status;
    renderHeaderStatus();
    for (const fn of listeners) fn(status);

    // A finished job means the data underneath the current view changed; reload it once
    // rather than making every view poll for itself.
    if (wasRunning && !nowRunning) {
      await loadVehicles();
      route();
    }
    schedulePoll(nowRunning ? 700 : 15000);
  } catch (err) {
    // The server going away is worth surfacing, but not worth a spin loop.
    schedulePoll(5000);
  }
}

function schedulePoll(delay) {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(refreshStatus, delay);
}

export async function loadVehicles() {
  try {
    state.vehicles = await api.vehicles();
  } catch {
    state.vehicles = [];
  }
  renderVehiclePicker();
}

// --- header ---

const NAV = [
  { hash: '#/', label: 'Dashboard' },
  { hash: '#/trips', label: 'Trips' },
  { hash: '#/ledger', label: 'Ledger' },
  { hash: '#/analysis', label: 'Analysis' },
  { hash: '#/settings', label: 'Settings' },
];

function renderNav() {
  const current = location.hash || '#/';
  const nav = document.getElementById('nav');
  mount(nav, ...NAV.map((item) => el('a', {
    href: item.hash,
    class: `nav-link${current === item.hash || (item.hash !== '#/' && current.startsWith(item.hash)) ? ' active' : ''}`,
  }, item.label)));
}

function renderVehiclePicker() {
  const host = document.getElementById('vehicle-picker');
  if (!host) return;
  if (state.vehicles.length < 1) { clear(host); return; }
  const selected = selectedVehicle();
  mount(host, el('select', {
    class: 'select',
    title: 'Car',
    onchange: (e) => setVehicle(e.target.value),
  }, ...state.vehicles.map((v) => el('option', {
    value: v.syncId,
    selected: selected && v.syncId === selected.syncId,
  }, v.vehicle.name))));
}

function renderHeaderStatus() {
  const host = document.getElementById('sync-status');
  if (!host) return;
  const status = state.status;
  if (!status) { clear(host); return; }
  const p = status.progress || {};

  if (p.running) {
    const pct = p.total > 0 ? Math.round((p.done / p.total) * 100) : null;
    mount(host,
      el('div', { class: 'sync running' },
        el('span', { class: 'spin' }),
        el('span', {}, p.message || 'Working'),
        p.full ? el('span', { class: 'sync-note' }, 'full re-read') : null,
        pct !== null ? el('span', { class: 'sync-pct' }, `${pct}%`) : null,
        el('button', { class: 'btn btn-ghost btn-sm', onclick: () => api.cancel().catch(() => {}) }, 'Stop')));
    return;
  }

  const parts = [];
  if (status.builtAt) parts.push(`Indexed ${fmt.relative(new Date(status.builtAt).getTime())}`);
  if (status.tripCount) parts.push(`${status.tripCount} trips`);

  // A partial index is the failure mode that matters most here: the trip list simply looks
  // shorter, with nothing to say why. Surface it in the header, not only in Settings.
  const warnings = status.warnings || [];
  const throttled = status.throttled || 0;

  const auto = status.autoRefresh || {};

  mount(host,
    el('div', { class: 'sync' },
      p.error ? el('span', { class: 'sync-error', title: p.error }, 'Last run failed') : null,
      warnings.length
        ? el('a', {
          class: 'sync-warn',
          href: '#/settings',
          title: [
            `${warnings.length} item(s) could not be read, so this index is incomplete.`,
            '',
            ...warnings.slice(0, 5),
          ].join('\n'),
        }, `⚠ ${warnings.length} unread`)
        : null,
      throttled
        ? el('span', {
          class: 'sync-throttle',
          title: `The server asked us to slow down ${throttled} time(s). `
            + 'Requests are being paced automatically; lower the rate in Settings if it persists.',
        }, `throttled ×${throttled}`)
        : null,
      el('span', { class: 'sync-text' }, parts.join(' · ') || 'Not indexed'),
      auto.enabled
        ? el('a', {
          class: 'sync-auto',
          href: '#/settings',
          title: autoTitle(auto),
        }, `auto ${fmt.interval(auto.minutes)}`)
        : null,
      el('button', {
        class: 'btn btn-sm',
        title: 'Re-read the sync folder now',
        onclick: async () => {
          try { await api.refresh(); refreshStatus(); } catch (e) { alert(e.message); }
        },
      }, 'Refresh')));
}

/** What the auto chip says when you hover it - including why it might be doing nothing. */
function autoTitle(auto) {
  const lines = [`The viewer re-reads the sync folder every ${fmt.interval(auto.minutes)} on its own.`, ''];
  if (auto.idle) {
    // Idle means the server saw no requests for a while, so it stopped walking for nobody.
    // This page just made it non-idle again; saying so beats showing an overdue countdown.
    lines.push('It had paused because nothing was reading this server. This page just woke it '
      + 'up, so the next check is moments away.');
  } else if (auto.nextAt) {
    lines.push(`Next check ${fmt.until(new Date(auto.nextAt).getTime())}.`);
  }
  return lines.join('\n');
}

// --- routing ---

const ROUTES = [
  { pattern: /^#\/$/, render: renderDashboard },
  { pattern: /^#\/trips$/, render: renderTrips },
  { pattern: /^#\/trip\/(.+)$/, render: renderTrip },
  { pattern: /^#\/ledger$/, render: renderLedger },
  { pattern: /^#\/analysis$/, render: renderAnalysis },
  { pattern: /^#\/settings$/, render: renderSetup },
];

export function navigate(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

async function route() {
  const view = document.getElementById('view');
  const hash = location.hash || '#/';
  renderNav();

  // Views own canvases and observers; give the outgoing one a chance to release them.
  if (state.active && typeof state.active.destroy === 'function') {
    try { state.active.destroy(); } catch { /* a broken teardown must not block navigation */ }
  }
  state.active = null;

  const match = ROUTES.find((r) => r.pattern.test(hash));
  if (!match) { navigate('#/'); return; }

  // Nothing is configured yet: every data view would just show an error, so send the user
  // to the one screen that can actually help.
  if (state.status && state.status.config && !state.status.config.configured && hash !== '#/settings') {
    navigate('#/settings');
    return;
  }

  mount(view, spinner('Loading'));
  const params = hash.match(match.pattern).slice(1).map(decodeURIComponent);
  try {
    const result = await match.render(view, ...params);
    state.active = result || null;
  } catch (err) {
    mount(view, errorBox(err.message, () => route()));
  }
}

window.addEventListener('hashchange', route);

// --- boot ---

async function boot() {
  try {
    state.status = await api.status();
  } catch (err) {
    mount(document.getElementById('view'), errorBox(
      `Cannot reach the viewer's own server: ${err.message}`,
    ));
    return;
  }
  renderHeaderStatus();
  if (state.status.config && state.status.config.configured) {
    await loadVehicles();
  }
  if (!location.hash) location.hash = '#/';
  await route();
  schedulePoll(state.status.progress && state.status.progress.running ? 700 : 15000);
}

export { refreshStatus };

boot();
