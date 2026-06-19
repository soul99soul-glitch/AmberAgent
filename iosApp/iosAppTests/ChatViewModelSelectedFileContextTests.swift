import XCTest
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
        XCTAssertTrue(content.contains("[Selected file preview:"))
        XCTAssertTrue(content.contains("Selected file body"))
        XCTAssertNil(viewModel.pendingSelectedFilePreview)
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
        let memoryMessage = try XCTUnwrap(uploadMessages.first)
        XCTAssertEqual(memoryMessage.role, MessageRole.system)
        XCTAssertTrue(textContent(of: memoryMessage).contains(memoryText))
        XCTAssertEqual(uploadMessages.dropFirst().first?.role, MessageRole.user)
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
        let memoryText = textContent(of: try XCTUnwrap(uploadMessages.first))
        XCTAssertFalse(memoryText.contains(coreText))
        XCTAssertTrue(memoryText.contains(longTermText))
    }

    func testAgentCouncilAndMcpToolCallsCanBeFinishedForResume() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        let declaredNames = Set(viewModel.currentToolDeclarationNames())
        let resumeToolNames = ["mcp_call", "subagent_dispatch", "model_council_run"]

        for toolName in resumeToolNames {
            XCTAssertTrue(declaredNames.contains(toolName), "\(toolName) must be declared for model tool calls")

            let toolCall = UIMessagePart.Tool(
                toolCallId: "call-\(toolName)",
                toolName: toolName,
                input: #"{"objective":"check resume"}"#,
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
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

            let resumed = viewModel.finishedToolCallMessagesForTesting(
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

    private func textContent(of message: UIMessage) -> String {
        message.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
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
