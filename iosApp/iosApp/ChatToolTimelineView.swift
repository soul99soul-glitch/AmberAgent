import SwiftUI
import UIKit
import Shared

/// 工具胶囊视觉族：驱动 Koboyo 实心图标；并行保留 SF `systemImage` 给顶栏活动岛。
enum ChatToolVisualKind: String, Equatable, CaseIterable {
    case search
    case web
    case webMount
    case webMountObserve
    case webMountCapture
    case workspaceRead
    case workspaceWrite
    case workspaceDelete
    case image
    case terminal
    case mcp
    case subagent
    case council
    case memory
    case code
    case generic

    static func resolve(toolName: String) -> ChatToolVisualKind {
        let name = toolName.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.contains("subagent_dispatch") { return .subagent }
        switch name {
        case "search_web": return .search
        case "scrape_web": return .web
        case "memory_tool": return .memory
        case "mcp_call": return .mcp
        case "model_council_run": return .council
        case "generate_image": return .image
        case "ish_handoff", "ios_ish_execute": return .terminal
        case "workspace_file_write": return .workspaceWrite
        case "workspace_artifact_delete": return .workspaceDelete
        case "workspace_file_read", "workspace_artifact_read": return .workspaceRead
        default:
            break
        }
        if name.hasPrefix("wm_") {
            switch name {
            case "wm_screenshot", "wm_visual_snapshot":
                return .webMountCapture
            case "wm_observe", "wm_extract", "wm_get":
                return .webMountObserve
            default:
                return .webMount
            }
        }
        if IOSWorkspaceToolCatalog.supportedToolNames.contains(name) {
            return .workspaceRead
        }
        let lower = name.lowercased()
        if lower.contains("search") { return .search }
        if lower.contains("code") || lower.contains("swift") { return .code }
        if lower.contains("read") || lower.contains("file") { return .workspaceRead }
        return .generic
    }

    /// 胶囊 leading：Koboyo 实心剪影。
    var koboyoMark: ChatKoboyoMark {
        switch self {
        case .search: .solidSearch
        case .web: .solidGlobe
        case .webMount: .solidMonitor
        case .webMountObserve: .solidEye
        case .webMountCapture: .solidCamera
        case .workspaceRead: .solidDocument
        case .workspaceWrite: .solidPen
        case .workspaceDelete: .solidWrench
        case .image: .solidImage
        case .terminal: .solidTerminal
        case .mcp: .solidPuzzle
        case .subagent: .solidUsers
        case .council: .solidPeopleGroup
        case .memory: .solidBrain
        case .code: .solidCode
        case .generic: .solidWrench
        }
    }

    /// 顶栏活动岛 / Live Activity 继续用 SF。
    var systemImage: String {
        switch self {
        case .search: "magnifyingglass"
        case .web: "globe"
        case .webMount, .webMountObserve, .webMountCapture: "globe.badge.chevron.backward"
        case .workspaceRead: "doc.text"
        case .workspaceWrite, .workspaceDelete: "folder"
        case .image: "photo.on.rectangle"
        case .terminal: "terminal"
        case .mcp: "puzzlepiece.extension"
        case .subagent: "person.2.fill"
        case .council: "person.3.sequence"
        case .memory: "brain.head.profile"
        case .code: "chevron.left.forwardslash.chevron.right"
        case .generic: "wrench.and.screwdriver"
        }
    }

    var isImageTool: Bool { self == .image }

    var activeIslandTint: ChatActivityIslandTint {
        switch self {
        case .search, .web, .webMount, .webMountObserve, .webMountCapture:
            .cyan
        case .image:
            .green
        case .subagent, .council:
            .indigo
        case .memory:
            .amber
        default:
            .accent
        }
    }
}


enum ChatToolStepState: Equatable {
    case done
    case active
    case failed

    var iconName: String {
        switch self {
        case .done:
            "checkmark"
        case .active:
            "circle.fill"
        case .failed:
            "exclamationmark"
        }
    }

    var iconSize: CGFloat {
        switch self {
        case .done, .failed:
            11
        case .active:
            7
        }
    }

    var color: Color {
        switch self {
        case .done:
            AmberTheme.accentGreen
        case .active:
            AmberTheme.accent
        case .failed:
            AmberTheme.accentRed
        }
    }

    var rowFill: Color {
        switch self {
        case .done:
            AmberTheme.accent.opacity(0.08)
        case .active:
            AmberTheme.accent.opacity(0.10)
        case .failed:
            AmberTheme.accentRed.opacity(0.10)
        }
    }

