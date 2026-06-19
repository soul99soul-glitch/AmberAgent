import XCTest
@testable import iosApp

final class IOSSSHProfileTests: XCTestCase {
    func testProfileValidationTrimsRequiredFields() throws {
        let profile = IOSSSHProfile(
            name: "  dev box  ",
            host: "  example.com  ",
            port: 22,
            username: "  amber  ",
            knownHostSHA256: "  SHA256:test  "
        )

        let validated = try profile.validated()

        XCTAssertEqual(validated.name, "dev box")
        XCTAssertEqual(validated.host, "example.com")
        XCTAssertEqual(validated.username, "amber")
        XCTAssertEqual(validated.knownHostSHA256, "SHA256:test")
    }

    func testProfileValidationRejectsMissingRequiredFields() {
        XCTAssertThrowsError(try IOSSSHProfile(host: "", username: "amber").validated())
        XCTAssertThrowsError(try IOSSSHProfile(host: "example.com", port: 0, username: "amber").validated())
        XCTAssertThrowsError(try IOSSSHProfile(host: "example.com", username: "").validated())
    }

    func testProbePolicyNeverOffersRealPassword() {
        XCTAssertTrue(IOSSSHProbePolicy.abortsAfterHostKey)
        XCTAssertEqual(IOSSSHProbePolicy.passwordOffer(realPassword: "secret"), IOSSSHProbePolicy.passwordPlaceholder)
        XCTAssertNotEqual(IOSSSHProbePolicy.passwordOffer(realPassword: "secret"), "secret")
    }

    func testKnownHostTrustIsBoundToHostAndPort() throws {
        let trusted = IOSSSHProfile(
            host: "example.com",
            port: 22,
            username: "amber",
            knownHostSHA256: "SHA256:test",
            knownHostHost: "example.com",
            knownHostPort: 22
        )

        let validated = try trusted.validated()

        XCTAssertEqual(validated.knownHostSHA256, "SHA256:test")
        XCTAssertEqual(validated.knownHostHost, "example.com")
        XCTAssertEqual(validated.knownHostPort, 22)
    }

    func testKnownHostTrustIsClearedWhenEndpointChanges() throws {
        let changedHost = IOSSSHProfile(
            host: "new.example.com",
            port: 22,
            username: "amber",
            knownHostSHA256: "SHA256:test",
            knownHostHost: "example.com",
            knownHostPort: 22
        )
        let changedPort = IOSSSHProfile(
            host: "example.com",
            port: 2222,
            username: "amber",
            knownHostSHA256: "SHA256:test",
            knownHostHost: "example.com",
            knownHostPort: 22
        )

        XCTAssertNil(try changedHost.validated().knownHostSHA256)
        XCTAssertNil(try changedPort.validated().knownHostSHA256)
    }
}

@MainActor
final class IOSTerminalSSHRuntimeTests: XCTestCase {
    func testRemoteSSHJobCompletesWithMockBackend() async {
        let backend = MockSSHBackend(result: IOSSSHCommandResult(output: "amber-terminal-smoke\n", exitCode: 0))
        let runtime = IOSTerminalRuntime(sshBackend: backend)
        let profile = trustedProfile()

        let started = await runtime.startJob(
            command: "echo amber-terminal-smoke",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: profile,
            sshPassword: "secret"
        )
        let finished = await runtime.waitJob(id: started.id, timeoutSeconds: 2)

        XCTAssertEqual(started.status, IOSTerminalJobStatus.running.rawValue)
        XCTAssertEqual(finished?.status, IOSTerminalJobStatus.completed.rawValue)
        XCTAssertEqual(finished?.exitCode, 0)
        XCTAssertEqual(finished?.outputTail, "amber-terminal-smoke\n")
    }

    func testRemoteSSHRequiresTrustedHostBeforeStart() async {
        let runtime = IOSTerminalRuntime(sshBackend: MockSSHBackend())
        var profile = trustedProfile()
        profile.knownHostSHA256 = nil

        let snapshot = await runtime.startJob(
            command: "echo amber-terminal-smoke",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: profile,
            sshPassword: "secret"
        )

        XCTAssertEqual(snapshot.status, IOSTerminalJobStatus.failed.rawValue)
        XCTAssertTrue(snapshot.outputTail.contains("Trust Host"))
    }

    func testRemoteSSHRejectsTrustWhenEndpointChanged() async {
        let backend = MockSSHBackend()
        let runtime = IOSTerminalRuntime(sshBackend: backend)
        var profile = trustedProfile()
        profile.host = "changed.example.com"

        let snapshot = await runtime.startJob(
            command: "echo amber-terminal-smoke",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: profile,
            sshPassword: "secret"
        )

        XCTAssertEqual(snapshot.status, IOSTerminalJobStatus.failed.rawValue)
        XCTAssertTrue(snapshot.outputTail.contains("Trust Host"))
        XCTAssertEqual(backend.executeCallCount, 0)
    }

    func testOutputTailIsLimitedToLast128KB() async {
        let oversized = String(repeating: "a", count: 140 * 1024)
        let backend = MockSSHBackend(result: IOSSSHCommandResult(output: oversized, exitCode: 0))
        let runtime = IOSTerminalRuntime(sshBackend: backend)

        let started = await runtime.startJob(
            command: "big-output",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: trustedProfile(),
            sshPassword: "secret"
        )
        let finished = await runtime.waitJob(id: started.id, timeoutSeconds: 2)

        XCTAssertEqual(finished?.outputTail.utf8.count, 128 * 1024)
    }

