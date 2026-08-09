import XCTest
import Shared
@testable import iosApp

private final class WatchTestTransport: WatchConnectivityTransporting {
    var isSupported = true
    var isPaired = true
    var isWatchAppInstalled = true
    var isReachable = true
    var sendError: Error?

    init(sendError: Error? = nil) {
        self.sendError = sendError
    }

    func activate() {}
    func updateApplicationContext(_ context: [String: Any]) throws {}
    func transferUserInfo(_ userInfo: [String: Any]) -> String { "transfer" }
    func sendMessage(
        _ message: [String: Any],
        replyHandler: (([String: Any]) -> Void)?,
        errorHandler: ((Error) -> Void)?
    ) {
        if let sendError {
            errorHandler?(sendError)
        }
    }
}

// @MainActor:部分用例直接构造 MainActor 隔离的 ViewModel/运行时依赖。
@MainActor
final class WatchTaskSnapshotTests: XCTestCase {
    func testBridgeActionTimeoutReportsTheMatchingRequestId() async {
        let bridge = WatchConnectivityBridge(actionTimeoutNanoseconds: 10_000_000)
        bridge.configure(transport: WatchTestTransport())
        let request = WatchTaskActionRequest(
            requestId: "request-timeout",
            runId: "run-timeout",
            conversationId: "conversation-timeout",
            decisionId: nil,
            action: .cancel,
            optionId: nil,
            text: nil,
            createdAt: Date()
        )
        let resultReceived = expectation(description: "action timeout")
        bridge.onActionResult = { result in
            XCTAssertEqual(result.requestId, request.requestId)
            XCTAssertEqual(result.runId, request.runId)
            XCTAssertFalse(result.accepted)
            XCTAssertTrue(result.message?.contains("超时") == true)
            resultReceived.fulfill()
        }

        bridge.sendAction(request)

        await fulfillment(of: [resultReceived], timeout: 1)
    }

    func testBridgeActionSendFailureReportsTheMatchingRequestId() async {
        let bridge = WatchConnectivityBridge(actionTimeoutNanoseconds: 1_000_000_000)
        bridge.configure(transport: WatchTestTransport(sendError: NSError(
            domain: "WatchTaskSnapshotTests",
            code: 1
        )))
        let request = WatchTaskActionRequest(
            requestId: "request-failure",
            runId: "run-failure",
            conversationId: "conversation-failure",
            decisionId: nil,
            action: .cancel,
            optionId: nil,
            text: nil,
            createdAt: Date()
        )
        let resultReceived = expectation(description: "action failure")
        bridge.onActionResult = { result in
            XCTAssertEqual(result.requestId, request.requestId)
            XCTAssertEqual(result.runId, request.runId)
            XCTAssertFalse(result.accepted)
            XCTAssertTrue(result.message?.contains("失败") == true)
            resultReceived.fulfill()
        }

        bridge.sendAction(request)

        await fulfillment(of: [resultReceived], timeout: 1)
    }

    func testCoordinatorRejectsDecisionFromEarlierNodeInSameRun() async {
        let coordinator = WatchTaskCoordinator(bridge: WatchConnectivityBridge())
        coordinator.publishAskUser(
            runId: "run-1",
            conversationId: "conversation-1",
            request: WatchAskUserRequest(
                id: "decision-b",
                question: "第二个问题",
                options: ["B1", "B2"]
            )
        )

        let result = await coordinator.handleWatchAction(WatchTaskActionRequest(
            requestId: "request-a",
            runId: "run-1",
            conversationId: "conversation-1",
            decisionId: "decision-a",
            action: .choose,
            optionId: "choice-0",
            text: nil,
            createdAt: Date()
        ))

        XCTAssertFalse(result.accepted)
        XCTAssertEqual(result.message, "这个确认步骤已失效")
        XCTAssertEqual(coordinator.currentSnapshot().decision?.id, "decision-b")
    }

    func testCoordinatorClearsSummaryWhenANewRunStartsWithoutOne() {
        let coordinator = WatchTaskCoordinator(bridge: WatchConnectivityBridge())
        coordinator.publishCompleted(
            runId: "run-a",
            conversationId: "conversation-a",
            summary: "A 的结果"
        )

        coordinator.publish(
            runId: "run-b",
            conversationId: "conversation-b",
            presentation: .generatingResponse(modelName: "model-b")
        )

        let snapshot = coordinator.currentSnapshot()
        XCTAssertEqual(snapshot.runId, "run-b")
        XCTAssertNil(snapshot.summary)
    }

