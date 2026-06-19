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

    func testStorageRequiresGrantThenPersists() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["storage"]))
        let runtime = IOSMiniAppBridgeRuntime(appId: app.id, repository: repo)

        let denied = await runtime.dispatch(method: "storage.set", params: ["key": "a", "value": "b"])
        XCTAssertEqual(denied.errorMessage, "Permission 'storage' has no grant decision.")

        try repo.setGrant(appId: app.id, permission: "storage", decision: .allow)
        let saved = await runtime.dispatch(method: "storage.set", params: ["key": "a", "value": "b"])
        XCTAssertEqual(saved, .success(.bool(true)))

        let loaded = await runtime.dispatch(method: "storage.get", params: ["key": "a"])
        XCTAssertEqual(loaded, .success(.string("b")))

        try repo.setGrant(appId: app.id, permission: "storage", decision: .deny)
        let rejected = await runtime.dispatch(method: "storage.get", params: ["key": "a"])
        XCTAssertEqual(rejected.errorMessage, "Permission 'storage' was denied.")
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

    func testAIGenerateChecksPolicyKeyThenCallsHandler() async throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["ai.generate"]))
        try repo.setGrant(appId: app.id, permission: "ai.generate", decision: .allow)
        var callCount = 0
        let noKeyRuntime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: true),
            apiKeyProvider: { "" },
            aiGenerateHandler: { _ in
                callCount += 1
                return .object(["text": .string("should not run")])
            }
        )

        let noKey = await noKeyRuntime.dispatch(method: "ai.generate", params: ["prompt": "hi"])
        XCTAssertEqual(noKey.errorMessage, "Amber.ai is not available because no API key is configured.")
        XCTAssertEqual(callCount, 0)

        let disabledRuntime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: false),
            apiKeyProvider: { "sk-test" },
            aiGenerateHandler: { _ in
                callCount += 1
                return .object(["text": .string("should not run")])
            }
        )

        let disabled = await disabledRuntime.dispatch(method: "ai.generate", params: ["prompt": "hi"])
        XCTAssertEqual(disabled.errorMessage, "Permission 'ai.generate' is disabled in MiniApp settings.")
        XCTAssertEqual(callCount, 0)

        var captured: IOSMiniAppAIGenerateRequest?
        let runtime = IOSMiniAppBridgeRuntime(
            appId: app.id,
            repository: repo,
            policy: IOSMiniAppBridgePolicy(aiEnabled: true),
            apiKeyProvider: { "sk-test" },
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
        XCTAssertEqual(callCount, 1)
        XCTAssertEqual(captured?.prompt.count, 16_000)
        XCTAssertEqual(captured?.system.count, 2_000)
        XCTAssertEqual(captured?.maxOutputChars, 16_000)
        XCTAssertEqual(captured?.temperature, 2)
        XCTAssertEqual(repo.auditLogs(appId: app.id).first?.method, "ai.generate")
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
