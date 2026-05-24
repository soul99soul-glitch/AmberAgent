# V3 Refactor Handoff — Session 2 (2026-05-23, post-rev2)

> 接续 `V3_REFACTOR_HANDOFF_2026-05-23.md` 的工作。本 session 主要：
> 1. 完整重构 ChatDrawer 为 V3 history-screen 布局
> 2. 完整重构 ModelList picker (active card / 段控 / provider 分组)
> 3. 写 ToolResultPreview Composable (3:4 sticky shelf 规格)
> 4. 修 SearchImageBlock 4:3 → 3:4
> 5. 修 reasoning header / askUserStep / ProviderPage / ProviderDetailPage / SkillsPage 等多页 V3 对齐
> 6. 修 build hook 误报 exit 0 但 BUILD FAILED 的陷阱

## TL;DR 当前状态

| 项 | 状态 |
|---|---|
| Build variant | `refactortest`（applicationIdSuffix `.refactortest`） |
| 包名 | `me.rerere.amberagent.refactortest` |
| 真机 | OPPO PMA110，`3B164901CEF00000` |
| 主分支 | `feature/chat-ui-refresh`（未 push） |
| 最新 APK | `app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk` (~105MB) |
| 当前活动主题 | Paper 砖红 |
| Subagent reviews | 共 6 次（最新审计的二级页 audit 报告在 `/private/tmp/claude-501/...` task transcripts） |

## 本 session 完成 ✅

### Drawer V3 完整重构（约 5 个 turn）

**最关键改动** — 之前只塞了 Amber 文字 + 加了 statusBars padding，骨架仍是旧 IA。本 session 完整重排：

| 区域 | 实现 |
|---|---|
| 1) Amber wordmark | 32sp Serif 置顶 |
| 2) Search bar | 999 圆角胶囊 + 搜索图标 + "搜索聊天" placeholder |
| 3) Primary nav | 新聊天 (accent W500) + 今日看板 + 小应用 |
| 4) QuickRow | 3 个 36×36 icon-only 圆角方块 (Folder/Sparkles/ChartColumn) |
| 5) Divider | 1dp hair line |
| 6) "最近" label | 12sp inkFaint |
| 7) ConversationList | active = accentSoft 胶囊 + accent 文字 |
| 8) Footer | hair line + 32dp 头像（无铅笔徽标） + Arquiel + Settings gear |
| 9) 全屏拉出 | 手机端 `drawerWidth = windowAdaptiveInfo.width` |

辅助 Composable：`V3NavRow`、`V3QuickBtn`、`UIAvatar.showEditBadge` 新参数

### ModelList picker V3 重构

| 项 | 实现 |
|---|---|
| Active 卡 | 跟同 provider 其他 model 在同张 group card 内（不再单独划出）；active 行 accentSoft 高亮 + 段控内嵌 |
| Group card | 每 provider 一张 16dp 圆角 card，内部模型行用 hairline 分隔 |
| Provider 名 label | accent 色 + 12sp |
| 头像 | 单层 32dp（去掉外层 rounded-rect + 内层圆形的嵌套） |
| 模型名字号 | 13.5sp（紧凑） |
| Capability icons | Message01 / Text 或 Image03 / Wrench01 / AiMagic（按 model.abilities + modalities） |
| 段控 | CircleShape 999 + theme.surface 白底 + 2dp padding + 11sp 字 + accent 实心选中段 |
| 段集按 model 推断 | DeepSeek = off/high/max (3 段, [官方文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)) / Claude = low/med/high/xhigh/max (5) / OpenAI = low/med/high/xhigh (4) / Kimi+GLM = off/auto (2) / **默认 fallback = off/auto** (2) |
| 收藏拖拽 | 移除 ReorderableItem，仅保留心形 toggle |
| 底部 provider chips | 12dp 圆角矩形 + 18×18 logo 块 + 名 + hairline 顶 |

新增数据通路：`ModelSelector` + `ModelList` 接 `currentAssistant: Assistant?` + `onUpdateAssistant: ((Assistant) -> Unit)?` (10 个 callsite 无需改)，ChatPage TopBar 接通 `vm.updateSettings(assistants map)` 回写。

