package com.driezy.medlog.capability.reminders.application

import android.util.Log
import com.driezy.medlog.data.model.PlanBoundaryNotAdvancedException
import com.driezy.medlog.data.recipient.ActiveRecipientStore
import com.driezy.medlog.data.repository.UserPreferencesRepository
import com.driezy.medlog.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动重算：被拒 / 被杀之后的**持久收敛点**（docs/medication-time-offset.md §3.6）。
 *
 * 启动路径只**发起**、不等待：`onCreate` 调 [schedule]，它在本类的 application 作用域协程里
 * 跑完整的一次重算；没有 `runBlocking`、没有 `await`，`onCreate` 不因它被拖住，也不因它失败被拖垮
 * （作用域是 `SupervisorJob`，见 `AppModule.provideApplicationScope`；本类再兜一层，异常不逃出协程）。
 *
 * 写路径与用户手改作息**完全相同**：读当前成员的已存作息 → [ResyncRemindersUseCase]（= B2 的
 * `planForRoutine` 单事务批量入口 + 提交后 reconcile）。没有第二套算术、没有"逐药跳过"、没有部分提交；
 * 这里**只**决定"失败怎么记"，不碰事务。
 *
 * 失败语义（§3.6 / §9 ① 的唯一例外）：
 * - `RoutineResyncConflictException` ⇒ [StartupRecomputeFailure.ILLEGAL_OFFSET]；
 * - `PlanBoundaryNotAdvancedException` ⇒ [StartupRecomputeFailure.PLAN_BOUNDARY]；
 * - 其余异常 ⇒ [StartupRecomputeFailure.OTHER]。
 *   三者都**不吞**：记日志（稳定 TAG + 分类 token + 冲突清单）并写进持久标记 [StartupRecomputeMarker]，
 *   下一次启动 / [convergeNow] 重试。抛出前整批已回滚，因此不存在"标记说失败、库里却有半批"。
 * - reconcile 在 [ResyncRemindersUseCase] 内部、**提交之后**才跑：失败时它根本不会执行，
 *   闹钟保持旧时刻，与 DB 逐字一致（W1/W3）。
 * - 取消（进程被杀 / 作用域取消）原样上抛、**不写标记**：事务由 Room 回滚，
 *   下一次启动从"库里仍是什么"重新判定即可。
 */
