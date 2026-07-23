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

data class RemoteAppData(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val systemApp: Boolean,
    val sourceDir: String,
    val versionCode: Long,
    val apkSizeBytes: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val fieldResults: Map<RemoteAppField, RemoteAppFieldResult>,
) {
    val errors: List<String>
        get() =
            fieldResults.mapNotNull { (field, result) ->
                result.errorReason?.let { reason -> "${field.wireName}: $reason" }
            }

    fun hasValue(field: RemoteAppField): Boolean =
        fieldResults[field]?.status == RemoteAppFieldStatus.Value

    fun isMissing(field: RemoteAppField): Boolean =
        fieldResults[field]?.status == RemoteAppFieldStatus.Missing
}

enum class RemoteAppField(val wireName: String) {
    Label("label"),
    Enabled("enabled"),
    SystemApp("systemApp"),
    SourceDir("sourceDir"),
    VersionCode("versionCode"),
    ApkSizeBytes("apkSizeBytes"),
    VersionName("versionName"),
    MinSdk("minSdk"),
    TargetSdk("targetSdk"),
    FirstInstallTime("firstInstallTime"),
    LastUpdateTime("lastUpdateTime"),
}

enum class RemoteAppFieldStatus {
    Value,
    Missing,
    Error,
}

data class RemoteAppFieldResult(
    val status: RemoteAppFieldStatus,
    val errorReason: String? = null,
)