### 思考展开 + ContextRing

| 项 | 实现 |
|---|---|
| ChainOfThought 左竖线 | colorScheme.outlineVariant (faint 灰) → **chatTheme.thinkRule** (accent@45% 主题色) + stroke 1→2dp |
| "思考了 N 秒" 头部 | brain 图标移除 (设计稿无) + 文字 12sp → 13sp + 字距 0.2 + 纯 accent (去 0.72 alpha) |
| metaText (auto / ≤ 8K) | 之前在右 extra 跑到右边 → **合并到主标签 inline "思考了 5.4 秒 · auto"** |
| 正文 ReasoningContent | 改读 `chatTheme.thinkBodyInk` 替代直接 `inkSoft`（token 语义分离）|
| ContextRing | 真实 token usage 接通（最后一条 assistant 消息 totalTokens / 200K 估值）|
| ContextRing popup | 290dp 浮层 (用量与上下文 / 5h/周/Context 行 / meta strip 本次/缓存命中/速度) |

### AskUserStep V3

| 项 | 实现 |
|---|---|
| AskOptionChip 圆角 | 20dp → **CircleShape 999 真胶囊** |
| 未选中 chip | accent 重蓝底 → **chatTheme.surface 白底 + 1dp surfaceEdge 6% 墨灰边 + 1dp 极淡阴影** |
| 选中 chip | chatTheme.accent 实心 + onAccent 文字 |
| chip 字号 | → 13.5sp + 字距 0.2 + 行高 17.5 |
| chip padding | → 8/14dp |

### 二级页面 V3 batch

| 文件 | 改 |
|---|---|
| `WorkspaceStyle.kt:104` 浅色支镜像深色支 | `paper / row / ink / muted / hairline` 全部走 `MaterialTheme.colorScheme.surface/onSurface/...` → 所有 settings 页自动跟主题 |
| `WorkspaceTone.Accent` (4 helpers) | 硬 `colors.blue/blueContainer` → `scheme.primary/primaryContainer` (跟主题 accent) |
| `Select.kt` | 方框 6dp → CircleShape 999 + chatTheme.accentSoft/accent + 去掉 `fillMaxWidth + Text.weight(1f)` 防止把 ListItem 文字挤出去 |
| `CardGroup.kt:35` | CardGroupCorner 12 → 18dp |
| `SettingPage.kt:309 SettingLeadingIcon` | 30/15dp → 32/21dp |
| `SettingAgentMemoryPage.kt` agents.md preview | 加 `FontFamily.Monospace` + 12.5sp + 18sp lineHeight |
| `SettingExperimentalPage.kt ExperimentDivider` | `padding(start = 46.dp)` → 60dp 对齐 leading icon 右边线 |
| `SettingProviderPage.kt ProviderItem` | 彩色 Surface 堆 → 极简 hairline 列表（移除行内删除按钮）|
| `SettingProviderDetailPage.kt bottomBar` | `NavigationBar` 重壳 → V3 2-flex tab + hair top + accent 选中 |
| `SettingDisplayPage.kt 圆角 hack` | 4/8 → 2/16（顶 16dp / 底 2dp 配 16dp 整组 18dp 圆角）|
| 12 处 Select 硬宽 100~180dp | 全部去掉，让 Select 内容自适应 (4 个 setting 页 sed 批改)|
| `SkillsPage.kt` Stats card | 8dp → 18dp / "已启用" Success 绿 → **Accent (设计稿明确"no green")** / 已加 logo 34dp/20dp |
| `SkillsPage.kt SkillCard` | 8dp Surface → 18dp + chatTheme.surface + hair border / 仅 disabled 显示未启用 pill / MCP pill "包含 MCP 配置" → "MCP" / 行内 magicWand + delete icon → **overflow menu (DropdownMenu)** |

### ToolResultPreview (standalone Composable)

