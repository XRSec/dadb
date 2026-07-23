package dadb.helper

import dadb.AdbShellResponse
import dadb.Dadb
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException

enum class RemoteFileType {
    Directory,
    RegularFile,
    SymbolicLink,
    Other,
}

data class RemoteDirectoryEntry(
    val name: String,
    val type: RemoteFileType,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
)

data class RemoteProcessEntry(
    val pid: Int,
    val rssBytes: Long,
    val name: String,
)

private const val MANAGEMENT_PROTOCOL_VERSION = "1"
private const val MANAGEMENT_MAIN_CLASS = "dadb.helper.ManagementSnapshotMain"
private const val MANAGEMENT_HEADER = "DADB_MANAGEMENT"
private const val MANAGEMENT_ERROR = "ERROR"
private const val HELPER_MISSING = "DADB_MANAGEMENT_HELPER_MISSING"

@Throws(IOException::class)
fun Dadb.loadDirectoryWithHelper(
    path: String,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): List<RemoteDirectoryEntry> {
    val response =
        invokeManagementHelper(
            command = "files",
            args = listOf(path.toByteArray().toByteString().base64()),
            localHelperJar = localHelperJar,
            remoteHelperPath = remoteHelperPath,
        )
    return parseRemoteDirectoryResponse(response.output)
}

@Throws(IOException::class)
fun Dadb.loadProcessesWithHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): List<RemoteProcessEntry> {
    val response =
        invokeManagementHelper(
            command = "processes",
            args = emptyList(),
            localHelperJar = localHelperJar,
            remoteHelperPath = remoteHelperPath,
        )
    return parseRemoteProcessResponse(response.output)
}

internal fun parseRemoteDirectoryResponse(output: String): List<RemoteDirectoryEntry> {
    val lines = protocolLines(output, expectedType = "FILES")
    return lines.map { line ->
        val fields = line.split('\t')
        if (fields.size != 5 || fields[0] != "F") {
            throw IOException("Remote management helper returned an invalid file record")
        }
        RemoteDirectoryEntry(
            name = decodeUtf8(fields[1], "file name"),
            type =
                when (fields[2]) {
                    "D" -> RemoteFileType.Directory
                    "F" -> RemoteFileType.RegularFile
                    "L" -> RemoteFileType.SymbolicLink
                    "O" -> RemoteFileType.Other
                    else -> throw IOException("Remote management helper returned an unknown file type")
                },
            sizeBytes = fields[3].toLongOrNull() ?: throw IOException("Remote management helper returned an invalid file size"),
            modifiedTimeMillis =
                fields[4].toLongOrNull()
                    ?: throw IOException("Remote management helper returned an invalid file modification time"),
        )
    }
}

internal fun parseRemoteProcessResponse(output: String): List<RemoteProcessEntry> {
    val lines = protocolLines(output, expectedType = "PROCESSES")
    return lines.map { line ->
        val fields = line.split('\t')
        if (fields.size != 4 || fields[0] != "P") {
            throw IOException("Remote management helper returned an invalid process record")
        }
        RemoteProcessEntry(
            pid = fields[1].toIntOrNull() ?: throw IOException("Remote management helper returned an invalid process id"),
            rssBytes = fields[2].toLongOrNull() ?: throw IOException("Remote management helper returned invalid process memory"),
            name = decodeUtf8(fields[3], "process name"),
        )
    }
}

private fun protocolLines(
    output: String,
    expectedType: String,
): List<String> {
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val first = lines.firstOrNull() ?: throw IOException("Remote management helper returned no output")
    if (first.startsWith("$MANAGEMENT_ERROR\t")) {
        val encodedMessage = first.substringAfter('\t')
        throw IOException(decodeUtf8(encodedMessage, "error"))
    }
    if (first != "$MANAGEMENT_HEADER\t$MANAGEMENT_PROTOCOL_VERSION\t$expectedType") {
        throw IOException("Remote management helper returned an unexpected response")
    }
    return lines.drop(1)
}

private fun Dadb.invokeManagementHelper(
    command: String,
    args: List<String>,
    localHelperJar: File,
    remoteHelperPath: String,
): AdbShellResponse {
    require(localHelperJar.isFile) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    val invocation =
        buildString {
            append("CLASSPATH=")
            append(managementShellQuote(remoteHelperPath))
            append(" exec app_process / ")
            append(MANAGEMENT_MAIN_CLASS)
            append(' ')
            append(managementShellQuote(MANAGEMENT_PROTOCOL_VERSION))
            append(' ')
            append(managementShellQuote(command))
            args.forEach { argument ->
                append(' ')
                append(managementShellQuote(argument))
            }
        }
    val guardedInvocation =
        "if [ ! -f ${managementShellQuote(remoteHelperPath)} ]; then echo $HELPER_MISSING; else $invocation; fi"

    fun execute(): AdbShellResponse = shell(guardedInvocation)

    val first = execute()
    if (isRecognizedManagementResponse(first)) {
        return first
    }

    push(localHelperJar, remoteHelperPath)
    val retry = execute()
    if (!isRecognizedManagementResponse(retry)) {
        throw IOException(
            buildString {
                append("Remote management helper remained unavailable after upload")
                retry.allOutput.trim().takeIf(String::isNotEmpty)?.let {
                    append(": ")
                    append(it)
                }
            },
        )
    }
    return retry
}

private fun isRecognizedManagementResponse(response: AdbShellResponse): Boolean {
    val firstLine = response.output.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine.startsWith("$MANAGEMENT_HEADER\t$MANAGEMENT_PROTOCOL_VERSION\t") ||
        firstLine.startsWith("$MANAGEMENT_ERROR\t")
}

private fun decodeUtf8(
    encoded: String,
    fieldName: String,
): String =
    encoded.decodeBase64()?.utf8()
        ?: throw IOException("Remote management helper returned invalid $fieldName data")

private fun managementShellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
