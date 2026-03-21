package dadb.android.tls

import java.security.cert.CertificateException

class AdbTlsPinMismatchException(
    val expectedPinSha256Base64: String,
    val observedPinSha256Base64: String,
    targetAuthority: String,
) : CertificateException(
        "TLS peer pin mismatch for $targetAuthority: expected=$expectedPinSha256Base64 observed=$observedPinSha256Base64",
    )
