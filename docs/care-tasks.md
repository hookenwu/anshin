# 照护事项（CareTask）—— SDD 规格

状态：spec 待评审 → 实施
范围：把「非药物干预」（吸氧、翻身、言语训练等）做成一等实体，与用药平级、共用排期/提醒/记录/统计底座。
前置依赖：**阶段 1（家庭成员提醒分区/通知区分成员）先落地**——本规格要改的提醒链路与阶段 1 要改的是同一处，合并设计、一次改到位，避免两次重写。
不改动：medications 表、药品库、相互作用引擎、OCR/AI、Bpx1、剂量与库存语义。

---

## 0. 本次已拍板的决策基线

1. 今日页：药物与照护事项**混排在同一条时间轴**，并可按标签分区/筛选。
2. 完成语义先做两种：**一键打卡**、**需要时长（开始—结束）**；定额（读数 20 遍）本期不做。
3. 提醒：**固定时刻 + 完成后计时混用**；**不做夜间静默窗口**（照常提醒）。
4. 记录粒度：日志只记完成/未完成（时长型另记开始/结束）；过程数据（血氧、氧流量、读数次数、体位）**进「健康」模块统一看**。
5. **不引入照护者署名**（记录不存"谁做的"）。

---

## 1. 领域模型

新增两张表（v19 → v20），字段刻意对齐 Medication / MedicationLog 的形状，但剔除用药专有概念。

### CareTask（定义）

| 字段 | 说明 | 来源对照 |
|---|---|---|
| id / careRecipientId | 主键 / 成员维度（FK CASCADE + 索引） | CareRecipient（阶段 0） |
| title | 事项名称（吸氧、翻身、读数练习） | Medication.name |
| category | 分类标签（呼吸治疗/体位与活动/言语训练/其他） | Medication.category |
| completionMode | `TOGGLE` \| `DURATION` | 新增（决策 2） |
| defaultDurationMinutes | 时长型的默认时长（30 分钟），打卡型为 null | 新增 |
| scheduleKind | `FIXED_TIMES` \| `INTERVAL` \| `AS_NEEDED` | Medication 的 timePeriod/intervalHours/isPRN 三者归一 |
| timePeriods | 作息时段 key 列表（早餐后/睡前…），`TimePeriods` 编码 | 复用 `TimePeriods` |
| reminderTimes | 逗号分隔 HH:mm，FIXED_TIMES 用 | 复用 |
| intervalHours | 完成后计时的小时间隔（翻身 2 小时） | 复用 Medication.intervalHours 语义 |
| frequencyType/Interval/Days | daily / interval / specific_days | 复用 |
| startDate / endDate | 起止 | 复用 |
| notes / isArchived / createdAt | 备注 / 归档 / 创建时间 | 复用 |

**不引入**：dose、doseUnit、stock、refillThreshold、isPRN+maxDailyDose、isTcm/fullPath、药库字段、相互作用输入。

### CareTaskLog（执行记录）

| 字段 | 说明 |
|---|---|
| id / careTaskId | 主键 / FK CASCADE |
| scheduledTimeMs | 计划时间戳；唯一键 `(careTaskId, scheduledTimeMs)`（对齐 medication_logs 的防重方式） |
| status | `DONE` \| `SKIPPED` \| `IN_PROGRESS`（时长型开始后） |
| actualStartMs / actualEndMs | 时长型的起止（打卡型只用 actualEndMs） |
| actualDurationMinutes | 冗余便于统计；由起止算出 |
| notes | 本次备注 |
| revisionType / createdAtMs / updatedAtMs | 对齐 MedicationLog 的撤销语义（撤销 = 物理删除） |

**不含**：谁做的（决策 5）、过程数据（决策 4，走健康模块）。

---

## 2. 排期与提醒（本期最大的改造）

### 2.1 排期：复用领域层，不新建发生器

`CareTask → MedicationSchedule` 的映射放在一个 mapper 里，直接复用既有能力：

