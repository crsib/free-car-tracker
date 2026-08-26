// Settings: connect to a WebDAV server, and manage the local cache.

import { el, mount, card } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { state, loadVehicles, refreshStatus, navigate } from '../app.js';

export async function renderSetup(view) {
  const status = await api.status();
  state.status = status;
  const cfg = status.config || {};

  const urlInput = el('input', {
    class: 'input', type: 'url', value: cfg.url || '', placeholder: 'https://cloud.example.com/remote.php/dav/files/me/CarTracker/',
  });
  const userInput = el('input', { class: 'input', type: 'text', value: cfg.username || '', autocomplete: 'username' });
  const passInput = el('input', {
    class: 'input', type: 'password', autocomplete: 'current-password',
    placeholder: cfg.hasPassword ? 'unchanged — leave blank to keep it' : '',
  });
  const insecureInput = el('input', { type: 'checkbox', checked: cfg.insecure || false });
  const rpsInput = el('input', {
    class: 'input', type: 'number', min: '0.2', step: '0.1', value: String(cfg.rps ?? 3),
  });
  const retriesInput = el('input', {
    class: 'input', type: 'number', min: '0', step: '1', value: String(cfg.maxRetries ?? 5),
  });
  const concInput = el('input', {
    class: 'input', type: 'number', min: '1', max: '16', step: '1', value: String(cfg.concurrency ?? 2),
  });
  const message = el('div', { class: 'form-message' });

  const payload = () => ({
    url: urlInput.value.trim(),
    username: userInput.value.trim(),
    password: passInput.value,
    insecure: insecureInput.checked,
    requestsPerSecond: Number(rpsInput.value) || 0,
    maxRetries: Number(retriesInput.value) || 0,
    concurrency: Number(concInput.value) || 0,
  });

  const setMessage = (text, kind) => {
    mount(message, el('span', { class: `msg msg-${kind}` }, text));
  };

  const testBtn = el('button', {
    class: 'btn', onclick: async () => {
      setMessage('Testing…', 'info');
      try {
        await api.test(payload());
        setMessage('Connected. The URL and credentials work.', 'ok');
      } catch (err) {
        setMessage(err.message, 'error');
      }
    },
  }, 'Test connection');

  const saveBtn = el('button', {
    class: 'btn btn-primary', onclick: async () => {
      setMessage('Saving…', 'info');
      try {
        const res = await api.setup(payload());
        state.status.config = res.config;
        passInput.value = '';
        setMessage('Saved. Reading your WebDAV folder now…', 'ok');
        refreshStatus();
        await loadVehicles();
      } catch (err) {
        setMessage(err.message, 'error');
      }
    },
  }, 'Save and index');

  const connection = card('WebDAV server',
    el('p', { class: 'muted' },
      'Point this at the same folder the phone app syncs to. The viewer only ever reads: it '
      + 'issues GET and PROPFIND, never PUT, MKCOL or DELETE.'),
    el('div', { class: 'form-grid' },
      el('label', { class: 'form-label' }, 'Directory URL'), urlInput,
      el('label', { class: 'form-label' }, 'Username'), userInput,
      el('label', { class: 'form-label' }, 'Password'), passInput,
      el('label', { class: 'form-label' }, 'Self-signed TLS'),
      el('div', { class: 'form-inline' }, insecureInput,
        el('span', { class: 'muted' }, 'Skip certificate verification (only for a home server)'))),
    el('p', { class: 'muted small' },
      'On Nextcloud, create a dedicated app password under Settings → Security rather than using '
      + 'your account password. It is stored in ', el('code', {}, 'config.json'),
      ' with owner-only permissions.'),
    el('div', { class: 'form-actions' }, testBtn, saveBtn, message));

  const throttled = status.throttled || 0;
  const pacing = card('Request pacing',
    el('p', { class: 'muted' },
      'How hard the viewer is allowed to hit your WebDAV server. Hosted providers throttle: '
      + 'Yandex.Disk answers a burst of requests with 429 and drops them, which shows up as trips '
      + 'silently missing from the index. Throttled requests are retried with backoff, and the '
      + 'viewer slows itself down further whenever the server pushes back.'),
    throttled
      ? el('div', { class: 'notice' },
        el('strong', {}, `The server has throttled us ${throttled} time${throttled === 1 ? '' : 's'} `
          + `since start (${Math.round(status.throttleWaitSeconds || 0)}s spent waiting).`),
        el('span', {}, ' A handful during a first index is normal. If it keeps happening, lower '
          + 'the rate below.'))
      : null,
    el('div', { class: 'form-grid' },
      el('label', { class: 'form-label' }, 'Requests per second'), rpsInput,
      el('label', { class: 'form-label' }, 'Retries on 429'), retriesInput,
      el('label', { class: 'form-label' }, 'Requests in flight'), concInput),
    el('p', { class: 'muted small' },
      'Saved with the connection above. A full re-index costs about one request per trip, so at '
      + '3/s a few hundred trips take roughly a minute. Trip payloads are cached afterwards, so '
      + 'this only matters on the first pass and after new drives sync.'));

  const cacheCard = card('Local cache',
    el('div', { class: 'kv' },
      el('div', {}, 'Location'), el('code', {}, cfg.cacheDir || '—'),
      el('div', {}, 'Downloaded'), el('div', {}, `${fmt.bytes(status.cacheBytes || 0)} in ${status.cacheFiles || 0} files`),
      el('div', {}, 'Cars'), el('div', {}, String(status.vehicleCount || 0)),
      el('div', {}, 'Trips'), el('div', {}, String(status.tripCount || 0)),
      el('div', {}, 'Trips with full data'),
      el('div', {}, `${status.summarisedTripCount || 0} of ${status.tripCount || 0}`)),
    el('p', { class: 'muted small' },
      'Trip payloads are cached by ETag, so a trip is downloaded once and then opens instantly. '
      + 'Clearing the cache does not touch the server.'),
    el('div', { class: 'form-actions' },
      el('button', {
        class: 'btn', onclick: async () => {
          try { await api.refresh(); refreshStatus(); } catch (e) { alert(e.message); }
        },
      }, 'Re-index now'),
      el('button', {
        class: 'btn', onclick: async () => {
          try { await api.prefetch(); refreshStatus(); } catch (e) { alert(e.message); }
        },
      }, 'Download all trip data'),
      el('button', {
        class: 'btn btn-danger', onclick: async () => {
          if (!confirm('Delete every cached file? The server is not touched; the next view re-downloads what it needs.')) return;
          try { await api.clearCache(); refreshStatus(); } catch (e) { alert(e.message); }
        },
      }, 'Clear cache')));

  const warnings = (status.warnings || []).length
    ? card('Index warnings',
      el('p', { class: 'muted' },
        'These parts of the folder could not be read, so the index is incomplete:'),
      el('ul', { class: 'warning-list' }, ...status.warnings.map((warning) => el('li', {}, warning))))
    : null;

  mount(view,
    el('div', { class: 'page page-narrow' },
      el('h1', { class: 'page-title' }, 'Settings'),
      connection,
      cfg.configured ? pacing : null,
      cfg.configured ? cacheCard : null,
      warnings,
      cfg.configured
        ? el('div', { class: 'form-actions' },
          el('button', { class: 'btn btn-ghost', onclick: () => navigate('#/') }, 'Back to the dashboard'))
        : null));
  return null;
}
