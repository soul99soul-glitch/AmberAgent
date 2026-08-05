# AmberAgent iOS — UI 全量审查报告

> 日期：2026-06-15 · 分支：`codex/ios-port-wip` · 范围：`iosApp/iosApp/*.swift`（38 屏原型对应的全部 SwiftUI 实现）
> 权威优先级：`DESIGN_SYSTEM.md` > `index.html` 原型 > `REDESIGN_DELTAS.md` > Android 源码。视觉以原型为准，逻辑/行为以 Android 源码为准。
> 方法：先读全部设计文档（DESIGN_SYSTEM / SCREEN_INVENTORY / HANDOFF / REDESIGN_DELTAS 71 条 CD / iOS Liquid Glass 设计系统），再读全部 View 源码（含 5 路并行子审查 + 主线对安全/接线关键断言的逐条复核）。
> **本阶段只输出报告，不改任何代码。**

---

## 1. Executive Summary

### 当前 UI 完成度

| 维度 | 完成度 | 说明 |
|---|---:|---|
| 视觉还原（对原型保真度） | **~82%** | 令牌与 `:root` 逐字一致；玻璃只用于 chrome；38 屏结构、分组、文案高保真；两条硬规则基本落实。 |
| Apple/iOS 原生度与可访问性 | **~55%** | 全量手搓 `AmberFormGroup`/自定义 switch 取代原生 `Form`/`List`/`Toggle`，丢掉 Dynamic Type、深色模式、滑动操作、原生手势。 |
| 真实能力接线（vs 应接） | **~18%** | 仅 4 个面真实：能力门控/权限请求、Remote SSH 运行时、默认聊天模型、旧版 Provider 配置。其余按 CD 有意停在草稿。 |
| **综合"可交付 UI"** | **~50%** | 看着像做完了，但离能上手的产品还有约一半，瓶颈不在像素，而在接线 + 可访问性 + 诚实度。 |

一句话结论：**视觉工程做得相当扎实且有纪律**（fake 动作大多有 `尚未接线` alert 自陈，CD 决策被严格执行，Skills 不混入 SubAgent、`显示席位输出` 已删除、灵动岛取代 Live 伴随等硬约束全部命中）。**真正的差距是三类系统性问题：原生结构缺失导致的可访问性/深色模式不可交付、若干"看着是真的、其实什么都没接"的假入口、以及孤儿路由与死路。**

### 最大的 5 个问题

1. **全量自定义表单架构，放弃原生 `Form`/`List`/`Toggle`。**
   `AmberTheme` 是硬编码浅色 `enum`（`PlaceholderViews.swift:3-27`），所有屏用 `ScrollView` + 手搓行 + 固定 `.system(size:)` 字号 + 固定 `frame(minHeight:)`。后果：(a) **Dynamic Type 不生效**；(b) **深色模式结构上不可交付**——`AppearanceSettingsView` 的"深色/跟随系统"能选中、能持久化，却永远不改变任何东西（CD-23 在当前主题层下无法落地）；(c) **无原生滑动删除/重排/下拉刷新**，但 MCP/记忆/技能等多处文案写着"左滑删除""下拉重连"，承诺了 UI 给不了的手势；(d) 5 套近似重复的自定义 switch（48×28，小于系统 51×31，旋钮色不一致）。

2. **Chat 的模型/思考/上下文控件是装饰，未接进真实请求（用户可感知的正确性 bug）。**
   选模型只改 chip 文案（`ChatView.swift:46→282`），而 `ChatViewModel.makeTextGenerationParams()`（`ChatViewModel.swift:352`）永远用 `settingsStore.modelId`、`reasoningLevel` 硬编码 `.off`（`:373`）。思考等级面板、上下文环（硬编码 `2.3%`、`23,450/1,000,000`）都不被任何请求读取。用户选了"GPT-5 Codex"、调了思考档，实际发出去的还是设置里的模型、关闭推理。

3. **遍布"示例数据当真实状态"的假入口，且多在安全相邻面上无任何"示例"提示。**
   MCP `已连接 · 12 个工具`（无 `McpManager`）、WebMount `已登录 / 限流中`（无 cookie/WebView）、今日看板 `刚刚更新 · 由 Amber 整理` 的绿色实时点、账户页统计（热力图/总会话/Token）、Provider 详情眼睛图标揭示一个**假的 `sk-…` key**。这些都把静态样例呈现为真实运行态。

4. **凭据面要么静默丢弃、要么文案过度承诺。**
   `ProviderAddView` 的"完成"把 `SecureField` 里键入的 API Key 直接 `dismiss()` 丢弃、无保存无提示（`ProvidersView.swift:250`）；`SearchProviderView`/`TTSSettingsView` 的"API Key 存入 Keychain"文案在没有任何保存动作的页面上出现；TTS"试听"是假播放器、无提示。

5. **真实能力被孤立 + 孤儿路由/死路。**
   `ToolPermissionsView`（CD-69 重写）把原型的 **Selected File Grant 控制面（选择文件→读取一次→清除授权）删掉了**，后端 `DocumentAccessStore.clearGrant()`（`:123`）还活着且工具层可达，但**用户无法在 UI 里撤销一个内存态文件授权**——真实安全面上的控制缺口。首页"最近"5 条里 4 条点击落到空的 `ContentUnavailableView`（`PlaceholderViews.swift:364`→`.conversation(id:)`）；`.modelPicker` sheet、`.workspace` 路由、`WorkspaceView`/`AssistantsView`/`AppTab`/`TabRouter` 已无人引用，是死脚手架。

### 最值得优先修的 5 个点（高价值、低风险）

