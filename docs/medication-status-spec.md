# 药品停用 / 恢复（状态事件 + 生效日期）—— SDD 规格（第 1 稿）

状态：**规格 + 审计；本轮不改任何业务代码、不提交、不推送。**
范围：`我的药品`（`MyMedicationsScreen`）的**批量停用 / 批量恢复**，带**生效日期**与可选**原因/备注**；`medication_status_events` 状态事件表；日期感知的计划投影 / 提醒 / 小组件 / 依从率；迁移 **v25 → v26**。
关联：`docs/medication-daily-flow.md`（今日打卡主流程、`FuturePlanCalculator`/`MedicationAdherenceCalculator` 契约）、`docs/care-tasks.md §11.2`（归档药不得进今日计划的来源与两次修复）、`docs/tracked-events-spec.md`（本仓「纯新增迁移 + 双时间戳（发生 vs 记录）」的最新先例）。

> 触发场景（用户真实工作流）：医生**每日调整处方**，用户需决定哪些药**继续吃 / 停掉 / 重新吃**，并把这三个日期**记下来**。停用/恢复**不是删除**，历史**绝不能丢**。

---

## 0. 四条边界（用户明确，本规格逐条应对）

1. **区分「实际状态变更」与「每日核对当前状态」**：每天对照当前清单打勾「继续服用 / 继续停用」是**核对（CHECK）**，**不得**产生重复状态事件（要求 4）。
2. **生效日期 ≠ 录入时间**：医生的交代发生在昨天、今天才录入是常态（**倒填/事后录入**正常）。两套时间必须分开存（要求 2）。
3. **停用 ≠ 删除 ≠ 永久归档**：真实历史服药记录不得被状态变更覆盖或丢失（要求 5）。
4. **一句话判据**：用户点的是「我要**换一种状态**」还是「我**确认现在就是这样**」？换 → 变更（写事件）；确认 → 核对（不写事件）。

---

## 1. 现状事实（代码核查，file:line）

### 1.1 归档机制端到端

| 事实 | 位置 |
|---|---|
| 状态是**单个 Boolean、无日期**：`Medication.isArchived: Boolean = false`；仅建 `Index("isArchived")` | `core/database/.../model/Medication.kt:79`、`:23` |
| DAO 提供 `archiveMedication` / `unarchiveMedication`（两条 `UPDATE medications SET isArchived=?`），但**归档路径不直接用它** | `core/database/.../local/MedicationDao.kt:96-100` |
| 仓库 `archiveMedication`/`unarchiveMedication` → `setArchived`：**先 `updatePlan(it.copy(isArchived=…), clock.millis())`**，在**录入时刻**打计划版本边界 | `app/.../data/repository/MedicationRepositoryImpl.kt:96-105` |
| `updatePlan(medication, changedAt)`：把旧计划存为 `MedicationPlanRevision`（`effectiveUntilMs=changedAt`），当前行 `planEffectiveFromMs=changedAt`；`changedAt` 恒为 `clock.millis()`（**无外部注入**） | `core/database/.../local/MedicationDao.kt:74-85` |
| 计划快照**自带** `isArchived` 快照列 | `core/database/.../model/MedicationPlanRevision.kt:37`、`planRevision():42-60` |
| 归档 UI 入口：详情页 ⋮ → `archive` 动作 → 弹 `ScreenOverlay.Confirm`（「归档」标题/正文）→ 确认后 `DetailUiAction.Archive` | `app/.../feature/medications/detail/MedicationDetailScreen.kt:126-160`、`:415-419` |
| 详情 VM `archiveMedication()`：已归档则 `unarchiveMedication`，否则 `archiveMedication`，随后 `ReconcileRemindersUseCase.medication(MEDICATION_CHANGED)` | `app/.../feature/medications/detail/MedicationDetailViewModel.kt:136-148` |
| 「已停用」筛选 chip：列表本地布尔 `archived`，`state.medications.filter { it.isArchived == archived }`；chip 文案 `medication_active=进行中` / `medication_archived=已停用` | `app/.../feature/medications/list/MyMedicationsScreen.kt:60-61`、`:104-113`；`res/values/medication_flow_strings.xml:5-6` |
| 列表数据源是**含归档**的全量 `getAllMedications()` | `app/.../feature/medications/list/MyMedicationsViewModel.kt:31` |
| `getActiveMedications`（SQL `isArchived=0`）是首页药物相互作用等处的活跃口径 | `core/database/.../local/MedicationDao.kt:12-19` |
| 计划版本链已被测试钉死（改计划 1000 / 停用 2000 / 恢复 3000 → 快照 `isArchived=[false,false,true]`） | `core/database/src/androidTest/.../MedicationDaoTransactionTest.kt:67-72` |

