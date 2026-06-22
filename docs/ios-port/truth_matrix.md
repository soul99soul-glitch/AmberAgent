# truth_matrix — iOS Parity Closure

> 真相矩阵（spec success_metric, line 31-37）。每个 cell 绑定具名测试。
> 绿格 iff: 绑定测试全过 **且** 非 skip-gate **且** 经实跑确认。
> **当前状态: VERIFIED (2026-06-22)** — 工程经 `xcodegen generate` 重建后（pbxproj 是 XcodeGen 生成物、被 gitignore，必须先重建），`xcodebuild test ... -skipMacroValidation` 全量绿：**386 tests / 0 failures**。下方矩阵已基于**实跑 + 本轮修复**刷新，不再是 P0-baseline 的静态自报。原 P0-baseline 的绑定理由保留在文末脚注（历史）。

## 图例
- 🟢 = GREEN（实跑通过，且经审查断言非弱化/假绿）
- 🟡 = PARTIAL（核心已修，深度部分仍开）
- 🔴 = 仍开（未实现/已知缺口）
- ⬜ = 非 P0_block 范围

## P0_block 矩阵（实跑刷新）

`cols[entry_real..secure_store]` × `[chat, deepread, subagent_standalone, subagent_chat, council]`

| row \ col | entry_real | provider_real | exec_real | honest_fail | secure_store |
|-----------|-----------|---------------|-----------|-------------|--------------|
| **chat** | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| **deepread** | 🟢 | 🟢 ✦ | 🟢 ✦ | 🟢 ✦ | 🟢 |
| **subagent_standalone** | 🟢 | 🟢 | 🟢 | 🟢 ✦ | 🟢 |
| **subagent_chat** | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| **council** | 🟢 | 🟢 ✦ | 🟢 | 🟢 | 🟢 |

✦ = 本轮（2026-06-22）修复或去假绿。所有格子均由 386-green 实跑中的具名白盒测试支撑。

## 其他行（实跑刷新）

| row \ col | provider_real | exec_real | honest_fail | secure_store | persist | recover |
|-----------|---------------|-----------|-------------|--------------|---------|---------|
| **search** | 🟢 ✦ | 🔴 (无多源编排, deferred) | — | 🟢 | — | — |
| **backup** | — | — | — | 🟢 | — | — |
| **council** | 🟢 ✦ | 🟢 | 🟢 | — | 🔴 (无消息持久化/重开) | — |
| **chat.recover** | — | — | — | — | — | 🟡 ✦ (running 记录已写, sweep 标 interrupted; 无 resume/checkpoint/幂等) |

## 本轮实跑刷新说明（2026-06-22）

提交序列（均 386-green 验证）：
- `67edfca9c` deepread 重试双漏（provider_real + honest_fail 的重试面）
- `ed6111e51`/`e4b6d297b`/`b6f283cea` 双真相源:SubAgent/Council/Board 执行 + 设置 UI 选择器全部统一到 IOSSharedSettingsStore（registry 仅剩 provider 增改 UI）
- `47793ea1e` search-failure 真失败态（scrape_status=failed + 生成器排除失败源）
- `2184d9507` subagent_standalone 测试去假绿（驱动真 dispatch seam，删硬编码常量）
- `f408fbcbf` deepread 第 4 阶段 Extended Reading（4 阶段管线，Android 对齐）
- `5420d81a8` recovery:start 写 running 记录（sweep 可标 interrupted）
- `8ef6b152e` WebMount 测试隔离（全绿基线）

**仍开（需独立实现，非本轮）：**
- `council.persist`: 消息/seats/round/pendingQuestion 持久化 + 重开 Room（需 Color 编码 + 重开 UX 决策）。
- `chat.recover` 深度: stream checkpoint、auto-resume、tool-call 幂等（runId+toolCallId+inputDigest）。
- `search.exec_real`: 多源编排（查询变体/并发/合并去重）。

---

## （历史）原 P0-baseline 绑定脚注

---

## cell 绑定详表（每格 ↔ 具名测试 ↔ 证据 ↔ 目标 phase）

### chat 行

¹ **chat.entry_real / chat.honest_fail** = 🟡 pre-existing (P3/P4 scope)
- 测试: `ProviderRegistryStoreTests.testChatSendRequiresApiKeyBeforeAppendingUserMessage` / `testChatSendRequiresValidBaseURL` / `testChatSendRequiresModelId`
- 证据: 发消息前应返回 `.missingAPIKey`/`.invalidBaseURL`/`.missingModel` 并阻止 append；当前未实现（:174-201）。
- 目标: P3（unified provider resolution）。

² **chat.provider_real** = 🟢 GREEN
- 证据: `ChatViewModel.makeProviderSetting()` (ChatViewModel.swift:904) 经 `ChatProviderConfiguration.provider(for:providers:)` 解析选中 provider；Claude 走原生 `/messages`（`ChatGenerationCoordinator.dispatchStream` :542-562）。无 rebuild-OpenAI gap。

