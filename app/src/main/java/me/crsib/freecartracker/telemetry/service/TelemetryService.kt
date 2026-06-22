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
import me.crsib.freecartracker.CarTrackerApp
import me.crsib.freecartracker.MainActivity
import me.crsib.freecartracker.R

/**
 * Foreground service that keeps the process alive (and shows the ongoing notification) while
 * [TripRecorder] does the actual OBD + GPS recording. It holds no recording state itself.
 */
class TelemetryService : Service() {

    private val container get() = (application as CarTrackerApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                container.tripRecorder.stop()
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
                return START_STICKY
            }
        }
    }

    private fun startForegroundSafely(): Boolean = runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundServiceType())
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
            .setContentTitle(getString(R.string.telemetry_recording_title))
            .setContentText(getString(R.string.telemetry_recording_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.telemetry_stop), stopIntent)
            .build()
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
        private const val CHANNEL_ID = "telemetry_recording"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "me.crsib.freecartracker.telemetry.STOP"
        private const val EXTRA_VEHICLE_ID = "vehicleId"

        /** Start recording for [vehicleId] using the saved connection config. */
        fun start(context: Context, vehicleId: Long) {
            val intent = Intent(context, TelemetryService::class.java)
                .putExtra(EXTRA_VEHICLE_ID, vehicleId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
