# V3 Refactor Handoff — Session 3 (2026-05-24)

> 接续 `docs/V3_HANDOFF_SESSION_2_2026-05-23.md`。Session 3 主要做大量精修与 bug 修复：主题色全局贯通、组件双层嵌套消除、动画/律动调整、loading 体验、各类硬编码蓝色清扫。

## TL;DR 当前状态

| 项 | 状态 |
|---|---|
| Build variant | `refactortest`（applicationIdSuffix `.refactortest`） |
| 包名 | `me.rerere.amberagent.refactortest` |
| 真机 | OPPO PMA110，`3B164901CEF00000` |
| 主分支 | `feature/chat-ui-refresh`（未 push） |
| 最新 APK | `app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk` |
| Subagent reviews | 本 session 没跑（直接迭代 + 真机验证） |

## 本 session 完成 ✅

### 设置页 / Provider 配置

| 项 | 改动 |
|---|---|
| **SettingProviderConfigPage Field 平卡输入** | 新建 `FlatTextField` (Input.kt) 替换 14 处 OutlinedTextField。设计稿 spec: 12sp label 在上 + 12dp 圆角平卡 + hair border + 14.5sp ink + mono 字体支持 |
| **SettingModelPage hero + 辅助任务两段化** | Chat hero 用 36/20 leadingIcon accent + 15sp/12.5sp + ModelSelector inline mode (22dp logo + 14.5sp accent + chevron-down)。辅助任务 ModelPickerRow 改 inline picker (padding start 42dp 对齐 leadingIcon 右边) |
| **WorkspaceTopBar 抽象** | `WorkspaceStyle.kt` 末尾新增 `WorkspaceTopBar` (22sp Medium + 字距 0.3 + workspace.paper + scheme.primary 跟主题)。替换 ~15 个 setting page (含 SettingPage 主页 / Display / Model / Provider / Search / TTS / Sandbox / Web / Files / Mcp / AgentMemory / AgentExecution / AgentExtensions / AgentPermissions / SystemAccess / SlidesFont / About / ExperimentalModelCouncil / 等) |
| **Provider 设置页 V3** | (1) 顶部 action icon `workspace.blue` → `scheme.primary`. (2) ProviderItem logo 双层嵌套 (圆角矩形 + 内圆形) 改单层 40dp CircleShape, AutoAIIcon(color=Transparent, size=28dp) 避免内置 Surface 圆形 bg 形成"双重圆环". (3) 启用/禁用对比加强: enabled = `accent 30% spot` (Paper 砖红 / Whisper 天蓝 / Plain 深灰 / Midnight 冷靛蓝) + row bg `accent 18% over surface`; disabled = transparent |
| **WorkspaceSearchField 统一为 drawer Amber 下方风格** | CircleShape 999 + chatTheme.searchBarBg + 1dp hair border + HugeIcons.Search01 16dp inkSoft + 14.5sp inkFaint placeholder. Provider 页 / SettingSearch 页 / Model picker 内搜索都改用同款 |
| **Model picker 优化** | (1) 字号 13.5 → 12.5sp + lineHeight 16 → 15sp. (2) 删 V3CapabilityIcons (chat/text/tool/AI 图标行没意义). (3) 选中区分裂修复: 把 `combinedClickable` 从 Card 内 Column 移到 Card 外层 modifier. (4) 底部 provider chip 三层嵌套 → CircleShape capsule + AutoAIIcon(color=Transparent) 16dp + 12.5sp text 单层. (5) reasoning segment 高度压缩: 外 padding 2→1.5dp, 内段 vertical 2→1dp, 字号 11→10sp + lineHeight 12sp |
| **Model picker 双击关闭** | 点非 active model → 切换 model 但保持 picker 开 (让用户顺手改 reasoning level); 点已 active model → 关闭 picker |
| **Claude adaptive auto** | `reasoningLevelsForModel` Claude 段集加 auto: `auto/low/med/high/xhigh/max` (6 段). ClaudeProvider 已映射 AUTO → thinking.type=adaptive |
| **子代理实验"限制" Select 右对齐** | `SubAgentSelectRow` Select 去掉 `width(112.dp)` 硬约束, Select 自适应内容 + weight(1f) Text 撑开后自动靠 row 右端 |
| **颜色模式下拉"跟随系"截断修复** | strings.xml 中文 (zh + zh-rTW) `_light` → "浅色模式" / `_dark` → "深色模式" (跟"跟随系统"齐 4 字) + Select.kt DropdownMenuItem `softWrap = false` + `widthIn(min = 140.dp)` |
| **隐藏 Web 服务器入口** | SettingPage 用 `val SHOW_WEB_SERVER = false` 守护, 改 true 恢复 |
| **聊天主题切换器** | SettingDisplayPage 把"Notion style"项重写: headline "聊天主题" + supportingContent `WorkspaceSegmentedChoice(微光/素白/暖纸)`. 删除下面重复的"聊天主题 (V3)" FilterChip 项 |
| **WorkspaceSegmentedChoice 胶囊化** | 外 6dp 圆角 + 内 4dp 圆角 → 两层都 CircleShape. 启动入口 / 聊天主题切换 一并变胶囊 |
| **飞书办公"保存包名" + "已就绪" pill 蓝色** | `ExperimentActionButton` primary 容器 `workspace.blue` → `scheme.primary`; `ExperimentBooleanPill` ready 态 `workspace.blue` → `scheme.primary/primaryContainer` |
| **Skill 详情加号按钮蓝色** | `WorkspaceIconButton(tone=Accent)` 删 `containerColor = colors.blueContainer` 硬编码, 让 tone 自己映射 scheme |
| **奥黛门 / 显示设置主题色全局** | Theme.kt `themedColorScheme` 加 `surfaceBright = chatTheme.containerHighest` + `surfaceDim = chatTheme.containerLowest`. dynamicLight 不再给浅蓝 tinted |

