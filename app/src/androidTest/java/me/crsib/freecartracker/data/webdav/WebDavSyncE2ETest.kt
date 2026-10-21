package me.crsib.freecartracker.data.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [SyncE2ETestBase] against a real WebDAV server (wsgidav). Start it on the host:
 *   python -m wsgidav.server.server_cli --config wsgidav.yaml   (root rw for test:test, port 8080)
 */
@RunWith(AndroidJUnit4::class)
class WebDavSyncE2ETest : SyncE2ETestBase() {

    private val baseUrl = "http://10.0.2.2:8080/"

    override fun prepareRemote(runId: String): WebDavConfig {
        assumeTrue("wsgidav not reachable at $baseUrl", serverReachable())
        // Create the run's folder first: production assumes the configured root already exists,
        // as testConnection verifies.
        runBlocking {
            sharedWebDav.ensureCollection(
                WebDavConfig(url = baseUrl, username = "test", password = "test"),
                "$runId/",
            ).getOrThrow()
        }
        return WebDavConfig(url = "$baseUrl$runId/", username = "test", password = "test", autoBackup = true)
    }

    private fun serverReachable(): Boolean = try {
        val req = okhttp3.Request.Builder().url(baseUrl)
            .header("Authorization", okhttp3.Credentials.basic("test", "test"))
            .method("PROPFIND", null).header("Depth", "0").build()
        probe.newCall(req).execute().use { it.isSuccessful || it.code == 207 }
    } catch (e: IOException) {
        false
    }

    private companion object {
        /** Shared across test instances for the same reason as [SyncE2ETestBase.sharedWebDav]. */
        val probe: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    }
}
