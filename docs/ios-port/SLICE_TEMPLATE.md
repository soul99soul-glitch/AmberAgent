# SLICE_TEMPLATE.md — P0.5 vertical_slice 复制模板

> P0.5 (DeepRead + Claude) 切片产出的可复制做法。后续 phase（P3/P4）把这三
> 个模式复制到 council / subagent_standalone 等能力，把 redactor 复制到剩余
> credential class（P2 已在本切片全量覆盖）。

本切片把 truth_matrix `deepread` 行的 5 格翻绿：
`entry_real`(已绿) / `provider_real` / `exec_real`(已绿) / `honest_fail` / `secure_store`。

---

## 模式 1: 真实 provider 解析（删手搓 OpenAI 重建）

**问题**: 高级能力（DeepRead/Council/SubAgent）自行 `ProviderSetting.OpenAI(... chatCompletionsPath:"/chat/completions")` 从 baseUrl+apiKey 重建 provider，忽略用户实际选中的 Claude → 静默打 `/chat/completions` 而非 Anthropic 原生 `/messages`。

**做法（locked_decision: 不引入 ProviderExecutionContext；只透传 (provider, model, params)）**:

在能力的生成器上加一个静态 resolver，**按 sealed 类型透传**选中 provider，让 adapter 自己 dispatch：

```swift
// IOSDeepReadDraftGenerator (IOSBoardPersistence.swift)
static func resolveProviderSetting(selected: ProviderSetting?) -> ProviderSetting? {
    guard let selected else { return nil }
    switch selected {
    case let openAI as ProviderSetting.OpenAI: return ProviderSetting.OpenAI(/* clone, 保留 apiKey/baseUrl/... */)
    case let claude as ProviderSetting.Claude: return ProviderSetting.Claude(/* clone */)
    default: return nil  // 非 OpenAI-compatible → 调用方降级（interim safeguard）
    }
}
```

调用方（`BoardView.createAndGenerateTask`）传入 `providerRegistry?.selectedProvider`，不再手搓。
adapter（`OpenAIKmpProviderAdapter.generateText`）已按 sealed 类型 dispatch（OpenAI→`/chat/completions`，Claude→`/messages`），所以透传即正确。

**验证**: `test_deepread_claudeSelected_constructsClaudeSetting` —— 选中 Claude → resolver 返回 `ProviderSetting.Claude`。

**复制到 council (P3)**: `IOSCouncilRoomRunner.makeProviderSetting(baseUrl:apiKey:)`（CouncilRunner.swift:991，硬编码 OpenAI）改成接收 `selected: ProviderSetting?` 并透传。`test_council_claudeSelected_constructsClaudeSetting` 翻绿。

**复制到 subagent_standalone (P4)**: standalone 页（SubAgentsView）已透传 provider（runViaEngine 接收 providerSetting 参数）；P4 只需把页面的 dispatch 从 legacy `run` 切到 `runViaEngine`。

---

## 模式 2: 诚实失败状态机

**问题**: `synthesize` 吞掉异常返回 `""`，`createAndGenerateTask` 无条件 `complete()` → 所有阶段失败/空的任务被标记 `.succeeded`，假成功。

**做法**: 生成器返回**结构化结果**（draft + didFail），调用方据此 `fail()` 或 `complete()`：

```swift
// IOSDeepReadDraftGenerator
struct GenerationResult { let markdown: String; let didFail: Bool; let failureReason: String }

static func generateViaLLMResult(...) async -> GenerationResult {
    var threwCount = 0, emptyCount = 0
    // 每阶段 synthesize 返回 (text, threw)；threw→threwCount++，空→emptyCount++
    let allStagesUnusable = (threwCount + emptyCount) == 3
    return GenerationResult(markdown: body, didFail: allStagesUnusable, failureReason: reason)
}
// 旧 generateViaLLM() → String 保留为 thin wrapper（向后兼容）。
```

```swift
// BoardView.createAndGenerateTask
let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(...)
if result.didFail {
    deepReadStore.fail(id: task.id, message: "深度阅读生成失败：\(result.failureReason)")
} else {
    deepReadStore.complete(id: task.id, markdown: result.markdown)
}
```

**关键判断**: 单阶段可用即不算失败（partial 产出如实 complete）。**所有**阶段 threw 或 empty 才 `didFail`。

**验证**: `test_deepread_allStagesThrow_statusFailed`（全 threw → `.failed`）；`test_deepread_stageEmpty_notMarkedReady`（全空 → 非 `.succeeded`）。

