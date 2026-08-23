import Foundation
import UIKit

enum IOSIshToolCatalog {
    static let supportedToolNames: Set<String> = ["ish_handoff"]
}

@MainActor
enum IOSIshHandoffExecutor {
    private static let maxScriptLength = 32_000
    private static let maxFilenameLength = 80

    static func execute(input: String, now: Date = Date()) -> String {
        do {
            let request = try parseRequest(input)
            let filename = safeFilename(request.filename, now: now)
            let script = try normalizedScript(command: request.command, script: request.script)
            let localURL = try writeScript(script, filename: filename)
            let pasteCommand = pasteReadyCommand(script: script, filename: filename)
            UIPasteboard.general.string = pasteCommand

            return IOSWorkspaceStore.json([
                "ok": true,
                "tool": "ish_handoff",
                "status": "handoff_prepared",
                "mode": "clipboard_handoff",
                "purpose": request.purpose ?? "",
                "script_file_name": filename,
                "amber_script_path": localURL.path,
                "copied_to_clipboard": true,
                "clipboard_command_chars": pasteCommand.count,
                "clipboard_command_preview": String(pasteCommand.prefix(900)),
                "requires_user_paste": true,
                "open_ish_supported": false,
                "stdout_available": false,
                "stderr_available": false,
                "exit_code_available": false,
                "result_collection": "manual",
                "user_next_step": "Open iSH, paste the clipboard contents, and press Return. AmberAgent cannot read iSH output or files automatically."
            ])
        } catch {
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": "ish_handoff",
                "status": "handoff_failed",
                "error": (error as? LocalizedError)?.errorDescription ?? error.localizedDescription,
                "stdout_available": false,
                "stderr_available": false,
                "exit_code_available": false
            ])
        }
    }

    static func approvalPreview(input: String) -> IshHandoffToolApprovalRequest? {
        let parsed = (try? parseRequest(input)) ?? IOSIshHandoffRequest(command: input, script: nil, filename: nil, purpose: nil)
        let previewSource = parsed.script?.nilIfBlank ?? parsed.command?.nilIfBlank ?? input
        return IshHandoffToolApprovalRequest(
            id: chatInputDigest(for: input),
            mode: .handoff,
            commandPreview: truncated(previewSource, maxLength: 320),
            filename: safeFilename(parsed.filename, now: Date()),
            reason: "iSH 交接会把脚本复制到剪贴板，需你切到 iSH 后手动粘贴执行；Amber 无法读取 stdout/stderr。"
        )
    }

    private static func parseRequest(_ input: String) throws -> IOSIshHandoffRequest {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw IOSIshHandoffError.emptyInput }
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return IOSIshHandoffRequest(command: trimmed, script: nil, filename: nil, purpose: nil)
        }
        return IOSIshHandoffRequest(
            command: stringValue(object["command"]),
            script: stringValue(object["script"]),
            filename: stringValue(object["filename"]),
            purpose: stringValue(object["purpose"])
        )
    }

    private static func normalizedScript(command: String?, script: String?) throws -> String {
        let rawScript = script?.nilIfBlank
        let rawCommand = command?.nilIfBlank
        let body: String
        if let rawScript {
            body = rawScript
        } else if let rawCommand {
            body = "set -eu\n\(rawCommand)"
        } else {
            throw IOSIshHandoffError.emptyInput
        }
        guard body.count <= maxScriptLength else {
            throw IOSIshHandoffError.scriptTooLarge(maxScriptLength)
        }
        let withShebang = body.hasPrefix("#!") ? body : "#!/bin/sh\n\(body)"
        return withShebang.hasSuffix("\n") ? withShebang : withShebang + "\n"
    }

    private static func writeScript(_ script: String, filename: String) throws -> URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSHomeDirectory())
        let directory = base.appendingPathComponent("ish-handoff", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(filename, isDirectory: false)
        guard let data = script.data(using: .utf8) else {
            throw IOSIshHandoffError.invalidUTF8
        }
        try data.write(to: url, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
        return url
    }

    private static func pasteReadyCommand(script: String, filename: String) -> String {
        var delimiter = "AMBER_ISH_\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        while script.contains(delimiter) {
            delimiter = "AMBER_ISH_\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        }
        let escapedFilename = filename
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "$", with: "\\$")
            .replacingOccurrences(of: "`", with: "\\`")
        return """
        mkdir -p "$HOME/amberagent"
        target="$HOME/amberagent/\(escapedFilename)"
        cat > "$target" <<'\(delimiter)'
        \(script)\(delimiter)
        chmod +x "$target"
        sh "$target"
        """
    }

    private static func safeFilename(_ raw: String?, now: Date) -> String {
        let fallback = "amber-ish-\(Int(now.timeIntervalSince1970)).sh"
        let candidate = raw?.nilIfBlank ?? fallback
        let mapped = candidate.map { character -> Character in
            if character.isLetter || character.isNumber || character == "." || character == "-" || character == "_" {
                return character
            }
            return "_"
        }
        var output = String(mapped)
        if output.count > maxFilenameLength {
            output = String(output.prefix(maxFilenameLength))
        }
        if output == "." || output == ".." || output.isEmpty {
            output = fallback
        }
        if !output.hasSuffix(".sh") {
            output += ".sh"
        }
        return output
    }

    private static func stringValue(_ value: Any?) -> String? {
        (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func truncated(_ value: String, maxLength: Int) -> String {
        guard value.count > maxLength else { return value }
        return String(value.prefix(maxLength)) + "..."
    }
}

private struct IOSIshHandoffRequest {
    var command: String?
    var script: String?
    var filename: String?
    var purpose: String?
}

private enum IOSIshHandoffError: LocalizedError {
    case emptyInput
    case invalidUTF8
    case scriptTooLarge(Int)

    var errorDescription: String? {
        switch self {
        case .emptyInput:
            "ish_handoff requires a non-empty command or script."
        case .invalidUTF8:
            "iSH handoff script could not be encoded as UTF-8."
        case .scriptTooLarge(let limit):
            "iSH handoff script is too large. Keep it under \(limit) characters."
        }
    }
}
