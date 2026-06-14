import XCTest
@testable import iosApp

final class AgentActivityPresentationTests: XCTestCase {
    func testDefaultRunningPresentationMatchesAcceptedStructure() {
        let presentation = AgentActivityPresentation.defaultRunning

        XCTAssertEqual(presentation.statusText, "Amber 正在阅读 Apple 文档")
        XCTAssertEqual(presentation.toolTitle, "网页搜索")
        XCTAssertEqual(presentation.phase, .running)
        XCTAssertEqual(presentation.steps.map(\.state), [.done, .current, .pending])
        XCTAssertEqual(presentation.steps.map(\.title), [
            "搜索 ActivityKit",
            "阅读 Apple 文档",
            "生成适配方案"
        ])
    }

    func testPresentationKeepsAtMostThreeSteps() {
        let presentation = AgentActivityPresentation(
            statusText: "Amber 正在处理",
            toolTitle: "网页搜索",
            phase: .running,
            steps: [
                AgentActivityStep(id: "1", title: "搜索资料", state: .done),
                AgentActivityStep(id: "2", title: "阅读文档", state: .current),
                AgentActivityStep(id: "3", title: "生成方案", state: .pending),
                AgentActivityStep(id: "4", title: "整理结果", state: .pending)
            ]
        )

        XCTAssertEqual(presentation.steps.map(\.id), ["1", "2", "3"])
    }

    func testSensitiveRawDetailsDegradeToGenericPublicSummaries() {
        let presentation = AgentActivityPresentation(
            statusText: "https://developer.apple.com/documentation/activitykit?token=abc",
            toolTitle: "/Users/example/project/secrets.env",
            phase: .running,
            steps: [
                AgentActivityStep(title: "curl https://internal.example.com?token=abc", state: .current),
                AgentActivityStep(title: "read /private/var/mobile/file.txt", state: .pending),
                AgentActivityStep(title: "Authorization: Bearer secret", state: .pending)
            ]
        )

        XCTAssertEqual(presentation.statusText, "处理敏感配置")
        XCTAssertEqual(presentation.toolTitle, "读取文件")
        XCTAssertEqual(presentation.steps.map(\.title), [
            "处理敏感配置",
            "读取文件",
            "处理敏感配置"
        ])
    }

    func testAdditionalSensitivePatternsAreNotDisplayedVerbatim() {
        let presentation = AgentActivityPresentation(
            statusText: "Bearer abc.def.ghi",
            toolTitle: "contact user@example.com",
            phase: .waitingForUser,
            steps: [
                AgentActivityStep(title: "connect 192.168.0.12", state: .current),
                AgentActivityStep(title: "open ~/.ssh/id_rsa", state: .pending),
                AgentActivityStep(title: "sk-test-123", state: .pending)
            ]
        )

        XCTAssertEqual(presentation.statusText, "处理敏感配置")
        XCTAssertEqual(presentation.toolTitle, "处理私密信息")
        XCTAssertEqual(presentation.steps.map(\.title), [
            "处理私密信息",
            "读取文件",
            "处理敏感配置"
        ])
    }

    func testRawPromptLikeTextFallsBackInsteadOfTruncating() {
        let presentation = AgentActivityPresentation(
            statusText: "where did I put my apartment door code",
            toolTitle: "internal.company.example",
            phase: .running,
            steps: [
                AgentActivityStep(title: "python script.py --password hunter2", state: .current),
                AgentActivityStep(title: "cat secrets.txt", state: .pending),
                AgentActivityStep(title: "summarize private legal notes", state: .pending)
            ]
        )

        XCTAssertEqual(presentation.statusText, "Amber 正在处理")
        XCTAssertEqual(presentation.toolTitle, "工具执行")
        XCTAssertEqual(presentation.steps.map(\.title), [
            "处理敏感配置",
            "处理敏感配置",
            "处理任务"
        ])
    }

    func testStateFactoriesCoverWaitingCompletedFailedAndCancelled() {
        XCTAssertEqual(AgentActivityPresentation.waitingForUser().phase, .waitingForUser)
        XCTAssertEqual(AgentActivityPresentation.completed().phase, .completed)
        XCTAssertEqual(AgentActivityPresentation.failed().phase, .failed)
        XCTAssertEqual(AgentActivityPresentation.cancelled().phase, .cancelled)
        XCTAssertEqual(AgentActivityPresentation.readingSelectedFile.phase, .running)
        XCTAssertEqual(AgentActivityPresentation.selectedFileReadCompleted.phase, .completed)
        XCTAssertEqual(AgentActivityPresentation.selectedFileReadFailed.phase, .failed)
        XCTAssertEqual(AgentActivityPresentation.selectedFileReadWaitingForUser.phase, .waitingForUser)
    }
}
