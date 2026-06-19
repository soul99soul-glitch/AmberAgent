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

    func testPermissionsStatusIncludesAdvancedExecutionApprovals() async throws {
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults(), taskStore: nil)
        permissionStore.recordApproval(
            capabilityId: "ios.agent.subagent_dispatch",
            toolName: "subagent_dispatch",
            action: .allowed,
            reason: "role=explorer token=secret",
            runId: "run-subagent"
        )
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
        let subAgent = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.agent.subagent_dispatch" })
        let remote = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.remote.command" })

        XCTAssertTrue(subAgent.modelToolNames.contains("subagent_dispatch"))
        XCTAssertEqual(subAgent.lastApprovalAction, IOSToolApprovalAction.allowed.title)
        XCTAssertFalse(subAgent.lastApprovalReason?.contains("secret") == true)
        XCTAssertTrue(remote.uiActionNames.contains("remote_command_cancel"))
        XCTAssertTrue(remote.modelToolNames.isEmpty)
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

        XCTAssertTrue(settings.globalEnabled)
        XCTAssertFalse(settings.evalEnabled)
        XCTAssertTrue(settings.allowedHosts.contains("github.com"))
        XCTAssertTrue(settings.allowedSchemes.contains("http"))
        XCTAssertTrue(settings.allowedSchemes.contains("https"))
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
            policy.validate("data:text/html,hi").failure,
            .unsupportedScheme("data")
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

    func testWebMountRedactionScrubsSensitivePlainStrings() {
        let text = IOSWebMountRedactor.redactedText(
            "Visit https://example.com/callback?token=secret and use Authorization: Bearer abcdef123456"
        )

        XCTAssertFalse(text.contains("token=secret"))
        XCTAssertFalse(text.contains("abcdef123456"))
        XCTAssertTrue(text.contains("https://example.com/callback"))
    }

    func testWebMountToolCatalogAndUnsupportedResult() {
        // 8 base tools + 6 interaction tools (wm_click/type/scroll/select/find/wait).
        XCTAssertEqual(IOSWebMountToolCatalog.supportedToolNames.count, 14)
        XCTAssertTrue(IOSWebMountToolCatalog.supportedToolNames.contains("wm_open"))
        XCTAssertTrue(IOSWebMountToolCatalog.supportedToolNames.contains("wm_click"))
        XCTAssertTrue(IOSWebMountToolCatalog.supportedToolNames.contains("wm_type"))
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

    func testWebMountExecutorRequiresApprovalAndClearRequiresUserAction() async throws {
        let controller = makeWebMountController(globalEnabled: false)
        let executor = makeExecutor(webMountController: controller)

        let stationsOutput = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "wm_stations",
                operation: "{}",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction(let stationsReason) = stationsOutput else {
            return XCTFail("Expected needsUserAction, got \(stationsOutput)")
        }
        XCTAssertTrue(stationsReason.contains("WebMount browser tools"))

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

    func testWebMountExecutorAllowsToolsAfterForegroundApproval() async throws {
        let controller = makeWebMountController(globalEnabled: true)
        let executor = makeExecutor(webMountController: controller)

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "wm_stations",
                operation: "{}",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
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

    func testWebMountOpenRequiresRegisteredEnabledStation() async throws {
        let controller = makeWebMountController(globalEnabled: true)

        let missing = await controller.execute(
            toolName: "wm_open",
            input: #"{"url":"https://removed.example.com/path?token=secret"}"#,
            isUserInitiated: true
        )
        let missingObject = try jsonObject(missing)
        XCTAssertEqual(missingObject["denied"] as? Bool, true)
        XCTAssertFalse(missing.contains("token=secret"))

        let disabled = await controller.execute(
            toolName: "wm_open",
            input: #"{"site_id":"github"}"#,
            isUserInitiated: true
        )
        let disabledObject = try jsonObject(disabled)
        XCTAssertEqual(disabledObject["denied"] as? Bool, true)
        XCTAssertEqual(disabledObject["site_id"] as? String, "github")
    }

    func testWebMountGetDeniesHtmlAndSensitiveValueSelectors() async throws {
        let controller = makeWebMountController(globalEnabled: true)

        let html = await controller.execute(
            toolName: "wm_get",
            input: #"{"selector":"body","kind":"html"}"#,
            isUserInitiated: true
        )
        let htmlObject = try jsonObject(html)
        XCTAssertEqual(htmlObject["denied"] as? Bool, true)

        let token = await controller.execute(
            toolName: "wm_get",
            input: #"{"selector":"input[name=csrf_token]","kind":"value"}"#,
            isUserInitiated: true
        )
        let tokenObject = try jsonObject(token)
        XCTAssertEqual(tokenObject["denied"] as? Bool, true)
    }

    func testWebMountContentHandoffRedactsAndBuildsChatAndBoardPayloads() throws {
        let site = try XCTUnwrap(IOSWebMountSite.seeds(nowMillis: 1).first { $0.id == "github" })
        let snapshot = IOSWebMountRuntimeSnapshot(
            sessionId: "handoff",
            status: .ready,
            requestedURL: "https://github.com/login",
            currentURL: "https://github.com/settings",
            title: "Settings",
            estimatedProgress: 1,
            canGoBack: true,
            canGoForward: false,
            error: nil,
            updatedAtMillis: 2
        )

        let handoff = try XCTUnwrap(IOSWebMountContentHandoff.from(
            site: site,
            snapshot: snapshot,
            extraction: [
                "title": "Account",
                "url": "https://github.com/settings?token=secret",
                "text": "Profile link https://github.com/settings?token=secret Authorization: Bearer abcdef123456",
                "links": [
                    ["href": "https://github.com/settings?token=secret"]
                ]
            ]
        ))

        XCTAssertFalse(handoff.text.contains("token=secret"))
        XCTAssertFalse(handoff.chatPrompt.contains("abcdef123456"))
        XCTAssertEqual(handoff.boardSignal.sourceType, IOSBoardSignalSourceType.webmount)
        XCTAssertTrue(handoff.boardSignal.metadataJson.contains(#""redacted":true"#))
    }

    func testMemoryToolWritePolicyRequiresForegroundAndRespectsDisabled() throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let executor = makeExecutor(permissionStore: permissionStore)
        let input = #"{"action":"create","content":"remember this"}"#

        XCTAssertEqual(
            executor.memoryToolWritePolicy(input: #"{"action":"list"}"#, isUserInitiated: false),
            .allow
        )
        guard case .needsUserAction(let reason) = executor.memoryToolWritePolicy(
            input: input,
            isUserInitiated: false
        ) else {
            return XCTFail("Expected foreground approval requirement")
        }
        XCTAssertTrue(reason.contains("foreground approval"))
        XCTAssertEqual(executor.memoryToolWritePolicy(input: input, isUserInitiated: true), .allow)

        let capability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.memory_write" }
        )
        permissionStore.setPolicy(.disabled, for: capability)
        guard case .denied(let disabledReason) = executor.memoryToolWritePolicy(
            input: input,
            isUserInitiated: true
        ) else {
            return XCTFail("Expected disabled policy to deny")
        }
        XCTAssertTrue(disabledReason.contains("disabled"))
    }

    func testWorkspaceReadRequiresApprovalAndReturnsImportedText() async throws {
        let workspaceStore = makeWorkspaceStore()
        let file = try makeTempFile(text: "# Workspace\nReadable file context.", extension: "md")
        let record = try await workspaceStore.importFile(url: file, source: "test")
        let executor = makeExecutor(workspaceStore: workspaceStore)

        let blocked = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_file_read",
                operation: #"{"file_id":"\#(record.id)"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction(let reason) = blocked else {
            return XCTFail("Expected approval requirement, got \(blocked)")
        }
        XCTAssertTrue(reason.contains("Workspace reads"))

        let allowed = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_file_read",
                operation: #"{"path":"/workspace/\#(record.workspacePath)"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )
        guard case .workspaceResult(let text) = allowed else {
            return XCTFail("Expected Workspace result, got \(allowed)")
        }
        let object = try jsonObject(text)
        XCTAssertEqual(object["ok"] as? Bool, true)
        XCTAssertTrue((object["text"] as? String)?.contains("Readable file context") == true)
    }

    func testWorkspaceWriteRequiresApprovalAndRejectsTraversal() async throws {
        let workspaceStore = makeWorkspaceStore()
        let executor = makeExecutor(workspaceStore: workspaceStore)
        let input = #"{"path":"/workspace/notes/summary.md","content":"hello workspace"}"#

        let blocked = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_file_write",
                operation: input,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction(let reason) = blocked else {
            return XCTFail("Expected write approval requirement, got \(blocked)")
        }
        XCTAssertTrue(reason.contains("writes"))

        let written = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_file_write",
                operation: input,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )
        guard case .workspaceResult(let writeText) = written else {
            return XCTFail("Expected write result, got \(written)")
        }
        XCTAssertEqual(try jsonObject(writeText)["ok"] as? Bool, true)

        let traversal = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_file_write",
                operation: #"{"path":"../secrets.txt","content":"nope"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )
        guard case .workspaceResult(let traversalText) = traversal else {
            return XCTFail("Expected traversal failure result, got \(traversal)")
        }
        let traversalObject = try jsonObject(traversalText)
        XCTAssertEqual(traversalObject["ok"] as? Bool, false)
        XCTAssertTrue((traversalObject["error"] as? String)?.contains("traversal") == true)
    }

    func testWorkspaceArtifactReadAndDeleteUseApproval() async throws {
        let workspaceStore = makeWorkspaceStore()
        let artifact = try workspaceStore.saveArtifact(
            title: "Report",
            content: "artifact body",
            type: .chat,
            sourceKind: "test"
        )
        let executor = makeExecutor(workspaceStore: workspaceStore)

        let blockedRead = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_artifact_read",
                operation: #"{"artifact_id":"\#(artifact.id)"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction = blockedRead else {
            return XCTFail("Expected read approval, got \(blockedRead)")
        }

        let read = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_artifact_read",
                operation: #"{"artifact_id":"\#(artifact.id)"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )
        guard case .workspaceResult(let readText) = read else {
            return XCTFail("Expected artifact read, got \(read)")
        }
        XCTAssertEqual(try jsonObject(readText)["content"] as? String, "artifact body")

        let blockedDelete = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_artifact_delete",
                operation: #"{"artifact_id":"\#(artifact.id)"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )
        guard case .needsUserAction = blockedDelete else {
            return XCTFail("Expected delete approval, got \(blockedDelete)")
        }

        let deleted = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "workspace_artifact_delete",
                operation: #"{"artifact_id":"\#(artifact.id)"}"#,
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )
        guard case .workspaceResult(let deleteText) = deleted else {
            return XCTFail("Expected artifact delete, got \(deleted)")
        }
        XCTAssertEqual(try jsonObject(deleteText)["deleted"] as? Bool, true)
        XCTAssertThrowsError(try workspaceStore.artifactContent(id: artifact.id))
    }

    private func makeExecutor(
        permissionStore: IOSPermissionStore? = nil,
        documentStore: DocumentAccessStore = DocumentAccessStore(),
        workspaceStore: IOSWorkspaceStore = IOSWorkspaceStore(baseDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)),
        webMountController: IOSWebMountController? = nil
    ) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: permissionStore ?? IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore,
            workspaceStore: workspaceStore,
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

    private func makeTempFile(text: String, extension fileExtension: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(fileExtension)
        try Data(text.utf8).write(to: url)
        return url
    }

    private func makeWorkspaceStore() -> IOSWorkspaceStore {
        IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
        )
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
            canGoBack: openedURLs.count > 1,
            canGoForward: false,
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

    func interact(method: String, selector: String?, text: String?, options: [String: Any]) async throws -> [String: Any] {
        ["ok": true, "method": method, "found": true, "message": "mock interaction"]
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