@Singleton
class RoutineStartupConvergence @Inject constructor(
    private val activeRecipient: ActiveRecipientStore,
    private val preferences: UserPreferencesRepository,
    private val resyncReminders: ResyncRemindersUseCase,
    private val marker: StartupRecomputeMarker,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** 串行化重复启动：并发的第二次在第一次之后跑，看到的是已提交的库 ⇒ 零写入。 */
    private val gate = Mutex()

    /**
     * 启动触发器（`MedLogApplication.onCreate` 调用）。**立即返回**：协程还没跑，
     * 调用方拿到的只是一个 [Job]，不等它、也不通过它处理结果。
     */
    fun schedule(): Job = scope.launch {
        try {
            convergeOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Throwable) {
            // 连标记都写不进去（DataStore 故障）时的最后一道：只记日志，不让异常碰到作用域/进程启动。
            Log.e(TAG, "startup recompute failed outside its classified handling", unexpected)
        }
    }

    /**
     * 与启动触发**同一条路径**的可等待入口：供诊断、测试与"再来一次"重试。
     * 它同样不吞异常之外的任何东西——失败被分类记录后正常返回，成功则返回这次的结果。
     */
    suspend fun convergeNow(): StartupRecomputeRecord = convergeOnce()

    private suspend fun convergeOnce(): StartupRecomputeRecord = gate.withLock {
        val recipientId = activeRecipient.current()
        if (recipientId == ActiveRecipientStore.NO_RECIPIENT) {
            // 没有成员 ⇒ 没有可重算的范围（与 resync 的取数范围一致）：不读作息、不写库、不重排。
            Log.i(TAG, "startup recompute skipped: no active recipient")
            return@withLock persistIfEdge(
                StartupRecomputeRecord(
                    status = StartupRecomputeStatus.SKIPPED_NO_RECIPIENT,
                    recipientId = recipientId,
                    atMs = clock.millis(),
                ),
            )
        }

        return@withLock when (val attempt = attempt(recipientId)) {
            is Attempt.Converged -> {
                Log.i(TAG, "startup recompute converged: advanced=${attempt.advanced} noOp=${attempt.noOp}")
                persistIfEdge(
                    StartupRecomputeRecord(
                        status = StartupRecomputeStatus.CONVERGED,
                        recipientId = recipientId,
                        advanced = attempt.advanced,
                        noOp = attempt.noOp,
                        atMs = clock.millis(),
                    ),
                )
            }

            is Attempt.Rejected -> recordFailure(
                recipientId = recipientId,
                failure = attempt.failure,
                conflicts = attempt.conflicts,
                error = attempt.error,
            )
        }
    }

    /**
     * 只跑写路径并分类；**不写标记**——标记写失败不能被误当成"重算失败"再记一次。
     * 取消（进程被杀）原样上抛：事务已回滚，这一次不需要在标记里留任何东西。
     */
    private suspend fun attempt(recipientId: Long): Attempt = try {
        val schedule = preferences.routineScheduleFor(recipientId)
        // 与用户手改作息完全同一条路径：B2 的单事务批量入口 + 提交后 reconcile。
        val result = resyncReminders(schedule)
        Attempt.Converged(advanced = result.advanced, noOp = result.noOp)
    } catch (conflict: RoutineResyncConflictException) {
        Attempt.Rejected(
            failure = StartupRecomputeFailure.ILLEGAL_OFFSET,
            conflicts = conflict.conflicts.map(OffsetConflict::describe),
            error = conflict,
        )
    } catch (boundary: PlanBoundaryNotAdvancedException) {
        Attempt.Rejected(StartupRecomputeFailure.PLAN_BOUNDARY, emptyList(), boundary)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (other: Throwable) {
        Attempt.Rejected(StartupRecomputeFailure.OTHER, emptyList(), other)
    }

    /**
     * 失败**总是**写标记：它是重试可见性（attempts）与"上次为什么没收敛"的唯一载体。
     * 日志带分类 token 与具名冲突清单，事后能定位到药。
     */
    private suspend fun recordFailure(
        recipientId: Long,
        failure: StartupRecomputeFailure,
        conflicts: List<String>,
        error: Throwable,
    ): StartupRecomputeRecord {
        val attempts = marker.latest()
            ?.takeIf { it.status == StartupRecomputeStatus.FAILED }
            ?.let { it.attempts + 1 }
            ?: 1
        val record = StartupRecomputeRecord(
            status = StartupRecomputeStatus.FAILED,
            recipientId = recipientId,
            failure = failure,
            conflicts = conflicts,
            detail = "${error.javaClass.simpleName}: ${error.message}",
            atMs = clock.millis(),
            attempts = attempts,
        )
        Log.w(
            TAG,
            "startup recompute did not converge: ${failure.logToken} attempts=$attempts " +
                "conflicts=${conflicts.ifEmpty { "none" }} detail=${record.detail}",
            error,
        )
        marker.write(record)
        return record
    }

    /**
     * 稳态不重写（边沿触发）：连续成功 / 连续无成员时 DataStore 零写入。
     * 失败走 [recordFailure]，每次都写（attempts 变了就是真的变了）。
     *
     * 返回值永远是**这一次**的结算（调用方要看到本次的 advanced/noOp），
     * 只是稳态时不再覆盖已存的那条同态记录。
     */
    private suspend fun persistIfEdge(record: StartupRecomputeRecord): StartupRecomputeRecord {
        val previous = marker.latest()
        val alreadyThere = previous != null &&
            previous.status == record.status &&
            previous.failure == record.failure &&
            previous.recipientId == record.recipientId
        if (!alreadyThere) marker.write(record)
        return record
    }

    /** 写路径的两种结局：成功（带计数）或已被分类的拒绝。 */
    private sealed interface Attempt {
        data class Converged(val advanced: Int, val noOp: Int) : Attempt

        data class Rejected(val failure: StartupRecomputeFailure, val conflicts: List<String>, val error: Throwable) :
            Attempt
    }

    private companion object {
        /** 稳定日志标签：按它 grep 即可拿到每一次启动重算的结局。 */
        const val TAG = "RoutineStartupConvergence"
    }
}

/** 具名冲突的一行（对话框逐行显示的东西，日志里也逐字带上）。 */
private fun OffsetConflict.describe(): String =
    "id=$medicationId name=$name anchor=${anchor ?: "-"} offset=${minutes}min " +
        "base=${base ?: "-"} -> minuteOfDay=$resolvedMinuteOfDay reason=$reason"