1. **Chat composer 诚实化/正确化**：把 `previewModel` + `selectedThinkingLevel` 接进 `makeTextGenerationParams()`，或明确标注"仅预览"，并把上下文环改为真实 `usage` 驱动或标为占位。— 唯一的功能正确性问题。
2. **内容层暖色修正**：代码块 `systemGray6 → AmberTheme.surface`、圆角 `8 → 12`，行内代码补底色 pill（`MarkdownView.swift:125`）。— 最显眼的内容层视觉缺陷，2 行改动。
3. **"示例当真实"诚实化扫一遍**：MCP/WebMount/Board/Account/Provider 详情 key/ProviderAdd/SearchProvider·TTS Keychain 文案，加"示例"标记或 reset 到非登录态或软化文案。— 纯文案/初值，低风险。
4. **ToolPermissions 补"清除授权"动作**：当 `documentStore.grantSummary != nil` 时提供撤销入口，调用已存在的 `clearGrant()`。— 真实安全面的控制缺口。
5. **修死路**：首页样例会话行不要落到空 `ContentUnavailableView`；移除/禁用 `.modelPicker`、`.workspace` 孤儿路由与死脚手架。— 破损导航。

---

## 2. Screen-by-screen Audit

> 字段：现状 / 与原型差异 / 与 Apple 差异 / 视觉细节 / 孤儿或占位（接线分类 A=应现在接 · B=草稿合理 · C=危险假入口）/ 建议改法 / 优先级。
> 全屏共性问题（自定义表单架构、硬编码主题、自定义 switch 偏小、nav-title 偏大）见 §4，不在每屏重复。

### 2.1 Home / Sessions 首页 — `ConversationsView`（`PlaceholderViews.swift:205-369`，原型 `#conversations`，CD-15/16/60/67/68）
- **现状**：大标题 + 设置/账户 glass 圆钮 + 搜索框 + 5 快捷入口固定轨道 + "最近"样例会话 + 陶土 FAB。视觉与 `renders/conversations.png` 高保真。
- **与原型差异**：忠实。快捷入口已按 CD-67 固定 54pt 轨道与搜索框对齐；"工作区"已按 CD-68 指向 `.sandbox`。
- **与 Apple 差异**：整屏 `ScrollView` 手搓而非 `List` + `.searchable`；搜索是一个**假按钮**（跳到自定义覆盖层，非系统搜索）——这是 CD-19 有意决定，可接受但失去原生搜索行为。
- **视觉细节**：FAB 用 `pencil` 图标，原型/设计建议 `square.and.pencil`；阴影双层得体。
- **孤儿/占位**：**C（死路）**——"最近"5 条里只有"iOS…"行进 `.chat`，其余 4 条进 `.conversation(id:)` → 空 `ContentUnavailableView`（`:364`、`AppShell.swift:336`）。样例数据本身是 B（CD-16），但点击落空是真实破损。
- **建议改法**：样例会话行统一落到 `.chat`（或一个静态样例会话详情），不要落到空占位；真实会话接入前别让用户点空。
- **优先级**：**P1**

### 2.2 Chat 工作区 — `ChatView` / `ChatViewModel` / `MessageBubbleView` / `MarkdownView`（原型 `#chat`，CD-18/62/63/64）
- **现状**：CD-18 空态样例 transcript（不写 VM）；CD-62 逐条 `toolStep` + 整行思考折叠 + 实体助手文本；CD-63 小上下文环 + idle 移除 meta 行；CD-64 大 `ComposerModelSheet`。真实 `ChatViewModel` 驱动发送/流式/取消 + 选中文件 preview 附加。玻璃只在 composer/sheet/popover，内容层实心——结构高质量。
- **与原型差异**：助手名硬编码 `"Amber"`（`ChatView.swift:793`）；每个工具 part 各自包一个单步 `ChatToolTimeline`（`MessageBubbleView.swift:49`），多次工具调用渲染成断开的单行块而非连续时间线；真实消息无 subagent 头像变体。
- **与 Apple 差异**：(a) **流式不自动滚动**——只监听 `messages.count`（`ChatView.swift:102`），同一条消息 token 增长时不跟随到底；(b) `+`/发送/停止触控区 28-32pt，**低于 44pt**（`:155/176/187`）；(c) 思考折叠是手搓 `DisclosureGroup`，缺 `.accessibilityValue`。
- **视觉细节**：代码块用 `Color(.systemGray6)` + 圆角 8（`MarkdownView.swift:125`）——**唯一一处冷灰泄漏进暖色内容层**，应为 `AmberTheme.surface` + 圆角 12；行内代码无底色 pill；标题用裸点数（28/24/20…）而非 Dynamic Type 文本样式。固定气泡最大宽 296/312pt 不随字号缩放。
- **孤儿/占位**：**C**——模型选择器（`ChatView.swift:46/282` vs `ChatViewModel.swift:352-366`）chip 变但请求不变；思考等级写 `selectedThinkingLevel` 但请求硬编码 `.off`（`:373`）；上下文环 `2.3%`/`23,450/1,000,000` 是写死样例当实时；provider 列表是 `ComposerProviderGroup.defaults` 里**虚构的、与用户已配置 provider 无关的模型**。CD-64 只说"不写 SettingsStore"，但"chip 变请求忽略"是 bug 不是 CD。**P0/P1 危险**：新建会话按钮 `viewModel.messages.removeAll()`（`:72-76`）**无确认直接清空**当前 transcript。
- **建议改法**：把选中的 model id / 思考档接进 `makeTextGenerationParams()`，或加一行"仅预览"caption；上下文环接真实 `usage` 或显式标占位；provider 列表从已配置 provider 取；新建会话加确认或新开 thread 而非原地清空；代码块两行暖色修正；`+`/发送/停止 frame 提到 44×44；流式滚动改为监听内容长度；工具 `.failed` 态（`ChatView.swift:983-990` 当前永远推不出 failed，真实失败显示为永久"运行中"）补上。
- **优先级**：模型/思考接线 **P0**；新建会话无确认 **P1**；代码块暖色 **P1**；触控区/流式滚动/工具 failed **P1**；上下文环占位 **P2**

