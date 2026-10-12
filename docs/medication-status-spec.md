# 药品停用 / 恢复（状态事件 + 生效日期）—— SDD 规格（第 5 稿·§0.2 同步修订）

状态：**已冻结（第 6 稿）；实现按 §14 的 T1–T10 推进。** 第 5 稿把正文 / 迁移 / 测试 / 验收统一到 §0.2（§0.3）；第 6 稿只做一次**定向复核**：以真实代码裁决「`isArchived` 兼容缓存与权威状态事件不一致时，能否产生错误用药提醒」，并把**发送前派生校验**从 Phase 2 计划**提升为首期交付**（更正 N，见 §0.4、§7、§13、§14 T10、§16.20）。本稿**只改本文件、不改业务代码**；冻结即提交并推送（§19）。
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
| 更正 F | 首期**只接受今天与过去的生效日**；~~发送前守卫降级为 Phase 2 计划~~ → **第 6 稿更正 N 已把守卫提升为首期**；首期仍覆盖**既有停用行为** | §3 D11、§7、§14、§16 |
| 更正 I（替换旧 G） | 遗留基线 = **唯一**机制：迁移补种一条**不可变** `LEGACY_BASELINE` 事件（生效日显式 NULL）；**删除**「零事件 ⇒ 回落 `isArchived` 缓存」机制；首个 `RESUME` **不得**把 `D` 之前重解释为一直在服（迁移前为 `UNKNOWN`，非暂停） | §5 R7、§12.1、§9.4 |
| 更正 J | 投影与依从率统一到**同一 dose 级谓词**（排除**剂次**而非整日） | §5.3、§6.1/§6.2、§9.7 |
| 更正 K | 多层 × 重复取代的**组合**以单一全序 + **性质测试**证明合流 | §5.1 R4/R5、§9.1 |
| 更正 L | 变更检测比较**活状态 kind**（非暂停布尔）：`PAUSE→DISCONTINUE` 是事件 | §3 D6、§5.4、§9.6 |
| 更正 M | 迁移前未知区间**不编造日期**、**不称暂停**（状态 = `UNKNOWN`）；统计按剂次排除且单列未知计数 | §5 R7、§6.3、§11 E20、§9.8 |
| 更正 H | 历史/依从率**显示暂停区间**且明示**暂停日不计漏服**；真实剂量记录保持可见不动 | §6、§10、§16 |
| 更正 N（第 6 稿） | **发送前派生校验提升为首期交付**：三类用药闹钟在真正弹通知前再读一次派生状态，非 `ACTIVE` 即抑制。缓存陈旧与 reconcile 漏跑都不能击穿它——它是缓存不一致的**唯一**兜底，故不能只停在 Phase 2 | §0.4、§1.3、§1.4、§2.A、§3 D7、§7、§11 E5、§13、§14 T10、§15、§16.20、§18 |

### 0.1 相对第 2 稿的实质变更（本轮）

1. **§5 重写取代链判定**（更正 D）。旧谓词 `supersedesId == null || !isSuperseded(...)` **有缺陷**：一个**自身没有 `supersedesId`、但已被别人取代**的行会被重新纳入派生，于是**已作废的事件仍可能生效**。改为「**整链一次算完**」的确定性判定，并给出：多层取代、VOID、重复取代、冲突取代、环防止五类规则与测试（见 §5、§9.1）。
2. **拆分两种事实**（更正 E）：`lastAutoReconciledAtMs`（系统写）与 `lastUserCheckedDate`（**仅用户主动核对**写）。自动核对通道**不得**移动用户核对日期（§8、§9.3）。
3. **首期不接受未来生效日**（更正 F）：生效日必须 ≤ 今天（按成员提醒时区的本地日界）；UI 禁止选择未来日期、校验拒绝未来日期。（第 5 稿曾把**发送前再校验守卫**整段降级为 Phase 2 计划；**第 6 稿更正 N 已把它提升为首期交付**，见 §0.4 / §7。）首期仍**必须覆盖既有停用行为**（§16.12）。
4. **遗留兼容**（更正 G→I）：`isArchived=true` 的遗留药**不得**因「事件列表为空」被当作「未暂停」而静默恢复。（第 5 稿：**唯一**机制 = 迁移补种**不可变 `LEGACY_BASELINE` 事件**；「零事件回落缓存」机制**已删除**，见 §0.3.1、§12.1。）
5. **历史/依从率显示暂停区间**（更正 H）：暂停窗口在历史/日历与依从率两处均**显式呈现为暂停**，且明示**暂停日不计漏服**（§6、§16.2/§16.4）。

### 0.2 剩余五项冻结前修订（本轮）

用户认可正交状态层、append-only 更正、首期无未来生效日，但指出**五项实质问题**；本轮逐项落定（每项 = 最小修订 + 反例 + 验收测试）：

1. **遗留基线必须不可变持久化**（更正 I）：旧稿由**可变缓存** `isArchived` 现算 `legacyArchived`，首个 `RESUME` 后缓存被改写、且回落以「事件为空」为键 ⇒ 迁移前的「暂停」不可恢复，首个 `RESUME` 会把 `D` 之前的日子**重解释为一直在服**。改为**迁移时补种一条 `LEGACY_BASELINE` 基线事件（生效日显式 NULL = 未知）**（§12.1），派生不再读缓存做遗留回落（§5 R7）。
2. **投影与依从率统一到达 dose 级谓词**（更正 J）：投影按 occurrence 的 `scheduledAt` 判定（dose 级），依从率却按 `dayStart` 排除整日（day 级）⇒ 一日两剂、其一落在暂停内时二者矛盾。改为**同一个 `doseActiveAt(events, scheduledInstant)` 谓词**驱动两者（§5.3、§6.2），排除粒度 = 被暂停的**剂次**而非整日。
3. **多层 × 重复取代的组合必须证明合流**（更正 K）：旧稿只用两个手写分叉验证。改为**单一全序 + 性质测试**（随机取代图：插入顺序无关、多层链与重复声称任意组合结果唯一）（§5.1 R4/R5、§9.1）。
4. **变更检测从「暂停布尔」加宽到「活状态 kind」**（更正 L）：`PAUSE→DISCONTINUE` 布尔不变但语义变了，旧稿会当 no-op 吞掉。改为比较**活状态 kind** 并枚举转换矩阵（§3 D6、§5.4、§9.6）。
5. **迁移前未知历史必须诚实呈现**（更正 M，第 5 稿细化为 `UNKNOWN` 边界）：遗留归档药迁移前**没有真实暂停日**，UI 不得编造；**迁移前一律标「状态未知（早于本次迁移）」、不得标「暂停」**（状态 = `UNKNOWN`，既非暂停也非活跃）；统计上**按剂次排除且单列未知计数**、绝不合成 `MISSED`（§5 R7、§6.3、§11 E20、§9.8）。

### 0.3 第 5 稿：§0.2 落定后的全篇同步清理

第 4 稿只在 §0.2 落定了新方向，正文 / 迁移 / 测试仍残留旧规则；本轮把 §0.2 之外的一切清理成与 §0.2 一致（**只改本文件**）。四处同步：

1. **只保留一种遗留机制**：保留「迁移补种 `LEGACY_BASELINE` 事件」，**删除**「零事件 ⇒ 回落 `isArchived` 缓存」这一旧机制（含它在 D2 / D4 / §7 的实现性文字）。一句话理由：`isArchived` 是会被首个新事件改写的**可变缓存**，以它为遗留真相来源即**第二真相来源**；append-only 的事件行**结构性不可变**、与全部事件同谓词（D12）。
2. **dose 级谓词是投影 / 依从率 / 历史 UI 三面的唯一判据**：三面**只**经共享 helper 的 `doseActiveAt`（= `statusAt == ACTIVE`）判定「是否应服」，删除所有「整日 / 按天扣除分母 / 暂停 N 天」的 day 级残留（一律改按**剂次**）。
3. **迁移前是 `UNKNOWN`，不是 `PAUSED`**：迁移只确知*迁移时刻*已归档。基线把 `t < 迁移时刻` 判 **`UNKNOWN`**（既非暂停也非活跃），`t ≥ 迁移时刻` 才判 `PAUSED`；迁移前的剂次**不计入依从率分母、单列「状态未知」、绝不合成 `MISSED`**，任何界面**不打印**被算出的暂停起点（§5 R7、§6.3、§9.8）。
4. **迁移 SQL / 测试 / 验收 / 正文四者对账**：§12 精确列出表、列、可空性、索引、kind 取值与迁移**写/不写**什么；§9.4 / §9.8 测试、§16 验收逐条与之对齐（见 §18 表）。

### 0.4 第 6 稿：缓存 vs 事件的定向复核（裁决 + 唯一实质修订）

**复核问题**：`isArchived` 兼容缓存与权威的状态事件不一致时，能否产生**错误用药提醒**？

**代码事实**（本轮已逐处核对，见 §1.3 / §1.4）：
- **排程端**的缓存读点是 `AndroidReminderReconciler.kt:40`（单药）与 `:88`（全量）的 `!medication.isArchived && !medication.isPRN`；本稿把这两处改为读 `deriveCurrentStatus(events, now)`（§1.3 A 类），故缓存**不再参与排程决策**。
- **取消是无条件的、且在排程判定之前**：`reconcileMedication` 先 `cancelAllAlarms`（`AndroidReminderReconciler.kt:34`）再 `cancelAllReminderNotifications`（`:39`），二者**都不读缓存**；`AlarmScheduler.cancelAllAlarms`（`AlarmScheduler.kt:237-259`）真的 `AlarmManager.cancel` + `PendingIntent.cancel` 并连带撤销提前预告 / 漏服再提醒闹钟；`cancelAllReminderNotifications`（`NotificationHelper.kt:411-415`）真的撤**已上屏**通知。
- **发送端完全没有状态校验**：`MedLogAlarmReceiver.handleMedication`（`MedLogAlarmReceiver.kt:91-222`）每个分支只 `getMedicationById`（`:104/116/131/148/187`），正式提醒分支还**续排下一次**（`scheduleNextReminderAfterDose`，`:199`）与漏服再提醒（`:209`）⇒ 一旦取消漏跑，它会**自我延续**。

**裁决（逐条）**：
1. **正常写入路径不产生分歧**：事件 `INSERT` 与缓存 `UPDATE` 在**一个事务**内（§11 E10、§2.A.3），提交后缓存恒等于派生（§2.A 不变量测试）。
2. **真正的分歧来源只有两个**：
   - ① **提交成功但 `reconcile` 未跑 / 抛错 / 被 Doze 推迟**：`MedicationDetailViewModel.kt:139-147` 的 `archiveMedication(id)` 与 `reconcileReminders.medication(...)` 是**两步**（现代码同样如此）。此时缓存是**对的**，漏的是**取消**——错误提醒与缓存无关。
   - ② **缓存列被外部 / 遗留直写且无对应事件**：派生判 `ACTIVE`，排程会**为已归档药重新排提醒**；而 §8.1 的自动核对把缓存收敛成派生，**恰好把归档抹掉**，不会自愈（§19③ 的旧表述在此方向上**方向相反**）。
3. **结论**：缓存不一致**能**导致错误提醒（情形 ② 直接导致；情形 ① 与之无关但同样错误提醒）。第 5 稿把唯一的兜底——§7 的**发送前派生校验**——整体放进 Phase 2，等于首期**根本没有兜底**（§1.3 末句自己承认「缓存陈旧 / 错误的最终兜底是 §7 的接收端守卫」）。**故本轮唯一实质修订 = 更正 N：把该守卫提升为首期交付。**
4. **首期「无未来生效日」的约束（D11）不变**；未来生效日仍属 Phase 2，其**发布闸门**仍是该守卫 + 其测试（§7）。

