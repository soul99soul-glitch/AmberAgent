import XCTest
@testable import iosApp

@MainActor
final class IOSSystemPermissionCoordinatorTests: XCTestCase {
    func testEntitlementCapabilityReportsRequiresEntitlement() async throws {
        let coordinator = IOSSystemPermissionCoordinator()
        let health = try capability("ios.health.read")

        let result = await coordinator.refreshStatus(for: health)

        XCTAssertEqual(result.status, .requiresEntitlement)
        XCTAssertTrue(result.message.contains("com.apple.developer.healthkit"))
    }

    func testExtensionOnlyCapabilityReportsRequiresExtensionTarget() async throws {
        let coordinator = IOSSystemPermissionCoordinator()
        let callDirectory = try capability("ios.call_directory")

        let result = await coordinator.refreshStatus(for: callDirectory)

        XCTAssertEqual(result.status, .requiresExtensionTarget)
        XCTAssertTrue(result.message.contains("Call Directory Extension"))
    }

    func testUnsupportedCapabilityReportsUnavailableOnDevice() async throws {
        let coordinator = IOSSystemPermissionCoordinator()
        let smsRead = try capability("android.sms.read")

        let result = await coordinator.refreshStatus(for: smsRead)

        XCTAssertEqual(result.status, .unavailableOnDevice)
    }

    func testHealthKitReadIsNotReportedAsPlainGranted() async throws {
        let coordinator = IOSSystemPermissionCoordinator()
        let health = try capability("ios.health.read")

        let result = await coordinator.refreshStatus(for: health)

        XCTAssertNotEqual(result.status, .authorized)
    }

    func testDirectPermissionWithUsageDescriptionDoesNotReportMissingInfoPlist() async throws {
        let coordinator = IOSSystemPermissionCoordinator()
        let camera = try capability("ios.camera.capture")

        let result = await coordinator.refreshStatus(for: camera)

        XCTAssertNotEqual(result.status, .missingUsageDescription)
    }

    func testConfiguredEntitlementsMatchSourceEntitlements() throws {
        let projectRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let entitlementsURL = projectRoot.appendingPathComponent("iosApp/AmberAgent.entitlements")
        let infoPlistURL = projectRoot.appendingPathComponent("iosApp/Info.plist")

        let entitlements = try plistDictionary(at: entitlementsURL)
        let infoPlist = try plistDictionary(at: infoPlistURL)
        let configured = try XCTUnwrap(
            infoPlist["AmberAgentConfiguredEntitlements"] as? [String],
            "AmberAgentConfiguredEntitlements must be an array of strings"
        )

        XCTAssertEqual(Set(entitlements.keys), Set(configured))
    }

    private func capability(_ id: String) throws -> IOSPlatformCapability {
        try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == id })
    }

    private func plistDictionary(at url: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any]
        )
    }
}
