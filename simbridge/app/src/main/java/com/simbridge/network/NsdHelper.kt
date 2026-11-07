package com.simbridge.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "NsdHelper"
private const val SERVICE_TYPE = "_simbridge._tcp."
private const val SERVICE_NAME = "SimBridge"

/**
 * Wraps Android NSD (Network Service Discovery / mDNS) for zero-config
 * discovery when both phones are on the same WiFi network.
 *
 * HOST registers itself; CLIENT discovers and resolves HOST's IP + port.
 */
class NsdHelper(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    // NsdManager only allows one outstanding resolve at a time
    private var resolving = false

    // ---- HOST: advertise ----

    fun registerService(port: Int, onRegistered: (name: String) -> Unit) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.d(TAG, "NSD registered as ${si.serviceName}")
                onRegistered(si.serviceName)
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
                Log.e(TAG, "NSD registration failed: $err")
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {
                Log.d(TAG, "NSD unregistered")
            }
            override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {}
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            registrationListener = null
        }
    }

    // ---- CLIENT: discover ----

    data class HostInfo(val name: String, val host: String, val port: Int)

    fun discoverHosts(onFound: (HostInfo) -> Unit, onLost: (String) -> Unit) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "NSD discovery started")
            }
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceType == SERVICE_TYPE) {
                    Log.d(TAG, "Found service: ${si.serviceName}")
                    resolveService(si, onFound)
                }
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                Log.d(TAG, "Lost service: ${si.serviceName}")
                onLost(si.serviceName)
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) {
                Log.e(TAG, "Discovery start failed: $err")
            }
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
            discoveryListener = null
        }
    }

    private fun resolveService(si: NsdServiceInfo, onResolved: (HostInfo) -> Unit) {
        if (resolving) return
        resolving = true
        nsdManager.resolveService(si, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, err: Int) {
                Log.e(TAG, "Resolve failed: $err")
                resolving = false
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving = false
                val host = info.host?.hostAddress ?: return
                Log.d(TAG, "Resolved ${info.serviceName} → $host:${info.port}")
                onResolved(HostInfo(info.serviceName, host, info.port))
            }
        })
    }
}
