package dadb

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class DadbShellBehaviorTest {

    @Test
    fun shell_usesShellV2WhenSupported() {
        val dadb = RecordingDadb(supportsShellV2 = true, shellV2Output = "hello\n", exitCode = 0)

        val response = dadb.shell("echo hello")

        assertThat(dadb.openedDestinations).containsExactly("shell,v2,raw:echo hello")
        assertThat(response.output).isEqualTo("hello\n")
        assertThat(response.errorOutput).isEmpty()
        assertThat(response.exitCode).isEqualTo(0)
    }

    @Test
    fun shell_fallsBackToLegacyShellWhenShellV2Unsupported() {
        val dadb = RecordingDadb(supportsShellV2 = false, legacyOutput = "legacy\n")

        val response = dadb.shell("echo hello")

        assertThat(dadb.openedDestinations).containsExactly("shell:echo hello")
        assertThat(response.output).isEqualTo("legacy\n")
        assertThat(response.errorOutput).isEmpty()
        assertThat(response.exitCode).isEqualTo(0)
    }

    @Test
    fun openShell_requiresShellV2() {
        val dadb = RecordingDadb(supportsShellV2 = false, legacyOutput = "unused")

        val error =
            assertFailsWith<IllegalStateException> {
                dadb.openShell("echo hello")
            }

        assertThat(error).hasMessageThat().contains("openShell requires peer feature 'shell_v2'")
    }

    private class RecordingDadb(
        private val supportsShellV2: Boolean,
        private val shellV2Output: String = "",
        private val legacyOutput: String = "",
        private val exitCode: Int = 0,
    ) : Dadb {
        val openedDestinations = mutableListOf<String>()

        override fun open(destination: String, enableDelayedAck: Boolean): AdbStream {
            openedDestinations += destination
            val source =
                Buffer().apply {
                    if (destination.startsWith("shell,v2,raw:")) {
                        writeByte(ID_STDOUT)
                        writeIntLe(shellV2Output.toByteArray().size)
                        writeUtf8(shellV2Output)
                        writeByte(ID_EXIT)
                        writeIntLe(1)
                        writeByte(exitCode)
                    } else {
                        writeUtf8(legacyOutput)
                    }
                }
            return object : AdbStream {
                override val source = source
                override val sink = Buffer()

                override fun close() = Unit
            }
        }

        override fun supportsFeature(feature: String): Boolean = feature == "shell_v2" && supportsShellV2

        override fun isTlsConnection(): Boolean = false

        override fun close() = Unit
    }
}
