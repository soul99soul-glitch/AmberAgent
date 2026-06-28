import Foundation
@preconcurrency import Shared

struct MemoryToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let action: String
    let scope: String?
    let kind: String?
    let contentPreview: String?
    let targetId: Int?
    let reason: String

    var title: String {
        switch action {
        case "create", "add", "write":
            "保存记忆"
        case "edit", "update":
            "更新记忆"
        case "delete", "remove":
            "删除记忆"
        default:
            "修改记忆"
        }
    }
}

struct WebMountToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let toolName: String
    let siteId: String
    let siteName: String
    let host: String
    let reason: String

    var title: String {
        switch toolName {
        case "wm_clear_session":
            "清除 WebMount Session"
        default:
            "执行 WebMount 前台动作"
        }
    }
}

struct WorkspaceToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let toolName: String
    let action: String
    let target: String
    let isWrite: Bool
    let reason: String

    var title: String {
        isWrite ? "修改 Workspace" : "读取 Workspace"
    }
}

struct SearchToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let toolName: String
    let target: String
    let providerName: String
    let providerType: String
    let reason: String

    var title: String {
        switch toolName {
        case "scrape_web":
            "读取网页"
        default:
            "执行网络搜索"
        }
    }
}

struct McpToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let serverName: String
    let toolName: String
    let argumentsPreview: String
    let reason: String

    var title: String { "执行 MCP 工具" }
}

enum ChatToolCallParsing {
    static func jsonObject(_ string: String) -> [String: Any]? {
        guard let data = string.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func stringArray(_ value: Any?) -> [String]? {
        if let values = value as? [String] {
            return values
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
        }
        if let text = value as? String {
            let values = text
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            return values.isEmpty ? nil : values
        }
        return nil
    }

    static func requestId(for toolCall: UIMessagePart.Tool) -> String {
        let rawId = toolCall.toolCallId.trimmingCharacters(in: .whitespacesAndNewlines)
        return rawId.isEmpty ? inputDigest(for: toolCall.input) : rawId
    }

    static func truncatedMcpArguments(_ value: Any?, maxLength: Int = 360) -> String {
        guard let value else { return "{}" }
        let text: String
        if let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
           let serialized = String(data: data, encoding: .utf8) {
            text = serialized
        } else {
            text = String(describing: value)
        }
        guard text.count > maxLength else { return text }
        return String(text.prefix(maxLength)) + "..."
    }

    static func truncatedSearchTarget(_ value: String, maxLength: Int = 180) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > maxLength else { return trimmed }
        return String(trimmed.prefix(maxLength)) + "..."
    }

    private static func inputDigest(for text: String) -> String {
        chatInputDigest(for: text)
    }
}

enum ChatToolApprovalRequestBuilder {
    static func search(
        for toolCall: UIMessagePart.Tool,
        reason: String,
        settings: Settings?
    ) -> SearchToolApprovalRequest? {
        let target: String
        switch toolCall.toolName {
        case "search_web":
            guard let request = try? IOSSearchExecutor.searchRequest(
                from: toolCall.input,
                defaultMaxResults: Int(settings?.searchCommonOptions.resultSize ?? 10)
            ) else {
                return nil
            }
            target = request.query
        case "scrape_web":
            guard let request = try? IOSSearchExecutor.scrapeRequest(from: toolCall.input) else {
                return nil
            }
            target = request.url.absoluteString
        default:
            return nil
        }

        let selection = IOSSearchExecutor.searchProviderSelection(settings: settings)
        return SearchToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            toolName: toolCall.toolName,
            target: ChatToolCallParsing.truncatedSearchTarget(target),
            providerName: toolCall.toolName == "scrape_web" ? "公开网页读取" : selection.providerName,
            providerType: toolCall.toolName == "scrape_web" ? "scrape_web" : selection.providerType,
            reason: reason
        )
    }

    @MainActor
    static func workspace(
        for toolCall: UIMessagePart.Tool,
        reason: String,
        localToolExecutor: IOSLocalToolExecutor?
    ) -> WorkspaceToolApprovalRequest? {
        guard let preview = localToolExecutor?.workspaceApprovalPreview(
            toolName: toolCall.toolName,
            input: toolCall.input
        ) else {
            return nil
        }
        return WorkspaceToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            toolName: preview.toolName,
            action: preview.action,
            target: preview.target,
            isWrite: preview.isWrite,
            reason: reason
        )
    }

    static func memory(
        for toolCall: UIMessagePart.Tool,
        reason: String
    ) -> MemoryToolApprovalRequest? {
        guard let preview = IOSMemoryToolExecutor.approvalPreview(input: toolCall.input) else {
            return nil
        }
        return MemoryToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            action: preview.action,
            scope: preview.scope,
            kind: preview.kind,
            contentPreview: preview.contentPreview,
            targetId: preview.targetId,
            reason: reason
        )
    }

    @MainActor
    static func webMount(
        for toolCall: UIMessagePart.Tool,
        reason: String,
        localToolExecutor: IOSLocalToolExecutor?
    ) -> WebMountToolApprovalRequest? {
        guard let preview = localToolExecutor?.webMountApprovalPreview(
            toolName: toolCall.toolName,
            input: toolCall.input
        ) else {
            return nil
        }
        return WebMountToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            toolName: preview.toolName,
            siteId: preview.siteId,
            siteName: preview.siteName,
            host: preview.host,
            reason: reason
        )
    }

    static func mcp(
        for toolCall: UIMessagePart.Tool,
        reason: String
    ) -> McpToolApprovalRequest? {
        guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
              let server = args["server"] as? String,
              let tool = args["tool"] as? String else {
            return nil
        }
        return McpToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            serverName: server,
            toolName: tool,
            argumentsPreview: ChatToolCallParsing.truncatedMcpArguments(args["arguments"]),
            reason: reason
        )
    }
}

