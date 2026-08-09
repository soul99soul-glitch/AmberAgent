import XCTest
@testable import iosApp

final class ChatViewportPolicyTests: XCTestCase {

    func testSwiftUIStreamingTailSuspendsOnlyAfterTailIsConfirmedOffscreen() {
        let messageID = "tail"

        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init()
            ),
            "未知可见性必须保守保持 live，不能把可能已在屏内的长尾行降级。"
        )
        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: true)
            )
        )
        XCTAssertTrue(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: false)
            ),
            "曾流式的尾行确认离屏后保持冻结，tool/terminal 不能唤醒全文渲染。"
        )
        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: false,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: false)
            ),
            "从未流式过的普通尾行不能进入流式 LOD。"
        )
        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: false,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: false)
            ),
            "新消息追加后，旧 assistant 不能继续沿用过期的尾行可见性冻结。"
        )
    }

    func testLegacyStreamFinishMapsToStreamClosedEvent() {
        XCTAssertEqual(ChatMessageUpdateReason.streamFinish.event, .assistantStreamClosed)
    }

    func testAssistantStreamClosedIsNotTerminalGenerationComplete() {
        XCTAssertEqual(ChatEvent.assistantStreamClosed.contract.payload, .stream)
        XCTAssertEqual(ChatEvent.generationCompleted.contract.payload, .generation)
        XCTAssertTrue(ChatEvent.assistantStreamClosed.contract.affectsViewport)
        XCTAssertTrue(ChatEvent.generationCompleted.contract.affectsViewport)
    }
}