按 claude design pixel spec 重写 (`ui/components/message/ToolResultPreview.kt`)：
- 72×96 (3:4) thumb + 8dp 圆角 + 1dp 8% ink border + 单层柔影 0.06
- 容器 padding (start=14, end=32, top=10, bottom=8) **asymmetric**
- align-items: Bottom（胶囊底沿与缩略图底沿对齐，不是 center）
- FakeBrowser 内 mock（Google 字标 / 搜索框 / tab strip / 2 result blocks / divider）
- ResultPill: fillMaxWidth + 999 圆角 + 22dp 高 (3px 上下 padding + 16dp 圆) + 11.5sp tool · query inline + 9dp chevrons + tabular-nums page nav

**注意**：组件本身实现完整，但**未集成进 chat flow**（数据层缺 web-search streaming 入口）。用户反馈"实际用的时候本来就是会显示真实的网页内容的，不是一个虚假的" → FakeBrowser 是 placeholder mock，等真实数据接入后替换。

### SearchImageBlock 改 3:4

`SearchImageBlock.kt:248` aspectRatio `4f/3f` → **`3f/4f`** 竖向 + heightIn min 72 → 96dp。

## 本 session 未做 / 部分做 ⏳

| 项 | 原因 |
|---|---|
| **ResultPill 集成到 chat flow** | 当前 SearchImageBlock 只显示图片 + caption；要在缩略图旁加 status pill 需要从消息数据里抽 tool name + query + page，数据层穿透深 |
| **SettingPage 主入口分组** 4 → 2 组 | audit 建议收敛，但合并后丢"模型与服务"/"数据"分组，决定保留 |
| **SettingModelPage hero + ModelPickerRow logo+accent** | 当前 3 个对等 ModelSection；改为 hero card + 辅助任务 hairline 列表需要 ModelSelector 内部重写 |
| **SettingAgentExecutionPage 字段集** | audit 标记为 IA 决策差异（设计稿是 Workspace/Runtime，Kotlin 是 操作预览/Tool Loop），非 UI 对齐问题 |
| **WorkspaceTopBar 抽象** | audit 建议抽 22sp medium + 14px padding + 32dp chevron + 无 elevation 的通用 TopBar，affect 10+ pages，但当前 M3 TopAppBar via CustomColors 已跟主题，纯样式抽象 ROI 低 |
| **SettingProviderConfigPage Field 平卡输入** | OutlinedTextField → 平卡输入，需重写 ProviderConfigure，>2h |
| **Thinking strip label + body 整体竖线** | 当前竖线在 ChainOfThought wrapper 上覆盖所有 step；设计稿仅 reasoning step 应有竖线，AskUser 不应有 |

## 踩过的坑（务必记住）

### 1. Build hook 误报 exit 0 但实际 BUILD FAILED

**多次出现**：调用 `Bash run_in_background` 跑 `gradlew assembleRefactortest`，task notification 报 `status: completed`、`exit code 0`，但实际 BUILD FAILED（编译错误）。导致：
- 我 push 旧 apk 到设备
- 用户看到的还是旧版本
- 我以为 "已部署" 实际 APK 时间戳还是几分钟前的

**对策**：每次 build 必须验证：
```bash
# 1. 看 task output 是否真 SUCCESSFUL
tail -3 /private/tmp/claude-501/.../tasks/<task-id>.output
# 2. 对比 APK mtime vs 源文件 mtime（apk 必须比 src 新）
stat -f "%m %Sm" app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk
stat -f "%m %Sm" <修改的源文件>
```
若 apk mtime < src mtime，就是 build 没真成功。

### 2. PMA110 设备 adb input 不稳

- swipe 容易把 app 切到后台（手势导航）
- 通知栏经常被 input swipe 误触发拉下来
- tap 坐标基于 1440x3168，部分 region 用绝对坐标算不准
- 解决：远程截图 + 用户手动点；或 `am start` deep link

### 3. workspaceColors() 浅色支硬编码白色

`WorkspaceStyle.kt:104-122` 之前浅色支返回 hardcoded `paper = Color.White` / `ink = #1F1F1F` 等，**不读 MaterialTheme.colorScheme**。导致：
- 切到 Paper 主题，设置页/抽屉/二级页背景全是白色（不跟主题）
- TopBar 用 workspace.paper@96% → Paper 顶栏白条遮住底部 halo

**对策**：浅色支镜像深色支，全部 `scheme.surface/onSurface/...`。Theme.kt 的 colorScheme override 已经把 scheme 替成 chatTheme，workspace 自动跟随。