### 2.3 我的账户与统计 — `AccountView`（原型无，CD-56 生成）
- **现状**：头像 + 昵称编辑 + 统计面板（21×7 `Canvas` 热力图 + 总会话/总消息 + Token/缓存/启动明细）。
- **与 Apple 差异**：**直接撞 DESIGN_SYSTEM 的"设置内不要 dashboard/统计卡"规则**——CD-56 又明确要这个强调色统计面板。这是设计系统与 CD 的内部冲突（见 QUESTIONS）。
- **视觉细节**：热力图魔数（`cellSize 12.3`、`cellGap 3.5`，`AccountView.swift:295`）不抗 Dynamic Type、窄屏会切月份轴；明细用 `.monospaced`、概览用 `.rounded`，同面板数字风格不一；多处 `minimumScaleFactor(0.76~0.82)` 说明字号已偏紧。
- **孤儿/占位**：**C**——统计全为写死样例（总会话 34、Token 2.60M、热力图活跃度…），**全屏无任何"示例"提示**，是本簇唯一把假统计当真实展示且无暗示的地方。昵称 `@State`（`:6`）无持久化，导航后丢失。
- **建议改法**：统计区加一行"示例数据 · 未接入真实统计"（仿 `StorageNote`/`SyncNote`）；昵称用 `@AppStorage` 持久化；与设计 owner 确认"设置内统计面板"的规则冲突如何裁决。
- **优先级**：**P1**（假统计无提示是本簇最高严重度的诚实度问题）

### 2.4 设置首页 — `SettingsHomeView`（`PlaceholderViews.swift:771-892`，原型 `#settings`，CD-17/60）
- **现状**：暖纸背景 + glass 返回 + 5 分组 Form（通用/Agent 运行时/模型与服务/数据/实验性）+ **彩虹 leading 图标循环**（有意设计，正确保留）+ trailing value/chevron。
- **与原型差异**：忠实。
- **与 Apple 差异**：手搓而非原生 `Form`；分组结构、行高节奏对路。
- **孤儿/占位**：**C（轻）**——trailing 值写死且与目标页不自洽："服务商 10 个"（目标页列的是 已启用+停用）、"搜索服务 4 个源"（目标页是 6 内置+2 配置）、"语音 TTS MiniMax"、"对话存储 128 个 · 24 MB"。看着是实时注册表计数，其实是常量。
- **建议改法**：trailing 值要么接真实计数，要么去掉，避免与目标页打架。
- **优先级**：**P3**

### 2.5 服务商与模型

**`ProvidersView`（原型 `#providers`，CD-34/38）** — 字母字标行（无大头像，正确）+ 两分组 + 绿点状态。**搜索胶囊是装饰**（`ProvidersView.swift:79-105`，静态 HStack，点了无反应）。endpoint 字号随 enabled 在 11/12pt + mono/default 间切换，不一致。`B` 行数据 / `C` 假搜索框。→ 把搜索框做成真 `TextField` 过滤或先撤掉；统一 endpoint 字体。**P2**。

**`ProviderDetailView`（原型 `#provider-detail`，CD-34/38/10）** — 配置/模型双 tab；"名称/API 地址"进旧版 `.providerSettings`（真实 Base URL/Key/Model）。**问题**：(1) **详情子页里套分段 tab**——设计系统明确反对、CD-10 为 modelEdit 专门改成单页 Form 来避免，这里却保留（原型 vs 设计系统冲突，见 QUESTIONS）；(2) **C**——眼睛图标揭示的是硬编码假 `sk-cdd7f4a2e8b91c6e`（`:195`），不是真实 Keychain key，用户会以为看到了真 key；余额 `¥48.20`（`:138`）样例当真实；(3) 保存按钮宽度钉死 58pt，大字号会切。→ 假 key 改中性占位或读真实掩码；放开 58pt；分段 tab 去留请裁决。**P1**（假 key 揭示）。

**`ModelEditView` / `ModelCustomFieldsView`（原型 `#modelEdit`，CD-10/39/57/59）** — 单页分组 Form（命中 CD-10 反分段），chip 多选、内置工具仅 Gemini、Headers/Body 子编辑器带预览校验。**问题**：原型 Headers/Body 显示"0 个"，iOS 硬编码"2 个"（`ModelEditView.swift:213/221`）且预置两条样例字段——**添加模式下新模型也显示"2 个"已存在 Headers**，明显 sample-as-real。→ 徽标由实际字段数驱动、add 模式起始为空。`C(轻)`。**P2**。

**`ModelDefaultsView`（原型 `#modelDefaults`，CD-41）** — **聊天模型行真实读写 `settingsStore.modelId`（`:96-99/243-245`）= A，本簇唯一真接线**；其余辅助任务/思考预算/上下文为本地 `@State`。**问题**：**indigo 误用**——四个辅助任务行图标用 `accentIndigo`（`:230`）做**装饰性分类色**，违反"indigo 仅限模型/选中"；而真正的"聊天模型"行用的是 accent 不是 indigo，语义被反转。→ 辅助行去 indigo 用 muted/accent。`B`。**P2**。

**`SearchServicesView` / `SearchProviderView`（原型 `#searchSvc`/`#searchProvider`，CD-42/43）** — 多源 UI + 内置源开关（用**原生 `Toggle`**，难得）+ 已配置 + 结果数量 + 推荐组合；启用计数比原型更好（实时算）。**问题**：(1) `+`/已配置行进 `SearchProviderView` **不传 provider 身份**（`SearchServicesView.swift:128`），点 Tavily 打开的是 Serper 默认草稿——孤儿路由；(2) **C**——`SearchProviderView` 的 `providerType.note` 文案"API Key 存入 Keychain；留空保存不覆盖"，但"完成"只 `dismiss()`（`:55-66`），`apiKey` 初值 `"········"` 像已存 key——凭据面文案过度承诺。→ 传 provider 身份；软化 Keychain 文案为"本地草稿，不写 Keychain"；清空假掩码初值。**P1**（凭据面误导文案）/ **P2**（身份路由）。