### Chat 流 / 消息渲染

| 项 | 改动 |
|---|---|
| **User message 隐藏头像 + 长按弹菜单** | `ChatMessage.kt` USER 分支用 `val SHOW_USER_AVATAR = false` + `val SHOW_USER_ACTION_BUTTONS = false` 守护; user bubble Surface 改 `combinedClickable(onClick=edit, onLongClick=openSheet)` |
| **ChatMessageActionsSheet 改造** | 加 `onCopy` + `onRegenerate` 行 (复制 / 重试); 删除"网页视图渲染" (`onWebViewPreview`) 项 |
| **Assistant avatar 固定 "Amber"** | ChatMessageAvatar 把 `model.displayName` 替成 `"Amber"` 固定字符串 |
| **思考展开 V3** | (1) ChainOfThought wrapper Card 改 `Color.Transparent + 0 elevation + 0 padding` 取消外框. (2) 加 `drawTimeline: Boolean = true` 参数, 多 step 链条 (含 tool steps) 时调用方传 false, 不画 wrapper 竖线避免工具串. (3) Reasoning 加 brain icon (Brain02 14dp accent) + `flushContent=true` 让 content 起始 X 跟 step icon center 对齐 (12dp). 删 ReasoningContent 内部 drawBehind 引用竖线 (依赖 wrapper timeline). icon 后面遮挡 bg 用 `scheme.background` 而非 LocalCardColor (透明时遮挡失效 bug). step content 加 `AnimatedVisibility(expand + fadeIn / shrink + fadeOut)` 过渡 |
| **AgentToolCallCapsule V3 比例** | `fillMaxWidth()` → `wrapContentWidth + widthIn(max=460dp)` 自适应宽度; 删除右侧 "失败" status pill (左侧 16dp red X badge 已表达); padding 3/10/3/3 (~22dp 高); 11.5sp letter 0.2 W500/W400 |
| **AskUserStep V3** | 已在 session 2 完成, session 3 维持 |
| **SandboxPeekBar V3** | (1) 缩略图 118×78 4:3 → 72×96 3:4. (2) 状态条 10dp 圆角矩形 → CircleShape 胶囊 + 3/10/3/3 padding + 11.5sp/10.5sp text + 9dp chevrons. (3) Status badge 20dp → 16dp accent 圆 + 10dp 白勾. (4) 父级 spacedBy 8 → 2dp 让卡片紧贴 composer. (5) 字号再压: 10.5 / 9.5sp |
| **Composer pill** | (1) 圆角 CircleShape → RoundedCornerShape(38dp) (= 76/2 单行 SendOrb 撑起的高度的一半, 保持单行完美半圆胶囊, 多行扩高时不再变更圆). (2) 高度 fix 68dp → heightIn(min=60dp) 允许多行扩展. (3) 左右 padding 12dp (跟屏幕边线留 12dp). (4) TextField `.absoluteOffset(x=-8dp)` + Row padding(start=8) + spacedBy(0) 让光标位置左移 ~20dp 抵消 M3 TextField 内置 16dp content padding |
| **深色阴影修复** | composer Surface `Modifier.shadow` ambient/spot 在深色下强制 Color.Black, 避免 default 白色 shadow 在 Midnight 上扩散成"白晕". border 改顶 5% 底 16% 白模拟落地下沉感 |
| **欢迎语两行** | `"Hi $nick，\n今天想聊点什么？"` + lineHeight 1.4f + Center align |
| **底部 NerdLine 隐藏** | `val SHOW_NERD_LINE = false` 守护两处 (ChatMessage / ChatMessageVirtualItems), token 数 / cached / tok/s / 2.3s 整行不显示 |
| **翻译按钮隐藏** | `val SHOW_TRANSLATE = false` 守护 (ChatMessageActionButtons) |
| **SearchImageBlock 3:4** | (session 2 已改 4:3 → 3:4, session 3 维持) |
| **Blockquote 灰蒙层 bug** | Markdown.kt / MarkdownNew.kt 两处 `drawWithContent` (bg 画在 content 之上盖住文字) → `drawBehind` (bg 在文字后). alpha 0.2 → 0.12 减轻蒙层感 |
| **Markdown 黑字 bug** | Theme.kt CompositionLocalProvider 加 `LocalContentColor provides themedColorScheme.onSurface`. M3 默认 LocalContentColor = Color.Black, 没 Surface 包裹时深色模式黑字看不见 |

