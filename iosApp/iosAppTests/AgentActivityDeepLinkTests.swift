import XCTest
@testable import iosApp

final class AgentActivityDeepLinkTests: XCTestCase {
    func testRoundTripKeepsOnlyTaskIdentifiersAndFocus() throws {
        let url = try XCTUnwrap(AgentActivityDeepLink.makeURL(
            runId: "run-123",
            conversationId: "01234567-89ab-cdef-0123-456789abcdef",
            focus: .confirmation
        ))

        XCTAssertEqual(url.scheme, AgentActivityDeepLink.scheme)
        XCTAssertEqual(
            AgentActivityDeepLink.parse(url),
            AgentActivityDeepLink.Target(
                runId: "run-123",
                conversationId: "01234567-89ab-cdef-0123-456789abcdef",
                focus: .confirmation
            )
        )
        XCTAssertFalse(url.absoluteString.contains("prompt"))
        XCTAssertFalse(url.absoluteString.contains("command"))
    }

    func testParserRejectsAnythingOutsideTheActivityContract() {
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "https://activity/run?conversation=abc&focus=task")!))
        let otherScheme = AgentActivityDeepLink.scheme == "amber" ? "amber-experimental" : "amber"
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "\(otherScheme)://activity/run?conversation=abc&focus=task")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://settings/run?conversation=abc&focus=task")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity//run?conversation=abc&focus=task")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity/run?conversation=abc&focus=approve")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity/run?focus=task")!))
        XCTAssertNil(AgentActivityDeepLink.parse(
            URL(string: "amber://activity/run?conversation=abc&focus=task&approve=true")!
        ))
        XCTAssertNil(AgentActivityDeepLink.makeURL(
            runId: String(repeating: "r", count: 129),
            conversationId: "01234567-89ab-cdef-0123-456789abcdef",
            focus: .task
        ))
    }

    func testSchemeMatchesBothAppAndWidgetBundleFamilies() {
        XCTAssertEqual(
            AgentActivityDeepLink.scheme(forBundleIdentifier: "app.amber.ios"),
            "amber"
        )
        XCTAssertEqual(
            AgentActivityDeepLink.scheme(forBundleIdentifier: "app.amber.ios.activity"),
            "amber"
        )
        XCTAssertEqual(
            AgentActivityDeepLink.scheme(
                forBundleIdentifier: "app.amber.ios.experimental-gpl"
            ),
            "amber-experimental"
        )
        XCTAssertEqual(
            AgentActivityDeepLink.scheme(
                forBundleIdentifier: "app.amber.ios.experimental-gpl.activity"
            ),
            "amber-experimental"
        )
    }

    func testAttributesWithoutConversationOwnershipExposeNoDestination() {
        let attributes = AgentActivityAttributes(
            runId: "run-123",
            conversationId: nil,
            startedAt: .now,
            conversationTitle: nil
        )

        XCTAssertNil(attributes.destinationURL(for: .openTask))
    }
}
