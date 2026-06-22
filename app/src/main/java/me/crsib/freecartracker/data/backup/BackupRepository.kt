package me.crsib.freecartracker.data.backup

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import me.crsib.freecartracker.data.AppDatabase
import me.crsib.freecartracker.domain.Money

/** Serializes the database to/from JSON, and exports a flat CSV for spreadsheets. */
class BackupRepository(private val db: AppDatabase) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportJson(now: Long): String {
        val data = BackupData(
            exportedAt = now,
            vehicles = db.vehicleDao().getAll(),
            fuelEntries = db.fuelEntryDao().getAll(),
            chargeEntries = db.chargeEntryDao().getAll(),
            expenses = db.expenseDao().getAll(),
        )
        return json.encodeToString(BackupData.serializer(), data)
    }

    /** Parse and validate a backup document. Throws on malformed input. */
    fun parse(text: String): BackupData = json.decodeFromString(BackupData.serializer(), text)

    /**
     * Imports a Drivvo CSV export, merging its rows into [vehicleId]. Amounts are interpreted in
     * [currency] (the vehicle's). Returns what was parsed so callers can report counts.
     */
    suspend fun importDrivvoCsv(
        text: String,
        vehicleId: Long,
        currency: String,
    ): DrivvoCsvParser.Result {
        val result = DrivvoCsvParser().parse(text, vehicleId, currency)
        db.withTransaction {
            db.fuelEntryDao().insertAll(result.fuel)
            db.chargeEntryDao().insertAll(result.charges)
            db.expenseDao().insertAll(result.expenses)
        }
        return result
    }

    suspend fun restore(data: BackupData, mode: RestoreMode) {
        db.withTransaction {
            when (mode) {
                RestoreMode.REPLACE -> {
                    db.expenseDao().deleteAll()
                    db.chargeEntryDao().deleteAll()
                    db.fuelEntryDao().deleteAll()
                    db.vehicleDao().deleteAll()
                    db.vehicleDao().insertAll(data.vehicles)
                    db.fuelEntryDao().insertAll(data.fuelEntries)
                    db.chargeEntryDao().insertAll(data.chargeEntries)
                    db.expenseDao().insertAll(data.expenses)
                }
                RestoreMode.MERGE -> {
                    // Insert vehicles as new rows, remapping their ids so child entries follow.
                    val idMap = HashMap<Long, Long>()
                    for (v in data.vehicles) {
                        val newId = db.vehicleDao().insert(v.copy(id = 0))
                        idMap[v.id] = newId
                    }
                    db.fuelEntryDao().insertAll(
                        data.fuelEntries.mapNotNull { e ->
                            idMap[e.vehicleId]?.let { e.copy(id = 0, vehicleId = it) }
                        },
                    )
                    db.chargeEntryDao().insertAll(
                        data.chargeEntries.mapNotNull { e ->
                            idMap[e.vehicleId]?.let { e.copy(id = 0, vehicleId = it) }
                        },
                    )
                    db.expenseDao().insertAll(
                        data.expenses.mapNotNull { e ->
                            idMap[e.vehicleId]?.let { e.copy(id = 0, vehicleId = it) }
                        },
                    )
                }
            }
        }
    }

    /** A single flat CSV across all entry types, for spreadsheet use (not re-importable). */
    suspend fun exportCsv(): String {
        val vehicles = db.vehicleDao().getAll().associateBy { it.id }
        val sb = StringBuilder()
        sb.appendLine("date_millis,vehicle,type,odometer_km,amount,currency,detail")

        db.fuelEntryDao().getAll().forEach { e ->
            val v = vehicles[e.vehicleId]
            sb.appendCsv(
                e.dateMillis, v?.name, "FUEL", e.odometerKm, e.totalCostMinor, e.currency,
                "${e.volumeL} L" + if (e.isFullTank) " full" else "",
            )
        }
        db.chargeEntryDao().getAll().forEach { e ->
            val v = vehicles[e.vehicleId]
            sb.appendCsv(
                e.dateMillis, v?.name, "CHARGE", e.odometerKm, e.totalCostMinor, e.currency,
                "${e.energyKwh} kWh",
            )
        }
        db.expenseDao().getAll().forEach { e ->
            val v = vehicles[e.vehicleId]
            sb.appendCsv(
                e.dateMillis, v?.name, "EXPENSE:${e.category.name}", e.odometerKm, e.totalCostMinor, e.currency,
                listOfNotNull(e.vendor, e.description).joinToString(" "),
            )
        }
        return sb.toString()
    }

    private fun StringBuilder.appendCsv(
        dateMillis: Long,
        vehicle: String?,
        type: String,
        odometerKm: Double?,
        amountMinor: Long,
        currency: String,
        detail: String,
    ) {
        val amount = Money.minorToMajor(amountMinor, currency).toPlainString()
        val cells = listOf(
            dateMillis.toString(),
            vehicle.orEmpty(),
            type,
            odometerKm?.toString().orEmpty(),
            amount,
            currency,
            detail,
        )
        appendLine(cells.joinToString(",") { csvEscape(it) })
    }
}
