package com.driezy.medlog.feature.medications.home

import com.driezy.medlog.data.model.CareTask
import com.driezy.medlog.data.model.CareTaskLog
import com.driezy.medlog.data.model.CareTaskLogStatus
import com.driezy.medlog.data.model.CareTaskScheduleKind
import com.driezy.medlog.data.model.LogStatus
import com.driezy.medlog.feature.caretasks.todayOccurrences
import java.time.Instant
import java.time.ZoneId

/**
 * 今日时间轴的目标类型（docs/care-tasks.md §4）。
 *
 * 药物与照护事项共用同一条时间轴，条目 key = `"<type>:<id>:<slot>:<scheduled>"`。
 */
enum class TodayTargetType { MEDICATION, CARE_TASK }

/** 时间轴上一条目的完成态（两类实体归一，供筛选/进度/呈现共用）。 */
enum class TodayItemStatus { PENDING, TAKEN, PARTIAL, SKIPPED, IN_PROGRESS }

/** 照护事项在时间轴上的载荷：定义 + 该时间槽的记录（可为空 = 未记录）。 */
data class CareTaskTimelineData(val task: CareTask, val log: CareTaskLog?)

/**
 * 今日时间轴的**唯一输入**（docs/care-tasks.md §4）。
 *
 * 药物与照护事项各由一个映射器产出 [TodayItem]（见 [medicationsToTodayItems] /
 * [careTasksToTodayItems]），渲染层只认这个类型，因此不存在第二套列表 UI。
 *
 * 具体呈现所需的数据挂在 [medication] / [careTask] 载荷上，渲染时按 [targetType] 分派。
 */
data class TodayItem(
    val targetType: TodayTargetType,
    val targetId: Long,
    val slotIndex: Int,
    val scheduledAtMs: Long,
    val status: TodayItemStatus,
    val label: String,
    val category: String,
    /** 用于「现在/稍后」切分与按时间排序；药物沿用既有 `scheduledMinuteOfDay()`，照护事项取本地钟点。 */
    val scheduledMinuteOfDay: Int,
    val medication: MedicationWithStatus? = null,
    val careTask: CareTaskTimelineData? = null,
) {
    /** 稳定条目 key：`"<type>:<id>:<slot>:<scheduled>"`。 */
    val listKey: String get() = "$targetType:$targetId:$slotIndex:$scheduledAtMs"

    val isHandled: Boolean
        get() = status == TodayItemStatus.TAKEN ||
            status == TodayItemStatus.PARTIAL ||
            status == TodayItemStatus.SKIPPED

    val isMedication: Boolean get() = targetType == TodayTargetType.MEDICATION

    val isCareTask: Boolean get() = targetType == TodayTargetType.CARE_TASK
}

/** 分类分组时无分类项的哨兵键（Compose UI 层解析为「其他」）。 */
const val TODAY_UNCATEGORIZED_KEY = "\u0000__uncategorized__"

/** 中成药相关分类排序靠前，其次按分类名。 */
private val TODAY_TCM_CATEGORY_KEYWORDS = listOf(
    "理气", "补益", "清热", "祛湿", "活血", "止咳", "安神", "妇科", "骨伤", "外科",
)

/**
 * 药物映射器：把用药今日排期转成 [TodayItem]。
 *
 * **严格保持入参顺序**（既有的用药时间轴顺序 = 计划器按药归组后的顺序），
 * 以保证「零照护事项时时间轴与改造前逐字节一致」这一硬不变式。
 * PRN 按需药物不进入时间轴（仍由独立的 PRN 区域渲染）；
 * 已归档（停用）药物一律不进入今日计划——这里是今日计划/进度的最后一道契约，
 * 即便上游误传入含归档的列表也不会泄漏。
 */
fun medicationsToTodayItems(items: List<MedicationWithStatus>): List<TodayItem> =
    items.filterNot { it.medication.isPRN || it.medication.isArchived }.map { item ->
        TodayItem(
            targetType = TodayTargetType.MEDICATION,
            targetId = item.medication.id,
            slotIndex = item.timeSlotIndex,
            scheduledAtMs = item.scheduledAtMs ?: 0L,
            status = item.todayStatus(),
            label = item.medication.name,
            category = item.medication.category,
            scheduledMinuteOfDay = item.scheduledMinuteOfDay(),
            medication = item,
        )
    }

private fun MedicationWithStatus.todayStatus(): TodayItemStatus = when (log?.status) {
    LogStatus.TAKEN -> TodayItemStatus.TAKEN
    LogStatus.PARTIAL -> TodayItemStatus.PARTIAL
    LogStatus.SKIPPED -> TodayItemStatus.SKIPPED
    else -> TodayItemStatus.PENDING
}

/**
 * 照护事项映射器：把今日排期展开成 [TodayItem]。
 *
 * 复用 [com.driezy.medlog.feature.caretasks.todayOccurrences] 的领域展开（不新建发生器）；
 * AS_NEEDED 无固定时刻，不进入时间轴。按 `(careTaskId, scheduledTimeMs)` 命中当日记录。
 */
