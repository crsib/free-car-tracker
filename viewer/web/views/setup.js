// Settings: connect to a WebDAV server or S3 bucket, and manage the local cache.

import { el, mount, card } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { state, loadVehicles, refreshStatus, navigate, onStatus } from '../app.js';

export async function renderSetup(view) {
  const status = await api.status();
  state.status = status;
  const cfg = status.config || {};

  let backend = cfg.backend || 'webdav';
  const s3 = cfg.s3 || {};

  const urlInput = el('input', {
    class: 'input', type: 'url', value: cfg.url || '', placeholder: 'https://cloud.example.com/remote.php/dav/files/me/CarTracker/',
  });
  const userInput = el('input', { class: 'input', type: 'text', value: cfg.username || '', autocomplete: 'username' });
  const passInput = el('input', {
    class: 'input', type: 'password', autocomplete: 'current-password',
    placeholder: cfg.hasPassword ? 'unchanged — leave blank to keep it' : '',
  });
  const insecureInput = el('input', { type: 'checkbox', checked: cfg.insecure || false });

  const s3EndpointInput = el('input', {
    class: 'input', type: 'url', value: s3.endpoint || '',
    placeholder: 'https://s3.eu-central-1.amazonaws.com — empty means AWS',
  });
  const s3RegionInput = el('input', { class: 'input', type: 'text', value: s3.region || '', placeholder: 'us-east-1 (R2: auto)' });
  const s3BucketInput = el('input', { class: 'input', type: 'text', value: s3.bucket || '' });
  const s3PrefixInput = el('input', {
    class: 'input', type: 'text', value: s3.prefix || '', placeholder: 'CarTracker — empty for the bucket root',
  });
  const s3KeyInput = el('input', { class: 'input', type: 'text', value: s3.accessKey || '', autocomplete: 'off' });
  const s3SecretInput = el('input', {
    class: 'input', type: 'password', autocomplete: 'off',
    placeholder: s3.hasSecret ? 'unchanged — leave blank to keep it' : '',
  });
  const s3VirtualHostInput = el('input', { type: 'checkbox', checked: s3.virtualHost || false });
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
    backend,
    s3: {
      endpoint: s3EndpointInput.value.trim(),
      region: s3RegionInput.value.trim(),
      bucket: s3BucketInput.value.trim(),
      prefix: s3PrefixInput.value.trim(),
      accessKey: s3KeyInput.value.trim(),
      secretKey: s3SecretInput.value,
      virtualHost: s3VirtualHostInput.checked,
    },
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
        setMessage(backend === 's3'
          ? 'Connected. The bucket and keys work.'
          : 'Connected. The URL and credentials work.', 'ok');
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
        s3SecretInput.value = '';
        setMessage(backend === 's3' ? 'Saved. Reading your bucket now…' : 'Saved. Reading your WebDAV folder now…', 'ok');
        refreshStatus();
        await loadVehicles();
      } catch (err) {
        setMessage(err.message, 'error');
      }
    },
  }, 'Save and index');

  const insecureRow = () => [
    el('label', { class: 'form-label' }, 'Self-signed TLS'),
    el('div', { class: 'form-inline' }, insecureInput,
      el('span', { class: 'muted' }, 'Skip certificate verification (only for a home server)'))];

  const webdavFields = () => [
    el('p', { class: 'muted' },
      'Point this at the same folder the phone app syncs to. The viewer only ever reads: it '
      + 'issues GET and PROPFIND, never PUT, MKCOL or DELETE.'),
    el('div', { class: 'form-grid' },
      el('label', { class: 'form-label' }, 'Directory URL'), urlInput,
      el('label', { class: 'form-label' }, 'Username'), userInput,
      el('label', { class: 'form-label' }, 'Password'), passInput,
      ...insecureRow()),
    el('p', { class: 'muted small' },
      'On Nextcloud, create a dedicated app password under Settings → Security rather than using '
      + 'your account password. It is stored in ', el('code', {}, 'config.json'),
      ' with owner-only permissions.')];

  const s3Fields = () => [
    el('p', { class: 'muted' },
      'Point this at the same bucket and folder the phone app syncs to — AWS or any '
      + 'S3-compatible store (MinIO, Cloudflare R2, Backblaze B2, Yandex Object Storage). The '
      + 'viewer only ever reads: it lists and downloads objects, never writes or deletes.'),
    el('div', { class: 'form-grid' },
      el('label', { class: 'form-label' }, 'Endpoint'), s3EndpointInput,
      el('label', { class: 'form-label' }, 'Region'), s3RegionInput,
      el('label', { class: 'form-label' }, 'Bucket'), s3BucketInput,
      el('label', { class: 'form-label' }, 'Folder in bucket'), s3PrefixInput,
      el('label', { class: 'form-label' }, 'Access key ID'), s3KeyInput,
      el('label', { class: 'form-label' }, 'Secret access key'), s3SecretInput,
      el('label', { class: 'form-label' }, 'Addressing'),
      el('div', { class: 'form-inline' }, s3VirtualHostInput,
        el('span', { class: 'muted' }, 'Virtual-hosted (bucket.host) — leave off unless your provider requires it')),
      ...insecureRow()),
    el('p', { class: 'muted small' },
      'Give the viewer its own key, limited to reading this bucket (s3:ListBucket and '
      + 's3:GetObject). It is stored in ', el('code', {}, 'config.json'),
      ' with owner-only permissions.')];

  // The inputs live outside the re-render, so switching backends and back keeps what was typed.
  const fields = el('div', {});
  const backendPicker = el('div', { class: 'segmented' });
  const paintBackend = () => {
    mount(backendPicker, ...[['webdav', 'WebDAV'], ['s3', 'S3']].map(([key, label]) => el('button', {
      class: backend === key ? 'seg active' : 'seg',
      onclick: () => { backend = key; mount(message); paintBackend(); },
    }, label)));
    mount(fields, ...(backend === 's3' ? s3Fields() : webdavFields()));
  };
  paintBackend();

  const connection = card('Sync folder',
    backendPicker,
    fields,
    el('div', { class: 'form-actions' }, testBtn, saveBtn, message));

  // --- updates ---

  // Minutes the picker offers. The saved value is added if it is not one of these, so an
  // interval set with -auto-refresh does not silently snap to something else on save.
  const AUTO_CHOICES = [0, 5, 15, 30, 60, 180, 360];
  const savedMinutes = Number(cfg.autoRefreshMinutes || 0);
  const choices = AUTO_CHOICES.includes(savedMinutes) ? AUTO_CHOICES : [...AUTO_CHOICES, savedMinutes].sort((a, b) => a - b);

  const autoMessage = el('div', { class: 'form-message' });
  const autoLine = el('div', { class: 'muted small' });
  const autoSelect = el('select', {
    class: 'select',
    onchange: async (e) => {
      const minutes = Number(e.target.value);
      try {
        await api.setAutoRefresh(minutes);
        mount(autoMessage, el('span', { class: 'msg msg-ok' },
          minutes > 0 ? `Checking every ${fmt.interval(minutes)}.` : 'Automatic updates are off.'));
        refreshStatus();
      } catch (err) {
        mount(autoMessage, el('span', { class: 'msg msg-error' }, err.message));
      }
    },
  }, ...choices.map((m) => el('option', { value: String(m), selected: m === savedMinutes },
    m === 0 ? 'Off — only when I ask' : `Every ${fmt.interval(m)}`)));

  const refreshBtn = el('button', {
    class: 'btn btn-primary',
    onclick: async () => {
      try { await api.refresh(); refreshStatus(); } catch (e) { mount(autoMessage, el('span', { class: 'msg msg-error' }, e.message)); }
    },
  }, 'Check for new data');

  const fullBtn = el('button', {
    class: 'btn',
    title: 'Re-download every trip.json instead of trusting the cached copies',
    onclick: async () => {
      if (!confirm('A full re-read asks the server for every trip manifest again — one request per '
        + 'trip, so it takes a while on a large folder. Continue?')) return;
      try { await api.refresh(true); refreshStatus(); } catch (e) { mount(autoMessage, el('span', { class: 'msg msg-error' }, e.message)); }
    },
  }, 'Full re-read');

  // The card has to keep up with the poll: a countdown that only moves when you reload the
  // page is worse than no countdown at all.
  const paintUpdates = (st) => {
    const progress = st.progress || {};
    const auto = st.autoRefresh || {};
    refreshBtn.disabled = progress.running;
    fullBtn.disabled = progress.running;
    if (progress.running) {
      mount(autoLine, `Reading the folder now — ${progress.message || 'working'}.`);
      return;
    }
    const bits = [];
    bits.push(st.builtAt ? `Last read ${fmt.relative(new Date(st.builtAt).getTime())}.` : 'Never read yet.');
    if (!auto.enabled) bits.push('Automatic updates are off.');
    else if (auto.idle) bits.push('Paused while nothing was reading this server; this page has just resumed it.');
    else if (auto.nextAt) bits.push(`Next automatic check ${fmt.until(new Date(auto.nextAt).getTime())}.`);
    mount(autoLine, bits.join(' '));
  };
  paintUpdates(status);
  const unsubscribe = onStatus(paintUpdates);

  const updates = card('Updates',
    el('p', { class: 'muted' },
      'New drives appear here once the phone has synced them and the viewer has re-read the '
      + 'folder. It can do that on a timer, and only while a page is open — with nobody '
      + 'looking there is nothing to keep fresh, and a hosted server that is walked all night '
      + 'starts answering with 429s.'),
    el('div', { class: 'form-grid' },
      el('label', { class: 'form-label' }, 'Check automatically'), autoSelect),
    autoLine,
    el('div', { class: 'form-actions' }, refreshBtn, fullBtn, autoMessage),
    el('p', { class: 'muted small' },
      'A check only lists directories and reads the manifests of trips it has not seen, so it '
      + 'is cheap. ', el('strong', {}, 'Full re-read'), ' re-downloads every manifest instead of '
      + 'trusting the cached copies — the thing to try when a trip looks wrong or an upload was '
      + 'interrupted mid-write.'));

  const throttled = status.throttled || 0;
  const pacing = card('Request pacing',
    el('p', { class: 'muted' },
      'How hard the viewer is allowed to hit your server. Hosted providers throttle: '
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
      cfg.configured ? updates : null,
      cfg.configured ? pacing : null,
      cfg.configured ? cacheCard : null,
      warnings,
      cfg.configured
        ? el('div', { class: 'form-actions' },
          el('button', { class: 'btn btn-ghost', onclick: () => navigate('#/') }, 'Back to the dashboard'))
        : null));
  return { destroy: unsubscribe };
}
