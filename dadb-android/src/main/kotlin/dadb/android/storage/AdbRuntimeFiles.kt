package dadb.android.storage

import java.io.File

class AdbRuntimeFiles(
    val rootDir: File,
) {
    val privateKeyFile: File
        get() = File(rootDir, "adbkey")

    val publicKeyFile: File
        get() = File(rootDir, "adbkey.pub")

    fun ensureRootDirectory() {
        if (rootDir.exists()) {
            check(rootDir.isDirectory) { "ADB storage root is not a directory: ${rootDir.absolutePath}" }
            return
        }

        check(rootDir.mkdirs()) { "Failed to create ADB storage root: ${rootDir.absolutePath}" }
    }
}
