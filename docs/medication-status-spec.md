# 药品停用 / 恢复（状态事件 + 生效日期）—— SDD 规格（第 3 稿·终稿修订）

状态：**规格 + 审计；本轮只改本文件，不改任何业务代码、不提交、不推送。**
范围：`我的药品`（`MyMedicationsScreen`）的**批量停用 / 批量恢复**，带**生效日期**与可选**原因/备注**；`medication_status_events` 状态事件表（**append-only、可更正**）；日期感知的计划投影 / 提醒 / 小组件 / 依从率；**两种事实分离**（系统自动核对 vs 用户主动核对）；迁移 **v25 → v26**。
关联：`docs/medication-daily-flow.md`（今日打卡主流程、`FuturePlanCalculator`/`MedicationAdherenceCalculator` 契约）、`docs/care-tasks.md §11.2`（归档药不得进今日计划的来源与两次修复）、`docs/tracked-events-spec.md`（「纯新增迁移 + 双时间戳（发生 vs 记录）+ **发送前再校验**」的最近先例）。

> 触发场景（用户真实工作流）：医生**每日调整处方**，用户需决定哪些药**继续吃 / 停掉 / 重新吃**，并把这三个日期**记下来**。停用/恢复**不是删除**，历史**绝不能丢**。

---

## 0. 决策账本（本稿逐条落点）

| # | 决策（用户原意） | 落点 |
|---|---|---|
| 决策 1 | 暂停(PAUSE)与归档(DISCONTINUE)共用**一套状态事件机制**，但 **UI 语义必须区分** | §3 D5、§10 |
| 决策 2 | 允许倒填生效日，**必须重算历史应服剂量与依从率**；**剂量日志永不丢**；历史统计变化**必须可解释** | §5、§6、§16 |
| 决策 3 | 未来生效的暂停**不得只靠 best-effort reconcile**：要么证明**生效后不会误提醒**，要么**首期直接不做未来生效日** | §3 D7/D11、§7、§14 |
| 决策 4 | **系统自动核对**记录 `lastAutoReconciledAtMs`；**用户主动核对**记录 `lastUserCheckedDate`；核**不产生状态事件**、**不得当作服药证据**（非依从输入） | §3 D6/D9、§8 |
| 决策 5 | `tools/` 脚本在**用法与引用确认后**移入 skill 资源目录，**永不删除/覆盖** | §17 |
| 更正 A | `isArchived` 只能是从属的**兼容缓存**；排程/提醒必须键在**生效日期** | §2.A、§1.3 |
| 更正 B | 先用真实代码**证明** `MedicationPlanRevision` 能否承载「倒填暂停 ∩ 后续剂量调整」，**不得**凭空假定插入/裁剪方案 | §2.B |
| 更正 C | 事件日志要有真实的**误记更正**机制（错生效日 / 错 kind / 重复），且**重算历史投影** | §2.C、§9 |
| 更正 D | **取代链判定谓词**必须「整链一次算完」；被取代者**永不生效**（多层 / VOID / 重复 / 冲突 / 环，全部确定性） | §5 R1–R6、§9.1 |
| 更正 E | **系统自动核对**与**用户主动核对**是**两个不同事实**，自动通道**不得**移动用户核对日期 | §8、§9.3 |
| 更正 F | 首期**只接受今天与过去的生效日**；发送前守卫降级为 **Phase 2 计划**；首期仍覆盖**既有停用行为** | §3 D11、§7、§14、§16 |
| 更正 G | 遗留数据：**空事件列表不得静默恢复**遗留归档药（基线保留规则） | §5 R7、§12、§9.4 |
| 更正 H | 历史/依从率**显示暂停区间**且明示**暂停日不计漏服**；真实剂量记录保持可见不动 | §6、§10、§16 |

### 0.1 相对第 2 稿的实质变更（本轮）

1. **§5 重写取代链判定**（更正 D）。旧谓词 `supersedesId == null || !isSuperseded(...)` **有缺陷**：一个**自身没有 `supersedesId`、但已被别人取代**的行会被重新纳入派生，于是**已作废的事件仍可能生效**。改为「**整链一次算完**」的确定性判定，并给出：多层取代、VOID、重复取代、冲突取代、环防止五类规则与测试（见 §5、§9.1）。
2. **拆分两种事实**（更正 E）：`lastAutoReconciledAtMs`（系统写）与 `lastUserCheckedDate`（**仅用户主动核对**写）。自动核对通道**不得**移动用户核对日期（§8、§9.3）。
3. **首期不接受未来生效日**（更正 F）：生效日必须 ≤ 今天（按成员提醒时区的本地日界）；UI 禁止选择未来日期、校验拒绝未来日期。**发送前再校验守卫**整段**降级为 Phase 2 计划**（§7 明确标注「Phase 2 计划」）。首期仍**必须覆盖既有停用行为**（§16.12）。
4. **遗留兼容**（更正 G）：`isArchived=true` 且**零事件**的遗留药**不得**因空事件列表被当作「未暂停」而静默恢复。选定**派生内显式遗留回落**（§5 R7），并比较、否决了「迁移时补种基线事件」等替代（§12）。
5. **历史/依从率显示暂停区间**（更正 H）：暂停窗口在历史/日历与依从率两处均**显式呈现为暂停**，且明示**暂停日不计漏服**（§6、§16.2/§16.4）。

---

## 1. 现状事实（代码核查，file:line）

### 1.1 归档机制端到端

| 事实 | 位置 |
|---|---|
| 状态是**单个 Boolean、无日期**：`Medication.isArchived: Boolean = false`；仅建 `Index("isArchived")` | `core/database/.../model/Medication.kt:79`、`:23` |
| DAO 提供 `archiveMedication` / `unarchiveMedication`（两条 `UPDATE medications SET isArchived=?`），但**归档路径不直接用它** | `core/database/.../local/MedicationDao.kt:96-100` |
| 仓库 `archiveMedication`/`unarchiveMedication` → `setArchived`：**先 `updatePlan(it.copy(isArchived=…), clock.millis())`** | `app/.../data/repository/MedicationRepositoryImpl.kt:96-105`（写入在 `:100-102`） |
| `updatePlan(medication, changedAt)`：把旧计划存为 `MedicationPlanRevision`（`effectiveUntilMs=changedAt`），当前行 `planEffectiveFromMs=changedAt`；`changedAt` 恒为 `clock.millis()`（**无外部注入**） | `core/database/.../local/MedicationDao.kt:74-85` |
| 计划快照**自带** `isArchived` 快照列 | `core/database/.../model/MedicationPlanRevision.kt:37`、`planRevision():42-60` |
| 归档 UI 入口：详情页 ⋮ → `archive` 动作 → `ScreenOverlay.Confirm` → `DetailUiAction.Archive` | `app/.../feature/medications/detail/MedicationDetailScreen.kt:126-160` |
| 详情 VM `archiveMedication()`：已归档则 `unarchiveMedication`，否则 `archiveMedication`，随后 `ReconcileRemindersUseCase.medication(MEDICATION_CHANGED)` | `app/.../feature/medications/detail/MedicationDetailViewModel.kt:136-148` |
| 「已停用」筛选 chip：`state.medications.filter { it.isArchived == archived }` | `app/.../feature/medications/list/MyMedicationsScreen.kt:61` |
| 列表数据源是**含归档**的全量 `getAllMedications()` | `app/.../feature/medications/list/MyMedicationsViewModel.kt:31` |
| `getActiveMedications`（SQL `isArchived=0`）是首页药物相互作用等处的活跃口径 | `core/database/.../local/MedicationDao.kt:12-19` |
| 计划版本链已被测试钉死（改计划 1000 / 停用 2000 / 恢复 3000 → 快照 `isArchived=[false,false,true]`，**严格单调**） | `core/database/src/androidTest/.../MedicationDaoTransactionTest.kt:50-77` |

**结论（现状）**：今天只有「归档/取消归档」一个布尔、**不能带日期、不能带原因、不能表达多次循环**；UI 已把「归档」直接叫**已停用**（`medication_flow_strings.xml:6`），即 UI 层已经**把归档等同于停用**——语义的分裂是**新需求**要解决的。

### 1.2 计划投影如何消费版本链（`FuturePlanCalculator`）

- 逐「计划段」投影：`val plans = history.map { it.applyTo(current) to it.effectiveUntilMs } + (current to Long.MAX_VALUE)`，随后 `if (medication.isArchived && (!includeArchived || history.isNotEmpty())) return@planLoop emptyList()`（`app/.../feature/medications/application/FuturePlanCalculator.kt:48-52`）。段窗口 = `[revision.planEffectiveFromMs, revision.effectiveUntilMs)`；**计算器不检测空洞/重叠**。
- 段顺序来自 `observePlanRevisions`（`ORDER BY effectiveFromMs`，`MedicationDao.kt:60-68`），但**计算器本身只按每段的窗口取值**——段之间是否连续、是否重叠，它既不校验也不修复。
- 依从率 `MedicationAdherenceCalculator.calculate` 用 `includeArchived=true` 且过滤含 `revisions.any { r -> r.medicationId == it.id }`（`MedicationAdherence.kt:70-72`）以**保留历史**；过去未匹配日志的槽位被合成为 `LogStatus.MISSED`（`:100`）并计入分母（`counted`，`:105-112`）。
- **倒填停用在本模型下会产生假义务**：`updatePlan` 的守卫（见 §2.B）使倒填段的边界**不会**落在生效日，旧活跃段仍覆盖 P…R → 计算器把这些天算成 **MISSED**。第 1 稿指望靠「把边界设成生效日」修掉它；§2.B 证明该修法**不成立**，改由**正交状态层**修（§3 D3'）。

### 1.3 `isArchived` 读者全表（更正 A 审计）

**A 类——决定「是否提醒/排程/投影」（必须键在生效日期，不得信缓存）：**

