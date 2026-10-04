# 添加药品 / 我的药品 —— 三个问题的 SDD 规格（第 1 轮）

状态：spec 待实施 → 逐条实现、测试、真机验证
范围：只解决下面三条，不做重构、不改数据库 schema、不动 AI/OCR/相互作用。

---

## 问题 1：剂量单位缺 `g`

### 证据

- 单位选项写死在 `app/.../feature/medications/editor/AddMedicationScreen.kt:111-120`：
  `片 / 粒 / ml / mg / 滴 / 袋 / 支 / 贴`，单选，无「自定义」入口。
- 渲染：`AddMedicationBasicSections.kt:266-286`（`doseUnits.forEach { FilterChip(...) }`）。
- 数据层不限制：`Medication.doseUnit` 是自由字符串（`Medication.kt:34`），通知、导出、详情都直接拼字符串
  （`MedLogAlarmReceiver.kt:111`、`PlanExportSchema.kt:35`、`MedicationDetailScreen.kt:278`），无单位白名单校验。

### 规格

- 在 `mg` 之后加入 `g`（毫克与克相邻，符合用药量的常用档位）。
- 把该列表抽成纯函数 `internal fun doseUnitOptions(tablet, capsule, drop, bag, tube, patch): List<String>`，
  以便用 JVM 单测锁住「包含 g」这件事（现在列表写在 composable 里，无法单测）。

### 非目标

- 不顺手把 `mg/ml` 改成 string 资源（已是硬编码拉丁串，改动会牵动四套 locale，另开一轮）。
- 不加自定义单位输入。

### 任务

- [x] `AddMedicationScreen.kt`：新增 `doseUnitOptions(...)`，`doseUnits` 改用它，列表加入 `"g"`。
- [x] `app/src/test/.../editor/DoseUnitOptionsTest.kt`：断言含 `"g"`、含 `ml/mg`、无重复、顺序稳定。

### 验收

JVM 单测通过；真机上添加药品第 1 步能看到并选中 `g`。

---

## 问题 2：我的药品要能按服用时间升/降序排序

### 证据

- 列表顺序来自 `MedicationDao.getAllMedications`（`:21-25`）：`ORDER BY isHighPriority DESC, name`；
  `MyMedicationsViewModel.kt:23-26` 直接映射，`MyMedicationsScreen.kt:47` 只按归档过滤。
- 界面无任何排序控件；且中文 `ORDER BY name` 是 UTF-8 码点序（实测：二甲双胍→布洛芬→氨氯地平→维生素C→阿司匹林），
  对使用者无意义。
- 时间已在模型里有多种形态：`reminderTimes`（逗号列表，exact）、`timePeriod`（作息时段）、
  `intervalHours`（间隔给药）、`isPRN`（按需）。`scheduledLocalTimeForSlot(index)`（`MedicationScheduleMapper.kt:45-52`）
  已是"某个槽位的钟点"的统一出口。

### 规格

- 排序档位三种：`默认`（= 现状：高优先级 → 名称，不改变默认行为）、`时间升序`、`时间降序`。
- 排序键：该药「当天最早一次服药时间」，用新增纯函数
  `Medication.earliestScheduledTime(): LocalTime`：
  - `ExactTimes` → `times.min()`（多时间点取最早，天然兼容问题 3 的多时段展开）
  - `RoutineAnchored` → `resolvedTime`
  - `Interval` / `AsNeeded` → `LocalTime(reminderHour, reminderMinute)` 作为兜底
- 并列时按名称（码点序）稳定排列，保证顺序可复现。
- 排序偏好持久化：DataStore 新键 `medication_sort_order`（`default` / `time_asc` / `time_desc`），
  与现有 UI 偏好（主题、首页样式等）同一套机制，重进 App 仍生效。非法值回落 `default`。
- UI：在 `MyMedicationsScreen` 已有的「在用药/已归档」筛选行下方，新增一行排序 chip
  （`默认排序 / 时间升序 / 时间降序`），横向可滚动；选中态即当前排序。
- 排序逻辑放纯函数 `internal fun List<Medication>.sortedFor(order: MedicationSortOrder)`，
  ViewModel 只做 `combine(repo.getAllMedications(), prefs.sortOrder)`，UI 保持无逻辑。

### 影响面

- `MedicationScheduleMapper.kt`（+最早时间纯函数）
- `data/local/SettingsDataStore.kt` + `data/repository/UserPreferencesRepository.kt`（新键与读写）
- `feature/medications/list/`：`MedicationSortOrder` + 排序纯函数 + ViewModel + Screen
- `app/src/main/res/values{,-en,-ja,-ko}/strings.xml`：三个 chip 文案

### 任务

- [x] `earliestScheduledTime()` + `sortedFor(order)` + `MedicationSortOrder` 枚举。
- [x] DataStore 键与仓库读写（默认 `DEFAULT`，非法值回落）。
- [x] ViewModel：combine 排序偏好；暴露 `onSortOrderChange`。
- [x] Screen：排序 chip 行（含选中态、点击回调）。
- [x] 单测：最早时间推导（exact 多时间 / 时段 / 间隔 / PRN）、升降序、并列按名称、默认档不变序。
- [x] 真机 UI 测试：排序 chip 存在、点击后回调携带正确档位。

