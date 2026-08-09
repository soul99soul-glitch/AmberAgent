import Foundation
@preconcurrency import Shared

/// 工具输出硬上限（对齐 exec 工具 `max_output_chars` 语义）。所有工具的文本输出
/// 经统一收口：总输出超限时截断并追加可见标记；JSON 形态输出保形截断，不破坏
/// ok/status/exit_code 等判定键。
enum IOSToolOutputLimits {
    /// 单次工具输出（search_web 格式化结果 / scrape_web JSON / 漏斗文本）总上限。
    static let maxOutputChars = 12_000
    /// 搜索结果单条 snippet 上限。
    static let maxSnippetChars = 1_200
    /// 与 IOSContextCompactionCoordinator.compactedToolOutputMarker 同文的压缩占位
    /// 标记（压缩处理过的输出豁免收口截断，避免二次截断）。
    static let compactedToolOutputMarker = "[tool output compacted]"
    /// 截断标记：`\n…[truncated N chars]`
    static func truncationMarker(droppedChars: Int) -> String {
        "\n…[truncated \(droppedChars) chars]"
    }
}

struct MemoryToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let action: String
    let scope: String?
    let kind: String?
    let contentPreview: String?
    let targetId: Int?
    let expectedUpdatedAt: Int64?
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

enum McpSkillImportMutationKind: String, Codable, Equatable {
    case new
    case update
}

enum McpSkillImportFileChangeKind: String, Codable, Equatable {
    case added
    case modified
    case removed
}

struct McpSkillImportFileChange: Identifiable, Codable, Equatable {
    let path: String
    let kind: McpSkillImportFileChangeKind
    let beforeText: String?
    let afterText: String?

    var id: String { path }

    init(
        path: String,
        kind: McpSkillImportFileChangeKind,
        beforeText: String? = nil,
        afterText: String? = nil
    ) {
        self.path = path
        self.kind = kind
        self.beforeText = beforeText
        self.afterText = afterText
    }
}

struct McpSkillImportPreview: Codable, Equatable {
    let skillName: String
    let mutationKind: McpSkillImportMutationKind
    let baseHash: String?
    let candidateHash: String
    let beforeSummary: String
    let afterSummary: String
    let changedFiles: [McpSkillImportFileChange]
    let containsMcpConfig: Bool

    init(
        skillName: String,
        mutationKind: McpSkillImportMutationKind,
        baseHash: String? = nil,
        candidateHash: String,
        beforeSummary: String,
        afterSummary: String,
        changedFiles: [McpSkillImportFileChange] = [],
        containsMcpConfig: Bool = false
    ) {
        self.skillName = skillName
        self.mutationKind = mutationKind
        self.baseHash = baseHash
        self.candidateHash = candidateHash
        self.beforeSummary = beforeSummary
        self.afterSummary = afterSummary
        self.changedFiles = changedFiles
        self.containsMcpConfig = containsMcpConfig
    }
}

struct McpToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let serverName: String
    let toolName: String
    let argumentsPreview: String
    let reason: String
    let skillImportPreview: McpSkillImportPreview?

    init(
        id: String,
        serverName: String,
        toolName: String,
        argumentsPreview: String,
        reason: String,
        skillImportPreview: McpSkillImportPreview? = nil
    ) {
        self.id = id
        self.serverName = serverName
        self.toolName = toolName
        self.argumentsPreview = argumentsPreview
        self.reason = reason
        self.skillImportPreview = skillImportPreview
    }

    var title: String {
        if toolName.hasPrefix("skill_") || toolName == "mcp_import_from_skill" || toolName == "mcp_test" {
            "确认扩展操作"
        } else {
            "执行 MCP 工具"
        }
    }
}

struct ChatAskUserRequest: Identifiable, Equatable {
    let id: String
    let question: String
    let options: [String]

    var title: String { "需要你的回答" }
}

enum CouncilToolApprovalKind: Equatable {
    case council
    case subAgent
}

struct CouncilToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let kind: CouncilToolApprovalKind
    let objectivePreview: String
    let maxSeats: Int?
    let reason: String

    var title: String {
        switch kind {
        case .council: "启动模型议会"
        case .subAgent: "调度子代理"
        }
    }

    var capabilityId: String {
        switch kind {
        case .council: "ios.agent.model_council_run"
        case .subAgent: "ios.agent.subagent_dispatch"
        }
    }

    var systemImage: String {
        switch kind {
        case .council: "person.3.sequence"
        case .subAgent: "person.crop.circle.badge.gearshape"
        }
    }
}

enum IshToolApprovalMode: String, Equatable {
    case handoff
    case embeddedExecute
}

