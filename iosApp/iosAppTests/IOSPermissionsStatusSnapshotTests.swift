import XCTest
@testable import iosApp

@MainActor
final class IOSPermissionsStatusSnapshotTests: XCTestCase {
    func testFileActionsAreSeparatedInSnapshot() throws {
        let snapshot = makeSnapshot()
        let selectedFile = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.files.selected_read" })

        XCTAssertTrue(selectedFile.uiActionNames.contains("file_pick"))
        XCTAssertFalse(selectedFile.modelToolNames.contains("file_pick"))
        XCTAssertTrue(selectedFile.modelToolNames.contains("file_read_selected"))
        XCTAssertTrue(selectedFile.executable)
        XCTAssertEqual(selectedFile.requestKind, IOSPermissionRequestKind.picker.title)
    }

    func testSnapshotIncludesFullCatalogMetadata() throws {
        let snapshot = makeSnapshot()
        let camera = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.camera.capture" })

        XCTAssertFalse(camera.summary.isEmpty)
        XCTAssertEqual(camera.systemStatus, IOSSystemPermissionStatus.notDetermined.title)
        XCTAssertEqual(camera.requestKind, IOSPermissionRequestKind.directSystemPrompt.title)
        XCTAssertEqual(camera.requestEntryPoint, "AVCaptureDevice.requestAccess(for: .video)")
        XCTAssertTrue(camera.canRequestInApp)
        XCTAssertTrue(camera.canOpenSettings)
        XCTAssertTrue(camera.requiredInfoPlistKeys.contains("NSCameraUsageDescription"))
    }

    func testFocusStatusCanRequestThroughCoordinator() throws {
        let snapshot = makeSnapshot()
        let focus = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.focus.status" })

        XCTAssertEqual(focus.requestKind, IOSPermissionRequestKind.directSystemPrompt.title)
        XCTAssertTrue(focus.canRequestInApp)
    }

    func testImageCaptureAndPasskeysCanRequestThroughCoordinator() throws {
        let snapshot = makeSnapshot()
        let imageCaptureContents = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.image_capture.contents" })
        let imageCaptureControl = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.image_capture.control" })
        let passkeys = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.authentication.passkeys_platform_credentials" })

        XCTAssertTrue(imageCaptureContents.canRequestInApp)
        XCTAssertTrue(imageCaptureControl.canRequestInApp)
        XCTAssertTrue(passkeys.canRequestInApp)
    }

    func testEntitlementAndExtensionCapabilitiesExposePrerequisites() throws {
        let snapshot = makeSnapshot()
        let health = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.health.read" })
        let callDirectory = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.call_directory" })

        XCTAssertEqual(health.systemStatus, IOSSystemPermissionStatus.requiresEntitlement.title)
        XCTAssertTrue(health.requiredEntitlements.contains("com.apple.developer.healthkit"))
        XCTAssertFalse(health.canRequestInApp)
        XCTAssertEqual(callDirectory.systemStatus, IOSSystemPermissionStatus.requiresExtensionTarget.title)
        XCTAssertTrue(callDirectory.requiredExtensionTargets.contains("Call Directory Extension"))
        XCTAssertFalse(callDirectory.canRequestInApp)
    }

    func testForegroundPickerWithoutCoordinatorImplementationIsNotGenericRequest() throws {
        let snapshot = makeSnapshot()
        let photoPicker = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.photos.picker" })

        XCTAssertEqual(photoPicker.requestKind, IOSPermissionRequestKind.picker.title)
        XCTAssertFalse(photoPicker.canRequestInApp)
    }

    func testEntitlementGatedDirectRequestDoesNotShowRequestUntilPreflightPasses() throws {
        let snapshot = makeSnapshot()
        let financeKit = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.finance.financekit" })

        XCTAssertEqual(financeKit.requestKind, IOSPermissionRequestKind.directSystemPrompt.title)
        XCTAssertEqual(financeKit.systemStatus, IOSSystemPermissionStatus.requiresEntitlement.title)
        XCTAssertFalse(financeKit.canRequestInApp)
    }

