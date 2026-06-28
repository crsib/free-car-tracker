package me.crsib.freecartracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.crsib.freecartracker.data.dao.ChargeEntryDao
import me.crsib.freecartracker.data.dao.CostDao
import me.crsib.freecartracker.data.dao.DeletedLedgerEntryDao
import me.crsib.freecartracker.data.dao.ExpenseDao
import me.crsib.freecartracker.data.dao.FuelEntryDao
import me.crsib.freecartracker.data.dao.VehicleDao
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.CostView
import me.crsib.freecartracker.data.entity.DeletedLedgerEntry
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.telemetry.data.dao.DeletedTripDao
import me.crsib.freecartracker.telemetry.data.dao.DtcDao
import me.crsib.freecartracker.telemetry.data.dao.GpsPointDao
import me.crsib.freecartracker.telemetry.data.dao.ObdExtendedSampleDao
import me.crsib.freecartracker.telemetry.data.dao.ObdSampleDao
import me.crsib.freecartracker.telemetry.data.dao.TripDao
import me.crsib.freecartracker.telemetry.data.entity.DeletedTrip
import me.crsib.freecartracker.telemetry.data.entity.DtcRecord
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.telemetry.data.entity.ObdExtendedSample
import me.crsib.freecartracker.telemetry.data.entity.ObdSample
import me.crsib.freecartracker.telemetry.data.entity.Trip

