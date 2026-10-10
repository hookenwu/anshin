# 药品停用 / 恢复（状态事件 + 生效日期）—— SDD 规格（第 2 稿·修订）

状态：**规格 + 审计；本轮只改本文件，不改任何业务代码、不提交、不推送。**
范围：`我的药品`（`MyMedicationsScreen`）的**批量停用 / 批量恢复**，带**生效日期**与可选**原因/备注**；`medication_status_events` 状态事件表（**append-only、可更正**）；日期感知的计划投影 / 提醒 / 小组件 / 依从率；**每日核对**（`lastReconciledAtMs`）；迁移 **v25 → v26**。
关联：`docs/medication-daily-flow.md`（今日打卡主流程、`FuturePlanCalculator`/`MedicationAdherenceCalculator` 契约）、`docs/care-tasks.md §11.2`（归档药不得进今日计划的来源与两次修复）、`docs/tracked-events-spec.md`（「纯新增迁移 + 双时间戳（发生 vs 记录）+ **发送前再校验**」的最近先例）。

> 触发场景（用户真实工作流）：医生**每日调整处方**，用户需决定哪些药**继续吃 / 停掉 / 重新吃**，并把这三个日期**记下来**。停用/恢复**不是删除**，历史**绝不能丢**。

---

## 0. 决策账本（本稿逐条落点）

| # | 决策（用户原意） | 落点 |
|---|---|---|
| 决策 1 | 暂停(PAUSE)与归档(DISCONTINUE)共用**一套状态事件机制**，但 **UI 语义必须区分** | §3 D5、§10 |
| 决策 2 | 允许倒填生效日，**必须重算历史应服剂量与依从率**；**剂量日志永不丢**；历史统计变化**必须可解释** | §5、§6、§16 |
| 决策 3 | 未来生效的暂停**不得只靠 best-effort reconcile**：要么证明**生效后不会误提醒**，要么**首期直接不做未来生效日** | §7、§3 D7 |
| 决策 4 | 首期含**轻量每日核对**，记录 **last-reconciled 日期**；核对**不产生状态事件**、**不得当作服药证据**（非依从输入） | §8 |
| 决策 5 | `tools/` 脚本在**用法与引用确认后**移入 skill 资源目录，**永不删除/覆盖** | §17 |
| 更正 A | `isArchived` 只能是从属的**兼容缓存**；排程/提醒必须键在**生效日期** | §2.A、§1.3 |
| 更正 B | 先用真实代码**证明** `MedicationPlanRevision` 能否承载「倒填暂停 ∩ 后续剂量调整」，**不得**凭空假定插入/裁剪方案 | §2.B |
| 更正 C | 事件日志要有真实的**误记更正**机制（错生效日 / 错 kind / 重复），且**重算历史投影** | §2.C、§9 |

### 0.1 相对第 1 稿的实质变更

1. **否决第 1 稿 D3/E8 的「计划段按生效日有序插入/裁剪」**。经代码核查（§2.B），`updatePlan` 的守卫会**静默丢弃**倒填段的旧计划，且没有任何测试覆盖倒填；该方案**不可采纳**。改为**与计划链正交的状态层**（§2.B、§3 D3'）。
2. **`updatePlan` 签名不变**（不再接收显式生效边界）；pause/resume **不再写入 `medication_plan_revisions`**，只 append 事件 + 直写派生缓存列（§3 D3'、§12）。
3. **误记更正**从第 1 稿「非目标」升级为**首期能力**（更正 C → §2.C、§9）：**追加取代事件**，不就地编辑。
4. **未来生效的暂停**：给出**发送前再校验守卫**的可证方案与测试，**首期可上，但以守卫合入为发布闸门**（§7）。
5. 新增**每日核对 + `lastReconciledAtMs`**（决策 4 → §8）；明确**核对 ≠ 服药**。
6. 新增**倒填可解释性**（决策 2 → §6）。
7. `tools/` 三个脚本**已移入** `anshin-android-dev` skill 的 `scripts/`（决策 5 → §17）。

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

**A 类——决定「是否提醒/排程」（必须键在生效日期，不得信缓存）：**

| 读者 | 位置 | 用途 |
|---|---|---|
| `AndroidReminderReconciler.reconcileMedication`：`if (medication != null && !medication.isArchived && !medication.isPRN) schedule(medication)` | `app/.../capability/reminders/AndroidReminderReconciler.kt:40` | **单药排程闸门** |
| `AndroidReminderReconciler.reconcileAll`：`if (!medication.isArchived && !medication.isPRN)` → 加入 `liveMedications` 并排程 | `app/.../capability/reminders/AndroidReminderReconciler.kt:88` | **全量排程 + 孤儿清理存活集** |
| `FuturePlanCalculator`：段 `isArchived=true` → 跳过 | `app/.../feature/medications/application/FuturePlanCalculator.kt:50` | **投影闸门** |

**B 类——SQL 活跃/归档口径（缓存消费者）：** `MedicationDao.kt:15`（`getActiveMedications`）、`:27`（`getArchivedMedications`）、`:36`（`getNormalizedActiveNames`）、`:39`（`deleteActiveMedications`）、`:107`（`getAllMedicationsOnce`）。

