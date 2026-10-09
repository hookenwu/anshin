# 照护事件间隔追踪（Care Event）—— SDD 规格（第 2 稿，已纳入用户 Review）

状态：**设计定稿候选；本轮不改业务代码、不提交**
首期范围：**仅「排便」**（`kind = BOWEL`）；模型保留扩展到排尿/呕吐等**其它事件**的能力，但**本期不实现**。
关联：`docs/care-tasks.md`（照护事项＝主动干预）、《今日页不新增区块》约束见 `docs/care-notes.md §0.4/§7` 与 `docs/record-center-spec.md §D5`；实体边界对照见 `docs/todos.md §5`。

> 用户 Review 五点已逐条落入：① 首期只做排便、模型可扩展；② 今日页加**轻量状态 + 快捷记录行**（**不加新 Tab**）；③ 无记录不提醒、提醒**默认关**、3 天只是**可改的推荐阈值**；④ **只有一张日志表**、不做类型管理；⑤ 补记/编辑/删除、**重启·重启后的提醒去重与过期、时间计算、成员隔离、备份/恢复**全部补齐。

---

## 0. 三个边界（用户明确，本规格逐条应对）

1. **区分「事件实际发生」与「照护者记录」**——不得把「3 天未记录」表述成「3 天未发生」。
2. **不同事件未必共用一套提醒**——排便要超期提醒，呕吐可能只记录不提醒（首期不做提醒扩展，但模型/配置按键预留）。
3. **补记、误记、删除后，状态与提醒必须重新计算**，且不得残留过期提醒。

---

## 1. 现状事实（代码核查，file:line；第 1 稿调研结论，沿用不重做）

| 事实 | 位置 |
|---|---|
| 现有「记录/任务」实体：Medication、CareTask、CareTodo、HealthRecord；另加 CareNote、CarePerson、SymptomLog | `core/database/.../model/` |
| `CareTask` 是**计划型干预**（吸氧/翻身/言语训练），排期 `FIXED_TIMES/INTERVAL/AS_NEEDED` | `CareTask.kt:15-65` |
| `HealthRecord` 是**数值型**体征（`value: Double`，类型为 `HealthType.name`）——**装不进「排便」这种非数值事件** | `HealthRecord.kt:25-72`、`HealthType.kt:8-36` |
| `MedicationLog` 撤销＝物理删除；成员行经父 id 继承、按成员 JOIN 过滤 | `MedicationLog.kt:8-44`、`CareTaskLogDao.kt:31-40` |
| 提醒：`AlarmScheduler`（@Singleton）+ `ReminderTargetType`，登记表持久化于 SharedPreferences（`ALARM_PROJECTION_PREFERENCES`），键 `<recipientId>:<type>:<id>`；`MedLogBootReceiver` 监听 `BOOT_COMPLETED/TIME_SET/TIMEZONE_CHANGED` → `ReconcileRemindersUseCase` 重建闹钟 | `AlarmScheduler.kt:46-57,178-196`、`MedLogBootReceiver.kt:18-24` |
| 提醒语义是**前向「下一次」**：`intervalAnchor = lastTakenAt + every` | `ReminderPlanner.kt:32-50` |
| 成员级偏好约定：DataStore 键为 `${legacy.name}#$recipientId` | `UserPreferencesRepository.kt:397-421` |
| 成员隔离约定：`ActiveRecipientStore.NO_RECIPIENT`（0L）时**读不发射、写抛错** | 各 `*RepositoryImplTest`、`docs/care-people.md:85` |
| `DatabaseSchema.VERSION = 24`；`BackupCompatibilityPolicy.canRestore` 上界**跟随 VERSION** | `DatabaseSchema.kt:6`、`BackupCompatibilityPolicyTest.kt:20-28` |
| `MedLogDatabaseMigrationTest` 有**5 处硬编码迁移列表**（均已含 `MIGRATION_23_24`） | `core/database/src/androidTest/.../MedLogDatabaseMigrationTest.kt` |
| **全库无「排便/排泄/事件间隔」既有概念** | 全库 grep |

---

## 2. 领域推导：Care Event 是什么、不是什么

关键区分：`CareTask` 是**计划型**（「每 2 小时翻身」＝**我主动去做**）；「排便」是**事件型**（「发生了才记录」）。前者是「下一次＝上次＋间隔」的前向排期，后者是**对已发生之事的回溯记录 + 间隔追踪**（缺席型），语义不可复用。