    var iconFill: Color {
        switch self {
        case .done:
            AmberTheme.accentGreen.opacity(0.10)
        case .active:
            AmberTheme.accentTint
        case .failed:
            AmberTheme.accentRed.opacity(0.10)
        }
    }

    var stroke: Color {
        switch self {
        case .done:
            AmberTheme.accent.opacity(0.16)
        case .active:
            AmberTheme.accent.opacity(0.20)
        case .failed:
            AmberTheme.accentRed.opacity(0.22)
        }
    }
}

struct ChatToolStepModel: Identifiable {
    let id: String
    /// 顶栏活动岛 / Live Activity 用的 SF Symbol（胶囊 leading 用 `koboyoMark`）。
    let systemImage: String
    let visualKind: ChatToolVisualKind
    let title: String
    let detail: String?
    let state: ChatToolStepState
    let isSubAgent: Bool
    /// Carried for subagent steps so the detail sheet can read the live prompt + streaming output.
    let tool: UIMessagePart.Tool?

    var koboyoMark: ChatKoboyoMark { visualKind.koboyoMark }

    init(
        id: String = UUID().uuidString,
        visualKind: ChatToolVisualKind,
        title: String,
        detail: String? = nil,
        state: ChatToolStepState,
        isSubAgent: Bool = false,
        tool: UIMessagePart.Tool? = nil
    ) {
        self.id = id
        self.visualKind = visualKind
        self.systemImage = visualKind.systemImage
        self.title = title
        self.detail = detail
        self.state = state
        self.isSubAgent = isSubAgent
        self.tool = tool
    }

    init(tool: UIMessagePart.Tool) {
        let stableID = Self.stableID(for: tool)
        let kind = ChatToolVisualKind.resolve(toolName: tool.toolName)
        // `.contains` (不是 `==`):流式合并偶发把工具名拼成 "subagent_dispatchsubagent_dispatch",
        // 用包含匹配才不会漏判、掉进裸名回退。
        if tool.toolName.contains("subagent_dispatch") {
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: .subagent,
                title: Self.subAgentTitle(from: tool.input),
                detail: failureReason ?? Self.subAgentDetail(from: tool.input),
                state: Self.state(executed: executed, failureReason: failureReason),
                isSubAgent: true,
                tool: tool
            )
            return
        }

