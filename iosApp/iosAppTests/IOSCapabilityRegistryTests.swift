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
            "ios.agent.memory_write",
            "ios.agent.subagent_dispatch",
            "ios.agent.model_council_run",
            "ios.network.search_tools",
            "ios.mcp.tool_call",
            "ios.workspace.file_read",
            "ios.workspace.file_write",
            "ios.embedded.ish_runtime",
            "ios.external.ish_handoff",
            "ios.remote.command",
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

    func testExecutableModelToolsIncludeSelectedFileAndWebMountSafeTools() {
        let expected = Set([
            "file_read_selected",
            "memory_tool",
            "subagent_dispatch",
            "model_council_run",
            "search_web",
            "scrape_web",
            "mcp_call",
            "mcp_test",
            "mcp_import_from_skill",
            "workspace_file_read",
            "workspace_file_list",
            "workspace_file_search",
            "workspace_artifact_read",
            "workspace_file_write",
            "workspace_file_edit",
            "workspace_file_move",
            "workspace_artifact_delete",
            "ish_handoff"
        ]).union(IOSWebMountToolCatalog.supportedToolNames)
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
        XCTAssertEqual(IOSCapabilityRegistry.executableToolNames, expected)
    }

    func testAdvancedExecutionCapabilitiesKeepRemoteCommandForegroundOnly() throws {
        let subAgent = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.subagent_dispatch" }
        )
        let council = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.model_council_run" }
        )
        let remote = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.remote.command" }
        )
        let ish = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.external.ish_handoff" }
        )
        let embeddedIsh = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.embedded.ish_runtime" }
        )

        XCTAssertEqual(subAgent.modelToolNames, ["subagent_dispatch"])
        XCTAssertEqual(council.modelToolNames, ["model_council_run"])
        XCTAssertEqual(ish.modelToolNames, ["ish_handoff"])
        XCTAssertEqual(ish.status, .degraded)
        XCTAssertTrue(ish.summary.contains("cannot control iSH"))
        XCTAssertTrue(ish.summary.contains("stdout/stderr"))
        if IOSTerminalBuildPolicy.experimentalRuntimesLinked {
            XCTAssertEqual(embeddedIsh.status, .supported)
            XCTAssertEqual(embeddedIsh.modelToolNames, ["ios_ish_execute"])
            XCTAssertTrue(IOSCapabilityRegistry.executableToolNames.contains("ios_ish_execute"))
            XCTAssertNil(embeddedIsh.unavailableReason)
        } else {
            XCTAssertEqual(embeddedIsh.status, .unsupported)
            XCTAssertTrue(embeddedIsh.modelToolNames.isEmpty)
            XCTAssertFalse(IOSCapabilityRegistry.executableToolNames.contains("ios_ish_execute"))
            XCTAssertTrue(embeddedIsh.unavailableReason?.contains("ExperimentalGPL") == true)
        }
        XCTAssertTrue(remote.uiActionNames.contains("remote_command_run"))
        XCTAssertTrue(remote.modelToolNames.isEmpty)
        XCTAssertFalse(IOSCapabilityRegistry.executableToolNames.contains("terminal_execute"))
        XCTAssertEqual(IOSCapabilityRegistry.capability(forUIActionName: "remote_command_run")?.id, "ios.remote.command")
    }

    @MainActor
    func testSubAgentRolesAndCouncilTaskMetadataAreBounded() throws {
        let role = try XCTUnwrap(IOSSubAgentRoleCatalog.resolve(roleId: "oracle"))
        XCTAssertEqual(role.id, "oracle")
        XCTAssertTrue(role.toolAllowlist.contains("file_read_selected"))
        XCTAssertFalse(role.toolAllowlist.contains("terminal_execute"))
        XCTAssertGreaterThan(role.outputBudgetChars, 0)

        let defaults = isolatedDefaults()
        let store = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "advanced")
        let subAgentTask = store.startTask(
            kind: .subAgent,
            title: "Oracle task",
            objective: "Review plan",
            roleId: role.id,
            toolScope: role.toolAllowlist,
            budgetSummary: "turns \(role.maxTurns) · output \(role.outputBudgetChars) chars",
            sourceToolName: "subagent_dispatch"
        )
        let councilTask = store.startTask(
            kind: .modelCouncil,
            title: "Council task",
            objective: "Choose fallback",
            budgetSummary: "mode compare · seats 3 · output 12000 chars",
            sourceToolName: "model_council_run",
            metadata: ["seat_names": "Host, Risk, Opponent"]
        )

        XCTAssertEqual(subAgentTask.roleId, "oracle")
        XCTAssertEqual(subAgentTask.sourceToolName, "subagent_dispatch")
        XCTAssertTrue(subAgentTask.toolScope.allSatisfy { role.toolAllowlist.contains($0) })
        XCTAssertEqual(councilTask.kind, .modelCouncil)
        XCTAssertTrue(councilTask.budgetSummary.contains("seats 3"))
        XCTAssertEqual(councilTask.metadata["seat_names"], "Host, Risk, Opponent")
    }

    @MainActor
    func testMemoryWriteCapabilityIsHighRiskAndPolicyManaged() throws {
        let capability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.memory_write" }
        )

        XCTAssertEqual(capability.modelToolNames, ["memory_tool"])
        XCTAssertEqual(capability.risk, .high)
        XCTAssertEqual(capability.requestKind, .foregroundSession)
        XCTAssertFalse(IOSPermissionStore.availablePolicies(for: capability).contains(.allowOncePerRun))
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
            "calendar_create",
            "wm_eval",
            "wm_signed_fetch",
            "wm_visual_read"
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

    func testWebMountCapabilityAdvertisesSafeAndUnsupportedTools() throws {
        let capability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.webmount.browser" }
        )

        XCTAssertEqual(Set(capability.modelToolNames), IOSWebMountToolCatalog.supportedToolNames)
        XCTAssertTrue(capability.blockedToolNames.contains("wm_eval"))
        XCTAssertTrue(capability.blockedToolNames.contains("wm_signed_fetch"))
        XCTAssertTrue(capability.blockedToolNames.contains("wm_network_inspect"))
        XCTAssertTrue(capability.blockedToolNames.contains("wm_visual_read"))
        XCTAssertTrue(capability.modelToolNames.contains("wm_visual_snapshot"))
        XCTAssertTrue(capability.modelToolNames.contains("wm_screenshot"))
        XCTAssertEqual(capability.risk, .high)
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
