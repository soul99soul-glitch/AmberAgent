# AmberAgent iOS · Theme Pack 设计规范

> 版本：2026-08（对应 iOS Phase 0–3）
> 目标：定义「可换层 / 不可换排版」的主题配方，让设计、设计 AI、工程用同一套语言交付主题。

---

## 1. 产品原则

Amber 是 **安静、可工作的 AI agent 工作台**，不是皮肤商店。

| 原则 | 含义 |
|------|------|
| **换皮不换排** | 主题只改「画什么」，不改「怎么排」 |
| **首页是角色舞台** | 角色感（点阵、品牌字、像素入口）主要在 **首页** 表达 |
| **全站吃颜色** | 画布色 + 强调色通过 token 渗透全 app |
| **阅读独立** | 聊天正文字体 / 字号归用户「显示与字体」，**不进主题包** |
| **深浅独立** | 浅色 / 深色 / 跟随系统 **不由主题包强制写入** |
| **少而精** | 内置包少量高质量；新风格用「角色包」整套上，不散装旋钮 |

一句话：**Theme Pack = 一套命名配方，一键套用；列表骨架永远不动。**

---

## 2. 分层契约（最重要）

### 2.1 可换层（主题负责）

| 层 | 英文槽位 | 改什么 | 不改什么 |
|----|----------|--------|----------|
| **画布色** | `paper` | 暖纸 / 暖灰 / 中性白等底色 token | 页面信息架构 |
| **画布纹理** | `canvasStyle` | 纯色 / 点阵 / 方格 / 纸纹 | 列表行结构 |
| **强调色** | `accent` | 按钮点缀、选中、FAB 图标 tint、active 条 | 大面积色块刷屏 |
| **品牌字** | `brandMark` | 首页顶栏 `Amber` 字标样式 | 右侧搜索 / 设置 / 头像布局 |
| **快捷入口图标** | `shortcutIconStyle` | **仅 5 个**首页快捷入口的 glyph 皮肤 | 入口数量、顺序、HStack 布局 |
| **外壳字体** | `chromeTypeface` | 品牌 /「会话」/ 快捷标题 / 搜索文案 /「新对话」 | 聊天气泡正文、设置表单默认体 |

### 2.2 冻结层（主题禁止动）

| 冻结项 | 说明 |
|--------|------|
| **会话列表排版** | List 顺序：顶栏 → 控制卡（Continue + 五入口）→「会话」→ 行 → 底部留白 |
| **会话行结构** | 左 icon · 标题 · 预览 · 时间等既有结构；swipe 语义 |
| **五入口语义** | 深度阅读 / 小说创作 / 模型议会 / 小应用 / WebMount，顺序固定 |
| **顶栏结构** | 左品牌字 + 右：搜索胶囊 · 设置 · 头像（HStack） |
| **聊天正文字体** | `IOSChatFont` + `fontScale`，用户偏好 |
| **会话列表图标体系** | 标题关键词 / LLM 图标；与首页快捷图标皮肤 **解耦** |

### 2.3 范围边界（当前实现）

| 能力 | 范围 |
|------|------|
| 画布纹理（点阵等） | 由 `canvasScope` 控制：`homeOnly` 仅首页；`shell` = 首页 + 外观/账户；`appWide` = 已 opt-in 的工作页（聊天/看板/小说/议会/WebMount/小应用）。**点阵 · 陶土 / 点阵 · Pi 均用 `shell`**，聊天只保留纸色、不铺方格 |
| 品牌字 / 像素快捷 / chrome 字体 | **首页** 外壳为主 |
| paper + accent 颜色 token | **全 app** `AmberTheme.*`；状态色 `accentAmber`/`statusAmber`（`#D98324`）固定，**不是**主题强调色 |
| 气泡圆角 | `bubbleChrome` → `AmberTheme.radiusXLarge`（含用户气泡） |
| 玻璃垫底 | `glassChrome` **仅首页** Liquid Glass 控件 |
| 外观设置页 | 主题卡片 + 自定义；**不**强制浅深 |