**交接窗（提升前，首期）**：从停用生效（= 记录时刻，D11）到**下一次成功 reconcile**。可用的收口点 = 用户动作内即时 reconcile、持久重试（`WorkManagerReminderReconciliationQueue` 一次性 work，可被 Doze 推迟）、应用启动（`MedLogApplication.kt:27-29` 入队一次性 `ReminderReconciliationWorker` → `reconcileAll`）、`MedLogBootReceiver`（开机 / 改时 / 改时区 / 精确闹钟权限，`:23-29`）、以及 §8.1 新增的 15 分钟自动核对。**最坏情形**（用户动作 reconcile 崩溃 + 未重开 App + 后台 work 被 Doze 推迟）可跨数小时、直至下次启动或开机。**提升后，该窗口对「闹钟 / 通知」面归零**（变更后已上屏的旧通知仍由下一次 reconcile/prune 撤走，见 §7 残余）。

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

**现状缺陷（本稿修订）**：A 类三个读者**当前**直接读 `isArchived` 缓存。改为：**A 类一律改读派生判定 `deriveCurrentStatus(events, now)`（§5）**；缓存 `isArchived` **不再是任何遗留回落来源**（更正 I：遗留基线是事件表里的一条 `LEGACY_BASELINE` 行）。因此布尔与事件一旦不一致，**排程与投影**以**派生值**为准。
**缓存不一致的两个真实来源（第 6 稿定向复核，§0.4）**：① 写入提交成功但 `reconcile` 未跑（`MedicationDetailViewModel.kt:139-147` 的 `archiveMedication` 与 `reconcileReminders` 是两步）——漏的是**取消**；② 缓存列被外部 / 遗留**直写**且无对应事件——派生判活跃，会**为已归档药重排提醒**，且自动核对把缓存收敛成派生、**不会自愈**。二者**都**由 §7 的**发送前派生校验**兜底，该守卫**本轮提升为首期交付**（更正 N）。「排程端读派生 + 缓存与事件同事务写入」只把**正常路径**的分歧窗口压到零，**不能**取代守卫（§0.4 裁决 3）。

### 1.4 提醒

| 情形 | 现状行为 | 位置 |
|---|---|---|
| 停用（当前已归档） | `cancelAllAlarms` + `cancelAllReminderNotifications`；因 `!medication.isArchived` 为假**不再排期** | `AndroidReminderReconciler.kt:31-44`（守卫在 `:40`） |
| 全量重排 | 仅 `!isArchived && !isPRN` 计入 `liveMedications` 并排期，其余只清理 | `AndroidReminderReconciler.kt:86-92` |
| 恢复 | `!isArchived` 通过 → `schedule(medication)` | `AndroidReminderReconciler.kt:131-143` |
| 闹钟触发后的行为 | `MedLogAlarmReceiver.handleMedication` **只**取 `medicationRepo.getMedicationById(medId)`，**从不检查** `isArchived`/有效状态 | `MedLogAlarmReceiver.kt:91-222`（取药在 `:104/116/131/148/187`） |
| 三态提醒分派 | `onReceive` 的 `when(dispatch.kind)` 把 `MEDICATION_NORMAL/EARLY/FOLLOW_UP` **全部**路由到 `handleMedication` | `MedLogAlarmReceiver.kt:81-87` |
| 事务收口 | 状态写入经 `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` → 立即重排 | `app/.../capability/reminders/application/ReminderReconciliation.kt:14-21` |

**结论**：停用应**立即停闹钟与通知**；恢复应**从恢复日重排**。**任何状态写入（首期 = 生效日 ≤ 今天）都必须触发一次 reconcile，并同步写缓存。** 但状态写入与 `reconcile` 是**两步**，且已登记的闹钟在漏取消后会**自我延续**（`MedLogAlarmReceiver.kt:199/209`），故**发送前派生校验守卫提升为首期交付**（更正 N，§7）——它是「闹钟已排 / 取消漏跑 / 缓存被直写」这一整类的收口，而不仅是为配合未来生效日。

### 1.5 剂量日志（要求 5）

- 归档路径**只写** `medications` 与 `medication_plan_revisions`（`MedicationRepositoryImpl.kt:100-105` → `MedicationDao.kt:74-85`），**从不触碰** `medication_logs`。`MedicationLogDao` 的删除接口只被「撤销打卡」类用例调用，**不在**状态路径上。
- **结论**：**剂量日志从不被状态变更触碰**——要求 5 对现状成立；新路径必须**保持**该性质（状态写入 = 只 append 事件 + 只改当前计划边界/派生缓存）。

---

## 2. 三处架构更正（A / B / C）

### 2.A `isArchived` 只允许是「从属兼容缓存」

**规则（可测试）**：`medications.isArchived` 的语义**唯一**定义为
`deriveCurrentStatus(events, now)`（§5），其中 *events* 是 `medication_status_events` 的全部事件（含被取代者，判定在派生内进行，见 §5 R1–R8），**并含迁移补种的 `LEGACY_BASELINE` 基线行**；*now* = `clock.millis()`。**派生不再读 `isArchived` 缓存、也不读任何 `legacyArchived` 快照**（更正 I）：遗留归档药的基线是**事件表里的一条不可变行**，与写入顺序、缓存新旧都无关。
**它永远不是提醒决策的真相来源**：排程与投影必须走派生判定（§5）；缓存列只是为了让 §1.3 的 B/C 两类既有消费者**零改动继续工作**（含 SQL `isArchived=0/1` 查询）；**它不再承担任何遗留判定**（更正 I：遗留基线是事件表里的 `LEGACY_BASELINE` 行）。

**边界如何从事件派生**：见 §5。历史任一时刻的状态由此**现算**——**与事件写入顺序无关**。

**缓存陈旧 / 与事件不一致时**：
1. **派生值赢**。任何读者若发现缓存与 `deriveCurrentStatus` 不一致，以派生值为准并由每日核对**重写缓存**（§8 自动通道）。
2. **提醒的最终兜底**是 §7 的**发送前再校验守卫**，**本轮提升为首期交付**（更正 N）：闹钟真正弹通知前再算一次派生判定、非 `ACTIVE` 即抑制。首期**同样交付**该守卫：写事件时**同步写缓存**并 reconcile 只保证**正常路径**一致；一旦 reconcile 漏跑或缓存被直写（§0.4），唯一防线就是它。
3. 倒填/首期生效不通过 `updatePlan` 改计划链（§2.B），只 append 事件 + 直写缓存列（`MedicationDao.archiveMedication/unarchiveMedication`，`:96-100`）。

**测试性**：
- 不变量测试：每次状态写入后、以及每次自动核对后，断言 `medications.isArchived == deriveCurrentStatus(events, now)`（property/事务测试）。
- 对抗测试：**人为把缓存住成 `isArchived=false`，而事件表给出「生效日≤now 的 PAUSE」** → 断言 (a) A 类读者（用派生判定）**不排期**该药；(b) 每日核对把缓存纠正回 true。此测试即「缓存不得成为提醒真相来源」的可执行证明。
- **发送端对抗测试（更正 N，首期交付；§16.20）**：在 (a) 的基础上**已经存在**三类用药闹钟（模拟「排完之后才停用」或「reconcile 漏跑」），再**依次投递** `MEDICATION_NORMAL` / `MEDICATION_EARLY` / `MEDICATION_FOLLOW_UP` 三种 intent 到 `MedLogAlarmReceiver.onReceive` → 断言**三种都被抑制**：不发通知、不续排下一次（`scheduleNextReminderAfterDose` / `scheduleFollowUpAlarm` 均未调用）；**同时**断言 `ACTION_TAKEN` / `ACTION_SKIP` 仍照常写日志（真实服药记录不得被拦）。这证明「缓存不一致 ⇒ 无错误提醒」在**发送端**也成立，而不仅在派生层。
- **遗留对抗测试**：迁移后遗留归档药事件表**非空**（恰一条 `LEGACY_BASELINE`，生效日 NULL）→ 断言派生值 = true；把缓存**人为置为 false** → 派生仍为 true（基线行压过缓存）。迁移**结构性保证**不存在「零事件遗留归档药」（§9.4）。

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

**采纳 (ii) 的理由**：状态层是**纯函数、与写入顺序无关**——倒填/中段恢复天然正确，**既不改计划链、也不改 `updatePlan` 签名**，回归面最小；且它同时满足决策 2（重算历史应服与依从率）与更正 C（取代事件只改状态层）。实现上：`FuturePlanCalculator` 增加 `statusEvents` 入参，对每个 occurrence 用**共享的** `doseActiveAt(events, occurrence.scheduledAt)` 过滤（见 §5.3）；历史药的归档判定由**状态层**给出：遗留归档药有一条 `LEGACY_BASELINE` 基线行 ⇒ `doseActiveAt=false` ⇒ 排除，与既有「段 `isArchived` 跳过」（`FuturePlanCalculator.kt:50`）**同向**（该段回落保留为兼容，但不再是唯一依据）。`medication_plan_revisions` 的既有 `isArchived` 快照列**保持不变但降级为兼容缓存**。

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

**D2 — `medications.isArchived` 保留，但降级为「派生的当前状态缓存」，不移除。**（更正 A、更正 I → §2.A）
读者太多（§1.3 A/B/C 三类），移除会造成大面积回归面。保留一个**由事件派生的**列，语义 = 「**按 §5 派生**的当前是否停用」。派生函数见 §5；**它不再承担任何遗留回落**（遗留归档药由迁移补种的 `LEGACY_BASELINE` 事件行承载，更正 I）。

**D3（替换第 1 稿 D3）— 计划链不变；倒填暂停由「正交状态层」承载，不写版本链。**（更正 B → §2.B）
pause/resume **不**改 `medication_plan_revisions`、**不**改 `updatePlan` 签名；只 append 事件 + 直写派生缓存列。投影 = 计划段投影 ∧ 该时刻未暂停。现有计划链只继续承担**剂量/时刻调整**（边界=记录时刻，维持现状）。

**D4 — 计划段快照不新增「暂停窗口」字段。** 快照 `isArchived` 列保留为**兼容缓存**（非遗留回落）；新增能力全部走事件层。

**D5 — 「归档」与「临时停用」= 同一事件引擎的两个 kind（`DISCONTINUE` / `PAUSE`），不建第二套关闭语义。**（决策 1 → §10）

**D6 — 三条写入路径严格区分：CHANGE（用户变更）、CHECK（用户核对）、RECONCILE（系统自动）。**（更正 E、更正 L → §8、§5.4）
- **CHANGE**（用户改状态）：比较**活状态 kind**（非暂停布尔）：`desiredKind != liveStatusKind(events, now)` → **append 事件** + 直写缓存 + reconcile（更正 L）。例如 `PAUSE→DISCONTINUE` 是**真实变更**，必须写事件（§5.4 转换矩阵）。
- **CHECK**（用户主动核对医嘱，`继续服用/继续停用` 的确认）：`desiredKind == liveStatusKind(events, now)`（同 kind 重复）→ **不写事件**；**只**写**用户事实** `lastUserCheckedDate`（§8）。**不写缓存列**、不 reconcile（状态未变）。
- **RECONCILE**（系统自动，每日/启动/小组件 tick）：**不写事件**；只写缓存列 + **系统事实** `lastAutoReconciledAtMs`（§8）。
- **三者都绝不写 `medication_logs`**；CHECK/RECONCILE **都不得**被当作服药证据。

**D7 — 未来生效的暂停移出首期；发送前再校验守卫反而提升为首期。**（决策 3、更正 F、**更正 N** → §0.4、§7、D11）
首期**不接受**未来生效日（D11）；但「发送前再校验守卫」是**缓存不一致 / 取消漏跑**这一整类的唯一兜底，故**首期交付**（更正 N）。未来生效日仍属 Phase 2，其**发布闸门**同样是该守卫 + 其测试。

**D8 — 迁移 v25 → v26 纯新增 + 备份/恢复上界随 `VERSION` 自动放行。** 见 §12。

**D9 — 两种事实分离：`lastAutoReconciledAtMs`（系统）/ `lastUserCheckedDate`（用户）。**（决策 4、更正 E → §8）核对不产生状态事件、不作为服药证据。

**D10 — 误记更正 = 追加取代事件 + 重算。**（更正 C → §2.C、§9.2）