**结论（现状）**：今天只有「归档/取消归档」一个布尔、**不能带日期、不能带原因、不能表达多次循环**；UI 已把「归档」直接叫**已停用**（`medication_archived=已停用`），即 UI 层已经**把归档等同于停用**——语义的分裂是**新需求**要解决的。

### 1.2 计划投影的陈旧快照隐患（已知 + 反推日期化后果）

- `FuturePlanCalculator.calculate` 逐「计划段」投影：`if (medication.isArchived && (!includeArchived || history.isNotEmpty())) return@planLoop emptyList()`（`app/.../feature/medications/application/FuturePlanCalculator.kt:48-50`）。即：**带 `isArchived=true` 的计划段会被跳过**。
- **已知泄漏**：`HomeViewModel` 与 `WidgetUtils` 都曾被「`isArchived=false` 的陈旧版本快照仍覆盖当天」坑到——归档当天，旧计划段被重新投影成「下一剂」。两处**边界栅栏**修复：`activeMedications = meds.filterNot { it.isArchived }`（`app/.../feature/medications/home/HomeViewModel.kt:571`）、`getAllMedications().first().filterNot { it.isArchived }`（`app/.../capability/widgets/WidgetUtils.kt:29`）；叙述见 `docs/care-tasks.md §11.2`，回归见 `WidgetTodayPlanTest`、`HomeViewModelTest`。
- **反推 ①：倒填停用**。若沿用 `updatePlan(changedAt=clock.millis())`，计划段边界落在**录入时刻 R**，则旧活跃段窗口延伸到 R → 计算器会把 **P（昨天）… R（今天）** 视为应有的剂量 → 依从率把这些天算成 **MISSED**。**必须**把边界设成**生效时刻**（见 §2 D3）。
- **反推 ②：恢复**。恢复现状 = `unarchive` → `planEffectiveFromMs=now`，`AndroidReminderReconciler` 因 `!isArchived` 通过并 `schedule(medication)`（`app/.../capability/reminders/AndroidReminderReconciler.kt:40-42`），闹钟**从当下**重排——对**倒填恢复**（生效昨天、今天录入）在「未来排期」上可接受；但**历史投影**应从生效日恢复活跃。
- **反推 ③：未来生效的停用**。若沿用布尔，`isArchived` 会**立刻**翻 true，今天就不再排期——但医生说的是「**从明天起**停」。布尔**装不下**「已排定但尚未生效」。
- **结论（D3/D7 依据）**：*版本快照不需要新增「暂停窗口」字段*——它已经带 `isArchived` 快照（`MedicationPlanRevision.kt:37`）且计算器**已经**按段跳过。真正缺的是**两点**：(a) 快照的**边界时间戳必须是生效日**（不是录入时刻）；(b) 当前状态要能表达「未来才生效」。补上这两点后，`FuturePlanCalculator` 的逐段跳过**天然**给出正确的历史投影（`MedicationAdherenceTest.kt:57-74` 已证明「停用窗口 → 当天 total=0」）。

### 1.3 依从率：暂停期是否被算成漏服？（**裁决**）

