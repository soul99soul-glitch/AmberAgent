import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P1-d: 线程间消息工具契约（send_message / followup_task / wait_agent +
/// 后台引擎 mailbox drain）。基建参考 IOSOrchestrationToolTests /
/// IOSMailboxDeliveryTests / IOSAgentToolEngineTests：隔离临时目录的会话存储 +
/// 隔离 Room 库 + fake 后台调度器 + 注入的 activity center（不碰 `.shared`）。
@MainActor
final class IOSThreadMessagingTests: XCTestCase {

    // MARK: - Fakes

    /// 捕获 handoff 的后台调度 fake（照 IOSOrchestrationToolTests 同款）。
    private final class FakeBackgroundScheduler: IOSThreadOrchestrationToolService.BackgroundScheduling {
        var startedHandoff: IOSChatBackgroundHandoff?
        var startedReturn = true
        var activeRunByHex: [String: String] = [:]
        var cancelledRunIds: [String] = []
        var cancelReturn = true
        /// P1-e: 后台活跃 job 计数（协议要求；本套件不驱动限额，恒 0）。
        var activeJobCount = 0

        func start(
            handoff: IOSChatBackgroundHandoff,
            conversationStore: IOSConversationStore,
            toolRuntime: ChatToolRuntime,
            liveActivityController: AgentLiveActivityController,
            saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
        ) -> Bool {
            startedHandoff = handoff
            return startedReturn
        }

        func activeRunId(conversationHex: String) -> String? {
            activeRunByHex[conversationHex]
        }

        @discardableResult
        func cancelJob(runId: String) -> Bool {
            cancelledRunIds.append(runId)
            return cancelReturn
        }
    }

    /// 记录每次 generateText 收到的 messages（断言下一轮 upload 折入信封）。
    private final class MessagesRecordingProvider: IOSAgentTextProvider, @unchecked Sendable {
        private var script: [UIMessage]
        private(set) var recordedMessages: [[UIMessage]] = []
        init(_ script: [UIMessage]) { self.script = script }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            recordedMessages.append(messages)
            let message: UIMessage
            if !script.isEmpty {
                message = script.removeFirst()
            } else {
                message = UIMessage(
                    id: KotlinUuid.companion.random(),
                    role: MessageRole.assistant,
                    parts: [UIMessagePart.Text(text: "stop", metadata: nil)],
                    annotations: [],
                    createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
                    finishedAt: nil,
                    modelId: nil,
                    usage: nil,
                    translation: nil
                )
            }
            return MessageChunk(
                id: "chunk-\(UUID().uuidString)",
                model: "test-model",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }

    /// 固定结果 executor（记录调用次数）。
    private final class FixedExecutor: IOSToolExecutor {
        let result: IOSAgentToolOutcome
        private(set) var calls = 0
        init(_ result: IOSAgentToolOutcome) { self.result = result }
        func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
            calls += 1
            return result
        }
    }

    // MARK: - Fixtures

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSThreadMessagingTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func makeTempDirectory(_ name: String) -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func makeStore(directory: URL) -> IOSConversationStore {
        IOSConversationStore(baseDirectory: directory)
    }

