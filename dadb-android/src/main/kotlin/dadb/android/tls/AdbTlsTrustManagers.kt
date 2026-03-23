package dadb.android.tls

import dadb.android.runtime.AdbNetworkEndpoint
import dadb.android.runtime.ExperimentalDadbAndroidApi
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

@OptIn(ExperimentalDadbAndroidApi::class)
internal object AdbTlsTrustManagers {
    fun createUnsafe(): X509ExtendedTrustManager =
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
                socket: Socket?,
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
                socket: Socket?,
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

    fun createPinned(
        target: AdbNetworkEndpoint,
        expectedPinSha256Base64: String,
    ): X509ExtendedTrustManager =
        object : X509ExtendedTrustManager() {
            private fun checkPinnedChain(chain: Array<out X509Certificate>?) {
                val leaf =
                    chain?.firstOrNull()
                        ?: throw CertificateException("TLS peer did not present a certificate for ${target.authority}")
                val observedPin = AdbTlsCertificatePins.publicKeySha256Base64(leaf)
                if (observedPin != expectedPinSha256Base64) {
                    throw AdbTlsPinMismatchException(
                        expectedPinSha256Base64 = expectedPinSha256Base64,
                        observedPinSha256Base64 = observedPin,
                        targetAuthority = target.authority,
                    )
                }
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?,
            ) = Unit

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = Unit

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?,
            ) = checkPinnedChain(chain)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = checkPinnedChain(chain)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = checkPinnedChain(chain)

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
}
