import XCTest
@testable import iosApp

/// Job-orchestration contract for embedded iSH runtimes: streaming output,
/// real cancellation, and wait-timeout termination. The GPL kernel is behind
/// the injected `IOSEmbeddedIshJobBackend` seam, so these run in the stable
/// test bundle against a fake backend.
@MainActor
final class IOSEmbeddedIshJobRuntimeTests: XCTestCase {
    func testEmbeddedIshJobStreamsOutputBeforeCompletion() async {
        let backend = MockEmbeddedIshBackend(
            result: IOSEmbeddedIshCommandResult(
                exitCode: 0,
                stdout: "partial-chunk\nfinal-chunk\n",
                stderr: "",
                timedOut: false,
                error: nil
            ),
            midRunChunks: [
                IOSEmbeddedIshOutputChunk(text: "partial-chunk\n", isStderr: false)
            ],
            chunkDelayNanoseconds: 50_000_000,
            completionDelayNanoseconds: 250_000_000
        )
        let runtime = makeRuntime(embeddedIshBackend: backend)

        let started = await runtime.startJob(
            command: "echo partial-chunk && sleep 1 && echo final-chunk",
            runtime: .ishExperimental,
            experimentalEnabled: true
        )
        XCTAssertEqual(started.status, IOSTerminalJobStatus.running.rawValue)

        let streamed = await eventuallyOutput(
            jobId: started.id,
            contains: "partial-chunk",
            runtime: runtime,
            timeoutSeconds: 2
        )
        XCTAssertTrue(streamed, "job outputTail should expose guest output while the command is still running")

        let finished = await runtime.waitJob(id: started.id, timeoutSeconds: 5)
        XCTAssertEqual(finished?.status, IOSTerminalJobStatus.completed.rawValue)
        XCTAssertEqual(finished?.exitCode, 0)
        XCTAssertTrue(finished?.outputTail.contains("final-chunk") == true)
    }

    func testStopEmbeddedIshJobCancelsAndDropsLateCompletion() async {
        let backend = MockEmbeddedIshBackend(
            result: IOSEmbeddedIshCommandResult(
                exitCode: 0,
                stdout: "late success",
                stderr: "",
                timedOut: false,
                error: nil
            ),
            completionDelayNanoseconds: 150_000_000,
            ignoresCancellation: true
        )
        let runtime = makeRuntime(embeddedIshBackend: backend)
        let started = await runtime.startJob(
            command: "sleep 60",
            runtime: .ishExperimental,
            experimentalEnabled: true
        )

        let stopped = runtime.stopJob(id: started.id)

        XCTAssertEqual(stopped?.status, IOSTerminalJobStatus.cancelled.rawValue)

        try? await Task.sleep(nanoseconds: 400_000_000)
        let final = runtime.readJob(id: started.id)
        XCTAssertTrue(backend.observedCancellation, "stop must cancel the task driving the guest command")
        XCTAssertEqual(final?.status, IOSTerminalJobStatus.cancelled.rawValue)
        XCTAssertFalse(final?.outputTail.contains("late success") == true)
    }

    func testEmbeddedIshJobMarksStderrTransitionsInStreamingPreview() async {
        let backend = MockEmbeddedIshBackend(
            result: IOSEmbeddedIshCommandResult(
                exitCode: 0,
                stdout: "out\n",
                stderr: "boom\n",
                timedOut: false,
                error: nil
            ),
            midRunChunks: [
                IOSEmbeddedIshOutputChunk(text: "out\n", isStderr: false),
                IOSEmbeddedIshOutputChunk(text: "boom\n", isStderr: true)
            ],
            chunkDelayNanoseconds: 30_000_000,
            completionDelayNanoseconds: 150_000_000
        )
        let runtime = makeRuntime(embeddedIshBackend: backend)
        let started = await runtime.startJob(
            command: "echo out && echo boom >&2",
            runtime: .ishExperimental,
            experimentalEnabled: true
        )

        let marked = await eventuallyOutput(
            jobId: started.id,
            contains: "[stderr]\nboom\n",
            runtime: runtime,
            timeoutSeconds: 2
        )
        XCTAssertTrue(marked, "streaming preview should mark the transition into stderr output")

        let finished = await runtime.waitJob(id: started.id, timeoutSeconds: 5)
        XCTAssertEqual(finished?.status, IOSTerminalJobStatus.completed.rawValue)
    }