### Drawer / TopBar / ContextRing

| 项 | 改动 |
|---|---|
| **Drawer 圆角** | `drawerShape` 0dp 直角 → `RoundedCornerShape(topEnd=24dp, bottomEnd=24dp)` 右侧圆角 |
| **"最近" section label** | 跟 V3NavRow accent=true 同款: HugeIcons.Time02 19dp accent + 15sp Medium accent + 字距 0.2 |
| **TopBar 模型名 ⌄ 移除 + ripple 胶囊** | ModelSelector minimalText 模式删 `Text("⌄")` chevron, Row 加 `clip(CircleShape).clickable.padding(12h,6v)` ripple 跟随胶囊 |
| **抽屉按钮 / 新聊天按钮 / ContextRing ripple 圆形** | `Box(.size(36dp).clip(CircleShape).clickable)` ripple 跟随圆形 |
| **ContextRing 白色 bug** | Midnight `contextEmpty` 默认 0xFFD6D9DE 浅灰在深底变白. override `contextEmpty = 0x38E8EAEF` (跟 contextTrack 同色) |
| **ContextRing popup 改进** | (1) width 290 → 260dp (左边不顶屏幕边). (2) 5h/周额度行守护 `quotaSupported = false` 默认隐藏 (大部分 model 没限额查询). (3) 底部 meta strip 从 Row 横排改 Column (label 上 / value 下) + Row SpaceBetween, 避免"速度"换行. (4) 删菱形箭头 (rotate 45° square 没遮住下半看着像奇怪对话框勾). (5) 加 popup 出场动画 `Animatable(0→1) tween(180ms) + fadeIn + scale 0.94→1` + transformOrigin (92%, 0) 从 ring 位置扩开 |