³ **chat.exec_real** = 🟢 GREEN
- 证据: 单执行链 stream→accumulator→handleCompletedStream→toolRuntime→executeToolCall→resume（ChatGenerationCoordinator.swift:313-398），`maxToolResumeCount=4`。无 forked/legacy 链。

⁴ **chat.secure_store** = 🔴 RED (P2 scope) — 多个 credential class:
- `test_settings_encode_containsNoCredential`（provider apiKey 明文写 UserDefaults `app.amber.ios.sharedSettingsJson`）
- `test_modelCustomHeaders_encode_containsNoCredential`（model customHeaders Authorization 明文）
- `test_assistantCustomHeaders_encode_containsNoCredential`（assistant customHeaders 经 import/restore 明文）
- `test_assistantCustomBodies_encode_containsNoCredential`（assistant customBodies 明文）
- 根因: `IosSettingsJsonBridge.encode` 无 redaction（IosSettingsJsonBridge.kt:10）。P2 加 JSON-tree redactor。

### deepread 行

⁵ **deepread.entry_real** = 🟢 GREEN
- 证据: `BoardView.createAndGenerateTask` (BoardView.swift:493-552) 真实入口（create→markRunning→generateViaLLM→complete），非 stub。

⁶ **deepread.provider_real** = 🔴 RED (P3 scope)
- 测试: `test_deepread_claudeSelected_constructsClaudeSetting`
- 证据: `BoardView.swift:513` 无条件构造 `ProviderSetting.OpenAI`，无 Claude 分支。选中 Claude 时静默打 `/chat/completions`。

⁷ **deepread.exec_real** = 🟢 GREEN
- 证据: `IOSDeepReadDraftGenerator.generateViaLLM` (IOSBoardPersistence.swift:2808) 真实 3 阶段管线，每阶段真实 model call。

⁸ **deepread.honest_fail** = 🔴 RED (P4 scope) — 多个:
- `test_deepread_allStagesThrow_statusFailed`（`synthesize` 吞错误返回 ""，仍标记 `.succeeded`；IOSBoardPersistence.swift:2900）
- `test_deepread_stageEmpty_notMarkedReady`（空阶段仍标记完成）
- `test_deepread_searchFailure_isSourceFailure` = 🟢 **回归保护**（当前已正确映射 source 级标记 + 不 hard-fail；锁定防 P4 改状态机时回归）

### subagent_standalone 行

⁹ **subagent_standalone.entry_real** = 🟢 GREEN (r6)
- 证据: `SubAgentsView` 是真实入口（SubAgentsView.swift:127,178 调 `runner.run`）。非 stub。注意: 当前入口走 legacy 路径（见 exec_real gap）。

¹⁰ **subagent_standalone.provider_real** = 🟢 GREEN (r6)
- 证据: standalone 继承 chat provider 选择；rebuild-OpenAI 是 chat 行 concern，非 standalone。无独立 provider gap。

¹¹ **subagent_standalone.exec_real** = 🔴 RED (P4 scope)
- 测试: `test_subagent_standalone_usesEnginePath`
- 证据: `SubAgentsView` 调 `runner.run`（legacy SubAgentManager/KMP 路径，SubAgentRunner.swift:379），非 `runViaEngine`（IOSAgentToolEngine）。基线旗标 `SubAgentsView.standaloneDispatchUsesEnginePath = false`。

¹² **subagent_standalone.honest_fail** = 🟢 GREEN (r6)
- 证据: 引擎路径工具/错误如实反映（IOSAgentToolEngine.swift:184-205）；legacy `mapStatus` 默认 completed 属同一 exec_real gap。

### subagent_chat 行

¹³ **subagent_chat.\*** = 🟢 GREEN (r1)
- 证据: `ChatToolRuntime.dispatchAdvancedToolCall` (ChatToolRuntime.swift:583-598) 调 `runViaEngine`（引擎路径），转发 resolved chat provider。provider_real GREEN（转发 Claude）、exec_real GREEN（IOSAgentToolEngine）、entry_real GREEN（真实 dispatch）、honest_fail GREEN（引擎如实报错）。mechanics 由 `IOSSubAgentEngineRunnerTests` 覆盖。

### council 行

¹⁴ **council.entry_real** = 🟢 GREEN
- 证据: 两入口（CouncilChatRuntimeView.swift:1052 standalone + ChatToolRuntime.swift:599 chat tool）均构造真实 `IOSCouncilRoomRunRequest` 并调 `IOSCouncilRoomRunner.run`。非 stub。

¹⁵ **council.provider_real** = 🟢 GREEN (P3 done)
- 测试: `test_council_claudeSelected_constructsClaudeSetting` (RED→GREEN)
- 修复: `IOSCouncilRoomRunner.resolveProviderSetting(selected:)`（SLICE_TEMPLATE 模式 1，复制自 DeepRead）透传选中 sealed type；两个 council 入口（CouncilChatRuntimeView standalone + chat tool）改用它。Claude → `ProviderSetting.Claude`，streamer(CouncilRunner.swift:580) dispatch 原生。
- 测试: `test_council_claudeSelected_constructsClaudeSetting`
- 证据: `IOSCouncilRoomRunner.makeProviderSetting(baseUrl:apiKey:)` (CouncilRunner.swift:991) 无条件返回 `ProviderSetting.OpenAI`。

