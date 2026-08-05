# Amber iOS 首页与 Liquid Glass 设计调研及建议

> **Status:** Proposed research synthesis（设计调研与产品建议，非已接受规格）
> **Date:** 2026-08-05
> **Scope:** 原生 iOS 首页（`ConversationsView`）信息架构、视觉层级与 Liquid Glass 采用策略
> **Not in scope:** Android `app/` 运行时、小说域内部产品契约、后台恢复实现细节

## 权威与相关文档

发生冲突时按以下顺序核对：

1. 真实代码、截图与真机证据
2. 仓库根 `AGENTS.md`、`.agents/skills/amberagent-ios-taste`
3. [`ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md`](ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md)（既有 iOS 玻璃系统设计稿）
4. 本文（调研综合与首页专项建议）

本文**不取代**既有 Liquid Glass 设计系统文档；它补齐：

- 对**当前首页截图与代码**的诊断
- 跨竞品 / Agent UX / ADA 的外部调研
- 近两年 Liquid Glass 仓库与 Agent Skills 清单
- **Amber 首页可执行改版建议**（蓝图与优先级）

---

## 1. 结论摘要

当前 Amber iOS 首页是「**功能齐全的会话列表 + 五宫格入口**」，不是「**安静、有辨识度的 iOS Agent 工作台**」。

离 Apple Design Award / 系统级品味的差距，**主要不在少贴一层 Liquid Glass**，而在：

| 轴 | 问题 | 方向 |
| --- | --- | --- |
| **原生轴** | 隐藏系统导航栏、内容区伪玻璃搜索、Material 式绿 FAB | 导航层用系统 Liquid Glass；内容层保持干净列表 |
| **Agent 轴** | 行信息只有标题/时间/条数；首屏不回答「继续什么 / 谁在跑」 | Continue 卡 + 进行中任务 + 意图入口，历史为第三层 |
| **身份轴** | 五等权彩虹宫格像小程序启动台，不像 Amber | 少入口、单 accent、能力语义进列表 |

**一句话产品定位（与 taste skill 一致）：**

> Amber iOS 应是安静、可操作的原生 Agent 工作台——**阅读面扎实，控制面可玻璃**；不是 marketing hero，也不是 ChatGPT 皮肤克隆。

**首页当前最伤「Design 推荐」的三件事：**

1. 五等权彩色快捷宫格
2. 右下角 Material 式绿色 FAB
3. 会话行缺乏 Agent 语义（类型 / 运行态 / 可继续性）

---

## 2. 现状诊断（代码 + 截图）

### 2.1 页面结构

入口：`AppShell` → `ConversationsView`（`iosApp/iosApp/PlaceholderViews.swift`）。

| 区域 | 实现要点 | 视觉角色 |
| --- | --- | --- |
| 顶栏 | 自定义大标题 “Amber” + 玻璃齿轮 / 头像；`.toolbar(.hidden)` | 品牌 + 设置 |
| 搜索 | 内容区玻璃 `TextField`「搜索会话」 | 过滤会话 |
| 快捷入口 | 五个等权入口：深度阅读 / 小应用 / 小说创作 / WebMount / 模型议会 | 功能启动台 |
| 列表 | 气泡 icon + 标题 + 相对时间 + 消息条数；当前会话淡 accent 底 | 历史线程 |
| 主 CTA | 右下角绿色玻璃 FAB（铅笔） | 新建对话 |
| 交互优点 | 原生 `List` + `swipeActions` + context menu + 生成中 ring | 已有好基础 |

### 2.2 已暴露问题

1. **层级塌缩**
   标题很大，但真正占注意的是彩色五宫格 + 长列表。首屏不优先「继续上次工作」。

2. **快捷区是 feature dump**
   五入口等权、11pt 中文标签、多色 SF Symbol，像微信小程序格，不像意图路由。无主次、无最近使用、无与当前上下文的关系。

3. **会话行信息过薄**
   仅标题 / 时间 / 条数。Agent 价值信号（是否生成中、小说/议会/WebMount、待审批、项目归属）几乎不进首页。生成中 ring 已有，但整体语义仍偏 IM 列表。

4. **Liquid Glass 用在「像玻璃」而非「导航层」**
   搜索、头像、设置、FAB 都在用 glass 语言；列表是 content。导航层与内容层边界模糊，又丢掉系统 toolbar 的 scroll-edge 一体感。

5. **FAB 偏 Android / Material**
   iOS 26 更常见 toolbar / bottom accessory 的 glass 主操作，而不是悬浮绿圆。

