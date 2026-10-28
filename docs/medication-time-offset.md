# 单药提醒时间偏移（per-medication time offset）— SDD 规格

状态：待实施（docs-only；本文件不改业务代码、不提交、不推送）。
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

## 1. 现状事实（代码核查，file:line）

### 1.1 时段 → 具体钟点：类型化解析（权威候选）

| 事实 | 位置 |
|---|---|
| 作息模型 = 5 个槽（WAKE/BREAKFAST/LUNCH/DINNER/BED，默认 07:00/08:00/12:00/18:00/22:00） | `core/model/src/main/kotlin/com/driezy/medlog/data/model/RoutineTimeSlot.kt:26-48` |
| `RoutineSchedule.resolve(anchor)`：早/午/晚餐各 ±15 分、`EVENING = bed − 60`、`AFTERNOON` 恒 15:00；用 `LocalTime.plus/minusMinutes`（**会回绕**） | 同上 `:51-62` |
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

**结论（推翻上一版草案 §3 的"既有缺口"判断）**：resync 在 HEAD 上**已经**逐药走 `updatePlan`，因此"改作息不留历史快照"的缺口**不存在**。上一版草案据以立论的 `MedicationDao.kt:88-91` 只是 DAO 层的批写方法，repository 层并没有用它（`MedicationRepositoryImpl.kt:83-86`，`git blame` 显示逐药 `updatePlan` 自 `3aae933f` 起就在）。所以 D2 不需要新入口、不需要新 DAO 方法，只需**补一条同毫秒规则 + 把物化带上偏移**。

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

单一校验函数（core/model，纯函数，无 Android 依赖）：

```kotlin
sealed interface TimeOffsetCheck {
    data class Allowed(val minutes: Int) : TimeOffsetCheck
    data class Rejected(
        val minutes: Int,
        val anchor: RoutineAnchor,
        val base: LocalTime,       // 该锚点在本作息下的基线钟点
        val reason: Reason,
    ) : TimeOffsetCheck
    enum class Reason { NOT_MULTIPLE_OF_15, OUT_OF_RANGE, CROSS_MIDNIGHT, NOT_APPLICABLE }
}

fun validateTimeOffset(
    minutes: Int,
    schedule: RoutineSchedule,
    periods: List<RoutineAnchor>,   // 空 = 精确时间，没有可解析的基线
    applicable: Boolean,            // false = PRN / intervalHours > 0
): TimeOffsetCheck
```

判定顺序：① 非 15 的倍数 ⇒ `NOT_MULTIPLE_OF_15`；② `|minutes| > 60` ⇒ `OUT_OF_RANGE`；③ `!applicable || periods.isEmpty()` 且 `minutes != 0` ⇒ `NOT_APPLICABLE`；④ 逐锚点算 `base.minuteOfDay + minutes`，任一落出 `0..1439` ⇒ `CROSS_MIDNIGHT`（**多时段取交集**：任一段越界，该档位整体不可用，`Rejected` 指明是哪一段）。

- 写侧（编辑器保存、导入、任何直接调用的数据层入口）用 `requireAllowed(...)`；不合规抛**已定义**的 `class IllegalTimeOffsetException(val check: TimeOffsetCheck.Rejected) : IllegalArgumentException`。**不**截断、**不**取整、**不**按方向交换。
- 抛出点在任何写入之前 ⇒ 事务整体回滚，无半行数据（§3.5）。
- 迁移/老数据：`DEFAULT 0` ⇒ 构造上合法，无需清洗（§4）。
- 区间判定用的是**数值分钟**（`0..1439`），它识别"同日基线 + 偏移越过 24:00"；它**不能**识别"基线本身已回绕"（如 `breakfast = 00:10` 的 `BEFORE_BREAKFAST = 23:55`，这是既有缺陷，见 §10）。

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
| 归档 / 取消归档 | 1 条 | 前进 | `isArchived` 在快照；`MedicationRepositoryImpl.kt:110-115` → `updatePlan` |
| **暂停 / 恢复（状态事件）** | **0 条** | 不动 | 只 append `medication_status_events`（`MedicationStatusRepositoryImpl.kt:26`），不碰 `medications` 行 ⇒ 不经过 `updatePlan` |
| 库存变化（`updateStock`） | 0 条 | 不动 | 走专用 SQL；既有测试 `MedicationDaoTransactionTest.kt:50-77` |
| 备注 / 优先级 / 备货设置 | 0 条 | 不动 | 不在快照里 ⇒ `previousPlan == nextPlan` |
| 无变化保存（同名同值） | 0 条 | 不动 | `previousPlan == nextPlan`（`MedicationDao.kt:79,82-84`） |
| 同毫秒的重复写（同一药） | 0 条，**原地替换** | 不动 | §3.2 |

