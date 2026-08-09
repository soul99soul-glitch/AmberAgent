import XCTest
@preconcurrency import Shared
@testable import iosApp

/// DEBUG 诊断探针（事后保留）：量化
/// `IOSContextCompactionCoordinator.requestOverheadTokens(params:)` 中
/// `params.tools` 的 name / description / `String(describing: parameters())`
/// 各分量，定位真机「上下文压缩后仍超过模型窗口预算（估算 1037162 / 850000
/// tokens）」报错里工具侧约 1M token（≈4.1M 字符）开销的来源。
///
/// 只打印测量结果，不做任何断言，不修改生产代码。基建照
/// `IOSToolSearchExposureTests`（fullIosDeclarations / makeViewModel 模式）。
@MainActor
final class IOSOverheadProbeTests: XCTestCase {

    // MARK: - 基建（照 IOSToolSearchExposureTests）

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSOverheadProbeTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func localToolExecutor() -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            )
        )
    }

    private func fullIosDeclarations() -> [Tool] {
        let names =
            IOSWorkspaceToolCatalog.supportedToolNames
            .union(IOSIshToolCatalog.supportedToolNames)
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
            .union(IOSWebMountToolCatalog.supportedToolNames)
            .union(IOSSkillToolCatalog.toolNames)
            .union(IOSMcpManagementToolCatalog.toolNames)
            .union([
                "search_web", "scrape_web", "memory_tool", "generate_image",
                "mcp_call", "subagent_dispatch", "model_council_run", "ask_user",
            ])
        return ToolKt.iosToolDeclarations(names: Array(names).sorted())
    }

    // MARK: - 单工具三围

    private struct Footprint {
        let name: String
        let nameChars: Int
        let descChars: Int
        let paramsChars: Int
        var totalChars: Int { nameChars + descChars + paramsChars }
    }

    private func footprint(of tool: Tool) -> Footprint {
        let schemaString = String(describing: tool.parameters())
        return Footprint(
            name: tool.name,
            nameChars: tool.name.count,
            descChars: tool.description_.count,
            paramsChars: schemaString.count
        )
    }

    private func report(
        label: String,
        tools: [Tool],
        customHeaders: [CustomHeader],
        customBody: [CustomBody]
    ) {
        let items = tools.map(footprint(of:))
        let nameTotal = items.reduce(0) { $0 + $1.nameChars }
        let descTotal = items.reduce(0) { $0 + $1.descChars }
        let paramsTotal = items.reduce(0) { $0 + $1.paramsChars }
        let charTotal = nameTotal + descTotal + paramsTotal
        print("OVERHEAD_PROBE [\(label)] tool count = \(items.count)")
        print("OVERHEAD_PROBE [\(label)] name chars total = \(nameTotal)")
        print("OVERHEAD_PROBE [\(label)] description chars total = \(descTotal)")
        print("OVERHEAD_PROBE [\(label)] params(String(describing: parameters())) chars total = \(paramsTotal)")
        print("OVERHEAD_PROBE [\(label)] grand chars total = \(charTotal) (≈\(charTotal / 4) tokens @ chars/4)")

        let sorted = items.sorted { $0.totalChars > $1.totalChars }
        print("OVERHEAD_PROBE [\(label)] top15 (name | nameChars | descChars | paramsChars | total):")
        for item in sorted.prefix(15) {
            print("OVERHEAD_PROBE [\(label)]   \(item.name) | \(item.nameChars) | \(item.descChars) | \(item.paramsChars) | \(item.totalChars)")
        }
        // nil-schema 的对照：确认参数为 nil 的工具字符串化为 "nil"(3)。
        let nilSchemaCount = items.filter { $0.paramsChars <= 3 }.count
        print("OVERHEAD_PROBE [\(label)] tools with paramsChars <= 3 (schema nil or trivial): \(nilSchemaCount)")

        // >10KB 的异常参数，打印前 500 字符片段。
        for item in sorted where item.paramsChars > 10_000 {
            guard let tool = tools.first(where: { $0.name == item.name }) else { continue }
            let s = String(describing: tool.parameters())
            let prefix = String(s.prefix(500))
            let head = prefix.replacingOccurrences(of: "\n", with: "\\n")
            print("OVERHEAD_PROBE [\(label)] HUGE params \(item.name) totalChars=\(item.paramsChars) first500=\"\(head)\"")
        }
    }

    // MARK: - 探针主体

    /// 镜像生产 `requestOverheadTokens`（IOSContextCompactionCoordinator.swift:828，
    /// 位于 private extension，测试不可直接调用；不改生产代码，此处逐行复刻公式）。
    private func mirroredRequestOverheadTokens(
        tools: [Tool],
        customHeaders: [CustomHeader] = [],
        customBody: [CustomBody] = []
    ) -> Int {
        let toolChars = tools.reduce(0) { total, tool in
            total +
                tool.name.count +
                tool.description_.count +
                String(describing: tool.parameters()).count
        }
        let headerChars = customHeaders.reduce(0) { total, header in
            total + header.name.count + header.value.count
        }
        let bodyChars = customBody.reduce(0) { total, body in
            total + body.key.count + String(describing: body.value).count
        }
        return (toolChars + headerChars + bodyChars) / 4
    }

    func testProbeOverheadBreakdown() throws {
        // 1) 生产路径：真实 ChatViewModel + makeTextGenerationParams。
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        _ = viewModel.currentToolDeclarationNames() // 触发 makeTextGenerationParams
        let params = viewModel.textGenerationParamsForTesting()
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
        print("OVERHEAD_PROBE production bridge.savingsSummary = \(bridge.savingsSummary())")
        print("OVERHEAD_PROBE production bridge.lazyModeEnabled = \(bridge.lazyModeEnabled())")

        // 2) 全量目录桥（重型配置的完整声明面）。
        let fullBridge = IosToolExposureBridge(tools: fullIosDeclarations())
        print("OVERHEAD_PROBE fullCatalog bridge.savingsSummary = \(fullBridge.savingsSummary())")
        print("OVERHEAD_PROBE fullCatalog bridge.lazyModeEnabled = \(fullBridge.lazyModeEnabled())")

        // 3) 逐工具三围 + 聚合 + top15（生产可见集 / 生产桥全量目录 / 全量已暴露集）。
        report(label: "production-visible", tools: params.tools, customHeaders: params.customHeaders, customBody: params.customBody)

        let productionFullTools = bridge.fullToolDeclarations()
        report(label: "production-full-catalog", tools: productionFullTools, customHeaders: [], customBody: [])

        let fullTools = fullBridge.fullToolDeclarations()
        report(label: "full-catalog", tools: fullTools, customHeaders: [], customBody: [])

        // tool_search 全部命中后的最大可见面（模拟最坏情况）。
        fullBridge.exposeToolNames(names: fullTools.map(\.name))
        report(label: "full-exposed", tools: fullBridge.visibleTools(), customHeaders: [], customBody: [])

        // 4) requestOverheadTokens 镜像总值（生产路径与全量目录）。
        let overheadProduction = mirroredRequestOverheadTokens(
            tools: params.tools,
            customHeaders: params.customHeaders,
            customBody: params.customBody
        )
        print("OVERHEAD_PROBE requestOverheadTokens(production-visible) = \(overheadProduction)")
        let overheadProductionFull = mirroredRequestOverheadTokens(tools: productionFullTools)
        print("OVERHEAD_PROBE requestOverheadTokens(production-full-catalog) = \(overheadProductionFull)")
        let overheadFull = mirroredRequestOverheadTokens(tools: fullTools)
        print("OVERHEAD_PROBE requestOverheadTokens(full-catalog) = \(overheadFull)")

        // 5) 对照：3KB 小会话的 estimateTokens（两条小消息）。
        let userText = String(repeating: "User message content about the current task. ", count: 35)
        let assistantText = String(repeating: "Assistant reply content with details. ", count: 40)
        let smallSession = [
            UIMessage.companion.user(prompt: userText),
            UIMessage.companion.assistant(prompt: assistantText),
        ]
        let smallChars = userText.count + assistantText.count
        let smallEstimate = IOSContextCompactionCoordinator.estimatedTokensForRequest(smallSession)
        print("OVERHEAD_PROBE small-session chars = \(smallChars)")
        print("OVERHEAD_PROBE small-session estimateTokens = \(smallEstimate)")

        // 6) 唯一随真机状态变化的工具输入：MCP 展开工具（mcp__*，schema 原样
        // 透传进 parameters）。用合成服务器验证机制与单位成本。
        probeMcpExpandedTools()
    }

    // MARK: - MCP 展开工具探针（真机上 params.tools 唯一非静态来源）

    private func probeMcpExpandedTools() {
        let schema = """
        {"type":"object","properties":{"query":{"type":"string","description":"Search query text. Use focused keywords for best results."},"filters":{"type":"array","items":{"type":"object","properties":{"field":{"type":"string","description":"Field name to filter on, e.g. status, priority, assignee."},"op":{"type":"string","enum":["eq","ne","gt","gte","lt","lte","contains","startsWith"],"description":"Comparison operator."},"value":{"type":"string","description":"Filter value."}},"required":["field","op","value"]},"description":"Optional list of filters."},"limit":{"type":"integer","minimum":1,"maximum":100,"description":"Max results to return, default 10."},"sort":{"type":"object","properties":{"field":{"type":"string"},"direction":{"type":"string","enum":["asc","desc"]}},"required":["field"]}},"required":["query"]}
        """
        let specs = (1...5).map { i in
            McpDiscoveredToolSpec(
                name: "tool_\(i)",
                description: "Synthetic MCP tool \(i) with a realistic multi-KB JSON schema.",
                inputSchemaJson: schema
            )
        }
        let expanded = ToolKt.mcpExpandedToolDeclarations(serverName: "probe-server", discovered: specs)
        print("OVERHEAD_PROBE mcp-expanded count = \(expanded.count)")
        let items = expanded.map(footprint(of:))
        for item in items {
            print("OVERHEAD_PROBE mcp-expanded   \(item.name) | name=\(item.nameChars) | desc=\(item.descChars) | params=\(item.paramsChars) | total=\(item.totalChars)")
        }
        let schemaChars = schema.count
        let paramsChars = items.reduce(0) { $0 + $1.paramsChars }
        let perToolOverheadChars = paramsChars / items.count
        print("OVERHEAD_PROBE mcp-expanded schemaJsonChars = \(schemaChars)")
        print("OVERHEAD_PROBE mcp-expanded avg params chars per tool = \(perToolOverheadChars) (≈\(perToolOverheadChars / 4) tokens/tool @ chars/4)")
        print("OVERHEAD_PROBE mcp-expanded ratio paramsChars/schemaJsonChars = \(Double(paramsChars) / Double(items.count * schemaChars))")
        // 达到真机 4.1M 字符所需规模（框架性对照，非实测）。
        if perToolOverheadChars > 0 {
            let neededForDevice = 4_100_000 / perToolOverheadChars
            print("OVERHEAD_PROBE mcp-expanded tools needed to reach ~4.1M chars at this avg schema size = \(neededForDevice)")
        }
        if let first = items.first, first.paramsChars > 0 {
            guard let tool = expanded.first else { return }
            let s = String(describing: tool.parameters())
            let head = String(s.prefix(300)).replacingOccurrences(of: "\n", with: "\\n")
            print("OVERHEAD_PROBE mcp-expanded first300 of params string: \(head)")
        }
    }

    // MARK: - 第二轮：真实设备数据复现探针
    //
    // 把真机拉回的会话 JSON + sharedSettingsJson 灌进真实生产组装链：
    //   store.selectConversation -> makeTextGenerationParams ->
    //   messagesByInjectingRuntimeContext -> editPreparedContext ->
    //   finalizedMessagesForRequest（同步、无网络，与设备报错同一函数族）。
    // 只打印，不做断言。

    private static let deviceConversationDir = "/tmp/amber-conv"
    private static let deviceConversationIds = [
        ("SMALL-407b3adc", "407b3adc-9bd0-4f7b-a949-6ffee7002540"),
        ("BIG-915b84ab", "915b84ab-2c6d-40e4-a359-93be49740cb7"),
    ]
    private static let devicePlistPath = "/tmp/amber-prefs/app.amber.ios.plist"
    private static let sharedSettingsKey = "app.amber.ios.sharedSettingsJson"

    /// 复刻 IOSContextCompactionCoordinator 的私有 estimatedChars（逐行镜像，
    /// 含 CJK ×4 的 weightedTokenChars 与 Image/Video/Audio=4500）。
    private func mirroredEstimatedChars(_ part: UIMessagePart) -> Int {
        switch part {
        case let text as UIMessagePart.Text:
            return mirroredWeightedChars(text.text)
        case let reasoning as UIMessagePart.Reasoning:
            return mirroredWeightedChars(reasoning.reasoning)
        case let tool as UIMessagePart.Tool:
            return mirroredWeightedChars(tool.input) + tool.output.reduce(0) { $0 + mirroredEstimatedChars($1) }
        case let document as UIMessagePart.Document:
            return document.fileName.count + 80
        case let miniApp as UIMessagePart.MiniApp:
            return mirroredWeightedChars(miniApp.title) + mirroredWeightedChars(miniApp.description_) + 120
        case is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio:
            return 4_500
        default:
            return String(describing: part).count
        }
    }

    /// 复刻 private extension String.weightedTokenChars（CJK 标量 ×4，其余 ×1）。
    private func mirroredWeightedChars(_ text: String) -> Int {
        text.unicodeScalars.reduce(0) { total, scalar in
            total + (isMirroredCJK(scalar.value) ? 4 : 1)
        }
    }

    private func isMirroredCJK(_ value: UInt32) -> Bool {
        switch value {
        case 0x4E00...0x9FFF,
             0x3400...0x4DBF,
             0x20000...0x2A6DF,
             0x2A700...0x2B73F,
             0x2B740...0x2B81F,
             0x2B820...0x2CEAF,
             0xF900...0xFAFF,
             0x2F800...0x2FA1F:
            return true
        default:
            return false
        }
    }

    /// 复刻 estimateTokens：Σ(role.name + Σ estimatedChars) / 4，底 4×count。
    private func mirroredEstimateTokens(_ messages: [UIMessage]) -> Int {
        let chars = messages.reduce(0) { total, message in
            total + message.role.name.count + message.parts.reduce(0) { $0 + mirroredEstimatedChars($1) }
        }
        return max(chars / 4, messages.count * 4)
    }

    private func partContentPreview(_ part: UIMessagePart) -> String {
        switch part {
        case let text as UIMessagePart.Text:
            return text.text
        case let reasoning as UIMessagePart.Reasoning:
            return reasoning.reasoning
        case let tool as UIMessagePart.Tool:
            let outPreview = tool.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.first ?? ""
            return "tool=\(tool.toolName) input=\(tool.input) output_head=\(outPreview.prefix(120))"
        case let image as UIMessagePart.Image:
            return image.url
        case let video as UIMessagePart.Video:
            return video.url
        case let audio as UIMessagePart.Audio:
            return audio.url
        case let document as UIMessagePart.Document:
            return document.fileName
        case let miniApp as UIMessagePart.MiniApp:
            return miniApp.title
        default:
            return String(describing: part)
        }
    }

    private func partKindName(_ part: UIMessagePart) -> String {
        switch part {
        case is UIMessagePart.Text: return "text"
        case is UIMessagePart.Reasoning: return "reasoning"
        case is UIMessagePart.Tool: return "tool"
        case is UIMessagePart.Document: return "document"
        case is UIMessagePart.MiniApp: return "miniApp"
        case is UIMessagePart.Image: return "image"
        case is UIMessagePart.Video: return "video"
        case is UIMessagePart.Audio: return "audio"
        default: return "other"
        }
    }

    /// 打印消息数组里按 estimatedChars 排序的前 10 个 part。
    private func reportTopParts(label: String, messages: [UIMessage]) {
        struct Item {
            let msgIndex: Int
            let msgIdPrefix: String
            let role: String
            let kind: String
            let rawChars: Int
            let estChars: Int
            let preview: String
        }
        var items: [Item] = []
        for (messageIndex, message) in messages.enumerated() {
            let msgId = String(describing: message.id)
            let idPrefix = msgId.count > 12 ? String(msgId.prefix(12)) : msgId
            for part in message.parts {
                let est = mirroredEstimatedChars(part)
                let raw = partContentPreview(part).count
                items.append(Item(
                    msgIndex: messageIndex,
                    msgIdPrefix: idPrefix,
                    role: message.role.name,
                    kind: partKindName(part),
                    rawChars: raw,
                    estChars: est,
                    preview: partContentPreview(part)
                ))
            }
        }
        items.sort { $0.estChars > $1.estChars }
        print("OVERHEAD_PROBE [\(label)] top10 parts by estimatedChars (of \(items.count) parts):")
        for item in items.prefix(10) {
            let preview = item.preview.replacingOccurrences(of: "\n", with: "\\n")
            let snippet = preview.count > 200 ? String(preview.prefix(200)) : preview
            print("OVERHEAD_PROBE [\(label)]   msg#\(item.msgIndex)(\(item.msgIdPrefix)) role=\(item.role) kind=\(item.kind) rawChars=\(item.rawChars) estChars=\(item.estChars) :: \(snippet)")
        }
    }

    func testProbeRealDeviceConversations() async throws {
        // 1) 临时目录：拷入两个真实会话 JSON（名字即 <conversationId>.json）。
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSOverheadProbeReal-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        for (_, id) in Self.deviceConversationIds {
            let src = URL(fileURLWithPath: Self.deviceConversationDir).appendingPathComponent("\(id).json")
            guard FileManager.default.fileExists(atPath: src.path) else {
                print("OVERHEAD_PROBE missing conversation file: \(src.path)")
                continue
            }
            try FileManager.default.copyItem(at: src, to: root.appendingPathComponent("\(id).json"))
        }

        // 2) 真实设置：把设备 sharedSettingsJson 写进隔离 UserDefaults。
        let defaults = isolatedDefaults()
        if let plistData = FileManager.default.contents(atPath: Self.devicePlistPath),
           let plist = try? PropertyListSerialization.propertyList(
               from: plistData, options: [], format: nil
           ) as? [String: Any],
           let json = plist[Self.sharedSettingsKey] as? String {
            defaults.set(json, forKey: Self.sharedSettingsKey)
            print("OVERHEAD_PROBE loaded real sharedSettingsJson (\(json.count) chars)")
        } else {
            print("OVERHEAD_PROBE WARNING: could not load device sharedSettingsJson")
        }

        // 3) 生产组装基座：真实 IOSSharedSettingsStore + ChatViewModel + 真实 store。
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let store = IOSConversationStore(baseDirectory: root)
        viewModel.conversationStore = store

        for (label, idString) in Self.deviceConversationIds {
            print("OVERHEAD_PROBE ===== \(label) (\(idString)) =====")
            let id = KotlinUuid.companion.parse(uuidString: idString)
            let didSelect = await store.selectConversationIfAvailable(id: id)
            guard didSelect else {
                print("OVERHEAD_PROBE [\(label)] selectConversationIfAvailable FAILED")
                continue
            }
            // 生产：切换会话后 reloadFromStore() 把 currentMessages 灌进 VM.messages。
            viewModel.reloadFromStore(reason: .initialLoad)
            let uploadMessages = viewModel.messages
            print("OVERHEAD_PROBE [\(label)] uploadMessages.count = \(uploadMessages.count)")
            print("OVERHEAD_PROBE [\(label)] estimateTokens(uploadMessages) = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(uploadMessages)) (mirrored = \(mirroredEstimateTokens(uploadMessages)))")

            // 4) makeTextGenerationParams（真实设置 → 真实模型/窗口/桥）。
            let params = viewModel.textGenerationParamsForTesting()
            let window = params.model.contextWindowTokens.map { Int(truncating: $0) } ?? -1
            print("OVERHEAD_PROBE [\(label)] params.model = \(params.model.modelId) contextWindowTokens = \(window)")
            print("OVERHEAD_PROBE [\(label)] params.tools.count = \(params.tools.count) customHeaders = \(params.customHeaders.count) customBody = \(params.customBody.count)")
            let overhead = mirroredRequestOverheadTokens(
                tools: params.tools,
                customHeaders: params.customHeaders,
                customBody: params.customBody
            )
            print("OVERHEAD_PROBE [\(label)] requestOverheadTokens(params) = \(overhead)")

            // 5) 生产 runtime 注入（内存/技能/MCP/系统提示/discovery 引导）。
            let runtimeInjected = viewModel.preparedUploadMessagesForTesting(uploadMessages)
            print("OVERHEAD_PROBE [\(label)] runtimeInjected.count = \(runtimeInjected.count)")
            print("OVERHEAD_PROBE [\(label)] estimateTokens(runtimeInjected) = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(runtimeInjected))")
            let runtimeOverhead = max(
                IOSContextCompactionCoordinator.estimatedTokensForRequest(runtimeInjected) -
                    IOSContextCompactionCoordinator.estimatedTokensForRequest(uploadMessages),
                0
            )
            print("OVERHEAD_PROBE [\(label)] promptOverheadTokens(runtime) = \(runtimeOverhead)")

            // 6) 生产 editPreparedContext 预处理（keepRecentMessages = keepRecentTurns*2 = 16，
            //    真机设置 keepRecentTurns=8；镜像 prepareMessagesForRequest 首段）。
            let edited = ContextCompactionEditTestSupport.editedMessagesWithCount(
                messages: uploadMessages,
                keepRecentMessages: 16
            )
            print("OVERHEAD_PROBE [\(label)] editPreparedContext removedToolResults = \(edited.removedToolResults)")
            print("OVERHEAD_PROBE [\(label)] estimateTokens(edited) = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(edited.messages))")

            reportTopParts(label: label, messages: uploadMessages)
            reportTopParts(label: "\(label)-runtimeInjected", messages: runtimeInjected)

            // 7) 生产最终门禁：finalizedMessagesForRequest（同步、无网络；与设备
            //    assertFitsRequest 报错同一函数族）。do/catch 打印抛错原文。
            let settings = sharedSettings.snapshot
            do {
                let finalized = try IOSContextCompactionCoordinator.shared.finalizedMessagesForRequest(
                    runtimeInjected,
                    settings: settings,
                    params: params
                )
                print("OVERHEAD_PROBE [\(label)] finalizedMessagesForRequest OK, count = \(finalized.count)")
                print("OVERHEAD_PROBE [\(label)] estimateTokens(finalized) = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(finalized))")
            } catch {
                let nsError = error as NSError
                print("OVERHEAD_PROBE [\(label)] finalizedMessagesForRequest THREW: \(nsError.localizedDescription)")
            }

            // 8) forceBudget 对照（estimateContextWindow × forceRatio）。
            let contextCompaction = settings.agentRuntime.contextCompaction
            let forceBudget = max(Int(Double(window) * Double(contextCompaction.forceRatio)), 1)
            print("OVERHEAD_PROBE [\(label)] forceBudget = \(forceBudget) (window \(window) × forceRatio \(contextCompaction.forceRatio))")

            // 10) 设备出事轮真实状态：故障发生在工具循环续轮，upload = 前 6 条
            //     （巨量 search_web 输出消息是最后一条）。此时 fit 无更小尾消息可保，
            //     单条超预算消息会被 fit 强制保留 → assertFitsRequest 抛错。
            if label == "BIG-915b84ab" {
                let upload6 = Array(uploadMessages.prefix(6))
                print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] uploadMessages.count = \(upload6.count)")
                print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] estimateTokens(upload6) = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(upload6))")
                let runtime6 = viewModel.preparedUploadMessagesForTesting(upload6)
                print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] estimateTokens(runtime6) = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(runtime6))")
                let lastIsGiant = runtime6.last?.parts.contains { part in
                    (part as? UIMessagePart.Tool)?.toolCallId == "call_01_1CyFBokgsUbWAPbrXBFz4648"
                        || (part as? UIMessagePart.Tool)?.toolCallId == "call_00_fMeDhGTUlTd47nYNjB8X7846"
                } ?? false
                print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] lastMessageCarriesGiantTool = \(lastIsGiant)")
                do {
                    let finalized6 = try IOSContextCompactionCoordinator.shared.finalizedMessagesForRequest(
                        runtime6,
                        settings: settings,
                        params: params
                    )
                    print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] finalizedMessagesForRequest OK, count = \(finalized6.count) est = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(finalized6))")
                } catch {
                    let nsError = error as NSError
                    print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] finalizedMessagesForRequest THREW: \(nsError.localizedDescription)")
                }
                let fit6 = ContextCompactionEditTestSupport.fittedMessagesWithBudget(
                    messages: runtime6,
                    maxTokens: 846_941
                )
                print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] support-fit(maxTokens 846941) count = \(fit6.count) est = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(fit6))")
                for (i, m) in fit6.enumerated() {
                    let hasGiant = m.parts.contains { part in
                        (part as? UIMessagePart.Tool)?.toolCallId == "call_01_1CyFBokgsUbWAPbrXBFz4648"
                            || (part as? UIMessagePart.Tool)?.toolCallId == "call_00_fMeDhGTUlTd47nYNjB8X7846"
                    } ?? false
                    print("OVERHEAD_PROBE [BIG-915b84ab-failingRound]   fit#\(i) role=\(m.role.name) parts=\(m.parts.count) est=\(mirroredEstimateTokens([m])) giantTool=\(hasGiant) textHead=\(m.toText().prefix(60).replacingOccurrences(of: "\n", with: "\\n"))")
                }
                // 对照：设备 1037162 ≈ est(runtime6) + requestOverheadTokens + promptOverheadTokens。
                let runtimeOverhead6 = max(
                    IOSContextCompactionCoordinator.estimatedTokensForRequest(runtime6) -
                        IOSContextCompactionCoordinator.estimatedTokensForRequest(upload6),
                    0
                )
                print("OVERHEAD_PROBE [BIG-915b84ab-failingRound] est(runtime6)+overhead = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(runtime6) + overhead + runtimeOverhead6)")
            }
            print("OVERHEAD_PROBE [\(label)] per-message est breakdown (runtimeInjected):")
            for (i, m) in runtimeInjected.enumerated() {
                let hasGiant = m.parts.contains { part in
                    if let tool = part as? UIMessagePart.Tool,
                       tool.toolCallId == "call_01_1CyFBokgsUbWAPbrXBFz4648" ||
                       tool.toolCallId == "call_00_fMeDhGTUlTd47nYNjB8X7846" {
                        return true
                    }
                    return false
                }
                print("OVERHEAD_PROBE [\(label)]   msg#\(i) role=\(m.role.name) parts=\(m.parts.count) est=\(mirroredEstimateTokens([m])) giantTool=\(hasGiant)")
            }
            let fittedBySupport = ContextCompactionEditTestSupport.fittedMessagesWithBudget(
                messages: runtimeInjected,
                maxTokens: 846_941
            )
            print("OVERHEAD_PROBE [\(label)] support-fit(maxTokens 846941) count = \(fittedBySupport.count) est = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(fittedBySupport))")
            for (i, m) in fittedBySupport.enumerated() {
                print("OVERHEAD_PROBE [\(label)]   fit#\(i) role=\(m.role.name) parts=\(m.parts.count) est=\(mirroredEstimateTokens([m])) textHead=\(m.toText().prefix(60).replacingOccurrences(of: "\n", with: "\\n"))")
            }
            // 极端对照：fit 是否在「单条消息就超预算」时仍保留该消息。
            let singleGiantFit = ContextCompactionEditTestSupport.fittedMessagesWithBudget(
                messages: runtimeInjected,
                maxTokens: 1_000
            )
            print("OVERHEAD_PROBE [\(label)] support-fit(maxTokens 1000) count = \(singleGiantFit.count) est = \(IOSContextCompactionCoordinator.estimatedTokensForRequest(singleGiantFit))")
        }
    }
}
