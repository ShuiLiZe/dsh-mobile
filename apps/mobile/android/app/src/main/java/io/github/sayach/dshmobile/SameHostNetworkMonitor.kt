package io.github.sayach.dshmobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Foreground-only hints. Network availability is not proof that a trusted endpoint is reachable. */
internal class SameHostNetworkMonitor(context: Context, private val check: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val task = Runnable { if (active) check() }
    private val periodic = object : Runnable {
        override fun run() { if (active) { signal(); handler.postDelayed(this, 30_000) } }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = signal()
        override fun onLost(network: Network) = signal()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = signal()
    }
    /** Only an affirmative current Wi-Fi capability suppresses the proactive remote probe. */
    fun hasWifi(): Boolean = runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    private fun signal() { handler.post { if (active) { handler.removeCallbacks(task); handler.postDelayed(task, 750) } } }
    fun start() {
        if (active) return
        active = true
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        handler.post(periodic)
    }
    fun stop() {
        active = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        handler.removeCallbacksAndMessages(null)
    }
}
