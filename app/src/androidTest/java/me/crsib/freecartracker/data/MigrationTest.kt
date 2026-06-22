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
        // Validate the full chain (1 -> 2 -> 3) against the exported v3 schema.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrate2To3_addsTelemetryTables() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, *AppDatabase.ALL_MIGRATIONS)
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