**C 类——展示/边界栅栏（缓存消费者）：** `HomeViewModel.kt:571`、`WidgetUtils.kt:29`、`TodayItem.kt:77/106/145`、`MyMedicationsScreen.kt:61`、`PlanExportSchema.kt:94`、`MedicationDetailScreen.kt:128/149`、`MedicationDetailViewModel.kt:140`、`MedicationAdherence.kt:72`。

**写入者：** `MedicationRepositoryImpl.kt:102`（`setArchived` → `updatePlan(copy(isArchived=…))`）、`MedicationDao.kt:96/99`（直接 UPDATE，当前未被归档路径使用）。

**现状缺陷**：**A 类三个读者全部直接读 `isArchived` 缓存**；没有任何一处按「生效时刻」判定。因此布尔一旦与事件不一致（倒填/未来生效/缓存陈旧），提醒与投影即随之出错。

### 1.4 提醒

| 情形 | 现状行为 | 位置 |
|---|---|---|
| 停用（当前已归档） | `cancelAllAlarms` + `cancelAllReminderNotifications`；因 `!medication.isArchived` 为假**不再排期** | `AndroidReminderReconciler.kt:31-44`（守卫在 `:40`） |
| 全量重排 | 仅 `!isArchived && !isPRN` 计入 `liveMedications` 并排期，其余只清理 | `AndroidReminderReconciler.kt:86-92` |
| 恢复 | `!isArchived` 通过 → `schedule(medication)` | `AndroidReminderReconciler.kt:131-143` |
| 闹钟触发后的行为 | `MedLogAlarmReceiver.handleMedication` **只**取 `medicationRepo.getMedicationById(medId)`，**从不检查** `isArchived`/有效状态 | `MedLogAlarmReceiver.kt:91-222`（取药在 `:104/116/131/148/187`） |
| 三态提醒分派 | `onReceive` 的 `when(dispatch.kind)` 把 `MEDICATION_NORMAL/EARLY/FOLLOW_UP` **全部**路由到 `handleMedication` | `MedLogAlarmReceiver.kt:81-87` |
| 事务收口 | 状态写入经 `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` → 立即重排 | `app/.../capability/reminders/application/ReminderReconciliation.kt:14-21` |

**结论**：停用应**立即停闹钟与通知**；恢复应**从恢复日重排**。**关键缺口**：闹钟触发路径**没有发送前再校验**（§7 要补）。**任何状态写入（含倒填、未来生效）都必须触发一次 reconcile**。

### 1.5 剂量日志（要求 5）

- 归档路径**只写** `medications` 与 `medication_plan_revisions`（`MedicationRepositoryImpl.kt:100-105` → `MedicationDao.kt:74-85`），**从不触碰** `medication_logs`。`MedicationLogDao` 的删除接口只被「撤销打卡」类用例调用，**不在**状态路径上。
- **结论**：**剂量日志从不被状态变更触碰**——要求 5 对现状成立；新路径必须**保持**该性质（状态写入 = 只 append 事件 + 只改当前计划边界/派生缓存）。

---

## 2. 三处架构更正（A / B / C）

### 2.A `isArchived` 只允许是「从属兼容缓存」

**规则（可测试）**：`medications.isArchived` 的语义**唯一**定义为
`derivePaused(events, now)`（§5），其中 *events* 是 `medication_status_events` 的全部未取代事件，*now* = `clock.millis()`。
**它永远不是提醒决策的真相来源**：排程与投影必须键在**生效日期**上（由事件算出）；缓存列只是为了让 §1.3 的 A/B/C 三类既有读者**零改动继续工作**（含 SQL `isArchived=0/1` 查询）。

**边界如何从事件派生**：`pausedAt(t) = ` 取 `events.filter { it.effectiveAtMs <= t }.maxWith(compareBy(effectiveAtMs, recordedAtMs, id))?.kind ∈ {PAUSE, DISCONTINUE}`（纯函数，§5）。历史任一时刻的状态由此现算——**与事件写入顺序无关**。

**缓存陈旧 / 与事件不一致时**：
1. **事件赢**。任何读者若发现缓存与 `derivePaused` 不一致，以派生值为准并**重写缓存**（每日核对 §8）。
2. **提醒的最终兜底**是 §7 的**发送前再校验守卫**：闹钟真正弹通知前**再算一次** `derivePaused(events, nowMs)`，与缓存无关。因此**即便缓存冻结、陈旧或错误，生效时刻之后也不会弹错提醒**。
3. 倒填/未来生效**不**通过 `updatePlan` 改计划链（§2.B），只 append 事件 + 直写缓存列（`MedicationDao.archiveMedication/unarchiveMedication`，`:96-100`）。

**测试性**：
- 不变量测试：每次状态写入后、以及每次每日核对后，断言 `medications.isArchived == derivePaused(events, now)`（property/事务测试）。
- 对抗测试：**人为把缓存住成 `isArchived=false`，而事件表给出「生效日≤now 的 PAUSE」** → 断言 (a) §7 守卫**抑制**提醒；(b) 每日核对把缓存纠正回 true。此测试即「缓存不得成为提醒真相来源」的可执行证明。

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
- **重复条目** → append 一条 `VOID`(或同 kind)事件 `supersedesId=重复者`，使其从派生中退出。

**重算**：任何事件 append（含取代）后，**重跑状态层**（§5）→ **重算历史应服剂量与依从率**（§6）→ 刷新缓存 + reconcile。**剂量日志行数/内容不变**。