> 角色包默认把纹理留在 home/shell，勿默认 `appWide` 把点阵塞进聊天玻璃底下。

---

## 3. Theme Pack 配方结构

每个主题包 = 一份 **整包匹配** 的配方（改任一槽 → 变为「自定义」）。

```text
AmberThemePack
├── id                 // 稳定英文 id，如 sit-terracotta
├── displayName        // 展示名，如「点阵 · 陶土」
├── paper              // 画布色系
├── accent             // 强调色
├── canvasStyle        // 纹理
├── brandMark          // 品牌字
├── shortcutIconStyle  // 快捷图标皮肤
└── chromeTypeface     // 外壳字体
```

### 3.1 槽位枚举（工程权威值）

**`paper`（非沉浸，可上架）**

| 值 | 展示 | 气质 |
|----|------|------|
| `paper` | 暖纸 | 偏暖纸本，适合角色包 |
| `neutral` | 暖灰 | E 版默认工作台 |
| `white` | 中性白 | 冷静、偏工具 |
| `pi` | 奶油稿纸 | Pi 角色包专用 |
| `notion` | 暖白 | Notion 式工作台 |

（沉浸色画布现已接线但 **不对用户开放**，新主题勿依赖。）

**`accent`（强调色 + 上墨色 ink）**

| 值 | 约 hex | 上墨 ink | 备注 |
|----|--------|----------|------|
| `amberGold` | `#B9863A` | 深墨 `#231602` | 默认琥珀 |
| `terracotta` | `#B8623A` | 白 | 陶土 |
| `sage` | `#5E9C6E` | 深墨 | 偏亮，必须深墨 |
| `mistBlue` | `#4F86D6` | 白 | |
| `steelBlue` | `#6B8CAD` | 奶油墨 `#FAF9F7` | Pi |
| `notionBlue` | `#0075DE` | 白 | Notion |
| `wisteria` | `#9277C4` | 白 | |
| `rose` | `#C2607A` | 白 | |
| `ink` | `#222226` | 白 | |

规则：**高亮度强调色必须配深 ink**；中低亮度可配白 ink。禁止一律白字。

**`canvasStyle`**

| 值 | 含义 | 设计要求 |
|----|------|----------|
| `flat` | 纯色 | 默认 |
| `dotGrid` | 浅点阵 | 低对比；不能脏列表字（列表行有实底卡片） |
| `lineGrid` | 方格线 + 交点 | Pi 稿纸；间距约 18pt |
| `paperGrain` | 细纸纹 | 稀疏 1pt fleck；浅 α≈0.04、深 α≈0.065（须低于点阵） |

点阵参数经验（首页）：间距约 15–18pt、点半径约 0.7、浅色 α≈0.055、深色 α≈0.08。宁稀勿脏。

**`brandMark`**

| 值 | 含义 | 设计要求 |
|----|------|----------|
| `systemWordmark` | `Amber` 系统粗体约 32pt | 默认 |
| `paintAMBER` | 粗体大写 AMBER / 手绘感 | 光学宽度勿挤爆右侧三控件；顶栏行高约 34–38 |
| `serifWordmark` | 衬线斜体 Amber | Pi 角色包 |

**`shortcutIconStyle`**

| 值 | 含义 | 设计要求 |
|----|------|----------|
| `phosphorFill` | Phosphor fill 实心 | 默认，与会话列表同源风格 |
| `pixelSit` | 16×16 像素风 5 入口 | 仅 5 语义入口；20pt 框内光学重量接近 fill |

五入口语义固定：

| entry | 中文 | 图形语义 |
|-------|------|----------|
| deepRead | 深度阅读 | 书 / 打开的书 |
| novel | 小说创作 | 本子 / 笔记 |
| council | 模型议会 | 对话气泡 |
| miniApps | 小应用 | 四宫格 |
| webMount | WebMount | 地球 / 网络 |

**`chromeTypeface`**

| 值 | 用途 |
|----|------|
| `system` | 默认 UI |
| `rounded` | 圆润角色包 |
| `serif` | 衬线外壳（勿动聊天正文） |
| `monospace` | 等宽外壳（Pi section / meta） |

