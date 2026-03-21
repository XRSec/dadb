package dadb.android.wireless

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovers Wireless Debugging pairing/connect services advertised via Android NSD.
 */
object AdbMdnsDiscoverer {
    fun discoverConnectService(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        includeLanDevices: Boolean = true,
    ): AdbMdnsEndpoint? = discoverService(context, TLS_CONNECT, timeoutMs, includeLanDevices)

    fun discoverPairingService(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        includeLanDevices: Boolean = true,
    ): AdbMdnsEndpoint? = discoverService(context, TLS_PAIRING, timeoutMs, includeLanDevices)

    private fun discoverService(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
        includeLanDevices: Boolean,
    ): AdbMdnsEndpoint? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        require(timeoutMs >= 0) { "timeoutMs must be >= 0" }

        val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
        val resultPort = AtomicInteger(-1)
        val resultHost = AtomicReference<String?>(null)
        val discoveryFinished = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val discoveryListener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.v(TAG, "discovery started: $serviceType")
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "start discovery failed: $serviceType, error=$errorCode")
                    latch.countDown()
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.v(TAG, "discovery stopped: $serviceType")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "stop discovery failed: $serviceType, error=$errorCode")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (discoveryFinished.get()) return
                    Log.v(TAG, "service found: ${serviceInfo.serviceName}")
                    resolveService(
                        nsdManager = nsdManager,
                        serviceInfo = serviceInfo,
                        includeLanDevices = includeLanDevices,
                        discoveryFinished = discoveryFinished,
                        resultHost = resultHost,
                        resultPort = resultPort,
                        latch = latch,
                    )
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.v(TAG, "service lost: ${serviceInfo.serviceName}")
                }
            }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        discoveryFinished.set(true)
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }

        val port = resultPort.get()
        val host = resultHost.get()
        return if (port > 0 && !host.isNullOrBlank()) AdbMdnsEndpoint(host, port) else null
    }

    @Suppress("DEPRECATION")
    private fun resolveService(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        includeLanDevices: Boolean,
        discoveryFinished: AtomicBoolean,
        resultHost: AtomicReference<String?>,
        resultPort: AtomicInteger,
        latch: CountDownLatch,
    ) {
        val resolveListener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Log.v(TAG, "resolve failed: ${serviceInfo.serviceName}, error=$errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (discoveryFinished.get()) return
                    val hostAddress = resolvedHostAddress(serviceInfo)?.takeIf { it.isNotBlank() } ?: return

                    if (!includeLanDevices) {
                        if (!isLocalInterfaceAddress(hostAddress)) return
                        if (!isPortOpened(serviceInfo.port)) return
                    }

                    if (resultPort.compareAndSet(-1, serviceInfo.port)) {
                        resultHost.set(hostAddress)
                        discoveryFinished.set(true)
                        latch.countDown()
                    }
                }
            }

        runCatching {
            nsdManager.resolveService(serviceInfo, resolveListener)
        }.onFailure { error ->
            Log.w(TAG, "resolveService failed for ${serviceInfo.serviceName}", error)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun resolvedHostAddress(serviceInfo: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.firstOrNull()?.hostAddress
        } else {
            serviceInfo.host?.hostAddress
        }

    private fun isLocalInterfaceAddress(hostAddress: String): Boolean =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { networkInterface ->
                networkInterface.inetAddresses.asSequence().any { address ->
                    address.hostAddress == hostAddress
                }
            }
        }.getOrDefault(false)

    private fun isPortOpened(port: Int): Boolean =
        try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }

    private const val TAG = "AdbMdnsDiscoverer"
    private const val DEFAULT_TIMEOUT_MS = 12_000L
    private const val TLS_CONNECT = "_adb-tls-connect._tcp"
    private const val TLS_PAIRING = "_adb-tls-pairing._tcp"
}

data class AdbMdnsEndpoint(
    val host: String,
    val port: Int,
)
