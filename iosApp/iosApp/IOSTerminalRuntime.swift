import Foundation

enum IOSTerminalRuntimeKind: String, CaseIterable, Codable, Identifiable {
    case remoteSSH = "remote_ssh"
    case localIOSTools = "local_ios_tools"
    case remoteMosh = "remote_mosh"
    case ishExperimental = "ish_experimental"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .remoteSSH: "Remote SSH"
        case .localIOSTools: "Local iOS Tools"
        case .remoteMosh: "Remote Mosh"
        case .ishExperimental: "iSH Experimental"
        }
    }
}

enum IOSTerminalRuntimeTier: String {
    case stable = "Stable"
    case experimental = "Experimental"
}

enum IOSTerminalLicenseClass: String {
    case permissive = "Permissive"
    case gplReviewRequired = "GPL review required"
}

struct IOSTerminalRuntimeCapability: Identifiable {
    let runtime: IOSTerminalRuntimeKind
    let tier: IOSTerminalRuntimeTier
    let supportsPTY: Bool
    let supportsPackageInstall: Bool
    let supportsLongRunningJobs: Bool
    let supportsInteractiveLogin: Bool
    let supportsFileSync: Bool
    let appStoreSafeByDefault: Bool
    let supportsExternalCLIByDefault: Bool
    let licenseClass: IOSTerminalLicenseClass
    let summary: String

    var id: String { runtime.rawValue }
}

enum IOSTerminalRuntimeCapabilities {
    static let all: [IOSTerminalRuntimeCapability] = [
        IOSTerminalRuntimeCapability(
            runtime: .remoteSSH,
            tier: .stable,
            supportsPTY: false,
            supportsPackageInstall: false,
            supportsLongRunningJobs: true,
            supportsInteractiveLogin: false,
            supportsFileSync: false,
            appStoreSafeByDefault: true,
            supportsExternalCLIByDefault: false,
            licenseClass: .permissive,
            summary: "Recommended remote exec runner. Password auth only in this MVP."
        ),
        IOSTerminalRuntimeCapability(
            runtime: .localIOSTools,
            tier: .stable,
            supportsPTY: false,
            supportsPackageInstall: false,
            supportsLongRunningJobs: false,
            supportsInteractiveLogin: false,
            supportsFileSync: true,
            appStoreSafeByDefault: true,
            supportsExternalCLIByDefault: false,
            licenseClass: .permissive,
            summary: "Lightweight local file and script tools; not a Termux replacement."
        ),
        IOSTerminalRuntimeCapability(
            runtime: .remoteMosh,
            tier: .experimental,
            supportsPTY: true,
            supportsPackageInstall: true,
            supportsLongRunningJobs: true,
            supportsInteractiveLogin: true,
            supportsFileSync: true,
            appStoreSafeByDefault: false,
            supportsExternalCLIByDefault: false,
            licenseClass: .gplReviewRequired,
            summary: "Experimental resilient mobile session runtime."
        ),
        IOSTerminalRuntimeCapability(
            runtime: .ishExperimental,
            tier: .experimental,
            supportsPTY: true,
            supportsPackageInstall: true,
            supportsLongRunningJobs: true,
            supportsInteractiveLogin: true,
            supportsFileSync: true,
            appStoreSafeByDefault: false,
            supportsExternalCLIByDefault: false,
            licenseClass: .gplReviewRequired,
            summary: "Experimental embedded Linux runtime gated out of stable builds."
        )
    ]

    static func capability(for runtime: IOSTerminalRuntimeKind) -> IOSTerminalRuntimeCapability {
        all.first { $0.runtime == runtime }!
    }
}

enum IOSTerminalBuildPolicy {
    #if ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES
    static let experimentalRuntimesLinked = true
    #else
    static let experimentalRuntimesLinked = false
    #endif

    static var selectableRuntimes: [IOSTerminalRuntimeKind] {
        if experimentalRuntimesLinked {
            return IOSTerminalRuntimeKind.allCases
        }
        return [.remoteSSH, .localIOSTools]
    }

    static func normalizedDefaultRuntime(_ runtime: IOSTerminalRuntimeKind) -> IOSTerminalRuntimeKind {
        selectableRuntimes.contains(runtime) ? runtime : .remoteSSH
    }
}

struct IOSTerminalJobSnapshot: Identifiable {
    let id: String
    let runtime: IOSTerminalRuntimeKind
    let status: String
    let exitCode: Int?
    let outputTail: String
    let startedAt: Date
    let updatedAt: Date
    let error: String?
}

enum IOSTerminalJobStatus: String {
    case queued
    case running
    case completed
    case failed
    case cancelled
    case timedOut = "timed_out"

    var isTerminal: Bool {
        switch self {
        case .completed, .failed, .cancelled, .timedOut:
            true
        case .queued, .running:
            false
        }
    }
}

protocol IOSSSHRuntimeBackendProtocol: Sendable {
    func testConnection(profile: IOSSSHProfile, password: String) async throws -> IOSSSHConnectionProbeResult
    func execute(
        command: String,
        profile: IOSSSHProfile,
        password: String,
        timeout: TimeInterval,
        output: @escaping @Sendable (String) -> Void
    ) async throws -> IOSSSHCommandResult
}