---

## 4. 颜色 Token 规则（画布）

每套 `paper` 的 light palette 需要 9 个角色（工程 `AmberPalette`）：

| Token | 作用 | 约束 |
|-------|------|------|
| `background` | 全屏画布 | 与 surface **必须可区分** |
| `surface` | 卡片 / 分组面 | 比 background 亮一档（浅色） |
| `surface2` | 嵌套底 / 分段轨道 | **不可**约等于 background（否则选中态塌） |
| `foreground` | 主墨 | 正文 / 品牌 |
| `foreground2` | 次主墨 | 节标题级 |
| `muted` / `muted2` | 次要 / 更淡 | 标签、闲置 |
| `border` / `borderSoft` | 描边 | 软分隔 |

深色：按 `paper` 分表（工程 `Paper.darkPalette`），**不**写入 Theme Pack JSON v1：

| paper | dark 画布气质 | background（约） |
|-------|---------------|------------------|
| `neutral` | E 版工作台（权威 `AmberTheme.darkPalette`） | `#0E0D10` |
| `paper` | 暖棕墨 | `#14110E` |
| `white` | 冷中性灰 | `#111111` |
| `pi` | 暖橄榄稿纸 | `#12110F` |
| `notion` | 冷灰工作台 | `#191919` |

约束：深色下仍须 `background ≠ surface ≠ surface2`；主墨对画布 ≥ 4.5:1。导入主题时校验 **accent ↔ ink ≥ 3.0:1**（UI/大字 AA；见 `AmberColorContrast`）。

强调色只做 **点缀**：FAB 图标 tint、选中描边、active 条、强调按钮。禁止整屏大色块主题（沉浸色已隐藏正是因此）。

---

## 5. 首页几何（设计对齐用，勿改排版）

| 区域 | 关键尺寸（约） |
|------|----------------|
| 顶栏左右 padding | 16 |
| 品牌字 | 约 32pt 区 / paint 字标高度 34 |
| 搜索胶囊 | 78×38 |
| 设置 / 头像 | 38×38 |
| 控制卡圆角 | 22 continuous |
| 快捷图标 | 20×20，label ~11 semibold |
| 五入口 | 等分 HStack（无障碍时横滑 minWidth 144） |
| 会话 section 标签 | ~11 semibold tracking 0.11 |
| 右下「新对话」 | 玻璃胶囊，非满宽底栏 |

**设计交付时**：mock 必须保持上述骨架；只换颜色、字标、五图标、底纹。

---

## 6. 资产规格（角色包）

### 6.1 品牌字

| 交付 | 说明 |
|------|------|
| 优先 | 矢量 / template PDF 或 SF-compatible 描述（字重、字距、是否全大写） |
| 备选 | 单色 template PNG @1x/2x/3x，高度对齐 ~34pt 显示 |
| 无障碍 | 读作 “Amber” |
| 禁止 | 过宽 wordmark 导致右侧三控件换行或重叠 |

### 6.2 快捷像素 / 风格图标

| 交付 | 说明 |
|------|------|
| 数量 | **恰好 5**，顺序与上表一致 |
| 画布 | 建议 16×16 或 24×24 网格，导出为可填色形状 / path / monochrome |
| 显示 | 约 20pt；光学重量接近 Phosphor fill |
| 颜色 | 单色 currentColor，由 UI `foregroundStyle` 上色 |
| 禁止 | 多色插画、阴影位图、与入口语义不符 |

### 6.3 画布纹理

| 交付 | 说明 |
|------|------|
| 点阵 | 描述 spacing / 点径 / 透明度即可；工程可 Canvas 实现 |
| 贴图 | 若必须贴图：可平铺、低对比、浅色/深色各一或 dynamic 说明 |
| 禁止 | 高对比网格压正文；动画噪点 |

### 6.4 设置页预览卡

