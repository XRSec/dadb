package dadb.android.wireless

import dadb.android.wireless.internal.AdbMdnsRegistry
import dadb.android.wireless.internal.parseAdbMdnsServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdbMdnsRegistryTest {
    @Test
    fun registry_groupsConnectAndPairingServices() {
        val registry = AdbMdnsRegistry(AdbMdnsConfig())

        registry.starting()
        registry.started()
        registry.upsert(AdbMdnsService("Pixel connect", "192.168.1.20", 37123, AdbMdnsServiceType.TLS_CONNECT))
        registry.upsert(AdbMdnsService("Pixel pair", "192.168.1.20", 43011, AdbMdnsServiceType.TLS_PAIRING))

        val state = registry.state.value
        assertEquals(AdbMdnsStatus.STARTED, state.status)
        assertFalse(state.loading)
        assertEquals(listOf("Pixel connect"), state.connectServices.map { it.name })
        assertEquals(listOf("Pixel pair"), state.pairingServices.map { it.name })
    }

    @Test
    fun registry_removesServiceByNameAndType() {
        val registry = AdbMdnsRegistry(AdbMdnsConfig())

        registry.starting()
        registry.upsert(AdbMdnsService("Pixel pair", "192.168.1.20", 43011, AdbMdnsServiceType.TLS_PAIRING))
        registry.remove("Pixel pair", AdbMdnsServiceType.TLS_PAIRING)

        assertEquals(emptyList<AdbMdnsService>(), registry.state.value.allServices)
        assertEquals(AdbMdnsStatus.STARTED, registry.state.value.status)
    }

    @Test
    fun registry_ignoresDisabledServiceTypes() {
        val registry = AdbMdnsRegistry(AdbMdnsConfig(serviceTypes = setOf(AdbMdnsServiceType.TLS_PAIRING)))

        registry.starting()
        registry.upsert(AdbMdnsService("Pixel connect", "192.168.1.20", 37123, AdbMdnsServiceType.TLS_CONNECT))

        assertEquals(emptyList<AdbMdnsService>(), registry.state.value.allServices)
    }

    @Test
    fun serviceTypeParser_acceptsLocalSuffixAndTrailingDot() {
        assertEquals(AdbMdnsServiceType.ADB, parseAdbMdnsServiceType("_adb._tcp"))
        assertEquals(AdbMdnsServiceType.ADB, parseAdbMdnsServiceType("_adb._tcp.local"))
        assertEquals(AdbMdnsServiceType.TLS_CONNECT, parseAdbMdnsServiceType("_adb-tls-connect._tcp.local."))
        assertEquals(AdbMdnsServiceType.TLS_PAIRING, parseAdbMdnsServiceType("_adb-tls-pairing._tcp"))
    }
}
