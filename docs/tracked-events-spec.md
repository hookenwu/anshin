# 照护事件间隔追踪（Care Event）—— SDD 规格（第 3 稿·冻结）

状态：**已冻结（用户确认 4 点 + 提醒边界 5 条已落入）；本轮不改业务代码、不提交**
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

**结论（D0）**：Care Event 是**一等新概念**，不复用 `CareTask`（前向 vs 回溯语义冲突）、不复用 `HealthRecord`（数值型装不下非数值事件）。复用的只有两样：**成员作用域**、**成员级 DataStore 偏好基础设施 + 既有 15 分钟周期 worker 与既有广播接收器**（本特性**不新增任何 AlarmManager 闹钟，也不新增调度器**）。

---

## 3. 关键设计决策

**D1 — 首期只做排便；模型用「一张表 + 一个 kind 常量」，可扩展、零类型管理。**
- 首期唯一 `kind = BOWEL`。事件类型是**代码常量**（`object CareEventKind { const val BOWEL = "BOWEL" }`），**不是用户可管理的数据**——因此**不建类型表、不做类型 CRUD UI**。
- `kind` 以字符串存列（对齐 `HealthRecord.type` 的存法）。将来加排尿/呕吐 ＝ 新增一个常量 + 一组偏好键，**无 schema 变更**。
- **不做**两张表（`care_event_types` + logs）方案——类型管理 UI + 级联/归档整套与「最简落地」冲突；真机验证确实需要时再作为独立迁移补。
- **设置页那一个排便开关只是「提醒偏好」，不是「类型管理」**——它不新建/编辑/删除任何事件类型，也不暴露类型列表（类型恒为代码常量）；读者不得把它误读成类型 CRUD 面（见 D3）。

**D2 — 应对边界 1（发生 vs 记录）：一条日志带两个时间戳。**
- `occurredAtMs`：事件**实际发生**时刻（补记时可为过去）。**间隔一律按它计算**。
- `createdAtMs`：照护者**录入**时刻（默认为 now；补记时＝录入时刻，与发生时间独立）。
- 文案口径固定为「距上次**记录**排便已 X 天」或「上次排便：X 天前」；**禁用**「X 天未排便」这种断言未发生的表述。这是文案层硬规则。

**D3 — 应对「不同事件不同提醒」：配置按 (成员, kind)，不搞统一引擎。**
- 每 (成员, kind) 独立：`enabled`（开关）+ `thresholdDays`（阈值）。首期只暴露排便的一组。
- 阈值/开关存在**既有成员级偏好层**（DataStore，键 `<key>#<recipientId>`），**不建表**——这就是「只有一张日志表、却仍能个性化阈值」的落法。
- **明确：开关＝提醒偏好，不是类型管理。** 它只决定「是否在该 kind 上做超期提醒」及阈值，**不**触及事件类型的增删改，**不**是类型 CRUD 界面；首期唯一的 kind 是代码常量 `BOWEL`（D1）。

**D4 — 应对边界 3（补记/误记/删除后重算）：间隔是派生值，提醒资格每次变更即重算。**
- **间隔不落库**：永远由「最新一条 `occurredAtMs`」读出后现算，无冗余状态可失同步。
- 补记（发生在过去）→ 最新发生时间可能不变或前移，间隔与提醒按**新的最新发生时间**重算。
- 误记（时间错）→ **就地编辑**该条 `occurredAtMs`（置 `updatedAtMs`）后重算。
- 删除 → **物理删除**（对齐 `MedicationLog` 撤销＝删除）。删最新一条 → 锚点回退到次新并重算；删到无记录 → 无锚点、**取消**提醒。
- 所有写入收敛到**单一命令入口**（对齐 `ToggleMedicationDoseUseCase` 的单命令约定），写完即重算提醒资格（不排任何闹钟）。
- **历史变更只重算、绝不同步通知**：补记/编辑/删除**只**更新派生状态与「未来」提醒排期，**绝不**因历史变更当场弹通知（边界 5；见 R11）。