    func testStopJobCancelsRunningTask() async {
        let runtime = IOSTerminalRuntime(sshBackend: MockSSHBackend(delayNanoseconds: 5_000_000_000))
        let started = await runtime.startJob(
            command: "sleep 5",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: trustedProfile(),
            sshPassword: "secret"
        )

        let stopped = runtime.stopJob(id: started.id)

        XCTAssertEqual(stopped?.status, IOSTerminalJobStatus.cancelled.rawValue)
    }

    func testLateBackendCompletionDoesNotOverwriteCancelledJob() async {
        let runtime = IOSTerminalRuntime(
            sshBackend: MockSSHBackend(
                result: IOSSSHCommandResult(output: "late success", exitCode: 0),
                delayNanoseconds: 50_000_000,
                ignoresCancellation: true
            )
        )
        let started = await runtime.startJob(
            command: "sleep",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: trustedProfile(),
            sshPassword: "secret"
        )

        _ = runtime.stopJob(id: started.id)
        try? await Task.sleep(nanoseconds: 100_000_000)
        let final = runtime.readJob(id: started.id)

        XCTAssertEqual(final?.status, IOSTerminalJobStatus.cancelled.rawValue)
        XCTAssertFalse(final?.outputTail.contains("late success") == true)
    }

    func testWaitJobTimeoutMarksTimedOut() async {
        let runtime = IOSTerminalRuntime(
            sshBackend: MockSSHBackend(delayNanoseconds: 5_000_000_000, ignoresCancellation: true)
        )
        let started = await runtime.startJob(
            command: "sleep",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: trustedProfile(),
            sshPassword: "secret"
        )

        let timedOut = await runtime.waitJob(id: started.id, timeoutSeconds: 0.1)

        XCTAssertEqual(timedOut?.status, IOSTerminalJobStatus.timedOut.rawValue)
        XCTAssertEqual(runtime.readJob(id: started.id)?.status, IOSTerminalJobStatus.timedOut.rawValue)
    }

    func testRemoteCommandPolicyRejectsDangerousCommands() {
        switch IOSRemoteCommandPolicy.validate("echo amber") {
        case .success(let command):
            XCTAssertEqual(command, "echo amber")
        case .failure(let message):
            XCTFail("Expected safe command, got \(message)")
        }

        switch IOSRemoteCommandPolicy.validate("rm -rf /") {
        case .success:
            XCTFail("Expected dangerous command to be blocked")
        case .failure(let message):
            XCTAssertTrue(message.contains("Blocked"))
        }
    }

    func testAdvancedTaskStorePersistsAndRedactsRemoteTaskState() {
        let defaults = isolatedDefaults()
        let store = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let task = store.startTask(
            kind: .remoteCommand,
            title: "Remote task",
            objective: "Run command",
            connectionSummary: "dev.example.com",
            commandPreview: "echo token=secret",
            sourceToolName: "remote_command_run"
        )
        store.appendLog(id: task.id, chunk: "Authorization: Bearer abcdef123456")
        _ = store.updateTask(
            id: task.id,
            status: .completed,
            resultSummary: "password=secret finished",
            retryable: false
        )

        let reloaded = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let restored = reloaded.recent(kind: .remoteCommand, limit: 1).first

        XCTAssertEqual(restored?.status, .completed)
        XCTAssertFalse(restored?.commandPreview.contains("secret") == true)
        XCTAssertFalse(restored?.logTail.contains("abcdef123456") == true)
        XCTAssertFalse(restored?.resultSummary.contains("secret") == true)
        XCTAssertFalse(restored?.canRetry == true)
    }

    private func trustedProfile() -> IOSSSHProfile {
        IOSSSHProfile(
            name: "Test",
            host: "example.com",
            port: 22,
            username: "amber",
            knownHostSHA256: "SHA256:test",
            knownHostHost: "example.com",
            knownHostPort: 22
        )
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

private final class MockSSHBackend: IOSSSHRuntimeBackendProtocol, @unchecked Sendable {
    var probeResult: IOSSSHConnectionProbeResult
    var result: IOSSSHCommandResult
    var delayNanoseconds: UInt64
    var ignoresCancellation: Bool
    var executeCallCount = 0

    init(
        probeResult: IOSSSHConnectionProbeResult = IOSSSHConnectionProbeResult(
            fingerprint: "SHA256:test",
            trustState: .trusted
        ),
        result: IOSSSHCommandResult = IOSSSHCommandResult(output: "", exitCode: 0),
        delayNanoseconds: UInt64 = 0,
        ignoresCancellation: Bool = false
    ) {
        self.probeResult = probeResult
        self.result = result
        self.delayNanoseconds = delayNanoseconds
        self.ignoresCancellation = ignoresCancellation
    }

    func testConnection(profile: IOSSSHProfile, password: String) async throws -> IOSSSHConnectionProbeResult {
        probeResult
    }

    func execute(
        command: String,
        profile: IOSSSHProfile,
        password: String,
        timeout: TimeInterval,
        output: @escaping @Sendable (String) -> Void
    ) async throws -> IOSSSHCommandResult {
        executeCallCount += 1
        if delayNanoseconds > 0 {
            do {
                try await Task.sleep(nanoseconds: delayNanoseconds)
            } catch where ignoresCancellation {
                // Continue to simulate a backend callback that arrives after stop/timeout.
            }
        }
        output(result.output)
        return result
    }
}