    private func makeDatabase(directory: URL) -> AgentRuntimeDatabase {
        IosDatabaseFactory.shared.createDatabase(
            atFilePath: directory.appendingPathComponent("messaging.db").path
        )
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "msg-test",
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

    private func makeParams() -> TextGenerationParams {
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
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeRuntime() -> ChatToolRuntime {
        ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
    }

    private func makeService(
        store: IOSConversationStore,
        db: AgentRuntimeDatabase,
        scheduler: FakeBackgroundScheduler,
        center: IOSMailboxActivityCenter,
        currentConversationId: @escaping () -> KotlinUuid?,
        foregroundActiveRunId: @escaping (String) -> String? = { _ in nil },
        waitTimeoutMinMs: Int64 = 5_000
    ) -> IOSThreadOrchestrationToolService {
        IOSThreadOrchestrationToolService(
            conversationStoreProvider: { store },
            mailboxDaoProvider: { db.mailboxDao() },
            threadEdgeDaoProvider: { db.threadEdgeDao() },
            agentRuntimeDaoProvider: { db.agentRuntimeDao() },
            backgroundCoordinator: scheduler,
            makeBackgroundToolRuntime: { [weak self] in self?.makeRuntime() ?? ChatToolRuntime(
                settingsStore: SettingsStore(),
                sharedSettings: IOSSharedSettingsStore(),
                localToolExecutor: nil,
                searchTransport: IOSURLSessionSearchHTTPTransport(),
                mcpManager: IOSMcpManager(serverProvider: { [] })
            ) },
            currentConversationId: currentConversationId,
            foregroundActiveRunId: foregroundActiveRunId,
            cancelForegroundRun: { _ in false },
            activityCenter: center,
            waitTimeoutMinMs: waitTimeoutMinMs
        )
    }

    private func insertEdge(
        db: AgentRuntimeDatabase,
        childHex: String,
        parentHex: String,
        agentPath: String
    ) async throws {
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childHex,
            parentThreadId: parentHex,
            agentPath: agentPath,
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: Int64(Date().timeIntervalSince1970 * 1000)
        ))
    }

    private func parseJSON(_ text: String) -> [String: Any] {
        let data = text.data(using: .utf8)!
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    /// 读取指定收件人的未投递信封（Sendable 归约，照既有测试模式）。
    private func pendingEnvelopeSnapshots(
        _ dao: MailboxDao,
        recipientHex: String
    ) async -> [(id: String, type: String, author: String, payload: String, triggerTurn: Bool, parentTurnId: String?)] {
        await withCheckedContinuation { cont in
            dao.pendingForRecipient(recipientId: recipientHex) { result, _ in
                cont.resume(returning: (result ?? []).map {
                    (
                        id: $0.id,
                        type: $0.type,
                        author: $0.authorThreadId,
                        payload: $0.payload,
                        triggerTurn: $0.triggerTurn,
                        parentTurnId: $0.parentTurnId
                    )
                })
            }
        }
    }

    private func enqueue(_ dao: MailboxDao, _ envelope: MailboxEnvelopeEntity) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            dao.enqueue(envelope: envelope) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    private func pollUntil(
        timeout: TimeInterval = 5,
        _ condition: () async -> Bool
    ) async throws -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        return await condition()
    }

    // MARK: - send_message

