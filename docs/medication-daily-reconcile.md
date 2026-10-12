# 今日用药核对（批量确认「仍在服用」）—— SDD 规格

状态：**已定稿（用户已裁决 §15 两项；本轮据裁决修订，只改本文件。docs-only：不改业务代码、不提交、不推送）。**
**本轮更正（跨存储原子性，§1.6 / §4.5）：** 修正 §4.2 / §4.3 / D12 原先「整次核对（含 `lastUserCheckedDate`）一个事务」的**错误**承诺——`lastUserCheckedDate` 存在 **DataStore**（非 Room），Room 的 `withTransaction` **无法回滚**它。事实边界 = **Room 侧（事件 + 缓存 + 事务内校验）= 一个事务**；**核对日期 = 事务提交后的独立事实（post-commit）**。详见 §1.6、§4.5。
范围：`我的药品` 页新增一个**子页面 / 子路由**「今日用药核对」：一次**批量确认「哪些药仍在服用」**，按当前状态预勾选、提交前先出**变更摘要**、只对**真正变化**的药写状态事件、绝不自动停用未被触碰的药、原因字段**不预填**。
关联（冻结稿，只读引用）：`docs/medication-status-spec.md`（批量停用 / 恢复、`medication_status_events`、kind 级变更检测、两种事实分离、D11 日期约束）；`docs/medication-daily-flow.md`（今日计划打卡主流程，**非本流程**）。

> **一句话定义（贯穿全文、UI 文案与测试）**：本流程是**对「哪些药仍在服用」的批量确认**，**不是**「今天这一剂药到底吃没吃」的记录。它**绝不写 `medication_logs`**，**不是**任何剂次的依从证据。

> **本轮三项已裁决事项（贯穿全文；§15 已无待决项）**：
> 1. **不变的确认也记录「今天已核对」**（§2 D5）。故本文（含反例 / 测试）所称「**零写入**」一律精确指「**零状态事件 + 零剂量日志**」，**不排除**用户核对日期 `lastUserCheckedDate` 的写入（§1.2、§5、§9 C1/C2、§13）。该写入是 **DataStore 上的事务外 post-commit 弱事实**（§4.5），**不**随 Room 事务回滚。
> 2. **整次核对的 Room 侧写入 = 一个事务**（§4.2）：混合停用与恢复的状态事件与缓存**要么全落、要么全不落**。复用既有机制、**不改冻结数据模型**（不改 Room schema、不加 kind、不改派生与 append-only 规则）；因混合 kind 单事务需要一个**统一的应用层入口点**，其**精确事务边界**见 §4.3，并**点名**冻结稿中需修订的句子（`docs/medication-status-spec.md` §11 边界情形 **E10**「批量的原子性」）。**核对日期 `lastUserCheckedDate` 不在该事务内**（DataStore，事务外 post-commit，§4.5）。
> 3. **三条必须钉死的边界**（各配规则 / 反例 / 测试）：事务内检测并中止的**并发状态变更**（§7 E15）、`UNKNOWN ≠ PAUSED` 决定**遗留药复选框当前态**（§7 E16）、误触「**全停**」的**显式护栏**（§7 E17）。

> **本轮三项细化（用户反馈；只改本文件 + 冻结稿 E10 的最小措辞）**：
> 1. **③ 与 ④ 独立处理失败**（§4.3 步骤 3、§4.5.1 A4）：核对日期的 DataStore 写入与 post-commit reconcile 各有自己的失败路径与 warn 日志；**核对日期写失败不得阻断 reconcile**，反之亦然。核对日期失败只让「今天尚未核对」提示**滞后**（弱事实，§4.5），提醒仍照常重排。
> 2. **事务内校验覆盖摘要里显示的每一味药**（含**未变更**者；D13、§4.3 步骤 2①、§7 E15）：**显示为「未变化」的药若在提交时已被外部改变，同样判为过期 ⇒ 中止 + 重建**，绝不把陈旧摘要呈现为已兑现。
> 3. **核对日期钉在事务验证过的 `summaryDay`**（§4.3 步骤 1/3③、§6、§4.5.1 A5）：提交后**只用**事务内校验通过的 `summaryDay` 写 `lastUserCheckedDate`，**绝不**重读 `today()`（否则跨零点确认会把新日盖到按旧日校验的事务上）；新增跨零点用例 **C21**。
>
> **随附（已获批准并**已应用**）**：对冻结稿 `docs/medication-status-spec.md` §11 **E10** 行应用**最小措辞修订**——(i) 同批可混合 kind；(ii) `lastUserCheckedDate` 为**事务外 post-commit** 事实、不在该事务边界/回滚内（§4.3）。

---

## 0. 触发场景

医生**每日调整处方**，照护者每天需要过一遍「这些药今天还吃不吃」。冻结的批量停用 / 恢复（`docs/medication-status-spec.md` §10 / §14 T8）已经能多选 + 选 kind + 生效日 + 原因，但**对照护者的日常动作太重**：他要知道每味药「现在是什么状态」，再想「该点停用还是恢复」，再逐味多选。真实动作是**逐条「还在吃 / 不吃了」的勾选**，一次坐下点完。

本流程就是把这个动作做成一屏：**列全部药、按当前状态预勾选、只动改过的那几个、提交前给他看看到底会变哪几味。**

---

## 1. 现状事实（代码核查，file:line）

> 规则同冻结稿：每条结论都能在源码里打开。本节只描述**现状**，不夹带方案。

### 1.1 我的药品的批量选择 UI 与 ViewModel

| 事实 | 位置 |
|---|---|
| 列表页入口在顶层目的地 `Route.MyMedications`（第 3 个 tab），NavHost 注册处 | `ui/navigation/MedLogDestinations.kt:16`、`:89-102`、`ui/MedLogApp.kt:252-259` |
| 「批量操作」入口 = 过滤器行里的一个 `FilterChip`，`onClick` 翻转 `selectionMode`（**不是路由**） | `feature/medications/list/MyMedicationsScreen.kt:129-136` |
| 进入选择模式后每行显示 `Checkbox`，点卡片切换选中 | `MyMedicationsScreen.kt:73-75`、`:185`、`:195-200` |
| 底部批量动作行：批量停用 / 批量恢复 / 批量归档三个 `Button`，各自把 `pendingKind` 设为对应 kind | `MyMedicationsScreen.kt:243-260`（`:249` pause、`:252` resume、`:255` discontinue） |
| 确认对话框 `BatchStatusDialog`：**生效日预填今天**、**原因字段预填 `""`（未预填）**、`onConfirm` 时 `reason.ifBlank { null }` | `MyMedicationsScreen.kt:264-274`、`:285-329`（`:286` 日期预填、`:287` 原因空、`:320` ifBlank→null） |
| 页面把选中集合一次性交给 VM：`onBatchStatusChange(selected.toSet(), kind, date, reason)` | `MyMedicationsScreen.kt:267-268` |
| VM 的 `applyStatus(ids, kind, date, reason)` → `setStatus.applyBatch(...)`（**空集合直接 return**） | `feature/medications/list/MyMedicationsViewModel.kt:68-73` |
| 列表数据源 = `repository.getAllMedications()`（**含归档全量**），每药的**活状态 kind** 由 `liveStatusKind(events, now)` 现算，用于行内角标 | `MyMedicationsViewModel.kt:41`、`:51-53` |
| 行内角标：`liveStatusKind` = `PAUSE`→「暂停中」、`DISCONTINUE`→「已归档」，其余无角标 | `MyMedicationsScreen.kt:278-282` |

**结论（现状）**：批量入口是**页面内模式**，能力（多选 + kind + 生效日 + 原因）已齐；**唯一缺口是「一次动作只能给一个 kind」**——现对话框对**全部选中药**施加**同一个** `kind`（`MyMedicationsScreen.kt:249/252/255` → 单值 `pendingKind` → 单次 `applyBatch(list, kind, …)`）。「今日核对」需要**同一批里既有停用又有恢复**，这是唯一必须新增的逻辑。

### 1.2 批量停用 / 恢复路径 + `SetMedicationStatusUseCase`

| 事实 | 位置 |
|---|---|
| 合法目标 kind = `{PAUSE, DISCONTINUE, RESUME}`（不含 VOID），非法即 `require` 失败 | `feature/medications/application/SetMedicationStatusUseCase.kt:25-29`、`:84` |
| 生效日约束：`effectiveDate > today(zone)` 抛 `FutureEffectiveDateNotAllowed`；`effectiveAtMs = 生效日当地 0 点` | `SetMedicationStatusUseCase.kt:22`、`:86-87` |
| 成员门槛：`activeRecipient.current()` 必须 ≠ `NO_RECIPIENT`，否则 `check` 失败 | `SetMedicationStatusUseCase.kt:89-90` |
| **一次 `applyBatch` 内一个事务、N 个药、同一 kind**；逐药 `getEventsOnce` → 比较**活状态 kind** → 不同才写 | `SetMedicationStatusUseCase.kt:92-114` |
| 同 kind ⇒ `MedicationStatusWriteResult.UserChecked`（**不写事件、不写缓存**）；不同 ⇒ `EventRecorded`（append 事件 + 同事务直写缓存 `setArchivedCache`） | `SetMedicationStatusUseCase.kt:32-38`、`:97-112` |
| 只要结果里**有任一** `UserChecked`，就写**用户事实** `lastUserCheckedDate`（成员级）——写在 `withTransaction` **块之外**（事务提交**之后**、DataStore） | `SetMedicationStatusUseCase.kt:116-119`（事务块为 `:93-114`） |
| 每个 `EventRecorded` 逐药触发一次即时 reconcile | `SetMedicationStatusUseCase.kt:120-122` |
| 另有 `userCheck(id, kind, date)` = 单药 `applyBatch` 的便捷包装——**仍然一个 kind** | `SetMedicationStatusUseCase.kt:68-73` |
| `TransactionRunner.withTransaction` 提供批量原子性——**仅 Room**（`db.withTransaction`；DataStore 不参与） | `SetMedicationStatusUseCase.kt:93`；`data/local/TransactionRunner.kt:16` |
| 缓存写入接口 `MedicationRepository.setArchivedCache(id, archived)`（语义 = `deriveCurrentStatus`） | `data/repository/MedicationRepository.kt:38` |
| 归档缓存直写 `archiveMedication/unarchiveMedication`（`UPDATE medications SET isArchived=…`，不动计划链） | `core/database/.../local/MedicationDao.kt:96-100` |

**结论（现状）**：`applyBatch` 是「N 个药 + **1 个 kind**」的**幂等、kind 级变更检测、带缓存直写与 reconcile** 的完整命令，内部自带一个 `withTransaction`（`:93`）。**本流程所需的「混合 kind、同一事务」不是它现成的能力**：按 kind 分两组各调一次 = **两个事务**，中间存在唯一的崩溃窗；而本流程已裁决为**整次核对的 Room 侧写入一个事务**（§4.2），故需把写入**统一到一个应用层入口点** `applyStatuses(...)`（`applyBatch` 退化为薄包装，§4.3），复用其全部内部机制（`liveStatusKind` diff、`insertEvent`、缓存直写、reconcile 触发点），**不改数据模型、不改 `applyBatch` 对外语义**。
**两个必须注意的现状细节**：① `applyBatch` 只在**存在 `UserChecked`** 时写 `lastUserCheckedDate`（`:116-119`）；若本次核对**全部都是真实变更**（全 `EventRecorded`），它**不会**写用户核对日期——故「今天已核对」这一事实需要一个**极薄的写入**（§2 D5）。按裁决 1，**不变的确认也要记录「今天已核对」**：本流程的「零写入」= **零状态事件 + 零剂量日志**，**登记核对日期不算写入**；**极薄的新入口**（§2 D5）。② **该写入在 `withTransaction` 块之外**（`:93-114` 是块，`:116-119` 在块后）——即**它今天就已经是事务外的 post-commit 事实**，**不**随 Room 事务回滚；本文档原先把它写进事务边界是**错的**，本轮更正（§1.6、§4.5）。

### 1.3 变更检测（活状态 kind）

| 事实 | 位置 |
|---|---|
| `liveStatusKind(events, now)` = 活行里状态承载 kind 中 `effectiveAtMs ≤ now` 的 `ORDER` 最大者；**VOID 不承载** | `feature/medications/application/MedicationStatusDerivation.kt:96-99` |
| `ORDER = (effectiveAtMs ?: MIN, recordedAtMs, id)`（基线恒被带日期行压过） | `MedicationStatusDerivation.kt:56-60` |
| `statusAt` 三值：`PAUSE/DISCONTINUE`→`PAUSED`、`RESUME`→`ACTIVE`、`LEGACY_BASELINE`（t≥M）→`PAUSED` /（t<M）→`UNKNOWN`、无候选→`ACTIVE` | `MedicationStatusDerivation.kt:73-84` |
| `doseActiveAt(events, scheduledInstant)` = `statusAt == ACTIVE`（**唯一** dose 级谓词） | `MedicationStatusDerivation.kt:92-93` |
| `deriveCurrentStatus(events, now)` = `pausedAt`（缓存语义，不读缓存） | `MedicationStatusDerivation.kt:102` |
| 转换矩阵：`desired == live kind` ⇔ CHECK（无事件）；`desired ≠ live kind` ⇔ CHANGE（写事件）。**注意 (无活状态行) × RESUME = CHANGE** | `docs/medication-status-spec.md §5.4` |