**`TTSSettingsView` / `TTSAddView`（原型 `#ttsSettings`，CD-11/40/71）** — 单选引擎列表（**checkmark 标记选中，比原型纯色更 HIG**）+ 分类型内联参数 + 删除（系统 TTS 保护）+ 添加草稿页。**问题**：**C**——"试听"是假播放器（`:181-209`，仅切 `isPreviewing`），且**无任何"本地状态"提示**（不像 `TTSAddView:303` 有诚实免责）；同样的"API Key 存入 Keychain"文案出现在一个**根本没有保存按钮**的页面（参数纯 `@State`，返回即失）。→ 试听下加"未实际合成"提示；软化 Keychain 文案。**P1**。

### 2.6 TTS 设置与添加引擎
见 §2.5 末。结论：列表/单选/分类型字段视觉到位，**接线全为草稿（B，CD-40/71）**，但试听假播放 + Keychain 文案是需要修的诚实度问题（C）。

### 2.7 技能 / 导入技能 / MCP 服务器（CD-9/30/31/32/65/66）
- **`SkillsView` / `SkillDetailView` / `SkillAddView`**：已安装/扩展/管理三组，**严格只列工具/任务技能、零 subagent 角色泄漏（硬约束命中）**；导入技能小面板（URL/文件，命中 CD-66）；所有 fake 动作有诚实"当前不会…"alert。视觉/接线均健康。`B`。**P3**。
- **`McpServersView` / `McpImportView` / `McpAddView`（原型 `#mcpServers`）**：`{ }` 字标（非 emoji，命中）、传输/状态/工具数 pills、导入 JSON + 手动添加草稿页。**问题**：(1) **C 危险**——3 个样例服务器显示 `已连接 · 12 个工具`（`:74-109`）+ `@AppStorage` 启用开关，暗示一个不存在的活连接（无 `McpManager`），诚实免责只在*添加*草稿页、不在*已连接*列表；(2) `FlowPills` 是固定 `HStack`（`:475`）不换行，大字号/窄屏 3 个 pill 会溢出；(3) 页脚"左滑删除/下拉重连"承诺自定义列表给不了的手势。→ 列表段加"示例服务器"标记或把"已连接"降为"示例"pill；pill 换行/缩放；软化手势文案。**P1**（假连接状态）。

### 2.8 SubAgent / 角色（CD-3/49/50/61）
- **`SubAgentsView` / `SubAgentRoleView`**：启用 + 固定/智能模式分段 + 动态角色 + 限制（输出上限 标准/加长，命中 CD-3）+ 内置/自定义角色；SubAgent 用 `person.2`、Council 用双气泡（命中 CD-61）；角色详情 model 行用 `accentIndigo`（正确，这是模型选择行）。智能模式下强制动态角色 ON+禁用，逻辑正确。`B`，接线全草稿且诚实。**P3**。

### 2.9 模型议会（CD-1/2/3/4/46/47/48，Q-1）
- **`CouncilView`（原型 `#council`）**：host-led 群聊样例 transcript、模式菜单、成员/原始/综合 bottom sheet、玻璃输入栏（合法的第二个 composer 玻璃位）。顶部用渐变模糊 fade 而非硬边线（命中硬规则 1），气泡实心（无玻璃叠玻璃）。**问题**：停止按钮 + 流式光标在静态 fixture 上演"运行中"的议会（`:257-265`），无真实 `ModelCouncilManager`——CD-46 有意草稿，但呈现为正在跑。`C(soft)`。**P2**（接线时再 gate）。
- **`CouncilSettingsView`（原型 `#councilSettings`）**：**显式核验通过**——`显示席位输出` 开关**确实已删除**（命中 CD-2）；核心三席段标"辩论模式自动加入"+ 注脚（命中 CD-1）；输出上限只有 标准/加长（命中 CD-3）；用**原生 `Toggle`**。`B`。**P3**。
- **`SeatEditorView`（原型 `#seatEditor`）**：Provider 模型/外部 CLI 分段——这里的分段是合法的"二选一运行方式"切换（非导航式子内容 tab），可接受。`B`。**P3**。
- **Q-1 张力（仅标记）**：Council transcript 的 host 产出"带证据的最终综合"，而 `CouncilSettingsView` 另有"综合模型"行也声称"给出最终结论"——即已知的 judge vs 综合模型职责重叠（REDESIGN_DELTAS Q-1）。待产品裁决，不提改法。

### 2.10 权限与能力 / 权限与批准（CD-7/20/21/69/70）
- **`ToolPermissionsView` = `.capabilities`（真实接线）**：常用权限/个人数据/设备与网络分组的系统权限入口，行点击走真实 `IOSSystemPermissionCoordinator.request/refreshStatus/openAppSettings`，文件行走真实 `DocumentAccessStore` + 系统选择器；状态用小 SF Symbol（命中 CD-70）。**问题**：**P1 真实安全控制缺口**——CD-69 重写把原型 `#capabilities` 的 **Selected File Grant 控制面（选择文件→读取一次→清除授权 + 用量/过期/digest）整段删掉了**，后端 `clearGrant()`（`DocumentAccessStore.swift:123`）+ `grantSummary`（`:65`）还活着且被本视图读取展示（`ToolPermissionsView.swift:137/264`），但**用户无法在 UI 撤销内存态授权**。另：每行都有状态图标 + 尾部 chevron，但 `.unavailable`/`.denied`/`.selectedFile` 行其实不进二级页，chevron 误导。→ `grantSummary != nil` 时提供"清除授权"动作（confirmationDialog：重新选择/清除）；不可导航的行去掉 chevron。（去掉 digest 表是对的，dev-facing，别加回。）**P1**。
- **`PermissionsApprovalView` = `.toolPermissions`（真实接线，已主线复核）**：实际实现是**真实的逐能力策略行**——`permissionStore.policy/availablePolicies/setPolicy/decisionSummary`（`IOSPermissionStore`），执行器 `IOSLocalToolExecutor.swift:150` 读取策略；平台门控 `allowGlobalAutoApproval` 在 `IOSPermissionModels.swift:1638` 独立生效、不可被绕过；不可选策略置灰不隐藏。**注**：当前代码**没有** CD-20 设计描述里的全局/高风险自动批准 @AppStorage 开关（已演进掉），所以不存在"自动批准假开关"问题——这点比 CD-20 文本更正确。`A`。视觉 P3（自定义 switch）。