private final class IOSTerminalJobState {
    let id: String
    let runtime: IOSTerminalRuntimeKind
    let startedAt: Date
    var updatedAt: Date
    var status: IOSTerminalJobStatus
    var exitCode: Int?
    var outputTail: String
    var error: String?
    var task: Task<Void, Never>?

    init(id: String, runtime: IOSTerminalRuntimeKind, startedAt: Date, status: IOSTerminalJobStatus) {
        self.id = id
        self.runtime = runtime
        self.startedAt = startedAt
        self.updatedAt = startedAt
        self.status = status
        self.outputTail = ""
    }

    var snapshot: IOSTerminalJobSnapshot {
        IOSTerminalJobSnapshot(
            id: id,
            runtime: runtime,
            status: status.rawValue,
            exitCode: exitCode,
            outputTail: outputTail,
            startedAt: startedAt,
            updatedAt: updatedAt,
            error: error
        )
    }
}

@MainActor
final class IOSTerminalRuntime {
    static let shared = IOSTerminalRuntime(sshBackend: IOSSSHRuntimeBackend())

    private static let outputTailLimit = 128 * 1024

    private let sshBackend: IOSSSHRuntimeBackendProtocol
    private var jobs: [String: IOSTerminalJobState] = [:]

    init(sshBackend: IOSSSHRuntimeBackendProtocol) {
        self.sshBackend = sshBackend
    }

    func testSSHConnection(profile: IOSSSHProfile, password: String) async throws -> IOSSSHConnectionProbeResult {
        let validated = try profile.validated()
        guard !password.isEmpty else { throw IOSSSHError.missingPassword }
        return try await sshBackend.testConnection(profile: validated, password: password)
    }

    func startJob(
        command: String,
        runtime: IOSTerminalRuntimeKind,
        experimentalEnabled: Bool
    ) async -> IOSTerminalJobSnapshot {
        await startJob(
            command: command,
            runtime: runtime,
            experimentalEnabled: experimentalEnabled,
            sshProfile: nil,
            sshPassword: nil
        )
    }

    func startJob(
        command: String,
        runtime: IOSTerminalRuntimeKind,
        experimentalEnabled: Bool,
        sshProfile: IOSSSHProfile?,
        sshPassword: String?,
        timeoutSeconds: TimeInterval = 60
    ) async -> IOSTerminalJobSnapshot {
        let now = Date()
        let capability = IOSTerminalRuntimeCapabilities.capability(for: runtime)
        if capability.tier == .experimental && !IOSTerminalBuildPolicy.experimentalRuntimesLinked {
            return failedSnapshot(
                runtime: runtime,
                command: command,
                now: now,
                message: "\(runtime.displayName) is not linked in this stable build."
            )
        }
        if capability.tier == .experimental && !experimentalEnabled {
            return failedSnapshot(
                runtime: runtime,
                command: command,
                now: now,
                message: "\(runtime.displayName) is experimental and disabled in the stable build."
            )
        }

        switch runtime {
        case .remoteSSH:
            return startSSHJob(
                command: command,
                now: now,
                profile: sshProfile,
                password: sshPassword,
                timeoutSeconds: timeoutSeconds
            )
        case .localIOSTools:
            return runLocalTool(command: command, now: now)
        case .remoteMosh:
            return failedSnapshot(
                runtime: runtime,
                command: command,
                now: now,
                message: "Remote Mosh requires GPL/license review before it can be linked into a distributable build."
            )
        case .ishExperimental:
            return failedSnapshot(
                runtime: runtime,
                command: command,
                now: now,
                message: "iSH Experimental requires the ExperimentalGPL target and source/license compliance bundle."
            )
        }
    }

    func readJob(id: String) -> IOSTerminalJobSnapshot? {
        jobs[id]?.snapshot
    }

