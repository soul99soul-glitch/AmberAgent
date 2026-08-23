import XCTest
@testable import iosApp

/// GrokWeb 流式走 WKWebView + 注入 fetch。native 侧靠 JS 回调(complete/error/line-isFinished)
/// 或 webView 代理来 resume `stream` 的 continuation。这两类回归网锁定"所有让 fetch 不再 settle
/// 的路径都有 native 收口点"，避免出现永久 generating（continuation 永不 resume）。
///
/// 无法做端到端运行时测试（需真实 grok.com 会话与可控的进程崩溃），故用源码契约 canary
/// 锁定结构性事实：删掉这些收口点会让 canary 转红。
final class IOSGrokWebStreamTerminalContractTests: XCTestCase {
    private func source(_ relativePath: String) throws -> String {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosAppRoot = testsDir.deletingLastPathComponent()
        return try String(contentsOf: iosAppRoot.appendingPathComponent(relativePath), encoding: .utf8)
    }

    /// 进程终止钩子：WKWebView 内容进程被系统杀时，fetch 不会 settle 也不触发 didFail，
    /// 必须由 `webViewWebContentProcessDidTerminate` 收口 stream/load 两个 continuation，
    /// 否则 generating 永久卡住。该钩子必须把状态报成失败（非取消），交由现有 onError 诚实收口。
    func testWebViewProcessTerminationResumesBothContinuationsAsFailure() throws {
        let provider = try source("iosApp/IOSGrokWebProvider.swift")
        XCTAssertTrue(
            provider.contains("func webViewWebContentProcessDidTerminate"),
            "IOSGrokWebBrowserTransport 必须实现 webViewWebContentProcessDidTerminate；" +
                "内容进程被系统终止时 fetch 不会 settle，缺此钩子会让 stream 永久挂起。",
        )
        // 取该钩子函数体（到下一个顶层 func 之前）检查它收口了两条 continuation。
        guard let start = provider.range(of: "func webViewWebContentProcessDidTerminate")?.lowerBound else {
            XCTFail("缺少 webViewWebContentProcessDidTerminate 实现"); return
        }
        let rest = provider[start...]
        let bodyEnd = rest.range(of: "\n    func ")?.lowerBound ?? rest.endIndex
        let body = rest[..<bodyEnd]
        XCTAssertTrue(body.contains("finishLoading(throwing:"), "进程终止钩子必须收口 load continuation")
        XCTAssertTrue(body.contains("finishStream(throwing:"), "进程终止钩子必须收口 stream continuation")
        XCTAssertFalse(
            body.contains("CancellationError"),
            "进程终止是真实传输中断，不应报成 CancellationError（会被当作取消静默吞掉）。",
        )
    }

    /// JS 注入脚本的每个非异常分支都必须 post 一条终结消息（complete/error），
    /// 否则 native continuation 永远等不到终结。重点锁"成功但无可读流"（200 + null body）
    /// 这条早 return 不再静默，而是显式 complete。
    func testStreamScriptPostsTerminalMessageOnEmptySuccessBody() throws {
        let provider = try source("iosApp/IOSGrokWebProvider.swift")
        // streamScript 是内联 JS 模板字符串。锁定它的关键不变量：
        // 对 `!response.body` 不再静默 return，而是 post complete。
        XCTAssertTrue(
            provider.contains(#"if (!response.body) { post("complete", {}); return; }"#),
            "GrokWeb 注入脚本在 HTTP 200 但 response.body 为空时必须 post complete；" +
                "静默 return 会让 native stream continuation 永久挂起。",
        )
    }
}
