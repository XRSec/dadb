package dadb.android.wireless.internal

import android.net.nsd.NsdServiceInfo
import android.os.Build
import dadb.android.wireless.AdbMdnsConfig
import dadb.android.wireless.AdbMdnsService
import dadb.android.wireless.AdbMdnsServiceType
import java.net.Inet4Address
import java.net.InetAddress

internal fun NsdServiceInfo.toAdbMdnsService(
    config: AdbMdnsConfig,
    fallbackServiceType: AdbMdnsServiceType,
): AdbMdnsService? {
    val key = toAdbMdnsServiceKey(fallbackServiceType) ?: return null
    val hostAddress = resolveHostAddress(config.preferIpv4) ?: return null
    val safePort = port.takeIf { it in 1..65535 } ?: return null
    return AdbMdnsService(
        name = key.name,
        host = hostAddress,
        port = safePort,
        serviceType = key.serviceType,
    )
}

internal fun NsdServiceInfo.toAdbMdnsServiceKey(
    fallbackServiceType: AdbMdnsServiceType,
): AdbMdnsServiceKey? {
    val name = serviceName?.trim().orEmpty()
    if (name.isBlank()) return null
    val parsedType = parseAdbMdnsServiceType(serviceType) ?: fallbackServiceType
    return AdbMdnsServiceKey(name = name, serviceType = parsedType)
}

private fun NsdServiceInfo.resolveHostAddress(preferIpv4: Boolean): String? {
    val address =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            selectAddress(hostAddresses, preferIpv4)
        } else {
            @Suppress("DEPRECATION")
            host
        }
    return address?.hostAddress?.takeIf { it.isNotBlank() }
}

private fun selectAddress(
    addresses: List<InetAddress>,
    preferIpv4: Boolean,
): InetAddress? =
    if (preferIpv4) {
        addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
    } else {
        addresses.firstOrNull()
    }
