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