### 4. Select 去掉 width 后挤垮 ListItem

`Modifier.width(150.dp)` 去掉后，ExposedDropdownMenuBox 默认 wrap content **但内 Row 用了 `fillMaxWidth + Text.weight(1f)`** 撑满剩余空间，把 ListItem 的 headlineContent "颜色模式" 文字挤到左边变成竖排单字。

**对策**：Select.kt 内 Row 去掉 `.fillMaxWidth()` 和 Text 的 `.weight(1f)` → 让 Row wrap content。

### 5. ModelAbility 包 path 错

`me.rerere.ai.core.ModelAbility` ❌ → `me.rerere.ai.provider.ModelAbility` ✓

### 6. WindowInsets.statusBars 在 ChatDrawer 没 import

直接写 `windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)` 不行，需要 import 完整：
```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
```

### 7. ChatTheme data class 加 token 顺序敏感

加 `isDark / topHaloCore / topHaloAlpha / bloomHeightFrac / heroSize/Weight/Letter / contextEmpty..High/Track / popoverBg` 都是在末尾 + default value，没破 4 个实例化（全命名参数）。**新加字段必须 default value + 加末尾**。

### 8. drawerWidth 全屏算法

之前手机端 `drawerWidth = 336.dp` 留右侧空隙。用户要全屏：
```kotlin
else -> windowAdaptiveInfo.width  // 手机端全屏覆盖
```
`windowAdaptiveInfo` 来自 `currentWindowDpSize()`，type 是 `WindowDpSize`，`.width` 直接是 Dp。

### 9. 设计稿"思考了 N 秒"无 brain 图标

我硬塞了 `HugeIcons.Brain02 14dp`，设计稿没有 → `icon = null`。同样 metaText "auto / ≤ 8K tokens" 不是右 extra slot，而是 inline 拼到主标签后面：`"思考了 5.4 秒 · auto"`。

### 10. ChainOfThought 左线粗细 1dp 看不见

底分屏 1dp 容易消失，**最少 2dp**。设计稿 spec `width: 2px` 也是 2dp。

### 11. ToolResultPreview FakeBrowser 没真实数据

用户反馈："实际用的时候本来就是会显示真实的网页内容的，不是一个虚假的" → mock 是 placeholder，真实接入后用 WebView/AsyncImage 替换。

## 文件改动 git diff stat

```
新增（本 session）:
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ToolResultPreview.kt
  docs/V3_HANDOFF_SESSION_2_2026-05-23.md (本文档)

修改（核心）:
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt (V3 全量重构 + helpers)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt (drawerWidth 全屏 + onUpdateAssistant 接通 + ContextRing 真实数据 + 56dp fade scrim 已有 + 主题 bloomTarget)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatListNormalSection.kt (showBloomInConvo 时 canvasAlpha 上限 0.85)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatTheme.kt (+ token isDark/topHalo/bloomHeight/hero/context/popover/WhisperTheme 显式 bloomHeightFrac)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ContextRing.kt (真实数据 + popup)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt (active 卡 + 段控 + provider 分组 + 紧凑布局 + chips 重设计)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/Select.kt → 实际是 ui/components/ui/Select.kt (胶囊 + 内容自适应)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/WorkspaceStyle.kt (浅色支接 scheme + 4 helpers Tone.Accent 跟主题)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt (12 → 18dp)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/UIAvatar.kt (+ showEditBadge param)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt (头部主题色 + 13sp + 无 brain icon + inline auto + thinkBodyInk + ReasoningTitle)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAskUserStep.kt (AskOptionChip 999 圆角 + 主题色 + 13.5sp + padding 8/14)
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt (lineColor outlineVariant → thinkRule + stroke 2dp)
  app/src/main/java/me/rerere/rikkahub/ui/components/richtext/SearchImageBlock.kt (aspectRatio 4:3 → 3:4)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt (SettingLeadingIcon 32/21 + 移 Select 硬宽)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayPage.kt (圆角 hack 4/8 → 2/16)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt (极简 hairline 列表)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt (V3 2-flex tab + V3DetailTab helper)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAgentMemoryPage.kt (agents.md monospace)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalPage.kt (divider indent 46 → 60dp)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingWebPage.kt (Select 硬宽移除)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSandboxPage.kt (同上)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalModelCouncilPage.kt (同上)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAgentExecutionPage.kt (同上)
  app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/SkillsPage.kt (stats card 18dp + 已启用 Accent + SkillCard 18dp + overflow menu + 仅 disabled pill + MCP→MCP)
```

