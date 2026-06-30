import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSConversationStoreTests: XCTestCase {

    func testSaveMessagesToExplicitConversationDoesNotOverwriteCurrentConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreTests-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.newConversation()
        let firstConversationId = try XCTUnwrap(store.currentConversation?.id)
        let firstMessages = [UIMessage.companion.user(prompt: "first conversation message")]
        await store.saveCurrent(messages: firstMessages)

        await store.newConversation()
        let secondConversationId = try XCTUnwrap(store.currentConversation?.id)

        let lateFirstMessages = [UIMessage.companion.user(prompt: "late first conversation update")]
        await store.save(messages: lateFirstMessages, to: firstConversationId)

        XCTAssertEqual(store.currentConversation?.id, secondConversationId)
        XCTAssertTrue(store.currentMessages.isEmpty)

        await store.selectConversation(id: firstConversationId)
        XCTAssertEqual(store.currentMessages.map { $0.toText() }, ["late first conversation update"])

        await store.selectConversation(id: secondConversationId)
        XCTAssertTrue(store.currentMessages.isEmpty)
    }

    /// 锁定 viewport 用来区分「真切换」与「同会话落盘」的修订号语义。
    /// 落盘/分支/重命名都不应 bump conversationSwitchedRevision,否则生成中的落盘会被
    /// ChatView 误当成切会话,重建 ScrollView → 抖动 + 上滑看历史被甩回锚点。
    func testConversationSwitchedRevisionOnlyBumpsOnRealSwitch() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreSwitchRevision-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()

        let baseline = store.conversationSwitchedRevision

        // 同会话落盘:不应 bump switchedRevision
        let convId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "first")])
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "同会话落盘不应 bump conversationSwitchedRevision")

        // 重命名:不应 bump
        await store.renameConversation(id: convId, title: "renamed")
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "重命名不应 bump conversationSwitchedRevision")

        // 真切换:newConversation 应 bump
        await store.newConversation()
        XCTAssertEqual(store.conversationSwitchedRevision, baseline + 1, "新建会话应 bump conversationSwitchedRevision")
        let afterNew = store.conversationSwitchedRevision

        // 切回旧会话:selectConversation 应 bump
        await store.selectConversation(id: convId)
        XCTAssertEqual(store.conversationSwitchedRevision, afterNew + 1, "selectConversation 应 bump conversationSwitchedRevision")

        // currentRevision 在所有这些操作中都应该增长(它是「内容可能变了」的粗信号)
        XCTAssertGreaterThan(store.currentRevision, baseline)
    }

    /// 回归防线:分支/编辑/删除消息/置顶都不得 bump conversationSwitchedRevision。
    /// 这些都是「同一会话内的内容变更」,若误 bump 会让生成中的编辑触发 ScrollView 重建 → 抖动。
    /// 同时验证 deleteConversation 删当前会话(真切换)必须 bump,删非当前会话不 bump。
    func testBranchAndDeleteOperationsDoNotBumpSwitchRevision() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreSwitchRevisionBranch-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        // bootstrap 在空 store 上必须 bump 一次(0 → 1),这是 UI 能感知首个会话的前提。
        await store.bootstrap()
        XCTAssertEqual(store.conversationSwitchedRevision, 1, "bootstrap 应把 conversationSwitchedRevision 从 0 bump 到 1")

        // 种入 [user, assistant] 一轮,作为分支操作的载体。
        await store.newConversation()  // 真切换 → bump
        let convId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "q"),
            UIMessage.companion.assistant(prompt: "a"),
        ])
        let baseline = store.conversationSwitchedRevision

        // 置顶:不应 bump
        await store.togglePin(id: convId)
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "togglePin 不应 bump conversationSwitchedRevision")

        // appendVariant(编辑/重生成原语):不应 bump
        _ = await store.appendVariant(messageIndex: 1, message: UIMessage.companion.assistant(prompt: "a2"))
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "appendVariant 不应 bump conversationSwitchedRevision")

        // selectVariant:不应 bump
        await store.selectVariant(messageIndex: 1, variantIndex: 1)
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "selectVariant 不应 bump conversationSwitchedRevision")

        // deleteMessage:不应 bump
        await store.deleteMessage(messageIndex: 1)
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "deleteMessage 不应 bump conversationSwitchedRevision")

        // truncateAfter:不应 bump
        await store.truncateAfter(messageIndex: 0)
        XCTAssertEqual(store.conversationSwitchedRevision, baseline, "truncateAfter 不应 bump conversationSwitchedRevision")

        // deleteConversation 删的是【当前】会话 → 回退到别的会话或新建,是真切换,必须 bump。
        await store.deleteConversation(id: convId)
        XCTAssertGreaterThan(
            store.conversationSwitchedRevision, baseline,
            "删除当前会话(回退到别的/新建)必须 bump conversationSwitchedRevision"
        )
    }

    func testSendMessagePersistsAcrossStoreRestart() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStorePhase2Acceptance-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let firstStore = IOSConversationStore(baseDirectory: baseDirectory)
        await firstStore.bootstrap()
        let firstConversationId = try XCTUnwrap(firstStore.currentConversation?.id)

        let firstViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        firstViewModel.conversationStore = firstStore
        firstViewModel.reloadFromStore()
        firstViewModel.inputText = "phase2 persistence acceptance"
        firstViewModel.sendMessage()

        let didPersistBeforeRestart = await waitFor {
            firstStore.currentMessages.map { $0.toText() } == ["phase2 persistence acceptance"]
        }
        XCTAssertTrue(didPersistBeforeRestart)

        let restartedStore = IOSConversationStore(baseDirectory: baseDirectory)
        await restartedStore.bootstrap()
        XCTAssertEqual(restartedStore.currentConversation?.id, firstConversationId)

        let restartedViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        restartedViewModel.conversationStore = restartedStore
        restartedViewModel.reloadFromStore()

        XCTAssertEqual(restartedViewModel.messages.map { $0.toText() }, ["phase2 persistence acceptance"])
    }

    func testSelectedFileContextPersistsAcrossStoreRestart() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreFileContext-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let firstStore = IOSConversationStore(baseDirectory: baseDirectory)
        await firstStore.bootstrap()
        let firstConversationId = try XCTUnwrap(firstStore.currentConversation?.id)

        let documentStore = DocumentAccessStore()
        _ = documentStore.registerPickedFile(try makeTempFile(text: "Persistent selected file body"))
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let firstViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )
        firstViewModel.conversationStore = firstStore
        firstViewModel.reloadFromStore()

        await firstViewModel.attachSelectedFilePreviewToNextMessage()
        firstViewModel.inputText = "Use this file"
        firstViewModel.sendMessage()

        let didPersistBeforeRestart = await waitFor {
            firstStore.currentMessages.first?.toText().contains("[文件上下文]") == true
        }
        XCTAssertTrue(didPersistBeforeRestart)

        let restartedStore = IOSConversationStore(baseDirectory: baseDirectory)
        await restartedStore.bootstrap()
        XCTAssertEqual(restartedStore.currentConversation?.id, firstConversationId)

        let restartedViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        restartedViewModel.conversationStore = restartedStore
        restartedViewModel.reloadFromStore()

        let text = try XCTUnwrap(restartedViewModel.messages.first?.toText())
        XCTAssertTrue(text.contains("Use this file"))
        XCTAssertTrue(text.contains("[文件上下文]"))
        XCTAssertTrue(text.contains("来源文件："))
        XCTAssertTrue(text.contains("Persistent selected file body"))
    }

    func testCurrentConversationCanBecomeDeepReadSourceAndReceiveResult() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreDeepRead-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "请深度阅读这个搜索结果"),
            UIMessage.companion.assistant(prompt: "可以，先整理来源和关键问题。")
        ])

        let source = try store.currentConversationDeepReadSource()
        XCTAssertEqual(source.kind, .conversation)
        XCTAssertTrue(source.content.contains("请深度阅读"))

        let deepReadStore = IOSDeepReadStore(baseDirectory: baseDirectory)
        let task = try deepReadStore.createTask(title: "会话深读", sources: [source])
        deepReadStore.complete(id: task.id, markdown: "# 会话深读\n\n结果")
        let saved = await store.appendDeepReadResultToCurrentConversation(try XCTUnwrap(deepReadStore.task(id: task.id)))

        XCTAssertTrue(saved)
        XCTAssertTrue(store.currentMessages.map { $0.toText() }.joined(separator: "\n").contains("已保存深度阅读结果"))
        XCTAssertTrue(store.currentMessages.map { $0.toText() }.joined(separator: "\n").contains("# 会话深读"))
    }

    private func waitFor(
        timeout: TimeInterval = 2,
        condition: @escaping @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return condition()
    }

    private func makeTempFile(text: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data(text.utf8).write(to: url)
        return url
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
