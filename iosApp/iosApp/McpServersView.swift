import SwiftUI
import Shared

struct McpServersView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    seedConfigSection
                    connectedSection
                    managementSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
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
        Text("Android/KMP 已有 MCP 配置、连接和工具调用链路；iOS 当前可读取 Settings.mcpServers 真实配置（只读），连接管理和工具调用待接。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    /// Read-only view of the REAL seeded MCP server configs from Settings.
    /// Seed is empty (MCP servers are user-configured, not seeded). Shows honest
    /// empty state or real config entries.
    private var seedConfigSection: some View {
        let servers = sharedSettings.snapshot.mcpServers
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "已配置 MCP 服务器（KMP · 只读）")
            AmberFormGroup {
                if servers.isEmpty {
                    HStack(spacing: 10) {
                        Image(systemName: "server.rack")
                            .font(.system(size: 16))
                            .foregroundStyle(AmberTheme.muted2)
                        Text("Settings.mcpServers 为空。种子数据不包含 MCP 服务器（需用户自行配置）。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                } else {
                    ForEach(Array(servers.enumerated()), id: \.offset) { index, server in
                        VStack(alignment: .leading, spacing: 3) {
                            let name = server.commonOptions.name
                            Text(name.isEmpty ? "未命名" : name)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("\(server.commonOptions.tools.count) 工具 · \(server.commonOptions.enable ? "启用" : "禁用")")
                                .font(.system(size: 11, weight: .regular, design: .monospaced))
                                .foregroundStyle(AmberTheme.muted2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)

                        if index < servers.count - 1 {
                            Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                        }
                    }
                }
            }
        }
    }

    private var connectedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "配置状态")
            AmberFormGroup {
                McpStatusRow(
                    title: "MCP 配置桥尚执行待接",
                    subtitle: "没有读取 Settings.mcpServers，也没有订阅 McpManager.syncingStatus。",
                    badge: "执行待接"
                )
            }

            McpNote("当前页面不会连接服务器、同步工具、切换启用状态或删除配置。")
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
                    subtitle: "粘贴标准 mcpServers JSON，仅做粗略文本预览"
                ) {
                    router.navigate(to: .mcpImport)
                }

                McpDivider()

                McpActionRow(
                    systemImage: "plus",
                    iconColor: AmberTheme.accent,
                    title: "手动添加",
                    subtitle: "填写传输类型、服务器地址与请求头草稿"
                ) {
                    router.navigate(to: .mcpAdd)
                }
            }

            McpNote("导入和手动添加页面当前不写入设置、不保存请求头、不发起 MCP 连接。")
        }
    }
}

struct McpImportView: View {
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
                McpDraftHeader(title: "导入服务器", doneTitle: "关闭") {
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

                        Text("粘贴 Claude / Codex 常见的 mcpServers 配置。当前只做粗略文本预览，不读取剪贴板、不写入设置。")
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
                McpPreviewRow(title: "粗略条目数", value: "\(estimatedServerCount)")
                McpDivider()
                McpPreviewRow(title: "处理方式", value: "本地草稿")
            }
        }
    }

    private var hasWarnings: Bool {
        !jsonText.contains("\"mcpServers\"") || estimatedServerCount == 0
    }

    private var validationText: String {
        if !jsonText.contains("\"mcpServers\"") {
            return "未检测到 mcpServers 文本；当前不会写入真实配置。"
        }

        if estimatedServerCount == 0 {
            return "文本包含 mcpServers，但粗略预览没有识别到服务器条目。"
        }

        return "粗略识别到 \(estimatedServerCount) 个疑似服务器条目；当前未使用真实 McpImportParser，只保留本地草稿。"
    }

    private var estimatedServerCount: Int {
        let lines = jsonText
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
        return lines.filter { line in
            line.hasPrefix("\"") &&
                line.contains("\":") &&
                !line.contains("\"mcpServers\"") &&
                !line.contains("\"transport\"") &&
                !line.contains("\"url\"") &&
                !line.contains("\"headers\"")
        }.count
    }
}

struct McpAddView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var name = "context7"
    @State private var transport: McpTransportOption = .streamableHTTP
    @State private var serverURL = "https://mcp.context7.com/mcp"
    @State private var needsApproval = true
    @State private var headers: [McpHeaderDraft] = [
        .init(name: "X-Client", value: "AmberAgent")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                McpDraftHeader(title: "手动添加", doneTitle: "关闭") {
                    dismiss()
                }

                ScrollView {
                    VStack(spacing: 0) {
                        connectionSection
                        headersSection
                        toolsSection
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

            McpNote("当前不会连接服务器，也不会保存到 MCP 配置。真实接入时再绑定 McpManager / SettingsStore。")
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

    private var toolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具审批")
            AmberFormGroup {
                McpDraftToggleRow(
                    title: "草稿工具默认需要批准",
                    subtitle: "仅影响本页草稿；当前没有写入 MCP 工具审批配置。",
                    isOn: needsApproval
                ) {
                    needsApproval.toggle()
                }
            }
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
            return "空名称或空值会在真实写入前被拦截；当前先保留本地草稿。"
        }
        return "\(validHeaders.count) 个请求头已准备好；当前不会保存。"
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
    let name: String
    let pills: [McpPillModel]
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text("{ }")
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                    .foregroundStyle(AmberTheme.accentCyan)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.accentCyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    Text(name)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)

                    FlowPills(pills: pills)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                McpSwitch(isOn: isOn)
            }
            .frame(minHeight: 66)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                McpSwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "开启" : "关闭")
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