| 读者 | 位置 | 用途 |
|---|---|---|
| `AndroidReminderReconciler.reconcileMedication`：`if (medication != null && !medication.isArchived && !medication.isPRN) schedule(medication)` | `app/.../capability/reminders/AndroidReminderReconciler.kt:40` | **单药排程闸门** |
| `AndroidReminderReconciler.reconcileAll`：`if (!medication.isArchived && !medication.isPRN)` → 加入 `liveMedications` 并排程 | `app/.../capability/reminders/AndroidReminderReconciler.kt:88` | **全量排程 + 孤儿清理存活集** |
| `FuturePlanCalculator`：段 `isArchived=true` → 跳过 | `app/.../feature/medications/application/FuturePlanCalculator.kt:50` | **投影闸门** |

**B 类——SQL 活跃/归档口径（缓存消费者）：** `MedicationDao.kt:15`（`getActiveMedications`）、`:27`（`getArchivedMedications`）、`:36`（`getNormalizedActiveNames`）、`:39`（`deleteActiveMedications`）、`:107`（`getAllMedicationsOnce`）。

**C 类——展示/边界栅栏（缓存消费者）：** `HomeViewModel.kt:571`、`WidgetUtils.kt:29`、`TodayItem.kt:77/106/145`、`MyMedicationsScreen.kt:61`、`PlanExportSchema.kt:94`、`MedicationDetailScreen.kt:128/149`、`MedicationDetailViewModel.kt:140`、`MedicationAdherence.kt:72`。

**写入者：** `MedicationRepositoryImpl.kt:102`（`setArchived` → `updatePlan(copy(isArchived=…))`）、`MedicationDao.kt:96/99`（直接 UPDATE，当前未被归档路径使用）。

**现状缺陷（本稿修订）**：A 类三个读者**当前**直接读 `isArchived` 缓存。改为：**A 类一律改读派生判定 `deriveCurrentStatus(events, now, cache, legacyArchived)`（§5）**；缓存 `isArchived` 仅作为**零事件遗留药的回落**（R7）。因此布尔与事件一旦不一致，提醒与投影以**派生值**为准。（缓存陈旧/错误的最终兜底是 §7 的发送前守卫，那是 **Phase 2**；首期无未来生效日，见 D11，因此同步写缓存 + 派生判定已足够。）

### 1.4 提醒

| 情形 | 现状行为 | 位置 |
|---|---|---|
| 停用（当前已归档） | `cancelAllAlarms` + `cancelAllReminderNotifications`；因 `!medication.isArchived` 为假**不再排期** | `AndroidReminderReconciler.kt:31-44`（守卫在 `:40`） |
| 全量重排 | 仅 `!isArchived && !isPRN` 计入 `liveMedications` 并排期，其余只清理 | `AndroidReminderReconciler.kt:86-92` |
| 恢复 | `!isArchived` 通过 → `schedule(medication)` | `AndroidReminderReconciler.kt:131-143` |
| 闹钟触发后的行为 | `MedLogAlarmReceiver.handleMedication` **只**取 `medicationRepo.getMedicationById(medId)`，**从不检查** `isArchived`/有效状态 | `MedLogAlarmReceiver.kt:91-222`（取药在 `:104/116/131/148/187`） |
| 三态提醒分派 | `onReceive` 的 `when(dispatch.kind)` 把 `MEDICATION_NORMAL/EARLY/FOLLOW_UP` **全部**路由到 `handleMedication` | `MedLogAlarmReceiver.kt:81-87` |
| 事务收口 | 状态写入经 `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` → 立即重排 | `app/.../capability/reminders/application/ReminderReconciliation.kt:14-21` |

**结论**：停用应**立即停闹钟与通知**；恢复应**从恢复日重排**。发送前再校验守卫是 **Phase 2 计划**（§7，配合未来生效日）。**任何状态写入（首期 = 生效日 ≤ 今天）都必须触发一次 reconcile，并同步写缓存。**

### 1.5 剂量日志（要求 5）

- 归档路径**只写** `medications` 与 `medication_plan_revisions`（`MedicationRepositoryImpl.kt:100-105` → `MedicationDao.kt:74-85`），**从不触碰** `medication_logs`。`MedicationLogDao` 的删除接口只被「撤销打卡」类用例调用，**不在**状态路径上。
- **结论**：**剂量日志从不被状态变更触碰**——要求 5 对现状成立；新路径必须**保持**该性质（状态写入 = 只 append 事件 + 只改当前计划边界/派生缓存）。

---

## 2. 三处架构更正（A / B / C）

### 2.A `isArchived` 只允许是「从属兼容缓存」

**规则（可测试）**：`medications.isArchived` 的语义**唯一**定义为
`deriveCurrentStatus(events, now, cache, legacyArchived)`（§5），其中 *events* 是 `medication_status_events` 的全部事件（含被取代者，判定在派生内进行，见 §5 R1–R7）；*now* = `clock.millis()`；*legacyArchived* = 迁移前该行的 `isArchived` 值（R7 只用它兜零事件遗留药）。
**它永远不是提醒决策的真相来源**：排程与投影必须走派生判定（§5）；缓存列只是为了让 §1.3 的 B/C 两类既有消费者**零改动继续工作**（含 SQL `isArchived=0/1` 查询），并作为零事件遗留药的回落。

**边界如何从事件派生**：见 §5。历史任一时刻的状态由此**现算**——**与事件写入顺序无关**。

**缓存陈旧 / 与事件不一致时**：
1. **派生值赢**。任何读者若发现缓存与 `deriveCurrentStatus` 不一致，以派生值为准并由每日核对**重写缓存**（§8 自动通道）。
2. **提醒的最终兜底**是 §7 的**发送前再校验守卫**（**Phase 2 计划**）：闹钟真正弹通知前再算一次派生判定。**首期（无未来生效日）不依赖该守卫**：任何状态写入生效日 ≤ 今天，写事件的同时**同步写缓存**并 reconcile，缓存即刻与派生一致（§3 D11）。
3. 倒填/首期生效不通过 `updatePlan` 改计划链（§2.B），只 append 事件 + 直写缓存列（`MedicationDao.archiveMedication/unarchiveMedication`，`:96-100`）。

**测试性**：
- 不变量测试：每次状态写入后、以及每次自动核对后，断言 `medications.isArchived == deriveCurrentStatus(events, now, cache, legacyArchived)`（property/事务测试）。
- 对抗测试：**人为把缓存住成 `isArchived=false`，而事件表给出「生效日≤now 的 PAUSE」** → 断言 (a) A 类读者（用派生判定）**不排期**该药；(b) 每日核对把缓存纠正回 true。此测试即「缓存不得成为提醒真相来源」的可执行证明。
- **遗留对抗测试**：缓存 `isArchived=true` 且**事件表为空** → 断言派生值仍为 true（R7），**不因空列表翻活跃**（§9.4）。

### 2.B 计划版本模型能否承载「倒填暂停 ∩ 后续剂量调整」？——**裁决：不能；改用正交状态层**

**代码证据（为什么插入/裁剪方案不成立）**：

1. `updatePlan` 的落段守卫是**单向**的：
   `if (changedAt > previous.planEffectiveFromMs) insertPlanRevision(previousPlan)`（`MedicationDao.kt:80`），随后**无条件** `updateMedication(medication.copy(planEffectiveFromMs = changedAt))`（`:81`）。
   → 当 `changedAt <= previous.planEffectiveFromMs`（**倒填早于上一次计划变更**），**旧段不落**，但当前行仍被回退到 `planEffectiveFromMs=changedAt`。
2. 仓库把 `changedAt` **硬编码为 `clock.millis()`**（`MedicationRepositoryImpl.kt:81` 与 `:102`）——**当前根本不存在「按生效日写计划段」的入口**。
3. `MedicationPlanRevision` 是**一段有头有尾的连续窗口**（`effectiveFromMs`/`effectiveUntilMs`，`MedicationPlanRevision.kt:24-25`），**没有段序、没有段身份、没有「被插入」概念**；消费端 `FuturePlanCalculator.kt:48-52` **不检测空洞/重叠**。
4. **唯一**的版本链测试是**严格单调**的（改计划 1000 / 停用 2000 / 恢复 3000，`MedicationDaoTransactionTest.kt:50-77`）——**没有任何倒填用例**。第 1 稿 E8 所依赖的「有序插入/裁剪」在本仓**零测试、零先例**。

**反例推演（为什么倒填会静默损坏）**：设 `A@T0 → 剂量调整B@T5 → 剂量调整C@T10`，则链 = `[A:T0–T5, B:T5–T10]` + 当前行 `C@T10`。现在补记一条**生效于 T3 的 PAUSE**：
- `changedAt=T3 <= previous.planEffectiveFromMs=T10` → 守卫**不落旧段**；
- 当前行被改写为「**已暂停的 C**」，`planEffectiveFromMs=T3`；
- 结果：链仍是 `[A:T0–T5, B:T5–T10]`，**T3–T5 的剂量照旧被 A 投影**（暂停无效，仍算 MISSED），且 **C 自己的档期从历史里消失**（当前行已被暂停版 C 顶替，且声明从 T3 起）。
- 后续 RESUME 再插入只会叠加更多重叠窗口。**结论：`MedicationPlanRevision` 不能承载「倒填暂停 ∩ 后续剂量调整」**；「插入/裁剪链」既无入口（2）、无段身份（3）、无测试（4），又会污染全 App 共用的投影读数（`FuturePlanCalculator` 被首页/历史/小组件共用）。

**已比较的最小可靠替代**：

| 方案 | 说明 | 裁决 |
|---|---|---|
| (i) 有序插入/裁剪计划链 | 需给段加序、改相邻段 `effectiveUntil`、拆/并段、加重叠检测 | **否决**：无入口、无测试、改最热共享读数，回归面大 |
| (ii) **重算投影：由事件日志派生「状态层」，与计划链正交** | 计划链**照旧**只描述「计划在各时刻投影了哪些槽位」；`pausedAt(t)` 由事件现算；**应服槽位 = 计划投影 ∧ 该时刻未暂停** | **采纳**（见下） |

