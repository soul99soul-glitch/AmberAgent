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
}