**结论（现状）**：判定「变化与否」的唯一权威是**活状态 kind**，且**布尔（暂停与否）不是判据**。**关键约束**：若把「勾选」朴素映射成 `RESUME`、把「不勾选」映射成 `PAUSE`，则**一个从未停过的在用药被判 CHECK** 没问题，但**一个无事件的新药（live kind = null）被勾选 = RESUME ≠ null = CHANGE** ⇒ 会**凭空写一条 RESUME 事件**（§9 反例 C6）。故分流判据必须是**意图布尔 vs 当前 active/inactive**（§4），不能是「勾选→RESUME」。

### 1.4 `medication_status_events` 写入路径与派生

| 事实 | 位置 |
|---|---|
| append-only 事件表 + `MedicationStatusKind` 常量（含 `VOID`、`LEGACY_BASELINE`） | `core/database/.../model/MedicationStatusEvent.kt:16-74` |
| `insertEvent` 前置校验：可空不变量、`supersedesId` 同药且更旧 | `core/database/.../local/MedicationStatusEventDao.kt:67-85` |
| 仓储：`observeEvents / getEventsOnce / observeAllEvents / getEventsForRecipientOnce / insertEvent`（**无 update/delete**） | `data/repository/MedicationStatusRepository.kt:12-31` |
| 全库事件响应式流（列表页已订阅它算角标） | `MedicationStatusRepository.kt:24`、`MyMedicationsViewModel.kt:43` |
| 派生纯函数 `liveEvents / statusAt / liveStatusKind`（整链一次算完、顺序无关） | `MedicationStatusDerivation.kt:31-99` |
| 自动核对（系统通道）**只写缓存 + `lastAutoReconciledAtMs`**、不写事件、不写 `lastUserCheckedDate` | `feature/medications/application/ReconcileMedicationStatusUseCase.kt:32-53` |

**结论（现状）**：写事件 = `SetMedicationStatusUseCase` 的 `EventRecorded` 分支**唯一**入口；派生 = `MedicationStatusDerivation` **唯一**模块。本流程**不得**绕开它们直插事件。

### 1.5 列表的归档 / 活跃过滤

| 事实 | 位置 |
|---|---|
| 页面用**局部状态** `archived: Boolean` 过滤：`visible = state.medications.filter { it.isArchived == archived }`（默认 `archived=false`=进行中） | `MyMedicationsScreen.kt:67`、`:71`、`:119-128` |
| `state.medications` 本身是**全量含归档**（`getAllMedications`） | `MyMedicationsViewModel.kt:41`；`MedicationRepository.kt:14`；`MedicationDao.kt:21-25` |
| SQL 口径：活跃 `isArchived=0` / 归档 `isArchived=1` | `MedicationDao.kt:12-19`、`:27-28` |

**结论（现状）**：全量清单**已经在 `state.medications` 里**；页面默认只渲染其中一半。核对页要用**全部**，直接吃 `state.medications`（或新 VM 里 `getAllMedications()`），**不套 `archived` 过滤**。

### 1.6 用户核对事实（`lastUserCheckedDate`）——**存储核查（跨存储原子性）**

| 事实 | 位置 |
|---|---|
| 偏好接口：`userCheckedDate(recipientId)` / `setUserCheckedDate(recipientId, epochDay)`（成员分片；`medication_status_user_checked_date#<id>`） | `data/repository/MedicationStatusPreferences.kt:14-15`、`MedicationStatusPreferencesImpl.kt:25-40` |
| **实现落在 DataStore（不是 Room）**：`setUserCheckedDate` → `context.settingsDataStore.edit { … }` | `MedicationStatusPreferencesImpl.kt:38-40` |
| `settingsDataStore` = `preferencesDataStore(name = "medlog_settings")`，即 **Preferences DataStore** 单例（与 Room `MedLogDatabase` 是**两个不同的存储**） | `data/local/SettingsDataStore.kt:12-14` |
| 批量写入的事务运行器 = **Room 专用**：`RoomTransactionRunner.withTransaction` → `db.withTransaction`（`androidx.room`） | `data/local/TransactionRunner.kt:16` |
| 写者**唯一** = `applyBatch` 的 `UserChecked` 分支，且该写入在 `withTransaction` **块外**（块 `:93-114`，写入 `:116-119`） | `SetMedicationStatusUseCase.kt:116-119` |
| 冻结稿语义：**不是服药证据**、不进依从率；文案「已核对医嘱（非服药记录）」已存在 | `docs/medication-status-spec.md §8.2`；`res/values/medication_flow_strings.xml:68` |
| **当前没有任何 UI 读它**（全库仅偏好实现与测试用例引用） | `grep userCheckedDate app/src/main` 仅命中 `MedicationStatusPreferences*.kt` |

**结论（现状，跨存储）**：`lastUserCheckedDate` 存于 **DataStore（`medlog_settings`）**，状态事件与 `isArchived` 缓存存于 **Room（`MedLogDatabase`）**；`TransactionRunner.withTransaction` 只开 **Room** 事务。**两存储之间不存在任何共享事务——跨存储原子性在当前代码下不可能**，除非把该事实迁进 Room（需新表 + v26→v27 迁移，§4.5「被否方案 b」）。故 §4.2 / §4.3 / D12 原先「整次核对（含核对日期）一个事务」的承诺**必须更正**：核对日期是 **Room 事务提交之后的独立事实**。此外，「今天已核对」这一事实**已存在、已有键、已有文案**，但**尚无读者**——正好供本流程写、供 `我的药品` 页读。

### 1.7 可复用 as-is vs 需薄新增（裁决）

| 能力 | 复用 as-is | 需薄新增 |
|---|---|---|
| 多选 + 生效日 + 原因 UI | ✅ 模式照搬 `MyMedicationsScreen.kt:129-136/243-274`（核对页改为**逐行复选框**而非「先多选再选 kind」） | 新屏 |
| N 个药 + 1 个 kind 的幂等写入（kind 级 diff、事件+cache 同事务、reconcile） | ✅ 复用 `applyStatuses` 内部机制（diff / `insertEvent` / 缓存直写 / reconcile）；`applyBatch` 变薄包装 | 需**多 kind 单事务**时：加 `applyReconcile(...)`（同走 `applyStatuses`，§4.3） |
| 派生 / 活状态 kind / dose 级谓词 | ✅ `MedicationStatusDerivation` **原样** | — |
| 事件流 / 一次性读 | ✅ `MedicationStatusRepository.observeAllEvents / getEventsOnce` | — |
| 「今天已核对」事实 | ✅ `MedicationStatusPreferences` | 加一个 `markUserChecked()` 入口（§2 D5） |
| 生效日 = 今天、拒绝未来日 | ✅ `applyBatch` 内建（`:86`）；核对页**不给日期选择器** | — |
| 状态事件审计链 | ✅ 复用；**不新增第二机制** | — |
| 入口（非新 tab） | ✅ 顶层 5 目的地**不动**（`NavigationHierarchyTest.kt:10`）；核对页是**子路由** | 新 `Route` |
| 「今天尚未核对」提示 | — | 读 `userCheckedDate` 的 1 行逻辑 + 文案 |
| 四语 / 无障碍文案 | ✅ 沿用 `medication_flow_strings.xml` 四语惯例 | 新字符串 ×4 |

---

## 2. 设计决策（含被否方案）

**D1 — 本流程 = 「仍在服用的批量确认」，不是剂量记录。** UI 标题「今日用药核对」、每一屏都带**显式免责文案**（§3），测试断言**不写 `medication_logs`**（§10）。**被否**：把它做成「今日打卡」的快捷入口——那会混淆「应服/已服」与「是否继续吃」，且违反冻结稿「核对不是服药证据」（`medication-status-spec.md §8.2`）。

**D2 — 列出成员的全部药（活跃 + 已停用/归档），按当前状态预勾选。** 活跃 ⇒ 预勾选；已停用/未知 ⇒ 预不勾选。**被否**：只列在用药（照护者需要「把停了的重新试回」时看不到它）；复用页面 `archived` 过滤（`MyMedicationsScreen.kt:71`）会给出一条半清单。

**D3 — 同一批里允许混合 kind。** 分流判据 = **意图布尔 vs 当前 active/inactive**，而不是「勾选→RESUME / 不勾选→PAUSE」（见 §1.3 的 `null × RESUME = CHANGE` 陷阱）：
- 停用组 `toPause` = {未勾选 ∧ 当前 ACTIVE}；
- 恢复组 `toResume` = {勾选 ∧ 当前非 ACTIVE（PAUSED 或 UNKNOWN）}；
- 其余 = 无变化。

**被否**：给 `applyBatch` 加一个「kind 映射」参数（要改冻结用例的内核，扩大回归面）；把每味药各自调一次单药 `apply`（N 个事务，失去批量原子性）。

**D4 — 生效 = 今天（不复用日期选择器）。** 两组的 `effectiveDate` 都取**确认时的当地日**，写入层把它换算为当地 0 点的 `effectiveAtMs`——即冻结稿既有路径（`SetEffectiveDate` 机制**不改**，只是本流程**不暴露**日期输入）。**被否**：允许核对时倒填/未来日——冻结稿 D11 已明令首期无未来日，倒填在本流程无意义（照护者核对的是「今天还吃不吃」）。

**D5 — 「今天已核对」事实：给用例加一个极薄的 `markUserChecked()`；按裁决 1，不变更的确认也记录。** 现状 `applyBatch` 仅在**存在 `UserChecked`** 时写 `lastUserCheckedDate`（`:116-119`，**事务块之外**）；本流程按 D3 只把真正变化的药交给写入，命中全是 `EventRecorded`，**不会**写用户核对日期。故新增：
```kotlin
// SetMedicationStatusUseCase
/** 记录「用户今日已主动核对」（成员级用户事实，冻结稿 §8.2）。幂等：同日重复写同值。 */
suspend fun markUserChecked() { /* recipient 守卫 + preferences.setUserCheckedDate(recipientId, today(zone).toEpochDay()) */ }
```
它与 `:118` **同一处写入、同一把键、同一 post-commit 语义**（`markUserChecked()` = `applyStatuses(空映射, …, CheckedDatePolicy.ALWAYS)`，§4.3），不引入第二事实、**不进任何 Room 事务**。**本流程的「零写入」= 零状态事件 + 零剂量日志，明确允许并预期这次核对日期的写入**（裁决 1）。**被否**：让核对页在无变化时也 `applyBatch(全部, 某 kind)` 去「蹭」一个 CHECK——对无事件新药会**凭空写事件**（§1.3），不安全；以及「不变的确认连核对日期都不写」——那会让「今天尚未核对」提示永不消失，与「确认即完成一次核对」相悖。

**D6 — 只写真正变化的药；未被触碰的药绝不自动停用。** 命门三条：
1. 写入只接收**变化组**（D3 的 `toPause`/`toResume`），**预勾选、未被改动**的药不进任何变更组 ⇒ 无状态事件；
2. 照护者**今天从不打开**本流程 ⇒ **零状态事件、零剂量日志、且不写核对日期**（无后台自动停用；系统自动核对 `ReconcileMedicationStatusUseCase.kt` 也不写事件）；
3. 页面上给**轻量提示**「今天尚未核对」（读 `userCheckedDate`），**不**因此编造任何状态变更。
**被否**：「沉默即停用」（未勾选 = 停）——那会把「忘了打开」变成停药；本流程的勾选语义恰好相反（**勾选 = 仍在服用**，默认全勾在用药），故「沉默」只会出现在用户**显式取消勾选**时。

**D7 — 提交前先出变更摘要，可返回。** 摘要由**确认时点**的实时活状态重算（不信任列表构建时的快照），显示「将停用 X 味：A、B；将恢复 Y 味：C；未变化 Z 味」，并列出**实际写入生效的日期**。确认按钮才落库。**被否**：点一下就落库（不可撤回的误停用风险）；用列表构建时的快照出摘要（外部改动会造成摘要与实写不一致，§7 E5/E15）。

**D8 — 可选原因：整批一个字段（沿用现对话框）。** 与既有 `BatchStatusDialog`（`MyMedicationsScreen.kt:309-314`）一致，逐药原因需要 N 个输入框、与「把操作做简单」相悖。原因**默认空**、`ifBlank { null }`（`:320`），**绝不预填**（如「医嘱调整」）。整批原因用于本次事务内**两类**事件。**被否**：逐药原因（UI 与数据都更重，且核对通常同一次临床决策）；预填任何默认文案（会污染审计链，冻结稿 `reason` 是「可选、由用户填」）。

**D9 — 复用冻结机制，不造第二套。** 事件仍由既有 `MedicationStatusRepository.insertEvent` 写；变化仍由 `liveStatusKind` 判定；派生仍由 `MedicationStatusDerivation` 给；reconcile 仍由既有 `ReconcileRemindersUseCase` 触发。**被否**：为核对新建事件表 / 新 kind / 新缓存列（违反「不改变冻结模型语义」的明确指令）。

