package dadb.android.wireless

import dadb.AdbKeyPair

data class WirelessDebugPairingRequest(
    val host: String,
    val port: Int,
    val pairingCode: String,
)

data class WirelessDebugPairingResult(
    val host: String,
    val port: Int,
    val peerAdbPublicKey: ByteArray,
    val pairingTlsPublicKeySha256Base64: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WirelessDebugPairingResult) return false

        return host == other.host &&
            port == other.port &&
            peerAdbPublicKey.contentEquals(other.peerAdbPublicKey) &&
            pairingTlsPublicKeySha256Base64 == other.pairingTlsPublicKeySha256Base64
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + peerAdbPublicKey.contentHashCode()
        result = 31 * result + (pairingTlsPublicKeySha256Base64?.hashCode() ?: 0)
        return result
    }
}

class WirelessDebugPairingClient(
    private val keyPair: AdbKeyPair,
) {
    fun pair(request: WirelessDebugPairingRequest): Result<WirelessDebugPairingResult> =
        runCatching {
            val session =
                DirectAdbPairingClient(
                    host = request.host,
                    port = request.port,
                    pairingCode = request.pairingCode,
                    key = AdbPairingKey(keyPair),
                ).use { client ->
                    client.start()
                } ?: throw IllegalStateException("Wireless Debugging pairing failed")

            WirelessDebugPairingResult(
                host = request.host,
                port = request.port,
                peerAdbPublicKey = session.peerAdbPublicKey,
                pairingTlsPublicKeySha256Base64 = session.pairingTlsPublicKeySha256Base64,
            )
        }
}
