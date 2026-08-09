# iOS Theme System Advancement Plan（主题系统完善推进计划）

Status: **Active**（P0–P3 已完成 2026-08-08；下一刀 P4 需产品闸门）
Created: 2026-08-08
Supplements: [`IOS_THEME_PACK_DESIGN_SPEC.md`](IOS_THEME_PACK_DESIGN_SPEC.md)（设计契约权威）；不取代 Taste skill 或首页 E 版已落地事实
Evidence base: 2026-08-08 对 `AmberThemePack.swift` / `PlaceholderViews.swift`（`AmberTheme` / `AmberThemeRuntime`）/ `AppearanceSettingsView.swift` / MiniApp `getTheme` 与全仓 `accentAmber` / `AmberThemePageBackground` 使用面的只读审计。**每个 Phase 下笔前必须按 §开工前通用复核 + 该 Phase 专属清单重新核对实时代码；本文件内的路径与数量是起草时点快照，不是永久事实。**

---

## 0. 背景与总原则

### 0.1 现状一句话

Theme Pack 架构已可用：核心槽 + 可选表面槽、3 个角色内置包（点阵·陶土 / 点阵·Pi / Notion·暖白；经典 paper×accent 走自定义）、JSON 导入导出、全站动态 token、首页角色表达完整。完善空间不在「再造一套主题系统」，而在 **跨面一致性、深色角色感、预留槽位真假、外围表面（MiniApp / 系统卡）对齐**。

### 0.2 产品原则（继承规格，不可破）

| 原则 | 含义 |
|------|------|
| 换皮不换排 | 主题不改列表骨架、五入口顺序、导航结构 |
| 少而精 | 内置包少量高质量；不散装旋钮商店 |
| 阅读独立 | 聊天正文字体 / 字号不进主题包 |
| 深浅独立 | 浅/深/跟随系统不由主题包强制写入 |
| 强调色只点缀 | 禁止整屏大色块（沉浸色仍默认隐藏） |
| 安静工作台 | Amber 不是皮肤商城 |

### 0.3 推进原则

1. **先一致性，后扩槽**：用户已选主题却仍看到固定琥珀/无纹理的页面，比缺纸纹更伤信任。
2. **每 Phase 独立可交付**：完成即有可感知价值；不做大爆炸。
3. **下笔前复核**：每个 Phase 开工时跑该节清单，用 `rg` / 测试 / 必要时模拟器截图更新事实；发现与计划矛盾则先改计划或停手，不硬推。
4. **红测试优先**：改契约先写失败测试（`AmberThemePackTests` / `HomeDesignContractTests` / 新增定点类）。
5. **不碰无关 WIP**：当前工作区有编排 / Chat 宽度等未提交改动时，主题改动必须文件范围隔离；冲突文件先说明再动。
6. **视觉验收分层**：代码绿 ≠ 真机观感；全局 token 变更至少抽查 首页 + Chat + 设置 + 小说 四表面。

### 0.4 Phase 依赖顺序

| 序 | 代号 | 主题 | 依赖 | 价值 |
|---|---|---|---|---|
| 1 | **P0** | 跨面一致性收口 | 无 | 立刻消灭「换主题一半生效」 |
| 2 | **P1** | 深色角色配方 + 对比度门禁 | P0（避免在错 accent 上调深色） | 深色下角色包不再塌成同一套 |
| 3 | **P2** | 预留槽位：实现或删除 | P0 | 去掉假能力，或交付纸纹等真能力 |
| 4 | **P3** | MiniApp / 系统外围主题对齐 | P0；深色字段建议等 P1 | Web 小应用与宿主色一致 |
| 5 | **P4** | 资源包 / 沉浸色上架（产品闸门） | P1 对比度门禁 + 明确产品决策 | 仅在决策通过后开工 |

**默认下一刀：P4（Paused）。** 直至产品书面接受资源包/沉浸色闸门。

---

## 开工前通用复核（每个 Phase 必跑）

在写任何生产代码之前，于仓库根执行并记录结果（可贴到该 Phase 的「复核记录」小节）：