| 要求 | 说明 |
|------|------|
| 尺寸节奏 | 约 2 列网格；预览区高度 ~120 + footer ~48 |
| 内容 | 示意画布色 + accent 点 +（可选）纹理/字标暗示 |
| 注意 | 预览色用 **主题自身 light 配方**，勿依赖系统当前深浅导致割裂 |

---

## 7. 现有内置包（对照）

内置只保留有辨识度的角色包；`paper × accent` 组合走外观页「自定义」，不再占预置格子。

| id | 显示名 | paper | accent | 风格槽 |
|----|--------|-------|--------|--------|
| sit-terracotta | 点阵 · 陶土 | paper | terracotta | dotGrid + paintAMBER + pixelSit + rounded + shell |
| pi-steel | 点阵 · Pi | pi（奶油稿纸） | steelBlue | **lineGrid 方格点阵** + serifWordmark + **monospace chrome** + **shell**（chat 只留纸色） |
| notion-blue | Notion · 暖白 | notion（暖白） | notionBlue `#0075de` | flat + system wordmark + phosphor + quieter glass；选中行中性晕 |

曾存在的经典 6 包（`warm-amber` / `paper-amber` / `white-mist` / `warm-sage` / `paper-rose` / `white-ink`）已移出 builtins；导入旧 JSON 进入本机主题库并可再选中；未入库的临时组合显示「当前为自定义组合」。

外观页：导入 → `AmberThemePackLibrary`（`Documents/theme-packs/library.json`）；右上角「管理」→ 多选导入包 → 一键移除。三个内置角色包不可移除。

---

## 8. 工程交接格式（设计 → 开发）

每个新主题请交付一份 YAML（或等价表）：

```yaml
id: ink-mono          # kebab-case，稳定
displayName: 墨韵 · 素
paper: white          # paper | neutral | white
accent:
  name: ink           # 或 new + hex + inkHex
  # 若新色：
  # accentHex: "0x2A2A2E"
  # inkHex: "0xFFFFFF"   # 或深墨
canvasStyle: flat     # flat | dotGrid | lineGrid | paperGrain
brandMark: systemWordmark   # systemWordmark | paintAMBER | <newStyleId>
shortcutIconStyle: phosphorFill  # phosphorFill | pixelSit | <newStyleId>
chromeTypeface: system      # system | rounded | serif

# 角色包附加
assets:
  brandMark: null     # 或路径/描述
  shortcutIcons: null # 5 个 monochrome，按 deepRead…webMount 顺序
  canvasNotes: "纯色，无纹理"

# 验收自检
checks:
  listLayoutUnchanged: true
  chatBodyFontUntouched: true
  accentNotFullBleed: true
  surfaceDistinctFromBackground: true
  fiveShortcutsOnly: true
```

实现落点（iOS）：

- 配方：`iosApp/iosApp/AmberThemePack.swift` → `builtins`
- 颜色 runtime：`AmberThemeRuntime` / `AmberTheme`
- 首页挂载：`AmberCanvasBackground` / `AmberBrandMarkView` / `HomeShortcutIconView` / `AmberChromeFont`

---

## 9. 设计质量清单（出图前勾）

- [ ] 列表仍是「顶栏 + 控制卡 + 会话行」，没有改成杂志流 / 大卡片瀑布
- [ ] 五入口顺序与语义正确，图标可辨
- [ ] 品牌字旁仍放得下 搜索 78 + 设置 38 + 头像 38
- [ ] 强调色只点缀，无整屏染色
- [ ] 浅色下 background / surface / surface2 三级可辨
- [ ] 深色下文字对比可读（用现有 dark palette 心智即可）
- [ ] 点阵/纹理不压「会话」标签与列表标题（行内有实底）
- [ ] 未规定聊天气泡改字体
- [ ] 主题名简洁：「材质 · 强调色」或「风格 · 色相」

---

## 10. 可发给设计 AI 的 Prompt

下面整段复制即可；把 `{风格描述}` 换成你想要的风格。

````markdown
你是产品视觉设计师，要为 iOS App **AmberAgent**（安静的 AI agent 工作台）设计 **一整套 Theme Pack（主题配方）**，不是重做 App 信息架构。

