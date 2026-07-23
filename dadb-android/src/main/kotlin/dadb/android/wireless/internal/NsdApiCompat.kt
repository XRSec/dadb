package dadb.android.wireless.internal

import android.annotation.SuppressLint
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.Executor

/** Keeps API 34 NSD callback types out of the API 21-compatible monitor implementation. */
@SuppressLint("NewApi")
internal class NsdServiceInfoCallbackRegistration(
    private val nsdManager: NsdManager,
    private val serviceInfo: NsdServiceInfo,
    private val executor: Executor,
    registrationFailedHandler: (Int) -> Unit,
    serviceUpdatedHandler: (NsdServiceInfo) -> Unit,
    serviceLostHandler: () -> Unit,
    unregisteredHandler: () -> Unit,
) {
    private val callback =
        AndroidServiceInfoCallback(
            registrationFailedHandler = registrationFailedHandler,
            serviceUpdatedHandler = serviceUpdatedHandler,
            serviceLostHandler = serviceLostHandler,
            unregisteredHandler = unregisteredHandler,
        )

    fun register() {
        nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
    }

    fun unregister() {
        nsdManager.unregisterServiceInfoCallback(callback)
    }
}

@SuppressLint("NewApi")
internal class AndroidServiceInfoCallback(
    private val registrationFailedHandler: (Int) -> Unit,
    private val serviceUpdatedHandler: (NsdServiceInfo) -> Unit,
    private val serviceLostHandler: () -> Unit,
    private val unregisteredHandler: () -> Unit,
) : NsdManager.ServiceInfoCallback {
    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
        registrationFailedHandler(errorCode)
    }

    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
        serviceUpdatedHandler(serviceInfo)
    }

    override fun onServiceLost() {
        serviceLostHandler()
    }

    override fun onServiceInfoCallbackUnregistered() {
        unregisteredHandler()
    }
}
