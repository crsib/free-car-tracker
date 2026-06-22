package me.crsib.freecartracker.data.webdav

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the relative WebDAV paths for the per-car / per-trip sync layout:
 * ```
 * {vehicleSyncId}/vehicle.json
 * {vehicleSyncId}/ledger.json
 * {vehicleSyncId}/trips/YYYY/MM/{tripSyncId}/{trip.json,obd.csv.gz,track.gpx.gz}
 * ```
 * Keys are UUIDs (URL-safe). Month buckets keep any one PROPFIND listing small.
 */
object WebDavPaths {
    const val VEHICLE_JSON = "vehicle.json"
    const val LEDGER_JSON = "ledger.json"
    const val LEDGER_BAK = "ledger.json.bak"
    const val TRIP_JSON = "trip.json"
    const val OBD_CSV_GZ = "obd.csv.gz"
    const val TRACK_GPX_GZ = "track.gpx.gz"

    private val YEAR = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC)
    private val MONTH = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC)

    fun vehicleDir(vehicleSyncId: String): String = "$vehicleSyncId/"
    fun vehicleJson(vehicleSyncId: String): String = "$vehicleSyncId/$VEHICLE_JSON"
    fun ledgerJson(vehicleSyncId: String): String = "$vehicleSyncId/$LEDGER_JSON"
    fun ledgerBak(vehicleSyncId: String): String = "$vehicleSyncId/$LEDGER_BAK"

    fun tripsRoot(vehicleSyncId: String): String = "$vehicleSyncId/trips/"

    /** `{vehicleSyncId}/trips/YYYY/MM/` derived from the trip's start time (UTC). */
    fun monthBucket(vehicleSyncId: String, startedAtMillis: Long): String {
        val instant = Instant.ofEpochMilli(startedAtMillis)
        return "$vehicleSyncId/trips/${YEAR.format(instant)}/${MONTH.format(instant)}/"
    }

    fun tripDir(vehicleSyncId: String, startedAtMillis: Long, tripSyncId: String): String =
        monthBucket(vehicleSyncId, startedAtMillis) + tripSyncId + "/"

    fun tripFile(vehicleSyncId: String, startedAtMillis: Long, tripSyncId: String, file: String): String =
        tripDir(vehicleSyncId, startedAtMillis, tripSyncId) + file
}
