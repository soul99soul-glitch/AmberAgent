# Amber Android → 鸿蒙 ArkUI 设计参考（候选快照）

核对日期：2026-09-13。范围仅为 Android 参考仓库。已先读取该仓库的 `AGENTS.md`。本报告没有运行 app、没有访问模拟器或手机，也没有修改 Android 工作树。

当前工作树有大量未提交 WIP；本报告涉及的 `AmberTokens.kt`、`AmberCanvas.kt`、`SettingDesignPrimitives.kt`、`ProviderRedesignPrimitives.kt`、`SessionHomePage.kt` 已被修改，`HomeSessionIcons.kt` 还是未跟踪文件，`SettingAppearancePage.kt` 也未跟踪。因此下面的源码值是给公司电脑任务使用的候选快照，最终引用必须绑定用户随后提供的 Android SHA，不能把本文当作最终提交的永久基线。

文中源码路径均相对 Android 仓库根目录，行号属于此次候选快照。正式执行优先遵循 `START_TASK.md` 与 `README.md` 的范围和验收规则；本附录用于查找设计来源，历史截图尚未打包到本交接目录。样板组合与批次以主计划为准。

## 设计来源与证据等级

源码注释给出的设计来源是：

- `app/src/main/java/app/amber/feature/ui/theme/AmberTokens.kt:8` 说明它是 `redesign/oc-amber.css §2` 的转录；四个中性色基底与独立强调色来自设计 §2.3。
- `app/src/main/java/app/amber/feature/ui/theme/AmberType.kt:10` 给出设计 §3 的 mono/sans 分工：JetBrains Mono 用于机器事实，Hanken Grotesk 用于人类可读文字；中文落到 Android 系统 CJK fallback。
- `app/src/main/java/app/amber/feature/ui/components/ds/AmberPrimitives.kt:46` 把公共原语对应到 `oc-amber.css §6.1 + design §6.2`。
- `app/src/main/java/app/amber/feature/ui/pages/chat/ChatTheme.kt:10` 说明旧聊天主题字段来自 V3 `themes.jsx`；当前 `AmberTokens.toChatTheme()` 是 Graphite 兼容适配层。
- `app/src/main/java/app/amber/feature/ui/pages/sessionhome/HomeSessionIcons.kt:14` 说明首页图标是与 iOS 同源的 300 个 Phosphor fill glyph，路径本地缓存，许可文件在 `app/src/main/assets/licenses/phosphor/LICENSE`。

验证文档是历史证据，不是当前 SHA 的源码基线：`docs/verification/2026-09-13-redesign.md:5` 记录当时的 81 个 HTML 输入和起点 `f243eb7`；`docs/verification/2026-09-13-redesign-coverage.md:1` 记录 2026-09-13 对设计稿与工作树源码的覆盖定义。文档当时读取过 `/private/tmp/amber-redesign-latest-l7_ml_uj/` 下的 `DESIGN.md`/`HANDOFF.md`，但本文没有把那个临时目录的内容当作当前 Android 源码。

## 主题、颜色与动态覆盖

### AmberTokens 的四个中性基底

以下是 `app/src/main/java/app/amber/feature/ui/theme/AmberTokens.kt:37` 的实际十六进制值。表中的 `ink/ink2/ink3/ink4` 和 `line/line2` 是按源码字段顺序列出的；不是根据名称推测的页面颜色。

| 基底 | `bg` / `surface` / `surface2` / `raised` | `ink` / `ink2` / `ink3` / `ink4` | `line` / `line2` | 用户气泡 `userBg` / `userInk` | `codeBg` | 内置 `signal` | `isDark` |
|---|---|---|---|---|---|---|---|
| `LIGHT`（暖纸） | `#EFE7D6` / `#FFFDF7` / `#F0EBE2` / `#FFFFFF` | `#1B1813` / `#5B5449` / `#746D62` / `#918A80` | `#ECE3D6` / `#DBCEBC` | `#1B1813` / `#FFFDF7` | `#F0EBE2` | `#5E9C6E` | false |
| `DARK`（暖石墨） | `#14110E` / `#221E19` / `#2E2822` / `#2E2822` | `#F5F0E8` / `#C8BDB0` / `#A89888` / `#6E6258` | `#2A241E` / `#3D342C` | `#F5F0E8` / `#14110E` | `#2E2822` | `#5E9C6E` | true |
| `SAGE`（绿调纸） | `#F0F2EA` / `#F6F8F0` / `#E7EADF` / `#FFFFFF` | `#1B201A` / `#535A4D` / `#888F7E` / `#B0B5A4` | `#E0E3D6` / `#D0D4C4` | `#1B201A` / `#F3F5EC` | `#E8EBDF` | `#2F8F76` | false |
| `SAGE_DARK`（深森林） | `#131711` / `#191D15` / `#1E2219` / `#20241B` | `#E6EBDF` / `#A0A896` / `#6E7563` / `#515845` | `#2A2E22` / `#353A2C` | `#E6EBDF` / `#1B201A` | `#1E2219` | `#4CAF8E` | true |

