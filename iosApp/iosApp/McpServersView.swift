import SwiftUI
import Shared

struct McpServersView: View {
    let sharedSettings: IOSSharedSettingsStore
    let configStore: IOSMcpConfigStore
    @State private var mcpManager: IOSMcpManager
    @State private var editingServer: IOSMcpServerConfig?

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    init(sharedSettings: IOSSharedSettingsStore, configStore: IOSMcpConfigStore) {
        self.sharedSettings = sharedSettings
        self.configStore = configStore
        self._mcpManager = State(initialValue: IOSMcpManager(sharedSettings: sharedSettings, configStore: configStore))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    localConfigSection
                    connectedSection
                    managementSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            guard sharedSettings.isCapabilityGateEnabled(.mcp) else { return }
            await mcpManager.syncAll()
        }
        .onChange(of: configStore.servers) { _, _ in
            guard sharedSettings.isCapabilityGateEnabled(.mcp) else { return }
            Task { await mcpManager.syncAll() }
        }
        .sheet(item: $editingServer) { server in
            McpAddView(configStore: configStore, editingServer: server)
                .presentationDetents([.fraction(0.82), .large])
                .presentationDragIndicator(.hidden)
                .presentationCornerRadius(26)
                .presentationBackground(AmberTheme.glassStrong)
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回技能", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("MCP 服务器")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            HStack(spacing: 8) {
                AmberGlassCircleButton(systemImage: "arrow.clockwise", accessibilityLabel: "刷新 MCP 工具", size: 38, symbolSize: 16) {
                    Task { await mcpManager.syncAll() }
                }
                AmberGlassCircleButton(systemImage: "square.and.arrow.down", accessibilityLabel: "导入服务器", size: 38, symbolSize: 16) {
                    router.navigate(to: .mcpImport)
                }
                AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加服务器", size: 38, symbolSize: 17) {
                    router.navigate(to: .mcpAdd)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("添加外部工具服务器，连接后聊天可以使用服务器提供的工具。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var localConfigSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "服务器")
            AmberFormGroup {
                if configStore.servers.isEmpty {
                    HStack(spacing: 10) {
                        Image(systemName: "tray")
                            .font(.system(size: 16))
                            .foregroundStyle(AmberTheme.muted2)
                        Text("还没有保存 MCP 服务器。可通过导入或手动添加。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                } else {
                    ForEach(Array(configStore.servers.enumerated()), id: \.element.id) { index, server in
                        McpServerRow(
                            server: server,
                            status: statusTitle(for: server.name),
                            onToggle: { enabled in
                                configStore.setEnabled(named: server.name, enabled: enabled)
                                Task { await mcpManager.syncAll() }
                            },
                            onEdit: {
                                editingServer = server
                            },
                            onDelete: {
                                configStore.remove(named: server.name)
                                Task { await mcpManager.syncAll() }
                            }
                        )

                        if index < configStore.servers.count - 1 {
                            McpDivider()
                        }
                    }
                }
            }
        }
    }

    private var connectedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "连接状态")
            AmberFormGroup {
                if mcpManager.servers.isEmpty {
                    McpStatusRow(
                        title: "没有 MCP 服务器",
                        subtitle: "导入或手动添加服务器后，可在这里查看连接状态。",
                        badge: "空配置"
                    )
                } else {
                    ForEach(Array(mcpManager.servers.enumerated()), id: \.element.id) { index, server in
                        let status = mcpManager.statusByServer[server.name] ?? .idle
                        McpStatusRow(
                            title: server.name.isEmpty ? "未命名" : server.name,
                            subtitle: statusSubtitle(for: server, status: status),
                            badge: status.title
                        )

                        if index < mcpManager.servers.count - 1 {
                            McpDivider()
                        }
                    }
                }
            }

            if !mcpManager.tools.isEmpty {
                AmberSectionLabel(text: "已发现工具")
                AmberFormGroup {
                    ForEach(Array(mcpManager.tools.enumerated()), id: \.element.id) { index, discovered in
                        McpToolToggleRow(
                            discovered: discovered,
                            onToggle: { enabled in
                                configStore.setToolEnabled(
                                    serverName: discovered.serverName,
                                    toolName: discovered.tool.name,
                                    enabled: enabled
                                )
                                mcpManager.refreshFromCurrentSettings()
                            }
                        )
                        if index < mcpManager.tools.count - 1 {
                            McpDivider()
                        }
                    }
                }
            }

            McpNote("页面进入时会尝试连接已启用的服务器并刷新工具列表。")
        }
    }

    private var managementSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "管理")
            AmberFormGroup {
                McpActionRow(
                    systemImage: "square.and.arrow.down",
                    iconColor: AmberTheme.accentCyan,
                    title: "导入服务器",
                    subtitle: "粘贴标准 mcpServers JSON，解析后保存到本机配置"
                ) {
                    router.navigate(to: .mcpImport)
                }

                McpDivider()

                McpActionRow(
                    systemImage: "plus",
                    iconColor: AmberTheme.accent,
                    title: "手动添加",
                    subtitle: "填写传输类型、服务器地址与请求头并保存"
                ) {
                    router.navigate(to: .mcpAdd)
                }
            }

            McpNote("导入和手动添加会保存到本机配置。")
        }
    }
    private func statusTitle(for serverName: String) -> String {
        switch mcpManager.statusByServer[serverName] ?? .idle {
        case .idle:
            return "未连接"
        case .connecting:
            return "连接中"
        case .connected:
            return "已连接"
        case .reconnecting:
            return "重连中"
        case .error:
            return "连接失败"
        }
    }

    private func statusSubtitle(for server: IOSMcpServerConfig, status: IOSMcpConnectionStatus) -> String {
        let base = "\(server.transportTitle) · \(server.url)"
        if case .error(let message) = status {
            return "\(base)\n\(message)"
        }
        let toolCount = server.tools.count
        return toolCount > 0 ? "\(base)\n发现 \(toolCount) 个工具" : base
    }
}