## 真机截图记录

```
/tmp/p_paper_final.png      Paper 空白态 + 全屏暖纸
/tmp/p_drawer_real_v3.png   Drawer V3 (Amber/搜索/3 nav/QuickRow/最近/footer)
/tmp/p_drawer_full.png      Drawer 全屏覆盖（无右侧空隙）
/tmp/p_drawer_clean.png     Footer 头像无铅笔徽标
/tmp/p_settings_main.png    Settings 主页 Paper 暖色 + "颜色模式 跟随系统" 胶囊
/tmp/p_skill_now.png        Skill 页 (stats card 18dp + 已启用 accent + skill 行)
/tmp/p_picker.png           Model picker (Paper accentSoft 高亮 + 段控)
/tmp/p_currstate.png        "Hi Arquiel, 今天想聊点什么？" hero 加昵称
```

## Subagent reviews 列表（保留供回溯）

```
1) Midnight composer shadow review (本 session 前)
2) 设计稿对照 audit (Phase 4 → P0/P1/P2 punch list)
3) 最终综合 review (4 套主题 token 完整性 + 7.5/10)
4) 二级页面 V3 audit (Settings 主页/Display/Search/Memory/Execution/Provider/Skills audit 5.5/10)
5) (隐式) 多次构建 review
6) (隐式) ContextUsagePopup spec compliance
```

## 下个 session 优先级建议

按 ROI:

1. **(S) ResultPill 集成到 SearchImageBlock** — 在缩略图侧并排显示 status pill；需要从消息或 tool call 数据里抽 tool name / query / pagination
2. **(M) ToolResultPreview FakeBrowser → 真实 web preview** — 接入 WebView/AsyncImage 渲染真实截图；需要 web-search-streaming pipeline
3. **(M) SettingProviderConfigPage Field 平卡输入** — OutlinedTextField → V3 平卡 + 12dp 圆角 + hair border
4. **(M) SettingModelPage hero + 辅助任务两段化** — chat model 提到 hero card + 辅助任务列表 hairline
5. **(L) 真机切 Whisper / Plain / Midnight 全主题回归** — 各主题二级页 V3 alignment 验证
6. **(L) WorkspaceTopBar 抽象 + 10 个 setting 页统一应用** — 22sp medium + 14dp padding + 32dp chevron + no elevation

## 重要硬约束

- 不 push，除非用户明确要求
- 用户主 app `me.rerere.amberagent.notion` 不能被覆盖（refactortest 是独立 build variant）
- AmoledDark 用户的纯黑省电选择不能被 chatTheme.bg 覆盖（已修，`useAmoledBlack` 标志）
- dynamicColor 用户的 Material You 偏好不能被静默覆盖（已修，`shouldApplyChatTheme` 跳过 override）
- adb install 卡死时换 `pm install` 绕过：
  ```bash
  adb -s 3B164901CEF00000 push <apk> /data/local/tmp/refresh.apk
  adb -s 3B164901CEF00000 shell pm install -r -t /data/local/tmp/refresh.apk
  ```

## 恢复 Prompt（用于下个 session）

