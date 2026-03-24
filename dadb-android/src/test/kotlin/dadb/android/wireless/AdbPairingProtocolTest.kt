package dadb.android.wireless

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingProtocolTest {
    @Test
    fun pairingProtocolConstants_matchAdbProtocolExpectations() {
        assertEquals(1.toByte(), CURRENT_KEY_HEADER_VERSION)
        assertEquals(1.toByte(), MIN_SUPPORTED_KEY_HEADER_VERSION)
        assertEquals(1.toByte(), MAX_SUPPORTED_KEY_HEADER_VERSION)
        assertEquals(8192, MAX_PEER_INFO_SIZE)
        assertEquals(MAX_PEER_INFO_SIZE * 2, MAX_PAYLOAD_SIZE)
        assertEquals(6, PAIRING_PACKET_HEADER_SIZE)
        assertEquals("adb-label\u0000", EXPORTED_KEY_LABEL)
        assertEquals(64, EXPORTED_KEY_SIZE)
    }

    @Test
    fun pairingPacketHeader_roundTripsValidHeader() {
        val header =
            PairingPacketHeader(
                version = CURRENT_KEY_HEADER_VERSION,
                type = PairingPacketHeader.Type.SPAKE2_MSG,
                payload = 128,
            )

        val buffer = ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        val decoded =
            PairingPacketHeader.readFrom(
                ByteBuffer.wrap(buffer.array()).order(ByteOrder.BIG_ENDIAN),
            )

        requireNotNull(decoded)
        assertEquals(CURRENT_KEY_HEADER_VERSION, decoded.version)
        assertEquals(PairingPacketHeader.Type.SPAKE2_MSG, decoded.type)
        assertEquals(128, decoded.payload)
    }

    @Test
    fun pairingPacketHeader_rejectsUnsupportedVersion() {
        val decoded = decodeHeader(version = 2, type = PairingPacketHeader.Type.SPAKE2_MSG.toInt(), payload = 16)

        assertNull(decoded)
    }

    @Test
    fun pairingPacketHeader_rejectsUnknownType() {
        val decoded = decodeHeader(version = 1, type = 9, payload = 16)

        assertNull(decoded)
    }

    @Test
    fun pairingPacketHeader_rejectsUnsafePayloadSizes() {
        assertNull(decodeHeader(version = 1, type = PairingPacketHeader.Type.PEER_INFO.toInt(), payload = 0))
        assertNull(
            decodeHeader(
                version = 1,
                type = PairingPacketHeader.Type.PEER_INFO.toInt(),
                payload = MAX_PAYLOAD_SIZE + 1,
            ),
        )
    }

    @Test
    fun stateMachine_allowsExpectedHappyPathTransitions() {
        val exchangingMsgs = PairingClientStateMachine.start(PairingClientState.READY)
        val exchangingPeerInfo =
            PairingClientStateMachine.afterMessageExchange(
                exchangingMsgs,
                success = true,
            )
        val stopped =
            PairingClientStateMachine.afterPeerInfoExchange(
                exchangingPeerInfo,
                success = true,
            )

        assertEquals(PairingClientState.EXCHANGING_MSGS, exchangingMsgs)
        assertEquals(PairingClientState.EXCHANGING_PEER_INFO, exchangingPeerInfo)
        assertEquals(PairingClientState.STOPPED, stopped)
    }

    @Test
    fun stateMachine_stopsAfterFailedMessageExchange() {
        val exchangingMsgs = PairingClientStateMachine.start(PairingClientState.READY)

        val stopped =
            PairingClientStateMachine.afterMessageExchange(
                exchangingMsgs,
                success = false,
            )

        assertEquals(PairingClientState.STOPPED, stopped)
    }

    @Test
    fun stateMachine_rejectsInvalidTransition() {
        val error =
            try {
                PairingClientStateMachine.afterPeerInfoExchange(PairingClientState.READY, success = true)
                null
            } catch (t: IllegalStateException) {
                t
            }

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("EXCHANGING_PEER_INFO"))
    }

    @Test
    fun decodePeerAdbPublicKey_throwsForInvalidPairingCode() {
        val error =
            try {
                decodePeerAdbPublicKey(null)
                null
            } catch (t: AdbInvalidPairingCodeException) {
                t
            }

        requireNotNull(error)
    }

    @Test
    fun decodePeerAdbPublicKey_returnsNullForInvalidPeerInfoSize() {
        var invalidSize: Int? = null

        val decoded =
            decodePeerAdbPublicKey(
                ByteArray(MAX_PEER_INFO_SIZE - 1),
                onInvalidSize = { invalidSize = it },
            )

        assertNull(decoded)
        assertEquals(MAX_PEER_INFO_SIZE - 1, invalidSize)
    }

    @Test
    fun decodePeerAdbPublicKey_trimsTrailingZeroPadding() {
        val payload = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        payload.put(PairingPeerInfo.ADB_RSA_PUB_KEY)
        payload.put("adb-rsa AAAA test@device".toByteArray())

        val decoded = decodePeerAdbPublicKey(payload.array())

        requireNotNull(decoded)
        assertArrayEquals("adb-rsa AAAA test@device".toByteArray(), decoded)
    }

    @Test
    fun pairInfo_roundTripsAndTruncatesOversizedData() {
        val raw = ByteArray(MAX_PEER_INFO_SIZE + 32) { 'A'.code.toByte() }
        val info = PairingPeerInfo(PairingPeerInfo.ADB_RSA_PUB_KEY, raw)
        val buffer = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)

        info.writeTo(buffer)

        val decoded = PairingPeerInfo.readFrom(ByteBuffer.wrap(buffer.array()).order(ByteOrder.BIG_ENDIAN))

        assertEquals(PairingPeerInfo.ADB_RSA_PUB_KEY, decoded.type)
        assertEquals(MAX_PEER_INFO_SIZE - 1, decoded.data.size)
        assertTrue(decoded.data.all { it == 'A'.code.toByte() })
    }

    private fun decodeHeader(
        version: Int,
        type: Int,
        payload: Int,
    ): PairingPacketHeader? {
        val bytes =
            ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
                put(version.toByte())
                put(type.toByte())
                putInt(payload)
            }.array()

        return PairingPacketHeader.readFrom(
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN),
            logError = {},
        )
    }
}