**采纳 (ii) 的理由**：状态层是**纯函数、与写入顺序无关**——倒填/中段恢复天然正确，**既不改计划链、也不改 `updatePlan` 签名**，回归面最小；且它同时满足决策 2（重算历史应服与依从率）与更正 C（取代事件只改状态层）。实现上：`FuturePlanCalculator` 增加 `statusEvents` 入参，对每个 occurrence 追加 `statusActiveAt(events, occurrence.scheduledAt)` 过滤；对于**无事件**的历史药，回落到既有「段 `isArchived` 跳过」（`FuturePlanCalculator.kt:50`）以零回填兼容。`medication_plan_revisions` 的既有 `isArchived` 快照列**保持不变但降级为遗留回落**。

**后果（写入路径变更，需写测试钉死）**：pause/resume **不再**经 `updatePlan(copy(isArchived=…))` 新建计划段（`:102`）；改为 **append 事件 + 直写缓存列**（`archiveMedication`/`unarchiveMedication`，`MedicationDao.kt:96-100`）+ reconcile。既有断言「状态改版会新建 revision」的测试用例（`MedicationDaoTransactionTest.kt:67-72`）**随之更新**为「状态改版只 append 事件、计划链不变」。

### 2.C 误记更正机制——**追加「取代/更正事件」+ 重算**（不就地编辑）

**可选方案比较**：

| 方案 | 机制 | 审计 | 与决策 1/更正 A 一致性 | 可解释性 | 裁决 |
|---|---|---|---|---|---|
| (i) **就地编辑**事件（照 `care_event_logs` 的 `updatedAtMs` 做法，`CareEventLog.kt:59`） | 直接覆盖 `effectiveAtMs`/`kind` | **无**：改前值不可恢复 | **冲突**：决策 1/更正 A 明确事件是 append-only 审计链 | **差**：无法解释「从什么改到什么」 | **否决** |
| (ii) **追加取代事件**（supersede） | 新增带 `supersedesId` 的事件指向被更正者；派生时**跳过被取代者** | **完整**：原事件留存，改动留痕 | **一致**：不 mutate、不 delete 任何行 | **强**：链上可读出「谁替了谁、何时记、为何」 | **采纳** |

**采纳 (ii)。** 三种误记的落法：
- **错生效日** → append 一条 `effectiveAtMs` 正确、`supersedesId=错事件` 的同 kind 事件。
- **错 kind**（PAUSE 误成 DISCONTINUE 或反之）→ append 正确 kind、`supersedesId=错事件` 的事件。
- **重复条目** → append 一条 `VOID` 事件 `supersedesId=重复者`，使其从派生中退出（VOID 的语义见 §5 R3）。

**重算**：任何事件 append（含取代）后，**重跑状态层**（§5）→ **重算历史应服剂量与依从率**（§6）→ 刷新缓存 + reconcile。**剂量日志行数/内容不变**。

**与决策 2 可解释性的衔接**：取代链本身就是**变更日志**（`recordedAtMs` = 何时改、`effectiveAtMs` = 改到哪个生效日、`reason`、`supersedesId` = 改了哪条），直接喂给 §6 的「历史已调整」UI。

**证明测试**：见 §9.2。

---

## 3. 关键设计决策

**D1 — 采用「状态事件表」（append-only、可取代），不用 `isArchived` 承载历史。**
新增 `medication_status_events`（`id, medicationId FK CASCADE, kind, effectiveAtMs, recordedAtMs, reason?, supersedesId?`）。**理由**：要求 1/2 + 「继续吃/停/重吃」的**多次循环**本质是**事件流**；布尔与单对列都装不下。

**D2 — `medications.isArchived` 保留，但降级为「派生的当前状态缓存 / 零事件遗留回落」，不移除。**（更正 A → §2.A）
读者太多（§1.3 A/B/C 三类），移除会造成大面积回归面。保留一个**由事件派生的**列，语义 = 「**按 §5 派生**的当前是否停用」。派生函数见 §5；**无事件**的遗留行回落到原 `isArchived`（迁移零回填，R7）。

**D3（替换第 1 稿 D3）— 计划链不变；倒填暂停由「正交状态层」承载，不写版本链。**（更正 B → §2.B）
pause/resume **不**改 `medication_plan_revisions`、**不**改 `updatePlan` 签名；只 append 事件 + 直写派生缓存列。投影 = 计划段投影 ∧ 该时刻未暂停。现有计划链只继续承担**剂量/时刻调整**（边界=记录时刻，维持现状）。

**D4 — 计划段快照不新增「暂停窗口」字段。** 快照 `isArchived` 列保留为**遗留回落**；新增能力全部走事件层。

**D5 — 「归档」与「临时停用」= 同一事件引擎的两个 kind（`DISCONTINUE` / `PAUSE`），不建第二套关闭语义。**（决策 1 → §10）

**D6 — 三条写入路径严格区分：CHANGE（用户变更）、CHECK（用户核对）、RECONCILE（系统自动）。**（更正 E → §8）
- **CHANGE**（用户改状态）：`desired != deriveCurrentStatus(events, now, …)` → **append 事件** + 直写缓存 + reconcile。
- **CHECK**（用户主动核对医嘱，`继续服用/继续停用` 的确认）：**不写事件**；**只**写**用户事实** `lastUserCheckedDate`（§8）。**不写缓存列**、不 reconcile（状态未变）。
- **RECONCILE**（系统自动，每日/启动/小组件 tick）：**不写事件**；只写缓存列 + **系统事实** `lastAutoReconciledAtMs`（§8）。
- **三者都绝不写 `medication_logs`**；CHECK/RECONCILE **都不得**被当作服药证据。

**D7 — 未来生效的暂停：移出首期，作为 Phase 2 计划。**（决策 3、更正 F → §7、D11）
首期**不接受**未来生效日（D11）；「发送前再校验守卫」整段作为 **Phase 2 计划**保留（含可证方案与测试），作为未来生效日放行的**发布闸门**。

**D8 — 迁移 v25 → v26 纯新增 + 备份/恢复上界随 `VERSION` 自动放行。** 见 §12。

**D9 — 两种事实分离：`lastAutoReconciledAtMs`（系统）/ `lastUserCheckedDate`（用户）。**（决策 4、更正 E → §8）核对不产生状态事件、不作为服药证据。

**D10 — 误记更正 = 追加取代事件 + 重算。**（更正 C → §2.C、§9.2）

**D11 — 首期生效日约束：只接受今天与过去（更正 F）。** 校验与 UI 双重限制：
- **校验**：`localDay(effectiveAtMs, 成员提醒时区) <= localDay(now, 成员提醒时区)`，否则拒绝（错误码 `FutureEffectiveDateNotAllowed`）。
- **UI**：批量/详情日期选择器 `maximumDate = 今天`，未来日期不可选。
- 理由：首期无发送前守卫；若允许未来生效日，生效时刻前若 reconcile 未跑到，可能误提醒（决策 3 明令「要么证明不误提醒，要么首期不做」，本稿取后者）。

**D12 — 遗留基线保留（更正 G → §5 R7、§12）。** 零事件的遗留药以 `isArchived` 为不可变基线兜底；首个新事件出现后由事件决定状态。自动核对**对零事件药跳过写缓存**，避免动到基线。

**D13 — 历史/依从率显式呈现暂停区间，暂停日不计漏服（更正 H → §6、§10）。**

---

## 4. 数据模型（D1/D10 落地）

```kotlin
// 状态种类是代码常量（对齐 care_event_logs 的 kind 存法；不用 enum Converter）
object MedicationStatusKind {
    const val PAUSE = "PAUSE"              // 临时停用（可能恢复）
    const val RESUME = "RESUME"            // 恢复
    const val DISCONTINUE = "DISCONTINUE"  // 归档 / 永久终止（将来仍可 RESUME，语义由 UI 区分）
    const val VOID = "VOID"                // 作废（配合 supersedesId 使用；本身不承载状态，见 §5 R3）
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
    val effectiveAtMs: Long,          // 状态「实际生效」时刻（可倒填；首期不允许未来，见 D11）
    val recordedAtMs: Long,           // 录入时刻（与生效独立）
    val reason: String? = null,       // 可选原因/备注
    val supersedesId: Long? = null,   // 更正：指向被本事件取代的旧事件（同药、且 id 更小）；不建自引用 FK
)
```
- 复合索引 `(medicationId, effectiveAtMs)` 覆盖「取某药最新生效事件」。
- **append-only**：**不就地编辑、不物理删除**（与 `care_event_logs` 的「就地编辑+物理删除」**有意不同**，`CareEventLog.kt:28/59`）；改错 = **追加取代事件**（`supersedesId`）。
- `supersedesId` 用**普通可空列 + 索引**（不建自引用 FK，避免自环级联复杂度）；不变量：
  - **同药**：指向同一 `medicationId` 的既有事件；
  - **严格更旧（环防止，§5 R6）**：`supersedesId` 必须 `< 本行 id`，即被引用者是在本行之前 append 的旧行；
  - **悬挂/跨药** 的 `supersedesId` 被派生**忽略**（不抑制任何东西，安全退化）。
- 删除药物 → FK CASCADE 连带状态事件（§11 E9）。
- **DAO**：`getEventsForMedication(id): Flow<List<…>>`、`getLatestEffectiveOnce(id, nowMs)`、`insertEvent`；仓储按 `ActiveRecipientStore` 作用域（`scoped{}`/`currentOrNull()`/`requireRecipientId()+stamped()`，见 skill）。
- **`insertEvent` 前置校验**：`supersedesId == null || (supersedesId < newId && 同药存在)`；违反 → 抛 `InvalidSupersedeReference`（结构上杜绝环，§5 R6）。