### 输入法 / 命令 / 互动

| 项 | 改动 |
|---|---|
| **TextField 圆角 + 半圆胶囊** | composerShape `RoundedCornerShape(38.dp)` = 单行 76/2 半圆胶囊; 多行扩高时半径固定不再变圆 |
| **SendOrb 呼吸增强** | outerScale 0.85↔1.50 / outerAlpha 0.40↔0.90 / innerScale 0.95↔1.30 / innerAlpha 0.65↔1.0 (比原振幅大). Paper sendHalo 砖红 alpha 50% (`0x80B5683A`) 适中清淡 |
| **Bloom 律动增强** | (session 中改了又改, 当前 radiusBreath 0.92↔1.05 / alphaBreath 0.88↔1.12 未提交改大版本, 留待 session 4 fine-tune) |
| **Session 切换 spinner** | (尝试过多种方案, 最终撤了 overlay. 当前 hero block 用 `!timelineLoadState.initialized → 居中 spinner / initialized=true && messageNodes empty → hero`. bloom 加 `!initialized → 锁定对话态低值` 避免切换瞬间 messageNodes 空快照触发 bloom 误升) |
| **斜杠命令面板 Popup 化** | (1) `SlashCommandPanel` 用 `Popup + 自定义 PopupPositionProvider` 浮在 composer 上方, 不占布局空间. y = anchor.top - panel.height - 8dp gap; x = 屏幕居中 = composer 左右齐平. (2) 显示条件 `isFocused && slashQuery != null` → `slashQuery != null` (点 / 按钮 append 后即弹, 不依赖 TextField 获焦). (3) width = `LocalConfiguration.screenWidthDp - 24.dp` 跟 composer 横向齐平. (4) border `workspace.hairline (12% ink)` → `chatTheme.surfaceEdge (5% ink)` 更细; shape 10dp → 14dp; shadow 1dp → 8dp |
| **"编辑中" 框隐藏** | `val SHOW_EDITING_BANNER = false` 守护 (硬编码蓝色 + 顶 composer 内部) |
| **斜杠图标主题色** | SlashCommandRow 内嵌图标 `workspace.blueContainer/blue` → `scheme.primaryContainer/primary`; SlashCommandLeadingMark 同改 |
| **发送按钮左侧 `/` 跟字体** | Canvas 画 line 改 `Text("/")` 字符, 跟 SlashCommandLeadingMark 同字体, 角度一致 |
| **Heatmap 主题色** | StatsPage `HeatmapGridCanvas` + `HeatmapCell` 改用 `LocalChatTheme.current.accent` + `toolPillBg` 替代 `MaterialTheme.colorScheme.primary/surfaceVariant` (即便 dynamicColor 开 Material You 也跟主题) |
| **agents.md/soul Card 蓝底** | SettingAgentMemoryPage Card colors 强制 `chatTheme.surface` 替代 `CustomColors.cardColorsOnSurfaceContainer` |
| **MessageJumper 浮卡** | border `outlineVariant@60% (粗黑勾边)` → `chatTheme.surfaceEdge` 极淡; 加 `Modifier.shadow(elevation=8dp, ambient/spot = composerShadow)`, 关闭 tonalElevation. divider 用 chatTheme.hair, icon tint chatTheme.inkSoft |

## 本 session 未完成 / 待 session 4 ⏳

