import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Branching parity tests for `IOSConversationStore`.
///
/// These verify the Android `ChatService` branching semantics that iOS now
/// mirrors (selectMessageNode / editMessage / regenerateAtMessage / deleteMessage):
/// the KMP `Conversation` tree carries `List<MessageNode>` where each node holds
/// sibling `UIMessage` variants + a `selectIndex`. The store mutates the tree
/// and persists; `currentMessages` (the flat projection) must reflect the
/// selected variant per node.
@MainActor
final class IOSConversationStoreBranchingTests: XCTestCase {

    private func makeStore() throws -> (IOSConversationStore, URL) {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationStoreBranchingTests-")
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        let store = IOSConversationStore(baseDirectory: baseDirectory)
        return (store, baseDirectory)
    }

    /// Seed a conversation with a [user, assistant] turn pair so branching
    /// operations have a node to act on.
    private func seedUserAssistantPair(_ store: IOSConversationStore) async {
        await store.newConversation()
        let messages = [
            UIMessage.companion.user(prompt: "what is 2+2"),
            UIMessage.companion.assistant(prompt: "4")
        ]
        await store.saveCurrent(messages: messages)
    }

    // MARK: - variantInfo

    func testVariantInfoReportsSingleVariantForFreshConversation() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        // Each node starts with exactly one variant.
        let userInfo = store.variantInfo(forMessageIndex: 0)
        XCTAssertEqual(userInfo?.variantCount, 1)
        XCTAssertEqual(userInfo?.selectedIndex, 0)
        XCTAssertFalse(userInfo?.hasMultipleVariants ?? true)
    }

    func testVariantInfoReturnsNilForOutOfRangeIndex() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        XCTAssertNil(store.variantInfo(forMessageIndex: -1))
        XCTAssertNil(store.variantInfo(forMessageIndex: 99))
    }

    // MARK: - appendVariant (edit / regenerate primitive)

    func testAppendVariantAddsSiblingAndSelectsIt() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        // Append a new assistant variant to the assistant node (index 1).
        let newNode = await store.appendVariant(
            messageIndex: 1,
            message: UIMessage.companion.assistant(prompt: "It is four.")
        )
        XCTAssertEqual(newNode, 1)

        // The node now has two variants, the second selected.
        let info = store.variantInfo(forMessageIndex: 1)
        XCTAssertEqual(info?.variantCount, 2)
        XCTAssertEqual(info?.selectedIndex, 1)
        XCTAssertTrue(info?.hasMultipleVariants ?? false)

        // The flat projection shows the newly-selected variant.
        XCTAssertEqual(store.currentMessages[1].toText(), "It is four.")
    }

    // MARK: - selectVariant

    func testSelectVariantSwitchesTheVisibleMessage() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        // Add a second assistant variant, then switch back to the first.
        _ = await store.appendVariant(
            messageIndex: 1,
            message: UIMessage.companion.assistant(prompt: "It is four.")
        )
        XCTAssertEqual(store.currentMessages[1].toText(), "It is four.")

        await store.selectVariant(messageIndex: 1, variantIndex: 0)
        XCTAssertEqual(store.variantInfo(forMessageIndex: 1)?.selectedIndex, 0)
        XCTAssertEqual(store.currentMessages[1].toText(), "4")
    }

    func testSelectVariantIgnoresInvalidVariantIndex() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        // Out-of-range and same-as-current must be no-ops.
        let before = store.currentMessages[1].toText()
        await store.selectVariant(messageIndex: 1, variantIndex: 99)
        await store.selectVariant(messageIndex: 1, variantIndex: 0)
        XCTAssertEqual(store.currentMessages[1].toText(), before)
    }

    // MARK: - truncateAfter (regenerate-from-USER primitive)

    func testTruncateAfterDropsNodesBeyondTheIndex() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        // Truncate at the user node (index 0): the assistant node is dropped.
        await store.truncateAfter(messageIndex: 0)
        XCTAssertEqual(store.currentMessages.count, 1)
        XCTAssertEqual(store.currentMessages[0].toText(), "what is 2+2")
    }

    func testTruncateAfterAtLastIndexIsNoOp() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        let beforeCount = store.currentMessages.count
        await store.truncateAfter(messageIndex: 1)
        XCTAssertEqual(store.currentMessages.count, beforeCount)
    }

    // MARK: - deleteMessage

    func testDeleteMessageRemovesTheNode() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        await store.deleteMessage(messageIndex: 1)
        XCTAssertEqual(store.currentMessages.count, 1)
        XCTAssertEqual(store.currentMessages[0].toText(), "what is 2+2")
    }

    // MARK: - persistence round-trip

    func testBranchSurvivesReloadFromDisk() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)

        // Append a variant so the assistant node has 2 siblings, select variant 0.
        _ = await store.appendVariant(
            messageIndex: 1,
            message: UIMessage.companion.assistant(prompt: "It is four.")
        )
        await store.selectVariant(messageIndex: 1, variantIndex: 0)
        let conversationId = try XCTUnwrap(store.currentConversation?.id)

        // Reopen the store from the same on-disk directory.
        let reopened = IOSConversationStore(baseDirectory: dir)
        await reopened.selectConversation(id: conversationId)

        // The tree (both variants) must round-trip; the selected variant (0)
        // determines the visible message.
        XCTAssertEqual(reopened.variantInfo(forMessageIndex: 1)?.variantCount, 2)
        XCTAssertEqual(reopened.variantInfo(forMessageIndex: 1)?.selectedIndex, 0)
        XCTAssertEqual(reopened.currentMessages[1].toText(), "4")
    }

    func testAssistantRegenerateAppendsVariantAndDropsStaleNodes() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await store.newConversation()
        let messages = [
            UIMessage.companion.user(prompt: "question"),
            UIMessage.companion.assistant(prompt: "old answer"),
            UIMessage.companion.user(prompt: "stale follow up")
        ]
        await store.saveCurrent(messages: messages)
        let conversationId = try XCTUnwrap(store.currentConversation?.id)

        let saved = await store.appendVariantAndTruncateAfter(
            messageIndex: 1,
            message: UIMessage.companion.assistant(prompt: "new answer"),
            conversationId: conversationId
        )

        XCTAssertTrue(saved)
        XCTAssertEqual(store.currentMessages.count, 2, "nodes after the regenerated assistant reply must be dropped")
        XCTAssertEqual(store.variantInfo(forMessageIndex: 1)?.variantCount, 2)
        XCTAssertEqual(store.variantInfo(forMessageIndex: 1)?.selectedIndex, 1)
        XCTAssertEqual(store.currentMessages[1].toText(), "new answer")

        await store.selectVariant(messageIndex: 1, variantIndex: 0)
        XCTAssertEqual(store.currentMessages[1].toText(), "old answer")

        let reopened = IOSConversationStore(baseDirectory: dir)
        await reopened.selectConversation(id: conversationId)
        XCTAssertEqual(reopened.currentMessages.count, 2)
        XCTAssertEqual(reopened.variantInfo(forMessageIndex: 1)?.variantCount, 2)
    }

    func testPendingAssistantRegenerateWithoutAssistantOutputDoesNotPersistPrefix() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        await seedUserAssistantPair(store)
        let conversationId = try XCTUnwrap(store.currentConversation?.id)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        viewModel.conversationStore = store
        viewModel.reloadFromStore()

        await viewModel.persistPendingAssistantRegenerationForTesting(
            conversationId: conversationId,
            targetMessageIndex: 1,
            generatedMessageIndex: 1,
            snapshot: [store.currentMessages[0]]
        )

        XCTAssertEqual(store.currentMessages.map { $0.toText() }, ["what is 2+2", "4"])
        XCTAssertEqual(viewModel.messages.map { $0.toText() }, ["what is 2+2", "4"])
    }
}
