package me.crsib.freecartracker.telemetry.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.crsib.freecartracker.CarTrackerApp
import me.crsib.freecartracker.MainActivity
import me.crsib.freecartracker.R
import me.crsib.freecartracker.telemetry.DiagLog

/**
 * Foreground service that keeps the process alive (and shows the ongoing notification) while
 * [TripRecorder] does the actual OBD + GPS recording. It holds no recording state itself.
 */
class TelemetryService : Service() {

    private val container get() = (application as CarTrackerApp).container

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selfStopJob: Job? = null
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                container.tripRecorder.stop()
                teardownNotification()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // START_STICKY redelivers a null intent after a process kill. We deliberately
                // don't resume recording (recoverIncompleteTrips() finalizes the orphan trip),
                // so without a vehicle to record there's nothing to do — stop rather than promote
                // to a do-nothing "Recording trip" foreground notification.
                val vehicleId = intent?.getLongExtra(EXTRA_VEHICLE_ID, -1L) ?: -1L
                if (vehicleId < 0) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Promote to foreground with only the service types we actually hold permission
                // for (Android 14 throws if a declared type's permission is missing). If even that
                // is rejected, bail out gracefully rather than crashing the app.
                if (!startForegroundSafely()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                container.tripRecorder.start(vehicleId, container.telemetrySettings.loadConnection())
                observeForSelfStop()
                observeForNotification()
                return START_STICKY
            }
        }
    }

    /**
     * Stops the service when the recorder returns to IDLE on its own — i.e. the auto-end watchdog
     * finalized the trip. Waits for recording to actually begin first, so the initial IDLE before
     * CONNECTING doesn't trip it. A manual Stop goes through ACTION_STOP and tears down directly;
     * this collector just falls away with the cancelled scope.
     */
    private fun observeForSelfStop() {
        selfStopJob?.cancel()
        selfStopJob = serviceScope.launch {
            container.tripRecorder.recordingState
                .dropWhile { it == RecordingState.IDLE }
                .first { it == RecordingState.IDLE }
            teardownNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Stop the 1 Hz re-render loop *before* [stopForeground] removes the notification. Otherwise the
     * loop can fire one more [NotificationManager.notify] between removal and [onDestroy] cancelling
     * the scope, re-posting an ongoing notification with no foreground service behind it — which the
     * user can neither swipe away (it's ongoing) nor stop (the Stop action has no service to reach).
     */
    private fun teardownNotification() {
        notificationJob?.cancel()
        notificationJob = null
    }

    private fun startForegroundSafely(): Boolean {
        val fullType = foregroundServiceType()
        if (startForeground(fullType)) return true

        // The most common rejection on a cold companion wake-up is the LOCATION type: starting a
        // location FGS from the background needs ACCESS_BACKGROUND_LOCATION, which the user may not
        // have granted ("While using the app" only). Rather than abandon the trip, retry without
        // location so OBD still records — GPS simply won't, which is expected without that grant.
        val withoutLocation = fullType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION.inv()
        if (withoutLocation != fullType && withoutLocation != 0 && startForeground(withoutLocation)) {
            return true
        }
        return false
    }

    private fun startForeground(type: Int): Boolean = runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        DiagLog.i(TAG, "startForeground ok (type=$type)")
    }.onFailure {
        // A cold-start from the background that the OS rejects lands here (e.g.
        // ForegroundServiceStartNotAllowedException). Surface it instead of silently bailing.
        DiagLog.w(TAG, "startForeground denied (type=$type): ${it.javaClass.simpleName}: ${it.message}")
    }.isSuccess

    /** OR of the manifest-declared FGS types whose backing runtime permission is currently held. */
    private fun foregroundServiceType(): Int {
        var type = 0
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return type
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Re-renders the ongoing notification once a second with live trip info (status, elapsed time,
     * distance, current speed) pulled from [TripRecorder]. Bounded to ~1 Hz rather than collecting
     * [TripRecorder.liveState] directly, which churns on every OBD/GPS reading.
     */
    private fun observeForNotification() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                nm.notify(NOTIFICATION_ID, buildNotification())
                delay(1000)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TelemetryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle())
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.telemetry_stop), stopIntent)
            .build()
    }

    /** Title reflects the connection phase so the user can tell connecting/reconnecting from live. */
    private fun notificationTitle(): String = when (container.tripRecorder.recordingState.value) {
        RecordingState.CONNECTING -> getString(R.string.telemetry_status_connecting)
        RecordingState.RECONNECTING -> getString(R.string.telemetry_status_reconnecting)
        else -> getString(R.string.telemetry_recording_title)
    }

    /** "12:34 · 8.3 km · 52 km/h" — elapsed always shown; distance/speed appear once they're live. */
    private fun notificationText(): String {
        val live = container.tripRecorder.liveState.value
        val parts = mutableListOf(formatElapsed(live.elapsedSec))
        if (live.distanceKm >= 0.05) parts += "%.1f km".format(live.distanceKm)
        val speed = live.speedKmh ?: live.gpsSpeedKmh
        if (speed != null && speed >= 0) parts += "%.0f km/h".format(speed)
        return parts.joinToString(" · ")
    }

    private fun formatElapsed(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.telemetry_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.telemetry_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ObdAutoStart"
        private const val CHANNEL_ID = "telemetry_recording"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "me.crsib.freecartracker.telemetry.STOP"
        private const val EXTRA_VEHICLE_ID = "vehicleId"

        /** Start recording for [vehicleId] using the saved connection config. */
        fun start(context: Context, vehicleId: Long) {
            val intent = Intent(context, TelemetryService::class.java)
                .putExtra(EXTRA_VEHICLE_ID, vehicleId)
            // From a cold companion wake-up this runs in the background; if the OS refuses the
            // background service start (BackgroundServiceStartNotAllowedException) it throws here,
            // so log rather than let it crash the companion service.
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { DiagLog.w(TAG, "startForegroundService denied: ${it.javaClass.simpleName}: ${it.message}") }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