        if tool.toolName == "search_web" {
            let query = Self.searchQuery(from: tool.input)
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: .search,
                title: Self.combinedLine(executed ? "已搜索" : "正在搜索", query),
                detail: executed ? (failureReason ?? Self.searchResultSummary(from: tool.output)) : query.map { "关键词：\($0)" },
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        if tool.toolName == "scrape_web" {
            let url = Self.scrapeURL(from: tool.input)
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: .web,
                title: Self.combinedLine(executed ? "已读取网页" : "正在读取网页", url),
                detail: executed ? (failureReason ?? Self.searchResultSummary(from: tool.output)) : url.map { "链接：\($0)" },
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        if tool.toolName == "memory_tool" {
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: .memory,
                title: executed ? "已更新核心记忆" : "正在更新核心记忆",
                detail: failureReason,
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        if tool.toolName == "mcp_call" {
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: .mcp,
                title: Self.combinedLine(executed ? "已调用 MCP" : "正在调用 MCP", Self.mcpName(from: tool.input)),
                detail: failureReason,
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        if tool.toolName == "model_council_run" {
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: .council,
                title: executed
                    ? (failureReason == nil ? "模型议会已完成" : "模型议会失败")
                    : "模型议会进行中",
                detail: failureReason,
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        if tool.toolName == "generate_image" {
            let prompt = Self.imagePrompt(from: tool.input)
            let imageCount = tool.output.compactMap { $0 as? UIMessagePart.Image }.count
            let executed = !tool.output.isEmpty
            if executed && imageCount == 0 {
                self.init(
                    id: stableID,
                    visualKind: .image,
                    title: Self.combinedLine("图片生成失败", prompt),
                    detail: ChatToolOutputFormatter.imageFailureReason(from: tool.output) ?? "没有返回图片",
                    state: .failed
                )
                return
            }
            self.init(
                id: stableID,
                visualKind: .image,
                title: Self.combinedLine(executed ? "图片已生成" : "正在生成图片", prompt),
                detail: executed ? "\(imageCount) 张图片" : prompt.map { "提示词：\($0)" },
                state: executed ? .done : .active
            )
            return
        }

        if tool.toolName == "ish_handoff" {
            let executed = !tool.output.isEmpty
            let failed = executed && Self.ishToolResultIndicatesFailure(tool.output)
            self.init(
                id: stableID,
                visualKind: .terminal,
                title: failed ? "iSH 交接失败" : (executed ? "iSH 交接已准备" : "准备 iSH 交接"),
                detail: executed ? Self.ishHandoffResultSummary(from: tool.output) : Self.ishHandoffInputSummary(from: tool.input),
                state: failed ? .failed : (executed ? .done : .active)
            )
            return
        }

        if tool.toolName == "ios_ish_execute" {
            let executed = !tool.output.isEmpty
            let failed = executed && Self.ishToolResultIndicatesFailure(tool.output)
            self.init(
                id: stableID,
                visualKind: .terminal,
                title: failed ? "内置 iSH 执行失败" : (executed ? "内置 iSH 已执行" : "准备执行内置 iSH"),
                detail: executed ? Self.ishExecuteResultSummary(from: tool.output) : Self.ishHandoffInputSummary(from: tool.input),
                state: failed ? .failed : (executed ? .done : .active)
            )
            return
        }

        if tool.toolName.hasPrefix("wm_") {
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: kind,
                title: Self.combinedLine(
                    executed ? Self.webMountCompletedTitle(for: tool) : Self.webMountPendingTitle(for: tool.toolName),
                    Self.webMountInputSummary(from: tool.input)
                ),
                detail: executed ? (failureReason ?? Self.webMountResultSummary(from: tool.output)) : Self.webMountInputSummary(from: tool.input),
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        if IOSWorkspaceToolCatalog.supportedToolNames.contains(tool.toolName) {
            let executed = !tool.output.isEmpty
            let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
            self.init(
                id: stableID,
                visualKind: kind,
                title: Self.combinedLine(
                    executed ? Self.workspaceCompletedTitle(for: tool.toolName) : Self.workspacePendingTitle(for: tool.toolName),
                    Self.workspaceInputSummary(from: tool.input)
                ),
                detail: executed ? (failureReason ?? Self.workspaceResultSummary(from: tool.output)) : Self.workspaceInputSummary(from: tool.input),
                state: Self.state(executed: executed, failureReason: failureReason)
            )
            return
        }

        let executed = !tool.output.isEmpty
        let failureReason = ChatToolOutputFormatter.failureReason(from: tool.output)
        let detailInput: String?
        if IOSProviderConfigToolCatalog.toolNames.contains(tool.toolName) {
            // Never surface raw api_key material on the tool capsule.
            detailInput = tool.input.isEmpty
                ? nil
                : IOSProviderConfigToolCatalog.redactedApprovalPreview(argumentsJSON: tool.input)
        } else {
            detailInput = tool.input.isEmpty ? nil : tool.input
        }
        self.init(
            id: stableID,
            visualKind: kind,
            title: Self.friendlyToolTitle(tool.toolName, executed: executed),
            detail: failureReason ?? detailInput,
            state: Self.state(executed: executed, failureReason: failureReason)
        )
    }

    private static func state(executed: Bool, failureReason: String?) -> ChatToolStepState {
        guard executed else { return .active }
        return failureReason == nil ? .done : .failed
    }

    private static func stableID(for tool: UIMessagePart.Tool) -> String {
        let callID = tool.toolCallId.trimmingCharacters(in: .whitespacesAndNewlines)
        if !callID.isEmpty { return callID }
        let fallbackInput = tool.input.replacingOccurrences(of: "\n", with: " ")
        return "\(tool.toolName):\(String(fallbackInput.prefix(80)))"
    }

    /// 未单独映射的工具:给一个友好中文标签,不显示裸工具名。状态由胶囊上的对勾/转圈表示,不再加文字。
    private static func friendlyToolTitle(_ name: String, executed: Bool) -> String {
        let known: [String: String] = [
            "file_read_selected": "读取选中文件",
            "skills_list": "列出技能",
            "use_skill": "加载技能",
            "skill_validate": "校验技能",
            "skill_import": "导入技能",
            "soul_import": "更新核心指令",
            "skill_enable": "启用技能",
            "skill_disable": "禁用技能",
            "mcp_list": "列出 MCP",
            "mcp_test": "测试 MCP",
            "mcp_import_from_skill": "从技能导入 MCP",
            "recipe_import": "导入 Recipe",
            "permissions_status": "查看权限状态",
            "tools_list": "列出可用工具",
            "subagent_report": "子智能体汇报",
            "ish_handoff": "iSH 交接",
            "read_health": "读取健康数据",
            "provider_config_status": "查看模型配置",
            "provider_config_apply": "应用提供商配置",
            "provider_refresh_models": "刷新模型列表",
            "settings_set_model_slot": "设置默认模型",
        ]
        if let mapped = known[name] { return mapped }
        // 动态工具名同样受列宽预算约束（见 combinedLine 注释），完整名在详情 sheet。
        if name.hasPrefix("mcp__") {
            return "MCP " + widthCappedPrefix(name.replacingOccurrences(of: "mcp__", with: ""), units: 32)
        }
        return name.isEmpty ? "工具调用" : "调用 \(widthCappedPrefix(name, units: 32))"
    }

    private static func scrapeURL(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else {
            let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : String(trimmed.prefix(48))
        }
        for key in ["url", "link", "target"] {
            if let value = args[key] as? String, !value.trimmingCharacters(in: .whitespaces).isEmpty {
                return Self.shortURL(value)
            }
        }
        if let urls = args["urls"] as? [Any], let first = urls.first as? String {
            return Self.shortURL(first)
        }
        return nil
    }

    /// 取域名 + 路径首段,去掉协议与 query,胶囊里更易读。
    private static func shortURL(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let comps = URLComponents(string: trimmed), let host = comps.host {
            let firstPath = comps.path.split(separator: "/").first.map { "/\($0)" } ?? ""
            return host + firstPath
        }
        return String(trimmed.prefix(48))
    }

    private static func mcpName(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else { return nil }
        for key in ["tool", "tool_name", "name", "server"] {
            if let value = args[key] as? String, !value.trimmingCharacters(in: .whitespaces).isEmpty {
                return value
            }
        }
        return nil
    }

    private static func subAgentArgs(from input: String) -> [String: Any]? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func subAgentRole(from input: String) -> String? {
        let args = subAgentArgs(from: input)
        let role = (args?["role_id"] as? String) ?? (args?["subagent_id"] as? String)
        guard let role, !role.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return role
    }

    static func subAgentTask(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else {
            // 解析失败(含流式未完成的截断 JSON):是 JSON 形态就不回退原始串,避免把 `{"objective"...`
            // 塞进胶囊标题;纯文本任务才原样用。
            let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
            return (trimmed.isEmpty || trimmed.hasPrefix("{") || trimmed.hasPrefix("[")) ? nil : trimmed
        }
        // 顶层字符串键
        for key in ["task", "prompt", "instruction", "objective", "input", "query"] {
            if let value = args[key] as? String,
               !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return value
            }
        }
        // 嵌套 task.objective(Android custom_subagent 的 task 结构)
        if let task = args["task"] as? [String: Any] {
            for key in ["objective", "prompt", "instruction"] {
                if let value = task[key] as? String,
                   !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    return value
                }
            }
        }
        return nil
    }

    private static func subAgentTitle(from input: String) -> String {
        // 胶囊只显示「标签 + 简短目标」,不再把整段 prompt 原样塞进标题。完整目标/输出留给详情 sheet。
        let role = subAgentRole(from: input)
        let label = role.map { "子智能体 @\($0)" } ?? "派发子任务"
        let objective = subAgentTask(from: input).map {
            String($0.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces).prefix(16))
        }
        return combinedLine(label, objective)
    }

    private static func subAgentDetail(from input: String) -> String? {
        guard let task = subAgentTask(from: input) else { return nil }
        return String(task.replacingOccurrences(of: "\n", with: " ").prefix(80))
    }

    /// "<verb> <subject>" on one line; the subject (query / prompt / target / task) is folded in
    /// so the pill reads like the action, not just a status word. Trimmed and length-capped.
    ///
    /// 上限按聊天列宽预算倒推（footnote 字号下 verb≤6 字 + subject 14 字 + 图标/
    /// 状态/内边距 ≈ 330pt ≤ 361pt 列宽）：cell 自 sizing 用无界提案询问理想宽度时
    /// `lineLimit(1)` 不生效，超长 subject 会把胶囊理想宽撑过列宽——轻则列宽随
    /// toolCallStarted/完成换词跳变，重则整行居中裁切、文字顶到屏幕两端。
    /// 完整内容在详情 sheet，胶囊只是状态芯片。
    private static func combinedLine(_ verb: String, _ subject: String?) -> String {
        guard let subject else { return verb }
        let oneLine = subject.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        guard !oneLine.isEmpty else { return verb }
        return "\(verb) \(widthCappedPrefix(oneLine, units: 28))"
    }

    /// 按显示宽度预算截断（CJK 计 2、ASCII 计 1）：纯按 Character 数会让
    /// ASCII subject 过短（14 个英文字母 ≈ 98pt，远低于列宽预算，信息白白损失），
    /// CJK 与混合文本仍守在 361pt 列宽内。
    private static func widthCappedPrefix(_ text: String, units: Int) -> String {
        var used = 0
        var count = 0
        for character in text {
            used += character.isASCII ? 1 : 2
            if used > units { break }
            count += 1
        }
        return String(text.prefix(count))
    }

    private static func searchQuery(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let query = object["query"] as? String {
            let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmedQuery.isEmpty ? nil : trimmedQuery
        }
        return trimmedInput
    }

    private static func imagePrompt(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let prompt = object["prompt"] as? String {
            let trimmedPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmedPrompt.isEmpty ? nil : String(trimmedPrompt.prefix(120))
        }
        return String(trimmedInput.prefix(120))
    }

    private static func searchResultSummary(from output: [UIMessagePart]) -> String? {
        let text = output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        guard !text.isEmpty else { return "已返回搜索结果" }
        let firstLine = text.split(separator: "\n", omittingEmptySubsequences: true).first.map(String.init)
        return firstLine ?? "已返回搜索结果"
    }

    private static func webMountPendingTitle(for toolName: String) -> String {
        switch toolName {
        case "wm_open": "准备打开网页"
        case "wm_tab_list": "准备读取网页标签页"
        case "wm_tab_new": "准备新建网页标签页"
        case "wm_tab_close": "准备关闭网页标签页"
        case "wm_observe": "准备观察网页"
        case "wm_extract": "准备提取网页"
        case "wm_get": "准备读取网页节点"
        case "wm_visual_snapshot": "准备读取视觉快照"
        case "wm_screenshot": "准备截取网页视口"
        case "wm_state": "准备读取网页状态"
        case "wm_back": "准备后退"
        case "wm_forward": "准备前进"
        case "wm_clear_session": "准备清理 WebMount Session"
        case "wm_site_add": "准备添加 WebMount 站点"
        case "wm_site_remove": "准备移除 WebMount 站点"
        case "wm_stations": "准备读取 WebMount 站点"
        default: toolName
        }
    }

    private static func webMountCompletedTitle(for tool: UIMessagePart.Tool) -> String {
        switch tool.toolName {
        case "wm_open": "网页已打开"
        case "wm_tab_list": "网页标签页已读取"
        case "wm_tab_new": "网页标签页已新建"
        case "wm_tab_close": "网页标签页已关闭"
        case "wm_observe": "网页观察已完成"
        case "wm_extract": "网页内容已提取"
        case "wm_get": "网页节点已读取"
        case "wm_visual_snapshot": "视觉快照已读取"
        case "wm_screenshot": "网页视口截图已保存"
        case "wm_state": "网页状态已读取"
        case "wm_back": "WebMount 已后退"
        case "wm_forward": "WebMount 已前进"
        case "wm_clear_session": "WebMount Session 已处理"
        case "wm_site_add": "WebMount 站点已添加"
        case "wm_site_remove": "WebMount 站点已移除"
        case "wm_stations": "WebMount 站点已读取"
        default: tool.toolName
        }
    }

    private static func webMountInputSummary(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let redactedAny = IOSWebMountRedactor.redactedJSONObject(object)
            let redacted = redactedAny as? [String: Any] ?? object
            // Prefer a short human label over raw JSON — long JSON titles expand
            // hug-content capsules and were part of the chat-column width overflow.
            for key in ["display_name", "name", "url", "homepage_url", "site_id", "selector", "text"] {
                if let value = redacted[key] as? String {
                    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { return String(trimmed.prefix(80)) }
                }
            }
            let json = IOSWebMountController.json(redactedAny)
            return String(json.prefix(80))
        }
        return String(IOSWebMountRedactor.redactedText(trimmedInput).prefix(80))
    }

    private static func ishHandoffInputSummary(from input: String) -> String? {
        guard let args = subAgentArgs(from: input) else {
            let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : String(trimmed.prefix(80))
        }
        if let filename = args["filename"] as? String, !filename.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return filename
        }
        if let purpose = args["purpose"] as? String, !purpose.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return purpose
        }
        if let command = args["command"] as? String, !command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(command.prefix(80))
        }
        if let script = args["script"] as? String, !script.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(script.prefix(80))
        }
        return nil
    }

    /// S5 (`IOSToolLoopGuard.proceedAndRemind`) appends a *separate* plain-text
    /// reminder Text part after the tool's real JSON output (see
    /// `appendingToolLoopReminder`), so joining every Text part with "\n" and
    /// parsing the combined string as one JSON object breaks the moment a
    /// reminder is present — the tool's own JSON is still valid on its own,
    /// but "json\nreminder sentence" as a whole is not, so every summary/
    /// failure-detection helper built on that join-then-parse pattern silently
    /// stopped recognizing a valid result (or a failure) once a reminder was
    /// attached. Parse each Text part independently instead and take the
    /// first one that decodes as a JSON object — behavior is unchanged for
    /// the common single-JSON-part case, and robust to "JSON + appended text".
    static func firstJSONObject(in parts: [UIMessagePart]) -> [String: Any]? {
        for text in parts.compactMap({ ($0 as? UIMessagePart.Text)?.text }) {
            guard let data = text.data(using: .utf8),
                  let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                continue
            }
            return object
        }
        return nil
    }

    private static func ishHandoffResultSummary(from output: [UIMessagePart]) -> String? {
        guard let object = firstJSONObject(in: output) else { return nil }
        if let ok = object["ok"] as? Bool, !ok {
            return (object["error"] as? String) ?? (object["reason"] as? String) ?? "交接失败"
        }
        let copied = (object["copied_to_clipboard"] as? Bool) == true ? "已复制" : "未复制"
        let file = object["script_file_name"] as? String ?? "script.sh"
        return "\(copied) · \(file) · 无输出回传"
    }

    private static func ishExecuteResultSummary(from output: [UIMessagePart]) -> String? {
        guard let object = firstJSONObject(in: output) else { return nil }
        if let ok = object["ok"] as? Bool, !ok {
            return (object["error"] as? String)?.nilIfBlank
                ?? (object["stderr"] as? String)?.nilIfBlank
                ?? "执行失败"
        }
        let exitCode = object["exit_code"] as? Int ?? 0
        let stdout = (object["stdout"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let stdout, !stdout.isEmpty {
            return "exit \(exitCode) · \(String(stdout.prefix(80)))"
        }
        return "exit \(exitCode) · 无输出"
    }

    private static func ishToolResultIndicatesFailure(_ output: [UIMessagePart]) -> Bool {
        guard let object = firstJSONObject(in: output) else { return false }
        if let ok = object["ok"] as? Bool { return !ok }
        if let denied = object["denied"] as? Bool, denied { return true }
        if let status = object["status"] as? String {
            return ["failed", "error", "denied", "timed_out"].contains(status.lowercased())
        }
        if let exitCode = object["exit_code"] as? Int {
            return exitCode != 0
        }
        return false
    }

    private static func workspacePendingTitle(for toolName: String) -> String {
        switch toolName {
        case "workspace_file_read": "准备读取 Workspace 文件"
        case "workspace_file_write": "准备写入 Workspace 文件"
        case "workspace_artifact_read": "准备读取 Artifact"
        case "workspace_artifact_delete": "准备删除 Artifact"
        default: toolName
        }
    }

    private static func workspaceCompletedTitle(for toolName: String) -> String {
        switch toolName {
        case "workspace_file_read": "Workspace 文件已读取"
        case "workspace_file_write": "Workspace 文件已写入"
        case "workspace_artifact_read": "Artifact 已读取"
        case "workspace_artifact_delete": "Artifact 已删除"
        default: toolName
        }
    }

    private static func workspaceInputSummary(from input: String) -> String? {
        let trimmedInput = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { return nil }
        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            return String(text.prefix(160))
        }
        return String(trimmedInput.prefix(160))
    }

    private static func workspaceResultSummary(from output: [UIMessagePart]) -> String? {
        let text = output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        guard !text.isEmpty else { return "已返回 Workspace 结果" }
        guard let object = firstJSONObject(in: output) else {
            return String(text.prefix(160))
        }
        if object["denied"] as? Bool == true {
            return "已拒绝：\((object["reason"] as? String) ?? "Workspace 权限限制")"
        }
        if let path = object["path"] as? String {
            return path
        }
        if let title = object["title"] as? String {
            return title
        }
        if let error = object["error"] as? String {
            return "失败：\(error)"
        }
        return "已返回 Workspace 结果"
    }

    private static func webMountResultSummary(from output: [UIMessagePart]) -> String? {
        let text = output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        guard !text.isEmpty else { return "已返回 WebMount 结果" }
        guard let object = firstJSONObject(in: output) else {
            return String(IOSWebMountRedactor.redactedText(text).prefix(160))
        }
        if object["denied"] as? Bool == true {
            return "已拒绝：\((object["reason"] as? String) ?? "WebMount 权限限制")"
        }
        if object["unsupported"] as? Bool == true {
            return "iOS 暂不支持：\((object["tool"] as? String) ?? "WebMount 工具")"
        }
        if let status = object["status"] as? String {
            let url = object["url"] as? String
            return [status, url].compactMap { $0?.nilIfBlank }.joined(separator: " · ")
        }
        if let artifact = object["artifact"] as? [String: Any],
           let artifactId = artifact["artifact_id"] as? String {
            let size = artifact["size_bytes"].map { "\($0) bytes" }
            return [artifactId, size].compactMap { $0?.nilIfBlank }.joined(separator: " · ")
        }
        if let closed = object["closed_session_id"] as? String {
            return "已关闭 \(closed)"
        }
        if let count = object["count"] as? Int {
            if object["sessions"] != nil {
                return "\(count) 个网页会话"
            }
            return "\(count) 个站点"
        }
        if let sessionId = object["session_id"] as? String {
            return "会话：\(sessionId)"
        }
        if let siteId = object["site_id"] as? String {
            return "站点：\(siteId)"
        }
        return "已返回 WebMount 结果"
    }

}