struct IshHandoffToolApprovalRequest: Identifiable, Equatable {
    let id: String
    let mode: IshToolApprovalMode
    let commandPreview: String
    let filename: String
    let reason: String

    var title: String {
        switch mode {
        case .handoff: "交接到 iSH"
        case .embeddedExecute: "执行内置 iSH"
        }
    }

    var primaryChip: (systemImage: String, title: String) {
        switch mode {
        case .handoff: ("doc.on.clipboard", "复制到剪贴板")
        case .embeddedExecute: ("terminal", "本地执行")
        }
    }

    var secondaryChip: (systemImage: String, title: String) {
        switch mode {
        case .handoff: ("hand.tap", "需手动粘贴")
        case .embeddedExecute: ("arrowshape.turn.up.left", "回传输出")
        }
    }
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

/// P0-b: flattened `mcp__{server}__{tool}` declarations from the current MCP
/// discovery directory, for ENABLED servers only. The caller (ChatViewModel)
/// appends the result to the run exposure bridge's input tools — the bridge
/// defers every `mcp__*` name behind tool_search, so MCP off / no discovered
/// tools yields zero change. Cross-server sanitized name collisions keep the
/// first occurrence (KMP `mcpExpandedToolDeclarations` dedups within a server).
@MainActor
func expandedMcpToolDeclarations(mcpManager: IOSMcpManager) -> [Tool] {
    let enabledServerNames = Set(mcpManager.servers.filter(\.enabled).map(\.name))
    var declarations: [Tool] = []
    var seenNames = Set<String>()
    for discovered in mcpManager.tools where enabledServerNames.contains(discovered.serverName) {
        let spec = McpDiscoveredToolSpec(
            name: discovered.tool.name,
            description: discovered.tool.description,
            inputSchemaJson: discovered.tool.inputSchema
        )
        for tool in ToolKt.mcpExpandedToolDeclarations(serverName: discovered.serverName, discovered: [spec]) {
            guard seenNames.insert(tool.name).inserted else { continue }
            declarations.append(tool)
        }
    }
    return declarations
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
            expectedUpdatedAt: preview.expectedUpdatedAt,
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

    @MainActor
    static func ishHandoff(
        for toolCall: UIMessagePart.Tool,
        reason: String
    ) -> IshHandoffToolApprovalRequest? {
        if IOSEmbeddedIshToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            guard let preview = IOSEmbeddedIshExecuteExecutor.approvalPreview(input: toolCall.input) else {
                return nil
            }
            return IshHandoffToolApprovalRequest(
                id: ChatToolCallParsing.requestId(for: toolCall),
                mode: preview.mode,
                commandPreview: preview.commandPreview,
                filename: preview.filename,
                reason: reason
            )
        }
        guard let preview = IOSIshHandoffExecutor.approvalPreview(input: toolCall.input) else {
            return nil
        }
        return IshHandoffToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            mode: .handoff,
            commandPreview: preview.commandPreview,
            filename: preview.filename,
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

    /// P0-b: approval card for a flattened `mcp__{server}__{tool}` call. The
    /// input carries the tool's OWN arguments (no `{server, tool, arguments}`
    /// envelope), so the resolved directory target is passed in explicitly —
    /// same display shape and gate as `mcp_call`.
    static func expandedMcp(
        for toolCall: UIMessagePart.Tool,
        server: String,
        tool: String,
        reason: String
    ) -> McpToolApprovalRequest {
        McpToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            serverName: server,
            toolName: tool,
            argumentsPreview: ChatToolCallParsing.truncatedMcpArguments(ChatToolCallParsing.jsonObject(toolCall.input)),
            reason: reason
        )
    }

