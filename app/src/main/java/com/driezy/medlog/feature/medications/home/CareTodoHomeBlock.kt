package com.driezy.medlog.feature.medications.home

import com.driezy.medlog.data.model.CareTodo
import com.driezy.medlog.data.model.CareTodoStatus
import java.time.Instant
import java.time.ZoneId

/** 首页待办区块默认上限（docs/todos.md §3：默认最多 3 条，其余收进「查看全部」）。 */
const val HOME_TODO_MAX_VISIBLE = 3

/**
 * 首页待办的截止分桶（派生量，不落库）。
 *
 * - [OVERDUE]：`status == OPEN && dueAtMs != null && dueAtMs < now`；
 * - [DUE_TODAY]：`dueAtMs` 落在「今天」这一天且未逾期；
 * - [UNDATED]：无 `dueAtMs`，或截止日在今天之后（本期只区分这三档，见 docs/todos.md §3）。
 *
 * **无 `dueAtMs` 的待办永不逾期。**
 */
enum class HomeTodoDueBucket { OVERDUE, DUE_TODAY, UNDATED }

/** 已排好序、已判定分桶的一条待办（渲染层不再依赖时钟）。 */
data class HomeTodoRow(val todo: CareTodo, val bucket: HomeTodoDueBucket)

/** 首页区块的展示切片：[visible] 已按规则排序并封顶，[totalCount] 为全部未闭环条数。 */
data class HomeTodoBlock(val visible: List<HomeTodoRow>, val totalCount: Int) {
    val hasMore: Boolean get() = totalCount > visible.size

    val hiddenCount: Int get() = (totalCount - visible.size).coerceAtLeast(0)
}

/** 单条待办的截止分桶判定。 */
fun homeTodoDueBucket(todo: CareTodo, nowMs: Long, zone: ZoneId): HomeTodoDueBucket {
    val due = todo.dueAtMs ?: return HomeTodoDueBucket.UNDATED
    if (due < nowMs) return HomeTodoDueBucket.OVERDUE
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val dueDate = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    return if (dueDate == today) HomeTodoDueBucket.DUE_TODAY else HomeTodoDueBucket.UNDATED
}

/**
 * 首页区块排序：`逾期` > `今天截止` > `无截止`，同级按 `createdAtMs` 升序（早的在前），
 * 再以 `id` 兜底保证稳定序。只保留未闭环 [CareTodoStatus.OPEN]。
 */
fun orderCareTodosForHome(todos: List<CareTodo>, nowMs: Long, zone: ZoneId): List<CareTodo> = todos.asSequence()
    .filter { it.status == CareTodoStatus.OPEN }
    .sortedWith(
        compareBy(
            { homeTodoDueBucket(it, nowMs, zone).ordinal },
            { it.createdAtMs },
            { it.id },
        ),
    )
    .toList()

/**
 * 组装首页区块：**无未闭环待办时返回 null（整个区块不渲染，无空标题、无占位）**；
 * 否则返回封顶 [maxVisible] 条的切片（含分桶）与总数。
 */
fun buildHomeTodoBlock(
    todos: List<CareTodo>,
    nowMs: Long,
    zone: ZoneId,
    maxVisible: Int = HOME_TODO_MAX_VISIBLE,
): HomeTodoBlock? {
    val ordered = orderCareTodosForHome(todos, nowMs, zone)
    if (ordered.isEmpty()) return null
    return HomeTodoBlock(
        visible = ordered.take(maxVisible.coerceAtLeast(0))
            .map { HomeTodoRow(todo = it, bucket = homeTodoDueBucket(it, nowMs, zone)) },
        totalCount = ordered.size,
    )
}