struct McpImportView: View {
    let configStore: IOSMcpConfigStore
    @Environment(\.dismiss) private var dismiss

    @State private var jsonText = """
    {
      "mcpServers": {
        "context7": {
          "transport": "streamableHttp",
          "url": "https://mcp.context7.com/mcp"
        }
      }
    }
    """

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                McpDraftHeader(title: "导入服务器", doneTitle: "保存") {
                    if !parsedServers.isEmpty {
                        configStore.importServers(json: jsonText)
                    }
                    dismiss()
                }

                ScrollView {
                    VStack(spacing: 0) {
                        introSection
                        jsonSection
                        previewSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var introSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "square.and.arrow.down")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentCyan)
                        .frame(width: 34, height: 34)
                        .background(AmberTheme.accentCyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text("标准 mcpServers JSON")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)

                        Text("粘贴 Claude / Codex 常见的 mcpServers 配置。保存后会写入 iOS 本机 MCP 配置并在服务器列表页同步连接。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            .padding(.top, 4)
        }
    }

    private var jsonSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "JSON")
            AmberFormGroup {
                TextEditor(text: $jsonText)
                    .font(.system(size: 13, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 220)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(AmberTheme.surface2.opacity(0.42), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 12)
            }

            McpValidationNote(text: validationText, isWarning: true)
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "文本预览")
            AmberFormGroup {
                McpPreviewRow(title: "根字段文本", value: jsonText.contains("\"mcpServers\"") ? "mcpServers" : "未检测到")
                McpDivider()
                McpPreviewRow(title: "解析条目数", value: "\(parsedServers.count)")
                McpDivider()
                McpPreviewRow(title: "保存结果", value: parsedServers.isEmpty ? "无可保存条目" : "可保存")
            }
        }
    }

    private var hasWarnings: Bool {
        !jsonText.contains("\"mcpServers\"") || parsedServers.isEmpty
    }

    private var validationText: String {
        if !jsonText.contains("\"mcpServers\"") {
            return "未检测到 mcpServers 文本；保存不会写入配置。"
        }

        if parsedServers.isEmpty {
            return "文本包含 mcpServers，但没有解析到服务器条目。"
        }

        return "解析到 \(parsedServers.count) 个服务器；点击保存后写入本机配置。"
    }

    private var parsedServers: [McpServerConfig] {
        McpImportParserKt.parseMcpServersFromJson(json: jsonText)
    }
}

struct McpAddView: View {
    let configStore: IOSMcpConfigStore
    let editingServer: IOSMcpServerConfig?
    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var transport: McpTransportOption
    @State private var serverURL: String
    @State private var enabled: Bool
    @State private var headers: [McpHeaderDraft]

