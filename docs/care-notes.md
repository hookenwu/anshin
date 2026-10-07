# 照护笔记（CareNote）— SDD 规格

状态：已定稿（不开发）；模型层面改动需新一轮评审
决策基线：用户已确认方向 + 以下 7 条调整。
关联阅读：`docs/family-mvp.md`、`docs/care-tasks.md`、`docs/todos.md`。

## 0. 决策基线（用户确认）

1. 模型走 **B：独立实体 + 多目标关联表**。
2. 概念为 **CareNote / 照护笔记**，不用 Experience/经验 —— 避免把个人观察或他人经验天然包装成正确医学知识。
3. **归属信息本期必做**，且区分类型：医护交代 / 照护经验 / 个人观察 / 外部资料；UI **持续可见**归属，**不得**把"我的观察"改写成医学因果结论。
4. 呈现**就地为主**：Medication / CareTask 等相关详情页展示关联笔记；完整入口在「更多 → 照护笔记」，支持搜索；**首页不新增区块**。
5. AI **本期不接入**；长期边界**不写成"AI 永远不能参与"**。允许未来用于整理、结构化、关联、检索**已有记录**；**禁止**把未经确认的观察升级为医学事实、生成患者特异性治疗建议、自动改动 Medication/CareTask。
6. Tripwire **暂不做**，本阶段不引入新的医学知识判断体系。
7. 需再评估轻量状态（`ACTIVE` / `SUPERSEDED` / `QUESTIONABLE`），避免"只有 `isArchived`"导致过时笔记仍被当成当前参考。

### 0.2 定稿调整（已确认）

以下 5 条为定稿调整，逐条覆盖 §12 的原待评审问题，均已确认：

1. **不做结构化替代链**：不新增 `supersededByNoteId`，保持 `status` + `supersededText`/`supersededAtMs`；等真实使用证明需要结构化替代链再扩展。
2. **归属不可省**：默认 `attributionType = PERSONAL_OBSERVATION`，`attributionName` 可空；但 **`attributionType` 在任何展示面都必须可见**，即使名称为空也不能出现无归属的裸正文。
3. **删除 `MEMBER` 关联目标**：`careRecipientId` 是"这条笔记属于谁"的唯一事实源；`CareNoteLink` 只表达"具体与什么对象相关"，保留 `MEDICATION` / `CARE_TASK` / `TODO`。没有任何 link 的 CareNote 自然就是成员级通用笔记，不为了排序制造冗余 `MEMBER` 关系。
4. **列表与搜索**：普通列表默认展示 `ACTIVE` + `QUESTIONABLE`，`SUPERSEDED` 折叠/隐藏；但用户主动关键词搜索时，命中的 `SUPERSEDED` 仍应返回，并明确标记「已被更新」，避免造成记录丢失的错觉。
5. **状态与行动职责分离**：`QUESTIONABLE` 只表达"这条信息有待确认"，不承担行动管理职责。若用户需要"去问医生确认"，应创建 CareTodo；本期不做 Note → Todo 联动，只把领域边界写清楚（见 §12）。

## 1. 命名与语义

**照护笔记**＝围绕这位成员积累的、**有归属、有日期、可被引用**的照护相关信息。它不排期、不打卡、不被"完成"，只在需要时被看到。

| | Medication | CareTask | CareTodo | **CareNote** |
|---|---|---|---|---|
| 本质 | 按点执行的处方 | 可重复干预 | 一次性跟进 | **有归属的参考记录** |
| 被"完成" | 会 | 会 | 会 | **不会** |
| 生命周期 | 计划期 | 计划期 | 开关 | **长期沉淀，可能过时或被更新** |
| 核心属性 | 剂量/频次 | 排期 | 截止 | **归属（谁说的）+ 时间 + 状态** |

硬规则：**App 不生成笔记内容、不改写笔记正文、不把笔记转成结论**；笔记正文由用户书写，App 只做存储、归属标注、关联与检索。

## 2. 归属（本期必做）

字段：`attributionType`（枚举）+ `attributionName?`（谁：护士张/王医生/护工/家人/自己/资料名）+ `attributionAtMs?`（何时）+ `attributionText?`（出处细节，如"3 楼护士查房时提到"）。

