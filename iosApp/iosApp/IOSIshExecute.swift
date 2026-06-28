import Foundation

enum IOSEmbeddedIshToolCatalog {
    #if ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES
    static let supportedToolNames: Set<String> = ["ios_ish_execute"]
    static let capabilityStatus: IOSCapabilityStatus = .supported
    static let unavailableReason: String? = nil
    #else
    static let supportedToolNames: Set<String> = []
    static let capabilityStatus: IOSCapabilityStatus = .unsupported
    static let unavailableReason: String? = "Embedded iSH is only linked in the ExperimentalGPL target."
    #endif
}

struct IOSEmbeddedIshExecuteRequest {
    let command: String?
    let script: String?
    let timeoutSeconds: TimeInterval
    let purpose: String?
}

@MainActor
enum IOSEmbeddedIshExecuteExecutor {
    private static let maxScriptLength = 32_000
    private static let defaultTimeout: TimeInterval = 60
    private static let maxTimeout: TimeInterval = 180

    static func execute(input: String) async -> String {
        do {
            let request = try parseRequest(input)
            let command = try normalizedCommand(command: request.command, script: request.script)
            let result = await IOSEmbeddedIshRuntime.shared.run(
                command: command,
                timeoutSeconds: request.timeoutSeconds
            )
            let status: String
            if result.timedOut {
                status = "timed_out"
            } else if let exitCode = result.exitCode, exitCode == 0, result.error == nil {
                status = "completed"
            } else {
                status = "failed"
            }
            let exitCodeValue: Any = result.exitCode.map { $0 as Any } ?? NSNull()
            return IOSWorkspaceStore.json([
                "ok": status == "completed",
                "tool": "ios_ish_execute",
                "runtime": "embedded_ish",
                "status": status,
                "purpose": request.purpose ?? "",
                "exit_code": exitCodeValue,
                "stdout": result.stdout,
                "stderr": result.stderr,
                "stdout_available": true,
                "stderr_available": true,
                "exit_code_available": result.exitCode != nil,
                "timed_out": result.timedOut,
                "error": result.error ?? ""
            ])
        } catch {
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": "ios_ish_execute",
                "runtime": "embedded_ish",
                "status": "failed",
                "error": (error as? LocalizedError)?.errorDescription ?? error.localizedDescription,
                "stdout": "",
                "stderr": "",
                "stdout_available": false,
                "stderr_available": false,
                "exit_code_available": false
            ])
        }
    }

    static func approvalPreview(input: String) -> IshHandoffToolApprovalRequest? {
        let parsed = (try? parseRequest(input)) ?? IOSEmbeddedIshExecuteRequest(
            command: input,
            script: nil,
            timeoutSeconds: defaultTimeout,
            purpose: nil
        )
        let previewSource = parsed.script?.nilIfBlank ?? parsed.command?.nilIfBlank ?? input
        return IshHandoffToolApprovalRequest(
            id: chatInputDigest(for: input),
            mode: .embeddedExecute,
            commandPreview: truncated(previewSource, maxLength: 1_200),
            filename: "embedded iSH · /bin/sh",
            reason: "内置 iSH 会在 Amber 沙盒内执行 Linux 命令，并把 stdout/stderr/exit code 回传给 Agent。"
        )
    }

    private static func parseRequest(_ input: String) throws -> IOSEmbeddedIshExecuteRequest {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw IOSEmbeddedIshExecuteError.emptyInput }
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return IOSEmbeddedIshExecuteRequest(
                command: trimmed,
                script: nil,
                timeoutSeconds: defaultTimeout,
                purpose: nil
            )
        }
        let timeoutValue = numericValue(object["timeout_seconds"]) ?? defaultTimeout
        return IOSEmbeddedIshExecuteRequest(
            command: stringValue(object["command"]),
            script: stringValue(object["script"]),
            timeoutSeconds: min(max(1, timeoutValue), maxTimeout),
            purpose: stringValue(object["purpose"])
        )
    }

    private static func normalizedCommand(command: String?, script: String?) throws -> String {
        let rawCommand = command?.nilIfBlank
        let rawScript = script?.nilIfBlank
        if rawCommand != nil, rawScript != nil {
            throw IOSEmbeddedIshExecuteError.ambiguousInput
        }
        guard let value = rawScript ?? rawCommand else {
            throw IOSEmbeddedIshExecuteError.emptyInput
        }
        guard value.count <= maxScriptLength else {
            throw IOSEmbeddedIshExecuteError.scriptTooLarge(maxScriptLength)
        }
        return value
    }

    private static func stringValue(_ value: Any?) -> String? {
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : string
    }

    private static func numericValue(_ value: Any?) -> TimeInterval? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let double = value as? Double { return double }
        if let int = value as? Int { return Double(int) }
        if let string = value as? String { return Double(string) }
        return nil
    }

    private static func truncated(_ value: String, maxLength: Int) -> String {
        guard value.count > maxLength else { return value }
        return String(value.prefix(maxLength)) + "\n..."
    }
}

enum IOSEmbeddedIshExecuteError: LocalizedError {
    case emptyInput
    case ambiguousInput
    case scriptTooLarge(Int)

    var errorDescription: String? {
        switch self {
        case .emptyInput:
            "Provide exactly one of command or script."
        case .ambiguousInput:
            "Provide command or script, not both."
        case .scriptTooLarge(let maxLength):
            "Embedded iSH script is too large. Maximum length is \(maxLength) characters."
        }
    }
}
