package dadb.android.wireless.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

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