| 维度 | Medication | CareTask | CareTodo | HealthRecord | **Care Event** |
|---|---|---|---|---|---|
| 本质 | 用药计划 | 计划型干预 | 一次性跟进 | 数值型测量 | **发生了才记录的事件** |
| 时间方向 | 前向排期 | 前向排期 | 无 | 回溯记录 | **回溯记录 + 间隔追踪** |
| 记录粒度 | 剂量/部分服用 | 完成/起止/体位 | 状态/关闭 | 数值 | **发生时刻（可补记为过去）** |
| 提醒 | 下一次服药 | 下一次干预 | 无 | 无 | **「距上次超阈值」缺席型（默认关）** |
| 核心指标 | 依从率 | 完成数 | 是否闭环 | 数值趋势 | **距上次发生的间隔** |

**判据（给读者的一句话）**：**我记录的是「我执行的一次照护动作」，还是「发生的一件事」？** 动作 → `CareTask`；一件事 → Care Event。

**结论（D0）**：Care Event 是**一等新概念**，不复用 `CareTask`（前向 vs 回溯语义冲突）、不复用 `HealthRecord`（数值型装不下非数值事件）。复用的只有两样：**成员作用域**、**既有一次性闹钟 + 成员级 DataStore 偏好基础设施**。

---

## 3. 关键设计决策

**D1 — 首期只做排便；模型用「一张表 + 一个 kind 常量」，可扩展、零类型管理。**
- 首期唯一 `kind = BOWEL`。事件类型是**代码常量**（`object CareEventKind { const val BOWEL = "BOWEL" }`），**不是用户可管理的数据**——因此**不建类型表、不做类型 CRUD UI**。
- `kind` 以字符串存列（对齐 `HealthRecord.type` 的存法）。将来加排尿/呕吐 ＝ 新增一个常量 + 一组偏好键，**无 schema 变更**。
- **不做**两张表（`care_event_types` + logs）方案——类型管理 UI + 级联/归档整套与「最简落地」冲突；真机验证确实需要时再作为独立迁移补。

**D2 — 应对边界 1（发生 vs 记录）：一条日志带两个时间戳。**
- `occurredAtMs`：事件**实际发生**时刻（补记时可为过去）。**间隔一律按它计算**。
- `createdAtMs`：照护者**录入**时刻（默认为 now；补记时＝录入时刻，与发生时间独立）。
- 文案口径固定为「距上次**记录**排便已 X 天」或「上次排便：X 天前」；**禁用**「X 天未排便」这种断言未发生的表述。这是文案层硬规则。

**D3 — 应对「不同事件不同提醒」：配置按 (成员, kind)，不搞统一引擎。**
- 每 (成员, kind) 独立：`enabled`（开关）+ `thresholdDays`（阈值）。首期只暴露排便的一组。
- 阈值/开关存在**既有成员级偏好层**（DataStore，键 `<key>#<recipientId>`），**不建表**——这就是「只有一张日志表、却仍能个性化阈值」的落法。

**D4 — 应对边界 3（补记/误记/删除后重算）：间隔是派生值，提醒每次变更重排。**
- **间隔不落库**：永远由「最新一条 `occurredAtMs`」读出后现算，无冗余状态可失同步。
- 补记（发生在过去）→ 最新发生时间可能不变或前移，间隔与提醒按**新的最新发生时间**重算。
- 误记（时间错）→ **就地编辑**该条 `occurredAtMs`（置 `updatedAtMs`）后重算。
- 删除 → **物理删除**（对齐 `MedicationLog` 撤销＝删除）。删最新一条 → 锚点回退到次新并重算；删到无记录 → 无锚点、**取消**提醒。
- 所有写入收敛到**单一命令入口**（对齐 `ToggleMedicationDoseUseCase` 的单命令约定），写完即重排提醒。

**D5 — 提醒语义：缺席型，默认关，阈值是「照护者自己的关注间隔」。**
- 读作「照护者对自己关注节奏的设置」，**绝不等同于医学/临床阈值**；文案与设置页都必须显式标注「这是你自己的关注间隔，不是医学建议」。
- 3 天只是**推荐默认值**，可改。
- **无任何记录 → 不提醒**（没有锚点，「从未记录」提醒属后续，见 §12）。
- 详见 §5 的精确规则（每日一次 + 跨重启去重 + 过期/重算）。

**D6 — 今日页：一条紧凑状态行 + 一键记录，刻意、经用户批准的例外。**
- 见 §6。**不是**新区块卡片、**不是**新底部 Tab。

**D7 — 与既有四实体的边界**：见 §7。

**D8 — 迁移 v24 → v25 纯新增；备份/恢复上界随 VERSION 自动放行。** 见 §8。

---