- 计算入口 `MedicationAdherenceCalculator.calculate` **刻意**用 `includeArchived=true` 且过滤条件含 `revisions.any { … }`（`app/.../feature/medications/application/MedicationAdherence.kt:70-80`）以**保留历史**。
- 期望剂量来源 = 计划投影；**过去未匹配到日志的槽位**被合成为 `LogStatus.MISSED`（`:100`），并计入分母（`counted`，`:105-112`）。
- **裁决**：
  - **前向录入**的归档/停用：停用段 `isArchived=true` 被计算器**跳过** → 暂停期**没有**期望剂量 → **不计为漏服**。证据：`MedicationAdherenceTest.kt:57-74`（停用后 `days[today].total == 0`；且 `FuturePlanCalculator` 对停用药 7 天投影为空）。
  - **倒填停用**（当前机制下）：边界落录入时刻 → P…R 旧活跃段仍在投影 → 这些天**会被算成 MISSED**。**这就是要修的缺陷**，修复即 D3（边界=生效日）。
  - **暂停当天**：若生效日=本地日界（00:00），整日无期望剂量；若生效时刻在当天中途，则当天**生效前**的槽位仍属应有剂量（是否算漏服取决于生效口径，本规格将批量 UI 的「生效日期」定义为**本地日界**，见 §3）。
  - **历史/日历屏**：`days` = 计划日 ∪ 有日志日 ∪ 今天（`MedicationAdherence.kt:83`）；暂停期若无日志且无计划，则**不出现**该天。规格要求暂停期改为**显式渲染为「暂停」而非 0%/漏服**（见 §5）。
- 备注：倒填停用后，若 R 之前存在真实服药日志，它们仍作为**额外记录**被计入 `counted`（`:105-106`），可能造成「有 taken 无分母」的显示小瑕疵；规格要求把暂停期内日志标为「暂停前/期间记录」而非计划内打卡（见 §5）。

### 1.4 提醒

| 情形 | 现状行为 | 位置 |
|---|---|---|
| 停用（当前已归档） | `reconcileMedication`：`cancelAllAlarms` + `cancelAllReminderNotifications`；因 `!medication.isArchived` 为假**不再排期** | `AndroidReminderReconciler.kt:31-44`（守卫在 `:40`） |
| 全量重排 | 仅 `!isArchived && !isPRN` 计入存活并排期，其余只清理 | `AndroidReminderReconciler.kt:86-92` |
| 恢复 | `!isArchived` 通过 → `schedule(medication)`（`ReminderPlanner.nextOccurrences` 取「现在之后的下一次」） | `AndroidReminderReconciler.kt:131-143`；`AlarmScheduler.kt:117-130` |
| 登记表身份 | `<recipientId>:med:<id>`，`ReminderTarget`（暂停/恢复**不改变**身份） | `ReminderTarget.kt:15-20`；`AlarmScheduler.kt:304-306` |
| 事务收口 | 所有状态写入都应经 `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` → 立即重排 + 入队 durable retry | `app/.../capability/reminders/application/ReminderReconciliation.kt:14-21` |

**结论**：停用应**立即停闹钟与通知**（现状已对归档如此）；恢复应**从恢复日重排**（现状从「现在」重排，对倒填恢复未来排期可接受）。**任何状态写入（含倒填、未来生效）都必须触发一次 reconcile**——否则通知/小组件/闹钟不会随状态变化更新。**未来生效的停用**在写入当下 `isArchived` 仍应为 false（尚未生效），因此**当时不会停机**；必须在**生效日后**的某个 reconcile 机会（应用启动 / `MedLogBootReceiver`）再次求值并停机（best-effort，与 `docs/tracked-events-spec.md §5 R13` 同口径）。

### 1.5 剂量日志（要求 5）

- 归档路径**只写** `medications` 与 `medication_plan_revisions`（`MedicationRepositoryImpl.kt:100-105` → `MedicationDao.kt:74-85`），**从不触碰** `medication_logs`。`MedicationLogDao` 的删除接口（`deleteLog` / `deleteLogsForMedicationAndDate` / `deleteLogForScheduledTime`，`MedicationLogDao.kt:57-66`）只被「撤销打卡」类用例调用，**不在**状态路径上。
- **结论**：**剂量日志从不被状态变更触碰**——要求 5 对现状成立；新路径必须**保持**该性质（状态写入 = 只 append 事件 + 只改当前计划边界 + 派生缓存）。
- **暂停前/暂停当天的剂量**：是真实记录，永久保留；`FuturePlanCalculator` 的间隔重放只用「已服/部分」日志（`FuturePlanCalculator.kt:100-106`），暂停不会改写它们。

---

## 2. 关键设计决策

**D1 — 采用「状态事件表」（append-only），不用 `isArchived` 承载历史。**
新增 `medication_status_events`（`id, medicationId FK CASCADE, kind, effectiveAtMs, recordedAtMs, reason?`）。**理由**：要求 1/2（生效日期、可选原因）+ 要求「继续吃/停/重吃」的**多次循环**本质是**事件流**；布尔与单对列都装不下。

