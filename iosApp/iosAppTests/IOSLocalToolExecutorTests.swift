import XCTest
import WebKit
@testable import iosApp

@MainActor
final class IOSLocalToolExecutorTests: XCTestCase {
    func testPermissionsStatusReturnsIOSSnapshot() async throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let fileCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" }
        )
        permissionStore.setPolicy(.disabled, for: fileCapability)
        let executor = makeExecutor(permissionStore: permissionStore)

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "permissions_status",
                operation: "status",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )

        guard case .permissionsStatus(let snapshot) = output else {
            return XCTFail("Expected permissions status, got \(output)")
        }
        XCTAssertEqual(snapshot.platform, "iOS")
        XCTAssertFalse(snapshot.capabilities.isEmpty)
        let selectedFile = snapshot.capabilities.first { $0.id == "ios.files.selected_read" }
        XCTAssertEqual(selectedFile?.policy, IOSAgentPermissionPolicy.disabled.title)
    }

    func testFilePickIsDeniedBecauseItIsUIOnly() async {
        let output = await makeExecutor().execute(
            IOSLocalToolExecutionRequest(
                toolName: "file_pick",
                operation: "pick",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )

        guard case .denied(let reason) = output else {
            return XCTFail("Expected denied, got \(output)")
        }
        XCTAssertTrue(reason.contains("foreground UI action"))
    }

    func testUnknownPlannedAndBlockedToolsAreDenied() async {
        let executor = makeExecutor()
        let toolNames = [
            "unknown_tool",
            "location_current",
            "sms_read",
            "notification_list",
            "terminal_execute"
        ]

        for toolName in toolNames {
            let output = await executor.execute(
                IOSLocalToolExecutionRequest(
                    toolName: toolName,
                    operation: "test",
                    scopeDigest: "",
                    payloadDigest: "",
                    isUserInitiated: true
                )
            )
            guard case .denied = output else {
                return XCTFail("Expected denied for \(toolName), got \(output)")
            }
        }
    }

    func testFileReadWithoutGrantNeedsUserAction() async {
        let output = await makeExecutor().execute(
            IOSLocalToolExecutionRequest(
                toolName: "file_read_selected",
                operation: "read_preview",
                scopeDigest: "missing",
                payloadDigest: "missing",
                isUserInitiated: true
            )
        )

        guard case .needsUserAction = output else {
            return XCTFail("Expected needsUserAction, got \(output)")
        }
    }

    func testValidGrantReturnsPreviewOnlyOnce() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults())
        let fileCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" }
        )
        permissionStore.setPolicy(.askEveryTime, for: fileCapability)
        let executor = makeExecutor(permissionStore: permissionStore, documentStore: documentStore)
        let request = IOSLocalToolExecutionRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let first = await executor.execute(request)
        guard case .selectedFilePreview(let result) = first else {
            return XCTFail("Expected selectedFilePreview, got \(first)")
        }
        XCTAssertEqual(result.bytesRead, 16)

        let second = await executor.execute(request)
        guard case .denied = second else {
            return XCTFail("Expected second execution to deny, got \(second)")
        }
    }

    func testScopeToolOrPayloadMismatchCannotSucceed() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let executor = makeExecutor(documentStore: documentStore)
        let requests = [
            IOSLocalToolExecutionRequest(
                toolName: "unknown_tool",
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: "wrong-scope",
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: "wrong-payload",
                isUserInitiated: true
            )
        ]

        for request in requests {
            let output = await executor.execute(request)
            guard case .denied = output else {
                XCTFail("Expected denied for mismatched request \(request), got \(output)")
                continue
            }
        }
    }

    func testAskEveryTimeRequiresForegroundUserAction() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let executor = makeExecutor(documentStore: documentStore)

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: false
            )
        )

        guard case .needsUserAction(let reason) = output else {
            return XCTFail("Expected needsUserAction, got \(output)")
        }
        XCTAssertTrue(reason.contains("Ask every time"))
    }

    func testWebMountRegistrySeedsAndPersists() throws {
        let defaults = isolatedDefaults()
        let registry = IOSWebMountRegistry(userDefaults: defaults)

        XCTAssertEqual(Set(registry.sites.map(\.id)), [
            "hackernews",
            "reddit",
            "github",
            "bilibili",
            "x_com",
            "weibo",
            "juejin",
            "zhihu",
            "feishu_docs"
        ])

        registry.setEnabled(id: "github", enabled: true)
        let reloaded = IOSWebMountRegistry(userDefaults: defaults)
        XCTAssertEqual(reloaded.site(id: "github")?.enabled, true)
    }

    func testWebMountRegistryRestoresSeedsWhenPersistedPayloadIsUnreadable() throws {
        let defaults = isolatedDefaults()
        defaults.set(Data([0x7b]), forKey: "app.amber.ios.webmount.sites.v1")
        defaults.set(true, forKey: "app.amber.ios.webmount.seeded.v1")

        let registry = IOSWebMountRegistry(userDefaults: defaults)

        XCTAssertEqual(registry.sites.count, 9)
        XCTAssertNotNil(registry.site(id: "github"))
    }

    func testWebMountRegistryAddRemoveRestore() throws {
        let defaults = isolatedDefaults()
        let registry = IOSWebMountRegistry(userDefaults: defaults)

        let custom = try registry.addCustomSite(
            displayName: "Example Docs",
            homepageURL: "https://docs.example.com/start?token=secret",
            needsLogin: true,
            loginCookieName: "sid"
        )
        XCTAssertTrue(custom.id.hasPrefix("user_"))
        XCTAssertEqual(custom.allowedHosts, ["docs.example.com"])
        XCTAssertTrue(registry.remove(id: "github"))
        XCTAssertNil(registry.site(id: "github"))
        XCTAssertEqual(registry.restoreMissingSeeds(), 1)
        XCTAssertNotNil(registry.site(id: "github"))
    }

    func testWebMountSettingsDefaultsAndEvalGate() {
        let settings = IOSWebMountSettings(userDefaults: isolatedDefaults())

        XCTAssertFalse(settings.globalEnabled)
        XCTAssertFalse(settings.evalEnabled)
        XCTAssertTrue(settings.allowedHosts.contains("github.com"))
        settings.globalEnabled = true
        settings.evalEnabled = true
        XCTAssertTrue(settings.evalEnabled)
        settings.globalEnabled = false
        XCTAssertFalse(settings.evalEnabled)
    }

    func testWebMountURLPolicyRejectsSchemeAndHostOutsideAllowlist() throws {
        let settings = IOSWebMountSettings(userDefaults: isolatedDefaults())
        let policy = IOSWebMountURLPolicy(settings: settings)

        XCTAssertNotNil(try? policy.validate("https://github.com/login").get())
        XCTAssertEqual(
            policy.validate("javascript:alert(1)").failure,
            .unsupportedScheme("javascript")
        )
        XCTAssertEqual(
            policy.validate("https://evil.example.com/").failure,
            .hostNotAllowed("evil.example.com")
        )
    }

    func testWebMountRedactionRemovesSensitiveValuesAndURLQuery() throws {
        let redacted = IOSWebMountRedactor.redactedJSONObject([
            "url": "https://example.com/path?token=secret#frag",
            "href": "https://example.com/next?auth=secret",
            "cookie": "sid=secret",
            "nested": [
                "Authorization": "Bearer secret"
            ]
        ])
        let json = IOSWebMountController.json(redacted)
        let object = try jsonObject(json)

        XCTAssertEqual(object["url"] as? String, "https://example.com/path")
        XCTAssertEqual(object["href"] as? String, "https://example.com/next")
        XCTAssertFalse(json.contains("token=secret"))
        XCTAssertFalse(json.contains("auth=secret"))
        XCTAssertFalse(json.contains("sid=secret"))
        XCTAssertFalse(json.contains("Bearer secret"))
    }

    func testWebMountToolCatalogAndUnsupportedResult() {
        XCTAssertEqual(IOSWebMountToolCatalog.supportedToolNames.count, 8)
        XCTAssertTrue(IOSWebMountToolCatalog.supportedToolNames.contains("wm_open"))
        XCTAssertTrue(IOSWebMountToolCatalog.unsupportedToolNames.contains("wm_eval"))
        XCTAssertTrue(IOSWebMountController.unsupportedToolResult(toolName: "wm_eval").contains("unsupported"))
    }

    func testWebMountStationsUsesRegistryAndRedactedCookieSummary() async throws {
        let controller = makeWebMountController(globalEnabled: true)
        let output = await controller.execute(toolName: "wm_stations", input: "{}", isUserInitiated: false)
        let object = try jsonObject(output)

        XCTAssertEqual(object["ok"] as? Bool, true)
        XCTAssertEqual(object["count"] as? Int, 9)
        XCTAssertFalse(output.contains("cookie-value"))
        let stations = try XCTUnwrap(object["stations"] as? [[String: Any]])
        XCTAssertTrue(stations.contains { ($0["id"] as? String) == "github" })
    }

    func testWebMountOpenAndExtractUseMockRuntimeAndRedactURLs() async throws {
        let controller = makeWebMountController(globalEnabled: true)
        controller.registry.setEnabled(id: "github", enabled: true)
        let open = await controller.execute(
            toolName: "wm_open",
            input: #"{"site_id":"github","url":"https://github.com/login?token=secret"}"#,
            isUserInitiated: false
        )
        let openObject = try jsonObject(open)
        XCTAssertEqual(openObject["ok"] as? Bool, true)
        XCTAssertFalse(open.contains("token=secret"))

        let extract = await controller.execute(toolName: "wm_extract", input: #"{"mode":"readable"}"#, isUserInitiated: false)
        XCTAssertTrue(extract.contains("Hello from mock"))
        XCTAssertFalse(extract.contains("secret"))

        let get = await controller.execute(
            toolName: "wm_get",
            input: #"{"selector":"h1","kind":"text"}"#,
            isUserInitiated: false
        )
        let getObject = try jsonObject(get)
        XCTAssertEqual(getObject["ok"] as? Bool, true)
        let result = try XCTUnwrap(getObject["result"] as? [String: Any])
        XCTAssertEqual(result["selector"] as? String, "h1")
        XCTAssertEqual(result["value"] as? String, "Hello from mock")
    }

    func testWebMountExecutorDeniesGlobalOffAndClearWithoutUserAction() async throws {
        let controller = makeWebMountController(globalEnabled: false)
        let executor = makeExecutor(webMountController: controller)

        let offOutput = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "wm_stations",
                operation: "{}",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction(let offReason) = offOutput else {
            return XCTFail("Expected needsUserAction, got \(offOutput)")
        }
        XCTAssertTrue(offReason.contains("Enable WebMount"))

        controller.settings.globalEnabled = true
        let clearOutput = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "wm_clear_session",
                operation: #"{"site_id":"github"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction(let clearReason) = clearOutput else {
            return XCTFail("Expected needsUserAction, got \(clearOutput)")
        }
        XCTAssertTrue(clearReason.contains("Clearing WebMount cookies"))
    }

    func testWebMountExecutorAllowsModelToolsAfterForegroundSessionEnabled() async throws {
        let controller = makeWebMountController(globalEnabled: true)
        let executor = makeExecutor(webMountController: controller)

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "wm_stations",
                operation: "{}",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )

        guard case .webMountResult(let text) = output else {
            return XCTFail("Expected WebMount result, got \(output)")
        }
        let object = try jsonObject(text)
        XCTAssertEqual(object["ok"] as? Bool, true)
    }

    func testWebMountExecutorRespectsDisabledPermissionPolicy() async throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let capability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.webmount.browser" }
        )
        permissionStore.setPolicy(.disabled, for: capability)
        let executor = makeExecutor(
            permissionStore: permissionStore,
            webMountController: makeWebMountController(globalEnabled: true)
        )

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "wm_stations",
                operation: "{}",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )

        guard case .denied(let reason) = output else {
            return XCTFail("Expected denied, got \(output)")
        }
        XCTAssertTrue(reason.contains("Disabled"))
    }

    private func makeExecutor(
        permissionStore: IOSPermissionStore? = nil,
        documentStore: DocumentAccessStore = DocumentAccessStore(),
        webMountController: IOSWebMountController? = nil
    ) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: permissionStore ?? IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore,
            webMountController: webMountController
        )
    }

    private func makeWebMountController(globalEnabled: Bool) -> IOSWebMountController {
        let defaults = isolatedDefaults()
        let registry = IOSWebMountRegistry(userDefaults: defaults)
        let settings = IOSWebMountSettings(userDefaults: defaults)
        settings.globalEnabled = globalEnabled
        return IOSWebMountController(
            registry: registry,
            settings: settings,
            cookieStore: MockWebMountCookieStore(),
            runtime: MockWebMountRuntime()
        )
    }

    private func jsonObject(_ text: String) throws -> [String: Any] {
        let data = try XCTUnwrap(text.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func makeTempFile(size: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data(repeating: 65, count: size).write(to: url)
        return url
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

private extension Result where Success == URL, Failure == IOSWebMountURLPolicyError {
    var failure: IOSWebMountURLPolicyError? {
        switch self {
        case .success:
            nil
        case .failure(let error):
            error
        }
    }
}

@MainActor
private final class MockWebMountCookieStore: IOSWebMountCookieStoreProtocol {
    var clearedSiteIds: [String] = []

    func summary(for site: IOSWebMountSite) async -> IOSWebMountCookieSummary {
        IOSWebMountCookieSummary(
            siteId: site.id,
            cookieCount: site.id == "github" ? 1 : 0,
            cookieNames: site.id == "github" ? ["user_session"] : [],
            domains: site.id == "github" ? ["github.com"] : [],
            hasLoginCookie: site.loginCookieName.map { $0 == "user_session" },
            redacted: true
        )
    }

    func clearSession(for site: IOSWebMountSite) async -> IOSWebMountCookieClearResult {
        clearedSiteIds.append(site.id)
        return IOSWebMountCookieClearResult(
            siteId: site.id,
            deletedCookieCount: 1,
            clearedWebsiteDataRecords: 1
        )
    }
}

@MainActor
private final class MockWebMountRuntime: IOSWebMountRuntimeServicing {
    var snapshot = IOSWebMountRuntimeSnapshot.idle(sessionId: "mock")
    var webView: WKWebView? { nil }
    var openedURLs: [URL] = []

    func open(_ url: URL, timeoutMillis: UInt64) async -> IOSWebMountRuntimeSnapshot {
        openedURLs.append(url)
        snapshot = IOSWebMountRuntimeSnapshot(
            sessionId: "mock",
            status: .ready,
            requestedURL: IOSWebMountRedactor.redactedURL(url.absoluteString),
            currentURL: IOSWebMountRedactor.redactedURL(url.absoluteString),
            title: "Mock Page",
            estimatedProgress: 1,
            error: nil,
            updatedAtMillis: 123
        )
        return snapshot
    }

    func state() async throws -> [String: Any] {
        [
            "url": "https://github.com/login?token=secret",
            "title": "Mock Page",
            "ready_state": "complete"
        ]
    }

    func extract(mode: String, maxChars: Int, maxLinks: Int) async throws -> [String: Any] {
        [
            "mode": mode,
            "text": "Hello from mock",
            "links": [
                ["href": "https://github.com/settings?token=secret", "text": "settings"]
            ]
        ]
    }

    func get(selector: String?, target: String?, kind: String, attrName: String?, maxChars: Int) async throws -> [String: Any] {
        [
            "ok": true,
            "selector": selector ?? target ?? "body",
            "kind": kind,
            "value": "Hello from mock"
        ]
    }

    func back() async -> IOSWebMountRuntimeSnapshot {
        snapshot.status = .ready
        return snapshot
    }

    func forward() async -> IOSWebMountRuntimeSnapshot {
        snapshot.status = .ready
        return snapshot
    }
}
