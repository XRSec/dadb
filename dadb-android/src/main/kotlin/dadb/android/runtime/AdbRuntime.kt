package dadb.android.runtime

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
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

/**
 * Experimental Android convenience layer around app-private ADB identity storage, Wireless
 * Debugging pairing, and Android-specific transport helpers.
 *
 * Prefer the lower-level transport and pairing APIs directly if you want a smaller, more explicit
 * integration surface.
 */
@ExperimentalDadbAndroidApi
class AdbRuntime(
    storageRoot: File,
    private val options: AdbRuntimeOptions = AdbRuntimeOptions(),
) {
    constructor(
        context: Context,
        options: AdbRuntimeOptions = AdbRuntimeOptions(),
    ) : this(defaultStorageRoot(context), options)

    private val identityStore =
        AdbIdentityStore(storageRoot) {
            options.identityLabel?.trim()?.takeIf { it.isNotEmpty() }
                ?: AdbRuntimeIdentityLabel.defaultLabel()
        }

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
        features: Set<String> = Dadb.connectFeatures(),
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
                        val certificate =
                            sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
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
                features,
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
        features: Set<String> = Dadb.connectFeatures(),
    ): Dadb {
        val keyPair = loadOrCreateKeyPair()
        return createAndWarmUsbDadb(
            usbManager = usbManager,
            usbDevice = usbDevice,
            description = description,
            keyPair = keyPair,
            features = features,
        )
    }

    private fun createAndWarmUsbDadb(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        description: String,
        keyPair: AdbKeyPair,
        features: Set<String>,
    ): Dadb {
        var lastError: Throwable? = null
        var activeUsbDevice = usbDevice

        repeat(3) { attempt ->
            Log.d(
                USB_RUNTIME_TAG,
                "createUsbDadb start attempt=${attempt + 1} description=$description features=${features.joinToString(
                    ",",
                )} " +
                    "devicePath=${activeUsbDevice.deviceName} vendor=${activeUsbDevice.vendorId} product=${activeUsbDevice.productId} " +
                    "manufacturer=${activeUsbDevice.manufacturerName ?: "Unknown"} productName=${activeUsbDevice.productName ?: "Unknown"}",
            )
            var dadb: Dadb? = null

            try {
                dadb =
                    Dadb.create(
                        UsbTransportFactory(
                            usbManager = usbManager,
                            usbDevice = activeUsbDevice,
                            description = description,
                        ),
                        keyPair,
                        features,
                    )
                // Warm the USB transport before returning so the caller does not pay the cost
                // of discovering a stale first handshake/open on its first shell request.
                dadb.supportsFeature("shell_v2")
                Log.d(
                    USB_RUNTIME_TAG,
                    "createUsbDadb warm-up success attempt=${attempt + 1} description=$description shell_v2=${
                        dadb.supportsFeature(
                            "shell_v2",
                        )
                    }",
                )
                return dadb
            } catch (t: Throwable) {
                lastError = t
                Log.w(
                    USB_RUNTIME_TAG,
                    "createUsbDadb warm-up failed attempt=${attempt + 1} description=$description error=${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
                runCatching { dadb?.close() }
                if (attempt < 2) {
                    val sleepMs = 150L * (attempt + 1)
                    Log.d(
                        USB_RUNTIME_TAG,
                        "createUsbDadb retrying after failure description=$description sleepMs=$sleepMs",
                    )
                    Thread.sleep(sleepMs)
                    val refreshedUsbDevice = refreshUsbReconnectCandidate(usbManager, activeUsbDevice)
                    if (refreshedUsbDevice !== activeUsbDevice) {
                        Log.d(
                            USB_RUNTIME_TAG,
                            "createUsbDadb switched USB candidate description=$description oldPath=${activeUsbDevice.deviceName} newPath=${refreshedUsbDevice.deviceName}",
                        )
                    }
                    activeUsbDevice = refreshedUsbDevice
                }
            }
        }

        Log.e(
            USB_RUNTIME_TAG,
            "createUsbDadb failed description=$description error=${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            lastError,
        )
        throw lastError ?: IllegalStateException("Failed to create USB Dadb: $description")
    }

    private fun refreshUsbReconnectCandidate(
        usbManager: UsbManager,
        currentDevice: UsbDevice,
    ): UsbDevice {
        val currentSerial =
            runCatching { currentDevice.serialNumber }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        val currentManufacturer = currentDevice.manufacturerName.orEmpty()
        val currentProductName = currentDevice.productName.orEmpty()
        val currentVendorId = currentDevice.vendorId
        val currentProductId = currentDevice.productId
        val currentInterfaceCount = currentDevice.interfaceCount

        val candidates = usbManager.deviceList.values.toList()

        currentSerial?.let { serial ->
            candidates
                .firstOrNull { candidate ->
                    runCatching { candidate.serialNumber }.getOrNull() == serial
                }?.let { return it }
        }

        return candidates.firstOrNull { candidate ->
            candidate.vendorId == currentVendorId &&
                candidate.productId == currentProductId &&
                candidate.interfaceCount == currentInterfaceCount &&
                candidate.manufacturerName.orEmpty() == currentManufacturer &&
                candidate.productName.orEmpty() == currentProductName
        } ?: currentDevice
    }

    companion object {
        private const val USB_RUNTIME_TAG = "DadbUsbRuntime"

        fun defaultStorageRoot(context: Context): File = File(context.filesDir, "adb_keys")
    }
}
