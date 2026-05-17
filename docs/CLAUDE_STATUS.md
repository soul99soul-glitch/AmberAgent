# Claude Code 外置状态文档 — AmberAgent god class refactor

> **用途**：Claude Code 跨会话续工作的"外置记忆"。每次开新会话先读本文件 + `SESSION_SNAPSHOT_*.md` 系列恢复上下文。本文件是**滚动更新**的活文档；session snapshot 是某次会话冻结的快照。

**Last updated**: 2026-05-17 (会话 #2 结束)

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
- **设备**: c9a8a837（断连中，需用户接上才能装机验）

---

## 2. 当前分支位置

- **Branch**: `refactor/p1-godclass`
- **HEAD**: 见 `git log --oneline -1`
- **Base**: 从 `4a6cbffb` (main "merge: integrate AmberAgent 2.0 feature line") 分叉
- **远端未 push**（github-private 还在 `f4326b6b`，建议下次 push 一次留备份）
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
| M1.8 | acceptance snapshot 文档 | `61ea87a9` 等 |

### ❌ 真正剩余 follow-up

| 项 | 估时 | 难度 |
|---|---|---|
| **M1.3.4** StreamingPipeline — 整合 MessageStreamAccumulator + 流式状态机 | 2-4h | **高**（需 characterization test 先固化行为） |
| **M1.3.5** ToolApprovalCoordinator — tool approval 流程拆出 | 2-3h | **高**（蓝图标 HIGH） |
| **M1.4 剩余** LocalTools 中带 WebView/QuickJS/TTS deps 的 tools + SystemAccessTools (1441 行) + FeishuOfficeTools (1107 行) + WebMountPrimitiveTools (1604 行) | 2-3 天 | 中（机械工作但量大） |
| **M1.6** Repository decoupling — 每 repo 只依赖 DAO + 网络 | 1-2 天 | 中 |

---

## 4. 当前关键文件尺寸

| 文件 | 行数 | 备注 |
|---|---|---|
| `app/.../di/AppModule.kt` | 64 | 从 691（-91%），只剩 cross-cutting infra |
| `app/.../data/datastore/PreferencesStore.kt` | 555 | 从 1127（-50%），class SettingsStore 已删，extension fns + Settings data class 保留 |
| `app/.../service/ChatService.kt` | ~2250 | 仍是最大单文件，M1.3 剩余 sub-step 主要面对它 |
| `app/.../data/agent/modelcouncil/ModelCouncilManager.kt` | 961 | 从 1150（-16%），2 Runner 已抽 |
| `app/.../data/ai/tools/LocalTools.kt` | 1093 | 从 1336（-18%），5 stateless tool 已抽 |
| `app/.../data/agent/tools/SystemAccessTools.kt` | 1441 | 未动 |
| `app/.../data/agent/tools/FeishuOfficeTools.kt` | 1107 | 未动 |
| `app/.../data/agent/webmount/tools/WebMountPrimitiveTools.kt` | 1604 | 未动 |

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

## 8. 装机 sanity（待跑）

设备 c9a8a837 当前断连。接上后跑：
```bash
# (在 worktree 内)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:installNotion --no-daemon
# 然后 adb -s c9a8a837 logcat 看 5 分钟:
# 关键 marker: I RikkaHubApp: incrementLaunchCount: NNN (不是 0)
#             无 W SettingsAggregator: Cannot update dummy settings 
#             BoardWorker / MemoryDreamWorker 起来正常
```

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

1. **装机 sanity + push remote** — 兜底必做（15 min）
2. **M1.4 继续抽** LocalTools 中剩余 stateless tool（每个 1 commit + 1 review），增量推进直到 LocalTools.kt < 500 行
3. **M1.3.4 StreamingPipeline** — 写 characterization test 先固化 streaming 行为，再拆（半天 - 1 天）
4. **M1.3.5 ToolApprovalCoordinator** — HIGH 风险，最后做
5. **SystemAccessTools / FeishuOfficeTools / WebMountPrimitiveTools** 分别按 LocalTools 同模板拆
6. **M1.6 Repository decoupling** — 每 repo 只依赖 DAO + 网络
7. **Phase 1 acceptance**：确认所有 god class / god file 都 < 500 行、Phase 1 完结
