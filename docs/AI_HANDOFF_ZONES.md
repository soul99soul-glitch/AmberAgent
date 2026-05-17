# AI Handoff Zones — AmberAgent

> **这份文档给来接力干活的 AI 看**（Codex / OpenCode / Cursor / Cline / Aider / Claude Code 都适用）。
> 让你 2 分钟内搞清楚：哪些代码正在被重构（不许碰）、哪些是待重构区（小心碰）、哪些可以放心改。
>
> **最后更新**：2026-05-16
> **维护者**：每次完成一个 milestone 都要更新红/黄区列表。

---

## 1. 项目快速认识

- **名字**：AmberAgent（fork of rikkahub）
- **类型**：Android 原生 LLM 聊天客户端
- **技术栈**：Kotlin + Jetpack Compose + Koin DI + Room + DataStore + KSP
- **目录结构**：
  - `app/` — 主 app 模块（UI / ViewModel / 业务逻辑）
  - `ai/` — AI provider SDK 抽象层（OpenAI / Anthropic / Google）
  - `common/` — 通用工具
  - `document/` — PDF/DOCX/PPTX 解析
  - `highlight/` — 代码高亮
  - `search/` — 搜索 SDK（Exa / Tavily / Zhipu）
  - `tts/` — 文本转语音
  - `web/` — 嵌入式 Ktor web server + 前端（web-ui/ 构建产物）

- **核心域模型**（理解这几个就能看懂大部分代码）：
  - `Assistant` — 助手配置（system prompt / 模型参数 / 工具开关）
  - `Conversation` — 持久化对话，含 MessageNode 树（支持分支）
  - `UIMessage` — 平台无关的消息抽象（含 text / image / reasoning / tool_call 等 parts）
  - `MessageNode` — 消息节点容器（支持多条 alternative messages 实现分支）
  - `Message Transformer` — 消息变换 pipeline（input / output）

---

## 2. 正在进行的 refactor（**红区源头**）

**当前阶段**：Phase 1 - M1.1 god class 拆分（SettingsStore）

god class `SettingsStore`（1131 行，原管 55 个设置）正在被拆成：
```
7 个域 Prefs (UIPrefs / SearchPrefs / AgentPrefs / ProviderPrefs
              / ChatPrefs / ExtensionPrefs / AssistantPrefs)
+ 1 个协调器 SettingsAggregator
```

**进度**：
- ✅ M1.1.1–7：7 个 Prefs 已写完
- ✅ M1.1.8a–d：SettingsAggregator 实现 + 63 个 caller 全部切流到 Aggregator
- ⏳ M1.1.8e：删除 god class（待做）
- ⏳ M1.2–M1.8：Orchestrator / ChatService 拆 / 4 个 Tools god 文件 / ModelCouncil 等

专用分支：`refactor/p1-godclass`。其他 AI 不要碰这个分支，**只在 `main` 或新分支工作**。

---

## 3. 🔴 红区 — **不要碰**

正在 refactor 的代码，外部 invariant 没文档化，你看到的"可改进"很可能是有意的设计。

| 文件 / 目录 | 状态 | 不许碰的原因 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/SettingsAggregator.kt` | 🔴 公开 API 冻结 | 8 个公开方法是与原 god class 等价的边界。**不许扩**任何新公开方法（命名为 N10 规则） |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/UIPrefs.kt` | 🔴 | 7 个 Prefs 之一，正在重构 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/SearchPrefs.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/AgentPrefs.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/ProviderPrefs.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/ChatPrefs.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/ExtensionPrefs.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/prefs/AssistantPrefs.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt` | 🔴 god class 本体 | M1.1.8e 即将删 class（保留文件 + extension fn） |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV1Migration.kt` | 🔴 | 数据库迁移，**永远豁免**，碰一下用户数据就丢 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV2Migration.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV3Migration.kt` | 🔴 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt` | 🔴 | DI 注册有顺序依赖（7 Prefs → Aggregator），错一行就启动崩 |
| `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt:540-565` | 🔴 supplier lambda | 喂入 Board 信号链（TimeAnchorSignalCollector / SignalAggregator） |
| `docs/SESSION_SNAPSHOT_2026-05-15-*.md` | 🔴 | 历史 snapshot，留作 audit trail，不要删 |
| `docs/AMBERAGENT_HANDOFF_2026-05-15.md` | 🔴 | refactor 总规划文档 |

