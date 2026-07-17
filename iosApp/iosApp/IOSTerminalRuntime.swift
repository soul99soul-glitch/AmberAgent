import Foundation
import Observation

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
            summary: "推荐的远程命令运行环境。当前支持密码认证。"
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
            supportsPTY: false,
            supportsPackageInstall: false,
            supportsLongRunningJobs: false,
            supportsInteractiveLogin: false,
            supportsFileSync: false,
            appStoreSafeByDefault: false,
            supportsExternalCLIByDefault: false,
            licenseClass: .gplReviewRequired,
            summary: "Experimental embedded iSH oneshot runner for short bounded commands; no PTY, stdin, live session, or immediate cancellation yet."
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

enum IOSAdvancedTaskKind: String, Codable, CaseIterable, Identifiable {
    case subAgent = "sub_agent"
    case modelCouncil = "model_council"
    case remoteCommand = "remote_command"
    case toolApproval = "tool_approval"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .subAgent: "SubAgent"
        case .modelCouncil: "模型议会"
        case .remoteCommand: "远程执行"
        case .toolApproval: "工具审批"
        }
    }
}

enum IOSAdvancedTaskStatus: String, Codable, CaseIterable, Identifiable {
    case queued
    case running
    case approvalRequired = "approval_required"
    case completed
    case failed
    case cancelled
    case timedOut = "timed_out"
    case interrupted

    var id: String { rawValue }

    var isTerminal: Bool {
        switch self {
        case .completed, .failed, .cancelled, .timedOut, .interrupted:
            true
        case .queued, .running, .approvalRequired:
            false
        }
    }

    var title: String {
        switch self {
        case .queued: "排队中"
        case .running: "运行中"
        case .approvalRequired: "等待确认"
        case .completed: "已完成"
        case .failed: "失败"
        case .cancelled: "已取消"
        case .timedOut: "已超时"
        case .interrupted: "已中断"
        }
    }
}

struct IOSAdvancedTaskRecord: Codable, Equatable, Identifiable {
    var id: String
    var kind: IOSAdvancedTaskKind
    var title: String
    var objective: String
    var status: IOSAdvancedTaskStatus
    var roleId: String?
    var toolScope: [String]
    var budgetSummary: String
    var connectionSummary: String
    var commandPreview: String
    var resultSummary: String
    var logTail: String
    var error: String
    var retryable: Bool
    var cancelCapability: Bool
    var sourceToolName: String
    var metadata: [String: String]
    var createdAt: Date
    var updatedAt: Date

    var canRetry: Bool {
        retryable && status.isTerminal
    }

    var compactSummary: String {
        if !resultSummary.isEmpty { return resultSummary }
        if !error.isEmpty { return error }
        if !logTail.isEmpty { return logTail }
        return objective
    }
}

@MainActor
@Observable
final class IOSAdvancedTaskStore {
    static let shared = IOSAdvancedTaskStore()

    private static let defaultStorageKey = "app.amber.ios.advancedTasks.v1"
    private static let maxPersistedTasks = 80
    private static let maxFieldLength = 4_000
    private static let maxLogLength = 24_000

    var tasks: [IOSAdvancedTaskRecord]

    @ObservationIgnored private let userDefaults: UserDefaults
    @ObservationIgnored private let storageKey: String
    @ObservationIgnored private let encoder = JSONEncoder()
    @ObservationIgnored private let decoder = JSONDecoder()

    init(
        userDefaults: UserDefaults = .standard,
        storageKey: String = IOSAdvancedTaskStore.defaultStorageKey
    ) {
        self.userDefaults = userDefaults
        self.storageKey = storageKey
        self.tasks = Self.load(from: userDefaults, key: storageKey, decoder: decoder)
    }

    @discardableResult
    func startTask(
        kind: IOSAdvancedTaskKind,
        title: String,
        objective: String,
        roleId: String? = nil,
        toolScope: [String] = [],
        budgetSummary: String = "",
        connectionSummary: String = "",
        commandPreview: String = "",
        sourceToolName: String = "",
        metadata: [String: String] = [:],
        now: Date = Date()
    ) -> IOSAdvancedTaskRecord {
        let record = IOSAdvancedTaskRecord(
            id: UUID().uuidString,
            kind: kind,
            title: Self.redacted(title),
            objective: Self.redacted(objective),
            status: .running,
            roleId: roleId,
            toolScope: toolScope.map(Self.redacted),
            budgetSummary: Self.redacted(budgetSummary),
            connectionSummary: Self.redacted(connectionSummary),
            commandPreview: Self.redactedCommand(commandPreview),
            resultSummary: "",
            logTail: "",
            error: "",
            retryable: false,
            cancelCapability: true,
            sourceToolName: sourceToolName,
            metadata: metadata.mapValues(Self.redacted),
            createdAt: now,
            updatedAt: now
        )
        upsert(record)
        return record
    }

