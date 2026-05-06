# Claude UI Refactor — Phase 0 Audit · 2026-05-05

走的是 **M3 Expressive 激进路线**。本文不 commit；用户审过再决定是否提交。

## 0. 分支 & 起点

| | |
|---|---|
| 分支 | `amberagent/ui-m3-expressive` |
| HEAD | `b2859c64 docs: add claude ui handoff` |
| 安全回滚 tag | `amberagent-ui-baseline-before-claude-20260505` → `fd8b913b` |
| 工作树多余文件 | `docs/ui-mockup-2026-05-05.html`（仅作视觉参考，不实装真理） |
| Compose BOM | `2026.04.01` |
| material3 | `1.5.0-alpha18`（已 opt-in `ExperimentalMaterial3ExpressiveApi`）|

### Repo 里已经在用的 Expressive 控件

| API | 出现位置 |
|---|---|
| `LargeFlexibleTopAppBar` | `CardGroup.kt`, `AssistantPage.kt`, `FavoritePage.kt`, `LogPage.kt`, `ExtensionsPage.kt`, `SettingDisplayPage.kt` 等 10+ 页面 |
| `LinearWavyProgressIndicator` | [McpPicker.kt:135](app/src/main/java/me/rerere/rikkahub/ui/components/ai/McpPicker.kt:135), [TranslatorPage.kt:162](app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorPage.kt:162) |
| `ContainedLoadingIndicator` | [RabbitLoading.kt:39](app/src/main/java/me/rerere/rikkahub/ui/components/ui/RabbitLoading.kt:39) |
| `MaterialShapes.Cookie6Sided.toShape(rotateAngle)` | [AvatarShape.kt:28](app/src/main/java/me/rerere/rikkahub/ui/hooks/AvatarShape.kt:28) |
| `SplitButtonLayout` | `AssistantImporter.kt` |
| `ButtonGroup` 自定义 wrapper | `setting/components/PresetThemeButtonGroup.kt` |

**含义**：激进路线的 API 风险已经被 repo 自身淌过。我做的不是引入，是扩展使用范围。

---

## 1. UI 树映射（5 个目标场景）

### 1.1 Chat Timeline

```
ChatPage [pages/chat/ChatPage.kt]
└── ChatPageContent
    └── ChatList [pages/chat/ChatList.kt 861 LOC]
        ├── latestRenderToken / compactRenderToken      ← LazyColumn 失效键，红线
        ├── LazyColumn
        │   └── itemsIndexed → ChatMessage per MessageNode
        └── ChatListPreview (search + jump)

ChatMessage [components/message/ChatMessage.kt 624 LOC]
├── Avatar Row (assistant + user)
├── MessagePartsBlock
│   └── groupMessageParts() [components/message/ChatMessageCot.kt 90 LOC] ← 红线
│       ├── ThinkingBlock → ChainOfThought()
│       │   ├── ChatMessageReasoningStep [ChatMessageReasoning.kt 262 LOC]
│       │   │   └── ReasoningCardState (Collapsed/Preview/Expanded)  ← 状态机不动
│       │   └── ChatMessageToolStep [ChatMessageTools.kt 1279 LOC]
│       │       ├── AgentToolCallCapsule (icon + title + status pill)
│       │       ├── ToolCallPreviewSheet (ModalBottomSheet)
│       │       │   ├── SearchWebPreview
│       │       │   ├── ScrapeWebPreview
│       │       │   └── GenericToolPreview
│       │       └── AskUserToolStep (FilterChip 多选 + OutlinedTextField)
│       └── ContentBlock → 三分支
│           ├── User: primaryContainer Surface (22/8/22/22 corners)
│           ├── Assistant + showAssistantBubble: surfaceContainerLow Surface (8/22/22/22)
│           └── Assistant + bare: MarkdownBlock 直渲染
└── ChatMessageActionButtons [ChatMessageActions.kt 462 LOC]
    └── FlowRow of icon buttons (Copy / Refresh / TTS / Translate / More + Branch)
```