**与决策 2 可解释性的衔接**：取代链本身就是**变更日志**（`recordedAtMs` = 何时改、`effectiveAtMs` = 改到哪个生效日、`reason`、`supersedesId` = 改了哪条），直接喂给 §6 的「历史已调整」UI。

**证明测试**：见 §9。

---

## 3. 关键设计决策

**D1 — 采用「状态事件表」（append-only、可取代），不用 `isArchived` 承载历史。**
新增 `medication_status_events`（`id, medicationId FK CASCADE, kind, effectiveAtMs, recordedAtMs, reason?, supersedesId?`）。**理由**：要求 1/2 + 「继续吃/停/重吃」的**多次循环**本质是**事件流**；布尔与单对列都装不下。

**D2 — `medications.isArchived` 保留，但降级为「派生的当前状态缓存」，不移除。**（更正 A → §2.A）
读者太多（§1.3 A/B/C 三类），移除会造成大面积回归面。保留一个**由事件派生的**列，语义 = 「**生效时刻 ≤ now 处于停用/归档**」。派生函数见 §5；**无事件**的历史行回落到原 `isArchived`（迁移零回填）。

**D3（替换第 1 稿 D3）— 计划链不变；倒填暂停由「正交状态层」承载，不写版本链。**（更正 B → §2.B）
pause/resume **不**改 `medication_plan_revisions`、**不**改 `updatePlan` 签名；只 append 事件 + 直写派生缓存列。投影 = 计划段投影 ∧ 该时刻未暂停。现有计划链只继续承担**剂量/时刻调整**（边界=记录时刻，维持现状）。

**D4 — 计划段快照不新增「暂停窗口」字段。**（沿用第 1 稿）快照 `isArchived` 列保留为**遗留回落**；新增能力全部走事件层。

**D5 — 「归档」与「临时停用」= 同一事件引擎的两个 kind（`DISCONTINUE` / `PAUSE`），不建第二套关闭语义。**（决策 1 → §10）

**D6 — 「核对(CHECK)」与「变更(CHANGE)」在写入路径上区分；每日核对(RECONCILE)是第三种、系统发起的路径。**
- CHANGE：`desired != deriveCurrentStatus(events, effectiveAtMs)` → append 事件。
- CHECK（用户确认当前状态）：**不写事件**。
- RECONCILE（系统每日）：**不写事件**，只写缓存 + `lastReconciledAtMs`（§8）。三者都**不写 `medication_logs`**。

**D7 — 未来生效的暂停：首期可上，但以「发送前再校验守卫」为发布闸门。**（决策 3 → §7）
守卫合入且测试绿 → 未来生效日放行；守卫未合入 → 输入层**拒绝/夹紧**未来生效日（不进首期）。

**D8 — 迁移 v25 → v26 纯新增 + 备份/恢复上界随 `VERSION` 自动放行。** 见 §12。

**D9 — 每日核对 + `lastReconciledAtMs`。**（决策 4 → §8）核对不产生状态事件、不作为服药证据。

**D10 — 误记更正 = 追加取代事件 + 重算。**（更正 C → §2.C、§9）

---

## 4. 数据模型（D1/D10 落地）

```kotlin
// 状态种类是代码常量（对齐 care_event_logs 的 kind 存法；不用 enum Converter）
object MedicationStatusKind {
    const val PAUSE = "PAUSE"              // 临时停用（可能恢复）
    const val RESUME = "RESUME"            // 恢复
    const val DISCONTINUE = "DISCONTINUE"  // 归档 / 永久终止（将来仍可 RESUME，语义由 UI 区分）
    const val VOID = "VOID"                // 取代/作废（配合 supersedesId 使用；本身不改变状态）
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
    val effectiveAtMs: Long,          // 状态「实际生效」时刻（可倒填 / 可未来）
    val recordedAtMs: Long,           // 录入时刻（与生效独立）
    val reason: String? = null,       // 可选原因/备注
    val supersedesId: Long? = null,   // 更正：指向被本事件取代的旧事件（同药）；不建自引用 FK
)
```
- 复合索引 `(medicationId, effectiveAtMs)` 覆盖「取某药最新生效事件」。
- **append-only**：**不就地编辑、不物理删除**（与 `care_event_logs` 的「就地编辑+物理删除」**有意不同**，`CareEventLog.kt:28/59`）；改错 = **追加取代事件**（`supersedesId`）。
- `supersedesId` 用**普通可空列 + 索引**（不建自引用 FK，避免自环级联复杂度）；不变量：指向**同一药**的既有事件；**悬挂/跨药**的 `supersedesId` 被派生**忽略**（不抑制任何东西，安全退化）。
- 删除药物 → FK CASCADE 连带状态事件（§11 E9）。
- **DAO**：`getEventsForMedication(id): Flow<List<…>>`、`getLatestEffectiveOnce(id, nowMs)`、`insertEvent`；仓储按 `ActiveRecipientStore` 作用域（`scoped{}`/`currentOrNull()`/`requireRecipientId()+stamped()`，见 skill）。

---

## 5. 派生：状态层与投影（正交，更正 B 核心）

