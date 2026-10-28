# 单药提醒时间偏移（per-medication time offset）— SDD 规格

状态：待实施（docs-only；本文件不含实现。第 2 轮外部评审后重写：§3.2 非前进写入改为**拒绝**、§3.7 作息变更的**阻断**与跨存储顺序、§3.8 取消归档重算、§2.3 回绕边界钉死、§8.1 幂等回归；§10 风险清单按实际处置重写）。
范围：为**每一味药**单独设置一个相对成员作息（起床/三餐/就寝）的时间偏移，范围 **-60 … +60 分钟、步长 15、默认 0**；作息被修改时自动重算；提醒、今日计划、今日用药核对、排程、统计保持一致；老数据在 0 偏移下完全等价；迁移 **v26 → v27**（纯新增一列）。
不改动：处方剂量与剂次口径、依从率定义、库存扣减、状态事件（暂停/恢复）语义、照护事项、`medication_logs`。
关联：`docs/medication-daily-flow.md`（今日计划/打卡）、`docs/medication-daily-reconcile.md`（今日用药核对 = 药品级确认，非剂次级）、`docs/medication-status-spec.md`（暂停/恢复 + dose 级谓词）。

> **一句话定义**：偏移只移动**提醒与排期**的钟点，**不改变医嘱规定的服药时间**；医嘱优先。任何 UI 文案不得暗示 App 允许把某一剂吃到处方窗口之外（§7）。

## 0. 已确认的需求（不再扩展）

| # | 需求 | 落点 |
|---|---|---|
| R1 | 偏移**按药独立**设置，互不影响 | §2、§6 E4 |
| R2 | 相对成员作息；-60..+60，步长 15，默认 0 | §2、§5.1 |
| R3 | 作息被改 ⇒ 药品时间**自动重算**，且重算**记入计划修订**（不是原地改写） | §1.3、§3、§5、§8 |
| R4 | 老数据兼容：既有药品一律 0 偏移，行为逐字不变 | §4、§6 E6 |
| R5 | 只作为提醒/排期调整；**不得**被表述为医学上许可的服药时间弹性 | §7 |
| R6 | 跨零点：一期**不支持**，但选项**可见且置灰**并说明原因；数据层**拒绝**而非钳位 | §2.2、§5.1、§6 E1 |
| R7 | 时段解析**只有一套权威**，另一套是薄委托，parity 测试守住 | §1.2、§5.0 |
| R8 | 计划写入的时间戳不前进 ⇒ **拒绝**（定义好的错误），**绝不**原地覆盖已有区间；原地替换只允许"计划内容逐字相同"的真无操作 | §3.2 |
| R9 | 作息变更会让已存偏移变非法 ⇒ **阻断该变更**并在对话框**指名**冲突药品与时段，零写入；顺序 = 先校验（读 Room）后写 DataStore | §3.7 |
| R10 | 取消归档 ⇒ 按**当前**作息重算钟点，且整次转换只留**恰一条**修订 | §3.8 |
| R11 | 回绕锚点（`breakfast = 00:10`）的合法性判定落在**解析后的瞬时**上，不能绕过校验 | §2.3 |
| R12 | reconcile 与重算的**可中断、可重启、可重复**由回归测试钉死（不留重复/缺失闹钟、不留重复修订） | §8.1 |

## 1. 现状事实（代码核查，file:line）

### 1.1 时段 → 具体钟点：类型化解析（权威候选）

| 事实 | 位置 |
|---|---|
| 作息模型 = 5 个槽（WAKE/BREAKFAST/LUNCH/DINNER/BED，默认 07:00/08:00/12:00/18:00/22:00） | `core/model/src/main/kotlin/com/driezy/medlog/data/model/RoutineTimeSlot.kt:26-48` |
| `RoutineSchedule.resolve(anchor)`：早/午/晚餐各 ±15 分、`EVENING = bed − 60`、`AFTERNOON` 恒 15:00；用 `LocalTime.plus/minusMinutes`（**会回绕**）⇒ 本特性改为整数版 `resolveUnwrapped`（§2.3），**输出逐字不变** | 同上 `:51-62` |
| 语义锚点 `RoutineAnchor`（10 个，与 `TimePeriod` 同名映射） | `core/model/.../domain/model/MedicationSchedule.kt:33-44` |
| 领域排程 `MedicationSchedule.RoutineAnchored(anchor, resolvedTime, recurrence)` **携带已解出的钟点** | 同上 `:46-57` |
| 作息键为**成员级** DataStore int 键（`wake_hour`…`bed_minute`）；编辑态折成 `RoutineSchedule` 走 `routineSchedule()` | `app/.../data/repository/UserPreferencesRepository.kt:193-201,164-171,488-504,616-640` |

### 1.2 第二套字符串版解析（调用方已审计）

| 事实 | 位置 |
|---|---|
| `ReminderTimeUtils.timePeriodToReminderTime(period, schedule)`：同一个 when 分支的字符串版；`adjustTime` 用 `%24` 处理进退位 | `app/.../util/ReminderTimeUtils.kt:24-36,39-44` |
| 调用方 **2 处**：编辑器 `resolvePeriodTimes`（药品，需带偏移） | `app/.../feature/medications/editor/AddMedicationViewModel.kt:422-428`（`:425`） |
| 调用方 2：照护事项（无药品、无偏移，恒 0） | `app/.../feature/caretasks/CareTaskEditorViewModel.kt:148` |
| 两套目前**逐字等价**（含回绕），但已是漂移风险点 | `app/src/test/.../ReminderTimeUtilsTest.kt:22-54`（`adjustTime` 6 例）、`:57-137`（时段映射 14 例）只锁字符串版 |

### 1.3 作息变更的调用链（审计结论，决定 D2 的最小改动）

| # | 步骤 | 位置 |
|---|---|---|
| 1 | 设置页改作息 → `preferences.updateRoutineTime(slot, time)` → 立刻 `resyncReminders(preferences.reminders.first().routineSchedule)` | `app/.../feature/settings/FocusedSettingsViewModels.kt:124-127` |
| 2 | 取当前成员**未归档**药品（`getMedicationsFor` ⇒ `getAllMedicationsOnce`，`WHERE isArchived = 0`） | `ResyncRemindersUseCase.kt:44-50`；`MedicationDao.kt:106-110` |
| 3 | 逐药过滤：跳过 `isPRN` / `intervalHours > 0`（`:52`）、无可解析锚点（`:53-55`）、**物化结果与现存钟点相同**则跳过（`:57` + `:65-68`） | `app/.../capability/reminders/application/ResyncRemindersUseCase.kt:51-59` |
| 4 | 整批写：`updateMedications(updates)`，**仅在 `updates` 非空时** | 同上 `:60` |
| 5 | `MedicationRepositoryImpl.updateMedications`：**一个** `transactions.withTransaction`、**一个** `changedAt`，**逐药**调 `medicationDao.updatePlan` | `app/.../data/repository/MedicationRepositoryImpl.kt:83-86` |
| 6 | `updatePlan`（`@Transaction`）：比对 → 插旧计划 → 前进边界 | `MedicationDao.kt:74-85` |
| 7 | 提交**之后** `reconcileReminders.all(ROUTINE_CHANGED)` | `ResyncRemindersUseCase.kt:61` |

**调用方清点（决定了要不要新 API）**

| 入口 | 调用方 |
|---|---|
| `MedicationRepository.updateMedications(list)` | 仅 `ResyncRemindersUseCase.kt:60` |
| `MedicationDao.updatePlan(med, changedAt)` | `MedicationRepositoryImpl.kt:81`（编辑器保存）、`:85`（resync 逐药）、`:112`（归档/取消归档） |
| `MedicationDao.updateMedications(list)`（批写、**不建修订**） | **零调用方**（全仓 grep 无命中）⇒ 死代码，见 §3.3 |

**结论（推翻上一版草案 §3 的"既有缺口"判断）**：resync 在 HEAD 上**已经**逐药走 `updatePlan`，因此"改作息不留历史快照"的缺口**不存在**。上一版草案据以立论的 `MedicationDao.kt:88-91` 只是 DAO 层的批写方法，repository 层并没有用它（`MedicationRepositoryImpl.kt:83-86`，`git blame` 显示逐药 `updatePlan` 自 `3aae933f` 起就在）。所以 D2 不需要新入口、不需要新 DAO 方法，只需**把非前进写入改成拒绝（§3.2）+ 把物化带上偏移**。

### 1.4 计划修订链与闹钟

| 事实 | 位置 |
|---|---|
| `MedicationPlanRevision` 快照含 `startDate/endDate/frequency*/timePeriod/reminderTimes/reminderHour/reminderMinute/intervalHours/isPRN/isArchived/doseQuantity/doseUnit`；**不含** `stock/notes/isHighPriority/maxDailyDose/refill*/offsetMinutes` | `core/database/.../model/MedicationPlanRevision.kt:21-40,42-60` |
| `updatePlan`：`previousPlan = previous.planRevision(changedAt)`、`nextPlan = medication.planRevision(changedAt).copy(effectiveFromMs = previous.planEffectiveFromMs)` ⇒ 两侧 `until/from` 相同，`!=` 只比**计划字段** | `MedicationDao.kt:77-79` |
| 有变 ⇒ `if (changedAt > previous.planEffectiveFromMs) insertPlanRevision(previousPlan)` 后 `planEffectiveFromMs = changedAt`；无变 ⇒ 边界**不动**（仍写整行） | `MedicationDao.kt:80-84` |
| 边界是**裸毫秒时间戳**（`clock.millis()`），不是当日 0 点；同一天两次编辑各得一个边界 | `MedicationRepositoryImpl.kt:81,84`；既有测试 `MedicationDaoTransactionTest.kt:50-77`（1000/2000/3000 三次均为同日） |
| requestCode = `medId*100+slot`（正点，`SLOT_STRIDE = 100`）/`+50_000`（提前预告）/`+100_000`（漏服再提醒）；调度只读 `toDomainSchedule()` | `ReminderTarget.kt:27-32,61`；`AlarmScheduler.kt:33,36,117-130`；`MedLogAlarmReceiver.kt:152,218` |
| 单药保存后 reconcile（取消+重排）；兜底：进程启动与开机各入队一次全量 reconcile | `AddMedicationViewModel.kt:583`；`MedLogApplication.kt:28`；`MedLogBootReceiver.kt:34` |
| reconcile 使用的 `handled` 槽集合来自**全部**日志（不按边界裁剪），`lastTaken` 才按 `scheduledTimeMs >= planEffectiveFromMs` 裁剪 | `AndroidReminderReconciler.kt:146-158`（`:148`、`:150`） |

### 1.5 投影 / 依从 / 今日 / 核对 / 小组件 / 二维码

| 事实 | 位置 |
|---|---|
| `FuturePlanCalculator.calculate` 是**唯一** occurrence 生产者；每个计划段 = `history.map{applyTo(current)}` + 当前行，段内按 `[max(dayStart, planEffectiveFromMs), min(dayEnd, effectiveUntilMs))` 裁剪；**段之间不去重** | `app/.../feature/medications/application/FuturePlanCalculator.kt:53-95`（`:55`、`:58-59`） |
| 投影与依从共用 dose 级谓词 `doseActiveAt`；依从率复用同一 calculator（`days=90`） | `FuturePlanCalculator.kt:83`；`MedicationAdherence.kt:93-105` |
| 日志与槽位按 `scheduledTimeMs` **精确**匹配，另有 4 小时内遗留就近兜底 | `app/.../application/DoseLogMatcher.kt:6,14-25` |
| 今日计划条目时间 = 投影 `scheduledAtMs` 的本地钟点；小组件取同一 calculator | `FuturePlanCalculator.kt:85-91`、`TodayItem.kt:76-90`、`HomeHeroPresentation.kt:90`、`capability/widgets/WidgetUtils.kt:34` |
| **今日用药核对**是**药品级**批量确认（`buildLines` 只产 `{id, name, liveKind}`），**不列时间** | `app/.../reconcile/MedicationDailyReconcileViewModel.kt:34-52,238-258` |
| 二维码条目带 `tp/rt/rh/rm`；解码 `ignoreUnknownKeys=true`、编码 `encodeDefaults=false`；`toMedication` 原样写入 `timePeriod/reminderTimes`（不重算） | `app/.../application/PlanExportSchema.kt:32-63,82-89,133-186`；`ImportPlanUseCase.kt:24-37` |
| 导入：先 `map{toMedication}`，再 `MERGE`/`REPLACE`，最后 reconcile；`REPLACE` 是**先删后插** | `ImportPlanUseCase.kt:25-34` |
| 详情页作息模式**只显示时段标签、不显示钟点** | `app/.../detail/MedicationDetailScreen.kt:297-300` |

