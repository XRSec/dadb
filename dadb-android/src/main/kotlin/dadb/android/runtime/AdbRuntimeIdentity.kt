package dadb.android.runtime

@ExperimentalDadbAndroidApi
data class AdbRuntimeIdentity(
    val privateKey: String?,
    val publicKey: String?,
)