**D10 — 入口 = `我的药品` 页里的一个子路由，不加底部 tab。** 照搬「批量操作」的入口模式（`MyMedicationsScreen.kt:129-136` 的 chip）改为**导航**到核对子路由；`TOP_LEVEL_DESTINATIONS` 保持 5 个（`NavigationHierarchyTest.kt:10`）。**被否**：新增第 6 个 tab（冻结稿 §15 明令、导航测试钉死）。

**D11 — 「今天」= 确认时点的成员提醒时区当地日；跨日不静默改写。** 详见 §6。**被否**：在列表构建时冻结「今天」（摘要跨零点会显示过期日）；在确认时静默改用新日（照护者看到的是旧日）。

**D12 — 整次核对的 Room 侧写入 = 一个事务；核对日期 = 事务外 post-commit（裁决 2，本轮更正）。** 变化的停用/恢复两组的状态事件 + 缓存**在同一 Room 事务内**写入；任一步失败 ⇒ **状态整体回滚**（事件与缓存零变化）。**`lastUserCheckedDate` 不在该事务的边界内**——它存于 **DataStore**，Room 的 `withTransaction` **无法回滚**它（§1.6），故它是**提交之后的独立事实**（§4.5），**不**被描述为回滚的一部分。复用 `TransactionRunner.withTransaction` 与既有写入机制（`liveStatusKind` diff、`MedicationStatusRepository.insertEvent`、`MedicationRepository.setArchivedCache`），**不改数据模型**。若现有「一 kind 一 `applyBatch`」无法覆盖混合 kind 的单事务，则把写入统一到一个**应用层入口点**（§4.3），其事务边界 = {① 事务内校验} ∪ {② 本次产生的**全部状态事件插入**} ∪ {② 对应的 `isArchived` **缓存直写**}；**reconcile（提醒重排）与核对日期写入都在提交之后**（§4.4、§4.5）。**被否**：两组各一事务 + 幂等自愈（存在唯一崩溃窗，且与「要么全落、要么全不落」的用户裁决不符）；把核对日期写进 Room 事务以「凑」原子性（跨存储不成立，且有迁移代价，§4.5 方案 b）。

**D13 — 事务内校验「摘要所见状态 == 提交时活状态」，任何分歧整体中止并重建摘要（裁决 3a）。** 摘要构建时记录每行的**活状态 kind**；提交事务内对**摘要里显示的每一味药**（**含未变更者**——本轮细化 2：显示为「未变化」的药若在提交时已被外部改变，也必须判为过期，**绝不**把陈旧摘要呈现为「已兑现」）重新读 `liveStatusKind` 并与摘要所假定的值比对；任一不符 ⇒ **抛出并整体回滚**，**不**落任何事件、**不**写核对日期；UI 丢弃过期摘要、按**当前**状态重建并请其**重新确认**（**不**应用过期 diff）。详见 §7 E15。

**D14 — `UNKNOWN ≠ PAUSED`：遗留药的复选框看「当前态」，历史段不被改写（裁决 3b）。** 遗留基线药 `statusAt(events, now)` 在 `now ≥ 迁移时刻 M` 时为 **`PAUSED`**（当前态），故**不预勾选**；`t < M` 的历史段**恒为 `UNKNOWN`**、**永不**被本流程或任何显示**追溯**改称「暂停」或「活跃」。勾选它 = **显式恢复**（写一条带真实日期的 `RESUME`）；保持不勾 / 取消勾 = **无变化、无事件**（**不是**「补一条 PAUSE」）。详见 §7 E16。

**D15 — 误触「全停」的显式护栏（裁决 3c）。** 当计算出的变更集**会停用全部当前活跃药**（或达一档明确阈值）时，**「确认核对」一次点击不足以提交**：必须再经一次**独立的、破坏性样式、默认取消**的确认对话框，文案点名「将停用全部 N 味药，今日计划将为空」。**不**禁止全停（医生确有全停医嘱），但**禁止**一次误触静默全停。详见 §7 E17。

---

## 3. 交互与界面规格

### 3.1 入口

- 位置：`我的药品` 页过滤器行，`批量操作` chip 旁，新增一个 chip「**今日用药核对**」（`FilterChip`，语义同 `MyMedicationsScreen.kt:129-136`）。
- 行为：`navigate(Route.MedicationDailyReconcile)`（**子路由**，非顶层目的地）。
- 状态提示：chip 旁 / 行内一行**轻量文案**「今天尚未核对」（当 `userCheckedDate != today` 时显示；已核对则隐藏或显示「今日已核对」）。**不得**用任何红点/告警语气。

### 3.2 屏幕状态机

```
[列表 List] --点「核对下一步」--> [摘要 Summary] --点「确认核对」--> [结果 Result]
     ^                                 |                                |
     +------------点「返回修改」-------+                                 |
     +-----------------popBackStack（从结果返回我的药品）----------------+
```

1. **列表（List）**：标题「今日用药核对」；副标题/免责「勾选表示仍在服用。本核对只更新用药状态，**不记录今天是否服药**。」；一屏列出**全部**药（活跃 + 已停用），按 `statusAt == ACTIVE` **预勾选**（**活跃勾、其余不勾**——含遗留药：其当前态为 `PAUSED`，见 §7 E16）；每行显示药名 + 当前状态角标（复用 `statusBadgeLabel`，`MyMedicationsScreen.kt:278-282`）；底部「核对下一步」；原因输入（可选，空）。**页面上**不出现任何剂次/打卡控件。
2. **摘要（Summary）**：在**确认时点**重算 `toPause`/`toResume`/未变化；
   - 有变化：`将停用 %1$d 味：%2$s；将恢复 %3$d 味：%4$s；未变化 %5$d 味`，并显示「生效日期：今天（M月D日）」；
   - 无变化：`本次没有变化（未变化 %1$d 味）`；按钮仍为「确认核对」（确认后**登记「今天已核对」但不写状态事件**，D5 / 裁决 1）；
   - **护栏（§7 E17）**：若 `toPause` 覆盖**全部**当前活跃药，或变更数达阈值，则「确认核对」**不直接提交**，先弹出**独立的二次确认**（破坏性样式、默认取消、文案含计数与「今日计划将为空」的后果）；
   - 按钮：「确认核对」/「返回修改」。**点「返回修改」不写任何东西**。
3. **结果（Result）**：显示**实写**结果（由本次**单事务**的 `MedicationStatusWriteResult` 汇总，不信任预期）：`已停用 %1$d 味；已恢复 %2$d 味；未变化 %3$d 味`，加「已核对医嘱（非服药记录）」（复用 `medication_status_user_checked`）。返回我的药品。**注意（§4.5 窗口 ②）**：若状态已提交、仅「登记今天已核对」这一步失败，结果屏仍按**已提交**的状态显示（**不**谎报失败、**不**回滚），「今天尚未核对」提示可能滞后一次；下次核对自愈。
4. **失败 / 过期**：写入抛错 ⇒ 停在摘要，提示「核对未完成，请重试」，**不**假装成功；若因**并发变更 / 跨零点**在事务内被中止（§7 E15 / §6）⇒ 摘要**自动重建**为当前状态并提示「药单已变化（或在摘要页跨过了零点），已按最新状态重新生成，请确认」，**绝不**落半截、**绝不**显示「成功」。

### 3.3 UI 文案（四语齐备，§8）

新字符串（`res/values/medication_flow_strings.xml`，`values`/`-en`/`-ja`/`-ko`）：
`medication_reconcile_entry`、`_title`、`_disclaimer`、`_reason`、`_step_review`、`_summary_title`、`_summary_changes`、`_summary_no_changes`、`_summary_effective_day`、`_summary_all_paused_warning`、`_bulk_change_warning`、`_all_paused_confirm_title`、`_all_paused_confirm_body`、`_bulk_confirm_title`、`_bulk_confirm_body`、`_confirm`、`_cancel`、`_back`、`_result_summary`、`_done`、`_failed`、`_stale_summary_rebuilt`、`_member_changed`、`_pending_hint`、`_checked_hint`、`_group_pause`、`_group_resume`、`_state_checked`、`_state_unchecked`、`_legacy_unknown_badge`。

**文案硬约束（测试断言）**：`_disclaimer` 与结果屏文案必须含「**不记录今天是否服药**」/「**非服药记录**」之意；**不得**出现「已服药」「漏服」等剂次措辞。

---

## 4. 差异计算与写入编排

### 4.1 纯函数（可单测，无 Android 依赖）

```kotlin
/** 一行 = 药 id、当前是否 active（= statusAt == ACTIVE）、复选框意图。 */
internal data class ReconcileRow(val id: Long, val currentlyActive: Boolean, val stillTaking: Boolean)

internal data class ReconcileDiff(val toPause: List<Long>, val toResume: List<Long>) {
    val changedCount get() = toPause.size + toResume.size
}

/** D3：判据是「意图 vs 当前 active」，不是「勾选→RESUME」。 */
internal fun diff(rows: List<ReconcileRow>): ReconcileDiff = ReconcileDiff(
    toPause = rows.filter { !it.stillTaking && it.currentlyActive }.map { it.id },
    toResume = rows.filter { it.stillTaking && !it.currentlyActive }.map { it.id },
)
```
`currentlyActive` 必须来自 `MedicationStatusDerivation.statusAt(events, now) == ACTIVE`（**UNKNOWN 与 PAUSED 都算「非 active」** ⇒ 预不勾选；勾选它们 ⇒ 走 `toResume`）。

### 4.2 写入编排（Room 侧 = 一个事务；核对日期 = 提交后；裁决 2，本轮更正）

```kotlin
suspend fun reconcile(stillTakingById: Map<Long, Boolean>, reason: String?): ReconcileOutcome {
    val day = todayInReminderZone()                            // D4 / §6：确认时点、成员提醒时区
    val events = statusRepository.getEventsForRecipientOnce()  // 确认时点实时重算（D7）
    val d = diff(buildRows(medications, events, stillTakingById, nowMillis()))
    val assumed = assumedKinds(medications, events, nowMillis())   // 摘要所见：每药活状态 kind（D13）

    // 唯一实现（§4.3）：Room 事务内 = 校验 + 事件 + 缓存；核对日期与 reconcile 在提交之后
    return setStatus.applyReconcile(
        toPause = d.toPause, toResume = d.toResume,
        effectiveDate = day, reason = reason,
        expectedKindByMedication = assumed,                    // 供事务内校验（D13 / §7 E15）
        // 内建：① 事务内校验（含成员门槛、跨零点）② 逐药按 kind append 事件 + 直写缓存
        //       ③ 提交后写核对日期（DataStore）④ 提交后 reconcile
    )
}
```
- **事务边界（精确）**：`applyReconcile` 内 **① 事务内校验 + ② 逐药事件插入 + 缓存直写** 在**同一** `withTransaction`（**只含 Room 写入**）。任一步抛错 ⇒ **事件与缓存整体回滚**，状态与提交前**逐字节一致**。
- **核对日期不在事务内**：`lastUserCheckedDate` 写在 `withTransaction` **之外**（提交后、DataStore，§4.5）——**它不随该事务回滚**（跨存储，§1.6）。故**不得**把「核对日期回滚」当作原子性的一部分。
- **不新增机制**：仍用 `MedicationStatusRepository.insertEvent`、`MedicationRepository.setArchivedCache`（语义 = `deriveCurrentStatus`）、`MedicationStatusDerivation.liveStatusKind`；**不改 Room schema、不改派生、不改 append-only 规则**（§11「不改」）。
- **单 kind 与混合 kind 同一条路径**：`applyBatch`/`apply`/`userCheck`（单 kind）与 `applyReconcile`（混合 kind）都是**同一实现** `applyStatuses(...)` 的薄包装（§4.3），**无任何路径绕开事务内校验**。
- **成员门槛**：`check(recipientId != NO_RECIPIENT)`（`SetMedicationStatusUseCase.kt:89-90`）在 ① 内执行。

### 4.3 统一的写入入口点（单 kind 与混合 kind 同一实现）与其精确事务边界

**为什么统一**：现状只有 `applyBatch(ids, kind, …)`（单 kind、N 药、一个 Room 事务、**无**事务内校验、核对日期在事务外）。核对的混合 kind 若按 kind 分两组各调一次 = **两个事务**（中间有崩溃窗），与裁决 2 不符。**更正后的做法不是新增一个平行的「旁路」入口点**（那会让单 kind 路径**不**走校验），而是把写入收敛到**唯一实现** `applyStatuses(...)`，`applyBatch` 退化为**薄包装**。这样**任何路径都不会绕开事务内校验**。

