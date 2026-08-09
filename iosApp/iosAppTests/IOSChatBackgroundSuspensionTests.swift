import XCTest
@preconcurrency import Shared
@testable import iosApp

final class IOSChatBackgroundSuspensionTests: XCTestCase {
    private var directory: URL!
    private var store: IOSChatBackgroundSuspensionStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatBackgroundSuspensionTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        store = IOSChatBackgroundSuspensionStore(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        store = nil
        directory = nil
        try super.tearDownWithError()
    }

    private func record(
        requestId: String = "app.amber.ios.chat.run1",
        runId: String = "run1",
        partial: String = "已经流出来的正文",
        suspendedAt: Int64 = 1_700_000_000_000,
        resumeCount: Int = 0
    ) -> IOSChatBackgroundSuspensionRecord {
        IOSChatBackgroundSuspensionRecord(
            requestId: requestId,
            runId: runId,
            partialAssistantText: partial,
            suspendedAt: suspendedAt,
            resumeCount: resumeCount
        )
    }

    func testSaveThenLoadRoundTripsPartialText() {
        let saved = record()
        store.save(saved)

        XCTAssertEqual(store.load(requestId: saved.requestId), saved)
    }

    func testLoadReturnsNilWhenNothingSuspended() {
        XCTAssertNil(store.load(requestId: "app.amber.ios.chat.absent"))
    }

    func testRemoveDeletesTheRecord() {
        let saved = record()
        store.save(saved)
        store.remove(requestId: saved.requestId)

        XCTAssertNil(store.load(requestId: saved.requestId))
        XCTAssertTrue(store.allRecords().isEmpty)
    }

    func testAllRecordsIsOrderedByInterruptionTime() {
        let later = record(requestId: "app.amber.ios.chat.b", runId: "b", suspendedAt: 200)
        let earlier = record(requestId: "app.amber.ios.chat.a", runId: "a", suspendedAt: 100)
        store.save(later)
        store.save(earlier)

        XCTAssertEqual(store.allRecords().map(\.runId), ["a", "b"])
    }

    func testAllRecordsIgnoresThePayloadFileSittingInTheSameDirectory() throws {
        store.save(record())
        // payload 与挂起记录同目录，扫描必须只认 .suspended.json 后缀。
        let payload = directory
            .appendingPathComponent(IOSChatBackgroundJobFileNaming.sanitized("app.amber.ios.chat.run1"))
            .appendingPathExtension("json")
        try Data("{\"runId\":\"run1\"}".utf8).write(to: payload)

        XCTAssertEqual(store.allRecords().count, 1)
    }

    func testResumeAttemptsAreCappedSoExpiryCannotLoopForever() {
        var current = record()
        XCTAssertTrue(current.canResume)

        for _ in 0..<IOSChatBackgroundSuspensionRecord.maxResumeAttempts {
            current = current.markingResumeAttempt()
        }

        XCTAssertEqual(current.resumeCount, IOSChatBackgroundSuspensionRecord.maxResumeAttempts)
        XCTAssertFalse(current.canResume, "到达上限后必须停止自动重投，降级成用户可见的可重试失败")
    }

    func testResumeCountSurvivesAnotherExpiry() {
        // 恢复后又被系统打断：计数必须继续累加，否则上限形同虚设。
        store.save(record().markingResumeAttempt())
        let carried = store.load(requestId: "app.amber.ios.chat.run1")?.resumeCount ?? 0

        XCTAssertEqual(carried, 1)
    }

    func testSanitizedNameKeepsDotsAndDashesButDropsSeparators() {
        let sanitized = IOSChatBackgroundJobFileNaming.sanitized("app.amber.ios.chat.a-b/c d")

        XCTAssertEqual(sanitized, "app.amber.ios.chat.a-b-c-d")
    }

    func testSuspensionAndPayloadFileNamesNeverCollide() {
        let requestId = "app.amber.ios.chat.run1"
        let payloadName = IOSChatBackgroundJobFileNaming.sanitized(requestId) + ".json"

        XCTAssertNotEqual(store.url(for: requestId).lastPathComponent, payloadName)
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
    private func makeHandoffCoordinator(
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
                bumpMessageRevision: { _ in },
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
                recordRun: { _, _, _, _, _ in },
                startLiveActivity: { _, _, _ in },
                saveMiniAppIfPresent: { _, _ in nil },
                messagesByInjectingRuntimeContext: { $0 },
                userFacingGenerationError: { rawMessage, _ in rawMessage }
            )
        )
        coordinator.backgroundStartOverrideForTesting = onStart
        return coordinator
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