**派生纯函数**（无事件 = 回落缓存）：
```
pausedAt(events, t): Boolean =
    events.filter { it.supersedesId == null || !isSuperseded(it, events) }   // 只算「未被取代」的事件
          .filter { it.effectiveAtMs <= t }
          .maxWithOrNull(compareBy({ it.effectiveAtMs }, { it.recordedAtMs }, { it.id }))
          ?.kind in { PAUSE, DISCONTINUE }
deriveCurrentStatus(events, now, cache) = if (events 为空) cache else pausedAt(events, now)
```
- 「被取代」判定：存在某事件 `e2` 且 `e2.supersedesId == e1.id` → `e1` 退出派生。
- **顺序无关**：以 `(effectiveAtMs, recordedAtMs, id)` 排序取最新，故倒填、中段、未来生效都天然正确。
- **写回时机**：① 每次事件写入后；② 每次每日核对 / 应用启动 / 未来事件到期（§8）；③ 每次 reconcile。

**投影（`FuturePlanCalculator`）**：对每个 occurrence 追加 `statusActiveAt(events, occurrence.scheduledAt)` 过滤；`statusActiveAt = !pausedAt(events, t)`。**无事件**的药回落到既有「段 `isArchived` 跳过」（`FuturePlanCalculator.kt:50`）。
- **倒填停用**：P…R 时段凡 `pausedAt=true` → 无应服槽位 → **不再算 MISSED**（决策 2、§6）。
- **中段恢复**：恢复生效后 `pausedAt=false` → 从恢复日恢复投影（用覆盖该时刻的计划段）。
- **多次循环**：状态层按事件自动分段，计划链不动。

---

## 6. 倒填的可解释性（决策 2）

**什么会动（派生、可重算）**：
- **历史应服剂量计划**：受影响窗口内 `pausedAt` 翻转 → 该窗口的应服槽位增减。
- **依从率分母**：受影响的 `AdherenceDay.resolved/total`（`MedicationAdherence.kt:105-112`）现算变化。
- 派生缓存 `isArchived`；提醒排期。

**永远不动（硬不变量）**：
- **`medication_logs` 行数与内容**——倒填/核对/更正**一律不触碰**（§1.5、§16 验收 5）。
- 真实 `TAKEN`/`PARTIAL` 记录照旧保留；暂停前/暂停当天的打卡**仍在**（可能显示为「暂停前/期间记录」，但不被删除）。
- 事件链本身（append-only；更正 = 追加，不改旧行）。

**UI 如何让照护者看懂（绝不静默改写过去）**：
1. **提交前**：倒填变更弹确认摘要——「将重算 9/28–10/01：由 **2 次应服 / 1 次漏服** → **暂停 0 应服**；**0 条服药记录受影响**」。
2. **提交后**：一条**可关闭的变更提示** + **变更记录**（由取代链现算：`recordedAtMs` 何时改、`effectiveAtMs` 改到哪天、`reason`、被取代者）。
3. **历史/日历屏**：受影响日打上 **「已调整」** 标记并可点开解释「因停药生效日更正，本日不再计入应服」。
4. 重算是**确定性纯函数**：同一条链 → 同一结果，可复现、可审计。

---

## 7. 提醒：发送前再校验守卫（决策 3 裁决）

**现状（缺口）**：`MedLogAlarmReceiver.handleMedication`（`MedLogAlarmReceiver.kt:91-222`）在**每个分支都只 `getMedicationById`**（`:104/116/131/148/187`），**从不检查**有效状态——**今天不存在发送前再校验**。

**可用性（为什么合入后够用且便宜）**：
- **单点收口**：`onReceive` 的 `when(dispatch.kind)`（`:81-87`）把 `MEDICATION_NORMAL/EARLY/FOLLOW_UP` **全部**路由进 `handleMedication`。在 `handleMedication` 顶部加一行
  `if (medicationStatus.derivePaused(medId, clock.millis())) return`
  即覆盖**全部三类用药提醒通知**。
- **无级联**：`scheduleNextReminderAfterDose` / `scheduleFollowUpAlarm`（`:198-218`）都在守卫之后，被抑制时**不**再排下一次。
- **读真相而非缓存**：守卫调 §5 的 `derivePaused(events, now)`（缓存仅作无事件回落），故**缓存陈旧也无法击穿守卫**（这正是更正 A 与决策 3 的交点）。
- **对照先例**：care-event 的发送前四要素再校验（`CareEventReminderEngine.kt:66-78`）已证明该模式在本仓可落地、可穷举测试。
- **不误伤**：`ACTION_TAKEN`/`ACTION_SKIP`（用户点通知按钮的显式动作，`:102-124`）**不**置于守卫之后——**真实服药记录不得被拦**（决策 2 / 要求 5）。
- **成本**：一个注入依赖 + 顶部一个早退 + 测试；每次闹钟触发多一次事件读，可忽略。

**裁决（明文）**：**守卫对「闹钟/通知」面充分且廉价 → 未来生效的暂停可进首期，条件是把本守卫与其测试作为同一阶段的发布闸门。** 若守卫未合入，则**首期只接受「生效日 ≤ now」**，输入层拒绝/夹紧未来生效日（D7）。
**残余（须写清）**：守卫只覆盖**闹钟/通知**；**小组件 / 今日计划**读的是缓存（`WidgetUtils.kt:29`、`HomeViewModel.kt:571`、`TodayItem.kt:77/106/145`），可能在生效时刻后有**一次刷新窗口**的陈旧显示——由 §8 每日核对 + 既有 15 分钟 `WidgetRefreshWorker` + `widgetRefresher.refreshAll()`（`AndroidReminderReconciler.kt:43`）收敛；另**已在上屏的旧通知**不会瞬时消失，由下一次 reconcile/prune 收掉。

