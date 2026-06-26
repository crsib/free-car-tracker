package me.crsib.freecartracker.data.webdav

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Fires [onAvailable] whenever the device regains a default network, so a sync requested while
 * offline (a trip finished in a car with no signal, a ledger edit on the move) gets pushed the
 * moment we're back online instead of waiting for the next app launch.
 *
 * This complements the WorkManager `CONNECTED` constraint rather than replacing it: that constraint
 * is the only thing currently waiting for the network, and OEM/head-unit power management can drop a
 * constraint-blocked job when the app is force-stopped on power-off. An in-process callback covers
 * the "network came back while the app is alive" case directly. App-scoped; never unregistered.
 */
class ConnectivityWatcher(
    context: Context,
    private val onAvailable: () -> Unit,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    /** Idempotent enough for app start: a failure here must not crash startup. */
    fun start() {
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
    }
}
