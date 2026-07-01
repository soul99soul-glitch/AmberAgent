import XCTest
@testable import iosApp

final class IOSCodexProviderResolverTests: XCTestCase {
    func testRequestDiagnosticLineRedactsBearerAndHeaderValues() {
        let line = IOSCodexProviderResolver.requestDiagnosticLine(
            originalAuthMode: "CODEX_OAUTH",
            resolvedBaseUrl: "https://chatgpt.com/backend-api/codex",
            finalURL: "https://chatgpt.com/backend-api/codex/responses",
            bearer: "eyJhbGciOiJub25lIn0.eyJhY2NvdW50IjoiYSJ9.signature",
            model: "gpt-5.5",
            headers: [
                "Authorization": "Bearer secret-token",
                "OpenAI-Beta": "responses=experimental",
                "ChatGPT-Account-Id": "account-secret",
                "": "empty-secret",
                "   ": "space-secret"
            ]
        )

        XCTAssertTrue(line.contains("original.authMode=CODEX_OAUTH"))
        XCTAssertTrue(line.contains("resolved.baseUrl=https://chatgpt.com/backend-api/codex"))
        XCTAssertTrue(line.contains("url=https://chatgpt.com/backend-api/codex/responses"))
        XCTAssertTrue(line.contains("bearer=JWT(len="))
        XCTAssertTrue(line.contains("model=gpt-5.5"))
        XCTAssertTrue(line.contains("headers=[Authorization,ChatGPT-Account-Id,OpenAI-Beta]"))
        XCTAssertFalse(line.contains("secret-token"))
        XCTAssertFalse(line.contains("account-secret"))
        XCTAssertFalse(line.contains("eyJhbGci"))
    }
}