## 4. 数据模型（D1 落地形态）

**唯一一张表（首期）**：

```kotlin
// 事件类型是代码常量，非用户可管理数据
object CareEventKind { const val BOWEL = "BOWEL" }   // 将来可加 URINATION / VOMITING（无 schema 变更）

@Entity(tableName = "care_event_logs",
        foreignKeys = [/* careRecipientId → care_recipients, ON DELETE CASCADE */],
        indices = [Index(value = ["careRecipientId", "kind", "occurredAtMs"])])
data class CareEventLog(
    val id: Long = 0,
    val careRecipientId: Long,
    val kind: String,               // CareEventKind.* —— 代码常量，不做类型表
    val occurredAtMs: Long,         // 事件实际发生时间（补记可为过去；间隔按此算）
    val note: String? = null,       // 可选备注
    val createdAtMs: Long = System.currentTimeMillis(),  // 录入时刻（发生 vs 记录，见 D2）
    val updatedAtMs: Long? = null,  // 就地编辑时置位
)
```

- 复合索引 `(careRecipientId, kind, occurredAtMs)` 同时覆盖「取某成员某 kind 的最新一条」（仓储的主查询）。
- 无 `revisionType`：本特性**就地编辑 + 物理删除**即可（对齐用户方向），不引入版本链。
- **提醒配置（既有成员级偏好层，键 `<key>#<recipientId>`，不建表）**：
  - `care_event_reminder_enabled#<recipientId>#<kind>`（默认 **false**）
  - `care_event_reminder_threshold_days#<recipientId>#<kind>`（默认 **3**，可改）
  - `care_event_nudged_day#<recipientId>#<kind>`（去重标记，见 §5）
- 新装**不预置任何键**＝默认全关；键按 kind 分片，将来加 kind 无需改键名。

---

## 5. 提醒规则（精确）

| # | 规则 |
|---|---|
| R1 | **默认关**：`enabled` 缺失即 false。用户可在设置页为排便开/关，并改阈值（推荐 3 天）。 |
| R2 | **无记录不提醒**：`kind` 下无任何日志 → 无锚点 → 取消闹钟、不提醒。 |
| R3 | **锚点**＝该 (成员, kind) 最新一条的 `occurredAtMs`。 |
| R4 | **超期判据**：`now - anchor >= thresholdDays * 86_400_000`（**绝对时长**，与本地日界/夏令时无关，避免 DST 误差）。 |
| R5 | **每日至多一次**：一旦超期，在成员**本地日历日**内**最多提醒一次**；超期持续期间，其后每天都在该成员提醒时刻排一次，直到记录新事件或关闭开关。 |
| R6 | **跨重启/重启去重**：提醒只在「当日尚未提醒过」时发出。发出前后都要**再校验**：`已超期 && 今日 local epochDay != care_event_nudged_day#…` 才发；发出后写 `care_event_nudged_day#… = 今日 epochDay`。该标记是**持久化成员级偏好**（非内存），因此 `MedLogBootReceiver` 在 `BOOT_COMPLETED/MY_PACKAGE_REPLACED/TIME_SET/TIMEZONE_CHANGED` 后重建闹钟时，**同一天不会重复提醒**。 |
| R7 | **过期/重算**：任何写入（新增/补记/编辑/删除）后，取消该 (成员, kind) 的旧闹钟并按新锚点重排。编辑或补记使最新时间前移、不再超期 → **取消**；删除最新一条使锚点回退 → 按次新锚点重排；删到无记录 → 取消（R2）。 |
| R8 | **删除不残留**：删除成员 → FK CASCADE 删 logs → 无锚点 → 取消闹钟；成员级偏好键随成员删除一并清理（沿用既有约定）。被删除的日志**绝不**留下过期提醒。 |
| R9 | **实现**：复用 `AlarmScheduler` 的一次性 `scheduleExact`，新增 `ReminderTargetType.CARE_EVENT` 与**独立 PendingIntent 编号基数**（对齐 `CARE_TASK_CODE_BASE = 100_000_000` 的分区做法）。语义是**缺席 deadline**，**不得**套用 `ReminderPlanner.nextOccurrences`。 |
| R10 | **措辞**：通知读「已 X 天未**记录**排便」并附「你关注的间隔」中性说明；**禁止**任何医学判断或因果措辞。 |

---

## 6. 今日页：一条紧凑状态行（刻意例外）

**形态**：**单行**（`Row`，非卡片、非区块）——左侧读状态（如「距上次记录排便 X 天」），右侧一个 `记录` 文本按钮，一键写入一条 `occurredAtMs = now` 的日志。无记录时该行显示引导（「还没有排便记录 · 记录」），**不排提醒**（R2）。