### 非目标

- 不做拼音排序、不做用户自定义拖拽。
- 不改「高优先级置顶」这一现状语义（在 `默认` 档保留）。

### 验收

默认档顺序与改造前逐行一致；切到时间升/降序后顺序按最早服药时间单调变化；重启 App 后档位保留。

---

## 问题 3：同一药品要能挂多个用餐时段

### 证据

- 模型单值：`Medication.timePeriod: String`（`Medication.kt:47`）；主提醒 `reminderHour/Minute` 单值。
- 作息时段与精确时间互斥、时段芯片单选：`AddMedicationScheduleSections.kt:208`、`:255-264`。
- 切时段会覆盖时间列表：`AddMedicationViewModel.kt:397-408`（非 EXACT 时 `reminderTimes = listOf(autoTime)`）。
- 但多钟点是既有能力：`reminderTimes` 是逗号列表（`Medication.kt:48`），编辑页有增删
  （`AddMedicationScheduleSections.kt:299-302`），闹钟按 `med.id*100 + timeIndex` 分槽（`AlarmScheduler.kt:101,157`），
  打卡唯一键是 `(medicationId, scheduledTimeMs)`——即"一天多次"这条链路已通。
- 单时段假设散布在：`MedicationScheduleMapper.kt:17-27`、`ScheduleOccurrences.kt:94`、
  `ResyncRemindersUseCase.kt:40-44`、`MedicationDetailScreen.kt:286-287`、`MedicationCard.kt:230-239`、
  `TimePeriodGroupCard.kt:205`、`MedicationQrDialog.kt:108`、`PlanExportSchema.kt:36`、`MedicationPlanRevision.kt:31`。

### 规格（最小可用，不动 schema）

- `timePeriod` 列语义扩展为**逗号分隔的时段 key 列表**，单值（如 `afterBreakfast`）继续合法 → 旧数据零迁移。
- 编辑页时段芯片改为**多选**：选中的每个时段按作息设置换算成一个具体钟点，统一写入 `reminderTimes`（升序去重）；
  与精确时间模式仍然互斥（切到精确模式保留已有时刻，切回时段模式按新选中集合重算）。
- 领域映射（`toDomainSchedule()`）：key 列表长度 == 1 → 维持 `RoutineAnchored`（今天的行为与测试不变）；
  长度 > 1 → 归约为 `ExactTimes(sortedTimes)`，直接复用现有多次提醒/打卡/库存链路。
- 作息变更重算：`ResyncRemindersUseCase` 改为按 `timePeriod` 的 key 列表逐个换算并整体回写
  （N=1 行为不变；N>1 不再"只重算第一个"）。
- 展示处统一 join 多个时段标签：详情页、药品卡、时段分组卡、QR 弹窗；导出/导入（`PlanExportSchema` 的 `tp` 字段）
  本来就是字符串，保持原样透传，导入时按新解析器容错。
- 校验：时段模式至少要选一个时段（编辑页即时提示），否则退回精确时间。

### 任务

- [x] 纯解析/编码：`TimePeriods.parse(raw): List<TimePeriod>`、`encode(list): String`、`isExact(raw)`
      （容错：空串/未知 key 忽略，归一化后为空视为 `exact`；列表里混进的 `exact` 项只丢弃该项，
      不会连带清掉同串里真实的用餐时段）。
- [x] `MedicationScheduleMapper`：多时段展开为 `ExactTimes`（`earliestScheduledTime()` 因此自动正确）。
- [x] `ResyncRemindersUseCase`：按 key 列表整体重算回写。
- [x] 编辑页：芯片多选 + 自动时间提示显示全部时刻 + 保存/回填（`AddMedicationViewModel` 状态由
      `TimePeriod` 改 `Set<TimePeriod>`）。
- [x] 展示处：详情/卡片/分组卡/QR 改为显示多时段标签。
- [x] 单测：解析容错与向后兼容（旧单值、`exact`、垃圾值）、多时段展开为升序多时刻、
      单时段仍走 `RoutineAnchored`、resync 对 N>1 的重算、编辑页回填往返。
- [ ] 真机：同一药品选「早餐后 + 晚餐后」，保存后详情/首页出现两个时段与两个提醒时刻，打卡可分别记。

### 非目标

- 不新增 schema 版本（不加列、不建表）；`timePeriod` 单值语义对旧代码路径保持可解析。
- 不引入"同一时段多个剂量"或按时段分别管理库存（库存仍按药品总量）。

### 验收

旧药（单时段/exact）行为不变；新药可选两个以上用餐时段，提醒与打卡按多个时刻工作；切换作息时间后多个时刻一起重算。

---

## 统一验证口径

1. JVM 单测（`app/src/test`）覆盖三节的纯逻辑；
2. 真机 instrumentation（`app/src/androidTest`）覆盖排序 chip 与时段多选的交互契约；
3. `./gradlew test assembleDebug` + `ktlintCheck` 通过；
4. 装机后逐条走真机路径，用截图/日志取证。
