package dadb.android.runtime

import android.os.Build

internal object AdbRuntimeIdentityLabel {
    private const val DEFAULT_SOFTWARE_NAME = "dadb-android"

    fun defaultLabel(
        softwareName: String = DEFAULT_SOFTWARE_NAME,
    ): String {
        val model =
            Build.MODEL
                ?.replace(" ", "")
                ?.trim()
                .orEmpty()
                .ifBlank { "Android" }
        return "$model@$softwareName"
    }
}
