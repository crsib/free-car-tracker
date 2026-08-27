# Car Tracker viewer

A read-only web/desktop client for the data the Android app syncs to WebDAV. One
self-contained binary: it does the WebDAV talking, caches what it downloads, and serves a
browser UI on `localhost`.

- **Dashboard** — the phone's home screen with room to breathe: full-tank economy, kWh/100 km,
  windowed spend, cost per distance split by energy source, consumption trend and spread.
- **Trips** — every synced recording, with badges for what data each one carries.
- **Trip detail** — the route on a map coloured by speed, next to multi-series OBD charts.
  Hovering either one moves the crosshair on the other; zooming the chart re-reads that window
  at full resolution.
- **Ledger** — one sortable, searchable table across fuel, charging and expenses, with CSV out.
- **Cross-trip analysis** — any two recorded metrics on a scatter plot with a least-squares fit,
  plus a per-month rollup. This is the "does cold weather actually cost me range" view.
- **Raw data** — download any trip's GPX, OBD CSV or `trip.json` individually, or a filtered zip
  of the lot. `.gz` files are decompressed by default so they open directly in other tools.

It **never writes to the WebDAV server** — only `GET` and `PROPFIND`, so pointing it at a live
sync folder cannot damage it. There is a unit test asserting exactly that.

## Why a local server and not a static page

A browser cannot talk to Nextcloud/ownCloud WebDAV directly: `PROPFIND` is a non-simple method,
so it triggers a CORS preflight that those servers do not answer, and the request dies before
authentication. This process does the WebDAV half server-side and serves a plain same-origin
JSON API to the page — which also makes the same binary deployable on a box you can reach from
your phone.

## Build and run

Requires Go 1.24+. No other dependencies — the UI is embedded in the binary and the whole
program uses only the standard library.

```bash
cd viewer && go build -o fct-viewer .
```

```bash
./fct-viewer
```

That opens <http://127.0.0.1:8420> in your browser. On first run the UI asks for your WebDAV
directory URL and credentials, verifies them, then indexes the folder.

On Nextcloud the directory URL looks like:

```
https://cloud.example.com/remote.php/dav/files/USERNAME/CarTracker/
```

Use a dedicated **app password** (Settings → Security → Create new app password), not your
account password.

### Flags

| Flag | Default | Purpose |
| --- | --- | --- |
| `-addr` | `127.0.0.1:8420` | Listen address. `0.0.0.0:8420` exposes it on the LAN. |
| `-auth user:pass` | — | Require HTTP Basic auth. Use whenever `-addr` is not loopback. |
| `-allow-from` | — | Comma-separated IPs/CIDRs allowed to connect. The fence to use behind an SSO proxy. |
| `-config` | OS config dir | Path to `config.json`. |
| `-cache` | OS cache dir | Where downloaded trip payloads live. |
| `-cache-max` | unlimited | Cap the payload cache (`6GB`, `800MB`); evicts least-recently-used. |
| `-url`, `-user`, `-password` | from config | Override the saved server for one run. |
| `-insecure` | `false` | Skip TLS verification (self-signed home servers only). |
| `-open` | `true` | Open a browser on start, for loopback listeners. |
| `-refresh` | `true` | Re-read the WebDAV folder on start. |
| `-webdav-rps` | `3` | Cap outbound WebDAV requests per second. |
| `-webdav-retries` | `5` | Retries for a throttled (429/503) request. |
| `-webdav-concurrency` | `2` | WebDAV requests in flight at once. |

`FCT_WEBDAV_URL`, `FCT_WEBDAV_USERNAME`, `FCT_WEBDAV_PASSWORD`, `FCT_CACHE_DIR` and
`FCT_WEBDAV_INSECURE` work too, which is the sane way to configure it in a container.

### Running it as a web app

The binary cross-compiles to Linux and macOS with no cgo:

```bash
scripts/build.sh v1.0.0        # or: pwsh scripts/build.ps1 -Version v1.0.0
```

That writes `dist/fct-viewer` (linux/amd64, ~7.7 MB) with the version and commit stamped in.

Two independent fences guard a non-loopback listener; use at least one:

