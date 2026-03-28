package dadb.helper

import dadb.Dadb
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

class RemoteAppIconData(
    val label: String,
    val iconHash: String,
    val imageBytes: ByteArray?,
    val changed: Boolean,
)

data class RemoteAppIconBatchRequest(
    val packageName: String,
    val localHash: String?,
)

class RemoteChangedAppIcon(
    val packageName: String,
    val label: String,
    val iconHash: String,
    val imageBytes: ByteArray?,
)

data class RemoteAppIconBatchData(
    val entries: List<RemoteChangedAppIcon>,
)

data class RemoteAppListItem(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val systemApp: Boolean,
    val sourceDir: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
)

data class RemoteHelperProbeResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class RemoteHelperFileState(
    val exists: Boolean,
    val detail: String,
)

private const val DEFAULT_REMOTE_HELPER_PATH = "/data/local/tmp/dadb-icon-helper-v3.jar"

@Throws(IOException::class)
fun Dadb.loadAppIconWithHelper(
    packageName: String,
    localHash: String? = null,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteAppIconData {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }

    val response =
        invokeRemoteHelper(
            args =
                buildList {
                    add("icon")
                    add(packageName)
                    localHash?.takeIf { it.isNotBlank() }?.let(::add)
                },
            remoteHelperPath = remoteHelperPath,
        )

    if (response.exitCode != 0) {
        throw IOException(
            buildString {
                append("Remote app icon helper failed")
                append(" (exit=")
                append(response.exitCode)
                append(")")
                val stdout = response.output.trim()
                if (stdout.isNotBlank()) {
                    append(" stdout=")
                    append(stdout)
                }
                val stderr = response.errorOutput.trim()
                if (stderr.isNotBlank()) {
                    append(" stderr=")
                    append(stderr)
                }
            },
        )
    }

    val lines =
        response.output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    if (lines.size < 3) {
        throw IOException("Remote app icon helper returned unexpected output")
    }

    val changed = lines[0] == "CHANGED"
    val iconHash = lines[1]
    val label =
        String(
            lines[2].decodeBase64()?.toByteArray()
                ?: throw IOException("Remote app icon helper returned invalid label payload"),
            Charsets.UTF_8,
        )
    val imageBytes =
        if (changed) {
            lines.getOrNull(3)?.decodeBase64()?.toByteArray()
                ?: throw IOException("Remote app icon helper missing changed icon payload")
        } else {
            null
        }

    return RemoteAppIconData(
        label = label,
        iconHash = iconHash,
        imageBytes = imageBytes,
        changed = changed,
    )
}

@Throws(IOException::class)
fun Dadb.loadAppIconBatchWithHelper(
    requests: List<RemoteAppIconBatchRequest>,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteAppIconBatchData {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    if (requests.isEmpty()) {
        return RemoteAppIconBatchData(entries = emptyList())
    }

    val requestPayload =
        requests.joinToString(separator = "\n") { request ->
            buildString {
                append(request.packageName)
                append('\t')
                append(request.localHash.orEmpty())
            }
        }
    val encodedRequest = requestPayload.toByteArray(Charsets.UTF_8).toByteString().base64()

    val response =
        invokeRemoteHelper(
            args = listOf("icons", encodedRequest),
            remoteHelperPath = remoteHelperPath,
        )

    if (response.exitCode != 0) {
        throw IOException(
            buildString {
                append("Remote app icon batch helper failed")
                append(" (exit=")
                append(response.exitCode)
                append(")")
                val stdout = response.output.trim()
                if (stdout.isNotBlank()) {
                    append(" stdout=")
                    append(stdout)
                }
                val stderr = response.errorOutput.trim()
                if (stderr.isNotBlank()) {
                    append(" stderr=")
                    append(stderr)
                }
            },
        )
    }

    val lines =
        response.output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    if (lines.size < 3 || lines[0] != "BATCH") {
        throw IOException("Remote app icon batch helper returned unexpected output")
    }

    val manifestLines =
        if (lines[1] == "-") {
            emptyList()
        } else {
            String(
                lines[1].decodeBase64()?.toByteArray()
                    ?: throw IOException("Remote app icon batch helper returned invalid manifest payload"),
                Charsets.UTF_8,
            ).lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
        }

    if (manifestLines.isEmpty()) {
        return RemoteAppIconBatchData(entries = emptyList())
    }

    val zipEntries =
        if (lines[2] == "-") {
            emptyMap()
        } else {
            unzipEntries(
                lines[2].decodeBase64()?.toByteArray()
                    ?: throw IOException("Remote app icon batch helper returned invalid zip payload"),
            )
        }
    val entries =
        manifestLines.map { line ->
            val parts = line.split('\t')
            if (parts.size < 4) {
                throw IOException("Remote app icon batch helper returned unexpected manifest line: $line")
            }
            val packageName = parts[0]
            val label =
                String(
                    parts[1].decodeBase64()?.toByteArray()
                        ?: throw IOException("Remote app icon batch helper returned invalid label payload"),
                    Charsets.UTF_8,
                )
            val iconHash = parts[2]
            val entryName = parts[3]
            val bytes =
                if (entryName == "-") {
                    null
                } else {
                    zipEntries[entryName]
                        ?: throw IOException("Remote app icon batch helper missing zip entry: $entryName")
                }
            RemoteChangedAppIcon(
                packageName = packageName,
                label = label,
                iconHash = iconHash,
                imageBytes = bytes,
            )
        }

    return RemoteAppIconBatchData(entries = entries)
}

@Throws(IOException::class)
fun Dadb.loadAppListPageWithHelper(
    offset: Int,
    limit: Int,
    includeSystem: Boolean,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): List<RemoteAppListItem> {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }

    val response =
        invokeRemoteHelper(
            args = listOf("list", offset.toString(), limit.toString(), if (includeSystem) "1" else "0"),
            remoteHelperPath = remoteHelperPath,
        )

    if (response.exitCode != 0) {
        throw IOException(
            buildString {
                append("Remote app list helper failed")
                append(" (exit=")
                append(response.exitCode)
                append(")")
                val stdout = response.output.trim()
                if (stdout.isNotBlank()) {
                    append(" stdout=")
                    append(stdout)
                }
                val stderr = response.errorOutput.trim()
                if (stderr.isNotBlank()) {
                    append(" stderr=")
                    append(stderr)
                }
            },
        )
    }

    return response.output
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { line ->
            val parts = line.split('\t')
            if (parts.size < 7) {
                throw IOException("Remote app list helper returned unexpected line: $line")
            }
            RemoteAppListItem(
                packageName = parts[0],
                label =
                    String(
                        parts[1].decodeBase64()?.toByteArray()
                            ?: throw IOException("Remote app list helper returned invalid label payload"),
                        Charsets.UTF_8,
                    ),
                enabled = parts[2] == "1",
                systemApp = parts[3] == "1",
                sourceDir =
                    String(
                        parts[4].decodeBase64()?.toByteArray()
                            ?: throw IOException("Remote app list helper returned invalid sourceDir payload"),
                        Charsets.UTF_8,
                    ),
                versionCode = parts[5].toLongOrNull() ?: 0L,
                lastUpdateTime = parts[6].toLongOrNull() ?: 0L,
            )
        }.toList()
}

