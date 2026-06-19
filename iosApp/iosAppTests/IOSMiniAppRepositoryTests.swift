import XCTest
@testable import iosApp

@MainActor
final class IOSMiniAppRepositoryTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    func testSaveGeneratedPersistsAndReloads() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(title: "计时器", html: html("v1")))

        let reloaded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(reloaded.get(app.id)?.title, "计时器")
        XCTAssertEqual(reloaded.get(app.id)?.htmlContent, html("v1"))
        XCTAssertEqual(reloaded.versions(appId: app.id).count, 1)
    }

    func testInvalidHtmlIsRejectedBeforeWrite() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        XCTAssertThrowsError(try repo.saveGenerated(output(html: #"<!DOCTYPE html><html><script>eval("1")</script></html>"#)))
        XCTAssertTrue(repo.apps.isEmpty)
    }

    func testVersionHistoryAndRestore() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(title: "版本", html: html("v1")))

        let v2 = try XCTUnwrap(repo.saveNewVersion(appId: app.id, htmlContent: html("v2"), changeNote: "manual"))
        XCTAssertEqual(v2.version, 2)
        XCTAssertEqual(repo.versions(appId: app.id).map(\.versionNumber), [2, 1])

        let restored = try XCTUnwrap(repo.restoreVersion(appId: app.id, versionNumber: 1))
        XCTAssertEqual(restored.version, 3)
        XCTAssertEqual(restored.htmlContent, html("v1"))
        XCTAssertEqual(repo.versions(appId: app.id).map(\.versionNumber), [3, 2, 1])
    }

    func testRenamePinDeleteAndMarkRun() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(title: "旧名"))

        try repo.rename(id: app.id, title: "新名", description: "新描述")
        try repo.setPinned(id: app.id, pinned: true)
        try repo.markRun(id: app.id)
        try repo.markRun(id: app.id)

        let updated = try XCTUnwrap(repo.get(app.id))
        XCTAssertEqual(updated.title, "新名")
        XCTAssertEqual(updated.description, "新描述")
        XCTAssertTrue(updated.pinned)
        XCTAssertEqual(updated.runCount, 2)
        XCTAssertNotNil(updated.lastRunAt)

        try repo.delete(id: app.id)
        XCTAssertNil(repo.get(app.id))
        XCTAssertTrue(repo.versions(appId: app.id).isEmpty)
    }

    func testGrantSharedDataAuditAndBoardSummaryPersist() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(permissions: ["storage", "sharedStore", "host.updateBoardSummary"]))

        try repo.setGrant(appId: app.id, permission: "storage", decision: .allow)
        try repo.setGrant(appId: app.id, permission: "sharedStore", decision: .deny)
        try repo.sharedSet(appId: app.id, namespace: nil, key: "demo.key", value: .object(["ok": .bool(true)]))
        try repo.audit(appId: app.id, method: "sharedStore.set", permission: "sharedStore", summary: "write", payload: #"{"ok":true}"#)
        try repo.updateBoardSummary(id: app.id, summary: "board summary")

        let reloaded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(reloaded.grantDecision(appId: app.id, permission: "storage"), .allow)
        XCTAssertEqual(reloaded.grantDecision(appId: app.id, permission: "sharedStore"), .deny)
        XCTAssertEqual(try reloaded.sharedGet(appId: app.id, namespace: nil, key: "demo.key"), .object(["ok": .bool(true)]))
        XCTAssertEqual(reloaded.auditLogs(appId: app.id).first?.method, "sharedStore.set")
        XCTAssertEqual(reloaded.get(app.id)?.boardSummary, "board summary")
    }

    func testCorruptStoreDoesNotOverwriteExistingFile() throws {
        let root = tempRoot()
        let dir = root.appendingPathComponent("miniapps", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("miniapps.json")
        try Data("{not-json".utf8).write(to: file)

        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: true)
        XCTAssertNotNil(repo.storageError)
        XCTAssertTrue(repo.apps.isEmpty)
        XCTAssertThrowsError(try repo.saveGenerated(output()))
        XCTAssertEqual(String(decoding: try Data(contentsOf: file), as: UTF8.self), "{not-json")
    }

    func testRunnerInitialHtmlReadsByAppIdFromRepository() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(html: html("runner")))

        XCTAssertEqual(MiniAppRunnerView.initialHtml(appId: app.id, repository: repo), html("runner"))
        XCTAssertTrue(MiniAppRunnerView.initialHtml(appId: "missing", repository: repo).contains("MiniApp 未找到"))
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-miniapp-tests-\(UUID().uuidString)", isDirectory: true)
        tempDirs.append(url)
        return url
    }

    private func output(
        title: String = "测试",
        description: String = "测试描述",
        permissions: [String] = [],
        html htmlContent: String? = nil
    ) -> IOSMiniAppGeneratedOutput {
        IOSMiniAppGeneratedOutput(
            title: title,
            description: description,
            icon: "🧪",
            category: "tool",
            permissions: permissions,
            html: htmlContent ?? html("ok")
        )
    }

    private func html(_ body: String) -> String {
        "<!DOCTYPE html><html><body><h1>\(body)</h1></body></html>"
    }
}
