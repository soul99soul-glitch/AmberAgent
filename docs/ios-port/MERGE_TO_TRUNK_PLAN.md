# iOS 分支合并回 Trunk — 执行工单

> 日期：2026-06-20
> 作者：架构分析（自动分类，可重跑）
> 范围：把 `codex/ios-port-wip` 的内核改动合回主干，最终让 Android 与 iOS 共用同一份 `commonMain`，停止双长期分支并行。
> 重跑分类：`python3 docs/ios-port/classify.py`（按影响面分档）、`conflict.py`（冲突预测）、`manifest.py`（三档清单）。

---

## 0. 结论先行

- iOS 分支相对 merge-base 领先 **205 个提交**，但 **188 个是纯 iOS（零 Android 风险）**。
- 真正需要在 Android 上做回归的，只有 **17 个触及共享内核的提交**，其中 **15 个是良性 KMP 下沉**、**2 个是网络栈大迁移**。
- 「203 commit 分叉」的实际合并风险，**集中在 2 个网络迁移提交 + 19 个双边都改过的热点文件**上。
- 合并按 A→B→C 三批推进，风险递增；C 批做完，OkHttp 才能从 Android 真正下线，回到单一网络栈。

### 分叉事实（快照 2026-06-20）

| 项 | 值 |
|---|---|
| merge-base | `8ea4bde` |
| Android `feature/council-host-orchestration-tools` HEAD | `b51648c` （领先 78） |
| iOS `codex/ios-port-wip` HEAD | `476f8f1` （领先 205） |
| 两边都改过的文件（冲突候选） | **30** |
| 其中落在 iOS 网络大迁移区的热点 | **19** |

---

## 1. 三档分类

### A 档 — 纯 iOS：188 个提交（可整批迁移，Android 零风险）

只动 `iosApp/`、`*/iosMain/`、iOS 新增模块（`shared`/`ai-core`/`ai-provider-openai`/`core:types`/`core:conversation-storage`/`core:native`/`native`）、`docs/`、build 脚本。Android 编译产物不受影响。

复现完整 hash 列表：

```bash
# 在 amberagent-ios worktree 内
python3 docs/ios-port/manifest.py        # 顶部打印 A=188 / B=15 / C=2，并列出 B 档
# 或直接拿 A 档清单：classify.py 内 pure_ios 列表
```

### B 档 — 良性 KMP 下沉：15 个提交（逐个验 Android 不回归）

把现有代码从 `androidMain` 搬到 `commonMain`、给 `*/api` 加接口、加 Android adapter。以纯新增为主，对 Android 源码兼容。

| commit | 摘要 | 触及共享区 |
|---|---|---|
| `a47ba6bb0` | Add iOS remote SSH runtime MVP hardening | feature/terminal, feature/modelcouncil (api+src) |
| `29c123b60` | KMP-ify feature:task (phase 4 step 1) | feature/task, app/di |
| `0c0bd9bc1` | KMP-ify core/app-infra (phase 4 step 2) | core/app-infra |
| `28d712298` | KMP-ify feature/modelcouncil + export to Shared (phase 4 step 3) | feature/modelcouncil, app（新增 AndroidCouncilAdapters） |
| `7bf0cd842` | move SubAgentDefinitions to commonMain (phase 6) | feature/subagent/api（+325 纯新增） |
| `06f6045a8` | wire iOS memory read/write via IosMemoryFactory (7.1) | core/memory/api（纯新增） |
| `4700dd3fb` | wire iOS Skill scanning via IosSkillFactory (7.2) | feature/task |
| `80099ea58` | Wire iOS SubAgent runtime | feature/subagent, app（新增 AndroidSubAgentAdapters） |
| `5fe4625e1` | Add iOS settings backup export import | core/sync/api |
| `7fd94b80c` | Wire Board Collection phase 1 KMP scaffolding (Slice 63) | feature/board/api（纯新增） |
| `07c126cd7` | Wire iOS MCP connection management via URLSession (Slice 65) | core/ai/api（从 app 搬迁 McpParser/Status + 新增接口） |
| `fbff39afd` | iOS marker cleanup Slice 5: real statistics | core/agent-store-room（DAO +9） |
| `6fd5da654` | iOS marker cleanup Slice 6: memory persistence | core/memory/api |
| `f9d2845f1` | Add WebMount tools and guard Android runtimes | app/SettingSandboxPage |
| `a352b7332` | Wire iOS memory tool runtime | core/memory/api |

> 验证基线：每个 commit 合入后跑 `./gradlew :app:assembleDebug` + 对应模块单测。预期均为搬家/新增，无行为变化。

### C 档 — 网络栈大迁移：2 个提交（真改 Android 行为，重点回归）

| commit | 摘要 | 影响 |
|---|---|---|
| `2488e2536` | KMP conversion of 27 modules + Ktor SSE migration + type erasure | **search 全家（20 service）、tts 全家、ai provider、`common/http/SSE`** 从 OkHttp 迁 Ktor；删 `core/settings/PreferencesStore.kt`(-611) |
| `f38596f6d` | complete OkHttp → Ktor migration for `:ai` | Claude/Google/OpenAI provider + OAuth 全量改 Ktor；`:ai` 内 `import okhttp3.*` 归零 |