    func testCoordinatorDoesNotClaimCancellationWhenNoRunOwnerAcceptsIt() async {
        let coordinator = WatchTaskCoordinator(bridge: WatchConnectivityBridge())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        coordinator.attach(chatViewModel: viewModel)
        coordinator.publish(
            runId: "background-run-without-owner",
            conversationId: "conversation-1",
            presentation: .generatingResponse(modelName: "model")
        )

        let result = await coordinator.handleWatchAction(WatchTaskActionRequest(
            requestId: "cancel-1",
            runId: "background-run-without-owner",
            conversationId: "conversation-1",
            decisionId: nil,
            action: .cancel,
            optionId: nil,
            text: nil,
            createdAt: Date()
        ))

        XCTAssertFalse(result.accepted)
        XCTAssertEqual(result.message, "当前任务已经结束或不再由 iPhone 执行")
    }

    func testAttachProjectsHydratedBackgroundRunAsReconnectingInsteadOfIdle() {
        let coordinator = WatchTaskCoordinator(bridge: WatchConnectivityBridge())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )

        coordinator.attach(
            chatViewModel: viewModel,
            reconnecting: WatchTaskReconnectProjection(
                runId: "hydrated-background-run",
                conversationId: "hydrated-conversation"
            )
        )

        let snapshot = coordinator.currentSnapshot()
        XCTAssertEqual(snapshot.runId, "hydrated-background-run")
        XCTAssertEqual(snapshot.conversationId, "hydrated-conversation")
        XCTAssertEqual(snapshot.phase, AgentActivityPhase.reconnecting.rawValue)
        XCTAssertNotEqual(snapshot.phase, "idle")
    }

    func testCodecRoundTripKeepsDecisionAndSummary() throws {
        let decision = WatchDecision(
            id: "decision-1",
            type: .approval,
            title: "网络搜索",
            body: "查询 Swift concurrency",
            options: [
                WatchDecisionOption(id: "deny", title: "拒绝", style: .deny),
                WatchDecisionOption(id: "approve", title: "允许", style: .approve)
            ],
            riskLevel: .medium,
            allowsVoice: false
        )
        let snapshot = WatchTaskSnapshot(
            runId: "run-123",
            conversationId: "01234567-89ab-cdef-0123-456789abcdef",
            kind: AgentActivityKind.research.rawValue,
            phase: AgentActivityPhase.waitingForUser.rawValue,
            stage: AgentActivityStage.waitingForConfirmation.rawValue,
            headline: "Deep research",
            detail: "Waiting for confirmation",
            summary: nil,
            metricText: "3",
            decision: decision,
            actions: [.openOnPhone, .approve, .deny, .cancel],
            updatedAt: Date(timeIntervalSince1970: 1_700_000_000),
            isStale: false
        )

        let data = try WatchTaskCodec.encodeSnapshot(snapshot)
        let decoded = try WatchTaskCodec.decodeSnapshot(data)
        XCTAssertEqual(decoded, snapshot)

        let message = try WatchTaskCodec.snapshotMessage(for: snapshot)
        XCTAssertEqual(message[WatchConnectivityPayloadKey.type] as? String, WatchConnectivityPayloadKey.typeSnapshot)
        XCTAssertEqual(
            message[WatchConnectivityPayloadKey.protocolVersion] as? Int,
            WatchConnectivityPayloadKey.currentProtocolVersion
        )
    }

    func testBuilderApprovalDecisionDoesNotLeakRawToolPayload() {
        let request = SearchToolApprovalRequest(
            id: "search-1",
            toolName: "search_web",
            target: "https://example.com/secret-token=abc",
            providerName: "Bing",
            providerType: "bing",
            reason: "网络搜索和网页读取会访问外部站点，需要你确认。"
        )
        let decision = WatchTaskSnapshotBuilder.decision(from: .search(request))
        XCTAssertEqual(decision.type, .approval)
        XCTAssertEqual(decision.title, "执行网络搜索")
        XCTAssertTrue(decision.body.contains("example.com"))
        XCTAssertFalse(decision.body.contains("api_key"))
        XCTAssertEqual(decision.options.map(\.id), ["deny", "approve", "open-phone"])
    }

    func testBuilderSkillImportDecisionKeepsCandidateIdentityCompact() {
        let request = McpToolApprovalRequest(
            id: "skill-import-1",
            serverName: "local",
            toolName: "skill_import",
            argumentsPreview: "this fallback must not define candidate identity",
            reason: "请核对完整文件变更。",
            skillImportPreview: McpSkillImportPreview(
                skillName: "a-very-long-skill-name-that-needs-watch-truncation",
                mutationKind: .update,
                baseHash: "0123456789abcdef",
                candidateHash: "fedcba9876543210",
                beforeSummary: "before",
                afterSummary: "after",
                changedFiles: [
                    McpSkillImportFileChange(path: "SKILL.md", kind: .modified),
                    McpSkillImportFileChange(path: "mcp.json", kind: .added)
                ]
            )
        )

        let decision = WatchTaskSnapshotBuilder.decision(from: .mcp(request))

        XCTAssertTrue(decision.body.contains("更新"))
        XCTAssertTrue(decision.body.contains("a-very-long-skill-name-that-…"))
        XCTAssertTrue(decision.body.contains("2 处变更"))
        XCTAssertTrue(decision.body.contains("01234567"))
        XCTAssertTrue(decision.body.contains("fedcba98"))
        XCTAssertEqual(decision.options.map(\.id), ["deny", "approve", "open-phone"])
        XCTAssertFalse(decision.body.contains("local.skill_import"))
        XCTAssertFalse(decision.body.contains("SKILL.md"))
    }

    func testBuilderClipsCompletedSummary() {
        let long = String(repeating: "总结", count: 200)
        let snapshot = WatchTaskSnapshotBuilder.make(
            runId: "run-9",
            conversationId: "conv-1",
            presentation: .completed(),
            summary: long
        )
        XCTAssertEqual(snapshot.phase, AgentActivityPhase.completed.rawValue)
        XCTAssertNotNil(snapshot.summary)
        XCTAssertLessThanOrEqual(snapshot.summary?.count ?? 0, 281)
        XCTAssertTrue(snapshot.actions.contains(.openOnPhone))
        XCTAssertFalse(snapshot.actions.contains(.approve))
    }

    func testDecisionWithoutConversationOffersNoOpenPhonePath() {
        let snapshot = WatchTaskSnapshotBuilder.make(
            runId: "run-no-conversation",
            conversationId: nil,
            presentation: .waitingForUser(kind: .workflow),
            decision: WatchTaskSnapshotBuilder.askUserDecision(from: WatchAskUserRequest(
                id: "ask-no-conversation",
                question: "继续吗？",
                options: ["继续"]
            ))
        )

        XCTAssertFalse(snapshot.actions.contains(.openOnPhone))
        XCTAssertFalse(snapshot.decision?.options.contains(where: { $0.style == .openOnPhone }) == true)
    }

    func testCoordinatorRejectsOpenPhoneWithoutConversation() async {
        let coordinator = WatchTaskCoordinator(bridge: WatchConnectivityBridge())
        coordinator.publishAskUser(
            runId: "run-no-conversation",
            conversationId: nil,
            request: WatchAskUserRequest(
                id: "ask-no-conversation",
                question: "继续吗？",
                options: ["继续"]
            )
        )

        let result = await coordinator.handleWatchAction(WatchTaskActionRequest(
            requestId: "open-no-conversation",
            runId: "run-no-conversation",
            conversationId: nil,
            decisionId: "ask-no-conversation",
            action: .openOnPhone,
            optionId: "open-phone",
            text: nil,
            createdAt: Date()
        ))

        XCTAssertFalse(result.accepted)
        XCTAssertEqual(result.message, "当前任务没有可打开的会话")
    }

    func testAskUserDecisionSupportsChoicesAndVoice() {
        let request = WatchAskUserRequest(
            id: "ask-1",
            question: "下一章要不要加入反转？",
            options: ["加入", "不加入", "先缓一缓", "四", "五", "六", "七"]
        )
        let decision = WatchTaskSnapshotBuilder.askUserDecision(from: request)
        XCTAssertEqual(decision.type, .askUser)
        XCTAssertTrue(decision.allowsVoice)
        XCTAssertTrue(decision.options.contains(where: { $0.id == "choice-0" }))
        XCTAssertTrue(decision.options.contains(where: { $0.id == "choice-5" }))
        XCTAssertFalse(decision.options.contains(where: { $0.id == "choice-6" }))
        XCTAssertTrue(decision.options.contains(where: { $0.id == "skip" }))
        XCTAssertTrue(decision.options.contains(where: { $0.id == "dictate" }))
    }

    func testTextHelpersClipAndCollapseNewlines() {
        XCTAssertEqual(WatchTaskText.clipped("  hello  ", maxLength: 10), "hello")
        XCTAssertEqual(
            WatchTaskText.singleLine("a\nb\tc", maxLength: 10),
            "a b c"
        )
        let clipped = WatchTaskText.clipped(String(repeating: "x", count: 20), maxLength: 8)
        XCTAssertEqual(clipped?.count, 9) // 8 + ellipsis
        XCTAssertTrue(clipped?.hasSuffix("…") == true)
    }

    func testSnapshotMessageCarriesCurrentProtocolVersion() throws {
        let snapshot = WatchTaskSnapshotBuilder.make(
            runId: "run-1",
            conversationId: "conv-1",
            presentation: .generatingResponse(modelName: "m")
        )
        let message = try WatchTaskCodec.snapshotMessage(for: snapshot)
        XCTAssertEqual(
            message[WatchConnectivityPayloadKey.protocolVersion] as? Int,
            WatchConnectivityPayloadKey.currentProtocolVersion
        )
        XCTAssertEqual(
            message[WatchConnectivityPayloadKey.type] as? String,
            WatchConnectivityPayloadKey.typeSnapshot
        )
    }

    func testApprovalDecisionUsesConfirmationOpenPath() {
        let decision = WatchTaskSnapshotBuilder.decision(
            from: .search(
                SearchToolApprovalRequest(
                    id: "search-2",
                    toolName: "search_web",
                    target: "swift concurrency",
                    providerName: "Bing",
                    providerType: "bing",
                    reason: "需要确认"
                )
            )
        )
        XCTAssertEqual(decision.type, .approval)
        XCTAssertTrue(decision.options.contains(where: { $0.style == .openOnPhone }))
    }

    func testPublishCompletedPathOmitsDecisionInBuilderUsage() {
        // Production publishCompleted passes decision:nil; completed snapshots must not expose approve/deny.
        let snapshot = WatchTaskSnapshotBuilder.make(
            runId: "run-done",
            conversationId: "conv",
            presentation: .completed(),
            summary: "done",
            decision: nil
        )
        XCTAssertEqual(snapshot.phase, AgentActivityPhase.completed.rawValue)
        XCTAssertNil(snapshot.decision)
        XCTAssertFalse(snapshot.actions.contains(.approve))
        XCTAssertFalse(snapshot.actions.contains(.deny))
        XCTAssertTrue(snapshot.actions.contains(.openOnPhone))
    }

    func testDecisionSwitchMapsAskUserPromptToAskUserDecision() {
        let request = ChatAskUserRequest(
            id: "ask-prompt-1",
            question: "继续用 A 方案还是 B 方案？",
            options: ["A", "B"]
        )
        let decision = WatchTaskSnapshotBuilder.decision(from: .askUser(request))
        XCTAssertEqual(decision.type, .askUser)
        XCTAssertEqual(decision.id, "ask-prompt-1")
        XCTAssertTrue(decision.body.contains("A 方案") || decision.body.contains("继续用"))
        XCTAssertTrue(decision.options.contains(where: { $0.id == "choice-0" }))
        XCTAssertTrue(decision.options.contains(where: { $0.id == "skip" }))
        XCTAssertTrue(decision.allowsVoice)
    }

    func testAskUserRequestBuilderDecodesQuestionAndOptions() {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "tool-ask-1",
            toolName: "ask_user",
            input: #"{"question":"下一步做什么？","options":["继续","暂停","换方向"]}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let request = ChatToolApprovalRequestBuilder.askUser(for: toolCall)
        XCTAssertEqual(request?.question, "下一步做什么？")
        XCTAssertEqual(request?.options, ["继续", "暂停", "换方向"])
        XCTAssertEqual(request?.id, "tool-ask-1")
    }

    func testFinishAskUserAnswerWritesAnswerJSON() {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "tool-ask-2",
            toolName: "ask_user",
            input: #"{"question":"是否继续？","options":["是","否"]}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let assistantSeed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: assistantSeed.id,
            role: assistantSeed.role,
            parts: [toolCall],
            annotations: assistantSeed.annotations,
            createdAt: assistantSeed.createdAt,
            finishedAt: assistantSeed.finishedAt,
            modelId: assistantSeed.modelId,
            usage: assistantSeed.usage,
            translation: assistantSeed.translation
        )
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(
                userDefaults: UserDefaults(suiteName: "WatchAskUserTests-\(UUID().uuidString)")!
            ),
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://example.com",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let model = Model(
            modelId: "test-model",
            displayName: "Test Model",
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
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: provider,
            params: TextGenerationParams(
                model: model,
                temperature: nil,
                topP: nil,
                maxTokens: nil,
                tools: [],
                reasoningLevel: ReasoningLevel.off,
                customHeaders: [],
                customBody: []
            ),
            runId: "run-ask",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )
        let answered = runtime.finishAskUserAnswer(pending: pending, answer: "继续")
        let finishedTool = answered
            .flatMap { $0.parts.compactMap { $0 as? UIMessagePart.Tool } }
            .first { $0.toolCallId == "tool-ask-2" }
        XCTAssertNotNil(finishedTool)
        let outputText = finishedTool?.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined() ?? ""
        XCTAssertTrue(outputText.contains("\"answer\""))
        XCTAssertTrue(outputText.contains("继续"))

        let skipped = runtime.finishAskUserAnswer(pending: pending, answer: "  ")
        let skippedTool = skipped
            .flatMap { $0.parts.compactMap { $0 as? UIMessagePart.Tool } }
            .first { $0.toolCallId == "tool-ask-2" }
        let skippedText = skippedTool?.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined() ?? ""
        XCTAssertTrue(skippedText.contains("denied"))
    }
}
