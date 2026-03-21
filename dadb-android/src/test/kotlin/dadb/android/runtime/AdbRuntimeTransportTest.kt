package dadb.android.runtime

import dadb.AdbKeyPair
import dadb.android.tls.AdbTlsCertificatePins
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

@OptIn(ExperimentalDadbAndroidApi::class)
class AdbRuntimeTransportTest {
    @Test
    fun plainAdbServer_usesTcpTransportMode() {
        val rootDir = Files.createTempDirectory("adb-runtime-transport-test").toFile()
        PlainAdbServer().use { server ->
            try {
                val runtime = AdbRuntime(rootDir)
                val connection =
                    runtime.connectNetworkDadb(
                        host = LOCALHOST,
                        port = server.port,
                        connectTimeout = 1_000,
                        socketTimeout = 1_000,
                    )

                connection.use {
                    assertEquals(false, connection.isTlsConnection())
                }
                server.awaitHandledConnection()
            } finally {
                rootDir.deleteRecursively()
            }
        }
    }

    @Test
    fun tlsConnect_reportsObservedPinsAcrossConnections() {
        val rootDir = Files.createTempDirectory("adb-runtime-transport-test").toFile()
        try {
            val observedPeers = mutableListOf<AdbTlsPeerIdentity>()
            val runtime =
                AdbRuntime(
                    rootDir,
                    AdbRuntimeOptions(
                        onServerTlsPeerObserved = observedPeers::add,
                    ),
                )
            runtime.loadOrCreateKeyPair()

            val firstServer = UpgradedTlsAdbServer()
            val firstConnectPin = firstServer.connectPin
            firstServer.use { server ->
                val firstConnection =
                    runtime.connectNetworkDadb(
                        host = LOCALHOST,
                        port = server.port,
                        connectTimeout = 1_000,
                        socketTimeout = 1_000,
                    )
                firstConnection.use {
                    assertEquals(true, firstConnection.isTlsConnection())
                }
                server.awaitHandledConnection()
            }

            UpgradedTlsAdbServer(allowClientAbort = true).use { mismatchedServer ->
                val secondConnection =
                    runtime.connectNetworkDadb(
                        host = LOCALHOST,
                        port = mismatchedServer.port,
                        connectTimeout = 1_000,
                        socketTimeout = 1_000,
                    )
                secondConnection.use {
                    assertEquals(true, secondConnection.isTlsConnection())
                }
                mismatchedServer.awaitHandledConnection()
            }

            assertEquals(2, observedPeers.size)
            assertEquals(LOCALHOST, observedPeers.first().target.host)
            assertEquals(firstServer.port, observedPeers.first().target.port)
            assertEquals(firstConnectPin, observedPeers.first().observedPinSha256Base64)
            assertTrue(observedPeers.last().observedPinSha256Base64.isNotBlank())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    private class PlainAdbServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val serverError = AtomicReference<Throwable?>()
        private val connectionHandled = CountDownLatch(1)
        private val serverThread =
            thread(start = true, isDaemon = true, name = "plain-adb-server") {
                try {
                    serverSocket.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val message = readMessageHeader(socket.getInputStream())
                        check(message.command == CMD_CNXN) { "Expected CNXN, got ${message.command}" }
                        writeMessage(
                            output = socket.getOutputStream(),
                            command = CMD_CNXN,
                            arg0 = CONNECT_VERSION,
                            arg1 = 4_096,
                            payload = "device::features=shell_v2".toByteArray(),
                        )
                        connectionHandled.countDown()
                    }
                } catch (_: SocketTimeoutException) {
                    connectionHandled.countDown()
                } catch (t: Throwable) {
                    serverError.set(t)
                    connectionHandled.countDown()
                } finally {
                    runCatching { serverSocket.close() }
                }
            }

        val port: Int = serverSocket.localPort

        fun awaitHandledConnection() {
            check(connectionHandled.await(10, TimeUnit.SECONDS)) { "Timed out waiting for plain ADB server" }
            serverError.get()?.let { throw AssertionError("Plain ADB server failed", it) }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            serverThread.join(5_000)
        }
    }

