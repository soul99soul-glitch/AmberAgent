import XCTest
import Shared
@testable import iosApp

final class ChatRowDigestTests: XCTestCase {

    private func makeRow(
        message: UIMessage,
        index: Int = 0,
        isLast: Bool = true,
        isStreaming: Bool = false,
        hasEverStreamed: Bool = false
    ) -> ChatMessageRowModel {
        let messageId = ChatMessageProjector.messageId(for: message)
        return ChatMessageRowModel(
            rowId: messageId,
            messageId: messageId,
            message: message,
            role: message.role,
            parts: message.parts,
            index: index,
            isLast: isLast,
            isStreaming: isStreaming,
            hasEverStreamed: hasEverStreamed,
            canAnimateInsertion: false
        )
    }

    private func makeRenderState(
        rendererMode: ChatRendererMode = .staticMarkdown,
        hasEverStreamed: Bool = false,
        liveRenderingEnabled: Bool = true,
        frozenMarkdownSnapshot: String? = nil
    ) -> ChatRenderState {
        ChatRenderState(
            rendererMode: rendererMode,
            hasEverStreamed: hasEverStreamed,
            liveRenderingEnabled: liveRenderingEnabled,
            frozenMarkdownSnapshot: frozenMarkdownSnapshot
        )
    }

    func testTextContentChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))
        let renderState = makeRenderState()

        let before = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let after = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 2,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(before.layout, after.layout)
    }

    func testGenerationActiveChangeOnLastRowChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"), isLast: true)
        let renderState = makeRenderState()

        let generating = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: true,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let notGenerating = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(generating.layout, notGenerating.layout)
    }

    func testGenerationActiveChangeOnNonLastRowChangesNeitherLayoutNorPresentation() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"), isLast: false)
        let renderState = makeRenderState()

        let generating = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: true,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let notGenerating = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertEqual(generating.layout, notGenerating.layout)
        XCTAssertEqual(generating.presentation, notGenerating.presentation)
    }

    func testRendererModeChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))

        let staticState = makeRenderState(rendererMode: .staticMarkdown)
        let streamingState = makeRenderState(rendererMode: .streamingMarkdown)

        let staticDigest = ChatRowDigests.digest(
            row: row,
            renderState: staticState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let streamingDigest = ChatRowDigests.digest(
            row: row,
            renderState: streamingState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(staticDigest.layout, streamingDigest.layout)
    }

    func testLiveRenderingEnabledChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))

        let liveOn = makeRenderState(liveRenderingEnabled: true)
        let liveOff = makeRenderState(liveRenderingEnabled: false)

        let onDigest = ChatRowDigests.digest(
            row: row,
            renderState: liveOn,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let offDigest = ChatRowDigests.digest(
            row: row,
            renderState: liveOff,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(onDigest.layout, offDigest.layout)
    }

    func testDisplaySettingSignatureChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))
        let renderState = makeRenderState()

        let before = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display-a",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let after = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display-b",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(before.layout, after.layout)
    }

    func testGenerativeUiSettingSignatureChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))
        let renderState = makeRenderState()

        let before = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative-a",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let after = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative-b",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(before.layout, after.layout)
    }

    /// 本次重构唯一有意的行为差异的护栏:row.index 只影响 presentation,不影响 layout。
    func testRowIndexChangeChangesPresentationButNotLayout() {
        let message = UIMessage.companion.assistant(prompt: "回复")
        let rowAtIndex0 = makeRow(message: message, index: 0)
        let rowAtIndex5 = makeRow(message: message, index: 5)
        let renderState = makeRenderState()

        let digestAtIndex0 = ChatRowDigests.digest(
            row: rowAtIndex0,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let digestAtIndex5 = ChatRowDigests.digest(
            row: rowAtIndex5,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertEqual(digestAtIndex0.layout, digestAtIndex5.layout)
        XCTAssertNotEqual(digestAtIndex0.presentation, digestAtIndex5.presentation)
    }

    func testSameInputsProduceEqualDigestsDeterministically() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"), index: 3)
        let renderState = makeRenderState()

        let first = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 42,
            isGenerationActive: true,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let second = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 42,
            isGenerationActive: true,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )

        XCTAssertEqual(first, second)
    }

    /// variantSwitcher 是整行内嵌控件:单变体 -> 多变体切换会让整行多出一条
    /// 控件,真实改变行高,必须归入 layout(否则 cell 不会 reconfigure,停在
    /// 旧高度上)。
    func testHasMultipleVariantsChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))
        let renderState = makeRenderState()

        let single = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: false,
            reasoningLevelLabel: nil
        )
        let multiple = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: 1,
            isGenerationActive: false,
            displaySettingSignature: "display",
            generativeUiSettingSignature: "generative",
            hasMultipleVariants: true,
            reasoningLevelLabel: nil
        )

        XCTAssertNotEqual(single.layout, multiple.layout)
    }

    /// reasoning 卡标题文案长度不同(nil -> "Auto" -> "High")会改变折行,进而
    /// 改变卡片/整行高度,必须归入 layout。
    func testReasoningLevelLabelChangeChangesLayout() {
        let row = makeRow(message: UIMessage.companion.assistant(prompt: "回复"))
        let renderState = makeRenderState()

        func digest(reasoningLevelLabel: String?) -> ChatRowDigest {
            ChatRowDigests.digest(
                row: row,
                renderState: renderState,
                contentHash: 1,
                isGenerationActive: false,
                displaySettingSignature: "display",
                generativeUiSettingSignature: "generative",
                hasMultipleVariants: false,
                reasoningLevelLabel: reasoningLevelLabel
            )
        }

        let noneDigest = digest(reasoningLevelLabel: nil)
        let autoDigest = digest(reasoningLevelLabel: "Auto")
        let highDigest = digest(reasoningLevelLabel: "High")

        XCTAssertNotEqual(noneDigest.layout, autoDigest.layout)
        XCTAssertNotEqual(autoDigest.layout, highDigest.layout)
    }
}
