package dadb.android.wireless.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

internal class AndroidResolveListener(
    private val serviceResolvedHandler: (NsdServiceInfo) -> Unit,
    private val resolveFailedHandler: (Int) -> Unit,
) : NsdManager.ResolveListener {
    override fun onResolveFailed(
        serviceInfo: NsdServiceInfo,
        errorCode: Int,
    ) {
        resolveFailedHandler(errorCode)
    }

    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        serviceResolvedHandler(serviceInfo)
    }
}