**底线**：上面任何一个文件，**先 `git log -10 -- <file>` 看最近 commit，并问负责 refactor 的人**。

---

## 4. 🟡 黄区 — 可改但小心

这些文件**未来要 refactor**（蓝图已规划），现在改没问题，但要：
- 让动作范围小（fix 单点 bug、加 1-2 行）
- 不要做"顺手大重构"，那是后续 M1.x 的事
- 改前 grep 看下有没有人正在改

| 文件 / 目录 | 未来 refactor 计划 | 当前可做 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（2154 行） | M1.3 拆 GenerationHandler + Orchestrator | 单点 bug fix、加日志、加 1-2 行守卫 |
| `app/src/main/java/me/rerere/rikkahub/data/agent/tools/LocalTools.kt`（1400+ 行） | M1.4 拆 4 个 god tool 文件之一 | 加新 tool、改 tool 描述 |
| `app/src/main/java/me/rerere/rikkahub/data/agent/tools/McpManagementTools.kt` | M1.4 同上 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/agent/tools/SkillsTools.kt` | M1.4 同上 | 同上 |
| `app/src/main/java/me/rerere/rikkahub/data/agent/modelcouncil/ModelCouncilManager.kt` | M1.5 拆 ModelCouncil | 单点 fix |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt` | UI 重构候选 | 加 state / 改 UI binding |
| 所有 UI VM 文件 | 后续可能改 ViewModel 模式 | 单页 bug fix |
| `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt` | Phase 3 namespace 改名时会改包名 | 加初始化逻辑、bug fix |

**约束**：任何 `*VM.kt` / `*Service.kt` 内调用 `settingsStore.*` 的地方，**只准用现有 8 个公开方法**：
- `settingsFlow: StateFlow<Settings>` — 读取
- `update(settings)` / `update(fn)` — 整体写
- `updateAssistant(id)` / `updateAssistantModel(...)` / `updateAssistantReasoningLevel(...)` / `updateAssistantMcpServers(...)` / `updateAssistantInjections(...)` — 助手字段写

**特别陷阱**：`settingsStore.settingsFlow` 是 `MutableStateFlow(Settings.dummy())`，初始值是 dummy。在 app 启动早期用 `.first()` 会立即拿到 dummy。如果你要写 `.first()` 等真实数据，请加 `.filterNot { it.init }.first()`（参考 commit `95f35951`）。

---

## 5. 🟢 绿区 — 可放心改

跟当前 refactor **零交叉**，做 bug 修复 / UI 改进 / 新功能都没问题。

### UI 渲染相关
```
app/src/main/java/me/rerere/rikkahub/ui/components/message/   ← 聊天消息渲染
app/src/main/java/me/rerere/rikkahub/ui/components/markdown/  ← Markdown 渲染
app/src/main/java/me/rerere/rikkahub/ui/components/ui/        ← 通用 UI（Toaster / Sheet / Dialog）
app/src/main/java/me/rerere/rikkahub/ui/theme/                ← 主题
highlight/                                                     ← 代码高亮独立模块
```

### AI provider / 流式解析
```
ai/src/main/java/me/rerere/ai/provider/providers/   ← OpenAI / Anthropic / Google SSE 解析
ai/src/main/java/me/rerere/ai/ui/Message.kt          ← UIMessage 模型 + chunk merge
ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt    ← 消息 part 类型
```

### 搜索 / TTS / 文档解析
```
search/    ← Exa / Tavily / Zhipu provider
tts/       ← TTS 实现
document/  ← PDF / DOCX / PPTX
```

### Web server / 前端
```
web/                       ← Ktor server 启动
web-ui/                    ← React 前端（构建产物）
```

### 单页 UI（多数情况安全，特别是 setting / 工具页）
```
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/*.kt   ← 大部分 setting 页面
app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/    ← Prompts / Skills / QuickMessages
app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/        ← 图片生成
app/src/main/java/me/rerere/rikkahub/ui/pages/translator/    ← 翻译
```

### 资源 / 配置
```
app/src/main/res/values*/strings.xml   ← i18n 字符串
app/src/main/res/drawable/             ← 图标
app/src/main/res/values/themes.xml     ← 主题资源
gradle/libs.versions.toml              ← 依赖版本
```