```bash
# 1. 工作区与分支
git status --short --branch

# 2. 主题权威源是否仍存在
test -f iosApp/iosApp/AmberThemePack.swift
test -f iosApp/iosApp/AppearanceSettingsView.swift
rg -n "enum AmberTheme|class AmberThemeRuntime|struct AmberThemePack" iosApp/iosApp --glob '*.swift'

# 3. 规格与 builtins 是否漂移（抽查）
rg -n "static let builtins|case paperGrain|enum Paper" iosApp/iosApp/AmberThemePack.swift iosApp/iosApp/PlaceholderViews.swift
rg -n "pi-steel|notion-blue|lineGrid|serifWordmark" docs/IOS_THEME_PACK_DESIGN_SPEC.md

# 4. 确认本 Phase 文件不与无关 WIP 冲突
git diff --name-only
```

**停手条件（任一成立则不进入实现）：**

- 主题权威源已迁出 `PlaceholderViews.swift` / 改名，而计划未更新
- 本 Phase 目标文件与当前无关 WIP 大量重叠且无法隔离
- 规格与代码对「冻结层」的定义已冲突，需先裁决

复核通过后，才允许写该 Phase 的第一个失败测试。

---

## P0. 跨面一致性收口

### P0.0 开工前专属复核

重新跑一遍并更新数字（起草时点：`accentAmber` 约 100+ 引用；`AmberThemePageBackground` 仅 Chat / Board / Account / Appearance）：

```bash
rg -n "accentAmber" iosApp/iosApp --glob '*.swift' | wc -l
rg -n "AmberTheme\.accent\b" iosApp/iosApp --glob '*.swift' | wc -l
rg -n "AmberThemePageBackground" iosApp/iosApp --glob '*.swift'
rg -n "AmberTheme\.background\.ignoresSafeArea" iosApp/iosApp --glob '*.swift' | head -40
```

**分类裁决（下笔前完成，写入本 Phase 复核记录）：**

对每个 `accentAmber` 调用点归入三类之一，禁止无分类盲替：

| 类 | 含义 | 目标 API |
|---|---|---|
| A | 品牌 / 选中 / 主交互强调 | `AmberTheme.accent`（+ 必要时 `accentInk`） |
| B | 语义「警告 / 进行中偏暖」且与用户强调色无关 | 保留固定语义色（可继续叫 `accentAmber` 或改名为 `statusAmber`） |
| C | 测试 / 预览夹具 | 按断言意图决定，不强制跟 runtime |

**appWide 页面清单裁决：** 列出应挂 `AmberThemePageBackground(surface: .app)` 的全屏页（至少：小说列表/会话/工作区、议会主界面、深度阅读入口、设置一级列表若需要纹理）。设置子页默认保持纯色，避免表单噪音——与规格「角色包纹理勿默认脏聊天」一致；`appWide` 只覆盖已 opt-in 的工作页。

### P0.1 目标与成功标准

**目标**：用户选择任意内置包后，主工作路径上看到的强调色与画布纹理与包配方一致；规范文档与 builtins 对齐。

**成功标准：**

1. 类 A 调用点不再钉死 `#D98324`；切换到 `terracotta` / `mistBlue` 后，小说顶栏 tint、关键 CTA、Chat 中同类控件跟随 runtime accent（模拟器或契约可证）。
2. `pi-steel` / `sit-terracotta`（均为 `.shell`）下 Chat **无**点阵/方格（只留纸色）；首页/外观仍绘纹理。`appWide` 仅留给导入包或未来 opt-in。
3. `docs/IOS_THEME_PACK_DESIGN_SPEC.md` 的 builtins / enum 表与代码一致（含 `pi`/`notion`/`lineGrid`/`serifWordmark`/`monospace`/`steelBlue`/`notionBlue`）。
4. `AmberThemePackTests` + 受影响定点测试全绿；首页契约不回退。

### P0.2 设计要点

- **不**在外观设置增加散装「纹理/字标」旋钮（仍属少而精）。
- **不**改列表排版、五入口顺序、聊天正文字体。
- `accentAmber` 若保留，文档注释明确其为 **status** 色，禁止再当品牌 accent 用。
- PageBackground 替换时保持 `surface:` 语义：`home` / `shell` / `app`，勿全部标成 `.app`。

### P0.3 建议任务切片