struct ChatToolTimeline: View {
    let steps: [ChatToolStepModel]
    /// Tapping a step (used for subagent steps, which open a detail sheet). nil = not tappable.
    var onTapStep: ((ChatToolStepModel) -> Void)? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(steps) { step in
                let tappable = onTapStep != nil
                if tappable {
                    Button { onTapStep?(step) } label: { row(step, chevron: true) }
                        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.98, haptic: .selection))
                } else {
                    row(step, chevron: false)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
    }

    // Cream capsule: colored tool icon (no backing square) + title (+ optional detail) + trailing
    // status (green check when done, spinner while active, ! on failure). Matches the requested
    // pill style shared with the reasoning card.
    @ViewBuilder
    private func row(_ step: ChatToolStepModel, chevron: Bool) -> some View {
        HStack(spacing: 7) {
            // Koboyo 实心剪影：与思考胶囊同系；进行中轻呼吸（不用 SF symbolEffect）。
            Group {
                if step.state == .active {
                    ChatKoboyoSpinningIcon(
                        mark: step.koboyoMark,
                        pointSize: 14,
                        tint: UIColor(step.state.color),
                        isActive: !reduceMotion
                    )
                } else {
                    ChatKoboyoIcon(step.koboyoMark, size: 14)
                        .foregroundStyle(step.state.color)
                }
            }
            .frame(width: 16, height: 16)

            Text(step.title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(1)
                .truncationMode(.tail)

            trailingStatus(for: step.state)

            if chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        // Background first (hug the HStack), then cap at the column proposal so long
        // titles truncate without expanding ScrollView content width. Short titles
        // stay chip-sized and leading-aligned — do not use fixedSize(horizontal:false)
        // here or the capsule stretches to full column width.
        .background(step.state.rowFill, in: Capsule(style: .continuous))
        .overlay {
            Capsule(style: .continuous)
                .stroke(step.state.stroke, lineWidth: 0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Capsule(style: .continuous))
        .animation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.84), value: step.state)
    }

    @ViewBuilder
    private func trailingStatus(for state: ChatToolStepState) -> some View {
        switch state {
        case .done:
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AmberTheme.accentGreen)
                .contentTransition(.symbolEffect(.replace.downUp))
        case .active:
            ProgressView()
                .controlSize(.mini)
                .tint(AmberTheme.accent)
        case .failed:
            Image(systemName: "exclamationmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AmberTheme.accentRed)
                .contentTransition(.symbolEffect(.replace.downUp))
        }
    }
}