6. **隐藏系统 NavigationBar**
   可控细节，但牺牲系统 Liquid Glass 导航层的免费能力（材质、scroll edge、形态一致）。

7. **空状态与首启几乎无设计**
   空列表只有图标 +「还没有会话」。Agent 产品的 time-to-magic-moment 应发生在首页。

### 2.3 与既有设计系统的对齐度

[`IOS_LIQUID_GLASS_DESIGN_SYSTEM.md`](ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md) 已写明：

- Reading surfaces are solid；Control surfaces may be glass
- Conversation history 应用 `List` + `.searchable`
- 避免 Android Material FAB / 假设备框 / 全卡片玻璃

**当前首页实现部分违背了该稿**（内容区搜索玻璃、FAB、五宫格装饰感）。后续改版应以该稿 + 本文建议收敛，而不是再开第三套视觉语言。

---

## 3. 外部调研综合

### 3.1 Apple：Liquid Glass 硬约束

来源：Adopting Liquid Glass、HIG Materials、WWDC25 *Meet Liquid Glass* / *Get to know the new design system* / *Build a SwiftUI app with the new design*。

| 规则 | 含义 | 对 Amber 首页 |
| --- | --- | --- |
| Glass 属于**导航/控制层** | 浮在内容之上，帮助聚焦内容 | 设置、搜索 island、主 CTA 可玻璃 |
| **不要**把 glass 用在 content 层 | 列表、表格、媒体保持标准材质 | 会话行、快捷宫格内容面不加 glass |
| 避免 glass-on-glass | 叠层会脏、会抢焦点 | 搜索玻璃 + 设置玻璃 + FAB 同时大面积出现时需克制 |
| 系统组件优先 | 标准 bar / sheet / control 自动适配 | 尽量恢复 toolbar / searchable，少自定义 chrome |
| 列表样式服务内容 | 用分组与间距表达层级，不靠装饰 | 置顶 / 进行中 / 今天 等分组优于行内毛玻璃 |

### 3.2 Apple Design Award 评什么

近年 ADA 维度（Delight、Inclusivity、Innovation、Interaction、Social Impact、Visuals 等）共性：

- **Craft**：滚动、过渡、空状态、控件手感一致到「像系统自带」
- **清晰身份**：一打开就知道这是什么 App
- **平台深度**：Live Activity、Widget、Haptics、App Intents，而非贴一层皮肤
- **Interaction**：路径短、控件直觉、平台适配到位

Amber 的问题不是「不够玻璃」，而是「**不够像 Amber**」：通用 AI 会话壳 + 五个功能图标。

### 3.3 主流 AI Chat 首页范式

| 范式 | 代表 | 特征 | 对 Amber |
| --- | --- | --- | --- |
| Conversation-first | ChatGPT / Claude | 最近线程 + 新建；Projects/文件夹组织；移动端列表或 drawer | 历史层可学；但 Amber 有多工作区，不能只抄 |
| Discovery-heavy | Perplexity 倾向 | 发现内容 + 搜索，首页更挤 | 不适合生产力 Agent 主路径 |
| Time-to-magic-moment | 优秀 AI 产品共性 | 立刻输入 / 继续 / 看到价值路径 | 空状态与 Continue 必须设计 |

**结论：** 纯会话列表不够；五宫格 feature dump 也不是答案。更接近：

> **「继续工作」为第一层 + 「按意图进入能力」为第二层 + 「历史会话」为第三层**

### 3.4 Agent UX（2025–2026）

- **对话流 ≠ 活动流**。异步 agent 工作塞进同一条普通会话列表，既做不好聊天，也做不好监控。
- 需要可见的 **Activity / 运行态**（正在跑什么、能否干预）。
- 意图已知用结构化入口；意图模糊用对话。

Amber 已有后台生成、Live Activity、小说/议会等长任务。首页应能回答：

1. 有没有正在进行的 Agent 工作？
2. 我上次做到哪？
3. 我想开哪类工作？

### 3.5 高质量原生列表 App 共性

Mail / Notes / Music / Things 气质：内容占屏 80%+、chrome 极薄、分组与节奏优先于装饰、主操作可预测、空状态是教育时刻。WWDC 也强调复杂信息优先 `List`，靠 layout/grouping 表达层级。

---

## 4. 对标矩阵

