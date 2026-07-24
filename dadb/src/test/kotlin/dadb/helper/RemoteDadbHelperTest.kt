package dadb.helper

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import dadb.SyncResult
import java.io.File
import kotlin.test.Test
import okio.Source

internal class RemoteDadbHelperTest {
    @Test
    fun prepare_reusesMatchingHelperWithoutUpload() {
        val dadb = VersionCheckDadb(listOf("DADB_HELPER_READY\n"))

        withHelperFile { helper -> dadb.prepareRemoteDadbHelper(helper) }

        assertThat(dadb.pushCount).isEqualTo(0)
    }

    @Test
    fun prepare_uploadsAndRetriesWhenVersionDoesNotMatch() {
        val dadb = VersionCheckDadb(listOf("DADB_HELPER_VERSION_MISMATCH\n", "DADB_HELPER_READY\n"))

        withHelperFile { helper -> dadb.prepareRemoteDadbHelper(helper) }

        assertThat(dadb.pushCount).isEqualTo(1)
        assertThat(dadb.shellCount).isEqualTo(2)
    }

    private fun withHelperFile(block: (File) -> Unit) {
        val helper = File.createTempFile("dadb-helper", ".jar")
        try {
            block(helper)
        } finally {
            helper.delete()
        }
    }

    private class VersionCheckDadb(outputs: List<String>) : Dadb {
        private val responses = ArrayDeque(outputs)
        var pushCount = 0
        var shellCount = 0

        override fun shell(command: String): AdbShellResponse {
            shellCount++
            return AdbShellResponse(responses.removeFirst(), "", 0)
        }

        override fun push(
            source: Source,
            remotePath: String,
            mode: Int,
            lastModifiedMs: Long,
        ): SyncResult {
            pushCount++
            source.close()
            return SyncResult.Success
        }

        override fun open(destination: String): AdbStream = error("Unexpected open: $destination")

        override fun supportsFeature(feature: String): Boolean = false

        override fun isTlsConnection(): Boolean = false

        override fun close() = Unit
    }
}
