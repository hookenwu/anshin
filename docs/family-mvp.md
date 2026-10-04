# Family MVP — CareRecipient 多成员化（spec / plan / tasks）

状态：阶段 0 实施中。本文档只保留决策与验收口径，不写流水账。

## 1. Spec（做什么 / 为什么 / 验收）

目标：把"一台设备一个人"的 Anshin 演化为同一设备管理多位家庭成员（爸爸/妈妈/…），
以 `CareRecipient` 为一级领域实体，各成员数据完整隔离。

范围与硬约束（用户已确认）：
- 成员档案**仅本地 Room**；`uuid` 稳定不变，为将来同步预留；本阶段不做账号/云同步。
- **新安装不预置"本人"**：成员由"首次添加成员"创建；只有从 v18 迁移的旧库才自动创建
  一个兼容档案承接历史数据。
- dose logs / plan revisions **通过 `medicationId` 继承** recipient，不加冗余 `recipientId`。
- 现有药品库只作为搜索/自动补全数据源，不作为可信医学知识或相互作用判断依据。
- Bpx1 与 Cloud AI 保留、本阶段不改不扩展。
- 严格 0 → 1 → 2；不做照护者视图、AI 扩展、相互作用重构、药品库重建、无关优化。
- 保留 `ToggleMedicationDoseUseCase`、提醒体系与可复用领域逻辑，不做无必要重写。

MVP 验收（阶段 0+1+2 全部完成后）：
1. 同一设备可创建爸爸、妈妈等多个成员；
2. 各自的药物、计划、服药记录、库存、健康记录、症状、作息、提醒完整隔离；
3. 两人不同作息可同时工作；
4. 任一成员的增删改 / Taken / Skipped / Undo / 导入不得影响其他成员。

## 2. Plan（分阶段，每阶段独立可发布）

### 阶段 0 — 数据地基与成员档案（本阶段）
交付：
- 新实体 `CareRecipient`（id PK、uuid 唯一、displayName、createdAtMs、updatedAtMs）+ DAO + repository。
- `medications` / `symptom_logs` / `health_records` 增加 `careRecipientId`（索引 + FK → care_recipients ON DELETE CASCADE）。
- `health_records` 唯一索引 `UNIQUE(sourceCacheKey)` → `UNIQUE(careRecipientId, sourceCacheKey)`（否则第二位成员导入同一份报告会被 `insert IGNORE` 静默丢弃）。
- 迁移 18→19：仅当库内存在历史人员数据（medications / symptom_logs / health_records 任一非空）时创建兼容档案并回填；空库不创建任何成员。
- 数据访问按"当前成员"收口：DAO 增加 recipient 维度，repository 内部按当前成员过滤/写入（ViewModel 调用点尽量不动，保持现有架构）。
- 最小 UI：无成员时进入"添加成员"引导；成员列表可创建/重命名/删除（删除级联）。
- 测试：单测（schema/uuid/scoping 逻辑）+ 迁移 instrumentation 测试（5→19 全链 + 18→19 数据承接），并在真机跑通。
验收：v18 旧数据迁移后落在兼容档案下且不丢；空库不产生成员；两成员各自建药互不可见（数据层可证）。

### 阶段 1 — 多成员读路径与设置隔离
- 顶层成员切换器（当前成员持久化）；列表/详情/历史/健康/日记全部按成员。
- 作息、时区、身高从设备全局 DataStore 迁到 per-recipient；`AlarmScheduler` / `ResyncRemindersUseCase` / `WidgetUtils` / 依从性计算全部按成员取 zone+routine。
- 通知标题带成员名；`alarm_projection_registry` 按成员分区。
验收：两人不同作息/时区并存，闹钟、通知、小组件、热力图互不串。

### 阶段 2 — 多成员写路径与隔离
- QR 导出升 v2 携带成员信息；MERGE 去重键 `(recipientId, 归一化名)`；REPLACE 只作用于当前成员。
- 备份恢复明确"整机还原"语义并加 identityHash 校验，或改为按成员导出/导入。
- 小组件按实例绑定成员（`appWidgetId → recipientId`，ActionParameters 带 recipientId）。

## 3. Tasks（阶段 0 清单）

- [x] T0.1 清理上一轮真机验证数据（阿司匹林 + SKIPPED log）
- [x] T0.2 新增 `CareRecipient` 实体 + `CareRecipientDao`
- [x] T0.3 `Medication` / `SymptomLog` / `HealthRecord` 加 `careRecipientId`（索引、FK、唯一索引调整）
- [x] T0.4 `DatabaseSchema.VERSION = 19` + `MIGRATION_18_19`（条件创建兼容档案 + 回填 + 表重建）
- [x] T0.5 `AppModule` 注册 DAO / 迁移 / provider
- [x] T0.6 当前成员解析（`ActiveRecipientStore`）+ DAO/repository 按成员收口
- [x] T0.7 最小成员 UI：无成员引导创建、成员列表（增/改名/删/切换）
- [x] T0.8 单测：uuid 稳定性、scoping 契约、schema 常量（540 tests 全绿）
- [x] T0.9 instrumentation：18→19 迁移（有数据/空库）+ 两成员隔离/级联删除（真机 OK 13 tests）
- [x] T0.10 真机验证：v18 建药 → 装 v19 → 数据落兼容档案；空库无成员；两成员互不可见
      （实测：真实 v18 库 5 药 / 8 药历 / 7 健康记录升级后 user_version=19、兼容档案「本人」自动创建、
      全部归入且零丢失、既有闹钟照常排定；新装库 0 成员；仓储级两成员互不可见）