@MainActor
enum ChatToolOutputFormatter {
    static func subAgentOutcome(
        for toolName: String,
        output: IOSLocalToolExecutionOutput
    ) -> IOSAgentToolOutcome {
        switch output {
        case .selectedFilePreview(let read):
            return .filled(IOSWorkspaceStore.json([
                "ok": true,
                "tool": toolName,
                "file_name": read.fileName,
                "file_type": read.fileType,
                "bytes_read": read.bytesRead,
                "total_bytes": read.totalBytes,
                "character_count": read.characterCount,
                "is_truncated": read.isTruncated,
                "note": read.note ?? "",
                "preview": read.preview
            ]))
        case .permissionsStatus(let snapshot):
            let capabilities = snapshot.capabilities.map { item in
                [
                    "id": item.id,
                    "title": item.title,
                    "status": item.status,
                    "policy": item.policy,
                    "model_tools": item.modelToolNames,
                    "blocked_tools": item.blockedToolNames
                ] as [String: Any]
            }
            return .filled(IOSWorkspaceStore.json([
                "ok": true,
                "tool": toolName,
                "platform": snapshot.platform,
                "capabilities": capabilities
            ]))
        case .workspaceResult(let result), .webMountResult(let result):
            return .filled(result)
        case .needsUserAction(let reason):
            return .denied(reason)
        case .denied(let reason):
            return .denied(reason)
        case .failed(let message):
            return .failed(message)
        }
    }

    static func workspaceResultText(
        for toolCall: UIMessagePart.Tool,
        output: IOSLocalToolExecutionOutput
    ) -> String {
        switch output {
        case .workspaceResult(let result):
            return result
        case .needsUserAction(let reason):
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "needs_user_action": true,
                "reason": reason
            ])
        case .denied(let reason):
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "denied": true,
                "reason": reason
            ])
        case .failed(let message):
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": message
            ])
        case .selectedFilePreview:
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": "Unexpected selected-file output for Workspace tool."
            ])
        case .permissionsStatus(let snapshot):
            return IOSWorkspaceStore.json([
                "ok": true,
                "tool": toolCall.toolName,
                "platform": snapshot.platform
            ])
        case .webMountResult:
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": "Unexpected WebMount output for Workspace tool."
            ])
        }
    }

    static func webMountResultText(
        for toolCall: UIMessagePart.Tool,
        output: IOSLocalToolExecutionOutput
    ) -> String {
        switch output {
        case .webMountResult(let result):
            return result
        case .needsUserAction(let reason):
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolCall.toolName,
                "needs_user_action": true,
                "reason": reason
            ])
        case .denied(let reason):
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolCall.toolName,
                "denied": true,
                "reason": reason
            ])
        case .failed(let message):
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": message
            ])
        case .selectedFilePreview:
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": "Unexpected selected-file output for WebMount tool."
            ])
        case .workspaceResult:
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": "Unexpected Workspace output for WebMount tool."
            ])
        case .permissionsStatus(let snapshot):
            return IOSWebMountController.json([
                "ok": true,
                "tool": toolCall.toolName,
                "platform": snapshot.platform
            ])
        }
    }

    static func searchFailureJSON(
        toolName: String,
        reason: String,
        denied: Bool = false
    ) -> String {
        var payload: [String: Any] = [
            "ok": false,
            "tool": toolName,
            "reason": reason
        ]
        if denied {
            payload["denied"] = true
            payload["policy"] = "user_denied"
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "\(toolName) failed: \(reason)"
        }
        return text
    }

    static func imageFailureJSON(reason: String) -> String {
        let payload: [String: Any] = [
            "ok": false,
            "tool": "generate_image",
            "reason": reason
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "generate_image failed: \(reason)"
        }
        return text
    }

    nonisolated static func imageFailureReason(from output: [UIMessagePart]) -> String? {
        let texts = output.compactMap { ($0 as? UIMessagePart.Text)?.text }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        guard !texts.isEmpty else { return nil }

        for text in texts {
            guard let data = text.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                continue
            }
            if object["ok"] as? Bool == false {
                return stringValue(in: object, keys: ["reason", "error", "detail", "message"])
            }
            if (object["tool"] as? String) == "generate_image",
               let reason = stringValue(in: object, keys: ["reason", "error", "detail", "message"]) {
                return reason
            }
        }

        return texts.first
    }

    nonisolated private static func stringValue(in object: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = object[key] as? String {
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { return trimmed }
            }
        }
        return nil
    }
}
