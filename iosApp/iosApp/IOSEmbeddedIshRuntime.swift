import Foundation
#if ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES
import IshEmbed
#endif

struct IOSEmbeddedIshCommandResult: Equatable, Sendable {
    let exitCode: Int?
    let stdout: String
    let stderr: String
    let timedOut: Bool
    let error: String?
}

/// One incremental output piece emitted while an embedded iSH command is
/// still running. Stable-target type so job orchestration and tests do not
/// depend on the GPL-only IshEmbed API.
struct IOSEmbeddedIshOutputChunk: Equatable, Sendable {
    let text: String
    let isStderr: Bool
}

/// One event from a spawned embedded-iSH session, projected onto stable-target
/// types so the event pump carries no GPL-only IshEmbed types.
enum IOSEmbeddedIshSessionEvent: Equatable, Sendable {
    case data(Data, isStderr: Bool)
    case exited(Int32)
}

/// Incremental UTF-8 decoder for byte-stream chunks. A multibyte sequence
/// can be split across two reads; decoding each chunk independently would
/// emit U+FFFD at the boundary. This decoder holds back a trailing
/// incomplete sequence (at most 3 bytes) and only emits complete text;
/// `finish()` flushes any held bytes lossily at end-of-stream so the tail
/// is never silently short.
struct IOSEmbeddedIshUTF8StreamDecoder: Sendable, Equatable {
    private(set) var pending = Data()

    /// Decode the maximal complete-text prefix of pending + `data`.
    mutating func decode(appending data: Data) -> String {
        guard !data.isEmpty else { return "" }
        var bytes = pending
        bytes.append(data)
        let validCount = Self.completePrefixLength(of: bytes)
        pending = bytes.subdata(in: validCount..<bytes.count)
        return String(decoding: bytes.prefix(validCount), as: UTF8.self)
    }

    /// Flush held bytes at end-of-stream, lossily if they are genuinely
    /// incomplete (corrupt or truncated stream).
    mutating func finish() -> String {
        let remaining = pending
        pending = Data()
        return String(decoding: remaining, as: UTF8.self)
    }

    /// Length of the longest prefix of `bytes` that ends on a UTF-8 sequence
    /// boundary. Corrupt bytes are treated as complete (they decode to
    /// U+FFFD) so `pending` never grows unboundedly on garbage input.
    static func completePrefixLength(of bytes: Data) -> Int {
        let count = bytes.count
        guard count > 0, let last = bytes.last, last & 0x80 != 0 else { return count }
        var continuationCount = 0
        var index = count - 1
        while index >= 0, bytes[index] & 0xC0 == 0x80, continuationCount < 3 {
            continuationCount += 1
            index -= 1
        }
        guard index >= 0 else { return count }
        let lead = bytes[index]
        let expectedLength: Int
        switch lead {
        case 0xC0...0xDF: expectedLength = 2
        case 0xE0...0xEF: expectedLength = 3
        case 0xF0...0xF7: expectedLength = 4
        default: return count // ASCII or invalid lead: corrupt, not incomplete
        }
        return continuationCount + 1 >= expectedLength ? count : index
    }
}

/// Dependencies of the session event pump. Production hooks wrap an
/// `IshSession` (GPL target only); tests inject fakes to drive every
/// terminal path in the stable test bundle.
struct IOSEmbeddedIshSessionHooks: Sendable {
    /// Blocking read of the next session event; the argument is the maximum
    /// wait in seconds. Must throw a poll-tick error (see `isReadTimeout`)
    /// when the wait elapses with no event.
    var read: @Sendable (TimeInterval) throws -> IOSEmbeddedIshSessionEvent
    /// Terminate the guest process group (SIGTERM, then SIGKILL after grace).
    var terminate: @Sendable () -> Void
    /// Release the session. Called exactly once when the pump exits.
    var close: @Sendable () -> Void
    /// True when an error thrown by `read` is a poll tick (wait elapsed with
    /// no event) rather than a failure.
    var isReadTimeout: @Sendable (Error) -> Bool
    /// Honest single-line description for a non-timeout read failure.
    var describeFailure: @Sendable (Error) -> String
}