---

## 5. 派生：状态层与投影（正交，更正 B 核心；谓词重写，更正 D）

### 5.1 取代链判定（**整链一次算完**）

> **核心更正 D**：旧谓词 `events.filter { it.supersedesId == null || !isSuperseded(it, events) }` **错误**。它把「自身 `supersedesId == null`」当作「仍然有效」，于是**一个自身不带 `supersedesId`、但已被别人取代的旧行，会被重新纳入派生并可能生效**。正确判定必须问「**有没有行指向我**」，而不是「我自己有没有 `supersedesId`」。

**规则（deterministic；实现用此等价式，避免递归歧义）：**

```
// R1 死/活（指针规则）：一道行是死的 ⇔ 事件集里有任何「活行」指向它。
//    「活行」= 确实存在于该药事件集的行（悬挂/跨药引用不算）；整链一次算完。
//    等价、无递归歧义的实现式：
//        dead = { s.supersedesId | s ∈ E, s.supersedesId != null }
//        live = E \ dead
//    注意：**不要**写成递归的「活行 ⇔ 没有活行取代我」——那样会复活多层链的祖先。
//    也不要读该行自己的 supersedesId 列来判定它是否仍然有效。
fun liveEvents(E): List<Event> {
    val targets = E.mapNotNull { it.supersedesId }.toSet()      // 被指向过的 id
    val afterR1 = E.filter { it.id !in targets }                 // R1：被指向者一律死
    // R4 重复取代：同一目标被 ≥2 条活行取代 → 只留 (recordedAtMs, id) 最大者，其余「遮蔽」
    val shadowed = mutableSetOf<Long>()
    E.filter { it.supersedesId != null }.groupBy { it.supersedesId!! }.forEach { (_, g) ->
        val competitors = g.filter { it.id !in targets }         // 已被指向的竞争者本就已死
        if (competitors.size > 1) {
            val winner = competitors.maxWith(compareBy({ it.recordedAtMs }, { it.id }))
            competitors.filter { it.id != winner.id }.forEach { shadowed.add(it.id) }
        }
    }
    return afterR1.filter { it.id !in shadowed }
}

// R2/R3/R5：状态判定只看「活行」里的「状态承载 kind」——VOID 不承载状态
fun pausedAt(E, t): Boolean {
    val latest = liveEvents(E)
        .filter { it.kind == PAUSE || it.kind == RESUME || it.kind == DISCONTINUE }  // 排除 VOID
        .filter { it.effectiveAtMs <= t }
        .maxWithOrNull(compareBy({ it.effectiveAtMs }, { it.recordedAtMs }, { it.id }))
    return latest?.kind == PAUSE || latest?.kind == DISCONTINUE
}
```

**五类规则（逐条确定 + 测试）：**

- **R1 多层取代**：`A ← B ← C`（`B.supersedesId=A`，`C.supersedesId=B`）⇒ `dead={A,B}`，`live={C}`，**只有 C 生效**。祖先 A、B 均**永不生效**。（旧谓词会保留 A，见 §5.2 反例。）
- **R2 反例锚点**：任何行一旦被指向即死，**与它自己是否带 `supersedesId` 无关**。
- **R3 VOID 语义（确定）**：`VOID` 行 `v` 且 `v.supersedesId=X` ⇒ **X 死**；`v` 自身是活行但**不承载状态**（kind ∉ {PAUSE,RESUME,DISCONTINUE}，故在状态判定里被跳过）。因此状态**回退到「被作废行之前的那条最新活状态行」**；若不存在更早的活状态行 → **视为「无状态事件」**（零事件 ⇒ 走 R7 回落，非遗留药即「未暂停」）。**明确：不是回到被作废行本身，也不是无条件回到「活跃」。**
- **R4 重复取代（平局打破）**：`≥2` 条活行指向**同一目标** ⇒ 目标死；竞争者按 `(recordedAtMs, id)` 取最大者为胜，**败者「遮蔽」（dead，派生忽略）但行保留**（append-only，可审计；不物理删除）。测试：`derive` 结果与「只存在胜者一条」时**逐字节一致**。
- **R5 冲突取代（确定性 or 结构禁止）——两者都做**：
  - **冲突形态 (a) 菱形/重复**（同目标多个取代者）→ 由 R4 的**全序**确定性解决。
  - **冲突形态 (b) 互指/成环**（`Y.supersedesId=X` 且 `X.supersedesId=Y`，或更长环）→ **结构上禁止**（R6）。
  - 任何「同一时刻按不同顺序应用取代会得出不同结果」的疑虑，由**单一 `max`（全序 `(effectiveAtMs, recordedAtMs, id)`）** 化解：派生是**顺序无关（confluent）**的，结果唯一。测试：把取代按**两种顺序**喂入，断言结果相同。
- **R6 环防止（结构强制）**：append-only + 单调 `autoGenerate` id 下，新行 id 恒为当前最大，故**只能引用更旧的 id**。`insertEvent` 强制 `supersedesId < newId`（§4），违反即拒绝。**取代图恒为 DAG**，环不可构造。测试：① 尝试 `supersedesId >= 自身 id` 或自指 → 被拒绝；② property 测试：取代图可拓扑排序（无环）。
- **R7 遗留回落（更正 G）**：`deriveCurrentStatus(events, now, cache, legacyArchived)`:

```
fun deriveCurrentStatus(events, now, cache, legacyArchived): Boolean =
    if (events.isEmpty()) legacyArchived        // 零事件遗留药：冻结在迁移前布尔，绝不被空列表翻活跃
    else pausedAt(events, now)                  // 有事件：事件权威
```

  - `legacyArchived` = 迁移前该行的 `isArchived` 值（对**迁移后新建**的药恒为 `false`）。
  - **首个新事件一旦出现**，`events` 非空 ⇒ 回落关闭，状态 = `pausedAt(live)`：
    - 首个事件是 `PAUSE`/`DISCONTINUE` ⇒ **仍暂停**（不翻活跃）；首个事件是 `RESUME` ⇒ 活跃（**用户显式恢复**，符合预期）。
  - 自动核对**对零事件药 `continue`（跳过写缓存）**，保证基线不被覆写（D12）。
  - **与「事件权威」不矛盾**：事件**一旦存在**即完全决定状态；`legacyArchived` 只是「无事件」这一**唯一**情形下对不可考历史的**有界兜底**，且**任何活状态事件都压过它**（见 §18 表行 12）。

### 5.2 具体反例（**为什么旧谓词错**）

事件（同药）：
- `eA`：`PAUSE`，`effectiveAtMs=300`，`recordedAtMs=1`，`supersedesId=null`
- `eB`：`RESUME`，`effectiveAtMs=200`，`recordedAtMs=2`，`supersedesId=2003(=eA.id)`（更正 eA：撤销那次暂停）

**意图**：`eA` 被 `eB` 取代 → `live={eB}`；`now=300` 时 `eB` 生效（200≤300）且 kind=`RESUME` ⇒ **未暂停**。

**旧谓词实际结果**：`eA.supersedesId==null` ⇒ 保留；`eB.supersedesId!=null` 但无人指向 `eB` ⇒ 保留。`live={eA,eB}`。取 `(effectiveAtMs,…)` 最大者 ⇒ `eA`（300 > 200）胜 ⇒ kind=`PAUSE` ⇒ **误判为「已暂停」**。**一个已被取代的旧行仍生效**——正是更正 D 要根除的缺陷。

**新规则结果**：`dead={eA.id}`，`live={eB}`，`pausedAt(300)=false` ⇒ **正确**。（多层版本见 R1；`live` 只含链末 C。）

### 5.3 写回时机与投影

- **写回时机**：① 每次事件写入后（同步）；② 每次自动核对 / 应用启动 / 小组件 tick（§8）；③ 每次 reconcile。
- **顺序无关**：以 `(effectiveAtMs, recordedAtMs, id)` 取最新，故倒填、中段恢复、重复/冲突取代都天然确定。
- **投影（`FuturePlanCalculator`）**：对每个 occurrence 追加 `statusActiveAt(events, occurrence.scheduledAt)` 过滤；`statusActiveAt = !pausedAt(events, t)`。**无事件**的药回落到既有「段 `isArchived` 跳过」（`FuturePlanCalculator.kt:50`）——与 R7 同口径（零事件 ⇒ 看遗留布尔）。
- **倒填停用**：P…R 时段凡 `pausedAt=true` → 无应服槽位 → **不再算 MISSED**（决策 2、§6）。
- **中段恢复**：恢复生效后 `pausedAt=false` → 从恢复日恢复投影（用覆盖该时刻的计划段）。
- **多次循环**：状态层按事件自动分段，计划链不动。

---

## 6. 倒填的可解释性 + 暂停区间的呈现（决策 2、更正 H）

**什么会动（派生、可重算）**：
- **历史应服剂量计划**：受影响窗口内 `pausedAt` 翻转 → 该窗口的应服槽位增减。
- **依从率分母**：受影响的 `AdherenceDay.resolved/total`（`MedicationAdherence.kt:105-112`）现算变化。
- 派生缓存 `isArchived`；提醒排期。

**永远不动（硬不变量）**：
- **`medication_logs` 行数与内容**——倒填/核对/更正**一律不触碰**（§1.5、§16.5）。
- 真实 `TAKEN`/`PARTIAL` 记录照旧保留；暂停前/暂停当天的打卡**仍在**（显示为「暂停前记录 / 暂停期间记录」，但**不被删除**）。
- 事件链本身（append-only；更正 = 追加，不改旧行）。