1. 写分类清单（表或 `rg` 注释 PR 描述）→ 类 A 的失败测试（例：apply terracotta 后某 Novel tint 解析 hex == runtime.accentHex）。
2. 批量替换类 A；类 B 重命名或加注释隔离。
3. 为已裁决工作页接入 `AmberThemePageBackground`；补 `showsCanvasTexture` 契约测试。
4. 回写 `IOS_THEME_PACK_DESIGN_SPEC.md`；修正重复「§12」编号。
5. 跑测试 + 模拟器抽查：暖灰琥珀 / 点阵陶土 / 点阵 Pi / Notion 暖白 × 浅色。

### P0.4 主要文件（复核后以实时为准）

- `iosApp/iosApp/PlaceholderViews.swift`（`AmberTheme.accentAmber` 语义注释）
- `iosApp/iosApp/NovelCreation/**`、`CouncilChatRuntimeView.swift`、Chat 相关视图中类 A 站点
- 小说/议会等全屏根背景
- `iosApp/iosAppTests/AmberThemePackTests.swift`（及必要新测试）
- `docs/IOS_THEME_PACK_DESIGN_SPEC.md`

### P0.5 明确不做

- 深色分 paper 配方（→ P1）
- `paperGrain` 实现、zip 资源包、沉浸色上架（→ P2/P4）
- 抽取 `PlaceholderViews` 大文件拆分（可另开工程债，不阻塞 P0）

### P0.6 复核记录（开工时填写）

- 日期：2026-08-08
- `accentAmber` 实时数量 / 类 A 数量：约 106 引用；多数为 Class B（警告/运行中/attention）。本 Phase 只改 Class A：`MiniAppSettingsView` 三处 toggle tint、`ProviderDetailView`「手动添加」；并文档化 `statusAmberHex` / `statusAmber`。
- PageBackground 已覆盖页：NovelSession / NovelProjectList / NovelProjectWorkspace / Council / CouncilChatRuntime / WebMount（列表+站点）/ MiniAppList / MiniAppRunner（加上原有 Chat/Board/Account/Appearance）。
- 与 WIP 冲突文件：编排 WIP 引用未入工程的 `IOSSteerQueueStore`；本轮用 `xcodegen generate` 纳入未跟踪文件后可编译。未改 Chat 编排文件。
- 裁决备注：小说 ProgressView / 同步横幅 / degraded 等保持 `accentAmber`（与 `IOSNovelCreationWiringTests` 语义一致）。设置表单页仍用纯色 background，不进 appWide 白名单。
- 验证：`AmberThemePackTests` **31/31** 全绿（含 3 个 P0 新契约）。
- Review 收口（2026-08-08）：去掉 workspace `content` 全屏纸色盖底；章节阅读器挂 PageBackground；WebMount「需要登录」→ `accent`；MiniApp WebView 下垫 `AmberTheme.background`。刻意未改：toolbar 纯色条、表单 sheet 无纹理、列表 gutter、置顶/主分支语义 amber。契约测试已加 workspace 盖底与 WebMount tint 断言；全量 xcodebuild 可能被编排 WIP（Mailbox Sendable）打断，源码契约脚本已 PASS。

---

## P1. 深色角色配方 + 对比度门禁

### P1.0 开工前专属复核

确认 P0 已合并或本分支已含 P0 行为；并核对：

```bash
rg -n "var darkPalette|static let darkPalette" iosApp/iosApp/PlaceholderViews.swift
rg -n "case \.paper.*darkPalette|pi, \.notion" iosApp/iosApp/PlaceholderViews.swift
rg -n "validate\(|invalidHex|immersivePaper" iosApp/iosApp/AmberThemePack.swift
```

确认当前仍是「非沉浸 paper 共用一套 `darkPalette`」；若已分配方，改写本 Phase 为「补洞」而非「新建」。

### P1.1 目标与成功标准

**目标**：主要非沉浸 paper 在深色下仍可区分气质；主题导入的 accent/ink 具备最低对比度门槛。

**成功标准：**

1. 至少为 `paper` / `neutral` / `white` / `pi` / `notion` 五套提供独立或分组深色 palette（允许 `neutral`≈默认工作台、`pi` 偏稿纸墨、`notion` 偏冷灰——具体 hex 以设计验收表为准，计划不锁死色值）。
2. 深色下 `background ≠ surface ≠ surface2` 仍成立（契约测试）。
3. 导入 JSON 时：accent 相对其 ink 未达约定对比度则拒绝并给出可读错误（阈值写入测试；初值 **3.0:1** large-text AA 量级，保住既有 steelBlue/mistBlue 配对；JSON v1 不含 palette，故不做 fg/bg 导入门禁）。
4. 外观设置主题卡预览仍用 **light 配方**（现有行为保留，避免深色模式预览割裂）。
5. 模拟器：系统深色 × 至少 `sit-terracotta` / `pi-steel` / `notion-blue` 三包 + 一组自定义 paper×accent 抽查可读。