> `offsetMinutes` **不**进修订快照（`planRevision()` 不读它），因此"偏移变化 ⇒ 修订"靠的是**物化后的 `reminderTimes` 变化**。这是有意为之：修订行只需能复原历史钟点，多存一个无消费者的偏移就是多一个可能说谎的字段。附带硬规则：对任何合法档位、任何锚点，`resolveWithOffset(anchor, o)` 在 `o != 0` 时必须与 `resolve(anchor)` 不同（§8 第 1 条逐格断言，防"改了却没修订"）。

**为什么是"每味药一条"而不是"每次编辑一条"**：修订表以 `medicationId` 为外键、按药各自成链（`MedicationPlanRevision.kt:11-20`）；N 味药同一次作息变更就是 N 条不同药的修订行，同一边界。这既满足"恰一条/变化药"，也不会把 A 药的时间写进 B 药的历史。

### 3.2 同一日的规则（`updatePlan` 的守卫缺口）

守卫是**严格毫秒比较**（`changedAt > previous.planEffectiveFromMs`），不是日历日比较。因此：

- **同日、不同毫秒的第二次编辑**：正常插入，得到两条区间相邻的修订（例如 08:00 改一次、09:30 再改一次，当天被切成三段）。**这是要保留的行为**，也是已打卡剂次能继续按旧 `scheduledTimeMs` 匹配的前提。
- **`changedAt <= previous.planEffectiveFromMs`**（同一毫秒的两次写、系统时钟回拨、或调用方传了更早的时间戳）：**今天会跳过插入，却仍然把边界改写成 `changedAt`**（`MedicationDao.kt:80-81`）。后果是**重叠**：上一修订的 `effectiveUntilMs` 仍指向旧边界，当前行却从更早的 `changedAt` 开始 ⇒ `FuturePlanCalculator` 的段循环（`:55-59`，段间不去重）会为重叠窗口产出**两份同剂次**，投影与依从率分母双双翻倍。这是必须补的规则，不是"可接受"。

**规则（最小改动，约 4 行）**：

```
effectiveAt = maxOf(changedAt, previous.planEffectiveFromMs)
if (effectiveAt > previous.planEffectiveFromMs) { insertPlanRevision(previousPlan); 边界 = effectiveAt }
else                                          { 不加修订；边界保持 previous.planEffectiveFromMs }
```

- 等值分支 = **零长度区间**，区间内不可能存在任何 occurrence ⇒ 不产生"被覆盖的历史"；同一毫秒的重复写因此安全归一。多药批写时 N 味药形参不同、各自成链，互不影响。
- 反向时钟（`changedAt` 早于既有边界，例如系统时间被回拨）同样落到等值分支：**边界永不后退**（单调性优先），新计划**从既有边界起原地生效**。代价如实说明：边界到"现在"这段**已过**的区间会用新钟点重算，而 `medication_logs` 里已打卡行仍保留旧 `scheduledTimeMs`——靠 `DoseLogMatcher` 的 4 小时就近兜底一般仍能配上，但当日条目可能显示为"与当前计划不符"。这是回拨场景下唯一不破坏单调性的选择（§10 第 3 条）。
- **拒绝的替代**：把边界夹到"当日 0 点"（会把同日多次修改合并成一条，历史在当天被覆盖）；给每条写加 `changedAt += 1`（制造 1ms 碎片区间）；在事务里读时钟自增序列（新表，超出本期）。

### 3.3 最小安全改动（D2 落地清单）

1. `MedicationDao.updatePlan`：按 §3.2 加 `maxOf` 规则（**唯一的业务代码改动点 1**）。
2. `ResyncRemindersUseCase` 的过滤与物化带上偏移（`validateTimeOffset` + `withResolvedRoutineTimes(schedule, periods, offset)`，§5.1）——这是 D1/D3 的改动，顺带覆盖 resync。
3. 删除死代码 `MedicationDao.updateMedications`（`:87-91`，零调用方）：它是唯一一条"整批改计划、不建修订"的入口，留着会被下一个人用错。删除即消除风险，无行为变化。
4. **不新增**批量 DAO 方法：`MedicationRepositoryImpl.updateMedications`（`:83-86`）已经是"一个事务 + 一个 `changedAt` + 逐药 `updatePlan`"，正是想要的形态。

### 3.4 拒绝的替代方案

| 方案 | 为什么拒绝 |
|---|---|
| resync 逐药调 `repository.updateMedication`（各自 `clock.millis()`） | N 个边界（1~N ms 抖动），同一天出现亚秒级碎片区间；且每药一次独立事务（部分失败即半批） |
| 新增 `updatePlans(list, changedAt)` 批量 DAO | 与现有 4 行等价，只多一个 API 面；两个入口迟早分叉 |
| 把 `changedAt` 对齐到次日 0 点 | 今天剩余时间的提醒与设置页不一致；"生效日"从一个变成两个；用户改作息正是因为今天起晚了 |
| 给修订加 `offsetMinutes` 列 | 无消费者的字段 + 读侧双计风险（§2.1 第 2 点） |
| 在 `updatePlan` 里 `coerce` 边界 | 静默合并同日历史，与"历史不可变"直接冲突 |