**UI 如何让照护者看懂（绝不静默改写过去）**：
1. **提交前**：倒填变更弹确认摘要——「将重算 9/28–10/01：由 **2 次应服 / 1 次漏服** → **暂停 0 应服**；**0 条服药记录受影响**」。
2. **提交后**：一条**可关闭的变更提示** + **变更记录**（由取代链现算：`recordedAtMs` 何时改、`effectiveAtMs` 改到哪天、`reason`、被取代者）。
3. **历史/日历屏**：受影响日打上 **「已调整」** 标记并可点开解释「因停药生效日更正，本日不再计入应服」。
4. 重算是**确定性纯函数**：同一条链 → 同一结果，可复现、可审计。

### 6.1 暂停区间在 **历史 / 日历** 的呈现（更正 H，UI 规格）

- **暂停窗口**渲染为一段**显式的「暂停」条带**（暂停图标 + 文案 `暂停中·自 MM/DD` 至 `MM/DD`；进行中则 `暂停中·自 MM/DD`，无结束日）。**不是空白、不是漏服红点。**
- 窗口内每一天标注 **「暂停（不计漏服）」**（区别于 MISSED）。
- **窗口内的真实打卡记录照旧显示、照旧计入 `TAKEN`**（只是不再作为「应服」的要求）；文案区分「暂停前记录」/「暂停期间记录」，**不删除、不隐藏**。
- 窗口受倒填更正影响时，按 §6.1 第 3 条打 **「已调整」** 标记。
- 这些状态**与 `isArchived` 缓存/派生 `pausedAt` 同源**（§5），保证历史视图与提醒/依从率口径一致。

### 6.2 暂停区间在 **依从率** 的呈现（更正 H）

- **暂停日不计入分母**：`MedicationAdherenceCalculator` 在生成 `AdherenceDay` 时，对 `pausedAt(events, dayStart)=true` 的日子 **`counted=false`**（不进分母），也**绝不合成 `MISSED`**（覆写 §1.2 中「过去未匹配槽位一律 MISSED」的行为：仅对**未暂停**的日子生效）。
- **依从率显示**：分母旁注明 `（已扣除暂停 N 天）`；暂停窗口在依从率视图上以**独立色带 + 「暂停期间不计漏服」**说明呈现。
- **可解释性**：倒填导致依从率变化时，§6 提交流程给出**前/后数值差**（「分母 30→27，已扣除暂停 3 天，漏服 2→2 不变」），并生成「已调整」记录——满足「历史统计变化必须可解释，而非静默」。

---

## 7. 【Phase 2 计划】提醒：发送前再校验守卫（决策 3 的推迟项，**首期不交付**）

> **里程碑定位（更正 F）**：**本节全部内容属 Phase 2**，是未来生效日放行的**发布闸门**。**首期不接受未来生效日（D11）**，因此首期**不需要**本守卫；本节保留为**已分析、可证**的 Phase 2 方案与测试清单。

**现状（缺口）**：`MedLogAlarmReceiver.handleMedication`（`MedLogAlarmReceiver.kt:91-222`）在**每个分支都只 `getMedicationById`**（`:104/116/131/148/187`），**从不检查**有效状态——**今天不存在发送前再校验**。

**可用性（为什么合入后够用且便宜）**：
- **单点收口**：`onReceive` 的 `when(dispatch.kind)`（`:81-87`）把 `MEDICATION_NORMAL/EARLY/FOLLOW_UP` **全部**路由进 `handleMedication`。在 `handleMedication` 顶部加一行
  `if (medicationStatus.derivePaused(medId, clock.millis())) return`
  即覆盖**全部三类用药提醒通知**。
- **无级联**：`scheduleNextReminderAfterDose` / `scheduleFollowUpAlarm`（`:198-218`）都在守卫之后，被抑制时**不**再排下一次。
- **读真相而非缓存**：守卫调 §5 的 `pausedAt`（缓存仅作无事件回落），故**缓存陈旧也无法击穿守卫**（这正是更正 A 与决策 3 的交点）。
- **对照先例**：care-event 的发送前四要素再校验（`CareEventReminderEngine.kt:66-78`）已证明该模式在本仓可落地、可穷举测试。
- **不误伤**：`ACTION_TAKEN`/`ACTION_SKIP`（用户点通知按钮的显式动作，`:102-124`）**不**置于守卫之后——**真实服药记录不得被拦**（决策 2 / 要求 5）。
- **成本**：一个注入依赖 + 顶部一个早退 + 测试；每次闹钟触发多一次事件读，可忽略。

**裁决（明文）**：**守卫对「闹钟/通知」面充分且廉价 → Phase 2 允许未来生效日时，条件是守卫与其测试作为该阶段的发布闸门。** 首期（无未来生效日）**不设闸门**。
**残余（Phase 2 须写清）**：守卫只覆盖**闹钟/通知**；**小组件 / 今日计划**读的是缓存（`WidgetUtils.kt:29`、`HomeViewModel.kt:571`、`TodayItem.kt:77/106/145`），可能在生效时刻后有**一次刷新窗口**的陈旧显示——由 §8 自动核对 + 既有 15 分钟 `WidgetRefreshWorker` + `widgetRefresher.refreshAll()`（`AndroidReminderReconciler.kt:43`）收敛；另**已在上屏的旧通知**不会瞬时消失，由下一次 reconcile/prune 收掉。

**证明测试（Phase 2 交付物，`MedLogAlarmReceiverTest`，注入可拨时钟）**：
- `t < F` 触发 EARLY/NORMAL/FOLLOW_UP → **照常**弹通知/排下一次；
- `t >= F`（F = 未来暂停的生效时刻）触发三类 → **全部抑制**，且不排下一次；
- 过期缓存（`isArchived=false`）但事件说已暂停 → 仍**抑制**（守卫读派生）；
- `ACTION_TAKEN/SKIP` 在暂停后仍**照常写日志**。

---

## 8. 两种事实分离：系统自动核对 vs 用户主动核对（决策 4、更正 E）

**要点（更正 E）**：**系统自动核对**与**用户主动核对医嘱**是**两个不同事实**，存两处、由两个不同写者写。**系统自动通道绝不得移动用户核对日期。**

### 8.1 系统自动核对（AUTO RECONCILE）—— 存 `lastAutoReconciledAtMs`

- **触发**（骑既有基础设施，不新增闹钟）：应用启动 + 既有 15 分钟 `WidgetRefreshWorker` + 既有 `MedLogBootReceiver`（`MedLogBootReceiver.kt:22-37`）——与 care-event R9/R13 同口径。
- **动作**：对当前成员逐药：`derived = deriveCurrentStatus(events, now, cache, legacyArchived)`；
  - **零事件**的药：`continue`（**跳过**，不动基线缓存，D12/R7）；
  - 若 `derived != cache` → 写缓存列（`MedicationDao.archiveMedication/unarchiveMedication`）；
  - 若 `derived` 由活跃翻为暂停 → `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` 撤闹钟/通知 + 小组件刷新；
  - 收尾写 **`lastAutoReconciledAtMs`**。
- **存储**：成员级偏好层 DataStore 键 `medication_status_auto_reconciled_at#<recipientId>`（对齐 `care_event_nudged_day#…` 的做法，`tracked-events-spec.md §4`）。
- **写者**：**仅** `ReconcileMedicationStatusUseCase`（自动通道）。
- **硬约束**：**不产生状态事件**；**不写 `medication_logs`**；**不写 `lastUserCheckedDate`**（**这是关键——自动通道不得触碰用户事实**）。

### 8.2 用户主动核对（USER CHECK）—— 存 `lastUserCheckedDate`

- **语义**：用户**主动**拿当前药单**核对医生医嘱**，确认「继续服用 / 继续停用」并**显式完成一次核对动作**（一个明确的「我已核对医嘱」完成按钮/手势，而非被动浏览）。
- **只有一个写者**：`SetMedicationStatusUseCase` 的 **CHECK 完成分支**。**系统任何自动路径都不得写它**（架构/单写者测试，§9.3）。
- **动作**：**不写事件**、**不写缓存列**、**不 reconcile**；**只**更新 `lastUserCheckedDate = localDay(now, 成员提醒时区)`。
- **存储**：成员级 DataStore 键 `medication_status_user_checked_date#<recipientId>`（本地 `epochDay`）。
- **硬约束**：**不是服药证据**——**不是** `medication_logs` 行、**不被依从率读取**、**不被 `MedicationAdherenceCalculator` 读取**。文案层**禁止**把「今天已核对医嘱」表述为「今天已服药」。

### 8.3 为什么必须分开（反例）

若沿用单一 `lastReconciledAtMs` 表示「今天已核对」，则**系统每天自动跑一次**就会把它刷新成「今天已核对」，**用户核对日期被系统覆盖**，用户再也无法知道「我上次**亲自**核对医嘱是哪天」。分开后：自动通道每天刷新 `lastAutoReconciledAtMs`（无害，仅表示系统何时自检），用户事实 `lastUserCheckedDate` **只在用户主动完成时前进**。

### 8.4 测试（见 §9.3）

---

## 9. 测试清单（本稿新增/更新）

### 9.1 取代链派生（更正 D —— 纯函数单测 `MedicationStatusDerivationTest`）

1. **多层取代**：`A←B←C` ⇒ `live={C}`；`pausedAt` 等于「仅 C」时的结果；**A、B 永不生效**。
2. **反例回归**：§5.2 的 `eA/eB` ⇒ 断言 `pausedAt(300)=false`（旧谓词为 true）。
3. **VOID**：`A(PAUSE@t1)` + `B(VOID, sup=A)` ⇒ `pausedAt(t≥t1)=false`（回退到无更早活状态行）；再前置一条 `Z(RESUME@t0<t1)` ⇒ `pausedAt(t≥t1)=false` 且由 Z 决定（**回退到被作废行之前的那条活状态行**，而非作废行本身）。
4. **重复取代**：两条活 `RESUME` 同时 `sup=A`（`recordedAtMs` 不同）⇒ 派生结果与「只有 `recordedAtMs` 较大者一条」**逐字节一致**；败者行仍在表内（append-only）。
5. **冲突取代顺序无关**：同一组事件按**两种喂入顺序**派生 ⇒ 结果相同（confluent）。
6. **环防止**：`insertEvent` 传 `supersedesId >= 自身 id`/自指 → 抛 `InvalidSupersedeReference`；取代图可拓扑排序（property）。
7. **悬挂/跨药** `supersedesId` ⇒ 安全退化（不抑制任何事件）。

