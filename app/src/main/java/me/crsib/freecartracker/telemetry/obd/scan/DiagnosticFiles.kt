package me.crsib.freecartracker.telemetry.obd.scan

/**
 * The `filesDir` subfolders where diagnostic CSVs are written (DID scans, PID sweeps, init maps,
 * watch logs). Each scan uploads to WebDAV inline, but that fails silently when offline — so the
 * force-sync ([ObdConnectionViewModel.syncDiagnosticFiles]) walks exactly these folders to retry.
 */
object DiagnosticFiles {
    const val DID_SCANS = "did-scans"
    const val STD_PID_SWEEPS = "std-pid-sweeps"
    const val INIT_MAPS = "init-maps"
    const val WATCH_LOGS = "did-watch"

    val allSubdirs = listOf(DID_SCANS, STD_PID_SWEEPS, INIT_MAPS, WATCH_LOGS)
}
