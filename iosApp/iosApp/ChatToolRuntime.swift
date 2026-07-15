import Foundation
@preconcurrency import Shared

func chatToolCallKey(_ toolCall: UIMessagePart.Tool) -> String {
    let id = toolCall.toolCallId.trimmingCharacters(in: .whitespacesAndNewlines)
    if !id.isEmpty { return id }
    return "\(toolCall.toolName):\(toolCall.input)"
}

private final class IOSClosureToolExecutor: IOSToolExecutor {
    private let handler: @MainActor (String, String, Bool) async -> IOSAgentToolOutcome

    init(_ handler: @escaping @MainActor (String, String, Bool) async -> IOSAgentToolOutcome) {
        self.handler = handler
    }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        await handler(name, arguments, isUserInitiated)
    }
}

private extension IOSLocalToolExecutionOutput {
    var isSuccessfulToolResult: Bool {
        switch self {
        case .selectedFilePreview, .permissionsStatus, .ishExecuteResult, .ishHandoffResult, .workspaceResult, .webMountResult:
            true
        case .needsUserAction, .denied, .failed:
            false
        }
    }
}

enum ChatPendingToolKind {
    case search
    case workspace
    case ish
    case webMount
    case memory
    case image
    case advanced
}

struct ChatPendingToolCall {
    let kind: ChatPendingToolKind
    let toolCall: UIMessagePart.Tool
}

enum ChatToolApprovalPrompt {
    case memory(MemoryToolApprovalRequest)
    case search(SearchToolApprovalRequest)
    case webMount(WebMountToolApprovalRequest)
    case workspace(WorkspaceToolApprovalRequest)
    case ish(IshHandoffToolApprovalRequest)
    case mcp(McpToolApprovalRequest)

    var toolTitle: String {
        switch self {
        case .memory:
            "记忆写入"
        case .search(let request):
            request.toolName == "scrape_web" ? "网页读取" : "网络搜索"
        case .webMount:
            "WebMount"
        case .workspace:
            "Workspace"
        case .ish(let request):
            request.title
        case .mcp:
            "MCP 工具"
        }
    }

    var activityKind: AgentActivityKind {
        switch self {
        case .memory:
            .memory
        case .search(let request):
            request.toolName == "scrape_web" ? .web : .research
        case .webMount:
            .web
        case .workspace:
            .document
        case .ish:
            .command
        case .mcp:
            .workflow
        }
    }
}

enum ChatToolRuntimeResult {
    case completed([UIMessage])
    case waitingForApproval(ChatToolApprovalPrompt)
}

private enum ChatCodexImageConfig {
    case signedIn(providerId: String)
    case notSignedIn
    case notSelected
}

