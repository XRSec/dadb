package dadb.adbserver

import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

data class AdbForwardRule(
    val serial: String,
    val local: String,
    val remote: String,
)

@Throws(IOException::class)
@JvmOverloads
fun AdbServer.listForwards(
    adbServerHost: String = "localhost",
    adbServerPort: Int = 5037,
    serial: String? = null,
): List<AdbForwardRule> {
    val output = queryHostService(adbServerHost, adbServerPort, buildHostForwardCommand("list-forward", serial))
    return parseForwardListOutput(output)
}

@Throws(IOException::class)
@JvmOverloads
fun AdbServer.forward(
    local: String,
    remote: String,
    adbServerHost: String = "localhost",
    adbServerPort: Int = 5037,
    serial: String? = null,
    noRebind: Boolean = false,
): String? {
    require(local.isNotBlank()) { "local must not be blank" }
    require(remote.isNotBlank()) { "remote must not be blank" }
    val prefix = if (noRebind) "forward:norebind:" else "forward:"
    val payload =
        queryHostService(
            adbServerHost,
            adbServerPort,
            buildHostForwardCommand("$prefix$local;$remote", serial),
        )
    return payload.ifBlank { null }
}

@Throws(IOException::class)
@JvmOverloads
fun AdbServer.killForward(
    local: String,
    adbServerHost: String = "localhost",
    adbServerPort: Int = 5037,
    serial: String? = null,
) {
    require(local.isNotBlank()) { "local must not be blank" }
    queryHostService(adbServerHost, adbServerPort, buildHostForwardCommand("killforward:$local", serial))
}

@Throws(IOException::class)
@JvmOverloads
fun AdbServer.killAllForwards(
    adbServerHost: String = "localhost",
    adbServerPort: Int = 5037,
    serial: String? = null,
) {
    queryHostService(adbServerHost, adbServerPort, buildHostForwardCommand("killforward-all", serial))
}

internal fun buildHostForwardCommand(
    command: String,
    serial: String? = null,
): String {
    require(command.isNotBlank()) { "command must not be blank" }
    return if (serial.isNullOrBlank()) {
        "host:$command"
    } else {
        "host-serial:$serial:$command"
    }
}

internal fun parseForwardListOutput(output: String): List<AdbForwardRule> {
    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size < 3) {
                null
            } else {
                AdbForwardRule(
                    serial = fields[0],
                    local = fields[1],
                    remote = fields[2],
                )
            }
        }
        .toList()
}

private fun queryHostService(
    adbServerHost: String,
    adbServerPort: Int,
    command: String,
): String {
    AdbBinary.ensureServerRunning(adbServerHost, adbServerPort)
    return Socket(adbServerHost, adbServerPort).use { socket ->
        AdbServer.send(socket, command)
        AdbServer.readString(DataInputStream(socket.getInputStream()))
    }
}