- `ScheduleOccurrences` 已支持 daily / 每 N 天 / 指定周天，以及 **Interval 的"完成锚定"展开**（`ScheduleOccurrences.kt:23-29` 按上次完成时间推算下一次）；
- `AlarmScheduler.scheduleAllReminders(medication, lastTakenMs)` 与 `nextOccurrenceForSlot(afterMs = actualTakenTimeMs ?: …)`（`AlarmScheduler.kt:79-84,126-136`）已经把"下一次 = 上次实际完成 + 间隔"实现好了 —— **翻身"距上次 2 小时"直接复用，无新算法**；
- `MAX_REMINDER_SLOTS = 20`（`AlarmScheduler.kt:24`）对两类实体共用。

### 2.2 提醒目标泛化（药物专用 → (type,id)）

现状是药物专用：registry 只存 `REGISTERED_MEDICATION_IDS`（`AlarmScheduler.kt:26`），`MedLogAlarmReceiver` 只认 `EXTRA_MED_ID`（`MedLogAlarmReceiver.kt:67-75`）。

改造为：

- 目标标识 `ReminderTarget(type = MEDICATION | CARE_TASK, id)`，序列化成 `"<recipientId>:med:<id>"` / `"<recipientId>:task:<id>"`——**阶段 1 已把登记项改成 `<recipientId>:<medId>`，本步在其上加 target 段，两处设计合并、一次到位**；
- registry 改为单一的 target key 集合（键名更换即可：`BootReceiver` + WorkManager 的 reconcile 会重建闹钟，无需数据迁移；阶段 1 已实现"无成员前缀的旧格式登记项在整表重排时作废重建"，同一机制继续适用）；
- PendingIntent 编号空间分区，避免撞码：
  - `MEDICATION` 沿用 `id*100 + slotIndex`（+ `EARLY_REMINDER_CODE_OFFSET=50_000`、`FOLLOW_UP_CODE_OFFSET=100_000`）
  - `CARE_TASK` 使用独立基数 `CARE_TASK_CODE_BASE = 100_000_000`（`base + id*100 + slotIndex`，同上偏移）
  - 加单测断言两区间不重叠（要求 medId/taskId 远小于 1e6，现有量级满足）
- 接收器泛化为按 target type 取文案与动作；时长型任务的通知动作为「开始」→「完成」（完成后按间隔推算下一次）。

### 2.3 提醒策略（决策 3）

- `FIXED_TIMES`：沿用多时间点排期；
- `INTERVAL`：完成后计时，顺延天然发生（未完成则按上次完成时间继续推算）；
- 两者可在同一事项上混用（有固定时段 + 需要间隔补做的场景）；
- **不做夜间静默**：23:00–07:00 照常按排期提醒（决策 3 明确）。若将来要加，落点就在本节的排期过滤处，不改模型。

---

## 3. 完成语义（决策 2）

- `TOGGLE`：一键完成 → 写 log `DONE`，`actualEndMs = now`；跳过 → `SKIPPED`；撤销 → 删除 log（与用药一致）。
- `DURATION`：点「开始」→ 写 `IN_PROGRESS` + `actualStartMs`；今日页与详情显示进行中与已用时长；点「完成」→ 收尾写 `actualEndMs`/`actualDurationMinutes`。
- 时长型允许跳过；**不做**自动超时结束（避免替用户编造完成时间）——这一点留作后续可选。
- 定额型（targetCount/读数 20 遍）本期不做；将来加的是 `targetCount: Int?` + 日志计数列，属 v20→v21 的独立迁移，不影响本期结构。

---

## 4. 今日页统一时间轴（决策 1）

