package dadb.android.runtime

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.android.storage.AdbIdentityStore
import dadb.android.tls.AdbTlsCertificatePins
import dadb.android.tls.TlsAdbTransportFactory
import dadb.android.usb.UsbTransportFactory
import dadb.android.wireless.WirelessDebugPairingClient
import dadb.android.wireless.WirelessDebugPairingRequest
import dadb.android.wireless.WirelessDebugPairingResult
import java.io.File
import java.security.cert.X509Certificate

@ExperimentalDadbAndroidApi
/**
 * Experimental Android convenience layer around app-private ADB identity storage, Wireless
 * Debugging pairing, and Android-specific transport helpers.
 *
 * Prefer the lower-level transport and pairing APIs directly if you want a smaller, more explicit
 * integration surface.
 */
class AdbRuntime(
    storageRoot: File,
    private val options: AdbRuntimeOptions = AdbRuntimeOptions(),
) {
    constructor(
        context: Context,
        options: AdbRuntimeOptions = AdbRuntimeOptions(),
    ) : this(defaultStorageRoot(context), options)

    private val identityStore = AdbIdentityStore(storageRoot)

    fun loadOrCreateKeyPair(): AdbKeyPair = identityStore.loadOrCreate()

    fun regenerateKeyPair(): AdbKeyPair = identityStore.regenerate()

    fun replaceKeyPair(
        privateKey: String,
        publicKey: String,
    ): AdbKeyPair {
        identityStore.replace(privateKey, publicKey)
        return identityStore.loadOrCreate()
    }

    fun readIdentity(): AdbRuntimeIdentity =
        AdbRuntimeIdentity(
            privateKey = identityStore.readPrivateKey(),
            publicKey = identityStore.readPublicKey(),
        )

    fun pairWithCode(
        host: String,
        port: Int,
        pairingCode: String,
    ): Result<WirelessDebugPairingResult> {
        val keyPair = loadOrCreateKeyPair()
        return WirelessDebugPairingClient(keyPair)
            .pair(
                WirelessDebugPairingRequest(
                    host = host,
                    port = port,
                    pairingCode = pairingCode,
                ),
            )
    }

    fun connectNetworkDadb(
        host: String,
        port: Int,
        connectTimeout: Int = 0,
        socketTimeout: Int = 0,
    ): Dadb {
        val keyPair = loadOrCreateKeyPair()
        val target = AdbNetworkEndpoint(host = host, port = port)

        val dadb =
            Dadb.create(
                TlsAdbTransportFactory(
                    host = host,
                    port = port,
                    keyPair = keyPair,
                    trustManager = options.tlsTrustPolicy.resolveTrustManager(target),
                    connectTimeout = connectTimeout,
                    socketTimeout = socketTimeout,
                    onHandshakeCompleted = { sslSocket ->
                        val certificate = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                            ?: return@TlsAdbTransportFactory
                        options.onServerTlsPeerObserved?.invoke(
                            AdbTlsPeerIdentity(
                                target = target,
                                observedPinSha256Base64 = AdbTlsCertificatePins.publicKeySha256Base64(certificate),
                            ),
                        )
                    },
                ),
                keyPair,
            )

        try {
            dadb.supportsFeature("shell_v2")
        } catch (t: Throwable) {
            runCatching { dadb.close() }
            throw t
        }

        return dadb
    }

    fun createUsbDadb(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        description: String = usbDevice.deviceName,
    ): Dadb {
        val keyPair = loadOrCreateKeyPair()
        return Dadb.create(
            UsbTransportFactory(
                usbManager = usbManager,
                usbDevice = usbDevice,
                description = description,
            ),
            keyPair,
        )
    }

    companion object {
        fun defaultStorageRoot(context: Context): File = File(context.filesDir, "adb_keys")
    }
}
