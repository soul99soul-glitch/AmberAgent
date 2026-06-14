import SwiftUI

struct SkillsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @State private var pendingAlert: SkillsAlert?

    private let installedSkills: [SkillRowModel] = [
        .init(name: "skill-creator", summary: "Use when the user wants to create a new AmberAgent skill, update an...", state: .enabled),
        .init(name: "deep-read-fact-check", summary: "Use when verifying a long article, news analysis, social-media clai...", state: .disabled),
        .init(name: "ssh-mac-mini", summary: "通过 SSH 远程操作 Mac mini，常用于离线视频转码、文件整理与备份。", state: .enabled),
        .init(name: "feishu-doc-reader", summary: "读取飞书文档、知识库与多维表格，回答内部检索类问题。", state: .enabled),
        .init(name: "amap-mcp", summary: "高德地图 MCP，地点搜索 / 路线规划 / 公交查询。", state: .mcp)
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
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
                pendingAlert = .add
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var installedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已安装")
            AmberFormGroup {
                ForEach(Array(installedSkills.enumerated()), id: \.element.id) { index, skill in
                    SkillRow(skill: skill) {
                        router.navigate(to: .skillDetail(name: skill.name))
                    }

                    if index != installedSkills.indices.last {
                        SkillDivider()
                    }
                }
            }

            SkillsFooter("Agent 会在每次运行前重新扫描已安装 Skill。已启用 4 个，未启用 1 个。")
        }
    }

    private var extensionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "扩展")
            AmberFormGroup {
                SkillUtilityRow(
                    systemImage: "point.3.connected.trianglepath.dotted",
                    iconColor: AmberTheme.accentCyan,
                    title: "MCP 服务器",
                    subtitle: "外部工具服务器 · 工具会并入技能列表",
                    trailing: "3 个"
                ) {
                    router.navigate(to: .mcpServers)
                }
            }

            SkillsFooter("通过 MCP（Model Context Protocol）接入第三方工具服务器。")
        }
    }

    private var managementSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "管理")
            AmberFormGroup {
                SkillUtilityRow(
                    systemImage: "square.and.arrow.down",
                    iconColor: AmberTheme.accentCyan,
                    title: "导入技能",
                    subtitle: "从 URL 或文件包安装新 Skill"
                ) {
                    pendingAlert = .importSkill
                }

                SkillDivider()

                SkillUtilityRow(
                    systemImage: "arrow.triangle.2.circlepath",
                    iconColor: AmberTheme.accent,
                    title: "全量规整",
                    subtitle: "重新扫描并修复所有 Skill 索引"
                ) {
                    pendingAlert = .rescan
                }
            }
        }
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
    case add
    case importSkill
    case rescan

    var id: String {
        switch self {
        case .add: "add"
        case .importSkill: "import-skill"
        case .rescan: "rescan"
        }
    }

    var title: String {
        switch self {
        case .add: "添加技能"
        case .importSkill: "导入技能"
        case .rescan: "全量规整"
        }
    }

    var message: String {
        switch self {
        case .add:
            "添加技能需要接入安装流程；当前只保留入口。"
        case .importSkill:
            "导入技能需要 URL/文件选择、校验和安装事务；当前不会读取或写入文件。"
        case .rescan:
            "全量规整需要扫描本地 Skill 目录并修复索引；当前不会修改文件系统。"
        }
    }
}
