package dadb.android.wireless.internal

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dadb.android.wireless.AdbMdnsServiceType
import dadb.android.wireless.AdbMdnsStatus

internal class AndroidDiscoveryListener(
    private val serviceType: AdbMdnsServiceType,
    private val onStatusChanged: (AdbMdnsStatus) -> Unit,
    private val onServiceFound: (NsdServiceInfo, AdbMdnsServiceType) -> Unit,
    private val onServiceLost: (NsdServiceInfo, AdbMdnsServiceType) -> Unit,
) : NsdManager.DiscoveryListener {
    override fun onDiscoveryStarted(serviceType: String) {
        onStatusChanged(AdbMdnsStatus.STARTED)
    }

    override fun onStartDiscoveryFailed(
        serviceType: String,
        errorCode: Int,
    ) {
        onStatusChanged(AdbMdnsStatus.FAILED)
    }

    override fun onDiscoveryStopped(serviceType: String) {
        onStatusChanged(AdbMdnsStatus.STOPPED)
    }

    override fun onStopDiscoveryFailed(
        serviceType: String,
        errorCode: Int,
    ) {
        onStatusChanged(AdbMdnsStatus.FAILED)
    }

    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        val foundType = parseAdbMdnsServiceType(serviceInfo.serviceType)
        if (foundType != serviceType) return
        onServiceFound(serviceInfo, serviceType)
    }

    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        val lostType = parseAdbMdnsServiceType(serviceInfo.serviceType)
        if (lostType != null && lostType != serviceType) return
        onServiceLost(serviceInfo, serviceType)
    }
}
