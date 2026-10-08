package com.driezy.medlog.feature.records

/** 记录中心的浏览模式（沿用本仓 chips 惯例，不引入嵌套 tab）。 */
enum class RecordsMode { ALL, DIARY, CARE_NOTES }

/** 点 ＋ 时的去向，由当前模式决定（docs/record-center-spec.md §3 D4）。 */
enum class RecordsAddTarget { TYPE_CHOICE, DIARY_EDITOR, CARE_NOTE_EDITOR }

/**
 * 「全部」→ 先选类型（2 次）；身心记录/照护笔记模式 → 直达该类型编辑器（1 次）。
 */
fun recordsAddTarget(mode: RecordsMode): RecordsAddTarget = when (mode) {
    RecordsMode.ALL -> RecordsAddTarget.TYPE_CHOICE
    RecordsMode.DIARY -> RecordsAddTarget.DIARY_EDITOR
    RecordsMode.CARE_NOTES -> RecordsAddTarget.CARE_NOTE_EDITOR
}

/**
 * 当前可用的模式。`enableSymptomDiary` 关闭时不得出现任何身心记录入口，
 * 故只保留照护笔记模式（照护笔记因此始终可达）。
 */
fun availableRecordsModes(diaryAvailable: Boolean): List<RecordsMode> = if (diaryAvailable) {
    listOf(
        RecordsMode.ALL,
        RecordsMode.DIARY,
        RecordsMode.CARE_NOTES,
    )
} else {
    listOf(RecordsMode.CARE_NOTES)
}

/**
 * 记录中心 UI 状态。
 *
 * `diaryAvailable` 反映 `enableSymptomDiary`：为 false 时身心记录相关模式被隐藏，且模式被
 * 收敛到照护笔记，保证「开关关闭时不出现任何身心记录面」的同时照护笔记仍可达。
 */
data class RecordsUiState(
    val mode: RecordsMode = RecordsMode.ALL,
    val query: String = "",
    val entries: List<RecordEntry> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val diaryAvailable: Boolean = true,
) {
    val availableModes: List<RecordsMode> get() = availableRecordsModes(diaryAvailable)

    val isSearching: Boolean get() = query.isNotBlank()

    /** 身心记录模式不提供搜索（现无此能力，本次不引入任何筛选）。 */
    val searchEnabled: Boolean get() = mode != RecordsMode.DIARY

    /** 空态：无任何记录时不渲染列表壳，只留引导语与 ＋。 */
    val showEmpty: Boolean get() = !isLoading && !failed && entries.isEmpty()
}

sealed interface RecordsUiAction {
    data class SetMode(val mode: RecordsMode) : RecordsUiAction

    data class QueryChanged(val query: String) : RecordsUiAction

    /** 由界面把 `enableSymptomDiary` 的当前值同步进来（关闭时收敛模式，隐藏身心记录面）。 */
    data class DiaryAvailabilityChanged(val available: Boolean) : RecordsUiAction
}
