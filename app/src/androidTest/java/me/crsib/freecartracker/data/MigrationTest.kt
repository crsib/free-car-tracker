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
        // Validate the full chain (1 -> 2 -> 3 -> 4) against the exported v4 schema.
        helper.runMigrationsAndValidate(TEST_DB, 4, true, *AppDatabase.ALL_MIGRATIONS)
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