- 现状：`HomeHeroPresentation` 的条目是 `MedicationWithStatus` + `MedicationDoseKey(medicationId, timeSlotIndex, scheduledAtMs)`，分组靠 `groupByTime` 在「按时间/按分类」间切换（`HomeScreen.kt:162-168,289-298`）。
- 规格：抽出 `TodayItem`（`targetType/targetId/slotIndex/scheduledAtMs/status/isHandled/标签`）作为时间轴的唯一输入，药物与照护事项各写一个映射器产出 `TodayItem`；**渲染只有一份**，禁止复制两套列表 UI。条目 key = `"<type>:<id>:<slot>:<scheduled>"`。
- 呈现：默认按时间混排；顶部保留「按时间 / 按分类」切换（现状的 `groupByTime`），分类分组时药物与照护事项按各自 category 归并；新增筛选 chips（全部 / 用药 / 照护 / 照护子类）复用现有 FilterChip 行样式。
- 顶部进度（`今日 5/7`）：按全部条目合计；`MedicationAdherenceCard` 的依从率**仍只统计药物**，照护事项的依从率后续单独出卡（本期先在今日页给已完成/总数，不做独立统计页）。

---

## 5. 过程数据与「健康」模块（决策 4）

- 测量值写入既有 `HealthRecord`：`type`（血氧 SpO2 / 氧流量 L/min / 读数次数）、`value`、`secondaryValue`（如需）、`timestamp`、`notes`、`source`；`sourceCacheKey` 用于防重；唯一键已是 `(careRecipientId, sourceCacheKey)`（`HealthRecord.kt:40`）。入口放在照护事项详情页的「记录一次」。
- **已确认（用户决策）**：`HealthRecord.value` 是 `Double`（`HealthRecord.kt:51`），**体位（左/右/平卧）是分类值，装不进数字字段** → 体位归 **CareTaskLog**（它本就是"这次翻身的结果"），健康模块只收数值型（血氧、氧流量、次数），`HealthRecord` 不为分类值加列。
- `HealthRecordSource` 现为 `MANUAL / LOCAL_OCR / CLOUD_OCR / IMPORT`（`HealthRecord.kt:8-13`）：本期用 `MANUAL`，不新增枚举值（避免存量字符串解析分支）。

---

## 6. 数据与迁移

- v19 → v20：新增 `care_tasks`、`care_task_logs` 两表 + 索引 + 唯一键；**不改任何既有列**。
- 迁移测试覆盖：空库、有药有日志的真实库（阶段 0 的模式）、新表成员维度隔离、级联删除、唯一键防重。
- 新装不含任何默认照护事项；不建默认数据。

## 7. 影响面（文件级）

新增：
- `core/database/.../model/CareTask.kt`、`CareTaskLog.kt`
- `core/database/.../local/CareTaskDao.kt`、`CareTaskLogDao.kt`
- `app/.../data/repository/CareTaskRepository.kt` + `Impl`
- `app/.../data/model/CareTaskScheduleMapper.kt`（CareTask → MedicationSchedule）
- `app/.../capability/reminders/ReminderTarget.kt`（目标标识与编号空间）
- `app/.../feature/caretasks/`（列表/详情/编辑器 + VM + Contract）
- `app/.../feature/medications/home/TodayItem.kt`（时间轴抽象 + 两个映射器）
- `docs/care-tasks.md`（本文件）

修改：
- `DatabaseSchema.kt`（VERSION 19→20）、`MedLogDatabase.kt`（注册实体与迁移）
- `AlarmScheduler.kt`（编号空间、registry 换 key 内容）、`MedLogAlarmReceiver.kt` → 泛化为 `ReminderAlarmReceiver.kt`
- `HomeScreen.kt` / `HomeHeroPresentation.kt`（统一时间轴、筛选 chips、进度口径）
- `AppModule.kt`（DI 绑定）、`medication_flow_strings.xml` 等四套 locale（新文案）

## 8. 任务分解（每步可独立验证）

