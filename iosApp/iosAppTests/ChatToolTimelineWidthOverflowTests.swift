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

    /// cell 自 sizing 用无界提案询问理想宽度：胶囊理想宽必须自身就在列宽预算内，
    /// 否则列宽随 toolCallStarted/完成换词跳变、超长行被居中裁切顶到屏幕两端。
    /// 有界提案下的 truncation 由上一条用例覆盖，这里只锁理想宽。
    func testToolCapsuleIdealWidthStaysWithinColumnUnderUnboundedProposal() {
        let longCJK = String(repeating: "长", count: 40)
        let tools: [UIMessagePart.Tool] = [
            UIMessagePart.Tool(
                toolCallId: "call_search_long",
                toolName: "search_web",
                input: #"{"query":""# + longCJK + #""}"#,
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            ),
            UIMessagePart.Tool(
                toolCallId: "call_image_long",
                toolName: "generate_image",
                input: #"{"prompt":""# + longCJK + #""}"#,
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            ),
            UIMessagePart.Tool(
                toolCallId: "call_mcp_long",
                toolName: "mcp__a_very_long_server_name__a_very_long_tool_name",
                input: "{}",
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            ),
        ]
        for tool in tools {
            let host = UIHostingController(rootView: ChatToolTimeline(steps: [ChatToolStepModel(tool: tool)]))
            let fitted = host.sizeThatFits(in: CGSize(
                width: CGFloat.greatestFiniteMagnitude,
                height: UIView.layoutFittingExpandedSize.height
            ))
            XCTAssertLessThanOrEqual(
                fitted.width,
                columnWidth + 1,
                "胶囊理想宽超出列宽（\(tool.toolName)）：fitted=\(fitted)"
            )
        }
    }

    /// 搜索胶囊生命周期三阶段宽度恒定契约（本轮真机 bug 的红测试）：
    /// 同一 tool call 的胶囊理想宽不得随「正在搜索 → 已搜索」换词或状态图标
    /// （转圈 → 对勾）变化——否则执行期间撑宽、完成缩回，胶囊/列宽随
    /// toolCallStarted / toolResultAppended 跳变。
    func testSearchCapsuleIdealWidthConstantAcrossLifecyclePhases() {
        // 真实形态：20+ 字中文查询（超过 subject 截断预算，三阶段共用同一截断后宽度）。
        let query = "苹果公司2026年秋季新品发布会时间安排和产品阵容一览表"
        XCTAssertGreaterThanOrEqual(query.count, 20, "用例前置：查询词应 ≥20 字")
        let input = #"{"query":"\#(query)"}"#

        func capsuleIdealWidth(output: [UIMessagePart]) -> CGFloat {
            let tool = UIMessagePart.Tool(
                toolCallId: "call_search_lifecycle",
                toolName: "search_web",
                input: input,
                output: output,
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )
            let host = UIHostingController(rootView: ChatToolTimeline(steps: [ChatToolStepModel(tool: tool)]))
            return host.sizeThatFits(in: CGSize(
                width: CGFloat.greatestFiniteMagnitude,
                height: UIView.layoutFittingExpandedSize.height
            )).width
        }

        let started = capsuleIdealWidth(output: [])
        let executing = capsuleIdealWidth(output: [])
        let completed = capsuleIdealWidth(output: [
            UIMessagePart.Text(text: #"{"results":[{"title":"苹果秋季发布会 2026"}]}"#, metadata: nil)
        ])

        XCTAssertEqual(
            executing, started,
            "toolCallStarted 与执行中阶段胶囊理想宽不一致：started=\(started) executing=\(executing)"
        )
        XCTAssertEqual(
            completed, started,
            "toolResultAppended 换词（正在搜索→已搜索）后胶囊理想宽变化（执行期间撑宽、完成缩回）：started=\(started) completed=\(completed)"
        )
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
