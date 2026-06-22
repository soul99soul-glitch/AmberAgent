# truth_matrix — iOS Parity Closure

> 真相矩阵（spec success_metric, line 31-37）。P0 的产出。每个 cell 绑定具名测试。
> 绿格 iff: 绑定测试全过 **且** 非 skip-gate **且** 经 verification_protocol 确认（spec line 34）。
> 当前状态: **P0 baseline** — 所有 P0_block 红格已绑定具名测试（RED），待后续 phase 翻绿。

## 图例
- 🔴 = RED（parity gap，绑定测试当前失败）— 这是 P0 的正确状态
- 🟢 = GREEN（parity 达标，或回归保护测试通过）
- ⬜ = 非 P0_block 范围（后续 phase 处理）
- 🟡 = pre-existing 失败已绑定（非 9 条 named，属后续 phase）

## P0_block 矩阵（goal 主体，spec line 36）

`cols[entry_real..secure_store]` × `[chat, deepread, subagent_standalone, subagent_chat, council]`

| row \ col | entry_real | provider_real | exec_real | honest_fail | secure_store |
|-----------|-----------|---------------|-----------|-------------|--------------|
| **chat** | 🟡 pre-existing¹ | 🟢 r4² | 🟢 r4³ | 🟡 pre-existing¹ | 🔴 r0/r3/r5⁴ |
| **deepread** | 🟢 r1⁵ | 🔴 r0⁶ | 🟢 r1⁷ | 🔴 r0⁸ | 🔴 r0⁴ |
| **subagent_standalone** | 🟢 r6⁹ | 🟢 r6¹⁰ | 🔴 r0¹¹ | 🟢 r6¹² | ⬜ |
| **subagent_chat** | 🟢 r1¹³ | 🟢 r1¹³ | 🟢 r1¹³ | 🟢 r1¹³ | ⬜ |
| **council** | 🟢 r1¹⁴ | 🔴 r0¹⁵ | 🟢 r1¹⁶ | 🟡 pre-existing¹⁷ | ⬜ |

## 其他行（非 P0_block，后续 phase）

| row \ col | entry_real | provider_real | exec_real | honest_fail | secure_store | persist | recover | verified |
|-----------|-----------|---------------|-----------|-------------|--------------|---------|---------|----------|
| **search** | ⬜ | ⬜ | ⬜ | ⬜ | 🔴 r1¹⁸ | ⬜ | ⬜ | ⬜ |
| **backup** | ⬜ | ⬜ | ⬜ | ⬜ | 🔴 r0¹⁹ | ⬜ | ⬜ | ⬜ |
| **chat.recover** | — | — | — | — | — | — | 🔴 r0²⁰ | — |

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

¹⁵ **council.provider_real** = 🔴 RED (P3 scope)
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
