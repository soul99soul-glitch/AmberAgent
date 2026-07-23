import XCTest
import WebKit
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class ChatViewModelSelectedFileContextTests: XCTestCase {
    func testSendWithoutPendingPreviewKeepsUserTextPlain() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertEqual(viewModel.messages.count, 1)
        XCTAssertEqual(textContent(of: viewModel.messages[0]), "Hello")
        XCTAssertNil(viewModel.pendingSelectedFilePreview)
    }

    func testSendMessageIsRejectedWhenBackgroundGenerationIsActiveForCurrentConversation() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.generationActiveOverrideForTesting = { _ in true }
        viewModel.inputText = "should not append while background generation is active"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.inputText, "should not append while background generation is active")
    }

    func testGenerateResponseIsRejectedWhenBackgroundGenerationIsActiveForCurrentConversation() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.generationActiveOverrideForTesting = { _ in true }

        viewModel.generateResponseForTesting(inputDigest: "digest", conversationId: nil)

        XCTAssertFalse(viewModel.isLoading)
        XCTAssertNil(viewModel.configurationError)
    }

    func testStaleCompactTerminalEventIsAppliedAfterRunFinishes() {
        XCTAssertTrue(ChatContextCompactEventRouter.shouldApply(
            event: .completed(summary: "上下文已压缩。"),
            eventRunId: "run-a",
            currentRunId: nil
        ))
        XCTAssertTrue(ChatContextCompactEventRouter.shouldApply(
            event: .idle,
            eventRunId: "run-a",
            currentRunId: nil
        ))
        XCTAssertFalse(ChatContextCompactEventRouter.shouldApply(
            event: .compacting,
            eventRunId: "run-a",
            currentRunId: nil
        ))
        XCTAssertFalse(ChatContextCompactEventRouter.shouldApply(
            event: .completed(summary: "旧任务完成"),
            eventRunId: "run-a",
            currentRunId: "run-b"
        ))
    }

    func testEditingUserMessagePreservesImageParts() {
        let original = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [
                UIMessagePart.Text(text: "old caption", metadata: nil),
                UIMessagePart.Image(url: "data:image/png;base64,AAA", metadata: nil)
            ],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )

        let edited = ChatViewModel.editedUserMessageForTesting(original: original, newText: "new caption")

        XCTAssertEqual(
            edited.parts.compactMap { ($0 as? UIMessagePart.Text)?.text },
            ["new caption"]
        )
        XCTAssertEqual(
            edited.parts.compactMap { ($0 as? UIMessagePart.Image)?.url },
            ["data:image/png;base64,AAA"]
        )
    }

    func testVisionRecognitionCacheKeyUsesDigestInsteadOfDataUrl() {
        let dataUrl = "data:image/png;base64," + String(repeating: "QUJD", count: 512)

        let key = ChatViewModel.visionRecognitionCacheKeyForTesting(dataUrl)

        XCTAssertEqual(key, ChatViewModel.visionRecognitionCacheKeyForTesting(dataUrl))
        XCTAssertNotEqual(key, dataUrl)
        XCTAssertFalse(key.contains("QUJD"))
        XCTAssertEqual(key.count, "vision:".count + 32)
    }

    func testBackgroundToolHandoffUploadMessagesInjectsRuntimeContextAndCoalescesSystem() {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: [])
        )
        harness.state.messagesByInjectingRuntimeContext = { messages in
            [UIMessage.companion.system(prompt: "runtime context")] + messages
        }
        let baseMessages = [
            UIMessage.companion.system(prompt: "base system"),
            UIMessage.companion.user(prompt: "需要工具")
        ]

        let prepared = harness.coordinator.backgroundToolHandoffUploadMessagesForTesting(baseMessages)
        let systemMessages = prepared.filter { $0.role == MessageRole.system }

        XCTAssertEqual(systemMessages.count, 1)
        XCTAssertTrue(systemMessages[0].toText().contains("runtime context"))
        XCTAssertTrue(systemMessages[0].toText().contains("base system"))
        XCTAssertEqual(prepared.filter { $0.role == MessageRole.user }.map { $0.toText() }, ["需要工具"])
    }

    func testBranchMutationsAreRejectedWhileVisionRecognitionIsPending() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatViewModelVisionRecognitionBranchGuard-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.newConversation()
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "question"),
            UIMessage.companion.assistant(prompt: "answer")
        ])

        let viewModel = ChatViewModel(settingsStore: SettingsStore(), autoGenerateResponses: false)
        viewModel.conversationStore = store
        viewModel.reloadFromStore()
        viewModel.isRecognizingImages = true

        viewModel.editMessage(atMessageIndex: 0, newText: "edited question")
        viewModel.regenerate(atMessageIndex: 0)
        viewModel.deleteMessage(atMessageIndex: 1)
        viewModel.selectVariant(messageIndex: 1, variantIndex: 0)
        try? await Task.sleep(nanoseconds: 100_000_000)

        XCTAssertEqual(store.currentMessages.map { $0.toText() }, ["question", "answer"])
        XCTAssertEqual(viewModel.messages.map { $0.toText() }, ["question", "answer"])
        XCTAssertEqual(viewModel.selectedFileContextError, "图片识别中，请稍候")
    }

    func testVisionRecognitionResultSurvivesUnrelatedRevisionBumpWhenUserMessageStillExists() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        let userMessage = UIMessage.companion.user(prompt: "image question")
        viewModel.messages = [userMessage]
        let userMessageId = ChatMessageProjector.messageId(for: userMessage)

        XCTAssertTrue(viewModel.shouldApplyVisionRecognitionResultForTesting(
            conversationId: nil,
            userMessageId: userMessageId
        ))

        viewModel.messageRevision &+= 1

        XCTAssertTrue(viewModel.shouldApplyVisionRecognitionResultForTesting(
            conversationId: nil,
            userMessageId: userMessageId
        ))
    }

    func testVisionRecognitionResultIsRejectedAfterUserMessageDisappears() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        let userMessage = UIMessage.companion.user(prompt: "image question")
        let userMessageId = ChatMessageProjector.messageId(for: userMessage)
        viewModel.messages = []

        XCTAssertFalse(viewModel.shouldApplyVisionRecognitionResultForTesting(
            conversationId: nil,
            userMessageId: userMessageId
        ))
    }

    func testVisionRecognitionFailureIsIgnoredAfterConversationChanges() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        let userMessage = UIMessage.companion.user(prompt: "image question")
        let userMessageId = ChatMessageProjector.messageId(for: userMessage)
        viewModel.messages = []

        viewModel.applyVisionRecognitionFailureForTesting(
            message: "OCR failed",
            conversationId: nil,
            userMessageId: userMessageId
        )

        XCTAssertNil(viewModel.selectedFileContextError)
    }

    func testVisionRecognitionFailureIsFlushedWhenOriginalConversationReturns() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatViewModelVisionFailureFlush-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let firstConversationId = try XCTUnwrap(store.currentConversation?.id)
        let userMessage = UIMessage.companion.user(prompt: "image question")
        await store.saveCurrent(messages: [userMessage])
        let userMessageId = ChatMessageProjector.messageId(for: userMessage)

        await store.newConversation()
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "other conversation")])

        let viewModel = ChatViewModel(settingsStore: SettingsStore(), autoGenerateResponses: false)
        viewModel.conversationStore = store
        viewModel.reloadFromStore()

        viewModel.applyVisionRecognitionFailureForTesting(
            message: "OCR failed",
            conversationId: firstConversationId,
            userMessageId: userMessageId
        )
        XCTAssertNil(viewModel.selectedFileContextError)

        await store.selectConversation(id: firstConversationId)
        viewModel.reloadFromStore()

        XCTAssertEqual(viewModel.selectedFileContextError, "OCR failed")
    }

    func testVisionRecognitionSuccessClearsTransientPendingPrompt() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        let userMessage = UIMessage.companion.user(prompt: "image question")
        viewModel.messages = [userMessage]
        viewModel.selectedFileContextError = "图片识别中，请稍候"

        viewModel.applyVisionRecognitionSuccessForTesting(
            conversationId: nil,
            userMessageId: ChatMessageProjector.messageId(for: userMessage)
        )

        XCTAssertNil(viewModel.selectedFileContextError)
    }

    func testModifyGeneratedImageIsRejectedWhileVisionRecognitionIsPending() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.isRecognizingImages = true

        viewModel.modifyGeneratedImage(
            sourceImageURL: "file:///tmp/source.png",
            prompt: "make it brighter",
            aspectRatio: "1:1"
        )

        XCTAssertEqual(viewModel.selectedFileContextError, "图片识别中，请稍候")
        XCTAssertFalse(viewModel.isLoading)
    }

    func testAttachSuccessAddsPreviewToNextMessageAndClearsIt() async throws {
        let documentStore = DocumentAccessStore()
        _ = documentStore.registerPickedFile(try makeTempFile(text: "Selected file body"))
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )

        await viewModel.attachSelectedFilePreviewToNextMessage()
        XCTAssertNotNil(viewModel.pendingSelectedFilePreview)

        viewModel.inputText = "Summarize this"
        viewModel.sendMessage()

        let content = try XCTUnwrap(viewModel.messages.first).parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
        XCTAssertTrue(content.contains("Summarize this"))
        XCTAssertTrue(content.contains("[文件上下文]"))
        XCTAssertTrue(content.contains("来源文件："))
        XCTAssertTrue(content.contains("状态：完整读取"))
        XCTAssertTrue(content.contains("Selected file body"))
        XCTAssertNil(viewModel.pendingSelectedFilePreview)
    }

    func testConversationChangeDiscardsSelectedFileContext() async throws {
        let documentStore = DocumentAccessStore()
        _ = documentStore.registerPickedFile(try makeTempFile(text: "Conversation-owned file"))
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )

        await viewModel.attachSelectedFilePreviewToNextMessage()
        XCTAssertNotNil(viewModel.pendingSelectedFilePreview)

        XCTAssertTrue(viewModel.prepareForConversationChange())
        XCTAssertNil(viewModel.pendingSelectedFilePreview)
        XCTAssertNil(viewModel.selectedFileContextError)
    }

    func testConversationChangeClearsInFlightSelectedFileState() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.isAttachingSelectedFile = true

        XCTAssertTrue(viewModel.prepareForConversationChange())
        XCTAssertFalse(viewModel.isAttachingSelectedFile)
    }

    func testDeletingCurrentConversationDiscardsSelectedFileContext() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatViewModelDeleteConversationContext-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let viewModel = ChatViewModel(settingsStore: SettingsStore(), autoGenerateResponses: false)
        viewModel.conversationStore = store
        viewModel.isAttachingSelectedFile = true
        viewModel.selectedFileContextError = "reading"

        viewModel.prepareForConversationDeletion(conversationId)

        XCTAssertFalse(viewModel.isAttachingSelectedFile)
        XCTAssertNil(viewModel.selectedFileContextError)
    }

    func testDeletingAnotherConversationKeepsCurrentSelectedFileContext() async throws {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatViewModelDeleteOtherConversationContext-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseDirectory) }

        let store = IOSConversationStore(baseDirectory: baseDirectory)
        await store.bootstrap()
        let viewModel = ChatViewModel(settingsStore: SettingsStore(), autoGenerateResponses: false)
        viewModel.conversationStore = store
        viewModel.isAttachingSelectedFile = true
        viewModel.selectedFileContextError = "reading"

        viewModel.prepareForConversationDeletion(KotlinUuid.companion.random())

        XCTAssertTrue(viewModel.isAttachingSelectedFile)
        XCTAssertEqual(viewModel.selectedFileContextError, "reading")
    }

    func testPendingPreviewIsNotAutomaticallyReused() async throws {
        let documentStore = DocumentAccessStore()
        _ = documentStore.registerPickedFile(try makeTempFile(text: "One shot"))
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )

        await viewModel.attachSelectedFilePreviewToNextMessage()
        viewModel.inputText = "First"
        viewModel.sendMessage()
        viewModel.inputText = "Second"
        viewModel.sendMessage()

        XCTAssertTrue(textContent(of: viewModel.messages[0]).contains("One shot"))
        XCTAssertFalse(textContent(of: viewModel.messages[1]).contains("One shot"))
    }

    func testAttachDeniedDoesNotModifyInputOrAppendMessages() async {
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore()
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )
        viewModel.inputText = "Keep this"

        await viewModel.attachSelectedFilePreviewToNextMessage()

        XCTAssertEqual(viewModel.inputText, "Keep this")
        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertNil(viewModel.pendingSelectedFilePreview)
        XCTAssertNotNil(viewModel.selectedFileContextError)
    }

    func testChatDoesNotCreateToolParts() async throws {
        let documentStore = DocumentAccessStore()
        _ = documentStore.registerPickedFile(try makeTempFile(text: "plain text"))
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )

        await viewModel.attachSelectedFilePreviewToNextMessage()
        viewModel.inputText = "Use context"
        viewModel.sendMessage()

        let hasToolPart = viewModel.messages.flatMap(\.parts).contains { $0 is UIMessagePart.Tool }
        XCTAssertFalse(hasToolPart)
    }

    func testPreparedUploadMessagesIncludeMemoryContextWithoutPersistingSystemMessage() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let memoryText = "用户喜欢把复杂任务拆成可验证的小闭环"
        IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.core,
            kind: MemoryKind.note,
            content: memoryText,
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
        )

        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.inputText = "帮我规划一下"
        viewModel.sendMessage()

        let uploadMessages = viewModel.preparedUploadMessagesForTesting(viewModel.messages)
        // The memory system message may no longer be `.first` now that the
        // assistant system prompt is also injected (Android parity). Find it by
        // content instead of position.
        let memoryMessage = try XCTUnwrap(uploadMessages.first { textContent(of: $0).contains(memoryText) })
        XCTAssertEqual(memoryMessage.role, MessageRole.system)
        XCTAssertTrue(uploadMessages.contains { $0.role == MessageRole.user })
        XCTAssertFalse(
            viewModel.messages.contains { $0.role == MessageRole.system },
            "memory context should be upload-only and must not pollute persisted chat history"
        )
    }

    func testPreparedUploadMessagesRespectDisabledMemoryScopes() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let coreText = "core memory should stay out"
        let longTermText = "long-term memory should remain"
        IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.core,
            kind: MemoryKind.note,
            content: coreText,
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
        )
        IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.longTerm,
            kind: MemoryKind.note,
            content: longTermText,
            assistantId: IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
        )

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.restoreSnapshot(
            IosSettingsMutations.shared.setMemoryRuntimeEnabled(
                settings: sharedSettings.snapshot,
                enableCoreMemory: false,
                enableShortTermMemory: false,
                enableLongTermMemory: true
            )
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        viewModel.inputText = "帮我规划一下"
        viewModel.sendMessage()

        let uploadMessages = viewModel.preparedUploadMessagesForTesting(viewModel.messages)
        // Locate the memory system message by content (it's no longer `.first`
        // after the assistant system-prompt injection was added).
        let memoryMessage = try XCTUnwrap(uploadMessages.first { textContent(of: $0).contains(longTermText) })
        let memoryText = textContent(of: memoryMessage)
        XCTAssertFalse(memoryText.contains(coreText))
        XCTAssertTrue(memoryText.contains(longTermText))
    }

    func testMemoryToolDeclarationFollowsRuntimeSwitches() {
        let enabledSettings = memorySettings(core: false, shortTerm: true, longTerm: false)
        let enabledViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: enabledSettings,
            autoGenerateResponses: false
        )
        XCTAssertTrue(Set(enabledViewModel.currentToolDeclarationNames()).contains("memory_tool"))

        let disabledSettings = memorySettings(core: false, shortTerm: false, longTerm: false)
        let disabledViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: disabledSettings,
            autoGenerateResponses: false
        )
        XCTAssertFalse(Set(disabledViewModel.currentToolDeclarationNames()).contains("memory_tool"))
    }

    func testMiniAppInstructionIsInjectedByDefault() throws {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        XCTAssertTrue(sharedSettings.isCapabilityGateEnabled(.miniApps))
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        viewModel.inputText = "帮我做一个 MiniApp 计时器"
        viewModel.sendMessage()

        let uploadMessages = viewModel.preparedUploadMessagesForTesting(viewModel.messages)
        let userText = textContent(of: try XCTUnwrap(uploadMessages.last { $0.role == MessageRole.user }))
        XCTAssertTrue(userText.contains("AmberAgent MiniApp V3"))
        XCTAssertTrue(userText.contains("Schema:"))
        XCTAssertTrue(userText.contains(#""permissions""#))
        XCTAssertTrue(userText.contains(#""html""#))
    }

    func testMemoryToolCreateEditDeletePersistsAndFeedsPrompt() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
            IOSMemoryPersistence.shared.persist()
        }

        let sharedSettings = memorySettings(core: true, shortTerm: true, longTerm: true)
        let createOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"create","scope":"long_term","kind":"user","content":"用户喜欢先做端到端验证","pinned":true,"confidence":0.8}"#,
            runtime: sharedSettings.agentRuntime,
            writePolicy: .allow
        )
        let createPayload = try jsonObject(createOutput)
        XCTAssertEqual(createPayload["ok"] as? Bool, true)

        var record = try XCTUnwrap(IosMemoryFactory.shared.getAllRecords().first)
        let id = Int(record.id)
        XCTAssertEqual(record.scope, MemoryScope.longTerm)
        XCTAssertEqual(record.kind, MemoryKind.user)
        XCTAssertTrue(record.pinned)
        XCTAssertEqual(record.confidence, 0.8, accuracy: 0.001)

        let editedText = "用户喜欢先做端到端验证，再提交小批量改动"
        let editOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"edit","id":\#(id),"scope":"short_term","kind":"project","content":"\#(editedText)","pinned":false}"#,
            runtime: sharedSettings.agentRuntime,
            writePolicy: .allow
        )
        let editPayload = try jsonObject(editOutput)
        XCTAssertEqual(editPayload["ok"] as? Bool, true)

        record = try XCTUnwrap(IosMemoryFactory.shared.getAllRecords().first { Int($0.id) == id })
        XCTAssertEqual(record.content, editedText)
        XCTAssertEqual(record.scope, MemoryScope.shortTerm)
        XCTAssertEqual(record.kind, MemoryKind.project)
        XCTAssertFalse(record.pinned)

        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        viewModel.inputText = "下一步怎么做"
        viewModel.sendMessage()

        let uploadMessages = viewModel.preparedUploadMessagesForTesting(viewModel.messages)
        // Locate the memory prompt by content (assistant system-prompt injection
        // means the memory message is no longer `.first`).
        let memoryPrompt = textContent(of: try XCTUnwrap(uploadMessages.first { textContent(of: $0).contains(editedText) }))
        XCTAssertTrue(memoryPrompt.contains(editedText))
        XCTAssertTrue(memoryPrompt.contains("[short_term/project]"))

        let deleteOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"delete","id":\#(id)}"#,
            runtime: sharedSettings.agentRuntime,
            writePolicy: .allow
        )
        let deletePayload = try jsonObject(deleteOutput)
        XCTAssertEqual(deletePayload["ok"] as? Bool, true)
        XCTAssertFalse(IosMemoryFactory.shared.getAllRecords().contains { Int($0.id) == id })
    }

    func testMemoryToolRejectsDisabledScope() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let sharedSettings = memorySettings(core: false, shortTerm: false, longTerm: true)
        let output = IOSMemoryToolExecutor.execute(
            input: #"{"action":"create","scope":"core","content":"disabled scope should not save"}"#,
            runtime: sharedSettings.agentRuntime,
            writePolicy: .allow
        )
        let payload = try jsonObject(output)

        XCTAssertEqual(payload["ok"] as? Bool, false)
        XCTAssertEqual(payload["scope"] as? String, "core")
        XCTAssertTrue(IosMemoryFactory.shared.getAllRecords().isEmpty)
    }

    func testMemoryToolWritePolicyBlocksMutationButAllowsList() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let sharedSettings = memorySettings(core: true, shortTerm: true, longTerm: true)
        IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.longTerm,
            kind: MemoryKind.note,
            content: "list should remain readable",
            assistantId: IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
        )

        let listOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"list","scope":"long_term"}"#,
            runtime: sharedSettings.agentRuntime,
            writePolicy: .needsUserAction("writes blocked")
        )
        let listPayload = try jsonObject(listOutput)
        XCTAssertEqual(listPayload["ok"] as? Bool, true)
        XCTAssertEqual(listPayload["count"] as? Int, 1)

        let createOutput = IOSMemoryToolExecutor.execute(
            input: #"{"action":"create","scope":"long_term","content":"should not save"}"#,
            runtime: sharedSettings.agentRuntime,
            writePolicy: .needsUserAction("Memory writes require foreground approval.")
        )
        let createPayload = try jsonObject(createOutput)
        XCTAssertEqual(createPayload["ok"] as? Bool, false)
        XCTAssertEqual(createPayload["needs_user_action"] as? Bool, true)
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().count, 1)
    }

    func testChatMemoryToolModelWriteRequiresForegroundApproval() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore()
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: memorySettings(core: true, shortTerm: true, longTerm: true),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )
        let input = #"{"action":"create","scope":"long_term","kind":"project","content":"model write should wait"}"#

        let request = try XCTUnwrap(viewModel.memoryApprovalRequestForTesting(input: input))
        XCTAssertEqual(request.action, "create")
        XCTAssertEqual(request.title, "保存记忆")
        XCTAssertEqual(request.scope, "long_term")
        XCTAssertEqual(request.kind, "project")
        XCTAssertEqual(request.contentPreview, "model write should wait")

        let output = viewModel.memoryToolOutputForTesting(input: input)
        let payload = try jsonObject(output)

        XCTAssertEqual(payload["ok"] as? Bool, false)
        XCTAssertEqual(payload["needs_user_action"] as? Bool, true)
        XCTAssertTrue(IosMemoryFactory.shared.getAllRecords().isEmpty)
    }

    func testMemoryToolApprovalAllowWritesAndDenyDoesNotWrite() throws {
        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore()
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: memorySettings(core: true, shortTerm: true, longTerm: true),
            localToolExecutor: executor,
            autoGenerateResponses: false
        )
        let input = #"{"action":"create","scope":"long_term","content":"approved memory write"}"#

        let deniedOutput = viewModel.memoryToolApprovalOutputForTesting(input: input, allow: false)
        let deniedPayload = try jsonObject(deniedOutput)
        XCTAssertEqual(deniedPayload["ok"] as? Bool, false)
        XCTAssertEqual(deniedPayload["denied"] as? Bool, true)
        XCTAssertEqual(deniedPayload["policy"] as? String, "user_denied")
        XCTAssertTrue(IosMemoryFactory.shared.getAllRecords().isEmpty)

        let allowedOutput = viewModel.memoryToolApprovalOutputForTesting(input: input, allow: true)
        let allowedPayload = try jsonObject(allowedOutput)
        XCTAssertEqual(allowedPayload["ok"] as? Bool, true)
        XCTAssertEqual(IosMemoryFactory.shared.getAllRecords().map(\.content), ["approved memory write"])
    }

    func testSearchToolApprovalRequestAndDenyOutput() async throws {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        sharedSettings.addSearchProvider(name: "Bing", serviceType: "bing_local")
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        let input = #"{"query":"swift concurrency","max_results":2}"#

        let request = try XCTUnwrap(viewModel.searchApprovalRequestForTesting(
            toolName: "search_web",
            input: input
        ))
        XCTAssertEqual(request.title, "执行网络搜索")
        XCTAssertEqual(request.target, "swift concurrency")
        XCTAssertEqual(request.providerName, "Bing HTML")
        XCTAssertEqual(request.providerType, "bing_local")

        let deniedOutput = await viewModel.searchToolApprovalOutputForTesting(
            toolName: "search_web",
            input: input,
            allow: false
        )
        let deniedPayload = try jsonObject(deniedOutput)
        XCTAssertEqual(deniedPayload["ok"] as? Bool, false)
        XCTAssertEqual(deniedPayload["tool"] as? String, "search_web")
        XCTAssertEqual(deniedPayload["denied"] as? Bool, true)
        XCTAssertEqual(deniedPayload["policy"] as? String, "user_denied")
    }

    func testSearchApprovalAllowExecutesWithMockTransport() async throws {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        sharedSettings.addSearchProvider(name: "Bing", serviceType: "bing_local")
        let transport = ChatSearchTransport(responses: [
            .html("""
            <html><body>
            <ol id="b_results">
              <li class="b_algo">
                <h2><a href="https://example.com/bing">Bing Result</a></h2>
                <p>Bing snippet from chat approval.</p>
              </li>
            </ol>
            </body></html>
            """)
        ])
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            searchTransport: transport,
            autoGenerateResponses: false
        )

        let output = await viewModel.searchToolApprovalOutputForTesting(
            toolName: "search_web",
            input: #"{"query":"amber agent","max_results":1}"#,
            allow: true
        )

        XCTAssertEqual(transport.requests.first?.url?.host, "www.bing.com")
        XCTAssertTrue(output.contains("来源：Bing HTML"))
        XCTAssertTrue(output.contains("Bing snippet from chat approval."))
    }

    func testSearchToolOutputReportsDisabledGateWithoutNetwork() async throws {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(false)
        let transport = ChatSearchTransport(responses: [])
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            searchTransport: transport,
            autoGenerateResponses: false
        )

        let output = await viewModel.searchToolApprovalOutputForTesting(
            toolName: "search_web",
            input: #"{"query":"amber agent"}"#,
            allow: true
        )
        let payload = try jsonObject(output)

        XCTAssertEqual(payload["ok"] as? Bool, false)
        XCTAssertEqual(payload["tool"] as? String, "search_web")
        XCTAssertTrue((payload["reason"] as? String)?.contains("disabled") == true)
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testWebMountClearSessionApprovalRequiresForegroundAndCanExecute() async throws {
        let defaults = isolatedDefaults()
        let registry = IOSWebMountRegistry(userDefaults: defaults)
        let settings = IOSWebMountSettings(userDefaults: defaults)
        settings.globalEnabled = true
        let cookieStore = ChatWebMountCookieStore()
        let controller = IOSWebMountController(
            registry: registry,
            settings: settings,
            cookieStore: cookieStore,
            runtime: ChatWebMountRuntime()
        )
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            webMountController: controller
        )
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setCapabilityGate(.webMount, enabled: true)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: executor,
            autoGenerateResponses: false
        )
        let input = #"{"site_id":"github"}"#

        let maybeRequest = await viewModel.webMountApprovalRequestForTesting(
            toolName: "wm_clear_session",
            input: input
        )
        let request = try XCTUnwrap(maybeRequest)
        XCTAssertEqual(request.toolName, "wm_clear_session")
        XCTAssertEqual(request.title, "清除 WebMount Session")
        XCTAssertEqual(request.siteId, "github")
        XCTAssertEqual(request.host, "github.com")

        let pendingOutput = await viewModel.webMountToolOutputForTesting(toolName: "wm_clear_session", input: input)
        let pendingPayload = try jsonObject(pendingOutput)
        XCTAssertEqual(pendingPayload["ok"] as? Bool, false)
        XCTAssertEqual(pendingPayload["needs_user_action"] as? Bool, true)
        XCTAssertTrue(cookieStore.clearedSiteIds.isEmpty)

        let deniedOutput = await viewModel.webMountToolApprovalOutputForTesting(
            toolName: "wm_clear_session",
            input: input,
            allow: false
        )
        let deniedPayload = try jsonObject(deniedOutput)
        XCTAssertEqual(deniedPayload["ok"] as? Bool, false)
        XCTAssertEqual(deniedPayload["denied"] as? Bool, true)
        XCTAssertEqual(deniedPayload["policy"] as? String, "user_denied")
        XCTAssertTrue(cookieStore.clearedSiteIds.isEmpty)

        let approvedOutput = await viewModel.webMountToolApprovalOutputForTesting(
            toolName: "wm_clear_session",
            input: input,
            allow: true
        )
        let approvedPayload = try jsonObject(approvedOutput)
        XCTAssertEqual(approvedPayload["ok"] as? Bool, true)
        XCTAssertEqual(approvedPayload["site_id"] as? String, "github")
        XCTAssertEqual(cookieStore.clearedSiteIds, ["github"])
    }

    func testWebMountOpenApprovalRequestAndTimelineRedactsInput() async throws {
        let defaults = isolatedDefaults()
        let registry = IOSWebMountRegistry(userDefaults: defaults)
        registry.setEnabled(id: "github", enabled: true)
        let controller = IOSWebMountController(
            registry: registry,
            settings: IOSWebMountSettings(userDefaults: defaults),
            runtime: ChatWebMountRuntime()
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
                documentStore: DocumentAccessStore(),
                webMountController: controller
            ),
            autoGenerateResponses: false
        )
        let input = #"{"site_id":"github","url":"https://github.com/login?token=secret"}"#

        let approvalRequest = await viewModel.webMountApprovalRequestForTesting(
            toolName: "wm_open",
            input: input
        )
        let request = try XCTUnwrap(approvalRequest)
        XCTAssertEqual(request.toolName, "wm_open")
        XCTAssertEqual(request.siteId, "github")
        XCTAssertEqual(request.host, "github.com")

        let pendingOutput = await viewModel.webMountToolOutputForTesting(toolName: "wm_open", input: input)
        let pendingPayload = try jsonObject(pendingOutput)
        XCTAssertEqual(pendingPayload["needs_user_action"] as? Bool, true)

        let toolCall = UIMessagePart.Tool(
            toolCallId: "wm-open",
            toolName: "wm_open",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let step = ChatToolStepModel(tool: toolCall)
        XCTAssertFalse(step.detail?.contains("token=secret") == true)
        XCTAssertTrue(step.detail?.contains("https://github.com/login") == true)
    }

    func testWebMountDirectToolExecutionHelperIsAvailableAfterUserAction() async throws {
        let defaults = isolatedDefaults()
        let settings = IOSWebMountSettings(userDefaults: defaults)
        settings.globalEnabled = false
        let cookieStore = ChatWebMountCookieStore()
        let controller = IOSWebMountController(
            registry: IOSWebMountRegistry(userDefaults: defaults),
            settings: settings,
            cookieStore: cookieStore,
            runtime: ChatWebMountRuntime()
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
                documentStore: DocumentAccessStore(),
                webMountController: controller
            ),
            autoGenerateResponses: false
        )

        let output = await viewModel.webMountToolOutputForTesting(
            toolName: "wm_clear_session",
            input: #"{"site_id":"github"}"#,
            isUserInitiated: true
        )
        let payload = try jsonObject(output)

        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(payload["site_id"] as? String, "github")
        XCTAssertEqual(cookieStore.clearedSiteIds, ["github"])
    }

    func testAdvancedToolDeclarationsFollowParityRules() throws {
        let alwaysOnToolNames = ["subagent_dispatch", "model_council_run"]

        let defaultViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
                documentStore: DocumentAccessStore()
            ),
            autoGenerateResponses: false
        )
        let defaultNames = Set(defaultViewModel.currentToolDeclarationNames())
        XCTAssertTrue(defaultNames.contains("mcp_call"), "mcp_call should be declared by default")
        for toolName in alwaysOnToolNames {
            XCTAssertTrue(defaultNames.contains(toolName), "\(toolName) should be declared by default")
        }
        XCTAssertTrue(defaultNames.contains { $0.hasPrefix("wm_") }, "WebMount tools should be declared by default")

        let webMountDefaults = isolatedDefaults()
        let webMountSettings = IOSWebMountSettings(userDefaults: webMountDefaults)
        let webMountExecutor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            webMountController: IOSWebMountController(
                registry: IOSWebMountRegistry(userDefaults: webMountDefaults),
                settings: webMountSettings,
                runtime: ChatWebMountRuntime()
            )
        )
        let webMountEnabledSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let webMountEnabledNames = Set(ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: webMountEnabledSettings,
            localToolExecutor: webMountExecutor,
            autoGenerateResponses: false
        ).currentToolDeclarationNames())
        XCTAssertTrue(webMountEnabledNames.contains { $0.hasPrefix("wm_") })

        let enabledViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false
        )
        let enabledNames = Set(enabledViewModel.currentToolDeclarationNames())
        XCTAssertTrue(enabledNames.contains("mcp_call"), "mcp_call should stay declared without a capability gate")
        for toolName in alwaysOnToolNames {
            XCTAssertTrue(enabledNames.contains(toolName), "\(toolName) should stay declared without a capability gate")
        }

        for toolName in ["mcp_call"] + alwaysOnToolNames {
            let toolCall = UIMessagePart.Tool(
                toolCallId: "call-\(toolName)",
                toolName: toolName,
                input: #"{"objective":"check resume"}"#,
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

            let resumed = enabledViewModel.finishedToolCallMessagesForTesting(
                toolCall,
                outputText: "tool result for \(toolName)",
                in: [UIMessage.companion.user(prompt: "run tool"), assistant]
            )
            let finishedTool = try XCTUnwrap(resumed.flatMap { $0.parts }.compactMap { $0 as? UIMessagePart.Tool }.first)
            let outputText = finishedTool.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()

            XCTAssertEqual(finishedTool.toolName, toolName)
            XCTAssertEqual(outputText, "tool result for \(toolName)")
        }
    }

    func testFinishedAdvancedToolCallMessagesMatchExactCallId() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false
        )
        let subAgentCall = UIMessagePart.Tool(
            toolCallId: "advanced-subagent",
            toolName: "subagent_dispatch",
            input: #"{"objective":"audit runtime","role_id":"explorer"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let councilCall = UIMessagePart.Tool(
            toolCallId: "advanced-council",
            toolName: "model_council_run",
            input: #"{"objective":"decide fallback","mode":"parallel"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [subAgentCall, councilCall],
            annotations: seed.annotations,
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )

        let resumed = viewModel.finishedToolCallMessagesForTesting(
            councilCall,
            outputText: "council conclusion",
            in: [UIMessage.companion.user(prompt: "run advanced tools"), assistant]
        )
        let tools = resumed.flatMap(\.parts).compactMap { $0 as? UIMessagePart.Tool }
        let subAgentOutput = try XCTUnwrap(tools.first { $0.toolCallId == "advanced-subagent" }).output
        let councilOutput = try XCTUnwrap(tools.first { $0.toolCallId == "advanced-council" }).output
        let councilText = councilOutput.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()

        XCTAssertTrue(subAgentOutput.isEmpty)
        XCTAssertEqual(councilText, "council conclusion")
    }

    func testFailingPendingToolCallsFillsAllUnfinishedOutputs() throws {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: [])
        )
        let searchCall = UIMessagePart.Tool(
            toolCallId: "pending-search",
            toolName: "search_web",
            input: #"{"query":"swift concurrency"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let imageCall = UIMessagePart.Tool(
            toolCallId: "pending-image",
            toolName: "generate_image",
            input: #"{"prompt":"amber icon"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [searchCall, imageCall],
            annotations: seed.annotations,
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )

        let failed = harness.coordinator.failingPendingToolCallMessagesForTesting(
            outputText: "tool loop exhausted",
            in: [UIMessage.companion.user(prompt: "run tools"), assistant]
        )
        let tools = failed.flatMap(\.parts).compactMap { $0 as? UIMessagePart.Tool }
        let searchOutput = try XCTUnwrap(tools.first { $0.toolCallId == "pending-search" }).output
        let imageOutput = try XCTUnwrap(tools.first { $0.toolCallId == "pending-image" }).output

        XCTAssertEqual(searchOutput.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(), "tool loop exhausted")
        XCTAssertEqual(imageOutput.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(), "tool loop exhausted")
    }

    func testFailingPendingToolCallsSeesSearchToolWhenSearchGateDisabled() throws {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: []),
            enableWebSearch: false
        )

        let failed = harness.coordinator.failingPendingToolCallMessagesForTesting(
            outputText: "tool disabled",
            in: harness.state.messages
        )
        let tool = try XCTUnwrap(failed.flatMap(\.parts).compactMap { $0 as? UIMessagePart.Tool }.first)
        let outputText = tool.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()

        XCTAssertEqual(outputText, "tool disabled")
    }

    func testPendingSearchApprovalKeepsCoordinatorActive() throws {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: [])
        )

        harness.coordinator.installPendingSearchApprovalForTesting(
            pending: harness.pending,
            request: harness.request
        )

        XCTAssertTrue(harness.coordinator.isRunning)
        XCTAssertFalse(harness.state.isLoading)
        XCTAssertNotNil(harness.state.pendingSearchApproval)
    }

    func testTerminalRevisionPublishesAfterCoordinatorBecomesInactive() async {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: [])
        )
        harness.coordinator.installPendingSearchApprovalForTesting(
            pending: harness.pending,
            request: harness.request
        )
        var wasRunningAtTerminal: Bool?
        harness.state.onBumpMessageRevision = { [weak coordinator = harness.coordinator] reason in
            guard reason == .generationCompleted else { return }
            wasRunningAtTerminal = coordinator?.isRunning
        }

        await harness.coordinator.denyPendingSearchTool()

        XCTAssertEqual(wasRunningAtTerminal, false)
        XCTAssertFalse(harness.coordinator.isRunning)
    }

    func testCancelledApprovedSearchDoesNotReplayStaleMessages() async throws {
        let transport = BlockingChatSearchTransport(response: .html("""
        <html><body>
        <a rel="nofollow" class='result-link' href="/l/?kh=-1&amp;uddg=https%3A%2F%2Fexample.com%2Fstale">Stale Result</a>
        <td class='result-snippet'>Should not be written after cancel.</td>
        </body></html>
        """))
        let harness = makeGenerationCoordinatorHarness(transport: transport)
        harness.coordinator.installPendingSearchApprovalForTesting(
            pending: harness.pending,
            request: harness.request
        )

        let approvalTask = Task { @MainActor in
            await harness.coordinator.approvePendingSearchTool()
        }
        await transport.waitUntilRequestStarted()
        XCTAssertTrue(harness.state.isLoading)

        harness.coordinator.cancel()
        transport.complete()
        await approvalTask.value

        let finishedTool = try XCTUnwrap(
            harness.state.messages.flatMap(\.parts).compactMap { $0 as? UIMessagePart.Tool }.first
        )
        XCTAssertTrue(finishedTool.output.isEmpty)
        XCTAssertFalse(harness.coordinator.isRunning)
        XCTAssertFalse(harness.state.isLoading)
        XCTAssertNil(harness.state.pendingSearchApproval)
    }

    func testCancelPersistsCancellationSnapshotInsteadOfLaterActiveMessages() async throws {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: [])
        )
        harness.coordinator.installPendingSearchApprovalForTesting(
            pending: harness.pending,
            request: harness.request
        )

        harness.state.onRecordRun = { [weak state = harness.state] in
            state?.messages = [
                UIMessage.companion.user(prompt: "different conversation after reload"),
            ]
        }

        harness.coordinator.cancel()
        try await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(
            harness.state.persistedSnapshots.last,
            ["search", ""],
            "取消收尾必须持久化 cancel 时刻的 run 快照,不能在延迟 Task 中回读当前活跃会话 messages"
        )
    }

    func testCancelCapturesWriteBaselineBeforeDelayedTerminalWork() async throws {
        let harness = makeGenerationCoordinatorHarness(
            transport: ChatSearchTransport(responses: [])
        )
        harness.coordinator.installPendingSearchApprovalForTesting(
            pending: harness.pending,
            request: harness.request
        )

        harness.coordinator.cancel()

        XCTAssertEqual(harness.state.persistenceEvents.first, "capture-baseline")
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(
            harness.state.persistenceEvents,
            ["capture-baseline", "record-run", "persist-snapshot"]
        )
    }

    private func textContent(of message: UIMessage) -> String {
        message.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
    }

    private func jsonObject(_ text: String) throws -> [String: Any] {
        let data = try XCTUnwrap(text.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func memorySettings(core: Bool, shortTerm: Bool, longTerm: Bool) -> IOSSharedSettingsStore {
        let store = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        store.restoreSnapshot(
            IosSettingsMutations.shared.setMemoryRuntimeEnabled(
                settings: store.snapshot,
                enableCoreMemory: core,
                enableShortTermMemory: shortTerm,
                enableLongTermMemory: longTerm
            )
        )
        return store
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

    private func makeGenerationCoordinatorHarness(
        transport: any IOSSearchHTTPTransport,
        enableWebSearch: Bool = true
    ) -> ChatGenerationCoordinatorHarness {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(enableWebSearch)
        let toolCall = UIMessagePart.Tool(
            toolCallId: "search-approval-test",
            toolName: "search_web",
            input: #"{"query":"swift concurrency","max_results":1}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [toolCall],
            annotations: seed.annotations,
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
        let messages = [UIMessage.companion.user(prompt: "search"), assistant]
        let state = ChatGenerationBindingState(messages: messages)
        let coordinator = ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: SettingsStore(),
                sharedSettings: sharedSettings,
                localToolExecutor: nil,
                searchTransport: transport,
                liveActivityController: .shared,
                autoGenerateResponses: false,
                mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
            ),
            bindings: state.bindings()
        )
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeOpenAIProviderSetting(),
            params: makeTextGenerationParams(),
            runId: "run-search-approval-test",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: messages
        )
        let request = SearchToolApprovalRequest(
            id: toolCall.toolCallId,
            toolName: toolCall.toolName,
            target: "swift concurrency",
            providerName: "DuckDuckGo Lite",
            providerType: "duckduckgo_builtin",
            reason: "Test approval"
        )
        return ChatGenerationCoordinatorHarness(
            coordinator: coordinator,
            state: state,
            pending: pending,
            request: request
        )
    }

    private func makeOpenAIProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
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
    }

    private func makeTextGenerationParams() -> TextGenerationParams {
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
        return TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: [],
            customBody: []
        )
    }
}