### 9.2 误记更正（更正 C —— `MedicationStatusCorrectionTest`）

- 错生效日更正 → 受影响窗口应服槽位改变、`medication_logs` 行数不变、`pausedAt` 反映更正值；
- 错 kind 更正（PAUSE↔DISCONTINUE）→ 派生 kind 改变、UI 标签与提醒行为随之改变；
- 重复条目作废（VOID）→ 派生结果与「只有一条」时**逐字节一致**，恰好一条有效事件被采纳；
- **被取代事件永不被读取**：`pausedAt` 与提醒/依从路径都忽略被指向者（R1）；
- 删除药物 → CASCADE 连带全部事件（含更正链）。

### 9.3 两种事实分离（更正 E —— `MedicationStatusReconciliationTest`）

- **自动通道不得移动用户日期**：可拨时钟推进 N 天、连续跑自动核对 ⇒ `lastUserCheckedDate` **不变**，`lastAutoReconciledAtMs` 前进；缓存被纠正但用户事实冻结。
- **用户核对**：显式完成核对 ⇒ `lastUserCheckedDate=今天`；`medication_status_events` 行数**不变**；`medication_logs` 行数**不变**；`lastAutoReconciledAtMs` **不变**。
- **单写者**：`lastUserCheckedDate` 仅由 CHECK 完成分支写入（架构测试/单一调用点断言）。
- **非服药证据**：`MedicationAdherenceCalculator` 不读 `lastUserCheckedDate`；文案「已核对医嘱」≠「已服药」（字符串资源断言）。

### 9.4 遗留兼容（更正 G —— `MedicationStatusLegacyTest`）

- **迁移保持暂停**：种 v25 库含 `isArchived=true` 且零事件的药 → 迁移 → 断言 `isArchived` 不变、`deriveCurrentStatus(空, now, cache, true)=true`、自动核对后仍 true。
- **空列表不静默恢复**：对上述药直接读 `pausedAt`/`deriveCurrentStatus`（含提醒路径与投影路径）⇒ 不翻活跃。
- **首个新事件不翻活跃**：给该药记录首个 `PAUSE`（及单独用例：首个 `DISCONTINUE`）⇒ 仍暂停；**首个 `RESUME` ⇒ 活跃（显式恢复，符合预期）**。
- **零事件不写缓存**：自动核对对该药**不写** `isArchived`（基线冻结）。

### 9.5 首期日期约束（更正 F）

- 校验：`effectiveAtMs` 落在未来本地日 → 拒绝（`FutureEffectiveDateNotAllowed`）；今天/过去 → 接受。
- UI：日期选择器 `maximumDate = 今天`（契约测试：未来日不可选）。
- **既有停用行为**：见 §16.12 的验收步骤（停今天 ⇒ 立即停提醒 + 该药统计行为启动）。

---

## 10. 归档 vs 临时停用（决策 1）

**不拆两套机制**：在同一张事件表上用两个 kind（`PAUSE`=临时停用、`DISCONTINUE`=归档/永久终止），**行为同构**（都停前向投影与提醒），**语义分开**（UI 两档）。本仓明确拒绝双关闭语义（`docs/care-notes.md:66`、`docs/todos.md:48`）。
**UI**：详情菜单两入口——`临时停用`（带生效日+原因，`PAUSE`）与`归档`（永久，`DISCONTINUE`）；列表 `已停用` chip 收纳**两者**，行内角标「暂停中·自 MM/DD」/「已归档」。
**结果**：要求「既分语义、又不分机制」——语义分（kind+文案），机制不分（一张表 + 一个派生列）。暂停区间在历史/依从率的呈现见 §6.1/§6.2。

---

## 11. 边界情形（逐条规格）

| # | 情形 | 规格 |
|---|---|---|
| E1 | 停用一个**没有未来剂量**的药（`endDate` 已过 / PRN / 已暂停） | 仍写事件（历史留痕）；排期部分 no-op。不得因「无未来剂量」拒绝记录 |
| E2 | **长时间暂停后恢复** | 恢复事件 + 状态层在恢复日翻开；暂停窗口保持被跳过 |
| E3 | **连续两次停用**（幂等） | 第二次 desired==当前 → 判为 CHECK，**不产生重复事件**（§3 D6）；若走用户核对路径则只更新 `lastUserCheckedDate` |
| E4 | **同一天先停后恢复** | 两事件都记录；派生以 `(effectiveAtMs, recordedAtMs, id)` 最新为准 |
| E5 | **生效日在未来** | **首期不接受**（D11）：UI 禁选、校验拒绝（`FutureEffectiveDateNotAllowed`）。未来生效日与 §7 守卫一并列入 **Phase 2 计划** |
| E6 | **倒填停用使已过的一天不再是漏服** | 状态层 `pausedAt=true` → 不再投影该窗口 → 该日不再是 MISSED；历史/日历显示「暂停（不计漏服）」（§6.1）。**必须有回归测试** |
| E7 | **恢复的日期** | 历史从恢复日恢复活跃；提醒从当下向前重排（未来排期可接受） |
| E8 | **生效日早于该药最后一次计划变更** | **不再依赖 `updatePlan` 的落段守卫**（§2.B）：状态层与计划链正交，倒填不影响计划链，**无空洞/重叠** |
| E9 | **删除带状态事件的药** | FK **CASCADE**：连同状态事件（含更正链）一起删。要求 5 的「历史不可丢」约束**跨停用**，不跨**删除** |
| E10 | **批量的原子性** | 一次事务内为 N 个药写事件 + 缓存列；任一失败整体回滚（对齐 `updateMedications` `MedicationRepositoryImpl.kt:83-86`） |
| E11 | **生效日跨时区** | 批量 UI 的「生效日期」按**成员提醒时区**的本地日界换算为 `effectiveAtMs`；D11 的「≤ 今天」也按该时区判定 |
| E12 | **误记更正** | 追加取代/更正事件 + 重算（§2.C/§9.2）；不改旧行、不删日志 |
| E13 | **系统自动核对 vs 用户主动核对** | 自动只写缓存 + `lastAutoReconciledAtMs`、**绝不**写 `lastUserCheckedDate`；用户核对**只**写 `lastUserCheckedDate`、不写事件、不算服药（§8、§9.3） |
| E14 | **多层取代** | 只有链末行生效（R1）；祖先永不生效 |
| E15 | **VOID 取代** | 目标死；状态回退到被作废行之前的活状态行，无则「无状态」（R3） |
| E16 | **重复取代** | 目标死；`(recordedAtMs, id)` 最大者胜，败者遮蔽保留（R4） |
| E17 | **冲突取代 / 成环** | 冲突由全序确定性解决；成环**结构禁止**（`supersedesId < 自身 id`，R5/R6） |
| E18 | **遗留归档药** | 零事件 ⇒ 冻结在 `isArchived`（R7）；首个非 RESUME 事件不翻活跃，首个 RESUME 翻活跃；自动核对不改基线（§9.4） |

---

## 12. 迁移 v25 → v26（纯新增 + 遗留基线）

**SQL 变更**（照 `MIGRATION_24_25` 纯新增先例，`MedLogDatabase.kt:297`）：`CREATE TABLE medication_status_events`（含 `supersedesId` 列）+ 索引 `index_medication_status_events_medicationId`、`index_medication_status_events_medicationId_effectiveAtMs`；**无表重建、零数据搬运**。**`medications` 与 `medication_plan_revisions` schema 不变**（§2.B：不新增暂停窗口字段、不改计划链）。历史 `isArchived` 行**零回填**（派生回落 R7）。

### 12.1 遗留基线的取舍（更正 G，逐项比较）

**问题**：`isArchived=true` 的遗留药**没有任何状态事件**。若朴素地 `pausedAt(events=∅)` ⇒ 「未暂停」⇒ **一被读就静默恢复**。

| 方案 | 机制 | 关键质疑 | 裁决 |
|---|---|---|---|
| **(a) 迁移时补种基线事件** | 为每个 `isArchived=true` 药 insert 一条 PAUSE 事件 | **它该带什么生效日？** 遗留布尔**根本没有日期**。补一个**伪造日期**（如迁移时刻 / epoch / `Long.MIN`）：要么把「原归档日 → 迁移日」之间**误标为活跃/漏服**（污染历史与依从率），要么在 UI 显示一个**凭空的生效日**，且违反「事件必须诚实记录真实发生」与 §6 可解释性（会显示一条并不存在的「归档于 X」） | **否决**（伪造历史，不可接受） |
| **(b) 派生内显式遗留回落** | 零事件且 `isArchived=true` ⇒ 暂停（R7） | 需要 `isArchived` 在零事件期间**不被覆写** | **采纳**（见下） |
| **(c) 更好的选项：基线视为「虚拟 PAUSE@-∞」，对零**状态**行兜底** | 等价于 (b) 的推广：无活状态行时注入虚拟基线 | 会把「全部事件被 VOID」与「从未有事件」混为一谈 | **不优于 (b)**：与「事件权威」张力更大，且本题只要求覆盖「零事件」；故**退回 (b)** |

**采纳 (b) + 配套不变量**（= D12）：
- 派生：`deriveCurrentStatus = if (events.isEmpty()) legacyArchived else pausedAt(events, now)`（R7）；`legacyArchived` = 迁移前该行 `isArchived`。
- **自动核对对零事件药跳过写缓存**（§8.1），从而**基线在其生命周期内不被覆写**。
- **零回填、零伪造日期**：`medications` 表历史行原样保留。
- **首个新事件**（用户对遗留药首次操作）到来时，`events` 非空 ⇒ 回落关闭，事件权威接管（首个 RESUME 显式恢复；首个 PAUSE/DISCONTINUE 仍暂停）。