**证明测试（`MedLogAlarmReceiverTest`，注入可拨时钟）**：
- `t < F` 触发 EARLY/NORMAL/FOLLOW_UP → **照常**弹通知/排下一次；
- `t >= F`（F = 未来暂停的生效时刻）触发三类 → **全部抑制**，且不排下一次；
- 过期缓存（`isArchived=false`）但事件说已暂停 → 仍**抑制**（守卫读派生）；
- `ACTION_TAKEN/SKIP` 在暂停后仍**照常写日志**。

---

## 8. 每日核对 + `lastReconciledAtMs`（决策 4）

**触发（骑既有基础设施，不新增闹钟）**：应用启动 + 既有 15 分钟 `WidgetRefreshWorker` + 既有 `MedLogBootReceiver`（`MedLogBootReceiver.kt:22-37`）——与 care-event R9/R13 同口径。

**动作**：对当前成员逐药：`derived = deriveCurrentStatus(events, now, cache)`；
- 若 `derived != cache` → 写缓存列（`MedicationDao.archiveMedication/unarchiveMedication`）；
- 若 `derived` 由活跃翻为暂停 → `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` 撤闹钟/通知 + 小组件刷新；
- 收尾写 **`lastReconciledAtMs`**。

**存储**：成员级偏好层 DataStore 键 `medication_status_reconciled_at#<recipientId>`（对齐 `care_event_nudged_day#…` 的做法，`tracked-events-spec.md §4`），**不建表、不改 schema**。记录「最后核对时刻」（或本地 `epochDay`）。

**硬约束**：
- **不产生状态事件**：核对**绝不** `insertEvent`（写入路径只写缓存列 + 该时间戳）。
- **不作为服药证据**：`lastReconciledAtMs` **不是** `medication_logs` 行、**不被依从率读取**；它**不是**「已服药/已核对=已服」。文案层**禁止**把「今天已核对」表述为「今天已服药」。
- **幂等**：同一派生结果重复核对不产生额外写入（缓存相等即跳过）。

---

## 9. 误记更正流程（更正 C 细化 → §2.C）

统一命令入口 `CorrectMedicationStatusEventUseCase`，输入「目标事件 id + 更正后的 {effectiveAtMs, kind} 或 作废」：
1. 校验 `supersedesId` 指向同药的既有事件（否则拒绝）。
2. append 更正/取代事件（`kind=VOID` 表示仅作废）。
3. **重跑状态层**（§5）→ 刷新缓存 → reconcile。
4. **重算历史投影与依从率**（§6）；**剂量日志不变**。
5. **变更记录**由取代链现算，喂给 §6 的「已调整」UI。

**证明测试（`MedicationStatusCorrectionTest`）**：
- 错生效日更正 → 受影响窗口应服槽位改变、`medication_logs` 行数不变、`derivePaused` 反映更正值；
- 错 kind 更正（PAUSE↔DISCONTINUE）→ 派生 kind 改变、UI 标签与提醒行为随之改变；
- 重复条目作废 → 派生结果与「只有一条」时**逐字节一致**，恰好一条有效事件被采纳；
- **被取代事件永不被读取**：`derivePaused` 与 §7 守卫都忽略 `supersedesId` 命中者；
- 悬挂/跨药 `supersedesId` → 安全退化（不抑制任何事件）;
- 删除药物 → CASCADE 连带全部事件（含更正链）。

---

## 10. 归档 vs 临时停用（决策 1）

**不拆两套机制**：在同一张事件表上用两个 kind（`PAUSE`=临时停用、`DISCONTINUE`=归档/永久终止），**行为同构**（都停前向投影与提醒），**语义分开**（UI 两档）。本仓明确拒绝双关闭语义（`docs/care-notes.md:66`、`docs/todos.md:48`）。
**UI**：详情菜单两入口——`临时停用`（带生效日+原因，`PAUSE`）与`归档`（永久，`DISCONTINUE`）；列表 `已停用` chip 收纳**两者**，行内角标「暂停中·自 MM/DD」/「已归档」。
**结果**：要求「既分语义、又不分机制」——语义分（kind+文案），机制不分（一张表 + 一个派生列）。

---

## 11. 边界情形（逐条规格）

