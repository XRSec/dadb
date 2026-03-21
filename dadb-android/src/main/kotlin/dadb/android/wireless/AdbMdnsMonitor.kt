package dadb.android.wireless

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface AdbMdnsMonitor : AutoCloseable {
    val state: StateFlow<AdbMdnsState>

    fun start()

    fun stop()

    override fun close() = stop()
}

data class AdbMdnsConfig(
    val serviceTypes: Set<AdbMdnsServiceType> = AdbMdnsServiceType.entries.toSet(),
    val preferIpv4: Boolean = true,
)

data class AdbMdnsState(
    val status: AdbMdnsStatus = AdbMdnsStatus.STOPPED,
    val loading: Boolean = false,
    val connectServices: List<AdbMdnsService> = emptyList(),
    val pairingServices: List<AdbMdnsService> = emptyList(),
) {
    val allServices: List<AdbMdnsService>
        get() = connectServices + pairingServices
}

data class AdbMdnsService(
    val name: String,
    val host: String,
    val port: Int,
    val serviceType: AdbMdnsServiceType,
) {
    fun toEndpoint(): AdbMdnsEndpoint = AdbMdnsEndpoint(host, port)
}

enum class AdbMdnsServiceType(val dnsType: String) {
    ADB("_adb._tcp"),
    TLS_CONNECT("_adb-tls-connect._tcp"),
    TLS_PAIRING("_adb-tls-pairing._tcp"),
}

enum class AdbMdnsStatus {
    STOPPED,
    STARTING,
    STARTED,
    FAILED,
}

fun AdbMdnsMonitor(
    context: Context,
    config: AdbMdnsConfig = AdbMdnsConfig(),
): AdbMdnsMonitor = AndroidAdbMdnsMonitor(context, config)