private struct ChatGenerationCoordinatorHarness {
    let coordinator: ChatGenerationCoordinator
    let state: ChatGenerationBindingState
    let pending: ChatPendingToolApproval
    let request: SearchToolApprovalRequest
}

@MainActor
private final class ChatGenerationBindingState {
    var messages: [UIMessage]
    var messageRevision = 0
    var isLoading = false
    var pendingSearchApproval: SearchToolApprovalRequest?
    var persistedCount = 0
    var persistedSnapshots: [[String]] = []
    var recordedRunStatuses: [String] = []
    var persistenceEvents: [String] = []
    var onRecordRun: (() -> Void)?
    var onBumpMessageRevision: ((ChatMessageUpdateReason) -> Void)?
    var messagesByInjectingRuntimeContext: ([UIMessage]) -> [UIMessage] = { $0 }

    init(messages: [UIMessage]) {
        self.messages = messages
    }

    func bindings() -> ChatGenerationBindings {
        ChatGenerationBindings(
            getMessages: { [weak self] in
                self?.messages ?? []
            },
            setMessages: { [weak self] messages in
                self?.messages = messages
            },
            bumpMessageRevision: { [weak self] reason in
                self?.messageRevision += 1
                self?.onBumpMessageRevision?(reason)
            },
            shouldPaceStreamPresentation: { true },
            setIsLoading: { [weak self] isLoading in
                self?.isLoading = isLoading
            },
            setPendingMemoryApproval: { _ in },
            setPendingSearchApproval: { [weak self] request in
                self?.pendingSearchApproval = request
            },
            setPendingWebMountApproval: { _ in },
            setPendingWorkspaceApproval: { _ in },
            setPendingIshHandoffApproval: { _ in },
            setPendingMcpApproval: { _ in },
            setPendingCouncilApproval: { _ in },
            setPendingAskUser: { _ in },
            setContextCompactState: { _ in },
            persistMessages: { [weak self] _ in
                self?.persistedCount += 1
            },
            capturePersistMessagesBaseline: { [weak self] _ in
                self?.persistenceEvents.append("capture-baseline")
                return nil
            },
            persistMessagesSnapshot: { [weak self] messages, _, _ in
                self?.persistedCount += 1
                self?.persistedSnapshots.append(messages.map { $0.toText() })
                self?.persistenceEvents.append("persist-snapshot")
            },
            recordRun: { [weak self] _, _, status, _, _ in
                await MainActor.run {
                    self?.recordedRunStatuses.append(status)
                    self?.persistenceEvents.append("record-run")
                    self?.onRecordRun?()
                }
            },
            startLiveActivity: { _, _, _ in },
            saveMiniAppIfPresent: { _, _ in nil },
            messagesByInjectingRuntimeContext: { [weak self] messages in
                self?.messagesByInjectingRuntimeContext(messages) ?? messages
            },
            userFacingGenerationError: { rawMessage, _ in rawMessage }
        )
    }
}