### 3.5 事务边界（新 resync 路径）

```
withTransaction {                                  // RoomTransactionRunner.kt:15-16
  val changedAt = clock.millis()                   // 一次，全批共用
  for (med in changed) {                           // 未变化的药根本不进这个列表
     dao.updatePlan(med, changedAt)                // @Transaction（嵌套，同一连接）
       ├ getMedicationById(id)                     // 事务内读，与写一致
       ├ insertPlanRevision(previousPlan)          // 仅当 effectiveAt > 旧边界
       └ updateMedication(row.planEffectiveFromMs = effectiveAt)
  }
}                                                  // 提交：N 行药品 + N 条修订 + N 个边界，原子
reconcileReminders.all(ROUTINE_CHANGED)            // 提交之后，不在事务内
```

- 一个事务里恰好三类写：**药品的新钟点**、**它们的修订行**、**当前行的边界**。任一失败全回滚（既有单药断言 `MedicationDaoTransactionTest.kt:80-99`，新增 N 药批次断言）。
- reconcile **不进事务**（不能在事务里碰 `AlarmManager`），且它自身 `runCatching` + 入队重试（`ReminderReconciliation.kt:14-21`）⇒ 提交成功、重排失败也不会留下"DB 已改、闹钟永不更新"的静默态。
- 无变化 ⇒ `updates` 为空 ⇒ 不进事务、不写行、不建修订；`reconcile` 仍无条件跑（既有行为，幂等，不作优化以免改动重排触发点）。

### 3.6 明确**不改**的东西

- requestCode 方案（`id*100+slot` / `+50_000` / `+100_000`）与 `FLAG_UPDATE_CURRENT` 覆盖语义。
- reconcile 触发点与 reason 取值（`ROUTINE_CHANGED` / `MEDICATION_CHANGED` / `SYSTEM_EVENT`），启动与开机兜底。
- 暂停/恢复：事件表 append、按 `scheduledAt` 的 `doseActiveAt` 判定、日期级生效边界、reconcile 前的 `pausedNow` 守卫（`AndroidReminderReconciler.kt:51-56`、`:103-106`）。
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
| **唯一权威** | `RoutineSchedule.resolve(anchor): LocalTime` | `core/model/.../RoutineTimeSlot.kt:51-62` |
| **偏移相加的唯一一处** | `RoutineSchedule.resolveWithOffset(anchor, offset): LocalTime = resolve(anchor).plusMinutes(offset)`，结果越界即抛 `IllegalTimeOffsetException`（前置条件由 `validateTimeOffset` 保证，这里是最后一道自检） | 同上，紧随 `resolve` |
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
  - 若用户的**已存**偏移在当前作息下变得非法（导入后改了就寝时间等），编辑器打开时把该值显示为**错误态**（红字 + 同上文案）且**阻止保存**直到改选合法档——不静默归零（§6 E8）。
- **实时预览**（必做，复用现有 suggestion chip 位）：`早餐后 08:00 → 08:30`；多时段逐条列出。
- 保存：`AddMedicationViewModel.kt:525-580` 事务内先 `requireAllowed(validateTimeOffset(...))`，再 `:552-554` 写三列并带上 `offsetMinutes`；仍走 `updateMedication`（⇒ `updatePlan`，⇒ 一条修订）与 `:583` 的单药 reconcile。

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
- 已知性质（**沿用现状，不是本特性引入**）：`rt` 是发送方的绝对钟点，接收方作息不同则 `rt ≠ 本地作息 + off`，直到接收方下一次改作息触发 resync 才收敛；若收敛时该 `off` 在新作息下越界，走 §6 E8 的"跳过并标非法"规则（不钳位、不静默归零）。

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

**E8 已存偏移在作息变更后变得非法** — 例：导入 `off=+60`（发送方 `bed=22:00`），本地 `bed=23:30`，用户随后改了作息 ⇒ `BEDTIME 23:30 + 60 = 00:30` 越界。
规则：**不钳位、不静默归零、不为它建修订**。resync 跳过该药（不 resolve、不写行、不建修订），保留其旧钟点；该药的编辑器把 `+60` 显示为错误态并**阻止保存**直到用户改选合法档（§5.1）。跳过是唯一非破坏性选择：钳位会篡改用户意图，归零会静默移动时间并为"没人做过的改动"建一条修订。
测试：构造上述状态 → 改作息 → 断言该药行**逐字不变**、无新修订、其它药照常重算并各得 1 条修订；打开编辑器 ⇒ 显示错误态 + 禁用文案；改选 +15 保存 ⇒ 一次正常 `updatePlan`（1 条修订）。

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

