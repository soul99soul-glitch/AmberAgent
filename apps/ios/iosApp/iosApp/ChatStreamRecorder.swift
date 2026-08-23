import Foundation
import QuartzCore
@preconcurrency import Shared

/// DEBUG-only, opt-in recorder for live assistant streaming snapshots.
///
/// Purpose: capture the snapshots actually published to the UI after the flush window
/// coalesces provider chunks, plus terminal/cancel boundary snapshots. The newline-delimited JSON fixture lets
/// `ChatStreamReplayTests` replay the UI's real input cadence without a live network
/// connection; it is not a recording of the provider's raw per-chunk cadence.
///
/// Zero-invasive by construction:
/// - Disabled by default (`chat.stream.recording.enabled` UserDefaults flag, default
///   `false`). When disabled, `record` costs exactly one `UserDefaults.bool(forKey:)`
///   read and returns.
/// - When enabled, the caller extracts the last assistant text once; diffing and file I/O
///   happen off the caller's thread on a private serial queue. Recording can therefore
///   perturb diagnostic timing slightly and is not a zero-cost profiler.
/// - In `RELEASE` builds the entire implementation compiles away to an empty function
///   body — the call site in `ChatGenerationCoordinator` pays no cost at all.
final class ChatStreamRecorder: @unchecked Sendable {
    static let shared = ChatStreamRecorder()

    /// UserDefaults key gating recording. Default (key absent) is `false`.
    static let enabledDefaultsKey = "chat.stream.recording.enabled"

    private init() {}

    /// Records one streaming snapshot for `runId`. Extracts the full text of the last
    /// assistant message (concatenated text parts), diffs it against the last text
    /// recorded for this `runId`, and appends one JSON line describing the delta to
    /// `Library/Caches/stream-recordings/<runId>.jsonl`.
    ///
    /// Safe to call on any thread/actor (e.g. `@MainActor` from
    /// `flushPendingStreamSnapshot`); the enabled-check happens synchronously and
    /// cheaply, everything else is dispatched to a background serial queue.
    func record(runId: String, snapshot: [UIMessage]) {
#if DEBUG
        guard UserDefaults.standard.bool(forKey: Self.enabledDefaultsKey) else { return }
        guard let lastAssistant = snapshot.last(where: { $0.role == MessageRole.assistant }) else { return }
        let fullText = lastAssistant.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
        let nowMillis = Self.nowMillis()
        queue.async { [weak self] in
            self?.appendRecord(runId: runId, fullText: fullText, nowMillis: nowMillis)
        }
#endif
    }

    /// Closes and forgets any recording state for `runId`. Call this once the run
    /// truly ends (completed/failed/cancelled/handed off) so the recorder doesn't
    /// keep an open `FileHandle` and per-run bookkeeping alive indefinitely across
    /// a long session with many runs.
    func finish(runId: String) {
#if DEBUG
        queue.async { [weak self] in
            self?.closeRun(runId: runId)
        }
#endif
    }

#if DEBUG
    private let queue = DispatchQueue(label: "app.amber.chatStreamRecorder", qos: .utility)
    private var lastFullTextByRunId: [String: String] = [:]
    private var startedAtByRunId: [String: Int64] = [:]
    private var fileHandlesByRunId: [String: FileHandle] = [:]

    /// Must only be called on `queue`.
    private func appendRecord(runId: String, fullText: String, nowMillis: Int64) {
        let startedAt: Int64
        if let existing = startedAtByRunId[runId] {
            startedAt = existing
        } else {
            startedAt = nowMillis
            startedAtByRunId[runId] = startedAt
            writeLine(
                runId: runId,
                line: "{\"meta\": {\"startedAt\": \(startedAt), \"runId\": \"\(Self.jsonEscaped(runId))\"}}"
            )
        }

        let previous = lastFullTextByRunId[runId] ?? ""
        guard fullText != previous else { return }
        lastFullTextByRunId[runId] = fullText

        let relativeT = max(0, nowMillis - startedAt)
        if fullText.hasPrefix(previous) {
            let suffix = String(fullText.dropFirst(previous.count))
            writeLine(runId: runId, line: "{\"t\": \(relativeT), \"d\": \"\(Self.jsonEscaped(suffix))\"}")
        } else {
            // Not a pure append (e.g. variant switch / regeneration reusing the same
            // runId) — record the full text as a reset point instead of a bogus diff.
            writeLine(runId: runId, line: "{\"t\": \(relativeT), \"reset\": \"\(Self.jsonEscaped(fullText))\"}")
        }
    }

    /// Must only be called on `queue`.
    private func writeLine(runId: String, line: String) {
        guard let handle = fileHandle(for: runId), let data = (line + "\n").data(using: .utf8) else { return }
        handle.seekToEndOfFile()
        handle.write(data)
    }

    /// Must only be called on `queue`. Closes the run's open `FileHandle` (if any)
    /// and drops all bookkeeping keyed by `runId`.
    private func closeRun(runId: String) {
        try? fileHandlesByRunId[runId]?.close()
        fileHandlesByRunId.removeValue(forKey: runId)
        lastFullTextByRunId.removeValue(forKey: runId)
        startedAtByRunId.removeValue(forKey: runId)
    }

    /// Must only be called on `queue`. Lazily opens (and, on first use this process,
    /// truncates) the per-run recording file.
    private func fileHandle(for runId: String) -> FileHandle? {
        if let existing = fileHandlesByRunId[runId] { return existing }
        guard let directory = Self.recordingsDirectory() else { return nil }
        let url = directory.appendingPathComponent("\(runId).jsonl")
        // Fresh file per process/run: a stale file from a previous app launch with the
        // same runId (unlikely, but not impossible) must not corrupt this recording.
        guard FileManager.default.createFile(atPath: url.path, contents: nil),
              let handle = try? FileHandle(forWritingTo: url) else { return nil }
        fileHandlesByRunId[runId] = handle
        return handle
    }

    private static func recordingsDirectory() -> URL? {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else {
            return nil
        }
        let directory = caches.appendingPathComponent("stream-recordings", isDirectory: true)
        if !FileManager.default.fileExists(atPath: directory.path) {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
        return directory
    }

    /// Wall-clock epoch millis at process reference point, paired with the matching
    /// `CACurrentMediaTime()` reading. `CACurrentMediaTime` is monotonic (backed by
    /// mach absolute time) and immune to wall-clock jumps (NTP sync, DST, user
    /// changing the device clock); a recording's timeline must stay monotonic even
    /// if the wall clock doesn't, so we only use `Date()` once, at reference time.
    private static let referenceWallMillis: Double = Date().timeIntervalSince1970 * 1000
    private static let referenceMediaTime: CFTimeInterval = CACurrentMediaTime()

    private static func nowMillis() -> Int64 {
        let elapsedMillis = (CACurrentMediaTime() - referenceMediaTime) * 1000
        return Int64((referenceWallMillis + elapsedMillis).rounded())
    }

    private static func jsonEscaped(_ text: String) -> String {
        var out = String()
        out.reserveCapacity(text.count)
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out
    }
#endif
}
