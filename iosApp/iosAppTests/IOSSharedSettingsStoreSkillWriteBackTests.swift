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

    func testMiniAppRuntimeOptionsPersistAcrossRestart() {
        let suiteName = "MiniAppRuntimeOptions-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)

        store1.updateMiniAppRuntime { _ in
            MiniAppSettingPatch(
                networkEnabled: false,
                searchEnabled: false,
                clipboardCopyEnabled: false,
                boardSummaryUpdateEnabled: false,
                hostContextEnabled: true,
                hostWriteEnabled: true,
                aiEnabled: false,
                sharedStoreEnabled: false,
                eventBusEnabled: false
            )
        }

        XCTAssertFalse(store1.agentRuntime.miniApp.networkEnabled)
        XCTAssertFalse(store1.agentRuntime.miniApp.searchEnabled)
        XCTAssertFalse(store1.agentRuntime.miniApp.aiEnabled)
        XCTAssertFalse(store1.agentRuntime.miniApp.sharedStoreEnabled)
        XCTAssertFalse(store1.agentRuntime.miniApp.eventBusEnabled)
        XCTAssertFalse(store1.agentRuntime.miniApp.clipboardCopyEnabled)
        XCTAssertFalse(store1.agentRuntime.miniApp.boardSummaryUpdateEnabled)
        XCTAssertTrue(store1.agentRuntime.miniApp.hostContextEnabled)
        XCTAssertTrue(store1.agentRuntime.miniApp.hostWriteEnabled)

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(store2.agentRuntime.miniApp.networkEnabled)
        XCTAssertFalse(store2.agentRuntime.miniApp.searchEnabled)
        XCTAssertFalse(store2.agentRuntime.miniApp.aiEnabled)
        XCTAssertFalse(store2.agentRuntime.miniApp.sharedStoreEnabled)
        XCTAssertFalse(store2.agentRuntime.miniApp.eventBusEnabled)
        XCTAssertFalse(store2.agentRuntime.miniApp.clipboardCopyEnabled)
        XCTAssertFalse(store2.agentRuntime.miniApp.boardSummaryUpdateEnabled)
        XCTAssertTrue(store2.agentRuntime.miniApp.hostContextEnabled)
        XCTAssertTrue(store2.agentRuntime.miniApp.hostWriteEnabled)
    }

    func testCapabilityGatesAreAlwaysAvailable() {
        let store = makeIsolatedStore(suiteName: "CapabilityGatesDefault-\(UUID().uuidString)")

        for gate in IOSCapabilityGate.allCases {
            XCTAssertTrue(store.isCapabilityGateEnabled(gate), "\(gate.rawValue) should be always available")
        }
    }

    func testCapabilityGateWritesDoNotHideFeaturesAcrossRestart() {
        let suiteName = "CapabilityGatesRestart-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)

        store1.setCapabilityGate(.miniApps, enabled: false)
        store1.setCapabilityGate(.mcp, enabled: true)
        store1.setCapabilityGate(.remoteRuntime, enabled: false)

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertTrue(store2.isCapabilityGateEnabled(.miniApps))
        XCTAssertTrue(store2.isCapabilityGateEnabled(.mcp))
        XCTAssertTrue(store2.isCapabilityGateEnabled(.remoteRuntime))
        XCTAssertTrue(store2.isCapabilityGateEnabled(.modelCouncil))
    }

    private func makeIsolatedStore(suiteName: String = "SkillWriteBack-\(UUID().uuidString)") -> IOSSharedSettingsStore {
        IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: suiteName)!)
    }
}