### 1.6 迁移配套现状

| 事实 | 位置 |
|---|---|
| `DatabaseSchema.VERSION = 26`；迁移对象集中在 `MedLogDatabase`（最近一版 `MIGRATION_25_26`）；DI 注册；导出 schema 到 `core/database/schemas/…/26.json` | `DatabaseSchema.kt:6`、`MedLogDatabase.kt:336-370`、`app/.../di/AppModule.kt:103-125` |
| **硬编码迁移清单**共 6 个 androidTest 文件 + 1 个单测常量断言 | `MedLogDatabaseMigrationTest.kt`（5 处）、`CareEvent/CareNote/CarePeople/CareTodo/MedicationStatusMigrationTest.kt`、`BackupCompatibilityPolicyTest.kt:24` |
| 仓库内**无** `@ColumnInfo(defaultValue = …)` 先例 | 全仓 grep 为空 ⇒ §4 第 3 条是本仓首次 |

### 1.7 归档 / 取消归档 / 暂停的写路径（§3.8 的事实基础）

| 事实 | 位置 |
|---|---|
| 取消归档**唯一**入口：设置页模块「恢复」→ `medications.unarchiveMedication(id)`（repository）→ `reconcileReminders.medication(MEDICATION_CHANGED)` | `FocusedSettingsViewModels.kt:210-216`；`SettingsContract.kt:47` |
| `unarchiveMedication` = `setArchived(id, false)`：`getMedicationById` → `updatePlan(it.copy(isArchived = false), clock.millis())`，**一个事务、不重算 `reminderTimes`** | `MedicationRepositoryImpl.kt:98,110-115` |
| `isArchived` **在**修订快照里 ⇒ 归档与取消归档各产生 1 条修订（既有断言 `listOf(false, false, true)`） | `MedicationPlanRevision.kt:37,57`；`MedicationDaoTransactionTest.kt:67-72` |
| resync 的取数是 `WHERE isArchived = 0` ⇒ **归档期间改作息，该药的时间不会被重算**；取消归档后仍是归档前的钟点 | `MedicationDao.kt:106-110`；`ResyncRemindersUseCase.kt:44-50` |
| 暂停/恢复走**另一条**路：`setArchivedCache` 直写 `UPDATE medications SET isArchived`，**不建修订**（§3.1 的 0 条规则），三个调用方 | `MedicationRepositoryImpl.kt:100-108`；`SetMedicationStatusUseCase.kt:197`、`ReconcileMedicationStatusUseCase.kt:41`、`CorrectMedicationStatusEventUseCase.kt:86` |
| reconcile 的排程闸门读派生 `pausedNow`（`isArchived` **或** 派生暂停），所以取消归档后必须重排，否则不会自己恢复 | `AndroidReminderReconciler.kt:43-59,103-106` |

## 2. 数据模型与校验

新增**一列**，落在 `medications` 的时段/提醒列组内（`Medication.kt:51-55` 之后）：

```
offsetMinutes INTEGER NOT NULL DEFAULT 0     -- ∈ {-60,-45,-30,-15,0,15,30,45,60}
```

Room 实体：`@ColumnInfo(defaultValue = "0") val offsetMinutes: Int = 0`。

- **只对"作息锚点"药品有意义**：`timePeriod` 解析结果非空且 `!isPRN && intervalHours == 0`。精确时间、按需、间隔给药**恒存 0**（非 0 即被拒绝，§2.2）。
- 不加索引（永不作为查询谓词、不参与排序）。
- **不做任何归一化/钳位**：没有 `coerceOffsetMinutes`。非法值不"修正"，而是**拒绝**（§2.2）。

### 2.1 为什么**不能**把偏移后的绝对时间当成唯一真相

今天 `reminderTimes/hour/minute` 已经是"解析结果的写穿缓存"（§1.5 的 `toDomainSchedule`）。偏移必须**只**作为**输入**存在，绝对时间永远由 `(作息, 时段, 偏移)` 算出：

1. **不可重构**：若只把 `早餐后 08:15 → 08:30` 写进 `reminderTimes` 而不存偏移，作息一改，重算得基线 08:15 且偏移无处可读 ⇒ +15 被**静默吞掉**，用户意图不可恢复、步进器也回不到原位。
2. **双重计入**：若既存偏移列、又让某条读路径把 `reminderTimes` 当"已含偏移的真相"再叠一次，会得到 +30。因此规则是**读路径永不消费 `offsetMinutes`**（`toDomainSchedule` / `FuturePlanCalculator` / `AlarmScheduler` / 小组件都不读），只有写路径的物化消费它（§5.1）。
3. 不变量（每个写点都必须成立）：`reminderTimes/reminderHour/reminderMinute == materialize( resolve(routine, periods) + offsetMinutes )`，且物化**幂等**——同一 `(routine, periods, offset)` 连算两次结果相同。
4. 副作用（正面）：因为读路径不读偏移，一行被外部写坏的 `offsetMinutes` **不会**让任何读路径崩溃或算出错时间，只会让该药下一次在编辑器里被要求修正（§5.1）。

结论：偏移列是**唯一新增的持久输入**；绝对时间仍是派生输出，随作息/偏移/时段任一变化整体重写。

### 2.2 校验：定义一个错误，不用钳位

单一校验函数（core/model，纯函数，无 Android 依赖）。判定输入是**解析后的瞬时**（`§2.3` 的未回绕分钟数），不是库里的原始数字：

```kotlin
sealed interface TimeOffsetCheck {
    data class Allowed(val minutes: Int) : TimeOffsetCheck
    data class Rejected(
        val minutes: Int,
        val anchor: RoutineAnchor,
        val base: LocalTime,          // 该锚点在本作息下的基线钟点（渲染值，可能已回绕）
        val resolvedMinuteOfDay: Int, // baseUnwrapped + minutes，即"解析后的瞬时"
        val reason: Reason,
    ) : TimeOffsetCheck
    enum class Reason { NOT_MULTIPLE_OF_15, OUT_OF_RANGE, CROSS_MIDNIGHT, WRAPPED_ANCHOR, NOT_APPLICABLE }
}

fun validateTimeOffset(
    minutes: Int,
    schedule: RoutineSchedule,
    periods: List<RoutineAnchor>,   // 空 = 精确时间，没有可解析的基线
    applicable: Boolean,            // false = PRN / intervalHours > 0
): TimeOffsetCheck
```

判定顺序：① 非 15 的倍数 ⇒ `NOT_MULTIPLE_OF_15`；② `|minutes| > 60` ⇒ `OUT_OF_RANGE`；③ `!applicable || periods.isEmpty()` 且 `minutes != 0` ⇒ `NOT_APPLICABLE`；④ 逐锚点取**未回绕基线** `baseUnwrapped = resolveUnwrapped(anchor)`（整数，可 <0 或 ≥1440，见 §2.3），任一段满足 `baseUnwrapped + minutes ∉ 0..1439` ⇒ `CROSS_MIDNIGHT`（**多时段取交集**：任一段越界，该档位整体不可用，`Rejected` 指明是哪一段、并带上 `resolvedMinuteOfDay` 供文案直接用）；⑤ `minutes != 0` 且 `baseUnwrapped ∉ 0..1439` ⇒ `WRAPPED_ANCHOR`（基线本身已回绕，见 §2.3）。

- 写侧（编辑器保存、导入、任何直接调用的数据层入口）用 `requireAllowed(...)`；不合规抛**已定义**的 `class IllegalTimeOffsetException(val check: TimeOffsetCheck.Rejected) : IllegalArgumentException`。**不**截断、**不**取整、**不**按方向交换。
- 抛出点在任何写入之前 ⇒ 事务整体回滚，无半行数据（§3.5）。
- 迁移/老数据：`DEFAULT 0` ⇒ 构造上合法，无需清洗（§4）。
- 判定用**解析后的瞬时**（`baseUnwrapped + minutes`）：既能识别"同日基线 + 偏移越过 24:00"，也能识别"基线本身已回绕"。**0 偏移是祖父条款**——`resolve(anchor)` 的输出逐字不变（R4/E6），只是任何非 0 偏移都不再允许加在回绕锚点上（§2.3）。

### 2.3 回绕边界（`LocalTime` wrap）必须钉死

**事实**：`resolve` 用 `LocalTime.minusMinutes/plusMinutes/minusHours`（`RoutineTimeSlot.kt:53-61`），这些运算在 0 点**回绕且不报错**；`ScheduleOccurrences` 随后把结果放到**同一日历日**的 `date.atTime(time)`（`ScheduleOccurrences.kt:47`）。因此 `breakfast = 00:10` 时 `BEFORE_BREAKFAST = 23:55`：渲染看起来正常，语义上却是**前一天的 23:55**（=-5 分），实际剂次比早餐晚 23 小时 45 分。这是既有缺陷，本特性**不修**（修它要改 anchor 的日语义与 occurrence 生成，超出本期），但也**不能让它绕过偏移校验**。

**钉死的做法**：在 `RoutineSchedule` 增加**整数**解析，唯一的 `LocalTime` 构造点在最后一步：

```kotlin
fun RoutineSchedule.resolveUnwrapped(anchor: RoutineAnchor): Int =   // 分钟，可 <0 / ≥1440，不回绕
    when (anchor) {
        MORNING -> wake.minuteOfDay
        BEFORE_BREAKFAST -> breakfast.minuteOfDay - 15
        AFTER_BREAKFAST -> breakfast.minuteOfDay + 15
        /* BEFORE_LUNCH / AFTER_LUNCH / BEFORE_DINNER / AFTER_DINNER 同构 */
        AFTERNOON -> 15 * 60              // 绝对值，永不回绕
        EVENING -> bed.minuteOfDay - 60
        BEDTIME -> bed.minuteOfDay
    }

fun RoutineSchedule.resolve(anchor) = minuteToLocalTime(floorMod(resolveUnwrapped(anchor), 1440))   // 逐字等于今天
fun RoutineSchedule.resolveWithOffset(anchor, offset): LocalTime {
    if (offset == 0) return resolve(anchor)                       // 祖父：与今天逐字相同
    val m = resolveUnwrapped(anchor) + offset
    require(m in 0..1439) { throw IllegalTimeOffsetException(...) }  // 最后一道自检；正常由 validateTimeOffset 拦下
    return LocalTime.of(m / 60, m % 60)
}
```

- `floorMod` 与 `LocalTime` 的加减回绕**语义相同**，所以 0 偏移下 `resolve` 的输出、以及 `ResyncRemindersUseCase` 的 "未变则跳过" 判定（`ResyncRemindersUseCase.kt:56-57`）逐字不变 ⇒ R4/E6 成立。
- 合法性的唯一判据是 `resolveUnwrapped(anchor) + offset ∈ 0..1439`，即**解析后的瞬时落在这个锚点自己的那一天之内**。对回绕基线（`-5`）来说：`+15 ⇒ 10` 落在 `0..1439` 内 **但并不合法**（它把"前一天 23:55"变成"当天 00:10"，跨了日）；因此 `minutes != 0` 时直接判 `WRAPPED_ANCHOR`，**不看**数值和是否在区间内——这正是"钉死边界"的含义：不许用数值区间把跨日问题伪装成合法。
- 多时段取交集（沿用 E1/E3）：只要该药有一个锚点回绕，**该药**就不能设非 0 偏移（否则同一味药一部分时段同日、一部分跨日）。
- 文案：`time_offset_wrapped_anchor_hint`（§7），置灰整条步进器而不是置灰单档——数值上"看起来能选"的档位在这里全是陷阱。
- 测试（§6 E10）：`breakfast = 00:10` ⇒ `resolveUnwrapped(BEFORE_BREAKFAST) == -5`、`resolve(BEFORE_BREAKFAST) == 23:55`（祖父不变）；`validateTimeOffset(+15)` 与 `validateTimeOffset(-15)` 均为 `Rejected(WRAPPED_ANCHOR)`；`validateTimeOffset(0) == Allowed`；且 `AFTER_BREAKFAST`（+15 基底 25 ⇒ 00:25）同样被拒——防止"只要落在区间内就放行"的实现混过去。

## 3. 版本生成（与 `updatePlan` 的关系）

### 3.1 哪些编辑产生修订