**D2 — `medications.isArchived` 保留，但降级为「派生的当前状态缓存」，不移除。**
读者太多（`MedicationDao` 的活跃/归档查询、`AndroidReminderReconciler` 两处守卫、`HomeViewModel`/`WidgetUtils` 边界栅栏、`MedicationAdherence`、`MyMedications` 筛选），移除会造成大面积改动与回归面。保留一个**由事件派生的** `isArchived` 列，语义 = 「**当前（生效时刻 ≤ now）处于停用/归档**」。
- 派生函数（纯）：`derivePaused(events, nowMs) = events.filter { it.effectiveAtMs <= nowMs }.maxWith(compareBy(effectiveAtMs, recordedAtMs, id))?.kind in {PAUSE, DISCONTINUE}`；**无事件的历史行**回落到原 `isArchived`（迁移零回填）。
- **每次事件写入**与**每次 reconcile/未来事件到期**后重算并写回 `isArchived`。
- 备选（**否决**）：只加 `pausedAtMs`/`resumedAtMs` 两列。**丢失**：(a) 无法表达**重复循环**（只剩最后一对，用户明确要多次「停—恢复—再停」）；(b) 无「每事件的原因/录入时刻」（要求 1/2）；(c) 无历史可展示与审计；(d) 无法区分「归档（永久）」与「停用（临时）」。

**D3 — 计划版本边界用「生效日」而非「录入时刻」。**
新增/改造写入路径，让 `updatePlan` 接收显式边界 `effectiveAtMs`（当前硬编码 `clock.millis()`，`MedicationDao.kt:75`/`MedicationRepositoryImpl.kt:101`）。停用写：旧段 `effectiveUntilMs=effectiveAtMs`、当前行 `planEffectiveFromMs=effectiveAtMs`、`isArchived=true`。恢复反之。这样才能让**倒填**在历史投影上正确（§1.3 裁决）。
- 边界情形：若 `effectiveAtMs <= previous.planEffectiveFromMs`（倒填早于上一次计划变更），现有守卫 `if (changedAt > previous.planEffectiveFromMs) insertPlanRevision(previousPlan)`（`MedicationDao.kt:80`）会**不落旧段**；规格要求改为「按生效时间**有序插入/裁剪**计划段」，并对「生效日早于最后一版计划」给出确定行为（见 §6 E8）。

**D4 — 计划段快照不新增「暂停窗口」字段；靠「快照 `isArchived` + 生效日边界」即可正确。**（依据 §1.2 结论）

**D5 — 「归档」与「临时停用」= 同一引擎的两个 kind（`DISCONTINUE` / `PAUSE`），不新建第二套关闭语义。**
理由：本仓反复**拒绝两套关闭语义**（`docs/care-notes.md:66`、`docs/todos.md:48`）。归档与停用在**机制上完全一致**（都停前向投影与提醒），差异只在**意图/文案**。详见 §4 的最终建议。

**D6 — 「核对」与「变更」在写入路径上区分：变更 = diff 后 append 事件；核对 = 不写事件（可选轻量 marker）。** 见 §3.4。

**D7 — 未来生效的停用不立即翻 `isArchived`。** 派生状态含 `effectiveAtMs <= now` 判据（D2），未来事件是「已排定」；到期由下一次 reconcile 机会求值（best-effort）。

**D8 — 迁移 v25 → v26 纯新增 + 备份/恢复上界随 `VERSION` 自动放行。** 见 §7。

---

## 3. 数据模型（D1/D2/D5 落地）

