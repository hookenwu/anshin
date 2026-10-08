package com.driezy.medlog.feature.records

import androidx.lifecycle.viewModelScope
import com.driezy.medlog.data.repository.CareNoteRepository
import com.driezy.medlog.data.repository.SymptomRepository
import com.driezy.medlog.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 记录中心 ViewModel（只读浏览 + 路由）。
 *
 * 数据边界：身心记录（[SymptomRepository]）与照护笔记（[CareNoteRepository]）各自独立成表、
 * 独立编辑、独立校验；本页只读取、不写入、不合并模型（docs/record-center-spec.md §3 D2）。
 *
 * 搜索词经 [flatMapLatest] 驱动照护笔记仓库查询：空词 → 默认列表（`SUPERSEDED` 折叠）；
 * 非空 → 主动搜索（命中的 `SUPERSEDED` 也会返回，由卡片标「已被更新」）。该规则在「全部」与
 * 「照护笔记」模式内一致。身心记录的跨类型匹配是纯本地文本匹配（不新增筛选能力）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val careNoteRepository: CareNoteRepository,
    private val symptomRepository: SymptomRepository,
) : BaseViewModel() {

    private val mode = MutableStateFlow(RecordsMode.ALL)
    private val query = MutableStateFlow("")
    private val diaryAvailable = MutableStateFlow(true)

    private val notes = query.flatMapLatest { current -> careNoteRepository.observeNotes(current) }

    val uiState = combine(
        mode,
        query,
        diaryAvailable,
        notes,
        symptomRepository.getAllLogs(),
    ) { currentMode, currentQuery, diary, noteRows, logs ->
        RecordsUiState(
            mode = currentMode,
            query = currentQuery,
            diaryAvailable = diary,
            entries = RecordCenterPresentation.entries(currentMode, currentQuery, noteRows, logs),
            isLoading = false,
        )
    }
        .catch { emit(RecordsUiState(isLoading = false, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordsUiState())

    fun onAction(action: RecordsUiAction) {
        when (action) {
            is RecordsUiAction.SetMode -> mode.value = clampMode(action.mode, diaryAvailable.value)
            is RecordsUiAction.QueryChanged -> query.value = action.query
            is RecordsUiAction.DiaryAvailabilityChanged -> {
                diaryAvailable.value = action.available
                mode.value = clampMode(mode.value, action.available)
            }
        }
    }

    /** 身心记录不可用时，任何身心记录模式都收敛到照护笔记，保证不出现身心记录面。 */
    private fun clampMode(candidate: RecordsMode, diary: Boolean): RecordsMode =
        if (diary || candidate == RecordsMode.CARE_NOTES) candidate else RecordsMode.CARE_NOTES
}