- T1 实体 + DAO + 仓储 + v19→v20 迁移（单测：迁移三路径、成员隔离、级联、唯一键）
- T2 `ReminderTarget` 抽象 + 编号空间（单测：区间不重叠、key 解析往返）
- T3 提醒通道身份泛化（新增 target-type/id extras + 接收器按类型分派 + CARE_TASK 通知分支；**用药路径逐字不变**，门禁 = 装机后与改造前基线做闹钟时刻 diff：`bash ~/t3-gate.sh`）——排期映射归 T4，完成语义归 T5，因此本步不写任何 CareTaskLog
- T4 `CareTaskScheduleMapper`（单测：FIXED/INTERVAL/AS_NEEDED、完成锚定顺延、多时段）
- T5 完成语义 UseCase（打卡/开始/完成/跳过/撤销 + 时长统计；单测 + 事务性）
- T6 照护事项 UI（列表/详情/编辑；UI 契约测试）

### T6b 排期接入（T9 的前置，规格新增；改动集中在重排这条高风险路径上）

- `AlarmScheduler`：新增 `scheduleCareTaskReminders(task, lastDoneMs, handledSlots)`，与用药版同构——
  同一个 `ReminderPlanner`、同一套"下一次 = 上次完成 + 间隔"；requestCode 取
  `ReminderTarget(careRecipientId, CARE_TASK, id).slotRequestCode(i)`；intent 带
  `EXTRA_TARGET_TYPE="task"` / `EXTRA_TARGET_ID` / `EXTRA_RECIPIENT_NAME` / `EXTRA_TIME_INDEX` / `EXTRA_SCHEDULED_MS`。
  **提前预告与漏服再提醒本期只服务用药**（照护事项只排正点提醒），`cancelTargetAlarms(CARE_TASK)` 落地替换 T3 的占位。
- `AndroidReminderReconciler.reconcileAll`：每位成员在用药之后处理其照护事项——
  含归档清单做清理（同用药的残留通知处理），`isArchived` 跳过排期，`AS_NEEDED` 不排闹钟，
  `INTERVAL` 用该事项最后一条 `DONE` 的 `actualEndMs` 作锚点（与用药的 `lastTaken` 同义）。
- 编辑/归档后重排：在照护事项的保存路径上触发重排（对齐用药侧 `ResyncRemindersUseCase` 的用法）。
- 完成后顺延：`CareTaskCompletionUseCase` 的 `complete`/`skip`/`undo` 成功后重排该事项一次
  （T5 刻意留出的缺口；领域能力见 T4 的 `completionInterval()`）。
- **门禁**：改动后必须重跑 `bash ~/t3-gate.sh`（用药闹钟时刻多重集与基线一致）+ 全套单测 +
  `core:database` 设备端 instrumentation；照护事项的闹钟条数与 `taskId*100` 编号空间另做一次核对。

- T7 今日页统一时间轴 + 筛选/分组（UI 契约测试：混排顺序、筛选、进度口径）
- T8 过程数据入口 → HealthRecord（单测：写库与防重）
- T9 真机全链路：新建「吸氧 每天3次每次30分钟」「翻身 每2小时」「读数 固定时段」，验证提醒、完成/时长记录、时间轴混排、健康记录落库

## 9. 非目标（本期明确不做）

- 定额型完成（读数 20 遍计数）
- 夜间静默窗口
- 照护者署名 / 照护者角色
- 照护事项的 QR/计划导出与导入
- 独立的照护依从率统计页、照护小组件
- 复用 medications 表或泛化 RegimenItem（已否决，见前一轮分析）

## 10. 验收