```kotlin
// 状态种类是代码常量（对齐 care_event_logs 的 kind 存法；不用 enum Converter，避免迁移/兼容耦合）
object MedicationStatusKind {
    const val PAUSE = "PAUSE"              // 临时停用（可能恢复）
    const val RESUME = "RESUME"            // 恢复
    const val DISCONTINUE = "DISCONTINUE"  // 归档 / 永久终止（可能将来再 RESUME，语义由 UI 区分）
}

@Entity(
    tableName = "medication_status_events",
    foreignKeys = [ForeignKey(
        entity = Medication::class, parentColumns = ["id"], childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.NO_ACTION)],
    indices = [Index("medicationId"), Index(value = ["medicationId", "effectiveAtMs"])],
)
data class MedicationStatusEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val kind: String,                 // MedicationStatusKind.*
    val effectiveAtMs: Long,          // 状态「实际生效」时刻（可倒填/可未来）
    val recordedAtMs: Long,           // 录入时刻（默认为 now；与生效独立）
    val reason: String? = null,       // 可选原因/备注
)
```
- 复合索引 `(medicationId, effectiveAtMs)` 覆盖「取某药最新生效事件」（派生当前状态的主查询）。
- **append-only**：不做就地编辑（与 `care_event_logs` 的「就地编辑+物理删除」**有意不同**——状态事件是**审计链**，改错应**追加更正事件**，不是覆盖）。删除药物 → FK CASCADE 连带状态事件，理由见 §6 E9。
- **DAO**：`getEventsForMedication(id): Flow<List<…>>`、`getLatestEffectiveOnce(id, nowMs)`、`insertEvent`；仓储按 `ActiveRecipientStore` 作用域（`scoped{}`/`currentOrNull()`/`requireRecipientId()+stamped()` 约定，见 `anshin-android-dev`）。

### 3.1 当前状态派生（D2）
- `derivePaused(events, now)`：最近一条 `effectiveAtMs <= now` 的事件 kind ∈ {PAUSE, DISCONTINUE} → 暂停；否则活跃；**无事件** → 回落 `medications.isArchived`。
- 写回时机：① 每次事件写入后；② 每次 reconcile / 应用启动 / 未来事件到期。

### 3.2 与计划投影（§1.2）
- 停用（含倒填）：`effectiveAtMs` 同时作为**计划段边界**（D3）与事件 `effectiveAtMs`；`isArchived` 由 D2 派生。
- 恢复：写 `RESUME` 事件 + 计划段边界=生效日 + `isArchived=false` → `FuturePlanCalculator` 从生效日恢复投影，`AndroidReminderReconciler` 重排。
- 现有边界栅栏（`HomeViewModel.kt:571`、`WidgetUtils.kt:29`）**保持不变且继续正确**：当前暂停的药 `isArchived=true`，天然被滤掉。
- **多段循环**：每次停/恢复各写一对事件与计划段；`FuturePlanCalculator` 的逐段模型本身支持多段（`revisions` 已按 `effectiveFromMs` 排序，`MedicationDao.kt:60-68`）。

### 3.3 与提醒 / 小组件（§1.4）
- 每次状态写入 → `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)`（停用→取消、恢复→重排、小组件 `refreshAll()`，`AndroidReminderReconciler.kt:43`）。
- 登记表身份不变；`pruneOrphanedProjections` 的存活判据继续用「派生 `isArchived`」，暂停药不判存活 → 幽灵闹钟被清。
- 未来生效：写入当下不停；到期日后的下一次 reconcile（启动/开机）求值并停（best-effort）。

### 3.4 核对（CHECK）vs 变更（CHANGE）（要求 4）
统一命令入口 `SetMedicationStatusUseCase`，输入「目标状态 + 生效日 + 原因」：
- **CHANGE**：`desired != deriveCurrentStatus(events, effectiveAtMs)` → append 一个事件 + 更新计划边界 + reconcile。
- **CHECK**（`desired == 当前`）：**不写事件**（写入路径 = diff，天然防重复）。可选：写一个**轻量、不进历史**的核对标记（如 DataStore `med_status_checked#<recipientId>#<day>`，或 `medications` 上一个 `lastReconciledAtMs` 列），仅用于「今天已核对过」的提示；**绝不**写成状态事件。
- 批量：对每个选中 drug 分别 diff；**同一事务**内 append 需要变更的事件；**全部** medication 的 reconcile 在事务提交后各触发一次（`updateMedications` 事务先例见 `MedicationRepositoryImpl.kt:83-86`）。

---

## 4. 归档 vs 临时停用：是否拆成两套语义？（最终建议）