@Throws(IOException::class)
fun Dadb.ensureRemoteAppIconHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
) {
    push(localHelperJar, remoteHelperPath) // 每次都覆盖
    val exists =
        runCatching {
            shell("test -f ${shellQuote(remoteHelperPath)} && echo 1 || echo 0")
                .output
                .trim()
                .equals("1")
        }.getOrNull()

    if (exists != true) {
        push(localHelperJar, remoteHelperPath)
    }
}

@Throws(IOException::class)
fun Dadb.prepareRemoteAppIconHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteHelperFileState {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    val beforeState = inspectRemoteAppHelperFile(remoteHelperPath)
    if (!beforeState.exists) {
        ensureRemoteAppIconHelper(localHelperJar, remoteHelperPath)
        return inspectRemoteAppHelperFile(remoteHelperPath)
    }
    return beforeState
}

@Throws(IOException::class)
fun Dadb.inspectRemoteAppHelperFile(remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH): RemoteHelperFileState {
    val response =
        shell(
            "if [ -f ${shellQuote(remoteHelperPath)} ]; then " +
                "echo EXISTS; ls -l ${shellQuote(remoteHelperPath)}; " +
                "else echo MISSING; fi",
        )
    val stdout = response.output.trim()
    val exists = stdout.lineSequence().firstOrNull() == "EXISTS"
    return RemoteHelperFileState(
        exists = exists,
        detail = stdout,
    )
}

@Throws(IOException::class)
fun Dadb.runRemoteAppHelperProbe(
    command: String,
    args: List<String> = emptyList(),
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteHelperProbeResult {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    prepareRemoteAppIconHelper(localHelperJar, remoteHelperPath)
    val response = invokeRemoteHelper(listOf(command) + args, remoteHelperPath)
    return RemoteHelperProbeResult(
        command =
            buildString {
                append(command)
                if (args.isNotEmpty()) {
                    append(' ')
                    append(args.joinToString(" "))
                }
            },
        exitCode = response.exitCode,
        stdout = response.output.trim(),
        stderr = response.errorOutput.trim(),
    )
}

private fun Dadb.invokeRemoteHelper(
    args: List<String>,
    remoteHelperPath: String,
) = shell(
    buildString {
        append("CLASSPATH=")
        append(shellQuote(remoteHelperPath))
        append(" app_process / dadb.helper.AppIconExportMain")
        args.forEach { arg ->
            append(' ')
            append(shellQuote(arg))
        }
    },
)

private fun unzipEntries(zipBytes: ByteArray): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                output.write(buffer, 0, read)
            }
            entries[entry.name] = output.toByteArray()
            input.closeEntry()
        }
    }
    return entries
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
