package dadb.android.tls

import java.io.IOException

/**
 * The remote Wireless Debugging peer rejected the TLS handshake because pairing or trust is missing.
 */
class AdbTlsAuthException : IOException("TLS peer rejected authentication or trust is missing")
