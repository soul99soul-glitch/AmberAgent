# Claude Code 外置状态文档 — AmberAgent god class refactor

> **用途**：Claude Code 跨会话续工作的"外置记忆"。每次开新会话先读本文件 + `SESSION_SNAPSHOT_*.md` 系列恢复上下文。本文件是**滚动更新**的活文档；session snapshot 是某次会话冻结的快照。

**Last updated**: 2026-05-17 (会话 #3 close-out — 装机 sanity + Codex review)

---

## 0. 立即对齐（每次开工先读这段）

### Worktree 隔离规则（**最重要**）
- ✅ **Claude 工作地**：`/Users/arquiel/Downloads/AI/rikkashit/rikkahub-refactor-godclass/`，branch `refactor/p1-godclass`
- ❌ **禁区**：`/Users/arquiel/Downloads/AI/rikkashit/rikkahub/` 是 Codex 的主线，不要 cd 过去、不要 `git checkout`、不要任何写操作
- ❌ 其它 worktree（`rikkahub-agent/`, `rikkahub-claude/`, `rikkahub-pulse/`）也别动 — 用户的别的实验
- **背景**：2026-05-17 一次事故 —— 共享 worktree 时 Codex 的 `git checkout main` 把我的 working tree 一起切了，下一步 commit 落到了 main，需用户介入清理。Worktree 隔离后两边互不干扰。

### 用户硬规则
1. **每一个单独的改动**都用 sub-agent 独立 review（不是每个 milestone，是每个 commit 级别）
2. 写入后必须回读自检（grep / Read 验证状态）
3. 工具失败局部 retry，不重规划工作流
4. 非 trivial 产出前先写 Sprint Contract / 跟用户对齐
5. 装机 sanity 是底线兜底 — 任何 DI 改动 / cold-flow 改造后必须装机看 logcat

### JDK / 构建环境
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:compileDebugKotlin :app:compileNotionKotlin :app:testDebugUnitTest --no-daemon
# install: ./gradlew :app:installNotion  (设备包名 me.rerere.amberagent.notion)
```

---

## 1. 项目语境

- **App**: AmberAgent (fork of RikkaHub) — 原生 Android LLM 对话客户端
- **Stack**: Kotlin + Jetpack Compose + Koin DI + Room + DataStore + KSP
- **Build types**: notion (debug 派生，应用 ID `.notion`), release, baseline
- **Phase 1 目标**: god class / god file 拆分，建立 service / DI 分层 seam，为 Phase 2/3 (namespace 重命名、UI 拆、Rust 栈升级) 做准备
- **设备**: 3B164901CEF00000（接上完成 navigation sanity；之前的 c9a8a837 已不用）

---

## 2. 当前分支位置

- **Branch**: `refactor/p1-godclass`
- **HEAD**: 见 `git log --oneline -1`（会话 #3 close-out 时 = `125f0cc7`，可能已推进）
- **Base**: 从 `4a6cbffb` (main "merge: integrate AmberAgent 2.0 feature line") 分叉
- **远端**：`github-private/refactor/p1-godclass`，会话 #3 期间多次 push，session close-out 推进到 `125f0cc7`
- **`main` 远端**：`f4d03954`（未动 — 验证用 `git ls-remote github-private refs/heads/main`）
- **Backup tag**: `backup/refactor-p1-godclass-pre-rebase-2026-05-17`

---

## 3. Phase 1 进度（截至 2026-05-17 会话 #2）

### ✅ 已完成（含 sub-agent review）

| Milestone | 内容 | Key commits |
|---|---|---|
| Phase 0 | merge main into refactor，零冲突自动合 | `344edc97` |
| M1.1.8e | 删除 `class SettingsStore` god class（556 行），54 个 PreferencesKey 迁到 `PreferencesKeys.kt`，11 个 caller + debug receiver 切换 + filterNot guard | `fc51aa96` |
| M1.2 | 引入 3 个 Orchestrator (Send/Regenerate/Branch) — ChatVM → Orchestrator → ChatService 命名层 | `30ae8b53` |
| M1.3.1 | 抽 `PendingMessageStore` 出 ChatService（12 caller 切换） | `f8227273` + `f0b27279` |
| M1.3.2 | 抽 `UserInputPreprocessor` 出 ChatService（建立 MessageTransformPipeline seam） | `355411f2` |
| M1.3.3 | audit — `ConversationContextEngine` + `ConversationContextPlanner` 已在 main 完整实现 | n/a |
| M1.5 (DI) | 抽 8 个独立子模块（chat/memory/iCloud/webMount/agentRuntime/agentInfra/board/workspace），99 single + 1 VM 移出 | 多 commit |
| M1.5 (Council) | ModelCouncilManager 抽 2 Runner 到 sibling（1150→961 行） | `694d3950` |
| M1.7 | tts 评估 — 结论不拆（无 god class） | `e7dbf8ce` + `991a4c15` |
| Worker fix | MemoryDreamWorker + BoardWorker 加 `.filterNot { it.init }.first()` guard | `e7dbf8ce` |
| M1.4 demo | LocalTools 抽 5 个 stateless tool 到 sibling（template 成熟） | `e8bb7e6b` 系列 |
| **M1.4 LocalTools 收尾** | **会话 #3** 抽完 LocalTools.kt 剩余所有 tool + helper：tools_list / tool_policy_explain / TTS / JavaScript / 7 个 webview tools + 4 helpers / generate_image，及 LocalToolOption sealed class 移文件、2 轮 dead-import 清理 | `648d5498` → `e49b697d`（8 commits）|
| M1.8 | acceptance snapshot 文档 | `61ea87a9` 等 |

### ❌ 真正剩余 follow-up

| 项 | 估时 | 难度 |
|---|---|---|
| **M1.3.4** StreamingPipeline — 整合 MessageStreamAccumulator + 流式状态机 | 2-4h | **高**（需 characterization test 先固化行为） |
| **M1.3.5** ToolApprovalCoordinator — tool approval 流程拆出 | 2-3h | **高**（蓝图标 HIGH） |
| ~~**M1.4 LocalTools 剩余**~~ | ✅ **完成（会话 #3）** | LocalTools.kt 1093→180 (-83%)；7 webview + javascript + tts + tools_list + tool_policy_explain + generate_image + LocalToolOption 全部抽完 |
| **M1.4 其它 god files** SystemAccessTools (1441 行 / Android 系统访问 / cursor lifecycle 敏感) + FeishuOfficeTools (1107 行 / Feishu API) + WebMountPrimitiveTools (1604 行 / 状态机 + JS eval gating) | 2-3 天 | 中-高（非纯逻辑 tool，需装机 sanity 验证）|
| **M1.6** Repository decoupling — 每 repo 只依赖 DAO + 网络 | 1-2 天 | 中 |

---

## 4. 当前关键文件尺寸

| 文件 | 行数 | 备注 |
|---|---|---|
| `app/.../di/AppModule.kt` | 64 | 从 691（-91%），只剩 cross-cutting infra |
| `app/.../data/datastore/PreferencesStore.kt` | 555 | 从 1127（-50%），class SettingsStore 已删，extension fns + Settings data class 保留 |
| `app/.../service/ChatService.kt` | ~2250 | 仍是最大单文件，M1.3 剩余 sub-step 主要面对它 |
| `app/.../data/agent/modelcouncil/ModelCouncilManager.kt` | 961 | 从 1150（-16%），2 Runner 已抽 |
| **`app/.../data/ai/tools/LocalTools.kt`** | **189** | **会话 #3：1093→189（-83%），低于 god 阈值，已完成**；剩 ctor + 14 lazy delegators + registryIntrospectionTools + getTools 分发器 |
| **`app/.../data/ai/tools/LocalToolOption.kt`** | **86** | **会话 #3 新建**：14 variant sealed class 独立文件 |
| **`app/.../data/ai/tools/*Tool.kt`** | 多 sibling | **会话 #2-#3 累计**：TimeTool / ClipboardTool / AskUserTool / RunPlanUpdateTool / PermissionsStatusTool / ToolsListTool / ToolPolicyExplainTool / TtsTool / JavascriptTool / WebViewTools (7 fn + 4 helper) / ImageGenTool |
| `app/.../data/agent/tools/SystemAccessTools.kt` | 1441 | 未动 — **下次目标候选 #1**（Android 系统访问 / cursor lifecycle 敏感 / 需装机 sanity）|
| `app/.../data/agent/tools/FeishuOfficeTools.kt` | 1107 | 未动 — **下次目标候选 #2**（Feishu API 集成）|
| `app/.../data/agent/webmount/tools/WebMountPrimitiveTools.kt` | 1604 | 未动 — **下次目标候选 #3**（状态机 + JS eval gating）|

---

## 5. 不变量 / 红线（不可破坏）

| 编号 | 内容 | 现状 |
|---|---|---|
| **N10** | `SettingsAggregator` public API 冻结 — 不可扩新 public method | ✅ 保持 |
| **F3** | 3 个 migration 文件可继续 import `SettingsStore.*` 已不存在；现用 `PreferencesKeys.*`，逻辑等价 | ✅ |
| **W1** | `_settingsFlow.value = settings` 必须 BEFORE `dataStore.edit { ... }` 在 SettingsAggregator.update() 里 | ✅ |
| **N13 cold-flow guard** | 任何 `.settingsFlow.first()` 在 app-init / service init / worker / debug-broadcast 路径必须用 `.filterNot { it.init }.first()`。当前 7 个 site 已正确：RikkaHubApp × 3 / ChatService:495 / WebServerService:68 / AmberagentSmokeReceiver / MemoryDreamWorker / BoardWorker | ✅ |
| **Worktree 隔离** | 详见 §0 | ✅ |
| **PreferencesKeys 54 keys** disk identifiers 必须 byte-identical | ✅ DataStore 数据零漂移 |

---

## 6. 已知 backlog（reviewer 标记的非 blocker）

- `LocalSettings.kt:7` error 字符串 `"No SettingsStore provided"` 仍用旧名（cosmetic）
- `AgentCronWorker.kt:56` 直接调 `chatService.sendMessage`，绕过 SendMessageOrchestrator — 跟 web routes 同属"deliberate bypass"，建议 M1.3 完整收尾后统一回顾
- Module 文件 style 不一致：`AgentRuntimeModule.kt` multi-line ctor / `AgentInfraModule.kt` single-line — style sweep 可选
- ChatService.kt 内部 5 个 ConcurrentHashMap（sessions / trustedRunToolNames / generationCheckpointAt / timelinePrefetchJobs / timelineLoadMutexes）未拆 — 是蓝图 M1.3.1 ConversationStateHolder 设想，但 PendingMessageStore 已经先拿了 M1.3.1 编号

---

## 7. 测试 / 编译基线

- `:app:compileDebugKotlin` ✅ pass
- `:app:compileNotionKotlin` ✅ pass
- `:app:testDebugUnitTest` — **419 tests, 6 failed, 6 ignored**
- 6 个失败全部继承自 main（在 main worktree 独立验证已失败），与本分支无关：
  - `GenerativeUiPlannerTest > directDrawingRequestsDisableToolDetours`
  - `GenerativeUiPlannerTest > toolMediatedVisualRequestsWarnAgainstProgressWidgets`
  - `ContextFootprintEstimatorTest > toolOutputIsCapped`
  - `ContextFootprintEstimatorTest > reasoningDoesNotCountAsInputFootprint`
  - `ContextFootprintEstimatorTest > fingerprintIgnoresUsageAndReasoningGrowth`
  - `DefaultProvidersTest > default providers are curated and deletable`
- **本次会话所有 commit 均未引入新 failure**

---

## 8. 装机 sanity（已部分完成，session #3）

### 8.1 新增 `refactortest` buildType（commit `03b16455` + fix `45a3da4b`）

为了让 refactor/p1-godclass 分支能跟你正在用的 `me.rerere.amberagent.notion` **共存**装机，加了 4 个 buildType：

| buildType | applicationId | 派生 | 用途 |
|---|---|---|---|
| `release` | `me.rerere.amberagent` | — | 正式发布 |
| `debug` | `me.rerere.amberagent.debug` | — | 默认 debug |
| `notion` | `me.rerere.amberagent.notion` | debug | 用户主用 |
| **`refactortest`** | **`me.rerere.amberagent.refactortest`** | **debug** | **refactor 分支 sanity 装机** |
| `baseline` | `me.rerere.amberagent.debug` | release(-) | benchmark |

构建 + 装机：
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH:$HOME/Library/Android/sdk/platform-tools
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleRefactortest --no-daemon
adb -s <device-id> install -r app/build/outputs/apk/refactortest/app-universal-refactortest.apk
```

### 8.2 ⚠️ google-services.json 是本地文件（每台机器各自维护）

`app/.gitignore` 第 3 行排除了 `app/google-services.json`，所以新 buildType 在**其他机器上 build 会失败**（`processRefactortestGoogleServices FAILED: No matching client found for package name 'me.rerere.amberagent.refactortest'`）。

**修法**：把当前机器上 `app/google-services.json` 里 notion 那条 client entry 复制一份，把 `package_name` 改成 `me.rerere.amberagent.refactortest`，`mobilesdk_app_id` 改成 `1:1234567890:android:amberagentrefactortest`。`api_key` 留 `local-debug` 即可（项目用占位 Firebase，没真后端）。

### 8.3 ⚠️ NOTION_LIKE 是 UI 主题开关，不是 feature flag（教训）

session #3 第一版 `refactortest` 把 `NOTION_LIKE` 写成 `false`，装机后 UI 全坏（字号大一号、背景土黄、按钮颜色错、动态颜色设置项出现）。读 `Theme.kt:159` / `Color.kt:178/195/203` / `SettingDisplayPage.kt:179` 任一处都能秒发现这是主题切换开关，但当时凭"refactortest 不是 notion 变体"的假设直接写了 false。

**铁律**：新 buildType 复用 notion 的视觉时，**所有** UI-critical BuildConfig flag 必须跟 notion 一致。`NOTION_LIKE`、`XIAOMI_XMS_BUILD_TYPE_DEBUG` 等都属此类。Codex 已建议加 Gradle assertion 或 unit test 强制 parity，未来一定加。

### 8.4 设备 3B164901CEF00000 上的 navigation sanity（已过）

session #3 close-out 时跑过的 sanity 项（设备 `3B164901CEF00000`，refactortest variant，PID 18614）：

| 验证项 | 结果 |
|---|---|
| Application onCreate | ✅ `I RikkaHubApp: incrementLaunchCount: 1` |
| ChatService 生命周期 | ✅ 4 次 createSession/removeSession 完美配对 |
| `Cannot update dummy settings` 警告 | ✅ **零出现**（filterNot guard 工作）|
| FATAL EXCEPTION | ✅ 零 |
| Koin DI 注入 | ✅ 7 个 Pref Module + 8 个 sub-module 全 init 正常 |
| Provider 设置页 | ✅ 5+ providers 启用状态 + 模型数对 |
| Search 服务页 | ✅ 6 个 source 启用状态对 |
| Memory 设置页 | ✅ agents.md + 核心 0/短期 0/长期 0/候选 0 显示对 |
| Display 设置页 + NOTION_LIKE | ✅ 动态颜色项已隐藏（fix `45a3da4b` 后）|
| OpenAI Provider Detail | ✅ 鉴权方式/API Key/Base URL/Path/Response API toggle 全对 |
| WorkManager 周期 worker | ✅ 23 min 内跑了 6 次 job |

### 8.5 还没跑的 sanity（Codex 建议补，merge 前必做）

- **真发 1-2 条聊天消息** — exercise Orchestrator → ChatService → 流式 → 渲染。需 API key。
- **Settings 写路径持久化** — toggle → force-stop → 重启 → 验证 DataStore 持久化。**session #3 因屏幕息屏被 auto-mode 拦中断，待补**。
- **真实 tool path 调用** — 至少跑 `tools_list`、`tool_policy_explain`、`eval_javascript`、一个 WebView open/read、`generate_image` 两条路径（配置/未配置 image-gen model）。Codex 列的具体 tool surface。

### 8.6 单测重跑（merge 前必做）

`:app:testDebugUnitTest` 上次跑还是 session #1 末。session #3 累计 12+ commit 后该再跑确认 baseline 失败数稳定在 6（见 §7）。

---

## 8.7 versionCode 自动 bump 提醒（保留旧条目）

如果设备装的是 v361+，可能要先 bump `app/build.gradle.kts` 的 versionCode（注意：某种 auto-bump 机制会在 build 时往这文件写值，commit 前 `git checkout -- app/build.gradle.kts` 还原）。

---

## 9. 工具与文档地图

| 文件 | 用途 |
|---|---|
| `docs/AI_HANDOFF_ZONES.md` | 红/黄/绿区禁令（哪些文件不能动） |
| `docs/CLAUDE_STATUS.md` | **本文件** — 滚动状态 |
| `docs/SESSION_SNAPSHOT_2026-05-17-PHASE1-PARTIAL.md` | 2026-05-17 会话冻结快照（commit-level 详细） |
| `docs/M1.7_TTS_EVAL.md` | tts 模块评估（结论不拆） |
| `docs/AMBERAGENT_HANDOFF_2026-05-15.md` | 早期 handoff 全本（背景） |
| `docs/refactor-p1-blueprint.md` | 原始拆分蓝图（已被 main 删，可 `git show 90292404:docs/refactor-p1-blueprint.md` 恢复） |

Claude Code 个人记忆（不在 repo 内）：
- `/Users/arquiel/.claude/projects/-Users-arquiel/memory/MEMORY.md` — 索引
- `feedback_rikkahub_worktree_isolation.md` — worktree 隔离规则（关键）
- `feedback_verify_after_write.md` — 写后回读
- `feedback_local_retry.md` — 工具失败局部重试
- `feedback_sprint_contract.md` — Sprint Contract 规则
- `feedback_task_working_memory.md` — 任务工作记忆

---

## 10. 下次接力推荐顺序

按风险递增 + 收益递减排：

1. ~~**M1.4 LocalTools 收尾**~~ ✅ **完成（会话 #3）** — LocalTools.kt 1093→189
2. ~~**装机 sanity navigation**~~ ✅ **完成（会话 #3，设备 3B164901CEF00000）** — Application/ChatService/Worker/7 Pref 页面全过
3. **补 sanity gap（merge 前必做）**：
   - 真发 1-2 条聊天消息（验证 Orchestrator → ChatService → 流式 → 渲染）
   - Settings 写路径持久化测试（toggle → 重启 → 验持久化）
   - 跑实际 tool path：tools_list / tool_policy_explain / eval_javascript / WebView / image gen 两条路径
   - `:app:testDebugUnitTest` 重跑确认 baseline 失败数仍 6
4. **Tier B polish（Codex 建议）**：
   - BuildConfig parity assertion（Gradle helper / unit test 强制 notion ↔ refactortest UI-critical flag 一致，防 NOTION_LIKE 复发）
   - tools_list / tool_policy_explain / WebView helper payload characterization 单测
5. **M1.4 SystemAccessTools 拆分** — 1441 行，含 27 个 Tool + 大量 ContentResolver helper。建议把纯数据查询 helper（queryContacts/querySms/queryCallLogs/queryCalendarEvents/queryMedia/maskPhone/maskEmail）剥到 sibling 文件 `SystemAccessQueries.kt`，让主类只保留 tool 注册 + execute 编排。**装机 sanity 是前提**（cursor 错就漏游标）
6. **M1.4 FeishuOfficeTools 拆分** — 1107 行，Feishu API 集成，状态较少，按 tool 簇分组 sibling 文件
7. **M1.4 WebMountPrimitiveTools 拆分** — 1604 行，WebMount JS eval gating + 状态机，难度最高
8. **M1.3.4 StreamingPipeline** — 写 characterization test 先固化 streaming 行为，再拆（半天 - 1 天）
9. **M1.3.5 ToolApprovalCoordinator** — HIGH 风险，最后做
10. **M1.6 Repository decoupling** — 每 repo 只依赖 DAO + 网络
11. **Phase 1 acceptance**：确认所有 god class / god file 都 < 500 行、Phase 1 完结
12. **合 main**：完成 #3 + #4 后可考虑。建议方式 = plain merge（保留 70+ commit milestone 颗粒度）或分批合（M1.1.8e+M1.5 先，M1.4 后）— 风险分散最优。

---

## 11. 会话 #3 完整 commit 链（2026-05-17）

### 11.1 LocalTools 抽取链（mid-session）

| commit | 内容 |
|---|---|
| `648d5498` | M1.4 — extract createToolsListTool(registry, permissionBroker) from LocalTools |
| `234fe1e0` | M1.4 — extract createToolPolicyExplainTool(registry) from LocalTools |
| `45fa9d0e` | M1.4 — extract createTtsTool(eventBus) from LocalTools |
| `52a7265d` | M1.4 — extract createJavascriptTool() from LocalTools |
| `f19b96b3` | M1.4 — extract 7 webview_* tools + 4 helpers to WebViewTools.kt |
| `476f3fa9` | M1.4 — extract createImageGenTool(conversationId, settingsStore, repo) from LocalTools |
| `81fd2065` | chore(p1): drop 4 dead imports in LocalTools.kt left behind by earlier extractions |
| `e49b697d` | refactor(p1): move LocalToolOption sealed class to its own file |
| `2e8560fd` | docs(p1): update CLAUDE_STATUS for session #3 — LocalTools fully refactored |

### 11.2 装机 sanity + Codex review polish（late session）

| commit | 内容 |
|---|---|
| `03b16455` | build(p1): add refactortest buildType for sanity-installing alongside notion |
| `45a3da4b` | fix(build): refactortest must set NOTION_LIKE=true, mirroring notion (UI theme switch, not feature flag — lesson learned) |
| `125f0cc7` | refactor(p1): bundle registry-introspection tools into LocalTools.registryIntrospectionTools(registry) (Codex Tier-A) |
| (本 commit) | docs(p1): session #3 close-out — sanity status + Codex review action items |

### 11.3 数字

- **Total**: 10 refactor + 3 docs + 2 build = 12+ commits。每个 refactor commit sub-agent reviewed APPROVE，hygiene 2 个合并 review APPROVE。
- **LocalTools.kt 净减**: 1093 → 189 lines (-904, -83%)。
- **编译**: `:app:compileDebugKotlin --rerun-tasks` + `:app:assembleRefactortest` 全 pass。
- **装机**: device `3B164901CEF00000` 装了 refactortest variant，navigation sanity 全过，与 notion 包共存且视觉一致。
- **远端备份**: `github-private/refactor/p1-godclass` 推进到本 commit。`main` 未动（`f4d03954`）。
- **Codex review**: P2 buildType 不可复现（已记录在 §8.2 + §10）、P3 doc 过时（本 commit 修了）；无行为级 blocker；提了 registryIntrospectionTools 收整建议（已实现 `125f0cc7`）+ BuildConfig parity assertion 建议（已记录到 §10 Tier B）。