    private class UpgradedTlsAdbServer(
        private val allowClientAbort: Boolean = false,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val serverError = AtomicReference<Throwable?>()
        private val connectionHandled = CountDownLatch(1)
        private val privateKey = generatePrivateKey()
        private val certificate = createCertificate(privateKey)
        val connectPin: String = AdbTlsCertificatePins.publicKeySha256Base64(certificate)

        private val serverThread =
            thread(start = true, isDaemon = true, name = "tls-adb-server") {
                try {
                    serverSocket.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val connectMessage = readMessage(socket.getInputStream())
                        check(connectMessage.command == CMD_CNXN) { "Expected CNXN, got ${connectMessage.command}" }
                        writeMessage(
                            output = socket.getOutputStream(),
                            command = CMD_STLS,
                            arg0 = CONNECT_VERSION,
                            arg1 = 0,
                            payload = byteArrayOf(),
                        )

                        val stlsMessage = readMessage(socket.getInputStream())
                        check(stlsMessage.command == CMD_STLS) { "Expected STLS, got ${stlsMessage.command}" }

                        val sslSocket = createServerSslContext().socketFactory.createSocket(socket, LOCALHOST, socket.port, true) as SSLSocket
                        sslSocket.use { upgradedSocket ->
                            upgradedSocket.useClientMode = false
                            upgradedSocket.soTimeout = 5_000
                            upgradedSocket.startHandshake()
                            writeMessage(
                                output = upgradedSocket.getOutputStream(),
                                command = CMD_CNXN,
                                arg0 = CONNECT_VERSION,
                                arg1 = 4_096,
                                payload = "device::features=shell_v2".toByteArray(),
                            )
                            connectionHandled.countDown()
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    connectionHandled.countDown()
                } catch (t: java.net.SocketException) {
                    if (allowClientAbort && t.message.orEmpty().contains("Broken pipe")) {
                        connectionHandled.countDown()
                    } else {
                        serverError.set(t)
                        connectionHandled.countDown()
                    }
                } catch (t: Throwable) {
                    serverError.set(t)
                    connectionHandled.countDown()
                } finally {
                    runCatching { serverSocket.close() }
                }
            }

        val port: Int = serverSocket.localPort

        fun awaitHandledConnection() {
            check(connectionHandled.await(10, TimeUnit.SECONDS)) { "Timed out waiting for TLS ADB server" }
            serverError.get()?.let { throw AssertionError("TLS ADB server failed", it) }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            serverThread.join(5_000)
        }

        private fun createServerSslContext(): SSLContext =
            SSLContext.getInstance("TLS").apply {
                init(
                    arrayOf(createServerKeyManager(privateKey, certificate)),
                    arrayOf(createInsecureTrustManager()),
                    SecureRandom(),
                )
            }
    }

    private data class TestAdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payloadLength: Int,
    )

    private companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_STLS = 0x534c5453
        private const val CONNECT_VERSION = 0x01000000

        private fun readMessageHeader(input: InputStream): TestAdbMessage {
            val header = input.readNBytes(24)
            check(header.size == 24) { "Incomplete ADB header" }
            val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = headerBuffer.int
            val arg0 = headerBuffer.int
            val arg1 = headerBuffer.int
            val payloadLength = headerBuffer.int
            headerBuffer.int
            headerBuffer.int
            return TestAdbMessage(
                command = command,
                arg0 = arg0,
                arg1 = arg1,
                payloadLength = payloadLength,
            )
        }

        private fun readMessage(input: InputStream): TestAdbMessage {
            val message = readMessageHeader(input)
            val payload = input.readNBytes(message.payloadLength)
            check(payload.size == message.payloadLength) { "Incomplete ADB payload" }
            return message
        }

        private fun writeMessage(
            output: OutputStream,
            command: Int,
            arg0: Int,
            arg1: Int,
            payload: ByteArray,
        ) {
            val checksum = payload.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }
            val header =
                ByteBuffer.allocate(24)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(command)
                    .putInt(arg0)
                    .putInt(arg1)
                    .putInt(payload.size)
                    .putInt(checksum)
                    .putInt(command xor -0x1)
                    .array()

            output.write(header)
            output.write(payload)
            output.flush()
        }

        private fun generatePrivateKey(): RSAPrivateKey =
            KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048)
            }.generateKeyPair().private as RSAPrivateKey

        private fun createCertificate(privateKey: RSAPrivateKey): X509Certificate {
            val rsaPublicKey =
                KeyFactory.getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(privateKey.modulus, BigInteger.valueOf(65537L))) as RSAPublicKey

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
            val builder =
                X509v3CertificateBuilder(
                    X500Name("CN=00"),
                    BigInteger.ONE,
                    java.util.Date(0),
                    java.util.Date(2_461_449_600_000L),
                    X500Name("CN=00"),
                    SubjectPublicKeyInfo.getInstance(rsaPublicKey.encoded),
                )
            val encoded = builder.build(signer).encoded

            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        }

        private fun createServerKeyManager(
            privateKey: PrivateKey,
            certificate: X509Certificate,
        ): X509ExtendedKeyManager =
            object : X509ExtendedKeyManager() {
                private val keyAlias = "adbkey"

                override fun chooseClientAlias(
                    keyType: Array<out String>?,
                    issuers: Array<out java.security.Principal>?,
                    socket: java.net.Socket?,
                ): String? = null

                override fun chooseServerAlias(
                    keyType: String?,
                    issuers: Array<out java.security.Principal>?,
                    socket: java.net.Socket?,
                ): String = keyAlias

                override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                    if (alias == keyAlias) {
                        arrayOf(certificate)
                    } else {
                        null
                    }

                override fun getPrivateKey(alias: String?): PrivateKey? =
                    if (alias == keyAlias) {
                        privateKey
                    } else {
                        null
                    }

                override fun getClientAliases(
                    keyType: String?,
                    issuers: Array<out java.security.Principal>?,
                ): Array<String>? = null

                override fun getServerAliases(
                    keyType: String?,
                    issuers: Array<out java.security.Principal>?,
                ): Array<String> = arrayOf(keyAlias)
            }

        private fun createInsecureTrustManager(): X509ExtendedTrustManager =
            object : X509ExtendedTrustManager() {
                private fun accept(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {
                    if (chain.isNullOrEmpty()) return
                    if (authType.isNullOrBlank()) return
                }

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    socket: java.net.Socket?,
                ) = accept(chain, authType)

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    engine: SSLEngine?,
                ) = accept(chain, authType)

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = accept(chain, authType)

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    socket: java.net.Socket?,
                ) = accept(chain, authType)

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    engine: SSLEngine?,
                ) = accept(chain, authType)

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = accept(chain, authType)

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
    }
}
