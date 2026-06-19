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