### 2.11 运行环境 / Sandbox — `RuntimeEnvironmentView`（原型 `#sandbox`，CD-8/22，真实接线）
- **现状**：完整 Remote SSH exec runner。运行时单选（实验性 Mosh/iSH 被 `IOSTerminalBuildPolicy` gate 禁用）、Smoke Test 跑真实 `IOSTerminalRuntime.shared.startJob`、SSH profile 表单（仅密码）、密码进 Keychain（`IOSSSHSecretStore`）、默认 profile 选择。
- **安全流程核验通过（本簇亮点）**：host 指纹信任状态机 `idle→testing→needsTrust→trusted|mismatch`；**mismatch 硬失败**（红卡、不持久化密码）；`needsTrust` 要求显式"信任此 Host"且**主动清除已存密码**避免发往未验证主机；trusted 仍跑真实认证、失败清密码。CD-8"未信任不发密码"正确落实。
- **唯一瑕疵**：动态状态串是英文（"SSH host trusted and password verified."，`:294-334`），与整屏中文不一致（CJK 用户）。
- **接线**：`A`，无安全假入口。→ 本地化状态串。**P2**（纯一致性打磨；安全本身正确）。

### 2.12 执行与任务 — `ExecutionSettingsView`（原型 `#execution`，CD-6/33）
- **现状**：操作预览模式 + 工具循环上限 + 生成式 UI + 灵动岛 Live Activity + 隐藏敏感 + 自动重试 + 重试上限 + 后台保活，全 `@AppStorage` 本地。**CD-6"删除 Live 伴随"已落实**，只剩 iOS 原生灵动岛段。
- **占位**：`B`——灵动岛注脚"由 iOS 原生 ActivityKit 驱动"读着像现状陈述，但不真启 Live Activity。→ 软化为意图态文案。**P3**。

### 2.13 今日看板 / 看板设置（CD-44/45）
- **`BoardView`**：硬编码 7 条热点 + `刚刚更新 · 由 Amber 整理` 绿色实时点 + 行 `Button` **空动作**（点了无反应、还带 chevron）。`C(轻)` sample-as-real + 死点击。→ 去掉实时点或标"示例"，行去 chevron/给反馈。**P3**。
- **`BoardSettingsView`**：纯本地 `@State`，用原生 `Toggle`（与他屏自定义 switch 不一致）。`B`。**P3**。

### 2.14 小应用 / 设置 / 运行器（CD-5/51/52/53）
- **`MiniAppListView`**：2 列网格，**字母/汉字字标图标（非 emoji，命中 CD-5）**，置顶星标，管理菜单 `confirmationDialog` 无副作用。`B`，正确草稿。**P3**。
- **`MiniAppSettingsView`**：单页 17 开关权限矩阵，主开关关闭级联禁用子项。**占位 C-watch**：开关命名了定位/剪贴板/任意脚本等敏感能力但**既不授予也不门控**，仅在运行时缺席（runner 是原生 demo、无 WebView）时安全。→ **未来护栏**：任何 WebMount/MiniApp WebView 运行时上线*之前*，这些开关必须先接真实能力门控。**P2**（今天安全，明确的潜在安全依赖）。
- **`MiniAppRunnerView`**：番茄钟原生 demo + 其它诚实空态，无 WebView。`B`，模范草稿。**P3**。

### 2.15 WebMount / 站点详情（CD-54/55）
- **`WebMountView` / `WebMountSiteView`**：Agent 集成开关 + 高风险任意 JS 开关 + 6 样例站点（状态徽标）+ 站点详情登录/退出/测试/删除（全本地 alert，且 alert 自陈"只验证界面，不发请求"——诚实）。**问题**：**C**——列表把 飞书/X 显示 `已登录`、B站 `限流中`（`:70-79`）当真实会话态（无 cookie/WebView/网络）；高风险"允许任意 JavaScript 执行"开关本地化、命名了真危险能力。动作侧已被诚实 alert 缓解，**误导的是列表徽标**。→ 样例站点 reset 到 `.off`/`.public` 或站点段加"示例"标记；同 MiniApp 护栏：运行时上线前先接门控。**P2**（安全相邻面的假登录态）。

