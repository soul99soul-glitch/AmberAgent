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

    func testSensitiveReusableCapabilitiesOfferRunScopedReuse() throws {
        let photos = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.photos.library_read" }
        )
        XCTAssertTrue(IOSPermissionStore.availablePolicies(for: photos).contains(.allowOncePerRun))
    }

    func testPolicyPersistsAndUnknownCapabilityIdsAreDropped() throws {
        let defaults = isolatedDefaults()
        let storageKey = "test.permissionPolicies"
        let fileCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" }
        )

        var raw = defaults.dictionary(forKey: storageKey) as? [String: String] ?? [:]
        raw["unknown.capability"] = IOSAgentPermissionPolicy.askEveryTime.rawValue
        defaults.set(raw, forKey: storageKey)

        let firstStore = IOSPermissionStore(userDefaults: defaults, storageKey: storageKey)
        firstStore.setPolicy(.allowOncePerRun, for: fileCapability)

        let secondStore = IOSPermissionStore(userDefaults: defaults, storageKey: storageKey)
        XCTAssertEqual(secondStore.policy(for: fileCapability), .allowOncePerRun)

        let persisted = try XCTUnwrap(defaults.dictionary(forKey: storageKey) as? [String: String])
        XCTAssertNil(persisted["unknown.capability"])
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
