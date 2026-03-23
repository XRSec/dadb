package dadb

import com.google.common.truth.Truth.assertThat
import dadb.adbserver.AdbForwardRule
import dadb.adbserver.buildHostForwardCommand
import dadb.adbserver.parseForwardListOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class AdbServerForwardCommandTest {

    @Test
    fun buildHostForwardCommand_defaultsToGenericHostPrefix() {
        assertEquals("host:list-forward", buildHostForwardCommand("list-forward"))
    }

    @Test
    fun buildHostForwardCommand_supportsSerialScopedHostPrefix() {
        assertEquals(
            "host-serial:SERIAL123:killforward:tcp:27183",
            buildHostForwardCommand("killforward:tcp:27183", serial = "SERIAL123"),
        )
    }

    @Test
    fun buildHostForwardCommand_rejectsBlankCommand() {
        val error = assertFailsWith<IllegalArgumentException> {
            buildHostForwardCommand("   ")
        }

        assertThat(error).hasMessageThat().contains("command must not be blank")
    }

    @Test
    fun parseForwardListOutput_parsesAllColumns() {
        val rules =
            parseForwardListOutput(
                """
                SERIAL123 tcp:27183 localabstract:scrcpy
                emulator-5554 tcp:27184 tcp:12345
                """.trimIndent(),
            )

        assertThat(rules).containsExactly(
            AdbForwardRule(serial = "SERIAL123", local = "tcp:27183", remote = "localabstract:scrcpy"),
            AdbForwardRule(serial = "emulator-5554", local = "tcp:27184", remote = "tcp:12345"),
        )
    }

    @Test
    fun parseForwardListOutput_ignoresMalformedRows() {
        val rules =
            parseForwardListOutput(
                """
                malformed-row

                SERIAL123 tcp:27183 tcp:12345
                """.trimIndent(),
            )

        assertThat(rules).containsExactly(
            AdbForwardRule(serial = "SERIAL123", local = "tcp:27183", remote = "tcp:12345"),
        )
    }
}