所有基底声明的内置 `accent` 是 `#B8623A`，但真实活动值由 `app/src/main/java/app/amber/feature/ui/theme/AmberTokens.kt:108` 注入。可选强调色是：`#B8623A` terracotta、`#5E9C6E` sage-green、`#4F86D6` blue、`#9277C4` purple、`#C2607A` rose（`app/src/main/java/app/amber/feature/ui/theme/AmberTokens.kt:88`）。`accentInkFor()` 对 terracotta 返回真正的黑色 `#000000`，对其余四个预设返回 `#0F150E`，未知颜色返回白色（`app/src/main/java/app/amber/feature/ui/theme/AmberTokens.kt:98`）。因此 `AmberLight` 常量里声明的白色 `accentInk` 与运行时通过 `buildAmberTokens()` 得到的 terracotta 黑色 `accentInk` 不同，迁移时应以活动构建链为准。

### 运行时选择与覆盖

`app/src/main/java/app/amber/feature/ui/theme/Theme.kt:145` 将 `SYSTEM/LIGHT/DARK` 解析为 `darkTheme`；`app/src/main/java/app/amber/feature/ui/theme/Theme.kt:215` 再按 `amberBaseFamily` 与深色状态选 `LIGHT/DARK` 或 `SAGE/SAGE_DARK`，从设置字符串解析用户强调色，并生成活动 tokens。当前 `DisplaySetting` 默认是 `amberBaseFamily = "WARM"`、`accentColor = "#B8623A"`（`core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt:408`）；`WARM` 在解析时落到 `LIGHT/DARK`。

AMOLED 只有在深色 app 模式且开关打开时生效：`app/src/main/java/app/amber/feature/ui/theme/Theme.kt:225` 覆盖 `bg = #000000`、`surface = #050505`、`surface2 = #101010`、`raised = #141414`、`codeBg = #101010`；M3 scheme 也在 `app/src/main/java/app/amber/feature/ui/theme/Theme.kt:157` 采用纯黑层级。ArkUI 不能只复制 WARM 浅色截图，至少要保留“颜色模式 × WARM/SAGE × AMOLED × 用户 accent”这条解析关系。

M3 角色不是独立的一套色板，而是由 `ChatTheme`/tokens 映射：`background = chatTheme.bg`、`surface = chatTheme.paper`、`surfaceVariant = chatTheme.toolPillBg`、五级 container 对应 `bg/surface/surface2/surface2/raised`，`primary/secondary = accent`，`onPrimary/onSecondary = accentInk`，outline 使用 `line2/line`（`app/src/main/java/app/amber/feature/ui/theme/Theme.kt:237`）。

设置原语通过 `workspaceColors()` 读取最终 M3 scheme，而不是直接读取 token 字段：canvas/background、paper/surface、row/surfaceVariant、note/surfaceContainer、ink/onSurface、muted/onSurfaceVariant、hairline/outlineVariant 的映射见 `app/src/main/java/app/amber/feature/ui/components/ui/WorkspaceStyle.kt:71`。其中成功色固定为 `#5E9C6E`，警告色为浅色 `#9C6A26`/深色 `#D8B575`，危险色固定为 `#C2554E`；这些不是 AmberTokens 的 `signal`。`green/amber/redContainer` 是相应颜色以 alpha `0.12` 与 surface 合成后的颜色。

### Canvas 点阵

`app/src/main/java/app/amber/feature/ui/components/ds/AmberCanvas.kt:17` 先绘制 `tokens.bg`，再以 `18.dp` 网格、半间距起点绘制 `1.4.dp` 圆端点。浅色点为固定 `#281F14` alpha `0.055`，深色点为固定 `#F4F1ED` alpha `0.08`；AMOLED 通过 `LocalAmoledDarkMode` 不绘制点阵。注释和验证文档都要求点阵只在画布出现，卡片、阅读纸面、代码与实际远程网页用不透明 surface 覆盖：`docs/verification/2026-09-13-redesign.md:23`。

## 字体与文字层级

`app/src/main/java/app/amber/feature/ui/theme/AmberType.kt:27` 的稳定层级是：

