import XCTest
import Shared
@testable import iosApp

@MainActor
final class IOSToolRuntimeTests: XCTestCase {
    func testTerminalToolFailureOutputIsStructuredAndRecognized() throws {
        let output = ChatToolOutputFormatter.toolFailureJSON(
            toolName: "search_web",
            reason: "The generation ended before this tool could run."
        )

        let data = try XCTUnwrap(output.data(using: .utf8))
        let payload = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(payload["ok"] as? Bool, false)
        XCTAssertEqual(payload["tool"] as? String, "search_web")
        XCTAssertEqual(
            ChatToolOutputFormatter.failureReason(
                from: [UIMessagePart.Text(text: output, metadata: nil)]
            ),
            "The generation ended before this tool could run."
        )
        let tool = UIMessagePart.Tool(
            toolCallId: "terminal-failure",
            toolName: "search_web",
            input: #"{"query":"swift"}"#,
            output: [UIMessagePart.Text(text: output, metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        XCTAssertEqual(ChatToolStepModel(tool: tool).state, .failed)
    }

    func testFileReadWithoutGrantNeedsUserAction() {
        let runtime = IOSToolRuntime(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore()
        )
        let decision = runtime.resolve(
            request: IOSToolInvocationRequest(
                toolName: "file_read_selected",
                operation: "read_preview",
                scopeDigest: "missing",
                payloadDigest: "missing",
                isUserInitiated: true
            )
        )

        guard case .needsUserAction = decision else {
            return XCTFail("Expected needsUserAction, got \(decision)")
        }
    }

    func testDisabledPolicyDeniesFileRead() throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let documentStore = DocumentAccessStore()
        let capability = try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" })
        permissionStore.setPolicy(.disabled, for: capability)
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(permissionStore: permissionStore, documentStore: documentStore)

        let decision = runtime.resolve(
            request: IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .deny = decision else {
            return XCTFail("Expected deny, got \(decision)")
        }
    }

    func testRunScopedPolicyIsNormalizedBeforeRuntimeResolve() throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults)
        let documentStore = DocumentAccessStore()
        let capability = try XCTUnwrap(IOSCapabilityRegistry.capabilities.first { $0.id == "ios.files.selected_read" })
        permissionStore.setPolicy(.allowOncePerRun, for: capability)
        XCTAssertEqual(permissionStore.policy(for: capability), .askEveryTime)
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(permissionStore: permissionStore, documentStore: documentStore)

        let decision = runtime.resolve(
            request: IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: false
            )
        )

        guard case .needsUserAction = decision else {
            return XCTFail("Expected needsUserAction, got \(decision)")
        }
    }

    func testScopeToolOrPayloadMismatchDenies() throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )

        let requests = [
            IOSToolInvocationRequest(
                toolName: "other_tool",
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: "wrong-scope",
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            ),
            IOSToolInvocationRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: "wrong-payload",
                isUserInitiated: true
            )
        ]

        for request in requests {
            let decision = runtime.resolve(request: request)
            guard case .deny = decision else {
                return XCTFail("Expected deny for \(request), got \(decision)")
            }
        }
    }

    func testValidGrantAllowsOnlyOnce() async throws {
        let documentStore = DocumentAccessStore()
        let grant = documentStore.registerPickedFile(try makeTempFile(size: 16))
        let runtime = IOSToolRuntime(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
        let request = IOSToolInvocationRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let firstResult = await runtime.executeFileReadSelected(request: request)
        guard case .success = firstResult else {
            return XCTFail("Expected success, got \(firstResult)")
        }

        let secondDecision = runtime.resolve(request: request)
        guard case .deny = secondDecision else {
            return XCTFail("Expected second use to deny, got \(secondDecision)")
        }
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