| 类型 | 含义 | 呈现基调 |
|---|---|---|
| `CLINICIAN` 医护交代 | 医生/护士的交代或医嘱解释 | 可作当前依据，但仍显示"请以医嘱为准" |
| `CAREGIVER_EXPERIENCE` 照护经验 | 护工/家属的经验做法 | 经验性表述 |
| `PERSONAL_OBSERVATION` 个人观察 | 我观察到的现象 | **中性呈现，绝不写成因果结论** |
| `EXTERNAL_MATERIAL` 外部资料 | 书/文章/视频等 | 需标注来源名；不背书其正确性 |

UI 规则（不可协商）：
- 任何呈现位置（就地卡片、列表、详情）**都必须显示 `attributionType`（归属类型）**；即使 `attributionName` 为空，类型也必须可见——**任何展示面都不得出现无归属的裸正文**（名称/时间有则一并显示，无则省略名称，类型恒在）。
- `PERSONAL_OBSERVATION` 一律以"我观察到…"的中性框架呈现；**不得**由 App 渲染成"X 导致 Y"的因果句式，也不提供把观察"升级为结论"的模板或按钮。
- 编辑器可给**中性书写提示**（如"建议写明是谁、什么时候说的"），但这不是任何形式的正确性判定。

## 3. 状态（对第 7 条的再评估）

**结论：采用三态，且状态只能由用户设置——App 不推断、不自动过期。**

| 状态 | 含义 | 呈现 |
|---|---|---|
| `ACTIVE` | 仍适用（默认） | 正常显示；不加标签或极轻标注 |
| `QUESTIONABLE` | 有待确认（例如观察待问医生） | 显式"待确认"标记 + 可选一行原因 |
| `SUPERSEDED` | 已被后续医护意见更新 | 显式"已被更新"标记 + `supersededText?`（新的说法）/`supersededAtMs?`；就地卡片默认折叠到次级位置 |

- **不加 `isArchived`**：`SUPERSEDED`（被更新，仍属历史）已覆盖"过时但仍要留存"；确实要移除的用删除。避免出现两套"关闭"语义（与 `CareTodo` 的决定同源）。
- 三态价值：不新增任何"医学判断体系"（符合第 6 条）——**判定人始终是用户**；`QUESTIONABLE` 只是把这层不确定性**显式写出来**，而不是让 App 去猜。
- **不做结构化替代链**：不引入 `supersededByNoteId` 之类字段；"被谁更新"只用自由文本 `supersededText`/`supersededAtMs` 表达，**仅在真实使用证明需要结构化替代链时**再扩展（见 §0.2 第 1 条）。
- **`QUESTIONABLE` 不承担行动管理职责**：它只表达"这条信息有待确认"，不派生待办、不派生提醒、不改动其他对象；"去问医生确认"属于 CareTodo 的职责（本期不做 Note → Todo 联动，边界见 §12）。
- 列表与搜索的默认可见性规则见 §6（普通列表默认 `ACTIVE` + `QUESTIONABLE`，`SUPERSEDED` 折叠；关键词搜索仍可返回 `SUPERSEDED` 并标记「已被更新」）。

## 4. 领域模型

`care_notes`
| 字段 | 类型 | 约束 |
|---|---|---|
| `id` | Long PK autoGenerate | |
| `careRecipientId` | Long | NOT NULL，FK → `care_recipients` ON DELETE CASCADE |
| `title` | String | NOT NULL |
| `body` | String | NOT NULL（用户原文，App 不改写） |
| `attributionType` | String | NOT NULL，默认 `PERSONAL_OBSERVATION`（最保守的默认） |
| `attributionName` | String? | |
| `attributionAtMs` | Long? | |
| `attributionText` | String? | |
| `status` | String | NOT NULL，默认 `ACTIVE`（`ACTIVE`/`QUESTIONABLE`/`SUPERSEDED`） |
| `supersededText` / `supersededAtMs` | String? / Long? | 仅 `SUPERSEDED` 有意义 |
| `createdAtMs` / `updatedAtMs?` | Long | NOT NULL / 可空 |

索引：`careRecipientId`、`status`。

