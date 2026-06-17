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
}
