package me.crsib.freecartracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates that the hand-written Room migrations produce a schema matching the
 * KSP-exported JSON. Run on an emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateAll_fromV1() {
        helper.createDatabase(TEST_DB, 1).close()
        // Validate the full chain (1 -> 2 -> ... -> 8) against the exported v8 schema.
        helper.runMigrationsAndValidate(TEST_DB, 8, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrate7To8_dedupesAndDeterministicallyRekeysLedger() {
        // Seed a v7 DB with two vehicles. Under one vehicle, two fuel rows that are the *same*
        // logical entry (same date + cost) but carry distinct random syncIds — exactly the
        // post-v6->v7 duplicate. A second, genuinely-different row and a row under the other
        // vehicle must survive untouched in count.
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO vehicles (id, name, powertrain, unitSystem, currency, " +
                    "initialOdometerKm, isArchived, createdAt, syncId) VALUES " +
                    "(1, 'A', 'ICE', 'METRIC', 'USD', 0, 0, 0, 'veh-1'), " +
                    "(2, 'B', 'ICE', 'METRIC', 'USD', 0, 0, 0, 'veh-2')",
            )
            execSQL(
                "INSERT INTO fuel_entries (id, vehicleId, dateMillis, odometerKm, volumeL, " +
                    "totalCostMinor, currency, isFullTank, syncId, updatedAtMillis) VALUES " +
                    "(1, 1, 100, 1000.0, 40.0, 5000, 'USD', 1, 'rand-a', 100), " +
                    "(2, 1, 100, 1000.0, 40.0, 5000, 'USD', 1, 'rand-b', 100), " +
                    "(3, 1, 200, 1200.0, 38.0, 4800, 'USD', 1, 'rand-c', 200), " +
                    "(4, 2, 100, 1000.0, 40.0, 5000, 'USD', 1, 'rand-d', 100)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, *AppDatabase.ALL_MIGRATIONS)

        // The two duplicates collapse to one (lowest id kept); the other two rows stay.
        db.query("SELECT COUNT(*), COUNT(DISTINCT syncId) FROM fuel_entries").use { c ->
            c.moveToFirst()
            assert(c.getInt(0) == 3) { "expected 3 rows after dedupe, got ${c.getInt(0)}" }
            assert(c.getInt(1) == 3) { "syncIds not distinct after dedupe" }
        }
        // The kept duplicate is row id 1 with the deterministic id, not either random id.
        db.query("SELECT syncId FROM fuel_entries WHERE id = 1").use { c ->
            c.moveToFirst()
            assert(c.getString(0) == "l3:f:veh-1:100:5000") {
                "expected deterministic syncId, got ${c.getString(0)}"
            }
        }
        // Same content under a different vehicle gets a different id (no cross-vehicle collision).
        db.query("SELECT syncId FROM fuel_entries WHERE id = 4").use { c ->
            c.moveToFirst()
            assert(c.getString(0) == "l3:f:veh-2:100:5000") {
                "expected vehicle-scoped syncId, got ${c.getString(0)}"
            }
        }
    }

    @Test
    fun migrate4To5_addsEvTelemetryColumns() {
        helper.createDatabase(TEST_DB, 4).close()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrate5To6_addsDeletedTripsTable() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrate6To7_backfillsDistinctLedgerSyncIds() {
        // Seed a vehicle and one entry of each type at v6 so the syncId backfill has rows.
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                "INSERT INTO vehicles (id, name, powertrain, unitSystem, currency, " +
                    "initialOdometerKm, isArchived, createdAt, syncId) VALUES " +
                    "(1, 'A', 'ICE', 'METRIC', 'USD', 0, 0, 0, 'veh-1')",
            )
            execSQL(
                "INSERT INTO fuel_entries (id, vehicleId, dateMillis, odometerKm, volumeL, " +
                    "totalCostMinor, currency, isFullTank) VALUES " +
                    "(1, 1, 100, 1000.0, 40.0, 5000, 'USD', 1), " +
                    "(2, 1, 200, 1200.0, 38.0, 4800, 'USD', 1)",
            )
            execSQL(
                "INSERT INTO charge_entries (id, vehicleId, dateMillis, energyKwh, " +
                    "totalCostMinor, currency, chargeType) VALUES " +
                    "(1, 1, 300, 50.0, 2000, 'USD', 'DC')",
            )
            execSQL(
                "INSERT INTO expenses (id, vehicleId, dateMillis, category, totalCostMinor, " +
                    "currency, recurrence) VALUES (1, 1, 400, 'SERVICE', 9000, 'USD', 'NONE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, *AppDatabase.ALL_MIGRATIONS)

        assertDistinctNonEmpty(db, "fuel_entries", expected = 2)
        assertDistinctNonEmpty(db, "charge_entries", expected = 1)
        assertDistinctNonEmpty(db, "expenses", expected = 1)
    }

    @Test
    fun migrate2To3_addsTelemetryTables() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrate3To4_backfillsDistinctSyncIds() {
        // Seed two vehicles and two trips at v3 so the backfill has rows to populate.
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO vehicles (id, name, powertrain, unitSystem, currency, " +
                    "initialOdometerKm, isArchived, createdAt) VALUES " +
                    "(1, 'A', 'ICE', 'METRIC', 'USD', 0, 0, 0), " +
                    "(2, 'B', 'ICE', 'METRIC', 'USD', 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO trips (id, vehicleId, startedAtMillis, status, " +
                    "obdSampleCount, gpsPointCount) VALUES " +
                    "(1, 1, 100, 'COMPLETED', 0, 0), (2, 1, 200, 'COMPLETED', 0, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *AppDatabase.ALL_MIGRATIONS)

        assertDistinctNonEmpty(db, "vehicles", expected = 2)
        assertDistinctNonEmpty(db, "trips", expected = 2)
    }

    private fun assertDistinctNonEmpty(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        expected: Int,
    ) {
        db.query(
            "SELECT COUNT(*), COUNT(DISTINCT syncId), MIN(length(syncId)) FROM $table",
        ).use { c ->
            c.moveToFirst()
            val total = c.getInt(0)
            val distinct = c.getInt(1)
            val minLen = c.getInt(2)
            assert(total == expected) { "$table: expected $expected rows, got $total" }
            assert(distinct == expected) { "$table: syncIds not distinct ($distinct/$total)" }
            assert(minLen == 36) { "$table: syncId not a 36-char UUID (min length $minLen)" }
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