### P1.2 设计要点

- 主题包 **仍不强制** 浅深模式；只提供「该 paper 在 dark appearance 下的颜色」。
- 角色包可不单独增加 dark 槽位进 JSON v1；先走 `Paper.darkPalette` 工程表。若未来要导出深色，再升 `AmberThemePackDocument.version`（另开任务）。
- 沉浸色仍默认 `immersivePolicy: hidden`，但对比度工具可复用于未来 P4。

### P1.3 建议任务切片

1. 设计表：五 paper × dark 9 token（可先工程草案 → 设计确认）。
2. 失败测试：`Paper.pi.darkPalette.background != Paper.neutral.darkPalette.background`（或约定的可区分断言）+ surface 三级可辨。
3. 实现 darkPalette 分表；导入对比度校验 + 错误文案。
4. 深色模拟器抽查；必要时调 token。
5. 回写规格 §4「深色」段落。

### P1.4 明确不做

- 为每个 Theme Pack 单独导出 dark 整包（除非产品要求）
- 上架沉浸色
- Widget / Watch 着色（→ P3/P4）

### P1.5 复核记录（开工时填写）

- 日期：2026-08-08（收口复核同日）
- P0 是否已在基线：是（本分支工作区已含 PageBackground / statusAmber 分轨）
- 现有 dark 是否仍单例：**否** — 五非沉浸 paper 已分表；`neutral` 仍锚定 E 版 `AmberTheme.darkPalette`。
- 设计色表状态：工程草案已落地（neutral=E 版原值；paper / white / pi / notion 分表）。导入对比度阈值 **3.0:1**（仅 accent↔ink）；`AppearanceSettingsView` 已接 `insufficientContrast` 文案。
- 实现：`AmberTheme.paperDark|whiteDark|piDark|notionDark` + `AmberColorContrast` + validate gate；契约测试已加。全量 xcodebuild 仍可能被编排 WIP 打断。
- Review 收口（精准）：①MiniApp `getTheme` 直接读 palette/accent hex，去掉中性 fallback；②深色首页 chrome 对非 neutral paper 只对齐 `hoverCard`/`avatarIdle*`/`section` 到 `paper.darkPalette`（玻璃/投影几何仍用 `homeDark`）；③计划成功标准 #3 与实现对齐（不做 JSON fg/bg 门禁）。刻意未做：toolbar appWide 纹理、网格 ink 分 paper、muted2 重调、导入路径外的对比度兜底。

---

## P2. 预留槽位：实现或删除

### P2.0 开工前专属复核

对每个预留槽跑「真接线 / 假接线」审计：

```bash
rg -n "paperGrain|settingsChrome|launchBrand|assetMode|immersivePolicy|emptyArt|glassChrome" iosApp/iosApp --glob '*.swift'
```

对每个槽填写：

| 槽 | 持久化？ | UI 可感知？ | 有非空绘制/行为？ | 本 Phase 决策 |
|---|---|---|---|---|
| paperGrain | | | | implement / remove / keep-hidden |
| settingsChrome | | | | |
| launchBrand | | | | |
| assetMode | | | | |
| immersivePolicy | | | | 默认 keep-hidden → P4 |

**产品默认建议（可在复核时推翻）：**

- `paperGrain`：**实现**最小 Canvas 噪点（低对比），或从 `CaseIterable`/导入合法值中移除并文档标明未支持
- `settingsChrome`：要么让 ≥1 角色包打开并扩大 `AmberChromeFont.settings` 覆盖面，要么删除槽位
- `launchBrand`：账户页双品牌风险高 → 保持 `none` 或删除
- `assetMode`：P4 前保持 `builtinOnly` 且文档写明「非用户功能」

### P2.1 目标与成功标准

**目标**：用户与导入格式看不到「选了没效果」的槽位。

**成功标准：**

