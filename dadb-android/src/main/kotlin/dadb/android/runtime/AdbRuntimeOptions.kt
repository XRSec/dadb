package dadb.android.runtime

import dadb.android.tls.AdbTlsTrustManagers
import javax.net.ssl.X509ExtendedTrustManager

@ExperimentalDadbAndroidApi
/**
 * Options for the Android runtime helper layer.
 *
 * This layer does not persist trust state by itself. If you want TOFU, pin comparison, or identity
 * change handling, observe [onServerTlsPeerObserved] and implement that policy in your own app
 * storage.
 */
data class AdbRuntimeOptions(
    val tlsTrustPolicy: AdbTlsTrustPolicy = AdbTlsTrustPolicy.TrustAll,
    val onServerTlsPeerObserved: ((AdbTlsPeerIdentity) -> Unit)? = null,
    val identityLabel: String? = null,
)

@ExperimentalDadbAndroidApi
/**
 * Observed TLS server identity for a successful Wireless Debugging handshake.
 *
 * The runtime reports what it saw on the wire. Callers can ignore it, persist it, compare it to a
 * previous observation, or surface it to users.
 */
data class AdbNetworkEndpoint(
    val host: String,
    val port: Int,
) {
    val authority: String
        get() = "$host:$port"
}

@ExperimentalDadbAndroidApi
data class AdbTlsPeerIdentity(
    val target: AdbNetworkEndpoint,
    val observedPinSha256Base64: String,
)

@ExperimentalDadbAndroidApi
sealed interface AdbTlsTrustPolicy {
    /**
     * Accept any TLS server certificate presented by the peer.
     *
     * This is the default because Wireless Debugging commonly uses self-signed certificates.
     * Callers that want endpoint validation, TOFU, or pin enforcement must implement that policy
     * themselves, typically by combining this mode with [AdbRuntimeOptions.onServerTlsPeerObserved]
     * or by supplying [Custom].
     */
    data object TrustAll : AdbTlsTrustPolicy

    /**
     * Provide a custom trust manager for TLS server verification.
     */
    class Custom(
        val createTrustManager: (target: AdbNetworkEndpoint) -> X509ExtendedTrustManager,
    ) : AdbTlsTrustPolicy

    /**
     * Require the TLS peer certificate public-key pin to match the expected value for the target.
     */
    class Pinned(
        val expectedPinSha256Base64: (target: AdbNetworkEndpoint) -> String,
    ) : AdbTlsTrustPolicy {
        constructor(expectedPinSha256Base64: String) : this({ expectedPinSha256Base64 })
    }
}

@OptIn(ExperimentalDadbAndroidApi::class)
internal fun AdbTlsTrustPolicy.resolveTrustManager(
    target: AdbNetworkEndpoint,
): X509ExtendedTrustManager =
    when (this) {
        AdbTlsTrustPolicy.TrustAll ->
            AdbTlsTrustManagers.createUnsafe()

        is AdbTlsTrustPolicy.Pinned ->
            AdbTlsTrustManagers.createPinned(
                target = target,
                expectedPinSha256Base64 = expectedPinSha256Base64(target),
            )

        is AdbTlsTrustPolicy.Custom ->
            createTrustManager(target)
    }
