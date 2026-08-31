// Formatting. Storage is canonical (km, litres, kWh, integer minor units); everything the
// user sees is converted here, per the vehicle's own unit system and currency - the same
// split the Android app makes between storage and display.

export const KM_PER_MILE = 1.609344;
export const LITRES_PER_US_GALLON = 3.785411784;

const dash = '—';

export const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

export function num(v, digits = 1) {
  if (!isNum(v)) return dash;
  return v.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function int(v) {
  if (!isNum(v)) return dash;
  return Math.round(v).toLocaleString();
}

/** Money from integer minor units, e.g. 4250 + "EUR" -> "€42.50". */
export function money(minor, currency, digits = 2) {
  if (!isNum(minor)) return dash;
  const major = minor / Math.pow(10, digits);
  try {
    return major.toLocaleString(undefined, { style: 'currency', currency, maximumFractionDigits: digits });
  } catch {
    return `${major.toFixed(digits)} ${currency || ''}`.trim();
  }
}

/** Money at higher precision, for per-km and per-litre figures that round to nothing. */
export function moneyPrecise(minor, currency, digits = 2) {
  if (!isNum(minor)) return dash;
  const major = minor / Math.pow(10, digits);
  try {
    return major.toLocaleString(undefined, {
      style: 'currency', currency, minimumFractionDigits: digits, maximumFractionDigits: digits + 2,
    });
  } catch {
    return `${major.toFixed(digits + 2)} ${currency || ''}`.trim();
  }
}

const imperial = (units) => units && units.distance === 'mi';

export function distance(km, units) {
  if (!isNum(km)) return dash;
  const v = imperial(units) ? km / KM_PER_MILE : km;
  return `${num(v, v < 100 ? 1 : 0)} ${units ? units.distance : 'km'}`;
}

export function volume(litres, units) {
  if (!isNum(litres)) return dash;
  const v = imperial(units) ? litres / LITRES_PER_US_GALLON : litres;
  return `${num(v, 1)} ${units ? units.volume : 'L'}`;
}

export function energy(kwh) {
  return isNum(kwh) ? `${num(kwh, 1)} kWh` : dash;
}

export function speed(kmh, units, digits = 0) {
  if (!isNum(kmh)) return dash;
  return imperial(units)
    ? `${num(kmh / KM_PER_MILE, digits)} mph`
    : `${num(kmh, digits)} km/h`;
}

/** L/100km in, display economy out: L/100km for metric, US MPG for imperial. */
export function fuelEconomy(l100, units) {
  if (!isNum(l100) || l100 <= 0) return dash;
  const v = imperial(units) ? 235.214583 / l100 : l100;
  return `${num(v, 1)} ${units ? units.fuelEconomy : 'L/100km'}`;
}

/** kWh/100km in; mi/kWh out for imperial. */
export function electricEconomy(kwh100, units) {
  if (!isNum(kwh100) || kwh100 <= 0) return dash;
  const v = imperial(units) ? (100 / KM_PER_MILE) / kwh100 : kwh100;
  return `${num(v, 1)} ${units ? units.electricEconomy : 'kWh/100km'}`;
}

/** Cost per distance, converted so an imperial car reads "per mile". */
export function costPerDistance(minorPerKm, units) {
  if (!isNum(minorPerKm)) return dash;
  const perDisplay = imperial(units) ? minorPerKm * KM_PER_MILE : minorPerKm;
  const digits = units ? units.fractionDigits : 2;
  return `${moneyPrecise(perDisplay, units && units.currency, digits)}/${units ? units.distance : 'km'}`;
}

export function date(millis) {
  if (!isNum(millis)) return dash;
  return new Date(millis).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function dateTime(millis) {
  if (!isNum(millis)) return dash;
  return new Date(millis).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

export function timeOnly(millis) {
  if (!isNum(millis)) return dash;
  return new Date(millis).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function duration(seconds) {
  if (!isNum(seconds) || seconds < 0) return dash;
  const s = Math.round(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
  if (m > 0) return `${m}m ${String(sec).padStart(2, '0')}s`;
  return `${sec}s`;
}

export function bytes(n) {
  if (!isNum(n)) return dash;
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v.toFixed(v < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

export function relative(millis) {
  if (!isNum(millis)) return dash;
  const diff = Date.now() - millis;
  const abs = Math.abs(diff);
  const min = 60000, hour = 3600000, day = 86400000;
  if (abs < min) return 'just now';
  if (abs < hour) return `${Math.round(abs / min)} min ago`;
  if (abs < day) return `${Math.round(abs / hour)} h ago`;
  if (abs < 30 * day) return `${Math.round(abs / day)} d ago`;
  return date(millis);
}

/** An interval in minutes, as the update settings speak of it: "15 min", "6 h", "Off". */
export function interval(minutes) {
  if (!isNum(minutes) || minutes <= 0) return 'off';
  if (minutes < 60) return `${Math.round(minutes)} min`;
  const h = minutes / 60;
  return `${h % 1 === 0 ? h : h.toFixed(1)} h`;
}

/** How long until a future timestamp. The counterpart of relative(), which looks backwards. */
export function until(millis) {
  if (!isNum(millis)) return dash;
  const diff = millis - Date.now();
  if (diff <= 15000) return 'any moment now';
  const min = 60000, hour = 3600000;
  if (diff < min) return `in ${Math.round(diff / 1000)} s`;
  if (diff < hour) return `in ${Math.round(diff / min)} min`;
  return `in ${Math.round(diff / hour)} h`;
}

/**
 * Turn SCREAMING_SNAKE enum names into something readable. Acronyms stay upper-case:
 * title-casing them gives "Ice" for an internal combustion engine and "Ac" for AC charging,
 * which reads as a mistake every single time.
 */
const ACRONYMS = new Set(['ICE', 'EV', 'REV', 'AC', 'DC', 'LPG', 'CNG', 'GPS', 'OBD', 'DTC', 'BT', 'BLE', 'WIFI']);

export function label(value) {
  if (!value) return dash;
  return String(value).split('_')
    .map((w) => (ACRONYMS.has(w.toUpperCase())
      ? (w.toUpperCase() === 'WIFI' ? 'Wi-Fi' : w.toUpperCase())
      : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join(' ');
}

/**
 * Display names for the standard OBD columns. These are a closed set defined by
 * ObdCsvSchema.standard, so naming them explicitly beats any camelCase-splitting heuristic —
 * that would give "Hv Battery Power Kw" and "Maf Grams Per Sec".
 */
const METRIC_NAMES = {
  speedKmh: 'Speed',
  rpm: 'Engine RPM',
  throttlePct: 'Throttle',
  engineLoadPct: 'Engine load',
  runTimeSec: 'Run time',
  coolantTempC: 'Coolant temp',
  timingAdvanceDeg: 'Timing advance',
  intakeAirTempC: 'Intake air temp',
  ambientAirTempC: 'Ambient temp',
  mafGramsPerSec: 'Mass air flow',
  manifoldPressureKpa: 'Manifold pressure',
  controlModuleVoltage: 'Module voltage',
  fuelLevelPct: 'Fuel level',
  fuelRateLph: 'Fuel rate',
  derivedL100: 'Consumption',
  derivedCo2Gkm: 'CO₂',
  odometerKm: 'Odometer',
  hybridBatteryLifePct: 'Hybrid battery',
  hvBatteryVoltage: 'HV battery voltage',
  hvBatteryCurrent: 'HV battery current',
  hvBatteryPowerKw: 'HV battery power',
};

/**
 * A metric key as a human label. Manufacturer keys (EV_SOC, MOTOR_TEMP) keep their shape —
 * they are the vendor's own identifiers and renaming them would break the link to the PID
 * profile — but lose the underscores.
 */
export function metricLabel(name) {
  if (!name) return '';
  if (METRIC_NAMES[name]) return METRIC_NAMES[name];
  if (name === name.toUpperCase()) return name.replace(/_/g, ' ');
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/^./, (c) => c.toUpperCase());
}