- `-auth user:pass` — HTTP Basic, for a standalone deployment.
- `-allow-from 127.0.0.1,::1,10.0.0.5` — restrict by peer IP, for a deployment behind an
  authenticating reverse proxy. This is the right control there: the proxy handles users, and
  the allowlist stops anything else on the network from addressing the backend port directly
  and bypassing it. It filters on the **socket peer address and ignores `X-Forwarded-For`** —
  that header is set by whoever is talking to us, so trusting it would defeat the whole point.

The process holds your WebDAV credentials, so with neither fence on a non-loopback listener it
hands your data to anything that can route to the port. It logs a warning if you forget. Put it
behind TLS if it is reachable from the internet.

`-cache-max 6GB` caps the payload cache and evicts least-recently-used files, which matters on a
server with a fixed disk. Eviction only sheds raw payloads: the catalog and the per-trip
summaries are never evicted, so the analysis views keep working from data whose source files
have already been dropped.

### Deployed at home

This runs as **host `trips`** on server, published behind proxy SSO at
`viewer.example.com`. `deploy/fct-viewer.service` is the unit; the full procedure —
container creation, the privilege model, the proxy resource, backups and rollback — is in
`ops-config/runbooks/deploy-car-tracker-viewer.md`.

Unlike the other-service dashboard next door, this service needs no privilege at all — it reads a
remote server and writes only its own config and cache — so it runs `NoNewPrivileges=yes` with
`ProtectSystem=strict` and no sudo grant whatsoever.

## Being a good WebDAV client

The server on the other end is not necessarily yours. Pointed at **Yandex.Disk**, the original
walk — eight parallel workers, two requests per trip, no retry — was answered with `429 Too Many
Requests` and lost **131 of 157 trips**. They surfaced as index warnings, but the trip list just
looked short, which is the worst way for this to fail.

So the client is paced and defensive, with defaults chosen for a hosted provider:

- A **global token bucket** caps the rate (default 3/s) regardless of how many workers run, so
  raising concurrency cannot defeat it.
- `429`/`503` are **retried with exponential backoff and jitter**, honouring `Retry-After` —
  including the difference between "wait 0" (come straight back) and no header at all.
- The pacer **slows further on each throttle and eases back off as requests succeed** (AIMD), so
  it converges on whatever the provider actually tolerates instead of crawling after one 429.
- Requests carry an **identifying User-Agent**, because shared services are entitled to know who
  is calling.
- The walk spends **one request per trip**, not two: it GETs `trip.json` directly rather than
  listing each folder first. A finished trip is immutable, so the listing bought nothing that the
  manifest's own `syncedAtMillis` does not already provide.

One consequence worth stating plainly: **side-file sizes are unknown until a file is downloaded**,
because they used to come from that per-folder listing. Whether a file *exists* comes from the
manifest and is always known, so anything user-facing keys off existence and treats size as
display-only. Conflating the two once hid the GPX and CSV download buttons on every trip that had
not been fetched yet.

Throttle counts and cumulative wait appear in `/api/status` and in the header, and the rate is
editable in **Settings → Request pacing**. A partial index is flagged in the header rather than
being left to look like a short trip list.

Against the real folder: 157/157 trips, 0 warnings, 0 throttles, ~38 s for a full walk.

## How it reads the folder

The layout is the one `WebDavPaths.kt` writes:

```
{vehicleSyncId}/vehicle.json
{vehicleSyncId}/ledger.json
{vehicleSyncId}/trips/YYYY/MM/{tripSyncId}/{trip.json, obd.csv.gz, track.gpx.gz}
{vehicleSyncId}/tombstones/{tripSyncId}
```

Indexing walks it with parallel `PROPFIND`s and reads every `trip.json`. A folder without a
`vehicle.json` is not a car and is skipped, so `did-scans/` and anything else sharing the
directory is left alone. A trip folder without `trip.json` is an upload that never finished —
`trip.json` is written last — so it is skipped too. Tombstoned trips are excluded even when
their folder is still present.

Anything the walk cannot read becomes a warning shown in Settings, rather than silently
shrinking the index.

### Caching

Payloads are cached on disk and validated by ETag, so a trip is downloaded once and then opens
instantly. **Download all trip data** in Settings or on the Trips page fetches everything up
front, which is what fills in the OBD-derived columns (ambient temperature, elevation gain, SoC,
per-metric means) that cross-trip analysis plots. Until a trip has been fetched, the analysis
view says so rather than quietly leaving it out.

