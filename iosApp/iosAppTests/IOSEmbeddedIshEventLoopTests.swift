import XCTest
@testable import iosApp

/// Terminal-path contract for the embedded-iSH session event pump
/// (`IOSEmbeddedIshRuntime.readSessionEvents`). The pump's session
/// dependency is injected via `IOSEmbeddedIshSessionHooks`, so these run in
/// the stable test bundle with fake hooks — no GPL runtime linked.
final class IOSEmbeddedIshEventLoopTests: XCTestCase {
    func testLoopCompletesOnExitAccumulatingOutputAndClosingSession() {
        let recorder = HookRecorder()
        let hooks = makeHooks(recorder: recorder, script: [
            .event(.data(Data("hello\n".utf8), isStderr: false)),
            .event(.data(Data("warn\n".utf8), isStderr: true)),
            .event(.exited(0))
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 5,
            onOutput: { recorder.chunks.append($0) }
        )

        XCTAssertEqual(result.exitCode, 0)
        XCTAssertEqual(result.stdout, "hello\n")
        XCTAssertEqual(result.stderr, "warn\n")
        XCTAssertFalse(result.timedOut)
        XCTAssertNil(result.error)
        XCTAssertEqual(recorder.chunks, [
            IOSEmbeddedIshOutputChunk(text: "hello\n", isStderr: false),
            IOSEmbeddedIshOutputChunk(text: "warn\n", isStderr: true)
        ])
        XCTAssertEqual(recorder.closeCallCount, 1)
        XCTAssertEqual(recorder.terminateCallCount, 0)
    }

    func testLoopDeadlineTerminatesAndPreservesPartialOutput() {
        let recorder = HookRecorder()
        // One data event, then a quiet guest that never exits: every further
        // read is a poll tick until the pump's own deadline fires.
        let hooks = makeHooks(recorder: recorder, script: [
            .event(.data(Data("partial".utf8), isStderr: false))
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 0.05,
            onOutput: nil
        )

        XCTAssertTrue(result.timedOut)
        XCTAssertNil(result.exitCode)
        XCTAssertEqual(result.stdout, "partial")
        XCTAssertEqual(result.error, "Embedded iSH command timed out.")
        XCTAssertEqual(recorder.terminateCallCount, 1)
        XCTAssertEqual(recorder.closeCallCount, 1)
    }

    func testLoopFatalReadErrorClosesHonestly() {
        let recorder = HookRecorder()
        let hooks = makeHooks(recorder: recorder, script: [
            .failure(FakeReadError.boom)
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 5,
            onOutput: nil
        )

        XCTAssertFalse(result.timedOut)
        XCTAssertNil(result.exitCode)
        XCTAssertEqual(result.error, "fake fatal: boom")
        XCTAssertEqual(recorder.terminateCallCount, 0)
        XCTAssertEqual(recorder.closeCallCount, 1)
    }

    func testLoopSignaledExitKeepsSupervisorExitCode() {
        let recorder = HookRecorder()
        // Supervisor reports signaled exits as 128 + signal (SIGTERM → 143).
        let hooks = makeHooks(recorder: recorder, script: [
            .event(.exited(143))
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 5,
            onOutput: nil
        )

        XCTAssertEqual(result.exitCode, 143)
        XCTAssertFalse(result.timedOut)
        XCTAssertNil(result.error)
        XCTAssertEqual(recorder.terminateCallCount, 0)
        XCTAssertEqual(recorder.closeCallCount, 1)
    }

    func testLoopEmptyDataEventsAreNotForwarded() {
        let recorder = HookRecorder()
        let hooks = makeHooks(recorder: recorder, script: [
            .event(.data(Data(), isStderr: false)),
            .event(.exited(0))
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 5,
            onOutput: { recorder.chunks.append($0) }
        )

        XCTAssertEqual(result.exitCode, 0)
        XCTAssertEqual(result.stdout, "")
        XCTAssertTrue(recorder.chunks.isEmpty)
        XCTAssertEqual(recorder.closeCallCount, 1)
    }

    func testLoopHoldsBackSplitMultibyteCharactersUntilComplete() {
        let recorder = HookRecorder()
        // "\u{597d}"（好）is 3 bytes (E5 A5 BD), split across two reads.
        let hooks = makeHooks(recorder: recorder, script: [
            .event(.data(Data([0xE5]), isStderr: false)),
            .event(.data(Data([0xA5, 0xBD]), isStderr: false)),
            .event(.exited(0))
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 5,
            onOutput: { recorder.chunks.append($0) }
        )

        XCTAssertEqual(result.stdout, "\u{597d}")
        XCTAssertEqual(recorder.chunks, [
            IOSEmbeddedIshOutputChunk(text: "\u{597d}", isStderr: false)
        ], "split multibyte bytes must not leak replacement characters into the streaming preview")
        XCTAssertEqual(recorder.closeCallCount, 1)
    }

    func testLoopFinishFlushesTruncatedSequenceLossily() {
        let recorder = HookRecorder()
        // Stream ends mid-sequence: the held byte must still reach the
        // preview (lossy), never silently vanish.
        let hooks = makeHooks(recorder: recorder, script: [
            .event(.data(Data("ok".utf8) + Data([0xE5]), isStderr: false)),
            .event(.exited(0))
        ])

        let result = IOSEmbeddedIshRuntime.readSessionEvents(
            hooks: hooks,
            timeoutSeconds: 5,
            onOutput: { recorder.chunks.append($0) }
        )

        XCTAssertEqual(result.exitCode, 0)
        XCTAssertEqual(recorder.chunks.map(\.text).joined(), "ok\u{FFFD}")
        XCTAssertEqual(result.stdout, "ok\u{FFFD}")
    }

    // MARK: - Fakes

    private enum Step {
        case event(IOSEmbeddedIshSessionEvent)
        case failure(Error)
    }

    private enum FakeReadError: Error {
        case boom
        case pollTick
    }

    private final class HookRecorder: @unchecked Sendable {
        var terminateCallCount = 0
        var closeCallCount = 0
        var chunks: [IOSEmbeddedIshOutputChunk] = []
    }

    /// Returns scripted steps in order, then throws endless poll ticks once
    /// the script is exhausted (mirrors a quiet guest that never exits).
    private final class ScriptBox: @unchecked Sendable {
        private var steps: [Step]

        init(script: [Step]) {
            self.steps = script
        }

        func next() throws -> IOSEmbeddedIshSessionEvent {
            guard !steps.isEmpty else { throw FakeReadError.pollTick }
            let step = steps.removeFirst()
            switch step {
            case .event(let event):
                return event
            case .failure(let error):
                throw error
            }
        }
    }

    private func makeHooks(
        recorder: HookRecorder,
        script: [Step]
    ) -> IOSEmbeddedIshSessionHooks {
        let box = ScriptBox(script: script)
        return IOSEmbeddedIshSessionHooks(
            read: { _ in try box.next() },
            terminate: { recorder.terminateCallCount += 1 },
            close: { recorder.closeCallCount += 1 },
            isReadTimeout: { error in
                guard case FakeReadError.pollTick = error else { return false }
                return true
            },
            describeFailure: { error in
                "fake fatal: \(error)"
            }
        )
    }
}

// MARK: - Incremental UTF-8 decoder

final class IOSEmbeddedIshUTF8StreamDecoderTests: XCTestCase {
    func testASCIIAndCompleteTextPassThrough() {
        var decoder = IOSEmbeddedIshUTF8StreamDecoder()
        XCTAssertEqual(decoder.decode(appending: Data("hello ".utf8)), "hello ")
        XCTAssertEqual(decoder.decode(appending: Data("\u{4F60}\u{597D}".utf8)), "\u{4F60}\u{597D}")
        XCTAssertTrue(decoder.pending.isEmpty)
        XCTAssertEqual(decoder.finish(), "")
    }

    func testSplitThreeByteSequenceIsHeldUntilComplete() {
        var decoder = IOSEmbeddedIshUTF8StreamDecoder()
        XCTAssertEqual(decoder.decode(appending: Data([0xE5])), "")
        XCTAssertEqual(decoder.pending, Data([0xE5]))
        XCTAssertEqual(decoder.decode(appending: Data([0xA5])), "")
        XCTAssertEqual(decoder.decode(appending: Data([0xBD])), "\u{597D}")
        XCTAssertTrue(decoder.pending.isEmpty)
    }

    func testSplitFourByteSequenceAcrossThreeChunks() {
        var decoder = IOSEmbeddedIshUTF8StreamDecoder()
        // "\u{1F600}" = F0 9F 98 80
        XCTAssertEqual(decoder.decode(appending: Data([0xF0, 0x9F])), "")
        XCTAssertEqual(decoder.decode(appending: Data([0x98])), "")
        XCTAssertEqual(decoder.decode(appending: Data([0x80, 0x61])), "\u{1F600}a")
        XCTAssertTrue(decoder.pending.isEmpty)
    }

    func testCompleteTextBeforeIncompleteTailIsEmitted() {
        var decoder = IOSEmbeddedIshUTF8StreamDecoder()
        XCTAssertEqual(decoder.decode(appending: Data("ab".utf8) + Data([0xE4, 0xBD])), "ab")
        XCTAssertEqual(decoder.decode(appending: Data([0xA0])), "\u{4F60}")
    }

    func testCorruptBytesDoNotAccumulatePending() {
        var decoder = IOSEmbeddedIshUTF8StreamDecoder()
        // Raw continuation byte with no lead: corrupt, decoded lossily now
        // rather than held forever.
        XCTAssertEqual(decoder.decode(appending: Data([0x80])), "\u{FFFD}")
        XCTAssertTrue(decoder.pending.isEmpty)
        // Invalid lead byte (0xF8 is not valid UTF-8): also not held.
        XCTAssertEqual(decoder.decode(appending: Data([0xF8, 0x80])), "\u{FFFD}\u{FFFD}")
        XCTAssertTrue(decoder.pending.isEmpty)
    }

    func testFinishFlushesHeldBytesLossily() {
        var decoder = IOSEmbeddedIshUTF8StreamDecoder()
        XCTAssertEqual(decoder.decode(appending: Data([0xE5, 0xA5])), "")
        // A truncated sequence flushes as one U+FFFD (maximal-subpart rule),
        // not one per byte.
        XCTAssertEqual(decoder.finish(), "\u{FFFD}")
        XCTAssertTrue(decoder.pending.isEmpty)
        XCTAssertEqual(decoder.finish(), "")
    }
}