    func testSendMessageEnqueuesToSameTreeChildAndFoldsAtTargetBoundary() async throws {
        let base = makeTempDirectory("SendFold")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        let childId = KotlinUuid.companion.random()
        let childHex = childId.toHexDashString()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: childId, assistantId: AssistantKt.DEFAULT_ASSISTANT_ID, messages: [], newConversation: false
        ))
        try await insertEdge(db: db, childHex: childHex, parentHex: rootHex, agentPath: "/root/worker")

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )

        let result = parseJSON(await service.execute(
            toolName: "send_message",
            arguments: #"{"target":"\#(childHex)","message":"查一下房价"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run",
            conversationId: rootId
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["status"] as? String, "queued")
        XCTAssertNil(scheduler.startedHandoff, "send_message 不得启动任何 run")

        // 信封入目标 mailbox：MESSAGE、triggerTurn=false、author=发送方 agentPath。
        let pending = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: childHex)
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending[0].type, "MESSAGE")
        XCTAssertEqual(pending[0].author, "/root")
        XCTAssertEqual(pending[0].payload, "查一下房价")
        XCTAssertEqual(pending[0].triggerTurn, false)
        XCTAssertEqual(pending[0].parentTurnId, "parent-run")

        // 目标边界折入（复用 P1-b 机制）：切到子会话，下一轮 upload 含渲染信封。
        await store.selectConversation(id: childId)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            mailboxStore: IOSMailboxStore(mailboxDao: db.mailboxDao()),
            orchestrationToolService: service
        )
        viewModel.conversationStore = store
        viewModel.reloadFromStore()
        let upload = await viewModel.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: [UIMessage.companion.assistant(prompt: "父工具结果")]
        )
        let uploadUserTexts = upload.filter { $0.role == MessageRole.user }.map { $0.toText() }
        XCTAssertEqual(uploadUserTexts, ["[mailbox MESSAGE from /root]\n查一下房价"])
        let remaining = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: childHex)
        XCTAssertTrue(remaining.isEmpty, "边界折入后信封必须标记 delivered")
    }

    func testSendMessageRejectsSelfNonDescendantAndUnknownTarget() async throws {
        let base = makeTempDirectory("SendRejects")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        let foreignRoot = KotlinUuid.companion.random()
        let foreignChildHex = "aaaa1111-1111-1111-1111-111111111111"
        try await insertEdge(db: db, childHex: foreignChildHex, parentHex: foreignRoot.toHexDashString(), agentPath: "/root/foreign")

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )
        let provider = makeProviderSetting()
        let params = makeParams()

        // 自身：按 path（"/root"）与按 hex 都拒绝。
        let selfByPath = parseJSON(await service.execute(
            toolName: "send_message",
            arguments: #"{"target":"/root","message":"hi"}"#,
            providerSetting: provider,
            params: params,
            runId: "r",
            conversationId: rootId
        ))
        XCTAssertEqual(selfByPath["error"] as? String, "cannot_message_self")
        let selfByHex = parseJSON(await service.execute(
            toolName: "send_message",
            arguments: #"{"target":"\#(rootHex)","message":"hi"}"#,
            providerSetting: provider,
            params: params,
            runId: "r",
            conversationId: rootId
        ))
        XCTAssertEqual(selfByHex["error"] as? String, "cannot_message_self")

        // 非树内（另一个 root 的后代）。
        let foreign = parseJSON(await service.execute(
            toolName: "send_message",
            arguments: #"{"target":"\#(foreignChildHex)","message":"hi"}"#,
            providerSetting: provider,
            params: params,
            runId: "r",
            conversationId: rootId
        ))
        XCTAssertEqual(foreign["error"] as? String, "not_a_descendant")
        XCTAssertEqual(foreign["ok"] as? Bool, false)

        // 未知 target。
        let unknown = parseJSON(await service.execute(
            toolName: "send_message",
            arguments: #"{"target":"nope","message":"hi"}"#,
            providerSetting: provider,
            params: params,
            runId: "r",
            conversationId: rootId
        ))
        XCTAssertEqual(unknown["error"] as? String, "unknown_target")
        XCTAssertTrue(scheduler.startedHandoff == nil)
    }

    // MARK: - followup_task

    func testFollowupToIdleTargetBootstrapsMessageAndStartsBackgroundRun() async throws {
        let base = makeTempDirectory("FollowupIdle")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        let childId = KotlinUuid.companion.random()
        let childHex = childId.toHexDashString()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: childId, assistantId: AssistantKt.DEFAULT_ASSISTANT_ID, messages: [], newConversation: false
        ))
        // 目标会话既有历史（bootstrap 必须在既有消息后追加，不能覆盖）。
        _ = await store.save(messages: [UIMessage.companion.user(prompt: "既有历史")], to: childId)
        try await insertEdge(db: db, childHex: childHex, parentHex: rootHex, agentPath: "/root/worker")

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )

        let result = parseJSON(await service.execute(
            toolName: "followup_task",
            arguments: #"{"target":"\#(childHex)","message":"继续调研"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run",
            conversationId: rootId
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["status"] as? String, "started")

        // 信封渲染消息直写目标会话（bootstrap 持久化，含既有历史）。
        let handoff = try XCTUnwrap(scheduler.startedHandoff, "idle 目标必须启动后台 run")
        XCTAssertEqual(handoff.conversationId.toHexDashString(), childHex)
        let childMessages = await store.messages(for: childId) ?? []
        let childUserTexts = childMessages.filter { $0.role == MessageRole.user }.map { $0.toText() }
        XCTAssertEqual(childUserTexts, ["既有历史", "[mailbox NEW_TASK from /root]\n继续调研"])

        // 审计信封标 delivered：目标 drain 为空，不会二次折入。
        let pending = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: childHex)
        XCTAssertTrue(pending.isEmpty, "bootstrap 信封只留审计记录，必须已投递")

        // 子 run 已记账（并发限额计数源）。
        let runs = try await db.agentRuntimeDao().listRecoverable(descriptorIds: ["chat"])
        XCTAssertTrue(runs.contains { $0.conversationId == childHex && $0.status == "running" })
    }

    func testFollowupToRunningTargetOnlyQueues() async throws {
        let base = makeTempDirectory("FollowupRunning")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        let childHex = "bbbb2222-2222-2222-2222-222222222222"
        try await insertEdge(db: db, childHex: childHex, parentHex: rootHex, agentPath: "/root/worker")

        let scheduler = FakeBackgroundScheduler()
        // 后台活跃 run（P1-c 两路径之一）。
        scheduler.activeRunByHex[childHex] = "bg-run-9"
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )

        let result = parseJSON(await service.execute(
            toolName: "followup_task",
            arguments: #"{"target":"\#(childHex)","message":"追加任务"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run",
            conversationId: rootId
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["status"] as? String, "queued")
        XCTAssertNil(scheduler.startedHandoff, "运行中目标不得 bootstrap 新 run")

        // 信封留 pending（等目标边界 drain），triggerTurn=true。
        let pending = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: childHex)
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending[0].type, "NEW_TASK")
        XCTAssertEqual(pending[0].triggerTurn, true)
        XCTAssertEqual(pending[0].parentTurnId, "parent-run")
    }

    func testFollowupToForegroundRunningTargetOnlyQueues() async throws {
        let base = makeTempDirectory("FollowupFg")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        let childHex = "cccc3333-3333-3333-3333-333333333333"
        try await insertEdge(db: db, childHex: childHex, parentHex: rootHex, agentPath: "/root/worker")

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        // 前台活跃 run（P1-c 两路径之二）。
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center,
            currentConversationId: { rootId },
            foregroundActiveRunId: { hex in hex == childHex ? "fg-run-1" : nil }
        )

        let result = parseJSON(await service.execute(
            toolName: "followup_task",
            arguments: #"{"target":"\#(childHex)","message":"追加"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run",
            conversationId: rootId
        ))
        XCTAssertEqual(result["status"] as? String, "queued")
        XCTAssertNil(scheduler.startedHandoff)
        let pending = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: childHex)
        XCTAssertEqual(pending.count, 1)
    }

    // MARK: - wait_agent

    func testWaitAgentReturnsImmediatelyWhenMailboxHasPending() async throws {
        let base = makeTempDirectory("WaitPending")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        // 预先有一条未投递信封。
        try await enqueue(db.mailboxDao(), MailboxEnvelopeEntity(
            id: "pending-1", authorThreadId: "/root/worker", recipientThreadId: rootHex,
            type: "MESSAGE", payload: "早到消息", triggerTurn: false, parentTurnId: nil,
            createdAt: 100, deliveredAt: nil
        ))
        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )

        let result = parseJSON(await service.execute(
            toolName: "wait_agent",
            arguments: #"{"timeout_ms":60000}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r",
            conversationId: rootId
        ))
        XCTAssertEqual(result["timed_out"] as? Bool, false)
        XCTAssertEqual(result["pending_count"] as? Int, 1)
        XCTAssertEqual(result["message"] as? String, "mailbox already has 1 pending")
    }

    func testWaitAgentReturnsEarlyOnMailboxActivity() async throws {
        let base = makeTempDirectory("WaitActivity")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)
        let childHex = "dddd4444-4444-4444-4444-444444444444"
        try await insertEdge(db: db, childHex: childHex, parentHex: rootHex, agentPath: "/root/worker")

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )
        let provider = makeProviderSetting()
        let params = makeParams()

        let waitTask = Task { @MainActor in
            await service.execute(
                toolName: "wait_agent",
                arguments: #"{"timeout_ms":60000}"#,
                providerSetting: provider,
                params: params,
                runId: "r",
                conversationId: rootId
            )
        }
        // 等订阅生效（订阅在前、pending 检查在后的窗口闭合依赖 actor 串行序）。
        let subscribed = try await pollUntil {
            await center.listenerCount(for: rootHex) > 0
        }
        XCTAssertTrue(subscribed)
        // 等 pending 检查完成（DAO 往返亚毫秒级；余量充足，不依赖具体实现时序）。
        try await Task.sleep(nanoseconds: 200_000_000)

        // 子线程向 root 投递信封（enqueue + signal 的真实生产路径）。
        let sendResult = parseJSON(await service.execute(
            toolName: "send_message",
            arguments: #"{"target":"\#(rootHex)","message":"报告进展"}"#,
            providerSetting: provider,
            params: params,
            runId: "child-run",
            conversationId: childIdFromHex(childHex)
        ))
        XCTAssertEqual(sendResult["status"] as? String, "queued")

        let result = parseJSON(await waitTask.value)
        XCTAssertEqual(result["timed_out"] as? Bool, false, "活动事件必须提前返回，不得等到超时")
        XCTAssertEqual(result["message"] as? String, "Mailbox activity detected.")
        XCTAssertEqual(result["pending_count"] as? Int, 1)
    }

    func testWaitAgentSteerInterruptReturnsFixedMessage() async throws {
        let base = makeTempDirectory("WaitSteer")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let rootHex = rootId.toHexDashString()
        let db = makeDatabase(directory: base)

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )
        // VM 与服务共用注入的 center（steer 打断走真实 VM 入队路径）。
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            mailboxStore: IOSMailboxStore(mailboxDao: db.mailboxDao()),
            orchestrationToolService: service,
            mailboxActivityCenter: center
        )
        viewModel.conversationStore = store
        viewModel.reloadFromStore()

        let waitTask = Task { @MainActor in
            await service.execute(
                toolName: "wait_agent",
                arguments: #"{"timeout_ms":60000}"#,
                providerSetting: makeProviderSetting(),
                params: makeParams(),
                runId: "r",
                conversationId: rootId
            )
        }
        let subscribed = try await pollUntil {
            await center.listenerCount(for: rootHex) > 0
        }
        XCTAssertTrue(subscribed)
        try await Task.sleep(nanoseconds: 200_000_000)

        // 生成中发送 = 入队 + 打断 wait（mailbox 不产生信封 → 固定「新输入打断」文案）。
        viewModel.generationActiveOverrideForTesting = { _ in true }
        viewModel.inputText = "先别等了"
        XCTAssertTrue(viewModel.sendMessage())

        let result = parseJSON(await waitTask.value)
        XCTAssertEqual(result["timed_out"] as? Bool, false)
        XCTAssertEqual(result["message"] as? String, "Wait interrupted by new input.")
    }

    func testWaitAgentTimeoutClampsToMinimumAndReportsClamp() async throws {
        let base = makeTempDirectory("WaitClamp")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        // 注入小下限：1ms 请求被 clamp 到 100ms，测试不等真实 5s。
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center,
            currentConversationId: { rootId },
            waitTimeoutMinMs: 100
        )

        let result = parseJSON(await service.execute(
            toolName: "wait_agent",
            arguments: #"{"timeout_ms":1}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r",
            conversationId: rootId
        ))
        XCTAssertEqual(result["timed_out"] as? Bool, true)
        XCTAssertEqual(result["timeout_ms"] as? Int64, 100, "1ms 必须被 clamp 到注入的下限 100ms")
        XCTAssertEqual(result["clamped"] as? Bool, true, "clamp 时返回里必须说明")
    }

    func testWaitAgentCancellationReturnsPromptlyNotSwallowed() async throws {
        let base = makeTempDirectory("WaitCancel")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)

        let scheduler = FakeBackgroundScheduler()
        let center = IOSMailboxActivityCenter()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, center: center, currentConversationId: { rootId }
        )

        // 请求 1ms → clamp 到默认下限 5s：若取消被吞，测试会等满 5s 才返回。
        let waitTask = Task { @MainActor in
            await service.execute(
                toolName: "wait_agent",
                arguments: #"{"timeout_ms":1}"#,
                providerSetting: makeProviderSetting(),
                params: makeParams(),
                runId: "r",
                conversationId: rootId
            )
        }
        try await Task.sleep(nanoseconds: 300_000_000)
        waitTask.cancel()
        let result = parseJSON(await waitTask.value)
        XCTAssertEqual(result["status"] as? String, "cancelled", "取消必须立即返回，不得冒充成功或等满超时")
        XCTAssertEqual(result["timed_out"] as? Bool, false)
    }

    // MARK: - 后台引擎 mailbox drain

    func testEngineBackgroundDrainFoldsEnvelopesIntoNextRoundWorkingAndPersists() async throws {
        let base = makeTempDirectory("EngineDrain")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let hex = conversationId.toHexDashString()
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])
        let db = makeDatabase(directory: base)
        // 第一轮间向 Room 投信封（模拟其他线程在工具执行期间发来的消息）。
        try await enqueue(db.mailboxDao(), MailboxEnvelopeEntity(
            id: "mb-1", authorThreadId: "/root/worker", recipientThreadId: hex,
            type: "MESSAGE", payload: "侧线程消息", triggerTurn: false, parentTurnId: nil,
            createdAt: 100, deliveredAt: nil
        ))

        // 脚本：第一轮模型发工具调用，第二轮收尾。
        let toolCall = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: "t1", toolName: "noop_tool", input: "{}", output: [],
                approvalState: ToolApprovalState.Auto.shared, streamIndex: nil, metadata: nil
            )],
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let provider = MessagesRecordingProvider([toolCall])
        let executor = FixedExecutor(.filled("{\"ok\":true}"))
        let mailboxStore = IOSMailboxStore(mailboxDao: db.mailboxDao())
        // 与后台协调器同构的 drain 闭包（协调器侧为
        // drainMailboxForBackgroundJob 助手）：drain + 渲染 + 持久化。
        let mailboxDrain: @Sendable () async -> IOSMailboxDrainResult = {
            await Self.drainAndRenderForTesting(
                mailboxStore: mailboxStore,
                store: store,
                conversationId: conversationId
            )
        }
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: ["noop_tool": executor],
            configuration: .init(maxSteps: 4, honorApprovalPause: false)
        )
        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [UIMessage.companion.user(prompt: "q1")],
            params: makeParams(),
            mailboxDrain: mailboxDrain
        )
        XCTAssertFalse(result.hitStepLimit)
        XCTAssertFalse(result.wasCancelled)

        // 第二轮 upload 含渲染信封（drain 在批量执行后、下一轮 streamStep 前）。
        XCTAssertGreaterThanOrEqual(provider.recordedMessages.count, 2)
        let secondRoundUserTexts = provider.recordedMessages[1]
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertTrue(
            secondRoundUserTexts.contains("[mailbox MESSAGE from /root/worker]\n侧线程消息"),
            "第二轮 upload 必须折入渲染信封，实际: \(secondRoundUserTexts)"
        )

        // Room 已标 delivered：二次 drain 为空。
        let remaining = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: hex)
        XCTAssertTrue(remaining.isEmpty)

        // 消息已持久化进会话。
        let persisted = (await store.messages(for: conversationId) ?? [])
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertTrue(persisted.contains("[mailbox MESSAGE from /root/worker]\n侧线程消息"))
    }

    // MARK: - M1: drain 折入后正常终态保存——信封恰好一次、无误导 notice

    /// 红测试对应缺陷：drain 把信封持久化进 current（displayMessages 快照不含
    /// drain），终态 saveBackgroundCompletion 的 else 分支以 job.displayMessages
    /// 为 base 比较 → 走 else 插 notice + 整段 suffix 二次落盘（信封重复）。
    /// 修复后：按消息 id 去重；差异全部来自 drain 时以 current 为终态不插 notice。
    func testDrainFoldedEnvelopePersistsExactlyOnceOnTerminalSaveWithoutNotice() async throws {
        let base = makeTempDirectory("DrainOnce")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let hex = conversationId.toHexDashString()
        let baseMessages = [UIMessage.companion.user(prompt: "q1")]
        await store.saveCurrent(messages: baseMessages)
        let db = makeDatabase(directory: base)
        // 第一轮间向 Room 投信封（模拟其他线程发来的消息）。
        try await enqueue(db.mailboxDao(), MailboxEnvelopeEntity(
            id: "mb-drain-1", authorThreadId: "/root/worker", recipientThreadId: hex,
            type: "MESSAGE", payload: "侧线程消息", triggerTurn: false, parentTurnId: nil,
            createdAt: 100, deliveredAt: nil
        ))

        // drain 折入（与后台协调器 drainMailboxForBackgroundJob 同构：drain +
        // 渲染 + 持久化 current+drained）。
        let mailboxStore = IOSMailboxStore(mailboxDao: db.mailboxDao())
        let drained = await Self.drainAndRenderForTesting(
            mailboxStore: mailboxStore,
            store: store,
            conversationId: conversationId
        )
        XCTAssertEqual(drained.values.count, 1, "drain 必须折入 1 条信封")

        // 正常终态：completedMessages = displayMessages + drained + 后台输出
        // （引擎 working 列表同构）。baseMessages 是 job.displayMessages（不含 drain）。
        let completed = baseMessages + drained.values + [UIMessage.companion.assistant(prompt: "后台完成")]
        let didSave = await store.saveBackgroundCompletion(
            baseMessages: baseMessages,
            completedMessages: completed,
            to: conversationId
        )
        XCTAssertTrue(didSave, "终态保存必须成功")

        let persisted = await store.messages(for: conversationId) ?? []
        let userTexts = persisted.filter { $0.role == MessageRole.user }.map { $0.toText() }
        let envelopeCount = userTexts.filter { $0.contains("[mailbox MESSAGE from /root/worker]") }.count
        XCTAssertEqual(envelopeCount, 1, "drain 折入信封必须恰好一次落盘，实际 \(envelopeCount) 次: \(userTexts)")

        let allText = persisted.map { $0.toText() }.joined()
        XCTAssertFalse(allText.contains("后台生成已完成"), "差异全部来自 drain 信封时不得插误导 notice")
        XCTAssertTrue(allText.contains("后台完成"), "后台输出必须落盘")
        // 信封已标 delivered：二次 drain 为空（不重复折入）。
        let remaining = await pendingEnvelopeSnapshots(db.mailboxDao(), recipientHex: hex)
        XCTAssertTrue(remaining.isEmpty)
    }

    private func childIdFromHex(_ hex: String) -> KotlinUuid {
        try! KotlinUuid.companion.parse(uuidString: hex)
    }

    /// 与后台协调器 `drainMailboxForBackgroundJob` 同构：Room drain + 渲染 +
    /// 会话持久化（MainActor 内完成；返回盒携带渲染出的 user 消息）。
    @MainActor
    private static func drainAndRenderForTesting(
        mailboxStore: IOSMailboxStore,
        store: IOSConversationStore,
        conversationId: KotlinUuid
    ) async -> IOSMailboxDrainResult {
        let snapshots = await mailboxStore.drainPending(forConversationId: conversationId)
        guard !snapshots.isEmpty else { return IOSMailboxDrainResult(values: []) }
        let drained = snapshots.map { envelope in
            UIMessage.companion.user(prompt: MailboxEnvelopeKt.renderMailboxEnvelopeToUserText(
                authorThreadId: envelope.authorThreadId,
                type: envelope.type,
                payload: envelope.payload
            ))
        }
        let current = (try? await store.loadConversationForOrchestration(conversationId))?.currentMessages ?? []
        let baseline = store.writeBaseline(for: conversationId)
        _ = await store.save(messages: current + drained, to: conversationId, ifUnchangedSince: baseline)
        return IOSMailboxDrainResult(values: drained)
    }
}
