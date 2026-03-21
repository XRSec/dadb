package dadb.android.wireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dadb.android.wireless.internal.AdbMdnsRegistry
import dadb.android.wireless.internal.AdbMdnsServiceKey
import dadb.android.wireless.internal.AndroidDiscoveryListener
import dadb.android.wireless.internal.AndroidResolveListener
import dadb.android.wireless.internal.AndroidServiceInfoCallback
import dadb.android.wireless.internal.toAdbMdnsService
import dadb.android.wireless.internal.toAdbMdnsServiceKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow

internal class AndroidAdbMdnsMonitor(
    context: Context,
    private val config: AdbMdnsConfig,
) : AdbMdnsMonitor {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val registry = AdbMdnsRegistry(config)
    private val callbackExecutor: ExecutorService = newMdnsCallbackExecutor()
    private val callbacks = mutableMapOf<AdbMdnsServiceKey, NsdManager.ServiceInfoCallback>()
    private val lock = Any()
    private var started = false
    private var closed = false

    private val discoveryListeners =
        config.serviceTypes.associateWith { serviceType ->
            AndroidDiscoveryListener(
                serviceType = serviceType,
                onStatusChanged = ::onStatusChanged,
                onServiceFound = ::onServiceFound,
                onServiceLost = ::onServiceLost,
            )
        }

    override val state: StateFlow<AdbMdnsState> = registry.state

    override fun start() {
        val listeners =
            synchronized(lock) {
                if (closed || started) return
                started = true
                callbacks.clear()
                registry.starting()
                discoveryListeners.toList()
            }

        if (listeners.isEmpty()) {
            onStatusChanged(AdbMdnsStatus.STARTED)
            return
        }

        listeners.forEach { (serviceType, listener) ->
            runCatching {
                nsdManager.discoverServices(
                    serviceType.dnsType,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }.onFailure {
                onStatusChanged(AdbMdnsStatus.FAILED)
            }
        }
    }

    override fun stop() {
        val listeners =
            synchronized(lock) {
                if (!started) return
                started = false
                discoveryListeners.values.toList()
            }

        listeners.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        clearServiceInfoCallbacks()

        synchronized(lock) {
            callbacks.clear()
            registry.stopped()
        }
    }

    override fun close() {
        stop()
        val shouldShutdown =
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
        if (shouldShutdown) {
            callbackExecutor.shutdownNow()
        }
    }

    private fun onStatusChanged(status: AdbMdnsStatus) {
        synchronized(lock) {
            when (status) {
                AdbMdnsStatus.STARTING -> if (started) registry.starting()
                AdbMdnsStatus.STARTED -> if (started) registry.started()
                AdbMdnsStatus.STOPPED -> if (!started) registry.stopped()
                AdbMdnsStatus.FAILED -> registry.failed()
            }
        }
    }

    private fun onServiceFound(
        serviceInfo: NsdServiceInfo,
        serviceType: AdbMdnsServiceType,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerServiceInfoCallback(serviceInfo, serviceType)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                nsdManager.resolveService(
                    serviceInfo,
                    AndroidResolveListener(
                        serviceResolvedHandler = { resolvedInfo -> upsertService(resolvedInfo, serviceType) },
                        resolveFailedHandler = { onStatusChanged(AdbMdnsStatus.FAILED) },
                    ),
                )
            }.onFailure {
                onStatusChanged(AdbMdnsStatus.FAILED)
            }
        }
    }

    private fun onServiceLost(
        serviceInfo: NsdServiceInfo,
        serviceType: AdbMdnsServiceType,
    ) {
        val key = serviceInfo.toAdbMdnsServiceKey(fallbackServiceType = serviceType) ?: return
        unregisterServiceInfoCallback(key)
        synchronized(lock) {
            if (started) {
                registry.remove(name = key.name, serviceType = key.serviceType)
            }
        }
    }

    private fun upsertService(
        serviceInfo: NsdServiceInfo,
        fallbackServiceType: AdbMdnsServiceType,
    ) {
        val service =
            serviceInfo.toAdbMdnsService(
                config = config,
                fallbackServiceType = fallbackServiceType,
            ) ?: return
        synchronized(lock) {
            if (started) {
                registry.upsert(service)
            }
        }
    }

    private fun registerServiceInfoCallback(
        serviceInfo: NsdServiceInfo,
        serviceType: AdbMdnsServiceType,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val key = serviceInfo.toAdbMdnsServiceKey(fallbackServiceType = serviceType) ?: return
        val callback =
            AndroidServiceInfoCallback(
                registrationFailedHandler = { onStatusChanged(AdbMdnsStatus.FAILED) },
                serviceUpdatedHandler = { updatedInfo -> upsertService(updatedInfo, serviceType) },
                serviceLostHandler = { removeServiceByKey(key) },
                unregisteredHandler = {
                    synchronized(lock) {
                        callbacks.remove(key)
                    }
                },
            )

        val shouldRegister =
            synchronized(lock) {
                if (!started || closed || callbacks.containsKey(key)) {
                    false
                } else {
                    callbacks[key] = callback
                    true
                }
            }
        if (!shouldRegister) return

        runCatching {
            nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
        }.onFailure {
            synchronized(lock) {
                callbacks.remove(key, callback)
            }
            onStatusChanged(AdbMdnsStatus.FAILED)
        }
    }

    private fun removeServiceByKey(key: AdbMdnsServiceKey) {
        synchronized(lock) {
            if (started) {
                registry.remove(name = key.name, serviceType = key.serviceType)
            }
        }
        unregisterServiceInfoCallback(key)
    }

    private fun unregisterServiceInfoCallback(key: AdbMdnsServiceKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = synchronized(lock) { callbacks.remove(key) } ?: return
        runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
    }

    private fun clearServiceInfoCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val currentCallbacks =
            synchronized(lock) {
                callbacks.values.toList().also { callbacks.clear() }
            }
        currentCallbacks.forEach { callback ->
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
        }
    }
}

/**
 * NsdManager may enqueue an unregister callback after unregisterServiceInfoCallback() returns.
 * Once the monitor is closed those late callbacks have no work left, so discard them instead of
 * throwing RejectedExecutionException on Android's ConnectivityThread.
 */
internal fun newMdnsCallbackExecutor(): ExecutorService =
    ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.DiscardPolicy(),
    )