`care_note_links`
| 字段 | 类型 | 约束 |
|---|---|---|
| `id` | Long PK autoGenerate | |
| `noteId` | Long | NOT NULL，FK → `care_notes` ON DELETE CASCADE |
| `targetType` | String | NOT NULL：`MEDICATION` / `CARE_TASK` / `TODO`（**不含 `MEMBER`**，见下） |
| `targetId` | Long | NOT NULL（**不建外键**，见 §5） |

索引：`(targetType, targetId)`（"这味药/这个动作有哪些笔记"）、`noteId`。

**成员级笔记**：`careRecipientId` 是"这条笔记属于谁"的**唯一事实源**；`care_note_links` 只表达"具体与什么对象相关"。因此**不存在 `MEMBER` 目标类型**——没有任何 link 的 CareNote 自然就是成员级通用笔记，不为了排序制造冗余 `MEMBER` 关系（见 §0.2 第 3 条）。

**既有约定**（已核实）：仓库里多态关联一律**不建外键、不级联、找不到就忽略**（`CareTodo.sourceType/sourceId` 即此约定，`MedLogDatabase.kt:19-34` 中无任何笔记类实体，`DrugRepositoryImpl:21` 是唯一搜索实现）。本特性沿用同一约定。

## 5. 关联的删除与悬挂处理

| 事件 | 行为 |
|---|---|
| 删除笔记 | `care_note_links` 由 FK **级联删除**（笔记侧唯一强制关系） |
| 删除成员 | `care_notes` FK 级联 → 其 links 再级联（两跳，SQLite 需 `PRAGMA foreign_keys=ON`，与现有实体同条件） |
| **删除被关联目标**（药/照护事项/待办） | links **不删除、不级联**；该关联成为**悬挂**：读取时**容忍**（不报错、不删笔记），就地呈现时**忽略该关联**；列表页可提示"关联目标已不存在"并允许一键清除该关联 |
| 不做的事 | **不**做自动清理任务、**不**用任何后台扫描修悬挂（与 `source*` 的既有做法一致，避免引入新的后台写路径） |

## 6. 搜索

- 现状：core 里**没有 FTS/全文检索基建**；唯一搜索是药品库的内存排序搜索。笔记体量（个人级，预计数十到数百条）**不需要 FTS**。
- 方案：Room 查询 + `LIKE`，作用域为当前成员，匹配 `title` / `body` / `attributionName`（结果一律显示归属类型，见 §2）。
- 默认可见性：普通列表默认展示 `ACTIVE` + `QUESTIONABLE`，`SUPERSEDED` 折叠/隐藏；但用户**主动关键词搜索**时，命中的 `SUPERSEDED` 仍应返回，并在结果中明确标记「已被更新」，避免造成记录丢失的错觉（见 §0.2 第 4 条）。
- 排序：先按 `status`（`QUESTIONABLE` > `ACTIVE` > `SUPERSEDED`）再按 `updatedAtMs` 倒序——把"待确认"顶上来，因为那是用户最需要先看见、先确认的（**不表示 CareNote 承担行动**，行动由 CareTodo 负责，见 §12.1）。
- 不引入标签体系（仓库无 tag 基建，`CareTask.category` 是固定枚举）；关键词搜索替代。

## 7. 就地呈现（主力）与入口

- **药品详情页**：底部「相关笔记」卡片（该药为 target 的笔记）。空态**不渲染**。
- **照护事项详情页**：同上（如"翻身"→"空掌心拍背排痰"那条）。
- 卡片内容：标题、正文、**归属行（类型 + 名称/时间）**、状态 chip（`QUESTIONABLE`/`SUPERSEDED`）；`SUPERSEDED` 默认折叠并可展开看被更新说明。
- **完整入口**：首页「更多」菜单新增「照护笔记」（与「待办」「照护事项」并列）→ 列表 + 搜索 + 新建/编辑 + 状态切换。**不加第六个底部 tab**（`NavigationHierarchyTest` 钉死 5 个）。
- **首页不新增区块**（首页已有进度卡、待办块、时间轴、按需、PRN；再挤会让安全关键的服药信息下沉）。
- 编辑器：标题、正文、归属四选一 + 名称/时间/出处、挂接目标（可多选：药/照护事项/待办；**不含"成员"**，成员归属由 `careRecipientId` 决定）、状态（默认 `ACTIVE`）。

