package dadb

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempFile
import kotlin.test.Test

class PlatformApiCompatTest {
    @Test
    fun readLocalFileModeUsesDefaultModeForRegularFile() {
        val file = createTempFile().toFile()
        try {
            file.setExecutable(false, false)

            assertThat(PlatformApiCompat.readLocalFileMode(file, DEFAULT_MODE))
                .isEqualTo(DEFAULT_MODE)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readLocalFileModePreservesExecutableAccess() {
        val file = createTempFile().toFile()
        try {
            check(file.setExecutable(true, true))

            assertThat(PlatformApiCompat.readLocalFileMode(file, DEFAULT_MODE))
                .isEqualTo(DEFAULT_MODE or EXECUTE_BITS)
        } finally {
            file.delete()
        }
    }

    @Test
    fun getOrPutReturnsExistingValueWithoutCreatingAnother() {
        val map = ConcurrentHashMap<String, String>()
        map["key"] = "existing"
        var createCount = 0

        val result =
            PlatformApiCompat.getOrPut(map, "key") {
                createCount++
                "created"
            }

        assertThat(result).isEqualTo("existing")
        assertThat(createCount).isEqualTo(0)
    }

    private companion object {
        const val DEFAULT_MODE = 0b110_100_100
        const val EXECUTE_BITS = 0b001_001_001
    }
}