**建议：不拆成两套机制；在**同一张事件表**上用两个 kind 区分（`PAUSE` = 临时停用，`DISCONTINUE` = 归档/永久终止），UI 用**两个标签**呈现。**
- **不新建第二套「关闭」机制**：本仓已有明确先例拒绝双关闭语义（`docs/care-notes.md:66`、`docs/todos.md:48`）。归档与停用在**行为上完全同构**（都停投影、停提醒、都被 `isArchived` 栅栏滤除），再造一套等于重复实现 + 两处可能失同步。
- **但保留用户可感知的两档**：`归档`（长期/永久，语义「这条药不再用了」）与 `临时停用`（医生让先停，可能恢复）。二者都是 `kind`，共享历史与派生逻辑。
- **UI 现状需修正**：今天 `归档` 的按钮/确认弹窗已被叫「已停用」（`medication_flow_strings.xml:6`；`MedicationDetailScreen.kt:126-160`），chip 也叫「已停用」。建议：详情菜单给**两个**入口——`临时停用`（带生效日+原因）与`归档`（永久；映射 `DISCONTINUE`）；列表 `已停用` chip 收纳**两者**，行内加状态角标「暂停中·自 MM/DD」/「已归档」，避免用户以为归档=临时停用。
- **结果**：要求 6 得到「既分语义、又不分机制」的答案——语义分（kind + 文案），机制不分（一张表 + 一个派生列）。

---

## 5. 日期感知的交互（统一口径）

| 面 | 现状（布尔） | 目标（日期感知） |
|---|---|---|
| 计划投影 `FuturePlanCalculator` | 按计划段的 `isArchived` 跳过 | 不变；靠**生效日边界**让段窗口正确（D3）。倒填不再产生假义务 |
| 首页/今日计划边界栅栏 | `filterNot{isArchived}`（`HomeViewModel.kt:571`） | 不变（当前暂停药被滤）。未来生效日之前 `isArchived=false`，正常显示 |
| 小组件 | `filterNot{isArchived}`（`WidgetUtils.kt:29`） | 不变 |
| 依从率 `MedicationAdherence` | `includeArchived=true` 保历史；暂停段被跳过 | 不变 + **暂停期显式呈现为「暂停」**（不显示 0%/漏服）；倒填修复后 P…R 不再是 MISSED |
| 历史/日历屏 | 暂停期无计划则当日不出现 | 暂停期**显式渲染**暂停区间（起止日），区间内不计算漏服分母 |
| 提醒 | 停用即取消、恢复即重排 | 不变；恢复/倒填/未来生效均触发 reconcile；未来生效到期由下一次 reconcile 求值 |

---

## 6. 边界情形（逐条规格）

| # | 情形 | 规格 |
|---|---|---|
| E1 | 停用一个**没有未来剂量**的药（`endDate` 已过 / PRN / 已暂停） | 仍写事件（历史留痕）；排期部分为 no-op。不得因「无未来剂量」而拒绝记录 |
| E2 | **长时间暂停后恢复** | 恢复事件 + 计划边界=恢复日；暂停窗口保持被跳过；`FuturePlanCalculator` 从恢复日恢复 |
| E3 | **连续两次停用**（幂等） | 第二次 desired==当前 → 判为 CHECK，**不产生重复事件**（§3.4） |
| E4 | **同一天先停后恢复** | 两个事件都记录；派生 = 以 `(effectiveAtMs, recordedAtMs, id)` 最新为准；边界相等时定义旧段为零长段（不投影任何剂量） |
| E5 | **生效日在未来** | 事件记录为「已排定」；`isArchived` 到期前保持 false；到期后下一次 reconcile 求值并停机（D7，best-effort） |
| E6 | **倒填停用使已过的一天不再是漏服** | 边界=生效日（D3）→ 计算器不再投影 P…R 的槽位 → 该日不再是 MISSED。**必须有回归测试** |
| E7 | **恢复的日期** | 历史从恢复日恢复活跃；提醒从当下向前重排（未来排期可接受） |
| E8 | **生效日早于该药最后一版计划变更** | `updatePlan` 现有守卫（`MedicationDao.kt:80`）不落旧段；规格要求改为**按生效时间有序插入/裁剪计划段**，避免计划段出现空洞或重叠 |
| E9 | **删除带状态事件的药** | FK **CASCADE**：连同状态事件一起删。**理由**：状态事件离开药无意义、无 UI 载体；本仓对「药拥有」的子行一律 CASCADE（`medication_plan_revisions`、`medication_logs` 皆如此，`MedicationLog.kt:11-17`）。要求 5 的「历史不可丢」约束**跨停用**，不跨**删除**。备选（事件在删除后残留）**否决**：会产生无归属孤儿行，且本仓只在**跨实体**引用才容忍悬挂（`care_note_links.targetId`），非本类 |
| E10 | **批量的原子性** | 一次事务内为 N 个药写事件+计划边界；任一失败整体回滚（对齐 `updateMedications` `MedicationRepositoryImpl.kt:83-86`） |
| E11 | **生效日跨时区** | 批量 UI 的「生效日期」按**成员提醒时区**的本地日界换算为 `effectiveAtMs` |