**位置（关键）**：渲染为今日页 `LazyColumn` 的**最后一项**——排在**服药/照护时间轴（及其「按需」专区）之后**。今日页现有条目顺序见 `HomeScreen.kt:297-545`（hero → 待办 → 低库存 → 相互作用 → 筛选 chips → 时间轴 → 按需专区）。这样**时间轴之上不新增任何元素，其上移量＝0**。

**为何可接受（写明这是刻意例外）**：`docs/care-notes.md §0.4/§7` 与 `docs/record-center-spec.md §D5` 的硬约束是「**首页不新增区块，避免安全关键的服药时间轴下沉**」。本行是一条**运行状态 + 快捷动作**，且**置于时间轴之后**，**不会**把给药信息往下推——与「参考知识/内容类」不同（那类仍不上首页）。代价：长日视图下该行在折叠线以下；接受此代价的理由是——**提醒（开启时）才是主要触达渠道，此行为便捷补充**。

**明确不做**：不加第 6 个底部 Tab（`NavigationHierarchyTest`/`CareNoteRegressionTest` 钉死 5 个顶层目的地）。

---

## 7. 与既有实体的边界（不重复）

| 你记录的是 | 用哪个 | 例子 | 本特性是否触碰 |
|---|---|---|---|
| **我要反复执行的干预**，做完重排时间 | `CareTask`（`INTERVAL`/`FIXED_TIMES`） | 翻身、吸氧 | **不触碰** |
| **一次性跟进** | `CareTodo` |「问医生确认…」 | **不触碰**；**本期不自动建 Todo** |
| **数值型体征** | `HealthRecord`（`value: Double`） | 血压/体温/体重 | **不触碰**；**不加 category 字段、不往此存事件** |
| **有归属的参考记录（知识）** | `CareNote` | 医护交代/照护经验 | **不触碰** |
| 身心日记 | `SymptomLog` | 症状/副作用流水 | **不触碰** |
| **发生了才记录的事件** | **`care_event_logs`（本特性）** | 排便（首期） | 新增 |

**判据**：动作 → `CareTask`；一件事 → Care Event。

---

## 8. 迁移、备份/恢复、成员隔离

**迁移 v24 → v25（纯新增）**：`CREATE TABLE care_event_logs` + 复合索引；**无表重建、不动既有数据**。配套（本仓反复踩坑，逐项必做）：
- `DatabaseSchema.VERSION` → **25**；注册 `MIGRATION_24_25` 到 `AppModule.provideDatabase.addMigrations(...)`；导出 **`25.json`**。
- `RoomModelContractTest` 版本断言改 **25**；**`MedLogDatabaseMigrationTest` 的 5 处硬编码迁移列表全部补 `MIGRATION_24_25`**（已核实当前为 5 处）；其余 androidTest 迁移测试（`CareTodoMigrationTest`/`CareNoteMigrationTest`/`CarePeopleMigrationTest`）按同模板同步。
- 新增 `CareEventMigrationTest`（照 `CareTodoMigrationTest` 模板）：**种一个带数据的 v24 库 → 迁移** → 断言：**旧数据零丢失**（药品/照护事项/日志/笔记/成员等仍在）、`care_event_logs` 存在且可读写、索引存在、**删除成员级联删除其事件**。
- `BackupCompatibilityPolicyTest` 钉桩更新（`assertEquals(25, VERSION)` 并补 `canRestore(24)`）。

**备份/恢复**：`BackupCompatibilityPolicy.canRestore` 的上界**跟随 `DatabaseSchema.VERSION`**，自动放行——**恢复的旧备份（如 v23/v24）在 App 首次打开时依次执行到 `MIGRATION_24_25`**（同一段迁移覆盖该路径，迁移测试即为此设）。既有约束不变：**旧版本 App 无法打开新版本备份**（非本特性缺陷，记录以免误报）。

**成员隔离**：完全沿用既有约定——按 `ActiveRecipientStore` 作用域；`NO_RECIPIENT(0L)` 时**读不发射（等价空）、写抛错**；所有查询带 `careRecipientId`。

---

## 9. 影响面（文件级，实施阶段）

新增：
- `core/database/.../model/CareEventLog.kt`、`.../local/CareEventLogDao.kt`
- `app/.../data/repository/CareEventRepository.kt` + `Impl`（成员作用域 `scoped{}`/`currentOrNull()`）
- `app/.../capability/reminders/`：`ReminderTargetType.CARE_EVENT` + 一次性缺席 deadline 排期函数（独立编号基数）
- `app/.../feature/careevents/`：今日页状态行/记录/补记/编辑/删除 + VM/Contract；设置页排便开关与阈值控件
- 迁移测试 `CareEventMigrationTest`
- `docs/tracked-events-spec.md`（本文件）