**D5 — 提醒语义：缺席型，默认关，阈值是「照护者自己的关注间隔」。**
- 读作「照护者对自己关注节奏的设置」，**绝不等同于医学/临床阈值**；文案与设置页都必须显式标注「这是你自己的关注间隔，不是医学建议」。
- 3 天只是**推荐默认值**，可改。
- **无任何记录 → 不提醒**（没有锚点，「从未记录」提醒属后续，见 §12）。
- 详见 §5 的精确规则（每日一次 + 跨重启去重 + 过期/重算）。

**D6 — 今日页：状态折入既有「今日计划」标题行 + 一键记录，刻意、经用户批准的例外。**
- 见 §6。**折入既有 `todayPlanHeader` 的同一条 `Row`**（不新增卡片、不新增 `LazyColumn` 条目），**不是**新区块卡片、**不是**新底部 Tab。无用药计划的成员也必须有承载状态的位置（§6 A3）。

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
- **就地编辑 + 物理删除，无版本审计**：编辑直接覆盖 `occurredAtMs`/`note` 并置 `updatedAtMs`，删除即物理删除；**无 `revisionType`、无版本链、无历史/审计表、无修订 UI**——本特性不提供任何历史或修订机制（用户确认 3）。
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
| R2 | **无记录不提醒**：`kind` 下无任何日志 → 无锚点 → 当日不具备提醒资格（worker 判定直接跳过；本设计不排任何闹钟，因此没有待发通知需要取消）。 |
| R3 | **锚点**＝该 (成员, kind) 最新一条的 `occurredAtMs`。 |
| R4 | **超期判据**：`now - anchor >= thresholdDays * 86_400_000`（**绝对时长**，与本地日界/夏令时无关，避免 DST 误差）。 |
| R5 | **每日至多一次（反复型，非一次性）**：一旦超期，在**设备本地日历日**内**最多提醒一次**；超期持续期间，其后**每本地日都会重新具备提醒资格**（在该日有 worker/接收器触发时判定），直到记录新事件或关闭开关。**明确否定「只提醒一次即止」的一次性读法**——但这既不是一次性排期，也不是每日定时闹钟。 |
| R6 | **当日去重（跨重启/重开机/重新开启/改阈值）**：提醒只在「当日尚未提醒过」时发出。发布前**再校验**：`已超期 && 已到目标窗口 && 设备本地 epochDay > care_event_nudged_day#…` 才发；发出后写 `care_event_nudged_day#… = 今日 epochDay`。该标记是**持久化成员级偏好**（非内存）。因此当日已提醒过之后，**进程被杀重启、`BOOT_COMPLETED`、把开关关掉再打开、或改阈值，都不会在同一设备本地日再次提醒**。重启或长时间 Doze 之后同样受此标记约束：标记已置位 → 当日不再发；`MedLogBootReceiver` 只提供**一次新的重新判定机会**，不清除标记、也不强制立即提醒。 |
| R7 | **过期/重算**：任何写入（新增/补记/编辑/删除）后，该 (成员, kind) 的**当日提醒资格按新锚点立即重算**（派生值；本设计不排任何闹钟，故无待发通知需要取消或重排）。编辑或补记使最新时间前移、不再超期 → 当日资格变为「不提醒」；删除最新一条使锚点回退 → 资格按次新锚点重算；删到无记录 → 无锚点、资格为「不提醒」（R2）。 |
| R8 | **删除不残留**：删除成员 → FK CASCADE 删 logs → 无锚点 → 当日资格即为「不提醒」；成员级偏好键随成员删除一并清理（沿用既有约定）。被删除的日志**绝不**留下任何过期提醒。 |
| R9 | **实现（worker 驱动，不新增闹钟）**：每日判定**不**经 `AlarmScheduler`、**不**新增 `scheduleExact` 闹钟（见 R13），判定在既有 `WidgetRefreshWorker` 内完成。通知复用既有 `NotificationHelper`，以 `ReminderTargetType.CARE_EVENT` 与**独立编号空间**（对齐 `CARE_TASK_CODE_BASE = 100_000_000` 的分区做法）分配通知 id/requestCode。语义是**缺席型每日判定**，**不得**套用 `ReminderPlanner.nextOccurrences`。 |
| R10 | **措辞**：通知读「已 X 天未**记录**排便」并附「你关注的间隔」中性说明；**禁止**任何医学判断或因果措辞。 |
| R11 | **历史变更不即时通知**：补记/编辑/删除**只**重算派生状态与**未来**排期（见 R7），**绝不**因历史变更当场发通知；下一次触达只可能落在下一个 09:00 窗口。 |
| R12 | **发送前即时再校验（防陈旧决策）**：真正发通知**之前**重新读取并校验四要素——① 当前活跃成员仍为该 (成员, kind)（`ActiveRecipientStore`）；② 该 kind 的提醒开关仍为开；③ 最新一条日志仍存在且锚点未变；④ 阈值未变。任一项不再成立即**放弃**该次通知（期间切了成员 / 关了开关 / 删了那条日志 / 改了阈值）。R6 的当日去重是第五道闸门。 |
| R13 | **骑既有基础设施、尽力而为（best-effort）**：首期提醒的**目标时刻**是设备本地 09:00，**不**新增作息/例程配置，也**不**新增 AlarmManager 闹钟或调度器。实现骑在**既有 15 分钟周期 `WidgetRefreshWorker`** 与**既有 `MedLogBootReceiver`** 之上。**已核实**：`WidgetRefreshWorker.kt:38-48` 用 `PeriodicWorkRequestBuilder(15, TimeUnit.MINUTES)`、**无任何 Constraints**、`ExistingPeriodicWorkPolicy.KEEP`、**未强制立即执行**；`MedLogBootReceiver.kt:18-24` 监听 `BOOT_COMPLETED/MY_PACKAGE_REPLACED/TIME_CHANGED/TIMEZONE_CHANGED/ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`。**平台不提供任何准时保证**：15 分钟是 WorkManager 对周期任务的**下限**，执行并不精确——系统会批量合并、在 Doze/app-standby 下推迟，可能延迟数小时或直到设备被唤醒；**首次运行也不保证恰好在入队后 15 分钟**。因此判定条件是「**设备本地日**内、设备本地时间已过 09:00、且当日尚未提醒」，**窗口是「今天」而不是「09:00–09:15」**：只要当天还有一次执行机会就补发（例如某日 14:30 才跑、当日标记未置、状态仍超期 → **照常提醒**）。**若当日始终没有任何一次执行（长时间 Doze / 关机跨过整日），该日的提醒即被跳过**：无积压、不补发、不做批量追发；设备恢复后的下一次执行按**新的设备本地日**重新判定（R14）。当日窗口内至多发一次（R6）。 |
| R14 | **日标记＝设备本地 epochDay 且单调**：`care_event_nudged_day#…` 存**设备本地日历日** `epochDay`；发布判据用**严格大于**（`今 > 上次`），以对**时钟回拨 / 时区西移**安全（不回退重发）；跨日 / 跨时区 / 对表后按**新的设备本地日**重新判定。 |

