# V3 Refactor Handoff — 2026-05-23 (rev 2)

> rev 2 (本 session 续): Paper 全屏暖纸色修复 / Select 胶囊主题色 / ContextRing usage panel popup
> / 4 项 P1 S 修复 / workspaceColors 浅色支跟主题。详见底部"rev 2 改动"。



> Amber Refresh UI 重构（Anthropic Design "Refined Chat Screens" V3 设计稿）。
> 本文档作为下个 session 的接续入口，覆盖：当前状态 / 已完成 / 设计依据 / 测试方法 / 待办。

## TL;DR

| 项 | 状态 |
|---|---|
| Build variant | `refactortest`（applicationIdSuffix `.refactortest`，与主 `notion` 变体并存） |
| 包名 | `me.rerere.amberagent.refactortest` |
| App 显示名 | "Amber Refresh"（`app/src/refactortest/res/values/strings.xml`） |
| 真机 | 用户 OPPO PMA110，`3B164901CEF00000` |
| 安装命令 | 见下面 [构建 / 安装](#构建--安装) |
| 主分支 | `feature/chat-ui-refresh`（基于 main，未 push） |
| 设计稿位置 | `/tmp/design-new/amberagent/` (新版 README + chat 对话稿) 与 `/tmp/amber-design/` (旧版 jsx 包) |
| 设计 chat 对话稿 | `/tmp/design-new/amberagent/chats/chat1.md` —— **强烈建议读** |
| Subagent reviews | 共 4 次（Phase 1 / Phase 2+3 / Phase 4 迁移 / colorScheme override） |

## 构建 / 安装

```bash
# 构建（refactortest 变体，会带 .refactortest 包名后缀）
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home \
  ./gradlew --offline -Pksp.incremental=false :app:assembleRefactortest

# adb install 在 OPPO PMA110 上会卡在 "通过 USB 安装" 确认框（看不到原因 / 不超时）。
# **绕过：先 push 到 /data/local/tmp/ 再用 pm install**
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 \
  push app/build/outputs/apk/refactortest/app-arm64-v8a-refactortest.apk /data/local/tmp/refresh.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 \
  shell pm install -r -t /data/local/tmp/refresh.apk

# 启动
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 3B164901CEF00000 \
  shell am start -n me.rerere.amberagent.refactortest/me.rerere.rikkahub.RouteActivity
```

**注：PMA110 手势导航对 swipe / back 行为有干扰；远程 input tap/swipe 测主题切换会把 app 切到后台**。截图测试建议让用户手动操作。

## 设计稿来源

```text
/Users/arquiel/Downloads/AI/amberagent.zip        ← 用户提供的离线包，已解压到 /tmp/amber-design/
WebFetch design URL 拿到的 5.8MB gzip 二进制       ← 解压到 /tmp/design-new/amberagent/
```

两份内容大致一致，**新版包额外有**：
- `amberagent/chats/chat1.md` (97KB) ——记录设计迭代的完整对话稿，包含所有 UI 决策的 WHY
- `amberagent/README.md` —— 明确说"先读 chat1.md"
- `themes.jsx` 仅微小更新（`popoverBg` for Midnight）

**关键 jsx**：
- `themes.jsx` — Whisper / Plain / Paper / Midnight 4 套 token
- `phone-screen.jsx` — 空白态 header / hero / composer / send orb
- `convo-screen.jsx` + `convo-user.jsx` + `convo-agent.jsx` + `convo-tool-result.jsx` — 对话页所有元素
- `model-picker.jsx` — 模型选择 bottom sheet (active card + thinking-level segment + provider chips)

## 关键设计决策（chat1.md 摘要）

1. **空白态**：早期有 AmberMark 几何宝石 → user "算了去掉吧" → **最终只剩居中问候**（无图标）
2. **底部蓝光晕** Whisper：Gemini 风格冷蓝雾，**对话页完全去掉**，纯白底
3. **送按钮**：早期玻璃质感 → user "有点丑，不要玻璃" → **纯色圆 + 双层呼吸光晕**
4. **用户气泡**：早期蓝灰底 + 18px 圆角 → 不对称圆角 → **borderRadius 999 capsule** + `#F2F4F7` 灰
5. **对话页色彩降为点缀**：Whisper "蓝光晕去掉"、Plain 黑胶囊改灰、Paper 保留极淡暖光、Midnight 蓝光降到 1/3
6. **新建按钮**：phone-screen.jsx 注释明确 "**naked compose icon — no surrounding circle**"
7. **斜杠按钮**：SVG line (7,19)→(17,5)，约 54° 角，不是字符 `/`
8. **composer 阴影**：`0 1px 2px rgba(15,20,25,0.04), 0 10px 32px rgba(15,20,25,0.08)` 双层墨灰，必须同时存在（单层都不够"浮"）
9. **不要麦克风**：phone-screen.jsx 早期版本有 mic，最终版**没有 mic**，斜杠按钮取代

## 已完成 ✅

### Phase 1 · Whisper 空白态主视觉
| File | 改动 |
|---|---|
| `WhisperHalo.kt` (新建) | 主题色 token (`ChatTheme.kt` 替代) + `WhisperBottomBloom` Canvas 动态光晕 |
| `SendOrb.kt` (新建) | 48dp 圆球 + 双层呼吸光晕（外 3.6s / 内 2.8s） |
| `ChatPage.kt` | TopBar: hamburger 三长短 + 模型名 + chevron + naked compose icon |
| `ChatPage.kt` | Surface 改 Box(.background(paper)) 强制 z-order，让 bloom 透到 chat content |
| `ChatPage.kt` | EmptyChatHero: 居中"今天想聊点什么？"（无宝石） |
| `ChatListNormalSection.kt` | 空白态时 background = Transparent，让 bloom 透上来 |
| `ChatInput.kt` | composer pill: 68dp CircleShape + naked compose + 双层 elevation shadow + 5% surfaceEdge + SVG slash glyph |

### Phase 1.x · Bloom 多次迭代
| 用户反馈 | 改动 |
|---|---|
| "蓝色太灰像雾霾" | core color #78AAF0 → #7AC0FF（Gemini 同色系，更饱和） |
| "范围太大不要超过半屏" | center y = h*1.0、radius = h*0.30，限到底部 1/3 |
| "一会左白一会右白" | 重写：基础蓝层居中 + 不漂移，3 个 ripple 高光斑叠加做波纹流动 |
| "硬切看不到淡出" | `animateFloatAsState` tween 700ms FastOutSlowInEasing 让 empty→convo bloom 淡出 |
| "对话态灰白分界线" | ChatListNormalSection bg 用 `paper`（白）而非 `canvas`（off-white #F7F7F5） |

### Phase 2 · 对话页视觉
| Element | File | 改动 |
|---|---|---|
| User bubble | `ChatMessageMessagePartsBlock.kt:215` | CircleShape + `#F2F4F7` + 1px 极淡边 + 18/10 padding + 取消左蓝条 |
| Tool pill | `ChatMessageTools.kt:335 AgentToolCallCapsule` | 灰底胶囊 + sky-blue wrench + V3 完成 badge（蓝填充圆+白勾） |
| Agent header | `ChatMessageAvatar.kt:130` | 6dp 绿点 + 模型名（取代 32dp provider 图标） |
| Thinking strip body | `ChatMessageReasoning.kt:205 ReasoningContent` | 左 2dp sky-blue 竖线 (drawBehind) + dim 文字 + 取消 Surface card |
| Context ring | `ContextRing.kt` (新建) | 22dp donut + 4 阈值色 (gray/blue/amber/red) + leading head dot |
| ChatPage TopBar 右侧 | `ChatPage.kt:1320` | 仅 hasMessages 时显 ring |

### Phase 3 · 模型选择 sheet
| File | 改动 |
|---|---|
| `ModelList.kt:254 ModalBottomSheet` | shape topStart/topEnd 28dp + containerColor = surface + 36×4dp 自定义 drag handle + sheetBackdrop scrim |
| `ModelList.kt:761 ModelItem` | active 卡片 accentSoft + accent 文字 + edge 高亮；非 active surface + ink + 1px hair border |
| `ModelList.kt:798 provider icon` | toolPillBg + 1px hair border 圆角 10dp |

### Phase 4 · 4 主题切换基建
| File | 改动 |
|---|---|
| `ChatTheme.kt` (新建) | `data class ChatTheme` 含 47 个 token + 4 instance (WhisperTheme / PlainTheme / PaperTheme / MidnightTheme) + `LocalChatTheme` static CompositionLocal |
| `Theme.kt:200` | `LocalChatTheme provides chatTheme`，浅色读 `displaySetting.chatThemeChoice`，深色强制 Midnight |
| `WhisperHalo.kt` | bloom 从 LocalChatTheme 读 `bloomCore/Secondary/Highlight/bloomMaxAlpha` |
| `SendOrb.kt` | 送按钮 从 LocalChatTheme 读 `sendBg/sendArrow/sendHalo` |
| 6 个 chat 文件 | `WhisperTokens.X` → `LocalChatTheme.current.X` 批量迁移（`ChatPage.kt` / `ChatMessageAvatar.kt` / `ChatMessageReasoning.kt` / `ChatMessageTools.kt` / `ChatMessageMessagePartsBlock.kt` / `ModelList.kt`） |
| 真机验证 | 系统深色模式自动切到 Midnight（截图 `/tmp/p30_midnight.png`） |

### Phase 4.5 · 用户主题切换 UI
| File | 改动 |
|---|---|
| `PreferencesStore.kt:DisplaySetting` | 新增 `chatThemeChoice: String = "WHISPER"` 字段（向后兼容，缺省解码到默认值） |
| `SettingDisplayPage.kt:327` | 新增"聊天主题 (V3)"item，3 个 `FilterChip` (微光 / 素白 / 暖纸) |
| `Theme.kt:200` | 读 `settings.displaySetting.chatThemeChoice` 选 Whisper/Plain/Paper；深色仍 Midnight |

### Phase 4.6 · MaterialTheme.colorScheme override（二级页面自动主题）
**核心**：所有用 `MaterialTheme.colorScheme` 的二级页面（Settings / Providers / History / Tablet）自动跟随聊天主题，无需逐文件迁移。

`Theme.kt:218 themedColorScheme` override 字段：
```
background = bg
onBackground = ink
surface = paper
onSurface = ink
surfaceVariant = toolPillBg
onSurfaceVariant = inkSoft
surfaceContainerLowest / Low / Mid (= surfaceContainer) / High / Highest  (5 级 hierarchy)
surfaceTint = accent
primary = accent
onPrimary = onAccent           ← Subagent #3 修复：Midnight 用 #0B0E14 深字
primaryContainer = accentSoft
onPrimaryContainer = accentDeep
secondary = accent
secondaryContainer = accentSoft
onSecondaryContainer = accentDeep
tertiary = accentDeep          ← Subagent #8 修复：FAB tertiary 同色系
onTertiary = onAccent
tertiaryContainer = accentTint
onTertiaryContainer = accentDeep
inverseSurface = ink           ← Subagent #9 修复：Snackbar 跟主题
inverseOnSurface = paper
inversePrimary = accentTint
outline = outlineStrong (20%)  ← Subagent #4 修复：TextField 边可见
outlineVariant = outlineSoft (12%)
```

**绕过条件**（Subagent #1/#2 修复）：
- `amoledDarkMode = true` → 跳过 override，用户得到真黑屏
- `dynamicColor = true && SDK>=S` → 跳过 override，尊重 Material You

### Phase 4.7 · 5 级 surfaceContainer hierarchy (Subagent #6 修复)
ChatTheme 新增 `containerLowest/Low/Mid/High/Highest` 5 个明度梯度。Whisper 例：
```
Lowest:  #FAFBFC (页面背景)
Low:     #FFFFFF (Card / Sheet 默认)
Mid:     #F7F9FB (NavDrawer)
High:    #F1F4F7 (BottomSheet head)
Highest: #E9EDF1 (TopAppBar elevated)
```
Plain / Paper / Midnight 各自的 5 级见 `ChatTheme.kt`。

### Subagent Reviews

| # | 范围 | 主要发现 | 修复状态 |
|---|---|---|---|
| 1 | Phase 1 (bloom / SendOrb / TopBar / composer) | 颜色/几何/动画 timing 完全对齐 themes.jsx | ✅ |
| 2 | Phase 2+3 (user bubble / tool pill / agent header / thinking / context ring / picker shell) | user bubble 用 CircleShape 而非 asymmetric, thinking rule 仅覆盖正文未覆盖 label | 已知 trade-off |
| 3 | Phase 4 (WhisperTokens→LocalChatTheme 迁移) | Midnight userBubble 5% 白对比度逼近 WCAG 下限 | ✅ 提到 12% |
| 4 | colorScheme override 10 个风险 | AmoledDark / dynamicColor / outline alpha / Midnight 阴影 / 5 级 hierarchy / tertiary 等 | ✅ 修了 8/10 |

## 终审 Subagent 风险（4 个，修了 3 个）

| # | 风险 | 修复 |
|---|---|---|
| 1 | Snackbar 深色反相 (inverseSurface 用 ink 在 dark 主题反了) | ✅ `if (darkTheme) chatTheme.paper else chatTheme.ink` |
| 2 | AmoledDark 时 bg=#000 但 LocalChatTheme.bg=#0B0E14 表面不一致 | ✅ `useAmoledBlack` 标志：仅 bg/surface/surfaceVariant 保留 amoled 黑，其他 token 仍用 chatTheme |
| 3 | Midnight composer shadow 25% 白 Material shadow 可能成"白晕"非顶部高光 | ⏳ 留下 session；如真机看怪，可换 Canvas 自绘 glow |
| 4 | M3 FilterChip 默认色在 Midnight 选中/未选中 ~12% alpha 看不出 | ✅ SettingDisplayPage 显式 `FilterChipDefaults.filterChipColors`：选中 = primary 实心，未选中 = surfaceVariant |

## 仍待办 ⏳

### 高优先级
- [ ] **Plain / Paper / Midnight 真机视觉验证**：用户需要在手机上：
  1. 打开 Amber Refresh
  2. 进入"设置 → 显示"
  3. 找到"聊天主题 (V3)"，点击 3 个 chip 切换
  4. 回到聊天页观察整 app 颜色（含设置页本身）
  5. 反馈每个主题哪里看着怪
- [ ] **Phase 3.5 完整 model picker**：active model 单独 card + thinking-level segment (off/on/max/adaptive) + provider chips 横滑栏。**未做**。需要扩展 `Model` 数据增加 effort tiers，或基于 ReasoningLevel 推断。

### 中优先级
- [ ] 二级页面 button alignment / size / corner radius 系统核查
  - 用户原话："按钮的对齐、大小、圆角，以及这个二级页面里面按钮到底是统一成大按钮还是小按钮等等"
  - 当前各页 button size 由 M3 default 决定（OutlinedButton 40dp / FilledButton 40dp / IconButton 48dp），不一致。
  - 建议统一：FilledButton 高度 48dp、IconButton size 40dp、圆角 RoundedCornerShape(12dp)
- [ ] **Thinking strip 整体竖线**（label + body 一起）：当前只在正文加了左 2dp rule，label 行（icon + "思考了 N 秒" + chevron）在外侧未被竖线覆盖。需要重构 `ControlledChainOfThoughtStep` 容器（共享于 5 个 chat message component，重构有连带风险）。

### 低优先级
- [ ] NotionLike palette 与 chatTheme 关系（Subagent #7）：当前 NotionLike build flavor 仍受 chatTheme override，Notion-tuned 灰色基底被覆盖。当前 refactortest 也是 NotionLike，所以 OK，但需要文档化"V3 chat theme 覆盖 Notion palette"是有意为之。
- [ ] User bubble asymmetric corners (chat1.md L514 提到 18/6/18/18) 被改成 full capsule (L549) 后未恢复；目前是 capsule，符合最终决策。
- [ ] AmberMark 几何宝石组件 `AmberMark.kt` 已写但不再使用（之前误以为是 hero icon），保留备用作其他场景。

## 设计映射 quickref

```
chat1.md 决策                                    → 实现位置
─────────────────────────────────────────────────────────────────────────────
"naked compose icon — no surrounding circle"     → ChatPage.kt:1325-1340
"按钮回到干净的纯色圆形，双层呼吸光晕"            → SendOrb.kt
"用户消息 → 真胶囊 borderRadius 999"             → ChatMessageMessagePartsBlock.kt:215
"对话页 Whisper：背景蓝光晕去掉，干净浅灰白"      → ChatPage.kt:524 (intensity = 0 when has msg)
"调用工具 灰底 + 蓝色图标 + 蓝色完成 badge"      → ChatMessageTools.kt:335
"模型名 + 6px 绿色状态点（取消 provider 图标）"  → ChatMessageAvatar.kt:130
"思考条左侧 2px 竖线 + 暗调文字"                 → ChatMessageReasoning.kt:205
"context ring 22px donut + threshold + head dot"→ ContextRing.kt
"模型选择 sheet 顶 28dp 圆角 + 4dp drag handle" → ModelList.kt:254
themes.jsx WHISPER bloom 配色                    → ChatTheme.kt:WhisperTheme (bloomCore #7AC0FF)
themes.jsx PAPER amber                           → ChatTheme.kt:PaperTheme (bloomCore #E6A564)
themes.jsx MIDNIGHT cool indigo                  → ChatTheme.kt:MidnightTheme (bloomCore #6E8CDC)
```

## 下个 session 建议优先级

```text
1. (1h)  问用户哪个 Plain/Paper/Midnight 真机看着不对，按反馈微调
2. (2h)  Phase 3.5 model picker 完整 V3 化（active card + segment + chips）
3. (2h)  二级页面 button 一致性 audit + 修
4. (1h)  Thinking strip label 行整体放进左竖线（重构 ChainOfThought 容器）
```

## 重要原则保留

来源 `/Users/arquiel/Documents/Codex/2026-05-22/https-x-com-anatolikopadze-status-2050225292585607440/AGENTS.md`：

1. 不清楚就问，不要假设
2. 先做最简单可行方案，不要过度设计
3. 只修改与当前任务直接相关的内容
4. 大改 / 破坏性 / 外部动作前必须确认
5. 对事实 / 数据 / 来源不确定时，必须明说
6. 完成任务后简要说明改了什么 / 没改什么 / 还需注意什么
7. 不 push，除非用户明确要求

## 已修改的文件（git diff stat 一览）

```
新增：
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatTheme.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/WhisperHalo.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/SendOrb.kt
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AmberMark.kt (备用，未使用)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ContextRing.kt
  docs/V3_REFACTOR_HANDOFF_2026-05-23.md (本文档)

修改：
  app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt
  app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt (DisplaySetting + chatThemeChoice)
  app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDisplayPage.kt (+ FilterChip 行)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt (TopBar + bloom 注入 + EmptyChatHero)
  app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatListNormalSection.kt (空白态 bg = Transparent + fade)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt (composer pill V3 + SendOrb 接入)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInputComposers.kt (minimalChrome param)
  app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt (sheet shell V3 + row 颜色)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageMessagePartsBlock.kt (user bubble V3)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt (tool pill V3 + badge)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt (绿点 + 模型名)
  app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt (左竖线正文)

新增 buildType resource:
  app/src/refactortest/res/values/strings.xml (codex 之前留的 "Amber Refresh" 名)
```

## 真机截图（验证记录）

```
/tmp/p12.png      Whisper 空白态：bloom 出来，naked compose icon，问候居中
/tmp/p18.png      Whisper 对话态：agent 绿点 + 模型名，bloom 已淡出
/tmp/p23.png      Whisper composer pill 双层阴影确认
/tmp/p25.png      Bloom 满底均匀 + 3 ripple 波纹
/tmp/p30_midnight.png  Midnight 主题真机验证：深墨蓝底 + 冷靛蓝 bloom + 浅文字
```

## rev 2 改动（2026-05-23 续 session）

### 用户反馈触发的修复
1. **"顶栏没变 Paper 暖色"** → `ChatPage.kt:1323` TopBar Surface `workspace.paper@96%` → `Color.Transparent`；`AmberHeaderLine` ink 改 `LocalChatTheme.current.ink`
2. **"二级页 Settings 没变 Paper"** → `WorkspaceStyle.kt:104` 浅色分支镜像深色分支，从 `MaterialTheme.colorScheme` 读取（之前硬编码白 → 自动跟随聊天主题）
3. **"Paper 太淡"** → 一度过暖回滚 → 最终方案：`bg=#FDFAF3` 设计稿原值 + `topHaloAlpha 0.22→0.30` 补 Compose 单 pass 衰减
4. **"跟随系统是方框 + 固定蓝"** → `Select.kt`: `RoundedCornerShape(6.dp)` → `CircleShape`；`workspace.blueContainer/blue` → `chatTheme.accentSoft/accent` (17 处 Select 一起改)

### 新增 ChatTheme token (10 个)
- `isDark` (Midnight=true, 用于跳 Material shadow)
- `topHaloCore` / `topHaloAlpha` (Paper/Midnight 顶层 ambient halo)
- `bloomHeightFrac` (Whisper 0.38 / Plain 0.50 / Paper 0.55 / Midnight 0.55)
- `heroSize/heroWeight/heroLetter` (Paper W600/0.5 / Plain 25sp/0.6 / Whisper/Midnight 26sp/0.2)
- `contextEmpty/Low/Mid/High/contextTrack` (Paper 棕渐变, Midnight 22% 白 track)
- `popoverBg` (Midnight #1A1F2A)

### P1 S batch (本 session 后段)
- `ChatMessageTools.kt:404` subtitle color: `workspace.faint` → `chatTheme.inkSoft`
- `ChatMessageAvatar.kt:145` agent name `15sp` → `17sp`
- `CardGroup.kt:35` `CardGroupCorner = 12.dp` → `18.dp` (settings 全站圆角)
- `ChatPage.kt` 对话态底部 56dp fade scrim（gradient bg(0→75%)）
- `ModelList.kt:278` drag handle 36→40dp
- `ChatMessageMessagePartsBlock.kt:215` user bubble 加 `paddingStart=60.dp` 强制右对齐留白

### ContextRing 升级
- 完整重写 `ContextRing.kt` 加 `ContextUsagePopup` (290dp 浮层)：用量与上下文 / 5h 额度 / 本周额度 / Context / meta strip
- Popup 用 `Popup` + custom `PopupPositionProvider`，箭头 14×14 旋转 45° 模拟
- 主题感知：阈值色用 `theme.contextEmpty/Low/Mid/High`，浮层 bg 用 `theme.popoverBg`(Midnight) ?: `surface`
- 数据：5h/周用量是 placeholder（app 当前未追踪），Context 用真实 used/total

### Phase 3.5 model picker thinking-level segment ✅ (本 session 续做)
- `ModelSelector` + `ModelList` 加 optional `currentAssistant` + `onUpdateAssistant` 参数（10 个 callsite 无需改）
- `ChatPage.kt` TopBar 接通 `onUpdateAssistant` callback（assistant 列表 map 更新）
- `ModelList.kt` 新增 `ThinkingLevelSegment` Composable —— OFF/AUTO/LOW/MED/HIGH/MAX 6 段 CircleShape 容器，主题色填充
- 渲染条件: `isActive && model.abilities.contains(ModelAbility.REASONING) && currentAssistant != null`
- 真机验证: `deepseek-v4-flash` 没 REASONING ability 不显示（条件正确）；用户切到 GPT/Claude/DeepSeek-pro reasoning 模型时才出 segment

### 还未做（按 design 但本 session 决定不做）
- **ToolResultPreview / AskUserCard 新组件**：当前 UI 无对应入口，纯新增组件，>2h
- **History drawer V3 重做**：当前 `workspaceColors()` 适配后已自动跟主题，视觉差距小
- **ContextUsagePopup 数据接通**：5h/周用量 app 当前未追踪，placeholder 是合理的

### Subagent reviews (rev 2)
- Midnight composer shadow review (gradient border P2 方案 → 安全合并)
- 设计稿对照审计 (P0/P1/P2 punch list 推动后续修复)
- 最终综合 review (4 套主题 token / colorScheme override / data class 向后兼容)

## END
