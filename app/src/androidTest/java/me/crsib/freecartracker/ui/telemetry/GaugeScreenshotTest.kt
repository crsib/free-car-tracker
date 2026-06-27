package me.crsib.freecartracker.ui.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the real [Gauge] with deliberately wide readouts and dumps a PNG so the
 * "RPM doesn't fit" fix can be verified visually. Not an assertion test — it exists
 * to capture an emulator screenshot of the gauges at their worst-case widths.
 */
class GaugeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureWideGauges() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    Column(
                        modifier = Modifier.width(380.dp).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Gauge("Speed", 94.0, 200.0, "km/h", Modifier.weight(1f))
                            Gauge("RPM", 2145.0, 8000.0, "rpm", Modifier.weight(1f))
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Gauge("RPM", 8888.0, 8000.0, "rpm", Modifier.weight(1f))
                            Gauge("Odometer", 188_888.0, 200_000.0, "km", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "gauge_fit.png")
        FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
