import XCTest
import Shared
@testable import iosApp

@MainActor
final class IOSToolRuntimeTests: XCTestCase {
    func testTerminalToolFailureOutputIsStructuredAndRecognized() throws {
        let output = ChatToolOutputFormatter.toolFailureJSON(
            toolName: "search_web",
            reason: "The generation ended before this tool could run."
        )

        let data = try XCTUnwrap(output.data(using: .utf8))
        let payload = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(payload["ok"] as? Bool, false)
        XCTAssertEqual(payload["tool"] as? String, "search_web")
        XCTAssertEqual(
            ChatToolOutputFormatter.failureReason(
                from: [UIMessagePart.Text(text: output, metadata: nil)]
            ),
            "The generation ended before this tool could run."
        )
        let tool = UIMessagePart.Tool(
            toolCallId: "terminal-failure",
            toolName: "search_web",
            input: #"{"query":"swift"}"#,
            output: [UIMessagePart.Text(text: output, metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        XCTAssertEqual(ChatToolStepModel(tool: tool).state, .failed)
    }

    func testFileReadWithoutGrantNeedsUserAction() {
        let runtime = IOSToolRuntime(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore()
        )
        let decision = runtime.resolve(
            request: IOSToolInvocationRequest(
                toolName: "file_read_selected",
                operation: "read_preview",
                scopeDigest: "missing",
                payloadDigest: "missing",
                isUserInitiated: true
            )
        )

        guard case .needsUserAction = decision else {
            return XCTFail("Expected needsUserAction, got \(decision)")
        }
    }

    func testDisabledPolicyDeniesFileRead() throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let documentStore = DocumentAccessStore()
        let capability = try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" })
        permissionStore.setPolicy(.disabled, for: capability)
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(permissionStore: permissionStore, documentStore: documentStore)

        let decision = runtime.resolve(
            request: IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .deny = decision else {
            return XCTFail("Expected deny, got \(decision)")
        }
    }

    func testRunScopedPolicyIsNormalizedBeforeRuntimeResolve() throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let documentStore = DocumentAccessStore()
        let capability = try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" })
        permissionStore.setPolicy(.allowOncePerRun, for: capability)
        XCTAssertEqual(permissionStore.policy(for: capability), .askEveryTime)
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(permissionStore: permissionStore, documentStore: documentStore)

        let decision = runtime.resolve(
            request: IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: false
            )
        )

        guard case .needsUserAction = decision else {
            return XCTFail("Expected needsUserAction, got \(decision)")
        }
    }

    func testScopeToolOrPayloadMismatchDenies() throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )

        let requests = [
            IOSToolInvocationRequest(
                toolName: "other_tool",
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: "wrong-scope",
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: "wrong-payload",
                isUserInitiated: true
            )
        ]

        for request in requests {
            let decision = runtime.resolve(request: request)
            guard case .deny = decision else {
                return XCTFail("Expected deny for \(request), got \(decision)")
            }
        }
    }

    func testValidGrantAllowsOnlyOnce() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let request = IOSToolInvocationRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let firstResult = await runtime.executeFileReadSelected(request: request)
        guard case .success = firstResult else {
            return XCTFail("Expected success, got \(firstResult)")
        }

        let secondDecision = runtime.resolve(request: request)
        guard case .deny = secondDecision else {
            return XCTFail("Expected second use to deny, got \(secondDecision)")
        }
    }

    func testSubAgentAskEveryTimePromptsInForegroundAndDeniesInBackground() async throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults, taskStore: nil)
        let capability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.subagent_dispatch" }
        )
        let localToolExecutor = IOSLocalToolExecutor(
            permissionStore: permissionStore,
            documentStore: DocumentAccessStore(),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: FileManager.default.temporaryDirectory
                    .appendingPathComponent("subagent-permission-\(UUID().uuidString)")
            )
        )
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: defaults),
            localToolExecutor: localToolExecutor,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )

        let provider = IOSCouncilRoomRunner.makeProviderSetting(
            baseUrl: "https://example.com/v1",
            apiKey: "test-key"
        )
        let model = Model(
            modelId: "subagent-permission-test",
            displayName: "SubAgent Permission Test",
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
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: ToolKt.iosToolDeclarations(names: ["subagent_dispatch"]),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let toolCall = UIMessagePart.Tool(
            toolCallId: "subagent-permission",
            toolName: "subagent_dispatch",
            input: #"{"objective":"审查后台状态"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let assistant = UIMessage.companion.assistant(prompt: "")
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: provider,
            params: params,
            runId: "run-1",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )

        permissionStore.setPolicy(.askEveryTime, for: capability)
        let foreground = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )
        guard case .waitingForApproval(.council(let request)) = foreground else {
            return XCTFail("Expected foreground SubAgent approval card, got \(foreground)")
        }
        XCTAssertEqual(request.kind, .subAgent)

        let backgroundExecutor = IOSToolRuntimeUncheckedExecutorBox(try XCTUnwrap(
            runtime.backgroundToolExecutors(
                providerSetting: provider,
                params: params,
                runId: "run-1"
            )["subagent_dispatch"]
        ))
        let background = await backgroundExecutor.execute(
            name: "subagent_dispatch",
            arguments: toolCall.input,
            isUserInitiated: false
        )
        guard case .denied(let reason) = background else {
            return XCTFail("Expected background SubAgent denial, got \(background)")
        }
        XCTAssertTrue(reason.contains("回到 App"))

        // 历史 allowOncePerRun 值会先归一成 askEveryTime，不能绕过上述门禁。
        permissionStore.setPolicy(.allowOncePerRun, for: capability)
        XCTAssertEqual(permissionStore.policy(for: capability), .askEveryTime)
        XCTAssertTrue(runtime.requiresSubAgentApproval())
    }

    func testSubAgentApprovalCardUsesSubAgentIdentity() throws {
        let call = UIMessagePart.Tool(
            toolCallId: "subagent-approval",
            toolName: "subagent_dispatch",
            input: #"{"objective":"审查后台状态","role_id":"reviewer"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )

        let request = try XCTUnwrap(ChatToolApprovalRequestBuilder.subAgent(
            for: call,
            reason: "需要确认"
        ))

        XCTAssertEqual(request.kind, .subAgent)
        XCTAssertEqual(request.title, "调度子代理")
        XCTAssertEqual(request.capabilityId, "ios.agent.subagent_dispatch")
        XCTAssertEqual(request.objectivePreview, "审查后台状态")
    }

    private func makeTempFile(size: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data(repeating: 65, count: size).write(to: url)
        return url
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

private final class IOSToolRuntimeUncheckedExecutorBox: @unchecked Sendable {
    private let base: any IOSToolExecutor

    init(_ base: any IOSToolExecutor) {
        self.base = base
    }

    func execute(
        name: String,
        arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        await base.execute(
            name: name,
            arguments: arguments,
            isUserInitiated: isUserInitiated
        )
    }
}