**性质判定**：两个 commit 的 message 均自带 `assembleDebug ✅ / :ai:test ✅`，是**有意且当时已验证**的改造。Android 仍走 Ktor 的 OkHttp 引擎，**网络行为等价**；app 侧改动只是 DI 构造参数适配（如 `ProviderManager(client=get())` → `ProviderManager()`）。风险不在「逻辑被改错」，而在「网络栈整体替换 + 与 Android 78 个领先提交在同文件上撞车」。

---

## 2. 合并冲突预测

两边都改过的 30 个文件中，**19 个落在 C 档迁移区**，会硬冲突，且因 iOS 侧是 OkHttp→Ktor 整体重写、Android 侧是功能改动，**无法机器合并**，需手工把 Android 改动在 Ktor 版本上重做：

```
ai/provider/ClaudeProvider.kt          ai/provider/GoogleProvider.kt
ai/provider/google/GoogleGeminiOAuth.kt
ai/provider/openai/ChatCompletionsAPI.kt   ai/provider/openai/OpenAICodexOAuth.kt
ai/provider/openai/ResponseAPI.kt
ai/test/.../ChatCompletionsAPIMessageTest.kt
core/settings/PreferencesStore.kt
search/{Bocha,Exa,Metaso,SearXNG,Zhipu}SearchService.kt           (5)
tts/provider/{Gemini,Groq,MiniMax,OpenAI,Qwen,XAI}TTSProvider.kt  (6)
```

其余 11 个冲突文件分散在 `app/src`、`feature/modelcouncil`、`feature/live`、`app/build.gradle.kts`、`.DS_Store`，多为小冲突。

> `.DS_Store` 应加进 `.gitignore` 并从索引移除，不该参与合并。

---

## 3. 推荐合并顺序（三批，风险递增）

### 第 1 批 — A 档 188 个纯 iOS
整批带过去（`iosApp/`、iOS 新模块、`iosMain`、docs）。把 iOS 骨架接进 trunk。
- 验证：`./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` 通过；`./gradlew :app:assembleDebug` 不受影响。

### 第 2 批 — B 档 15 个 KMP 下沉
逐个合入，每个跑：
```bash
./gradlew :app:assembleDebug
./gradlew :<动到的模块>:test          # 见上表「触及共享区」列
```
预期全为搬家/新增，Android 不回归。

### 第 3 批 — C 档 2 个网络迁移（成败点）
1. 在 trunk 上 rebase/重做 `2488e2536`、`f38596f6d`；
2. 对 19 个热点文件，手工把 Android 78 个领先提交里的功能改动在 Ktor 版本上重做；
3. **Android 全量回归**（正是 C 档动的四块）：
   - LLM 调用：Claude / OpenAI（含 Codex OAuth）/ Google（含 Gemini OAuth）listModels + generateText；
   - SSE 流式输出（`ResponseAPI.streamCodexText`、`GoogleGeminiOAuth.streamGenerateContent`）；
   - search 各 provider 真实查询；
   - TTS 各 provider 合成；
   - settings 读写（`PreferencesStore` 已删，确认迁移目标无回归）。
4. 通过后，OkHttp 从 Android provider 构造里彻底下线 → 单一网络栈达成。

---

## 4. 反向清单 — Android 领先的 74 个提交（iOS 补齐）

Android 领先 merge-base **74 个提交**（去 merge）。分类（重跑 `python3 docs/ios-port/reverse.py`）：

| 类别 | 数量 | iOS 处理 |
|---|---|---|
| 触及共享内核/能力层 | **22** | 需评估补齐 |
| 仅 `app/` (Android UI/VM) | 38 | iOS 重写，仅对齐功能语义 |
| 仅 docs/build/其它 | 14 | 忽略 |

22 个内核提交集中在 4 块，其中 **council-room 与 live 是 iOS 目前缺的两块大功能**：

- **council-room（8）**：host 主持议会室全套（roster、inline synthesis、ask_user HITL、pre-topic research）。`3b966d2cf 3367a4ca7 23884b75a 6436e9409 9548a2c7a ef04e7de3 17557905e` + UI。
- **live / 陪伴模式（6）**：`LiveEngine` 决策状态机（防抖/去重/冷却/退避，纯逻辑可单测）、`LiveScenes` 场景画像、模式设置。`08e28181a 4f884db99 ed281ec14 e6e9e4461 0ad8e1d09 d2d9a5725`。
- **deep-read（4）**：claim 级引用 pill、可配置 TTL 缓存、排版修复。`122740540 97fc976ae 1dd05a71e 55842dfc4`。iOS 已自做 deep-read，需 diff 对齐而非照搬。
- **streaming/chat 加固（3）+ 风险审计批修（1）**：`ea3bd593c`(SSE 解析加固)、`42b28a540`(流式 checkpoint + 中断恢复)、`1ee8e8eaa`(流式视觉)、`0efc0b6b3`。其中 SSE 加固与 C 档 Ktor 迁移**改的是同一块**，应在重做 Ktor 版本时一并带上。

> 关键：council-room / live 的内核是纯 Kotlin、可单测 → 直接进 `commonMain`，iOS 只补 SwiftUI；streaming 加固应并入 C 批，不单独做。

---

## 5. 重跑与校准

分类脚本已归档在 `docs/ios-port/`，HEAD 前进后可直接重跑校准本工单：

```bash
python3 docs/ios-port/classify.py    # A/B/C 计数 + 混合提交
python3 docs/ios-port/conflict.py    # 30 冲突文件 + 19 热点
python3 docs/ios-port/manifest.py    # B 档全表
```

> 脚本里的 merge-base / HEAD 是 2026-06-20 快照值，分支推进后需更新脚本顶部常量。