# 产品气质
- 安静、可工作、克制；不是皮肤商城，不是赛博霓虹大片。
- 主题 = 换皮不换排：只改视觉层，不改列表排版与导航结构。

# 冻结（绝对不要改）
1. 首页结构：顶栏（左品牌字 Amber / 右 搜索胶囊+设置+头像）→ 控制卡（可选 Continue + 五个快捷入口横排）→ 「会话」小标题 → 会话列表行（左图标+标题+预览+时间）→ 右下「新对话」玻璃胶囊。
2. 五个快捷入口固定且顺序固定：
   - 深度阅读（书）
   - 小说创作（本子）
   - 模型议会（对话）
   - 小应用（四宫格）
   - WebMount（地球/网络）
3. 不要改会话行布局、不要改成双列/瀑布/大封面。
4. 不要规定聊天正文字体（正文由用户「显示与字体」控制）。
5. 不要强制浅色/深色模式（由系统外观独立控制）。

# 你必须交付的「主题槽位」（整包）
请给出一个完整配方，并解释选择理由：

| 槽位 | 可选值 / 规则 |
|------|----------------|
| id | kebab-case 英文，稳定 |
| displayName | 中文，建议「气质 · 色相」，如「点阵 · 陶土」 |
| paper | paper（暖纸）/ neutral（暖灰）/ white（中性白）三选一，并给出 background/surface/surface2/foreground/muted/border 等 hex（background≠surface≠surface2） |
| accent | 一个强调色 hex + 上墨色 inkHex（亮色强调必须深墨；中低亮度可用白） |
| canvasStyle | flat 纯色 / dotGrid 浅点阵 / 其他纹理需说明密度与透明度（低对比，不脏字） |
| brandMark | system「Amber」或角色化字标（如全大写 AMBER 手绘感）；宽度不能挤爆右侧三控件 |
| shortcutIconStyle | 默认实心线面图标 或 你设计的 5 个单色风格图标（16×16 或 24 网格，currentColor，显示约 20pt，光学重量扎实） |
| chromeTypeface | system / rounded / serif（仅外壳：品牌、会话标签、入口字、搜索文案；勿动聊天正文） |

# 目标风格（由需求方填写）
{风格描述}

# 输出格式（严格按此结构）
## 1. 主题一句话概念
## 2. 配方表（id / displayName / 各槽位取值）
## 3. 色板
- paper light tokens（表：token → hex）
- accent + ink
- 说明为何 accent 不铺满全屏
## 4. 画布纹理
- 参数或贴图说明；浅色/深色注意点
## 5. 品牌字
- 字样、字重、字距、高度约 32–34pt 的约束
## 6. 五个快捷图标
- 每个入口：语义 + 图形描述（可附 16×16 点阵/ASCII 或绘制说明）
## 7. 首页视觉说明
- 在「冻结布局」下，哪些元素变了、哪些没变
## 8. 设置页主题卡预览建议
- 小预览如何一眼看出这是该主题（色+纹理+字标暗示）
## 9. 工程交接 YAML
（按下列字段填完整）
```yaml
id:
displayName:
paper:
accent:
  name:
  accentHex:
  inkHex:
canvasStyle:
brandMark:
shortcutIconStyle:
chromeTypeface:
assets:
  brandMark:
  shortcutIcons:
  canvasNotes:
checks:
  listLayoutUnchanged: true
  chatBodyFontUntouched: true
  accentNotFullBleed: true
  surfaceDistinctFromBackground: true
  fiveShortcutsOnly: true
```
## 10. 反例
- 列出 3 个「看起来炫但违反本规范」的做法并否定它们

# 质量红线
- 强调色只做点缀（选中、小按钮、图标 tint），禁止整屏染色主题。
- 点阵/纹理必须低对比；列表标题在实底卡片上，纹理主要在画布露底处。
- 五图标单色、可辨、重量接近；禁止多色贴纸风。
- 保持「能干活的工具」而不是「壁纸 App」。
````