> T9 真机验收进度（2026-10-05，逐条对照）：**已过** ①用药侧零回归（闹钟时刻多重集与改造前基线逐项一致，18 条）；②照护事项真的进入排期（登记表出现 `1:task:1`，编号空间独立于 `med`）；③今日页筛选 chips（全部/用药/照护/呼吸治疗）与"进度按全部条目合计"（首页显示 0/3）；④装机冷启动无崩溃。**过程中发现并修复一处真缺陷**：只选作息时段的事项保存时没把时段换算成钟点，排期落到映射器兜底 08:00（静默排错时间）——已修并推送 `72cbecd`。**尚未在真机验证** ②′翻身（每 2 小时）与读数（固定时段打卡）两条场景、完成/时长/跳过/撤销的实际落库、今日页混排顺序与按分类切换、过程数据入健康模块与防重、两位成员的 UI 层交互（闹钟并存已在登记表层面看到 `1:med:*` 与 `2:med:*` 并存）、通知标题区分成员、药品 UI 的"两用餐时段"一项。待办：临时测试事项 `O2-30min`（成员 1）需删除；`screen_off_timeout` 需从 1800000 恢复为 30000。

1. 用药侧零回归：既有 564 单测全绿，真机上药物提醒/打卡/跳过/撤销行为与改造前一致；
2. 三个真实场景可建、可提醒、可记录：吸氧（3×30 分钟，时长型）、翻身（每 2 小时，完成后计时）、读数练习（固定时段，打卡型）；
3. 今日页混排按时间单调、筛选与分组切换正确、顶部进度按全部条目合计；
4. 过程数据（血氧/氧流量/次数）在「健康」模块可见且可回溯；
5. 两位成员的照护事项互不可见、互不影响，删除成员级联清除。

## 11. 设计修订（2026-10-05，来自真机使用反馈）

1. **AS_NEEDED 事项不再隐形（推翻本文件早先"按需项不进今日页"的决定）**。用户四条真实事项全是按需型，原设计等于该功能对他完全不可见。现改为：按需照护事项在今日页有**独立「按需」区块**（对齐用药侧 PRN 的既有做法），可在那里直接打卡/记录（复用 `CareTaskCompletionUseCase`，不另开日志路径）；**不进时间轴、不计入计划进度**，口径与 PRN 药一致；筛选 chips 只作用于时间轴条目。硬不变式保持不变：无照护事项时时间轴与改造前逐字节一致。
2. **归档药不得进今日计划**（真机反馈）。原以为映射器滤了 PRN 就够，实则更隐蔽：`FuturePlanCalculator` 会跳过"当前已归档"的药，但**该药残留的计划版本快照里仍是 `isArchived=false`**，于是它又被投影回今日计划（连 hero、进度、二维码导出一起污染）。现于 `HomeViewModel` 用单一 `activeMedications = meds.filterNot { it.isArchived }` 栅栏收口，并在今日计划边界 `TodayItem` 处再滤一次。
   - **有意保留归档清单的调用点**：`MyMedicationsViewModel`（我的药品：进行中/已停用视图）、`MedicationAdherence`（历史服用统计，`includeArchived=true`）。
   - **已修（2026-10-09）：`WidgetUtils` 的小组件归档残留**。`WidgetUtils.todayPlan()` 曾把含归档清单交给 `FuturePlanCalculator`，同样被残留版本快照坑到——`NextDoseWidget`／`MedLogWidget` 在归档当天仍会显示已停用药的下一剂，且用户可打卡、撑大 taken/total 分母。现于 `WidgetUtils.kt:26` 加唯一栅栏 `getAllMedications().first().filterNot { it.isArchived }`（两个 Widget 共用该入口，一处收口）；`FuturePlanCalculator.includeArchived` 契约与历史依从率（`MedicationAdherence` 传 `includeArchived=true`）**保持不变**。回归测试见 `WidgetTodayPlanTest`（stale-revision 排除 + 活药不误滤）。归档/恢复/删除均经 `ReconcileRemindersUseCase` → `AndroidReminderReconciler` 立即 `widgetRefresher.refreshAll()`，故修复即时生效。
3. **单位新增 `μg`**：每次剂量与每粒规格两个选择器都加，旧条目原样保留且顺序不变；`doseUnitFamily` 把 `μg/µg/ug` 归入质量族，因此"计数单位 × 规格"仍可求和，且极小数值不再被格式化成 `0`（`formatDosePrecise` 修正）。