    func testWaitJobTimeoutTerminatesEmbeddedIshJob() async {
        let backend = MockEmbeddedIshBackend(
            result: IOSEmbeddedIshCommandResult(
                exitCode: 0,
                stdout: "done",
                stderr: "",
                timedOut: false,
                error: nil
            ),
            completionDelayNanoseconds: 5_000_000_000
        )
        let runtime = makeRuntime(embeddedIshBackend: backend)
        let started = await runtime.startJob(
            command: "sleep 60",
            runtime: .ishExperimental,
            experimentalEnabled: true
        )

        let timedOut = await runtime.waitJob(id: started.id, timeoutSeconds: 0.1)

        XCTAssertEqual(timedOut?.status, IOSTerminalJobStatus.timedOut.rawValue)
        XCTAssertEqual(runtime.readJob(id: started.id)?.status, IOSTerminalJobStatus.timedOut.rawValue)
        let observedCancellation = await eventuallyObservedCancellation(backend: backend, timeoutSeconds: 1)
        XCTAssertTrue(observedCancellation, "wait timeout must cancel the task driving the guest command")
    }

    private func makeRuntime(embeddedIshBackend: MockEmbeddedIshBackend) -> IOSTerminalRuntime {
        IOSTerminalRuntime(
            sshBackend: IOSSSHRuntimeBackend(),
            embeddedIshBackend: embeddedIshBackend,
            experimentalRuntimesLinked: true
        )
    }

    private func eventuallyObservedCancellation(
        backend: MockEmbeddedIshBackend,
        timeoutSeconds: TimeInterval
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if backend.observedCancellation { return true }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return backend.observedCancellation
    }

    private func eventuallyOutput(
        jobId: String,
        contains text: String,
        runtime: IOSTerminalRuntime,
        timeoutSeconds: TimeInterval
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if runtime.readJob(id: jobId)?.outputTail.contains(text) == true {
                return true
            }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return runtime.readJob(id: jobId)?.outputTail.contains(text) == true
    }
}

private final class MockEmbeddedIshBackend: IOSEmbeddedIshJobBackend, @unchecked Sendable {
    private let result: IOSEmbeddedIshCommandResult
    private let midRunChunks: [IOSEmbeddedIshOutputChunk]
    private let chunkDelayNanoseconds: UInt64
    private let completionDelayNanoseconds: UInt64
    private let ignoresCancellation: Bool
    private(set) var runCallCount = 0
    private(set) var observedCancellation = false

    init(
        result: IOSEmbeddedIshCommandResult,
        midRunChunks: [IOSEmbeddedIshOutputChunk] = [],
        chunkDelayNanoseconds: UInt64 = 20_000_000,
        completionDelayNanoseconds: UInt64 = 0,
        ignoresCancellation: Bool = false
    ) {
        self.result = result
        self.midRunChunks = midRunChunks
        self.chunkDelayNanoseconds = chunkDelayNanoseconds
        self.completionDelayNanoseconds = completionDelayNanoseconds
        self.ignoresCancellation = ignoresCancellation
    }

    func runJob(
        command: String,
        timeoutSeconds: TimeInterval,
        onOutput: @escaping @Sendable (IOSEmbeddedIshOutputChunk) -> Void
    ) async -> IOSEmbeddedIshCommandResult {
        runCallCount += 1
        for chunk in midRunChunks {
            await sleepSlice(chunkDelayNanoseconds)
            if noteCancellation(), !ignoresCancellation {
                return cancelledResult()
            }
            onOutput(chunk)
        }
        var remaining = completionDelayNanoseconds
        while remaining > 0 {
            let slice = min(remaining, 20_000_000)
            await sleepSlice(slice)
            remaining -= slice
            if noteCancellation(), !ignoresCancellation {
                return cancelledResult()
            }
        }
        return result
    }

    private func noteCancellation() -> Bool {
        if Task.isCancelled {
            observedCancellation = true
        }
        return Task.isCancelled
    }

    private func sleepSlice(_ nanoseconds: UInt64) async {
        try? await Task.sleep(nanoseconds: nanoseconds)
    }

    private func cancelledResult() -> IOSEmbeddedIshCommandResult {
        IOSEmbeddedIshCommandResult(
            exitCode: nil,
            stdout: "",
            stderr: "",
            timedOut: false,
            error: "cancelled"
        )
    }
}
