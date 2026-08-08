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

    func testRestoreVersionRestoresHistoricalMetadataSnapshot() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let original = try repo.saveGenerated(output(title: "旧标题", description: "旧描述", html: html("v1")))
        _ = try XCTUnwrap(repo.saveRevision(
            appId: original.id,
            output: IOSMiniAppGeneratedOutput(
                title: "新标题",
                description: "新描述",
                icon: "🆕",
                category: "info",
                permissions: ["toast"],
                html: html("v2")
            ),
            expectedBaseVersion: 1
        ))

        let restored = try XCTUnwrap(repo.restoreVersion(appId: original.id, versionNumber: 1))

        XCTAssertEqual(restored.title, "旧标题")
        XCTAssertEqual(restored.description, "旧描述")
        XCTAssertEqual(restored.iconEmoji, "🧪")
        XCTAssertEqual(restored.category, "tool")
        XCTAssertEqual(restored.permissions, [])
        XCTAssertEqual(restored.htmlContent, html("v1"))
    }

    func testGeneratedMutationRollbackRemovesPersistedAppAndVersion() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let mutation = try repo.saveGeneratedMutation(output(title: "临时应用"))

        XCTAssertNotNil(repo.get(mutation.record.id))
        XCTAssertTrue(repo.hasPendingConversationMutations)
        XCTAssertTrue(try repo.rollback(mutation))
        XCTAssertFalse(repo.hasPendingConversationMutations)

        let reloaded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertNil(reloaded.get(mutation.record.id))
        XCTAssertTrue(reloaded.versions(appId: mutation.record.id).isEmpty)
    }

    func testPendingGeneratedMutationRollsBackAfterRelaunchWithoutConversationReference() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let mutation = try repo.saveGeneratedMutation(output(title: "强杀窗口"))

        let relaunched = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertTrue(relaunched.hasPendingConversationMutations)
        try relaunched.reconcilePendingConversationMutations(referenced: [])

        let recovered = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertNil(recovered.get(mutation.record.id))
        XCTAssertTrue(recovered.versions(appId: mutation.record.id).isEmpty)
        XCTAssertFalse(recovered.hasPendingConversationMutations)
    }

    func testPendingGeneratedMutationCommitsAfterRelaunchWithExactConversationReference() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let mutation = try repo.saveGeneratedMutation(output(title: "已落聊天"))

        let relaunched = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        try relaunched.reconcilePendingConversationMutations(referenced: [
            IOSMiniAppConversationReference(record: mutation.record),
        ])

        let recovered = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(recovered.get(mutation.record.id), mutation.record)
        XCTAssertFalse(recovered.hasPendingConversationMutations)
    }

    func testReconciliationOnlyConsumesMutationsPresentAtScanStart() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let first = try repo.saveGeneratedMutation(output(title: "扫描前"))
        let scanSnapshot = repo.pendingConversationMutationIds()
        let second = try repo.saveGeneratedMutation(output(title: "扫描中"))

        try repo.reconcilePendingConversationMutations(
            referenced: [IOSMiniAppConversationReference(record: first.record)],
            mutationIds: scanSnapshot
        )

        XCTAssertEqual(repo.get(first.record.id), first.record)
        XCTAssertEqual(repo.get(second.record.id), second.record)
        XCTAssertTrue(repo.hasPendingConversationMutations)
        XCTAssertEqual(repo.pendingConversationMutationIds().count, 1)
    }

    func testPendingRevisionDoesNotTreatOlderCardAsPersisted() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let original = try repo.saveGenerated(output(title: "初版", html: html("v1")))
        let mutation = try XCTUnwrap(repo.saveRevisionMutation(
            appId: original.id,
            output: output(title: "二版", html: html("v2")),
            expectedBaseVersion: 1
        ))

        let relaunched = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        try relaunched.reconcilePendingConversationMutations(referenced: [
            IOSMiniAppConversationReference(record: original),
        ])

        let recovered = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(recovered.get(original.id), original)
        XCTAssertEqual(recovered.versions(appId: original.id).map(\.versionNumber), [1])
        XCTAssertNotEqual(mutation.record.htmlHash, original.htmlHash)
    }

    func testPendingRevisionRollbackRestoresVersionPrunedWhileStaging() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let original = try repo.saveGenerated(output(title: "版本 1", html: html("v1")))
        for version in 2...30 {
            _ = try repo.saveNewVersion(appId: original.id, htmlContent: html("v\(version)"))
        }
        let beforeMutation = try XCTUnwrap(repo.get(original.id))
        _ = try XCTUnwrap(repo.saveRevisionMutation(
            appId: original.id,
            output: output(title: "版本 31", html: html("v31")),
            expectedBaseVersion: 30
        ))
        XCTAssertEqual(repo.versions(appId: original.id).map(\.versionNumber), Array((2...31).reversed()))

        let relaunched = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        try relaunched.reconcilePendingConversationMutations(referenced: [])

        let recovered = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(recovered.get(original.id), beforeMutation)
        XCTAssertEqual(recovered.versions(appId: original.id).map(\.versionNumber), Array((1...30).reversed()))
    }

    func testCommitClearsPendingMutationWithoutChangingMiniApp() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let mutation = try repo.saveGeneratedMutation(output(title: "正常完成"))

        XCTAssertTrue(try repo.commit(mutation))

        let reloaded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(reloaded.get(mutation.record.id), mutation.record)
        XCTAssertFalse(reloaded.hasPendingConversationMutations)
    }

    func testRevisionMutationRollbackRestoresPreviousRecordAndVersions() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let original = try repo.saveGenerated(output(title: "原版本", html: html("v1")))
        let mutation = try XCTUnwrap(repo.saveRevisionMutation(
            appId: original.id,
            output: output(title: "新版本", html: html("v2")),
            expectedBaseVersion: 1
        ))

        XCTAssertEqual(mutation.record.version, 2)
        XCTAssertTrue(try repo.rollback(mutation))

        let reloaded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(reloaded.get(original.id), original)
        XCTAssertEqual(reloaded.versions(appId: original.id).map(\.versionNumber), [1])
    }

    func testRevisionRollbackRestoresPermissionAndSharedStateSnapshot() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let original = try repo.saveGenerated(output(title: "原版本", permissions: ["storage", "sharedStore"]))
        try repo.setGrant(appId: original.id, permission: "storage", decision: .allow)
        try repo.sharedSet(appId: original.id, namespace: nil, key: "value", value: .string("before"))
        try repo.audit(appId: original.id, method: "before", permission: "storage", summary: "before", payload: "before")
        let mutation = try XCTUnwrap(repo.saveRevisionMutation(
            appId: original.id,
            output: output(title: "新版本", permissions: ["storage", "sharedStore"], html: html("v2")),
            expectedBaseVersion: 1
        ))
        try repo.setGrant(appId: original.id, permission: "storage", decision: .deny)
        try repo.sharedSet(appId: original.id, namespace: nil, key: "value", value: .string("after"))
        try repo.audit(appId: original.id, method: "after", permission: "storage", summary: "after", payload: "after")

        XCTAssertTrue(try repo.rollback(mutation))

        XCTAssertEqual(repo.grantDecision(appId: original.id, permission: "storage"), .allow)
        XCTAssertEqual(try repo.sharedGet(appId: original.id, namespace: nil, key: "value"), .string("before"))
        XCTAssertEqual(repo.auditLogs(appId: original.id).map(\.method), ["before"])
    }

    func testRollbackDoesNotOverwriteLaterMiniAppChanges() throws {
        let root = tempRoot()
        let repo = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let mutation = try repo.saveGeneratedMutation(output(title: "初版"))
        try repo.rename(id: mutation.record.id, title: "用户已改名", description: "保留后续修改")

        XCTAssertFalse(try repo.rollback(mutation))
        XCTAssertEqual(repo.get(mutation.record.id)?.title, "用户已改名")

        let reloaded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        XCTAssertEqual(reloaded.get(mutation.record.id)?.title, "用户已改名")
        XCTAssertFalse(reloaded.hasPendingConversationMutations)
    }

    func testRollbackIgnoresUnrelatedMiniAppChanges() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let mutation = try repo.saveGeneratedMutation(output(title: "待回滚"))
        let unrelated = try repo.saveGenerated(output(title: "另一个应用"))

        XCTAssertTrue(try repo.rollback(mutation))
        XCTAssertNil(repo.get(mutation.record.id))
        XCTAssertEqual(repo.get(unrelated.id), unrelated)
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

    func testFailedPersistenceDoesNotLeakUncommittedStateInMemory() throws {
        let root = tempRoot()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let blocker = root.appendingPathComponent("not-a-directory")
        try Data("blocker".utf8).write(to: blocker)
        let repo = IOSMiniAppRepository(baseDirectory: blocker, seedOnMissingStore: false)

        XCTAssertThrowsError(try repo.saveGenerated(output(title: "不应泄漏")))
        XCTAssertTrue(repo.apps.isEmpty)
        XCTAssertTrue(repo.list().isEmpty)
        XCTAssertNotNil(repo.storageError)
    }

    func testProductionRepositoryRemovesUntouchedLegacySeedSample() throws {
        let root = tempRoot()
        let seeded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: true)
        XCTAssertNotNil(seeded.get(IOSMiniAppFixtures.sampleId))

        let production = IOSMiniAppRepository(baseDirectory: root)
        XCTAssertNil(production.get(IOSMiniAppFixtures.sampleId))

        let reloaded = IOSMiniAppRepository(baseDirectory: root)
        XCTAssertNil(reloaded.get(IOSMiniAppFixtures.sampleId))
    }

    func testProductionRepositoryPreservesEditedLegacySeedSample() throws {
        let root = tempRoot()
        let seeded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: true)
        try seeded.rename(id: IOSMiniAppFixtures.sampleId, title: "我的小应用", description: "用户编辑过")

        let production = IOSMiniAppRepository(baseDirectory: root)
        XCTAssertEqual(production.get(IOSMiniAppFixtures.sampleId)?.title, "我的小应用")
    }

    func testProductionRepositoryPreservesUsedLegacySeedSample() throws {
        let root = tempRoot()
        let seeded = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: true)
        try seeded.markRun(id: IOSMiniAppFixtures.sampleId)

        let production = IOSMiniAppRepository(baseDirectory: root)
        XCTAssertEqual(production.get(IOSMiniAppFixtures.sampleId)?.runCount, 1)
    }

    func testRunnerInitialHtmlReadsByAppIdFromRepository() throws {
        let repo = IOSMiniAppRepository(baseDirectory: tempRoot(), seedOnMissingStore: false)
        let app = try repo.saveGenerated(output(html: html("runner")))

        XCTAssertEqual(MiniAppRunnerView.initialHtml(appId: app.id, repository: repo), html("runner"))
        XCTAssertTrue(MiniAppRunnerView.initialHtml(appId: "missing", repository: repo).contains("小应用未找到"))
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