| 维度 | 现状 | ChatGPT 系 | iOS 26 系统 | Agent 工作台目标 | ADA 级目标 |
| --- | --- | --- | --- | --- | --- |
| 首屏主角 | 五宫格 + 列表 | 新建/输入 + 历史 | 内容 + 浮层导航 | Continue + Activity | 一眼认得出产品 |
| 能力入口 | 5 等权图标 | 侧栏/命令 | 少而准的 toolbar | 意图卡 / 情境入口 | 有主次与叙事 |
| Glass | 内容搜索玻璃 + FAB | 克制 | 仅导航层 | 仅 chrome | 系统一致 |
| 会话语义 | 标题/时间/条数 | 标题/时间/项目 | 原生 List | +运行态/能力类型 | 信息密度刚好 |
| 新建 | 右下 FAB | 顶栏 + | toolbar/composer | 同左 | 毫不犹豫 |
| 空状态 | 弱 | 示例 prompt | 系统空态文案 | 引导首个 Agent 任务 | 有记忆点 |
| 个性 | 弱（通用 AI） | 品牌色轻 | 系统默认 | Amber 冷静工作台 | 有 signature |

---

## 5. Amber 首页建议（核心交付）

### 5.1 设计原则（首页专用）

1. **内容第一，chrome 第二**
   列表与 Continue 卡是主角；玻璃只服务搜索、设置、主 CTA。

2. **意图路由，不是 App 启动台**
   深度阅读 / 小说 / 议会等是「工作类型」，不是五个平权迷你 App。入口要有主次，默认不超过 3 个一级意图。

3. **Agent 语义进列表**
   行级至少表达：标题、时间、类型或状态；进行中任务优先于「N 条消息」。

4. **单 accent 体系**
   功能色只用于状态（运行中 / 失败 / 成功），不为五个入口各配一色。

5. **系统壳优先于手绘壳**
   能交给 `NavigationStack` toolbar、`.searchable`、`scrollEdgeEffect` 的，不重复发明。

6. **空状态 = 首启产品叙事**
   3 个 starter + 一句定位，而不是「还没有会话」。

### 5.2 Liquid Glass：允许 / 禁止

| 允许 | 禁止 |
| --- | --- |
| 顶栏设置 / 账户 glass 控件 | 会话行玻璃底 |
| 系统 toolbar / search island | 五个快捷入口都上 glass |
| 主 CTA `glass` / `glassProminent` | 内容层全屏 glass 背景 |
| Sheet / menu 系统材质 | glass-on-glass 堆叠 |
| 滚动内容从 glass 下穿过 | 用玻璃遮盖层级问题 |

> **Glass 是导航层的「手」，不是内容层的「皮肤」。**

### 5.3 信息架构建议

推荐默认结构（自上而下）：

```text
[ 导航层 glass：搜索 · 设置 · 账户 · 新建 ]
[ 主：Continue 卡 — 最近有意义会话 / 进行中 run ]
[ 次：Active agents — 0–N 个精简运行态（可与 Live Activity 同源） ]
[ 再：意图入口 — ≤3 个主意图；其余进「更多」]
[ 底：会话列表 — 置顶 / 进行中 / 今天 / 更早，或按能力轻分组 ]
```

能力与数据映射（产品语义，非强制 schema）：

| 用户意图 | 当前路由能力 | 首页呈现建议 |
| --- | --- | --- |
| 继续对话 / 新对话 | Chat | Continue + 新建（主 CTA） |
| 深度阅读 | Board | 二级意图或「更多」 |
| 小说创作 | Novel | 若近期有项目，可进 Continue 类型；入口二级或主意图之一 |
| 模型议会 | Council | 有进行中房间时进 Active；否则二级 |
| WebMount / 小应用 | WebMount / MiniApps | 默认「更多」，避免占首屏 |

### 5.4 三套蓝图

#### 蓝图 1 — 最小改动、最大收益（建议先做）

- 保留 `List` + swipeActions + context menu
- 五宫格 → 最多 3 个 intent chips（或 icon-only + a11y label）
- FAB → toolbar trailing / bottom accessory 新建
- 搜索 → `.searchable` 或导航层 search island
- 会话行加类型 tag + 强化 generating 状态
- 空状态加强

**目标：** 从「通用 AI 壳」变成「可信的 iOS 原生列表 App」。

#### 蓝图 2 — Agent 工作台（中期）

- 顶部 Continue + Active runs
- 中部 Intent
- 下部会话分组
- 进行中任务与 Live Activity / agent activity 同源

**目标：** 体现 Amber 差异化，而不是追 ChatGPT 皮肤。