**与「事件权威」的一致性（显式写清）**：事件**一旦存在**完全决定状态；`legacyArchived` 仅在**零事件**这一唯一情形下兜底不可考历史，且**任何活状态事件都压过它**（§5 R7、§18 表行 12）。

### 12.2 本仓全部配套（逐项必做）
- [ ] `DatabaseSchema.VERSION` → **26**（`DatabaseSchema.kt:6`，当前 25）。
- [ ] 新增并注册 `MIGRATION_25_26`（`MedLogDatabase.kt`）。
- [ ] `AppModule.provideDatabase.addMigrations(...)` 追加 `MIGRATION_25_26`（`AppModule.kt:118` 处）。
- [ ] 导出 **`26.json`**（`core/database/schemas/.../MedLogDatabase/`，当前止于 `25.json`）。
- [ ] **每一处硬编码迁移列表**都补 `MIGRATION_25_26`（当前含 `MIGRATION_24_25` 的**测试列表共 13 处**，分 5 个文件）：
  - `MedLogDatabaseMigrationTest.kt` — **5 处**（`:55`、`:108`、`:154`、`:255`、`:347`）。
  - `CareEventMigrationTest.kt` — 2 处（`:46`、`:106`）。
  - `CarePeopleMigrationTest.kt` — 2 处（`:48`、`:137`）。
  - `CareNoteMigrationTest.kt` — 2 处（`:49`、`:156`）。
  - `CareTodoMigrationTest.kt` — 2 处（`:54`、`:123`）。
  - （`CareRecipientMigrationTest`/`CareTaskMigrationTest` 只迁到固定旧版本，**不含** `MIGRATION_24_25`，**无需**改。）
- [ ] 版本断言：`RoomModelContractTest.kt:34`（`assertEquals(25, …)` → **26**）。
- [ ] `BackupCompatibilityPolicyTest.kt:24`（`assertEquals(25, DatabaseSchema.VERSION)` → **26**）并补 `assertTrue(canRestore(25))`。
- [ ] 新增 **`MedicationStatusMigrationTest`**（照 `CareEventMigrationTest` 模板）：**种一个带数据的 v25 库 → 迁移 → 断言**：旧数据零丢失、`medication_status_events` 存在且可读写、两条索引存在、删除药级联删事件。
- [ ] 新增 **`MedicationStatusLegacyTest`**（§9.4）：**`isArchived=true` 零事件药迁移后仍暂停、空列表不静默恢复、首个非 RESUME 事件不翻活跃、首个 RESUME 翻活跃、自动核对不改基线**。
- [ ] 四套 locale 文案（`values`/`-en`/`-ja`/`-ko` 的 `medication_flow_strings.xml`），含暂停区间与「暂停不计漏服」文案。

**备份/恢复后果**：`BackupCompatibilityPolicy.canRestore` 上界跟随 `VERSION`（`BackupRestoreUseCase.kt:83-88`）→ 恢复的 **v25 备份自动放行**，首次打开依次迁移到 26。既有约束不变：**旧版本 App 打不开新版本备份**（非本特性缺陷）。

---

## 13. 影响面（文件级）

**新增**
- `core/database/.../model/MedicationStatusEvent.kt`（+ `MedicationStatusKind` 常量，含 `VOID`）
- `core/database/.../local/MedicationStatusEventDao.kt`（含 `insertEvent` 的 `supersedesId < 自身 id` 校验）
- `app/.../data/repository/MedicationStatusRepository.kt` + `Impl`（成员作用域）
- `app/.../feature/medications/application/SetMedicationStatusUseCase.kt`（单药 + 批量，含 diff/CHECK/`liveEvents`/`pausedAt`/`deriveCurrentStatus` 纯函数）
- `app/.../feature/medications/application/CorrectMedicationStatusEventUseCase.kt`（更正 C）
- 自动核对用例 `ReconcileMedicationStatusUseCase`（§8.1）
- 迁移/派生/相容/事实分离测试：`MedicationStatusMigrationTest`、`MedicationStatusDerivationTest`、`MedicationStatusCorrectionTest`、`MedicationStatusReconciliationTest`、`MedicationStatusLegacyTest`
- `docs/medication-status-spec.md`（本文件）

**修改**
- `DatabaseSchema.kt`（25→26）、`MedLogDatabase.kt`（注册实体 + `MIGRATION_25_26`）、`AppModule.kt`（DI）
- `MedicationRepositoryImpl.setArchived`（改为 append 事件 + 直写缓存列，不再 `updatePlan(copy(isArchived=…))`）
- `AndroidReminderReconciler`（A 类排程判据改**读派生** `deriveCurrentStatus`，不再裸读 `isArchived`）
- `FuturePlanCalculator`（增加 `statusEvents` 入参 + `statusActiveAt` 过滤；无事件回落段 `isArchived`）
- `MedicationAdherenceCalculator`（暂停日 `counted=false`、不合成 MISSED，§6.2）+ 历史/日历屏暂停区间与「已调整」标记（§6.1）
- `MedicationDaoTransactionTest`（更新「状态改版不新建计划段」断言）
- 迁移/契约测试（§12.2 清单）

**Phase 2（不在本轮）**
- `MedLogAlarmReceiver.handleMedication` 顶部**发送前再校验守卫**（§7）

---

## 14. 任务分解（分阶段，每步可独立验证）

**Phase 1（首期；**生效日 ≤ 今天**，无发送前守卫）**
- [ ] T1 实体 + DAO + 仓储 + v25→v26 迁移（迁移测试：种 v25 有数据 → 零丢失、级联；**LegacyTest：遗留归档药迁移后仍暂停、空列表不静默恢复**）
- [ ] T2 `liveEvents`/`pausedAt`/`deriveCurrentStatus` 纯函数 + `SetMedicationStatusUseCase` 的 CHANGE/CHECK diff（单测：**多层/VOID/重复/冲突/环（§9.1）**、重复停用幂等、同日后停恢复、倒填、批量原子性）
- [ ] T3 正交状态层接入 `FuturePlanCalculator`（`statusEvents` + `statusActiveAt`）；回归：`FuturePlanCalculatorTest`、`MedicationAdherenceTest`、`MedicationDaoTransactionTest`
- [ ] T4 依从率/历史屏：**暂停区间显式呈现 + 暂停日不计漏服（不计分母、不合成 MISSED）**，倒填不再 MISSED（单测 + 契约测试）
- [ ] T5 **两种事实分离**（§8）：`lastAutoReconciledAtMs`（自动）与 `lastUserCheckedDate`（仅用户核对写）；**单写者测试 + 「自动通道不得移动用户日期」测试**（§9.3）
- [ ] T6 **首期日期约束**（D11）：校验拒绝未来生效日（`FutureEffectiveDateNotAllowed`）+ UI 日期选择器 `maximumDate=今天`（契约测试）
- [ ] T7 误记更正 = 取代事件 + 重算（§2.C/§9.2；`MedicationStatusCorrectionTest`）
- [ ] T8 我的药品多选 + 批量动作栏 + 详情双入口 + 变更记录/「已调整」/暂停区间 UI（Compose 契约测试；不新增底部 Tab）
- [ ] T9 真机全链路：批量停用（倒填）→ 首页/小组件不再出现 → 依从率不再漏服且显示暂停区间 → 恢复 → 从恢复日重排；**既有停用行为验收（§16.12）**

**Phase 2（真实待定，不阻塞首期）**
- [ ] 未来生效日的**精确定时**：合入**发送前再校验守卫**（§7）并以其测试为**发布闸门**；解除首期「拒绝未来生效日」限制
- [ ] 剂量调整自身的生效日期（计划段的生效日语义）

---

## 15. 非目标（本期明确不做）

- 不新增第 6 个底部 Tab（`NavigationHierarchyTest` 钉死 5 个顶层目的地）。
- **不做状态事件的就地编辑/物理删除**（append-only 审计链；改错=追加取代事件）。**注：更正能力本身在首期**（§9.2），「不做」的只是「就地编辑/删除」。
- **不采纳第 1 稿 D3/E8 的「计划段按生效日有序插入/裁剪」**（§2.B 已证不可行）。
- 不改 `updatePlan` 签名；pause/resume 不写 `medication_plan_revisions`。
- **首期不做未来生效日**（D11）；**不做发送前再校验守卫**（§7 属 Phase 2）。
- 不做用药医嘱/剂量与状态的联动（医生处方文本解析）。
- 不做跨设备同步 / 云端状态历史。
- 不改 `MedicationLog` 的既有撤销=物理删除语义；不改打卡主流程。
- 不迁移/清除既有的 `isArchived` 历史（**零回填、零伪造日期**，§12.1）。
- **自动核对不写用户核对日期**；核对**不产生状态事件、不作为服药证据**；`lastAutoReconciledAtMs`/`lastUserCheckedDate` **均不进依从率**。

---

## 16. 验收