修改：
- `DatabaseSchema.kt`（24→25）、`MedLogDatabase.kt`（注册实体 + `MIGRATION_24_25`）、`AppModule.kt`（DI）、`ReminderTarget.kt`（新类型 + 编号空间）
- `MedLogDatabaseMigrationTest`（5 处列表）、`RoomModelContractTest`、`BackupCompatibilityPolicyTest`
- 四套 locale 文案（含 D2/R10 口径）

---

## 10. 任务分解（每步可独立验证）

- [ ] T1 实体 + DAO + 仓储 + v24→v25 迁移（迁移测试：种 v24 有数据 → 零丢失、级联、成员隔离、双时间戳）
- [ ] T2 间隔派生纯函数（最新 `occurredAtMs` → 间隔；补记/编辑/删除后重算；单测含 DST/日界）
- [ ] T3 缺席型提醒（`CARE_EVENT` 目标 + 每日至多一次 + **跨重启去重** + 变更重排；单测锚点、去重、重排、取消）
- [ ] T4 今日页状态行 + 记录/补记/编辑/删除（UI 契约测试；时间轴位置不变的钉桩）
- [ ] T5 设置页排便开关/阈值 + 真机全链路（记录→间隔→超期每日提醒→补记/删除后重算→重启不重复提醒）

---

## 11. 非目标（本期明确不做）

- 除排便外的其它 `kind` 的 UI/提醒（模型预留，本期不实现）
- 用户自定义事件类型（类型表 + 类型管理 UI）
- 「从未记录」的提醒语义（无锚点时的提醒）
- 提醒升级/多次催促/夜间静默
- 事件统计页/趋势图；与 HealthRecord/CareNote 联动；Note/Todo 自动联动
- 新底部 Tab；跨设备同步

---

## 12. 已确认决策 / 仍开放

**已确认（用户 Review 已定）**
1. 首期**只做排便**，模型经 `kind` 常量可扩展。
2. 今日页加**一条状态行 + 快捷记录**，**不加新 Tab**，置于**时间轴之后**以免给药信息下沉（§6）。
3. **无记录不提醒；提醒默认关；3 天只是可改的推荐阈值**，且标注为「照护者自己的关注间隔」而非医学阈值。
4. **只有一张日志表**，**不做类型管理**；阈值/开关进既有成员级偏好层。
5. 补记/编辑/删除 → 状态重算；**跨重启去重 + 过期取消**；间隔按 `occurredAtMs` 现算。

**仍开放（真实待定，不阻塞首期）**
- 每日提醒的**具体时刻**：复用成员既有作息/提醒时刻偏好，还是固定本地时刻？（倾向复用既有偏好）
- 间隔**显示粒度**：<1 天是否显示「约 X 小时」，还是统一「不足 1 天」。
- 后续 `kind` 的次序与各自默认阈值。
- 设置控件的**落点**（既有设置页内联 vs 排便专属小组）。

---

## 13. 验收

1. 记录排便 → 呈现「距上次**记录**排便 X 天」（口径为「记录」而非「发生」）。
2. 补记（发生在过去）→ 按 `occurredAtMs` 正确回算；编辑改时间 → 重算；删除最新一条 → 回退次新并重算；删到无记录 → 无提醒。
3. 排便超阈值（默认 3 天）→ 每**本地日**至多提醒一次，文案「已 X 天未**记录**排便」；记录新一次后旧提醒取消、按新锚点重排。
4. **跨重启去重**：当日已提醒过，杀进程/重启/`BOOT_COMPLETED` 后**当日不再重复提醒**；次日仍超期则再提醒一次。
5. 关闭提醒的 `kind`（如将来加呕吐）→ 只记录、零提醒、零闹钟（首期无 UI，逻辑以单测覆盖）。
6. 两位成员的事件互不可见；`NO_RECIPIENT` 读空/写抛错；删除成员级联清除日志且**不残留提醒**。
7. **今日页时间轴位置不变**（改造前后 dump 对比一致）；顶层导航仍 **5** 个。
8. 备份/恢复：恢复的 v24 备份经 `MIGRATION_24_25` 正常打开、旧数据零丢失。
9. 无 schema 变更之外的回归：用药/照护事项/待办/提醒行为不变（闹钟时刻 diff 与基线一致）。