    func waitJob(id: String, timeoutSeconds: TimeInterval = 60) async -> IOSTerminalJobSnapshot? {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            guard let job = jobs[id] else { return nil }
            if job.status.isTerminal {
                return job.snapshot
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        guard let job = jobs[id] else { return nil }
        guard !job.status.isTerminal else { return job.snapshot }
        job.task?.cancel()
        job.status = .timedOut
        job.error = IOSSSHError.commandTimedOut.localizedDescription
        job.updatedAt = Date()
        return job.snapshot
    }

    func stopJob(id: String) -> IOSTerminalJobSnapshot? {
        guard let job = jobs[id] else { return nil }
        guard !job.status.isTerminal else { return job.snapshot }
        job.task?.cancel()
        job.status = .cancelled
        job.error = IOSSSHError.commandCancelled.localizedDescription
        job.updatedAt = Date()
        return job.snapshot
    }

    private func startSSHJob(
        command: String,
        now: Date,
        profile: IOSSSHProfile?,
        password: String?,
        timeoutSeconds: TimeInterval
    ) -> IOSTerminalJobSnapshot {
        let validated: IOSSSHProfile
        do {
            guard let profile else { throw IOSSSHError.noDefaultProfile }
            validated = try profile.validated()
            guard validated.knownHostSHA256?.isEmpty == false else {
                throw IOSSSHError.hostKeyNotTrusted("Run Test Connection and Trust Host first.")
            }
            guard let password, !password.isEmpty else { throw IOSSSHError.missingPassword }
        } catch {
            return failedSnapshot(
                runtime: .remoteSSH,
                command: command,
                now: now,
                message: error.localizedDescription
            )
        }

        let job = IOSTerminalJobState(
            id: UUID().uuidString,
            runtime: .remoteSSH,
            startedAt: now,
            status: .running
        )
        jobs[job.id] = job

        let jobId = job.id
        let sshBackend = sshBackend
        let sshPassword = password ?? ""
        job.task = Task {
            do {
                let result = try await sshBackend.execute(
                    command: command,
                    profile: validated,
                    password: sshPassword,
                    timeout: timeoutSeconds,
                    output: { chunk in
                        Task { @MainActor in
                            self.appendOutput(chunk, to: jobId)
                        }
                    }
                )
                updateJob(
                    id: jobId,
                    status: result.exitCode == 0 ? .completed : .failed,
                    exitCode: result.exitCode,
                    output: result.output,
                    error: result.exitCode == 0 ? nil : "Remote command exited with \(result.exitCode ?? -1)."
                )
            } catch is CancellationError {
                updateJob(
                    id: jobId,
                    status: .cancelled,
                    exitCode: nil,
                    output: nil,
                    error: IOSSSHError.commandCancelled.localizedDescription
                )
            } catch IOSSSHError.commandTimedOut {
                updateJob(
                    id: jobId,
                    status: .timedOut,
                    exitCode: nil,
                    output: nil,
                    error: IOSSSHError.commandTimedOut.localizedDescription
                )
            } catch {
                updateJob(
                    id: jobId,
                    status: .failed,
                    exitCode: nil,
                    output: nil,
                    error: error.localizedDescription
                )
            }
        }
        return job.snapshot
    }

    private func appendOutput(_ chunk: String, to id: String) {
        guard let job = jobs[id], !chunk.isEmpty else { return }
        guard !job.status.isTerminal else { return }
        job.outputTail = limitedTail(job.outputTail + chunk)
        job.updatedAt = Date()
    }

    private func updateJob(
        id: String,
        status: IOSTerminalJobStatus,
        exitCode: Int?,
        output: String?,
        error: String?
    ) {
        guard let job = jobs[id] else { return }
        guard !job.status.isTerminal else { return }
        if let output {
            job.outputTail = limitedTail(output)
        }
        job.status = status
        job.exitCode = exitCode
        job.error = error
        job.updatedAt = Date()
    }

    private func limitedTail(_ value: String) -> String {
        let utf8 = Array(value.utf8)
        guard utf8.count > Self.outputTailLimit else { return value }
        let suffix = utf8.suffix(Self.outputTailLimit)
        return String(decoding: suffix, as: UTF8.self)
    }

    private func runLocalTool(command: String, now: Date) -> IOSTerminalJobSnapshot {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
        let output: String
        let exitCode: Int
        let error: String?

        switch trimmed {
        case "pwd":
            output = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.path ?? NSHomeDirectory()
            exitCode = 0
            error = nil
        case "ls":
            let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            let names = directory.flatMap { try? FileManager.default.contentsOfDirectory(atPath: $0.path) } ?? []
            output = names.sorted().joined(separator: "\n")
            exitCode = 0
            error = nil
        case "curl --version":
            output = "curl is planned through ios_system; native dependency is not linked in this stable skeleton."
            exitCode = 64
            error = "ios_system is not linked"
        case "python hello-world", "python3 hello-world":
            output = "Python is planned through ios_system/a-Shell-compatible tooling; native dependency is not linked yet."
            exitCode = 64
            error = "python runtime is not linked"
        default:
            output = "Unsupported local iOS tools command: \(trimmed)"
            exitCode = 64
            error = "unsupported local command"
        }

        return IOSTerminalJobSnapshot(
            id: UUID().uuidString,
            runtime: .localIOSTools,
            status: exitCode == 0 ? IOSTerminalJobStatus.completed.rawValue : IOSTerminalJobStatus.failed.rawValue,
            exitCode: exitCode,
            outputTail: output,
            startedAt: now,
            updatedAt: Date(),
            error: error
        )
    }

    private func failedSnapshot(
        runtime: IOSTerminalRuntimeKind,
        command: String,
        now: Date,
        message: String
    ) -> IOSTerminalJobSnapshot {
        IOSTerminalJobSnapshot(
            id: UUID().uuidString,
            runtime: runtime,
            status: IOSTerminalJobStatus.failed.rawValue,
            exitCode: nil,
            outputTail: "\(message)\nCommand: \(command)",
            startedAt: now,
            updatedAt: Date(),
            error: message
        )
    }
}