### 使用示例（把 `{风格描述}` 换成）

- `日系和纸 + 朱砂点缀，安静书房感，轻微纸纹，不要可爱贴纸。`
- `冷淡极简 Bauhaus，中性白画布，墨黑强调，纯几何五图标，无纹理。`
- `赛博但克制：深墨强调 + 雾蓝点缀，点阵极淡，像素图标偏终端，不要霓虹满屏。`
- `编辑部杂志风：衬线外壳字体，暖纸，玫瑰强调，品牌字典雅，图标仍简洁单色。`

---

## 11. 可选表面槽（扩展）

默认值 = 经典包现状；角色包可覆盖。

| 槽 | 取值 | 作用 | 点阵包 |
|----|------|------|--------|
| `canvasScope` | homeOnly / shell / appWide | 纹理画在哪些页 | **shell** |
| `bubbleChrome` | standard / soft / crisp | 气泡/卡片圆角档 | soft |
| `glassChrome` | standard / quieter / solid | **仅首页** 玻璃垫底 | quieter |
| `emptyArt` | none / character | 首页空会话装饰 | character |
| `settingsChrome` | bool | 外观页标题/section 跟 `chromeTypeface`（`AmberChromeFont.settings`） | **true**（Pi）；sit/经典 **false** |
| `launchBrand` | none / matchBrand | 账户页复用品牌字；双品牌风险 → 内置包保持 **none**；导入 `matchBrand` 有接线 | **none** |
| `assetMode` | builtinOnly | 仅内置资源；zip 包见 Future（P4），非用户旋钮 | builtinOnly |
| `immersivePolicy` | hidden | 沉浸色 picker 仍隐藏 → Future（P4） | hidden |

### Future（非本规格交付面）

- 自定义图标 / 资源 zip（`assetMode` 扩展）
- 沉浸色 paper 上架与对比度验收
- 为角色包默认打开 `launchBrand: matchBrand`（需先过双品牌验收）
- Live Activity / Widget 跟随 Theme Pack（当前扩展目标**无** App Group 主题快照，使用静态暖橙 `amberAccent` 回退色，不假装已对齐）

### MiniApp `host.getTheme`（iOS）

字段（颜色 only，无点阵纹理）：`dark`、`background`、`surface`、`surface2`、`foreground`、`muted`、`primary`、`primaryInk`。  
组装：`IOSMiniAppThemeBridge.payload`（随当前 `paper` 浅/深 palette + runtime accent/ink）。

## 12. 导入 / 导出（工程 v1）

| 项 | 说明 |
|----|------|
| 格式 | `format: "amber.theme.pack"`, `version: 1` |
| 内容 | 核心槽 + 可选表面槽（旧文件缺省 → 默认值） |
| 不含 | 浅深模式、聊天字体、自定义资源 zip |
| 入口 | 外观设置 →「主题文件」 |
| 校验 | 拒沉浸 `paper`、未知 enum、错误 format/version |
| 代码 | `AmberThemePackTransfer` / `AmberThemePackDocument` |

---

## 13. 维护说明

| 变更类型 | 谁改 | 注意 |
|----------|------|------|
| 只换 paper×accent 的经典包 | 工程 `builtins` + 可选预览 | 风格槽保持默认 |
| 新角色包 | 设计交付 YAML + 资产 → 工程加槽/资源 | 列表排版冻结 |
| 新 `canvasStyle` / 图标风格枚举 | 工程扩 enum + adapter | 旧包默认值不变；导入会拒绝未知 rawValue |
| 沉浸色上架 | 产品单独决策 | 需全 app 对比度验收 |
| 导入导出版本 | 升 `version` 时保持解码兼容策略 | |

**相关代码：**

- `iosApp/iosApp/AmberThemePack.swift`
- `iosApp/iosApp/AppearanceSettingsView.swift`
- `iosApp/iosApp/PlaceholderViews.swift`（`AmberTheme` / `AmberThemeRuntime` / Home）

---

*本文档与实现不一致时，以代码中的 enum 与 `builtins` 为准，并回写本文档。*
