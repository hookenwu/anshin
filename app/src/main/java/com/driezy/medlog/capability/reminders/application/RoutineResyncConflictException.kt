package com.driezy.medlog.capability.reminders.application

import com.driezy.medlog.data.model.IllegalTimeOffsetException
import com.driezy.medlog.data.model.Medication
import com.driezy.medlog.data.model.TimeOffsetCheck
import com.driezy.medlog.domain.model.RoutineAnchor
import java.time.LocalTime

/**
 * 一行"偏移在当前作息下不可物化"的具名冲突（docs/medication-time-offset.md §3.7 第二张表）。
 *
 * 七个字段就是对话框逐行要显示的东西：哪一味药、哪个时段、偏移多少、基线是什么、
 * 解析后落在哪一分钟、为什么被拒（`reason` 决定用跨零点文案还是回绕锚点文案）。
 *
 * 偏移档位字段叫 `minutes`（不叫 `offsetMinutes`），与 `TimeOffsetCheck.Rejected.minutes` 同名：
 * 这是**展示用的档位**，不是从 `Medication` 行上读出来的列——INV-3 的结构断言（
 * `MedicationOffsetGateTest.no read path consumes the raw offset`）要求 `capability/reminders`
 * 下的代码不出现该列名，本类因此沿用校验结果的字段名。
 */
data class OffsetConflict(
    val medicationId: Long,
    val name: String,
    val anchor: RoutineAnchor?,
    val minutes: Int,
    val base: LocalTime?,
    val resolvedMinuteOfDay: Int,
    val reason: TimeOffsetCheck.Reason,
)

/** 把校验拒绝直接映射成具名冲突：不改写、不钳位、不重算，字段一一对应。 */
fun IllegalTimeOffsetException.toConflict(medication: Medication) = OffsetConflict(
    medicationId = medication.id,
    name = medication.name,
    anchor = check.anchor,
    minutes = check.minutes,
    base = check.base,
    resolvedMinuteOfDay = check.resolvedMinuteOfDay,
    reason = check.reason,
)

/**
 * 作息重算被整批拒绝：至少一味药的偏移在新作息下不可物化。
 *
 * **不是**"跳过这一味"的信号——调用方收到它时整批已经回滚（§3.5），库里没有任何一行被改成新作息。
 * 设置页**不得** catch（§9 ①）：吞掉它会得到"作息已改、钟点永久旧、无人知道"。
 */
class RoutineResyncConflictException(val conflicts: List<OffsetConflict>) :
    IllegalStateException(
        "Routine resync rejected the whole batch: " +
            conflicts.joinToString {
                "${it.name} ${it.anchor} ${it.minutes}min -> ${it.resolvedMinuteOfDay} (${it.reason})"
            },
    )
