import XCTest
@testable import iosApp

@MainActor
final class CouncilSourceMaterialsTests: XCTestCase {

    func testDisplayObjectiveFallsBackWhenOnlyMaterials() {
        XCTAssertEqual(
            CouncilMaterialsComposer.displayObjective(userText: "  ", hasMaterials: true),
            CouncilMaterialsComposer.materialsOnlyObjective
        )
        XCTAssertEqual(
            CouncilMaterialsComposer.displayObjective(userText: "对比两份方案", hasMaterials: true),
            "对比两份方案"
        )
        XCTAssertEqual(
            CouncilMaterialsComposer.displayObjective(userText: "  ", hasMaterials: false),
            ""
        )
    }

    func testUserBubbleBodySummarizesMaterialsWithoutDumpingFullText() {
        let materials = CouncilResolvedMaterials(
            files: [
                CouncilPendingFile(
                    fileName: "brief.pdf",
                    fileType: "application/pdf",
                    preview: String(repeating: "长正文", count: 200),
                    characterCount: 800,
                    isTruncated: true,
                    statusSummary: "内容已截断",
                    totalBytes: 12_000
                ),
            ],
            imageContexts: [
                .init(displayName: "截图 1", text: "<image_context>\n白板内容\n</image_context>"),
            ]
        )
        let body = CouncilMaterialsComposer.userBubbleBody(
            userText: "帮我组织一场评审",
            materials: materials
        )
        XCTAssertTrue(body.contains("帮我组织一场评审"))
        XCTAssertTrue(body.contains("📎 材料："))
        XCTAssertTrue(body.contains("brief.pdf"))
        XCTAssertTrue(body.contains("截图 1"))
        XCTAssertFalse(body.contains("长正文长正文"), "Bubble must not dump full file preview.")
    }

    func testPromptBlockIncludesFileAndImageContextAndRespectsBudget() {
        let materials = CouncilResolvedMaterials(
            files: [
                CouncilPendingFile(
                    fileName: "notes.md",
                    fileType: "text/markdown",
                    preview: "第一章 背景\n第二章 目标",
                    characterCount: 20,
                    isTruncated: false,
                    statusSummary: "完整读取",
                    totalBytes: 40
                ),
            ],
            imageContexts: [
                .init(displayName: "白板", text: "识别出三条行动项"),
            ]
        )
        let block = materials.promptBlock()
        XCTAssertTrue(block.contains("[文件 1] notes.md"))
        XCTAssertTrue(block.contains("第一章 背景"))
        XCTAssertTrue(block.contains("[图片 1] 白板"))
        XCTAssertTrue(block.contains("识别出三条行动项"))

        let tiny = materials.promptBlock(budget: 40)
        XCTAssertTrue(tiny.contains("…（材料过长，已截断）") || tiny.count <= 60)
    }

    func testResearchObjectiveKeepsCompactMaterialHint() {
        let materials = CouncilResolvedMaterials(
            files: [
                CouncilPendingFile(
                    fileName: "market.pdf",
                    fileType: "application/pdf",
                    preview: String(repeating: "A", count: 500),
                    characterCount: 500,
                    isTruncated: true,
                    statusSummary: "内容已截断",
                    totalBytes: 9_000
                ),
            ],
            imageContexts: []
        )
        let query = CouncilMaterialsComposer.researchObjective(
            userText: "市场进入策略",
            materials: materials
        )
        XCTAssertTrue(query.contains("市场进入策略"))
        XCTAssertTrue(query.contains("market.pdf"))
        XCTAssertTrue(query.contains("材料摘录"))
        XCTAssertLessThan(query.count, 900, "Research query should stay compact for search APIs.")
    }

    func testFinalTopicPromptIncludesUploadedMaterialsSection() {
        // Source-level contract: host topic refinement must see uploaded materials.
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosRoot = testDirectory.deletingLastPathComponent()
        let runner = try! String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/CouncilRunner.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(runner.contains("var sourceMaterials: String? = nil"))
        XCTAssertTrue(runner.contains("var researchObjective: String? = nil"))
        XCTAssertTrue(runner.contains("用户上传材料（文件解析 / 图片视觉识别结果）"))
        XCTAssertTrue(runner.contains("若用户上传了材料，最终议题必须紧扣材料中的事实与争议点"))
        XCTAssertTrue(
            runner.contains("sourceMaterials: request.sourceMaterials"),
            "Seat plan must receive materials hint from the same request."
        )
    }

    func testCouncilComposerWiresAttachmentEntryAndSendPipeline() {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosRoot = testDirectory.deletingLastPathComponent()
        let runtime = try! String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )
        let composer = try! String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/ChatComposerViews.swift"),
            encoding: .utf8
        )
        let chat = try! String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/ChatView.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(runtime.contains("pendingFiles"))
        XCTAssertTrue(runtime.contains("pendingImages"))
        XCTAssertTrue(runtime.contains("attachPickedFile"))
        XCTAssertTrue(runtime.contains("visionRecognizer.recognize"))
        XCTAssertTrue(runtime.contains("sourceMaterials: sourceMaterials"))
        XCTAssertTrue(runtime.contains("researchObjective: researchObjective"))
        XCTAssertTrue(runtime.contains("canAttachMaterials"))
        // Design controls shared with standard Chat (not a private council fork).
        XCTAssertTrue(runtime.contains("ComposerAttachmentGlassPanel"))
        XCTAssertTrue(runtime.contains("ComposerAttachToggleButton"))
        XCTAssertTrue(runtime.contains("ComposerPendingImageStrip"))
        XCTAssertTrue(runtime.contains("ComposerPendingFileCard"))
        XCTAssertTrue(composer.contains("struct ComposerAttachmentGlassPanel"))
        XCTAssertTrue(chat.contains("ComposerAttachmentGlassPanel"))
        XCTAssertTrue(chat.contains("ComposerAttachToggleButton"))
        // Cancel / archive ownership gate (review C1/M1): generation token + isReplay guard.
        XCTAssertTrue(runtime.contains("materialsPrepGeneration"))
        XCTAssertTrue(runtime.contains("invalidateMaterialsPreparation"))
        XCTAssertTrue(runtime.contains("finishMaterialsPreparation(ifGeneration:"))
        XCTAssertTrue(runtime.contains("guard !isReplay, !text.isEmpty, !isRunning, activeDiscussionID == nil"))
        XCTAssertTrue(
            runtime.contains("invalidateMaterialsPreparation(showCancelledMessage: false)"),
            "openArchive / resetRoom must invalidate materials prep so late vision cannot start a run."
        )
    }
}
