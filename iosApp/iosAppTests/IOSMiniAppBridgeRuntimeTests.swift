import XCTest
@testable import iosApp

@MainActor
final class IOSMiniAppBridgeRuntimeTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    func testRunnerCSPBlocksDirectBrowserNetworkAndKeepsInlineAppCode() {
        let html = "<!doctype html><html><head></head><body><script>fetch('https://example.com')</script></body></html>"

        let sandboxed = IOSMiniAppHTMLSandbox.enforceBridgeOnlyNetwork(html)

        XCTAssertTrue(sandboxed.contains("connect-src 'none'"))
        XCTAssertTrue(sandboxed.contains("script-src 'unsafe-inline'"))
        XCTAssertTrue(sandboxed.contains("Content-Security-Policy"))
    }

    func testAppInfoWorksWithoutGrant() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: []))
        let runtime = IOSMiniAppBridgeRuntime(appId: app.id, repository: repo)

        let result = await runtime.dispatch(method: "app.info", params: [:])
        guard case .success(let value) = result,
              case .object(let object) = value else {
            return XCTFail("expected app.info success")
        }
        XCTAssertEqual(object["appId"], .string(app.id))
        XCTAssertEqual(object["version"], .number(1))
    }

    func testStorageFirstUseGrantPromptThenPersists() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["storage"]))
        var prompts = 0
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            grantHandler: { permission in
                prompts += 1
                XCTAssertEqual(permission, .storage)
                return true
            }
        )

        let saved = await runtime.dispatch(method: "storage.set", params: ["key": "a", "value": "b"])
        XCTAssertEqual(saved, .success(.bool(true)))
        XCTAssertEqual(prompts, 1)
        XCTAssertEqual(repo.grantDecision(appId: app.id, permission: "storage"), .allow)

        let loaded = await runtime.dispatch(method: "storage.get", params: ["key": "a"])
        XCTAssertEqual(loaded, .success(.string("b")))
        XCTAssertEqual(prompts, 1, "cached grant should not prompt again")

        try repo.setGrant(appId: app.id, permission: "storage", decision: .deny)
        let rejected = await runtime.dispatch(method: "storage.get", params: ["key": "a"])
        XCTAssertEqual(rejected.errorMessage, "Permission 'storage' was denied.")
    }

    func testStorageWithoutGrantHandlerStillFailsClosed() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["storage"]))
        let runtime = IOSMiniAppBridgeRuntime(appId: app.id, repository: repo)

        let denied = await runtime.dispatch(method: "storage.set", params: ["key": "a", "value": "b"])
        XCTAssertEqual(denied.errorMessage, "Permission 'storage' has no grant decision.")
    }

    func testSearchRespectsPolicyEvenWhenGranted() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["search"]))
        try repo.setGrant(appId: app.id, permission: "search", decision: .allow)
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(searchEnabled: false)
        )

        let result = await runtime.dispatch(method: "search", params: ["query": "AmberAgent"])
        XCTAssertEqual(result.errorMessage, "Permission 'search' is disabled in MiniApp settings.")
    }

    func testFetchDeniesPrivateHostsEvenWhenGranted() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["network"]))
        try repo.setGrant(appId: app.id, permission: "network", decision: .allow)
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(networkEnabled: true)
        )

        let result = await runtime.dispatch(method: "fetch", params: ["url": "https://127.0.0.1/admin"])

        XCTAssertEqual(result.errorMessage, "URL is not allowed: local, loopback, link-local, and private hosts are blocked")
    }

    func testAIGenerateLetsConfiguredProviderHandlerOwnAuthentication() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["ai.generate"]))
        try repo.setGrant(appId: app.id, permission: "ai.generate", decision: .allow)
        var callCount = 0
        let noLegacyKeyRuntime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: true),
            aiGenerateHandler: { _ in
                callCount += 1
                return .object(["text": .string("provider-owned auth")])
            }
        )

        let noLegacyKey = await noLegacyKeyRuntime.dispatch(method: "ai.generate", params: ["prompt": "hi"])
        XCTAssertEqual(noLegacyKey, .success(.object(["text": .string("provider-owned auth")])))
        XCTAssertEqual(callCount, 1)

        let disabledRuntime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: false),
            aiGenerateHandler: { _ in
                callCount += 1
                return .object(["text": .string("should not run")])
            }
        )

        let disabled = await disabledRuntime.dispatch(method: "ai.generate", params: ["prompt": "hi"])
        XCTAssertEqual(disabled.errorMessage, "Permission 'ai.generate' is disabled in MiniApp settings.")
        XCTAssertEqual(callCount, 1)

        var captured: IOSMiniAppAIGenerateRequest?
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: true),
            aiGenerateHandler: { request in
                captured = request
                callCount += 1
                return .object([
                    "text": .string("mock response"),
                    "model": .string("mock-model"),
                ])
            }
        )

        let result = await runtime.dispatch(
            method: "ai.generate",
            params: [
                "prompt": String(repeating: "p", count: 17_000),
                "system": String(repeating: "s", count: 2_500),
                "maxOutputChars": 20_000,
                "temperature": 9.0,
            ]
        )

        XCTAssertEqual(
            result,
            .success(.object([
                "text": .string("mock response"),
                "model": .string("mock-model"),
            ]))
        )
        XCTAssertEqual(callCount, 2)
        XCTAssertEqual(captured?.prompt.count, 16_000)
        XCTAssertEqual(captured?.system.count, 2_000)
        XCTAssertEqual(captured?.maxOutputChars, 16_000)
        XCTAssertEqual(captured?.temperature, 2)
        XCTAssertEqual(repo.auditLogs(appId: app.id).first?.method, "ai.generate")
    }

    func testFetchRejectsOversizedRequestBodyBeforeStartingTransport() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["network"]))
        try repo.setGrant(appId: app.id, permission: "network", decision: .allow)
        let transport = MiniAppFetchTransport()
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(networkEnabled: true),
            fetchTransport: transport
        )

        let result = await runtime.dispatch(
            method: "fetch",
            params: [
                "url": "https://example.com/upload",
                "method": "POST",
                "body": String(repeating: "x", count: 64 * 1_024 + 1),
            ]
        )

        XCTAssertEqual(result.errorMessage, "Amber.fetch request body is too large.")
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testHostMethodsCheckPolicyHandlerThenAudit() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: [
            "host.context",
            "host.sendToConversation",
            "host.createArtifact",
        ]))
        try repo.setGrant(appId: app.id, permission: "host.context", decision: .allow)
        try repo.setGrant(appId: app.id, permission: "host.sendToConversation", decision: .allow)
        try repo.setGrant(appId: app.id, permission: "host.createArtifact", decision: .allow)
        var callCount = 0

        let disabledRuntime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(hostContextEnabled: false, hostWriteEnabled: true),
            hostHandler: { _ in
                callCount += 1
                return .bool(true)
            }
        )
        let disabled = await disabledRuntime.dispatch(method: "host.getConversationContext", params: [:])
        XCTAssertEqual(disabled.errorMessage, "Permission 'host.context' is disabled in MiniApp settings.")
        XCTAssertEqual(callCount, 0)

        let noHandlerRuntime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(hostContextEnabled: true, hostWriteEnabled: true)
        )
        let noHandler = await noHandlerRuntime.dispatch(method: "host.getConversationContext", params: [:])
        XCTAssertEqual(noHandler.errorMessage, "MiniApp host confirmation is not available in this runner.")

        var captured: [IOSMiniAppHostRequest] = []
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(hostContextEnabled: true, hostWriteEnabled: true),
            hostHandler: { request in
                captured.append(request)
                switch request {
                case .getConversationContext:
                    return .object(["ok": .bool(true), "kind": .string("context")])
                case .sendToConversation(let request):
                    return .object(["accepted": .bool(true), "mode": .string(request.mode), "text": .string(request.text)])
                case .createArtifact(let request):
                    return .object(["accepted": .bool(true), "title": .string(request.title), "type": .string(request.type)])
                }
            }
        )

        let context = await runtime.dispatch(method: "host.getConversationContext", params: ["maxChars": 9_000])
        XCTAssertEqual(context, .success(.object(["ok": .bool(true), "kind": .string("context")])))

        let send = await runtime.dispatch(
            method: "host.sendToConversation",
            params: ["text": String(repeating: "x", count: 9_000), "mode": "send"]
        )
        guard case .success(.object(let sendObject)) = send else {
            return XCTFail("expected host.sendToConversation success")
        }
        XCTAssertEqual(sendObject["mode"], .string("draft"))
        XCTAssertEqual(sendObject["text"]?.stringValue?.count, 8_000)

        let artifact = await runtime.dispatch(
            method: "host.createArtifact",
            params: [
                "title": String(repeating: "t", count: 90),
                "content": String(repeating: "c", count: 13_000),
                "type": "note",
            ]
        )
        guard case .success(.object(let artifactObject)) = artifact else {
            return XCTFail("expected host.createArtifact success")
        }
        XCTAssertEqual(artifactObject["title"]?.stringValue?.count, 80)
        XCTAssertEqual(artifactObject["type"], .string("note"))

        XCTAssertEqual(captured.count, 3)
        if case .getConversationContext(let request) = captured[0] {
            XCTAssertEqual(request.maxChars, 8_000)
        } else {
            XCTFail("expected context request")
        }
        if case .createArtifact(let request) = captured[2] {
            XCTAssertEqual(request.content.count, 12_000)
        } else {
            XCTFail("expected artifact request")
        }
        let methods = Set(repo.auditLogs(appId: app.id).map(\.method))
        XCTAssertTrue(methods.contains("host.getConversationContext"))
        XCTAssertTrue(methods.contains("host.sendToConversation"))
        XCTAssertTrue(methods.contains("host.createArtifact"))
    }

    func testHostConfirmationCloseRejectsPendingContinuationExactlyOnce() async throws {
        let owner = MiniAppHostConfirmationOwner()
        let request = IOSMiniAppHostRequest.getConversationContext(.init(maxChars: 1_000))
        let resultTask = Task { @MainActor in
            try await owner.request(appTitle: "Close Test", request: request)
        }
        while !owner.hasPendingRequest {
            await Task.yield()
        }

        XCTAssertTrue(owner.close())
        XCTAssertFalse(owner.close())
        let allowed = try await resultTask.value
        XCTAssertFalse(allowed)
        XCTAssertFalse(owner.hasPendingRequest)
        XCTAssertNil(owner.pendingPrompt)
    }

    func testPermissionGrantCoalescesSamePermissionAndSerializesDifferentOnes() async throws {
        let owner = MiniAppHostConfirmationOwner()
        async let first = owner.requestPermission(appTitle: "App", permission: .storage)
        while owner.pendingPrompt == nil {
            await Task.yield()
        }
        async let secondSame = owner.requestPermission(appTitle: "App", permission: .storage)
        async let thirdDifferent = owner.requestPermission(appTitle: "App", permission: .network)

        // Still only the first storage prompt is visible.
        if case .grant(let prompt) = owner.pendingPrompt {
            XCTAssertEqual(prompt.permission, .storage)
        } else {
            return XCTFail("expected storage grant prompt")
        }

        XCTAssertTrue(owner.resolve(allow: true))
        let sameResult = try await secondSame
        let firstResult = try await first
        XCTAssertTrue(sameResult)
        XCTAssertTrue(firstResult)

        while owner.pendingPrompt == nil {
            await Task.yield()
        }
        if case .grant(let prompt) = owner.pendingPrompt {
            XCTAssertEqual(prompt.permission, .network)
        } else {
            return XCTFail("expected network grant prompt after storage resolved")
        }
        XCTAssertTrue(owner.resolve(allow: false))
        let thirdResult = try await thirdDifferent
        XCTAssertFalse(thirdResult)
        XCTAssertNil(owner.pendingPrompt)
    }

    func testRuntimeCloseCancelsInFlightBridgeTaskExactlyOnce() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["ai.generate"]))
        try repo.setGrant(appId: app.id, permission: "ai.generate", decision: .allow)
        let started = expectation(description: "AI handler started")
        let cancellationProbe = MiniAppCancellationProbe()
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: true),
            aiGenerateHandler: { _ in
                started.fulfill()
                return try await withTaskCancellationHandler {
                    try await Task.sleep(nanoseconds: 10_000_000_000)
                    return .object(["text": .string("late")])
                } onCancel: {
                    cancellationProbe.increment()
                }
            }
        )
        let dispatchTask = Task { @MainActor in
            await runtime.dispatch(method: "ai.generate", params: ["prompt": "wait"])
        }
        await fulfillment(of: [started], timeout: 1)

        runtime.close()
        runtime.close()

        let result = await dispatchTask.value
        XCTAssertEqual(result.errorMessage, "MiniApp bridge request was cancelled.")
        XCTAssertEqual(cancellationProbe.count, 1)
    }

    func testSharedStoreRejectsCrossAppNamespace() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["sharedStore"]))
        try repo.setGrant(appId: app.id, permission: "sharedStore", decision: .allow)
        let runtime = IOSMiniAppBridgeRuntime(appId: app.id, repository: repo)

        let result = await runtime.dispatch(
            method: "sharedStore.set",
            params: ["namespace": "other", "key": "k", "value": ["ok": true]]
        )
        XCTAssertEqual(result.errorMessage, "Cross-app SharedStore namespaces are not granted yet.")
    }

    func testHostUpdateBoardSummaryAndAudit() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["host.updateBoardSummary"]))
        try repo.setGrant(appId: app.id, permission: "host.updateBoardSummary", decision: .allow)
        let runtime = IOSMiniAppBridgeRuntime(appId: app.id, repository: repo)

        let result = await runtime.dispatch(method: "host.updateBoardSummary", params: ["summary": "今日完成"])
        XCTAssertEqual(result, .success(.bool(true)))
        XCTAssertEqual(repo.get(app.id)?.boardSummary, "今日完成")
        XCTAssertEqual(repo.auditLogs(appId: app.id).first?.method, "host.updateBoardSummary")
    }

    func testEventBusPublishesToSubscription() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["eventBus"]))
        try repo.setGrant(appId: app.id, permission: "eventBus", decision: .allow)
        var emitted: IOSMiniAppJSONValue?
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            eventEmitter: { _, _, payload in emitted = payload }
        )

        let subscribed = await runtime.dispatch(method: "eventBus.subscribe", params: ["topic": "demo"])
        guard case .success(let value) = subscribed,
              case .object(let object) = value,
              object["subscriptionId"]?.stringValue != nil else {
            return XCTFail("expected subscription id")
        }

        let published = await runtime.dispatch(method: "eventBus.publish", params: ["topic": "demo", "payload": ["ok": true]])
        XCTAssertEqual(published, .success(.bool(true)))
        XCTAssertEqual(emitted, .object(["ok": .bool(true)]))
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-miniapp-bridge-tests-\(UUID().uuidString)", isDirectory: true)
        tempDirs.append(url)
        return url
    }

    private func output(permissions: [String]) -> IOSMiniAppGeneratedOutput {
        IOSMiniAppGeneratedOutput(
            title: "桥接",
            description: "桥接测试",
            icon: nil,
            category: "tool",
            permissions: permissions,
            html: "<!DOCTYPE html><html><body><h1>Bridge</h1></body></html>"
        )
    }
}

@MainActor
private final class MiniAppFetchTransport: IOSSearchHTTPTransport {
    private(set) var requests: [URLRequest] = []

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        return (
            HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "text/plain"]
            )!,
            Data("ok".utf8)
        )
    }
}

private final class MiniAppCancellationProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    var count: Int {
        lock.withLock { value }
    }

    func increment() {
        lock.withLock { value += 1 }
    }
}