| 编辑种类 | 产生修订 | 边界 | 依据 |
|---|---|---|---|
| 偏移变化（编辑器） | **1 条 / 该药** | 前进到 `changedAt` | `reminderTimes` 变化 ⇒ `previousPlan != nextPlan`（偏移本身不在快照里，见下） |
| 作息变化（resync，逐药） | **每味"物化结果真的变了"的药各 1 条**；未变者 **0 条** | 全部前进到**同一个** `changedAt` | §1.3 步骤 3/5/6 |
| 时段集合变化（编辑器） | 1 条 | 前进 | `timePeriod` + `reminderTimes` 都在快照 |
| 精确时间变化 | 1 条 | 前进 | `reminderTimes` 在快照 |
| 剂量/剂次（`doseQuantity`/`doseUnit`） | 1 条 | 前进 | 都在快照 |
| 频率（`frequency*`）/起止日（`startDate`/`endDate`） | 1 条 | 前进 | 都在快照 |
| 归档 | 1 条 | 前进 | `isArchived` 在快照；`MedicationRepositoryImpl.kt:110-115` → `updatePlan` |
| **取消归档** | **1 条**（同时携带**按当前作息重算**的新钟点） | 前进 | §3.8 |
| 暂停 / 恢复（状态事件） | **0 条** | 不动 | 只 append `medication_status_events`（`MedicationStatusRepositoryImpl.kt:26`），不碰 `medications` 行 ⇒ 不经过 `updatePlan` |
| 库存变化（`updateStock`） | 0 条 | 不动 | 走专用 SQL；既有测试 `MedicationDaoTransactionTest.kt:50-77` |
| 备注 / 优先级 / 备货设置 | 0 条 | 不动 | 不在快照里 ⇒ `previousPlan == nextPlan` |
| 无变化保存（同名同值） | 0 条 | 不动 | `previousPlan == nextPlan`（`MedicationDao.kt:79,82-84`），非计划列照常落库 |
| 同毫秒的重复写，**内容相同** | 0 条，原地写 | 不动 | §3.2 分支 1（真无操作） |
| 同毫秒的重复写 / 时钟回拨，**内容不同** | — | **拒绝**（`PlanBoundaryNotAdvancedException`），零写入 | §3.2 分支 3 |

> `offsetMinutes` **不**进修订快照（`planRevision()` 不读它），因此"偏移变化 ⇒ 修订"靠的是**物化后的 `reminderTimes` 变化**。这是有意为之：修订行只需能复原历史钟点，多存一个无消费者的偏移就是多一个可能说谎的字段。附带硬规则：对任何**非回绕**锚点、任何合法档位，`resolveWithOffset(anchor, o)` 在 `o != 0` 时必须与 `resolve(anchor)` 不同（§8 第 1 条逐格断言，防"改了却没修订"）；回绕锚点的非 0 档在**校验阶段**就被拒绝（§2.3），不会走到物化。

**为什么是"每味药一条"而不是"每次编辑一条"**：修订表以 `medicationId` 为外键、按药各自成链（`MedicationPlanRevision.kt:11-20`）；N 味药同一次作息变更就是 N 条不同药的修订行，同一边界。这既满足"恰一条/变化药"，也不会把 A 药的时间写进 B 药的历史。

### 3.2 时间戳不前进：**拒绝**，不覆盖（取代上一版的 `maxOf` 规则）

**事实（守卫的两个分支）**：守卫是严格毫秒比较 `changedAt > previous.planEffectiveFromMs`（`MedicationDao.kt:79-81`），不是日历日比较。于是：

| 情形 | 今天的行为 | 后果 |
|---|---|---|
| 同日、不同毫秒的第二次编辑 | 正常插入，两条区间相邻（08:00 改一次、09:30 再改一次，当天切成三段） | **要保留**：已打卡剂次还能按旧 `scheduledTimeMs` 匹配（`DoseLogMatcher`） |
| **`changedAt <= boundary` 且内容变了** | 跳过插入，**却把边界改写成 `changedAt`**（`:80-81`） | 上一修订的 `effectiveUntilMs` 仍是旧边界，当前行却从更早的 `changedAt` 生效 ⇒ **重叠**；`FuturePlanCalculator` 段间不去重（`:55-59`）⇒ 重叠窗口产出两份同剂次，投影与依从率分母双双翻倍 |

**为什么 `maxOf` 不够（上一版方案作废）**：`maxOf` 把边界按在旧值，于是"内容不同的写"变成**从旧边界起原地生效**。但上一修订的 `until` 恰好等于那个旧边界 ⇒ 它的区间长度变成 **0** ⇒ 它从**所有**投影里消失；同时 `[旧边界, 现在)` 这段**已经过去**的时间被新钟点重新算过。同毫秒场景只影响 1ms，看着无害；**时钟回拨**场景是几分钟到几天，等于把"过去"按新计划重写了一遍——正是要禁止的事。形式化：任何"内容变化 + 边界不动"的原地替换，都必然让前一段满足 `until == from`，即零长度 ⇒ 历史被吃掉。因此 `maxOf` 只解决了"重叠"，没解决"覆盖"。

**规则（三分支，约 6 行；分支 1/3 是这次新增的语义）**：

```
if (previousPlan == nextPlan)                        // 计划字段逐字相同（两侧已归一化 from）
    → updateMedication(med.copy(planEffectiveFromMs = previous.planEffectiveFromMs))   // 非计划列照常落库
    → PlanWriteResult.NoOp；0 修订；边界不动                    ← 只有这种原地替换是合法的
else if (changedAt > previous.planEffectiveFromMs)
    → insertPlanRevision(previousPlan)                          // until = changedAt
    → updateMedication(med.copy(planEffectiveFromMs = changedAt))
    → PlanWriteResult.Advanced
else
    → throw PlanBoundaryNotAdvancedException(id, boundaryMs = previous.planEffectiveFromMs, requestedMs = changedAt)
    → 一行都不写（异常穿出 @Transaction ⇒ 回滚）
```

- 异常是**已定义类型**（不是 `require`）：`class PlanBoundaryNotAdvancedException(val medicationId: Long, val boundaryMs: Long, val requestedMs: Long) : IllegalStateException(...)`，消息带两个时间戳，便于日志与用户提示区分"同毫秒"与"时钟回拨"。
- 为什么不是"插一条零长度修订"：`(from = boundary, until = boundary)` 等于把旧计划压成 0 长区间，旧计划从投影里消失 ⇒ 仍然是追溯覆盖，只是换了张表。
- `updatePlan` 返回 `PlanWriteResult`（`Advanced` / `NoOp`），批写路径据此判断是否需要 reconcile（`NoOp` 不必重排）。

**不变量（任一药品、任一时刻都成立，可被断言）**：

| # | 不变量 | 由什么保证 |
|---|---|---|
| I1 | **过去只插不改**：修订行永不 UPDATE / DELETE（唯一删除是 `medications` 行 FK CASCADE） | DAO 只有 `insertPlanRevision` / `observePlanRevisions`（`MedicationDao.kt:60-71`），无更新语句 |
| I2 | **边界单调**：`planEffectiveFromMs` 不减；**内容变化必定严格前进** | 分支 2 才写新边界，分支 3 直接拒绝 |
| I3 | **链相邻**：按 `effectiveFromMs` 排序后 `r_i.effectiveUntilMs == r_{i+1}.effectiveFromMs`，且 `last.effectiveUntilMs == current.planEffectiveFromMs`，且每段 `until > from` | 插入时 `previousPlan.until = changedAt` 与新边界同一个值（`MedicationDao.kt:77-81`） |
| I4 | **单门**：计划字段只能经 `updatePlan` 写 | `@Update updateMedication` 只被 `updatePlan` 调用；批写死代码 `updateMedications`（`:87-91`）删除（§3.3）后绕过入口为 0 |

I1 + I3 ⇒ 修订区间是 `[first.from, ∞)` 的一个**划分**（无洞、无叠）⇒ 任一时刻最多落在一个段里 ⇒ 投影"段间不去重"也不会重复计数。这就是"历史不被追溯覆盖"的可证明形式：不是"我们尽量不动旧的"，而是**旧的区间插入后长度不再改变，且旧行不可更新**。

**调用方拿这个异常做什么（不吞、不错时间戳、不自动重试）**：

| 调用方 | 行为 |
|---|---|
| 编辑器保存（`MedicationRepositoryImpl.kt:80-81`，`clock.millis()`） | 异常穿出 `withTransaction` ⇒ 零提交；VM 不清草稿（`clearDraft()` 在成功之后）、置 `isSaving=false`、发一条错误 effect「保存失败：计划时间未能前进，请重试或校正系统时间」。用户再点一次保存即可（同毫秒冲突下 `clock.millis()` 已前进）；时钟回拨下会持续失败到时钟越过边界——**有意的**，宁可报错也不改写历史（§10） |
| 批量 resync（`MedicationRepositoryImpl.kt:83-86`，批前取一次 `changedAt`） | 任一药被拒 ⇒ 整个事务回滚（N 行药 + N 条修订全不落）。`ResyncRemindersUseCase` **不吞**：记日志后抛给设置页 ⇒ 提示「作息已保存，药品提醒时间将在下次同步时更新」；收敛靠 §3.6 的**启动重算**（幂等，未变者零写） |
| 归档 / 取消归档（`:110-115`） | 事务回滚，行保持原状；不会出现"已归档但时间已改"或反之的半状态；设置页提示失败 |
| **禁止**的做法 | 调用方"把 `changedAt` 加 1ms 再试"、或用 `maxOf`/`coerce` 修边界：那等于让代码替系统时钟撒谎，并制造 1ms 碎片区间；并发写会互相踩（两个写都用同一个伪造值 ⇒ 又回到同毫秒） |

**钉住违规的测试**（§8 第 15 条）：
`planChainStaysPartitionedUnderDuplicateAndBackwardWrites` — ① `updatePlan(t=5000, 新钟点)` 成功；② 再 `updatePlan(t=5000, 另一钟点)` ⇒ 抛 `PlanBoundaryNotAdvancedException`，行与修订**逐字**不变；③ `updatePlan(t=1000, 又一钟点)` ⇒ 同样抛异常；④ 断言 `assertChain(medId)`（`zipWithNext { a, b -> a.effectiveUntilMs == b.effectiveFromMs }`、`last.effectiveUntilMs == row.planEffectiveFromMs`、`all { until > from }`）；⑤ `FuturePlanCalculator` 当天每个 `(slotIndex)` 恰一条。这条断言在 `maxOf` 方案下**必红**——它断言的正是 `maxOf` 放弃的那条性质。

### 3.3 最小安全改动（D2 落地清单）

1. `MedicationDao.updatePlan`：按 §3.2 改为三分支（同内容原地 / 前进插修订 / 否则抛 `PlanBoundaryNotAdvancedException`），返回 `PlanWriteResult`（**唯一的业务代码改动点 1**）。
2. `ResyncRemindersUseCase` 的过滤与物化带上偏移（`validateTimeOffset` + `withResolvedRoutineTimes(schedule, anchors, offset)`，§5.1）——这是 D1/D3 的改动，顺带覆盖 resync；并且**新增一条启动重算**（§3.6），让"resync 被拒/被杀"有持久收敛点。
3. 删除死代码 `MedicationDao.updateMedications`（`:87-91`，零调用方）：它是唯一一条"整批改计划、不建修订、且绕过 `updatePlan` 守卫"的入口，留着会被下一个人用错。删除即消除风险，无行为变化（I4 靠它成立）。
4. **不新增**批量 DAO 方法：`MedicationRepositoryImpl.updateMedications`（`:83-86`）已经是"一个事务 + 一个 `changedAt` + 逐药 `updatePlan`"，正是想要的形态。

### 3.4 拒绝的替代方案

| 方案 | 为什么拒绝 |
|---|---|
| resync 逐药调 `repository.updateMedication`（各自 `clock.millis()`） | N 个边界（1~N ms 抖动），同一天出现亚秒级碎片区间；且每药一次独立事务（部分失败即半批） |
| 新增 `updatePlans(list, changedAt)` 批量 DAO | 与现有 4 行等价，只多一个 API 面；两个入口迟早分叉 |
| 把 `changedAt` 对齐到次日 0 点 | 今天剩余时间的提醒与设置页不一致；"生效日"从一个变成两个；用户改作息正是因为今天起晚了 |
| 给修订加 `offsetMinutes` 列 | 无消费者的字段 + 读侧双计风险（§2.1 第 2 点） |
| 在 `updatePlan` 里 `coerce` 边界 | 静默合并同日历史，与"历史不可变"直接冲突 |
| `maxOf(changedAt, boundary)`（上一版方案） | 不重叠了，但把"内容变化"变成"从旧边界原地生效" ⇒ 前一段 `until == from` ⇒ 零长度，历史被吃掉；回拨场景等于重写过去（§3.2） |
| 调用方 `changedAt += 1` | 伪造时间戳；两处并发写用同一伪造值 ⇒ 仍撞同毫秒；产生 1ms 碎片区间（§3.2） |