    static func extensionMutation(
        for toolCall: UIMessagePart.Tool,
        reason: String,
        skillImportPreview: McpSkillImportPreview? = nil
    ) -> McpToolApprovalRequest? {
        let args = ChatToolCallParsing.jsonObject(toolCall.input) ?? [:]
        let target = (args["name"] as? String)
            ?? (args["skill_name"] as? String)
            ?? (args["workspace_path"] as? String)
            ?? (args["server_id"] as? String)
            ?? toolCall.toolName
        let argumentsPreview: String
        if let preview = skillImportPreview {
            let action = preview.mutationKind == .new ? "新建" : "更新"
            let base = preview.baseHash.map { String($0.prefix(8)) } ?? "无"
            let candidate = String(preview.candidateHash.prefix(8))
            argumentsPreview = "\(action) \(preview.skillName) · \(preview.changedFiles.count) 个文件 · \(base)→\(candidate)"
        } else {
            // Always pass a JSON object; bare String fails JSONSerialization.
            argumentsPreview = ChatToolCallParsing.truncatedMcpArguments(
                args.isEmpty ? ["target": target] : args
            )
        }
        return McpToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            serverName: "local",
            toolName: toolCall.toolName,
            argumentsPreview: argumentsPreview,
            reason: reason,
            skillImportPreview: skillImportPreview
        )
    }

    static func council(
        for toolCall: UIMessagePart.Tool,
        reason: String
    ) -> CouncilToolApprovalRequest? {
        let args = ChatToolCallParsing.jsonObject(toolCall.input)
        let objective = args?["objective"] as? String ?? toolCall.input
        return CouncilToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            kind: .council,
            objectivePreview: ChatToolCallParsing.truncatedSearchTarget(objective),
            maxSeats: args?["max_seats"] as? Int,
            reason: reason
        )
    }

    static func subAgent(
        for toolCall: UIMessagePart.Tool,
        reason: String
    ) -> CouncilToolApprovalRequest? {
        guard toolCall.toolName == "subagent_dispatch" else { return nil }
        let args = ChatToolCallParsing.jsonObject(toolCall.input)
        let objective = args?["objective"] as? String ?? toolCall.input
        return CouncilToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            kind: .subAgent,
            objectivePreview: ChatToolCallParsing.truncatedSearchTarget(objective),
            maxSeats: nil,
            reason: reason
        )
    }

    static func askUser(
        for toolCall: UIMessagePart.Tool
    ) -> ChatAskUserRequest? {
        guard toolCall.toolName == "ask_user" else { return nil }
        let args = ChatToolCallParsing.jsonObject(toolCall.input) ?? [:]
        let question = (args["question"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let options = (ChatToolCallParsing.stringArray(args["options"]) ?? [])
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        // Deduplicate while preserving order.
        var seen = Set<String>()
        let uniqueOptions = options.filter { seen.insert($0).inserted }
        let resolvedQuestion: String
        if let question, !question.isEmpty {
            resolvedQuestion = question
        } else {
            let fallback = toolCall.input.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !fallback.isEmpty else { return nil }
            resolvedQuestion = fallback
        }
        let clippedOptions = Array(uniqueOptions.prefix(6))
        return ChatAskUserRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            question: resolvedQuestion,
            options: clippedOptions
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
        case .workspaceResult(let result), .webMountResult(let result), .ishExecuteResult(let result), .ishHandoffResult(let result):
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
        case .ishExecuteResult(let result):
            return result
        case .ishHandoffResult(let result):
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

    static func ishHandoffResultText(
        for toolCall: UIMessagePart.Tool,
        output: IOSLocalToolExecutionOutput
    ) -> String {
        let canReturnExecutionOutput = IOSEmbeddedIshToolCatalog.supportedToolNames.contains(toolCall.toolName)
        switch output {
        case .ishExecuteResult(let result):
            return result
        case .ishHandoffResult(let result):
            return result
        case .needsUserAction(let reason):
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "needs_user_action": true,
                "reason": reason,
                "stdout_available": canReturnExecutionOutput,
                "stderr_available": canReturnExecutionOutput,
                "exit_code_available": false
            ])
        case .denied(let reason):
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "denied": true,
                "reason": reason,
                "stdout_available": canReturnExecutionOutput,
                "stderr_available": canReturnExecutionOutput,
                "exit_code_available": false
            ])
        case .failed(let message):
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": message,
                "stdout_available": canReturnExecutionOutput,
                "stderr_available": canReturnExecutionOutput,
                "exit_code_available": false
            ])
        default:
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": "Unexpected output for iSH handoff tool.",
                "stdout_available": canReturnExecutionOutput,
                "stderr_available": canReturnExecutionOutput,
                "exit_code_available": false
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
        case .ishExecuteResult, .ishHandoffResult:
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolCall.toolName,
                "error": "Unexpected iSH output for WebMount tool."
            ])
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

    static func toolFailureJSON(
        toolName: String,
        reason: String,
        denied: Bool = false,
        cancelled: Bool = false,
        status: String? = nil
    ) -> String {
        var payload: [String: Any] = [
            "ok": false,
            "tool": toolName,
            "reason": reason
        ]
        if let status {
            payload["status"] = status
        }
        if denied {
            payload["denied"] = true
            payload["policy"] = "user_denied"
        }
        if cancelled {
            payload["cancelled"] = true
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "\(toolName) failed: \(reason)"
        }
        return text
    }

    /// Structured error for a tool call whose `input` failed
    /// `UIMessagePart.Tool.parseInputStrict()` (I-2, fail-closed). Not executed —
    /// this is the entire output the model sees, so it must both satisfy the
    /// `tool_arguments_invalid` / `message` / `raw_prefix` contract callers rely on
    /// and be recognized as a failure by `failureReason(from:)` above (hence `ok`
    /// and `reason`, matched the same way `toolFailureJSON` is).
    static func toolArgumentsInvalidJSON(
        toolName: String,
        message: String,
        rawPrefix: String
    ) -> String {
        let payload: [String: Any] = [
            "ok": false,
            "tool": toolName,
            "error": "tool_arguments_invalid",
            "reason": message,
            "message": message,
            "raw_prefix": rawPrefix
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "\(toolName) failed: tool_arguments_invalid: \(message)"
        }
        return text
    }

    /// 工具输出收口上限（对齐 exec 工具 `max_output_chars` 语义）：文本总量超
    /// [maxChars] 时截断并追加可见标记。JSON 形态输出保形截断（保留所有键与
    /// ok/status/exit_code 判定），普通文本保留头部、截断尾部。已带
    /// `[tool output compacted]` 压缩标记的输出豁免，不二次截断。
    static func cappedToolOutputParts(
        _ parts: [UIMessagePart],
        maxChars: Int = IOSToolOutputLimits.maxOutputChars
    ) -> [UIMessagePart] {
        let texts = parts.compactMap { $0 as? UIMessagePart.Text }
        guard !texts.isEmpty else { return parts }
        let totalChars = texts.reduce(0) { $0 + $1.text.count }
        guard totalChars > maxChars else { return parts }
        guard !partsContainsCompactedMarker(parts) else { return parts }
        if texts.count == 1,
           let only = texts.first,
           let capped = cappedStructuredJSON(only.text, maxChars: maxChars) {
            return [UIMessagePart.Text(text: capped, metadata: only.metadata)]
        }
        return cappedPlainTextParts(parts, maxChars: maxChars)
    }

    private static func partsContainsCompactedMarker(_ parts: [UIMessagePart]) -> Bool {
        parts.contains { part in
            guard let text = part as? UIMessagePart.Text else { return false }
            return text.text.contains(IOSToolOutputLimits.compactedToolOutputMarker)
        }
    }

    /// JSON 对象保形截断：从最长字符串值开始减半，保持所有键（含 ok/status/
    /// exit_code 判定键）与结构，最终追加 truncated 标记。不可保形时返回 nil。
    private static func cappedStructuredJSON(_ raw: String, maxChars: Int) -> String? {
        guard let data = raw.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        var working: [String: Any] = object
        var string = jsonString(working)
        guard string.count > maxChars else { return raw }
        var truncated = false
        var iterations = 0
        while string.count > maxChars, iterations < 128 {
            iterations += 1
            // 地板 12：短于此的字符串值不再减半——判定键值（如 "timed_out"，9 字符）
            // 被截断会让失败被误读为成功（checker 整体复核低级项）。
            guard let longest = longestStringValue(in: working, minimumLength: 12),
                  longest.value.count > 12 else { break }
            let shorter = String(longest.value.prefix(max(longest.value.count / 2, 12)))
            working = replacingStringValue(at: longest.path, in: working, with: shorter) as! [String: Any]
            truncated = true
            string = jsonString(working)
        }
        guard truncated else { return nil }
        working["truncated"] = true
        working["truncation_note"] = IOSToolOutputLimits.truncationMarker(
            droppedChars: max(raw.count - string.count, 0)
        )
        string = jsonString(working)
        if string.count > maxChars {
            working["truncation_note"] = nil
            string = jsonString(working)
        }
        return string
    }

    private static func cappedPlainTextParts(_ parts: [UIMessagePart], maxChars: Int) -> [UIMessagePart] {
        var remaining = maxChars
        var droppedChars = 0
        var result: [UIMessagePart] = []
        for part in parts {
            guard let text = part as? UIMessagePart.Text else {
                result.append(part)
                continue
            }
            if remaining <= 0 {
                droppedChars += text.text.count
                continue
            }
            let keep = String(text.text.prefix(remaining))
            droppedChars += text.text.count - keep.count
            remaining -= keep.count
            result.append(UIMessagePart.Text(text: keep, metadata: text.metadata))
        }
        guard droppedChars > 0, let lastTextIndex = result.indices.last(where: { result[$0] is UIMessagePart.Text }) else {
            return result
        }
        let last = result[lastTextIndex] as! UIMessagePart.Text
        let marked = UIMessagePart.Text(
            text: last.text + IOSToolOutputLimits.truncationMarker(droppedChars: droppedChars),
            metadata: last.metadata
        )
        result[lastTextIndex] = marked
        return result
    }

    /// 树中（字典/数组递归）最长的字符串值及其路径。
    private static func longestStringValue(
        in value: Any,
        minimumLength: Int,
        path: [Any] = []
    ) -> (path: [Any], value: String)? {
        if let string = value as? String, string.count > minimumLength {
            return (path, string)
        }
        if let dict = value as? [String: Any] {
            var best: (path: [Any], value: String)?
            for (key, child) in dict {
                if let candidate = longestStringValue(in: child, minimumLength: minimumLength, path: path + [key]),
                   candidate.value.count > (best?.value.count ?? 0) {
                    best = candidate
                }
            }
            return best
        }
        if let array = value as? [Any] {
            var best: (path: [Any], value: String)?
            for (index, child) in array.enumerated() {
                if let candidate = longestStringValue(in: child, minimumLength: minimumLength, path: path + [index]),
                   candidate.value.count > (best?.value.count ?? 0) {
                    best = candidate
                }
            }
            return best
        }
        return nil
    }

    /// 按路径（String 键 / Int 下标）替换树中的字符串值。
    private static func replacingStringValue(
        at path: [Any],
        index: Int = 0,
        in value: Any,
        with replacement: String
    ) -> Any {
        guard index < path.count else { return value }
        let key = path[index]
        if let dict = value as? [String: Any], let keyString = key as? String {
            var next = dict
            if index == path.count - 1 {
                next[keyString] = replacement
            } else if let child = next[keyString] {
                next[keyString] = replacingStringValue(at: path, index: index + 1, in: child, with: replacement)
            }
            return next
        }
        if let array = value as? [Any], let arrayIndex = key as? Int {
            var next = array
            if index == path.count - 1 {
                next[arrayIndex] = replacement
            } else if arrayIndex < next.count {
                next[arrayIndex] = replacingStringValue(
                    at: path,
                    index: index + 1,
                    in: next[arrayIndex],
                    with: replacement
                )
            }
            return next
        }
        return value
    }

    private static func jsonString(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    nonisolated static func failureReason(from output: [UIMessagePart]) -> String? {
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
                    ?? "工具执行失败"
            }
            if let denied = object["denied"] as? Bool, denied {
                return stringValue(in: object, keys: ["reason", "error", "detail", "message"]) ?? "用户已拒绝"
            }
            if let status = (object["status"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
                if ["error", "failed", "failure", "denied", "timed_out", "timeout"].contains(status) {
                    return stringValue(in: object, keys: ["reason", "error", "detail", "message"])
                        ?? "工具执行失败"
                }
            }
            if let exitCode = object["exit_code"] as? Int, exitCode != 0 {
                return stringValue(in: object, keys: ["stderr", "error", "reason", "message"]) ?? "exit \(exitCode)"
            }
        }
        return nil
    }

    nonisolated static func imageFailureReason(from output: [UIMessagePart]) -> String? {
        let hasImage = output.contains { $0 is UIMessagePart.Image }
        var sawStructuredSuccess = false
        let texts = output.compactMap { ($0 as? UIMessagePart.Text)?.text }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        guard !texts.isEmpty else { return nil }

        for text in texts {
            guard let data = text.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                continue
            }
            if let reason = failureReason(from: [UIMessagePart.Text(text: text, metadata: nil)]) {
                return reason
            }
            if object["ok"] as? Bool == true {
                sawStructuredSuccess = true
                continue
            }
            if let status = (object["status"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
                if ["ok", "success", "succeeded", "completed"].contains(status) {
                    sawStructuredSuccess = true
                    continue
                }
            }
            if (object["source"] as? String) == "generate_image",
               object["files"] != nil {
                sawStructuredSuccess = true
                continue
            }
            if (object["tool"] as? String) == "generate_image",
               let reason = stringValue(in: object, keys: ["reason", "error", "detail", "message"]) {
                return reason
            }
        }

        if hasImage || sawStructuredSuccess {
            return nil
        }
        return texts.first
    }

    nonisolated static func imageFailureReason(
        in messages: [UIMessage],
        matching targetToolCall: UIMessagePart.Tool? = nil
    ) -> String? {
        for message in messages where message.role == MessageRole.assistant {
            for case let tool as UIMessagePart.Tool in message.parts {
                guard tool.toolName == "generate_image",
                      !tool.output.isEmpty else {
                    continue
                }
                if let targetToolCall,
                   chatToolCallKey(tool) != chatToolCallKey(targetToolCall) {
                    continue
                }
                if let reason = imageFailureReason(from: tool.output) {
                    return reason
                }
            }
        }
        return nil
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