**关键契约**：
- `ChainOfThoughtScope` 接口在 [ChainOfThought.kt:180](app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt:180)，`ChatMessageReasoningStep` 和 `ChatMessageToolStep` 都是该 Scope 上的扩展函数 — 不能改 Scope 签名。
- `groupMessageParts()` 的"空 reasoning marker 不打断 Text"是回归测试锁定的不变式。
- `ReasoningCardState` 三态 + `displaySetting.autoCloseThinking` 联动 — 状态机不能动。

### 1.2 Composer

```
ChatInput [components/ai/ChatInput.kt 2291 LOC, 32 composables]
├── 顶部状态展示行 (sandboxActivity preview)
├── TextField (BasicSecureTextField TextFieldLineLimits.MultiLine)
├── ExpandState Toggle (Collapsed | Files) ← 当前 "+" 行为，保留
│   └── 展开时 → 一列 ListItem 风格按钮 (Image / File / Camera / ...)
├── 操作行 (action row)
│   ├── "+" 切换 ExpandState
│   ├── Model picker chip (AssistantPicker?)
│   ├── Search service toggle
│   ├── MCP picker
│   ├── Reasoning picker
│   ├── Sandbox activity pill
│   └── Send / Cancel button (round, fillIcon)
├── SandboxActivitySheet (ModalBottomSheet)
└── Camera / UCrop / Permission launchers (上层 Activity 注册)
```

**红线（ChatInput 内）**：`inputState`、`messageContent`、`Camera launcher`、`UCrop`、permission state、`McpManager`、`WebViewLink`、`WebViewOperationStore`、`SandboxActivitySheet` 内部。

### 1.3 Live Status Notification

```
ChatService → AgentLiveStatusNotifier [data/agent/AgentLiveStatusNotifier.kt 369 LOC]
├── notifyRunning(conversationId, ...)
│   └── buildStatus()
│       ├── if SandboxActivityUiState != null → 工具卡渲染
│       └── else 检查 lastAssistant.parts:
│           ├── lastTool && !isExecuted → RUNNING_TOOL / WAITING_PERMISSION
│           ├── lastReasoning.finishedAt == null → PLANNING
│           └── lastText → WRITING / PLANNING (兜底)
├── notifyFailure(...)
└── cancel(conversationId)

输出去向：
NotificationCompat.Builder
├── smallIcon = R.drawable.amberagent_live_status_icon  ← 红线，绝不动
├── largeIcon = null (上轮已 fix)                       ← 红线
├── ongoing = true
├── onlyAlertOnce = true
├── category = CATEGORY_PROGRESS
├── requestPromotedOngoing = true                      ← 红线
├── shortCriticalText = chipText                       ← 可调文案/长度
└── actions = [Stop] (when canStopGeneration())
```

**红线**：smallIcon 矢量 path / fillType（[amberagent_live_status_icon.xml](app/src/main/res/drawable/amberagent_live_status_icon.xml)）；`largeIcon = null`；`requestPromotedOngoing`；`smallIcon = R.drawable.amberagent_live_status_icon` 这条赋值。

### 1.4 Settings Index

```
SettingPage [pages/setting/SettingPage.kt]
├── Sponsor AlertDialog (launchCount-based)
├── Scaffold + LargeFlexibleTopAppBar
└── LazyColumn
    └── CardGroup × N
        ├── "通用" → ColorMode select + Display ListItem
        ├── "Agent 运行时" → Memory / Extensions / Sandbox / Permissions / SystemAccess ListItems
        ├── ... 其他分组
        └── "关于" → About / Donate
```

**红线**：`Screen.*` 路由名称（影响深链）；`SettingVM` 内部。

### 1.5 Bubble wrapper

仅指 `ChatMessage.kt:351-415` 里包裹 `MarkdownBlock` 的两个 `Surface`。
**红线**：不能加 `Modifier.fillMaxWidth()` 到 Surface / Column / MarkdownBlock。
**红线**：不能动 `Markdown.kt` / `MarkdownNew.kt` 内部。

