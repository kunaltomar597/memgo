package com.remu.app.network.hub

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finds the hub on the shop WiFi through mDNS (`_remu-hub._tcp`, docs/ARCHITECTURE.md section 9).
 * Emits "host:port" strings; the pairing screen pre-fills the hub address from the first one.
 */
class HubDiscovery(private val context: Context) {
    @Suppress("DEPRECATION")
    fun find(): Flow<String> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { close(); return@callbackFlow }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        trySend("$host:${info.port}")
                    }
                })
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            close()
        }
        awaitClose { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { } }
    }

    companion object { const val SERVICE_TYPE = "_remu-hub._tcp." }
}
