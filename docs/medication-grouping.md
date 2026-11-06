# 我的药品：按时段分组视图

状态：实现中（分支 `feat/med-grouping`，基线 `impl/offset-ui`）
范围：只改「我的药品」列表的**渲染**。无 schema、无新查询、无迁移、无提醒/闹钟行为改动。

## 1. 证据

- `Medication.timePeriod`（`core/database/src/main/kotlin/com/driezy/medlog/data/model/Medication.kt:53`）
  是唯一描述作息的列：历史单值，现在逗号分隔多值。`TimePeriods.parse`
  （`app/src/main/java/com/driezy/medlog/data/model/TimePeriods.kt:19`）已定义归一化：
  丢弃 `EXACT`、忽略未知 key、去重保序、归一化后为空即等价 `exact`。
- 提醒引擎以同一列为准：`toDomainSchedule()`（`.../data/model/MedicationScheduleMapper.kt:12`）
  先判 `isPRN`，再判 `intervalHours > 0`，之后才解析 `timePeriod`。分组若用别的来源，
  就会出现「分组说早餐后、提醒却按别的时间响」。
- 列表行目前只有一处渲染（`.../list/MyMedicationsScreen.kt:207-269`），批量选择是
  `mutableStateListOf<Long>`（:77），批量回调已 `selected.toSet()`（:295）。

## 2. 规格

### 2.1 分类依据（决策 D1）

只看 `timePeriod`，且与 `toDomainSchedule()` **同优先级、同归一化**：

1. `isPRN == true` → 无时段组；
2. `intervalHours > 0` → 无时段组（间隔给药不挂在作息锚点上）；
3. 否则 `TimePeriods.parse(timePeriod)` 的每个时段映射到组；结果为空 → 无时段组。

**不**从 `reminderTimes` 的钟点反推时段：`reminderTimes` 只是 `(时段, 偏移)` 的物化结果
（`MaterializedFor`），22:00 的精确时间药不该被猜成「睡前」，而且用户改偏移不应让药跳组。
`frequencyType`（interval / specific_days）是**日**的重复规则，不决定一天中的时段，
因此仍然按时段分组。

### 2.2 时段 → 组（决策 D2）

| 组（渲染顺序） | 命中的 `TimePeriod` |
| --- | --- |
| 早 | `BEFORE_BREAKFAST` `MORNING` `AFTER_BREAKFAST` |
| 午 | `BEFORE_LUNCH` `AFTER_LUNCH` `AFTERNOON` |
| 晚 | `BEFORE_DINNER` `AFTER_DINNER` `EVENING` |
| 睡前 | `BEDTIME` |
| 无固定时段 | 空集（exact / PRN / 间隔给药 / 全部未知 key） |

**睡前单独成组**，并列在「晚」之后而不是并进「晚」：就寝服药与晚饭后服药在临床上不是
同一件事（镇静、降脂、部分激素要求睡前），并且「睡前」不随晚饭时间漂移；并进「晚」会让
「晚」组的成员取决于一个数据里根本不存在的「晚饭几点」的假设。顺序固定为
早 → 午 → 晚 → 睡前 → 无固定时段，不受排序档影响。

### 2.3 渲染（决策 D3）

- 纯函数 `List<Medication>.groupedByDosePeriod()`，输入是**已过滤、已排序**的列表，
  输出只含非空组，组内保持输入顺序（因此「时间升序」等排序档在组内继续生效）。
- 视图切换用分段控件 `全部 / 按时段`，放在筛选块**最后一行**（列表正上方）。理由：
  现有的 `在服/已停用/批量操作/今日用药核对` 四个 chip 与排序行位置一律不动，
  屏幕保持可辨认；分段控件与 chip 是两种语义（视图模式 vs 过滤条件），不混在一行。
- 组头 = `组名 · N 种`，只做视觉分隔，不是可点实体。
- 只显示非空组；整个列表为空时沿用现有空态卡片（`medication_active_empty` /
  `medication_archived_empty`），分组视图不新增空态。只有一个组时也照常显示组头，
  不为「单组」写第二条渲染路径。

