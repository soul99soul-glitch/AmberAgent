import XCTest
@testable import iosApp
@preconcurrency import Shared

@MainActor
final class ChatImageGenerationReferenceTests: XCTestCase {
    func testWantsAttachedImageParsesBooleanAliases() {
        XCTAssertTrue(ChatImageGenerationReference.wantsAttachedImage(
            #"{"prompt":"x","use_attached_image":true}"#
        ))
        XCTAssertTrue(ChatImageGenerationReference.wantsAttachedImage(
            #"{"prompt":"x","use_reference_image":true}"#
        ))
        XCTAssertTrue(ChatImageGenerationReference.wantsAttachedImage(
            #"{"prompt":"x","source_image_url":"attached"}"#
        ))
        XCTAssertFalse(ChatImageGenerationReference.wantsAttachedImage(
            #"{"prompt":"x"}"#
        ))
        XCTAssertFalse(ChatImageGenerationReference.wantsAttachedImage(
            #"{"prompt":"x","use_attached_image":false}"#
        ))
    }

    func testLatestUserAttachedImageURLSkipsAssistantAndOlderUsers() {
        let messages = [
            userMessage(text: "old", imageURL: "data:image/png;base64,OLD"),
            assistantText("ok"),
            userMessage(text: "turn this realistic", imageURL: "data:image/png;base64,NEW"),
            assistantToolCall(input: #"{"prompt":"realistic version","use_attached_image":true}"#)
        ]

        XCTAssertEqual(
            ChatImageGenerationReference.latestUserAttachedImageURL(in: messages),
            "data:image/png;base64,NEW"
        )
    }

    func testEnrichInjectsSourceImageURLFromLatestUserAttachment() throws {
        let messages = [
            userMessage(text: "漫画改写实", imageURL: "data:image/png;base64,QUJD"),
            assistantToolCall(input: #"{"prompt":"photorealistic remake","use_attached_image":true}"#)
        ]

        let enriched = try ChatImageGenerationReference.enrichToolInput(
            #"{"prompt":"photorealistic remake","use_attached_image":true}"#,
            messages: messages
        ).get()
        let object = try jsonObject(enriched)

        XCTAssertEqual(object["prompt"] as? String, "photorealistic remake")
        XCTAssertEqual(object["use_attached_image"] as? Bool, true)
        XCTAssertEqual(object["source_image_url"] as? String, "data:image/png;base64,QUJD")
    }

    func testEnrichKeepsExplicitSourceImageURL() throws {
        let messages = [
            userMessage(text: "ignore me", imageURL: "data:image/png;base64,USER"),
            assistantToolCall(input: #"{"prompt":"edit","source_image_url":"amber-image-generation://image-generation/a.png"}"#)
        ]

        let enriched = try ChatImageGenerationReference.enrichToolInput(
            #"{"prompt":"edit","source_image_url":"amber-image-generation://image-generation/a.png"}"#,
            messages: messages
        ).get()
        let object = try jsonObject(enriched)

        XCTAssertEqual(
            object["source_image_url"] as? String,
            "amber-image-generation://image-generation/a.png"
        )
    }

    func testEnrichFailsWhenAttachedImageRequestedButMissing() {
        let result = ChatImageGenerationReference.enrichToolInput(
            #"{"prompt":"photorealistic","use_attached_image":true}"#,
            messages: [userMessage(text: "no image")]
        )

        XCTAssertEqual(result, .failure(.attachedImageRequestedButMissing))
    }

    func testEnrichIsNoOpWithoutReferenceIntent() throws {
        let messages = [
            userMessage(text: "also has image", imageURL: "data:image/png;base64,QUJD")
        ]
        let input = #"{"prompt":"a cat","count":1}"#
        let enriched = try ChatImageGenerationReference.enrichToolInput(input, messages: messages).get()
        XCTAssertEqual(enriched, input)
    }

    func testImageGenerationRoutingPromptMentionsAttachedReference() {
        let prompt = ChatImageGenerationReference.routingGuidancePrompt
        XCTAssertTrue(prompt.contains("use_attached_image"))
        XCTAssertTrue(prompt.contains("generate_image"))
        XCTAssertTrue(
            prompt.localizedCaseInsensitiveContains("reference")
                || prompt.localizedCaseInsensitiveContains("attached")
        )
    }

    func testExecNestedWhitelistExcludesGenerateImage() {
        XCTAssertTrue(ChatToolRuntime.execNestedToolExclusions.contains("generate_image"))
        let whitelist = ChatToolRuntime.execNestedToolWhitelist(
            visibleToolNames: ["generate_image", "search_web", "exec"]
        )
        XCTAssertFalse(whitelist.contains("generate_image"))
        XCTAssertTrue(whitelist.contains("search_web"))
        XCTAssertFalse(whitelist.contains("exec"))
    }

    private func userMessage(text: String, imageURL: String? = nil) -> UIMessage {
        var parts: [UIMessagePart] = [UIMessagePart.Text(text: text, metadata: nil)]
        if let imageURL {
            parts.append(UIMessagePart.Image(url: imageURL, metadata: nil))
        }
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: parts,
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func assistantText(_ text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func assistantToolCall(input: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Tool(
                    toolCallId: "img-\(UUID().uuidString)",
                    toolName: "generate_image",
                    input: input,
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                )
            ],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func jsonObject(_ text: String) throws -> [String: Any] {
        let data = try XCTUnwrap(text.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}
