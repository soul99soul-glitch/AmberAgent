import XCTest
import SwiftUI
import Shared
@testable import iosApp

/// WebMount 工具胶囊标题不得把聊天列撑出左右对称裁切。
@MainActor
final class ChatToolTimelineWidthOverflowTests: XCTestCase {

    private let screenWidth: CGFloat = 393
    private let columnWidth: CGFloat = 393 - ChatLayout.contentHorizontalInset * 2

    func testWebMountTitlePrefersHumanLabelOverRawJSON() {
        let input = """
        {"display_name":"GitHub","homepage_url":"https://github.com/openai/codex","site_id":"user_github"}
        """
        let tool = UIMessagePart.Tool(
            toolCallId: "call_wm_site_add",
            toolName: "wm_site_add",
            input: input,
            output: [UIMessagePart.Text(text: #"{"ok":true}"#, metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let title = ChatToolStepModel(tool: tool).title
        XCTAssertTrue(title.contains("GitHub"), "标题应含站点名，实际=\(title)")
        XCTAssertFalse(title.contains("{"), "标题不应再塞整段 JSON，实际=\(title)")
    }

    func testWebMountTitlePrefersNameWhenDisplayNameMissing() {
        let input = #"{"name":"OpenAI Codex","url":"https://github.com/openai/codex"}"#
        let tool = UIMessagePart.Tool(
            toolCallId: "call_wm_site_add_name",
            toolName: "wm_site_add",
            input: input,
            output: [UIMessagePart.Text(text: #"{"ok":true}"#, metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let title = ChatToolStepModel(tool: tool).title
        XCTAssertTrue(title.contains("OpenAI Codex"), "标题应优先 name，实际=\(title)")
        XCTAssertFalse(title.contains("https://"), "不应退回整段 URL，实际=\(title)")
    }

    func testLongWebMountToolTitleFitsWhenColumnWidthIsProposed() {
        let input = """
        {"display_name":"GitHub","homepage_url":"https://github.com/openai/codex","site_id":"user_github","timeout_ms":15000}
        """
        let tool = UIMessagePart.Tool(
            toolCallId: "call_wm_site_add",
            toolName: "wm_site_add",
            input: input,
            output: [UIMessagePart.Text(text: #"{"ok":true}"#, metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let host = UIHostingController(rootView: ChatToolTimeline(steps: [ChatToolStepModel(tool: tool)]))
        let fitted = host.sizeThatFits(in: CGSize(
            width: columnWidth,
            height: UIView.layoutFittingExpandedSize.height
        ))
        XCTAssertLessThanOrEqual(fitted.width, columnWidth + 1, "fitted=\(fitted)")
    }
}

final class ChatToolGlyphMappingTests: XCTestCase {
    func testKoboyoMarksParseToNonEmptyPaths() {
        for mark in ChatKoboyoMark.allCases {
            let bounds = mark.renderedPath.boundingRect
            XCTAssertFalse(
                bounds.isNull || bounds.isEmpty || bounds.width < 1 || bounds.height < 1,
                "\(mark.rawValue) path should parse to a drawable glyph"
            )
        }
    }

    func testVisualKindMapsKnownTools() {
        let cases: [(String, ChatToolVisualKind, ChatKoboyoMark, String)] = [
            ("search_web", .search, .solidSearch, "magnifyingglass"),
            ("scrape_web", .web, .solidGlobe, "globe"),
            ("wm_open", .webMount, .solidMonitor, "globe.badge.chevron.backward"),
            ("wm_observe", .webMountObserve, .solidEye, "globe.badge.chevron.backward"),
            ("wm_screenshot", .webMountCapture, .solidCamera, "globe.badge.chevron.backward"),
            ("workspace_file_read", .workspaceRead, .solidDocument, "doc.text"),
            ("workspace_file_write", .workspaceWrite, .solidPen, "folder"),
            ("workspace_artifact_delete", .workspaceDelete, .solidWrench, "folder"),
            ("generate_image", .image, .solidImage, "photo.on.rectangle"),
            ("ish_handoff", .terminal, .solidTerminal, "terminal"),
            ("ios_ish_execute", .terminal, .solidTerminal, "terminal"),
            ("mcp_call", .mcp, .solidPuzzle, "puzzlepiece.extension"),
            ("subagent_dispatch", .subagent, .solidUsers, "person.2.fill"),
            ("model_council_run", .council, .solidPeopleGroup, "person.3.sequence"),
            ("memory_tool", .memory, .solidBrain, "brain.head.profile"),
        ]
        for (name, kind, mark, systemImage) in cases {
            let resolved = ChatToolVisualKind.resolve(toolName: name)
            XCTAssertEqual(resolved, kind, name)
            XCTAssertEqual(resolved.koboyoMark, mark, name)
            XCTAssertEqual(resolved.systemImage, systemImage, name)
        }
    }

    func testStepModelUsesKoboyoMarkAndKeepsIslandSystemImage() {
        let tool = UIMessagePart.Tool(
            toolCallId: "c1",
            toolName: "search_web",
            input: #"{"query":"amber"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let step = ChatToolStepModel(tool: tool)
        XCTAssertEqual(step.visualKind, .search)
        XCTAssertEqual(step.koboyoMark, .solidSearch)
        XCTAssertEqual(step.systemImage, "magnifyingglass")
        XCTAssertTrue(step.visualKind.isImageTool == false)
    }

    func testImageToolVisualKindFlagsIslandImageKind() {
        XCTAssertTrue(ChatToolVisualKind.image.isImageTool)
        XCTAssertEqual(ChatToolVisualKind.image.activeIslandTint, .green)
        XCTAssertEqual(ChatToolVisualKind.search.activeIslandTint, .cyan)
    }
}
