package dadb.android.wireless.internal

import dadb.android.wireless.AdbMdnsConfig
import dadb.android.wireless.AdbMdnsService
import dadb.android.wireless.AdbMdnsServiceType
import dadb.android.wireless.AdbMdnsState
import dadb.android.wireless.AdbMdnsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class AdbMdnsRegistry(
    private val config: AdbMdnsConfig,
) {
    private val services = linkedMapOf<AdbMdnsServiceKey, AdbMdnsService>()
    private val mutableState = MutableStateFlow(AdbMdnsState())

    val state: StateFlow<AdbMdnsState> = mutableState

    fun starting() {
        services.clear()
        mutableState.value = AdbMdnsState(status = AdbMdnsStatus.STARTING, loading = true)
    }

    fun started() {
        emit(status = AdbMdnsStatus.STARTED)
    }

    fun failed() {
        mutableState.value = mutableState.value.copy(status = AdbMdnsStatus.FAILED, loading = false)
    }

    fun stopped() {
        services.clear()
        mutableState.value = AdbMdnsState(status = AdbMdnsStatus.STOPPED, loading = false)
    }

    fun upsert(service: AdbMdnsService) {
        if (service.serviceType !in config.serviceTypes) return
        if (service.name.isBlank()) return
        if (service.host.isBlank()) return
        if (service.port !in 1..65535) return

        services[AdbMdnsServiceKey(service.name, service.serviceType)] = service
        emit(status = currentActiveStatus())
    }

    fun remove(
        name: String,
        serviceType: AdbMdnsServiceType,
    ) {
        services.remove(AdbMdnsServiceKey(name, serviceType))
        emit(status = currentActiveStatus())
    }

    private fun currentActiveStatus(): AdbMdnsStatus =
        if (mutableState.value.status == AdbMdnsStatus.STOPPED) {
            AdbMdnsStatus.STOPPED
        } else {
            AdbMdnsStatus.STARTED
        }

    private fun emit(status: AdbMdnsStatus) {
        val values =
            services.values.sortedWith(
                compareBy(AdbMdnsService::name, AdbMdnsService::host, AdbMdnsService::port),
            )
        val connectServices =
            values.filter {
                it.serviceType == AdbMdnsServiceType.ADB || it.serviceType == AdbMdnsServiceType.TLS_CONNECT
            }
        val pairingServices = values.filter { it.serviceType == AdbMdnsServiceType.TLS_PAIRING }
        mutableState.value =
            AdbMdnsState(
                status = status,
                loading = status != AdbMdnsStatus.STOPPED && status != AdbMdnsStatus.FAILED && values.isEmpty(),
                connectServices = connectServices,
                pairingServices = pairingServices,
            )
    }
}
