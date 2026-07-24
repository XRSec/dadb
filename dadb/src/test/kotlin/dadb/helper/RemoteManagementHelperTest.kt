package dadb.helper

import com.google.common.truth.Truth.assertThat
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class RemoteManagementHelperTest {
    @Test
    fun parseDirectory_distinguishesSuccessfulEmptyDirectory() {
        val entries = parseRemoteDirectoryResponse("DADB_MANAGEMENT\tFILES\n")

        assertThat(entries).isEmpty()
    }

    @Test
    fun parseDirectory_decodesStructuredEntries() {
        val directoryName = "Folder 空格".encodeUtf8().base64()
        val fileName = "notes.txt".encodeUtf8().base64()
        val output =
            """
            DADB_MANAGEMENT	FILES
            F	$directoryName	D	4096	1720000000000
            F	$fileName	F	27	1720000001000
            """.trimIndent()

        val entries = parseRemoteDirectoryResponse(output)

        assertThat(entries)
            .containsExactly(
                RemoteDirectoryEntry("Folder 空格", RemoteFileType.Directory, 4096, 1720000000000),
                RemoteDirectoryEntry("notes.txt", RemoteFileType.RegularFile, 27, 1720000001000),
            ).inOrder()
    }

    @Test
    fun parseProcesses_decodesStructuredEntries() {
        val processName = "com.example.oldvm".encodeUtf8().base64()
        val entries =
            parseRemoteProcessResponse(
                "DADB_MANAGEMENT\tPROCESSES\nP\t321\t1048576\t$processName\n",
            )

        assertThat(entries)
            .containsExactly(RemoteProcessEntry(pid = 321, rssBytes = 1048576, name = "com.example.oldvm"))
    }

    @Test
    fun parseDevice_decodesValuesAndFieldErrors() {
        val model = "Android SDK built for arm64".encodeUtf8().base64()
        val error = "exit=127 output=missing".encodeUtf8().base64()
        val snapshot =
            parseRemoteDeviceResponse(
                "DADB_MANAGEMENT\tDEVICE\n" +
                    "D\tmodel\t$model\t-\n" +
                    "D\tsecurity_patch\t-\t$error\n",
            )

        assertThat(snapshot.fields[RemoteDeviceField.Model])
            .isEqualTo(RemoteDeviceFieldResult("Android SDK built for arm64", ""))
        assertThat(snapshot.fields[RemoteDeviceField.SecurityPatch])
            .isEqualTo(RemoteDeviceFieldResult("", "exit=127 output=missing"))
    }

    @Test
    fun helperReadiness_usesGlobalMarker() {
        assertThat(isDadbHelperReady("DADB_HELPER_READY\n")).isTrue()
        assertThat(isDadbHelperReady("DADB_HELPER_VERSION_MISMATCH\n")).isFalse()
        assertThat(isDadbHelperReady("DADB_HELPER_MISSING\n")).isFalse()
    }

    @Test
    fun parseResponse_surfacesExplicitHelperError() {
        val message = "Unable to enumerate /proc".encodeUtf8().base64()

        val error =
            assertFailsWith<java.io.IOException> {
                parseRemoteProcessResponse("ERROR\t$message\n")
            }

        assertThat(error).hasMessageThat().isEqualTo("Unable to enumerate /proc")
    }

    @Test
    fun parseResponse_rejectsMalformedRecords() {
        val error =
            assertFailsWith<java.io.IOException> {
                parseRemoteDirectoryResponse("DADB_MANAGEMENT\tFILES\nF\tbad\n")
            }

        assertThat(error).hasMessageThat().contains("invalid file record")
    }
}
