import XCTest
import UIKit
@preconcurrency import Shared
@testable import iosApp

final class IOSChatBackgroundSuspensionTests: XCTestCase {
    private struct HandoffSubmitFailure: Error {}

    private var directory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatBackgroundSuspensionTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        directory = nil
        try super.tearDownWithError()
    }

    // MARK: - 交接守卫（在途工具分类：.pure 允许 / .sideEffect、生图、审批拒绝）
    //
    // 真实场景：生成中用户点按另一个会话 → prepareForConversationChange →
    // handoffCurrentGenerationToBackground。旧守卫在任何前台工具执行中都拒绝
    // 交接，点按静默无效；本组测试钉死「在途 .pure 工具允许交接、.sideEffect /
    // 生图 / 审批维持拒绝」的决策边界。后台 start 用 override 替身观察，不提交
    // 真实 BGTask。

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.handoff.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }

    private func makeHandoffProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "handoff-test",
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

    private func makeHandoffParams() -> TextGenerationParams {
        let model = Model(
            modelId: "handoff-test-model",
            displayName: "handoff-test-model",
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
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeHandoffAssistantMessage(parts: [UIMessagePart]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026, month: 8, day: 8, hour: 0, minute: 0, second: 0, nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func makeHandoffPendingToolPart(toolName: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "handoff-tool-1",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func makeHandoff(runId: String, pendingToolName: String?) -> IOSChatBackgroundHandoff {
        let provider = makeHandoffProviderSetting()
        let parts: [UIMessagePart] = pendingToolName.map {
            [makeHandoffPendingToolPart(toolName: $0, input: "{}")]
        } ?? []
        let messages = [makeHandoffAssistantMessage(parts: parts)]
        return IOSChatBackgroundHandoff(
            runId: runId,
            startedAt: Int64(Date().timeIntervalSince1970 * 1000),
            inputDigest: "handoff-digest",
            conversationId: KotlinUuid.companion.random(),
            providerId: provider.id.toHexDashString(),
            providerSetting: provider,
            params: makeHandoffParams(),
            uploadMessages: messages,
            displayMessages: messages,
            mode: .continueModel,
            generativeUiRequirement: .none,
            generativeUiFallbackAttempted: false,
            fullToolNames: pendingToolName.map { [$0] } ?? []
        )
    }

    @MainActor
    private func makeHandoffConversationStore() -> IOSConversationStore {
        IOSConversationStore(
            baseDirectory: directory.appendingPathComponent("conv-\(UUID().uuidString)", isDirectory: true)
        )
    }

    @MainActor
    private func makeHandoffKeepAlive(submitSucceeds: Bool) -> BackgroundGenerationKeepAlive {
        BackgroundGenerationKeepAlive(
            beginBackgroundTask: { _, _ in UIBackgroundTaskIdentifier(rawValue: 901) },
            endBackgroundTask: { _ in },
            submitTaskRequest: { _ in
                if !submitSucceeds { throw HandoffSubmitFailure() }
            },
            cancelTaskRequest: { _ in },
            registerLaunchHandler: { _, _ in true }
        )
    }

    @MainActor
    private func makeHandoffCoordinator(
        backgroundExecution: BackgroundGenerationKeepAlive = .shared,
        onStart: @escaping (IOSChatBackgroundHandoff, IOSConversationStore) -> Bool
    ) -> ChatGenerationCoordinator {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let coordinator = ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: SettingsStore(),
                sharedSettings: sharedSettings,
                localToolExecutor: nil,
                searchTransport: HandoffTestSearchTransport(),
                liveActivityController: .shared,
                autoGenerateResponses: false,
                mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared),
                orchestrationToolService: nil,
                memoryPollutionMarker: nil
            ),
            bindings: ChatGenerationBindings(
                getMessages: { [] },
                setMessages: { _ in },
                bumpMessageRevision: { _, _ in },
                shouldPaceStreamPresentation: { true },
                setIsLoading: { _ in },
                setPendingMemoryApproval: { _ in },
                setPendingSearchApproval: { _ in },
                setPendingWebMountApproval: { _ in },
                setPendingWorkspaceApproval: { _ in },
                setPendingIshHandoffApproval: { _ in },
                setPendingMcpApproval: { _ in },
                setPendingCouncilApproval: { _ in },
                setPendingAskUser: { _ in },
                setContextCompactState: { _ in },
                persistMessages: { _ in true },
                capturePersistMessagesBaseline: { _ in nil },
                persistMessagesSnapshot: { _, _, _ in true },
                recordRun: { _, _, _, _, _ in true },
                startLiveActivity: { _, _, _ in },
                saveMiniAppIfPresent: { _, _ in nil },
                messagesByInjectingRuntimeContext: { $0 },
                userFacingGenerationError: { rawMessage, _ in rawMessage }
            ),
            backgroundExecution: backgroundExecution
        )
        coordinator.backgroundStartOverrideForTesting = onStart
        return coordinator
    }

    @MainActor
    func testBackgroundTransitionSettlesUIKitOnlyRunInsideShortWindow() {
        let keepAlive = makeHandoffKeepAlive(submitSucceeds: false)
        var startedCount = 0
        let coordinator = makeHandoffCoordinator(backgroundExecution: keepAlive) { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-ui-only-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))
        keepAlive.begin(runId, title: "t", subtitle: "s")

        XCTAssertEqual(keepAlive.executionAssertion(for: runId), .uiOnly)
        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore(),
            honorKeepAliveLease: true
        )

        XCTAssertFalse(didHandoff)
        XCTAssertEqual(startedCount, 0)
        // cancel 已同步清掉业务 owner；UIKit 短窗特意保留到异步落盘结束。
        XCTAssertEqual(keepAlive.executionAssertion(for: runId), .uiOnly)
        XCTAssertFalse(coordinator.isRunning)
    }

    @MainActor
    func testBackgroundTransitionWithoutAnyExecutionCancelsForegroundRun() {
        let keepAlive = BackgroundGenerationKeepAlive(
            beginBackgroundTask: { _, _ in .invalid },
            endBackgroundTask: { _ in },
            submitTaskRequest: { _ in },
            cancelTaskRequest: { _ in },
            registerLaunchHandler: { _, _ in true }
        )
        var startedCount = 0
        let coordinator = makeHandoffCoordinator(backgroundExecution: keepAlive) { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-none-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))
        keepAlive.begin(runId, title: "t", subtitle: "s", submitSystemTask: false)

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore(),
            honorKeepAliveLease: true
        )

        XCTAssertFalse(didHandoff)
        XCTAssertEqual(startedCount, 0)
        XCTAssertFalse(coordinator.isRunning)
    }

    @MainActor
    func testBackgroundTransitionKeepsForegroundSubmittedRequest() {
        let keepAlive = makeHandoffKeepAlive(submitSucceeds: true)
        var startedCount = 0
        let coordinator = makeHandoffCoordinator(backgroundExecution: keepAlive) { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-submitted-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))
        keepAlive.begin(runId, title: "t", subtitle: "s")

        XCTAssertEqual(keepAlive.executionAssertion(for: runId), .submitted)
        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore(),
            honorKeepAliveLease: true
        )

        XCTAssertFalse(didHandoff)
        XCTAssertEqual(startedCount, 0)
        XCTAssertEqual(keepAlive.executionAssertion(for: runId), .submitted)
    }

    @MainActor
    func testConversationChangeTransfersSubmittedRequestToDedicatedOwner() {
        let keepAlive = makeHandoffKeepAlive(submitSucceeds: true)
        var startedCount = 0
        let coordinator = makeHandoffCoordinator(backgroundExecution: keepAlive) { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-switch-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))
        keepAlive.begin(runId, title: "t", subtitle: "s")

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore(),
            honorKeepAliveLease: false
        )

        XCTAssertTrue(didHandoff)
        XCTAssertEqual(startedCount, 1)
        XCTAssertEqual(keepAlive.executionAssertion(for: runId), .none)
    }

    /// 在途 wait_agent（.pure）时交接必须成功：后台 start 被调、遗留 pending
    /// wait_agent 保持未执行（不标失败），由后台引擎 executePreExistingPendingTools 重放。
    @MainActor
    func testHandoffAllowsInFlightPureWaitAgentTool() {
        var startedCount = 0
        let coordinator = makeHandoffCoordinator { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-run-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        let handoff = makeHandoff(runId: runId, pendingToolName: "wait_agent")
        coordinator.installBackgroundHandoffForTesting(handoff)
        coordinator.installForegroundToolExecutionForTesting(toolName: "wait_agent", input: "{}")

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore()
        )

        XCTAssertTrue(didHandoff, "在途 pure 工具（wait_agent）必须允许交接")
        XCTAssertEqual(startedCount, 1, "后台 start 必须被调用一次")
        let pendingParts = handoff.displayMessages
            .flatMap(\.parts)
            .compactMap { $0 as? UIMessagePart.Tool }
        XCTAssertEqual(pendingParts.map(\.toolName), ["wait_agent"])
        XCTAssertEqual(pendingParts.first?.output.isEmpty ?? false, true,
                       "pending wait_agent 必须保持未执行（不标失败），留给后台重放")
    }

    /// 在途 tools_list（.pure，本地目录查询）同样允许交接。
    @MainActor
    func testHandoffAllowsInFlightPureToolsListTool() {
        var startedCount = 0
        let coordinator = makeHandoffCoordinator { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-run-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        let handoff = makeHandoff(runId: runId, pendingToolName: "tools_list")
        coordinator.installBackgroundHandoffForTesting(handoff)
        coordinator.installForegroundToolExecutionForTesting(toolName: "tools_list", input: "{}")

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore()
        )

        XCTAssertTrue(didHandoff, "在途 pure 工具（tools_list）必须允许交接")
        XCTAssertEqual(startedCount, 1)
    }

    /// 在途 workspace_file_write（.sideEffect）时交接必须拒绝，防止双重执行。
    @MainActor
    func testHandoffRejectsInFlightSideEffectWorkspaceWrite() {
        var startedCount = 0
        let coordinator = makeHandoffCoordinator { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-run-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        let handoff = makeHandoff(runId: runId, pendingToolName: "workspace_file_write")
        coordinator.installBackgroundHandoffForTesting(handoff)
        coordinator.installForegroundToolExecutionForTesting(
            toolName: "workspace_file_write",
            input: #"{"path":"a.txt","content":"x"}"#
        )

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore()
        )

        XCTAssertFalse(didHandoff, "在途 sideEffect 工具（workspace_file_write）必须拒绝交接")
        XCTAssertEqual(startedCount, 0, "拒绝时后台 start 不得被调用")
    }

    /// 在途生图任务一律拒绝交接（生成图片是真实副作用）。
    @MainActor
    func testHandoffRejectsInFlightImageGeneration() {
        var startedCount = 0
        let coordinator = makeHandoffCoordinator { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-run-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))
        coordinator.installForegroundImageToolExecutionForTesting()

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore()
        )

        XCTAssertFalse(didHandoff, "在途生图必须拒绝交接")
        XCTAssertEqual(startedCount, 0)
    }

    /// 审批等待中维持拒绝（既有语义不动）。
    @MainActor
    func testHandoffRejectsPendingToolApproval() async {
        var startedCount = 0
        let coordinator = makeHandoffCoordinator { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-run-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))
        let pending = ChatPendingToolApproval(
            toolCall: makeHandoffPendingToolPart(toolName: "search_web", input: #"{"query":"amber"}"#),
            providerSetting: makeHandoffProviderSetting(),
            params: makeHandoffParams(),
            runId: runId,
            startedAt: Int64(Date().timeIntervalSince1970 * 1000),
            inputDigest: "handoff-digest",
            conversationId: nil,
            baseMessages: []
        )
        await coordinator.installPendingSearchApprovalForTesting(
            pending: pending,
            request: SearchToolApprovalRequest(
                id: "handoff-req-1",
                toolName: "search_web",
                target: "amber",
                providerName: "handoff-test",
                providerType: "openai",
                reason: "test"
            )
        )

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore()
        )

        XCTAssertFalse(didHandoff, "审批等待中必须拒绝交接（既有语义）")
        XCTAssertEqual(startedCount, 0)
    }

    /// 无在途工具（纯流式）→ true，既有路径不回归。
    @MainActor
    func testHandoffAllowsStreamingWithNoInFlightTool() {
        var startedCount = 0
        let coordinator = makeHandoffCoordinator { _, _ in
            startedCount += 1
            return true
        }
        let runId = "handoff-run-\(UUID().uuidString)"
        coordinator.installRunSnapshotForTesting(runId: runId, snapshot: nil)
        coordinator.installBackgroundHandoffForTesting(makeHandoff(runId: runId, pendingToolName: nil))

        let didHandoff = coordinator.handoffCurrentGenerationToBackground(
            conversationStore: makeHandoffConversationStore()
        )

        XCTAssertTrue(didHandoff, "无在途工具（纯流式）必须允许交接（既有路径）")
        XCTAssertEqual(startedCount, 1)
    }
}