### 2.16 搜索服务 / 同步 / 对话存储 / 外观 / 显示字体 / 核心记忆 / agents.md
- **`SearchServicesView`/`SearchProviderView`**：见 §2.5。
- **`SyncBackupView`（CD-26）**：忠实端口，**全簇最强诚实免责**（每个网络/OAuth/文件动作都自陈不发请求/不覆盖）；图标 tint 与原型 inline override 一致（正确）。`B`。"已连接"账户态是静态样例但每个动作都免责，borderline。**P3**（可选给账户行加"示例"）。
- **`ConversationStorageView`（CD-25）**：CD-25 诚实模范——删除全部走 `.destructive` alert 且文案明说"不会删除"。瑕疵：清理行 leading 图标染了 accent（原型是单色 muted）；确认按钮文案"继续占位/保留数据"是 placeholder 措辞。`B`。**P3**。
- **`AppearanceSettingsView`（CD-23）**：浅/深/跟随 + 强调色 + 背景色调，全 `@AppStorage`。**C**——深色/跟随**看着能选、能持久化，却永远无效**（`AmberTheme` 硬编码浅色，§4），用户切深色得到一个无反馈的惰性设置。→ 最小诚实修法：禁用/置灰深色+跟随并加"开发中"，或加注脚说明外观未应用。**P1**（看着是活的惰性控件）。
- **`DisplayFontSettingsView`（CD-24）**：字号滑块（真实绑定、自我演示）+ 14 开关（不影响聊天）。无"未应用到聊天"提示。`B`。→ 加一行免责。**P2**。
- **`MemoryOverviewView`（CD-27）**：agents.md soul 卡 + 6 配置开关 + 6 样例记忆卡。**问题**：(1) 文案"向左滑动可删除"（`:221`）但**无滑动删除**（自定义卡片，非 `List`）——指令与能力不符；(2) `长期` 记忆用 `accentCyan`（`:249`）vs 原型 indigo——iOS 选择其实更合规（indigo 应仅限模型），是原型自身违反全局规则（见 QUESTIONS）。`B`/`C(轻)`。→ 删/软化"向左滑动"文案。**P2**。
- **`MemoryEditView`（CD-29）**：`@State` 草稿，删除/完成只 dismiss 且 alert 诚实；"完成"用 glass capsule 在 nav chrome（合规）。`B`，模范。**P3**。
- **`AgentsMarkdownView`（CD-28）**：monospace `TextEditor`，`@AppStorage` 草稿。**C**——注脚称"会注入到 System Prompt"，但 `ChatViewModel` **从不读** `app.amber.ios.agentsMd.draft`（主线已 grep 核实），即编辑器什么都不做、文案却断言行为。→ 接进系统消息，或改文案为草稿/未来态。**P2**。

### 2.17 旧版 `SettingsView`（无原型对应，经 `.providerSettings` 可达，真实接线）
- **现状**：原生 `Form` 承载 OpenAI base/key/model + 连接测试 + SSH profile CRUD + 能力矩阵，**完全可用**。
- **问题**：是**唯一拿到原生 Dynamic Type/深色/手势**的屏，但代价是**完全脱离暖陶土品牌**（系统灰、蓝色按钮、原生开关）——从 ProviderDetail 进来会撞进另一个视觉宇宙。能力矩阵是"设置内 dashboard"。
- **接线**：`A`（真实），但架构上孤立。→ 决策：迁到 `AmberFormGroup` 与全局一致，或明确把它划为"技术性 SSH 面有意原生"。**P1**（可达屏的视觉割裂）。

---

## 3. Capability Wiring Audit

### 3.1 已接真实能力（A — 真实状态来源/业务逻辑）
| 面 | 真实接线 | 证据 |
|---|---|---|
| 默认聊天模型 | `SettingsStore.modelId`/`baseUrl`/`apiKey`(Keychain) | `ModelDefaultsView:96-99`、`SettingsStore.swift` |
| Chat 发送/流式/取消 | 真实 `ChatViewModel` + provider 请求 | `ChatViewModel.swift:185/352` |
| 权限与能力（系统权限请求） | `IOSSystemPermissionCoordinator` request/refresh/openSettings | `ToolPermissionsView:321-354` |
| Selected File 读取 | `DocumentAccessStore` + 系统选择器（10min/1次/2MB） | `DocumentAccessStore.swift`、`IOSLocalToolExecutor.swift` |
| 批准策略（逐能力） | `IOSPermissionStore` policy + 执行器读取 + 平台门控 | `PermissionsApprovalView:93-100`、`IOSPermissionModels.swift:1638` |
| Remote SSH 运行环境 | SSH profile + Keychain 密码 + 指纹信任状态机 + Smoke Test | `RuntimeEnvironmentView`、`SettingsStore`、`IOSTerminalRuntime` |
| 旧版 Provider 配置 | Base URL/Key(Keychain)/Model + 连接测试 | `SettingsView.swift`（`.providerSettings`） |

### 3.2 只是本地草稿（B — 按 CD 合理停在 UI，多数有诚实自陈）
外观（部分）、显示字体、对话存储、同步备份、核心记忆/记忆编辑、技能/技能详情/添加技能、MCP 列表与导入/添加、SubAgent/角色、议会运行/设置/席位、今日看板/设置、小应用列表/设置/运行器、WebMount、搜索服务/编辑、模型编辑/添加、TTS 列表/添加、执行与任务。
**这些屏视觉基本完成，进一步像素打磨边际收益低**（详见 §6）。

### 3.3 危险的假入口（C — 看着是真的、其实没接或会误导）
按严重度排序：
1. **Chat 模型/思考/上下文控件** — chip/档位变、请求不变；上下文环写死当实时。**用户可感知的正确性问题。P0/P1**。
2. **凭据静默丢弃/过度承诺** — ProviderAdd 丢 API Key；SearchProvider/TTS "存入 Keychain" 无保存；ProviderDetail 揭示假 `sk-…` key。**P1**。
3. **示例当真实运行态（安全相邻）** — MCP `已连接·12工具`、WebMount `已登录`、Board 实时点、Account 统计。**P1~P2**。
4. **agents.md "注入 System Prompt"** 但从不读取。**P2**。
5. **外观深色/跟随** 可选可存却永远无效。**P1**（惰性控件）。
6. **TTS 试听** 假播放无提示。**P1**。
7. **潜在安全依赖** — MiniApp/WebMount 敏感能力开关今天不门控，仅因运行时缺席而安全；运行时上线前必须先接门控。**P2（护栏）**。

