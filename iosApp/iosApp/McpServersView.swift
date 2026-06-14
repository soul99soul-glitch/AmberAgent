import SwiftUI

struct McpServersView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.mcp.amap.enabled") private var amapEnabled = true
    @AppStorage("app.amber.ios.mcp.feishu.enabled") private var feishuEnabled = true
    @AppStorage("app.amber.ios.mcp.github.enabled") private var githubEnabled = false
    @State private var pendingAlert: McpAlert?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    connectedSection
                    managementSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $pendingAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
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
                AmberGlassCircleButton(systemImage: "square.and.arrow.down", accessibilityLabel: "导入服务器", size: 38, symbolSize: 16) {
                    pendingAlert = .importServer
                }
                AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加服务器", size: 38, symbolSize: 17) {
                    pendingAlert = .addServer
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("MCP（Model Context Protocol）服务器为 Agent 接入外部工具。已连接服务器的工具会并入技能列表，可在对话中调用。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var connectedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已连接")
            AmberFormGroup {
                McpServerRow(
                    name: "高德地图",
                    pills: [
                        .init(text: "Streamable HTTP", kind: .network),
                        .init(text: "已连接", kind: .connected),
                        .init(text: "12 个工具", kind: .idle)
                    ],
                    isOn: amapEnabled
                ) {
                    amapEnabled.toggle()
                }

                McpDivider()

                McpServerRow(
                    name: "飞书 feishu",
                    pills: [
                        .init(text: "SSE", kind: .network),
                        .init(text: "已连接", kind: .connected),
                        .init(text: "8 个工具", kind: .idle)
                    ],
                    isOn: feishuEnabled
                ) {
                    feishuEnabled.toggle()
                }

                McpDivider()

                McpServerRow(
                    name: "GitHub",
                    pills: [
                        .init(text: "Streamable HTTP", kind: .network),
                        .init(text: "未连接", kind: .idle)
                    ],
                    isOn: githubEnabled
                ) {
                    githubEnabled.toggle()
                }
            }

            McpNote("关闭后该服务器的工具不再加载。左滑删除，下拉可重新连接全部服务器。")
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
                    subtitle: "粘贴标准 mcpServers JSON 配置"
                ) {
                    pendingAlert = .importServer
                }

                McpDivider()

                McpActionRow(
                    systemImage: "plus",
                    iconColor: AmberTheme.accent,
                    title: "手动添加",
                    subtitle: "填写传输类型、服务器地址与请求头"
                ) {
                    pendingAlert = .addServer
                }
            }

            McpNote("支持 SSE 与 Streamable HTTP 两种传输；可为每台服务器设置自定义请求头与工具审批。")
        }
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

private enum McpAlert: Identifiable {
    case importServer
    case addServer

    var id: String {
        switch self {
        case .importServer: "import-server"
        case .addServer: "add-server"
        }
    }

    var title: String {
        switch self {
        case .importServer: "导入服务器"
        case .addServer: "手动添加"
        }
    }

    var message: String {
        switch self {
        case .importServer:
            "导入需要解析 mcpServers JSON 并写入设置；当前不会读取剪贴板或修改配置。"
        case .addServer:
            "手动添加需要传输类型、服务器地址、请求头和工具审批编辑表单；当前只保留入口。"
        }
    }
}