**复制到 council (P4)**: council 的 seat 失败已进 `failedSeats`（机制存在），P4 把「全部 seat 失败 → outcome.failed」状态机固化 + 测试。

---

## 模式 3: 凭据落 Keychain（iOS-only 方案 B）

**问题**: 凭据（apiKey / customHeaders Authorization / MCP headers / search/TTS keys）明文写 UserDefaults `app.amber.ios.sharedSettingsJson` + legacy mirror + 默认备份 dump。

**做法（locked_decision: iOS-only；不动共享 ProviderSetting；credentialRef 跨端统一不在本 goal）**:

两层：**redactor**（encode 前剥离）+ **Keychain side-table**（真值存储 + load 时回灌）。

### 3a. Redactor（剥离）
```swift
// IOSCredentialRedactor.swift — 遍历 JSON 树，mask 任何 credential-bearing key
// (apikey/password/secret/token/authorization/privatekey) + credential header
// (headers/customHeaders/customBodies 里的 Authorization/x-api-key/...)
static func redact(_ jsonString: String) -> String { /* JSONSerialization 树遍历，敏感 key → mask sentinel */ }
```

接入点（所有 persist 路径都包一层 redactor）：
- `IOSSharedSettingsStore.restoreSnapshot` → encode 后 redact 再写 UserDefaults
- `IOSSyncBackup.export` → encode 后 redact 再加密（与 Android `BackupSettingsRedactor` 对等）
- legacy mirror（`addSearchProvider`/`addTtsEngine`）→ apiKey 字段直接写 `IOSCredentialRedactor.mask`
- `IOSMcpConfigStore.persist` → `IOSMcpStoredServer.redactSensitiveHeaders()` mask header value

### 3b. Keychain side-table（真值 + 回灌）
```swift
// IOSCredentialSideTable.swift — 通用 Keychain 边表（service app.amber.ios.credentials）
static func store(key:, value:) / load(key:) / delete(key:)
// key builder: providerApiKey(providerId:) / mcpHeader(serverName:, headerName:)
```

接入点：
- persist 前：敏感值存 side-table（MCP: `persist()` 里 redact 前先 `IOSCredentialSideTable.store`）
- load 时：mask sentinel → 从 side-table `load` 回灌（MCP: `loadServers` 里 value==mask → 查 side-table）

**内存中的 snapshot/servers 保留真值**（运行时需要）；只有**持久化形式**无明文。

**验证**（P0.5 已全量覆盖 8 个 credential class，本切片即 P2 主体）:
- `test_settings_encode_containsNoCredential`（provider apiKey）
- `test_modelCustomHeaders_encode_containsNoCredential` / `test_assistantCustomHeaders_encode_containsNoCredential` / `test_assistantCustomBodies_encode_containsNoCredential`
- `test_searchProviderApiKey_encode_containsNoCredential` / `test_ttsEngineApiKey_encode_containsNoCredential`
- `test_mcpServerHeaders_encode_containsNoCredential`
- `test_backup_default_containsNoCredential`（备份 round-trip 后无明文）

**待补（P2 收尾）**: provider apiKey 的 side-table 回灌（当前 SettingsStore/ProviderRegistryStore 已各自用 Keychain，但 `IOSSharedSettingsStore` load 路径未从 side-table 回灌 snapshot.providers[*].apiKey —— 当前重启后这些会读成 mask。P2 补 restoreSnapshot-load 回灌 + 旧明文值迁移）。

---

## 复制 checklist（后续能力 / phase）

对每个新能力（council / subagent / search / ...）:
1. **provider_real**: 找到能力里手搓 `ProviderSetting.OpenAI(... "/chat/completions")` 的点 → 改用 `resolveProviderSetting(selected:)` 透传。
2. **honest_fail**: 找到吞异常/无条件 complete 的点 → 改用 `GenerationResult`/结构化结果 + `fail()`。
3. **secure_store**: 新增 credential class → 确认 redactor 覆盖其 key（或加进敏感 key 集）+ side-table key builder + persist/load 回灌。

每个改动点配一条白盒红灯测试（RED→GREEN），过 verification_protocol。

## 切片实测结果（P0.5）
- `deepread.provider_real` / `honest_fail`(×2) / `secure_store`(×8): 全 GREEN。
- 回归: DeepRead mechanics (3) / Backup (12) / MCP (6) / SharedSettings (15) 全 GREEN，无回退。
- HARD-LOCK 文件（ChatView/PlaceholderViews/AppearanceSettingsView）未触碰。
- VISUAL-GUARDED BoardView 有 diff（honest-fail 状态机改动）→ 须重新采集 board baseline 比对（P0.5 EXIT 步骤）。
