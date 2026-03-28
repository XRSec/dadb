package dadb

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import java.nio.file.Files
import kotlin.test.Test

internal class DadbInstallBehaviorTest {

    @Test
    fun installMultiple_prefersAbbExecWhenSupported() {
        val dadb = RecordingInstallDadb(features = setOf("abb_exec", "cmd"))
        val tempDir = kotlin.io.path.createTempDirectory(prefix = "dadb-install-").toFile().apply { deleteOnExit() }
        val apk1 = createApk(tempDir, "base.apk", "base")
        val apk2 = createApk(tempDir, "split.apk", "split")

        listOf(apk1, apk2).forEach {
            it.deleteOnExit()
        }

        dadb.installMultiple(listOf(apk1, apk2))

        assertThat(dadb.openedDestinations).containsExactly(
            "abb_exec:package\u0000install-create\u0000-S\u00009",
            "abb_exec:package\u0000install-write\u0000-S\u00004\u0000session123\u0000base.apk\u0000-",
            "abb_exec:package\u0000install-write\u0000-S\u00005\u0000session123\u0000split.apk\u0000-",
            "abb_exec:package\u0000install-commit\u0000session123",
        ).inOrder()
        assertThat(dadb.streamedPayloads()).containsExactly("base", "split").inOrder()
    }

    private fun createApk(dir: java.io.File, name: String, content: String): java.io.File {
        val file = java.io.File(dir, name)
        Files.writeString(file.toPath(), content)
        return file
    }

    private class RecordingInstallDadb(
        private val features: Set<String>,
    ) : Dadb {
        val openedDestinations = mutableListOf<String>()
        private val writeSinks = mutableListOf<Buffer>()

        override fun open(destination: String): AdbStream {
            openedDestinations += destination
            val sink = Buffer()
            if (destination.startsWith("abb_exec:package\u0000install-write")) {
                writeSinks += sink
            }

            val response =
                when {
                    destination.startsWith("abb_exec:package\u0000install-create") -> "Success: created install session [session123]"
                    destination.startsWith("abb_exec:package\u0000install-write") -> "Success"
                    destination.startsWith("abb_exec:package\u0000install-commit") -> "Success"
                    else -> error("Unexpected destination: $destination")
                }

            return object : AdbStream {
                override val source = Buffer().writeUtf8(response)
                override val sink = sink

                override fun close() = Unit
            }
        }

        override fun supportsFeature(feature: String): Boolean = features.contains(feature)

        override fun isTlsConnection(): Boolean = false

        override fun close() = Unit

        fun streamedPayloads(): List<String> = writeSinks.map { it.snapshot().utf8() }
    }
}
