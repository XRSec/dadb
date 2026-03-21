package dadb.android.wireless.internal

import dadb.android.wireless.AdbMdnsServiceType

internal data class AdbMdnsServiceKey(
    val name: String,
    val serviceType: AdbMdnsServiceType,
)