---

## 7. 迁移 v25 → v26（纯新增）

**SQL 变更**（照 `MIGRATION_24_25` 纯新增先例，`MedLogDatabase.kt:297-320`）：`CREATE TABLE medication_status_events` + 索引 `index_medication_status_events_medicationId`、`index_medication_status_events_medicationId_effectiveAtMs`；**无表重建、零数据搬运**。历史 `isArchived` 行**零回填**（D2 派生回落）。

**本仓全部配套（踩坑清单，逐项必做）**
- [ ] `DatabaseSchema.VERSION` → **26**（`core/database/.../local/DatabaseSchema.kt:6`）。
- [ ] 新增并注册 `MIGRATION_25_26`（`MedLogDatabase.kt`）。
- [ ] `AppModule.provideDatabase.addMigrations(...)` 追加 `MIGRATION_25_26`（`app/.../di/AppModule.kt:98-119`）。
- [ ] 导出 **`26.json`**（`core/database/schemas/com.driezy.medlog.data.local.MedLogDatabase/`，当前止于 `25.json`）。
- [ ] **每一处硬编码迁移列表**都补 `MIGRATION_25_26`（当前含 `MIGRATION_24_25` 的列表共 **13 处**，分 5 个文件）：
  - `MedLogDatabaseMigrationTest.kt` — **5 处**（`:55`、`:108`、`:154`、`:255`、`:347`）。
  - `CareEventMigrationTest.kt` — 2 处（`:46`、`:106`）。
  - `CarePeopleMigrationTest.kt` — 2 处（`:48`、`:137`）。
  - `CareNoteMigrationTest.kt` — 2 处（`:49`、`:156`）。
  - `CareTodoMigrationTest.kt` — 2 处（`:54`、`:123`）。
  - （`CareRecipientMigrationTest`/`CareTaskMigrationTest` 只迁到固定旧版本 19/20，**不含** `MIGRATION_24_25`，**无需**改。）
- [ ] 版本断言：`RoomModelContractTest.kt:34`（`assertEquals(25, …)` → **26**）。
- [ ] `BackupCompatibilityPolicyTest.kt:24`（`assertEquals(25, VERSION)` → **26**）并补 `assertTrue(canRestore(25))`。
- [ ] 新增 **`MedicationStatusMigrationTest`**（照 `CareTodoMigrationTest` 模板）：**种一个带数据的 v25 库 → 迁移 → 断言**：旧数据零丢失（药/日志/计划快照/成员等仍在）、`medication_status_events` 存在且可读写、两条索引存在、删除药级联删事件、`isArchived` 派生回落正确。
- [ ] 四套 locale 文案（`values`/`-en`/`-ja`/`-ko` 的 `medication_flow_strings.xml`）。

**备份/恢复后果**：`BackupCompatibilityPolicy.canRestore` 上界跟随 `VERSION`（`BackupRestoreUseCase.kt:83-88`）→ 恢复的 **v25 备份自动放行**，首次打开依次迁移到 26（同一段迁移覆盖该路径，`MedicationStatusMigrationTest` 即为此设）。既有约束不变：**旧版本 App 打不开新版本备份**（非本特性缺陷）。

---

## 8. 影响面（文件级）

**新增**
- `core/database/.../model/MedicationStatusEvent.kt`（+ `MedicationStatusKind` 常量）
- `core/database/.../local/MedicationStatusEventDao.kt`
- `app/.../data/repository/MedicationStatusRepository.kt` + `Impl`（成员作用域）
- `app/.../feature/medications/application/SetMedicationStatusUseCase.kt`（单药 + 批量，含 diff/CHECK 逻辑、`derivePaused` 纯函数）
- 迁移测试 `MedicationStatusMigrationTest`
- `docs/medication-status-spec.md`（本文件）