### 测试
```
app/src/test/                          ← 单测（**不要破坏现有 16 个 SettingsAggregatorHelpersTest**）
```

---

## 6. 常见任务建议

### "修一个 setting 页面的小 bug"
绿区。直接动 `ui/pages/setting/<具体文件>.kt`。

### "改 UI 文案 / 翻译"
绿区。改 `res/values*/strings.xml`。

### "改 markdown / 代码高亮渲染"
绿区。`ui/components/markdown/` + `highlight/`。

### "改流式渲染（chunk 防抖 / 重组优化 / scroll 跟随）"
绿区。`ui/components/message/` + `ai/ui/Message.kt`。改完**必装机**测一下流式聊天。

### "加一个新 AI provider"
绿区。`ai/src/main/java/me/rerere/ai/provider/providers/` 加新文件，extend `Provider` 基类。

### "改 LLM tool 行为 / 加新 tool"
🟡 黄区（M1.4 待拆）。可以加，但**不要顺手重构** `LocalTools.kt` 这种 god 文件。

### "改聊天会话逻辑"
🟡 黄区（ChatService 是 M1.3 待拆）。单点改 OK，大改要等 M1.3。

### "改设置存储 / DataStore"
🔴 红区。不要碰，找负责 refactor 的人。

---

## 7. 工作流约束（所有 AI 都遵守）

### Branch
- 当前主线分支 `main`，refactor 分支 `refactor/p1-godclass`
- 你只能在 `main` 或自己起的新分支工作（`git checkout -b feat/your-thing`）
- **不要 push 到 refactor/p1-godclass**（除非你就是那个 refactor 的负责人）

### Commit
- 每次切工具前 commit（不要留半截工作树）
- commit message 中文 / 英文都行，但要清楚说改了什么 + 为什么

### Push
- **commit 是本地，push 由人来决定**
- 不要主动 push 到任何远端，让用户审 diff 后再说

### 装机测试
- 任何改流式渲染 / DataStore 写入 / DI 注册 / Service 长生命周期代码的改动，**必须装机** + 看 logcat 至少 5 分钟
- 包名：`me.rerere.amberagent.notion`
- 构建：`./gradlew :app:assembleNotion`（**notion 是 buildType 不是 flavor，没有 assembleNotionDebug**）
- 装机：`adb install -r app/build/outputs/apk/notion/app-universal-notion.apk`
- JDK：`$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home`

### 测试
- 跑单测：`./gradlew :app:testDebugUnitTest`
- 基线：368 tests / 6 failed（baseline 失败是 GenerativeUiPlanner ×2、ContextFootprintEstimator ×3、DefaultProviders ×1，与你的改动无关）

### 不要做的事
- ❌ 不要扩展 `SettingsAggregator` 的公开 API（N10 冻结）
- ❌ 不要随手清理"看起来死代码"的注释/变量（refactor 中可能是有意保留）
- ❌ 不要把 `.settingsFlow.first()` 当冷流用（要 `.filterNot { it.init }.first()`）
- ❌ 不要 enable mock 数据库测试（项目 policy：integration 测试必须真 DB）

---

## 8. 出问题时找谁

如果你不确定能不能碰某个文件、或不知道是不是踩了红区：
1. `git log -10 -- <file>` 看最近 commit
2. grep `\bSettingsStore\b\|\bSettingsAggregator\b` 看依赖关系
3. **push back** 给人，让人拍板
4. 千万**不要默认进 autonomous mode**

---

## 9. 文档地图

| 文档 | 内容 |
|---|---|
| `docs/AI_HANDOFF_ZONES.md`（本文档） | 红/黄/绿区地图，所有 AI 上手必读 |
| `docs/AMBERAGENT_HANDOFF_2026-05-15.md` | refactor 总规划，给负责 refactor 的 AI 看 |
| `docs/SESSION_SNAPSHOT_2026-05-15-*.md` | 各 session 进度快照（PM / EVENING / NIGHT） |
| `docs/refactor-p1-blueprint.md` | Phase 1 蓝图 |
| `CLAUDE.md`（项目根） | 项目级开发约定（UI 模式 / i18n / AI provider 接入等） |
| `app/CLAUDE.md`（如有） | 模块级约定 |

---

**总结**：你不知道改哪里时 → 看绿区。你确定要改黄区时 → 小步、单点、问。**红区始终问人**。