enum IOSEmbeddedIshRuntimeError: LocalizedError {
    case notLinked
    case missingBundledRootfs
    case invalidBundledRootfs(URL)
    case rootfsCopyFailed(String)

    var errorDescription: String? {
        switch self {
        case .notLinked:
            "Embedded iSH is only linked in the ExperimentalGPL target."
        case .missingBundledRootfs:
            "Embedded iSH rootfs resource is missing from the app bundle."
        case .invalidBundledRootfs(let url):
            "Embedded iSH rootfs is incomplete at \(url.path)."
        case .rootfsCopyFailed(let message):
            "Failed to prepare embedded iSH rootfs: \(message)"
        }
    }
}

actor IOSEmbeddedIshRuntime {
    static let shared = IOSEmbeddedIshRuntime()

    private static let rootfsVersion = "v0.3.3"
    private static let bundledRootfsName = "fs"
    private static let rootfsReadySentinel = ".amberagent-rootfs-v0.3.3.ready"
    private var booted = false
    /// In-flight boot work, so concurrent first runs share one rootfs copy +
    /// kernel boot instead of racing each other. Cleared on completion.
    private var bootTask: Task<Void, Error>?

    private init() {}

    /// Runs a command to completion, streaming stdout/stderr pieces through
    /// `onOutput` as they arrive. Cancelling the surrounding task terminates
    /// the guest process (SIGTERM, then SIGKILL after the grace period) and
    /// the result still carries whatever output was captured. A guest-side
    /// deadline independent of task cancellation enforces `timeoutSeconds`;
    /// values below 1 second are clamped to 1 second. Exits by signal surface
    /// as 128 + signum exit codes (supervisor convention: SIGKILL → 137,
    /// SIGTERM → 143), which is also what a cancelled command reports.
    func run(
        command: String,
        timeoutSeconds: TimeInterval = 60,
        onOutput: (@Sendable (IOSEmbeddedIshOutputChunk) -> Void)? = nil
    ) async -> IOSEmbeddedIshCommandResult {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return IOSEmbeddedIshCommandResult(
                exitCode: 64,
                stdout: "",
                stderr: "",
                timedOut: false,
                error: "Command is required."
            )
        }

        #if ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES
        return await runSpawned(command: trimmed, timeoutSeconds: timeoutSeconds, onOutput: onOutput)
        #else
        return IOSEmbeddedIshCommandResult(
            exitCode: nil,
            stdout: "",
            stderr: "",
            timedOut: false,
            error: IOSEmbeddedIshRuntimeError.notLinked.localizedDescription
        )
        #endif
    }

    #if ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES
    /// ISH_ERR_TIMEOUT (-12), mirrored from `include/ishembed.h` in the
    /// ish-arm64-pkg checkout. `IshSession.read(timeout:)` throws this when
    /// the wait window elapses with no session event; it is a poll tick, not
    /// a failure — the package's C oneshot path (host/ishembed.c) loops on
    /// it the same way. It is not part of the package's Swift API surface,
    /// hence the mirrored constant; re-verify on dependency upgrades.
    private static let ishReadTimeoutCode: Int32 = -12

    /// Serializes terminate()/close() for one session. `IshSession` fetches
    /// its raw pointer under its own lock but dereferences it after
    /// unlocking, so a terminate racing another thread's close() could touch
    /// freed C state. Holding this box's lock across each full call removes
    /// that interleaving: a terminate either lands before close or no-ops
    /// after it. `ish_embed_session_terminate` is a non-blocking frame send
    /// (host/ishembed.c), so holding the lock across it does not stall the
    /// cancellation path.
    private final class IshSessionLifecycleBox: @unchecked Sendable {
        private let lock = NSLock()
        private var session: IshSession?

        init(_ session: IshSession) {
            self.session = session
        }

        func terminate() {
            lock.lock()
            defer { lock.unlock() }
            guard let session else { return }
            try? session.terminate()
        }

        func close() {
            lock.lock()
            defer { lock.unlock() }
            guard let session = self.session else { return }
            self.session = nil
            session.close()
        }
    }

    /// Resolved once per process; the kernel boots once and VM directory
    /// trees persist in fakefs, so re-running `ensureDefaultVM` (a guest
    /// `ls` round-trip) on every command is pure overhead.
    private var cachedDefaultVM: IshVM?

    private func resolvedDefaultVM() throws -> IshVM {
        if let cachedDefaultVM { return cachedDefaultVM }
        let vm = try IshInstance.shared.ensureDefaultVM()
        cachedDefaultVM = vm
        return vm
    }

    private func runSpawned(
        command: String,
        timeoutSeconds: TimeInterval,
        onOutput: (@Sendable (IOSEmbeddedIshOutputChunk) -> Void)?
    ) async -> IOSEmbeddedIshCommandResult {
        do {
            try await bootIfNeeded()
            // Boot work is detached; a cancellation that arrived during it is
            // honored here, before paying for a spawn that would be killed
            // right away.
            try Task.checkCancellation()
            let defaultVM = try resolvedDefaultVM()
            let session = try IshInstance.shared.spawn(
                IshSpawnOptions(
                    argv: ["/bin/sh", "-lc", command],
                    cwd: "/root",
                    chrootPath: defaultVM.guestPath
                )
            )
            let lifecycle = IshSessionLifecycleBox(session)
            let hooks = IOSEmbeddedIshSessionHooks(
                read: { timeout in
                    switch try session.read(timeout: timeout) {
                    case .data(let data, let kind, _):
                        return .data(data, isStderr: kind == .stderr)
                    case .exited(let code, _):
                        return .exited(code)
                    }
                },
                terminate: { lifecycle.terminate() },
                close: { lifecycle.close() },
                isReadTimeout: { error in
                    if case IshError.raw(let code, _) = error {
                        return code == Self.ishReadTimeoutCode
                    }
                    return false
                },
                describeFailure: { error in
                    (error as? IshError)?.description ?? error.localizedDescription
                }
            )
            let effectiveTimeout = max(1, timeoutSeconds)
            return await withTaskCancellationHandler {
                await Task.detached(priority: .userInitiated) {
                    Self.readSessionEvents(
                        hooks: hooks,
                        timeoutSeconds: effectiveTimeout,
                        onOutput: onOutput
                    )
                }.value
            } onCancel: {
                lifecycle.terminate()
            }
        } catch is CancellationError {
            return IOSEmbeddedIshCommandResult(
                exitCode: nil,
                stdout: "",
                stderr: "",
                timedOut: false,
                error: "Embedded iSH command was cancelled."
            )
        } catch {
            return IOSEmbeddedIshCommandResult(
                exitCode: nil,
                stdout: "",
                stderr: "",
                timedOut: false,
                error: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            )
        }
    }

    private func bootIfNeeded() async throws {
        guard !booted else { return }
        if let bootTask {
            // A concurrent run is already booting; share its outcome instead
            // of racing a second rootfs copy / kernel boot.
            return try await bootTask.value
        }
        // Rootfs copy and kernel boot are blocking work; keep them off the
        // actor's cooperative thread so other runs and cancellation stay
        // responsive during the (first-run, multi-second) prepare.
        let task = Task.detached(priority: .userInitiated) {
            let rootfsURL = try Self.preparedWritableRootfsURL()
            try IshInstance.shared.boot(
                IshInstance.BootOptions(
                    rootfsPath: rootfsURL.path,
                    workdir: "/"
                )
            )
        }
        bootTask = task
        do {
            try await task.value
            booted = true
            bootTask = nil
        } catch {
            bootTask = nil
            throw error
        }
    }

    private static func preparedWritableRootfsURL() throws -> URL {
        let fileManager = FileManager.default
        let support = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support
            .appendingPathComponent("embedded-ish", isDirectory: true)
            .appendingPathComponent(Self.rootfsVersion, isDirectory: true)
            .appendingPathComponent(Self.bundledRootfsName, isDirectory: true)

        if isValidRootfs(at: directory, requiresSentinel: true) {
            return directory
        }

        let parent = directory.deletingLastPathComponent()
        do {
            try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
            let bundled = try bundledRootfsURL()
            let staging = parent.appendingPathComponent(".\(Self.bundledRootfsName)-\(UUID().uuidString).tmp", isDirectory: true)
            let backup = parent.appendingPathComponent(".\(Self.bundledRootfsName)-\(UUID().uuidString).bak", isDirectory: true)
            var movedExistingToBackup = false

            defer {
                if fileManager.fileExists(atPath: staging.path) {
                    try? fileManager.removeItem(at: staging)
                }
                if fileManager.fileExists(atPath: backup.path) {
                    try? fileManager.removeItem(at: backup)
                }
            }

            try fileManager.copyItem(at: bundled, to: staging)
            guard isValidRootfs(at: staging) else {
                throw IOSEmbeddedIshRuntimeError.invalidBundledRootfs(staging)
            }
            try writeRootfsSentinel(at: staging)
            guard isValidRootfs(at: staging, requiresSentinel: true) else {
                throw IOSEmbeddedIshRuntimeError.invalidBundledRootfs(staging)
            }

            if fileManager.fileExists(atPath: directory.path) {
                try fileManager.moveItem(at: directory, to: backup)
                movedExistingToBackup = true
            }
            do {
                try fileManager.moveItem(at: staging, to: directory)
            } catch {
                if movedExistingToBackup,
                   !fileManager.fileExists(atPath: directory.path),
                   fileManager.fileExists(atPath: backup.path) {
                    try? fileManager.moveItem(at: backup, to: directory)
                }
                throw error
            }
            return directory
        } catch let error as IOSEmbeddedIshRuntimeError {
            throw error
        } catch {
            throw IOSEmbeddedIshRuntimeError.rootfsCopyFailed(error.localizedDescription)
        }
    }

    private static func bundledRootfsURL() throws -> URL {
        let fileManager = FileManager.default
        if let direct = Bundle.main.url(forResource: Self.bundledRootfsName, withExtension: nil),
           isValidRootfs(at: direct) {
            return direct
        }
        if let resourceURL = Bundle.main.resourceURL {
            let candidate = resourceURL.appendingPathComponent(Self.bundledRootfsName, isDirectory: true)
            if isValidRootfs(at: candidate) {
                return candidate
            }
            if let enumerator = fileManager.enumerator(
                at: resourceURL,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            ) {
                for case let url as URL in enumerator where url.lastPathComponent == Self.bundledRootfsName {
                    if isValidRootfs(at: url) {
                        return url
                    }
                }
            }
        }
        throw IOSEmbeddedIshRuntimeError.missingBundledRootfs
    }

    private static func writeRootfsSentinel(at url: URL) throws {
        let marker = "AmberAgent embedded iSH rootfs \(Self.rootfsVersion)\n"
        try marker.write(
            to: url.appendingPathComponent(Self.rootfsReadySentinel),
            atomically: true,
            encoding: .utf8
        )
    }

    private static func isValidRootfs(at url: URL, requiresSentinel: Bool = false) -> Bool {
        let fileManager = FileManager.default
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            return false
        }
        let required = [
            url.appendingPathComponent("meta.db").path,
            url.appendingPathComponent("data").path,
            url.appendingPathComponent("data/bin/sh").path,
            url.appendingPathComponent("data/bin/busybox").path,
            url.appendingPathComponent("data/etc/alpine-release").path
        ]
        let bundledSQLiteSidecars = [
            url.appendingPathComponent("meta.db-shm").path,
            url.appendingPathComponent("meta.db-wal").path
        ]
        if requiresSentinel {
            guard fileManager.fileExists(atPath: url.appendingPathComponent(Self.rootfsReadySentinel).path) else {
                return false
            }
        } else if !bundledSQLiteSidecars.allSatisfy({ fileManager.fileExists(atPath: $0) }) {
            return false
        }
        return required.allSatisfy { fileManager.fileExists(atPath: $0) }
    }
    #endif
}