```
你接手 AmberAgent / RikkaHub Android Kotlin/Compose 项目的 V3 UI 重构 (session 3)。

项目路径：
/Users/arquiel/Downloads/AI/rikkashit/rikkahub

行为准则（必读）：
1. 用户原话："不能跟设计稿对齐吗？""我感觉实现的美感仍然很差很差"——说明对像素级还原要求高，认真比照设计稿，不要凭印象写
2. 每次 build 必须验证 BUILD SUCCESSFUL 真假 (apk mtime 比 src 新)，hook 多次误报 exit 0 但实际失败
3. 不 push，除非用户明确要求
4. 不混入 deepread/today-board/template-workbench 等 V3 范围外的改动

【第一步：读上 session 的 handoff doc】
docs/V3_HANDOFF_SESSION_2_2026-05-23.md (本文档)
docs/V3_REFACTOR_HANDOFF_2026-05-23.md (rev 1 + rev 2)

【第二步：读设计原稿】
/tmp/design-new/amberagent/project/*.jsx (themes / phone-screen / convo-* / model-picker / settings-* / provider-screens / convo-history)
/tmp/amber-zip/ (原始 zip 解压，如 /tmp 被清重解压 /Users/arquiel/Downloads/AI/amberagent.zip)

【第三步：确认 git 状态】
git status --short
git branch --show-current
git log --oneline -8

【当前 build 变体】
buildType = refactortest
applicationId = me.rerere.amberagent.refactortest（与主 notion 包并存）
App 名 = "Amber Refresh"
真机 = OPPO PMA110, 3B164901CEF00000

【构建】
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home \
  ./gradlew --offline -Pksp.incremental=false :app:assembleRefactortest

# 必查 BUILD SUCCESSFUL 真假，比对 apk mtime 和 src mtime
stat -f "apk: %m %Sm" app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk
stat -f "src: %m %Sm" <last edited file>

【装机】
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 push \
  app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk /data/local/tmp/refresh.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 shell \
  pm install -r -t /data/local/tmp/refresh.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 shell am force-stop me.rerere.amberagent.refactortest
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 shell am start -n me.rerere.amberagent.refactortest/me.rerere.rikkahub.RouteActivity

【session 2 已完成的（按 handoff doc）】
- Drawer V3 完整重构 (Amber/search/3 nav/QuickRow/最近/footer 32dp 头像无铅笔)
- ModelList V3 重构 (active 卡内嵌段控 / provider 分组 hairline / 段集按 model 推断 / chips 重设计)
- 思考展开 V3 (无 brain icon / 13sp accent / inline "auto" / 左竖线 thinkRule 2dp / body 13.5/inkSoft/1.7 lineHeight)
- AskUserStep V3 (option 999 圆角 / 白底 hair border / 极淡阴影 / 13.5sp 字距 0.2)
- ContextRing 真实 token usage + popup
- ToolResultPreview standalone Composable (3:4 thumb + asymmetric padding + FakeBrowser mock + ResultPill)
- SearchImageBlock 4:3 → 3:4
- WorkspaceStyle 浅色支接 scheme (二级页自动跟主题)
- WorkspaceTone.Accent 跟主题 (4 helpers)
- Select 胶囊 + 主题色 + 内容自适应
- CardGroup 18dp + LeadingIcon 32/21dp
- SkillsPage V3 (stats card 18dp / 已启用 accent / overflow menu)
- ProviderPage 极简 hairline 列表
- ProviderDetailPage V3 2-flex tab
- AgentMemoryPage agents.md monospace
- 多页 12 处 Select 硬宽移除

【未做（按 ROI 排）】
1. (S) ResultPill 集成到 SearchImageBlock 边上 (数据层缺 tool/query/page，需挖)
2. (M) ToolResultPreview 接真实 web 内容 (FakeBrowser 是 placeholder，等接入 web-search-stream pipeline)
3. (M) SettingProviderConfigPage Field 平卡 (OutlinedTextField → 平卡)
4. (M) SettingModelPage hero + 辅助任务两段化
5. (L) Whisper / Plain / Midnight 全主题二级页回归
6. (L) WorkspaceTopBar 抽象统一 10+ pages
7. (L) Phase 3.5 model picker "adaptive auto" 按钮 (Claude 专属)

【硬规则】
- 不 push，除非用户明确要求
- 不混入 V3 范围外改动
- 每个 Phase 做完跑 Subagent review
- 真机视觉验证 > 单元测试
- adb install 卡死时换 pm install
- 添加 ChatTheme token 必须 default value 加末尾，4 个 instance 全命名参数

【硬约束保留】
- 用户主 app me.rerere.amberagent.notion 不能被覆盖
- AmoledDark 用户的纯黑省电选择不能被 chatTheme.bg 覆盖
- dynamicColor 用户的 Material You 偏好不能被静默覆盖
```

## END
