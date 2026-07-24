package dadb.helper

import dadb.Dadb
import java.io.File
import java.io.IOException

const val DADB_HELPER_VERSION = "1.4.2"
const val DEFAULT_REMOTE_HELPER_PATH = "/data/local/tmp/dadb-helper.jar"

private const val HELPER_VERSION_MAIN_CLASS = "dadb.helper.HelperVersionMain"
private const val HELPER_READY_MARKER = "DADB_HELPER_READY"
private const val HELPER_MISSING_MARKER = "DADB_HELPER_MISSING"
private const val FORCE_PUSH = false

@Throws(IOException::class)
fun Dadb.prepareRemoteDadbHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteHelperFileState {
    require(localHelperJar.isFile) { "Local helper jar not found: ${localHelperJar.absolutePath}" }
    val versionCommand =
        buildString {
            append("CLASSPATH=")
            append(helperShellQuote(remoteHelperPath))
            append(" exec app_process / ")
            append(HELPER_VERSION_MAIN_CLASS)
            append(' ')
            append(helperShellQuote(DADB_HELPER_VERSION))
        }
    val guardedCommand =
        "if [ ! -f ${helperShellQuote(remoteHelperPath)} ]; then " +
            "echo $HELPER_MISSING_MARKER; else $versionCommand; fi"

    fun execute() = shell(guardedCommand)

    if (FORCE_PUSH) {
        push(localHelperJar, remoteHelperPath)
    }
    val first = execute()
    if (isDadbHelperReady(first.output)) {
        return RemoteHelperFileState(exists = true, detail = "Helper version verified")
    }

    push(localHelperJar, remoteHelperPath)
    val retry = execute()
    if (!isDadbHelperReady(retry.output)) {
        throw IOException(
            "Remote helper remained unavailable after upload: ${retry.allOutput.trim()}",
        )
    }
    return RemoteHelperFileState(exists = true, detail = "Helper uploaded and version verified")
}

internal fun isDadbHelperReady(output: String): Boolean =
    output.lineSequence().firstOrNull()?.trim() == HELPER_READY_MARKER

private fun helperShellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