| 语义 | 字体 | 字号 / 行高 | 字重与附加 |
|---|---|---|---|
| 屏幕标题 | Hanken Grotesk | 17 / 23sp | Bold |
| 会话标题 | Hanken Grotesk | 16 / 21sp | Bold |
| 人类正文 | Hanken Grotesk | 14 / 20sp | Normal |
| 辅助文字 | Hanken Grotesk | 12 / 17sp | Normal |
| 机器 metadata | JetBrains Mono | 12 / 16sp | Normal，`tnum, zero` |
| `//` eyebrow | JetBrains Mono | 11 / 14sp | SemiBold，letter spacing 1.3sp，`tnum, zero` |
| tiny tag | Hanken Grotesk | 10.5 / 13sp | SemiBold |

实际页面会局部覆盖这些值，不能只用 token 名推断最终字号。`Theme.kt:52-68` 的 MaterialTypography 还定义了 display 28/36、25/32、22/29，headline 20/27、18/25、17/23，title 17/23、14/20、13/18，body 15/22、14/21、12/18，label 12/17、11/15、10/14sp。验证文档记录的二级页收紧目标是标题 17/23、常规文字 14/20、辅助文字 12/17，单行卡组 48dp、双行 56dp、顶栏 52dp、开关视觉 44×26dp（`docs/verification/2026-09-13-compact-ui.md:5`）。

中文目前依赖系统 CJK fallback，代码注释明确这是接近 Noto Sans SC 的近似，未来才考虑打包 subset；鸿蒙端应准备 Hanken/JetBrains Mono 的等价字体与中文 fallback，并保持“人类文字 sans、机器事实 mono”的语义分工。

## 设置原语（SettingDesignPrimitives）

### 卡组与行

`app/src/main/java/app/amber/feature/ui/pages/setting/SettingDesignPrimitives.kt:37` 的 `SettingSectionTitle` 使用 `SectionLabel`：水平 padding 2dp、垂直 2dp、文字与规则间距 9dp、规则 1dp、规则色 `workspaceColors().hairline`。`SettingCardGroup` 把标题放在卡外，标题上方 10dp，标题与卡间距 6dp；它调用 `CardGroup` 时显式设置 `containerColor = colors.paper`、无 border、无 shadow、`itemSpacing = 0`、divider 为 `hairline` alpha `0.28`、divider 起始 12dp、leading offset 38dp。

`CardGroup` 本体的公共实现仍有 14dp 圆角，默认 border 是 1dp outlineVariant，行默认最小高度单行 48dp、带 overline/supporting 时 56dp，行水平 padding 12dp；这些细节见 `app/src/main/java/app/amber/feature/ui/components/ui/CardGroup.kt:26`。Setting wrapper 明确把默认卡间隔和边框覆盖掉，所以鸿蒙端应实现“单一纸面卡 + 0.5dp 内部分隔线”，不要把默认 CardGroup 的边框带进设置页。

`settingSingleLine()` 是 `heightIn(min = 48.dp)`，`settingTwoLine()` 是 `heightIn(min = 56.dp)`；页面横向 inset 是 16dp。leading icon 走 `WorkspaceLeadingIcon`：默认容器 28dp、图标 16dp、圆角 9dp（`app/src/main/java/app/amber/feature/ui/components/ui/WorkspaceStyle.kt:152`）。设置 segmented choice 外层是 row 色、1dp hairline、圆端、3dp 内 padding，选中项是 paper 圆端填充，外部选项透明，选中/未选中文字为 ink/muted（`app/src/main/java/app/amber/feature/ui/pages/setting/SettingDesignPrimitives.kt:99`）。

`WorkspaceTopBar` 的实际 expandedHeight 是 52dp，背景使用 canvas，标题使用 `LocalAmberType.current.screenTitle`（`app/src/main/java/app/amber/feature/ui/components/ui/WorkspaceStyle.kt:280`）。图标按钮通常有 48dp 最小触控区，而可见 surface 可以是 40dp，内层圆角 12dp（`app/src/main/java/app/amber/feature/ui/components/ui/WorkspaceStyle.kt:191`）。

## Provider 原语

实现集中在 `app/src/main/java/app/amber/feature/ui/pages/setting/components/ProviderRedesignPrimitives.kt`。其共同规则是：视觉控件收紧，点击区保持 48dp；颜色全部来自 `LocalAmberTokens`/`LocalAmberType`，错误边界才读取 M3 error。