### 3.5 事务边界（resync 路径）

```
withTransaction {                                  // RoomTransactionRunner.kt:15-16
  val changedAt = clock.millis()                   // 一次，全批共用
  for (med in changed) {                           // 未变化的药根本不进这个列表
     dao.updatePlan(med, changedAt)                // @Transaction（嵌套，同一连接）
       ├ getMedicationById(id)                     // 事务内读，与写一致
       ├ previousPlan == nextPlan ? 原地写 : insertPlanRevision(previousPlan)
       ├ changedAt <= 边界 且内容变了 ⇒ throw PlanBoundaryNotAdvancedException   // ⇒ 整批回滚
       └ updateMedication(row.planEffectiveFromMs = 新边界或旧边界)
  }
}                                                  // 提交：N 行药品 + N 条修订 + N 个边界，原子
reconcileReminders.all(ROUTINE_CHANGED)            // 提交之后，不在事务内
```

- 一个事务里恰好三类写：**药品的新钟点**、**它们的修订行**、**当前行的边界**。任一失败全回滚（既有单药断言 `MedicationDaoTransactionTest.kt:80-99`，新增 N 药批次断言）。**拒绝是"整批不落"而不是"跳过这一味"**：跳过的药会保留旧钟点、其余药已前进，同一次作息变更被拆成两套时间，且没有后续触发点会修它。
- reconcile **不进事务**（不能在事务里碰 `AlarmManager`），且它自身 `runCatching` + 入队重试（`ReminderReconciliation.kt:14-21`）⇒ 提交成功、重排失败也不会留下"DB 已改、闹钟永不更新"的静默态。
- 无变化 ⇒ `updates` 为空 ⇒ 不进事务、不写行、不建修订；`reconcile` 仍无条件跑（既有行为，幂等，不作优化以免改动重排触发点）。

### 3.6 启动重算：被拒 / 被杀时的持久收敛点

`MedLogApplication.onCreate` 已经无条件入队一次全量 reconcile（`:23-30`，源码注释写明"Application startup is the final recovery net"）；在同一处加一次"用**当前成员的已存作息**跑 `ResyncRemindersUseCase`"。它只读 DataStore + Room，写路径与用户手改作息完全相同（同一个用例、同一个 `changedAt` 取法），因此：

- **幂等**：未变的药在 `ResyncRemindersUseCase.kt:56-57` 就被过滤掉 ⇒ 0 次 `updateMedications` ⇒ 0 条修订、边界不动（§8 第 7 条已钉住这条性质）；
- 它补上"作息已写、resync 被拒/被杀"的窗口（§3.7 W1/W3/W5），**不需要新增任何持久化状态**（没有"dirty 标志"表、没有新迁移）；
- 只覆盖**当前成员**——与 resync 本身的取数范围一致（`:44-50` 用 `activeRecipient.current()`），不存在"漏了别人的成员"；
- 与 §3.2 的拒绝规则不冲突：拒绝时它同样会被拒（时钟仍没越过边界），但那是有明确报错的失败，不是静默态；时钟一恢复，下一次启动即收敛。

### 3.7 作息变更：先**阻断**、再写、最后重算（跨存储顺序与失败窗口）

**跨存储事实（为什么不能"校验 + 写"原子完成）**：作息是成员级 DataStore int 键（读 `UserPreferencesRepository.kt:164-171`、按成员读 `:488-504`、写 `:616-640`），偏移在 Room 的 `medications.offsetMinutes`（§2）。`DataStore.edit` 是单文件原子写，但**不能**参与 Room 事务；SQLite 事务也包不住 DataStore。今天的调用链更糟：`FocusedSettingsViewModels.kt:124-127` 是**先** `preferences.updateRoutineTime(slot, time)`（写完才返回）、**再** `resyncReminders(preferences.reminders.first().routineSchedule)`（这才读 Room）。要阻断，校验必须在**第一个写之前**完成，而且只能**读**（DataStore 读作息、Room 读偏移），不能有任何写。

**新增一个只读用例**（`capability/reminders/application/CheckRoutineChangeUseCase`）：

```kotlin
suspend operator fun invoke(candidate: RoutineSchedule): List<OffsetConflict>
// recipient = activeRecipient.current()
// meds      = medications.getAllMedicationsFor(recipient)      ← 含归档（把 §3.8 的洞一起堵上）
// current   = preferences.routineScheduleFor(recipient)   // 与 settingsFlow 同源（:556 按成员解析）
// 逐药：offsetMinutes == 0 → skip（0 恒合法，§2.3 祖父条款）
//       anchors 空 / isPRN / intervalHours > 0 → skip（非适格，存非 0 已被 E7 拒绝）
//       legalNow   = validateTimeOffset(offset, current,   anchors, applicable = true)
//       legalAfter = validateTimeOffset(offset, candidate, anchors, applicable = true)
//       conflict ⇔ legalNow is Allowed && legalAfter is Rejected
// OffsetConflict(id, name, anchor, offsetMinutes, base, resolvedMinuteOfDay, reason)
```

- **只阻断"由这次变更造成"的非法**（`legalNow` 合法 → `legalAfter` 非法）。已经非法（E8 状态）的药**不**参与阻断：那种药本来就已被 resync 跳过，若也阻断，用户永远改不了作息（死锁），而这次变更既没让它更坏也没让它更好。
- 候选作息是纯函数构造：`preferences.reminders.first().routineSchedule.withTime(slot, time)`（`RoutineSchedule.withTime` 已存在，`RoutineTimeSlot.kt:41-47`），不写任何东西。

**确定的顺序（六步，前两步零写入）**：

| # | 动作 | 失败时 |
|---|---|---|
| 1 | `candidate = current.withTime(slot, time)` | — （纯函数） |
| 2 | `conflicts = check(candidate)`（读 Room） | 读失败 ⇒ 不写作息、提示重试（宁可这次改不了，也不在不知道的情况下写） |
| 3 | 非空 ⇒ `ShowRoutineConflict(conflicts)` 并 **return** | DataStore 未动、Room 未动、闹钟未动 |
| 4 | `preferences.updateRoutineTime(slot, time)` | DataStore 写失败 ⇒ 报错，无需回滚任何东西 |
| 5 | `resyncReminders(<从 DataStore 重读的作息>)`（§3.5，单事务） | 异常 ⇒ 见 W1 |
| 6 | 成功 ⇒ 设置页显示新值；resync 内部已 reconcile | — |

注意第 5 步**重读**作息而不传 `candidate`：写进去的才是真相，避免"校验用的是 A、写进去的是 B"这类漂移（`updateRoutineTime` 只改一个槽，理论上相等，但重读更便宜也更诚实）。

**失败窗口（如实列出，逐个有恢复路径）**：

| 窗口 | 落库状态 | 恢复机制 |
|---|---|---|
| **W1** DataStore 已写、resync 事务失败/被 §3.2 拒绝 | 作息=新；药品钟点=旧但**仍然合法**（第 2 步已证明候选作息下它们合法）；修订未增 | 第 5 步 catch：记日志 + 提示「作息已保存，药品提醒时间将在下次同步时更新」+ 入队一次 `reconcileAll`；外加**启动重算**（§3.6）⇒ 下次启动收敛。期间闹钟按 DB 现状重排，不会出现"旧钟点 + 新作息"却没人知道 |
| **W2** resync 已提交、reconcile 失败/进程死 | 钟点=新、闹钟=旧 | `ReconcileRemindersUseCase` 提交后**无条件**入队（`ReminderReconciliation.kt:32-39`）→ Worker `Result.retry()`（`ReminderReconciliationWorker.kt:26-34`）→ 启动/开机全量（`MedLogApplication.kt:28`、`MedLogBootReceiver.kt:34`） |
| **W3** 第 4 步写入后进程被杀 | 同 W1 | 同 W1（启动重算） |
| **W4** 第 2 步与第 4 步之间新增了冲突药（并发：导入/另一路径写入） | 可能存下"新作息 + 非法偏移"的行 | resync 的逐药校验（E8）**不为它写** ⇒ DB 里**不会**出现非法钟点；该药在编辑器显示错误态并阻止保存；不阻断本次作息（它不属于"被这次变更弄非法"的判定对象） |
| **W5** 时钟回拨 ⇒ resync 被 `PlanBoundaryNotAdvancedException` 拒 | 作息=新、钟点=旧（合法） | 同 W1；时钟不越过边界前，该药的钟点停在旧值（旧值合法），用户看到提示 |

**为什么不尝试回滚 DataStore**：回滚是第二个跨存储写，本身也会失败；而且会把用户刚输入的值丢掉。更关键的是"新作息 + 旧药品钟点"这个中间态是**安全**的——药品钟点仍然合法（第 2 步的结论），只是不是最新的派生值，而它恰好是启动重算能修的形态。

**为什么反向顺序（先 Room 后 DataStore）更糟**：Room 里的钟点会按"尚未存在的作息"算出来；一旦 DataStore 写失败，DB 里就长期留着与已存作息不符的钟点，而 resync **只在作息变更时**触发 ⇒ 没有任何后续点会修它（启动重算倒是会修，但那等于把顺序又倒回来了）。先写 DataStore 则"已存作息"永远是有效输入。

**为什么阻断比"允许后报错"安全（三条都落在代码上）**：

1. E8 的"跳过非法药"意味着：允许写入 ⇒ 这味药的时间**冻在旧值**、其它药一起动 ⇒ 同一个时段两味药显示不同钟点，UI 对此没有任何解释；
2. resync 没有"失败清单"回传，也没有全局横幅（§10 R-10）⇒ 用户**不会知道**哪味药没跟上；
3. 导入路径已经是"整包校验、零写入"（§5.5）——同一条规则平移到作息，只有**一个**写入口（`SettingsUiAction.UpdateRoutineTime`）需要拦，代价是一次纯读。

**用户看到什么**（必须模态对话框，不是 toast）：

| 元素 | 内容 |
|---|---|
| 标题 | 「无法修改作息」 |
| 正文 | 「以下药品的提醒时间会跨过零点，请先调整它们的偏移」 |
| 逐行 | 「{药名}：{时段标签} {+/-}{n} 分钟 → {结果钟点}，会跨过零点」（`routine_conflict_line`，参数取自 `OffsetConflict`） |
| 回绕锚点行 | 换成 `time_offset_wrapped_anchor_hint`（§7），不用跨零点文案 |
| 动作 | 「取消」（唯一写操作路径就此结束）／「查看{药名}」（跳该药编辑器，仅第一味提供跳转，避免按钮爆炸） |

不提供「仍然保存」：允许保存等于把"不可物化的状态"写进库，而这正是要排除的。

### 3.8 取消归档：按**当前作息**重算，且只留一条修订

**事实**（§1.7）：`unarchiveMedication` → `setArchived(false)` → `updatePlan(copy(isArchived = false), clock.millis())`（`MedicationRepositoryImpl.kt:98,110-115`），**不重算钟点**；resync 取数 `WHERE isArchived = 0`（`MedicationDao.kt:106-110`）⇒ 归档期间改的作息从未进入这味药。`isArchived` 在快照里（`MedicationPlanRevision.kt:37`）⇒ 取消归档确实会产生 1 条修订，那条修订如实记录了旧计划，但**新**计划的内容是**归档前**的派生值：违反 §2.1 的不变量 `reminderTimes == materialize(routine, periods, offset)`。后果是提醒在错误钟点响，且没有任何路径会修它（resync 只在作息变更时跑）。

**最小改动**（不新增 API 面，只换实现）：

```kotlin
// MedicationRepositoryImpl：注入 ReminderPreferences（或 UserPreferencesRepository）
override suspend fun unarchiveMedication(id: Long) = transactions.withTransaction {
    val row = dao.getMedicationById(id) ?: return@withTransaction
    val schedule = prefs.routineScheduleFor(row.careRecipientId)   // :488-504，按成员读
    dao.updatePlan(row.restoredFor(schedule), clock.millis())      // 一次 updatePlan ⇒ 恰 1 条修订
}
```

