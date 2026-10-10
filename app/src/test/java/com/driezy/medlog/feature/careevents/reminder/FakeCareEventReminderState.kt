package com.driezy.medlog.feature.careevents.reminder

/**
 * 内存假件：可读可写状态，并可在「快照读完、再校验开始前」触发一次变更，
 * 用来验证 R12 的四要素发送前再校验。
 *
 * 引擎的读序固定为：activeRecipientId → newestAnchorMs → thresholdDays → isEnabled → nudgedDay（5 次快照读）。
 */
class FakeCareEventReminderState(
    var recipientId: Long = 1L,
    var enabled: Boolean = true,
    var threshold: Int = 3,
    var anchorMs: Long? = null,
    var nudged: Long? = null,
) : CareEventReminderStateSource {

    /** 在快照读完毕、再校验读开始之前执行一次（模拟期间并发变更）。 */
    var mutateBeforeRevalidation: (() -> Unit)? = null

    val markedDays = mutableListOf<Long>()

    private var reads = 0
    private var snapshotComplete = false

    private fun touch() {
        if (reads >= SNAPSHOT_SIZE && !snapshotComplete) {
            snapshotComplete = true
            mutateBeforeRevalidation?.invoke()
        }
        reads++
    }

    override suspend fun activeRecipientId(): Long {
        touch()
        return recipientId
    }

    override suspend fun isEnabled(recipientId: Long, kind: String): Boolean {
        touch()
        return enabled
    }

    override suspend fun thresholdDays(recipientId: Long, kind: String): Int {
        touch()
        return threshold
    }

    override suspend fun newestAnchorMs(recipientId: Long, kind: String): Long? {
        touch()
        return anchorMs
    }

    override suspend fun nudgedDay(recipientId: Long, kind: String): Long? {
        touch()
        return nudged
    }

    override suspend fun markNudged(recipientId: Long, kind: String, epochDay: Long) {
        markedDays += epochDay
        nudged = epochDay
    }

    private companion object {
        const val SNAPSHOT_SIZE = 5
    }
}