> 说明：本 App 为单设备照护，「成员本地日」与「设备本地日」重合；本规格统一以**设备本地**（系统默认时区）作为日界与 09:00 基准。
> 说明：本设计**不新增任何 AlarmManager 闹钟/调度器**。R2/R7/R8 描述的是**当日提醒资格的重新计算**：写入后立即重算派生资格，而「是否弹通知」的判定只发生在既有 15 分钟周期 worker 与既有 `MedLogBootReceiver` 触发时——因为设计里根本不存在闹钟，所以没有任何东西可被「取消」或「重排」。

---

## 6. 今日页：状态折入「今日计划」标题行（刻意例外）

**形态**：不新增卡片、不新增区块——状态**折入既有 `todayPlanHeader` 的同一条 `Row`**（`HomeScreen.kt:405-441`）：在该行内追加状态文案（如「距上次记录排便 X 天」）与一个 `记录` 文本按钮，一键写入 `occurredAtMs = now` 的日志。无记录时显示引导（「还没有排便记录 · 记录」），**不排提醒**（R2）。

**位置（关键）**：折入**既有「今日计划」标题行**（`home_hero_plan_title`，`HomeScreen.kt:405`），**取代**第 2 稿「置于时间轴之后、折叠线以下」的旧放法。该行位于时间轴**之上**，复用同一条 `Row`、不新增 `LazyColumn` 条目。