- `restoredFor(schedule)`：`isArchived = false`；`anchors = TimePeriods.parse(timePeriod).mapNotNull { anchor }`；anchors 空 / `isPRN` / `intervalHours > 0` ⇒ 钟点不动（无基线，且偏移必为 0，E7）；否则 `requireAllowed(validateTimeOffset(offsetMinutes, schedule, anchors, true))` + `withResolvedRoutineTimes(schedule, anchors, offsetMinutes)`（§5.0 唯一物化点）。
- **归档路径不改**：`archiveMedication` 仍是 `updatePlan(copy(isArchived = true))`（归档不需要钟点，1 条修订、钟点原样），避免为对称而多做一次可能被拒的写。
- **修订链正确性（对齐 §3.2 的 I1-I4）**：整个转换是**一次** `updatePlan`，内容因 `isArchived` 与 `reminderTimes` 双变而必然判为"变了"（内容不同 + 边界前进 ⇒ 分支 2）⇒ 旧段 `[…]` 记为 `isArchived = true` + **归档前钟点**（历史保真），新段从 `changedAt` 起为 `isArchived = false` + **重算后的钟点**。**恰 1 条**，不存在"先恢复再重算"的两次写，也不会撞上分支 3。
- **重算被判非法时**（`CROSS_MIDNIGHT` / `WRAPPED_ANCHOR`）：**拒绝取消归档**，行保持 `isArchived = true`（无半状态），对话框两个动作：「取消」/「将偏移归零并恢复」。归零必须**显式同意**（0 恒合法），是唯一既能让该药恢复、又不写入不可物化状态的做法；它会写一次 `updatePlan` ⇒ 1 条修订。**不**选"恢复但用旧钟点"：那会存下一对无法物化的 `(作息, 偏移)`，违反 §3.7 的全局不变量。
- 该分支实际极少触发：§3.7 的冲突扫描**含归档药**，所以"归档期间把它的偏移弄非法"在写作息时就被拦下；这里只是防御并发窗口（W4）与本特性上线前的历史数据。
- **闹钟**：调用点已 `reconcileReminders.medication(MEDICATION_CHANGED)`（`FocusedSettingsViewModels.kt:210-216`），从 DB 现状重排 ⇒ 新钟点自动生效，**reconcile 零改动**；而 `pausedNow` 读的是 `isArchived` 缓存（`AndroidReminderReconciler.kt:46-59`），恢复后才会真的排期。
- **暂停/恢复不在本次范围**：它走 `setArchivedCache`（`MedicationRepositoryImpl.kt:100-108`）直写 SQL、按 `docs/medication-status-spec.md` 是"状态缓存、0 修订"；若也重算就必须建修订，与那份规格冲突。据实记为残余风险（§10 R-7），并说明它与归档不是同一条路径。

### 3.9 明确**不改**的东西

- requestCode 方案（`id*100+slot` / `+50_000` / `+100_000`）与 `FLAG_UPDATE_CURRENT` 覆盖语义。
- reconcile 触发点与 reason 取值（`ROUTINE_CHANGED` / `MEDICATION_CHANGED` / `SYSTEM_EVENT`），启动与开机兜底。
- 暂停/恢复：事件表 append、按 `scheduledAt` 的 `doseActiveAt` 判定、日期级生效边界、reconcile 前的 `pausedNow` 守卫（`AndroidReminderReconciler.kt:51-56`、`:103-106`）；仍走 `setArchivedCache` 直写 SQL、0 修订（§1.7、§3.8 末条），本次不为它接重算，残余风险见 §10 R-7。
- `handled` 槽集合的语义（全部日志、按精确时刻抑制），只在 `lastTaken` 上用 `planEffectiveFromMs` 裁剪。

## 4. 迁移 v26 → v27（纯新增、零搬运）

```kotlin
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `medications` ADD COLUMN `offsetMinutes` INTEGER NOT NULL DEFAULT 0")
    }
}
```

- 仅 `ALTER TABLE ADD COLUMN`：**不重建表**、不触碰索引与既有列、无 UPDATE 回填（`DEFAULT 0` 即回填）、老二进制/老备份照旧。
- **全部配套项（缺一即在运行时炸，不是编译期）**：
  1. `DatabaseSchema.kt:6` 26 → **27**。
  2. `MedLogDatabase.kt` 增 `MIGRATION_26_27`（紧随 `:336` 的 `MIGRATION_25_26`，同风格 KDoc）。
  3. 实体加 `@ColumnInfo(defaultValue = "0")`——**否则** `TableInfo` 比对（导出 schema 的 `defaultValue="0"` vs 实体声明 `undefined`）会在**打开数据库时**抛 `IllegalStateException`；本仓无先例，靠第 8 条守住。
  4. 提交导出的 **`core/database/schemas/com.driezy.medlog.data.local.MedLogDatabase/27.json`**（跑 `:core:database:kspDebugKotlin` 后的产物）。
  5. `AppModule.kt:103-125` 注册 `MIGRATION_26_27`。
  6. `BackupCompatibilityPolicyTest.kt:24` 的 `assertEquals(26, DatabaseSchema.VERSION)` → 27（`:22-23` 注释同步）。
  7. **androidTest 里每一份硬编码迁移清单**（`grep -rn "MIGRATION_25_26" --include=*.kt .`）：`MedLogDatabaseMigrationTest.kt`（5 处 `runMigrationsAndValidate`）、`CareEventMigrationTest.kt`、`CareNoteMigrationTest.kt`、`CarePeopleMigrationTest.kt`、`CareTodoMigrationTest.kt`、`MedicationStatusMigrationTest.kt`。漏一处只有那条测试运行时才报 "Migration didn't properly handle" ⇒ **以 grep 结果对账，不以"编译过"为准**。
  8. 新增迁移测试（`core/database/src/androidTest/.../MedicationTimeOffsetMigrationTest.kt`，仿 `MedicationStatusMigrationTest.kt:40-115`）：`migrate26To27_onPopulatedDatabase_keepsEveryMedicationAtZeroOffsetAndLosesNothing` — 造 v26 库并灌数据（1 味精确时间、1 味双时段、1 味 `isPRN`、1 味 `intervalHours>0`、1 味归档，各带 2 条 `medication_logs` 与 1 条 `medication_plan_revisions`）。迁移后断言：`medications` 行数不变、**每行 `offsetMinutes = 0`**、`name/timePeriod/reminderTimes/reminderHour/reminderMinute/stock/planEffectiveFromMs` **逐字**不变、两张关联表行数不变、`runMigrationsAndValidate(..., validateDroppedTables = true)` 通过。

## 5. 交互与单一权威

### 5.0 谁解析时段（D3）

| 角色 | 实现 | 位置 |
|---|---|---|
| **唯一权威** | `RoutineSchedule.resolve(anchor): LocalTime`，实现改为 `floorMod(resolveUnwrapped(anchor), 1440)`（输出与今天的 `LocalTime` 加减**逐字相同**） | `core/model/.../RoutineTimeSlot.kt:51-62`；§2.3 |
| **未回绕基线** | `RoutineSchedule.resolveUnwrapped(anchor): Int`（分钟，可 <0 / ≥1440）——所有"回绕"只在这里出现一次，之后的加偏移与校验都在整数域 | 同上，§2.3 |
| **偏移相加的唯一一处** | `RoutineSchedule.resolveWithOffset(anchor, offset)`：`offset == 0` 直接返回 `resolve(anchor)`（祖父）；否则 `m = resolveUnwrapped(anchor) + offset`，`m ∉ 0..1439` 即抛 `IllegalTimeOffsetException`（前置条件由 `validateTimeOffset` 保证，这里是最后一道自检） | 同上，紧随 `resolve` |
| **写路径唯一物化点** | `Medication.withResolvedRoutineTimes(schedule, anchors, offset)`：逐锚点 `resolveWithOffset` → `distinct().sorted()` → 写 `reminderTimes/reminderHour/reminderMinute` | `app/.../data/model/MedicationScheduleMapper.kt:59-68`（新增重载，旧单参重载转调它、传 0） |
| **薄委托** | `ReminderTimeUtils.timePeriodToReminderTime(period, schedule)`（签名**不加** offset 参数 ⇒ 结构上无法二次加偏移）= `EXACT → ""`，其余 `schedule.resolve(period.toAnchor()).format("%02d:%02d")` | `app/.../util/ReminderTimeUtils.kt:24-36` 改为委托 |
| 映射 | `TimePeriod.toAnchor()`：除 `EXACT` 外与 `RoutineAnchor` **同名 1:1**（`TimePeriod.key` 仅用于持久化；映射按 `name`，与 `ResyncRemindersUseCase.kt:53-55` 现有做法一致） | 同上 |
| 删除 | `adjustTime`（`:39-44`）委托后**零生产调用方** ⇒ 同 commit 删除，连同 `ReminderTimeUtilsTest.kt:22-54` 的 6 个用例（由 §8 第 12 条的 parity 测试取代回绕断言） | — |

- 调用方处置：药品侧（`AddMedicationViewModel.kt:422-428`）**不再**用委托，改用 `withResolvedRoutineTimes(schedule, periods, offset)` 的纯函数形式取字符串（预览与保存同一函数、同一偏移）；照护事项（`CareTaskEditorViewModel.kt:148`）**保持调用委托**，行为逐字不变（偏移恒 0，且签名里根本没有偏移）。
- **parity 测试**是这条设计的验收点：对每个 `TimePeriod`（`EXACT` 除外）、每档作息、含回绕边界，`delegate` 必须等于 `authority.resolve(anchor).format()`；两套解析从此不可能各说各话（§8 第 12 条）。
- **偏移只在此一处相加**；读路径（`toDomainSchedule`、`FuturePlanCalculator`、`AlarmScheduler`、小组件、核对页）**不读** `offsetMinutes`，因此不存在双计。

### 5.1 编辑器（单药）

- 位置：`add_section_time_period` 段内、时段 chip 之下（段标题 `:193`、时段 chip 行 `:244-266`、紧随 `add_reminder_hint_format` 的 suggestion chip `:267-280`）；仅当 `!isExactMode` 时显示（`isExactMode` 定义在 `:208`）；PRN/间隔给药不显示。
- 形态：**步进器** `−  0 分钟  +`，9 档全在（-60…+60），步长 15。`0` 显示为中性的 `0 分钟`，非 0 显示 `+15 分钟` / `-30 分钟`。
- **跨零点 = 可见但不可选（D1/R6）**：会越界的档位**照常渲染**，`enabled = false`（视觉置灰，不是隐藏、不是跳到邻近档），并在其下方常驻原因文案：
  `就寝后延 60 分钟将跨过零点，本期不支持`（按方向取 `time_offset_cross_midnight_hint_later` / `_earlier`，参数 = 该锚点标签 + 档位分钟数；越界后的具体钟点由预览行给出）。
  - 多时段取**交集**：只要有一段越界，该档位整体禁用，文案指名**是哪一段**（如 `睡前后延 30 分钟将跨过零点（23:30 → 00:00），本期不支持`）。
  - **绝不钳位**：按钮不可点，值不会自己变成 +15，也不会在保存时被截断。
  - **回绕锚点（§2.3）另有一套置灰**：该药任一时段的基线已回绕 ⇒ **整条步进器**禁用（不是单档），文案 `time_offset_wrapped_anchor_hint`；原因不是"某个档位越界"，而是"这个时段的时间解析本身跨了日，加任何偏移都会跨日"。
  - 若用户的**已存**偏移在当前作息下变得非法（导入后改了就寝时间等），编辑器打开时把该值显示为**错误态**（红字 + 同上文案）且**阻止保存**直到改选合法档——不静默归零（§6 E8）。
- **实时预览**（必做，复用现有 suggestion chip 位）：`早餐后 08:00 → 08:30`；多时段逐条列出。
- 保存：`AddMedicationViewModel.kt:525-580` 事务内先 `requireAllowed(validateTimeOffset(...))`，再 `:552-554` 写三列并带上 `offsetMinutes`；仍走 `updateMedication`（⇒ `updatePlan`，⇒ 一条修订）与 `:583` 的单药 reconcile。若 `updatePlan` 抛 `PlanBoundaryNotAdvancedException`（§3.2），保持草稿、`isSaving=false`、显示「保存失败：计划时间未能前进，请重试或校正系统时间」——**不改**时间戳、**不**自动重试。

### 5.2 计算出的时间展示

- 列表/卡片：沿用 `scheduledMinuteOfDay()`（`HomeHeroPresentation.kt:90`），不新增文本。
- 详情：作息模式目前只显示时段标签（`MedicationDetailScreen.kt:297-300`）；本期**追加** `早餐后 08:00 → 08:30` 一行（同一 preview 字符串），并在时段标签后加**中性小标记**（`+30`）。不写任何"可延后服用"字样。

### 5.3 闹钟（零代码改动）

