package dadb.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteAppDataProtocolTest {
    @Test
    fun `parses value missing and error fields without confusing a real zero`() {
        val output =
            listOf(
                "com.ss.android.ugc.aweme",
                "V:5oqW6Z+z",
                "V:1",
                "V:0",
                "V:L2RhdGEvYXBwL2Jhc2UuYXBr",
                "V:0",
                "E:cGVybWlzc2lvbiBkZW5pZWQ=",
                "M",
                "V:23",
                "V:34",
                "V:0",
                "M",
            ).joinToString("\t")

        val app = parseRemoteAppDataResponse(output).single()

        assertEquals("抖音", app.label)
        assertTrue(app.enabled)
        assertFalse(app.systemApp)
        assertEquals(0L, app.versionCode)
        assertTrue(app.hasValue(RemoteAppField.VersionCode))
        assertEquals(RemoteAppFieldStatus.Error, app.fieldResults.getValue(RemoteAppField.ApkSizeBytes).status)
        assertEquals("permission denied", app.fieldResults.getValue(RemoteAppField.ApkSizeBytes).errorReason)
        assertTrue(app.isMissing(RemoteAppField.VersionName))
        assertTrue(app.hasValue(RemoteAppField.FirstInstallTime))
        assertEquals(0L, app.firstInstallTime)
        assertTrue(app.isMissing(RemoteAppField.LastUpdateTime))
    }

    @Test
    fun `drops a malformed record without shifting neighboring fields`() {
        val valid =
            listOf(
                "com.example.valid",
                "V:VmFsaWQ=",
                "V:1",
                "V:0",
                "M",
                "M",
                "M",
                "M",
                "M",
                "M",
                "M",
                "M",
            ).joinToString("\t")
        val malformed = valid.replaceFirst("V:1", "UNKNOWN")

        val apps = parseRemoteAppDataResponse("$malformed\n$valid")

        assertEquals(listOf("com.example.valid"), apps.map(RemoteAppData::packageName))
    }
}