---

## 2. 风险账本（按风险高→低）

| # | 区域 | 风险 | 缓解 |
|---|---|---|---|
| 1 | `coalesceStreamParts` + `groupMessageParts` 不变式 | 一旦动了，流式文本会再次被切成竖直一列 | 不碰；Phase 5/6 涉及 ChatMessage 内部时只改 Surface，跑两个回归测试 |
| 2 | `ReasoningCardState` 三态 + `autoCloseThinking` 联动 | 改了会影响"思考时折叠 → 完成时自动收起"的体验 | 不动状态机；只改 `Idea01` icon 的呈现层（容器 / 装饰）|
| 3 | `latestRenderToken` 必须随 `UIMessagePart` shape 变化 | 任何加字段都要更新 token 否则 LazyColumn 不刷新 | 本轮不加字段 |
| 4 | `MaterialShapes` 多边形顶点插值在长胶囊上的表现 | 见 mockup 反馈：百分比 border-radius 在窄高元素上失真 | 用 `MaterialShapes.Cookie6Sided.toShape(angle)` 这种顶点定义；或对长胶囊用绝对像素 RoundedCornerShape |
| 5 | live status icon 矢量 | 改了会在 OPPO Fluid Cloud / Pixel promoted ongoing 上塌成白球 | 完全不碰资源 |
| 6 | `ExpandState.Collapsed/Files` 行为 | 改成 FAB Menu 已被用户否决 | 保留行为，仅视觉统一 |
| 7 | shape morph 性能（50+ 工具卡同时 morph） | 可能掉帧 | 用 `animateValueAsState` 锁定到状态切换瞬间，稳态后落到静态 shape |
| 8 | alpha API 签名漂移 | alpha18 → 1.5.0 stable 间名字可能变 | 包一层 `AmberWavyLoading` / `AmberToolShape`，集中收敛 |
| 9 | Compose preview / `@Preview` 编译 | 部分文件有 `@Preview`，添加 alpha API 时 preview 可能编译失败 | 触碰 `@Preview` 时单独检查 |

---

## 3. 阶段化实施计划

每阶段 = 一个 commit；commit 后跑 verification gate；不过就回退。

### Phase 1 · 工具胶囊 shape morph + wavy 状态带
**文件**：[ChatMessageTools.kt](app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt) 的 `AgentToolCallCapsule` + 状态色板。

**行为**：
- `shape` 从固定 `RoundedCornerShape(24.dp)` 改为基于 `AgentToolStatus` 的 `animatedShape`：
  - `RUNNING` / `WAITING_FOR_PERMISSION` → pill (`RoundedCornerShape(24.dp)`)
  - `SUCCEEDED` → `MaterialShapes.Cookie6Sided.toShape(0)` 但仅作用于左侧 icon 容器；胶囊本体改用 `RoundedCornerShape(28.dp, 12.dp, 28.dp, 12.dp)` 非对称 squircle
  - `FAILED` → 同 SUCCEEDED 但红色 errorContainer
  - `CANCELLED` → 全圆角 small (8dp) 内陷感
- 在 `RUNNING` 状态下，胶囊正下方加一条 12px 高的 `LinearWavyProgressIndicator`（已在 repo 用过）
- 用 `animateValueAsState` 控制 corner 切换的 motion；`motionScheme.defaultSpatialSpec()` 已有

**风险**：风险账本 #4、#7。

**验证**：`:app:compileDebugKotlin` + `ChatMessageCotTest` + 手动调一次 `terminal_execute` 看胶囊三态切换。

### Phase 2 · ChainOfThought 容器 + 时间线 loading 行
**文件**：
- [ChainOfThought.kt](app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt)（仅视觉，不动 Scope 接口）
- [ChatList.kt:387-408](app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt:387) loading 行