| # | 情形 | 规格 |
|---|---|---|
| E1 | 停用一个**没有未来剂量**的药（`endDate` 已过 / PRN / 已暂停） | 仍写事件（历史留痕）；排期部分 no-op。不得因「无未来剂量」拒绝记录 |
| E2 | **长时间暂停后恢复** | 恢复事件 + 状态层在恢复日翻开；暂停窗口保持被跳过 |
| E3 | **连续两次停用**（幂等） | 第二次 desired==当前 → 判为 CHECK，**不产生重复事件**（§3 D6） |
| E4 | **同一天先停后恢复** | 两事件都记录；派生以 `(effectiveAtMs, recordedAtMs, id)` 最新为准 |
| E5 | **生效日在未来** | 事件记录为「已排定」；`pausedAt(now)=false` 前照常；**生效时刻后由发送前守卫驳回任何误提醒**（§7，D7）。有效期切换的缓存刷新由 §8 收敛 |
| E6 | **倒填停用使已过的一天不再是漏服** | 状态层 `pausedAt=true` → 不再投影该窗口 → 该日不再是 MISSED。**必须有回归测试** |
| E7 | **恢复的日期** | 历史从恢复日恢复活跃；提醒从当下向前重排（未来排期可接受） |
| E8 | **生效日早于该药最后一次计划变更** | **不再依赖 `updatePlan` 的落段守卫**（§2.B）：状态层与计划链正交，倒填不影响计划链，**无空洞/重叠**（这取代第 1 稿 E8 的「有序插入/裁剪」） |
| E9 | **删除带状态事件的药** | FK **CASCADE**：连同状态事件（含更正链）一起删。理由：状态事件离开药无意义、无 UI 载体；本仓对「药拥有」的子行一律 CASCADE（`MedicationLog.kt:11-17`）。要求 5 的「历史不可丢」约束**跨停用**，不跨**删除** |
| E10 | **批量的原子性** | 一次事务内为 N 个药写事件 + 缓存列；任一失败整体回滚（对齐 `updateMedications` `MedicationRepositoryImpl.kt:83-86`） |
| E11 | **生效日跨时区** | 批量 UI 的「生效日期」按**成员提醒时区**的本地日界换算为 `effectiveAtMs` |
| E12 | **误记更正** | 追加取代/更正事件 + 重算（§9）；不改旧行、不删日志 |
| E13 | **每日核对与变更/核对的关系** | 核对只写缓存 + `lastReconciledAtMs`，**不写事件**、**不算服药**（§8） |

---

## 12. 迁移 v25 → v26（纯新增）

**SQL 变更**（照 `MIGRATION_24_25` 纯新增先例，`MedLogDatabase.kt:297`）：`CREATE TABLE medication_status_events`（含 `supersedesId` 列）+ 索引 `index_medication_status_events_medicationId`、`index_medication_status_events_medicationId_effectiveAtMs`；**无表重建、零数据搬运**。**`medications` 与 `medication_plan_revisions` schema 不变**（§2.B：不新增暂停窗口字段、不改计划链）。历史 `isArchived` 行**零回填**（派生回落）。

**本仓全部配套（逐项必做）**
- [ ] `DatabaseSchema.VERSION` → **26**（`DatabaseSchema.kt:6`，当前 25）。
- [ ] 新增并注册 `MIGRATION_25_26`（`MedLogDatabase.kt`）。
- [ ] `AppModule.provideDatabase.addMigrations(...)` 追加 `MIGRATION_25_26`（`AppModule.kt:118` 处）。
- [ ] 导出 **`26.json`**（`core/database/schemas/com.driezy.medlog.data.local.MedLogDatabase/`，当前止于 `25.json`）。
- [ ] **每一处硬编码迁移列表**都补 `MIGRATION_25_26`（当前含 `MIGRATION_24_25` 的**测试列表共 13 处**，分 5 个文件）：
  - `MedLogDatabaseMigrationTest.kt` — **5 处**（`:55`、`:108`、`:154`、`:255`、`:347`）。
  - `CareEventMigrationTest.kt` — 2 处（`:46`、`:106`）。
  - `CarePeopleMigrationTest.kt` — 2 处（`:48`、`:137`）。
  - `CareNoteMigrationTest.kt` — 2 处（`:49`、`:156`）。
  - `CareTodoMigrationTest.kt` — 2 处（`:54`、`:123`）。
  - （`CareRecipientMigrationTest`/`CareTaskMigrationTest` 只迁到固定旧版本，**不含** `MIGRATION_24_25`，**无需**改。）
- [ ] 版本断言：`RoomModelContractTest.kt:34`（`assertEquals(25, …)` → **26**）。
- [ ] `BackupCompatibilityPolicyTest.kt:24`（`assertEquals(25, DatabaseSchema.VERSION)` → **26**）并补 `assertTrue(canRestore(25))`。
- [ ] 新增 **`MedicationStatusMigrationTest`**（照 `CareEventMigrationTest` 模板）：**种一个带数据的 v25 库 → 迁移 → 断言**：旧数据零丢失（药/日志/计划快照/成员等仍在）、`medication_status_events` 存在且可读写、两条索引存在、删除药级联删事件、`isArchived` 派生回落正确。
- [ ] 四套 locale 文案（`values`/`-en`/`-ja`/`-ko` 的 `medication_flow_strings.xml`）。

**备份/恢复后果**：`BackupCompatibilityPolicy.canRestore` 上界跟随 `VERSION`（`BackupRestoreUseCase.kt:83-88`）→ 恢复的 **v25 备份自动放行**，首次打开依次迁移到 26。既有约束不变：**旧版本 App 打不开新版本备份**（非本特性缺陷）。

---

## 13. 影响面（文件级）