// MARK: - Session event pump

extension IOSEmbeddedIshRuntime {
    /// Blocking event pump for one spawned session. Runs on a detached task
    /// because `hooks.read` is a synchronous blocking call; each poll blocks
    /// at most 0.2s so deadlines and termination are observed promptly.
    /// `hooks.close()` runs exactly once on every exit path.
    static func readSessionEvents(
        hooks: IOSEmbeddedIshSessionHooks,
        timeoutSeconds: TimeInterval,
        onOutput: (@Sendable (IOSEmbeddedIshOutputChunk) -> Void)?
    ) -> IOSEmbeddedIshCommandResult {
        defer { hooks.close() }
        var stdout = Data()
        var stderr = Data()
        var stdoutDecoder = IOSEmbeddedIshUTF8StreamDecoder()
        var stderrDecoder = IOSEmbeddedIshUTF8StreamDecoder()
        var exitCode: Int?
        var timedOut = false
        var failure: String?
        let deadline = Date().addingTimeInterval(timeoutSeconds)

        eventLoop: while true {
            let remaining = deadline.timeIntervalSinceNow
            if remaining <= 0 {
                timedOut = true
                hooks.terminate()
                break
            }
            do {
                switch try hooks.read(min(remaining, 0.2)) {
                case .data(let data, let isStderr):
                    if isStderr {
                        emit(
                            data: data,
                            isStderr: true,
                            stdout: &stdout,
                            stderr: &stderr,
                            decoder: &stderrDecoder,
                            onOutput: onOutput
                        )
                    } else {
                        emit(
                            data: data,
                            isStderr: false,
                            stdout: &stdout,
                            stderr: &stderr,
                            decoder: &stdoutDecoder,
                            onOutput: onOutput
                        )
                    }
                case .exited(let code):
                    exitCode = Int(code)
                    break eventLoop
                }
            } catch {
                if hooks.isReadTimeout(error) { continue }
                failure = hooks.describeFailure(error)
                break
            }
        }

        flushDecoders(stdout: &stdoutDecoder, stderr: &stderrDecoder, onOutput: onOutput)
        return IOSEmbeddedIshCommandResult(
            exitCode: exitCode,
            stdout: String(decoding: stdout, as: UTF8.self),
            stderr: String(decoding: stderr, as: UTF8.self),
            timedOut: timedOut,
            error: timedOut ? "Embedded iSH command timed out." : failure
        )
    }

