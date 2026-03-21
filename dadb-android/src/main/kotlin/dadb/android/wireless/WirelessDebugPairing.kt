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
)

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