| 原语 | 已核实的视觉值与语义 |
|---|---|
| `ProviderSectionLabel` | 上 22dp、下 11dp；`//` 与大写 label 使用 mono eyebrow，间距 7dp；可附 `· count`，右侧 1dp `line` 规则（:68-92）。 |
| `ProviderCard` | `surface` 填充，14dp 圆角，1dp `line` alpha 0.42 描边（:94-106）。 |
| `ProviderHairline` | 0.5dp，`line` alpha 0.46（:109-118）。 |
| `ProviderMonogram` | 默认 34dp；可变圆角为 size×0.28；surface2 填充、line alpha 0.46；文本最多 4 个字符，mono Bold，字号按长度系数 0.27/0.31/0.33×size，禁用用 ink4（:120-151）。 |
| `ProviderLiveDot` | 默认 6dp 圆点；启用用 `tokens.signal`，禁用用 ink4（:153-165）。活动构建中 `signal` 会被 `buildAmberTokens` 别名为用户 accent，因此它不必然是绿色。 |
| `ProviderIconButton` | 默认 48dp 圆端点击区、19dp 图标，可旋转 180°，按压统一走 `pressable`（:167-204）。 |
| `ProviderPillSeg` | 外层/每项最小 48dp，项间 6dp；可见 capsule 32dp、CircleShape；选中背景 accent alpha 0.10、文字 accent，未选中背景 surface2、文字 ink3；选中 SemiBold，未选中 Medium（:206-250）。 |
| `ProviderFieldLabel` | 起始 2dp、下 7dp；mono 10.5sp SemiBold、ink3（:252-263）。 |
| `ProviderTerminalFilter` | 最小 48dp 行，`▸` accent、间距 7dp；输入 mono 12sp；底部 1dp line2（:265-322）。 |
| `ProviderTextField` | 默认最小 46dp；surface2、12dp 圆角、1dp line alpha 0.58、水平 14dp/垂直 12dp padding；mono 走 meta，普通输入走 body；错误使用 M3 error（:324-373）。 |
| `ProviderSecretField` | 14dp 圆角、surface2、1dp line alpha 0.58；左 14/右 6/上 8/下 8dp，内部间距 8dp；不可见文本固定显示 40 个 `•`，可切换显示并保留复制按钮；mono 行高 19sp，两个小按钮各 48dp（:388-440）。 |
| `ProviderSmallIconButton` | 48dp 触控区、8dp 圆角、17dp 图标（:442-465）。 |
| `ProviderToggle` | 48dp 触控区；可见轨道 44×26dp、999dp 圆角、内 padding 3dp；checked 为 accent，unchecked 为 line2；thumb 20dp raised（:467-496）。 |
| `ProviderCapFlags` | mono 11sp，多个能力标签之间用 `·` 与 5dp 间距，首项可用 accent（:498-523）。 |
| `ProviderCommandButton` | 外部最小 48dp；可见高度 36dp、CircleShape；主按钮为 accent，普通启用为 accent alpha 0.10，禁用为 surface2；水平 16dp，图标 17dp、图文间距 7dp，文字 mono 11.5sp Medium（:525-584）。 |
| `ProviderGhostButton` | 外部最小 48dp；可见高度 34dp、CircleShape；accent 状态为 accent alpha 0.10，否则 surface2；水平 14dp，图标 13dp，文字 mono 11sp Medium（:586-628）。 |
| `ProviderUnderlineTabs` | mono eyebrow；文字上 11/下 9dp，选中下划线 2dp accent，左右 12dp，组件末尾接 0.5dp hairline（:630-669）。 |
| `ProviderSquareTag` | 名称沿用旧称但当前是胶囊：外部最小 48dp，可见高度 34dp，CircleShape，水平 12dp；solid selected 为 accent，普通 selected 为 accent alpha 0.12，未选中为 surface2；mono 10.5sp，selected SemiBold（:671-719）。 |
| `ProviderLedgerRow` | field label 后放内容，底部留 12dp 再画 hairline（:721-734）。 |
| `ProviderSplitBar` | 水平 16dp、垂直 8dp padding，按钮间距 8dp；取消走非 accent ghost，确认走 enabled 的 accent command，两个按钮各占一半（:736-767）。 |
| `ProviderAuthBadge` / `ProviderSheetGrabber` | badge 为 surface2 圆端，水平 8/垂直 3dp，mono 10sp SemiBold；sheet grabber 42×4dp、999dp 圆角，上 10/下 4dp（:769-800）。 |

Provider 详情页把这些原语组合为身份卡、协议/鉴权/端点/账户卡，页面根部的真实间距是 `LazyColumn` 14dp item spacing、水平 16dp/垂直 12dp content padding（`app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderConfigPage.kt:209`）。详情顶部 tab 是 44dp 行、20dp tab 间距、选中下划线 2dp（`app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderDetailPage.kt:242`）。模型页使用 16dp 横向 content padding、顶部/底部 10/88dp，空态卡高 180dp（`app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderModelTabPage.kt:191`）。

