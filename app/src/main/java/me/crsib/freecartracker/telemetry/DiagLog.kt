package me.crsib.freecartracker.telemetry

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Telemetry diagnostics that are both logged to logcat AND appended to a rotating file under the
 * app's files dir, so a "stuck session" can be diagnosed after the fact — logcat is wiped on reboot
 * and usually unreachable on a head unit. [init] once at app start; calls before that only hit
 * logcat. Thread-safe. The file is bounded by rotating to a single `.1` backup.
 */
object DiagLog {
    private const val MAX_BYTES = 512L * 1024
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var file: File? = null

    fun init(dir: File) {
        runCatching {
            dir.mkdirs()
            file = File(dir, "telemetry.log")
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText("${stamp.format(Date())} $level/$tag: $msg\n")
            }
        }
    }

    /** Keep one previous file so the log is bounded to ~2×[MAX_BYTES] on disk. */
    private fun rotate(f: File) {
        val backup = File(f.parentFile, f.name + ".1")
        if (backup.exists()) backup.delete()
        f.renameTo(backup)
    }
}
