package dadb.android.wireless.internal

import dadb.android.wireless.AdbMdnsServiceType

internal fun parseAdbMdnsServiceType(type: String?): AdbMdnsServiceType? {
    val normalized =
        type
            ?.trim()
            ?.trimEnd('.')
            ?.removeSuffix(".local")
            ?: return null
    return AdbMdnsServiceType.entries.firstOrNull { it.dnsType == normalized }
}