模型行有一个必须保留的交互语义：前景用 `amberCanvas()` 覆盖背景；只有 `dismissDirection != Settled` 时才显示取消/删除背景操作（`app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderModelTabPage.kt:494`）。历史修复文档记录的真实 bug 是透明前景让静止的删除按钮透出（`docs/verification/2026-09-13-model-row-swipe-fix.md:3`）。鸿蒙端应保留“静止隐藏、拖动显示、取消复位、明确点击删除”的状态机。

## Session 首页与图标

当前 `SessionHomePage` 的根是 `amberCanvas()`；列表、header、搜索和 feature rail 同在可滚动 `LazyColumn`，底部 content padding 100dp，页面同时处理 status/navigation insets（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:299`）。这是当前工作树事实，和较早验证文档所说“首页只保留胶囊改动”的历史快照（`docs/verification/2026-09-13-redesign.md:12`）不一致，迁移时应优先等待最终 SHA。

### Header、搜索、底部动作

- Header 最小高度 68dp，水平 16dp、垂直 4dp；字标 100×26dp，cursor 6×15dp。字标 tint 为 ink，cursor 为 accent。
- cursor 是 `rememberInfiniteTransition` 的 1050ms step 闪烁：0–520ms 不透明，525ms 变为 0（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:600`）。
- 收起的搜索入口 58×32dp、16dp 圆角、surface2、1dp line；外部点击区宽 58×44dp。设置和头像各有 44dp 点击区，内层可见圆面 32dp，图标 18dp，头像也是 32dp（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:647`）。
- 日期是 mono 10/13sp，letter spacing 0.15sp，ink3。展开搜索的 field 最小 40dp、12dp 圆角、surface2、1dp line、水平 12dp；输入和 placeholder 14.5/18sp；清除与全文搜索按钮各 44dp（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:825`）。
- 搜索展开与收起均是 fade + vertical expand/shrink：进入 190ms、退出 170ms；header 内入口对应 horizontal expand/shrink 也是 190/170ms（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:337`）。
- 顶部滚动渐隐只在列表能向上滚动时出现；层高 40dp，status bar inset 后使用 Haze blur radius 12dp 和白到透明的 mask，再叠加 `bg → bg 60% → transparent` 垂直渐变；进入 90ms、退出 150ms（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:541`）。底部为 72dp transparent→bg 渐变，让出 FAB 区域。
- 新对话 FAB 外部点击高度 48dp，右/下 inset 为 26dp；可见胶囊高 40dp，CircleShape，水平 14dp，accent 填充，15dp 铅笔图标与 12/15sp Medium 文字。黑色阴影半径 12dp、向下 4dp，alpha 浅色 0.14/深色 0.24（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:465`）。

### Feature rail 与会话行

- Feature rail 外部水平 16dp、垂直 8dp；使用 `AmberContinuousShape(22.dp)`，surface 填充、0.5dp line alpha 0.72 描边；shadow elevation 7dp，ambient ink alpha 0.18、spot ink alpha 0.12（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1115`）。连续圆角是视觉语义，ArkUI 端不应退化成明显折线的普通矩形。
- 有 continue candidate 时，顶部行水平 14dp、垂直 10dp；36dp 图标 tile、12dp 圆角、accent alpha 0.12，图标 19dp；标题 13/17sp SemiBold，副标题 10.5/15sp，continue 文字 12.5/17sp SemiBold，18dp 圆角、水平 14/垂直 6dp、accent alpha 0.14。副标题只在真实运行中在标题与 summary 之间切换，Crossfade 为 220ms；空闲不伪造运行摘要（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1029`）。
- 功能入口固定五项：深读、Mini Apps、小说、Sites、议会。每项最小 48dp、内层 10dp 圆角，20dp 图标，10.5/14sp 文字，图标 ink2、文字 ink3；rail 行水平 12dp、垂直 8dp（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1126`）。
- 会话分组标题水平 16dp，上 10/下 6dp；`//` 与标题都是 mono 11/14sp SemiBold、letter spacing 1.4sp，规则 0.5dp line alpha 0.46（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1078`）。
- 每个会话外部水平 16dp；首行上角与末行下角为 14dp，中间行方角，通过同一 surface 形成卡组。行内容水平 14dp、垂直 13dp；左侧图标容器 36dp 圆形，背景为 tileColor alpha 0.13，glyph 18dp；标题为 13.5/18sp Medium、letter spacing -0.2sp、最多两行；时间、消息数和分隔点为 mono 11/14sp，元数据 ink3/ink4，标题与元数据间 2dp。非末行分隔线从 60dp 起、右侧 14dp、0.5dp line alpha 0.46（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1292`）。
- 最近访问会话的 row surface 是 accent alpha 0.08（浅色）或 0.16（深色）与 tokens.surface 合成；其 tileColor 是 accent，其他会话 tileColor 为 ink3（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1343`）。这是状态强调，不是按标题生成彩虹色。
- 会话左滑（EndToStart）露出 accent 删除背景，右滑（StartToEnd）露出 surface2 置顶背景；动作图标 17dp、标签 mono 10sp、letter spacing 0.4sp。置顶在阈值触发后回调并返回 false 让行回弹，删除方向交给移除流程（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt:1314`）。

