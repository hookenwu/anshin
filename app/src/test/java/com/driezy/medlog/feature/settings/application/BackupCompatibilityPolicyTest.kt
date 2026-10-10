package com.driezy.medlog.feature.settings.application

import com.driezy.medlog.data.local.DatabaseSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompatibilityPolicyTest {
    @Test
    fun `current schema backup is restorable`() {
        assertTrue(BackupCompatibilityPolicy.canRestore(DatabaseSchema.VERSION))
    }

    @Test
    fun `older schema backup is restorable through Room migrations`() {
        assertTrue(BackupCompatibilityPolicy.canRestore(5))
    }

    @Test
    fun `restored v24 backup is admitted and upgraded to the current schema`() {
        // v24 备份上界跟随 DatabaseSchema.VERSION：bump 到 25 后自动放行，
        // 恢复的 v24 库在 App 首次打开时执行 MIGRATION_24_25。
        assertEquals(25, DatabaseSchema.VERSION)
        assertTrue(BackupCompatibilityPolicy.canRestore(21))
        assertTrue(BackupCompatibilityPolicy.canRestore(22))
        assertTrue(BackupCompatibilityPolicy.canRestore(23))
        assertTrue(BackupCompatibilityPolicy.canRestore(24))
    }

    @Test
    fun `future and invalid schema backups are rejected`() {
        assertFalse(BackupCompatibilityPolicy.canRestore(DatabaseSchema.VERSION + 1))
        assertFalse(BackupCompatibilityPolicy.canRestore(0))
        assertFalse(BackupCompatibilityPolicy.canRestore(4))
    }
}