```kotlin
// SetMedicationStatusUseCase —— 应用层入口点（不改 schema / 不加 kind / 不改数据模型）
enum class CheckedDatePolicy { ON_CHECK_ONLY, ALWAYS }

/** 唯一状态写入实现：N 药 × per-med kind（可混合）在**一个 Room 事务**内；核对日期在**提交之后**。 */
suspend fun applyStatuses(
    desiredKindByMedication: Map<Long, String>,                 // 有序 药 id → 目标 kind；可混合 PAUSE/RESUME/DISCONTINUE
    effectiveDate: LocalDate,
    reason: String?,
    expectedKindByMedication: Map<Long, MedicationStatusKind?> = emptyMap(),  // 摘要所见（**摘要里显示的每一味**）；空 = 不做事务内校验
    checkedDatePolicy: CheckedDatePolicy = CheckedDatePolicy.ON_CHECK_ONLY,
    summaryDay: LocalDate? = null,                              // **摘要所见日**（事务内校验用；核对路径 = 摘要构建时显示的 day）
    expectedRecipientId: Long? = null,                          // 摘要构建时的活动成员（**实现收口时新增**）；事务内比对成员未切换（E9）；空 = 不校验
): List<MedicationStatusWriteResult>

// —— 单 kind 薄包装（对外签名/语义同今日的 applyBatch，逐字保留）——
suspend fun applyBatch(ids, kind, date, reason) = applyStatuses(ids.associateWith { kind }, date, reason)
suspend fun apply(id, kind, date, reason) = applyBatch(listOf(id), kind, date, reason).single()
suspend fun userCheck(id, kind, date) = applyBatch(listOf(id), kind, date, null).single()

// —— 核对流程（混合 kind）——
// `date` 与 `summaryDay` **是同一个值**：摘要构建时解析、事务内校验（todayAtCommit == summaryDay），
// 提交后写核对日期也**只用这个已被事务验证过的 summaryDay**（**不得**再重读 today()——见下）。
suspend fun applyReconcile(toPause, toResume, date, reason, expected, expectedRecipientId = null) =
    applyStatuses(LinkedHashMap<Long, String>().apply {
        toPause.forEach { put(it, MedicationStatusKind.PAUSE) }
        toResume.forEach { put(it, MedicationStatusKind.RESUME) }
    }, date, reason, expected, CheckedDatePolicy.ALWAYS, summaryDay = date, expectedRecipientId = expectedRecipientId)

// —— 无变化的显式登记（D5）——
suspend fun markUserChecked() = applyStatuses(emptyMap(), today(), null, emptyMap(), CheckedDatePolicy.ALWAYS)
```

**`applyStatuses` 的精确结构**（一个 `withTransaction`）：

1. **事务前**（沿用现状 `:84-90`）：`require(kind ∈ USER_ACTIONS)`（对每个值）、未来日 `FutureEffectiveDateNotAllowed`、`effectiveAtMs` 换算、读 `recipientId`、`check(recipientId != NO_RECIPIENT)`；记下 `recipientIdAtOpen = recipientId`（供事务内成员比对）。
2. **`withTransaction { … }`（只含 Room 写入）**：
   - **① 事务内校验**（当 `expectedKindByMedication` 非空；即核对路径）——**覆盖摘要里显示的每一味药，不止变更的那些**（裁决 3a，**本轮细化**）：对 `expectedKindByMedication` 的**每一个键**（= 摘要里显示的**每一味**药，含**未变更**者）重读 `liveStatusKind` 与其期望值比对；**任一不符（含「显示为未变化、提交时却已被外部改成别的 kind」）⇒ `throw`（整体回滚）**。同时比对成员（`activeRecipient.current() == recipientIdAtOpen`，E9）与 `todayAtCommit == summaryDay`（当 `summaryDay != null`，§6）；任一不符 ⇒ `throw`。**空映射 = 不校验**（单 kind 既有路径行为不变）。
   - **② 逐药**（只在 `desiredKindByMedication` 的键上）：`getEventsOnce` → `liveStatusKind`；`live == desired` ⇒ 记 `UserChecked`（不写）；否则 `insertEvent` + 同事务 `setArchivedCache` ⇒ 记 `EventRecorded`。
3. **提交后（`withTransaction` 之外，按此顺序；③ 与 ④ **彼此独立**——见下）**：
   - **③ 核对日期**：`shouldMark = (policy == ALWAYS) || 结果含 UserChecked`；若真则 `preferences.setUserCheckedDate(recipientId, checkedDay.toEpochDay())`——**DataStore 写入，事务外**（§4.5）。**`checkedDay` 必须是事务已验证过的 `summaryDay`**（`summaryDay ?: today(zone)`）；核对路径（`summaryDay != null`）**绝不**在提交后重读 `today()`（否则跨零点确认会把新日盖到已按旧日校验的事务上，§6、裁决 3 细化 3）。
   - **④ reconcile**：对每个 `EventRecorded` **各自** `runCatching { ReconcileRemindersUseCase.medication(...) }`（§4.4）。
   - **③ 与 ④ 独立（裁决细化 1）**：`markUserChecked` 的 DataStore 写入失败 **不得**阻断 ④；反之 ④ 的失败也 **不得**影响已提交的状态与已写的核对日期。**各自 `runCatching`（各记一条 warn 日志），互不依赖彼此的成败**——核对日期失败只表现为「今天尚未核对」提示滞后（弱事实，§4.5），提醒仍照常重排。
4. **返回**：按 `desiredKindByMedication` 的插入顺序（= 输入顺序）映射结果——与现状 `medicationIds.map { … }` 顺序一致。

**行为保持（哪些变、哪些不变）**：
- **不变**：`applyBatch` 的返回顺序、`UserChecked`/`EventRecorded` 语义、单 kind 的返回、非法 kind / 未来日 / 无成员守卫、`lastUserCheckedDate` 的「**有 CHECK 才写**」语义（`ON_CHECK_ONLY`）；**单 kind 既有路径**（`summaryDay == null`）核对日期仍用 `today(zone)` 而非 `effectiveDate`（沿用现状 `:118`）。
- **变（本轮细化）**：核对路径改传 `summaryDay`（= 摘要所见日）；③ 用它写核对日期（**不再**重读 `today()`），② 的校验覆盖**摘要里每一味**（含未变更），③/④ 各自 `runCatching` 独立处理失败。
- **变**：唯一实现从 `applyBatch` 体（`:83-123`）搬到 `applyStatuses`；`applyBatch`/`apply`/`userCheck` 变薄包装；新增 `applyReconcile`/`markUserChecked`；`applyStatuses` 多出**可选**的 `expectedKindByMedication`（空 = 无校验）与 `CheckedDatePolicy`（默认 = 现状）。
- **实现收口时的参数增补（与代码对齐）**：`applyStatuses` 比本节最初列出的**又多了两个可选参数**——`summaryDay`（摘要所见日：供事务内跨零点校验、并在提交后**只用它**写核对日期，细化 3）与 `expectedRecipientId`（摘要构建时的活动成员：供事务内**成员门槛**比对，E9）。二者默认 `null`，**单 kind 既有路径不传 ⇒ 行为不变**；`applyReconcile` 亦相应多出 `expectedRecipientId`。实现见 `SetMedicationStatusUseCase.applyStatuses`（`app/.../application/SetMedicationStatusUseCase.kt:143-151`，共 7 个参数），与本节上方签名块一致。
- **调用者核查（全部经唯一实现，无一绕过）**：`apply` ← `MedicationDetailViewModel.kt:154`（详情页单药）；`userCheck` ← 测试（`MedicationStatusReconciliationTest.kt:108/124`）；`applyBatch` ← `MyMedicationsViewModel.kt:71`（我的药品批量停用/恢复/归档）；核对流程 ← 新 VM 调 `applyReconcile`。

**事务边界（精确）**：一个 `TransactionRunner.withTransaction`，其内覆盖 {① 事务内校验（覆盖摘要里显示的**每一味**药，含**未变更**者）} ∪ {② 每一味实际写事件药的 `insertEvent`} ∪ {② 对应的 `setArchivedCache`}。**核对日期（③）与 reconcile（④）明确在事务外，且二者彼此独立（各自失败路径）**。

**回滚语义**：事务内任一步抛错（校验失败、DB 错误、`require`/`check` 失败）⇒ **事件与缓存整体回滚**：**无**任何事件、**无**任何缓存改写；且因 ③ 在**事务之后**、事务抛错时**根本不会执行**，核对日期**也不会**被写（**不是**「回滚」，而是「未执行」——跨存储无回滚可言，§4.5）。界面收到后丢弃摘要并按**当前**状态重建（§7 E15）。**没有半落状态**。

**reconcile 不在事务内**：提醒重排（`ReconcileRemindersUseCase`）**在提交之后**执行（§4.4）——把系统副作用留在事务外，避免「已取消/已排闹钟」与「已回滚的事件」不一致。

**③ 与 ④ 独立（裁决细化 1）**：核对日期的 DataStore 写入与 reconcile 是**两个独立的 post-commit 动作**，各有自己的 `runCatching` 与 warn 日志。**核对日期写入失败 ⇒ 仍照常执行 reconcile**（提醒不会因为一个提示标记写不进去而不重排）；**reconcile 失败 ⇒ 已提交状态与已写核对日期都不受影响**。两者的唯一共享是「在同一个事务提交成功之后」，除此之外**互不依赖**（不得让 ④ 包在 ③ 的 try 里，也不得让 ③ 的失败短路 ④）。

**冻结稿 §11 E10 的最小修订（本轮已获批准并**已应用**）**：`docs/medication-status-spec.md:647` **§11 边界情形 E10（批量的原子性）** 的正文句——

> 「一次事务内为 N 个药写事件 + 缓存列；任一失败整体回滚（对齐 `updateMedications` `MedicationRepositoryImpl.kt:83-86`）」

该句把批量事务描述为**同类**操作（配合 §16.1「批量停用」/§16.3「批量恢复」各自独立，冻结批量用例一次只带**一个 kind**）。为**显式放行**本流程，**最小修订**（= 本轮已对该句应用的措辞）增补两点：**(i) 同一批量事务内**各药可携带不同 kind**（`PAUSE`/`RESUME` 混合）；**(ii) `lastUserCheckedDate` 由核对流程在事务提交之后、作为**事务外的 post-commit 事实**单独写入——**不在**该事务边界内、**不在**回滚范围**（**不得**写成「该事务一并写入核对日期」——那是跨存储的假承诺）。**本轮已把该最小修订应用到 `docs/medication-status-spec.md` 的 E10 行**（纯措辞、不改数据模型）。
**被否**：改 `applyBatch` 的**对外签名**为 kind 映射参数（改冻结用例内核、扩大回归面——本方案只加**内部**实现，对外签名不变）；为混合 kind 新建 kind 或新表（改冻结数据模型，违反裁决 2）；把核对日期写进 Room 事务（跨存储不成立，§4.5）。

### 4.4 提醒重排的时机（提交后）

**规则**：Room 事务**只**写事实（事件 + 缓存）；**核对日期与提醒重排（reconcile）都在提交之后**——核对日期立即写（DataStore，§4.5），reconcile 逐个变更药触发（对齐既有即时 reconcile 的触发点）。**二者独立**：核对日期写入失败仍照常 reconcile；reconcile 失败不影响已提交状态或已写核对日期（各自 `runCatching` + warn，§4.3）。

**为什么提交后是安全的**：即便 reconcile **漏跑、抛错或被 Doze 推迟**，也**不会**产生错误提醒——冻结稿已把**发送前派生校验守卫**（`docs/medication-status-spec.md` §7，更正 N）**提升为首期交付**：`MedLogAlarmReceiver.handleMedication` 在真正弹通知前再算一次 `statusAt(medId, now)`，**非 `ACTIVE` 即抑制**（含 `PAUSED`/`UNKNOWN`）。被停用的药，其派生状态在**事务提交的同一刻**即已改变，故守卫立刻生效，**无需**依赖 reconcile 是否跑到。
**精确边界（不夸大）**：守卫只保证「**不产生错误提醒**」。对**恢复**方向，若 reconcile 被推迟，**重新排期**可能延后 ⇒ 可能**少提醒**（漏了本应恢复的闹钟），这是既有的「状态写入与 reconcile 两步」窗（冻结稿 §0.4 交接窗），本流程**不**新增也不消除它；收口点仍是用户动作内即时 reconcile + 启动/开机/15 分钟自动核对。**绝不**把 reconcile 放进事务内（事务回滚会让已排闹钟成为幽灵）。

### 4.5 核对日期（`lastUserCheckedDate`）的一致性方案（可测）

**存储事实（§1.6）**：`lastUserCheckedDate` 在 **DataStore（`medlog_settings`）**，状态事件与缓存列在 **Room（`MedLogDatabase`）**；`TransactionRunner.withTransaction` **只开 Room 事务**。**跨存储原子性在当前代码下不可能**。下面给出**可测**方案并如实对比「迁进 Room」。

#### 4.5.1 不变量（Invariant MARK-LAG，可断言）

> **`lastUserCheckedDate == D` ⇒ 本次核对的 Room 事务（其事件/缓存）已在 `D` 提交，或本次核对没有任何状态变更。反之不成立：事务可以已提交而标记仍停留在旧日（或缺失）。**

一句话：**标记可以「落后」于状态，但绝不「领先」——绝不声称一次未提交的核对。** 这直接决定**写入顺序**：标记必须在 `withTransaction` **返回之后**写（post-commit）；**若把它写进事务内**（本文档原稿的错误设计），一旦事务回滚而 DataStore 已写，就**违反**该不变量（标记声称了未提交的核对）。

