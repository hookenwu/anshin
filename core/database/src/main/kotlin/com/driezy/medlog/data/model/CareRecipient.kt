package com.driezy.medlog.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 家庭成员（一级领域实体）。
 *
 * 一台设备可以管理多位用药人（爸爸 / 妈妈 / …）。`uuid` 是稳定标识，
 * 迁移与将来的跨设备同步都以它为准；`id` 只用于本地外键与查询。
 */
@Entity(
    tableName = "care_recipients",
    indices = [Index(value = ["uuid"], unique = true)],
)
data class CareRecipient(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 稳定标识，创建后永不修改。 */
    val uuid: String = newUuid(),
    val displayName: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 生成本地新成员标识；迁移与同步都以该值对齐。 */
        fun newUuid(): String = UUID.randomUUID().toString()
    }
}
