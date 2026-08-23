import XCTest
@preconcurrency import Shared
@testable import iosApp

/// [Slice 4] Verifies the subAgent override write-back bridge.
///
/// Acceptance covered:
///  - "新增 subAgent override（systemPrompt）→重启→还在"
///  - "删除 subAgent override→重启→真没了"
///
/// The override lives at `snapshot.agentRuntime.subAgent.overrides[roleId]`
/// (subAgent is a field of AgentRuntimeSetting, merged via
/// IosSettingsMutations.putSubAgentOverride/removeSubAgentOverride).
///
/// Uses isolated UserDefaults suites so tests don't touch the app's real
/// settings or bleed into each other.
@MainActor
final class IOSSharedSettingsStoreSubAgentOverrideTests: XCTestCase {

    private let testRoleId = "historian"

    func testAddSubAgentOverrideMergesIntoSnapshot() {
        let store = makeIsolatedStore()
        let baseline = store.snapshot.agentRuntime.subAgent.overrides.count

        store.addSubAgentOverride(roleId: testRoleId, systemPrompt: "你是历史学家，专做会话回顾")

        let after = store.snapshot.agentRuntime.subAgent.overrides
        XCTAssertEqual(after.count, baseline + 1, "addSubAgentOverride must add a real override to the snapshot")
        XCTAssertEqual(after[testRoleId]?.systemPrompt, "你是历史学家，专做会话回顾")
    }

    func testAddedSubAgentOverrideSurvivesRestart() {
        let suiteName = "Slice4-Sub-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addSubAgentOverride(roleId: testRoleId, systemPrompt: "重启后应在")
        let countBeforeRestart = store1.snapshot.agentRuntime.subAgent.overrides.count

        let store2 = makeIsolatedStore(suiteName: suiteName)
        let afterRestart = store2.snapshot.agentRuntime.subAgent.overrides
        XCTAssertEqual(
            afterRestart.count,
            countBeforeRestart,
            "Added subAgent override must survive a fresh store init (app restart)"
        )
        XCTAssertEqual(afterRestart[testRoleId]?.systemPrompt, "重启后应在")
    }

    func testRemoveSubAgentOverrideDoesNotResurrectAfterRestart() {
        let suiteName = "Slice4-SubDel-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addSubAgentOverride(roleId: testRoleId, systemPrompt: "待删除")

        let mirrorCount = store1.savedSubAgentOverrides.count
        XCTAssertGreaterThan(mirrorCount, 0)

        store1.removeSubAgentOverride(at: mirrorCount - 1)
        XCTAssertNil(
            store1.snapshot.agentRuntime.subAgent.overrides[testRoleId],
            "removed override must be gone from the snapshot immediately"
        )

        // Restart: must not come back.
        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertNil(
            store2.snapshot.agentRuntime.subAgent.overrides[testRoleId],
            "Deleted subAgent override must not resurrect after restart"
        )
    }

    func testUpdateSubAgentOverrideReplacesExisting() {
        // Adding an override for an existing roleId should replace, not stack.
        let store = makeIsolatedStore()
        store.addSubAgentOverride(roleId: testRoleId, systemPrompt: "v1")
        store.addSubAgentOverride(roleId: testRoleId, systemPrompt: "v2")

        let override = store.snapshot.agentRuntime.subAgent.overrides[testRoleId]
        XCTAssertEqual(override?.systemPrompt, "v2", "second put for the same roleId must overwrite")
        XCTAssertEqual(
            store.savedSubAgentOverrides.filter { $0["roleId"] == testRoleId }.count,
            1,
            "the compatibility mirror must replace the same role instead of creating duplicate rows"
        )

        store.removeSubAgentOverride(at: 0)
        XCTAssertNil(store.snapshot.agentRuntime.subAgent.overrides[testRoleId])
        XCTAssertFalse(store.savedSubAgentOverrides.contains { $0["roleId"] == testRoleId })
    }

    // MARK: - helpers

    private func makeIsolatedStore(suiteName: String = "Slice4-SubAgent-\(UUID().uuidString)") -> IOSSharedSettingsStore {
        IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: suiteName)!)
    }
}