data class RemoteHelperQueryResult(
    val value: String,
    val error: String,
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

const val DADB_DEVICE_HELPER_VERSION = "1.3.1"
const val DEFAULT_REMOTE_HELPER_PATH = "/data/local/tmp/dadb-device-helper.jar"
private const val HELPER_MISSING_MARKER = "DADB_HELPER_MISSING"
private const val HELPER_VERSION_MISMATCH_MARKER = "DADB_HELPER_VERSION_MISMATCH"
private const val FORCE_PUSH =  false

@Throws(IOException::class)
fun Dadb.loadAppIconWithHelper(
    packageName: String,
    localHash: String? = null,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteAppIconData {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }

    val response =
        invokeRemoteHelperWithUploadRetry(
            args =
                buildList {
                    add("icon")
                    add(packageName)
                    localHash?.takeIf { it.isNotBlank() }?.let(::add)
                },
            remoteHelperPath = remoteHelperPath,
            localHelperJar = localHelperJar,
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
        invokeRemoteHelperWithUploadRetry(
            args = listOf("icons", encodedRequest),
            remoteHelperPath = remoteHelperPath,
            localHelperJar = localHelperJar,
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

    return parseAppIconBatchResponse(response.output)
}

@Throws(IOException::class)
fun Dadb.loadAppsWithHelper(
    includeUser: Boolean,
    includeSystem: Boolean,
    includeEnabled: Boolean,
    includeDisabled: Boolean,
    fields: Set<String>,
    packageNames: Set<String> = emptySet(),
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): List<RemoteAppData> {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    val response =
        invokeRemoteHelperWithUploadRetry(
            args =
                listOf(
                    "appdata",
                    includeUser.toHelperFlag(),
                    includeSystem.toHelperFlag(),
                    includeEnabled.toHelperFlag(),
                    includeDisabled.toHelperFlag(),
                    fields.sorted().joinToString(","),
                    packageNames
                        .sorted()
                        .joinToString("\n")
                        .takeIf(String::isNotEmpty)
                        ?.toByteArray(Charsets.UTF_8)
                        ?.toByteString()
                        ?.base64()
                        ?: "-",
                ),
            remoteHelperPath = remoteHelperPath,
            localHelperJar = localHelperJar,
        )
    if (response.exitCode != 0) {
        throw IOException(
            "Remote app batch helper failed (exit=${response.exitCode}) " +
                "stdout=${response.output.trim()} stderr=${response.errorOutput.trim()}",
        )
    }
    return parseRemoteAppDataResponse(response.output)
}

internal fun parseRemoteAppDataResponse(output: String): List<RemoteAppData> {
    val responseLines =
        output
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    val parsed =
        responseLines.mapNotNull { line ->
            runCatching {
                val parts = line.split('\t')
                require(parts.size >= 12)
                val label = parts[1].decodeAppTextField(RemoteAppField.Label)
                val enabled = parts[2].decodeAppBooleanField(RemoteAppField.Enabled)
                val systemApp = parts[3].decodeAppBooleanField(RemoteAppField.SystemApp)
                val sourceDir = parts[4].decodeAppTextField(RemoteAppField.SourceDir)
                val versionCode = parts[5].decodeAppLongField(RemoteAppField.VersionCode)
                val apkSizeBytes = parts[6].decodeAppLongField(RemoteAppField.ApkSizeBytes)
                val versionName = parts[7].decodeAppTextField(RemoteAppField.VersionName)
                val minSdk = parts[8].decodeAppIntField(RemoteAppField.MinSdk)
                val targetSdk = parts[9].decodeAppIntField(RemoteAppField.TargetSdk)
                val firstInstallTime = parts[10].decodeAppLongField(RemoteAppField.FirstInstallTime)
                val lastUpdateTime = parts[11].decodeAppLongField(RemoteAppField.LastUpdateTime)
                RemoteAppData(
                    packageName = parts[0],
                    label = label.value.orEmpty(),
                    enabled = enabled.value ?: false,
                    systemApp = systemApp.value ?: false,
                    sourceDir = sourceDir.value.orEmpty(),
                    versionCode = versionCode.value ?: 0L,
                    apkSizeBytes = apkSizeBytes.value ?: 0L,
                    versionName = versionName.value.orEmpty(),
                    minSdk = minSdk.value ?: 0,
                    targetSdk = targetSdk.value ?: 0,
                    firstInstallTime = firstInstallTime.value ?: 0L,
                    lastUpdateTime = lastUpdateTime.value ?: 0L,
                    fieldResults =
                        listOf(
                            label,
                            enabled,
                            systemApp,
                            sourceDir,
                            versionCode,
                            apkSizeBytes,
                            versionName,
                            minSdk,
                            targetSdk,
                            firstInstallTime,
                            lastUpdateTime,
                        ).associate { decoded -> decoded.field to decoded.result },
                )
            }.getOrNull()
        }
    if (parsed.isEmpty() && responseLines.isNotEmpty()) {
        throw IOException("Remote app data helper returned no valid records")
    }
    return parsed
}

@Throws(IOException::class)
fun Dadb.runQueriesWithHelper(
    queries: Map<String, String>,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): Map<String, RemoteHelperQueryResult> {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    if (queries.isEmpty()) return emptyMap()
    val requestPayload =
        queries.entries.joinToString("\n") { (key, command) ->
            "$key\t${command.toByteArray(Charsets.UTF_8).toByteString().base64()}"
        }
    val response =
        invokeRemoteHelperWithUploadRetry(
            args = listOf("queries", requestPayload.toByteArray(Charsets.UTF_8).toByteString().base64()),
            remoteHelperPath = remoteHelperPath,
            localHelperJar = localHelperJar,
        )
    if (response.exitCode != 0) {
        throw IOException(
            "Remote query batch helper failed (exit=${response.exitCode}) " +
                "stdout=${response.output.trim()} stderr=${response.errorOutput.trim()}",
        )
    }
    val responseLines =
        response.output
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    val parsed =
        responseLines.mapNotNull { line ->
            runCatching {
                val parts = line.split('\t')
                require(parts.size >= 3)
                parts[0] to
                    RemoteHelperQueryResult(
                        value = parts[1].decodeHelperText("query value"),
                        error = parts[2].decodeHelperText("query error"),
                    )
            }.getOrNull()
        }.toMap()
    if (parsed.isEmpty() && responseLines.isNotEmpty()) {
        throw IOException("Remote query batch helper returned no valid records")
    }
    return parsed
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
        invokeRemoteHelperWithUploadRetry(
            args = listOf("list", offset.toString(), limit.toString(), if (includeSystem) "1" else "0"),
            remoteHelperPath = remoteHelperPath,
            localHelperJar = localHelperJar,
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
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    invokeRemoteHelperWithUploadRetry(listOf("ping"), remoteHelperPath, localHelperJar)
}

@Throws(IOException::class)
fun Dadb.prepareRemoteAppIconHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteHelperFileState {
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    val response = invokeRemoteHelperWithUploadRetry(listOf("ping"), remoteHelperPath, localHelperJar)
    if (response.exitCode != 0 || response.output.trim() != "PING_OK") {
        throw IOException(
            "Remote helper readiness check failed (exit=${response.exitCode}) " +
                "stdout=${response.output.trim()} stderr=${response.errorOutput.trim()}",
        )
    }
    return RemoteHelperFileState(
        exists = true,
        detail = "Helper hash verified",
    )
}

@Throws(IOException::class)
fun Dadb.injectRemoteTextWithHelper(
    text: String,
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
) {
    require(text.isNotEmpty()) { "Text must not be empty" }
    require(localHelperJar.exists()) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    val encodedText = text.toByteArray(Charsets.UTF_8).toByteString().base64()
    val response =
        invokeRemoteHelperWithUploadRetry(
            args = listOf("input_text", encodedText),
            remoteHelperPath = remoteHelperPath,
            localHelperJar = localHelperJar,
        )
    if (response.exitCode != 0 || response.output.trim() != "INPUT_TEXT_OK") {
        throw IOException(
            "Remote text input helper failed (exit=${response.exitCode}) " +
                "stdout=${response.output.trim()} stderr=${response.errorOutput.trim()}",
        )
    }
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
    val response = invokeRemoteHelperWithUploadRetry(listOf(command) + args, remoteHelperPath, localHelperJar)
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

private fun Dadb.invokeRemoteHelperWithUploadRetry(
    args: List<String>,
    remoteHelperPath: String,
    localHelperJar: File,
) = run {
    val helperCommand =
        buildString {
            append("CLASSPATH=")
            append(shellQuote(remoteHelperPath))
            append(" exec app_process / dadb.helper.AppIconExportMain invoke ")
            append(shellQuote(DADB_DEVICE_HELPER_VERSION))
            args.forEach { arg ->
                append(' ')
                append(shellQuote(arg))
            }
        }
    val guardedCommand =
        buildString {
            append("if [ ! -f ")
            append(shellQuote(remoteHelperPath))
            append(" ]; then echo ")
            append(HELPER_MISSING_MARKER)
            append("; else ")
            append(helperCommand)
            append("; fi")
        }
    if (FORCE_PUSH) {
        // 每次都覆盖 push
        push(localHelperJar, remoteHelperPath)
    }
    fun execute() = shell(guardedCommand)

    val firstResponse = execute()
    if (!isHelperUnavailableOutput(firstResponse.output)) {
        return@run firstResponse
    }
    push(localHelperJar, remoteHelperPath)
    val retryResponse = execute()
    if (isHelperUnavailableOutput(retryResponse.output)) {
        throw IOException(
            "Remote helper remained unavailable after upload: ${retryResponse.output.trim()}",
        )
    }
    retryResponse
}

private fun isHelperUnavailableOutput(output: String): Boolean {
    val status = output.lineSequence().firstOrNull()?.trim().orEmpty()
    return status == HELPER_MISSING_MARKER || status == HELPER_VERSION_MISMATCH_MARKER
}

private fun parseAppIconBatchResponse(output: String): RemoteAppIconBatchData {
    val lines =
        output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
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
            ).lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        }
    if (manifestLines.isEmpty()) return RemoteAppIconBatchData(emptyList())

    val zipEntries =
        if (lines[2] == "-") {
            emptyMap()
        } else {
            unzipEntries(
                lines[2].decodeBase64()?.toByteArray()
                    ?: throw IOException("Remote app icon batch helper returned invalid zip payload"),
            )
        }
    return RemoteAppIconBatchData(
        entries =
            manifestLines.map { line ->
                val parts = line.split('\t')
                if (parts.size < 4) {
                    throw IOException("Remote app icon batch helper returned unexpected manifest line: $line")
                }
                val entryName = parts[3]
                RemoteChangedAppIcon(
                    packageName = parts[0],
                    label = parts[1].decodeHelperText("label"),
                    iconHash = parts[2],
                    imageBytes =
                        if (entryName == "-") null else zipEntries[entryName]
                            ?: throw IOException("Remote app icon batch helper missing zip entry: $entryName"),
                )
            },
    )
}

private data class DecodedRemoteAppField<T>(
    val field: RemoteAppField,
    val value: T?,
    val result: RemoteAppFieldResult,
)

private fun String.decodeAppTextField(field: RemoteAppField): DecodedRemoteAppField<String> =
    decodeAppField(field) { payload ->
        String(
            payload.decodeBase64()?.toByteArray()
                ?: throw IOException("Remote app helper returned invalid ${field.wireName} value"),
            Charsets.UTF_8,
        )
    }

private fun String.decodeAppLongField(field: RemoteAppField): DecodedRemoteAppField<Long> =
    decodeAppField(field) { payload ->
        payload.toLongOrNull()
            ?: throw IOException("Remote app helper returned invalid ${field.wireName} value")
    }

private fun String.decodeAppIntField(field: RemoteAppField): DecodedRemoteAppField<Int> =
    decodeAppField(field) { payload ->
        payload.toIntOrNull()
            ?: throw IOException("Remote app helper returned invalid ${field.wireName} value")
    }

private fun String.decodeAppBooleanField(field: RemoteAppField): DecodedRemoteAppField<Boolean> =
    decodeAppField(field) { payload ->
        when (payload) {
            "1" -> true
            "0" -> false
            else -> throw IOException("Remote app helper returned invalid ${field.wireName} value")
        }
    }

private inline fun <T> String.decodeAppField(
    field: RemoteAppField,
    decodeValue: (String) -> T,
): DecodedRemoteAppField<T> =
    when {
        this == "M" ->
            DecodedRemoteAppField(
                field = field,
                value = null,
                result = RemoteAppFieldResult(RemoteAppFieldStatus.Missing),
            )

        startsWith("V:") ->
            DecodedRemoteAppField(
                field = field,
                value = decodeValue(removePrefix("V:")),
                result = RemoteAppFieldResult(RemoteAppFieldStatus.Value),
            )

        startsWith("E:") -> {
            val reasonPayload = removePrefix("E:")
            val reason =
                String(
                    reasonPayload.decodeBase64()?.toByteArray()
                        ?: throw IOException("Remote app helper returned invalid ${field.wireName} error"),
                    Charsets.UTF_8,
                )
            DecodedRemoteAppField(
                field = field,
                value = null,
                result = RemoteAppFieldResult(RemoteAppFieldStatus.Error, reason),
            )
        }

        else -> throw IOException("Remote app helper returned unknown ${field.wireName} field state")
    }

private fun String.decodeHelperText(fieldName: String): String =
    if (this == "-") {
        ""
    } else {
        String(
            decodeBase64()?.toByteArray()
                ?: throw IOException("Remote app helper returned invalid $fieldName payload"),
            Charsets.UTF_8,
        )
    }

private fun Boolean.toHelperFlag(): String = if (this) "1" else "0"

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