| 项 | 状态 |
|---|---|
| **Bloom 律动幅度** | session 中改了 radiusBreath 0.78↔1.25 / alphaBreath 0.55↔1.30 但被 reverted. 用户希望"更明显"的呼吸. 待 session 4 重新调 |
| **Provider 组卡内 model 行左侧分界小缺口** | 用户提到但没看代码定位. ProviderGroup wrapper 的 hairline divider 缩进可能不对齐 |
| **Editing banner 真正修复** | 目前是 `SHOW_EDITING_BANNER = false` 整块隐藏. 长期方案应当: hoist 状态到父级让它浮在 composer 之上 + 改主题色 |
| **真机三主题回归** | 当前主要在 Paper 主题验证. Whisper / Plain / Midnight 全主题二级页 alignment 没系统跑完 |
| **WorkspaceTopBar 抽象统一** | 已替换 ~15 页, 但还有些边缘页 (SettingExperimentalOfficeProPage / SettingExperimentalSubAgentPage / SettingExperimentalModelCouncilPage 内部 SubAgent / Council 等) 没替换. 各 page 子页面 (SettingProviderConfigPage 等) 也没替换 |
| **ResultPill 集成到 SearchImageBlock 边** | session 2 留下的 (S) 任务. 数据层缺 tool/query/page 字段, 没接 |
| **ToolResultPreview FakeBrowser → 真实 web 内容** | session 2 留下的 (M) 任务 |
| **SettingProviderConfigPage Field 平卡** | session 3 已用 FlatTextField 替换 OutlinedTextField. 但 GoogleAuth OAuth / Codex OAuth 子表单内部还有 Checkbox / SegmentedButton 等没动 V3 化 |
| **SettingModelPage hero + 辅助任务两段化** | 改了 inline picker 但内部细节 (icon padding / chip 样式) 没全 align 设计稿 |

## 踩过的坑（务必记住）

### 1. M3 TextField 不可直接修改 content padding
M3 state-based `TextField` 内置 16dp horizontal content padding 无法通过 API 改. 想压缩光标位置只能:
- 减外层 Row padding + spacedBy
- 用 `Modifier.absoluteOffset(x=(-N).dp)` 视觉左移 TextField (不影响布局尺寸, 不会跟旁边元素重叠)

### 2. M3 LocalContentColor 默认 Color.Black
没有 Surface 显式提供 contentColor 时 LocalContentColor = `Color.Black`. 深色主题下 ChatPage 直接调 MarkdownBlock 的无 bubble 路径会黑字看不见.
**修复**: Theme.kt CompositionLocalProvider 显式 `LocalContentColor provides themedColorScheme.onSurface`.

### 3. `drawWithContent` 把 bg 画在 content 之上
Markdown.kt / MarkdownNew.kt 的 BlockQuote 用 `drawWithContent { drawContent(); drawRect(bg, ...) }` —— 先画文字再画灰矩形覆盖. **改 `drawBehind`** 让 bg 画在 content 之后.

### 4. CircleShape 在 Surface 高度变化时半径 = 高度/2
Composer pill 多行扩高时 CircleShape 越变越圆. 用 `RoundedCornerShape(N.dp)` 固定半径让单行 = height/2 = 完美半圆, 多行扩高时圆角不变.

### 5. AutoAIIcon 内置 Surface 圆形 bg
AutoAIIcon 内部用 `Surface(shape = avatarShape, color = secondaryContainer)` 画品牌 logo. 外层再包圆形 bg → "双重圆环". 传 `color = Color.Transparent` 让内层 Surface 透明.

### 6. ChainOfThought icon 后面遮挡 bg = LocalCardColor
原代码 icon Box.background(LocalCardColor.current) 遮挡 wrapper timeline 竖线. 我把 cardColors.containerColor 改透明后 LocalCardColor 也透明 → 竖线穿过 icon. **修复**: `maskColor = if (transparent) scheme.background else cardColors.containerColor`.

### 7. ChainOfThoughtScope interface 加参数需要同步 override
加 `flushContent` 参数到 `ControlledChainOfThoughtStep` interface + impl 必须同步, 否则 override 错配.

### 8. Popup + AnimatedVisibility 不兼容
Popup 不在 layout 树, AnimatedVisibility 包它无效. 想给 Popup 加入场动画用 `Animatable(0→1) + graphicsLayer { alpha; scale; transformOrigin }`.