@MainActor
private final class ChatSearchTransport: IOSSearchHTTPTransport {
    struct Response {
        let status: Int
        let headers: [String: String]
        let body: Data

        static func html(_ body: String, status: Int = 200) -> Response {
            Response(
                status: status,
                headers: ["Content-Type": "text/html; charset=utf-8"],
                body: Data(body.utf8)
            )
        }
    }

    private var responses: [Response]
    private(set) var requests: [URLRequest] = []

    init(responses: [Response]) {
        self.responses = responses
    }

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        let response = responses.isEmpty ? .html("") : responses.removeFirst()
        let http = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: response.status,
            httpVersion: "HTTP/1.1",
            headerFields: response.headers
        )!
        return (http, response.body)
    }
}

@MainActor
private final class BlockingChatSearchTransport: IOSSearchHTTPTransport {
    private let response: ChatSearchTransport.Response
    private var didStartRequest = false
    private var requestStartedContinuation: CheckedContinuation<Void, Never>?
    private var responseContinuation: CheckedContinuation<(HTTPURLResponse, Data), Error>?
    private(set) var requests: [URLRequest] = []

    init(response: ChatSearchTransport.Response) {
        self.response = response
    }

    func waitUntilRequestStarted() async {
        guard !didStartRequest else { return }
        await withCheckedContinuation { continuation in
            requestStartedContinuation = continuation
        }
    }

