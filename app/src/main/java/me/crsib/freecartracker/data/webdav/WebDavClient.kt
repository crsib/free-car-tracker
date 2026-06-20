package me.crsib.freecartracker.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Thin WebDAV client over OkHttp: PUT to upload, GET to download, PROPFIND to test. */
class WebDavClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json".toMediaType()

    private fun fileUrl(config: WebDavConfig): String {
        val base = if (config.url.endsWith("/")) config.url else config.url + "/"
        return base + WebDavConfig.BACKUP_FILENAME
    }

    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(if (config.url.endsWith("/")) config.url else config.url + "/")
                .method("PROPFIND", null)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { resp ->
                check(resp.isSuccessful || resp.code == 207) {
                    "Server returned ${resp.code}"
                }
            }
        }
    }

    suspend fun upload(config: WebDavConfig, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(fileUrl(config))
                .put(content.toByteArray().toRequestBody(jsonMedia))
                .header("Authorization", Credentials.basic(config.username, config.password))
                .build()
            client.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "Upload failed: ${resp.code}" }
            }
        }
    }

    suspend fun download(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(fileUrl(config))
                .get()
                .header("Authorization", Credentials.basic(config.username, config.password))
                .build()
            client.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "Download failed: ${resp.code}" }
                resp.body?.string() ?: error("Empty response")
            }
        }
    }
}
