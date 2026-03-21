package dadb.android.tls

import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

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

}
