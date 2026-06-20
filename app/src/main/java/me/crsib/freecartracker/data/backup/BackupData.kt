package me.crsib.freecartracker.data.backup

import kotlinx.serialization.Serializable
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle

/** Versioned snapshot of the whole database, used for JSON export/import. */
@Serializable
data class BackupData(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long = 0,
    val vehicles: List<Vehicle> = emptyList(),
    val fuelEntries: List<FuelEntry> = emptyList(),
    val chargeEntries: List<ChargeEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class RestoreMode { REPLACE, MERGE }
