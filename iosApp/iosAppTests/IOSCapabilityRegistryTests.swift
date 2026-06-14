import XCTest
@testable import iosApp

final class IOSCapabilityRegistryTests: XCTestCase {
    func testCapabilityIdsAreUnique() {
        let ids = IOSCapabilityRegistry.capabilities.map(\.id)
        XCTAssertEqual(Set(ids).count, ids.count)
    }

    func testFullCatalogContainsRepresentativeIOSCapabilities() {
        let ids = Set(IOSCapabilityRegistry.capabilities.map(\.id))
        [
            "ios.location.when_in_use",
            "ios.location.always",
            "ios.location.temporary_precise",
            "ios.camera.capture",
            "ios.microphone.record",
            "ios.speech.recognition",
            "ios.speech.personal_voice",
            "ios.photos.library_read",
            "ios.photos.limited_library_management",
            "ios.files.external_storage_capture",
            "ios.image_capture.contents",
            "ios.image_capture.control",
            "ios.contacts.full",
            "ios.contacts.limited",
            "ios.calendar.full",
            "ios.reminders.full",
            "ios.health.read",
            "ios.motion.fitness",
            "ios.motion.fall_detection",
            "ios.workoutkit.scheduler",
            "ios.finance.financekit",
            "ios.alarmkit.alarms",
            "ios.bluetooth.ble",
            "ios.network.local",
            "ios.network.wifi_sharing",
            "ios.nfc.reader",
            "ios.focus.status",
            "ios.authentication.passkeys_platform_credentials",
            "ios.notifications.live_activities",
            "ios.messages.critical_sms",
            "ios.journaling_suggestions.picker",
            "ios.screen_time.family_controls",
            "ios.replaykit.record",
            "ios.wallet.pass_library"
        ].forEach { id in
            XCTAssertTrue(ids.contains(id), "Missing \(id)")
        }
    }

    func testEveryRequestableCapabilityHasRequestMetadata() {
        for capability in IOSCapabilityRegistry.requestableCapabilities {
            XCTAssertFalse(capability.summary.isEmpty, capability.id)
            XCTAssertFalse(capability.requestEntryPoint.isEmpty, capability.id)
            XCTAssertNotEqual(capability.requestKind, .unsupported, capability.id)
        }
    }

    func testFilePickIsOnlyForegroundUIAction() {
        XCTAssertEqual(
            IOSCapabilityRegistry.capability(forUIActionName: "file_pick")?.id,
            "ios.files.selected_read"
        )
        XCTAssertNil(IOSCapabilityRegistry.capability(forToolName: "file_pick"))
        XCTAssertTrue(IOSCapabilityRegistry.executableToolNames.contains("file_read_selected"))
    }

    func testOnlySelectedFileReadIsCurrentlyExecutableAsModelTool() {
        XCTAssertEqual(IOSCapabilityRegistry.executableToolNames, ["file_read_selected"])
    }

    func testBlockedIOSAndAndroidToolsAreNotExecutable() {
        let blocked = [
            "sms_read",
            "call_log_list",
            "notification_list",
            "usage_stats_list",
            "terminal_execute",
            "apps_installed_list",
            "location_current",
            "camera_capture",
            "audio_record_once",
            "contacts_search",
            "calendar_create"
        ]

        for toolName in blocked {
            XCTAssertTrue(IOSCapabilityRegistry.blockedToolNames.contains(toolName), toolName)
            XCTAssertFalse(IOSCapabilityRegistry.executableToolNames.contains(toolName), toolName)
        }
    }

    func testExtensionOnlyCapabilitiesDoNotEnterDirectRequestList() {
        let directIds = Set(IOSCapabilityRegistry.directInAppRequestCapabilities.map(\.id))
        [
            "ios.call_directory",
            "ios.sms_filter",
            "ios.keyboard.full_access",
            "ios.network_extension.vpn_dns_filter"
        ].forEach { id in
            XCTAssertFalse(directIds.contains(id), id)
        }
    }

    func testCompositeActionRequirementsResolveAllCapabilities() {
        let ids = Set(IOSCapabilityRegistry.capabilities(forActionName: "video_record").map(\.id))

        XCTAssertTrue(ids.contains("ios.camera.capture"))
        XCTAssertTrue(ids.contains("ios.microphone.record"))
    }
}
