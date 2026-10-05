# 药品规格（单粒强度）+ 清理死列 dose — SDD 规格

状态：待实施
范围：把「0.25g × 2粒」这类记法做成可完整存储与显示；顺手清掉死列 `dose`；合计总量进详情统计。
不改动：库存扣减语义、部分服用、依从率口径、照护事项、提醒链路。

## 0. 用户已拍板的决策

1. **规格按"药品属性"存**：一次填写、长期复用（不是每次服用的属性）。
2. **本期清理死列 `dose`**。
3. **合计总量（0.5g）要在详情里显示、并进统计**。

## 1. 证据基线（实施前已核实，file:line）

- `dose` 是死列：`app/src/main` 内**零读取**；编辑器恒写 `dose = doseQuantity`（`AddMedicationViewModel.kt:511,524,526`）。
- 录入只有一个数：「每次剂量」是单值滑块/输入（`AddMedicationBasicSections.kt:222-251`），单位是另一排 chip（`DoseUnitOptions.kt:16`）。
- `doseQuantity` 是**库存与服用数量**（以 `doseUnit` 计）：扣减 `ToggleMedicationDoseUseCase.kt:119`、钳制 `:218`、部分服用 `:247-250`、备货估算 `HomeViewModel.kt:551`、详情 ± 步进、二维码导出——**这套语义正确，本次一律不动**。
- 显示点共 6 处：`MyMedicationsScreen.kt:160`、`MedicationDetailScreen.kt:278`、`TimePeriodGroupCard.kt:269`、`MedicationCard.kt:245`、`HomeHero.kt:427`、`MedicationQrDialog.kt:108`。
- 统计现状：详情页「近30天已服次数」（`MedicationDetailViewModel.kt:27`）；依从率**按状态计数、不按量加权**。
- 药品库**没有规格字段**（`Drug.kt:6-16`），选药只预填 name/category/isTcm/fullPath（`AddMedicationViewModel.kt:369-384`）。
- 通知里的剂量是**未格式化原始 Double**（`MedLogAlarmReceiver.kt:134/164/190`）——顺手修。
- `medication_plan_revisions` 不含 `dose`（只快照 `doseQuantity`+`doseUnit`），本次无需改。

## 2. 模型（Room v20 → v21）

`medications`：
- 新增 `doseStrength: Double?`、`doseStrengthUnit: String?`（可空；**配对约束**：要么都为 null，要么都非空）。
- 删除 `dose`（NOT NULL 死列）→ 按 Room 惯例走**表重建**，其余列与全部数据原样保留，无回填。
- 语义边界：
  - `doseQuantity` + `doseUnit` = **每次服用数量与其单位**（也是库存计量单位），语义不变；
  - `doseStrength` + `doseStrengthUnit` = **每 1 个 `doseUnit` 的规格**（如 0.25 g/粒）；
  - 合计 = `doseStrength × doseQuantity`，**仅当两者同为计量口径时才有意义**。

## 3. 迁移与兼容

- 纯结构变更：+2 可空列、−1 死列；旧数据零丢失、日志不动。
- 导出/二维码：现状 `d` 与 `dq` 都等于 `doseQuantity`（`PlanExportSchema.kt:34,40,128`）。删列后**只写 `dq`**，解码端保持向后兼容（旧码里的 `d` 仍能读、忽略即可），旧 QR 必须仍可解。
- `RoomModelContractTest.kt:17,31` 用位参构造 `Medication(name, dose, doseUnit)` → 需随之调整。
- `SeedDataFormatTest.kt:43-45` 断言 `dose>0 && doseQuantity>0` → 断言改为不依赖 `dose`。

## 4. 显示（6 处统一口径）

- **有规格**：`0.25g × 2粒`（规格 + 次数），详情额外一行 **`合计 0.5g`**。
- **无规格**：保持现状 `2 粒`（向后兼容，不逼老数据补字段）。
- 单位换算口径（实施时修正了本规格初稿的错误例子）：**计数单位（片/粒/滴/袋/支/贴）× 计量规格（mg/g/ml）一律可以相乘**——`0.25mg × 2片` 就是 0.5mg，与 `0.25g × 2粒` 同类。
  只有"两边都是计量单位但属不同族"时才**不做乘法求和**（质量 vs 体积，例如 `0.25 mg` 规格配 `2 ml` 每次量），此时只显示 `规格 × 次数`，`合计` 行不出现。
- 通知剂量字符串改走既有 `formatDose*`（修掉 "2.0 粒"）。

## 5. 统计（决策 3）

- 详情页在「近30天已服次数」旁新增**累计用量**：
  - 有规格：`Σ(TAKEN 日志的 actualDoseQuantity) × doseStrength`，单位 = `doseStrengthUnit`；
  - 无规格：退化为 `Σ actualDoseQuantity` + `doseUnit`（即"累计 N 粒"）。
- **依从率口径不变**（仍按状态计数）——本次不引入按量加权，避免悄悄改变既有含义。
- 不新增独立统计页/图表。

## 6. 录入

- 「每次服用」：数量（现有输入）+ 单位（现有 chip）——不变。
- 新增**可选**「每粒规格」：数值 + 单位（计量类：mg/g/ml）。留空则行为与今天完全一致。
- 校验：规格数值与单位必须成对；数值非空时须 > 0。

## 7. 任务分解

- D1 实体两列 + 删 `dose` + v20→v21 表重建迁移（迁移测试：有数据、空库、列确实消失、新列可空、旧行内容不变）
- D2 编辑器：可选规格录入 + 配对校验 + 预览串（`0.25g × 2粒`）
- D3 显示 6 处统一 + 通知格式修复
- D4 统计：详情页累计用量
- D5 导出/二维码只写 `dq` + 兼容旧码 + 受影响测试调整

## 8. 验收

1. 单测全绿（含新增：规格配对校验、显示格式化、累计用量计算、导出兼容往返）；
2. **core:database 设备端 instrumentation 迁移测试**：v20（含药品/日志/成员）→ v21 后数据零丢失、`dose` 列消失、新列可空；
3. 真机：建一条 `0.25g/粒 × 2粒` 的药 → 列表/首页/详情显示 `0.25g × 2粒`、详情显示 `合计 0.5g`、累计用量正确、二维码导出仍可被旧版本解码；
4. 无规格的老药行为与改造前一致（列表仍显示 `2 粒`）。

## 9. 非目标

- 药品库补规格字段（将来补上后可用于选药自动预填）；
- 按量加权的依从率；
- 库存计量单位重构（库存仍以 `doseUnit` 计数）。
