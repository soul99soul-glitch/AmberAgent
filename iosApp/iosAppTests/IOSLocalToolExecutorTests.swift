import XCTest
@testable import iosApp

@MainActor
final class IOSLocalToolExecutorTests: XCTestCase {
    func testPermissionsStatusReturnsIOSSnapshot() async throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let fileCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" }
        )
        permissionStore.setPolicy(.disabled, for: fileCapability)
        let executor = makeExecutor(permissionStore: permissionStore)

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: "permissions_status",
                operation: "status",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: false
            )
        )

        guard case .permissionsStatus(let snapshot) = output else {
            return XCTFail("Expected permissions status, got \(output)")
        }
        XCTAssertEqual(snapshot.platform, "iOS")
        XCTAssertFalse(snapshot.capabilities.isEmpty)
        let selectedFile = snapshot.capabilities.first { $0.id == "ios.files.selected_read" }
        XCTAssertEqual(selectedFile?.policy, IOSAgentPermissionPolicy.disabled.title)
    }

    func testFilePickIsDeniedBecauseItIsUIOnly() async {
        let output = await makeExecutor().execute(
            IOSLocalToolExecutionRequest(
                toolName: "file_pick",
                operation: "pick",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: true
            )
        )

        guard case .denied(let reason) = output else {
            return XCTFail("Expected denied, got \(output)")
        }
        XCTAssertTrue(reason.contains("foreground UI action"))
    }

    func testUnknownPlannedAndBlockedToolsAreDenied() async {
        let executor = makeExecutor()
        let toolNames = [
            "unknown_tool",
            "location_current",
            "sms_read",
            "notification_list",
            "terminal_execute"
        ]

        for toolName in toolNames {
            let output = await executor.execute(
                IOSLocalToolExecutionRequest(
                    toolName: toolName,
                    operation: "test",
                    scopeDigest: "",
                    payloadDigest: "",
                    isUserInitiated: true
                )
            )
            guard case .denied = output else {
                return XCTFail("Expected denied for \(toolName), got \(output)")
            }
        }
    }

    func testFileReadWithoutGrantNeedsUserAction() async {
        let output = await makeExecutor().execute(
            IOSLocalToolExecutionRequest(
                toolName: "file_read_selected",
                operation: "read_preview",
                scopeDigest: "missing",
                payloadDigest: "missing",
                isUserInitiated: true
            )
        )

        guard case .needsUserAction = output else {
            return XCTFail("Expected needsUserAction, got \(output)")
        }
    }

    func testValidGrantReturnsPreviewOnlyOnce() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults())
        let fileCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" }
        )
        permissionStore.setPolicy(.askEveryTime, for: fileCapability)
        let executor = makeExecutor(permissionStore: permissionStore, documentStore: documentStore)
        let request = IOSLocalToolExecutionRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let first = await executor.execute(request)
        guard case .selectedFilePreview(let result) = first else {
            return XCTFail("Expected selectedFilePreview, got \(first)")
        }
        XCTAssertEqual(result.bytesRead, 16)

        let second = await executor.execute(request)
        guard case .denied = second else {
            return XCTFail("Expected second execution to deny, got \(second)")
        }
    }

    func testScopeToolOrPayloadMismatchCannotSucceed() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let executor = makeExecutor(documentStore: documentStore)
        let requests = [
            IOSLocalToolExecutionRequest(
                toolName: "unknown_tool",
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: "wrong-scope",
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: "wrong-payload",
                isUserInitiated: true
            )
        ]

        for request in requests {
            let output = await executor.execute(request)
            guard case .denied = output else {
                XCTFail("Expected denied for mismatched request \(request), got \(output)")
            }
        }
    }

    func testAskEveryTimeRequiresForegroundUserAction() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let executor = makeExecutor(documentStore: documentStore)

        let output = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: false
            )
        )

        guard case .needsUserAction(let reason) = output else {
            return XCTFail("Expected needsUserAction, got \(output)")
        }
        XCTAssertTrue(reason.contains("Ask every time"))
    }

    private func makeExecutor(
        permissionStore: IOSPermissionStore? = nil,
        documentStore: DocumentAccessStore = DocumentAccessStore()
    ) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: permissionStore ?? IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
    }

    private func makeTempFile(size: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data(repeating: 65, count: size).write(to: url)
        return url
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