The catalog is persisted, so restarting shows your data before any network call — and a viewer
started offline still browses everything already cached.

## Fuel usage and economy

The app only fills a trip's `fuelUsedL` by integrating `fuelRateLph` (PID 0x5E), and most cars
never answer that PID — on a real 157-trip folder it was set on **two**. So the viewer estimates
it from whatever the ECU did report, and says which method it used:

| Method | Confidence | Source |
| --- | --- | --- |
| engine off | measured | rpm and engine load both flat at zero — nothing was burned |
| fuel rate | measured | the app's own aggregate, or our integration of `fuelRateLph` |
| mass air flow | estimated | `mafGramsPerSec` / AFR / density, assuming stoichiometric |
| tank gauge | rough | `fuelLevelPct` drop × the car's tank capacity |

Every figure is plausibility-checked and falls through to the next method on failure, because
the sources lie in specific ways:

- **A stationary engine outranks everything.** On a range-extender most trips run on battery,
  and the gauge still wanders — one 6 km trip's gauge "dropped" 14.5 points, implying 8.7 L,
  while rpm sat at zero for all 303 samples. That trip reports 0 L.
- **Absurd totals are rejected rather than propagated.** A single spurious fuel-rate sample
  integrates into nonsense; two real trips carried an app-computed 597,423,361,848,841 L. A
  figure above two tankfuls is treated as a broken reading.
- **An integral needs enough samples to be a trip total.** A two-sample MAF blip once beat a
  demonstrably stationary engine.
- **The gauge needs distance to mean anything.** Below 15 km its quantisation dwarfs the
  reading, so no estimate is offered. A gauge that *rose* (a refuel, or slosh) is rejected, and
  one that started brim-full is flagged as reading low, since it stays pinned after a fill.

Signals are extracted once at summarise time and stored raw, so changing a car's tank capacity
re-values every trip without re-downloading anything. `fuelLitres` / `fuelL100` / `fuelMethod`
are in the analysis rows and the CSV, so estimated economy can be plotted against temperature or
anything else.

## Matching the app's numbers

A viewer that disagreed with the phone would be worse than useless, so the economy maths is a
deliberate port rather than a re-derivation: `internal/domain` mirrors `EconomyCalculator.kt`,
`EnergyCostCalculator.kt`, `RecurrenceProjector.kt`, `UnitConversions.kt` and `Money.kt`,
including the full-tank anchoring rule, the skip conditions, and the recurring-expense
expansion. `internal/domain/economy_test.go` pins that behaviour.

If you change those calculators in the app, change them here too.

## Tests

```bash
cd viewer && go test ./...
```

They cover the full-tank economy method, energy-cost shares, recurrence expansion, the OBD CSV
parser (including blanks-as-gaps and spike-preserving decimation), the GPX parser and its
namespaced extensions, the WebDAV multistatus parser against a Nextcloud-shaped response, and
the index walk with tombstones and half-finished uploads, the peer-IP allowlist (including that
it ignores forged `X-Forwarded-For`), LRU cache eviction (including that it never sheds summaries
or the catalog), rate limiting and `Retry-After` handling, and that a throttled server costs no
trips and the walk never lists a trip folder.

## Layout

```
viewer/
  main.go                 flags, embedded UI, graceful shutdown
  internal/webdav/        read-only WebDAV client (PROPFIND + GET)
  internal/model/         Go mirrors of the sync JSON documents
  internal/domain/        port of the app's pure calculators
  internal/series/        obd.csv and track.gpx parsing, decimation
  internal/store/         byte cache, catalog index, per-trip summaries
  internal/api/           JSON endpoints, CSV and zip exports
  web/                    the UI (no build step, no dependencies)
  deploy/                 systemd unit for the container deployment
  scripts/                cross-compile helpers (build.ps1 / build.sh)
```

The UI is plain ES modules with hand-written canvas chart and map renderers. That is a
deliberate trade: no npm, no vendored assets, no CDN — so `go build` produces the entire
application, and it works offline apart from map tiles (switchable to none).
