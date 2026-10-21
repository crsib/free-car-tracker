package me.crsib.freecartracker.data.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * [SyncE2ETestBase] against a real S3-compatible server that checks signatures, e.g. RustFS on
 * the host with an existing bucket:
 *   docker run -d --rm --name fct-s3 -p 9000:9000 -e RUSTFS_ACCESS_KEY=fctadmin \
 *     -e RUSTFS_SECRET_KEY=fctsecret123 rustfs/rustfs:latest
 *   curl --aws-sigv4 "aws:amz:us-east-1:s3" --user fctadmin:fctsecret123 -X PUT http://localhost:9000/cars
 * Those values are the defaults; override with instrumentation args `s3Endpoint`, `s3Bucket`,
 * `s3AccessKey`, `s3SecretKey`. Each run syncs under its own key prefix.
 */
@RunWith(AndroidJUnit4::class)
class S3SyncE2ETest : SyncE2ETestBase() {

    override fun prepareRemote(runId: String): WebDavConfig {
        val args = InstrumentationRegistry.getArguments()
        val s3 = S3Config(
            endpoint = args.getString("s3Endpoint") ?: "http://10.0.2.2:9000",
            bucket = args.getString("s3Bucket") ?: "cars",
            accessKeyId = args.getString("s3AccessKey") ?: "fctadmin",
            secretAccessKey = args.getString("s3SecretKey") ?: "fctsecret123",
            prefix = runId,
        )
        val reachable = runBlocking { S3Storage(S3Client(), s3).testConnection() }
        assumeTrue("S3 not reachable at ${s3.endpoint}: ${reachable.exceptionOrNull()?.message}", reachable.isSuccess)
        return WebDavConfig(backend = SyncBackend.S3, s3 = s3, autoBackup = true)
    }
}
