import XCTest
import Shared
@testable import iosApp

final class ChatMessageProjectionTests: XCTestCase {

    func testInitialLoadRowsDoNotAnimateInsertion() {
        let rows = ChatMessageProjector.rows(
            messages: [
                UIMessage.companion.user(prompt: "你好"),
                UIMessage.companion.assistant(prompt: "在。")
            ],
            event: .conversationLoaded
        )

        XCTAssertEqual(rows.count, 2)
        XCTAssertFalse(rows[0].canAnimateInsertion)
        XCTAssertFalse(rows[1].canAnimateInsertion)
    }

    func testOnlyLastUserAppendCanAnimateInsertion() {
        let user = UIMessage.companion.user(prompt: "新的问题")
        let rows = ChatMessageProjector.rows(
            messages: [user],
            event: .userMessageAppended
        )

        XCTAssertEqual(rows.first?.messageId, ChatMessageProjector.messageId(for: user))
        XCTAssertTrue(rows.first?.canAnimateInsertion ?? false)
    }

    func testBranchChangeDoesNotAnimateUserRows() {
        let rows = ChatMessageProjector.rows(
            messages: [UIMessage.companion.user(prompt: "切分支后的用户消息")],
            event: .branchChanged
        )

        XCTAssertFalse(rows.first?.canAnimateInsertion ?? true)
    }

    func testStreamingFinalReplaceKeepsRowIdentityAndStreamedMemory() {
        let streamed = UIMessage.companion.assistant(prompt: "正在生成")
        let messageId = ChatMessageProjector.messageId(for: streamed)

        let streamingRows = ChatMessageProjector.rows(
            messages: [streamed],
            event: .assistantStreamDelta
        )
        let finalRows = ChatMessageProjector.rows(
            messages: [streamed],
            event: .generationCompleted,
            streamedMessageIDs: [messageId]
        )

        XCTAssertEqual(streamingRows.first?.rowId, finalRows.first?.rowId)
        XCTAssertTrue(streamingRows.first?.isStreaming ?? false)
        XCTAssertTrue(finalRows.first?.hasEverStreamed ?? false)
    }
}