/// 计数专用替身 transport：本组测试不发起真实网络。
private final class HandoffTestSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [:]
        )!
        return (response, Data())
    }
}

final class IOSChatBackgroundStaleSweepTests: XCTestCase {
    private let taskMapKey = "\(Bundle.main.bundleIdentifier ?? "app.amber.ios").chat.backgroundTaskMap"
    private var originalTaskMap: Any?

    override func setUpWithError() throws {
        try super.setUpWithError()
        originalTaskMap = UserDefaults.standard.object(forKey: taskMapKey)
        UserDefaults.standard.removeObject(forKey: taskMapKey)
    }

    override func tearDownWithError() throws {
        if let originalTaskMap {
            UserDefaults.standard.set(originalTaskMap, forKey: taskMapKey)
        } else {
            UserDefaults.standard.removeObject(forKey: taskMapKey)
        }
        try super.tearDownWithError()
    }

    @MainActor
    func testColdStartSweepRemovesPersistedOwnerWithoutSubmittingAnotherRequest() {
        let requestId = "\(Bundle.main.bundleIdentifier ?? "app.amber.ios").chat.stale-run"
        UserDefaults.standard.set([requestId: "stale-run"], forKey: taskMapKey)

        let coordinator = IOSChatBackgroundGenerationCoordinator.shared
        coordinator.finalizeStalePersistedJobsIfNeeded()
        coordinator.finalizeStalePersistedJobsIfNeeded()

        XCTAssertTrue(
            UserDefaults.standard
                .dictionary(forKey: taskMapKey)?[requestId] == nil,
            "冷启动扫尾后不能保留会触发下一次后台提交的 task map owner"
        )
    }
}
