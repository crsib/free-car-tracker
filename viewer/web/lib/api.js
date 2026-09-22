// Thin wrapper over the local JSON API. Every call is same-origin, so there is no auth or
// CORS handling here - the server does the WebDAV half.

// The API itself never redirects, so a 3xx can only be a reverse proxy in front of it bouncing
// an unauthenticated request to a login page on another origin. Letting fetch follow that is
// useless: the login origin sends no CORS headers, so the promise rejects with a bare "Failed
// to fetch" that names neither the cause nor the cure. Catching the redirect instead lets the
// tab re-authenticate itself through a real navigation, which the proxy does handle.
const REAUTH_KEY = 'fct.reauthAt';
const REAUTH_GUARD_MS = 60000;

function reauthenticate() {
  let last = 0;
  try { last = Number(sessionStorage.getItem(REAUTH_KEY)) || 0; } catch { /* private mode */ }
  // One reload a minute at most: if signing in does not actually fix it, a reload loop would
  // leave the page unreadable, and unfixable from inside it.
  if (Date.now() - last < REAUTH_GUARD_MS) {
    return 'Your session expired. Reload the page to sign in again.';
  }
  try { sessionStorage.setItem(REAUTH_KEY, String(Date.now())); } catch { /* private mode */ }
  location.reload();
  return 'Your session expired. Signing in again…';
}

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(path, {
      headers: { 'Content-Type': 'application/json' },
      redirect: 'manual',
      ...options,
    });
  } catch (err) {
    throw new Error(`Cannot reach the viewer: ${err.message}`);
  }
  if (res.type === 'opaqueredirect' || res.status === 0) {
    throw new Error(reauthenticate());
  }
  const text = await res.text();
  let body = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = { error: text }; }
  }
  if (!res.ok) {
    throw new Error((body && body.error) || `${res.status} ${res.statusText}`);
  }
  return body;
}

const qs = (params) => {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params || {})) {
    if (v === null || v === undefined || v === '' ) continue;
    search.set(k, Array.isArray(v) ? v.join(',') : String(v));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
};

export const api = {
  status: () => request('/api/status'),
  test: (cfg) => request('/api/test', { method: 'POST', body: JSON.stringify(cfg) }),
  setup: (cfg) => request('/api/setup', { method: 'POST', body: JSON.stringify(cfg) }),
  /** full=true re-downloads every manifest instead of trusting the cached copies. */
  refresh: (full) => request(`/api/refresh${qs({ full: full ? 1 : null })}`, { method: 'POST' }),
  setAutoRefresh: (minutes) => request('/api/auto-refresh', {
    method: 'POST', body: JSON.stringify({ minutes }),
  }),
  prefetch: (vehicle) => request(`/api/prefetch${qs({ vehicle })}`, { method: 'POST' }),
  cancel: () => request('/api/cancel', { method: 'POST' }),
  clearCache: () => request('/api/cache/clear', { method: 'POST' }),

  vehicles: () => request('/api/vehicles'),
  dashboard: (vehicle, windowDays) => request(`/api/vehicles/${encodeURIComponent(vehicle)}/dashboard${qs({ windowDays })}`),
  ledger: (params) => request(`/api/ledger${qs(params)}`),

  trips: (params) => request(`/api/trips${qs(params)}`),
  trip: (id) => request(`/api/trips/${encodeURIComponent(id)}`),
  track: (id, params) => request(`/api/trips/${encodeURIComponent(id)}/track${qs(params)}`),
  series: (id, params) => request(`/api/trips/${encodeURIComponent(id)}/series${qs(params)}`),

  analysis: (params) => request(`/api/analysis/trips${qs(params)}`),
};

/** Download URLs are handed to the browser as links, so they are built rather than fetched. */
export const urls = {
  ledgerCsv: (params) => `/api/ledger.csv${qs(params)}`,
  analysisCsv: (params) => `/api/analysis/trips.csv${qs(params)}`,
  tripFile: (id, name, raw) => `/api/trips/${encodeURIComponent(id)}/file/${name}${qs({ raw: raw ? 1 : null })}`,
  exportZip: (params) => `/api/export/trips.zip${qs(params)}`,
};
