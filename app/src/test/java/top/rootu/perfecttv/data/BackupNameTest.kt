package top.rootu.perfecttv.data

import org.junit.Assert.assertTrue
import org.junit.Test

class BackupNameTest {
    @Test fun fileNameHasTimestampAndExtension() {
        val n = Backup.fileName(1_794_759_682_000L)
        assertTrue(n, Regex("backup-\\d{14}\\.perfecttv").matches(n))
    }
}