`AlarmScheduler` 只吃 `toDomainSchedule()`，正点/提前预告/漏服再提醒三条都由 `mainTriggerMs` 派生（§1.4），因此偏移**自动**传导；requestCode 不变（`FLAG_UPDATE_CURRENT` 覆盖同码），不需要新码段、不需要迁移在飞闹钟。作息变更后由 `ROUTINE_CHANGED` 全量 reconcile 收敛（§3.5）。

### 5.4 今日计划 / 今日用药核对 / 小组件 / 依从

| 面 | 变化 |
|---|---|
| 今日计划（时间轴/分组/打卡） | 无代码改动：`scheduledAtMs` 来自投影，自然位移；列表按新时间重排 |
| **今日用药核对** | **无任何改动**。它是**药品级**批量确认（`MedicationDailyReconcileViewModel.kt:238`），不列时间、不写 `medication_logs`。**明确不做**：不在此页显示偏移、不允许在此页改时间 |
| 小组件 | 无代码改动（`WidgetUtils.kt:34` 同一 calculator） |
| 依从率 / 投影 | 无代码改动；同一 `doseActiveAt` 谓词 ⇒ 逐字一致（§8 第 11 条 parity 测试守住） |
| **变更日** | 作息/偏移改动会把**今天**切成"旧段 + 新段"：已打卡剂次留在旧段的原钟点上，`changedAt` 之后的时间按新值出现 ⇒ 变更日可能比平时多出**一条**本周期的待服条目（例：08:15 已打卡、09:00 改作息 ⇒ 当天还有 09:30 一条）。这是既有单药改时间路径的同一行为，由修订链正确切分，**不是**历史保真缺口；用 §8 第 9 条钉住 |

### 5.5 二维码 / 导出 / 导入

- 载荷**新增** `"off": Int = 0`（`MedExportEntry`，`PlanExportSchema.kt:32-63`）；`encodeDefaults=false` ⇒ 0 时**不出现**，体积影响仅 +6~10 B/药。
- **决定：携带偏移，且导入时原样写入、不在导入侧重算**。理由：①偏移是用户意图的**唯一**记录，不携带则接收方首次改作息就把发送方的偏移静默丢掉；②导入侧不重算，因为 `ImportPlanUseCase` 不注入作息偏好（`ImportPlanUseCase.kt:19-22`），重算要新加依赖、还会**悄悄移动二维码当面显示过的时间**；③兼容双向成立——老 App 读新码 `ignoreUnknownKeys=true` 忽略 `off`、仍按 `rt` 走，新 App 读老码 `off` 缺省 0。
- **导入时必须校验 `off`（D1）**：在 `ImportPlanUseCase.kt:25-29` 的 `map` 之后、任何写之前调 `validateImportedOffset(entry)`：语法（15 的倍数、|off| ≤ 60）+ 区间（对每个 `rt` 值验 `rtMinute − off ∈ 0..1439`，即反推基线不得越日）。任一失败 ⇒ 抛 `IllegalTimeOffsetException`，整包失败、**零写入**；`REPLACE` 模式尤其重要——它在 `:33` 是**先删后插**，校验必须早于删除，否则用户的既有数据会被清空后再报错。该反推检查对**一期合法码不会误拒**（合法码的基线必在 `0..1439`）；将来若放开跨零点，`off` 必须配上显式的日偏移标记才可区分——正是本期"不做 next day"的另一面。
- 已知性质（**沿用现状，不是本特性引入**）：`rt` 是发送方的绝对钟点，接收方作息不同则 `rt ≠ 本地作息 + off`，直到接收方下一次改作息触发 resync 才收敛；若收敛时该 `off` 在新作息下越界，走 §6 E8 的"跳过并标非法"规则（不钳位、不静默归零）；若本地锚点本身已回绕（§2.3），`off != 0` 同样落入 E8——导入侧不能判定这一点，因为它按设计**不读**本地作息。

## 6. 边界（规则 + 测试）

**E1 跨零点（睡前 23:30 + 30/45/60）** — 现有 occurrence 生成把 `RoutineAnchored.resolvedTime` 放到**同一日历日**（`ScheduleOccurrences.kt:94`），`LocalTime` 加法还会回绕 ⇒ 00:30 会把这一剂挪到**当天凌晨**（相对 23:30 早约 23 小时）。
规则：**一期不支持，且诚实地不支持**。① 编辑器把越界档位**渲染出来并置灰**，附 `time_offset_cross_midnight_hint_{later,earlier}`（含具体档位与越界后的钟点）；② 数据层 `validateTimeOffset` 返回 `Rejected(CROSS_MIDNIGHT, anchor, base)`，写入口抛 `IllegalTimeOffsetException`；③ **任何地方都没有钳位/取整/方向自动纠正**；④ **不**引入 "next day" 标记（那要改 occurrence 生成，超出本期）。
测试：`bed = 23:30` ⇒ `BEDTIME` 基线 23:30，可选上限 **+15**（23:45 合法；+30 → 00:00、+45、+60 越界）；断言 `+30/+45/+60` 三档**存在但 `enabled=false`** 且带原因文案、点击无状态变化、保存路径对这三个值抛 `IllegalTimeOffsetException` 且**库内 `offsetMinutes` 未变**（无钳位到 +15 的痕迹）；而 `EVENING`（= bed−60 = 22:30）+60 = 23:30 仍合法（同一药品另一时段）。

**E2 作息变更 ∩ 暂停/恢复生效中** — 暂停是 dose 级、按 `scheduledAt` 判定（`doseActiveAt`），与钟点无关；暂停/恢复**不产生修订**（§3.1）。
规则：resync 只改时间、不改状态、不碰事件表；被暂停的剂次位移后**仍然**被排除，且不排闹钟（发送前守卫另有一道）。
测试：今日暂停某药 → 改作息 → 断言投影里该剂次仍被排除、依从率分母仍不含它、`scheduleAllReminders` 不为它排正点/预告/再提醒、`medication_plan_revisions` 增量只来自时间变化（1 条）而不来自状态。

**E3 多时段** — 偏移是**药品级**，对每个时段各自生效后 `distinct().sorted()`（`ResyncRemindersUseCase.kt:56`、`withResolvedRoutineTimes`）。
规则：同一味药的偏移是**统一**位移 ⇒ 各时段间距不变 ⇒ 偏移**不可能**制造新撞点；今天在 0 偏移下撞点被去重的情形，在任何偏移下仍只占一格。
测试：①`wake=07:45, breakfast=08:00` ⇒ `MORNING` 与 `BEFORE_BREAKFAST` 本已撞点，断言 -60..+60 全程只有一格；②`beforeLunch`(11:45) + `afterBreakfast`(08:15) 全程两格且 `reminderTimes` 恒升序；③多时段里**仅一段**越界时，该档位整体禁用且文案指名是哪一段。

**E4 同一时段、两味药、不同偏移** — 每行独立。
测试：A=早餐后 +15、B=早餐后 −15 ⇒ A 08:15 / B 07:45；改 A 为 +30 后断言 B 的行与 B 的三类闹钟时间**逐字不变**，且 B **没有**新增修订（只有 A 一条）。

**E5 偏移/作息在"App 已关闭、闹钟已武装"时被改** — 写完立即 reconcile（`AddMedicationViewModel.kt:583`、`ResyncRemindersUseCase.kt:61`、`ImportPlanUseCase.kt:36`）；若恰在事务提交后、reconcile 完成前被杀，`MedLogApplication.kt:28` 下次启动入队 `SYSTEM_EVENT` 全量 reconcile，开机广播同样（`MedLogBootReceiver.kt:34`）。
规则：reconcile **幂等且以 DB 为唯一真相**，旧码闹钟被同码覆盖，不存在残留。**残余风险（据实说明）**：提交到 reconcile 的窗口里已武装闹钟仍在旧时刻，发送前守卫只校验状态不校验时刻；本特性不新增该窗口（与今天的单药编辑同路径）。
测试（`AndroidReminderReconcilerTest` 风格）：改 DB 后不调 reconcile ⇒ 模拟一次启动 reconcile ⇒ 断言每个槽位 `triggerAtMs` 等于新派生时间、且每个 requestCode 只有一条待发闹钟。

**E6 迁移后的既有药品（偏移 0）** — 派生时间必须**逐字**等于迁移前。
测试：v26 库（含双时段/精确/PRN/归档）迁移后重算 ⇒ 每条 `reminderTimes` 与迁移前字符串相等；`FuturePlanCalculator` 在同一 `now` 下产出的 `scheduledAt` 集合与旧实现相等（零字节漂移）。

**E7 非适格药品的非 0 偏移** — 精确时间、`isPRN`、`intervalHours > 0` 存非 0 偏移没有意义。
规则：`validateTimeOffset` 返回 `Rejected(NOT_APPLICABLE)` ⇒ 写入口抛异常。**不做**"读到就忽略"（那等于静默接受脏数据）。
测试：①编辑器在精确模式/PRN 下不显示步进器且保存写 0；②直接调 `repository.updateMedication(med.copy(offsetMinutes = 15))`（精确模式）⇒ 抛 `IllegalTimeOffsetException`，库内仍为 0。

**E8 已存偏移在**（导入/并发造成的）**当前作息下已非法** — 例：导入 `off=+60`（发送方 `bed=22:00`），本地 `bed=23:30` ⇒ `BEDTIME 23:30 + 60 = 00:30` 越界。**注意**：经由作息变更已**不可能**产生这种状态——§3.7 在写 DataStore 之前就阻断并指名冲突药；E8 只剩两条来源：导入（发送方作息不同，`rt` 原样写入）与并发窗口 W4。
规则：**不钳位、不静默归零、不为它建修订**。resync 跳过该药（不 resolve、不写行、不建修订），保留其旧钟点；该药的编辑器把 `+60` 显示为错误态并**阻止保存**直到用户改选合法档（§5.1）；它**不**阻断作息变更（§3.7 只阻断"由本次变更造成"的非法，否则用户被死锁）。跳过是唯一非破坏性选择：钳位会篡改用户意图，归零会静默移动时间并为"没人做过的改动"建一条修订。
测试：构造上述状态（直接写库造 `off=+60`）→ 改作息 → 断言该药行**逐字不变**、无新修订、其它药照常重算并各得 1 条修订、且**作息变更没有被阻断**（该药本来就不合法）；打开编辑器 ⇒ 显示错误态 + 禁用文案；改选 +15 保存 ⇒ 一次正常 `updatePlan`（1 条修订）。

**E9 取消归档必须按当前作息重算** — `isArchived=false` 与重算在**同一次** `updatePlan`（§3.8）。
规则：一次写 ⇒ 恰 1 条修订：旧段 `isArchived=true` + 归档前钟点，新段 `isArchived=false` + 重算钟点；之后 `reconcileMedication` 按新钟点排期。若重算非法 ⇒ 拒绝恢复、行仍归档、对话框给「将偏移归零并恢复」（显式同意）。
测试：①造药 `bed=22:00` 下 `BEDTIME +60`（合法 23:00）→ 归档 → 把 `bed` 改成 `22:15`（`+60 = 23:15` 仍合法 ⇒ §3.7 **不**阻断；关键是这味药已归档、被 resync 排除，钟点留在 23:00）→ 取消归档 ⇒ 断言行 `isArchived=false`、`reminderTimes = "23:15"`（新 `bed` + 60，而非归档前的 23:00）、修订数 +1、该条修订 `isArchived=true` 且 `reminderTimes="23:00"`、边界前进；②非法分支：造 `bed=23:30` + 归档药 `+60` ⇒ 取消归档被拒、行仍 `isArchived=true`、修订数不变；点「归零并恢复」⇒ `offsetMinutes=0`、钟点 = 23:30、1 条修订。

**E10 回绕锚点不能绕过校验** — `breakfast = 00:10` ⇒ `BEFORE_BREAKFAST` 未回绕基线 `-5`、渲染 `23:55`（§2.3）。
规则：某锚点的未回绕基线 `∉ 0..1439` 且 `minutes != 0` ⇒ 该锚点 `Rejected(WRAPPED_ANCHOR)`；多时段取交集 ⇒ 该药不能设非 0 偏移（整条步进器置灰，`time_offset_wrapped_anchor_hint`）。`minutes == 0` 仍是 `Allowed`（祖父，R4/E6 逐字不变）。注意**只**拒回绕的那些锚点：`AFTER_BREAKFAST` 基线 `25` 未回绕 ⇒ 它自己 `+15 → 00:40` 是**合法**的，不该被连带拒绝。
测试：①`resolveUnwrapped(BEFORE_BREAKFAST) == -5` 且 `resolve(BEFORE_BREAKFAST) == LocalTime.of(23,55)`；②`validateTimeOffset(+15, …, [BEFORE_BREAKFAST])` 与 `(-15, …)` 均 `Rejected(WRAPPED_ANCHOR)`（`-15` 若只做数值区间会算出 23:40 而"通过"——这条正是防它）；③单时段 `AFTER_BREAKFAST +15 == Allowed`（基线 25 → 40），而同一味药若**同时**带 `BEFORE_BREAKFAST` ⇒ 整药 `Rejected` 且 `Rejected.anchor == BEFORE_BREAKFAST`（交集规则，既不放过、也不连带误杀）；④断言 `IllegalTimeOffsetException` 抛出且库内 `offsetMinutes` 仍为 0；⑤`bed = 00:30` 时 `EVENING` 基线 `-30` ⇒ 同样被拒（覆盖 `minusHours` 的回绕），而 `BEDTIME`（1430）`+15 = 1445` ⇒ 走 `CROSS_MIDNIGHT`（两条不同的 `Reason` 各自可辨）。