**新增**
- `core/database/.../model/MedicationStatusEvent.kt`（+ `MedicationStatusKind` 常量，含 `VOID`）
- `core/database/.../local/MedicationStatusEventDao.kt`
- `app/.../data/repository/MedicationStatusRepository.kt` + `Impl`（成员作用域）
- `app/.../feature/medications/application/SetMedicationStatusUseCase.kt`（单药 + 批量，含 diff/CHECK 逻辑、`derivePaused`/`pausedAt` 纯函数）
- `app/.../feature/medications/application/CorrectMedicationStatusEventUseCase.kt`（更正 C）
- `app/.../capability/reminders/`：`MedLogAlarmReceiver.handleMedication` 顶部**发送前再校验守卫**（§7）
- 每日核对用例 `ReconcileMedicationStatusUseCase`（§8）
- 迁移测试 `MedicationStatusMigrationTest`、更正测试 `MedicationStatusCorrectionTest`
- `docs/medication-status-spec.md`（本文件）

**修改**
- `DatabaseSchema.kt`（25→26）、`MedLogDatabase.kt`（注册实体 + `MIGRATION_25_26`）、`AppModule.kt`（DI）
- `MedicationRepositoryImpl.setArchived`（改为 append 事件 + 直写缓存列，不再 `updatePlan(copy(isArchived=…))`）
- `FuturePlanCalculator`（增加 `statusEvents` 入参 + `statusActiveAt` 过滤；无事件回落段 `isArchived`）
- `MedicationAdherenceCalculator`（暂停期呈现为「暂停」而非漏服）+ 历史/日历屏「已调整」标记
- `AndroidReminderReconciler`（排程判据可选改为按派生状态）
- `MedicationDaoTransactionTest`（更新「状态改版不新建计划段」断言）
- 迁移/契约测试（§12 清单）

---

## 14. 任务分解（分阶段，每步可独立验证）

**Phase 1（首期）**
- [ ] T1 实体 + DAO + 仓储 + v25→v26 迁移（迁移测试：种 v25 有数据 → 零丢失、级联、`isArchived` 派生回落）
- [ ] T2 `pausedAt`/`derivePaused` 纯函数 + `SetMedicationStatusUseCase` 的 CHANGE/CHECK diff（单测：重复停用幂等、同日后停恢复、倒填、批量原子性）
- [ ] T3 正交状态层接入 `FuturePlanCalculator`（`statusEvents` + `statusActiveAt`）；回归：`FuturePlanCalculatorTest`、`MedicationAdherenceTest`、`MedicationDaoTransactionTest`
- [ ] T4 依从率/历史屏：暂停区间呈现为「暂停」，倒填不再 MISSED（单测 + 契约测试）
- [ ] T5 **发送前再校验守卫**（§7）+ 测试（**发布闸门**：未合入 → 首期不接受未来生效日，D7）
- [ ] T6 每日核对 + `lastReconciledAtMs`（§8；单测：不写事件、不算服药、幂等）
- [ ] T7 误记更正 = 取代事件 + 重算（§9；`MedicationStatusCorrectionTest`）
- [ ] T8 我的药品多选 + 批量动作栏 + 详情双入口 + 变更记录/「已调整」UI（Compose 契约测试；不新增底部 Tab）
- [ ] T9 真机全链路：批量停用（倒填）→ 首页/小组件不再出现 → 依从率不再漏服 → 恢复 → 从恢复日重排；（若含未来生效）到期不误提醒

**Phase 2（真实待定，不阻塞首期）**
- [ ] 未来生效日的**精确定时**（若 Phase 1 走「拒绝未来生效日」分支）
- [ ] 剂量调整自身的生效日期（计划段的生效日语义）

---

## 15. 非目标（本期明确不做）

- 不新增第 6 个底部 Tab（`NavigationHierarchyTest` 钉死 5 个顶层目的地）。
- **不做状态事件的就地编辑/物理删除**（append-only 审计链；改错=追加取代事件）。**注：更正能力本身在首期**（§9），「不做」的只是「就地编辑/删除」。
- **不采纳第 1 稿 D3/E8 的「计划段按生效日有序插入/裁剪」**（§2.B 已证不可行）。
- 不改 `updatePlan` 签名；pause/resume 不写 `medication_plan_revisions`。
- 不做用药医嘱/剂量与状态的联动（医生处方文本解析）。
- 不做跨设备同步 / 云端状态历史。
- 不改 `MedicationLog` 的既有撤销=物理删除语义；不改打卡主流程。
- 不迁移/清除既有的 `isArchived` 历史（零回填）。
- **核对不产生状态事件、不作为服药证据**；`lastReconciledAtMs` 不进依从率。

---

## 16. 验收

1. 批量停用（多选）+ 生效日 + 原因 → **一个事务**写入，全部选中药立即从今日计划/小组件消失，提醒取消。
2. **生效日 ≠ 录入时间**：倒填（生效昨天、今天录入）后，昨天/今天**不再**被算成漏服；历史屏显示暂停区间 + 「已调整」。
3. 批量恢复（可倒填）→ 从恢复日重新排期；当日/未来计划恢复；提醒重排。
4. **核对不产生事件**：对当前状态点「继续服用/继续停用」→ `medication_status_events` **行数不变**；**每日核对**后 `medication_status_events` **行数不变**（只写 `lastReconciledAtMs`）。
5. **历史永不丢**：任何停/恢复/更正/核对操作后，`medication_logs` 行数与内容不变。
6. **多次循环**：停→恢复→再停→再恢复可表达且投影正确。
7. **未来生效**：生效日前照常服药/提醒；**生效时刻后触发三类提醒一律被 §7 守卫驳回**（测试）；到期后缓存由 §8 收敛。
8. **两档语义**：`临时停用` 与 `归档` 共用历史与派生，文案/入口可分。
9. **误记更正**：错生效日/错 kind/重复均可更正，重算历史投影，旧链留存可审计，日志不变。
10. **迁移**：v25 升级 26 零丢失；恢复的 v25 备份正常打开；§12 全部测试通过。
11. **零回归**：既有 5 个顶层目的地不变；用药打卡/跳过/撤销、照护事项、提醒时刻与基线一致。