**D11 — 首期生效日约束：只接受今天与过去（更正 F）。** 校验与 UI 双重限制：
- **校验**：`localDay(effectiveAtMs, 成员提醒时区) <= localDay(now, 成员提醒时区)`，否则拒绝（错误码 `FutureEffectiveDateNotAllowed`）。
- **UI**：批量/详情日期选择器 `maximumDate = 今天`，未来日期不可选。
- 理由：若允许未来生效日，生效时刻前若 reconcile 未跑到即可能误提醒（决策 3 明令「要么证明不误提醒，要么首期不做」，本稿取后者）。**注（第 6 稿）**：守卫**已首期交付**（更正 N），但它只保证「闹钟弹通知前」不误报，**不**替代「生效日必须 ≤ 今天」这条更强的约束（无未来日期 = 状态在用户动作当刻即已确定，无需等待任何调度）。

**D12 — 遗留基线 = 迁移补种的一条不可变基线事件（更正 I，替换更正 G → §5 R7、§12.1）。** v25→v26 迁移为**每个 `isArchived=1` 的遗留药插入恰好一条 `LEGACY_BASELINE` 行**（`effectiveAtMs = NULL` = 显式未知；`recordedAtMs = 迁移时刻`）。基线是 append-only 事件表里的普通行 ⇒ **结构性不可变**（无 UPDATE/DELETE 入口），**不再以可变缓存 `isArchived` 兜底**。派生改为 `deriveCurrentStatus(events, now)`（无 `legacyArchived` 参数）；「零事件遗留药」特例**消失**。

**D13 — 历史/依从率显式呈现暂停区间，暂停日不计漏服（更正 H → §6、§10）。**

---

## 4. 数据模型（D1/D10 落地）

```kotlin
// 状态种类是代码常量（对齐 care_event_logs 的 kind 存法；不用 enum Converter）
object MedicationStatusKind {
    const val LEGACY_BASELINE = "LEGACY_BASELINE" // 迁移补种：遗留归档药的基线（生效日未知）；系统写，用户不可写
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
    val effectiveAtMs: Long?,         // 状态「实际生效」时刻（可倒填；首期不允许未来，见 D11）；**NULL ⇔ kind==LEGACY_BASELINE（生效日显式未知）**
    val recordedAtMs: Long,           // 录入时刻（与生效独立）
    val reason: String? = null,       // 可选原因/备注
    val supersedesId: Long? = null,   // 更正：指向被本事件取代的旧事件（同药、且 id 更小）；不建自引用 FK
)
```
- 复合索引 `(medicationId, effectiveAtMs)` 覆盖「取某药最新生效事件」；SQLite 中 NULL 排最前，基线（NULL）恒被任何带日期事件胜过（见 §5 R7）。
- **可空不变量**：`effectiveAtMs` 可空的**唯一**情形是 `LEGACY_BASELINE`；其余 kind 必非空（`insertEvent` 校验 `kind != LEGACY_BASELINE ⟹ effectiveAtMs != null`，违反抛 `InvalidStatusEvent`）。
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

// R2/R3/R5/R7：状态判定只看「活行」里的「状态承载 kind」——VOID 不承载；基线(LEGACY_BASELINE)只断言「迁移时刻已归档」
enum class Status { ACTIVE, PAUSED, UNKNOWN }

fun statusAt(E, t): Status {
    val latest = liveEvents(E)
        .filter { it.kind == PAUSE || it.kind == RESUME || it.kind == DISCONTINUE || it.kind == LEGACY_BASELINE }  // 排除 VOID
        .filter { it.effectiveAtMs == null || it.effectiveAtMs <= t }   // 基线 effectiveAtMs=NULL ⇒ 恒为候选（下界）
        .maxWithOrNull(compareBy({ it.effectiveAtMs ?: Long.MIN_VALUE }, { it.recordedAtMs }, { it.id }))
    return when (latest?.kind) {
        PAUSE, DISCONTINUE -> Status.PAUSED
        RESUME              -> Status.ACTIVE
        // 认知地平线：迁移时刻确知「已归档」⇒ 该时刻及其后为 PAUSED；其前状态不可考 ⇒ UNKNOWN（不编造暂停起点）
        LEGACY_BASELINE     -> if (t >= latest.recordedAtMs) Status.PAUSED else Status.UNKNOWN
        null                -> Status.ACTIVE   // R8：无任何事件（迁移后新建药在首个事件前）
    }
}

fun pausedAt(E, t): Boolean = statusAt(E, t) == Status.PAUSED
fun doseActiveAt(E, scheduledInstant): Boolean = statusAt(E, scheduledInstant) == Status.ACTIVE
fun unknownAt(E, t): Boolean = statusAt(E, t) == Status.UNKNOWN
```

**五类规则（逐条确定 + 测试）：**

- **R1 多层取代**：`A ← B ← C`（`B.supersedesId=A`，`C.supersedesId=B`）⇒ `dead={A,B}`，`live={C}`，**只有 C 生效**。祖先 A、B 均**永不生效**。（旧谓词会保留 A，见 §5.2 反例。）
- **R2 反例锚点**：任何行一旦被指向即死，**与它自己是否带 `supersedesId` 无关**。
- **R3 VOID 语义（确定）**：`VOID` 行 `v` 且 `v.supersedesId=X` ⇒ **X 死**；`v` 自身是活行但**不承载状态**（kind ∉ {PAUSE,RESUME,DISCONTINUE}，故在状态判定里被跳过）。因此状态**回退到「被作废行之前的那条最新活状态行」**；若不存在更早的活状态行 → **视为「无活状态行」**（`pausedAt=false`，即「未暂停」；遗留药必有基线行充当更早的活状态行，故**不会**落入此枝）。**明确：不是回到被作废行本身，也不是无条件回到「活跃」。**
- **R4 重复取代（平局打破）**：`≥2` 条活行指向**同一目标** ⇒ 目标死；竞争者按 `(recordedAtMs, id)` 取最大者为胜，**败者「遮蔽」（dead，派生忽略）但行保留**（append-only，可审计；不物理删除）。测试：`derive` 结果与「只存在胜者一条」时**逐字节一致**。
- **R5 冲突取代（确定性 or 结构禁止）——两者都做**：
  - **冲突形态 (a) 菱形/重复**（同目标多个取代者）→ 由 R4 的**全序**确定性解决。
  - **冲突形态 (b) 互指/成环**（`Y.supersedesId=X` 且 `X.supersedesId=Y`，或更长环）→ **结构上禁止**（R6）。
  - 任何「同一时刻按不同顺序应用取代会得出不同结果」的疑虑，由**单一 `max`（全序 `(effectiveAtMs, recordedAtMs, id)`）** 化解：派生是**顺序无关（confluent）**的，结果唯一。（一致性由 §9.1.8 的**随机取代图性质测试**证明：插入顺序无关 + 多层 × 重复组合结果唯一。）
- **R6 环防止（结构强制）**：append-only + 单调 `autoGenerate` id 下，新行 id 恒为当前最大，故**只能引用更旧的 id**。`insertEvent` 强制 `supersedesId < newId`（§4），违反即拒绝。**取代图恒为 DAG**，环不可构造。测试：① 尝试 `supersedesId >= 自身 id` 或自指 → 被拒绝；② property 测试：取代图可拓扑排序（无环）。
- **R7 遗留基线（更正 I，替换旧 R7）**：迁移为每个 `isArchived=1` 的遗留药补种**恰一条** `LEGACY_BASELINE` 基线事件（`effectiveAtMs=NULL`、`recordedAtMs=迁移时刻`、`supersedesId=NULL`）。语义 = 「迁移时刻该药已归档；**归档始于何时不可考**」。**不得**据此断言「迁移前的每一天都处于暂停」——迁移只确知*迁移时刻*的状态。故基线的 `recordedAtMs`（= 迁移时刻 `M`）就是**认知地平线**：`t ≥ M` ⇒ `PAUSED`（确知已归档且未变），`t < M` ⇒ `UNKNOWN`（**既非暂停也非活跃**）。派生统一为：

```
fun deriveCurrentStatus(events, now): Boolean = pausedAt(events, now)   // 不再需要 legacyArchived / cache 参数
```

  - **「零事件遗留药」不再存在**：迁移后每个遗留归档药事件表**非空**（恰一条基线行）；故 `statusAt` 对它恒含基线候选，**不可能被空列表翻活跃**。
  - **基线是下界**：`effectiveAtMs=NULL` 在比较键取 `Long.MIN_VALUE`，故**任何 `effectiveAtMs ≤ t` 的活状态行都压过基线**；仅当「`t` 之前没有任何带日期状态行」时基线才生效。
  - **认知地平线 = 迁移时刻 `M`**（`baseline.recordedAtMs`）：`t ≥ M` 基线判 `PAUSED`；`t < M` 判 `UNKNOWN`。**迁移前绝不判 `PAUSED`**（否则等于把「迁移时已归档」倒推成一段起点不存在的暂停史），**也绝不判 `ACTIVE`**（否则会把未知期当「一直在服」而计入漏服）。
  - **首个新事件**（用户对遗留药首次操作）：
    - 首个 `RESUME@D`（`D ≥ M`）⇒ `t < M` = `UNKNOWN`；`M ≤ t < D` = `PAUSED`（已证归档）；`t ≥ D` = `ACTIVE`（RESUME）。**`D` 之前绝不被重解释为「一直在服」**（否则假造漏服）；**`M` 之前也绝不被说成「暂停」**（那是编造暂停起点）。
    - 首个 `PAUSE@D`/`DISCONTINUE@D` ⇒ `t < M` = `UNKNOWN`、`t ≥ M` = `PAUSED`（基线与新行同向）。
  - **与「事件权威」的一致性**：基线与所有事件同处一张 append-only 表、同一谓词；**不存在第二真相来源**（既不是可变缓存，也不是派生内特判）。基线可被更正（如一条 `VOID` 指向它），更正后由活行重新决定。

- **R8 无事件情形（防御）**：`pausedAt(∅, t)=false`（活跃）。迁移后遗留归档药**不**落入此情形；**迁移后新建药**在首个事件前落此情形（活跃），符合预期。

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
- **投影（`FuturePlanCalculator`）**：对每个 occurrence 用**共享的 dose 级谓词** `doseActiveAt(events, occurrence.scheduledAt)`（= `statusAt(events, occurrence.scheduledAt) == ACTIVE`）过滤。**这是唯一谓词**：依从率（§6.2）与历史 UI（§6.1）调用**同一个** `doseActiveAt` ⇒ 三者不可能漂移（更正 J）。遗留归档药在迁移地平线之后由基线判 `PAUSED`、之前判 `UNKNOWN`，二者皆非 `ACTIVE` ⇒ 皆不投影，与既有「段 `isArchived` 跳过」（`FuturePlanCalculator.kt:50`）同向。
- **共享 helper（不可漂移）**：`object MedicationStatusDerivation { fun statusAt(events, t): Status; fun pausedAt(events, t): Boolean; fun unknownAt(events, t): Boolean; fun doseActiveAt(events, scheduledAt): Boolean; fun liveStatusKind(events, now): Kind? }`；投影、依从率、历史 UI **只**经该 helper 判定「是否应服 / 是否暂停 / 是否未知」，**禁止**任何路径内联 `pausedAt(dayStart)` 之类的 day 级判定（§9.7 以 property 测试断言两集合恒等）。
- **倒填停用**：P…R 时段凡 `pausedAt=true` → 无应服槽位 → **不再算 MISSED**（决策 2、§6）。
- **中段恢复**：恢复生效后 `pausedAt=false` → 从恢复日恢复投影（用覆盖该时刻的计划段）。
- **多次循环**：状态层按事件自动分段，计划链不动。

### 5.4 变更检测：活状态 kind（更正 L）

**旧缺陷**：`desired != deriveCurrentStatus(...)` 比较的是**暂停布尔**。`PAUSE → DISCONTINUE`（临时停用 → 归档）布尔**不变**（都为「暂停」），旧式判为 no-op ⇒ **归档被静默吞掉**（既不写事件、也不改 UI 标签）。

**规则**：CHANGE 比较 **活状态 kind**：

```
fun liveStatusKind(events, now): Kind? =
    liveEvents(events)
        .filter { it.kind == PAUSE || it.kind == RESUME || it.kind == DISCONTINUE || it.kind == LEGACY_BASELINE }
        .filter { it.effectiveAtMs == null || it.effectiveAtMs <= now }
        .maxWithOrNull(compareBy({ it.effectiveAtMs ?: Long.MIN_VALUE }, { it.recordedAtMs }, { it.id }))?.kind