### 2.4 重复显示与状态一致性（决策 D4）

- 多时段药出现在它命中的**每一个**组里，行是同一个 `Medication` 实例（同一
  `state.medications`、同一 `state.statusKinds[med.id]`），不做字段拷贝，所以状态角标、
  库存、偏移片段在各组里必然一致。
- 一行的动作 payload 永远是 `med.id`：点击 = `onOpen(med.id)`，勾选 = `med.id`，
  批量 = 选中 id 集合。不存在「组内副本」这个概念。
- LazyColumn 的行 key 必须带组前缀（`"<group>:<id>"`）：不带前缀时同一个 id 出现两次会
  因重复 key 直接崩溃。这是唯一随分组变化的 key，`全部` 视图仍是 `it.id`。
- 选择状态是 `Set<Long>`（不是 List）：结构上不可能存两个相同 id；`toggle` 是
  `if (id in s) s - id else s + id`。因此同一种药在两个组里勾选状态一致，取消也一致，
  `已选 N 项` 数的是**药品**不是行。
- 切换视图不改选择集、不改 `在服/已停用` 过滤、不改排序、不改批量/核对入口：
  视图模式只是渲染分支，不进纯函数入参，也不落 DataStore（没有新增偏好的需求）。

## 3. 影响面

- 新增：`.../list/MedicationDosePeriodGrouping.kt`（纯函数 + 组枚举 + `toggledSelection`）、
  `app/src/test/.../list/MedicationDosePeriodGroupingTest.kt`、四个 locale 的资源键。
- 改动：`MyMedicationsScreen.kt` 抽出单一 `MedicationRow` 供两种视图复用；选择状态
  `List<Long>` → `Set<Long>`；筛选块末尾加分段控件；两个渲染分支。
- 不动：ViewModel、Repository、DAO、Room schema、`toDomainSchedule()`、提醒调度、
  `今日用药核对` 路由与入口、批量状态用例。

## 4. 任务

- [x] 分组纯函数 + 组枚举（`groupOf(period)`、`dosePeriodGroups()`、`groupedByDosePeriod()`）
- [x] `toggledSelection(Set<Long>, Long)`：选择集的唯一写入点
- [x] 抽出 `MedicationRow` 并在 `全部` / `按时段` 两个分支复用
- [x] 分段控件 + `rememberSaveable` 的视图模式
- [x] 四个 locale 的 8 个字符串
- [x] JVM 测试 + 负向对照
- [x] 全量门槛 + 真机验收

## 5. 测试与验收

JVM（`MedicationDosePeriodGroupingTest`）：

1. 早+午的药同时出现在两个组；2. 只有 `bedtime` 的药只在「睡前」，不进「晚」；
3. PRN / `intervalHours>0` / `exact` / 未知 key / 带空格的 key 各归其组；
4. 空列表 → 无组；5. 单组 → 一个组；6. 组顺序固定且空组不出现；
7. 组内保持输入顺序；8. 跨组选同一个 id 只算一个药；9. 分组不丢药不造药（id 并集等于输入）、
   且行是同一实例（`assertSame`）；10. 已停用过滤在组内仍然生效。

负向对照：把分类换成「直接 `timePeriod.split(",")`，不归一化、不看 PRN/间隔」，
记录哪些用例转红，然后恢复。

真机（先备份数据库）：/data/local/tmp 安装 → 切「按时段」→ 早/午/晚/睡前/无固定时段
各组内容正确 → 多时段药出现在多组且状态一致、切回「全部」只出现一次 → 已停用过滤在组内
生效 → `今日用药核对` 仍可达且行为不变 → 清理自建测试数据。

## 6. 非目标

- 不按时段改提醒时间、不改 `offsetMinutes` 语义；
- 不新增底部 tab、不改首页/详情页；
- 不做「按周几」或「按成员」分组；不把视图模式持久化为偏好；
- 不动冻结的 `docs/medication-time-offset.md` / `medication-daily-reconcile.md`。