**行为**：
- ChainOfThought 容器从纯左竖线（`isReasoningOnlyBlock`）升级为 `surface-container-low` 圆角块（已在 mockup 验证视觉），保持 `collapsedAdaptiveWidth` 行为不变
- 时间线 `LoadingIndicatorKey` row 把 `PigLoadingIndicator + DotLoading` 改用 `ContainedLoadingIndicator`（已在 RabbitLoading 用过）+ 一条 `LinearWavyProgressIndicator` 作为长进度暗示
- `processingStatus` 文字保留，单行省略

**风险**：保留 `ChainOfThoughtScope` 接口；不动 `LoadingIndicatorKey` 常量。

**验证**：`:app:compileDebugKotlin` + 手动发一句长问题。

### Phase 3 · LiveStatus 文案 + WavyProgress + 状态岛兼容
**文件**：
- [AgentLiveStatusNotifier.kt](app/src/main/java/me/rerere/rikkahub/data/agent/AgentLiveStatusNotifier.kt)（仅文案常量 + chipText 优化）
- `app/src/main/res/values/strings.xml` 与 `values-zh/strings.xml`

**行为**：
- 缩短 `shortCriticalText`（CJK 4 字符以内，保证 Fluid Cloud 不被截）
- `chipText` 系列加 emoji-free 短词：`运行 1/3` / `等待` / `规划` / `撰写`
- 在通知卡片本体（不是 small icon）的 RemoteViews 路径里，如果系统支持 `setProgress(...)` 配合 promoted ongoing，加 `LinearWavyProgressIndicator` 的等价线性 wavy 进度（**这一步先验证 NotificationCompat 是否给 wavy progress 通道，否则降级为普通 indeterminate**）
- 完全不碰 `smallIcon` / `largeIcon` / `requestPromotedOngoing` 决策

**风险**：风险账本 #5；`setProgress` 的 wavy 是否被 Fluid Cloud 接受，需要真机验证。

**验证**：真机 OPPO 或 Pixel 上用 wired adb 装包，触发一次工具调用看状态岛。

### Phase 4 · Composer 操作行 → ButtonGroup + SplitButton
**文件**：[ChatInput.kt](app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt) 仅操作行附近 (~lines 480-580 区域)。

**行为**：
- Model picker / Search toggle / MCP picker 三个 chip 收纳进 `ButtonGroup`（参考 `PresetThemeButtonGroup`）
- 发送按钮从圆形 `IconButton` 升级为 `SplitButtonLayout`：主键 = 发送，副键 = 长按等价的"silent send" 选项
- "+" 附件按钮**保留 `ExpandState` 行为不动**（用户已否决 FAB Menu）；展开后的附件 chip 用 `MaterialShapes.Cookie6Sided.toShape(0)` 作为 leading icon container 形状
- composer outer shape 保持现有非对称 32/32/28/14 角形（产品识别度）

**红线**：不动 `inputState`、附件 launcher、camera/UCrop、permission、MCP picker、WebView 预览、SandboxActivitySheet。

**验证**：`:app:compileDebugKotlin` + 五条路径手测：发文本 → 发带附件 → 切模型 → 切搜索 → 流式中取消。

### Phase 5 · Settings Hero 卡 + CardGroup shape rotation
**文件**：[SettingPage.kt](app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt)。

**行为**：
- 顶部加一张 Hero 状态卡，绑定 `AgentToolActivityStore.flow`：显示 Agent 当前是 idle / planning / running tool / waiting / writing；用 `MaterialShapes.Cookie6Sided.toShape(rotation)` 做形状（旋转 4° 增强活力）
- CardGroup 的 corner 在每个 group 里渐进：上半组用 `extra-large`，下半组用 `extra-large + extra` (32dp)，形成"由上至下圆度递增"
- ListItem 的 leading icon container 用 `MaterialShapes.Cookie6Sided.toShape(0)`（与头像、工具卡 icon 统一形状语言）
- Switch 用现有 M3 expressive Switch（already provided）

**红线**：不重命名 Screen 路由；不动 SettingVM。