1. 每一个仍暴露在 enum / 导入校验中的 `canvasStyle` 都有可见绘制（或导入拒绝）。
2. 规格 §3 / §11 与代码 enum 一致；「预留」只出现在明确标为 Future 的段落。
3. 若实现 `paperGrain`：浅/深 α 有契约或截图像素抽查；不脏会话标题。
4. 既有 builtins 行为不回退（`AmberThemePackTests` 全绿）。

### P2.2 明确不做

- zip 自定义图标包（→ P4）
- 外观页增加散装风格旋钮

### P2.3 复核记录（开工时填写）

- 日期：2026-08-08
- 各槽决策表：

| 槽 | 持久化？ | UI 可感知？ | 有非空绘制/行为？ | 本 Phase 决策 |
|---|---|---|---|---|
| paperGrain | 是（canvasStyle） | 导入可设 | **已实现** `AmberPaperGrainOverlay` | **implement** |
| settingsChrome | 是 | 外观 section/标题 | 门控字体；**pi-steel = true** | **implement**（开 1 包 + 扩大 Appearance 覆盖） |
| launchBrand | 是 | AccountView 接线 | `matchBrand` 可绘；内置包保持 none | **keep**（包默认 none；非假接线） |
| assetMode | 是 | 无用户旋钮 | 仅 `builtinOnly` | **keep**（文档标明非用户功能 / Future zip） |
| immersivePolicy | 是 | picker 隐藏 | 仅 `hidden` | **keep-hidden → P4** |

- 是否改 document version：否（v1 已含这些字段）
- 验证：`AmberThemePackTests` 全绿（含 P2 导入集成：paperGrain / settingsChrome / matchBrand / 非法 assetMode）。Review 收口：Appearance header/disclosure 改回 Text Style（仍走 settingsChrome design）；空壳 gate 测改为 `settingsPackDesign` 断言。跨 Phase 复核补刀：`miniPreview` 背景纹理强制 `.environment(\.colorScheme, .light)`，避免深色 Appearance 下 light 配方卡预览丢失点阵/grain。

---

## P3. MiniApp 与外围主题对齐

### P3.0 开工前专属复核

```bash
rg -n "struct IOSMiniAppThemePayload|miniAppThemePayload|host.getTheme" iosApp/iosApp --glob '*.swift'
rg -n "AmberTheme|UIColor|Color\(" iosApp/*Widget* iosApp/**/*Activity* 2>/dev/null | head -40
```

确认 payload 字段与 Android / 文档承诺是否已有扩展；Live Activity / Widget 是否已有独立色板。

### P3.1 目标与成功标准

**目标**：MiniApp `getTheme` 足够驱动与宿主同气质的浅表面；系统外围至少不与默认琥珀永久绑死（在技术可行范围内）。

**成功标准：**

1. `IOSMiniAppThemePayload`（或后继）至少包含：`dark`、`background`、`surface`、`surface2`、`foreground`、`muted`、`primary`、`primaryInk`（命名可调整，但语义齐全）。
2. 样例 MiniApp / bridge 测试断言字段随 runtime paper/accent 变化。
3. Runner 错误页 / 透明 WebView 底与宿主 background 一致（现有行为不回退）。
4. Widget / Live Activity：若扩展目标存在共享 App Group 可读主题快照，则强调色跟随；若系统限制无法动态，则文档标明「静态回退色」并停止假装已对齐。

### P3.2 设计要点

- 不把点阵纹理塞进 WebView（成本高、易脏）；颜色 token 足够。
- 主题变更时是否热更新 MiniApp：优先 `getTheme` 重读 + 可选事件；避免强杀 WebView。

### P3.3 明确不做

- 让 MiniApp 换首页字标 / 像素图标
- 完整皮肤商店

### P3.4 复核记录（开工时填写）