    func testContextRequiredForegroundRequestsAreNotGenericRequests() throws {
        let snapshot = makeSnapshot()
        let criticalSMS = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.messages.critical_sms" })
        let wifiSharing = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.network.wifi_sharing" })
        let fallDetection = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.motion.fall_detection" })

        XCTAssertEqual(criticalSMS.requestKind, IOSPermissionRequestKind.foregroundSystemUI.title)
        XCTAssertFalse(criticalSMS.canRequestInApp)
        XCTAssertEqual(wifiSharing.requestKind, IOSPermissionRequestKind.foregroundSystemUI.title)
        XCTAssertFalse(wifiSharing.canRequestInApp)
        XCTAssertEqual(fallDetection.systemStatus, IOSSystemPermissionStatus.requiresExtensionTarget.title)
        XCTAssertFalse(fallDetection.canRequestInApp)
    }

    func testAndroidOnlyToolsAreBlockedAndNotExecutable() throws {
        let snapshot = makeSnapshot()
        let smsRead = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.unavailable.sms.read" })
        let notificationRead = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.unavailable.notification.listener" })
        let terminal = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.unavailable.terminal" })

        XCTAssertTrue(smsRead.blockedToolNames.contains("sms_read"))
        XCTAssertTrue(notificationRead.blockedToolNames.contains("notification_list"))
        XCTAssertTrue(terminal.blockedToolNames.contains("terminal_execute"))
        XCTAssertEqual(smsRead.domain, "Unavailable on iOS")
        XCTAssertFalse(smsRead.executable)
        XCTAssertFalse(notificationRead.executable)
        XCTAssertFalse(terminal.executable)
    }

    func testAdvancedExecutionCapabilitiesExposeApprovalState() throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults, taskStore: nil)
        permissionStore.recordApproval(
            capabilityId: "ios.remote.command",
            toolName: "remote_command_run",
            action: .allowed,
            reason: "User confirmed remote run for token=secret",
            runId: "task-1"
        )
        let snapshot = makeSnapshot(permissionStore: permissionStore)
        let subAgent = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.agent.subagent_dispatch" })
        let council = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.agent.model_council_run" })
        let remote = try XCTUnwrap(snapshot.capabilities.first { $0.id == "ios.remote.command" })

        XCTAssertTrue(subAgent.executable)
        XCTAssertTrue(subAgent.modelToolNames.contains("subagent_dispatch"))
        XCTAssertTrue(council.executable)
        XCTAssertTrue(council.modelToolNames.contains("model_council_run"))
        XCTAssertTrue(remote.executable)
        XCTAssertTrue(remote.modelToolNames.isEmpty)
        XCTAssertTrue(remote.uiActionNames.contains("remote_command_run"))
        XCTAssertEqual(remote.lastApprovalAction, IOSToolApprovalAction.allowed.title)
        XCTAssertFalse(remote.lastApprovalReason?.contains("secret") == true)
    }

    func testSnapshotDoesNotContainAndroidPermissionInstructions() {
        let snapshot = makeSnapshot()
        let text = snapshot.capabilities
            .map {
                [
                    $0.id,
                    $0.title,
                    $0.domain,
                    $0.status,
                    $0.systemStatus,
                    $0.risk,
                    $0.policy,
                    $0.requestKind,
                    $0.requestEntryPoint,
                    $0.uiActionNames.joined(separator: " "),
                    $0.modelToolNames.joined(separator: " "),
                    $0.blockedToolNames.joined(separator: " "),
                    $0.requiredInfoPlistKeys.joined(separator: " "),
                    $0.requiredEntitlements.joined(separator: " "),
                    $0.requiredBackgroundModes.joined(separator: " "),
                    $0.requiredExtensionTargets.joined(separator: " "),
                    $0.reason ?? ""
                ].joined(separator: " ")
            }
            .joined(separator: "\n")

        XCTAssertFalse(text.contains("Open Android Settings"))
        XCTAssertFalse(text.contains("android.permission."))
        XCTAssertFalse(text.contains("android."))
    }

    private func makeSnapshot(permissionStore: IOSPermissionStore? = nil) -> IOSPermissionsStatusSnapshot {
        let coordinator = IOSSystemPermissionCoordinator()
        let executor = IOSLocalToolExecutor(
            permissionStore: permissionStore ?? IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            systemPermissionCoordinator: coordinator
        )
        return executor.permissionsStatus()
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