**可执行的断言**（§9/§10 引用）：
- **A1（领先禁止）**：注入「事务内校验失败 ⇒ 事务抛出」⇒ 断言 `lastUserCheckedDate` **未变**（事务外代码未执行）。
- **A2（滞后允许）**：注入「`setUserCheckedDate` 抛错」于**事务成功提交之后** ⇒ 断言事件与缓存**已落**、`lastUserCheckedDate` **未写/停留旧日**，且**不**回滚状态。
- **A3（自愈）**：紧接 A2 重跑一次核对 ⇒ 断言**零新事件**（幂等）且 `lastUserCheckedDate` 前进到今日。
- **A4（post-commit 二者独立，本轮细化 1）**：提交成功、令 ③ `setUserCheckedDate` 抛错 ⇒ 断言 ④ reconcile **仍被触发**（每个 `EventRecorded` 各一次）；反之令 ④ 抛错 ⇒ 断言状态与核对日期不受影响。**③ 不得短路 ④，④ 也不得回滚 ③**。
- **A5（用事务验证过的 summaryDay，本轮细化 3）**：提交后写出的 `epochDay` **恒等于**事务内校验通过的 `summaryDay`；**不得**在提交后重读 `today()` 重新取日（跨零点注入可证，§6 / C12）。

#### 4.5.2 选择：保留为 DataStore 的 post-commit 弱事实（方案 a，**采纳**）

**顺序**：`withTransaction`（校验 + 事件 + 缓存）提交 → **然后** `setUserCheckedDate` → **然后** reconcile。

**失败模式（只有一种）**：核对日期**陈旧或缺失** ⇒ 用户看到**「今天尚未核对」提示错误地仍在**（或再次进页面重又提示）。**绝不**出现「状态错、提醒错、依从率错」——标记**不是**服药证据、**不进**依从率、**不**驱动任何状态派生（§8.2）。

**崩溃窗表**：

| 窗口 | 状态事件 + `isArchived` 缓存（Room） | 核对日期（DataStore） | 用户看到 | 自愈 |
|---|---|---|---|---|
| ① 事务内失败 / 提交前进程死 | **零**（回滚） | 未写（事务外未执行） | 摘要重建 / 「核对未完成，请重试」；「今天尚未核对」仍在 | 重新核对 |
| ② **事务已提交**、标记写入前进程死 **或** 标记写入抛错 | **已全落** | **未写**（停留旧日/缺失） | 状态**正确**；「今天尚未核对」**错误地**仍在 | 下次核对重写标记（A3）；最坏多显示一次提示 |
| ③ 标记已写、其后进程死（reconcile 未跑） | 已全落 | 已写 | 状态正确、提示消失；**恢复方向**可能**少排**提醒（既有两步窗，§4.4） | 启动/开机/15 分钟自动核对补排 |

**窗口 ② 的诚实边界**：用户**确实**完成了核对，但提示仍说「今天尚未核对」——**弱事实落后**。它**不**影响任何正确性属性（无错误状态、无幽灵提醒、依从率不变），且**再确认一次即自愈**；代价是可接受的一次提示误显。

**为什么不选另一种 DataStore 方案**（把它写进 `withTransaction` 块内装作原子）：**它更差**——DataStore 写入**不随 Room 回滚**，所以块内写 = 事务可能回滚而标记已落 = **违反 A1（标记领先）**。**唯一正确的位置就是事务之后**（现代码 `:116-119` 已经如此）。

#### 4.5.3 被否方案 b：把核对日期迁进 Room（原子，但有迁移代价）

**做法**：新表（如 `medication_check_state(recipientId, epochDay, …)`）或新列，随 `withTransaction` 提交 ⇒ 标记与事件/缓存**同库同事务**、真原子。

**代价（如实计价）**：
- **Room schema 版本 v26 → v27** + `Migration` + DAO + 仓储 + DI 绑定 + 迁移测试；落在**冻结的数据模型**上。
- **直接违反**本流程硬约束：裁决 2 的「**不改冻结数据模型**」、§11「**不改**」、§13「**不为核对单独建事件表 / 缓存列 / 迁移**」。
- **收益极小**：仅把「一次提示误显」（窗口 ②）换成「真原子」；而该提示**非**正确性属性、**自愈**、且**不**被任何依从/状态/提醒逻辑读取（§8.2）。
- **另加**：DataStore 的 `clearForRecipient` 与 Room 删除语义需重新对齐；小组件等 DataStore 读者与新表的双写面扩大回归。

**裁决**：**采纳方案 a**（保留 DataStore post-commit 弱事实）。理由：不变量已把失败模式收敛到**唯一的、自愈的、无正确性后果的**提示落后；方案 b 的迁移成本与「不改冻结数据模型」的硬约束**不相称**。若未来该事实获得**正确性用途**（例如驱动排程或依从），再评估迁移——**本轮不迁**。

**残余（不隐瞒）**：窗口 ② 期间若用户**永不**再确认核对，提示会**一直**显示「今天尚未核对」，而其实当天已核对——这是**弱事实**的已知代价，**不**试图用一个假的事务消除它。

---

## 5. 幂等

> 口径（裁决 1）：「零新事件」= **零新状态事件**；「零写入」= **零状态事件 + 零剂量日志**。**登记 `lastUserCheckedDate` 不算写入。**

| 规则 | 说明 |
|---|---|
| 同一当地日**重复核对** | 第二次时每一行的当前状态已等于其复选框默认值 ⇒ `diff` 为空 ⇒ 变更集为空 ⇒ **零新状态事件、零剂量日志**；**事务提交后**仍写 `lastUserCheckedDate` = **同一** `epochDay`（DataStore 覆盖、不追加行，§4.5）⇒ **第二次核对 = 空 diff + 仍记录当天已核对**（裁决 1）。 |
| 单药重复同 kind | 已由 kind 级 CHECK 保证（`SetMedicationStatusUseCase.kt:97-100`；冻结稿 §5.4 转换矩阵）。 |
| **先取消勾选、再重新勾选**（提交前） | 意图回到与当前状态一致 ⇒ `diff` 空、变更集空 ⇒ **零状态事件**（§9 C2）。 |
| **全不变更的直接确认** | 变更集空 ⇒ 事务内**无事件、无缓存改写**；事务**提交后**写核对日期 ⇒ 「今天尚未核对」消失（D5 / §9 C1）。 |
| 跨日重复 | 第二天再核对属于**新的核对动作**：`lastUserCheckedDate` 推进到新 `epochDay`；若药的状态自昨天未变，仍**零状态事件**。 |
| 事务级幂等 | 变化判定键在**当前活状态 kind**，且**全部状态事件 + 缓存**在一个事务内 ⇒ 崩溃/重试只会看到「尚未落库」或「**事件+缓存**已全部落库」两态（核对日期是**事务外**的第三态，可能滞后，§4.5）；重开本流程以**当前**状态重新预勾选，剩余差异照常显示（§4.4 提交后 reconcile 的收敛）。 |

---

## 6. 日界（「今天」的定义）

**定义**：本流程的「今天」= **成员提醒时区的当地日**（与全部既有路径同源），在**确认时点**解析，并由写入层换算为当地 0 点的 `effectiveAtMs`。

**规则（D11）**：
1. 摘要构建时解析一次 `day` 并**显示**「生效日期：今天（M月D日）」。
2. 点「确认核对」时**在同一事务内再校验一次 `day`**（与 §7 E15 的并发校验同一处，D13）——该 `day`（= 摘要所见日 `summaryDay`）**就是提交后写核对日期所用的日**（不再重读 `today()`，本轮细化 3）：
   - 若与摘要显示的 `day` **相同** ⇒ 落库，`effectiveDate = day`；
   - 若**不同**（照护者在摘要页停留跨过了零点）⇒ **不落库**（事务内 `throw`、整体回滚）；用新 `day` **重建摘要**并请其**重新确认**（提示「已跨天，请按新日期重新核对」）。
3. 因此 **`effectiveDate` 恒 ≤ 当即「今天」**，永不落未来日（满足冻结稿 D11），也**永不静默改写照护者看到的日期**。

**23:59 打开、00:01 确认**：
- 若 00:01 时才点「核对下一步」⇒ 摘要直接按**新日**构建、显示新日、确认写**新日**；
- 若 23:59 已进入摘要、停在摘要页跨零点才点「确认核对」⇒ 规则 2 触发：**不写**（事务整体回滚），按新日重建摘要，再次确认后写**新日**。
- **绝不**把状态倒填到昨天、也绝不显示一个日期而写另一个日期。

**被否**：在列表构建时冻结「今天」（摘要跨零点显示过期日期，且可能与实写不一致）；确认时静默改日（照护者看到旧日、写新日）。理由：核对是「今天还吃不吃」的动作，**写哪天就该让照护者看到哪天**；同时任何方向都不可越过「今天」这条更硬的约束（冻结稿 D11）。

---

## 7. 边界情形（逐条）

| # | 情形 | 规格 |
|---|---|---|
| E1 | **照护者从不打开本流程** | **零状态事件、零剂量日志、且不写核对日期**；无后台自动停用（D6）。测试：跑一天后断言事件表、日志表、核对日期均无变化。 |
| E2 | **打开但一行未动、直接确认** | `diff` 空 ⇒ **零状态事件、零剂量日志**；事务**提交后**写 `lastUserCheckedDate` = 今天（§9 C1、§4.5）。**这正是裁决 1**：「零写入」不排除核对日期。 |
| E3 | **先取消再勾回、提交前** | 意图回到默认 ⇒ `diff` 空 ⇒ 零状态事件（§9 C2）。 |
| E4 | **同一日再次核对** | 幂等（§5）：空 diff、零新事件；核对日期仍为同日。 |
| E5 | **打开到确认之间，某药在别处被改了** | 触发 **E15**：提交时活状态与摘要所见不符 ⇒ **整体中止**。 |
| E6 | **全部取消勾选（全停）** | 计算得 `toPause` = 全部当前活跃药 ⇒ **必须**先通过 **E17** 的二次确认；确认后所有当前活跃药各写一条 PAUSE，已停用药无变化。**不禁止**，但**禁止**一次误触静默全停。 |
| E7 | **全部勾选（全继续）** | 所有非活跃药各写一条 RESUME（**含迁移遗留药**，见 E8）；活跃药无变化。 |
| E8 | **勾选一个迁移遗留（`LEGACY_BASELINE`，起点 `UNKNOWN`）的药** | live kind = `LEGACY_BASELINE` ⇒ 期望 `RESUME` ⇒ **CHANGE**：写一条 `RESUME`，`effectiveAtMs = 今天`（**一条带真实日期的显式恢复**）。派生随之为：`t<M` 仍 **`UNKNOWN`**、`M≤t<今天` 为已证 `PAUSED`、`t≥今天` = `ACTIVE`（冻结稿 §5 R7——**不**把 `M` 之前重解释为「一直在服」）。测试见 §10 T7 / §9 C16。 |
| E9 | **成员切换** | 本流程在打开时**捕获** recipientId 并据此构建/预勾选；若确认时活动成员已变，**事务内成员门槛失败 ⇒ 不写、整体回滚**，提示「已切换家庭成员，请重新核对」并回到列表按新成员重建。**绝不**把 A 的勾选写进 B。 |
| E10 | **无任何药** | 显示空状态「暂无药品」；「核对下一步」禁用。 |
| E11 | **某药只剩 PRN / 无未来剂量** | 仍按状态写事件（与冻结稿 E1 同：状态留痕，排期 no-op）。 |
| E12 | **原因留空** | 事件 `reason = null`（`ifBlank { null }`，照 `MyMedicationsScreen.kt:320`）；**绝不**预填。测试断言事件 `reason == null`（§9 C8）。 |
| E13 | **原因填写** | 同一个原因写入本批**事务内**的全部事件（D8）。 |
| E14 | **跨零点确认** | 见 §6：事务内校验发现 `day` 已变 ⇒ 不写旧日、整体回滚、按新日重建摘要再确认。 |
| **E15** | **摘要与提交之间发生了并发状态变更（裁决 3a）** | **规则**：提交事务内，对**摘要里显示的每一味药**（**含显示为「未变化」者**，本轮细化 2）重读 `liveStatusKind`，与摘要构建时假定的 kind 比对；**任一不符 ⇒ 抛错、整体回滚**（**事件与缓存**全不落；核对日期因**事务外写入未被执行**而未写——**不是**「回滚」，跨存储无回滚，§4.5）；**不应用过期 diff**。UI 丢弃摘要、按**当前**活状态**重建**并请其重新确认，提示「药单已变化，已按最新状态重新生成」。**四种源**：① **另一入口点**（详情页单药 停用/恢复/归档，写事件 ⇒ 活 kind 变）；② **一次恢复**（别处写了 `RESUME`，使某药由非活跃翻活跃）；③ **系统自动核对**（`ReconcileMedicationStatusUseCase`）——它**只写缓存、不写事件**，**不能**改变 `liveStatusKind`，故**结构上不会触发中止**（明列以示与边界 a 的规则不矛盾）；④ **另一成员的活动 / 成员切换**——若活动成员已变，事务内成员门槛失败 ⇒ 中止回滚并提示（同 E9）。**照护者看到**：一条明确的中止提示 + **重建后的摘要**（编号与计数按当前状态），**绝不**看到「成功」或半截结果。**测试**：§10 `MedicationReconcileTransactionTest`；**反例** §9 C15。 |
| **E16** | **遗留药的复选框：`UNKNOWN ≠ PAUSED`（裁决 3b）** | **规则**：复选框看**当前态**（`statusAt(events, now)`），**不看历史段**。`now ≥ 迁移时刻 M` ⇒ 当前 = `PAUSED` ⇒ **不预勾选**。**勾选** = 显式恢复 ⇒ 写一条 `RESUME@today`（带真实日期）；**保持不勾 / 取消勾** = 与当前态一致 ⇒ **零事件**（**不是**「补一条 PAUSE」）。**`t < M` 的历史段恒为 `UNKNOWN`、永不追溯改称「暂停」或「活跃」**；行内文案不打印任何被算出的开始日（沿用冻结稿 §6.1/§6.3 的「状态未知（早于本次迁移）」/「已归档·自迁移」口径）。**行如何向照护者解释**：药名 + 当前角标「已停用·迁移前状态未知」+ 说明「本次核对只看它**现在**是否在服；迁移前的状态未知，不作追溯」。**测试**：§10；**反例** §9 C16。 |
| **E17** | **误触「全停」的显式护栏（裁决 3c）** | **规则（精确）**：设 `A` = 当前活跃药集合，`n = |A|`，`p = |toPause|`，`c = |变更集|`。**需二次确认** ⇔ ① `n > 0 且 p == n`（会停用**全部**活跃药），**或** ② `p ≥ max(3, ⌈n/2⌉)`（批量停用达**半数或以上**且不少于 3 味），**或** ③ `c ≥ 5`（一次核对变更 ≥ 5 味）。二次确认 = **独立对话框**、破坏性样式、**默认动作 = 取消**、文案点名计数与后果（「将停用全部 %1$d 味药，今日计划将为空。确认吗？」）。「确认核对」一次点击**不足以**提交这些情形。**不禁全停**（医生可能确有全停医嘱），**只禁止一次误触静默全停**。**阈值理由**：全停是最高危单点；半数×下限阈值覆盖「一屏药停一半」的批量误触；绝对下限 3/5 兜住小药单与多数批量。**注意（与 E15 的区别）**：E17 是**用户自身聚合意图**触发的**提交前**确认（确定性、无竞态，**追加一次确认**、不重建摘要）；E15 是**外部变更**触发的**事务内**中止（竞态，**整体回滚 + 重建摘要**）。二者次序 = 先 E17（确认意图）、后 E15（事务内校验），不重叠。**测试**：§10 `MedicationReconcileGuardTest`；**反例** §9 C17。 |

