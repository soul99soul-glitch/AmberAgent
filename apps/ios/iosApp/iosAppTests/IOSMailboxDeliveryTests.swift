import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P1-b: mailbox 信封折入契约（基建参考 IOSSteerQueueTests）。
///
/// 契约：
/// 1. 工具循环边界折入：注入 2 信封 → 边界消费 → 下一轮 upload 含渲染文本（FIFO）、
///    会话持久化出 user 消息、Room 标记 delivered。
/// 2. 顺序：mailbox 信封在 steer 文本之前。
/// 3. 终态 leftover 不回 composer：留在 Room；下一次 run 首轮 `prepareAndStartStreaming`
///    头消费（同门控：补绘重试轮 displayMessagesOverride != nil 不消费）。
/// 4. 幂等：同一 run 重复边界不重复折入（drain 二次为空）。
@MainActor
final class IOSMailboxDeliveryTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSMailboxDeliveryTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func makeTempDirectory(_ name: String) -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func makeConversationStore(directory: URL) -> IOSConversationStore {
        IOSConversationStore(baseDirectory: directory)
    }

    /// 隔离 Room 库（临时文件路径，与生产 Documents/agent_runtime.db 无关）。
    private func makeMailboxDao(directory: URL) -> MailboxDao {
        let db = IosDatabaseFactory.shared.createDatabase(
            atFilePath: directory.appendingPathComponent("mailbox.db").path
        )
        return db.mailboxDao()
    }

    private func makeViewModel(
        conversationStore: IOSConversationStore,
        mailboxDao: MailboxDao
    ) -> ChatViewModel {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            mailboxStore: IOSMailboxStore(mailboxDao: mailboxDao)
        )
        viewModel.conversationStore = conversationStore
        viewModel.reloadFromStore()
        return viewModel
    }

    private func envelope(
        id: String,
        recipient: KotlinUuid,
        payload: String,
        createdAt: Int64,
        type: String = MailboxEnvelopeType.message.name
    ) -> MailboxEnvelopeEntity {
        MailboxEnvelopeEntity(
            id: id,
            authorThreadId: "/root/a",
            recipientThreadId: recipient.toHexDashString(),
            type: type,
            payload: payload,
            triggerTurn: false,
            parentTurnId: nil,
            createdAt: createdAt,
            deliveredAt: nil
        )
    }

    private func enqueue(_ dao: MailboxDao, _ envelope: MailboxEnvelopeEntity) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            dao.enqueue(envelope: envelope) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    private func pendingEnvelopeIds(_ dao: MailboxDao, recipient: KotlinUuid) async -> [String] {
        await withCheckedContinuation { cont in
            dao.pendingForRecipient(recipientId: recipient.toHexDashString()) { result, _ in
                // 回调内归约成 Sendable 字符串（Kotlin 实体非 Sendable，不外传）。
                cont.resume(returning: (result ?? []).map(\.id))
            }
        }
    }

    /// 轮询等待 drain 的异步会话落盘完成（drain 的 persist 走 fire-and-forget Task，
    /// 与 drainSteerQueue 同一模式）。
    private func pollPersistedUserTexts(
        store: IOSConversationStore,
        conversationId: KotlinUuid,
        minUserMessages: Int,
        timeout: TimeInterval = 5
    ) async throws -> [String] {
        let deadline = Date().addingTimeInterval(timeout)
        var last: [String] = []
        while Date() < deadline {
            let messages = await store.messages(for: conversationId) ?? []
            last = messages.filter { $0.role == MessageRole.user }.map { $0.toText() }
            if last.count >= minUserMessages { return last }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return last
    }

    private func renderedText(_ payload: String) -> String {
        MailboxEnvelopeKt.renderMailboxEnvelopeToUserText(
            authorThreadId: "/root/a",
            type: MailboxEnvelopeType.message.name,
            payload: payload
        )
    }

    // MARK: - 1. 工具循环边界折入

    func testToolLoopBoundaryFoldsEnvelopesIntoNextRoundUploadAndDelivers() async throws {
        let base = makeTempDirectory("MailboxBoundary")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeConversationStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let dao = makeMailboxDao(directory: base)
        try await enqueue(dao, envelope(id: "mb-1", recipient: conversationId, payload: "信封一", createdAt: 100))
        try await enqueue(dao, envelope(id: "mb-2", recipient: conversationId, payload: "信封二", createdAt: 200))
        let viewModel = makeViewModel(conversationStore: store, mailboxDao: dao)

        // 模拟工具结果轮：continueAfterToolResult 边界消费。
        let baseMessages = [UIMessage.companion.assistant(prompt: "工具结果")]
        let nextRoundUpload = await viewModel.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: baseMessages
        )

        // 下一轮 upload 含渲染文本（FIFO 顺序，结构头格式）。
        let uploadUserTexts = nextRoundUpload
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertEqual(uploadUserTexts, [renderedText("信封一"), renderedText("信封二")])

        // 会话内存出现 user 节点。
        let memoryUserTexts = viewModel.messages
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertEqual(memoryUserTexts, [renderedText("信封一"), renderedText("信封二")])

        // Room 标记 delivered：再次 drain 为空。
        let remainingAfterDelivery = await pendingEnvelopeIds(dao, recipient: conversationId)
        XCTAssertTrue(remainingAfterDelivery.isEmpty)

        // 会话持久化出 user 消息节点（exactly once：drain 即落盘）。
        let persisted = try await pollPersistedUserTexts(
            store: store,
            conversationId: conversationId,
            minUserMessages: 2
        )
        XCTAssertEqual(persisted, [renderedText("信封一"), renderedText("信封二")])
    }

    // MARK: - 2. 顺序：mailbox 先于 steer

    func testMailboxEnvelopesFoldBeforeSteerTextsInUpload() async throws {
        let base = makeTempDirectory("MailboxOrdering")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeConversationStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let dao = makeMailboxDao(directory: base)
        try await enqueue(dao, envelope(id: "mb-1", recipient: conversationId, payload: "信封甲", createdAt: 100))
        try await enqueue(dao, envelope(id: "mb-2", recipient: conversationId, payload: "信封乙", createdAt: 200))
        let viewModel = makeViewModel(conversationStore: store, mailboxDao: dao)

        // 生成中排队一条 steer。
        viewModel.generationActiveOverrideForTesting = { _ in true }
        viewModel.inputText = "steer 文本"
        XCTAssertTrue(viewModel.sendMessage())

        let baseMessages = [UIMessage.companion.assistant(prompt: "工具结果")]
        let nextRoundUpload = await viewModel.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: baseMessages
        )
        let uploadUserTexts = nextRoundUpload
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertEqual(
            uploadUserTexts,
            [renderedText("信封甲"), renderedText("信封乙"), "steer 文本"],
            "mailbox 信封必须排在 steer 文本之前"
        )
    }

    // MARK: - 3. 终态 leftover 留在 Room；下一次 run 首轮头消费

    func testTerminalLeftoverStaysInRoomUntilNextRunHeadConsumesIt() async throws {
        let base = makeTempDirectory("MailboxTerminalLeftover")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeConversationStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let dao = makeMailboxDao(directory: base)
        try await enqueue(dao, envelope(id: "mb-late", recipient: conversationId, payload: "终态信封", createdAt: 100))
        let viewModel = makeViewModel(conversationStore: store, mailboxDao: dao)

        // 无工具边界的 run 终态：信封不回 composer（与 steer 不同），留在 Room。
        XCTAssertTrue(viewModel.inputText.isEmpty)
        XCTAssertEqual(
            viewModel.messages.filter { $0.role == MessageRole.user }.count,
            0,
            "终态 leftover 不落成消息、不进 composer"
        )
        let pendingAtTerminal = await pendingEnvelopeIds(dao, recipient: conversationId)
        XCTAssertEqual(
            pendingAtTerminal,
            ["mb-late"],
            "未消费信封必须留在 Room 等下次 run"
        )

        // 下一次 run 首轮 prepareAndStartStreaming 头消费（displayMessagesOverride == nil）。
        let drained = await viewModel.generationCoordinatorForTesting
            .drainMailboxAtNewRunHeadForTesting(
                conversationId: conversationId,
                displayMessagesOverride: nil
            )
        XCTAssertEqual(drained.map { $0.toText() }, [renderedText("终态信封")])
        XCTAssertEqual(
            viewModel.messages.filter { $0.role == MessageRole.user }.map { $0.toText() },
            [renderedText("终态信封")]
        )
        let remainingAfterHeadConsumption = await pendingEnvelopeIds(dao, recipient: conversationId)
        XCTAssertTrue(remainingAfterHeadConsumption.isEmpty, "头消费后 Room 必须标记 delivered")
    }

    // MARK: - 4. 补绘重试轮不消费

    func testRedrawRoundDoesNotConsumeMailbox() async throws {
        let base = makeTempDirectory("MailboxRedraw")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeConversationStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let dao = makeMailboxDao(directory: base)
        try await enqueue(dao, envelope(id: "mb-draw", recipient: conversationId, payload: "补绘不该消费", createdAt: 100))
        let viewModel = makeViewModel(conversationStore: store, mailboxDao: dao)

        // displayMessagesOverride 非 nil = 补绘重试轮：展示基线是显式快照，不消费。
        let drained = await viewModel.generationCoordinatorForTesting
            .drainMailboxAtNewRunHeadForTesting(
                conversationId: conversationId,
                displayMessagesOverride: [UIMessage.companion.assistant(prompt: "补绘基线")]
            )
        XCTAssertTrue(drained.isEmpty)
        XCTAssertEqual(
            viewModel.messages.filter { $0.role == MessageRole.user }.count,
            0,
            "补绘轮不得把信封折入展示/落盘谱系"
        )
        let pendingAfterRedraw = await pendingEnvelopeIds(dao, recipient: conversationId)
        XCTAssertEqual(
            pendingAfterRedraw,
            ["mb-draw"],
            "补绘轮后信封必须原样留在 Room"
        )
    }

    // MARK: - 5. 幂等：同一 run 重复边界不重复折入

    func testRepeatedBoundaryDoesNotDoubleFoldEnvelopes() async throws {
        let base = makeTempDirectory("MailboxIdempotent")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeConversationStore(directory: base)
        await store.newConversation()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let dao = makeMailboxDao(directory: base)
        try await enqueue(dao, envelope(id: "mb-i1", recipient: conversationId, payload: "幂等一", createdAt: 100))
        try await enqueue(dao, envelope(id: "mb-i2", recipient: conversationId, payload: "幂等二", createdAt: 200))
        let viewModel = makeViewModel(conversationStore: store, mailboxDao: dao)

        let baseMessages = [UIMessage.companion.assistant(prompt: "工具结果")]
        let firstRound = await viewModel.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: baseMessages
        )
        XCTAssertEqual(
            firstRound.filter { $0.role == MessageRole.user }.map { $0.toText() },
            [renderedText("幂等一"), renderedText("幂等二")]
        )

        // 同一 run 第二次边界（如后续工具轮）：drain 二次为空，不重复折入。
        let secondRound = await viewModel.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: baseMessages
        )
        XCTAssertEqual(
            secondRound.filter { $0.role == MessageRole.user }.count,
            0,
            "已投递信封不得再次折入"
        )
        XCTAssertEqual(
            viewModel.messages.filter { $0.role == MessageRole.user }.count,
            2,
            "内存 user 消息恰好 2 条（不因重复边界翻倍）"
        )
    }
}
