import Foundation
#if ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES
import IshEmbed
#endif

struct IOSEmbeddedIshCommandResult: Equatable {
    let exitCode: Int?
    let stdout: String
    let stderr: String
    let timedOut: Bool
    let error: String?
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

    private init() {}

    func run(command: String, timeoutSeconds: TimeInterval = 60) async -> IOSEmbeddedIshCommandResult {
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
        do {
            try bootIfNeeded()
            let vm = try IshInstance.shared.ensureDefaultVM()
            let result = try IshInstance.shared.runOneshot(
                IshSpawnOptions(
                    argv: ["/bin/sh", "-lc", trimmed],
                    cwd: "/root",
                    timeout: timeoutSeconds,
                    chrootPath: vm.guestPath
                )
            )
            let stdout = String(decoding: result.stdoutData, as: UTF8.self)
            let stderr = String(decoding: result.stderrData, as: UTF8.self)
            let exitCode = Int(result.exitCode)
            return IOSEmbeddedIshCommandResult(
                exitCode: exitCode,
                stdout: stdout,
                stderr: stderr,
                timedOut: result.timedOut,
                error: result.timedOut ? "Embedded iSH command timed out." : nil
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
    private func bootIfNeeded() throws {
        guard !booted else { return }
        let rootfsURL = try preparedWritableRootfsURL()
        try IshInstance.shared.boot(
            IshInstance.BootOptions(
                rootfsPath: rootfsURL.path,
                workdir: "/"
            )
        )
        booted = true
    }

    private func preparedWritableRootfsURL() throws -> URL {
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

    private func bundledRootfsURL() throws -> URL {
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

    private func writeRootfsSentinel(at url: URL) throws {
        let marker = "AmberAgent embedded iSH rootfs \(Self.rootfsVersion)\n"
        try marker.write(
            to: url.appendingPathComponent(Self.rootfsReadySentinel),
            atomically: true,
            encoding: .utf8
        )
    }

    private func isValidRootfs(at url: URL, requiresSentinel: Bool = false) -> Bool {
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