---

## 8. 无障碍与四语

- **每行**：整行 `Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = …)`；`semantics { stateDescription = 已勾选/未勾选; contentDescription = "<药名>，当前：<正在服用 / 已停用>" }`；触控目标 ≥ 48dp；**不**仅靠颜色表达勾选。TalkBack 朗读「药名，复选框，已勾选/未勾选」。
- **摘要**：`semantics { liveRegion = LiveRegionMode.Polite }`，确认弹出时自动朗读「将停用 X 味…」。
- **大字号**：沿用既有大字号支持（`MedicationFlowUiTest` 检查空状态与可达性），核对页同样以**语义/文本**断言、不读图像（skill 的 UI 验证约定）。
- **四语**：`values`（zh）、`values-en`、`values-ja`、`values-ko` 的 `medication_flow_strings.xml`，键集完全一致；扩展现有 `MedicationStatusStringsLocaleTest.kt:17-36` 的 `required` 列表（或新增 `MedicationReconcileStringsLocaleTest`）断言核对键在四语齐备。**免责/非服药文案在四语都不得弱化**（如 en「not a dose record」、ja「服薬記録ではありません」）。

---

## 9. 反例（会抓住错误实现；逐条可执行）

> 口径（裁决 1）：「零写入」= **零状态事件 + 零剂量日志**；**登记核对日期不算写入**。故 C1/C2 同时断言「事件与日志行数不变」**且**「核对日期 = 今天」。

- **C1 打开并直接确认（无变更）= 零状态事件 + 零剂量日志（但记录今天已核对）**：成员 12 味药（3 停用、9 在服）全部保持预勾选 → 确认 → 断言 `medication_status_events` 行数**不变**、`medication_logs` 行数**不变**，**且** `lastUserCheckedDate == today`（裁决 1，事务外 post-commit，§4.5）。若实现「沉默即停用」或「勾选→RESUME」，或「不变更则连核对日期都不写」，在此必失败。
- **C2 取消勾选再勾回 = 零状态事件**：对某在服药取消、再勾回 → 确认 → 断言事件行数不变、日志行数不变（核对日期仍写今天）。
- **C3 本流程绝不写剂量日志、也绝不动依从剂量计数**：在 `diff` 为空的核对前后，断言 `medication_logs` 行数与内容不变，且 `MedicationAdherenceCalculator` 输出**逐项相等**。
- **C4 未被触碰的药明天仍在服用**：打开、只改动药 A、确认 → 断言药 B 无新事件、`statusAt(B, 次日) == ACTIVE`；再断言「**不打开**本流程」的情形下同样无事件。
- **C5 「勾选一个在服药」不产生事件**：对当前 `ACTIVE` 且 `live kind = null`（无事件新药）保持勾选 → 确认 → 断言**零事件**（抓住「勾选→RESUME」的假变更）。
- **C6 「勾选一个在服药但 live kind = RESUME」也不产生事件**：断言零事件（CHECK）。
- **C7 一次核对里混合变更**：4 味，2 味取消勾选（在服）、1 味勾选（停用）、1 味不动 → 断言**恰好** 2 条 PAUSE + 1 条 RESUME（共 3 条事件）、第 4 味零事件。
- **C8 原因不预填**：不填原因 → 断言 3 条事件 `reason` 均为 `null`；再断言摘要页原因输入框初值为空（契约测试）。
- **C9 外部改动不产生重复事件**：摘要构建后、确认前，对某在服药注入一条外部 PAUSE（模拟别处已停）→ 点确认 → 断言**整体中止、零事件**（E15 源①），且摘要被重建为该药按当前态显示（不再出现在 `toPause`）。
- **C10 幂等**：同日核对两次 → 第二次 `diff` 空、断言事件行数不变；`lastUserCheckedDate` 两次相同（均 = 今天）。
- **C11 遗留药勾选 = 带真实日期的显式恢复**：对迁移遗留归档药勾选 → 断言恰 1 条 `RESUME`，`effectiveAtMs == 今天当地 0 点`；派生 `t<M` 仍 `UNKNOWN`。
- **C12 跨零点确认不写旧日**：摘要构建于 D 23:59，时钟到 D+1 00:01 点确认 → 断言**未写**任何事件、摘要重建为 D+1；D+1 再确认 → 事件 `effectiveAtMs == D+1 当地 0 点`。
- **C21 核对日期钉在事务验证过的 `summaryDay`（本轮细化 3）**：摘要构建于 D；确认后事务先提交，**在提交成功与 ③ 写核对日期之间把时钟拨到 D+1**（注入点：假 `TransactionRunner` 在 `block()` 返回后、`setUserCheckedDate` 前拨钟）→ 断言写出的 `epochDay == D`（**事务校验过的日**），**不是** D+1；再断言**没有**任何路径在提交后重读 `today()` 取日。
- **C13 成员切换不串写**：成员 A 打开，切到 B，确认 → 断言 A 名下零事件、整体回滚、提示要求重新核对。
- **C14 全停/全继续**：全取消 → 先过 E17 二次确认，确认后每个在服药恰 1 条 PAUSE；全勾选 → 每个非活跃药恰 1 条 RESUME。
- **C15 并发变更在事务内被检测并中止**（裁决 3a）：构建摘要后、提交前，对某在服药注入一条外部 `PAUSE`（源①：另一入口点）→ 点确认 → 断言：`medication_status_events` **零新增**、`isArchived` 缓存**零改写**、`lastUserCheckedDate` **未写**（事务外代码未执行，§4.5）、结果屏为**重建后的摘要**、无「成功」。再分别以源②（外部 `RESUME`）、源④（活动成员切换）重复同断言；对源③（自动核对只写缓存）断言**不触发中止**（活 kind 未变）。**本轮细化 2**：对**显示为「未变化」**的药注入外部变更（其活 kind 变）⇒ 提交同样**中止 + 重建摘要**（陈旧摘要**绝不**被呈现为已兑现）——覆盖「校验不止于变更药」。
- **C16 `UNKNOWN ≠ PAUSED` 不混淆**（裁决 3b）：对迁移遗留药——(a) 打开页面，断言该行**当前态 = PAUSED、复选框不勾**、角标含「迁移/未知」之意、**不含**任何被算出的开始日、**不含**「正在服用」；(b) 保持不勾 → 确认 → 断言该药**零事件**（**不**是补 PAUSE）；(c) 勾选 → 确认 → 断言**恰一条** `RESUME`、`effectiveAtMs == 今天当地 0 点`，且 `statusAt(t<M)` 仍 `UNKNOWN`；(d) 断言任何把 `t<M` 标成「暂停」或「活跃」（或据此预勾选/写 PAUSE）的实现都会失败。
- **C17 全停护栏**（裁决 3c）：成员 6 味在服药，照护者本意只停下其中 1 味，但误触「全不勾」（或一次误点清空全部复选框），此时 `p=6=n` → 点「确认核对」→ 断言**不直接提交**、弹出二次确认（默认取消、文案含 6）；选「取消」⇒ 断言**零事件**。再验阈值分支：`p=3,n=6`（= ⌈n/2⌉）、`c=5` 均触发二次确认；`p=1,n=6` **不**触发。
- **C18 标记写入失败：状态已提交、提示待写**（**失败注入**，§4.5 A2）：让 `MedicationStatusPreferences.setUserCheckedDate` 在**事务成功提交之后**抛错 → 断言：`medication_status_events` 已含本次全部事件、`isArchived` 缓存已改写、`lastUserCheckedDate` **未变**（停留旧日/缺失）、**绝不**回滚状态；并断言**没有**任何路径在 Room 事务内写核对日期（防「块内写」回归：`withTransaction` 假实现内不得观察到 `setUserCheckedDate`）。
- **C19 重跑自愈 + 无重复事件**（§4.5 A3）：紧接 C18 重跑同一核对 → 断言 `medication_status_events` **行数不变**（零重复）、`isArchived` 不变、`lastUserCheckedDate` **前进到今日**、「今天尚未核对」提示消失。
- **C20 标记不领先**（§4.5 A1）：令事务内校验失败（注入外部 `PAUSE`，源①）→ 断言事务抛出后 `lastUserCheckedDate` **未变**——即**永不**出现「标记说今天已核对、但状态回滚了」。

---

## 10. 测试清单

**JVM 纯函数 / 用例（`app/src/test`，backtick 名、`runTest`、`MainDispatcherRule`）**
- `MedicationReconcileDiffTest`：diff 四象限（勾/不勾 × active/paused/unknown/legacy）——C5/C6/C7/E6/E7 的分类断言。
- `MedicationReconcileUseCaseTest`（注入 `FakeMedicationStatusRepository`、假偏好、可拨 `Clock`）：
  - C1 无变更零状态事件 + 记核对日期（裁决 1）；C2 勾回零事件；C4 未触碰药无事件且次日仍 active；C10 幂等；C11 遗留药恢复；C12 跨零点（含 **C21**：提交后台钟跨零点 ⇒ 核对日期钉在**事务验证过的 `summaryDay`**，不重读 `today()`）；C14 全停/全继续；E9 成员切换；C8 `reason == null`；E13 整批原因。
  - `markUserChecked()` / 核对日期：单写者 + 幂等（同日重复写同值）；**C16(b)(c)** 遗留药「不勾=零事件 / 勾=恰一条 RESUME」。
- **`MedicationReconcileTransactionTest`（新，裁决 2 + 3a）**：
  - **单事务全落 / 全不落**：混合 2 味停用 + 1 味恢复 → 断言三者**同一 Room 事务**提交（`TransactionRunner` 假实现计数 `withTransaction` 调用 = 1）；令第 3 味写入抛错 → 断言**事件与缓存全部回滚**（零事件、零缓存改写）；且因核对日期在**事务外**、事务抛错时未执行 ⇒ 断言 `lastUserCheckedDate` **未写**（C20）。
  - **C15 事务内并发校验**：摘要后注入外部 `PAUSE`/`RESUME`/成员切换 ⇒ 中止 + 回滚 + 摘要重建；源③（自动核对只写缓存）**不**触发中止；**细化 2**：对显示为「未变化」的药注入外部变更（其活 kind 变）⇒ 同样中止 + 重建（校验覆盖摘要里**每一味**）。
  - **提交后 reconcile**：断言 reconcile 在事务提交**之后**触发（顺序断言），事务回滚时**不**触发（无幽灵闹钟）。
  - **失败注入（§4.5，新增）**：**C18** 提交后令 `setUserCheckedDate` 抛错 ⇒ 事件/缓存已落、标记未写、状态**绝不**回滚；**C19** 重跑 ⇒ 零重复事件 + 标记前进（自愈）；**C20** 事务内校验失败 ⇒ 标记**不领先**（未写）。另断言 `withTransaction` 假实现里**从不**观察到 `setUserCheckedDate` 调用（防「把核对日期写进事务」的回归）。**细化 1（A4）**：提交成功后令 `setUserCheckedDate` 抛错 ⇒ 断言 reconcile **仍被触发**（每个 `EventRecorded` 各一次）；另令 reconcile 抛错 ⇒ 断言状态与核对日期不受影响。**细化 3（C21）**：在假 `TransactionRunner` 里于 `block()` 返回后拨钟跨零点 ⇒ 断言写出的 `epochDay` = 事务校验过的 `summaryDay`（非新 `today()`）。
