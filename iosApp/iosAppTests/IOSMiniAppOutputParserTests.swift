import XCTest
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
}