- 四语言 = `values/`(zh 默认)、`values-en/`、`values-ja/`、`values-ko/` **同时**落地；缺一即视为未完成。
- 免责声明**常驻**步进器正下方（不是 tooltip、不是首次引导），并在详情页的计算时间旁复述一次短句。
- 禁用档位的说明必须**渲染在屏幕上**（不是 tooltip、不是点击后 toast）：用户要能看见"为什么不给选"。

## 8. 回归测试

1. **算术 / 物化一致性**：`base + offset` 对 10 个 anchor × 9 个偏移档逐格断言；并断言 `o != 0` 时 `resolveWithOffset(anchor, o) != resolve(anchor)`（§3.1 的"改了必然有修订"前提）。
2. **幂等**：同一 `(routine, periods, offset)` 连续物化两次，`reminderTimes` 字符串相等（守住 §2.1 第 2 点的双计陷阱）。
3. **校验拒绝**（不用钳位）：非 15 倍数（17）、越界（±75/90）、跨零点（bed=23:30 +30）、非适格（精确时间 +15）各一条 ⇒ `IllegalTimeOffsetException` 带 `Reason`，且库内值**原样未变**、事务无残留行。
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

## 9. 一致性检查（本版重跑的四处）

| 冲突点 | 结论 |
|---|---|
| **"派生时间"不变量 vs 物化的 `reminderTimes`** | 不冲突，但要有归属：`reminderTimes` 是**派生缓存**（§2.1），`offsetMinutes` 是**唯一新增输入**，物化只有一个函数（§5.0）。校验放在写入口、且读路径不读偏移 ⇒ "缓存脏"不会传染到任何读路径，最坏情况是该药在编辑器里被要求修正（E8） |
| **resync 建修订 vs 暂停/恢复的边界行为** | 不冲突：暂停/恢复只 append 事件表、日期级生效、0 修订（§3.1）；resync 只写时间、不碰事件表；`doseActiveAt` 按 `scheduledAt` 判定与钟点无关（E2）。唯一交集是**变更日**的位移可能让某剂次跨过当天 0 点：这只能来自偏移越界（已被 E1 排除）或作息槽本身落在 0 点附近（既有回绕缺陷，§10 第 2 条，与偏移无关、不新增风险） |
| **校验拒绝 vs 二维码导入** | 一致：`off` 的校验在任何写之前（含 REPLACE 的删除），拒绝即整包零写入；`rt` 仍原样写入不重算；导入**不**用本地作息做跨零点判定（那会拒绝发送方合法的码），而是在下一次改作息时才可能触发 E8 |
| **跨零点禁用 vs 偏移是"药品级"** | 多时段取交集（任一段越界即整档禁用），文案指名越界段；不做"按段分别限制"，那会让同一药品的偏移变成 N 份 |

## 10. 非目标与据实说明

- ❌ 任意分钟数（只能 15 的倍数）；❌ 全局/按时段的统一偏移（必须按药）；❌ 跨零点/"next day" 语义（E1）。
- ❌ 改动处方剂量、剂次数量、依从率定义、库存扣减、`medication_logs`；❌ 给 `medication_plan_revisions` 加偏移列；❌ 给照护事项加偏移（`CareTaskEditorViewModel.kt:148` 恒 0）；❌ 跨设备同步 / 服务端下发。
- ❌ 新 tab / 新页面：只在编辑器内、详情页复述。
- ❌ 修订表的清理/压缩策略（本期只保证正确性与单调性，行数随编辑次数线性增长）。

**据实说明（不能保证的部分）**：

1. **归档期间不补算**：resync 只取未归档药品（`MedicationDao.kt:106-110`），因此归档期间改作息、之后取消归档的药会显示归档前的钟点，直到下一次改作息或手动调整。既有行为，本期不修。
2. **回绕基线**：`resolve` 用 `LocalTime` 加减（会回绕），`breakfast = 00:10` 时 `BEFORE_BREAKFAST = 23:55` 属既有缺陷；偏移的合法性只做数值区间判定，识别不了这种"基线本身已跨日"的情况（§2.2）。修它要改 anchor 语义，超出本期。
3. **时钟回拨**：边界永不后退（§3.2），新计划从既有边界起原地生效，因此"边界→现在"这段已过区间会被重算；已打卡日志保留旧 `scheduledTimeMs`，仅靠 4 小时就近兜底匹配，跨度很大时当日打卡可能显示不符。
4. **无全局提示**：某药偏移因作息变化失效时，只有该药的编辑器会拦保存（E8），今日计划页/设置页不会弹提示——一期不做横幅。
5. **提交→reconcile 窗口**：已武装闹钟在窗口内仍在旧时刻，发送前守卫只校验状态不校验时刻（E5）。