fun careTasksToTodayItems(tasks: List<CareTask>, logs: List<CareTaskLog>, zone: ZoneId, nowMs: Long): List<TodayItem> =
    tasks.asSequence()
        .filterNot { it.isArchived }
        .filterNot { it.scheduleKind == CareTaskScheduleKind.AS_NEEDED }
        .flatMap { task ->
            task.todayOccurrences(zone, nowMs).map { occurrence ->
                val scheduledMs = occurrence.scheduledAt.toEpochMilli()
                val log = logs.firstOrNull {
                    it.careTaskId == task.id && it.scheduledTimeMs == scheduledMs
                }
                TodayItem(
                    targetType = TodayTargetType.CARE_TASK,
                    targetId = task.id,
                    slotIndex = occurrence.slotIndex,
                    scheduledAtMs = scheduledMs,
                    status = log.todayStatus(),
                    label = task.title,
                    category = task.category,
                    scheduledMinuteOfDay = scheduledMs
                        .let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toSecondOfDay() / 60 },
                    careTask = CareTaskTimelineData(task, log),
                )
            }
        }
        .toList()

/**
 * 按需（`AS_NEEDED`）照护事项映射器：产出「按需照护」专区的 [TodayItem]。
 *
 * 与时间轴**分离**：AS_NEEDED 无固定时刻，不进入 [mergeTodayItems] 的时间序列，
 * 也不计入 [HomeUiState.overallTotal]（对齐 PRN 药物不计入依从率的处理）。专区独立渲染。
 *
 * 记录按 `careTaskId` 取当日最新一条命中，因此专区行在刷新之间保持稳定；
 * 首次记录前的 [TodayItem.scheduledAtMs] 取 [nowMs]，用作「此刻完成」的日志锚点。
 */
fun careTasksToAsNeededItems(
    tasks: List<CareTask>,
    logs: List<CareTaskLog>,
    zone: ZoneId,
    nowMs: Long,
): List<TodayItem> = tasks.asSequence()
    .filterNot { it.isArchived }
    .filter { it.scheduleKind == CareTaskScheduleKind.AS_NEEDED }
    .map { task ->
        val log = logs.filter { it.careTaskId == task.id }.maxByOrNull { it.scheduledTimeMs }
        val anchorMs = log?.scheduledTimeMs ?: nowMs
        TodayItem(
            targetType = TodayTargetType.CARE_TASK,
            targetId = task.id,
            slotIndex = 0,
            scheduledAtMs = anchorMs,
            status = log.todayStatus(),
            label = task.title,
            category = task.category,
            scheduledMinuteOfDay = Instant.ofEpochMilli(anchorMs)
                .atZone(zone)
                .toLocalTime()
                .toSecondOfDay() / 60,
            careTask = CareTaskTimelineData(task, log),
        )
    }
    .toList()

private fun CareTaskLog?.todayStatus(): TodayItemStatus = when (this?.status) {
    CareTaskLogStatus.DONE -> TodayItemStatus.TAKEN
    CareTaskLogStatus.SKIPPED -> TodayItemStatus.SKIPPED
    CareTaskLogStatus.IN_PROGRESS -> TodayItemStatus.IN_PROGRESS
    else -> TodayItemStatus.PENDING
}

private val TODAY_ITEM_ORDER = compareBy<TodayItem>(
    { it.scheduledMinuteOfDay },
    { it.targetType.ordinal },
    { it.label },
    { it.targetId },
    { it.slotIndex },
)

/**
 * 同一条时间轴的合并器：用药序列原样保留，照护事项按时间并入。
 *
 * 硬不变式的关键：**零照护事项时走快路径，直接返回用药序列**，
 * 不排序、不重排，因此与改造前的用药时间轴逐项一致（同序、同动作、同进度）。
 * 有照护事项时按时间单调混排（验收 §3）。
 */
fun mergeTodayItems(medicationItems: List<TodayItem>, careTaskItems: List<TodayItem>): List<TodayItem> {
    if (careTaskItems.isEmpty()) return medicationItems
    return (medicationItems + careTaskItems).sortedWith(TODAY_ITEM_ORDER)
}

/**
 * 分类分组（药物与照护事项按各自 category 归并；同名分类会合并到一组）。
 * 全部无分类时返回单个 `""` 分组，供扁平渲染。
 */
fun groupTodayItemsByCategory(items: List<TodayItem>): List<Pair<String, List<TodayItem>>> {
    if (items.isEmpty()) return emptyList()
    if (items.none { it.category.isNotBlank() }) return listOf("" to items)
    return items
        .groupBy { it.category.ifBlank { TODAY_UNCATEGORIZED_KEY } }
        .entries
        .sortedWith(
            compareBy(
                { entry -> if (TODAY_TCM_CATEGORY_KEYWORDS.any { entry.key.contains(it) }) 0 else 1 },
                { it.key },
            ),
        )
        .map { it.key to it.value }
}

/** 今日时间轴筛选（全部 / 用药 / 照护 / 照护子类）。 */
sealed interface TodayFilter {
    val id: String

    data object All : TodayFilter {
        override val id: String get() = "all"
    }

    data object Medication : TodayFilter {
        override val id: String get() = "med"
    }

    data object CareTask : TodayFilter {
        override val id: String get() = "care"
    }

    data class CareCategory(val category: String) : TodayFilter {
        override val id: String get() = "care:$category"
    }
}

/** 该条目是否命中筛选。 */
fun TodayFilter.matches(item: TodayItem): Boolean = when (this) {
    TodayFilter.All -> true
    TodayFilter.Medication -> item.isMedication
    TodayFilter.CareTask -> item.isCareTask
    is TodayFilter.CareCategory -> item.targetType == TodayTargetType.CARE_TASK && item.category == category
}