- **`MedicationReconcileGuardTest`（新，裁决 3c）**：C17 的 `p==n`、`p≥max(3,⌈n/2⌉)`、`c≥5` 三分支触发二次确认且默认取消；`p=1,n=6` 不触发；取消后零事件。
- `MedicationReconcileInvarianceTest`：C3（空差异时 `medication_logs` 与依从率输出不变）、「本流程从不调用 `MedicationLogDao` 的写接口」。

**Compose 契约测试（`app/src/androidTest`，渲染 `internal …Content` + state 对象、断言 dispatched 动作）**
- `MedicationDailyReconcileContractTest`：
  - 预勾选默认（active 勾；paused / legacy 不勾）；遗留药行角标含「迁移/未知」且**无被算出的开始日**（C16a）；
  - 点「核对下一步」→ 摘要显示正确计数（含「未变化 N 味」）；点「返回修改」→ **不**派发动作；
  - 点「确认核对」→ 单组时派发一次；**混合时派发一次多 kind 入口调用**（`toPause`/`toResume`/`today`/`reason` + 期望 kind 映射，空组不写）；
  - **全停 / 批量**：命中 E17 ⇒ 「确认核对」先弹二次对话框、默认取消（C17）；
  - 原因输入初值为空（C8）；免责文案含「不记录…服药」之意（四语各自断言）；
  - **过期摘要**：state 被标 stale ⇒ 渲染重建态与提示（C15）。
- `MedicationStatusStringsLocaleTest`（扩展）或 `MedicationReconcileStringsLocaleTest`：核对键四语齐备（含新增护栏 / 重建 / 遗留未知键）。
- 受影响既有测试需同步：**因 `applyBatch` 变薄包装**（转调 `applyStatuses`），核对其**对外语义未变**（返回顺序、`UserChecked`/`EventRecorded`、非法 kind / 未来日 / 无成员守卫、`lastUserCheckedDate` 的「有 CHECK 才写」）⇒ `MedicationStatusStatusWriteTest` 应**原样通过**（如 `batch pause writes every event in one pass...`、`repeating the same kind is a check...`）；仅当实现细节变更导致计数/顺序变化时才更新。

**真机（复用 skill 的 screencap+OCR 验证约定，设备被占用时停手）**
- 打开核对 → 预勾选与状态一致（**含遗留药不勾**）→ 改动 2 味 → 摘要计数正确 → 确认 → 我的药品角标 / 今日计划 / 提醒随之变化 → **剂量日志与坚持率不变** → 「今天尚未核对」提示消失。
- **全停护栏**：不勾全部 → 确认 → 二次对话框出现 → 取消 → 无变化。
- **并发**：摘要页停留期间在详情页停用某药 → 返回确认 → 摘要被重建、无半截写入。

---

## 11. 影响面（文件级）

**新增**
- `app/.../feature/medications/reconcile/MedicationDailyReconcileViewModel.kt`（状态机 + `diff` + 编排 + 摘要重建）
- `app/.../feature/medications/reconcile/MedicationDailyReconcileScreen.kt`（`internal …Content` + 状态对象，供契约测试）
- `app/.../feature/medications/reconcile/MedicationReconcileDiff.kt`（纯函数 `diff` + `ReconcileDiff`）
- `app/.../feature/medications/reconcile/MedicationReconcileGuard.kt`（纯函数：全停 / 批量阈值判定，§7 E17）
- `Route.MedicationDailyReconcile`（`ui/navigation/MedLogDestinations.kt`，**非**顶层目的地）
- 测试：`MedicationReconcileDiffTest`、`MedicationReconcileUseCaseTest`、`MedicationReconcileTransactionTest`、`MedicationReconcileGuardTest`、`MedicationReconcileInvarianceTest`、`MedicationDailyReconcileContractTest`、locale 测试（扩展或新增）
- 本文档

**修改**
- `ui/MedLogApp.kt`：注册 `composable<Route.MedicationDailyReconcile>`（照 `:252-259` 的写法）
- `feature/medications/list/MyMedicationsScreen.kt`：入口 chip + 「今天尚未核对」提示（读 `userCheckedDate`，经 VM）
- `feature/medications/list/MyMedicationsViewModel.kt`：暴露 `userCheckedToday`（读 `MedicationStatusPreferences.userCheckedDate`）
- `feature/medications/application/SetMedicationStatusUseCase.kt`：抽出**唯一实现 `applyStatuses(...)`**（§4.3，把现 `applyBatch` 体 `:83-123` 搬入，加**可选**事务内校验 + `CheckedDatePolicy`）；`applyBatch`/`apply`/`userCheck` 变**薄包装**（对外签名与语义不变）；新增 **`applyReconcile(...)`**（混合 kind）与 **`markUserChecked()`**（复用 post-commit 写入与键，§4.5）
- `res/values*/medication_flow_strings.xml`：新字符串 ×4 语（§3.3，含护栏 / 重建 / 遗留未知键）

**不改**
- **冻结的数据模型与语义**：`medication_status_events` 表 / 结构 / 派生 / 迁移版本（**不**改 Room schema、**不**加 kind、**不**改 append-only 与取代规则）。
- `applyBatch` 的**既有语义与对外签名**（实现改为转调 `applyStatuses` 的薄包装，行为不变）；`medication_logs` 及其撤销语义；打卡主流程；`TOP_LEVEL_DESTINATIONS`（仍 5 个）。
- **不改冻结数据模型/schema**；对 `docs/medication-status-spec.md` 的**唯一**改动 = **本轮已应用的 E10 最小措辞修订**（放行混合 kind 单事务 + 声明 `lastUserCheckedDate` 为事务外 post-commit 事实）；**不改**其 §11 之外任何内容，**不**改表/列/kind/派生（§4.3）。

**Phase 2（不在本轮）**
- 可选的核对完成后的轻量变更留痕（复用既有取代链读数，不新增机制）。

---

## 12. 任务分解（分阶段，每步可独立验证）

**Phase 1（首期；整次核对的 Room 侧写入 = 一个事务，核对日期事务后；裁决 2）**
- [x] T1 纯函数 `diff` + `ReconcileRow` + `MedicationReconcileGuard`（`MedicationReconcileDiffTest`：四象限；`MedicationReconcileGuardTest`：E17 三分支）
- [x] T2 `SetMedicationStatusUseCase.applyStatuses(...)`（**唯一实现**，§4.3：Room 事务内校验 + 逐药写事件/缓存；核对日期与 reconcile 在**提交后**）+ `applyBatch`/`apply`/`userCheck` 薄包装；`applyReconcile(...)` + `markUserChecked()`（复用 post-commit 写入与键）。`MedicationReconcileTransactionTest`：单事务全落/全不落、**失败注入 C18/C19/C20**、提交后 reconcile
- [x] T3 核对用例 / VM 编排（调用 `applyReconcile`；`MedicationReconcileUseCaseTest`：C1/C2/C4/C7/C8/C10/C11/C14/C16；`MedicationReconcileInvarianceTest`：C3）
- [x] T4 核对屏（列表 → 摘要 → 结果 + 摘要重建 + 二次确认对话框；`internal …Content` + 状态对象）+ 子路由注册 + 入口 chip（`MedicationDailyReconcileContractTest`；`NavigationHierarchyTest` 仍 5）
- [x] T5 四语文案 + 无障碍语义（locale 测试；免责文案四语断言；护栏/重建/遗留未知键）
- [x] T6 `我的药品`「今天尚未核对」提示（读 `userCheckedDate`）
- [x] T7 三条边界：并发中止与摘要重建（C15 / E15）、遗留药 `UNKNOWN≠PAUSED` 复选框（C16 / E16）、全停护栏（C17 / E17）
- [ ] T8 **真机全链路（核对屏冒烟，§10 真机项）—— 明确未做**：待设备窗口（设备被占用 / 时间预算，本轮仍无法执行）。JVM 门已通过（`testDebugUnitTest` 924/0 + `ktlintCheck` 绿），androidTest 源已编译；真机行为在文档内不可证（§17 残余 7）。
- [x] T9 文档收口（本文件定稿）

**Phase 1 前置（文档，非本流程代码）**
- [x] 修订冻结稿 `docs/medication-status-spec.md:647` §11 E10 的句子以**显式放行**「一事务内**混合 kind**」并明确「`lastUserCheckedDate` 是**事务外 post-commit** 事实、**不在**该事务边界内」（§4.3）——**本轮已获批准并应用**（纯措辞，不改数据模型）。

**Phase 2（不在首期）**
- [ ] 可选的核对完成后轻量变更留痕（复用既有取代链读数，不新增机制）。

---

## 13. 非目标（明确不做）

- **不记录今天是否服药**：本流程不写 `medication_logs`、不进依从分母、不是剂次依从证据（§0、D1）。**「零写入」= 零状态事件 + 零剂量日志**；**登记「今天已核对」不算写入**（裁决 1）。
- **不改冻结数据模型**：不新增状态 kind、不改 `medication_status_events` schema、不改派生 / append-only / 取代规则；混合 kind 单事务只用既有 `PAUSE`/`RESUME` 与既有事件表（裁决 2、D9）。
- **不改冻结稿的数据模型**：仅对 `docs/medication-status-spec.md` §11 E10 行应用**已批准的最小措辞修订**（§4.3）；不改其表 / kind / 派生 / append-only / 迁移版本。
- **不是提醒/闹钟**：不新增任何定时器或通知；不改提醒排程逻辑（仅由**提交后** reconcile 触发既有重排，§4.4）。
- **不替代「今日计划打卡」**：今日计划（`docs/medication-daily-flow.md`）的剂次勾选仍走既有打卡主流程；本流程与它是**两个不同事实**（继续吃 vs 这一剂吃了）。
- **不新增底部 tab**：入口是子路由，顶层仍 5 个（`NavigationHierarchyTest.kt:10`）。
- **不为核对单独建事件表 / 缓存列 / 迁移**；不改 Room 版本。
- **不预填原因**；不引入第二套「沉默即停用」语义。
- **不把 `UNKNOWN` 当 `PAUSED` 或 `ACTIVE`**（裁决 3b）；本流程与页面**不追溯重写**迁移前历史段。
- **不禁止「全停」**：只要求显式二次确认，不拦合法医嘱（裁决 3c）。
- **不做未来生效日**（冻结稿 D11）；核对生效日固定为「今天」。
- 不做跨设备同步 / 云端核对历史。

---

## 14. 验收

1. **入口**：`我的药品` 出现「今日用药核对」入口；进入后列出该成员**全部**药（活跃 + 已停用），**活跃预勾选、其余（含遗留药）预不勾选**；顶层导航仍 5 个目的地。
2. **非服药语义**：核对页与结果页文案明确「不记录今天是否服药」；全流程**不写** `medication_logs`；空差异时坚持率输出**逐项不变**。**不变的确认仍记录「今天已核对」**（裁决 1）。
3. **提交前摘要**：点「核对下一步」显示「将停用 X 味／将恢复 Y 味／未变化 Z 味」与生效日期；「返回修改」**不写任何东西**。
4. **只写变化**：确认后 `medication_status_events` 只对**实际变化**的药各加一行；预勾选未动的药**零事件**。
5. **幂等**：同一当地日核对两次 ⇒ 第二次空 diff、**零新状态事件、零剂量日志**；且两次都登记了当天的核对日期；提交前取消再勾回 ⇒ 零状态事件。
6. **绝不自停**：从不打开本流程、或打开后一行未动 ⇒ 零状态事件、零剂量日志；未被触碰的药**次日仍为在服**（active）。
7. **混合变更 = 一个（Room）事务**：一次核对里停用与恢复共存 ⇒ 两类事件 + 缓存写入在**同一事务**内；令任一写入失败 ⇒ **事件与缓存全部回滚**（零事件、零缓存改写）；核对日期因**事务外**写入未执行而未写（**非**回滚，§4.5）。事件数 = 变化药数。
8. **生效日**：所有事件 `effectiveAtMs` = 确认当日**成员提醒时区**当地 0 点；无未来日。
9. **遗留药（裁决 3b）**：复选框看**当前态**（当前为 `PAUSED` ⇒ 不勾）；不勾 ⇒ 零事件；勾选 ⇒ **一条带真实日期的 `RESUME`**；`t<M` 仍 `UNKNOWN`（不重解释为一直在服、也不称暂停）；行内文案含「迁移/未知」且**无被算出的开始日**。
10. **并发变更（裁决 3a）**：打开到确认之间某药在别处被改 / 成员切换 ⇒ 提交**整体中止**（零事件、零缓存、零日期）、摘要按当前状态**重建**、提示重新确认；**绝不**应用过期 diff、**绝不**半截成功。系统自动核对（只写缓存）**不**触发中止。
11. **全停护栏（裁决 3c）**：`toPause` 覆盖全部活跃药，或 `p≥max(3,⌈n/2⌉)`，或 `c≥5` ⇒「确认核对」**不足以**提交，须经独立二次确认（默认取消）；取消 ⇒ 零事件；`p=1,n=6` 不触发。
12. **成员隔离**：切换成员后确认**不**把 A 的勾选写进 B；整体回滚并提示重新核对。
13. **原因**：字段默认空、**绝不预填**；留空则事件 `reason == null`；填写则整批事件共用该原因。
14. **四语 + 无障碍**：核对相关字符串四语齐备且免责语义不弱化；每行对 TalkBack 暴露「药名 + 当前状态 + 勾选态」。
15. **零回归 + 冻结模型不变**：既有批量停用/恢复/归档、打卡、提醒、依从率、导航行为不变；**Room schema / kind / 派生 / append-only 未改**；`docs/medication-status-spec.md` **未被编辑**；`ktlintCheck` 通过。