    @discardableResult
    func updateTask(
        id: String,
        status: IOSAdvancedTaskStatus? = nil,
        resultSummary: String? = nil,
        logTail: String? = nil,
        error: String? = nil,
        retryable: Bool? = nil,
        cancelCapability: Bool? = nil,
        metadata: [String: String]? = nil,
        now: Date = Date()
    ) -> IOSAdvancedTaskRecord? {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return nil }
        if let status { tasks[index].status = status }
        if let resultSummary { tasks[index].resultSummary = Self.redacted(resultSummary) }
        if let logTail { tasks[index].logTail = Self.redactedLog(logTail) }
        if let error { tasks[index].error = Self.redacted(error) }
        if let retryable { tasks[index].retryable = retryable }
        if let cancelCapability { tasks[index].cancelCapability = cancelCapability }
        if let metadata {
            for (key, value) in metadata {
                tasks[index].metadata[key] = Self.redacted(value)
            }
        }
        tasks[index].updatedAt = now
        persist()
        return tasks[index]
    }

    func appendLog(id: String, chunk: String, now: Date = Date()) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].logTail = Self.redactedLog(tasks[index].logTail + chunk)
        tasks[index].updatedAt = now
        persist()
    }

    @discardableResult
    func markRetryRequested(id: String, now: Date = Date()) -> IOSAdvancedTaskRecord? {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return nil }
        tasks[index].status = .queued
        tasks[index].retryable = false
        tasks[index].cancelCapability = false
        tasks[index].error = ""
        tasks[index].updatedAt = now
        persist()
        return tasks[index]
    }

    func recent(kind: IOSAdvancedTaskKind? = nil, limit: Int = 8) -> [IOSAdvancedTaskRecord] {
        tasks
            .filter { kind == nil || $0.kind == kind }
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(limit)
            .map { $0 }
    }

    /// Called once at app startup. A process-local Council run cannot still own
    /// work after relaunch, so persisted running rows must become honest terminals.
    @discardableResult
    func markInterruptedCouncilTasks(now: Date = Date()) -> [String] {
        let ids = tasks
            .filter { $0.kind == .modelCouncil && $0.status == .running }
            .map(\.id)
        guard !ids.isEmpty else { return [] }

        let idSet = Set(ids)
        for index in tasks.indices where idSet.contains(tasks[index].id) {
            tasks[index].status = .interrupted
            tasks[index].resultSummary = "模型议会因应用进程结束而中断。"
            tasks[index].error = ""
            tasks[index].retryable = false
            tasks[index].cancelCapability = false
            tasks[index].metadata["interruption_reason"] = "process_terminated"
            tasks[index].updatedAt = now
        }
        persist()
        return ids
    }

    @discardableResult
    func upsert(_ record: IOSAdvancedTaskRecord) -> IOSAdvancedTaskRecord {
        var sanitized = record
        sanitized.title = Self.redacted(record.title)
        sanitized.objective = Self.redacted(record.objective)
        sanitized.commandPreview = Self.redactedCommand(record.commandPreview)
        sanitized.logTail = Self.redactedLog(record.logTail)
        sanitized.resultSummary = Self.redacted(record.resultSummary)
        sanitized.error = Self.redacted(record.error)
        sanitized.metadata = record.metadata.mapValues(Self.redacted)

        if let index = tasks.firstIndex(where: { $0.id == sanitized.id }) {
            tasks[index] = sanitized
        } else {
            tasks.insert(sanitized, at: 0)
        }
        tasks = Array(tasks.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.maxPersistedTasks))
        persist()
        return sanitized
    }

    func replaceAll(_ records: [IOSAdvancedTaskRecord]) {
        tasks = Array(records.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.maxPersistedTasks))
        persist()
    }

    private func persist() {
        if let data = try? encoder.encode(tasks) {
            userDefaults.set(data, forKey: storageKey)
        }
    }

    private static func load(from defaults: UserDefaults, key: String, decoder: JSONDecoder) -> [IOSAdvancedTaskRecord] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? decoder.decode([IOSAdvancedTaskRecord].self, from: data) else {
            return []
        }
        return Array(decoded.sorted { $0.updatedAt > $1.updatedAt }.prefix(maxPersistedTasks))
    }

    static func redactedCommand(_ value: String) -> String {
        redacted(value)
    }

    static func redactedLog(_ value: String) -> String {
        let redacted = redacted(value)
        guard redacted.count > maxLogLength else { return redacted }
        return String(redacted.suffix(maxLogLength))
    }

    static func redacted(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let patterns = [
            #"(?i)bearer\s+[A-Za-z0-9._~+/=-]{6,}"#,
            #"(?i)(api[_-]?key|token|password|passwd|secret|authorization)\s*[:=]\s*["']?[^"'\s]+["']?"#,
            #"sk-[A-Za-z0-9_\-]{12,}"#,
            #"(?i)(ssh-rsa|ssh-ed25519)\s+[A-Za-z0-9+/=]+"#
        ]
        var output = trimmed
        for pattern in patterns {
            output = output.replacingOccurrences(
                of: pattern,
                with: "[redacted]",
                options: .regularExpression
            )
        }
        guard output.count > maxFieldLength else { return output }
        return String(output.prefix(maxFieldLength)) + "..."
    }
}

