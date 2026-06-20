# Car Tracker

A fast, compact Android app for tracking all car-related expenses, with first-class support
for **REV / plug-in hybrid** vehicles that consume both fuel *and* electricity.

- **Dashboard** — last fuel economy (full-tank-to-full-tank), electric economy (kWh/100 km),
  spend over a configurable window, cost per distance, a consumption trend chart, and recent
  entries. Per-vehicle, with a vehicle switcher.
- **Entries** — fuel fills, charging sessions, and expenses (service, car wash, accessories,
  financing, insurance, tax, parking, tolls, other). Forms adapt to the powertrain: ICE hides
  charging, EV hides fuel, REV/hybrid shows what applies.
- **Multiple vehicles** — add, edit, archive, set a default.
- **Backup** — JSON export/import (merge or replace) and CSV export via the system file picker;
  optional automatic daily backup to your own **WebDAV** server (Nextcloud, ownCloud, …).
- **Offline-first** — all data lives in a local Room database; backup is layered on top.

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · DataStore · WorkManager · OkHttp ·
kotlinx-serialization. Manual DI (no Hilt) to keep the build fast and the APK small
(~3.6 MB release). `minSdk 34` (Android 14), `targetSdk 35` (Android 15).

Storage is canonical (km, litres, kWh, integer money minor units); units are converted for
display per the vehicle's Metric/Imperial setting.

## Build

Requires JDK 17 and the Android SDK (platform 35, build-tools 35). `local.properties` points
at the SDK.

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # minified release APK (R8 + resource shrinking)
./gradlew :app:testDebugUnitTest  # unit tests (economy, conversions, money)
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/me/crsib/freecartracker/
  data/        Room entities, DAOs, database, repositories, backup, webdav, prefs
  domain/      EconomyCalculator, unit conversions, money, enums (pure, unit-tested)
  ui/          dashboard, entry, history, vehicles, settings, webdav, components, theme
  di/          AppContainer (manual DI)
app/src/test/  domain unit tests
```

## Fuel economy method

Between two full-tank fills, distance is the odometer delta and consumption is the sum of
every fill made *after* the opening full tank up to and including the closing one (partial
fills included). This is the accurate way to measure real consumption; mark fills as "full"
for it to work. See `EconomyCalculator`.
