import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSConversationStoreTests: XCTestCase {
    private func makeMessage(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026,
                month: 7,
                day: 9,
                hour: 0,
                minute: 0,
                second: 0,
                nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func assistantToolMessage(output: [UIMessagePart] = []) -> UIMessage {
        makeMessage(
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Tool(
                    toolCallId: "tool-1",
                    toolName: "web_search",
                    input: "{\"q\":\"amber\"}",
                    output: output,
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                )
            ]
        )
    }

    func testImportConversationDocumentsUsesStorageOwnedBatchImport() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreImport-\(UUID().uuidString)")
        let sourceDirectory = root.appendingPathComponent("source")
        let destinationDirectory = root.appendingPathComponent("destination")
        try FileManager.default.createDirectory(at: sourceDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let source = IOSConversationStore(baseDirectory: sourceDirectory)
        await source.bootstrap()
        await source.saveCurrent(messages: [UIMessage.companion.user(prompt: "restored message")])
        let sourceId = try XCTUnwrap(source.currentConversation?.id)
        let documentURL = sourceDirectory.appendingPathComponent("\(sourceId).json")
        let document = try String(contentsOf: documentURL, encoding: .utf8)

        let destination = IOSConversationStore(baseDirectory: destinationDirectory)
        await destination.bootstrap()
        let importedCount = try await destination.importConversationDocuments([document])

        XCTAssertEqual(importedCount, 1)
        XCTAssertTrue(destination.summaries.contains { $0.id == sourceId })
        await destination.selectConversation(id: sourceId)
        XCTAssertEqual(destination.currentMessages.map { $0.toText() }, ["restored message"])
    }

    func testStartNewConversationReusesCurrentEmptyConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreReuseCurrentEmpty-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let emptyConversationId = try XCTUnwrap(store.currentConversation?.id)
        let summaryCount = store.summaries.count

        await store.startNewConversationReusingEmpty()

        XCTAssertEqual(store.currentConversation?.id, emptyConversationId)
        XCTAssertEqual(store.summaries.count, summaryCount)
        XCTAssertTrue(store.currentMessages.isEmpty)
    }

    func testStartNewConversationReusesMostRecentEmptyConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreReuseRecentEmpty-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let populatedConversationId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "已开始的对话")])

        await store.newConversation()
        let emptyConversationId = try XCTUnwrap(store.currentConversation?.id)
        let summaryCount = store.summaries.count

        await store.selectConversation(id: populatedConversationId)
        XCTAssertEqual(store.currentConversation?.id, populatedConversationId)

        await store.startNewConversationReusingEmpty()

        XCTAssertEqual(store.currentConversation?.id, emptyConversationId)
        XCTAssertEqual(store.summaries.count, summaryCount)
        XCTAssertTrue(store.currentMessages.isEmpty)
    }

    func testSelectingMissingConversationFailsWithoutChangingCurrentConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreMissingSelection-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let originalId = try XCTUnwrap(store.currentConversation?.id)

        let didSelect = await store.selectConversationIfAvailable(id: KotlinUuid.companion.random())

        XCTAssertFalse(didSelect)
        XCTAssertEqual(store.currentConversation?.id, originalId)
    }

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

    /// P2.5 回归防线:后台生成/工具回填落盘必须 bump 定向的 backgroundContentRevision +
    /// 把目标会话 id 加入 pendingBackgroundContentConversationIds,但绝不能碰
    /// conversationSwitchedRevision(否则会复发"每次落盘重灌历史"的甩回老 bug)。
    /// 同时锁定普通前台落盘(saveCurrent/save)不得触碰这个定向信号。
    func testBackgroundCompletionBumpsDirectedSignalWithoutTouchingSwitchRevision() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreBackgroundCompletion-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)

        let baseMessages = [UIMessage.companion.user(prompt: "q")]
        await store.saveCurrent(messages: baseMessages)

        // 基线:先确认普通前台落盘(saveCurrent)不碰 backgroundContentRevision。
        XCTAssertEqual(store.backgroundContentRevision, 0, "普通前台落盘不应 bump backgroundContentRevision")
        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.isEmpty,
            "普通前台落盘不应往 pendingBackgroundContentConversationIds 塞任何 id"
        )

        let switchBaseline = store.conversationSwitchedRevision

        // 1) saveBackgroundCompletion 成功落盘后:backgroundContentRevision +1、
        //    pendingBackgroundContentConversationIds 含目标会话、conversationSwitchedRevision 不变。
        let completedMessages = baseMessages + [UIMessage.companion.assistant(prompt: "背景生成结果")]
        await store.saveBackgroundCompletion(
            baseMessages: baseMessages,
            completedMessages: completedMessages,
            to: convId
        )
        XCTAssertEqual(store.backgroundContentRevision, 1, "saveBackgroundCompletion 应 bump backgroundContentRevision")
        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.contains(String(describing: convId)),
            "saveBackgroundCompletion 应把目标会话加入 pendingBackgroundContentConversationIds"
        )
        XCTAssertEqual(
            store.conversationSwitchedRevision, switchBaseline,
            "saveBackgroundCompletion 不应 bump conversationSwitchedRevision"
        )
        XCTAssertEqual(store.currentMessages.map { $0.toText() }, ["q", "背景生成结果"])

        // 2) saveBackgroundToolCompletion 同上验证。
        let baseMessages2 = store.currentMessages
        let completedMessages2 = baseMessages2 + [UIMessage.companion.assistant(prompt: "工具回填结果")]
        await store.saveBackgroundToolCompletion(
            baseMessages: baseMessages2,
            completedMessages: completedMessages2,
            to: convId
        )
        XCTAssertEqual(store.backgroundContentRevision, 2, "saveBackgroundToolCompletion 应再次 bump backgroundContentRevision")
        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.contains(String(describing: convId)),
            "saveBackgroundToolCompletion 应把目标会话加入 pendingBackgroundContentConversationIds"
        )
        XCTAssertEqual(
            store.conversationSwitchedRevision, switchBaseline,
            "saveBackgroundToolCompletion 不应 bump conversationSwitchedRevision"
        )

        // 3) 再来一次普通前台落盘(save(messages:to:)):backgroundContentRevision 不应继续增长。
        await store.save(messages: store.currentMessages + [UIMessage.companion.user(prompt: "foreground edit")], to: convId)
        XCTAssertEqual(store.backgroundContentRevision, 2, "普通前台 save(messages:to:) 不应继续 bump backgroundContentRevision")
    }

    func testBackgroundToolCompletionPatchesExistingToolCallAndPreservesForegroundMessages() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreBackgroundToolParity-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)

        let user = UIMessage.companion.user(prompt: "查一下 amber")
        let pendingTool = assistantToolMessage()
        let baseMessages = [user, pendingTool]
        await store.saveCurrent(messages: baseMessages)

        let foreground = UIMessage.companion.user(prompt: "同时补一句前台消息")
        await store.saveCurrent(messages: baseMessages + [foreground])

        let completedTool = assistantToolMessage(output: [
            UIMessagePart.Text(text: "{\"result\":\"done\"}", metadata: nil)
        ])
        await store.saveBackgroundToolCompletion(
            baseMessages: baseMessages,
            completedMessages: [user, completedTool],
            to: convId
        )

        XCTAssertEqual(store.currentMessages.count, 3)
        XCTAssertEqual(store.currentMessages[0].toText(), "查一下 amber")
        XCTAssertEqual(store.currentMessages[2].toText(), "同时补一句前台消息")
        let tool = store.currentMessages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        let output = tool?.output.compactMap { $0 as? UIMessagePart.Text }.first?.text
        XCTAssertEqual(output, "{\"result\":\"done\"}")
        XCTAssertFalse(
            store.currentMessages.map { $0.toText() }.contains { $0.contains("后台工具执行已完成") },
            "能原地补齐 tool output 时不应追加 notice 气泡"
        )
    }

    func testBackgroundSaveReportsFailureAfterConversationWasDeleted() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreBackgroundSaveResult-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let deletedId = try XCTUnwrap(store.currentConversation?.id)
        let baseMessages = [UIMessage.companion.user(prompt: "base")]
        await store.saveCurrent(messages: baseMessages)
        await store.deleteConversation(id: deletedId)

        let completionSaved = await store.saveBackgroundCompletion(
            baseMessages: baseMessages,
            completedMessages: baseMessages + [UIMessage.companion.assistant(prompt: "late")],
            to: deletedId
        )
        let toolSaved = await store.saveBackgroundToolCompletion(
            baseMessages: baseMessages,
            completedMessages: baseMessages,
            to: deletedId
        )

        XCTAssertFalse(completionSaved)
        XCTAssertFalse(toolSaved)
    }

    func testDeleteFailureDoesNotCommitOwnerCleanupOrSwitchConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreDeleteFailure-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        store.beforeDeleteForTesting = {
            throw NSError(domain: "IOSConversationStoreTests", code: 1)
        }
        var didCommitOwnerCleanup = false

        let didDelete = await store.deleteConversation(id: conversationId) {
            didCommitOwnerCleanup = true
        }

        XCTAssertFalse(didDelete)
        XCTAssertFalse(didCommitOwnerCleanup)
        XCTAssertEqual(store.currentConversation?.id, conversationId)
        XCTAssertTrue(store.summaries.contains(where: { $0.id == conversationId }))
    }

    /// 观察合并丢通知防线:两会话在同一 tick 先后落盘时,单值信号会被后落盘的覆盖,
    /// 先落的那个会话丢失通知。集合语义天然免疫——两个 id 都应留在待通知集合里。
    func testPendingBackgroundContentConversationIdsSurvivesSameTickMultiConversationLanding() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStorePendingMerge-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let firstConvId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])

        await store.newConversation()
        let secondConvId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q2")])

        // 会话一先落盘后台完成。
        await store.saveBackgroundCompletion(
            baseMessages: [UIMessage.companion.user(prompt: "q1")],
            completedMessages: [UIMessage.companion.user(prompt: "q1"), UIMessage.companion.assistant(prompt: "a1")],
            to: firstConvId
        )
        // 会话二紧接着（同 tick）落盘后台完成。
        await store.saveBackgroundCompletion(
            baseMessages: [UIMessage.companion.user(prompt: "q2")],
            completedMessages: [UIMessage.companion.user(prompt: "q2"), UIMessage.companion.assistant(prompt: "a2")],
            to: secondConvId
        )

        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.contains(String(describing: firstConvId)),
            "先落盘的会话一不应被后落盘的会话二覆盖丢失"
        )
        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.contains(String(describing: secondConvId)),
            "后落盘的会话二也应保留在待通知集合里"
        )
    }

    /// consumeBackgroundContentNotification 只应移除指定会话的待通知状态,不得误清其他会话。
    func testConsumeBackgroundContentNotificationOnlyRemovesSpecifiedConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreConsumeSelective-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let firstConvId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])

        await store.newConversation()
        let secondConvId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q2")])

        await store.saveBackgroundCompletion(
            baseMessages: [UIMessage.companion.user(prompt: "q1")],
            completedMessages: [UIMessage.companion.user(prompt: "q1"), UIMessage.companion.assistant(prompt: "a1")],
            to: firstConvId
        )
        await store.saveBackgroundCompletion(
            baseMessages: [UIMessage.companion.user(prompt: "q2")],
            completedMessages: [UIMessage.companion.user(prompt: "q2"), UIMessage.companion.assistant(prompt: "a2")],
            to: secondConvId
        )

        store.consumeBackgroundContentNotification(for: String(describing: firstConvId))

        XCTAssertFalse(
            store.pendingBackgroundContentConversationIds.contains(String(describing: firstConvId)),
            "consume 应移除指定会话的待通知状态"
        )
        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.contains(String(describing: secondConvId)),
            "consume 不应误清其他会话的待通知状态"
        )
    }

    /// 负向锁:普通前台落盘(saveCurrent/save)绝不能往 pendingBackgroundContentConversationIds 塞 id,
    /// 否则会误触发 ChatView 的后台内容上屏路径。
    func testForegroundSaveDoesNotPopulatePendingBackgroundContentConversationIds() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreForegroundNoPending-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)

        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q")])
        await store.save(messages: [UIMessage.companion.user(prompt: "q"), UIMessage.companion.assistant(prompt: "a")], to: convId)

        XCTAssertTrue(
            store.pendingBackgroundContentConversationIds.isEmpty,
            "普通前台落盘不应往 pendingBackgroundContentConversationIds 塞任何 id"
        )
    }

    func testDeleteTombstonePreventsInFlightForegroundSaveFromResurrectingConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreDeleteTombstoneForeground-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let deletedConversationId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "will be deleted")])

        await store.newConversation()
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "still alive")])

        let reachedPersist = expectation(description: "foreground save reached persist")
        var allowPersist: CheckedContinuation<Void, Never>?
        store.beforePersistForTesting = { conversation in
            guard String(describing: conversation.id) == String(describing: deletedConversationId) else { return }
            reachedPersist.fulfill()
            await withCheckedContinuation { continuation in
                allowPersist = continuation
            }
        }

        let saveTask = Task { @MainActor in
            await store.save(
                messages: [
                    UIMessage.companion.user(prompt: "will be deleted"),
                    UIMessage.companion.assistant(prompt: "late foreground write"),
                ],
                to: deletedConversationId
            )
        }
        await fulfillment(of: [reachedPersist], timeout: 2)
        await store.deleteConversation(id: deletedConversationId)
        store.beforePersistForTesting = nil
        allowPersist?.resume()
        let didSave = await saveTask.value
        XCTAssertFalse(didSave, "删除墓碑命中后，挂起中的前台 save 应报告未落盘")

        let restarted = IOSConversationStore(baseDirectory: baseDirectory)
        await restarted.bootstrap()
        XCTAssertFalse(
            restarted.summaries.contains { String(describing: $0.id) == String(describing: deletedConversationId) },
            "删除墓碑应阻止已进入 persist 前窗口的前台 save 复活会话文件"
        )
    }

    func testInFlightSaveDoesNotOverwriteConcurrentRenameTitle() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreRenameRace-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "old title seed")])

        let reachedPersist = expectation(description: "foreground save captured stale metadata")
        var allowPersist: CheckedContinuation<Void, Never>?
        store.beforePersistForTesting = { conversation in
            guard String(describing: conversation.id) == String(describing: convId) else { return }
            reachedPersist.fulfill()
            await withCheckedContinuation { continuation in
                allowPersist = continuation
            }
        }

        let saveTask = Task { @MainActor in
            await store.save(
                messages: [
                    UIMessage.companion.user(prompt: "old title seed"),
                    UIMessage.companion.assistant(prompt: "late answer"),
                ],
                to: convId
            )
        }
        await fulfillment(of: [reachedPersist], timeout: 2)

        await store.renameConversation(id: convId, title: "renamed while saving")
        store.beforePersistForTesting = nil
        allowPersist?.resume()
        let didSave = await saveTask.value
        XCTAssertTrue(didSave)

        let restarted = IOSConversationStore(baseDirectory: baseDirectory)
        await restarted.bootstrap()
        XCTAssertEqual(
            restarted.summaries.first(where: { String(describing: $0.id) == String(describing: convId) })?.title,
            "renamed while saving",
            "已捕获旧 metadata 的复合 save 不应把并发 rename 的新标题反向覆盖回旧值"
        )
    }

    func testDeleteTombstonePreventsInFlightBackgroundCompletionFromResurrectingConversation() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreDeleteTombstoneBackground-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let deletedConversationId = try XCTUnwrap(store.currentConversation?.id)
        let baseMessages = [UIMessage.companion.user(prompt: "background base")]
        await store.saveCurrent(messages: baseMessages)

        await store.newConversation()
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "still alive")])

        let reachedPersist = expectation(description: "background completion reached persist")
        var allowPersist: CheckedContinuation<Void, Never>?
        store.beforePersistForTesting = { conversation in
            guard String(describing: conversation.id) == String(describing: deletedConversationId) else { return }
            reachedPersist.fulfill()
            await withCheckedContinuation { continuation in
                allowPersist = continuation
            }
        }

        let completionTask = Task { @MainActor in
            await store.saveBackgroundCompletion(
                baseMessages: baseMessages,
                completedMessages: baseMessages + [UIMessage.companion.assistant(prompt: "late background write")],
                to: deletedConversationId
            )
        }
        await fulfillment(of: [reachedPersist], timeout: 2)
        await store.deleteConversation(id: deletedConversationId)
        store.beforePersistForTesting = nil
        allowPersist?.resume()
        await completionTask.value

        let restarted = IOSConversationStore(baseDirectory: baseDirectory)
        await restarted.bootstrap()
        XCTAssertFalse(
            restarted.summaries.contains { String(describing: $0.id) == String(describing: deletedConversationId) },
            "删除墓碑应阻止已进入 persist 前窗口的后台 completion 复活会话文件"
        )
    }

    func testStaleSnapshotWriteBaselineDoesNotOverwriteNewerForegroundSave() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreWriteBaselineStaleSnapshot-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)
        let baseMessages = [UIMessage.companion.user(prompt: "cancel snapshot")]
        await store.saveCurrent(messages: baseMessages)

        let staleBaseline = store.writeBaseline(for: convId)
        let newerMessages = baseMessages + [UIMessage.companion.user(prompt: "new foreground message")]
        await store.save(messages: newerMessages, to: convId)

        let didSave = await store.save(
            messages: baseMessages + [UIMessage.companion.assistant(prompt: "late cancelled snapshot")],
            to: convId,
            ifUnchangedSince: staleBaseline
        )

        XCTAssertFalse(didSave, "序列号推进后，延迟取消快照不应覆盖同会话新消息")
        XCTAssertEqual(
            store.currentMessages.map { $0.toText() },
            ["cancel snapshot", "new foreground message"]
        )
        XCTAssertNil(store.lastIOError, "预期内的 stale snapshot skip 不应弹保存错误")
    }

    func testBackgroundCompletionWriteBaselineDoesNotOverwriteForegroundMessage() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreWriteBaselineBackground-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)
        let baseMessages = [UIMessage.companion.user(prompt: "background base")]
        await store.saveCurrent(messages: baseMessages)

        let reachedPersist = expectation(description: "background completion reached persist")
        var allowPersist: CheckedContinuation<Void, Never>?
        store.beforePersistForTesting = { conversation in
            guard String(describing: conversation.id) == String(describing: convId) else { return }
            reachedPersist.fulfill()
            await withCheckedContinuation { continuation in
                allowPersist = continuation
            }
        }

        let completionTask = Task { @MainActor in
            await store.saveBackgroundCompletion(
                baseMessages: baseMessages,
                completedMessages: baseMessages + [UIMessage.companion.assistant(prompt: "late background result")],
                to: convId
            )
        }
        await fulfillment(of: [reachedPersist], timeout: 2)

        store.beforePersistForTesting = nil
        let foregroundMessages = baseMessages + [UIMessage.companion.user(prompt: "new foreground message")]
        await store.save(messages: foregroundMessages, to: convId)
        allowPersist?.resume()
        await completionTask.value

        XCTAssertEqual(
            store.currentMessages.map { $0.toText() },
            ["background base", "new foreground message"],
            "后台完成已进入延迟窗口后，前台新消息落盘应推进序列号并阻止旧后台快照覆盖"
        )
        XCTAssertFalse(
            store.pendingBackgroundContentConversationIds.contains(String(describing: convId)),
            "被序列号拒绝的后台旧快照不应产生后台内容通知"
        )
    }

    func testBackgroundCompletionRetriesAfterForegroundWriteAndKeepsResult() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreBackgroundRetryAfterForeground-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let convId = try XCTUnwrap(store.currentConversation?.id)
        let baseMessages = [UIMessage.companion.user(prompt: "background base")]
        await store.saveCurrent(messages: baseMessages)

        let reachedFirstPersist = expectation(description: "background completion reached first stale persist")
        var allowPersist: CheckedContinuation<Void, Never>?
        var didBlockFirstAttempt = false
        store.beforePersistForTesting = { conversation in
            guard String(describing: conversation.id) == String(describing: convId),
                  !didBlockFirstAttempt else { return }
            didBlockFirstAttempt = true
            reachedFirstPersist.fulfill()
            await withCheckedContinuation { continuation in
                allowPersist = continuation
            }
        }

        let completionTask = Task { @MainActor in
            await store.saveBackgroundCompletion(
                baseMessages: baseMessages,
                completedMessages: baseMessages + [UIMessage.companion.assistant(prompt: "late background result")],
                to: convId
            )
        }
        await fulfillment(of: [reachedFirstPersist], timeout: 2)

        store.beforePersistForTesting = nil
        await store.save(messages: baseMessages + [UIMessage.companion.user(prompt: "new foreground message")], to: convId)
        allowPersist?.resume()
        await completionTask.value

        XCTAssertEqual(
            store.currentMessages.map { $0.toText() },
            [
                "background base",
                "new foreground message",
                "后台生成已完成；当前会话期间已有新内容，以下是后台完成的结果。",
                "late background result",
            ],
            "后台回复的旧 baseline 被拒后应以新 baseline 重试并合并为 notice，而不是凭空消失"
        )
        XCTAssertTrue(store.pendingBackgroundContentConversationIds.contains(String(describing: convId)))
        XCTAssertNil(store.lastIOError)
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