---

## 15. 已裁决事项（无待决项）

> 本节原先的两项待裁决**已由用户裁决**，记录如下；本文档据此定稿。

1. **空差异确认是否记录「今天已核对」？ → 裁决：记录。** 确认即完成一次核对，`lastUserCheckedDate` 推进、「今天尚未核对」提示随之消失（§2 D5）。故本文所称「**零写入**」一律精确指「**零状态事件 + 零剂量日志**」，**明确允许**这次核对日期的写入（§1.2、§5、§9 C1/C2、§13）。
2. **两组的原子性？ → 裁决：整次核对的 Room 侧写入一个事务；核对日期事务外（本轮更正范围）。** 混合停用/恢复的**状态事件与缓存**要么全落、要么全不落（§2 D12、§4.2）。复用既有机制、**不改冻结数据模型**。**关键更正**：`lastUserCheckedDate` 在 **DataStore**（非 Room），**不可能**加入 Room 事务（§1.6），故它是**提交后的独立 post-commit 事实**（§4.5），**不在**回滚边界内。因混合 kind 需把写入统一到一个**应用层入口点**（其精确事务边界见 §4.3），需**修订冻结稿 `docs/medication-status-spec.md:647` §11 边界情形 E10（批量的原子性）的句子**（**本轮已应用该最小修订**）——该句现描述同类批量操作：**「一次事务内为 N 个药写事件 + 缓存列；任一失败整体回滚（对齐 `updateMedications` `MedicationRepositoryImpl.kt:83-86`）」**；最小修订 = 增补 (i)「同批各药可混合 kind」与 (ii)「`lastUserCheckedDate` 是事务外 post-commit 事实、不在该事务边界内」（**不**得写成「一并写入」）。**本流程只点名、不编辑该文件**（§4.3、§13）。
3. **三条边界**（各已给规则 / 反例 / 测试）：并发状态变更事务内检测并中止（§7 E15 / §9 C15 / §10）；`UNKNOWN ≠ PAUSED` 决定遗留药复选框（§7 E16 / §9 C16 / §10）；误触全停的显式护栏（§7 E17 / §9 C17 / §10）。

---

## 16. 自洽性检查（本轮，逐对核验）

| # | 检查的对 | 证据 | 裁决 |
|---|---|---|---|
| 1 | **裁决 1（不变确认也记录「今天已核对」） vs 「零写入」反例** | §2 D5 明确「零写入 = 零状态事件 + 零剂量日志，允许核对日期写入」；C1/C2/E2 均已改写为「断言事件与日志行数不变、**且**核对日期 = 今天」 | **一致**（不再自相矛盾） |
| 2 | **D5 登记核对日期 vs 冻结稿 §8.2（CHECK 不写事件/缓存、只写日期）** | 核对日期由**同一应用层入口点**在**事务提交后**写（**DataStore，事务外**，§4.3/§4.5）；「只写日期、不写事件」的冻结语义仅指**无变化的 CHECK 情形**，本文在该情形下同样零事件、零缓存 | **一致**（同一事实、同一键、同一写者；且**不再**谎称同事务） |
| 3 | **Room 单事务 vs 核对日期 / reconcile 提交后** | §4.2/§4.4/§4.5：**Room 事实（事件+缓存+校验）**在事务内；**核对日期（DataStore）与 reconcile 都在提交后**；回滚时不执行后两者 | **一致**（跨存储无回滚；副作用留事务外，回滚无幽灵闹钟） |
| 4 | **Room 单事务 vs 冻结稿 §11 E10（批量原子性）** | E10 描述同类批量事务；§4.3 点名该句为需修订处、要求显式放行混合 kind，并明确核对日期为**事务外 post-commit**；**未改冻结稿** | **一致**（点名而非越权修改） |
| 5 | **边界 b（裁决 3b） vs 遗留药既有测试（冻结稿 §9.4/§9.8）** | 复选框看**当前态** `statusAt(events, now)`（`now≥M` ⇒ `PAUSED` ⇒ 不勾）；`t<M` 恒 `UNKNOWN`、不追溯；不勾=零事件、勾=恰一条 `RESUME`——与冻结稿「首个 RESUME 不重解释 D 之前」同向 | **一致**（复用同一派生命题，无第二套判定） |
| 6 | **边界 c（护栏，加确认） vs 边界 a（并发中止并重建摘要）** | **不同触发源、不同动作**：c 由**用户自身聚合意图**触发（确定性、无竞态），在**打开事务之前**，动作 = **追加一次显式确认**（不阻断、不重建）；a 由**外部变更**触发（竞态），在**事务之内**，动作 = **整体回滚 + 重建摘要**。**次序**：先 c（确认意图），后 a（事务内校验）；若 a 重建了摘要，则按**重建后**的摘要**重新评估 c**（活跃药数可能已变）。故不存在「c 悄悄放行、a 悄悄改写」的重叠 | **一致**（互补不矛盾） |
| 7 | **边界 a 的「另一成员活动」 vs E9 成员切换** | 同一处收敛：事务内成员门槛 `check(recipientId != NO_RECIPIENT)` 与活动成员比对失败 ⇒ 中止回滚 + 提示重新核对 | **一致** |
| 8 | **Day-boundary（§6） vs 边界 a（事务内校验）** | 跨零点校验与并发校验**同一处**（事务内 D13 前置）：`todayAtCommit != summaryDay` ⇒ 中止 + 重建 | **一致**（同一中止通道） |
| 9 | **单事务 vs 幂等（§5）** | 变化判定键在当前活状态 kind；重试只见「未落」或「全落」两态；同日重复核对 = 空 diff + 仍记日期 | **一致** |
| 10 | **「不改数据模型」 vs 统一入口点** | 入口点只用既有 `insertEvent`/`setArchivedCache`/`liveStatusKind`/`withTransaction`；核对日期仍在 DataStore、**不迁 Room**；不新增表/列/kind/迁移 | **一致**（应用层新增，模型零改） |

| 11 | **跨存储原子性 vs §4.2/§4.3/D12 的旧承诺（本轮新增）** | §1.6 核查证实 `lastUserCheckedDate` 在 **DataStore**、`withTransaction` 只开 Room 事务 ⇒ 跨存储不可原子；§4.2/§4.3/D12/§4.5 已改为「Room 侧一个事务 + 核对日期事务后」；旧「含核对日期一个事务」措辞**全部清除** | **一致**（更正了原稿的**事实错误**） |
| 12 | **核对日期的写入顺序 vs 不变量 MARK-LAG（§4.5，本轮新增）** | 写入在 `withTransaction` **之后**；事务抛错时该代码不执行 ⇒ 标记**永不领先**（A1），失败只表现为**滞后**（A2，自愈 A3） | **一致**（顺序由不变量决定） |

| 13 | **③ 写核对日期 vs ④ reconcile（细化 1）** | §4.3 步骤 3：二者**都在提交之后、各自 `runCatching` + warn**；核对日期失败不阻断 reconcile（A4），reconcile 失败不影响已提交状态/已写日期 | **一致**（独立失败路径，互不短路） |
| 14 | **事务内校验范围 vs D13/E15（细化 2）** | 校验遍历 `expectedKindByMedication` 的**每一个键** = 摘要里显示的**每一味**（含未变更者）；显示为「未变化」却被外部改变 ⇒ 同样中止 + 重建（C15 细化分支） | **一致**（覆盖全量显示药，杜绝陈旧摘要被兑现） |
| 15 | **核对日期取日 vs 跨零点（细化 3）** | ③ 用事务已验证的 `summaryDay`（= ② 校验 `todayAtCommit == summaryDay` 的那个日），**不**重读 `today()`；跨零点注入（C21/A5）可证写出旧日；单 kind 旧路径（`summaryDay == null`）仍用 `today(zone)` | **一致**（写哪天 = 校验过哪天 = 显示哪天） |

**自洽性结论**：本轮消解的历史矛盾（裁决 1 与「零写入」措辞、单事务与「两次 `applyBatch`」、护栏与并发中止的职责）**加上一项事实更正**——原稿把 **DataStore** 的核对日期写进 **Room** 事务边界是**错的**（§1.6），已按「Room 侧一个事务 + 核对日期事务后 post-commit」全线更正（§4.2/§4.3/§4.5/D12/§5/E2/E15/§14/§15/§17）。

---

## 17. 复核结论与残余风险（诚实清单）

**复核结论**：本稿在用户三项裁决下自洽——① 不变的确认仍记录「今天已核对」，「零写入」被精确重定义为「零状态事件 + 零剂量日志」，与全部反例/测试一致；② 整次核对的 **Room 侧写入为**一个事务**（事件 + 缓存 + 事务内校验），而**核对日期 `lastUserCheckedDate` 是事务外的 post-commit 弱事实**（DataStore，跨存储不可原子，§1.6 / §4.5），**未改冻结数据模型**；其所需的多 kind 单事务由**统一入口点** `applyStatuses` 承载（§4.3），并对冻结稿 `docs/medication-status-spec.md:647` §11 E10 应用**最小措辞修订**（放行混合 kind + 声明核对日期为事务外 post-commit）（**本轮已编辑该文件，仅 E10 一行**）；③ 三条边界（并发中止、`UNKNOWN≠PAUSED`、全停护栏）各配规则/反例/测试，且与既有并发/成员/跨零点路径**同通道**、互不矛盾。**本轮据用户反馈更正了原稿的跨存储原子性事实错误；只改本文档、未改业务代码。**

**残余风险（本设计无法保证的）**：
1. **冻结稿 §11 E10 已按批准的最小修订放行**（本轮应用）：现显式允许「同一批量事务内各药携带不同 kind」，并声明 `lastUserCheckedDate` 为**事务外 post-commit** 事实（不在该事务边界/回滚内）；冻结稿的表 / kind / 派生 / 迁移版本**未改**（§4.3）。
1b. **核对日期的跨存储原子性不可得（§4.5）**：Room 事务与 DataStore 写入**无法成组提交**；故「事务已提交、标记未写」的窗口 ② 真实存在，表现为**「今天尚未核对」提示滞后/误显**，**不影响**状态、提醒、依从率；它**自愈**于下一次核对，但若用户**永不**再确认则提示会一直陈旧。本流程**不**用假事务消除它，**也**不为它付 Room 迁移代价（方案 b 已被否）。
2. **事务内校验是乐观的、且是 kind 级而非事件级**：它只能检测「摘要假定 kind ≠ 提交时活 kind」。若外部变更**不改变**该药的活 kind（例如把 `PAUSE` 更正为另一条 `PAUSE`、或改 `reason`），校验**不会**中止——这类变更对本次核对**语义无影响**，但「摘要所见 == 提交时状态」只保证到 **kind** 粒度。
3. **提交后 reconcile 的窗口**：对**恢复**方向，若 reconcile 被 Doze 推迟，可能**少排**提醒（守卫只抑制错误提醒、不补排漏排）；这是既有「两步窗」，本流程**未消除**（§4.4）。
4. **全停护栏是启发式的**：阈值（`p==n`、`p≥max(3,⌈n/2⌉)`、`c≥5`）挡住的是**聚合误触**，不挡「照护者本意如此」的合法全停；也**不挡分批误触**（先停一半、再停另一半，两次都低于阈值）——本设计**不**承诺拦截此类。
5. **迁移前「是否在服」不可知**：本流程沿用冻结稿的 `UNKNOWN`，**不试图恢复**；页面只是**不谎报**，不是**知道**。
6. **缓存陈旧对非提醒面**：小组件/今日计划读缓存，可能有一次刷新窗口的陈旧显示（沿用冻结稿 §7 残余）；本流程不改变该性质。
7. **真机行为文档内不可证**：二次确认对话框的实际可达性/误触率、提醒取消时延、摘要重建的用户感知，须 §10 真机与后续可用性评估。
