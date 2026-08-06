import XCTest
import Shared
@testable import iosApp

final class IOSMiniAppOutputParserTests: XCTestCase {
    func testParsesFencedJsonAndNormalizesPermissions() throws {
        let text = """
        ```json
        {
          "title": "搜索工具",
          "description": "一个搜索小工具",
          "icon": "🔎",
          "category": "tool",
          "permissions": ["fetch", "ambient-light", "fetch"],
          "html": "<!DOCTYPE html><html><body><h1>Search</h1></body></html>"
        }
        ```
        """

        let output = try IOSMiniAppOutputParser().parse(text)
        XCTAssertEqual(output.permissions, ["network", "sensor"])
        XCTAssertEqual(output.title, "搜索工具")
    }

    func testRejectsUnknownPermissionAndInvalidHtml() {
        let text = """
        {"title":"坏","description":"坏输出","category":"tool","permissions":["root"],"html":"<!DOCTYPE html><html><script>eval('1')</script></html>"}
        """
        XCTAssertThrowsError(try IOSMiniAppOutputParser().parse(text))
    }

    func testParsesJsonWithDefaultCategoryAndPermissions() throws {
        let text = """
        {"title":"计时器","description":"一个番茄钟","html":"<!DOCTYPE html><html><body>ok</body></html>"}
        """
        let output = try IOSMiniAppOutputParser().parse(text)
        XCTAssertEqual(output.category, "tool")
        XCTAssertEqual(output.permissions, [])
        XCTAssertEqual(output.title, "计时器")
    }

    func testParsesFencedHtmlFallback() throws {
        let text = """
        ```html
        <!DOCTYPE html><html><head><title>Fallback App</title></head><body><h1>Hello</h1></body></html>
        ```
        """

        let output = try IOSMiniAppOutputParser().parse(text)
        XCTAssertEqual(output.title, "Fallback App")
        XCTAssertEqual(output.category, "custom")
        XCTAssertTrue(output.permissions.isEmpty)
    }

    func testExplicitMiniAppRequestHeuristics() {
        XCTAssertTrue(IOSMiniAppOutputParser.isExplicitMiniAppRequest("帮我做一个番茄钟小应用"))
        XCTAssertTrue(IOSMiniAppOutputParser.isExplicitMiniAppRequest("Create a mini app timer"))
        XCTAssertFalse(IOSMiniAppOutputParser.isExplicitMiniAppRequest("不要生成小应用，只要说明"))
        XCTAssertFalse(IOSMiniAppOutputParser.isExplicitMiniAppRequest("帮我做一个 PPT deck"))
        XCTAssertTrue(IOSMiniAppOutputParser.isExplicitMiniAppRequest("把这个内容做成小应用版 presentation"))
    }

    func testGenerationInstructionDocumentsEveryRunnableV3Capability() {
        let prompt = IOSMiniAppOutputParser.miniAppInstruction

        for contract in [
            "Amber.fetch",
            "{items:[{title,url,snippet,source,publishedAt?}]}",
            "Amber.clipboard.read()",
            "Amber.host.getConversationContext",
            "Amber.host.sendToConversation",
            "Amber.host.createArtifact",
            "Amber.sharedStore.get/set/remove",
            "Amber.eventBus.subscribe",
            "Amber.launch",
            "Amber.location.getCurrent",
            "Amber.sensor.subscribe",
            "生成后自检要求",
        ] {
            XCTAssertTrue(prompt.contains(contract), "Missing MiniApp generation contract: \(contract)")
        }
    }

    @MainActor
    func testContinueAfterTruncatedMiniAppKeepsOriginalGenerationIntent() throws {
        let original = "帮我做一个番茄钟小应用"
        let messages: [UIMessage] = [
            UIMessage.companion.user(prompt: original),
            UIMessage.companion.assistant(
                prompt: #"{"title":"番茄钟","description":"计时","html":"<!DOCTYPE html><html><body>"#
            ),
            ChatGenerationCoordinator.outputLimitNotice(),
            UIMessage.companion.user(prompt: "继续"),
        ]

        let turn = try XCTUnwrap(ChatRuntimeContextBuilder.miniAppTurnContext(in: messages))
        XCTAssertEqual(turn.currentUserIndex, 3)
        XCTAssertEqual(turn.requestText, original)
        XCTAssertTrue(turn.isContinuation)
    }

    @MainActor
    func testContinueDoesNotReviveMiniAppBeforeAnUnrelatedUserTurn() {
        let messages: [UIMessage] = [
            UIMessage.companion.user(prompt: "帮我做一个番茄钟小应用"),
            UIMessage.companion.assistant(
                prompt: #"{"title":"番茄钟","description":"计时","html":"<!DOCTYPE html><html><body>"#
            ),
            ChatGenerationCoordinator.outputLimitNotice(),
            UIMessage.companion.user(prompt: "先解释一下时间管理方法"),
            UIMessage.companion.assistant(prompt: "可以从设定边界开始。"),
            UIMessage.companion.user(prompt: "继续"),
        ]

        XCTAssertNil(ChatRuntimeContextBuilder.miniAppTurnContext(in: messages))
    }
}