## 8. 迁移（v22 → v23）

纯新增两张表 + 索引，**无表重建、不动既有数据**。`VERSION` → 23；`AppModule` 追加 `MIGRATION_22_23`；导出 `23.json`；`RoomModelContractTest` 改 23；**`MedLogDatabaseMigrationTest` 里五处硬编码迁移列表全部要补**；新增 `CareNoteMigrationTest`（照 `CareTodoMigrationTest` 模板：种一个带数据的 v22 库 → 迁移 → 旧数据零丢失 + 新表可写 + 索引存在 + 笔记删除级联 links + 成员删除级联笔记）。备份上界随 `VERSION` 自动放行（有测试钉住）。

## 9. 分阶段

- **N1 数据层**：两表 + DAO + 仓储（成员作用域：`NO_RECIPIENT` 读空/写抛错）+ 迁移 + 迁移/隔离/级联/悬挂读取容错测试。
- **N2 就地呈现**：药品详情、照护事项详情「相关笔记」卡片（含空态不渲染、归属行、状态 chip）。
- **N3 入口与编辑**：「更多 → 照护笔记」列表（搜索 + 状态过滤）+ 新建/编辑 + 挂接多目标 + 状态切换。
- **N4（后续，待定）**：打卡/待办页面就地展开相关笔记；AI 辅助**整理与检索已有记录**（见 §10）。

## 10. AI 边界（本期不接入；写清"边界"而非"永不"）

- **本期**：不接入，无任何 AI 调用（AI 三开关默认关闭，有代码证据）。
- **长期允许**：对**已存在的用户记录**做整理、结构化、关联、检索（例如归类散记、找出与某味药相关的笔记、生成供人复核的草稿）。产物必须显式标注为"**机器整理，未经确认**"，且必须可追溯到原始记录与归属。
- **长期禁止**：把未经确认的观察升级为医学事实；生成患者特异性治疗建议；自动修改 Medication / CareTask（或任何临床对象）；自动改动笔记状态或归属。

## 11. 非目标（本期）

Tripwire 与任何新的医学知识判断体系、AI 生成/建议、对笔记内容做正确性裁定、与药品库或相互作用引擎联动、推送提醒与自动过期、标签体系、把笔记并入现有三类实体、首页新增区块、第六条底部导航。

## 12. 定稿结论与边界

以下四项原待评审问题均已确认（定稿依据见 §0.2）：

1. **替代链**：不做结构化替代链，不引入 `supersededByNoteId`；保持 `status` + `supersededText`/`supersededAtMs`，等真实使用证明需要再扩展。
2. **归属默认**：默认 `attributionType = PERSONAL_OBSERVATION`（最保守）；`attributionName` 可空，编辑器给强引导，但**类型在任何展示面恒可见**（见 §2）。
3. **`MEMBER` 目标**：删除。`careRecipientId` 是成员归属的唯一事实源；`CareNoteLink` 只保留 `MEDICATION` / `CARE_TASK` / `TODO`；无 link 的笔记即成员级通用笔记。
4. **列表默认隐藏 `SUPERSEDED`**：普通列表默认折叠/隐藏；**主动关键词搜索仍返回命中的 `SUPERSEDED` 并标记「已被更新」**。

### 12.1 CareNote ↔ CareTodo 领域边界

- CareNote 是**有归属的参考记录**：不被"完成"、不排期、不派生行动。
- `QUESTIONABLE` **只表达"这条信息有待确认"**，不承担行动管理职责；需要"去问医生确认"时，由用户创建 **CareTodo**。
- **本期不开发 Note → Todo 联动**：不自动创建 CareTodo、不写回 CareNote、不因 Todo 状态变化改动 CareNote（`status` 仍只能由用户设置）。
- 边界写在这里即可，本期无需引入任何双向引用字段。

**本规格自此定稿**：模型层面的任何改动（新增/删除字段、状态、目标类型或表）都需要**新一轮评审**，不得在实现期就地扩展。