¹⁶ **council.exec_real** = 🟢 GREEN
- 证据: `IOSCouncilRoomRunner.run` 经真实 streamer 循环（CouncilRunner.swift:1041），seat 失败→failedSeats。mechanics 由 `IOSCouncilRunnerMechanicsTests` 覆盖。

¹⁷ **council.honest_fail / persist** = 🟡 pre-existing (P4 scope)
- 测试: `IOSCouncilRunnerMechanicsTests.testRoomRunnerFreeChatPersistsTaskApprovalAndOrderedMessages` / `testRoomRunnerSeatFailureCompletesWithFailedSeat`
- 证据: council 返回 `failed` 而非 `completed`，席位数翻倍 7 vs 4，failedSeats 未记录（:141-206）。

### search / backup 行（secure_store，P2 scope）

¹⁸ **search.secure_store** = 🔴 RED (P2 scope)
- 测试: `test_searchProviderApiKey_encode_containsNoCredential`
- 证据: `addSearchProvider` (IOSSharedSettingsStore.swift:535) 写 apiKey 到 legacy mirror `customSearchProviders` + full Settings JSON。两 surface 明文。

¹⁹ **backup.secure_store** = 🔴 RED (P2 scope) — 多个 credential class:
- `test_backup_default_containsNoCredential`（provider apiKey 经 export→import round-trip 明文）
- `test_ttsEngineApiKey_encode_containsNoCredential`（TTS apiKey 两 surface 明文）
- `test_mcpServerHeaders_encode_containsNoCredential`（MCP Authorization header 明文写 `app.amber.ios.mcpServers`）
- 根因: `IOSSyncBackup.export` (IOSSyncBackup.swift:63) 无 redaction（Android 有 `BackupSettingsRedactor`）。

### recover 列

²⁰ **chat.recover** = 🔴 RED (P5 scope)
- 测试: `test_recovery_killMidStream_runInterruptedOrResumed`
- 证据: `ChatGenerationCoordinator.start` 只存内存（:91-94）；`recordRun` 只在 terminal event 调。kill mid-stream 不写行；无 startup sweep（`markInterrupted`/`listUnfinished` 存在但 Swift 未调）。

---

## P0 exit gate 对照（spec line 57-69）

- ✅ 真相矩阵建成，P0 行红格全部对应到具名测试（本文件）。
- ✅ 9 条 named 红灯存在（IOSParityRedLightTests.swift，8 RED + 1 回归保护 GREEN）。
- ✅ discovery loop-until-dry（7 轮，连续 2 轮 r6&r7 clean）。+5 secure_store cell 绑定。
- ✅ 视觉基线已建（visual-baseline/，5 屏 + manifest）。
- ✅ 工作区未提交改动已 stash（pre-start hygiene）。

## 绑定测试总览（15 条，文件 IOSParityRedLightTests.swift）

| # | 测试 | 状态 | cell | phase |
|---|------|------|------|-------|
| 1 | test_deepread_claudeSelected_constructsClaudeSetting | 🔴 | deepread.provider_real | P3 |
| 2 | test_deepread_allStagesThrow_statusFailed | 🔴 | deepread.honest_fail | P4 |
| 3 | test_deepread_stageEmpty_notMarkedReady | 🔴 | deepread.honest_fail | P4 |
| 4 | test_deepread_searchFailure_isSourceFailure | 🟢 | deepread.honest_fail（回归保护）| 保持绿 |
| 5 | test_subagent_standalone_usesEnginePath | 🔴 | subagent_standalone.exec_real | P4 |
| 6 | test_council_claudeSelected_constructsClaudeSetting | 🔴 | council.provider_real | P3 |
| 7 | test_settings_encode_containsNoCredential | 🔴 | chat.secure_store | P2 |
| 8 | test_backup_default_containsNoCredential | 🔴 | backup.secure_store | P2 |
| 9 | test_recovery_killMidStream_runInterruptedOrResumed | 🔴 | chat.recover | P5 |
| 10 | test_searchProviderApiKey_encode_containsNoCredential | 🔴 | search.secure_store | P2 |
| 11 | test_ttsEngineApiKey_encode_containsNoCredential | 🔴 | backup.secure_store | P2 |
| 12 | test_mcpServerHeaders_encode_containsNoCredential | 🔴 | backup.secure_store | P2 |
| 13 | test_modelCustomHeaders_encode_containsNoCredential | 🔴 | chat.secure_store | P2 |
| 14 | test_assistantCustomHeaders_encode_containsNoCredential | 🔴 | chat.secure_store | P2 |
| 15 | test_assistantCustomBodies_encode_containsNoCredential | 🔴 | chat.secure_store | P2 |

**14 RED + 1 回归保护 GREEN。0 crash。全部白盒、不依赖 live key。**
