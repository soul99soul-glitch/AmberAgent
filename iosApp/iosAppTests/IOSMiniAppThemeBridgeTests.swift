import XCTest
@testable import iosApp

@MainActor
final class IOSMiniAppThemeBridgeTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    func testThemePayloadExposesHostSurfaceTokens() {
        let light = IOSMiniAppThemeBridge.payload(
            paper: .notion,
            dark: false,
            accentHex: AmberAccentOption.notionBlue.accentHex,
            accentInkHex: AmberAccentOption.notionBlue.inkHex
        )
        XCTAssertFalse(light.dark)
        XCTAssertEqual(light.background, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionLight.background))
        XCTAssertEqual(light.surface, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionLight.surface))
        XCTAssertEqual(light.surface2, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionLight.surface2))
        XCTAssertEqual(light.foreground, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionLight.foreground))
        XCTAssertEqual(light.muted, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionLight.muted))
        XCTAssertEqual(light.primary, IOSMiniAppThemeBridge.cssHex(AmberAccentOption.notionBlue.accentHex))
        XCTAssertEqual(light.primaryInk, IOSMiniAppThemeBridge.cssHex(AmberAccentOption.notionBlue.inkHex))

        let dark = IOSMiniAppThemeBridge.payload(
            paper: .notion,
            dark: true,
            accentHex: AmberAccentOption.notionBlue.accentHex,
            accentInkHex: AmberAccentOption.notionBlue.inkHex
        )
        XCTAssertTrue(dark.dark)
        XCTAssertEqual(dark.background, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionDark.background))
        XCTAssertEqual(dark.surface, IOSMiniAppThemeBridge.cssHex(AmberTheme.notionDark.surface))
        XCTAssertNotEqual(dark.background, dark.surface)
        XCTAssertNotEqual(dark.surface, dark.surface2)
    }

    func testThemePayloadTracksAccentWhenPaperUnchanged() {
        let steel = IOSMiniAppThemeBridge.payload(
            paper: .pi,
            dark: false,
            accentHex: AmberAccentOption.steelBlue.accentHex,
            accentInkHex: AmberAccentOption.steelBlue.inkHex
        )
        let rose = IOSMiniAppThemeBridge.payload(
            paper: .pi,
            dark: false,
            accentHex: AmberAccentOption.rose.accentHex,
            accentInkHex: AmberAccentOption.rose.inkHex
        )
        XCTAssertEqual(steel.background, rose.background)
        XCTAssertNotEqual(steel.primary, rose.primary)
        XCTAssertNotEqual(steel.primaryInk, rose.primaryInk)
    }

    func testGetThemeBridgeReturnsExpandedJSONKeys() async throws {
        let expected = IOSMiniAppThemeBridge.payload(
            paper: .paper,
            dark: true,
            accentHex: AmberAccentOption.terracotta.accentHex,
            accentInkHex: AmberAccentOption.terracotta.inkHex
        )
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(
            IOSMiniAppGeneratedOutput(
                title: "Theme App",
                description: "theme",
                permissions: ["theme"],
                html: "<!doctype html><html><body>ok</body></html>"
            )
        )
        let bridge = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            grantHandler: { _ in true },
            themeProvider: { expected }
        )
        let result = await bridge.dispatch(method: "host.getTheme", params: [:])
        guard case .success(let json) = result, case .object(let obj) = json else {
            return XCTFail("expected success object, got \(result)")
        }
        for key in ["dark", "background", "surface", "surface2", "foreground", "muted", "primary", "primaryInk"] {
            XCTAssertNotNil(obj[key], "missing \(key)")
        }
        XCTAssertEqual(obj["background"], .string(expected.background))
        XCTAssertEqual(obj["surface"], .string(expected.surface))
        XCTAssertEqual(obj["primaryInk"], .string(expected.primaryInk))
        XCTAssertEqual(obj["dark"], .bool(true))
    }

    func testRunnerWebViewSitsOnHostBackgroundNotTexture() throws {
        let text = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/MiniAppRunnerView.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(text.contains("IOSMiniAppThemeBridge.payload"))
        XCTAssertTrue(text.contains(".background(AmberTheme.background)"))
        XCTAssertTrue(text.contains("AmberThemePageBackground(surface: .app)"))
        // Theme is a WebView prop, but must not be part of remount identity.
        XCTAssertTrue(text.contains("theme: miniAppThemePayload"))
        XCTAssertFalse(
            text.contains("access: miniAppAccessIdentity\n                theme: miniAppThemePayload"),
            "theme must not participate in MiniAppRunnerWebViewIdentity"
        )
    }

    func testInjectHostThemeCSSPlacesVarsBeforeHeadClose() {
        let theme = IOSMiniAppThemeBridge.payload(
            paper: .pi,
            dark: true,
            accentHex: AmberAccentOption.steelBlue.accentHex,
            accentInkHex: AmberAccentOption.steelBlue.inkHex
        )
        let html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>:root{--bg:#fff}</style></head><body></body></html>"
        let out = IOSMiniAppThemeBridge.injectHostThemeCSS(html, theme: theme)
        XCTAssertTrue(out.contains("id=\"amber-host-theme\""))
        XCTAssertTrue(out.contains("--bg:\(theme.background)"))
        XCTAssertTrue(out.contains("--primary-ink:\(theme.primaryInk)"))
        // Injected block must follow author :root so host wins first paint.
        let author = out.range(of: ":root{--bg:#fff}")!
        let host = out.range(of: "id=\"amber-host-theme\"")!
        XCTAssertLessThan(author.lowerBound, host.lowerBound)
    }

    func testApplyThemeJavaScriptMentionsAllCssVars() {
        let theme = IOSMiniAppThemeBridge.payload(
            paper: .notion,
            dark: false,
            accentHex: AmberAccentOption.notionBlue.accentHex,
            accentInkHex: AmberAccentOption.notionBlue.inkHex
        )
        let js = IOSMiniAppThemeBridge.applyThemeJavaScript(theme)
        XCTAssertTrue(js.contains(theme.background))
        XCTAssertTrue(js.contains("--muted"))
        XCTAssertTrue(js.contains(theme.primaryInk))
    }

    func testThemeMethodAliasReturnsExpandedJSON() async throws {
        let expected = IOSMiniAppThemeBridge.payload(
            paper: .paper,
            dark: false,
            accentHex: AmberAccentOption.rose.accentHex,
            accentInkHex: AmberAccentOption.rose.inkHex
        )
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(
            IOSMiniAppGeneratedOutput(
                title: "Theme Alias",
                description: "theme",
                permissions: ["theme"],
                html: "<!doctype html><html><body>ok</body></html>"
            )
        )
        let bridge = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            grantHandler: { _ in true },
            themeProvider: { expected }
        )
        let result = await bridge.dispatch(method: "theme", params: [:])
        guard case .success(let json) = result, case .object(let obj) = json else {
            return XCTFail("expected success object, got \(result)")
        }
        XCTAssertEqual(obj["primary"], .string(expected.primary))
        XCTAssertEqual(obj["surface2"], .string(expected.surface2))
    }

    func testThemeGrantDeniedFailsGetTheme() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(
            IOSMiniAppGeneratedOutput(
                title: "Theme Deny",
                description: "theme",
                permissions: ["theme"],
                html: "<!doctype html><html><body>ok</body></html>"
            )
        )
        let bridge = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            grantHandler: { _ in false },
            themeProvider: {
                IOSMiniAppThemeBridge.payload(
                    paper: .neutral,
                    dark: false,
                    accentHex: 0xB9863A,
                    accentInkHex: 0x231602
                )
            }
        )
        let result = await bridge.dispatch(method: "host.getTheme", params: [:])
        guard case .failure(let message) = result else {
            return XCTFail("expected failure, got \(result)")
        }
        XCTAssertTrue(message.contains("denied"), message)
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSMiniAppThemeBridgeTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }
}