    /// End-of-stream flush so preview text held back at a split multibyte
    /// boundary is not silently missing from the tail.
    static func flushDecoders(
        stdout: inout IOSEmbeddedIshUTF8StreamDecoder,
        stderr: inout IOSEmbeddedIshUTF8StreamDecoder,
        onOutput: (@Sendable (IOSEmbeddedIshOutputChunk) -> Void)?
    ) {
        let stdoutFlush = stdout.finish()
        if !stdoutFlush.isEmpty {
            onOutput?(IOSEmbeddedIshOutputChunk(text: stdoutFlush, isStderr: false))
        }
        let stderrFlush = stderr.finish()
        if !stderrFlush.isEmpty {
            onOutput?(IOSEmbeddedIshOutputChunk(text: stderrFlush, isStderr: true))
        }
    }

    static func emit(
        data: Data,
        isStderr: Bool,
        stdout: inout Data,
        stderr: inout Data,
        decoder: inout IOSEmbeddedIshUTF8StreamDecoder,
        onOutput: (@Sendable (IOSEmbeddedIshOutputChunk) -> Void)?
    ) {
        guard !data.isEmpty else { return }
        if isStderr {
            stderr.append(data)
        } else {
            stdout.append(data)
        }
        let text = decoder.decode(appending: data)
        guard !text.isEmpty else { return }
        onOutput?(IOSEmbeddedIshOutputChunk(
            text: text,
            isStderr: isStderr
        ))
    }
}

extension IOSEmbeddedIshRuntime: IOSEmbeddedIshJobBackend {
    func runJob(
        command: String,
        timeoutSeconds: TimeInterval,
        onOutput: @escaping @Sendable (IOSEmbeddedIshOutputChunk) -> Void
    ) async -> IOSEmbeddedIshCommandResult {
        await run(command: command, timeoutSeconds: timeoutSeconds, onOutput: onOutput)
    }
}