### HomeSessionIcons

枚举从本地 256×256 SVG path 建立 20×20dp `ImageVector`，仅在 `remember(title)` 时解析缓存；当前枚举实际计数为 300。`homeSessionIcon()` 先 trim + `Locale.ROOT` 小写，空标题和“新消息/新对话/new message/new chat”固定为 `CHAT_CIRCLE`，然后按 `HomeSessionKeywords` 首个匹配返回；没有匹配时对原始标题 UTF-8 字节做 `hash = 127 × hash + unsignedByte`，用固定 fallback 列表取模，保证陌生标题跨重启稳定（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/HomeSessionIcons.kt:321`）。

鸿蒙迁移应保留关键词优先级、UTF-8 稳定 hash、空标题语义、glyph 的 fill 轮廓与许可归属；可以换成平台的矢量承载方式，但不能改成随机图标、按设备语言产生不同结果，或每次生成/持久化一个新图标。

## ChatTheme 兼容层的实际语义

`app/src/main/java/app/amber/feature/ui/pages/chat/ChatTheme.kt:128` 当前由 `AmberTokens.toChatTheme()` 生成 `name = "Graphite"`：

- 基础 surface：`bg = tokens.bg`、`paper/surface = tokens.surface`、`ink = ink`、`inkSoft = ink2`、`inkFaint = ink3`、`hair/surfaceEdge = line`。
- 强调与发送：`accentDeep = accent`、`accentSoft = accent alpha 0.14`、`accentTint = accent alpha 0.22`、`sendBg = accent`、`sendArrow/onAccent = accentInk`。
- 装饰光效被明确压平：`sendHalo`、`bloomCore`、`bloomSecondary`、`bloomHighlight` 都是 Transparent，`bloomMaxAlpha = 0`、`showBloomInConvo = false`，`topHaloCore` Transparent、`topHaloAlpha = 0`、`bloomHeightFrac = 0`。这对应 Graphite 的“平面 + hairline”意图，不代表 ArkUI 可以凭 token 名自行恢复光晕。
- 用户气泡：`userBubble = userBg`、边界 `line`、文字 `userInk`。
- 状态/工具/思考：model status dot = `signal`；工具 pill = `codeBg`/`line`，label = accent；工具 icon 在深色固定 `#8AD39A`、浅色固定 `#4B9866`；完成 badge glyph 固定 `#1B1A17`；思考规则 `line2`，思考标题深色 `#E4AD61`、浅色 `#D68A28`，思考正文 `ink3`。
- 浮层/容器：深色 sheet backdrop `#99000000`、浅色 `#52000000`；search bar = surface2；composer shadow Transparent；outlineStrong/Soft = line2/line；五级 container = bg/surface/surface2/surface2/raised；popover = surface；widget canvas = surface2，widget border = line。
- context ring 的 empty/track = line2/line，low/mid/high 都是当前 accent；它不再按 `ChatTheme` 字段名恢复原 V3 的蓝/黄/红阶梯。

这是兼容层，不是未使用的数据类。当前工具图标、思考标题/正文、ContextRing、widget canvas、composer shadow 等消费者仍在 Android UI 中读取 `LocalChatTheme`。鸿蒙端要按消费者保留可见结果和状态，不要因为 bloom 字段透明就删除整个 ChatTheme 语义；应把真正仍显示的工具、思考、上下文环、widget 和输入区映射到同一套动态 tokens。

通用按压反馈是 `app/src/main/java/app/amber/feature/ui/components/ds/AmberPrimitives.kt:51` 的 `pressable`：按下 scale `0.975`，松开回到 1；LiveDot 的非 idle 呼吸周期 2400ms、halo alpha 从 0.35 退到 0（`app/src/main/java/app/amber/feature/ui/components/ds/AmberPrimitives.kt:129`）。这些属于视觉反馈，应在 ArkUI 侧保留相同节奏和终态，而不是把所有交互改成瞬时颜色跳变。

