import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Provider/model self-configuration tools — status (pure redacted read),
/// apply (foreground approval + Keychain write), refresh_models, set_model_slot.
@MainActor
final class IOSProviderConfigToolTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "IOSProviderConfigToolTests-\(UUID().uuidString)")!
    }

    private func makeStore() -> IOSSharedSettingsStore {
        IOSSharedSettingsStore(userDefaults: isolatedDefaults())
    }

    private func makeService(_ store: IOSSharedSettingsStore) -> IOSProviderConfigToolService {
        IOSProviderConfigToolService(sharedSettings: store)
    }

    private func makeRuntime(store: IOSSharedSettingsStore) -> ChatToolRuntime {
        ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: store,
            localToolExecutor: nil,
            searchTransport: ProviderConfigCountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "provider-config-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeParams(toolNames: [String]) -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: ToolKt.iosToolDeclarations(names: toolNames),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeToolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "provider-cfg-\(UUID().uuidString)",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func makeAssistantMessage(parts: [UIMessagePart]) -> UIMessage {
        let seed = UIMessage.companion.assistant(prompt: "")
        return UIMessage(
            id: seed.id,
            role: seed.role,
            parts: parts,
            annotations: [],
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
    }

    private func parseJSON(_ text: String) -> [String: Any] {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("输出必须是可解析 JSON，实际: \(text.prefix(200))")
            return [:]
        }
        return object
    }

    private func assertNoSecretLeak(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(text.contains("sk-live"), file: file, line: line)
        XCTAssertFalse(text.contains("sk-secret"), file: file, line: line)
        XCTAssertFalse(text.contains("\"apiKey\""), "must not use camelCase apiKey field", file: file, line: line)
        // Status labels only — never raw material after a write path.
        if text.contains("sk-") {
            XCTFail("tool result must never contain sk- key material: \(text.prefix(240))", file: file, line: line)
        }
    }

    private func seedProvider(
        store: IOSSharedSettingsStore,
        name: String = "半残DeepSeek",
        apiKey: String = "",
        withModel: Bool = false
    ) -> String {
        store.addCustomModel(
            name: withModel ? "seed-model" : "placeholder",
            modelId: withModel ? "deepseek-chat" : "placeholder-model",
            providerName: name
        )
        let provider = store.snapshot.providers.last!
        let id = provider.id.description() as String
        if !withModel {
            _ = store.updateProviderChatModels(providerId: id, models: [])
        }
        _ = store.updateProviderApiKey(providerId: id, apiKey: apiKey)
        // Leave chat slot pointing at a nonexistent model to exercise issues.
        store.setCurrentChatModelId("00000000-0000-0000-0000-000000000099")
        return id
    }

    // MARK: - P0 status

    func testStatusReportsMissingKeyEmptyModelsAndUnresolvedChatSlot() async {
        let store = makeStore()
        let providerId = seedProvider(store: store, name: "半残配置壳", apiKey: "", withModel: false)
        let service = makeService(store)

        let output = await service.execute(
            toolName: "provider_config_status",
            argumentsJSON: #"{"provider_id":"\#(providerId)"}"#
        )
        assertNoSecretLeak(output)
        let payload = parseJSON(output)
        XCTAssertEqual(payload["ok"] as? Bool, true)
        let providers = payload["providers"] as? [[String: Any]] ?? []
        XCTAssertEqual(providers.count, 1)
        let entry = try! XCTUnwrap(providers.first)
        XCTAssertEqual(entry["id"] as? String, providerId)
        XCTAssertEqual(entry["has_api_key"] as? Bool, false)
        XCTAssertEqual(entry["chat_model_count"] as? Int, 0)
        XCTAssertFalse(entry.keys.contains("apiKey"))

        let issues = payload["issues"] as? [String] ?? []
        XCTAssertTrue(issues.contains { $0.contains("no API key") }, "issues: \(issues)")
        XCTAssertTrue(issues.contains { $0.contains("chat") && $0.contains("resolve") }, "issues: \(issues)")
        let slots = payload["slots"] as? [String: Any]
        let chat = slots?["chat"] as? [String: Any]
        XCTAssertEqual(chat?["resolved"] as? Bool, false)
    }

    func testStatusNeverEchoesStoredKey() async {
        let store = makeStore()
        _ = seedProvider(store: store, apiKey: "sk-secret-should-never-appear-in-output", withModel: true)
        let service = makeService(store)
        let output = await service.execute(toolName: "provider_config_status", argumentsJSON: "{}")
        assertNoSecretLeak(output)
        let payload = parseJSON(output)
        let providers = payload["providers"] as? [[String: Any]] ?? []
        XCTAssertTrue(providers.contains { ($0["has_api_key"] as? Bool) == true })
    }

    func testStatusReportsAssistantChatSlot() async throws {
        let store = makeStore()
        _ = seedProvider(store: store, withModel: true)
        let model = try XCTUnwrap(
            store.snapshot.providers.last?.models.first(where: { $0.type == ModelType.chat })
        )
        let modelId = model.id.toHexDashString()
        store.setCurrentAssistantChatModelId(modelId)

        let output = await makeService(store).execute(
            toolName: "provider_config_status",
            argumentsJSON: "{}"
        )

        let payload = parseJSON(output)
        let slots = try XCTUnwrap(payload["slots"] as? [String: Any])
        let assistant = try XCTUnwrap(slots["assistant_chat"] as? [String: Any])
        XCTAssertEqual(assistant["model_id"] as? String, modelId)
        XCTAssertEqual(assistant["resolved"] as? Bool, true)
    }

    // MARK: - P1 apply

    func testApplyWritesKeyAndStatusReflectsHasKeyWithoutEcho() async {
        let store = makeStore()
        let providerId = seedProvider(store: store, apiKey: "", withModel: false)
        let service = makeService(store)
        let secret = "sk-live-real-key-material-abc12345"

        let applyOut = await service.execute(
            toolName: "provider_config_apply",
            argumentsJSON: #"{"provider_id":"\#(providerId)","api_key":"\#(secret)","enabled":true}"#
        )
        assertNoSecretLeak(applyOut)
        let apply = parseJSON(applyOut)
        XCTAssertEqual(apply["ok"] as? Bool, true)
        XCTAssertEqual(apply["api_key_status"] as? String, "updated")
        XCTAssertEqual(apply["has_api_key"] as? Bool, true)
        XCTAssertFalse(applyOut.contains(secret))

        let statusOut = await service.execute(
            toolName: "provider_config_status",
            argumentsJSON: #"{"provider_id":"\#(providerId)"}"#
        )
        assertNoSecretLeak(statusOut)
        XCTAssertFalse(statusOut.contains(secret))
        let status = parseJSON(statusOut)
        let entry = (status["providers"] as? [[String: Any]])?.first
        XCTAssertEqual(entry?["has_api_key"] as? Bool, true)
    }

    func testApplyRejectsHttpNonLocalBaseUrlAndPlaceholderKey() async {
        let store = makeStore()
        let providerId = seedProvider(store: store)
        let service = makeService(store)

        let badUrl = await service.execute(
            toolName: "provider_config_apply",
            argumentsJSON: #"{"provider_id":"\#(providerId)","base_url":"http://evil.example/v1"}"#
        )
        XCTAssertTrue(badUrl.contains("https") || badUrl.contains("failed"), badUrl)

        let placeholder = await service.execute(
            toolName: "provider_config_apply",
            argumentsJSON: #"{"provider_id":"\#(providerId)","api_key":"sk-xxx"}"#
        )
        XCTAssertTrue(placeholder.contains("占位") || placeholder.contains("failed"), placeholder)

        // Config must remain keyless.
        let entry = store.snapshot.providers.first { ($0.id.description() as String) == providerId }
        let key = (entry as? ProviderSetting.OpenAI)?.apiKey ?? ""
        XCTAssertTrue(key.isEmpty)
    }

    func testApplyApprovalRequiredAndDenyDoesNotWrite() async {
        let store = makeStore()
        let providerId = seedProvider(store: store, apiKey: "")
        let runtime = makeRuntime(store: store)
        let secret = "sk-secret-should-not-be-written"
        let toolCall = makeToolCall(
            name: "provider_config_apply",
            input: #"{"provider_id":"\#(providerId)","api_key":"\#(secret)"}"#
        )
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(toolNames: ["provider_config_apply"]),
            runId: "provider-apply-run",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending
        )
        guard case .waitingForApproval(let prompt) = result else {
            return XCTFail("apply 必须弹审批卡，实际: \(result)")
        }
        if case .mcp(let request) = prompt {
            XCTAssertFalse(request.argumentsPreview.contains(secret), "审批卡不得展示明文 key")
            XCTAssertTrue(request.argumentsPreview.contains("****") || request.argumentsPreview.contains("clear"))
        } else {
            XCTFail("expect mcp approval prompt")
        }

        // Deny path.
        let deniedMessages = await runtime.finishMcpApproval(pending: pending, allow: false)
        let deniedText = deniedMessages
            .flatMap(\.parts)
            .compactMap { ($0 as? UIMessagePart.Tool)?.output }
            .flatMap { $0 }
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined()
        XCTAssertTrue(deniedText.contains("拒绝") || deniedText.contains("denied"), deniedText)
        let entry = store.snapshot.providers.first { ($0.id.description() as String) == providerId }
        let key = (entry as? ProviderSetting.OpenAI)?.apiKey ?? ""
        XCTAssertTrue(key.isEmpty, "拒绝后 key 不得写入")
    }

    func testApplyAllowWritesKey() async {
        let store = makeStore()
        let providerId = seedProvider(store: store, apiKey: "")
        let runtime = makeRuntime(store: store)
        let secret = "sk-live-approved-write-key-xyz"
        let toolCall = makeToolCall(
            name: "provider_config_apply",
            input: #"{"provider_id":"\#(providerId)","api_key":"\#(secret)"}"#
        )
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(toolNames: ["provider_config_apply"]),
            runId: "provider-apply-allow",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
        _ = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending
        )
        let allowed = await runtime.finishMcpApproval(pending: pending, allow: true)
        let finishedTool = allowed
            .flatMap(\.parts)
            .compactMap { $0 as? UIMessagePart.Tool }
            .first
        let text = finishedTool?.output
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined() ?? ""
        assertNoSecretLeak(text)
        XCTAssertFalse(text.contains(secret))
        // C1: persisted tool.input must be redacted so later turns do not re-upload the key.
        XCTAssertFalse((finishedTool?.input ?? "").contains(secret), "finished input must redact api_key")
        assertNoSecretLeak(finishedTool?.input ?? "")
        let openAI = store.snapshot.providers.first { ($0.id.description() as String) == providerId }
            as? ProviderSetting.OpenAI
        XCTAssertEqual(openAI?.apiKey, secret)
    }

    func testApplyValidateBeforeWriteDoesNotPartialMutate() async {
        let store = makeStore()
        let providerId = seedProvider(store: store, name: "AtomicApply", apiKey: "", withModel: false)
        let before = store.snapshot.providers.first { ($0.id.description() as String) == providerId }
            as? ProviderSetting.OpenAI
        let service = makeService(store)
        let out = await service.execute(
            toolName: "provider_config_apply",
            argumentsJSON: #"{"provider_id":"\#(providerId)","enabled":false,"base_url":"http://evil.example/v1","api_key":"sk-live-should-not-write"}"#
        )
        XCTAssertTrue(out.contains("failed") || out.contains("https"), out)
        let after = store.snapshot.providers.first { ($0.id.description() as String) == providerId }
            as? ProviderSetting.OpenAI
        XCTAssertEqual(after?.enabled, before?.enabled, "enabled must not change when validation fails")
        XCTAssertEqual(after?.apiKey ?? "", "", "key must not write when validation fails")
        XCTAssertEqual(after?.baseUrl, before?.baseUrl)
    }

    // MARK: - P2 slots

    func testSetModelSlotUniqueAndAmbiguous() async {
        let store = makeStore()
        store.addCustomModel(name: "A", modelId: "alpha-unique-model", providerName: "ProvA")
        store.addCustomModel(name: "B", modelId: "beta-shared-token", providerName: "ProvB")
        store.addCustomModel(name: "C", modelId: "gamma-shared-token", providerName: "ProvC")
        let service = makeService(store)

        let unique = await service.execute(
            toolName: "settings_set_model_slot",
            argumentsJSON: #"{"slot":"chat","model_ref":"alpha-unique"}"#
        )
        let uniquePayload = parseJSON(unique)
        XCTAssertEqual(uniquePayload["ok"] as? Bool, true)
        XCTAssertEqual(uniquePayload["slot"] as? String, "chat")
        XCTAssertFalse(store.snapshot.chatModelId.toHexDashString().isEmpty)

        let amb = await service.execute(
            toolName: "settings_set_model_slot",
            argumentsJSON: #"{"slot":"title","model_ref":"shared-token"}"#
        )
        let ambPayload = parseJSON(amb)
        XCTAssertEqual(ambPayload["status"] as? String, "ambiguous")
        let candidates = ambPayload["candidates"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(candidates.count, 2)
    }

    func testSetModelSlotByModelId() async {
        let store = makeStore()
        store.addCustomModel(name: "OCR-m", modelId: "ocr-wire", providerName: "OCRProv")
        let model = try! XCTUnwrap(store.snapshot.providers.last?.models.first)
        let hex = model.id.toHexDashString()
        let service = makeService(store)
        let out = await service.execute(
            toolName: "settings_set_model_slot",
            argumentsJSON: #"{"slot":"ocr","model_id":"\#(hex)"}"#
        )
        let payload = parseJSON(out)
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(store.snapshot.ocrModelId.toHexDashString().caseInsensitiveCompare(hex), .orderedSame)
    }

    // MARK: - Wiring / background / search

    func testToolsDeferredAndChineseSearchHit() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: makeStore(),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
                documentStore: DocumentAccessStore(),
                workspaceStore: IOSWorkspaceStore(
                    baseDirectory: FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString)
                )
            ),
            autoGenerateResponses: false
        )
        _ = viewModel.currentToolDeclarationNames()
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
        let full = Set(bridge.fullToolDeclarations().map(\.name))
        for name in IOSProviderConfigToolCatalog.toolNames {
            XCTAssertTrue(full.contains(name), "\(name) must be in bridge catalog")
            XCTAssertFalse(Set(bridge.visibleTools().map(\.name)).contains(name), "\(name) deferred")
        }
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"配置提供商","limit":12}"#)
        XCTAssertTrue(payload.contains("provider_config_status"), payload)
        XCTAssertTrue(payload.contains("provider_config_apply"), payload)
    }

    func testBackgroundRegistersStatusOnlyDeniesMutations() async {
        let store = makeStore()
        let providerId = seedProvider(store: store, apiKey: "")
        let runtime = makeRuntime(store: store)
        let params = makeParams(toolNames: Array(IOSProviderConfigToolCatalog.toolNames).sorted())
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "bg-provider-cfg",
            conversationId: nil
        )
        XCTAssertNotNil(executors["provider_config_status"])
        XCTAssertNotNil(executors["provider_config_apply"])

        let statusBox = UncheckedToolExecutorBox(try! XCTUnwrap(executors["provider_config_status"]))
        let statusOutcome = await statusBox.execute(
            name: "provider_config_status",
            arguments: #"{"provider_id":"\#(providerId)"}"#,
            isUserInitiated: false
        )
        guard case .filled(let statusText) = statusOutcome else {
            return XCTFail("status should fill: \(statusOutcome)")
        }
        assertNoSecretLeak(statusText)

        let applyBox = UncheckedToolExecutorBox(try! XCTUnwrap(executors["provider_config_apply"]))
        let applyOutcome = await applyBox.execute(
            name: "provider_config_apply",
            arguments: #"{"provider_id":"\#(providerId)","api_key":"sk-secret-bg"}"#,
            isUserInitiated: false
        )
        guard case .denied(let reason) = applyOutcome else {
            return XCTFail("background apply must deny: \(applyOutcome)")
        }
        XCTAssertTrue(reason.contains("前台"), reason)
        let key = (store.snapshot.providers.first { ($0.id.description() as String) == providerId }
            as? ProviderSetting.OpenAI)?.apiKey ?? ""
        XCTAssertTrue(key.isEmpty)
    }

    func testRedactedApprovalPreviewMasksKey() {
        let preview = IOSProviderConfigToolCatalog.redactedApprovalPreview(
            argumentsJSON: #"{"provider_name":"OpenRouter","api_key":"sk-live-abcdefghijklmnop"}"#
        )
        XCTAssertFalse(preview.contains("sk-live-abcdefghijklmnop"))
        XCTAssertTrue(preview.contains("****") || preview.contains("mnop"))
    }

    func testAwaitingApprovalSnapshotRedactsKeyWithoutMutatingLiveCall() throws {
        let secret = "sk-live-awaiting-approval-secret"
        let tool = makeToolCall(
            name: "provider_config_apply",
            input: #"{"provider_name":"OpenRouter","api_key":"\#(secret)"}"#
        )
        let liveMessages = [makeAssistantMessage(parts: [tool])]

        let persisted = IOSProviderConfigToolCatalog.redactedApprovalMessages(liveMessages)
        let persistedTool = try XCTUnwrap(
            persisted.flatMap(\.parts).compactMap { $0 as? UIMessagePart.Tool }.first
        )

        XCTAssertFalse(persistedTool.input.contains(secret))
        XCTAssertTrue(persistedTool.input.contains("****"))
        XCTAssertTrue(tool.input.contains(secret), "审批执行仍需使用内存中的原始参数")
    }

    func testTimelineDetailRedactsApiKey() {
        let tool = makeToolCall(
            name: "provider_config_apply",
            input: #"{"provider_name":"X","api_key":"sk-secret-timeline-leak"}"#
        )
        let step = ChatToolStepModel(tool: tool)
        XCTAssertFalse((step.detail ?? "").contains("sk-secret-timeline-leak"))
    }
}

private final class ProviderConfigCountingSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let http = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/html; charset=utf-8"]
        )!
        return (http, Data())
    }
}

private final class UncheckedToolExecutorBox: @unchecked Sendable {
    private let base: any IOSToolExecutor
    init(_ base: any IOSToolExecutor) { self.base = base }
    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        await base.execute(name: name, arguments: arguments, isUserInitiated: isUserInitiated)
    }
}
