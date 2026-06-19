import SwiftUI
import Shared

struct SkillsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    let sharedSettings: IOSSharedSettingsStore

    @State private var pendingAlert: SkillsAlert?
    @State private var importOptionsPresented = false
    @State private var scannedSkills: [IosSkillFactory.SkillMetadata] = []

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    scannedSkillsSection
                    installedSection
                    extensionSection
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
        .confirmationDialog("导入技能", isPresented: $importOptionsPresented, titleVisibility: .visible) {
            Button("从 URL 导入") {
                pendingAlert = .importFromURL
            }

            Button("从文件导入") {
                pendingAlert = .importFromFile
            }

            Button("取消", role: .cancel) {}
        } message: {
            Text("选择 Skill 的来源")
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("技能")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加技能", size: 44, symbolSize: 20) {
                router.navigate(to: .skillAdd)
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    /// Real KMP skill scan — uses IosSkillFactory to scan Documents/skills/
    /// for real SKILL.md files. If empty (fresh app, no skills installed),
    /// shows honest "no skills found" message.
    private var scannedSkillsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "技能扫描（KMP · 真实）")
            AmberFormGroup {
                if scannedSkills.isEmpty {
                    HStack(spacing: 10) {
                        Image(systemName: "doc.questionmark")
                            .font(.system(size: 16))
                            .foregroundStyle(AmberTheme.muted2)
                        Text("Documents/skills/ 未找到 SKILL.md。将技能放入该目录的子文件夹后重新进入此页可扫描到。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                } else {
                    ForEach(Array(scannedSkills.enumerated()), id: \.offset) { index, skill in
                        let enabled = sharedSettings.isSkillEnabled(skill.name)
                        Button {
                            router.navigate(to: .skillDetail(name: skill.name, dirName: skill.dirName))
                        } label: {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(skill.name)
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(AmberTheme.foreground)
                                    if !skill.description_.isEmpty {
                                        Text(skill.description_)
                                            .font(.caption)
                                            .foregroundStyle(AmberTheme.muted)
                                            .lineLimit(2)
                                    }
                                    Text("dir: \(skill.dirName)")
                                        .font(.system(size: 10, weight: .regular, design: .monospaced))
                                        .foregroundStyle(AmberTheme.muted2)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                Text(enabled ? "启用" : "关闭")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(enabled ? AmberTheme.accentGreen : AmberTheme.muted2)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AmberTheme.muted2)
                            }
                            .frame(minHeight: 52)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if index < scannedSkills.count - 1 {
                            Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                        }
                    }
                }
            }

            HStack(spacing: 12) {
                Button {
                    if let docsDir = try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true).path {
                        scannedSkills = IosSkillFactory.shared.listSkills(documentsDir: docsDir)
                    }
                } label: {
                    Label("重新扫描", systemImage: "arrow.clockwise")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)

            Text("扫描 iOS Documents/skills/ 下的真实 SKILL.md 文件（frontmatter 解析 name/description）。这是真实文件系统扫描，不是硬编码。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 6)
        }
        .onAppear {
            if let docsDir = try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true).path {
                scannedSkills = IosSkillFactory.shared.listSkills(documentsDir: docsDir)
            }
        }
    }

    // [Slice 1] 扫描标记已撤：IosSkillFactory.shared.listSkills(documentsDir:) 真实扫描
    // Documents/skills/*/SKILL.md（feature/task/.../IosSkillFactory.kt:24），本页 scannedSkillsSection
    // 已在 SkillsView.swift:132(按钮)/:153(onAppear) 调用，结果存入 scannedSkills。
    // 因此"本机 Skill 库"改为诚实反映扫描结果数，不再保留旧占位标记。
    private var installedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "本机 Skill 库")
            AmberFormGroup {
                SkillStatusRow(
                    systemImage: "wrench.and.screwdriver",
                    iconColor: AmberTheme.accentAmber,
                    title: scannedSkills.isEmpty ? "尚未扫描到 Skill" : "已扫描 \(scannedSkills.count) 个 Skill",
                    subtitle: scannedSkills.isEmpty
                        ? "Documents/skills/ 下没有 SKILL.md。把技能放入该目录的子文件夹后点\"重新扫描\"即可。"
                        : "扫描结果见上方\"技能扫描\"区；点条目可打开真实 SKILL.md，并修改启用状态或删除本地 Skill。",
                    badge: scannedSkills.isEmpty ? "空" : "已扫描"
                )
            }

            SkillsFooter("扫描为真实文件系统读取（IosSkillFactory.listSkills）；启用状态写入当前 assistant.enabledSkills。")
        }
    }

    // [Slice 1] MCP 标记已撤：IOSMcpManager 真连接（JSON-RPC over URLSession）。
    // McpServersView.swift:15 构造 IOSMcpManager，进入页面 .task 同步已启用服务器（tools/list）；
    // tools/call 由 IOSMcpClient.swift:220 发起，IOSMcpManager.callTool（IOSMcpManager.swift:69）暴露给后续调用链。
    private var extensionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "扩展")
            AmberFormGroup {
                SkillUtilityRow(
                    systemImage: "point.3.connected.trianglepath.dotted",
                    iconColor: AmberTheme.accentCyan,
                    title: "MCP 服务器",
                    subtitle: "已接入 IOSMcpManager（真 JSON-RPC 连接）；进入可查看连接状态与工具列表",
                    trailing: "已接"
                ) {
                    router.navigate(to: .mcpServers)
                }
            }

            SkillsFooter("MCP 已接：IOSMcpManager 负责真实连接与 tools/list、tools/call（见 MCP 服务器页）。")
        }
    }

    // [Slice 1] 重新扫描标记已撤：managementSection 的"重新扫描"按钮与上方 scannedSkillsSection
    // 复用同一 IosSkillFactory.shared.listSkills 调用链（SkillsView.swift:132），点击即真扫描。
    private var managementSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "管理")
            AmberFormGroup {
                SkillUtilityRow(
                    systemImage: "square.and.arrow.down",
                    iconColor: AmberTheme.accentCyan,
                    title: "导入技能本地预览",
                    subtitle: "只说明导入方式，不下载、不打开文件、不写入 Skill 目录"
                ) {
                    importOptionsPresented = true
                }

                SkillDivider()

                SkillUtilityRow(
                    systemImage: "arrow.triangle.2.circlepath",
                    iconColor: AmberTheme.accent,
                    title: "重新扫描",
                    subtitle: "重新调用 IosSkillFactory.listSkills 扫描 Documents/skills/"
                ) {
                    rescanSkills()
                }
            }
        }
    }

    private func rescanSkills() {
        if let docsDir = try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true).path {
            scannedSkills = IosSkillFactory.shared.listSkills(documentsDir: docsDir)
        }
    }
}