## 7. 文案（医嘱优先；四语言）

**must**：只描述"提醒/排期时间调整"，**不得**出现"可以推迟/提前服药""灵活服药""弹性时间""医嘱允许"等表述。

| key | zh（`values/`） | en（`values-en/`） | ja（`values-ja/`） | ko（`values-ko/`） |
|---|---|---|---|---|
| `time_offset_section_title` | 提醒时间微调 | Reminder time adjustment | リマインダー時刻の微調整 | 알림 시간 미세 조정 |
| `time_offset_stepper_label` | 相对作息偏移 | Offset from routine | 生活時間からのずれ | 생활 시간 대비 오프셋 |
| `time_offset_preview_format` | %1$s %2$s → %3$s | %1$s %2$s → %3$s | %1$s %2$s → %3$s | %1$s %2$s → %3$s |
| **`time_offset_disclaimer`** | **仅调整提醒与排期时间，不改变医嘱的服药时间；请以医嘱为准。** | **Adjusts reminder and scheduling times only. It does not change the prescribed dosing times — always follow your doctor's orders.** | **通知と予定の時刻のみを調整します。処方された服用時刻は変わりません。必ず医師の指示に従ってください。** | **알림과 일정 시간만 조정합니다. 처방된 복용 시간은 바뀌지 않으며, 항상 의사의 지시를 따르세요.** |
| **`time_offset_cross_midnight_hint_later`** | **%1$s后延 %2$d 分钟将跨过零点，本期不支持** | **%1$s later by %2$d minutes would cross midnight — not available in this version** | **%1$s を %2$d 分遅らせると日付をまたぐため、この版では選べません** | **%1$s을(를) %2$d분 늦추면 자정을 넘어가므로 이 버전에서는 선택할 수 없습니다** |
| **`time_offset_cross_midnight_hint_earlier`** | **%1$s提前 %2$d 分钟将跨过零点，本期不支持** | **%1$s earlier by %2$d minutes would cross midnight — not available in this version** | **%1$s を %2$d 分早めると日付をまたぐため、この版では選べません** | **%1$s을(를) %2$d분 앞당기면 자정을 넘어가므로 이 버전에서는 선택할 수 없습니다** |
| `time_offset_illegal_stored` | 已保存的偏移在当前作息下跨过零点，请重新选择 | The saved offset crosses midnight with the current routine. Please choose another. | 保存されたずれは現在の生活時間で日付をまたぎます。選び直してください。 | 저장된 오프셋이 현재 생활 시간에서 자정을 넘습니다. 다시 선택해 주세요. |
| **`time_offset_wrapped_anchor_hint`** | **%1$s 的时间解析已跨日，本期不能为该药设置偏移** | **%1$s already resolves across midnight; offsets aren't available for this medication in this version** | **%1$s の時刻計算がすでに日付をまたいでいるため、この版ではこの薬にずれを設定できません** | **%1$s의 시간 계산이 이미 자정을 넘어가므로 이 버전에서는 이 약에 오프셋을 설정할 수 없습니다** |
| **`routine_conflict_title`** | **无法修改作息** | **Can't change routine** | **生活時間を変更できません** | **생활 시간을 변경할 수 없습니다** |
| **`routine_conflict_body`** | **以下药品的提醒时间会跨过零点，请先调整它们的偏移** | **These medications' reminder times would cross midnight. Adjust their offsets first.** | **次の薬の通知時刻が日付をまたぎます。先にずれを調整してください。** | **다음 약의 알림 시간이 자정을 넘습니다. 먼저 오프셋을 조정하세요.** |
| **`routine_conflict_line`** | **%1$s：%2$s %3$s 分钟 → %4$s，会跨过零点** | **%1$s: %2$s %3$s min → %4$s would cross midnight** | **%1$s：%2$s %3$s 分 → %4$s は日付をまたぎます** | **%1$s: %2$s %3$s분 → %4$s, 자정을 넘습니다** |
| `routine_conflict_cancel` | 取消 | Cancel | キャンセル | 취소 |
| `routine_conflict_open_medication` | 查看%1$s | Open %1$s | %1$s を開く | %1$s 열기 |
| **`unarchive_illegal_offset_title`** | **无法恢复：提醒时间会跨过零点** | **Can't restore: the reminder time would cross midnight** | **復元できません：通知時刻が日付をまたぎます** | **복원할 수 없습니다: 알림 시간이 자정을 넘습니다** |
| `unarchive_reset_offset` | 将偏移归零并恢复 | Reset offset to 0 and restore | ずれを 0 にして復元 | 오프셋을 0으로 하고 복원 |

- 四语言 = `values/`(zh 默认)、`values-en/`、`values-ja/`、`values-ko/` **同时**落地；缺一即视为未完成。
- 免责声明**常驻**步进器正下方（不是 tooltip、不是首次引导），并在详情页的计算时间旁复述一次短句。
- 禁用档位的说明必须**渲染在屏幕上**（不是 tooltip、不是点击后 toast）：用户要能看见"为什么不给选"。

## 8. 回归测试

1. **算术 / 物化一致性**：`base + offset` 对 10 个 anchor × 9 个偏移档逐格断言；并断言 `o != 0` 时 `resolveWithOffset(anchor, o) != resolve(anchor)`（§3.1 的"改了必然有修订"前提；**回绕锚点跳过这条**：它们的非 0 档在 §2.3 的校验层就被拒绝，到不了物化）。
2. **幂等**：同一 `(routine, periods, offset)` 连续物化两次，`reminderTimes` 字符串相等（守住 §2.1 第 2 点的双计陷阱）。
3. **校验拒绝**（不用钳位）：非 15 倍数（17）、越界（±75/90）、跨零点（bed=23:30 +30）、回绕（`breakfast=00:10` 的任意非 0 档，§2.3）、非适格（精确时间 +15）各一条 ⇒ `IllegalTimeOffsetException` 带 `Reason`，且库内值**原样未变**、事务无残留行。
4. **编辑器**：`bed = 23:30` ⇒ `+30/+45/+60` 三档**存在**且 `enabled = false`、带原因文案；`+15` 可选；多时段取交集并指名越界段；已存非法值时显示错误态且保存被阻。
5. **迁移**：§4 第 8 条（既有药品=0、零丢失、schema 校验通过）。
6. **作息变更 ⇒ 每药恰一条修订**：造 N 味作息锚点药 + 1 味精确 + 1 味 PRN + 1 味间隔 + 1 味归档，改作息 ⇒ `medication_plan_revisions` **恰增 N 条**（其余四味 0 条、行逐字不变），每条的 `effectiveUntilMs == changedAt` 且 `reminderTimes` 是**旧**值；扩展 `ResyncRemindersUseCaseTest.kt:21-46` 的 `inOrder` 断言。
7. **无变化 ⇒ 零写**：同一 `RoutineSchedule` 重复 resync ⇒ `updateMedications` **未调用**（`ResyncRemindersUseCaseTest.kt:49-67` 风格）、修订数不变、`planEffectiveFromMs` 不动。
8. **同日两次改作息**：08:00 与 09:30 各改一次 ⇒ 恰 2 条修订、区间严格相邻不重叠（`rev[0].effectiveUntilMs == rev[1].effectiveFromMs == 当前边界`），`FuturePlanCalculator` 当日不出现重复 `scheduledAt`。
9. **历史不变 + 变更日切分**：改作息后，`changedAt` 之前的每一天投影逐条相等（`medId/scheduledAt/slotIndex` 全等），已打卡剂次仍匹配原 `scheduledTimeMs`；变更日按 §5.4 出现"旧段已打卡 + 新段待服"。
10. **提醒三时刻**：偏移 +30（或改作息后）⇒ 正点 = 新时间、提前预告 = 正点 − `earlyReminderMinutes`、再提醒 = 正点 + `followUpDelayMinutes`，三个 requestCode 仍为 `id*100+slot`、`+50_000`、`+100_000`；只排一条待发。
11. **偏移走修订路径**：编辑器改 0 → +15 ⇒ 恰 1 条修订且其 `reminderTimes` 为旧值、当前行边界前进；投影/依从 parity（同 `now` 下投影剂次时间 == 依从分母里该剂次时间，仿 `MedicationStatusDoseParityTest.kt`）。
12. **双解析 parity**：对每个 `TimePeriod`（`EXACT` 除外）× 多档作息（含 `bed=00:30` 的回绕用例）断言 `ReminderTimeUtils.timePeriodToReminderTime == RoutineSchedule.resolve(anchor).format("%02d:%02d")`；另加一条 `TimePeriod → RoutineAnchor` 名称 1:1 的枚举断言（防将来加枚举时静默漏映射）。
13. **二维码**：`off=30` round-trip；`off=0` 编码后 JSON **不含** `off`；无 `off` 的老串解码为 0；`off=17 / 90 / -75` ⇒ 整包拒绝且 **REPLACE 未删除既有数据**（校验早于删除）。
14. **既有行为不回归**：`ResyncRemindersUseCaseTest`（3 例，改断言不删用例）、`MedicationDaoTransactionTest`（含 `:50-77` 的"库存不建修订"与 `:80-99` 的回滚）、`MedicationAdherenceTest`、`ReminderTimeUtilsTest`（删 `adjustTime` 6 例、其余 14 例保留且仍全绿）、`AlarmScheduler`/`AndroidReminderReconciler` 既有排期测试。
15. **修订链是划分**（§3.2 的 I1-I4）：`planChainStaysPartitionedUnderDuplicateAndBackwardWrites` — 正常写 → 同毫秒写（拒绝）→ 回拨写（拒绝）→ 断言行与修订逐字不变、`assertChain(medId)` 全绿、当天投影每个 `(slotIndex)` 恰一条。**这条在 `maxOf` 方案下必红。**
16. **同内容写是真无操作**：只改 `stock`/`notes`/`isHighPriority`（不动计划字段）⇒ `PlanWriteResult.NoOp`、0 修订、`planEffectiveFromMs` 不动（既有 `MedicationDaoTransactionTest.kt:50-77` 已覆盖库存，这里补"返回 NoOp"与"offsetMinutes 单独变化不可能走到 NoOp"两条断言）。
17. **作息变更阻断**（§3.7）：造 A=`BEDTIME +30`（bed 22:00 ⇒ 22:30）、B=`BEDTIME +15`（⇒ 22:15）；把 bed 改成 **23:30** ⇒ A 算出 00:00（冲突）、B 算出 23:45（合法）⇒ 断言对话框**只列 A**（按药名精确断言：防漏列也防多列）、DataStore **未变**（`reminders.first().routineSchedule.bed == 22:00`）、Room **未变**、修订数不变；改成 **23:15** ⇒ A 算出 23:45 合法、B 算出 23:30 ⇒ 允许写入，A/B 各得 1 条修订。**归档药也参与扫描**：把 A 归档后再试 bed=23:30 ⇒ 仍被阻断（A 在 `getAllMedicationsFor` 的扫描集合里）。
18. **取消归档重算 + 恰一条修订**：§6 E9 第 ① ② 两条。
19. **回绕不绕过**：§6 E10 全部断言，另加"编辑器整条步进器 `enabled=false` + `time_offset_wrapped_anchor_hint`"。

### 8.1 幂等回归（重新核对中断 / 进程重启 / 重复执行）