- 日期：2026-08-08
- 当前 payload 字段（开工前）：`dark` / `background` / `foreground` / `primary`（与 Android `MiniAppTheme` 同窄）
- 落地后字段：`dark` / `background` / `surface` / `surface2` / `foreground` / `muted` / `primary` / `primaryInk`（`IOSMiniAppThemeBridge.payload`；样例 HTML boot 时 `applyTheme`）
- Widget/LA 技术约束结论：**无 App Group 主题快照**；`ActivityWidget` 使用扩展内静态 `Color.amberAccent`（约暖橙），**不跟随**宿主 Theme Pack。文档标明静态回退，不假装已对齐。不在本 Phase 引入 App Group 同步（过度设计）。
- Android：仍为四字段；本 Phase 只扩 iOS bridge（跨端对齐非本刀范围）。
- 验证：`IOSMiniAppThemeBridgeTests` + `AmberThemePackTests` 相关断言。
- Review 收口（精准）：①加载前 `injectHostThemeCSS` 消 FOUC；②theme 变更不进 WebView `.id`，`updateUIView` 刷新 provider + `applyThemeJavaScript`；③补 `theme` 别名 / grant deny / 注入契约测；④生成 prompt 标明 iOS 8 字段 vs Android 4 字段。刻意未做：App Group、Android payload 扩容、themeEnabled 设置旋钮。

---

## P4. 资源包与沉浸色（产品闸门）

**默认状态：Paused until product decision。**

### P4.0 闸门条件（全部满足才开工）

1. 产品书面确认：需要用户可安装的主题资源包，和/或上架沉浸色画布。
2. P1 对比度门禁已在生产路径生效。
3. 安全：zip 仅允许 monochrome 矢量/模板图、大小上限、无脚本；路径不逃逸沙盒。
4. 无障碍：沉浸色通过抽检 AA；提供一键回到非沉浸包。

### P4.1 若开工的目标草案

- `assetMode` 扩展 + 导入格式 v2（可含资源清单 hash，仍可不嵌入巨大二进制）
- 沉浸色从 `immersivePolicy: hidden` 改为可选上架子集
- 完整真机对比度与「强调色不铺满」验收

### P4.2 明确不做（即使闸门打开）

- 改会话列表信息架构
- 主题包控制聊天正文字体或强制深色

---

## 验证门禁（跨 Phase）

### 自动化（按触及面裁剪）

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AmberThemePackTests \
  -only-testing:iosAppTests/HomeDesignContractTests \
  test
```

触及小说强调色时追加相关 `IOSNovelCreationWiringTests` / 定点类。  
触及 MiniApp 时追加 `IOSMiniAppBridgeRuntimeTests`（类名以实时工程为准）。

### 视觉抽查矩阵（模拟器最低集）

| 包 | 浅色 | 深色（P1+） |
|---|---|---|
| 自定义（如暖灰×琥珀） | 首页 + Chat + 设置 | 同左 |
| sit-terracotta | 首页有点阵；Chat 无点阵 | 同左 |
| pi-steel | Chat/已 opt-in 工作页有线格 | 同左 |
| notion-blue | 激活行中性晕、蓝仅点缀 | 同左 |

真机观感与 120Hz 手感：能装机则装；不能则在 `PROJECT_STATE.md` 标明「主题 Phase X 真机未验」。

---

## 文档与状态义务

| 时机 | 动作 |
|---|---|
| 本计划入库 | `docs/README.md` 增加入口；Status=Proposed |
| 某 Phase 开工 | 填该 Phase「复核记录」；Status → Active；可在 `PROJECT_STATE.md` 记下一刀 |
| 某 Phase 完成 | 勾成功标准；更新规格与 `PROJECT_STATE` 验证段；未完成项不得标完成 |
| 全部默认 Phase（P0–P3）完成或砍掉 | Status → Completed / Superseded，并写明取代关系 |

---

## 风险与非目标汇总

| 风险 | 缓解 |
|---|---|
| 盲替 `accentAmber` 毁掉语义警告色 | P0 强制分类表 |
| appWide 纹理弄脏设置表单 | 白名单 opt-in 页面 |
| 深色分配方工作量大 | P1 允许分组共享，不要求五套完全无关 |
| 与编排 WIP 文件冲突 | 通用复核停手条件 |
| 做成皮肤商店 | 原则 0.2；P4 默认暂停 |

**本计划非目标：** Android 主题对齐、Web 营销站、Chat 正文字体进包、重做首页信息架构。

---

## 建议执行方式

1. 用户确认本计划 Status 可改为 **Active**，并确认默认从 **P0** 开工。  
2. 执行代理：先跑「通用复核 + P0.0」，把复核记录写回本文件，再按 P0.3 切片 TDD。  
3. P0 合并并验证后，再开 P1；不要并行改 dark palette 与 accent 盲替。

---

*与实现冲突时以实时代码与测试为准，并回写本计划与 `IOS_THEME_PACK_DESIGN_SPEC.md`。*
