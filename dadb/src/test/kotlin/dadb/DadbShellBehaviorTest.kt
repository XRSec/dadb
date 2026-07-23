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
    fun openShell_streamsLegacyOutputWhenShellV2Unsupported() {
        val dadb = RecordingDadb(supportsShellV2 = false, legacyOutput = "legacy\n")

        val response = dadb.openShell("echo hello").use(AdbShellStream::readAll)

        assertThat(dadb.openedDestinations).containsExactly("shell:echo hello")
        assertThat(response.exitCode).isEqualTo(0)
        assertThat(response.output).isEqualTo("legacy\n")
        assertThat(response.errorOutput).isEmpty()
    }

    @Test
    fun shell_fallsBackAndRemembersWhenAdvertisedShellV2IsRefused() {
        val dadb =
            RecordingDadb(
                supportsShellV2 = true,
                rejectShellV2Open = true,
                legacyOutput = "legacy\n",
            )

        val first = dadb.shell("echo first")
        val second = dadb.shell("echo second")

        assertThat(dadb.openedDestinations)
            .containsExactly(
                "shell,v2,raw:echo first",
                "shell:echo first",
                "shell:echo second",
            )
            .inOrder()
        assertThat(first.output).isEqualTo("legacy\n")
        assertThat(second.output).isEqualTo("legacy\n")
    }

    @Test
    fun openShell_fallsBackWhenAdvertisedShellV2IsRefused() {
        val dadb =
            RecordingDadb(
                supportsShellV2 = true,
                rejectShellV2Open = true,
                legacyOutput = "streamed legacy\n",
            )

        val stream = dadb.openShell("echo hello")
        val response = stream.use(AdbShellStream::readAll)

        assertThat(stream.protocol).isEqualTo(AdbShellProtocol.LEGACY)
        assertThat(response.output).isEqualTo("streamed legacy\n")
        assertThat(dadb.openedDestinations)
            .containsExactly(
                "shell,v2,raw:echo hello",
                "shell:echo hello",
            )
            .inOrder()
    }

    @Test
    fun legacyOpenShell_writesRawStdin() {
        val dadb = RecordingDadb(supportsShellV2 = false, legacyOutput = "")

        dadb.openShell().use { stream ->
            assertThat(stream.protocol).isEqualTo(AdbShellProtocol.LEGACY)
            stream.write("echo hello\n")
        }

        assertThat(dadb.writtenUtf8).isEqualTo("echo hello\n")
    }

    @Test
    fun openPtyShell_requiresShellV2() {
        val dadb = RecordingDadb(supportsShellV2 = false, legacyOutput = "unused")

        val error =
            assertFailsWith<IllegalStateException> {
                dadb.openPtyShell("echo hello")
            }

        assertThat(error).hasMessageThat().contains("openPtyShell requires peer feature 'shell_v2'")
    }

    @Test
    fun legacyOpenShell_rejectsFramedWrites() {
        val dadb = RecordingDadb(supportsShellV2 = false, legacyOutput = "")

        val error =
            dadb.openShell().use { stream ->
                assertFailsWith<IllegalStateException> {
                    stream.write(ID_CLOSE_STDIN)
                }
            }

        assertThat(error).hasMessageThat().contains("Framed shell writes require shell_v2")
    }

    private class RecordingDadb(
        private val supportsShellV2: Boolean,
        private val shellV2Output: String = "",
        private val legacyOutput: String = "",
        private val exitCode: Int = 0,
        private val rejectShellV2Open: Boolean = false,
    ) : Dadb {
        val openedDestinations = mutableListOf<String>()
        private val written = Buffer()
        private var shellV2Rejected = false
        val writtenUtf8: String
            get() = written.clone().readUtf8()

        override fun open(destination: String): AdbStream {
            openedDestinations += destination
            if (rejectShellV2Open && destination.startsWith("shell,v2,raw:")) {
                throw AdbStreamOpenException(destination, "refused")
            }
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
                override val sink = written

                override fun close() = Unit
            }
        }

        override fun supportsFeature(feature: String): Boolean =
            feature == "shell_v2" && supportsShellV2 && !shellV2Rejected

        override fun markFeatureRejected(feature: String) {
            if (feature == "shell_v2") {
                shellV2Rejected = true
            }
        }

        override fun isTlsConnection(): Boolean = false

        override fun close() = Unit
    }
}
