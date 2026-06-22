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
import me.crsib.freecartracker.data.dao.ExpenseDao
import me.crsib.freecartracker.data.dao.FuelEntryDao
import me.crsib.freecartracker.data.dao.VehicleDao
import me.crsib.freecartracker.data.entity.ChargeEntry
import me.crsib.freecartracker.data.entity.CostView
import me.crsib.freecartracker.data.entity.Expense
import me.crsib.freecartracker.data.entity.FuelEntry
import me.crsib.freecartracker.data.entity.Vehicle
import me.crsib.freecartracker.telemetry.data.dao.DtcDao
import me.crsib.freecartracker.telemetry.data.dao.GpsPointDao
import me.crsib.freecartracker.telemetry.data.dao.ObdExtendedSampleDao
import me.crsib.freecartracker.telemetry.data.dao.ObdSampleDao
import me.crsib.freecartracker.telemetry.data.dao.TripDao
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
    ],
    views = [CostView::class],
    version = 3,
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

        /** All migrations, in order. Exposed so the migration test can validate them. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
