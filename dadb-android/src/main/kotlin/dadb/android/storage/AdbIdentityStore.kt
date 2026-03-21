package dadb.android.storage

import dadb.AdbKeyPair
import java.io.File

class AdbIdentityStore(
    private val runtimeFiles: AdbRuntimeFiles,
    private val identityLabel: () -> String = { "unknown@unknown" },
) {
    constructor(
        rootDir: File,
        identityLabel: () -> String = { "unknown@unknown" },
    ) : this(AdbRuntimeFiles(rootDir), identityLabel)

    fun loadOrCreate(): AdbKeyPair {
        runtimeFiles.ensureRootDirectory()

        if (!runtimeFiles.privateKeyFile.exists() || !runtimeFiles.publicKeyFile.exists()) {
            AdbKeyPair.generate(
                runtimeFiles.privateKeyFile,
                runtimeFiles.publicKeyFile,
                publicKeyOwner = identityLabel(),
            )
        }

        return AdbKeyPair.read(runtimeFiles.privateKeyFile, runtimeFiles.publicKeyFile)
    }

    fun regenerate(): AdbKeyPair {
        runtimeFiles.ensureRootDirectory()
        if (runtimeFiles.privateKeyFile.exists()) {
            runtimeFiles.privateKeyFile.delete()
        }
        if (runtimeFiles.publicKeyFile.exists()) {
            runtimeFiles.publicKeyFile.delete()
        }

        AdbKeyPair.generate(
            runtimeFiles.privateKeyFile,
            runtimeFiles.publicKeyFile,
            publicKeyOwner = identityLabel(),
        )
        return AdbKeyPair.read(runtimeFiles.privateKeyFile, runtimeFiles.publicKeyFile)
    }

    fun replace(
        privateKey: String,
        publicKey: String,
    ): Boolean {
        runtimeFiles.ensureRootDirectory()
        val identityChanged =
            readPrivateKey() != privateKey ||
                readPublicKey() != publicKey

        runtimeFiles.privateKeyFile.writeText(privateKey)
        runtimeFiles.publicKeyFile.writeText(publicKey)
        return identityChanged
    }

    fun readPrivateKey(): String? {
        runtimeFiles.ensureRootDirectory()
        if (!runtimeFiles.privateKeyFile.exists()) {
            return null
        }

        return runtimeFiles.privateKeyFile.readText()
    }

    fun readPublicKey(): String? {
        runtimeFiles.ensureRootDirectory()
        if (!runtimeFiles.publicKeyFile.exists()) {
            return null
        }

        return runtimeFiles.publicKeyFile.readText()
    }
}
