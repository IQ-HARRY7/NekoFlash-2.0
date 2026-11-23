package ru.forum.adbfastboottool

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.ArrayDeque

/**
 * Foreground-only DNS-SD discovery; never opens an ADB connection on its own.
 * Resolves one NSD service at a time because legacy Android NSD implementations
 * can reject concurrent resolveService() calls.
 */
internal class AdbWifiDiscovery(
    context: Context,
    private val onChanged: (List<Endpoint>, Boolean) -> Unit
) : Closeable {
    enum class Kind(val serviceType: String) {
        CONNECT("_adb-tls-connect._tcp."),
        PAIR("_adb-tls-pairing._tcp.")
    }

    data class Endpoint(val name: String, val host: String, val port: Int, val kind: Kind) {
        val key: String get() = "${kind.name}:$name"
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("NekoFlash-ADB-discovery").apply { setReferenceCounted(false) }
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val seen = mutableSetOf<String>()
    private val endpoints = linkedMapOf<String, Endpoint>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, Kind>>()
    private var resolving = false
    private var running = false
    private var hadFailure = false

    fun start() {
        if (running) return
        running = true
        hadFailure = false
        runCatching { lock.acquire() }.onFailure { hadFailure = true }
        Kind.entries.forEach(::discover)
        publish()
    }

    private fun discover(kind: Kind) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                main.post {
                    if (!running) runCatching { manager.stopServiceDiscovery(this) }
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    hadFailure = true
                    runCatching { manager.stopServiceDiscovery(this) }
                    publish()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (!running) return@post
                    val key = "${kind.name}:${serviceInfo.serviceName}"
                    seen.add(key)
                    pending.addLast(serviceInfo to kind)
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    val key = "${kind.name}:${serviceInfo.serviceName}"
                    seen.remove(key)
                    endpoints.remove(key)
                    publish()
                }
            }
        }
        listeners.add(listener)
        runCatching {
            manager.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            hadFailure = true
            publish()
        }
    }

    private fun resolveNext() {
        if (!running || resolving || pending.isEmpty()) return
        resolving = true
        val (service, kind) = pending.removeFirst()
        val key = "${kind.name}:${service.serviceName}"
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val host = serviceInfo.host?.hostAddress
                    if (running && key in seen && !host.isNullOrBlank() && serviceInfo.port in 1..65535) {
                        endpoints[key] = Endpoint(service.serviceName, host, serviceInfo.port, kind)
                        publish()
                    }
                    resolveNext()
                }
            }
        }
        runCatching { manager.resolveService(service, callback) }.onFailure {
            resolving = false
            resolveNext()
        }
    }

    private fun publish() {
        if (running) onChanged(endpoints.values.sortedWith(
            compareBy<Endpoint> { it.kind != Kind.PAIR }.thenBy { it.name }
        ), hadFailure)
    }

    override fun close() {
        running = false
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
        pending.clear()
        endpoints.clear()
        runCatching { if (lock.isHeld) lock.release() }
    }
}

/** AOSP mDNS names contain the paired device GUID; never auto-connect to a different device. */
internal object AdbWifiEndpointMatch {
    fun isPairMate(serviceName: String, pairedGuid: String): Boolean {
        val guid = pairedGuid.trim().removePrefix("adb-")
        return guid.isNotEmpty() &&
            serviceName.startsWith("adb-$guid-", ignoreCase = true)
    }
}