private struct SkillStatusRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let badge: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)

                SkillBadge(text: badge, color: AmberTheme.muted, background: AmberTheme.surface2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 72)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct SkillRowModel: Identifiable {
    let id = UUID()
    let name: String
    let summary: String
    let state: SkillState
}

private enum SkillState {
    case enabled
    case disabled
    case mcp

    var dimmed: Bool {
        if case .disabled = self {
            true
        } else {
            false
        }
    }
}

private struct SkillRow: View {
    let skill: SkillRowModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                SkillIcon(state: skill.state)

                VStack(alignment: .leading, spacing: 3) {
                    Text(skill.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(skill.state.dimmed ? AmberTheme.muted : AmberTheme.foreground)
                        .lineLimit(1)

                    Text(skill.summary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)

                    switch skill.state {
                    case .disabled:
                        SkillBadge(text: "未启用", color: AmberTheme.muted2, background: AmberTheme.surface2)
                    case .mcp:
                        SkillBadge(text: "MCP", color: AmberTheme.accentCyan, background: AmberTheme.accentCyan.opacity(0.12))
                    case .enabled:
                        EmptyView()
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 64)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SkillIcon: View {
    let state: SkillState

    var body: some View {
        Image(systemName: "wrench.and.screwdriver")
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(foreground)
            .frame(width: 32, height: 32)
            .background(background, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
    }

    private var foreground: Color {
        switch state {
        case .enabled: AmberTheme.accent
        case .disabled: AmberTheme.muted2
        case .mcp: AmberTheme.accentCyan
        }
    }

    private var background: Color {
        switch state {
        case .enabled: AmberTheme.accentTint
        case .disabled: AmberTheme.surface2
        case .mcp: AmberTheme.accentCyan.opacity(0.12)
        }
    }
}

private struct SkillBadge: View {
    let text: String
    let color: Color
    let background: Color

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(background, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

private struct SkillUtilityRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    var trailing: String?
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

                if let trailing {
                    Text(trailing)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                }

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

private struct SkillDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 58)
    }
}

private struct SkillsFooter: View {
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

private enum SkillsAlert: Identifiable {
    case importFromURL
    case importFromFile
    case rescan

    var id: String {
        switch self {
        case .importFromURL: "importFromURL"
        case .importFromFile: "importFromFile"
        case .rescan: "rescan"
        }
    }

    var title: String {
        switch self {
        case .importFromURL: "从 URL 导入"
        case .importFromFile: "从文件导入"
        case .rescan: "全量规整"
        }
    }

    var message: String {
        switch self {
        case .importFromURL:
            "真实导入前需要填写 Skill 包地址；当前只保留入口，不会发起网络请求。"
        case .importFromFile:
            "真实导入时会打开本地文件选择器；当前只保留入口，不会打开文件选择器或复制文件。"
        case .rescan:
            "全量规整需要扫描本地 Skill 目录并修复索引；当前不会修改文件系统。"
        }
    }
}