**修改**
- `DatabaseSchema.kt`（25→26）、`MedLogDatabase.kt`（注册实体 + `MIGRATION_25_26`）、`AppModule.kt`（DI）
- `MedicationDao`/`MedicationRepository`（`updatePlan` 接收显式生效边界；新增批量命令）
- `MyMedicationsScreen`/`MyMedicationsViewModel`（多选 + 批量动作栏 + 状态角标）、`MedicationDetailScreen`/`MedicationDetailViewModel`（停用/归档双入口 + 生效日/原因）
- `MedicationAdherenceCalculator`（暂停期呈现为「暂停」而非漏服）+ 历史/日历屏
- `AndroidReminderReconciler`（派生状态求值口径，若有变化）
- 迁移/契约测试（§7 清单）

---

## 9. 任务分解（每步可独立验证）

- [ ] T1 实体 + DAO + 仓储 + v25→v26 迁移（迁移测试：种 v25 有数据 → 零丢失、级联、`isArchived` 派生回落）
- [ ] T2 `derivePaused` 纯函数 + `SetMedicationStatusUseCase` 的 CHANGE/CHECK diff（单测：重复停用幂等、同日后停恢复、倒填/未来生效、批量原子性）
- [ ] T3 计划边界用生效日（改 `updatePlan` 签名 + 有序插入/裁剪，E8 用例）；回归：`FuturePlanCalculatorTest`、`MedicationAdherenceTest`、`MedicationDaoTransactionTest`
- [ ] T4 依从率/历史屏：暂停区间呈现为「暂停」，倒填不再产生 MISSED（单测 + 契约测试）
- [ ] T5 提醒/小组件：状态写入触发 reconcile、未来生效到期求值（回归 `AndroidReminderReconcilerTest`、`WidgetTodayPlanTest`）
- [ ] T6 我的药品多选 + 批量动作栏（停用/恢复 + 生效日 + 原因）+ 详情双入口（Compose 契约测试，`internal …Content` + state 对象断言 dispatch；不新增底部 Tab）
- [ ] T7 真机全链路：批量停用（倒填）→ 首页/小组件不再出现 → 依从率不再漏服 → 批量恢复 → 从恢复日重排

---

## 10. 非目标（本期明确不做）

- 不新增第 6 个底部 Tab（`NavigationHierarchyTest` 钉死 5 个顶层目的地）。
- 不做状态事件的**就地编辑/删除 UI**（append-only 审计链；改错=追加更正事件）。
- 不做用药医嘱/剂量与状态的联动（医生处方文本解析、按处方自动停用）——医生调整仍由用户手动决定。
- 不做跨设备同步 / 云端状态历史。
- 不改 `MedicationLog` 的既有撤销=物理删除语义；不改打卡主流程。
- 不迁移/清除既有的 `isArchived` 历史（零回填）。
- 不做「未来生效」的精确定时（不做新闹钟）；到期由既有 reconcile 机会 best-effort 求值。

---

## 11. 验收

1. 批量停用（多选）+ 生效日 + 原因 → **一个事务**写入，全部选中药立即从今日计划/小组件消失，提醒取消。
2. **生效日 ≠ 录入时间**：倒填（生效昨天、今天录入）后，昨天/今天**不再**被算成漏服；历史屏显示暂停区间。
3. 批量恢复（可倒填）→ 从恢复日重新排期；当日/未来计划恢复；提醒重排。
4. **核对不产生事件**：对当前状态点「继续服用/继续停用」→ `medication_status_events` **行数不变**（要求 4）。
5. **历史永不丢**：任何停/恢复操作后，`medication_logs` 行数与内容不变（要求 5）；暂停前/当天的打卡记录仍在。
6. **多次循环**：停→恢复→再停→再恢复可表达且投影正确（事件数=4，计划段正确衔接）。
7. **未来生效**：生效日前仍照常服药/提醒，到期后（下一次 reconcile）停用。
8. **两档语义**：`临时停用` 与 `归档` 共用历史与派生，但文案/入口可分；列表 `已停用` chip 收纳两者并带角标。
9. **迁移**：v25 库升级到 26 零数据丢失；恢复的 v25 备份正常打开；§7 全部测试通过。
10. **零回归**：既有 5 个顶层目的地不变；用药打卡/跳过/撤销、照护事项、提醒时刻与基线一致。