@MainActor
final class ChatToolRuntime {
    private let settingsStore: SettingsStore
    private let sharedSettings: IOSSharedSettingsStore
    private let localToolExecutor: IOSLocalToolExecutor?
    private let searchTransport: any IOSSearchHTTPTransport
    private let mcpManager: IOSMcpManager
    private lazy var subAgentRunner = SubAgentRunner()
    private lazy var councilRunner = CouncilRunner()

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        localToolExecutor: IOSLocalToolExecutor?,
        searchTransport: any IOSSearchHTTPTransport,
        mcpManager: IOSMcpManager
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.localToolExecutor = localToolExecutor
        self.searchTransport = searchTransport
        self.mcpManager = mcpManager
    }

    func backgroundToolExecutors(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String
    ) -> [String: any IOSToolExecutor] {
        var executors: [String: any IOSToolExecutor] = [:]

        for name in IOSSearchExecutor.supportedToolNames {
            executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                guard self.shouldExecuteSearchInBackground(toolName: toolName, arguments: arguments) else {
                    return .denied("后台生成期间需要回到 App 确认网络搜索或网页读取。")
                }
                return .filled(await self.dispatchSearchToolCall(self.toolCall(name: toolName, input: arguments)))
            }
        }

        if localToolExecutor != nil {
            for name in IOSWorkspaceToolCatalog.supportedToolNames {
                executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                    guard let self else { return .failed("Chat runtime is unavailable.") }
                    let toolCall = self.toolCall(name: toolName, input: arguments)
                    let output = await self.workspaceToolExecutionOutput(toolCall, isUserInitiated: false)
                    if case .needsUserAction(let reason) = output {
                        return .denied("后台生成期间需要回到 App 确认 Workspace 操作：\(reason)")
                    }
                    return .filled(ChatToolOutputFormatter.workspaceResultText(for: toolCall, output: output))
                }
            }

            for name in IOSIshToolCatalog.supportedToolNames.union(IOSEmbeddedIshToolCatalog.supportedToolNames) {
                executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                    guard let self else { return .failed("Chat runtime is unavailable.") }
                    let toolCall = self.toolCall(name: toolName, input: arguments)
                    let output = await self.ishToolExecutionOutput(toolCall, isUserInitiated: false)
                    if case .needsUserAction(let reason) = output {
                        return .denied("后台生成期间需要回到 App 确认 iSH 操作：\(reason)")
                    }
                    return .filled(ChatToolOutputFormatter.ishHandoffResultText(for: toolCall, output: output))
                }
            }
        }

        if isWebMountRuntimeEnabled {
            for name in IOSWebMountToolCatalog.supportedToolNames.union(IOSWebMountToolCatalog.unsupportedToolNames) {
                executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                    guard let self else { return .failed("Chat runtime is unavailable.") }
                    let toolCall = self.toolCall(name: toolName, input: arguments)
                    let output = await self.webMountToolExecutionOutput(toolCall, isUserInitiated: false)
                    if case .needsUserAction(let reason) = output {
                        return .denied("后台生成期间需要回到 App 确认 WebMount 操作：\(reason)")
                    }
                    return .filled(ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output))
                }
            }
        }

        executors["memory_tool"] = IOSClosureToolExecutor { [weak self] _, arguments, _ in
            guard let self else { return .failed("Chat runtime is unavailable.") }
            let policy = self.memoryToolWritePolicy(input: arguments, isUserInitiated: false)
            if case .needsUserAction(let reason) = policy {
                return .denied("后台生成期间需要回到 App 确认记忆写入：\(reason)")
            }
            return .filled(self.dispatchMemoryToolCall(self.toolCall(name: "memory_tool", input: arguments), writePolicy: policy))
        }

        executors["generate_image"] = IOSClosureToolExecutor { [weak self] _, arguments, _ in
            guard let self else { return .failed("Chat runtime is unavailable.") }
            return .filledParts(await self.dispatchImageToolCall(self.toolCall(name: "generate_image", input: arguments)))
        }

        // Advanced tools in background. The foreground approval UI cannot surface
        // during a BGContinuedProcessingTask, so:
        //  - subagent_dispatch: gated by the capability switch (isAdvancedToolEnabled),
        //    same as foreground; runs silently via the engine loop and returns its
        //    JSON report. The live token panel is not driven — the user sees the
        //    final result on return to the app.
        //  - mcp_call: high-risk (external/remote), mirrors the foreground gate —
        //    only runs when the high-risk auto-approve switch is on, otherwise
        //    denied so the user returns to the app to confirm.
        //  - model_council_run: DENIED in background. A council run is a long,
        //    multi-seat/multi-round streaming sequence (many sequential HTTP calls,
        //    potentially tens of minutes) that occupies a single executor step. The
        //    BGTask expirationHandler cannot interrupt an in-flight streamText, so a
        //    background council would overrun the BGTask window and be force-killed,
        //    leaving an incomplete run. It also drives the council-room @Observable
        //    UI, which has no subscriber in background. Revert to foreground.
        executors["subagent_dispatch"] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
            guard let self else { return .failed("Chat runtime is unavailable.") }
            guard self.isAdvancedToolEnabled(toolName) else {
                return .failed("\(toolName) 未开启。请先在设置中启用对应能力。")
            }
            let result = await self.dispatchAdvancedToolCall(
                self.toolCall(name: toolName, input: arguments),
                providerSetting: providerSetting,
                params: params,
                runId: runId
            )
            return .filled(result)
        }

        executors["model_council_run"] = IOSClosureToolExecutor { _, _, _ in
            .denied("模型委员会运行时间较长且依赖前台房间界面，请回到 App 内执行。")
        }

        executors["mcp_call"] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
            guard let self else { return .failed("Chat runtime is unavailable.") }
            // High-risk gate mirrors the foreground path (executeAdvancedToolCall):
            // MCP may touch external services, so only auto-run when the high-risk
            // auto-approve switch is on. Otherwise deny so the user returns to confirm.
            guard IOSLocalToolExecutor.isHighRiskAutoApproveEnabled else {
                return .denied("后台生成期间需要回到 App 确认 MCP 工具。")
            }
            guard self.isAdvancedToolEnabled(toolName) else {
                return .failed("\(toolName) 未开启。请先在设置中启用对应能力。")
            }
            let result = await self.dispatchAdvancedToolCall(
                self.toolCall(name: toolName, input: arguments),
                providerSetting: providerSetting,
                params: params,
                runId: runId
            )
            return .filled(result)
        }

        return executors
    }

    func nextPendingToolCall(in messages: [UIMessage]) -> ChatPendingToolCall? {
        if sharedSettings.snapshot.enableWebSearch,
           let toolCall = pendingSearchToolCall(in: messages) {
            return ChatPendingToolCall(kind: .search, toolCall: toolCall)
        }
        if let toolCall = pendingWorkspaceToolCall(in: messages) {
            return ChatPendingToolCall(kind: .workspace, toolCall: toolCall)
        }
        if let toolCall = pendingIshToolCall(in: messages) {
            return ChatPendingToolCall(kind: .ish, toolCall: toolCall)
        }
        if let toolCall = pendingWebMountToolCall(in: messages) {
            return ChatPendingToolCall(kind: .webMount, toolCall: toolCall)
        }
        if let toolCall = pendingMemoryToolCall(in: messages) {
            return ChatPendingToolCall(kind: .memory, toolCall: toolCall)
        }
        if let toolCall = pendingImageToolCall(in: messages) {
            return ChatPendingToolCall(kind: .image, toolCall: toolCall)
        }
        if let toolCall = pendingAdvancedToolCall(in: messages) {
            return ChatPendingToolCall(kind: .advanced, toolCall: toolCall)
        }
        return nil
    }

    func hasUnresolvedToolCall(in messages: [UIMessage]) -> Bool {
        unresolvedToolCall(in: messages) != nil
    }

    func execute(
        _ pendingToolCall: ChatPendingToolCall,
        context: ChatPendingToolApproval
    ) async -> ChatToolRuntimeResult {
        switch pendingToolCall.kind {
        case .search:
            return await executeSearchToolCall(context)
        case .workspace:
            return await executeWorkspaceToolCall(context)
        case .ish:
            return await executeIshToolCall(context)
        case .webMount:
            return await executeWebMountToolCall(context)
        case .memory:
            return executeMemoryToolCall(context)
        case .image:
            return await executeImageToolCall(context)
        case .advanced:
            return await executeAdvancedToolCall(context)
        }
    }

    func userInitiatedImageToolCall(input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "image-\(UUID().uuidString)-\(chatInputDigest(for: input))",
            toolName: "generate_image",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    func messagesByExecutingImageToolCall(
        _ toolCall: UIMessagePart.Tool,
        in messages: [UIMessage]
    ) async -> [UIMessage] {
        let resultParts = await dispatchImageToolCall(toolCall)
        return messagesByFinishingToolCall(
            toolCall,
            outputParts: resultParts,
            in: messages
        )
    }

    func finishMemoryApproval(
        pending: ChatPendingToolApproval,
        writePolicy: IOSMemoryToolWritePolicy
    ) -> [UIMessage] {
        let allowed: Bool
        if case .allow = writePolicy {
            allowed = true
        } else {
            allowed = false
        }
        recordToolApproval(
            capabilityId: "ios.agent.memory_write",
            toolCall: pending.toolCall,
            action: allowed ? .allowed : .denied,
            reason: allowed ? "User approved memory write." : "User denied memory write.",
            runId: pending.runId
        )

        let resultText = IOSMemoryToolExecutor.execute(
            input: pending.toolCall.input,
            runtime: sharedSettings.agentRuntime,
            writePolicy: writePolicy
        )
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishSearchApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        recordToolApproval(
            capabilityId: "ios.network.search_tools",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved network search." : "User denied network search.",
            runId: pending.runId
        )

        let resultText = allow
            ? await dispatchSearchToolCall(pending.toolCall)
            : ChatToolOutputFormatter.searchFailureJSON(
                toolName: pending.toolCall.toolName,
                reason: "User denied network search.",
                denied: true
            )
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishWebMountApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        recordToolApproval(
            capabilityId: "ios.webmount.browser",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved WebMount foreground action." : "User denied WebMount foreground action.",
            runId: pending.runId
        )

        let resultText: String
        if allow {
            let output = await webMountToolExecutionOutput(pending.toolCall, isUserInitiated: true)
            resultText = ChatToolOutputFormatter.webMountResultText(for: pending.toolCall, output: output)
        } else {
            resultText = IOSWebMountController.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied WebMount foreground action."
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishWorkspaceApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        let resultText: String
        if allow {
            let output = await workspaceToolExecutionOutput(pending.toolCall, isUserInitiated: true)
            resultText = ChatToolOutputFormatter.workspaceResultText(for: pending.toolCall, output: output)
        } else {
            resultText = IOSWorkspaceStore.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied Workspace tool access."
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishIshHandoffApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        let isEmbeddedIshExecution = IOSEmbeddedIshToolCatalog.supportedToolNames.contains(pending.toolCall.toolName)
        recordToolApproval(
            capabilityId: isEmbeddedIshExecution ? "ios.embedded.ish_runtime" : "ios.external.ish_handoff",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved iSH tool." : "User denied iSH tool.",
            runId: pending.runId
        )

        let resultText: String
        if allow {
            let output = await ishToolExecutionOutput(pending.toolCall, isUserInitiated: true)
            resultText = ChatToolOutputFormatter.ishHandoffResultText(for: pending.toolCall, output: output)
        } else {
            resultText = IOSWorkspaceStore.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied iSH tool.",
                "stdout_available": isEmbeddedIshExecution,
                "stderr_available": isEmbeddedIshExecution,
                "exit_code_available": false
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishMcpApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        recordToolApproval(
            capabilityId: "ios.mcp.tool_call",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved MCP tool call." : "User denied MCP tool call.",
            runId: pending.runId
        )

        let resultText: String
        if allow {
            resultText = await dispatchAdvancedToolCall(
                pending.toolCall,
                providerSetting: pending.providerSetting,
                params: pending.params,
                runId: pending.runId
            )
        } else {
            resultText = "用户拒绝执行 MCP 工具。"
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func messagesByFinishingToolCall(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        let outputPart = UIMessagePart.Text(text: outputText, metadata: nil)
        return messagesByFinishingToolCall(targetToolCall, outputParts: [outputPart], in: messages)
    }

    func messagesByFailingPendingToolCalls(
        in messages: [UIMessage],
        outputText: String
    ) -> [UIMessage] {
        var resolvedMessages = messages
        var resolvedKeys = Set<String>()

        while let toolCall = unresolvedToolCall(in: resolvedMessages) {
            let key = chatToolCallKey(toolCall)
            guard resolvedKeys.insert(key).inserted else { break }
            resolvedMessages = messagesByFinishingToolCall(
                toolCall,
                outputText: outputText,
                in: resolvedMessages
            )
        }

        return resolvedMessages
    }

    private func unresolvedToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingSearchToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { IOSSearchExecutor.supportedToolNames.contains($0.toolName) && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingWorkspaceToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        guard localToolExecutor != nil else { return nil }
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { IOSWorkspaceToolCatalog.supportedToolNames.contains($0.toolName) && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingIshToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        guard localToolExecutor != nil else { return nil }
        let ishNames = IOSIshToolCatalog.supportedToolNames
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { ishNames.contains($0.toolName) && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingWebMountToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        guard isWebMountRuntimeEnabled else { return nil }
        let webMountNames = IOSWebMountToolCatalog.supportedToolNames
            .union(IOSWebMountToolCatalog.unsupportedToolNames)
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { webMountNames.contains($0.toolName) && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingMemoryToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        guard IOSMemoryToolExecutor.isEnabled(runtime: sharedSettings.agentRuntime) else { return nil }
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.toolName == "memory_tool" && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingImageToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.toolName == "generate_image" && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    /// Resolve the designated image-generation model (Settings.imageGenerationModelId) to
    /// its modelId + provider apiKey/baseURL. Returns nil when no usable image model is set,
    /// which gates the generate_image tool off (image generation disabled).
    private func resolvedImageGenerationConfig() -> (modelId: String, apiKey: String, baseURL: String)? {
        let snap = sharedSettings.snapshot
        guard let model = snap.findModelById(uuid: snap.imageGenerationModelId),
              let provider = ChatProviderConfiguration.provider(for: model, providers: snap.providers) else {
            return nil
        }
        let apiKey = ChatProviderConfiguration.apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
        let baseURL = ChatProviderConfiguration.baseURL(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !apiKey.isEmpty, !baseURL.isEmpty else { return nil }
        return (model.modelId, apiKey, baseURL)
    }

    /// Resolve a codex (ChatGPT OAuth) image target. The actual request uses
    /// Android's fixed Codex image routing model, so only the provider id is
    /// needed here for OAuth token lookup.
    private func codexImageConfig() -> ChatCodexImageConfig {
        let snap = sharedSettings.snapshot
        guard let model = snap.findModelById(uuid: snap.imageGenerationModelId),
              let provider = ChatProviderConfiguration.provider(for: model, providers: snap.providers) as? ProviderSetting.OpenAI,
              provider.authMode == OpenAIAuthMode.codexOauth else {
            return .notSelected
        }
        let providerId = provider.id.description()
        guard IOSCodexAuthStore.load(providerId: providerId) != nil else { return .notSignedIn }
        return .signedIn(providerId: providerId)
    }

    private func pendingAdvancedToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        let advancedNames: Set<String> = ["mcp_call", "subagent_dispatch", "model_council_run"]
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { advancedNames.contains($0.toolName) && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func executeSearchToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        // Honor the global / high-risk auto-approve switches (Permissions page). When on,
        // skip the per-call approval card and dispatch directly.
        let autoApprove = IOSLocalToolExecutor.isGlobalAutoApproveEnabled
            || IOSLocalToolExecutor.isHighRiskAutoApproveEnabled
        if !autoApprove,
           let request = ChatToolApprovalRequestBuilder.search(
            for: pending.toolCall,
            reason: "网络搜索和网页读取会访问外部站点，需要你确认。",
            settings: sharedSettings.snapshot
        ) {
            return .waitingForApproval(.search(request))
        }

        let resultText = await dispatchSearchToolCall(pending.toolCall)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeWorkspaceToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let output = await workspaceToolExecutionOutput(pending.toolCall, isUserInitiated: false)
        if case .needsUserAction(let reason) = output,
           let request = ChatToolApprovalRequestBuilder.workspace(
               for: pending.toolCall,
               reason: reason,
               localToolExecutor: localToolExecutor
           ) {
            return .waitingForApproval(.workspace(request))
        }

        let resultText = ChatToolOutputFormatter.workspaceResultText(for: pending.toolCall, output: output)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeIshToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let output = await ishToolExecutionOutput(pending.toolCall, isUserInitiated: false)
        if case .needsUserAction(let reason) = output,
           let request = ChatToolApprovalRequestBuilder.ishHandoff(for: pending.toolCall, reason: reason) {
            return .waitingForApproval(.ish(request))
        }

        let resultText = ChatToolOutputFormatter.ishHandoffResultText(for: pending.toolCall, output: output)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeWebMountToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let output = await webMountToolExecutionOutput(pending.toolCall, isUserInitiated: false)
        if case .needsUserAction(let reason) = output,
           let request = ChatToolApprovalRequestBuilder.webMount(
               for: pending.toolCall,
               reason: reason,
               localToolExecutor: localToolExecutor
           ) {
            return .waitingForApproval(.webMount(request))
        }

        let resultText = ChatToolOutputFormatter.webMountResultText(for: pending.toolCall, output: output)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeMemoryToolCall(_ pending: ChatPendingToolApproval) -> ChatToolRuntimeResult {
        let writePolicy = memoryToolWritePolicy(input: pending.toolCall.input, isUserInitiated: false)
        if case .needsUserAction(let reason) = writePolicy,
           let request = ChatToolApprovalRequestBuilder.memory(for: pending.toolCall, reason: reason) {
            return .waitingForApproval(.memory(request))
        }

        let resultText = dispatchMemoryToolCall(pending.toolCall, writePolicy: writePolicy)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeImageToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let resultParts = await dispatchImageToolCall(pending.toolCall)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputParts: resultParts,
            in: pending.baseMessages
        ))
    }

    private func executeAdvancedToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        // MCP is high-risk (external/remote); auto-approve only when the high-risk switch is on.
        if pending.toolCall.toolName == "mcp_call",
           !IOSLocalToolExecutor.isHighRiskAutoApproveEnabled,
           let request = ChatToolApprovalRequestBuilder.mcp(
               for: pending.toolCall,
               reason: "MCP 工具可能访问外部服务或执行远端操作，需要你确认。"
           ) {
            return .waitingForApproval(.mcp(request))
        }

        let resultText = await dispatchAdvancedToolCall(
            pending.toolCall,
            providerSetting: pending.providerSetting,
            params: pending.params,
            runId: pending.runId
        )
        recordAdvancedToolApprovalIfNeeded(toolCall: pending.toolCall, runId: pending.runId)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func dispatchSearchToolCall(_ toolCall: UIMessagePart.Tool) async -> String {
        guard sharedSettings.snapshot.enableWebSearch else {
            return ChatToolOutputFormatter.searchFailureJSON(
                toolName: toolCall.toolName,
                reason: "Web search is disabled in settings."
            )
        }
        do {
            if toolCall.toolName == "search_web" {
                return try await executeSearchWebWithFallback(toolCall)
            }
            return try await IOSSearchExecutor.execute(
                toolName: toolCall.toolName,
                toolInput: toolCall.input,
                settings: sharedSettings.snapshot,
                transport: searchTransport
            )
        } catch {
            return ChatToolOutputFormatter.searchFailureJSON(
                toolName: toolCall.toolName,
                reason: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            )
        }
    }

    private func shouldExecuteSearchInBackground(toolName: String, arguments: String) -> Bool {
        let autoApprove = IOSLocalToolExecutor.isGlobalAutoApproveEnabled
            || IOSLocalToolExecutor.isHighRiskAutoApproveEnabled
        guard !autoApprove else { return true }
        let toolCall = toolCall(name: toolName, input: arguments)
        return ChatToolApprovalRequestBuilder.search(
            for: toolCall,
            reason: "网络搜索和网页读取会访问外部站点，需要你确认。",
            settings: sharedSettings.snapshot
        ) == nil
    }

    private func executeSearchWebWithFallback(_ toolCall: UIMessagePart.Tool) async throws -> String {
        let settings = sharedSettings.snapshot
        let maxResults = Int(settings.searchCommonOptions.resultSize)
        let request = try IOSSearchExecutor.searchRequest(
            from: toolCall.input,
            defaultMaxResults: maxResults
        )
        let initialSelection = IOSSearchExecutor.searchProviderSelection(settings: settings)
        do {
            let execution = try await IOSSearchExecutor.searchResults(
                toolInput: toolCall.input,
                maxResults: maxResults,
                settings: settings,
                transport: searchTransport
            )
            return IOSSearchExecutor.format(
                query: execution.request.query,
                results: execution.results,
                selection: execution.selection
            )
        } catch {
            guard let fallbackSelection = chatSearchFallbackSelection(
                after: initialSelection,
                settings: settings,
                initialError: error
            ) else {
                throw error
            }
            let results: [IOSSearchResult]
            switch fallbackSelection.route {
            case .duckDuckGoLite:
                results = try await IOSSearchExecutor.searchDuckDuckGoLite(
                    query: request.query,
                    maxResults: request.maxResults,
                    transport: searchTransport
                )
            case .bingHTML:
                results = try await IOSSearchExecutor.searchBingHTML(
                    query: request.query,
                    maxResults: request.maxResults,
                    transport: searchTransport
                )
            default:
                throw error
            }
            return IOSSearchExecutor.format(
                query: request.query,
                results: results,
                selection: fallbackSelection
            )
        }
    }

    private func chatSearchFallbackSelection(
        after selection: IOSSearchProviderSelection,
        settings: Settings,
        initialError: Error
    ) -> IOSSearchProviderSelection? {
        let reason = "原搜索服务 \(selection.providerName) 失败：\(searchErrorSummary(initialError))"
        if selection.route != .duckDuckGoLite, settings.searchBuiltinDuckDuckGoEnabled {
            return IOSSearchProviderSelection(
                route: .duckDuckGoLite,
                providerName: "DuckDuckGo Lite",
                providerType: "duckduckgo_builtin",
                serviceId: nil,
                fallbackReason: reason
            )
        }
        if selection.route != .bingHTML, settings.searchBuiltinBingEnabled {
            return IOSSearchProviderSelection(
                route: .bingHTML,
                providerName: "Bing HTML",
                providerType: "bing_builtin",
                serviceId: nil,
                fallbackReason: reason
            )
        }
        return nil
    }

    private func searchErrorSummary(_ error: Error) -> String {
        let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        guard message.count > 120 else { return message }
        return String(message.prefix(120)) + "..."
    }

    private func workspaceToolExecutionOutput(
        _ toolCall: UIMessagePart.Tool,
        isUserInitiated: Bool
    ) async -> IOSLocalToolExecutionOutput {
        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }
        return await localToolExecutor.execute(
            IOSLocalToolExecutionRequest(
                toolName: toolCall.toolName,
                operation: toolCall.input,
                scopeDigest: "workspace",
                payloadDigest: chatInputDigest(for: toolCall.input),
                isUserInitiated: isUserInitiated
            )
        )
    }

    private func ishToolExecutionOutput(
        _ toolCall: UIMessagePart.Tool,
        isUserInitiated: Bool
    ) async -> IOSLocalToolExecutionOutput {
        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }
        return await localToolExecutor.execute(
            IOSLocalToolExecutionRequest(
                toolName: toolCall.toolName,
                operation: toolCall.input,
                scopeDigest: "ish",
                payloadDigest: chatInputDigest(for: toolCall.input),
                isUserInitiated: isUserInitiated
            )
        )
    }

    private func dispatchWebMountToolCall(_ toolCall: UIMessagePart.Tool) async -> String {
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: false)
        return ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output)
    }

    private func webMountToolExecutionOutput(
        _ toolCall: UIMessagePart.Tool,
        isUserInitiated: Bool
    ) async -> IOSLocalToolExecutionOutput {
        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }
        return await localToolExecutor.execute(
            IOSLocalToolExecutionRequest(
                toolName: toolCall.toolName,
                operation: toolCall.input,
                scopeDigest: "webmount",
                payloadDigest: chatInputDigest(for: toolCall.input),
                isUserInitiated: isUserInitiated
            )
        )
    }

    private func dispatchMemoryToolCall(
        _ toolCall: UIMessagePart.Tool,
        writePolicy: IOSMemoryToolWritePolicy
    ) -> String {
        IOSMemoryToolExecutor.execute(
            input: toolCall.input,
            runtime: sharedSettings.agentRuntime,
            writePolicy: writePolicy
        )
    }

    private func memoryToolWritePolicy(input: String, isUserInitiated: Bool) -> IOSMemoryToolWritePolicy {
        localToolExecutor?.memoryToolWritePolicy(
            input: input,
            isUserInitiated: isUserInitiated
        ) ?? (IOSMemoryToolExecutor.requiresWriteApproval(input: input)
            ? .needsUserAction("Memory writes require foreground approval.")
            : .allow)
    }

    private func dispatchImageToolCall(_ toolCall: UIMessagePart.Tool) async -> [UIMessagePart] {
        do {
            switch codexImageConfig() {
            case .signedIn(let codex):
                let request = try IOSImageGenerationRepository.shared.toolRequest(
                    from: toolCall.input,
                    modelId: IOSCodexOAuthConstants.imageModelId
                )
                let record = try await IOSImageGenerationRepository.shared.generateViaCodex(
                    request: request,
                    providerId: codex
                )
                var parts: [UIMessagePart] = record.files.map { file in
                    UIMessagePart.Image(
                        url: IOSImageGenerationRepository.chatImageURLString(filePath: file.path),
                        metadata: nil
                    )
                }
                parts.append(UIMessagePart.Text(text: IOSImageGenerationRepository.shared.toolResultJSON(record: record), metadata: nil))
                return parts
            case .notSignedIn:
                return [UIMessagePart.Text(
                    text: ChatToolOutputFormatter.imageFailureJSON(reason: "请先在服务商设置里登录 Codex 后再生成或修改图片。"),
                    metadata: nil
                )]
            case .notSelected:
                break
            }
            guard let config = resolvedImageGenerationConfig() else {
                return [UIMessagePart.Text(
                    text: ChatToolOutputFormatter.imageFailureJSON(reason: "请先在「默认模型 → 辅助任务」里设置生图模型。"),
                    metadata: nil
                )]
            }
            let request = try IOSImageGenerationRepository.shared.toolRequest(from: toolCall.input, modelId: config.modelId)
            if request.sourceImageURL != nil {
                return [UIMessagePart.Text(
                    text: ChatToolOutputFormatter.imageFailureJSON(reason: "当前图片修改只支持 Codex 生图。"),
                    metadata: nil
                )]
            }
            let record = try await IOSImageGenerationRepository.shared.generate(
                request: request,
                apiKey: config.apiKey,
                baseURL: config.baseURL
            )
            var parts: [UIMessagePart] = record.files.map { file in
                UIMessagePart.Image(
                    url: IOSImageGenerationRepository.chatImageURLString(filePath: file.path),
                    metadata: nil
                )
            }
            parts.append(UIMessagePart.Text(text: IOSImageGenerationRepository.shared.toolResultJSON(record: record), metadata: nil))
            return parts
        } catch {
            return [
                UIMessagePart.Text(
                    text: ChatToolOutputFormatter.imageFailureJSON(reason: error.localizedDescription),
                    metadata: nil
                )
            ]
        }
    }

    private func dispatchAdvancedToolCall(
        _ toolCall: UIMessagePart.Tool,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String
    ) async -> String {
        guard isAdvancedToolEnabled(toolCall.toolName) else {
            return "\(toolCall.toolName) 未开启。请先在设置中启用对应能力。"
        }
        switch toolCall.toolName {
        case "subagent_dispatch":
            let args = ChatToolCallParsing.jsonObject(toolCall.input)
            let objective = args?["objective"] as? String ?? toolCall.input
            let roleId = args?["role_id"] as? String ?? args?["subagent_id"] as? String ?? "explorer"
            let scope = ChatToolCallParsing.stringArray(args?["tool_scope"])
                ?? ChatToolCallParsing.stringArray(args?["tools"])
                ?? []
            return await subAgentRunner.runViaEngine(
                objective: objective,
                roleId: roleId,
                requestedToolScope: scope,
                providerSetting: providerSetting,
                modelId: params.model.modelId,
                baseParams: params,
                parentToolExecutors: subAgentParentToolExecutors(runId: runId),
                toolCallId: toolCall.toolCallId
            )
        case "model_council_run":
            let args = ChatToolCallParsing.jsonObject(toolCall.input)
            let objective = args?["objective"] as? String ?? toolCall.input
            let mode = args?["mode"] as? String ?? "compare"
            let budget = args?["output_budget_chars"] as? Int ?? 12_000
            return await councilRunner.run(objective: objective, mode: mode, outputBudgetChars: budget, selectedProvider: providerSetting)
        case "mcp_call":
            guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
                  let server = args["server"] as? String,
                  let tool = args["tool"] as? String else {
                return "mcp_call 参数无效：需要 server 与 tool。"
            }
            let arguments = (args["arguments"] as? [String: Any]) ?? [:]
            do {
                return try await mcpManager.callTool(serverName: server, toolName: tool, arguments: arguments)
            } catch {
                return "MCP 调用失败（server: \(server)，tool: \(tool)）：\(error.localizedDescription)"
            }
        default:
            return "未知工具：\(toolCall.toolName)"
        }
    }

    private func subAgentParentToolExecutors(runId: String) -> [String: any IOSToolExecutor] {
        Dictionary(uniqueKeysWithValues: IOSSubAgentToolPolicy.readOnlyParentToolNames.map { name in
            (name, IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else {
                    return .failed("Chat runtime is unavailable.")
                }
                return await self.executeSubAgentParentTool(name: toolName, arguments: arguments, runId: runId)
            } as any IOSToolExecutor)
        })
    }

    private func executeSubAgentParentTool(
        name: String,
        arguments: String,
        runId: String
    ) async -> IOSAgentToolOutcome {
        guard IOSSubAgentToolPolicy.readOnlyParentToolNames.contains(name) else {
            return .denied("SubAgent read-only scope does not allow \(name).")
        }

        if IOSSearchExecutor.supportedToolNames.contains(name) {
            let output = await dispatchSearchToolCall(toolCall(name: name, input: arguments))
            recordSubAgentParentToolApproval(toolName: name, arguments: arguments, action: .allowed, runId: runId)
            return .filled(output)
        }

        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }

        let request: IOSLocalToolExecutionRequest
        if name == "file_read_selected" {
            request = localToolExecutor.requestForCurrentSelectedFile(isUserInitiated: true)
        } else {
            request = IOSLocalToolExecutionRequest(
                toolName: name,
                operation: arguments,
                scopeDigest: "subagent",
                payloadDigest: chatInputDigest(for: arguments),
                isUserInitiated: false
            )
        }
        let output = await localToolExecutor.execute(request)
        recordSubAgentParentToolApproval(
            toolName: name,
            arguments: arguments,
            action: output.isSuccessfulToolResult ? .allowed : .denied,
            runId: runId
        )
        return ChatToolOutputFormatter.subAgentOutcome(for: name, output: output)
    }

    private func recordSubAgentParentToolApproval(
        toolName: String,
        arguments: String,
        action: IOSToolApprovalAction,
        runId: String
    ) {
        guard let localToolExecutor,
              let capability = IOSCapabilityRegistry.capability(forToolName: toolName) else { return }
        localToolExecutor.recordApproval(
            capabilityId: capability.id,
            toolName: toolName,
            action: action,
            reason: "SubAgent read-only parent tool \(action == .allowed ? "executed" : "denied").",
            runId: runId,
            scopeDigest: "subagent",
            payloadDigest: chatInputDigest(for: arguments)
        )
    }

    private func recordAdvancedToolApprovalIfNeeded(
        toolCall: UIMessagePart.Tool,
        runId: String
    ) {
        let capabilityId: String
        switch toolCall.toolName {
        case "subagent_dispatch":
            capabilityId = "ios.agent.subagent_dispatch"
        case "model_council_run":
            capabilityId = "ios.agent.model_council_run"
        default:
            return
        }
        let enabled = isAdvancedToolEnabled(toolCall.toolName)
        recordToolApproval(
            capabilityId: capabilityId,
            toolCall: toolCall,
            action: enabled ? .allowed : .denied,
            reason: "\(toolCall.toolName) model tool call \(enabled ? "executed" : "denied").",
            runId: runId
        )
    }

    private func toolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "subagent-\(name)-\(chatInputDigest(for: input))",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func recordToolApproval(
        capabilityId: String,
        toolCall: UIMessagePart.Tool,
        action: IOSToolApprovalAction,
        reason: String,
        runId: String
    ) {
        localToolExecutor?.recordApproval(
            capabilityId: capabilityId,
            toolName: toolCall.toolName,
            action: action,
            reason: reason,
            runId: runId,
            scopeDigest: chatToolCallKey(toolCall),
            payloadDigest: chatInputDigest(for: toolCall.input)
        )
    }

    private func messagesByFinishingToolCall(
        _ targetToolCall: UIMessagePart.Tool,
        outputParts: [UIMessagePart],
        in messages: [UIMessage]
    ) -> [UIMessage] {
        var didFinishToolCall = false

        return messages.map { message in
            guard message.role == MessageRole.assistant else { return message }
            var didChangeMessage = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard !didFinishToolCall,
                      let toolPart = part as? UIMessagePart.Tool,
                      chatToolCallKey(toolPart) == chatToolCallKey(targetToolCall) else {
                    return part
                }

                didFinishToolCall = true
                didChangeMessage = true
                return UIMessagePart.Tool(
                    toolCallId: toolPart.toolCallId,
                    toolName: toolPart.toolName,
                    input: toolPart.input,
                    output: outputParts,
                    approvalState: toolPart.approvalState,
                    streamIndex: toolPart.streamIndex,
                    metadata: nil
                )
            }

            guard didChangeMessage else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt ?? chatNowLocalDateTime(),
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    private var isWebMountRuntimeEnabled: Bool {
        true
    }

    private func isAdvancedToolEnabled(_ toolName: String) -> Bool {
        switch toolName {
        case "mcp_call":
            sharedSettings.isCapabilityGateEnabled(.mcp)
        case "subagent_dispatch":
            isCapabilityPolicyEnabled("ios.agent.subagent_dispatch")
        case "model_council_run":
            isCapabilityPolicyEnabled("ios.agent.model_council_run")
        default:
            false
        }
    }

    private func isCapabilityPolicyEnabled(_ capabilityId: String) -> Bool {
        guard let localToolExecutor else { return true }
        let snapshot = localToolExecutor.permissionsStatus()
        return snapshot.capabilities.first { $0.id == capabilityId }?.policy != IOSAgentPermissionPolicy.disabled.title
    }

#if DEBUG
    func finishedToolCallMessagesForTesting(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        messagesByFinishingToolCall(targetToolCall, outputText: outputText, in: messages)
    }

    func memoryToolOutputForTesting(input: String) -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-memory-tool",
            toolName: "memory_tool",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        return dispatchMemoryToolCall(
            toolCall,
            writePolicy: memoryToolWritePolicy(input: input, isUserInitiated: false)
        )
    }

    func memoryApprovalRequestForTesting(input: String) -> MemoryToolApprovalRequest? {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-memory-tool",
            toolName: "memory_tool",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        guard case .needsUserAction(let reason) = memoryToolWritePolicy(input: input, isUserInitiated: false) else {
            return nil
        }
        return ChatToolApprovalRequestBuilder.memory(for: toolCall, reason: reason)
    }

    func memoryToolApprovalOutputForTesting(input: String, allow: Bool) -> String {
        IOSMemoryToolExecutor.execute(
            input: input,
            runtime: sharedSettings.agentRuntime,
            writePolicy: allow ? .allow : .deniedByUser("User denied memory write.")
        )
    }

    func webMountToolOutputForTesting(
        toolName: String,
        input: String,
        isUserInitiated: Bool = false
    ) async -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-webmount-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        if !isUserInitiated {
            return await dispatchWebMountToolCall(toolCall)
        }
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: isUserInitiated)
        return ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output)
    }

    func webMountApprovalRequestForTesting(
        toolName: String,
        input: String
    ) async -> WebMountToolApprovalRequest? {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-webmount-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: false)
        guard case .needsUserAction(let reason) = output else { return nil }
        return ChatToolApprovalRequestBuilder.webMount(
            for: toolCall,
            reason: reason,
            localToolExecutor: localToolExecutor
        )
    }

    func webMountToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-webmount-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        guard allow else {
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied WebMount foreground action."
            ])
        }
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: true)
        return ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output)
    }

    func searchApprovalRequestForTesting(
        toolName: String,
        input: String
    ) -> SearchToolApprovalRequest? {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-search-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        return ChatToolApprovalRequestBuilder.search(
            for: toolCall,
            reason: "Test approval",
            settings: sharedSettings.snapshot
        )
    }

    func searchToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-search-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        guard allow else {
            return ChatToolOutputFormatter.searchFailureJSON(
                toolName: toolName,
                reason: "User denied network search.",
                denied: true
            )
        }
        return await dispatchSearchToolCall(toolCall)
    }
#endif
}