## 3.1 Tasks（阶段 1 清单）

- [ ] T1.1 作息 / 时区 / 身高 → per-recipient（含旧全局值的一次性回落，保证既有用户行为不变）
- [ ] T1.2 全部消费方按成员取 zone + routine：`AlarmScheduler`、`ResyncRemindersUseCase`、
      `WidgetUtils`、`ReminderTimeUtils`（编辑器）、依从性/热力图计算
- [ ] T1.3 `alarm_projection_registry` 按成员分区；`reconcile` 只重排受影响成员，
      不再"取消全部 → 只为当前成员重排"（阶段 0 已知限制由此关闭）
- [ ] T1.4 通知身份：提醒标题带成员名；常驻进度通知不再全局单例
- [ ] T1.5 顶层成员切换器（当前成员持久化）+ 各页按成员取数核对
- [ ] T1.6 单测 + instrumentation + 真机：两位成员不同作息/时区并存，闹钟、通知互不串

阶段 1 决策记录：
- 体位 → `CareTaskLog`（照护阶段）；`HealthRecord` 保持数值型，不为分类值加列（用户已确认）。
- **作息 / 时区 / 身高的落点：分键 DataStore（`<legacy>#<recipientId>`），不动 Room schema（仍是 v19）**。
  读取顺序「成员键 → 改造前的全局键 → 默认值」，写入时若还没有成员则沿用全局键；
  因此升级用户零迁移、行为不变，也不需要新迁移测试。代价是这些设置与成员实体不在一张表里，
  删除成员时由 `clearMemberScopedSettings()` 清理。若将来这些值要参与跨设备同步，再迁进 Room。
- **`settingsFlow` 本身改为按当前成员解析**（flatMapLatest 当前成员 + 只在数据类层面覆盖作息/时区/身高），
  于是所有既有消费方（编辑器时段换算、首页/历史时区、依从性、健康 BMI）自动拿到成员级值，
  不需要逐个改调用点；只有"为非当前成员排闹钟"的场景走新的一次性接口
  `routineScheduleFor(id)` / `reminderZoneFor(id)`。
- **闹钟登记项格式改为 `<recipientId>:<medicationId>`**，新增 `cancelAlarmsFor(recipientId)`；
  重排只清理并重建该成员自己的那部分（阶段 0 的"切成员清掉别人闹钟"由此关闭）。
  阶段 0 遗留的裸 id 登记项由 `cancelUnattributedAlarms()` 在每次全量重排时一次性作废（会被同一轮重排重建）。
- **常驻进度通知仍复用单一通知 id**，但标题带成员名并在切成员时整体改写。
  理由：按成员分配 id 会在切换后残留另一位成员的常驻通知（ongoing 不会自动消失），
  单一 id + 成员名在"通知不串档"这个验收点上更稳妥；若将来要并列展示两位成员的进度再改。

## 4. 阶段 0 已知限制（留给阶段 1，不属回归）

- 单成员行为与改造前完全一致；存在两位以上成员时：
  - `AndroidReminderReconciler.reconcileAll()` 会先取消全部已登记闹钟、再只为"当前成员"重排 →
    切换成员后其他成员的闹钟会被清掉。阶段 1 按成员分区 `alarm_projection_registry` 与重排逻辑后修复。
  - 通知文案不区分成员，常驻进度通知仍是全局单例。
  - 三个小组件展示"当前成员"的数据，尚未按实例绑定成员（阶段 2）。
  - 作息/时区/身高仍是设备级设置；阶段 1 迁到 per-recipient。

## 5. 代码事实校验（实施前已核对）

- `MedLogDatabase`：7 实体 → 8 实体，`version = DatabaseSchema.VERSION`，`exportSchema = true`，手写 Migration 注册在 `AppModule`（无 destructive fallback）。
- `medications` 无 FK；`medication_logs` / `medication_plan_revisions` FK→medications CASCADE；`symptom_logs` / `health_records` 无 FK。
- 每 repository 只注入一个 DAO + `TransactionRunner`，是 recipient 过滤的天然切面。
- 真机实测确认：`medication_logs` 有 `UNIQUE(medicationId, scheduledTimeMs)` 幂等键；撤销=删行 + 按 `stockDeducted` 回补库存。