@Database(
    entities = [
        Vehicle::class,
        FuelEntry::class,
        ChargeEntry::class,
        Expense::class,
        Trip::class,
        GpsPoint::class,
        ObdSample::class,
        ObdExtendedSample::class,
        DtcRecord::class,
        DeletedTrip::class,
        DeletedLedgerEntry::class,
    ],
    views = [CostView::class],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun fuelEntryDao(): FuelEntryDao
    abstract fun chargeEntryDao(): ChargeEntryDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun costDao(): CostDao
    abstract fun tripDao(): TripDao
    abstract fun gpsPointDao(): GpsPointDao
    abstract fun obdSampleDao(): ObdSampleDao
    abstract fun obdExtendedSampleDao(): ObdExtendedSampleDao
    abstract fun dtcDao(): DtcDao
    abstract fun deletedTripDao(): DeletedTripDao
    abstract fun deletedLedgerEntryDao(): DeletedLedgerEntryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds [Expense.recurrenceEndMillis] so recurring costs can stop on a final date. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN recurrenceEndMillis INTEGER")
            }
        }

        /** v3 adds the telemetry tables (trips + GPS/OBD time-series + DTCs). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`vehicleId` INTEGER NOT NULL, `startedAtMillis` INTEGER NOT NULL, " +
                        "`endedAtMillis` INTEGER, `status` TEXT NOT NULL, `distanceKm` REAL, " +
                        "`durationSec` INTEGER, `avgSpeedKmh` REAL, `maxSpeedKmh` REAL, " +
                        "`movingSec` INTEGER, `fuelUsedL` REAL, `energyUsedKwh` REAL, " +
                        "`avgConsumptionL100` REAL, `obdSampleCount` INTEGER NOT NULL, " +
                        "`gpsPointCount` INTEGER NOT NULL, `transport` TEXT, `profileKey` TEXT, " +
                        "`note` TEXT, FOREIGN KEY(`vehicleId`) REFERENCES `vehicles`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trips_vehicleId_startedAtMillis` " +
                        "ON `trips` (`vehicleId`, `startedAtMillis`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gps_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitudeM` REAL, " +
                        "`speedKmh` REAL, `bearingDeg` REAL, `accuracyM` REAL, " +
                        "`provider` TEXT NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_gps_points_tripId_timestampMillis` " +
                        "ON `gps_points` (`tripId`, `timestampMillis`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `obd_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                        "`speedKmh` REAL, `rpm` REAL, `throttlePct` REAL, `engineLoadPct` REAL, " +
                        "`runTimeSec` INTEGER, `coolantTempC` REAL, `intakeAirTempC` REAL, " +
                        "`ambientAirTempC` REAL, `mafGramsPerSec` REAL, `manifoldPressureKpa` REAL, " +
                        "`controlModuleVoltage` REAL, `fuelLevelPct` REAL, `fuelRateLph` REAL, " +
                        "`derivedL100` REAL, `derivedCo2Gkm` REAL, `hybridBatteryLifePct` REAL, " +
                        "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_obd_samples_tripId_timestampMillis` " +
                        "ON `obd_samples` (`tripId`, `timestampMillis`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `obd_extended_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                        "`metricKey` TEXT NOT NULL, `value` REAL NOT NULL, `unit` TEXT NOT NULL, " +
                        "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_obd_extended_samples_tripId_timestampMillis` " +
                        "ON `obd_extended_samples` (`tripId`, `timestampMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_obd_extended_samples_metricKey` " +
                        "ON `obd_extended_samples` (`metricKey`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dtc_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL, " +
                        "`code` TEXT NOT NULL, `pending` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dtc_records_tripId` " +
                        "ON `dtc_records` (`tripId`)",
                )
            }
        }

        /**
         * v4 adds a stable cross-device [syncId] (UUID) to vehicles and trips for WebDAV
         * folder identity, plus [Trip.syncedAtMillis] to track which trips are uploaded.
         * Existing rows are backfilled with distinct UUIDs synthesized in pure SQLite:
         * `randomblob` is re-evaluated per row, so each gets a unique value.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `syncedAtMillis` INTEGER")
                db.execSQL(uuidBackfillSql("vehicles"))
                db.execSQL(uuidBackfillSql("trips"))
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_vehicles_syncId` " +
                        "ON `vehicles` (`syncId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_syncId` " +
                        "ON `trips` (`syncId`)",
                )
            }

            /** Canonical 8-4-4-4-12 UUIDv4 string assembled from random bytes, per row. */
            private fun uuidBackfillSql(table: String): String =
                "UPDATE `$table` SET `syncId` = " +
                    "lower(hex(randomblob(4))) || '-' || " +
                    "lower(hex(randomblob(2))) || '-4' || " +
                    "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                    "substr('89ab', abs(random()) % 4 + 1, 1) || " +
                    "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                    "lower(hex(randomblob(6)))"
        }

        /**
         * v5 adds EV/odometer telemetry columns to `obd_samples`: timing advance (PID 0x0E),
         * odometer (0xA6), and HV pack voltage/current/derived-power (0x9A). All nullable, so
         * existing rows just get NULLs.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (col in listOf(
                    "timingAdvanceDeg", "odometerKm",
                    "hvBatteryVoltage", "hvBatteryCurrent", "hvBatteryPowerKw",
                )) {
                    db.execSQL("ALTER TABLE `obd_samples` ADD COLUMN `$col` REAL")
                }
            }
        }

        /**
         * v6 adds the `deleted_trips` tombstone table so a deleted-but-synced trip can be
         * purged remotely and kept from being re-imported on the next pull.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_trips` (" +
                        "`tripSyncId` TEXT NOT NULL, `vehicleSyncId` TEXT NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, `deletedAtMillis` INTEGER NOT NULL, " +
                        "`remotePurged` INTEGER NOT NULL, PRIMARY KEY(`tripSyncId`))",
                )
            }
        }

        /**
         * v7 gives fuel/charge/expense entries a stable cross-device [syncId] and a per-row
         * [updatedAtMillis] clock so the WebDAV ledger can be *merged* by identity instead of
         * replaced wholesale (which silently dropped entries added on another device). Also adds the
         * `deleted_ledger_entries` tombstone table so deletions propagate. Existing rows get distinct
         * UUIDs and `updatedAtMillis = dateMillis` (a sane last-write proxy for pre-existing data).
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("fuel_entries", "charge_entries", "expenses")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAtMillis` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(ledgerUuidBackfillSql(table))
                    db.execSQL("UPDATE `$table` SET `updatedAtMillis` = `dateMillis`")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_syncId` ON `$table` (`syncId`)",
                    )
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_ledger_entries` (" +
                        "`syncId` TEXT NOT NULL, `vehicleId` INTEGER NOT NULL, " +
                        "`deletedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`syncId`))",
                )
            }

            /** Canonical 8-4-4-4-12 UUIDv4 string assembled from random bytes, per row. */
            private fun ledgerUuidBackfillSql(table: String): String =
                "UPDATE `$table` SET `syncId` = " +
                    "lower(hex(randomblob(4))) || '-' || " +
                    "lower(hex(randomblob(2))) || '-4' || " +
                    "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                    "substr('89ab', abs(random()) % 4 + 1, 1) || " +
                    "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                    "lower(hex(randomblob(6)))"
        }

        /**
         * v8 repairs the divergent-identity bug introduced by [MIGRATION_6_7]: that migration
         * gave every pre-existing ledger entry a *random* `syncId`, run independently on each
         * device, so the same logical entry got a different id everywhere. The new per-entry merge
         * then saw those as distinct rows and duplicated them across devices.
         *
         * This re-keys every entry to a deterministic id derived from its (already cross-device
         * stable) vehicle plus its own content — see [SyncImporter.legacyLedgerSyncId], which must
         * stay byte-for-byte in sync with the SQL below so a stale remote ledger re-keys to the
         * same value. Rows that collide after re-keying are the duplicates; we keep the lowest
         * `id` and drop the rest. Every device runs this and converges on one id per entry.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for ((table, tag) in listOf(
                    "fuel_entries" to "f", "charge_entries" to "c", "expenses" to "e",
                )) {
                    // Drop the unique syncId index so the re-key can transiently collide.
                    db.execSQL("DROP INDEX IF EXISTS `index_${table}_syncId`")
                    db.execSQL(
                        "UPDATE `$table` SET `syncId` = 'l3:$tag:' || " +
                            "IFNULL((SELECT `v`.`syncId` FROM `vehicles` `v` " +
                            "WHERE `v`.`id` = `$table`.`vehicleId`), CAST(`$table`.`vehicleId` AS TEXT)) || " +
                            "':' || `$table`.`dateMillis` || ':' || `$table`.`totalCostMinor`",
                    )
                    // Collapse rows that now share an id (the duplicates), keeping the lowest id.
                    db.execSQL(
                        "DELETE FROM `$table` WHERE `id` NOT IN " +
                            "(SELECT MIN(`id`) FROM `$table` GROUP BY `syncId`)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_syncId` ON `$table` (`syncId`)",
                    )
                }
                // Tombstones written under v7 reference now-defunct random ids; drop them so they
                // can't mis-delete a re-keyed entry. (Pre-v8 deletions made in this window are rare;
                // the entry simply reappears and can be deleted again.)
                db.execSQL("DELETE FROM `deleted_ledger_entries`")
            }
        }

        /** All migrations, in order. Exposed so the migration test can validate them. */
        val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8,
            )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "car_tracker.db",
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
    }
}
