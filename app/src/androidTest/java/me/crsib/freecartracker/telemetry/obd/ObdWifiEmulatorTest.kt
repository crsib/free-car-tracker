package me.crsib.freecartracker.telemetry.obd

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.crsib.freecartracker.telemetry.obd.pid.StandardPidProfile
import me.crsib.freecartracker.telemetry.obd.transport.WifiTransport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end OBD read against the Ircama ELM327-emulator running on the dev host.
 * Start it first: `python -m elm -n 35000 -s car`. From the Android emulator the host is
 * reachable at 10.0.2.2. Skips (does not fail) when the emulator is not reachable.
 */
@RunWith(AndroidJUnit4::class)
class ObdWifiEmulatorTest {

    @Test
    fun readsLivePidsThroughWifiTransport() = runBlocking {
        val transport = WifiTransport(HOST, PORT, connectTimeoutMs = 3000)
        try {
            withTimeout(5000) { transport.connect() }
        } catch (e: Exception) {
            assumeNoException("ELM327 emulator not reachable at $HOST:$PORT", e)
            return@runBlocking
        }

        try {
            val session = ObdSession(transport)
            session.initialize()

            val rpm = session.query(StandardPidProfile.pids.first { it.key == "rpm" })
            val speed = session.query(StandardPidProfile.pids.first { it.key == "speedKmh" })
            val coolant = session.query(StandardPidProfile.pids.first { it.key == "coolantTempC" })

            assertNotNull("RPM should decode", rpm)
            assertNotNull("Speed should decode", speed)
            assertNotNull("Coolant temp should decode", coolant)
            assertTrue("RPM within plausible range", rpm!!.value in 0.0..8000.0)
            assertTrue("Speed within plausible range", speed!!.value in 0.0..300.0)

            // Supported-PID discovery should parse a plausible set of 2-hex codes. We don't
            // assert specific PIDs because the emulator's support bitmask and its per-PID
            // answers aren't fully consistent (parseBitmask is covered by unit tests).
            val supported = session.discoverSupportedPids()
            assertTrue("Should discover some supported PIDs, got: $supported", supported.isNotEmpty())
            assertTrue(
                "All discovered codes are 2-hex, got: $supported",
                supported.all { it.length == 2 && it.all { c -> c.isDigit() || c in 'A'..'F' } },
            )
        } finally {
            transport.close()
        }
    }

    companion object {
        private const val HOST = "10.0.2.2"
        private const val PORT = 35000
    }
}