    func complete() {
        guard let continuation = responseContinuation else { return }
        responseContinuation = nil
        let requestURL = requests.last?.url ?? URL(string: "https://example.com")!
        let http = HTTPURLResponse(
            url: requestURL,
            statusCode: response.status,
            httpVersion: "HTTP/1.1",
            headerFields: response.headers
        )!
        continuation.resume(returning: (http, response.body))
    }

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        didStartRequest = true
        requestStartedContinuation?.resume()
        requestStartedContinuation = nil
        return try await withCheckedThrowingContinuation { continuation in
            responseContinuation = continuation
        }
    }
}

@MainActor
private final class ChatWebMountCookieStore: IOSWebMountCookieStoreProtocol {
    var clearedSiteIds: [String] = []

    func summary(for site: IOSWebMountSite) async -> IOSWebMountCookieSummary {
        IOSWebMountCookieSummary(
            siteId: site.id,
            cookieCount: site.id == "github" ? 1 : 0,
            cookieNames: site.id == "github" ? ["user_session"] : [],
            domains: site.id == "github" ? ["github.com"] : [],
            hasLoginCookie: site.loginCookieName.map { $0 == "user_session" },
            redacted: true
        )
    }

    func clearSession(for site: IOSWebMountSite) async -> IOSWebMountCookieClearResult {
        clearedSiteIds.append(site.id)
        return IOSWebMountCookieClearResult(
            siteId: site.id,
            deletedCookieCount: 1,
            clearedWebsiteDataRecords: 1
        )
    }
}