enum IOSRemoteCommandPolicyResult {
    case success(String)
    case failure(String)
}

enum IOSRemoteCommandPolicy {
    static func validate(_ command: String) -> IOSRemoteCommandPolicyResult {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failure("Command is required.")
        }
        guard trimmed.count <= 2_000 else {
            return .failure("Command is too long for a single remote execution task.")
        }

        let lowercased = trimmed.lowercased()
        let blockedFragments = [
            "rm -rf /",
            "mkfs",
            "diskutil erase",
            "shutdown",
            "reboot",
            ":(){",
            "dd if=",
            ">:",
            "chmod -r 777 /"
        ]
        if let fragment = blockedFragments.first(where: { lowercased.contains($0) }) {
            return .failure("Blocked potentially destructive command fragment: \(fragment)")
        }
        return .success(trimmed)
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
            return startEmbeddedIshJob(
                command: command,
                now: now,
                timeoutSeconds: timeoutSeconds
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
        if job.runtime == .ishExperimental {
            job.error = "Embedded iSH commands cannot be interrupted by wait timeout; this job is still running until the command exits or its own timeout fires."
            job.updatedAt = Date()
            return job.snapshot
        }
        job.task?.cancel()
        job.status = .timedOut
        job.error = IOSSSHError.commandTimedOut.localizedDescription
        job.updatedAt = Date()
        return job.snapshot
    }

    func stopJob(id: String) -> IOSTerminalJobSnapshot? {
        guard let job = jobs[id] else { return nil }
        guard !job.status.isTerminal else { return job.snapshot }
        if job.runtime == .ishExperimental {
            job.error = "Embedded iSH commands cannot be interrupted after launch; this job will finish when the command exits or its timeout fires."
            job.updatedAt = Date()
            return job.snapshot
        }
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

    private func startEmbeddedIshJob(
        command: String,
        now: Date,
        timeoutSeconds: TimeInterval
    ) -> IOSTerminalJobSnapshot {
        switch IOSRemoteCommandPolicy.validate(command) {
        case .success:
            break
        case .failure(let message):
            return failedSnapshot(
                runtime: .ishExperimental,
                command: command,
                now: now,
                message: message
            )
        }

        let job = IOSTerminalJobState(
            id: UUID().uuidString,
            runtime: .ishExperimental,
            startedAt: now,
            status: .running
        )
        jobs[job.id] = job

        let jobId = job.id
        job.task = Task {
            let result = await IOSEmbeddedIshRuntime.shared.run(
                command: command,
                timeoutSeconds: timeoutSeconds
            )
            guard !Task.isCancelled else {
                updateJob(
                    id: jobId,
                    status: .cancelled,
                    exitCode: nil,
                    output: nil,
                    error: IOSSSHError.commandCancelled.localizedDescription
                )
                return
            }
            let output = Self.embeddedIshOutput(stdout: result.stdout, stderr: result.stderr)
            let status: IOSTerminalJobStatus
            if result.timedOut {
                status = .timedOut
            } else if result.exitCode == 0, result.error == nil {
                status = .completed
            } else {
                status = .failed
            }
            updateJob(
                id: jobId,
                status: status,
                exitCode: result.exitCode,
                output: output,
                error: result.error ?? (status == .failed ? "Embedded iSH command exited with \(result.exitCode ?? -1)." : nil)
            )
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

    private static func embeddedIshOutput(stdout: String, stderr: String) -> String {
        var parts: [String] = []
        if !stdout.isEmpty {
            parts.append(stdout)
        }
        if !stderr.isEmpty {
            parts.append("[stderr]\n\(stderr)")
        }
        return parts.joined(separator: stdout.hasSuffix("\n") ? "" : "\n")
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