#### 蓝图 3 — Composer-as-Home（实验）

- 打开即大输入框 + suggestion chips
- 历史与工具二级化
- 适合重度对话用户；**不宜直接替换**多工作区发现路径
- 可作为 A/B 或设置项，不作为默认唯一形态

### 5.5 组件级建议

#### 顶栏

- 优先恢复系统 navigation chrome，或在自定义 chrome 中严格模拟「导航层」：少背景、glass 仅控件。
- 大标题 “Amber” 可保留，但下方不得紧贴彩虹宫格；标题与内容之间的节奏要有意设计。
- 设置与账户保持一组相关 glass 控件（`GlassEffectContainer`）。

#### 搜索

- 从列表第 0 行内容卡片，迁到 **系统 searchable** 或 **导航层 island**。
- 搜索结果页（已有 `SearchView`）继续承担会话 + 消息检索；首页搜索框不重复造第二套视觉语言。

#### 快捷入口

- **删掉五等权彩虹宫格**。
- 方案 A：2–3 个水平 intent chip（次要表面，非 glass）。
- 方案 B：主路径只保留「新对话」，其他全部进「工具」sheet。
- 颜色：默认 foreground / muted；accent 仅选中或主意图。

#### 会话行

| 元素 | 建议 |
| --- | --- |
| 主文案 | 标题（空则「新对话」） |
| 副文案 | 时间 · 预览一句 **或** 时间 · 条数（二选一为主，避免三列噪音） |
| 类型 | 小说 / 议会 / 深度阅读等轻 tag 或不同 leading symbol |
| 状态 | 生成中：行尾 live pill 或强化 leading ring；失败可微红点 |
| 当前会话 | 可保留淡 accent，降低描边存在感，避免「选中卡片」过重 |
| 操作 | 保持 swipe + context menu（已有优点） |

#### 新建 CTA

- 去掉右下角悬浮绿球。
- 使用 toolbar `glassProminent` 或底部 safe-area glass accessory。
- 无障碍标签保持「新建聊天」。

#### 空状态

- 标题：一句话产品定位（例如「你的 iOS Agent 工作台」——最终文案需本地化评审）。
- 3 个 starter（新对话 / 深度阅读 / 小说等按产品优先级）。
- 搜索无结果与真正空列表要分开文案。

#### 动效

- Spring：进入会话、Continue 出现、运行态变化。
- **不要**对列表过滤、高频状态刷新使用 spring。
- 与 taste skill 一致：流式正文高度变化禁止 spring。

#### 无障碍

- 11pt 五标签在 Dynamic Type 下会崩；减数量或 icon-only。
- 触控目标 ≥ 44pt（FAB 视觉可小于 hit area 的模式仅用于明确胶囊控件，不用于主新建）。
- 生成中、置顶状态进入 `accessibilityLabel`（部分已有，需随类型 tag 扩展）。

### 5.6 优先级清单

#### P0 — 立刻改变「廉价感」

1. 杀掉五等权彩虹宫格（≤3 意图或「更多」）。
2. FAB → 系统主操作。
3. 搜索进导航层。
4. 会话行升级为 Agent 摘要（类型 + 运行态）。

#### P1 — 结构升级

5. Continue 卡。
6. 列表分组（置顶 / 进行中 / 今天 / 更早）。
7. 空状态 = 首启体验。

#### P2 — ADA 级细节

8. 字阶与节奏（大标题 vs 列表密度）。
9. Motion 纪律。
10. Live Activity / Widget / App Intents 与首页 Active 同源。
11. 暗色、对比度、Dynamic Type 验收。

### 5.7 实现落点（代码地图）

| 改动 | 主要文件 / 区域 |
| --- | --- |
| 首页结构 | `PlaceholderViews.swift` → `ConversationsView` |
| 会话存储与摘要 | `IOSConversationStore`、`ConversationSummary` |
| 生成中态 | `ChatViewModel.isGenerationActive`、background job 通知 |
| 导航 | `AppShell`、`RouterPath`、destination 路由 |
| 主题 / glass helper | `AmberTheme`、既有 `amberGlass` / `amberProminentGlass` |
| 品味门禁 | `.agents/skills/amberagent-ios-taste` |
| 玻璃实现规范 | `docs/ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md` |

**约束（来自工程习惯）：**

- 一次一个小闭环：蓝图 1 先于蓝图 2。
- 不借改首页重写 Chat timeline / 小说状态机。
- 与其他脏工作区改动重叠时，先读单文件 diff。
- 视觉结论需截图或真机证据；单测绿 ≠ 首页品味通过。