### 3.4 孤儿入口 / 死路 / 死代码
- 首页样例会话 4/5 行 → `.conversation(id:)` → 空 `ContentUnavailableView`（死路）。
- `.modelPicker` sheet（`AppShell:54/224`）**无人 present**，落到英文 `PlaceholderListView`（孤儿）。
- `.workspace` 路由（CD-68 后首页改指 `.sandbox`）**无人导航**（孤儿）。
- `WorkspaceView`/`AssistantsView`/`PlaceholderListView`/`AppTab`/`TabRouter` — CD-15 有意保留的死脚手架，但 `WorkspaceView`/`AssistantsView` 是英文 Lorem 占位。
- `ToolPermissions` 的 `clearGrant()` 后端活着但 UI 入口缺失（孤立真实能力）。

### 3.5 推荐接线顺序（先低风险高价值，再重副作用）
1. **Chat composer 正确性**（接 model/think，或标预览）— 影响主场景可信度，改动局部。
2. **诚实度扫一遍**（§3.3 第 2-6 项的文案/初值/reset/disclaimer）— 纯 UI、零外部副作用、消除误导。
3. **ToolPermissions 清除授权** + **修死路/孤儿路由** — 小、可验证。
4. **主题层环境化**（解锁深色模式 + 部分 a11y）— 架构性，见 §4，单独立项。
5. **真实注册表接线**（顺序）：Provider/Model registry（让 ProviderAdd/ModelEdit/ModelDefaults 辅助行落真实）→ TTS provider + Keychain → 搜索服务持久化 → 记忆库 → 同步（OAuth/网络，重副作用最后）。
6. **运行时门控**（MiniApp/WebMount 能力开关）— **必须早于**任何 WebView 运行时上线。

---

## 4. Visual Polish Checklist

### 架构性（影响所有屏，最高杠杆）
- [ ] **主题硬编码 → 深色不可交付**：`AmberTheme`（`PlaceholderViews.swift:3-27`）是浅色 `enum`。深色/跟随系统（CD-23）在它环境化之前无法落地。**P1**。
- [ ] **无 Dynamic Type**：到处 `.system(size:)` + 固定 `frame(minHeight: 52/54/58)`。改为 `@ScaledMetric` 下限 + 可缩放文本样式。**P2**。
- [ ] **自定义 switch 偏小且不一**：5 套 48×28（系统 51×31），旋钮色 white vs background 混用，`BoardSettings`/`SearchServices`/`CouncilSettings` 又用原生 `Toggle`。统一为一个 `AmberSwitch`(51×31) 或全用原生。**P3**。
- [ ] **承诺了给不了的手势**：多处文案"左滑删除/下拉重连"，自定义 `ScrollView` 无 `.swipeActions`/`.refreshable`。实现需迁 `List`，或软化文案。**P2**。

### 边距 / 对齐
- [ ] 账户热力图魔数（`AccountView.swift:295`）不抗缩放、窄屏切月份轴。**P2**。
- [ ] `convStorage` 清理行图标染色 vs 原型单色 detail 图标（`ConversationStorageView:213`）。**P3**。

### 字号 / 行高
- [ ] **nav-title 全屏 `.title2.bold`(~22pt) vs 原型 `.nav-title` 17px/600** — 一致性偏大，请裁决（见 QUESTIONS）。**P2**。
- [ ] Markdown 标题用裸点数而非 Dynamic Type 文本样式（`MarkdownView.swift:100`）。**P2**。
- [ ] Provider endpoint 字号随 enabled 在 11/12pt 切换（`ProvidersView:156-160`）。**P3**。

### 图标
- [ ] **indigo 误用**：`ModelDefaultsView:230` 四辅助行装饰性 indigo（应仅限模型/选中）。**P2**。
- [ ] `ToolPermissions` 不可导航行带 chevron（`:458-460`）。**P3**。
- [ ] 字标/`{}`/字母图标非 emoji — **已正确**（MCP/小应用核验通过）。

### 色彩
- [ ] **代码块冷灰泄漏**：`systemGray6`→`AmberTheme.surface`，圆角 8→12（`MarkdownView.swift:125`）。**P1**。
- [ ] 行内代码补底色 pill。**P2**。
- [ ] 记忆 `长期` cyan vs 原型 indigo（iOS 更合规）— 请确认有意（见 QUESTIONS）。**P3**。

### Sheet / Popover
- [ ] `ComposerModelSheet` 用 `presentationBackground(.glassStrong)`（`ChatView:52`）让整张 sheet 玻璃化承载内容；内层已 reasserts 实心，去掉冗余玻璃底更稳。**P2**。
- [ ] `FlowPills` 不换行（`McpServersView:475`），大字号/窄屏溢出。**P2**。

### 输入框
- [ ] API Key 字段已用 `SecureField`（多处正确）；但 `SearchProvider`/`TTS` 的"存入 Keychain"文案与无保存动作矛盾。**P1**。
- [ ] `ProviderDetail` 眼睛揭示假 key（`:195`）。**P1**。

### 状态表达
- [ ] sample-as-real 普遍缺"示例"标记（MCP/WebMount/Board/Account）。**P1~P2**。
- [ ] 工具 `.failed` 永远推不出（`ChatView:983`）→ 真实失败显示为永久"运行中"。**P1**。
- [ ] 流式不自动滚动（`ChatView:102`）。**P1**。

### 可访问性
- [ ] 触控区 <44pt：Chat `+`/发送/停止 28-32pt（`:155/176/187`）。**P1**。
- [ ] 思考折叠缺 `.accessibilityValue`（`ChatView:858`）。**P2**。
- [ ] 自定义 switch/手搓控件缺原生 VoiceOver 切换语义（随 §4 switch 统一一并解决）。**P2**。
- [ ] 减少透明度/增加对比度回退：玻璃修饰符有 `.ultraThinMaterial` 老系统回退，但内容层手搓色不响应系统对比度设置。**P3**。