## Compose → ArkUI 应保留的视觉语义与适配事项

1. 保留角色化颜色：`canvas → paper/surface → row/note → ink/muted/faint → hairline` 的层级，以及 accent、状态点、错误三类语义。不要把所有背景压成一个色；不要用 token 名猜最终值，先完成 WARM/SAGE、浅/深、AMOLED 和自定义 accent 的解析。
2. 保留画布与内容面分离：点阵只进入 canvas；卡片、输入井、阅读纸面、代码块、widget 和远程网页都要有不透明覆盖。若平台没有等价 blur，适配应保留“上缘渐隐 + 低强度背景过渡”的视觉关系，具体实现由鸿蒙工程验证。
3. 保留字体职责和 fallback：Hanken Grotesk 负责标题/正文/标签，JetBrains Mono 负责 `//`、模型 ID、context、计数、时间、版本、工具事实；中文要明确 fallback，避免整页使用等宽字体。
4. 保留“视觉尺寸小、触控区大”：Provider/设置中的可见 capsule 多为 32/34/36/40dp，但外部点击区多为 48dp；设置行 48/56dp；首页搜索、设置、头像、FAB 也有独立 hit area。ArkUI 需要让 hit area 不改变视觉布局，并避免 swipe action 与前景内容重叠。
5. 保留圆角层级：普通卡 14dp，输入 12/14dp，图标 tile 9/12dp，feature rail 22dp 连续圆角，胶囊/状态/切换器为圆端，列表首末行只有外侧圆角。不要把所有圆角统一成一个值。
6. 保留动态列表与真实状态：Session 的会话、continue、Provider 的模型与 OAuth 状态来自 VM/settings/registry/repository；HTML 固定标题、模型、成功状态只是样例。`redesign-coverage.md:162-168` 明确要求不复制静态数据、不绕过权限和持久化（`docs/verification/2026-09-13-redesign-coverage.md:162`）。
7. 保留动效的目的和可中断性：cursor 1050ms、press scale 0.975、LiveDot 2400ms、搜索 190/170ms、continue crossfade 220ms、首页 scroll fade 90/150ms；滚动/键盘/底部 sheet/分页需要在平台生命周期变化时正确收敛，不要为截图加等待或伪造进度。
8. 具体鸿蒙适配需要单独验证：dp/sp 到 vp/fp 的尺寸换算；status/navigation safe area 与 edge-to-edge；大字号和窄屏换行；LazyColumn/HorizontalPager/ModalBottomSheet/SwipeToDismiss 的等价布局与手势；矢量 path 缓存和 Phosphor MIT 许可；密码 field 的遮罩/复制/可访问描述；键盘出现时 bottom dock 与搜索焦点；AMOLED 纯黑；blur/shadow 的深色对比。这里仅列需要对齐的行为与证据，不假设任何 ArkUI SDK API。

可用 320dp/390dp 逻辑宽度、字号 1.0/1.3 以及深浅主题作为代表配置；WARM/SAGE、AMOLED 和自定义强调色在共享主题组件中验证。各页面按真实风险补输入、滚动、sheet 或 swipe 状态，不对每页穷举这些组合。公司端实际可用尺寸和字体环境需记录，验收范围以主计划为准。

## 最值得先做的三个样板页

| 优先级 | 样板页 | 能覆盖的组件族 | 为什么先做 |
|---|---|---|---|
| 1 | `Screen.SessionHome` → `app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt` | `amberCanvas`、动态主题、字标/cursor、收起/展开搜索、连续圆角 feature rail、continue 状态、会话卡组、Phosphor glyph、pin/delete swipe、FAB、顶部/底部渐隐 | 视觉身份最集中，并且同时暴露画布、surface、动态列表、触控区、滚动、动效和状态层级；一页能发现 ArkUI 的安全区、字体、blur、手势和列表问题。 |
| 2 | `Screen.SettingAppearance` → 当前工作树候选 `app/src/main/java/app/amber/feature/ui/pages/setting/SettingAppearancePage.kt`，配合稳定的 `app/src/main/java/app/amber/feature/ui/pages/setting/SettingDisplayPage.kt` | theme/color mode、WARM/SAGE、accent swatches、AMOLED、`SettingCardGroup`、section title、48/56dp 行、segmented choice、Switch、Select、slider、Markdown 字体预览、即时保存 | 它直接覆盖主题解析与设置密度；同时能验证自定义 accent、暗色/AMOLED、中文文字、长内容自然增高和“视觉控件小但可点击区大”。由于 Appearance 文件尚未跟踪，最终实现必须以固定 SHA 复核。 |
| 3 | `Screen.SettingProviderDetail(providerId)` → `app/src/main/java/app/amber/feature/ui/pages/setting/SettingProviderDetailPage.kt` 的配置/模型双 tab | Provider identity card、monogram、status dot、auth badge、toggle、section label、pill selector、text/secret field、terminal filter、underline tabs、ledger row、ghost/command/split buttons、model tags、model swipe/delete、bottom sheet | 它是最密集的表单与状态页，能一次检验 Provider 原语、键盘/安全区、秘密字段、错误边界、tab/pager、sheet 和 swipe 前景遮盖；历史真机 bug 也发生在模型行，适合作为鸿蒙交互闭环样板。 |