### 5.8 验收标准（可证伪）

**蓝图 1 完成当且仅当：**

1. 首页无五等权彩虹宫格；一级意图 ≤ 3。
2. 无 Material 式右下角 FAB；新建在导航层/系统位置。
3. 搜索不在内容层伪装成卡片玻璃。
4. 会话行可见类型或运行态之一；生成中态可扫读。
5. 空状态提供至少 2 个可点 starter。
6. 亮色 / 暗色下内容与 chrome 层级分明；列表行无 glass。
7. Dynamic Type 最大档位下主要控件仍可用。
8. 既有 swipe 删除 / 重命名 / 置顶仍可用。

**蓝图 2 额外：**

9. 有进行中任务时，首页在首屏 1.5 屏内可见 Active / Continue。
10. Continue 一键进入正确会话或工作区，不串线。

---

## 6. 参考资源清单

### 6.1 官方（必读）

| 资源 | 用途 |
| --- | --- |
| [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass) | 采用路线 |
| [Liquid Glass overview](https://developer.apple.com/documentation/technologyoverviews/liquid-glass) | 语言总览 |
| [HIG · Materials](https://developer.apple.com/design/human-interface-guidelines/materials) | glass 边界硬规则 |
| [Landmarks: Building an app with Liquid Glass](https://developer.apple.com/documentation/swiftui/landmarks-building-an-app-with-liquid-glass) | 官方 sample |
| WWDC25 219 / 356 / 323 | 原则 + 系统 + SwiftUI API |
| [Apple Design Resources](https://developer.apple.com/design/resources/) | Figma/Sketch 模板 |
| [Apple Design Awards](https://developer.apple.com/design/awards/) | Craft / Interaction 标杆 |

### 6.2 GitHub 仓库（按优先级）

**Tier S**

| 仓库 | 用途 |
| --- | --- |
| Apple Landmarks (Liquid Glass sample) | 结构正确、系统 API 权威 |
| [conorluddy/LiquidGlassReference](https://github.com/conorluddy/LiquidGlassReference) | API / 原则速查手册 |
| [GetStream/awesome-liquid-glass](https://github.com/GetStream/awesome-liquid-glass) | 导航层组件与动画灵感 |
| [AvdLee/SwiftUI-Agent-Skill](https://github.com/AvdLee/SwiftUI-Agent-Skill) | SwiftUI + glass 工程化 skill |

**Tier A**

| 仓库 | 用途 |
| --- | --- |
| [mertozseven/LiquidGlassSwiftUI](https://github.com/mertozseven/LiquidGlassSwiftUI) | 小型 demo |
| [GonzaloFuentes28/LiquidGlassCheatsheet](https://github.com/GonzaloFuentes28/LiquidGlassCheatsheet) | 速查 |
| [donnywals/LiquidPath](https://github.com/donnywals/LiquidPath) | 自定义 glass 控件边界 |
| [StewartLynch/Liquid-Glass-Controls-And-Views](https://github.com/StewartLynch/Liquid-Glass-Controls-And-Views) | 控件与转场练习 |
| [sanjaynela/liquid-glass-ios-system](https://github.com/sanjaynela/liquid-glass-ios-system) | 迷你 design system 组织方式 |
| [artemnovichkov/iOS-26-by-Examples](https://github.com/artemnovichkov/iOS-26-by-Examples) | iOS 26 API 合集 |

**刻意少看：** Web Liquid Glass、全页 glassmorphism 模板、列表/卡片全糊玻璃的 demo——与 Apple 规则及 Amber taste 冲突。

### 6.3 Agent Skills

**本机 / 本仓库已有（改首页推荐组合）**

| Skill | 用途 |
| --- | --- |
| `amberagent-ios-taste` | 品味门禁：层级、白底、composer、反滥用玻璃 |
| `swiftui-liquid-glass` | 原生 glass API、container、fallback |
| `swiftui-ui-patterns` | 列表 / sheet / 导航结构 |
| `swiftui-view-refactor` | 大 View 拆分 |
| `swiftui-performance-audit` | 玻璃 + 滚动性能 |
| `swiftui-visual-tips` / `swiftui-microinteractions` | 视觉与微交互细节 |
| `ios-simulator-browser` | 预览与迭代验证 |

**社区可选（防 API 幻觉，二选一即可）**

| 资源 | 用途 |
| --- | --- |
| [Dimillian/Skills · swiftui-liquid-glass](https://github.com/Dimillian/Skills) | 原生 glass skill |
| [haider-nawaz/liquid-glass-skill](https://github.com/haider-nawaz/liquid-glass-skill) | 迁移 / 反幻觉 |
| [rshankras/claude-code-apple-skills](https://github.com/rshankras/claude-code-apple-skills) | `design/liquid-glass` + 排版/符号等 |

**裁决顺序：** Apple HIG + `amberagent-ios-taste` > 社区 skill 文案。

### 6.4 设计阅读（非代码）

| 资源 | 用途 |
| --- | --- |
| Donny Wals · Designing custom UI with Liquid Glass | 自定义控件边界 |
| createwithswift · Liquid Glass / Hierarchy | 原则 |
| learnui.design · iOS 26 illustrated guidelines | tab inset、search island、list 不加 glass |
| Mobbin · ChatGPT / Claude / 系统 App | 真实产品对照 |
| Agent UX 文献（活动流 vs 对话流） | Continue / Active 结构依据 |

### 6.5 Amber 最短参考路径

1. HIG Materials + Adopting Liquid Glass
2. Landmarks sample
3. `LiquidGlassReference`
4. `awesome-liquid-glass`（只抄导航层）
5. 本机 `swiftui-liquid-glass` + 仓库 `amberagent-ios-taste` + `swiftui-ui-patterns`
6. 本文第 5 节蓝图 1

---

## 7. 建议的实施顺序

```text
Phase 0  对齐：通读本文 §5 + IOS_LIQUID_GLASS_DESIGN_SYSTEM，确认不新开视觉语言
Phase 1  蓝图 1：入口收敛 + FAB + 搜索导航层 + 行语义 + 空状态
Phase 2  真机 / 截图验收 §5.8 条款 1–8
Phase 3  蓝图 2：Continue + Active + 分组（需数据与 Live Activity 契约）
Phase 4  平台深度：App Intents / Widget 与首页 Active 同源（可选）
```

**不做：**

- 用 Web glass 效果重画首页
- 会话行全面 glassmorphism
- 为「更 Apple」重写 Chat / 小说状态机
- 在脏工作区无隔离地大改共享 theme 而未扫用法

---

## 8. 开放问题（实施前需产品拍板）

1. 一级意图默认保留哪 2–3 个？（建议：新对话恒在主 CTA；小说 vs 深度阅读 vs 议会按真实使用数据选）
2. Continue 卡数据源：最近会话 vs 最近「有正文变更」的会话 vs 进行中 run 优先？
3. 小说 / 议会是否与普通 Chat 混排，还是分组 / 分 tab？
4. 是否允许用户把首页设为 Composer-first（蓝图 3）？
5. iOS 版本底线：glass API 仅 26+ 时，旧系统 fallback 是否已产品接受？

---

## 9. 文档维护

- 本文状态为 **Proposed research**。蓝图 1 落地并验收后，应：
  - 将首页结构结论回写或收敛进 `IOS_LIQUID_GLASS_DESIGN_SYSTEM.md`
  - 更新 `PROJECT_STATE.md` 中与 UI 相关的当前事实（若成为工作主线）
  - 视需要把本文标为 `Superseded` 或降为历史调研
- 不在本文追加会话日记；实现证据以 PR / 截图 / 真机验收为准。

---

## 附录 A — 调研来源类型

| 类型 | 内容 |
| --- | --- |
| 代码证据 | `ConversationsView`、`AppShell`、session 截图 |
| 官方 | Apple HIG、Adopting Liquid Glass、WWDC25、Landmarks、ADA |
| 竞品 / 行业 | ChatGPT / Claude / Perplexity 模式；Agent UX 活动流分离 |
| 社区工程 | LiquidGlassReference、awesome-liquid-glass、Donny Wals 等 |
| 仓库内规范 | amberagent-ios-taste、IOS_LIQUID_GLASS_DESIGN_SYSTEM |

## 附录 B — 与上一轮对话的映射

| 对话产出 | 本文位置 |
| --- | --- |
| 首页截图诊断与 P0–P2 清单 | §2、§5.5–5.6 |
| 竞品 / ADA / Agent UX 调研 | §3、§4 |
| 三套蓝图 | §5.4 |
| Liquid Glass 允许/禁止 | §5.2 |
| 仓库与 Skills 清单 | §6 |
| Amber 最短参考路径 | §6.5 |