    init(configStore: IOSMcpConfigStore, editingServer: IOSMcpServerConfig? = nil) {
        self.configStore = configStore
        self.editingServer = editingServer
        self._name = State(initialValue: editingServer?.name ?? "context7")
        self._transport = State(initialValue: editingServer.map(McpTransportOption.init(server:)) ?? .streamableHTTP)
        self._serverURL = State(initialValue: editingServer?.url ?? "https://mcp.context7.com/mcp")
        self._enabled = State(initialValue: editingServer?.enabled ?? true)
        let headerDrafts = editingServer?.headers
            .sorted { $0.key < $1.key }
            .map { McpHeaderDraft(name: $0.key, value: $0.value) }
        self._headers = State(initialValue: headerDrafts ?? [.init(name: "X-Client", value: "AmberAgent")])
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                McpDraftHeader(title: editingServer == nil ? "手动添加" : "编辑服务器", doneTitle: "保存") {
                    if let server = draftServer {
                        configStore.upsert(server, replacing: editingServer?.name)
                    }
                    dismiss()
                }

                ScrollView {
                    VStack(spacing: 0) {
                        connectionSection
                        headersSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var connectionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "连接")
            AmberFormGroup {
                McpDraftToggleRow(
                    title: "启用服务器",
                    subtitle: "关闭后不会连接，也不会向聊天提供这个服务器的工具。",
                    isOn: enabled
                ) {
                    enabled.toggle()
                }
                McpDivider()
                McpDraftTextFieldRow(title: "名称", text: $name, placeholder: "例如 context7")
                McpDivider()
                Menu {
                    ForEach(McpTransportOption.allCases) { option in
                        Button(option.title) {
                            transport = option
                            serverURL = option.defaultURL
                        }
                    }
                } label: {
                    McpDraftPickerRow(title: "传输类型", value: transport.title)
                }
                McpDivider()
                McpDraftTextFieldRow(title: "服务器 URL", text: $serverURL, placeholder: transport.defaultURL, monospace: true)
            }

            McpNote("保存后会写入本机配置，并可在服务器列表页同步连接。")
        }
    }

    private var headersSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "请求头")
            AmberFormGroup {
                ForEach(headers.indices, id: \.self) { index in
                    McpHeaderDraftRow(header: $headers[index]) {
                        headers.remove(at: index)
                    }

                    if index < headers.count - 1 {
                        McpDivider()
                    }
                }

                if !headers.isEmpty {
                    McpDivider()
                }

                Button {
                    headers.append(.init(name: "", value: ""))
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "plus.circle.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)

                        Text("添加请求头")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)

                        Spacer()
                    }
                    .frame(minHeight: 52)
                    .padding(.horizontal, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            McpValidationNote(text: headerValidationText, isWarning: hasHeaderWarnings)
        }
    }

    private var draftServer: IOSMcpServerConfig? {
        let trimmedName = name.trimmed
        let trimmedURL = serverURL.trimmed
        guard !trimmedName.isEmpty, !trimmedURL.isEmpty else { return nil }
        let headerMap = Dictionary(uniqueKeysWithValues: validHeaders.map { ($0.name.trimmed, $0.value.trimmed) })
        let preservedTools = editingServer?.tools ?? []
        switch transport {
        case .streamableHTTP:
            return .streamableHTTP(name: trimmedName, url: trimmedURL, headers: headerMap, enabled: enabled, tools: preservedTools)
        case .sse:
            return .sse(name: trimmedName, url: trimmedURL, headers: headerMap, enabled: enabled, tools: preservedTools)
        }
    }

    private var validHeaders: [McpHeaderDraft] {
        headers.filter { !$0.name.trimmed.isEmpty && !$0.value.trimmed.isEmpty }
    }

    private var hasHeaderWarnings: Bool {
        headers.contains { $0.name.trimmed.isEmpty || $0.value.trimmed.isEmpty }
    }

    private var headerValidationText: String {
        if hasHeaderWarnings {
            return "空名称或空值不会保存到请求头。"
        }
        return "\(validHeaders.count) 个请求头将随服务器配置保存。"
    }
}

private struct McpStatusRow: View {
    let title: String
    let subtitle: String
    let badge: String

