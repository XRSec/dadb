package dadb.android.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalDadbAndroidApi::class)
class AdbRuntimeTest {
    @Test
    fun readIdentity_returnsStoredPublicKey() {
        val rootDir = Files.createTempDirectory("adb-runtime-test").toFile()
        try {
            val runtime = AdbRuntime(rootDir)

            runtime.loadOrCreateKeyPair()

            val identity = runtime.readIdentity()

            assertNotNull(identity.publicKey)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun regenerateKeyPair_refreshesIdentityFiles() {
        val rootDir = Files.createTempDirectory("adb-runtime-test").toFile()
        try {
            val runtime = AdbRuntime(rootDir)
            runtime.loadOrCreateKeyPair()

            val regeneratedKeyPair = runtime.regenerateKeyPair()
            val identity = runtime.readIdentity()

            assertNotNull(regeneratedKeyPair)
            assertNotNull(identity.privateKey)
            assertNotNull(identity.publicKey)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun replaceKeyPair_preservesIdentity_whenInputMatchesExistingKeyPair() {
        val rootDir = Files.createTempDirectory("adb-runtime-test").toFile()
        try {
            val runtime = AdbRuntime(rootDir)
            runtime.loadOrCreateKeyPair()
            val identity = runtime.readIdentity()

            runtime.replaceKeyPair(
                privateKey = identity.privateKey.orEmpty(),
                publicKey = identity.publicKey.orEmpty(),
            )

            assertEquals(identity.privateKey, runtime.readIdentity().privateKey)
            assertEquals(identity.publicKey, runtime.readIdentity().publicKey)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun replaceKeyPair_roundTripsIdentityIntoFreshRuntime() {
        val sourceRoot = Files.createTempDirectory("adb-runtime-source").toFile()
        val targetRoot = Files.createTempDirectory("adb-runtime-target").toFile()
        try {
            val sourceRuntime = AdbRuntime(sourceRoot)
            sourceRuntime.loadOrCreateKeyPair()
            val sourceIdentity = sourceRuntime.readIdentity()

            val targetRuntime = AdbRuntime(targetRoot)
            val restoredKeyPair =
                targetRuntime.replaceKeyPair(
                    privateKey = sourceIdentity.privateKey.orEmpty(),
                    publicKey = sourceIdentity.publicKey.orEmpty(),
                )

            assertNotNull(restoredKeyPair)
            assertEquals(sourceIdentity.privateKey, targetRuntime.readIdentity().privateKey)
            assertEquals(sourceIdentity.publicKey, targetRuntime.readIdentity().publicKey)
        } finally {
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }
}