**验证**：navigate 每个子页面 + 返回。

### Phase 6 · 气泡呼吸 + 头像形变（陷阱阶段）
**文件**：[ChatMessage.kt:351-415](app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt:351) 仅 assistant Surface 分支。

**行为**：
- assistant bubble 的 corner 从 `(8,22,22,22)` 微调到 `(8,28,28,24)`（绝对 px，不用百分比）
- 增加 8px 内 padding 缓冲
- assistant `avatar`（22×22 圆形）loading 状态改用 `MaterialShapes.Cookie6Sided.toShape(angle)`（动态旋转），就像 [AvatarShape.kt:28](app/src/main/java/me/rerere/rikkahub/ui/hooks/AvatarShape.kt:28) 现有做法 — 把它扩展到 ChatMessage 的内联 avatar
- **绝对禁止** `Modifier.fillMaxWidth()` 出现在 Surface / Column / MarkdownBlock 上

**红线**：风险账本 #1。

**验证**：
- `:ai:testDebugUnitTest --tests MessageStreamAccumulatorTest`
- `:app:testDebugUnitTest --tests ChatMessageCotTest`
- 手测：流式发 "你好" / "OK" / 长 markdown 列表 / 代码块 / 工具结果。无一渲染成竖直一列。短回复也不撑满屏幕。

---

## 4. 验证矩阵

| 阶段 | compile | stream tests | settings smoke | real device |
|---|---|---|---|---|
| 1 | ✅ | ✅ | — | recommended |
| 2 | ✅ | ✅ | — | recommended |
| 3 | ✅ | — | — | **required** (Fluid Cloud) |
| 4 | ✅ | — | — | recommended (composer) |
| 5 | ✅ | — | ✅ | recommended |
| 6 | ✅ | **required** | — | **required** (回归手测) |

固定环境：

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/Users/arquiel/Library/Android/sdk
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
```

最终 APK：

```
/Users/arquiel/Downloads/AmberAgent-v0.8.3-claude.apk
```

---

## 5. 不动清单（this UI pass）

- `coalesceStreamParts` 和 `groupMessageParts` body
- `ReasoningCardState` / `rememberReasoningState` 状态机
- `latestRenderToken` / `compactRenderToken`
- `UIMessagePart` 任何字段
- `amberagent_live_status_icon.xml` 任何 path / fillType
- `notifyRunning()` 的 smallIcon / largeIcon / requestPromotedOngoing 三件
- ChatInput 内的 `inputState` / `messageContent` / 启动器 / MCP picker / WebView / SandboxActivitySheet 内部
- `Markdown.kt` / `MarkdownNew.kt` 内部
- `Screen.*` 路由名
- `ChainOfThoughtScope` 接口签名
- ChatService / GenerationHandler / Provider / Room / Terminal / SAF
- `experiment/ui-redesign-20260505` 分支（Codex 的工作区，盲做）
- `docs/CODEX_TIMELINE_UI_REDESIGN_HANDOFF_*.md`（已读完后不再回看）

---

## 6. 比稿规则记录

| | Claude (我) | Codex |
|---|---|---|
| 分支 | `amberagent/ui-m3-expressive` @ `b2859c64` | `experiment/ui-redesign-20260505` @ `741b1e93` |
| 风格 | M3 Expressive 激进 | M3 标准 + 克制 |
| 范围 | timeline + composer + live status + settings + bubble | 同 |
| 视觉武器 | shape morph / wavy / ButtonGroup / SplitButton / Cookie shape | 标准 RoundedCornerShape / 线性 progress / 排版打磨 |
| APK 输出 | `Downloads/AmberAgent-v0.8.3-claude.apk` | `Downloads/AmberAgent-v0.8.3-codex.apk` |
| 盲做承诺 | 不读对方分支 / mockup / audit doc | 不读对方分支 / mockup / audit doc |

---

**Phase 0 结束**。等用户审过这份 audit，确认范围 + 阶段顺序，再进入 Phase 1。