**A3 必须经受的真机条件**：小屏、系统字体放大（含 App 内 `AppTextScale` / `UiDensityScale`）、很长的药品名，以及**完全没有用药计划的成员**。**优先级明确**：**用药/照护信息必须始终可见**；状态文案是**次级信息**——放不下时允许**换行、截断，或在窄于某阈值时直接隐藏状态**（例如只留 `记录` 入口），**绝不允许**为了塞下状态而压缩、截断或遮挡药品名与时间。允许标题行因此增高、允许状态被挤掉，**不承诺任何「零位移」**。

**无计划成员的落点（关键）**：既有 `todayPlanHeader` 仅在 `overallTotal > 0` 时渲染（`HomeScreen.kt:404`）。**当 `overallTotal == 0` 但有排便记录或正在追踪时**，必须仍有**一个明确承载状态的地方**：把该标题行的渲染条件放宽为「`overallTotal > 0` **或** 存在排便事件/追踪」，使同一条 `Row` 在无用药计划时也渲染并承载状态（此时行内没有「N 项」计数与照护入口，只显示状态 + `记录`）。**不允许**出现「有排便数据却完全看不到状态」的情况。

**为何可接受（写明这是刻意例外与代价）**：`docs/care-notes.md §0.4/§7` 与 `docs/record-center-spec.md §D5` 的硬约束是「**首页不新增区块，避免安全关键的服药时间轴下沉**」。本件**复用既有标题行的同一条 `Row`、不新增 `LazyColumn` 条目**，正常情况下**不额外增加行高**；但这**不是「零位移」承诺**：字体放大或长文案下标题行可能增高、状态可能换行/截断/隐藏，**取舍是「状态可让位，用药信息不被压缩」**。与「参考知识/内容类」仍不同（那类仍不上首页）。

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
- `app/.../capability/reminders/`：`ReminderTargetType.CARE_EVENT` + **worker 驱动的缺席型每日判定**（含发送前四要素再校验）与**独立通知编号空间**（**不新增闹钟**）
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
- [ ] T2 间隔派生纯函数（最新 `occurredAtMs` → 间隔；补记/编辑/删除后重算；单测含 DST/日界/**时钟前拨与回拨**）
- [ ] T3 缺席型提醒（骑 15 分钟 `WidgetRefreshWorker`、设备本地 09:00 后的**当日**窗口 + 每日至多一次 + **当日去重（重启/重开/改阈值）** + **发送前四要素再校验** + 历史变更不即时通知 + 写入即重算资格；单测锚点、窗口、去重、再校验、重算、跨日/时区/时钟调整）
- [ ] T4 今日页状态**折入 `todayPlanHeader` 同一条 `Row`** + 记录/补记/编辑/删除（UI 契约测试；**不新增 `LazyColumn` 条目；用药信息优先、状态可让位**）
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
2. 今日页状态**折入既有「今日计划」标题行**（同一条 `Row`，`HomeScreen.kt:405`）+ 快捷记录，**不加新 Tab、不新增卡片**、不新增 `LazyColumn` 条目；放不下时状态可换行/截断/隐藏，**用药信息优先**（§6 A3）。
3. **无记录不提醒；提醒默认关；3 天只是可改的推荐阈值**，且标注为「照护者自己的关注间隔」而非医学阈值。
4. **只有一张日志表**，**不做类型管理**；阈值/开关进既有成员级偏好层。
5. 补记/编辑/删除 → 状态重算；**跨重启去重 + 过期取消**；间隔按 `occurredAtMs` 现算。
6. 超期后**每日至多一次**（**否定一次性**）；**历史变更只重算、不即时通知**。
7. **就地编辑、无版本审计**（无历史/修订机制）。
8. 设置页排便开关是**提醒偏好，不是类型管理**。
9. 提醒时刻**固定 09:00 设备本地**，骑既有 15 分钟 `WidgetRefreshWorker` + `MedLogBootReceiver`，不新增调度器。

**仍开放（真实待定，不阻塞首期）**
- 间隔**显示粒度**：<1 天是否显示「约 X 小时」，还是统一「不足 1 天」。
- 后续 `kind` 的次序与各自默认阈值。
- 设置控件的**落点**（既有设置页内联 vs 排便专属小组）。

---

## 13. 验收

1. 记录排便 → 呈现「距上次**记录**排便 X 天」（口径为「记录」而非「发生」）。
2. 补记（发生在过去）→ 按 `occurredAtMs` 正确回算；编辑改时间 → 重算；删除最新一条 → 回退次新并重算；删到无记录 → 无提醒。
3. 排便超阈值（默认 3 天）→ 在**设备本地日**内、设备本地时间已过 09:00 后**至多提醒一次**，文案「已 X 天未**记录**排便」。提醒是**尽力而为（best-effort）**的：平台可能推迟（Doze/app-standby 下可达数小时，或直到设备被唤醒），当天 09:00 后任意一次执行都会补发；**若当天没有任何执行机会，则当日提醒被跳过**（无积压、不补发）。记录新一次后按新锚点重算、不再具备资格。**补记/编辑/删除本身不触发任何即时通知**（R11）。
4. **当日去重**：当日已提醒过，杀进程/重启/`BOOT_COMPLETED`/**关开开关**/**改阈值**后**当日不再重复提醒**；次日仍超期则再提醒一次。
5. 关闭提醒的 `kind`（如将来加呕吐）→ 只记录、零提醒、零闹钟（首期无 UI，逻辑以单测覆盖）。
6. 两位成员的事件互不可见；`NO_RECIPIENT` 读空/写抛错；删除成员级联清除日志且**不残留提醒**。
7. 状态折入既有 `todayPlanHeader` 同一条 `Row`，**不新增 `LazyColumn` 条目**；顶层导航仍 **5** 个。**真机条件（§6 A3）**：小屏、系统/应用字体放大、超长药品名、**无用药计划**四种情况下，**用药/照护信息始终可见**（状态可换行/截断/隐藏，绝不压缩药品内容）；无计划成员仍有可见的状态承载位。
8. 备份/恢复：恢复的 v24 备份经 `MIGRATION_24_25` 正常打开、旧数据零丢失。
9. 无 schema 变更之外的回归：用药/照护事项/待办/提醒行为不变（闹钟时刻 diff 与基线一致）。
10. **时间边界测试**（§5 R13/R14）：① **跨日翻转**——当日已提醒，本地日推进且仍超期 → 次日窗口再提醒一次；② **时区变更**——`TIMEZONE_CHANGED` 后日标记按**新设备本地日**判定，不回退、不重复当日；③ **系统时钟调整**——前拨跨过阈值 → 于**下一个判定机会**（设备本地时间已过 09:00）提醒（不即时）；回拨/西移 → 因 `今 > 上次` 单调判据**不重发**；④ **已过期后才开启开关**——启用**不**即时提醒，仅在**下一个判定机会**（设备本地时间已过 09:00）生效。