---

## 17. 工具脚本迁移（决策 5）

**引用核查（先确认再移动）**：
- `tools/dump_ui.py`、`tools/find_node.py`、`tools/pull_db.sh` 在本仓**任何 docs / 脚本 / skill 中均无引用**（全库 `grep 'dump_ui|find_node|pull_db|tools/'` 仅命中无关的 `seven_segment_ocr/`（PaddleOCR 自带 `tools/`），**与这三个脚本无关**）。
- 三者**未被 git 跟踪**（`git ls-files tools/` 为空；`git status` 显示 `?? tools/`），故**无需删除任何已跟踪文件**。

**动作（已完成）**：将三个脚本**原样移动**到 `anshin-android-dev` skill 的资源目录，**未删除、未覆盖**（目录此前不存在）：
- `~/work/anshin/tools/dump_ui.py` → `~/.hermes/skills/software-development/anshin-android-dev/scripts/dump_ui.py`
- `~/work/anshin/tools/find_node.py` → `~/.hermes/skills/software-development/anshin-android-dev/scripts/find_node.py`
- `~/work/anshin/tools/pull_db.sh` → `~/.hermes/skills/software-development/anshin-android-dev/scripts/pull_db.sh`

**结果**：仓库内 `tools/` 已清空（`git status` 不再出现 `?? tools/`）；三个脚本现归属 `anshin-android-dev` skill（设备 UI dump / 节点定位 / 拉取 live DB+闹钟登记表），供后续真机 QA 复用。

---

## 18. 自洽性检查（决策密集，逐对核验）

| # | 检查的对 | 证据 | 裁决 |
|---|---|---|---|
| 1 | **`isArchived` 缓存 vs 规范里每一处读该标志处** | §1.3 全表；A 类（`AndroidReminderReconciler.kt:40/88`、`FuturePlanCalculator.kt:50`）已改为键在生效日期；B/C 类仅作缓存消费者；§7 守卫再兜底 | **一致**（缓存从属；提醒真相=事件） |
| 2 | **决策 2 的「重算历史」vs 决策 1 的「append-only 历史」** | 重算只**读**事件链并派生应服/依从率，**不改**事件行、**不删**日志（§5/§6/§9） | **一致** |
| 3 | **决策 3 的「证明/推迟」vs 任何残留「best-effort 就够了」措辞** | 第 1 稿 §1.4/§3.3/E5/非目标里的「best-effort 到期求值」措辞**已删除/替换**为「发送前守卫 + 发布闸门」（§7、D7、E5、§15） | **已修正**（无残留 best-effort 兜底未来生效） |
| 4 | **决策 4「核对≠服药」vs 依从率章节** | `lastReconciledAtMs` **非** `medication_logs` 行、**不被** `MedicationAdherenceCalculator` 读取；§8/§15/§16.4 明写 | **一致** |
| 5 | **更正 C 的变更模型 vs 更正 A「事件 append-only」** | C 采纳**追加取代事件**（不就地编辑、不物理删除），`supersedesId` 只增加行（§2.C/§4/§9） | **一致**（无 mutation） |
| 6 | **第 1 稿 D3「计划段边界=生效日」vs 更正 B（计划链不倒填）** | 第 1 稿 D3 与 B 直接冲突：B 已证倒填写链会静默损坏。**已用 D3'（正交状态层）替换**（§2.B、§3 D3'、§12） | **已修正**（冲突消除） |
| 7 | **第 1 稿 E8「有序插入/裁剪计划段」vs 更正 B 裁决** | E8 主张改为有序插入/裁剪；B 证其无入口/无测试/污染共享读数。**E8 已重写**为「状态层正交，计划链不动」（§11 E8） | **已修正**（方案废弃） |
| 8 | **「append-only 审计链」vs「误记更正」** | 二者张力由「取代事件」化解：更正=追加，审计链完整（§2.C/§9） | **一致** |
| 9 | **决策 5 移动脚本 vs「只改本文件」** | 移动的是**未被 git 跟踪**的 `tools/` 脚本（非业务代码、非提交）；仓库唯一跟踪变更 = 本文件（§17、§19 git status） | **一致** |
| 10 | **`updatePlan` 不变 vs 倒填需要边界** | 倒填边界改由**事件层**承载（`effectiveAtMs`），计划链继续只为剂量调整服务——二者不再竞争（§2.B/§3 D3'） | **一致** |

---

## 19. 本轮变更与验证

- **只改本文件**：`git status --short` 仅 `M docs/medication-status-spec.md`（`tools/` 已按 §17 移出，仓库内无业务代码改动、无提交、无推送）。
- 行数：第 1 稿 282 行 → 本稿 **503 行**（19 节）。
- 若后续决定走「首期拒绝未来生效日」（D7 分支），则 §7 守卫降级为 Phase 2 交付，其余不变。