```

- `desiredKind != liveStatusKind(events, now)` → **CHANGE**：append 事件 + 写缓存 + reconcile。
- `desiredKind == liveStatusKind(events, now)` → **CHECK**：不写事件，只写 `lastUserCheckedDate`（§8、D6）。

**转换矩阵（`desired` 由用户动作给出 ∈ {PAUSE, DISCONTINUE, RESUME}）：**

| 当前 kind vs 期望 kind | PAUSE | DISCONTINUE | RESUME |
|---|---|---|---|
| PAUSE | CHECK（同 kind，无事件） | **CHANGE（事件）** | **CHANGE（事件）** |
| DISCONTINUE | **CHANGE（事件）** | CHECK | **CHANGE（事件）** |
| RESUME | **CHANGE（事件）** | **CHANGE（事件）** | CHECK |
| LEGACY_BASELINE | **CHANGE（事件）** | **CHANGE（事件）** | **CHANGE（事件）** |
| （无活状态行） | CHANGE | CHANGE | CHANGE |

- **判据（一句话）**：`desiredKind == 当前 kind` ⇔ CHECK；否则 CHANGE。**只看 kind，不看布尔。**
- **VOID 回归到同 kind = 事件（明确裁决）**：`VOID` **不是**用户 CHANGE 的目标 kind（用户动作只有 PAUSE/DISCONTINUE/RESUME）。「`VOID` 使状态回退到与更正前相同的 kind」发生在**更正路径**（§2.C），而更正**一律 append**（无论净 kind 是否改变）——审计链要求「记下这次更正」，故**是事件**。CHECK 的 no-op 只适用于**用户状态流**，**不**适用于更正流。

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

### 6.1 暂停区间在 **历史 / 日历** 的呈现（更正 H、J、M）

- **暂停窗口**渲染为一段**显式的「暂停」条带**（暂停图标 + 文案）；**状态未知窗口**（见 §6.3）另渲染为一段**「状态未知」条带**，二者**视觉与文案都区分**。**都不是空白、不是漏服红点。** 文案：
  - 已知起止：`暂停·MM/DD–MM/DD`；进行中：`暂停中·自 MM/DD`。
  - **迁移地平线之前（更正 M）**：一律渲成 **「状态未知（早于本次迁移）」**，起点用**开放式**样式（左端渐隐 / 无端点）。**不得**写成「暂停中·开始日期未知」，也**不得**打印任何被算出的开始日——迁移只确知迁移时刻的状态，之前既非已证暂停、也非已证活跃。
  - 迁移地平线之后、首个事件之前：`已归档·自迁移`（这段是确知的暂停，不是未知）。
- **粒度 = dose（更正 J）**：是否暂停 / 是否未知都按 occurrence 的 `scheduledAt` 判定（§5.3）；日格只是对**逐 occurrence 结果**的聚合显示，**绝不用「日」作判据**：
  - 该日**全部** occurrence 皆为 `PAUSED`：日格标 **「暂停（不计漏服）」**。
  - 该日**部分** occurrence 被判排除（暂停或未知）：日格标 **「部分暂停（该时点不计漏服）」**，逐时点标注。**不得**把整日标成暂停或漏服。
- **窗口内的真实打卡记录照旧显示、照旧计入 `TAKEN`**（只是不再作为「应服」的要求）；文案区分「暂停前记录」/「暂停期间记录」，**不删除、不隐藏**。
- 窗口受倒填更正影响时，打 **「已调整」** 标记（§6 第 3 条）。
- 这些状态**与派生 `pausedAt`/`doseActiveAt` 同源**（§5），保证历史视图与提醒/依从率口径一致。

### 6.2 暂停区间在 **依从率** 的呈现（更正 H、J、M）

- **粒度 = dose，与投影同一谓词（更正 J）**：`MedicationAdherenceCalculator` 生成每个 `AdherenceDay` 的 `expected`/`counted` 槽位时，对**每个 occurrence** 用 `doseActiveAt(events, slot.scheduledAt)`（**与 `FuturePlanCalculator` 同一个 helper**）过滤：
  - `doseActiveAt=false` 的 occurrence ⇒ **不进 `expected`、不进 `counted`（不进分母）**，**绝不合成 `MISSED`**。
  - 排除的是**被暂停的剂次**，**不是整日**。一日两剂、其一在暂停内 ⇒ 该日 `counted=1`（另一剂），**不是 0、也不是 2**。
- **显示**：分母旁注明 `（已扣除暂停 N 剂）`（**剂**，非天数）；暂停时点以独立色带 + 「暂停期间不计漏服」说明呈现。
- **未知窗口（更正 M）**：`statusAt=UNKNOWN`（迁移地平线之前）的**剂次**一律**不进分母、不合成 MISSED**，并在视图**单列** `状态未知 N 剂（早于本次迁移，不计入依从率）`；**不以任何形式编造开始日**，也**不把这段并入「已扣除暂停」**（二者分列，细则见 §6.3）。
- **可解释性**：倒填导致依从率变化时，§6 提交流程给出**前/后数值差**（「应服剂次 60→58，已扣除暂停 2 剂，漏服 2→2 不变」）并生成「已调整」记录——满足「历史统计变化必须可解释，而非静默」。

### 6.3 迁移前的未知历史：显示与统计的诚实规则（更正 M）

**数据事实**：遗留归档药在迁移前**只有一个布尔、没有日期**。我们能确定的只有「**迁移时刻**该药已归档」；**不能**确定它何时开始归档/暂停，也**不能**确定迁移前它是否在服。基线行 `effectiveAtMs=NULL` 就是这一事实的**忠实表达**：它不携带任何日期。

**认知边界（明确写定）**：设迁移时刻为 `M`（= 基线事件 `recordedAtMs`）。
- `t ≥ M`：**确知已归档** ⇒ `PAUSED`（迁移时刻是已知状态，且其后未变）。
- `t < M`：**状态未知** ⇒ `UNKNOWN`。**既不说「一直在服」（会假造漏服），也不说「一直在暂停」（会假造一个起点不存在的暂停史）。**

- **显示规则**：
  1. `t < M` 的窗口 → **`状态未知（早于本次迁移）`**（**不是**「暂停·开始日期未知」）。
  2. 该窗口若以一次 `RESUME@D` 结束（`D ≥ M`）→ 未知段与已归档段**分开**标注：`状态未知（早于本次迁移）` + `已归档·自迁移` + `MM/DD 起恢复`，**绝不合成「一段暂停」**。
  3. **任何位置**（历史条带、详情、变更记录、导出）**都不得**打印一个被算出来的「开始日」；未知段起点用**开放式**样式（左端渐隐/无端点），tooltip 说明「状态早于迁移，具体转折不可考」。
- **统计规则（明确裁决）**：`statusAt=UNKNOWN` 的**剂次**（`t < M`）**一律 `counted=false`（排除出分母）**，并在汇总**单列** `状态未知 N 剂（早于本次迁移，不计入依从率）`；**绝不**合成 `MISSED`、**绝不**把这些剂次并进「已扣除暂停 N 剂」的**已知**暂停计数（二者**分列**）。
  - **为什么不计为漏服**：迁移前的布尔**没有**证明这些时点「本应服药」，也没有证明其当时的计划；把 `t < M` 计入分母（合成 `MISSED`）是**确定的假话**——我们并不知道当时它是否在服。
  - **为什么不计为「已暂停」**：我们**没有**它的归档起点；把 `t < M` 说成「已暂停」等于**编造一段暂停史**（需要一个凭空起点）。只有 `t ≥ M` 才是已证暂停。
  - **为什么单列而非省略**：完全省略会让照护者以为「这段没有药/没有数据」；显式标注「状态未知（早于迁移）」既诚实又不误导。

---

## 7. 提醒：发送前再校验守卫（**首期交付**，更正 N）

> **里程碑定位（更正 N，第 6 稿修订）**：本节**首期交付**——它是「缓存与事件不一致 / 取消漏跑」这一整类错误提醒的**唯一**兜底（§0.4）。它**同时**是未来生效日放行的**发布闸门**：首期**不接受**未来生效日（D11），未来生效日仍属 Phase 2，其放行条件即本守卫已在线 + 其测试通过。

**现状（缺口）**：`MedLogAlarmReceiver.handleMedication`（`MedLogAlarmReceiver.kt:91-222`）在**每个分支都只 `getMedicationById`**（`:104/116/131/148/187`），**从不检查**有效状态——**今天不存在发送前再校验**。

**可用性（为什么合入后够用且便宜）**：
- **单点收口**：`onReceive` 的 `when(dispatch.kind)`（`:81-87`）把 `MEDICATION_NORMAL/EARLY/FOLLOW_UP` **全部**路由进 `handleMedication`。在 `handleMedication` 顶部加一行
  `if (medicationStatus.statusAt(medId, clock.millis()) != Status.ACTIVE) return`
  即覆盖**全部三类用药提醒通知**。
- **无级联**：`scheduleNextReminderAfterDose` / `scheduleFollowUpAlarm`（`:198-218`）都在守卫之后，被抑制时**不**再排下一次。
- **读真相而非缓存**：守卫调 §5 的 `statusAt`（`PAUSED` 与 `UNKNOWN` 均抑制提醒；**不读缓存、无任何缓存回落**，更正 I），故**缓存陈旧也无法击穿守卫**（这正是更正 A 与决策 3 的交点）。
- **对照先例**：care-event 的发送前四要素再校验（`CareEventReminderEngine.kt:66-78`）已证明该模式在本仓可落地、可穷举测试。
- **不误伤**：`ACTION_TAKEN`/`ACTION_SKIP`（用户点通知按钮的显式动作，`:102-124`）**不**置于守卫之后——**真实服药记录不得被拦**（决策 2 / 要求 5）。
- **成本**：一个注入依赖 + 顶部一个早退 + 测试；每次闹钟触发多一次事件读，可忽略。

**裁决（明文）**：**守卫对「闹钟/通知」面充分且廉价 → 首期即交付**（更正 N）：三类用药闹钟在弹通知前一律以派生状态为准，非 `ACTIVE`（含 `PAUSED`、`UNKNOWN`）一律抑制且不续排；`ACTION_TAKEN` / `ACTION_SKIP` 不受影响。**它同时是未来生效日的发布闸门**：Phase 2 放行未来生效日的条件 = 本守卫已在线 + 其测试通过。
**残余（首期同样存在，须写清）**：守卫只覆盖**闹钟 / 通知**；**小组件 / 今日计划**读的是**缓存**（`WidgetUtils.kt:29`、`HomeViewModel.kt:571`、`TodayItem.kt:77/106/145`），可能有一次刷新窗口的陈旧显示——由 §8.1 自动核对 + 既有 15 分钟 `WidgetRefreshWorker` + `widgetRefresher.refreshAll()`（`AndroidReminderReconciler.kt:43`）收敛；另**已上屏的旧通知**不会瞬时消失，由下一次 reconcile / prune（`cancelAllReminderNotifications`，`NotificationHelper.kt:411`）收掉。

**证明测试（**首期交付物**，`MedLogAlarmReceiverTest`，注入可拨时钟）**：
- `t < F` 触发 EARLY/NORMAL/FOLLOW_UP → **照常**弹通知/排下一次；
- `t >= F`（F = 未来暂停的生效时刻）触发三类 → **全部抑制**，且不排下一次；
- 过期缓存（`isArchived=false`）但事件说已暂停 → 仍**抑制**（守卫读派生）；
- `ACTION_TAKEN/SKIP` 在暂停后仍**照常写日志**。

---

## 8. 两种事实分离：系统自动核对 vs 用户主动核对（决策 4、更正 E）

**要点（更正 E）**：**系统自动核对**与**用户主动核对医嘱**是**两个不同事实**，存两处、由两个不同写者写。**系统自动通道绝不得移动用户核对日期。**

### 8.1 系统自动核对（AUTO RECONCILE）—— 存 `lastAutoReconciledAtMs`

- **触发**（骑既有基础设施，不新增闹钟）：应用启动 + 既有 15 分钟 `WidgetRefreshWorker` + 既有 `MedLogBootReceiver`（`MedLogBootReceiver.kt:22-37`）——与 care-event R9/R13 同口径。
- **动作**：对当前成员逐药：`derived = deriveCurrentStatus(events, now)`（**只读事件、含基线行；不读缓存**）；
  - 若 `derived != cache` → 写缓存列（`MedicationDao.archiveMedication/unarchiveMedication`）；
  - 若 `derived` 由活跃翻为暂停 → `ReconcileRemindersUseCase.medication(id, MEDICATION_CHANGED)` 撤闹钟/通知 + 小组件刷新；
  - 收尾写 **`lastAutoReconciledAtMs`**。
  - （**本轮删除**旧「零事件药跳过写缓存」特例，更正 I）真相现由基线**事件行**承载，缓存任何时候都可被派生重写而**不会**丢失迁移前事实 ⇒ 该特例不再需要。
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
8. **多层 × 重复组合的合流性质测试（更正 K）**：随机生成取代图（随机事件数、随机 `kind ∈ {PAUSE,RESUME,DISCONTINUE,VOID}`、随机 `effectiveAtMs`/`recordedAtMs`、随机 `supersedesId < id`，**含多层链与「同一目标被多条活行取代」的组合**）。断言：
   - **P1 插入顺序无关**：对同一事件集随机打乱 N 种喂入顺序 ⇒ `liveEvents` 集合与 `pausedAt(t)`（在 t 网格上）**逐项一致**。
   - **P2 参考模型一致**：一个独立「不动点」解析器（反复删除被指向者至稳定）与单遍 `liveEvents` **逐项一致**。
   - **P3 多层 × 重复组合的定值**：构造 `A←B←C`（多层）与 `A←D`（重复声称 A）同图 ⇒ 断言 `live={C,D}`（`A`、`B` 因被指向而死；`A` 的活竞争者仅 `D`），且 `pausedAt(t)` = 活行里 `ORDER` 最大者（确定性、可复现）。
   - **P4 受害者单调**：`targets(E) ⊆ targets(E∪{x})`（一旦被指向恒死），在随机 append 序列上恒成立。
   - **P5 无环**：强制 `supersedesId < id` 下随机 append 的取代图恒可拓扑排序。
   - **比什么**：比较对象 = **`liveEvents(E)` 的 id 集合** 与 **`pausedAt(E, t)` 在 t 网格上的布尔序列**；不变量 = 「二者是事件集 `E` 的纯函数，与喂入顺序无关、与多层/重复如何组合无关」。

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

### 9.4 遗留基线（更正 I/G/M —— `MedicationStatusLegacyTest`）

- **迁移补种基线**：种 v25 库含 `isArchived=true`、零事件、且有若干历史剂量日志的药 → 迁移 v26 → 断言 `medication_status_events` 该药**恰一条** `LEGACY_BASELINE`（`effectiveAtMs=null`、`recordedAtMs=迁移时刻`）；`medications` 行与 `medication_logs` 行**零变化**。
- **首个 RESUME 不重解释历史（核心反例）**：设迁移时刻 `M`，对该药 append `RESUME@D`（`D > M`）→ 断言 `statusAt(t<M)=UNKNOWN`、`statusAt(M≤t<D)=PAUSED`、`statusAt(D)=ACTIVE`；**投影**中 `D` 之前（无论 `UNKNOWN` 还是 `PAUSED`）无应服 occurrence、`D` 起有；**依从率**中 `t<M` 的剂次进「状态未知」单列、`M≤t<D` 的剂次进「已扣除暂停」、`D` 起计入分母，全程**不合成 `MISSED`**。两种旧实现都在此必失败：把 `D` 之前当「一直在服」（假造漏服），或把 `M` 之前当「暂停」（假造暂停史）。
- **首个非 RESUME 事件**：首个 `PAUSE@D` / `DISCONTINUE@D` ⇒ `t<M` 仍 `UNKNOWN`、`t≥M` 仍 `PAUSED`（新行不改 `M` 之前的未知）。
- **剂量日志不动**：以上每步后 `medication_logs` 行数与内容不变、可见。
- **缓存非真相**：把 `medications.isArchived` 人为置 `false` → 派生仍 `true`（基线行压过缓存）。
- **迁移后无零事件遗留归档药**：断言不存在 `isArchived=1` 且事件表为空的遗留行。

### 9.5 首期日期约束（更正 F）

- 校验：`effectiveAtMs` 落在未来本地日 → 拒绝（`FutureEffectiveDateNotAllowed`）；今天/过去 → 接受。
- UI：日期选择器 `maximumDate = 今天`（契约测试：未来日不可选）。
- **既有停用行为**：见 §16.12 的验收步骤（停今天 ⇒ 立即停提醒 + 该药统计行为启动）。

### 9.6 变更检测 kind 级（更正 L —— `MedicationStatusChangeTest`）

- **PAUSE→DISCONTINUE 是事件**：先 `PAUSE`，再对同药 `DISCONTINUE` ⇒ **新增事件**（事件数 +1）、`liveStatusKind` 由 `PAUSE` 变 `DISCONTINUE`、UI 标签由「暂停中」变「已归档」；旧布尔比较在此**判为 no-op**（回归锚点）。
- **DISCONTINUE→RESUME 是事件**：⇒ 新增事件、kind 变 `RESUME`、提醒从恢复日重排。
- **同 kind 重复是 CHECK**：连点两次同一 kind ⇒ **仅一条事件**、无第二条；用户路径只更新 `lastUserCheckedDate`（§9.3）。
- **矩阵 property**：对 `from ∈ {PAUSE,DISCONTINUE,RESUME,LEGACY_BASELINE,NONE}` × `to ∈ {PAUSE,DISCONTINUE,RESUME}` 断言「事件产生 ⇔ `from≠to`」。
- **VOID 回归同 kind = 事件**：更正路径 append 一条 `VOID`（使活行回退到与更正前相同的 kind）⇒ 事件数 +1（审计留存），不被 CHECK no-op 吞掉。

### 9.7 投影 / 依从率 dose 级谓词一致（更正 J —— `MedicationStatusDoseParityTest`）

- **反例锚点**：一日两剂（08:00、20:00），`PAUSE@12:00` 当日生效 ⇒ 断言**投影**当日应服 occurrence = {08:00}；**依从率**当日 `counted` 的 occurrence = {08:00}（`20:00` 被排除）；**排除计数 = 1 剂**（**不是整日 0、也不是 2**）。
- **反向**：`RESUME@12:00`（此前暂停）⇒ 投影当日 = {20:00}、依从率当日 = {20:00}。
- **集合恒等 property**：随机 medication/plan/events ⇒ 恒有「投影在 `t` 应服的 occurrence 集」== 「依从率在 `t` 记入分母的 occurrence 集」（同一 `doseActiveAt` 谓词驱动）。
- **shared-helper 架构断言**：`FuturePlanCalculator` 与 `MedicationAdherenceCalculator` **都**只经 `MedicationStatusDerivation.doseActiveAt` 判定应服（单一调用点断言；禁止内联 `pausedAt(dayStart)`）。

### 9.8 迁移前未知历史：显示与统计（更正 M —— `MedicationStatusUnknownStartTest`）

- **未知 ≠ 暂停**：断言 `statusAt(t<M)=UNKNOWN`、`statusAt(M)=PAUSED`（迁移时刻起才是已证暂停）；UI 文案断言含「状态未知（早于本次迁移）」且**不含**「暂停」前缀、**不含**任何被算出的 MM/DD 开始日（字符串/资源断言）。
- **统计排除 + 单列**：`t<M` 的剂次 `counted=false`、不合成 `MISSED`；汇总单列「状态未知 N 剂」且**不计入**「已扣除暂停 N 剂」计数（两者分列、剂量级）。
- **RESUME 变体**：以 `RESUME@D` 结束的窗口 ⇒ 未知段（`t<M`）与已归档段（`M≤t<D`）**分开**标注，文案分别为「状态未知（早于本次迁移）」与「已归档·自迁移」，二者不合成一段暂停。

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
| E5 | **生效日在未来** | **首期不接受**（D11）：UI 禁选、校验拒绝（`FutureEffectiveDateNotAllowed`）。未来生效日仍属 **Phase 2**，其发布闸门 = **首期已交付**的 §7 守卫（更正 N） |
| E6 | **倒填停用使已过的时点不再是漏服** | 状态层 `statusAt=PAUSED` → 不再投影该 occurrence → 那些剂次不再是 MISSED（**逐 dose 判定**，非整日口径）；历史/日历对应时点显示「暂停（不计漏服）」（§6.1）。**必须有回归测试** |
| E7 | **恢复的日期** | 历史从恢复日恢复活跃；提醒从当下向前重排（未来排期可接受） |
| E8 | **生效日早于该药最后一次计划变更** | **不再依赖 `updatePlan` 的落段守卫**（§2.B）：状态层与计划链正交，倒填不影响计划链，**无空洞/重叠** |
| E9 | **删除带状态事件的药** | FK **CASCADE**：连同状态事件（含更正链）一起删。要求 5 的「历史不可丢」约束**跨停用**，不跨**删除** |
| E10 | **批量的原子性** | 一次事务内为 N 个药写事件 + 缓存列；**同一批量事务内各药可携带不同 kind（`PAUSE`/`RESUME` 混合，勿误解为同类操作）**；任一失败整体回滚（对齐 `updateMedications` `MedicationRepositoryImpl.kt:83-86`）。**事务外 post-commit 事实（例外，不在该事务边界/回滚内）**：`lastUserCheckedDate` 由核对流程（`docs/medication-daily-reconcile.md`）在**事务提交之后**单独写入（DataStore，与 Room 不同存储，跨存储无回滚）——**不得**读作「该事务一并写入核对日期」 |
| E11 | **生效日跨时区** | 批量 UI 的「生效日期」按**成员提醒时区**的本地日界换算为 `effectiveAtMs`；D11 的「≤ 今天」也按该时区判定 |
| E12 | **误记更正** | 追加取代/更正事件 + 重算（§2.C/§9.2）；不改旧行、不删日志 |
| E13 | **系统自动核对 vs 用户主动核对** | 自动只写缓存 + `lastAutoReconciledAtMs`、**绝不**写 `lastUserCheckedDate`；用户核对**只**写 `lastUserCheckedDate`、不写事件、不算服药（§8、§9.3） |
| E14 | **多层取代** | 只有链末行生效（R1）；祖先永不生效 |
| E15 | **VOID 取代** | 目标死；状态回退到被作废行之前的活状态行，无则「无状态」（R3） |
| E16 | **重复取代** | 目标死；`(recordedAtMs, id)` 最大者胜，败者遮蔽保留（R4） |
| E17 | **冲突取代 / 成环** | 冲突由全序确定性解决；成环**结构禁止**（`supersedesId < 自身 id`，R5/R6） |
| E18 | **遗留归档药** | 迁移补种恰一条 `LEGACY_BASELINE`（生效日 NULL）⇒ `t≥迁移` 判 `PAUSED`（`deriveCurrentStatus=true`）、`t<迁移` 判 **`UNKNOWN`**（**绝不**判暂停或活跃）；首个 `RESUME@D` **不重解释** `D` 之前（`t<M` 未知、`M≤t<D` 已证暂停）；首个非 RESUME 事件不翻活跃；缓存非真相（§9.4） |
| E19 | **一日两剂其一在暂停内** | 按 **dose 级**判定（§5.3/§6.1/§9.7）：投影与依从率都只排除被暂停的**那一剂**；日格标「部分暂停」，不计该时点漏服 |
| E20 | **遗留归档药迁移前无真实暂停日** | 迁移前（`t<M`）状态为 **`UNKNOWN`**：显示「状态未知（早于本次迁移）」；统计**排除且单列未知计数**（剂次级）、不合成 `MISSED`、不编造暂停起点（§6.3、§9.8） |
| E21 | **PAUSE→DISCONTINUE（同布尔异 kind）** | 比较 kind ⇒ **真实变更**，必须写事件（§5.4、§9.6）；同 kind 重复才是 CHECK |

---

## 12. 迁移 v25 → v26（纯新增 + 遗留基线补种）

**SQL 变更**（照 `MIGRATION_24_25` 纯新增先例，`MedLogDatabase.kt:297`）：

1. `CREATE TABLE medication_status_events`，schema **精确**如下（与 §4 实体逐列一致）：

   | 列 | 类型 | 可空 | 约束 / 说明 |
   |---|---|---|---|
   | `id` | INTEGER | NOT NULL | PRIMARY KEY AUTOINCREMENT |
   | `medicationId` | INTEGER | NOT NULL | FK → `medications.id` `ON DELETE CASCADE` |
   | `kind` | TEXT | NOT NULL | 取值 ∈ {`LEGACY_BASELINE`,`PAUSE`,`RESUME`,`DISCONTINUE`,`VOID`}（§4 常量） |
   | `effectiveAtMs` | INTEGER | **允许 NULL** | **唯一**的 NULL 情形 ⇔ `kind='LEGACY_BASELINE'`；其余 kind 由 `insertEvent` 保证非 NULL |
   | `recordedAtMs` | INTEGER | NOT NULL | 录入时刻；基线 = 迁移时刻 `:migrationMs`（= 认知地平线 `M`） |
   | `reason` | TEXT | NULL | 可选 |
   | `supersedesId` | INTEGER | NULL | 无自引用 FK；不变量 `supersedesId < id`（或 NULL） |

   索引：`index_medication_status_events_medicationId(medicationId)` 与 `index_medication_status_events_medicationId_effectiveAtMs(medicationId, effectiveAtMs)`。**不建**任何唯一索引（append-only，允许重复/取代行）。
2. **基线补种（更正 I）**：
   ```
   INSERT INTO medication_status_events
       (medicationId, kind, effectiveAtMs, recordedAtMs, reason, supersedesId)
   SELECT id, 'LEGACY_BASELINE', NULL, :migrationMs, NULL, NULL
   FROM medications WHERE isArchived = 1
   ```
   **写什么**：为每个 `isArchived=1` 的遗留药**恰一条**基线行（`effectiveAtMs = NULL` = **不携带任何日期**；`recordedAtMs = :migrationMs`）。
   **不写什么**：**不**写 `effectiveAtMs`（**故不伪造任何暂停起点**）；**不** UPDATE / 覆写 `medications.isArchived`；**不**为 `isArchived=0` 的药写任何行；**不**触碰 `medication_logs`；**不**新增 / 重建 `medications` 与 `medication_plan_revisions` 的列或表。

**无表重建**；`medications` 与 `medication_plan_revisions` schema **不变**（§2.B：不新增暂停窗口字段、不改计划链；**也不新增列** —— 这正是选「基线事件」而非「新可空列」的理由之一）。历史 `isArchived` 行**零覆写**。

### 12.1 遗留基线的取舍（更正 I，替换旧更正 G，逐项比较）

**问题**：`isArchived=true` 的遗留药**没有任何状态事件**；且 `isArchived` 是**可变缓存**（首个新事件后会改写），**不能**可靠承载迁移前事实。若朴素 `pausedAt(∅)=false` ⇒ 静默恢复；若用可变 `isArchived` 兜底 ⇒ 首个 RESUME 后事实丢失（自相矛盾，见 §5.2 反例）。

| 方案 | 机制 | 关键质疑 | 裁决 |
|---|---|---|---|
| **(a) `medications` 新增可空列** | `ALTER TABLE medications ADD COLUMN legacyArchivedAtMigration INTEGER`（迁移时 `= isArchived`） | **不可变仅靠约定**：普通列无 append-only 保护，任何后续 UPDATE 都能改动它；且引入**第二个真相来源**（列 vs 事件），与「事件权威」张力大；`medications` schema + schema 导出随之变化 | **否决** |
| **(b) 事件表补种一条 `LEGACY_BASELINE`（生效日显式 NULL）** | 迁移 `INSERT ... SELECT` 为每个归档遗留药插入一条基线事件 | 「生效日未知」需要 `effectiveAtMs` 可空，但**这是新表，零迁移成本** | **采纳** |
| **(c) 派生内虚拟基线（不落库）** | 无活状态行时注入 `虚拟 PAUSE@-∞` | 把「全部事件被 VOID」与「从未有事件」混为一谈；且**没落库** ⇒ 首个 RESUME 后仍无法回放基线的「未知开始」 | **不优于 (b)**：不解决「不可持久化」这一核心诉求 |

**采纳 (b) 的理由（相对 (a) 的三点优势）**：
1. **结构性不可变**：基线是 append-only 事件表里的普通行，**没有** UPDATE/DELETE 入口（§4），只能被**更正事件**取代——与所有其它事件同机制。
2. **单一真相来源**：基线与事件同表同谓词，「事件权威」**无例外**；`deriveCurrentStatus(events, now)` **不再**需要 `legacyArchived` 参数；「零事件遗留药」这一特例**整体消失**。
3. **零伪造 + 零 `medications` 变更**：`effectiveAtMs=NULL` **显式**表达「未知」，既不伪造日期，也不改 `medications` schema。

**与「事件权威」的一致性（显式写清）**：事件（含基线）**一旦存在**完全决定状态；基线只是「用户尚未对遗留药做任何操作」时对**不可考历史**的有界表达——它**只断言**「迁移时刻已归档」（`t≥M` = `PAUSED`），对 `t<M` **只给 `UNKNOWN`、不给暂停**；且**任何 `effectiveAtMs ≤ t` 的活状态行都压过基线**（§5 R7）。

**旧否决的收窄**：旧 §12.1 否决的「迁移时补种基线事件」指的是**补种一条带伪造日期的 PAUSE**（会对「未知开始 → 迁移」误标活跃/漏服，或显示凭空生效日）。本稿补种的是**显式未知生效日的 `LEGACY_BASELINE` 标记**（`effectiveAtMs=NULL`，**不携带任何日期**），故**不触发**旧否决的理由。
- **零覆写**：`medications` 表历史行原样保留（不写新列、不改 `isArchived`）。

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
- [ ] 新增 **`MedicationStatusMigrationTest`**（照 `CareEventMigrationTest` 模板）：**种一个带数据的 v25 库 → 迁移 → 断言**：旧数据零丢失、`medication_status_events` 存在且可读写、两条索引存在、**`isArchived=1` 的遗留药各补种恰一条 `LEGACY_BASELINE`（`effectiveAtMs=NULL`）且其它药不补种**、删除药级联删事件。
- [ ] 新增 **`MedicationStatusLegacyTest`**（§9.4）：**迁移补种基线、`t<迁移` 判 `UNKNOWN`（不判暂停 / 不判活跃）、首个 RESUME 不重解释 `D` 之前（未知段与已归档段分列）、首个非 RESUME 事件不翻活跃、日志不动、缓存非真相、迁移后无零事件遗留归档药**。
- [ ] 四套 locale 文案（`values`/`-en`/`-ja`/`-ko` 的 `medication_flow_strings.xml`），含暂停区间与「暂停不计漏服」文案。

**备份/恢复后果**：`BackupCompatibilityPolicy.canRestore` 上界跟随 `VERSION`（`BackupRestoreUseCase.kt:83-88`）→ 恢复的 **v25 备份自动放行**，首次打开依次迁移到 26。既有约束不变：**旧版本 App 打不开新版本备份**（非本特性缺陷）。

---

## 13. 影响面（文件级）

**新增**
- `core/database/.../model/MedicationStatusEvent.kt`（+ `MedicationStatusKind` 常量，含 `VOID`）
- `core/database/.../local/MedicationStatusEventDao.kt`（含 `insertEvent` 的 `supersedesId < 自身 id` 校验）
- `app/.../data/repository/MedicationStatusRepository.kt` + `Impl`（成员作用域）
- `app/.../feature/medications/application/MedicationStatusDerivation.kt`（共享纯函数：`liveEvents`/`statusAt`/`pausedAt`/`unknownAt`/`doseActiveAt`/`liveStatusKind`/`deriveCurrentStatus`）+ `SetMedicationStatusUseCase.kt`（单药 + 批量，含 kind 级 diff/CHECK）
- `app/.../feature/medications/application/CorrectMedicationStatusEventUseCase.kt`（更正 C）
- 自动核对用例 `ReconcileMedicationStatusUseCase`（§8.1）
- 迁移/派生/相容/事实分离测试：`MedicationStatusMigrationTest`、`MedicationStatusDerivationTest`、`MedicationStatusCorrectionTest`、`MedicationStatusReconciliationTest`、`MedicationStatusLegacyTest`
- `docs/medication-status-spec.md`（本文件）

**修改**
- `DatabaseSchema.kt`（25→26）、`MedLogDatabase.kt`（注册实体 + `MIGRATION_25_26`）、`AppModule.kt`（DI）
- `MedicationRepositoryImpl.setArchived`（改为 append 事件 + 直写缓存列，不再 `updatePlan(copy(isArchived=…))`）
- `AndroidReminderReconciler`（A 类排程判据改**读派生** `deriveCurrentStatus`，不再裸读 `isArchived`）
- `FuturePlanCalculator`（增加 `statusEvents` 入参 + `doseActiveAt` 过滤；遗留归档药由基线行排除）+ `MedicationAdherenceCalculator`（**逐 occurrence 用同一个 `doseActiveAt`**、不合成 MISSED，§6.2；历史/日历屏加「部分暂停」「状态未知（早于迁移）」标记 §6.1/§6.3）——**共享 `MedicationStatusDerivation.doseActiveAt`，禁止内联 day 级判定**
- `MedicationDaoTransactionTest`（更新「状态改版不新建计划段」断言）
- `MedLogAlarmReceiver.handleMedication` 顶部**发送前再校验守卫**（§7，**更正 N：首期交付**）——`statusAt(medId, now) != ACTIVE` 即早退，覆盖三类用药闹钟，`ACTION_TAKEN`/`ACTION_SKIP` 不受影响；测试 `MedLogAlarmReceiverTest`（§7 / §16.20）
- 迁移/契约测试（§12.2 清单）

**Phase 2（不在本轮）**
- 未来生效日的**精确定时**：解除 D11「拒绝未来生效日」限制（**发布闸门 = 首期已交付的 §7 守卫 + 其测试**，更正 N）

---

## 14. 任务分解（分阶段，每步可独立验证）

**Phase 1（首期；**生效日 ≤ 今天**；发送前派生校验守卫**已交付**，更正 N）**
- [ ] T1 实体 + DAO + 仓储 + v25→v26 迁移（**含 `LEGACY_BASELINE` 补种 INSERT ... SELECT**；迁移测试：种 v25 有数据 → 零丢失、级联、**每个归档药恰一条基线行**；**LegacyTest：首个 RESUME 不重解释 D 之前**）
- [ ] T2 `MedicationStatusDerivation`（`liveEvents`/`statusAt`/`pausedAt`/`unknownAt`/`doseActiveAt`/`liveStatusKind`/`deriveCurrentStatus`）+ `SetMedicationStatusUseCase` 的 **kind 级** CHANGE/CHECK diff（单测：**多层/VOID/重复/冲突/环 + 组合合流性质测试（§9.1）**、**PAUSE→DISCONTINUE 是事件（§9.6）**、同 kind 幂等、同日后停恢复、倒填、批量原子性）
- [ ] T3 正交状态层接入 `FuturePlanCalculator`（`statusEvents` + `doseActiveAt`）与 `MedicationAdherenceCalculator`（**同一 `doseActiveAt`**）；回归：`FuturePlanCalculatorTest`、`MedicationAdherenceTest`、`MedicationDaoTransactionTest`、**`MedicationStatusDoseParityTest`（§9.7）**
- [ ] T4 依从率/历史屏：**dose 级暂停呈现（全部剂次暂停 vs 部分暂停）+ 暂停不计漏服（按剂次排除分母、不合成 MISSED）+ 迁移前未知区间「状态未知（早于本次迁移）」与单列统计**（§6.1/§6.2/§6.3；单测 + 契约测试；`MedicationStatusUnknownStartTest`）
- [ ] T5 **两种事实分离**（§8）：`lastAutoReconciledAtMs`（自动）与 `lastUserCheckedDate`（仅用户核对写）；**单写者测试 + 「自动通道不得移动用户日期」测试**（§9.3）
- [ ] T6 **首期日期约束**（D11）：校验拒绝未来生效日（`FutureEffectiveDateNotAllowed`）+ UI 日期选择器 `maximumDate=今天`（契约测试）
- [ ] T7 误记更正 = 取代事件 + 重算（§2.C/§9.2；`MedicationStatusCorrectionTest`；含 **VOID 回归同 kind 仍是事件**）
- [ ] T8 我的药品多选 + 批量动作栏 + 详情双入口 + 变更记录/「已调整」/暂停区间 UI（Compose 契约测试；不新增底部 Tab）
- [ ] T9 真机全链路：批量停用（倒填）→ 首页/小组件不再出现 → 依从率不再漏服且显示暂停区间 → 恢复 → 从恢复日重排；**既有停用行为验收（§16.12）**
- [ ] T10 **发送前派生校验守卫**（更正 N，首期交付）：`MedLogAlarmReceiver.handleMedication` 顶部 `if (statusAt(medId, clock.millis()) != ACTIVE) return`（覆盖三类用药闹钟；`ACTION_TAKEN`/`ACTION_SKIP` 不受影响）；单测 `MedLogAlarmReceiverTest`：三类闹钟在 `PAUSED`/`UNKNOWN` 下一律抑制且**不续排**、`t < 生效` 照常提醒、**过期缓存（`isArchived=false`）不击穿**、`ACTION_TAKEN`/`ACTION_SKIP` 仍写日志；**对抗验收 §16.20**

**Phase 2（真实待定，不阻塞首期）**
- [ ] 未来生效日的**精确定时**：解除首期「拒绝未来生效日」限制（**发布闸门 = 首期已交付的 §7 守卫 + 其测试**，更正 N）
- [ ] 剂量调整自身的生效日期（计划段的生效日语义）

---

## 15. 非目标（本期明确不做）

- 不新增第 6 个底部 Tab（`NavigationHierarchyTest` 钉死 5 个顶层目的地）。
- **不做状态事件的就地编辑/物理删除**（append-only 审计链；改错=追加取代事件）。**注：更正能力本身在首期**（§9.2），「不做」的只是「就地编辑/删除」。
- **不采纳第 1 稿 D3/E8 的「计划段按生效日有序插入/裁剪」**（§2.B 已证不可行）。
- 不改 `updatePlan` 签名；pause/resume 不写 `medication_plan_revisions`。
- **首期不做未来生效日**（D11）。**注：发送前再校验守卫首期交付**（§7，更正 N）——「不做未来生效日」与「做守卫」并不冲突：守卫正是首期无未来生效日时对缓存不一致 / 取消漏跑的兜底。
- 不做用药医嘱/剂量与状态的联动（医生处方文本解析）。
- 不做跨设备同步 / 云端状态历史。
- 不改 `MedicationLog` 的既有撤销=物理删除语义；不改打卡主流程。
- 不迁移/覆写既有的 `isArchived` 历史；迁移**只**为归档遗留药补种一条**无日期**的 `LEGACY_BASELINE`（**不伪造日期**，§12.1）。
- **自动核对不写用户核对日期**；核对**不产生状态事件、不作为服药证据**；`lastAutoReconciledAtMs`/`lastUserCheckedDate` **均不进依从率**。

---

## 16. 验收

1. 批量停用（多选）+ 生效日 + 原因 → **一个事务**写入，全部选中药立即从今日计划/小组件消失，提醒取消。
2. **生效日 ≠ 录入时间**：倒填（生效昨天、今天录入）后，昨天/今天**不再**被算成漏服；**历史/日历显示暂停区间（「暂停（不计漏服）」）**，依从率**按剂次扣除暂停时点**（§6.1/§6.2）。
3. 批量恢复（可倒填）→ 从恢复日重新排期；当日/未来计划恢复；提醒重排。
4. **两种事实分离**：① 用户主动核对（显式完成）→ `lastUserCheckedDate=今天`、事件行数不变、日志行数不变；② **连续跑系统自动核对（时钟推进）→ `lastUserCheckedDate` 不变**、`lastAutoReconciledAtMs` 前进；③ 二者都不写 `medication_logs`。
5. **历史永不丢**：任何停/恢复/更正/核对操作后，`medication_logs` 行数与内容不变。
6. **多次循环**：停→恢复→再停→再恢复可表达且投影正确。
7. **首期不作未来生效**：UI 禁选 + 校验拒绝未来生效日（错误码 `FutureEffectiveDateNotAllowed`）；今天/过去可接受。
8. **两档语义**：`临时停用` 与 `归档` 共用历史与派生，文案/入口可分。
9. **误记更正**：错生效日/错 kind/重复均可更正，重算历史投影，旧链留存可审计，日志不变。
10. **取代链正确性**：多层/VOID/重复/冲突/环 全部按 §9.1 通过；**被取代事件永不生效**（§5.2 回归）。
11. **遗留药**：遗留归档药迁移后恰一条 `LEGACY_BASELINE` 基线行；`t<迁移` 判 `UNKNOWN`、`迁移≤t<D` 判已证 `PAUSED`；**首个 `RESUME@D` 不重解释 `D` 之前**、也**不把迁移前说成暂停**，首个非 RESUME 事件不翻活跃；缓存非真相（§9.4）。
12. **既有停用行为（must still hold）**：对某药**今天**执行停用 → (a) 该药**立即停止提醒**（排程撤销，§1.4）；(b) 该药**统计行为启动**：暂停窗口内其**被暂停的剂次** `counted=false`、不再合成漏服（**逐 dose，非整日**；§6.2），历史屏显示暂停区间（此窗口起点为今天、已知，不是未知）；(c) 恢复后统计从恢复日恢复计入口径。**逐步验收并有测试钉死。**
13. **迁移**：v25 升级 26 零丢失；恢复的 v25 备份正常打开；§12.2 全部测试通过。
14. **遗留基线（更正 I/M）**：遗留归档药迁移后恰一条 `LEGACY_BASELINE`；`t<迁移` 为 **`UNKNOWN`**（**不判暂停、不判活跃**）、`迁移≤t<D` 为已证 `PAUSED`、`D` 起活跃；**迁移前既不重解释为「一直在服」、也不编造为「暂停史」**；`D` 之前剂量日志可见不动（§9.4 / §6.3）。
15. **dose 级一致性（更正 J）**：一日两剂其一在暂停内 ⇒ 投影应服集 == 依从率应服集 == {另一剂}；排除计数 = 1 剂（非整日）；UI 标「部分暂停」（§9.7）。
16. **kind 级变更检测（更正 L）**：`PAUSE→DISCONTINUE` 产生状态事件（非 no-op），UI 标签随之变化；仅同 kind 重复才是 CHECK（§9.6）。
17. **未知区间诚实呈现（更正 M）**：任何界面不出现被算出的开始日、也不把迁移前说成「暂停」；`t<迁移` 的剂次 `counted=false`、单列「状态未知 N 剂」、不合成 `MISSED`（§9.8）。
18. **合流（更正 K）**：随机取代图的派生在任意插入顺序下结果一致；多层链 × 重复声称的组合结果唯一（§9.1）。
19. **零回归**：既有 5 个顶层目的地不变；用药打卡/跳过/撤销、照护事项、提醒时刻与基线一致。
20. **发送前派生校验（更正 N，首期）**：**强制缓存与事件不一致**——把 `medications.isArchived` 置为 `false`，同时事件表含一条「生效日 ≤ now 的活 `PAUSE`」（或遗留药只有 `LEGACY_BASELINE`，判 `UNKNOWN`）→ **依次投递三类用药闹钟**（`MEDICATION_NORMAL` / `MEDICATION_EARLY` / `MEDICATION_FOLLOW_UP`）到 `MedLogAlarmReceiver.onReceive` ⇒ **三类都不发通知、都不续排下一次闹钟**（无任何错误提醒）；**同时** `ACTION_TAKEN` / `ACTION_SKIP` 仍照常写 `medication_logs`；且 `t < 生效时刻` 的同类闹钟**照常提醒**（守卫只抑制生效后）。此条即「缓存与事件不一致 ⇒ 无错误提醒」在**发送端**的可执行证明（配 §2.A 的派生层对抗测试、§7 的 `MedLogAlarmReceiverTest`）。

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

## 18. 终稿一致性检查（逐对核验；第 5 稿同步复检）

| # | 检查的对 | 证据 | 裁决 |
|---|---|---|---|
| 1 | **§5 派生谓词 vs 每一处读「状态」的路径** | 提醒排程（§1.3 A 类）改读 `deriveCurrentStatus`；投影 `FuturePlanCalculator` 与依从率 `MedicationAdherenceCalculator` 都用**同一个 `doseActiveAt`**（§5.3/§6.2）；缓存写回用 `deriveCurrentStatus`（§2.A/§8.1）；**全部走同一 §5 helper**，无第二套判定 | **一致**（派生为唯一真相；projection == adherence） |
| 2 | **§5 派生谓词 vs 缓存 `isArchived`** | 缓存降级为 B/C 类消费者；**不再承担任何遗留回落**（更正 I）；A 类不裸读（§1.3/§2.A） | **一致** |
| 3 | **旧谓词 `supersedesId == null || …` vs 更正 D** | 旧式**已删除**，全篇不再出现该形式；§5.1/§5.2 给出新式与反例 | **已修正**（无残留） |
| 4 | **R1 多层取代 vs「只 C 生效」测试** | §5.1 R1、§9.1.1 | **一致** |
| 5 | **R3 VOID vs「回退到被作废行之前的活状态行」** | §5.1 R3、§11 E15、§9.1.3 | **一致**（确定，不回到作废行本身） |
| 6 | **R4 重复取代平局 vs「败者遮蔽保留」** | §5.1 R4、§11 E16、§9.1.4；append-only 不删行 | **一致** |
| 7 | **R5/R6 冲突 vs 环防止** | 冲突由全序确定性解决（顺序无关测试），环由 `supersedesId<自身 id` 结构禁止（§4/§5.1 R6/§9.1.6） | **一致**（既确定性又结构禁止） |
| 8 | **§5 派生 vs 提醒路径（指定复核，第 6 稿复核）** | **排程端**：A 类三读者（`AndroidReminderReconciler.kt:40/88`、`FuturePlanCalculator.kt:50`）统一读 `deriveCurrentStatus`/`statusAt`（`!= ACTIVE` 即抑制提醒）（§1.3、§13「修改」）；**发送端**：`MedLogAlarmReceiver.handleMedication` 顶部同一谓词早退（§7、§13、§14 T10）。缓存只在写回时被派生覆盖，**任何**决策路径都不裸读它 | **一致**（缓存不一致对提醒面零影响，两级同谓词） |
| 9 | **§5 派生 vs 依从路径（指定复核）** | `MedicationAdherenceCalculator` 逐 occurrence 用 `doseActiveAt` 决定进不进分母，不合成 MISSED（§6.2）；**与投影同一 helper**（§9.7 property） | **一致** |
| 10 | **D11「首期不接受未来生效日」 vs 全篇 phase-1 文本** | §0 决策 3、§0.1.3、§2.A.2、§3 D7/D11、§7（**守卫首期交付**，但未来生效日本身仍属 Phase 2）、§11 E5、§14 Phase1/Phase2、§15、§16.7 全部口径为「首期拒绝未来生效日」；**无残留**「首期可上未来生效」措辞 | **一致**（无矛盾；「首期交付守卫」与「首期不做未来生效日」不冲突） |
| 11 | **D11 vs Phase 2 计划章节（第 6 稿修订）** | §7 标题改为「**首期交付**」；守卫移入 §13「修改」与 §14 T10；§14 Phase 2 与 §13 Phase 2 只留「解除未来生效日限制」（发布闸门 = 已交付的守卫 + 其测试）；二者**互补不冲突** | **一致**（守卫首期落地，Phase 2 只剩放行条件） |
| 12 | **R7 遗留基线 vs「事件权威」规则（指定复核）** | 基线 = 事件表里的一条 `LEGACY_BASELINE` 行（生效日 NULL）；**不存在**「事件为空」特例（§5.1 R7、§12.1）；缓存非真相 | **一致**（基线与事件同表同谓词，无第二真相来源） |
| 13 | **§8 两种事实 vs 依从率/日志** | `lastAutoReconciledAtMs`/`lastUserCheckedDate` **均非** `medication_logs` 行、**均不被** `MedicationAdherenceCalculator` 读取；§8/§9.3/§15/§16.4 明写 | **一致** |
| 14 | **§8 自动通道 vs 用户核对日期** | 自动通道写 `lastAutoReconciledAtMs` **且**对其「不得写 `lastUserCheckedDate`」有硬约束 + 单写者/不移动测试（§8.1/§8.3/§9.3） | **一致**（自动不能移动用户日期） |
| 15 | **§6 暂停区间呈现 vs「统计变化可解释」** | 历史/日历显式暂停条带 + 「已调整」标记（§6.1）；依从率按剂次扣除暂停时点 + 前后数值差（§6.2） | **一致**（非静默） |
| 16 | **决策 2 的重算历史 vs 决策 1 的 append-only** | 重算只**读**事件链派生应服/依从率，**不改**事件行、**不删**日志（§5/§6/§9.2） | **一致** |
| 17 | **更正 C 的变更模型 vs 更正 A「事件 append-only」** | C 采纳**追加取代事件**（不就地编辑、不物理删除），`supersedesId` 只增加行（§2.C/§4/§9.2） | **一致** |
| 18 | **第 1 稿 D3/E8「计划段边界=生效日/有序插入」 vs 更正 B** | 已用 D3'（正交状态层）替换，E8 重写为「状态层正交，计划链不动」（§2.B、§3 D3'、§11 E8） | **已修正**（冲突消除） |
| 19 | **决策 5 移动脚本 vs「只改本文件」** | 移动的是**未被 git 跟踪**的 `tools/` 脚本（非业务代码、非提交）；仓库唯一跟踪变更 = 本文件（§17、§19） | **一致** |
| 20 | **`updatePlan` 不变 vs 倒填需要边界** | 倒填边界改由**事件层**（`effectiveAtMs`）承载，计划链只为剂量调整服务（§2.B/§3 D3'） | **一致** |
| 21 | **新基线机制 vs「事件权威」规则（指定复核）** | 基线是 `medication_status_events` 里的一条普通 append-only 行；`deriveCurrentStatus(events, now)` 只读事件、**无 `legacyArchived` 参数**；与事件同谓词、同表、无第二真相来源（§5 R7、§12.1） | **一致** |
| 22 | **新基线机制 vs 它取代的旧遗留回落文本** | 旧 `deriveCurrentStatus(events, now, cache, legacyArchived)`、`if (events.isEmpty()) legacyArchived`、「零事件回落」「自动核对对零事件药跳过写缓存」**全篇已删除/改写**（§1.3/§2.A/§3 D12/§5 R7/§8.1/§12.1） | **已修正**（无残留） |
| 23 | **共享 dose 级谓词 vs 全篇每一处整日（day 级）陈述（指定复核）** | 旧「暂停日不计入分母 / `pausedAt(events, dayStart)=true` ⇒ `counted=false` / 扣除暂停 N **天**」**逐处改为 dose 级**（§5.3/§6.1/§6.2/§13/§14/§16）；仅「整日暂停」作为**全部 occurrence 皆暂停**的特例保留，并新增「部分暂停」 | **一致**（无 day 级口径残留） |
| 24 | **加宽的 kind 变更检测 vs「CHECK 不写事件」规则（指定复核）** | CHECK 的判据同为 `desiredKind == liveStatusKind`（同 kind）；`PAUSE→DISCONTINUE` 因 kind 不同走 CHANGE（写事件、写缓存，§5.4/§9.6）；CHECK **仍**只写 `lastUserCheckedDate`、不写事件、不写缓存（§8.2） | **一致**（二者共用同一 kind 判据，互不越界） |
| 25 | **迁移前未知区间显示 vs UI 与可解释性（第 5 稿修订）** | 迁移前渲成「状态未知（早于本次迁移）」、起点开放式、**不打印被算出的开始日**、**不称暂停**（§6.1/§6.3）；可解释性说明「迁移前状态未知、不计入依从率」，二者分列（§6/§9.8） | **已清理**（未知 ≠ 暂停；不编造起点） |

| 26 | **遗留机制单一 vs「零事件回落缓存」（第 5 稿）** | 全篇只保留 `LEGACY_BASELINE` 事件机制；后者（`legacyArchived` 读缓存 / `if (events.isEmpty())` 回落 / 自动核对对零事件药跳过写缓存）的实现性文字在 D2 / D4 / §7 已删（§0.3.1、D12、§5 R7） | **已清理**（单一机制） |
| 27 | **迁移前 `UNKNOWN` vs 旧「暂停·开始未知」文案（第 5 稿）** | §5 R7 引入 `statusAt`：`t<M` = `UNKNOWN`、`t≥M` = `PAUSED`；§6.1 / §6.3 / §9.8 / §11 E18 / E20 / §16.11 / 16.14 / 16.17 文案统一为「状态未知（早于本次迁移）」，全篇不再有「暂停·开始未知」 | **已清理**（未知 ≠ 暂停） |
| 28 | **迁移 SQL 精确 schema vs §4 实体 与 §12.2 测试（第 5 稿）** | §12 列全表 / 列 / 可空性 / 索引 / kind 取值 / 写与不写；与 §4 逐列一致；§12.2 测试断言「恰一条 `LEGACY_BASELINE`、其它药不补种」 | **一致** |
| 29 | **缓存不一致 ⇒ 错误提醒？（第 6 稿定向复核）** | 分歧来源只有两个：`reconcile` 两步之间漏跑（`MedicationDetailViewModel.kt:139-147`）与缓存列被直写（§0.4）；排程端读派生（§1.3 A 类）+ 取消无条件且在判定前（`AndroidReminderReconciler.kt:34/39`）+ **发送端守卫**（§7）三级同谓词；`AlarmScheduler.cancelAllAlarms`（`AlarmScheduler.kt:237-259`）/`cancelAllReminderNotifications`（`NotificationHelper.kt:411-415`）真的撤销闹钟与已上屏通知 | **已修正**（guard 由 Phase 2 提升为首期，更正 N） |
| 30 | **更正 N（守卫首期）vs 全篇「Phase 2」旧措辞（第 6 稿）** | §0 账本 / §0.4 / §1.3 / §1.4 / §2.A / §3 D7 / §7 标题与裁决 / §11 E5 / §13 / §14 / §15 / §16.20 / §18 行 8/10/11 全部同步；**唯一保留的 Phase 2 项**是「未来生效日的精确定时」与其放行闸门 | **已清理**（无「首期不做守卫」残留） |

**第 5 稿同步复核结论**：第 4 稿只在 §0.2 落定方向、正文未同步，故表行 21–25 当时的「一致」是**未核验即宣称**（本轮已按 §0.3 逐项落点：① 遗留机制单一化 → 表行 2/12/21/22/26；② dose 级谓词三面一致、清 day 级残留 → 表行 1/9/23；③ 迁移前判 `UNKNOWN` 而非 `PAUSED` → 表行 12/25/27；④ 迁移 SQL / 测试 / 验收对账 → 表行 28 与 §12）。**本轮属「已清理」而非「已证明」**：仍不可证 / 未能解决项见 §19。

**第 6 稿定向复核结论**：表格行 8（提醒路径）、行 10/11（Phase 2 措辞）本轮**逐处重核并修订**，新增行 29/30。裁决 = **缓存不一致会**导致错误提醒（情形 ② 直写缓存列是唯一会**自愈失败**的一类，因为自动核对把缓存收敛成派生、方向相反），**发送前派生校验守卫因此从 Phase 2 提升为首期**（更正 N）；§14 T10 与 §16.20 给出可执行验收。本轮同样**只做静态对账、未跑测试**（本稿只改文档）。

---

## 19. 本轮变更与验证

- **只改本文件**：`git status --short` 仅 `M docs/medication-status-spec.md`（`tools/` 已按 §17 移出；仓库内**无业务代码改动**）。
- **第 6 稿（本轮）唯一实质修订 = 更正 N**：把§7 的**发送前派生校验守卫**从 Phase 2 计划**提升为首期交付**，理由是真实代码裁决出缓存不一致有两个来源、且其中一个（缓存列被直写）**无法靠 reconcile 自愈**（自动核对把缓存收敛成派生，方向相反），故首期若无该守卫就没有兜底（§0.4）。
- **第 6 稿同步落点**（逐处，避免「决策块改了、正文没改」）：§0 账本（新增更正 N 行）、§0.4（复核 + 裁决 + 交接窗）、§1.3（两个分歧来源）、§1.4（结论）、§2.A（第 2 点 + 发送端对抗测试）、§3 D7、§7（标题 / 定位 / 裁决 / 残余 / 测试标题）、§11 E5、§13（守卫移入「修改」）、§14（T10 + Phase 1 标题 + Phase 2）、§15、§16.20（验收）、§18（行 8/10/11 修 + 新增 29/30 + 第 6 稿结论）。
- **行数**：第 4 稿 802 → 第 5 稿 853 → 本稿 **见 `wc -l`**（新增 §0.4、更正 N 相关落点与 §16.20）。
- **保留的第 5 稿同步清理四点（§0.3）**：① 只留 `LEGACY_BASELINE` 事件机制、删「零事件回落缓存」；② dose 级谓词为投影 / 依从率 / 历史 UI 三面唯一判据、删 day 级残留；③ 迁移前判 `UNKNOWN`（不是 `PAUSED`）；④ 迁移 SQL / 测试 / 验收 / 正文四者对账。**保留的第 4 稿五项修订（§0.2）**：更正 I / J / K / L / M。
- **冻结与推送**：本稿即**终稿**，状态行已标「已冻结（第 6 稿）」；提交并推送（含早前仅本地的 `2c68f5a`、`8393f97`、`757c7d9`）。
- **仍不可证 / 未能解决（诚实清单）**：
  1. 「迁移前该药是否真的在服」**不可知**——本稿只判 `UNKNOWN`，不试图恢复。
  2. `medications.isArchived` 是可变缓存；缓存列被**外部直写**（脚本 / 调试种子 / 未改造的写入者）时，**派生无法识别**——更正 N 的守卫能保证它**不产生错误提醒**（守卫读派生），但**不能**把被抹掉的归档状态找回；这类写入**不在本仓任何正常路径上**，属运维 / 遗留工具的越权写入，仅记录、不设计兜底。
  3. 真机行为（提醒取消时延、小组件刷新窗口、已上屏旧通知的撤走时机）**文档内不可证**，须 §14 T9 实测。
  4. 本稿的复核是**静态对账**：所有 `file:line` 均已逐处打开源码核对（`MedLogAlarmReceiver.kt`、`AndroidReminderReconciler.kt`、`AlarmScheduler.kt`、`NotificationHelper.kt`、`MedicationDao.kt`、`MedicationRepositoryImpl.kt`、`MedicationDetailViewModel.kt`、`MedLogBootReceiver.kt`、`MedLogApplication.kt`、`ReminderReconciliation.kt`、`ReminderReconciliationWorker.kt`、`WidgetRefreshWorker.kt`、`CareEventReminderEngine.kt`），但**未跑任何测试、未触真机**（本稿只改文档；T1–T10 属实现轮）。