@MainActor
private final class ChatWebMountRuntime: IOSWebMountRuntimeServicing {
    var snapshot = IOSWebMountRuntimeSnapshot.idle(sessionId: "chat-test")
    var webView: WKWebView? { nil }

    func open(_ url: URL, timeoutMillis: UInt64) async -> IOSWebMountRuntimeSnapshot {
        snapshot = IOSWebMountRuntimeSnapshot(
            sessionId: "chat-test",
            status: .ready,
            requestedURL: IOSWebMountRedactor.redactedURL(url.absoluteString),
            currentURL: IOSWebMountRedactor.redactedURL(url.absoluteString),
            title: "Chat Test",
            estimatedProgress: 1,
            canGoBack: false,
            canGoForward: false,
            error: nil,
            updatedAtMillis: 123
        )
        return snapshot
    }

    func state() async throws -> [String: Any] {
        ["title": "Chat Test"]
    }

    func extract(mode: String, maxChars: Int, maxLinks: Int) async throws -> [String: Any] {
        ["mode": mode, "text": "Chat Test"]
    }

    func get(
        selector: String?,
        target: String?,
        kind: String,
        attrName: String?,
        maxChars: Int
    ) async throws -> [String: Any] {
        ["ok": true, "selector": selector ?? target ?? "body", "kind": kind, "value": "Chat Test"]
    }

    func interact(method: String, selector: String?, text: String?, options: [String: Any]) async throws -> [String: Any] {
        ["ok": true, "method": method, "found": true, "message": "mock interaction"]
    }

    func screenshot() async throws -> IOSWebMountScreenshotCapture {
        IOSWebMountScreenshotCapture(data: Data([0x89, 0x50, 0x4E, 0x47]), width: 320, height: 640, format: "png")
    }

    func back() async -> IOSWebMountRuntimeSnapshot {
        snapshot
    }

    func forward() async -> IOSWebMountRuntimeSnapshot {
        snapshot
    }
}
