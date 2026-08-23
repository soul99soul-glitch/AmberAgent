import XCTest
@testable import iosApp

@MainActor
final class IOSPermissionStoreTests: XCTestCase {
    func testOnlyUnsupportedCapabilitiesAreForcedDisabled() throws {
        let store = IOSPermissionStore(userDefaults: isolatedDefaults())
        let unsupported = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "android.sms.read" }
        )
        let entitlement = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.health.read" }
        )
        let direct = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.location.when_in_use" }
        )

        store.setPolicy(.askEveryTime, for: unsupported)
        XCTAssertEqual(store.policy(for: unsupported), .disabled)
        XCTAssertEqual(store.availablePolicies(for: unsupported), [.disabled])

        XCTAssertTrue(store.availablePolicies(for: entitlement).contains(.askEveryTime))
        XCTAssertTrue(store.availablePolicies(for: direct).contains(.askEveryTime))
    }

    func testHighAndFreshPresenceCapabilitiesDoNotOfferRunScopedReuse() throws {
        let camera = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.camera.capture" }
        )
        XCTAssertFalse(IOSPermissionStore.availablePolicies(for: camera).contains(.allowOncePerRun))
    }

    func testRunScopedReuseIsNotOfferedUntilRunScopeExists() throws {
        let photos = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.photos.library_read" }
        )
        XCTAssertFalse(IOSPermissionStore.availablePolicies(for: photos).contains(.allowOncePerRun))
    }

    func testPolicyPersistsAndUnsafeRunScopedPolicyIsNormalized() throws {
        let defaults = isolatedDefaults()
        let storageKey = "test.permissionPolicies"
        let fileCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" }
        )

        var raw = defaults.dictionary(forKey: storageKey) as? [String: String] ?? [:]
        raw["unknown.capability"] = IOSAgentPermissionPolicy.askEveryTime.rawValue
        raw[fileCapability.id] = IOSAgentPermissionPolicy.allowOncePerRun.rawValue
        defaults.set(raw, forKey: storageKey)

        let firstStore = IOSPermissionStore(userDefaults: defaults, storageKey: storageKey)
        XCTAssertEqual(firstStore.policy(for: fileCapability), .askEveryTime)

        firstStore.setPolicy(.allowOncePerRun, for: fileCapability)

        let secondStore = IOSPermissionStore(userDefaults: defaults, storageKey: storageKey)
        XCTAssertEqual(secondStore.policy(for: fileCapability), .askEveryTime)

        let persisted = try XCTUnwrap(defaults.dictionary(forKey: storageKey) as? [String: String])
        XCTAssertNil(persisted["unknown.capability"])
        XCTAssertEqual(persisted[fileCapability.id], IOSAgentPermissionPolicy.askEveryTime.rawValue)
    }

    func testApprovalRecordsPersistAndRedactSensitiveValues() throws {
        let defaults = isolatedDefaults()
        let store = IOSPermissionStore(userDefaults: defaults, taskStore: nil)

        store.recordApproval(
            capabilityId: "ios.remote.command",
            toolName: "remote_command_run",
            action: .allowed,
            reason: "password=secret token=abc123",
            runId: "run-1",
            payloadDigest: "payload"
        )

        let reloaded = IOSPermissionStore(userDefaults: defaults, taskStore: nil)
        let record = try XCTUnwrap(reloaded.latestApproval(
            for: try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.remote.command" })
        ))

        XCTAssertEqual(record.action, .allowed)
        XCTAssertEqual(record.toolName, "remote_command_run")
        XCTAssertFalse(record.reason.contains("secret"))
        XCTAssertFalse(record.reason.contains("abc123"))
    }

    func testHighRiskAdvancedCapabilitiesDoNotOfferRunScopedReuse() throws {
        let mcp = try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.mcp.tool_call" })
        let remote = try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.remote.command" })

        XCTAssertEqual(mcp.risk, .high)
        XCTAssertEqual(remote.risk, .high)
        XCTAssertFalse(IOSPermissionStore.availablePolicies(for: mcp).contains(.allowOncePerRun))
        XCTAssertFalse(IOSPermissionStore.availablePolicies(for: remote).contains(.allowOncePerRun))
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