## 已有截图/视频与证据缺口

### 可复用的现有图像位置

这些是已存在的文件位置，均为历史或临时验收产物，不能直接证明当前工作树：

- 旧版/历史画廊：`app/build/redesign-review/index.html`、`app/build/redesign-review/compact.html`。其中包含 `screenshots/core/`、`screenshots/navigation/`、`screenshots/compact/`，以及 `reference/*.html`。
- 首页/设置首轮图：`app/build/redesign-review/first-pass/01-home-before-seed-light.png`、`02-home-with-sessions-light.png`、`03-settings-light.png`、`04-display-light.png`、`05-settings-dark.png`；按压图在 `first-pass/12-settings-pressed.png`、`13-search-pressed.png`、`14-new-chat-pressed.png`。
- 二级页导航图：`app/build/redesign-review/screenshots/navigation/providers.png`、`provider-detail.png`、`provider-models.png`、`model-editor.png`、`about.png`、`chat-storage.png`、`novel-workspace.png` 等。
- 聊天细化截图：`/private/tmp/amber-native-chat-qa/final-390/chat-soft-timeline-smoke/` 与 `/private/tmp/amber-native-chat-qa/final-320-large/chat-soft-timeline-smoke/`，每组有 `01-first-collapsed.png` 到 `06-second-task-completed.png`；文档描述了 390/320 字号配置与限制（`docs/verification/2026-09-13-native-chat-soft-timeline.md:14`）。
- Sandbox 设置截图：`/private/tmp/amber-sandbox-compact/final-390/sandbox-settings-smoke/` 与 `final-320-large/sandbox-settings-smoke/`，主页面是 `01-default.png`，更多设置是 `02-more-settings.png`；同目录还留有高级/SSH 状态图和失败断言图。文档明确截图使用临时 SSH 档案、只做模拟器视觉/交互验证（`docs/verification/2026-09-13-runtime-settings-compact.md:14`）。

当前这些目录中没有发现 mp4、mov、webm 或 gif，因此可引用的证据是截图和 HTML 画廊，没有现成视频证据。

### 缺口

- 当前目标源码未固定 SHA；验证文档里的 APK hash、`SessionHomePage` 旧 hash 和“首页保持原样”结论属于历史阶段。当前工作树的首页已经加入 Haze 顶部渐隐、滚动结构、最近访问高亮和 300 glyph 映射，不能把旧图直接称为当前基线。
- 本次没有运行 Android、没有编译、没有模拟器/真机截图，也没有访问设备；因此本文只完成源码证据和历史产物索引。公司电脑任务需要先固定用户 SHA，再重新读取源和生成鸿蒙侧证据。
- 既有文档中的截图多为模拟器临时数据。`redesign.md:61` 明确模拟器截图不等于真实 Provider 网络、OAuth 账号或全部内容组合（`docs/verification/2026-09-13-redesign.md:51`）；Provider 与 OAuth 的真实网络、错误、长模型名、无凭据/有凭据状态仍需单独验收。
- 没有 ArkUI 实现、公司电脑截图、字体加载结果、平台 blur/shadow 对照或真实鸿蒙设备证据；尺寸和颜色关系可迁移，渲染细节仍是待验证项。
- `ChatTheme` 仍有 legacy consumers，不能只移植 Chat 页面表面颜色后删除字段；需要按工具、思考、ContextRing、widget、composer 的实际消费者做一次 Android/鸿蒙分别对照。

交付给公司电脑 AI 的最小输入应是：用户随后提供的 Android SHA、本文路径、三页样板顺序、上述颜色/字体/圆角/触控/动效语义，以及“截图历史仅作参考、最终必须在鸿蒙电脑和设备上重新验证”的证据边界。