---

## 5. Implementation Plan（一屏/一组件一个 commit）

> 原则：小步、可验证、不做大而空的重构。每个 commit 落地后按 CD 约定追加/更新 CD-n，并尽量产出 `render_screen.py <id>` 基准图或模拟器截图对比。
> **注意验证盲区**：`renders/` 当前只有 4 张成功基准图（chat/conversations/providers/settings），其余 ~34 屏 `render_screen.py` 失败（QUESTIONS Q-19~Q-28+），多数屏只用模拟器截图验收过、缺原型并排比对。修复渲染脚本或建立替代基准应作为独立任务。

**第一批（正确性 + 诚实度，低风险）**
1. `commit: Chat composer 接真实模型/思考或标注预览` — `ChatView.swift` + `ChatViewModel.swift`。把选中 model id/思考档接进 `makeTextGenerationParams()`；上下文环接 `usage` 或标占位；provider 列表取自已配置 provider。验证：选不同模型后真实请求模型变化；reasoning 生效。风险：触碰主请求路径，需回归发送/流式。
2. `commit: 内容层暖色 + indigo 修正` — `MarkdownView.swift`（代码块 surface/12 + 行内 pill）+ `ModelDefaultsView.swift`（去四辅助行 indigo）。验证：代码块暖色、与气泡同族；模型默认页无装饰 indigo。风险：极低。
3. `commit: 新建会话确认 + 修死路/孤儿路由` — `ChatView.swift`（新建加确认或开新 thread）+ `PlaceholderViews.swift:364`（样例行落 `.chat`）+ `AppShell.swift`（移除/禁用 `.modelPicker`、`.workspace` 孤儿）。验证：点样例会话不再落空；无数据丢失。风险：低。

**第二批（诚实度扫尾，纯 UI）**
4. `commit: sample-as-real 诚实化` — MCP `已连接`→示例标记（`McpServersView`）、WebMount 站点 reset/标记（`WebMountView`）、Board 去实时点（`BoardView`）、Account 统计加"示例"（`AccountView`）。
5. `commit: 凭据面文案修正` — ProviderAdd 保存或明确不保存提示（`ProvidersView`/`ProviderAddView`）、SearchProvider/TTS 软化 Keychain 文案、TTS 试听加"未实际合成"、ProviderDetail 假 key→中性占位。
6. `commit: agents.md 文案/接线` — 接进系统消息或改草稿态文案（`AgentsMarkdownView` + 视情 `ChatViewModel`）。

**第三批（安全控制 + 可访问性小步）**
7. `commit: ToolPermissions 补清除授权` — `ToolPermissionsView`（`grantSummary != nil` 时 confirmationDialog 调 `clearGrant()`）+ 去不可导航行 chevron。
8. `commit: 触控区 44pt + 工具 failed 态 + 流式滚动` — `ChatView.swift` 局部。
9. `commit: 外观深色诚实化` — `AppearanceSettingsView` 置灰深色/跟随 + 注脚（在主题环境化前）。

**第四批（架构性，单独立项、需评审）**
10. `主题环境化`：`AmberTheme` → `@Environment`/可注入 theme store，解锁深色模式 + 应用 Appearance/DisplayFont 偏好。
11. `统一 AmberSwitch`(51×31) + 关键列表迁 `List` 以获得滑动删除/重排（搜索源优先级、技能、MCP、记忆、席位）。

**第五批（真实注册表接线，重副作用靠后）**
12. Provider/Model registry → 13. TTS + Keychain → 14. 搜索服务持久化 → 15. 记忆库 → 16. 同步（OAuth/网络）。每项落地前先接 §3.5 第 6 条的运行时门控护栏。

---

## 6. 结论

### 距离可交付还差多少
**视觉脚手架 ~82% 完成，但"可交付 UI"约 50%——剩下的约一半不在像素，而在：**
- **接线诚实度（~15%）**：消除 §3.3 的假入口/误导文案，是最快提升可信度的部分，且基本零外部副作用。
- **可访问性 + 深色模式（~20%）**：主题环境化 + Dynamic Type + 原生控件，是架构性投入，决定 CD-23/24 能否真正交付。
- **真实能力接线（~15%）**：除已真实的 4 个面外，Provider/Model/TTS/搜索/记忆/同步需逐步落地。

### 第一批最该修的 3 个 commit
1. **Chat composer 接真实模型/思考（或标预览）+ 新建会话确认** — 主场景的正确性与数据安全，最高价值。
2. **内容层暖色修正（代码块 + 行内代码）+ ModelDefaults indigo** — 最显眼的视觉缺陷，几行改动、零风险。
3. **诚实度扫一遍（MCP/WebMount/Board/Account/凭据文案）** — 纯 UI、消除一批"假真实"，把"看着能用"变成"可信"。

### 哪些地方不建议继续打磨 UI，而应先接真实能力
以下屏**视觉已基本到位，继续像素打磨边际收益低**，应把精力转向接线/护栏：
- **今日看板、小应用（列表/设置/运行器）、WebMount、模型议会（运行/设置/席位）、SubAgent、记忆、同步备份、TTS 参数、搜索服务** — 这些是 B 类草稿，原型保真度已高；它们需要的是**真实状态来源**，不是更多视觉调整。
- 反过来，**真正值得现在投入"非像素"工作的三处**：(1) **主题/表单架构决策**（解锁深色 + a11y + 列表重排，一次性影响全局）；(2) **Chat composer 接线**（主场景可信度）；(3) **安全面收口**（ToolPermissions 清除授权 + MiniApp/WebMount 运行时门控护栏 + 凭据面诚实化）。

> 附：本轮曾把发现的设计内部矛盾（非阻塞）追加到一次性 Open Design 问题清单；该清单已从当前工作树移除，可从 Git 历史追溯。本阶段未改任何代码。