### 9. Loading overlay 越改越烂
试过 spinner overlay + animation 衔接, 引入多次闪烁. 最终撤回, 只在 hero block 加 `!initialized → spinner / initialized + messageNodes empty → hero / messageNodes non-empty → ChatList`. 简单方案最优.

### 10. Bloom 误升 bug
切换 conversation 瞬间 messageNodes 空快照, bloomTarget 误判为"空白态"开始 700ms 渐变到 1.0, messageNodes 填充后又反向. **修复**: bloomTarget 加 `!initialized → 锁定对话态低值`.

### 11. Slash panel 浮位置 + focus 双 bug
(1) panel 在 TextInputRow Column 内顶起 composer pill (占布局空间). 用 Popup + 自定义 PopupPositionProvider 浮在上方.
(2) 点 / 按钮 append "/" 不让 TextField 获焦. 把 `slashVisible = isFocused && slashQuery != null` 改 `slashQuery != null`.

### 12. dropdown menu width = anchor width
M3 ExposedDropdownMenu 默认 width = anchor width. 短 anchor ("浅色" 60dp) → dropdown item ("跟随系统" 80dp) 被截. **修复**: 用 strings "浅色模式" / "深色模式" 让 anchor 拉长; 也给 DropdownMenuItem 加 `softWrap=false + widthIn(min=140dp)` 兜底.

### 13. Build hook 误报 exit 0 但 BUILD FAILED
延续 session 2 教训, 必须验证 `BUILD SUCCESSFUL` + APK mtime > src mtime.

## 关键文件改动

```
新增（本 session）:
  docs/V3_HANDOFF_SESSION_3_2026-05-24.md (本文档)

主要修改:
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
    (composer CircleShape→RoundedCornerShape, heightIn min 60dp, padding 12dp,
     TextField absoluteOffset -8dp, send button shadow 深色 Color.Black,
     "/" 按钮 Canvas line → Text("/"), MessageJumper 浮卡 shadow + surfaceEdge)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInputComposers.kt
    (Editing banner 隐藏, SlashCommandPanel Popup 浮于 composer 上方,
     SlashCommandRow + LeadingMark 主题色, slashVisible 去 isFocused 条件)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInputSandbox.kt
    (SandboxPeekBar 72×96 3:4 缩略图 + CircleShape 胶囊状态条 +
     16dp accent 圆 + 10.5/9.5sp text + 紧贴 composer)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt
    (字号 12.5sp / 删 V3CapabilityIcons / Card clickable 移外层 /
     底部 provider chip 单层 CircleShape / Claude auto / 双击关闭 /
     reasoning segment 高度压缩 / 模型 picker 搜索改 WorkspaceSearchField)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
    (USER avatar 隐藏 / actions 行隐藏 / NerdLine 隐藏 / Sheet 加 onCopy + onRegenerate)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt
    (Sheet 加 copy + regenerate row, 删 onWebViewPreview row, 翻译按钮隐藏)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt
    (model.displayName 固定 "Amber")
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageMessagePartsBlock.kt
    (user bubble combinedClickable long-press → sheet, DisableSelection 屏蔽系统 ActionMode,
     ChainOfThought drawTimeline = isReasoningOnlyBlock)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt
    (brain icon + flushContent=true + 删除 ReasoningContent 内部 drawBehind 竖线)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
    (AgentToolCallCapsule wrapContentWidth + 删失败 pill + V3ToolLeadingBadge)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageVirtualItems.kt
    (NerdLine 隐藏 + Sheet 加 onCopy/onRegenerate)
  app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
    (BlockQuote drawBehind 修复)
  app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt
    (HtmlBlockquote drawBehind 修复)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt
    (Card transparent + drawTimeline param + flushContent param + maskColor 修复 +
     AnimatedVisibility content expand)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/Input.kt
    (新增 FlatTextField — V3 平卡输入)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/Select.kt
    (DropdownMenuItem softWrap=false + widthIn min 140dp)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/WorkspaceSearchField.kt
    (drawer Amber 下方风格: 999 capsule + chatTheme.searchBarBg + HugeIcons.Search01)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/WorkspaceStyle.kt
    (WorkspaceTopBar helper + Tone.Accent 用 scheme.primary)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt
    (drawerShape 右侧 24dp 圆角 + "最近" label V3NavRow accent 风格)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatListSubViews.kt
    (MessageJumper 浮卡 surfaceEdge + shadow 8dp)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
    (TopBar ripple 圆形 + 欢迎语两行 + bloom !initialized 锁低值 + spinner hero block 共存)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatTheme.kt
    (Midnight contextEmpty override + Paper sendHalo 0x80B5683A)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ContextRing.kt
    (260dp panel + ripple 圆形 + meta strip 列 + popup 入场动画 + 删菱形箭头)
  app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/SkillDetailPage.kt
    (加号按钮去硬编码 blueContainer)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayPage.kt
    (Notion style → 聊天主题切换器 + WorkspaceSegmentedChoice 胶囊)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalPage.kt
    (ExperimentActionButton + ExperimentBooleanPill scheme.primary)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalSubAgentPage.kt
    (SubAgentSelectRow Select 自适应宽度)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPage.kt
    (chat hero + ModelSelector inline + WorkspaceTopBar)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt
    (WorkspaceTopBar + 隐藏 Web 服务器)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt
    (WorkspaceTopBar + ProviderItem 单层 logo + enabled/disabled accent 区分)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAgentMemoryPage.kt
    (soul Card 强制 chatTheme.surface 避免蓝底)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
    (14 处 OutlinedTextField → FlatTextField)
  app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsPage.kt
    (heatmap 改 chatTheme.accent + toolPillBg, 强制跟主题)
  app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt
    (themedColorScheme 加 surfaceBright/Dim + LocalContentColor provides onSurface)
  app/src/main/res/values-zh/strings.xml (浅色模式 / 深色模式)
  app/src/main/res/values-zh-rTW/strings.xml (淺色模式 / 深色模式)
  + ~15 个 setting page 同步用 WorkspaceTopBar 替换 TopAppBar
```