    var body: some View {
        HStack(spacing: 12) {
            Text("{ }")
                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                .foregroundStyle(AmberTheme.accentCyan)
                .frame(width: 32, height: 32)
                .background(AmberTheme.accentCyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)

                Text(badge)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 72)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct McpPillModel: Identifiable {
    let id = UUID()
    let text: String
    let kind: McpPillKind
}

private enum McpPillKind {
    case network
    case connected
    case idle

    var foreground: Color {
        switch self {
        case .network: AmberTheme.accentCyan
        case .connected: AmberTheme.accentGreen
        case .idle: AmberTheme.muted
        }
    }

    var background: Color {
        switch self {
        case .network: AmberTheme.accentCyan.opacity(0.12)
        case .connected: AmberTheme.accentGreen.opacity(0.12)
        case .idle: AmberTheme.surface2
        }
    }
}

private struct McpServerRow: View {
    let server: IOSMcpServerConfig
    let status: String
    let onToggle: (Bool) -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text("{ }")
                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                .foregroundStyle(AmberTheme.accentCyan)
                .frame(width: 32, height: 32)
                .background(AmberTheme.accentCyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(server.name.isEmpty ? "未命名" : server.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(server.transportTitle) · \(server.url)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                Text(status)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(server.enabled ? AmberTheme.accentGreen : AmberTheme.muted2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: Binding(
                get: { server.enabled },
                set: { value in
                    Task { @MainActor in onToggle(value) }
                }
            ))
                .labelsHidden()
                .toggleStyle(.switch)
                .accessibilityLabel(server.name.isEmpty ? "MCP 服务器" : server.name)
                .accessibilityValue(server.enabled ? "开启" : "关闭")

            Button(action: onEdit) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 30, height: 30)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("编辑 MCP 服务器")

            Button(role: .destructive, action: onDelete) {
                Image(systemName: "trash")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(width: 30, height: 30)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("删除 MCP 服务器")
        }
        .frame(minHeight: 74)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct McpToolToggleRow: View {
    let discovered: IOSMcpDiscoveredTool
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: discovered.tool.enabled ? "wrench.and.screwdriver.fill" : "wrench.and.screwdriver")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(discovered.tool.enabled ? AmberTheme.accentGreen : AmberTheme.muted2)
                .frame(width: 30, height: 30)
                .background(
                    (discovered.tool.enabled ? AmberTheme.accentGreen : AmberTheme.surface2).opacity(0.12),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 3) {
                Text(discovered.tool.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                if let description = discovered.tool.description, !description.isEmpty {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                Text(discovered.serverName)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: Binding(
                get: { discovered.tool.enabled },
                set: { value in
                    Task { @MainActor in onToggle(value) }
                }
            ))
                .labelsHidden()
                .toggleStyle(.switch)
                .accessibilityLabel(discovered.tool.name)
                .accessibilityValue(discovered.tool.enabled ? "开启" : "关闭")
        }
        .frame(minHeight: 64)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct FlowPills: View {
    let pills: [McpPillModel]

    var body: some View {
        HStack(spacing: 5) {
            ForEach(pills) { pill in
                Text(pill.text)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(pill.kind.foreground)
                    .lineLimit(1)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(pill.kind.background, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            }
        }
    }
}

private struct McpActionRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
                    .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct McpSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private struct McpDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 58)
    }
}

private struct McpNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private struct McpDraftHeader: View {
    let title: String
    let doneTitle: String
    let dismiss: () -> Void

    var body: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回 MCP 服务器", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text(title)
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text(doneTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel(doneTitle)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }
}

private enum McpTransportOption: String, CaseIterable, Identifiable {
    case streamableHTTP
    case sse

    var id: String { rawValue }

    init(server: IOSMcpServerConfig) {
        switch server {
        case .streamableHTTP:
            self = .streamableHTTP
        case .sse:
            self = .sse
        }
    }

    var title: String {
        switch self {
        case .streamableHTTP: "Streamable HTTP"
        case .sse: "SSE"
        }
    }

    var defaultURL: String {
        switch self {
        case .streamableHTTP: "https://mcp.context7.com/mcp"
        case .sse: "https://example.com/sse"
        }
    }
}

private struct McpHeaderDraft: Identifiable, Hashable {
    let id = UUID()
    var name: String
    var value: String
}

private struct McpDraftTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var monospace = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            TextField(placeholder, text: $text)
                .font(monospace ? .system(size: 14, weight: .regular, design: .monospaced) : .body)
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
    }
}

private struct McpDraftPickerRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)

                Text(value)
                    .font(.body)
                    .foregroundStyle(AmberTheme.accent)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct McpDraftToggleRow: View {
    let title: String
    let subtitle: String
    let isOn: Bool
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(disabled ? AmberTheme.muted : AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                McpSwitch(isOn: isOn)
                    .opacity(disabled ? 0.5 : 1)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .accessibilityLabel(title)
        .accessibilityValue(disabled ? "不可用" : (isOn ? "开启" : "关闭"))
    }
}

private struct McpHeaderDraftRow: View {
    @Binding var header: McpHeaderDraft
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 10) {
                TextField("Header", text: $header.name)
                    .font(.system(size: 14, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(width: 32, height: 32)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("删除请求头")
            }

            TextField("Value", text: $header.value)
                .font(.system(size: 14, weight: .regular, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .background(AmberTheme.surface2.opacity(0.58), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct McpPreviewRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.system(size: 14, weight: .medium, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct McpValidationNote: View {
    let text: String
    let isWarning: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 7) {
            Image(systemName: isWarning ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isWarning ? AmberTheme.accentAmber : AmberTheme.accentGreen)
                .padding(.top, 2)

            Text(text)
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 7)
    }
}

private extension String {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
