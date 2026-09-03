package com.macropad.app.ai

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds a MacroPad daemon advertising itself on the local network.
 *
 * A home server sits on whatever address the router handed it, and that changes.
 * Rather than asking people to reserve a DHCP lease, the daemon advertises over mDNS
 * and the app re-resolves when the stored address stops answering.
 */
object LanDiscovery {

    /** What the daemon registers itself as. */
    const val SERVICE_TYPE = "_macropad._tcp."

    private const val TAG = "LanDiscovery"

    data class Found(val host: String, val port: Int, val secure: Boolean = true) {
        val baseUrl: String
            get() = "${if (secure) "https" else "http"}://$host:$port"
    }

    /**
     * Whether [url] points somewhere on this network.
     *
     * This is the guard that keeps rediscovery from doing damage. Someone running the
     * daemon behind their own domain name with a real certificate must never have that
     * address silently replaced by a LAN address just because their server was briefly
     * down — so only `.local` names and private ranges are ever reconsidered.
     */
    fun isLocalAddress(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()
            ?: return false
        if (host.endsWith(".local") || host == "localhost") return true

        // Every part must be numeric. Discarding the non-numeric ones would read
        // "10.0.0.1.attacker.net" as 10.0.0.1 and call an attacker's host private.
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false

        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }

    /**
     * Looks for the daemon, returning the first one that answers.
     *
     * Returns null on timeout, which is the ordinary outcome when you aren't home.
     */
    suspend fun discover(context: Context, timeoutMs: Long = 6_000): Found? =
        withTimeoutOrNull(timeoutMs) {
            val nsd = context.applicationContext
                .getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return@withTimeoutOrNull null

            suspendCancellableCoroutine { continuation ->
                val settled = AtomicBoolean(false)
                var listener: NsdManager.DiscoveryListener? = null

                fun finish(found: Found?) {
                    if (!settled.compareAndSet(false, true)) return
                    listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                    if (continuation.isActive) continuation.resumeWith(Result.success(found))
                }

                listener = discoveryListener(nsd, settled, ::finish)
                continuation.invokeOnCancellation {
                    if (settled.compareAndSet(false, true)) {
                        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                    }
                }

                try {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (e: Exception) {
                    Log.w(TAG, "could not start discovery: ${e.message}")
                    finish(null)
                }
            }
        }

    private fun discoveryListener(
        nsd: NsdManager,
        settled: AtomicBoolean,
        finish: (Found?) -> Unit
    ) = object : NsdManager.DiscoveryListener {

        override fun onServiceFound(service: NsdServiceInfo) {
            if (settled.get()) return
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress ?: return
                    @Suppress("DEPRECATION")
                    val port = resolved.port
                    // The daemon can say it is plain HTTP; absent the hint, assume the
                    // TLS the installer sets up.
                    val secure = resolved.attributes["scheme"]
                        ?.toString(Charsets.UTF_8)
                        ?.equals("http", ignoreCase = true) != true
                    finish(Found(host = host, port = port, secure = secure))
                }

                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed: $errorCode")
                    // Another advertisement may still resolve; keep waiting for the
                    // timeout rather than giving up on the first failure.
                }
            })
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceLost(service: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discovery failed to start: $errorCode")
            finish(null)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }
}