## 重要硬约束（延续）

- 不 push，除非用户明确要求
- 用户主 app `me.rerere.amberagent.notion` 不能被覆盖（refactortest 是独立 build variant）
- AmoledDark 用户的纯黑省电选择不能被 chatTheme.bg 覆盖（已修，`useAmoledBlack` 标志）
- dynamicColor 用户的 Material You 偏好不能被静默覆盖（已修，`shouldApplyChatTheme` 跳过 override）
- adb install 卡死时换 `pm install` 绕过

## 构建/装机命令

```bash
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home \
  ./gradlew --offline -Pksp.incremental=false :app:assembleRefactortest

# 验证: BUILD SUCCESSFUL 真假 + apk mtime > src mtime
stat -f "apk: %m %Sm" app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk
stat -f "src: %m %Sm" <last edited file>

# 装机:
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 push \
  app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk /data/local/tmp/refresh.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 shell pm install -r -t /data/local/tmp/refresh.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 shell am force-stop me.rerere.amberagent.refactortest
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 shell am start -n me.rerere.amberagent.refactortest/me.rerere.rikkahub.RouteActivity
```

## 用户协作偏好（从本 session 摸出来的）

- "不要直接动手做": 重大方向决策先列方案让用户选, 不要直接 implementation
- "保留代码不删除": 用户希望隐藏 feature 用 `val SHOW_X = false` 守护, 改 true 即恢复
- 像素级对齐: "我感觉实现的美感仍然很差很差" / "不能跟设计稿对齐吗?" → 认真对照原稿
- 严禁硬编码 workspace.blue / blueContainer 等: 必须跟主题 `scheme.primary / chatTheme.accent`
- 双层嵌套 = 反模式: 用户反复指出"框中框", 始终单层
- 律动 / 动画 / 阴影都要"有感觉但不突兀": 调到中间值

## END
