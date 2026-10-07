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
    fun `restored v21 backup is admitted and upgraded to the current schema`() {
        // v21 备份上界跟随 DatabaseSchema.VERSION：bump 到 23 后自动放行，
        // 恢复的 v21 库在 App 首次打开时依次执行 MIGRATION_21_22 与 MIGRATION_22_23。
        assertEquals(23, DatabaseSchema.VERSION)
        assertTrue(BackupCompatibilityPolicy.canRestore(21))
        assertTrue(BackupCompatibilityPolicy.canRestore(22))
    }

    @Test
    fun `future and invalid schema backups are rejected`() {
        assertFalse(BackupCompatibilityPolicy.canRestore(DatabaseSchema.VERSION + 1))
        assertFalse(BackupCompatibilityPolicy.canRestore(0))
        assertFalse(BackupCompatibilityPolicy.canRestore(4))
    }
}