针对的机制：`ReconcileRemindersUseCase`（先 `runCatching` 立即重排、**再**无条件 `enqueue`，`ReminderReconciliation.kt:14-39`）→ `WorkManagerReminderReconciliationQueue.enqueueUniqueWork(UNIQUE_WORK_NAME, REPLACE)`（`ReminderReconciliationWorker.kt:46-55`，同名唯一作业 ⇒ 重复入队只有一份）→ `ReminderReconciliationWorker.doWork` = `reconcileAll` + 失败 `Result.retry()`（`:26-34`）→ `AndroidReminderReconciler.reconcileAll` 逐成员"先撤销再重建 + 收尾 prune"（`:88-130`）→ `AlarmScheduler` 的 SharedPreferences 登记表（StringSet，`register` 对已有项返回原集合，`AlarmProjectionRegistry.kt:27-28`）+ `PendingIntent` 同 requestCode `FLAG_UPDATE_CURRENT` 覆盖（`AlarmScheduler.kt:178-196`）。

| # | 测试 | 断言（都要有） | 依据的机制 |
|---|---|---|---|
| I1 | **reconcile 半途被杀再重跑**：用可控 `AlarmScheduler`（或 `ReminderPlanner`）在第 2 味药时抛异常，模拟"已撤销第一味、未排第二味" | 重跑一次后：每个 `(requestCode, slot)` **恰好一条**待发闹钟、集合等于新派生的期望值（既无重复也无缺失）；登记表无 phantom | `reconcileAll` 是"按 DB 事实重建"（`:98-116`），`pruneOrphanedProjections`（`:120-128`）兜残留；失败即 `Result.retry()` |
| I2 | **中断后无人重跑**（模拟进程被杀、WorkManager 未及重试）：抛异常后断言"缺失"确实存在，再触发**启动 reconcile**（`MedLogApplication:28`） | 启动一次即收敛到与 I1 相同的终态 | 启动即入队是"final recovery net"（源码注释原文） |
| I3 | **进程重启发生在 resync 事务中**：在 `insertPlanRevision` 之后、`updateMedication` 之前抛异常（现有 `MedicationDaoTransactionTest.kt:80-99` 的 trigger 手法） | 提交/不提交二选一：若回滚 ⇒ 修订数、行钟点、边界三者**同时**为旧值；若提交 ⇒ 三者**同时**为新值；**不存在**中间态。随后启动重算再跑一次 ⇒ 收敛到"钟点 = materialize(当前作息, 偏移)"且**不新增**修订（内容已相同 ⇒ 过滤） | Room 单事务；resync 的"未变则跳过"（`ResyncRemindersUseCase.kt:56-57`） |
| I4 | **同一 resync 连跑两次** | 第二次：`updateMedications` **未调用**、修订数不变、`planEffectiveFromMs` 不动、闹钟集合逐字相同（同一 requestCode 仍只有一条） | `:57` 的相同值过滤 + `register` 幂等 + `FLAG_UPDATE_CURRENT` |
| I5 | **同一 reconcile 连跑两次（不过事务）** | 第二次结束态与第一次**逐字相同**（registry 集合相等、每个槽位 `triggerAtMs` 相等）；不出现"第二轮把第一轮的闹钟取消后没重排" | `cancelAlarmsFor` 只撤登记项（`:275-280`），随后同一循环重建（`:98-107`） |
| I6 | **登记/撤销路径幂等**：`registerProjection` 同目标两次、`cancelAllAlarms` 两次、`cancelAlarmsFor` 对空登记表一次、`unregister` 不存在的目标 | 集合不变（`register` 返回原集合 ⇒ `SharedPreferences.edit` 不写，`:406-413`）；第二次 `cancelAllAlarms` 因 `FLAG_NO_CREATE` 拿到 null 直接 `continue`（`:240-245`），**不抛、不误删他人** | `AlarmProjectionRegistry` 纯集合语义（已有 `AlarmProjectionRegistryTest`，扩展 4 例） |
| I7 | **WorkManager 队列幂等**：连续 3 次 `enqueue(ROUTINE_CHANGED / SYSTEM_EVENT / MEDICATION_CHANGED)` | `getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)` 只有 1 条（`REPLACE`），最终执行的是最后一次 reason；执行完 `reconcileAll` 一次即够 | `enqueueUniqueWork(REPLACE)`（`:50-54`） |

- 这些用例都不引入新机制：它们钉的是**已有**的三层兜底（立即重排 + 唯一作业重试 + 启动/开机全量）在"偏移让每次写都改变钟点"之后仍然成立——本特性只改**写什么钟点**，不改**怎么写/怎么重排**；I1-I7 就是这条断言的回归形式。
- 断言方式：`AlarmScheduler` 侧用可注入的 `AlarmManager` 影子（现有 `AlarmScheduler`/`AndroidReminderReconciler` 测试同款），把 `(requestCode, triggerAtMs)` 收成 `Map<Int, Long>` 后比较，避免依赖真实系统闹钟。

## 9. 一致性检查（本版重跑：四处交叉冲突 + 一处附带）

| 冲突点 | 结论 |
|---|---|
| **① §3.2 的拒绝规则 vs resync 的错误处理** | 一致，但必须写死一条：`updatePlan` 抛 `PlanBoundaryNotAdvancedException` ⇒ 事务回滚 ⇒ `ResyncRemindersUseCase` **不吞**（整批不落 + 页面提示 + 启动重算收敛）。三者互补：拒绝保证"不产生错的行"，回滚保证"不产生半批"，启动重算保证"被拒的意图会被重试"。**反例检查**：若 resync 像 `ReconcileRemindersUseCase` 那样 `runCatching` 吞掉，就会得到"作息已改、钟点永久旧、无人知道"——所以**禁止**在 resync 外层 `runCatching`。这与 reconcile 允许吞掉不矛盾：reconcile 是幂等的全量重建（吞掉后有队列/启动兜底），resync 是数据写（吞掉就把失败藏了）。差异写进 §3.5/§3.7。 |
| **② §3.7 的阻断规则 vs 跨存储失败窗口** | 一致：阻断降低 W1/W4 的概率，但**不假装原子**。W1 的终态"新作息 + 旧但合法钟点"由第 2 步的校验直接证明合法 ⇒ 不变量（库里每一对 `(作息, 偏移)` 都可物化）在任何窗口都成立。W4 是唯一可能破例的路径，靠 resync 的逐药校验（E8 跳过）挡住"写非法钟点"，破例只表现为"某药停在旧钟点 + 编辑器错误态"，**不是**"错时间被写进库"。 |
| **③ §3.8 的取消归档 vs §3.2 的修订规则** | 一致：一次 `updatePlan`、内容必变、边界必前进 ⇒ 只走分支 2，不会撞分支 3（分支 3 要 `changedAt <= 边界`）。**唯一例外**是时钟回拨：那时取消归档被拒、行仍归档，与 §3.2"宁可报错"同向，由 W5 覆盖。与 §3.1"暂停/恢复 0 修订"也不冲突——那是另一条路径（`setArchivedCache`），§3.9 明确不动。 |
| **④ §2.3 的解析瞬时校验 vs §8.1 的幂等** | 一致且互补：回绕判定是**纯函数**（`resolveUnwrapped` + 整数区间），不读时钟、不读 DB ⇒ 同一输入永远同一结论 ⇒ 重跑 resync/reconcile 不会"这次合法、那次非法"地抖动（若校验依赖 `LocalTime` 的回绕结果或 `clock`，I3/I4 就会红）。0 偏移的祖父条款让"未变的药"仍走 `:57` 过滤 ⇒ 启动重算对老数据零写。 |
| 附：§5.5 导入校验 vs §2.3 | 不重叠：导入侧用 `rt − off` 反推基线（没有本地作息可算 unwrapped 基线）验"发送方的码自洽"；写侧用 `resolveUnwrapped + off` 验"本地作息下可物化"。两条都要保留。 |

## 10. 非目标与风险清单（本版重写）

**非目标**（与上一版一致，另加三条由本轮修订明确排除的）

- ❌ 任意分钟数（只能 15 的倍数）；❌ 全局/按时段的统一偏移（必须按药）；❌ 跨零点/`next day` 语义（E1）。
- ❌ 改动处方剂量、剂次数量、依从率定义、库存扣减、`medication_logs`；❌ 给 `medication_plan_revisions` 加偏移列；❌ 给照护事项加偏移（`CareTaskEditorViewModel.kt:148` 恒 0）；❌ 跨设备同步 / 服务端下发。
- ❌ 新 tab / 新页面：只在编辑器内、详情页复述，外加 §3.7 的阻断对话框。
- ❌ 修订表的清理/压缩策略（本期只保证正确性与单调性，行数随编辑次数线性增长）。
- ❌ **把作息搬进 Room 或把偏移搬进 DataStore**（前者是设置模型迁移 + 重写闹钟同步，后者丢失行级 FK/级联与投影读取）——这是 §3.7 接受"派生时间最终一致"的原因。
- ❌ **暂停/恢复的重算**（`setArchivedCache` 仍 0 修订，见风险 R-7）。
- ❌ **自动修补时钟回拨**（不加单调时钟、不加时间戳序列、不加"强行加 1ms"）。

**风险清单**：`设计关闭` = 本轮给出了可证明的最小设计 + 验收测试，**但代码尚未实现**（规格阶段，不宣称已解决）；`仍存在` = 本轮没有解决，如实保留。

| # | 风险 | 状态 | 依据 / 处置 |
|---|---|---|---|
| R-1 | 同毫秒 / 回拨写入让修订区间**重叠** ⇒ 投影与依从率重复计数 | **设计关闭** | §3.2 分支 3 拒绝；I1-I4 + §8 第 15 条的 `assertChain` |
| R-2 | 同毫秒 / 回拨写入**追溯覆盖**历史（上一版 `maxOf` 的残留问题） | **设计关闭** | §3.2 的零长度论证；原地替换只允许"内容逐字相同"；`maxOf` 已明确作废（§3.4） |
| R-3 | 作息变更让已存偏移变非法，而用户无从得知 | **设计关闭** | §3.7 先校验后写 + 指名对话框；E8 只剩导入/并发两个来源 |
| R-4 | 取消归档后钟点停在归档前（旧"归档不补算"风险） | **设计关闭** | §3.8 单次 `updatePlan`（`isArchived` + 重算钟点）；E9 |
| R-5 | 回绕锚点（`breakfast=00:10`）绕过偏移校验 | **设计关闭**（仅"不被偏移放大"这一半） | §2.3 `resolveUnwrapped` + `WRAPPED_ANCHOR`；E10 |
| R-6 | reconcile 中断 / 重启 / 重复执行 ⇒ 重复或缺失闹钟、重复修订 | **机制已存在 + 本轮补测试**（不新增机制） | §8.1 I1-I7 |
| R-7 | **暂停/恢复期间改作息，恢复后钟点陈旧**：`setArchivedCache`（`MedicationRepositoryImpl.kt:100-108`）只翻 `isArchived`、0 修订，且 `ReconcileMedicationStatusUseCase.kt:40-49` 只在**暂停**时 reconcile ⇒ 恢复既不重算也不重排，闹钟要等下一次全量 reconcile | **仍存在（本特性不修）** | 修它必须给状态路径建修订，与 `docs/medication-status-spec.md` 的"0 修订"冲突；"恢复漏 reconcile"是那份规格的既有缺口。归档路径已修（R-4），两者不是同一条路 |
| R-8 | **时钟回拨时该药的计划改动被持续拒绝**（保存/重算都抛 `PlanBoundaryNotAdvancedException`） | **仍存在（有意的取舍）** | §3.2：有明确报错，不改历史；恢复手段是校正系统时间（不做单调时钟/序列） |
| R-9 | **W1/W3 窗口内"新作息 + 旧钟点"**：启动前提醒仍按旧钟点响（合法但过期） | **仍存在（有恢复路径）** | §3.7 第 5 步失败分支的提示 + 启动重算收敛；不是静默态 |
| R-10 | **无全局提示**：E8 状态的药只有它自己的编辑器拦保存；今日计划/设置页不弹横幅 | **仍存在** | 一期不做横幅；§3.7 对话框只覆盖"变更当场" |
| R-11 | **提交→reconcile 窗口**：已武装闹钟在窗口内仍是旧时刻，发送前守卫只校验状态不校验时刻 | **仍存在** | E5；本特性不新增该窗口（与今天的单药编辑同路径） |
| R-12 | **回绕基线本身的日错位**（`breakfast=00:10` 的 `BEFORE_BREAKFAST` 落在同一天 23:55，比早餐晚约 24h） | **仍存在（既有缺陷）** | §2.3 保留祖父行为（R4/E6 逐字不变）；修它要改 anchor 日语义与 occurrence 生成，超出本期。本轮保证的是"偏移不能让这种锚点变得更错" |
| R-13 | **导入的 `off` 在接收方作息不同时可能已非法**（发送方合法） | **仍存在（已界定）** | §5.5：导入不重算 `rt` 是有意为之；落地为 E8 状态，由编辑器修正 |