1. 批量停用（多选）+ 生效日 + 原因 → **一个事务**写入，全部选中药立即从今日计划/小组件消失，提醒取消。
2. **生效日 ≠ 录入时间**：倒填（生效昨天、今天录入）后，昨天/今天**不再**被算成漏服；**历史/日历显示暂停区间（「暂停（不计漏服）」）**，依从率**扣除暂停天**（§6.1/§6.2）。
3. 批量恢复（可倒填）→ 从恢复日重新排期；当日/未来计划恢复；提醒重排。
4. **两种事实分离**：① 用户主动核对（显式完成）→ `lastUserCheckedDate=今天`、事件行数不变、日志行数不变；② **连续跑系统自动核对（时钟推进）→ `lastUserCheckedDate` 不变**、`lastAutoReconciledAtMs` 前进；③ 二者都不写 `medication_logs`。
5. **历史永不丢**：任何停/恢复/更正/核对操作后，`medication_logs` 行数与内容不变。
6. **多次循环**：停→恢复→再停→再恢复可表达且投影正确。
7. **首期不作未来生效**：UI 禁选 + 校验拒绝未来生效日（错误码 `FutureEffectiveDateNotAllowed`）；今天/过去可接受。
8. **两档语义**：`临时停用` 与 `归档` 共用历史与派生，文案/入口可分。
9. **误记更正**：错生效日/错 kind/重复均可更正，重算历史投影，旧链留存可审计，日志不变。
10. **取代链正确性**：多层/VOID/重复/冲突/环 全部按 §9.1 通过；**被取代事件永不生效**（§5.2 回归）。
11. **遗留药**：`isArchived=true` 零事件药迁移后仍暂停、**空列表不静默恢复**、首个非 RESUME 事件不翻活跃（§9.4）。
12. **既有停用行为（must still hold）**：对某药**今天**执行停用 → (a) 该药**立即停止提醒**（排程撤销，§1.4）；(b) 该药**统计行为启动**：在暂停窗口内其依从日 `counted=false`、不再合成漏服（§6.2），历史屏显示暂停区间；(c) 恢复后统计从恢复日恢复计入口径。**逐步验收并有测试钉死。**
13. **迁移**：v25 升级 26 零丢失；恢复的 v25 备份正常打开；§12.2 全部测试通过。
14. **零回归**：既有 5 个顶层目的地不变；用药打卡/跳过/撤销、照护事项、提醒时刻与基线一致。

---

## 17. 工具脚本迁移（决策 5）

**引用核查（先确认再移动）**：
- `tools/dump_ui.py`、`tools/find_node.py`、`tools/pull_db.sh` 在本仓**任何 docs / 脚本 / skill 中均无引用**（全库 `grep 'dump_ui|find_node|pull_db|tools/'` 仅命中无关的 `seven_segment_ocr/`（PaddleOCR 自带 `tools/`），**与这三个脚本无关**）。
- 三者**未被 git 跟踪**（`git ls-files tools/` 为空；`git status` 显示 `?? tools/`），故**无需删除任何已跟踪文件**。

**动作（已完成）**：将三个脚本**原样移动**到 `anshin-android-dev` skill 的资源目录，**未删除、未覆盖**（目录此前不存在）：
- `~/work/anshin/tools/dump_ui.py` → `~/.hermes/skills/software-development/anshin-android-dev/scripts/dump_ui.py`
- `~/work/anshin/tools/find_node.py` → `~/.hermes/skills/software-development/anshin-android-dev/scripts/find_node.py`
- `~/work/anshin/tools/pull_db.sh` → `~/.hermes/skills/software-development/anshin-android-dev/scripts/pull_db.sh`

**结果**：仓库内 `tools/` 已清空（空目录不被 git 跟踪，`git status` 不显示）；三个脚本现归属 `anshin-android-dev` skill（设备 UI dump / 节点定位 / 拉取 live DB+闹钟登记表），供后续真机 QA 复用。

---

## 18. 终稿一致性检查（逐对核验；含三项指定复核）

| # | 检查的对 | 证据 | 裁决 |
|---|---|---|---|
| 1 | **§5 派生谓词 vs 每一处读「状态」的路径** | 提醒排程（§1.3 A 类）改读 `deriveCurrentStatus`；投影 `FuturePlanCalculator` 用 `statusActiveAt=!pausedAt`（§5.3）；依从率用 `pausedAt`（§6.2）；缓存写回用 `deriveCurrentStatus`（§2.A/§8.1）；**全部走同一个 §5 谓词**，无第二套判定 | **一致**（派生为唯一真相） |
| 2 | **§5 派生谓词 vs 缓存 `isArchived`** | 缓存降级为 B/C 类消费者 + 零事件回落（R7）；A 类不再裸读（§1.3/§2.A） | **一致** |
| 3 | **旧谓词 `supersedesId == null || …` vs 更正 D** | 旧式**已删除**，全篇不再出现该形式；§5.1/§5.2 给出新式与反例 | **已修正**（无残留） |
| 4 | **R1 多层取代 vs「只 C 生效」测试** | §5.1 R1、§9.1.1 | **一致** |
| 5 | **R3 VOID vs「回退到被作废行之前的活状态行」** | §5.1 R3、§11 E15、§9.1.3 | **一致**（确定，不回到作废行本身） |
| 6 | **R4 重复取代平局 vs「败者遮蔽保留」** | §5.1 R4、§11 E16、§9.1.4；append-only 不删行 | **一致** |
| 7 | **R5/R6 冲突 vs 环防止** | 冲突由全序确定性解决（顺序无关测试），环由 `supersedesId<自身 id` 结构禁止（§4/§5.1 R6/§9.1.6） | **一致**（既确定性又结构禁止） |
| 8 | **§5 派生 vs 提醒路径（指定复核）** | A 类三读者（`AndroidReminderReconciler.kt:40/88`、`FuturePlanCalculator.kt:50`）统一读 `deriveCurrentStatus`/`pausedAt`（§1.3、§13「修改」）；首期同步写缓存保证即时一致 | **一致** |
| 9 | **§5 派生 vs 依从路径（指定复核）** | `MedicationAdherenceCalculator` 用 `pausedAt` 决定 `counted`，不合成 MISSED（§6.2）；与派生同源 | **一致** |
| 10 | **D11「首期不接受未来生效日」 vs 全篇 phase-1 文本** | §0 决策 3、§0.1.3、§2.A.2、§3 D7/D11、§7（整节标注 Phase 2）、§11 E5、§14 Phase1/Phase2、§15、§16.7 全部改口径为「首期拒绝未来生效日」；**无残留**「首期可上未来生效」措辞 | **一致**（无矛盾） |
| 11 | **D11 vs Phase 2 计划章节（指定复核）** | §7 标题与首句明标「【Phase 2 计划】…首期不交付」；§14 Phase 2 首项即守卫 + 解除限制；§13 Phase 2 单列；二者**互补不冲突** | **一致**（Phase 2 计划完整保留） |
| 12 | **R7 遗留回落 vs「事件权威」规则（指定复核）** | `deriveCurrentStatus`：**零事件**才用 `legacyArchived`；**有事件则 `pausedAt` 完全决定**（§5.1 R7、§12.1）；自动核对对零事件药跳过写缓存，基线不被覆写 | **一致**（回落仅限零事件这一唯一情形，事件始终压过基线） |
| 13 | **§8 两种事实 vs 依从率/日志** | `lastAutoReconciledAtMs`/`lastUserCheckedDate` **均非** `medication_logs` 行、**均不被** `MedicationAdherenceCalculator` 读取；§8/§9.3/§15/§16.4 明写 | **一致** |
| 14 | **§8 自动通道 vs 用户核对日期** | 自动通道写 `lastAutoReconciledAtMs` **且**对其「不得写 `lastUserCheckedDate`」有硬约束 + 单写者/不移动测试（§8.1/§8.3/§9.3） | **一致**（自动不能移动用户日期） |
| 15 | **§6 暂停区间呈现 vs「统计变化可解释」** | 历史/日历显式暂停条带 + 「已调整」标记（§6.1）；依从率扣除暂停天 + 前后数值差（§6.2） | **一致**（非静默） |
| 16 | **决策 2 的重算历史 vs 决策 1 的 append-only** | 重算只**读**事件链派生应服/依从率，**不改**事件行、**不删**日志（§5/§6/§9.2） | **一致** |
| 17 | **更正 C 的变更模型 vs 更正 A「事件 append-only」** | C 采纳**追加取代事件**（不就地编辑、不物理删除），`supersedesId` 只增加行（§2.C/§4/§9.2） | **一致** |
| 18 | **第 1 稿 D3/E8「计划段边界=生效日/有序插入」 vs 更正 B** | 已用 D3'（正交状态层）替换，E8 重写为「状态层正交，计划链不动」（§2.B、§3 D3'、§11 E8） | **已修正**（冲突消除） |
| 19 | **决策 5 移动脚本 vs「只改本文件」** | 移动的是**未被 git 跟踪**的 `tools/` 脚本（非业务代码、非提交）；仓库唯一跟踪变更 = 本文件（§17、§19） | **一致** |
| 20 | **`updatePlan` 不变 vs 倒填需要边界** | 倒填边界改由**事件层**（`effectiveAtMs`）承载，计划链只为剂量调整服务（§2.B/§3 D3'） | **一致** |

**三项指定复核结论**：(1) §5 派生被提醒路径、投影路径、依从路径、缓存写回**统一使用**（表行 1/8/9）——一致；(2) 首期「无未来生效日」（D11）与 phase-1 文本**无矛盾**、与 Phase 2 计划**互补**（表行 10/11）——一致；(3) R7 遗留回落与「事件权威」**相容**（零事件唯一例外，事件始终压过基线，表行 12）——一致。**检查未发现冲突；无可修项。**

---

## 19. 本轮变更与验证

- **只改本文件**：`git status --short` 仅 `M docs/medication-status-spec.md`（`tools/` 已按 §17 移出，仓库内无业务代码改动、无提交、无推送）。
- 行数：第 2 稿 503 行 → 本稿 **683 行**（19 节，新增 §5.1/§5.2 取代链规则与反例、§6.1/§6.2 暂停区间呈现、§8.1/§8.2 两种事实、§9.1–§9.5 测试清单、§12.1 遗留基线取舍、§18 终稿一致性检查）。
- **本轮编码的五项更正**：D（取代链谓词重写）→ §5、§9.1；E（两种事实分离）→ §8、§9.3；F（首期无未来生效日 + 守卫移 Phase 2）→ D11、§7、§14、§16.7；G（遗留空列表不静默恢复）→ R7、§12.1、§9.4；H（历史/依从率显示暂停区间）→ §6.1、§6.2、§16.2。
