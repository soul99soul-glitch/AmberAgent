import XCTest
@testable import iosApp

@MainActor
final class IOSSharedSettingsStoreSkillWriteBackTests: XCTestCase {

    func testSetSkillEnabledPersistsAcrossRestart() {
        let suiteName = "SkillWriteBack-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)

        store1.setSkillEnabled(name: "research-helper", enabled: true)

        XCTAssertTrue(store1.currentAssistantEnabledSkillNames.contains("research-helper"))

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertTrue(
            store2.currentAssistantEnabledSkillNames.contains("research-helper"),
            "enabled skill names must persist in the full Settings snapshot"
        )
    }

    func testDisableSkillPersistsAcrossRestart() {
        let suiteName = "SkillDisable-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.setSkillEnabled(name: "research-helper", enabled: true)
        store1.setSkillEnabled(name: "research-helper", enabled: false)

        XCTAssertFalse(store1.currentAssistantEnabledSkillNames.contains("research-helper"))

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(
            store2.currentAssistantEnabledSkillNames.contains("research-helper"),
            "disabled skill must not resurrect after restart"
        )
    }

    func testRemoveSkillFromAllAssistantsClearsCurrentAssistant() {
        let suiteName = "SkillDelete-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.setSkillEnabled(name: "delete-me", enabled: true)

        store1.removeSkillFromAllAssistants(name: "delete-me")

        XCTAssertFalse(store1.currentAssistantEnabledSkillNames.contains("delete-me"))

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(
            store2.currentAssistantEnabledSkillNames.contains("delete-me"),
            "deleted skill enablement must not resurrect after restart"
        )
    }

    func testMiniAppHostAccessPersistsAcrossRestart() {
        let suiteName = "MiniAppHostAccess-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)

        store1.setMiniAppHostContextEnabled(true)
        store1.setMiniAppHostWriteEnabled(true)

        XCTAssertTrue(store1.agentRuntime.miniApp.hostContextEnabled)
        XCTAssertTrue(store1.agentRuntime.miniApp.hostWriteEnabled)

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertTrue(
            store2.agentRuntime.miniApp.hostContextEnabled,
            "MiniApp hostContextEnabled must persist in the full Settings snapshot"
        )
        XCTAssertTrue(
            store2.agentRuntime.miniApp.hostWriteEnabled,
            "MiniApp hostWriteEnabled must persist in the full Settings snapshot"
        )
    }

    private func makeIsolatedStore(suiteName: String = "SkillWriteBack-\(UUID().uuidString)") -> IOSSharedSettingsStore {
        IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: suiteName)!)
    }
}
