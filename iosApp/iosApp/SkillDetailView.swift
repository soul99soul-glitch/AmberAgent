import SwiftUI

struct SkillDetailView: View {
    @Environment(\.dismiss) private var dismiss

    let skillName: String
    @State private var pendingAlert: SkillDetailAlert?

    init(skillName: String) {
        self.skillName = skillName
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    hero
                    enableSection
                    triggerSection
                    toolsSection
                    infoSection
                    deleteSection
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

            Text("技能详情")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            SkillEditButton {
                pendingAlert = .edit
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var hero: some View {
        VStack(spacing: 8) {
            Image(systemName: "wrench.and.screwdriver")
                .font(.system(size: 27, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 64, height: 64)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }

            Text(skillName)
                .font(.title3.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            HStack(spacing: 6) {
                Circle()
                    .fill(AmberTheme.muted2)
                    .frame(width: 7, height: 7)
                Text("未接线 · 未读取 SKILL.md")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
        .padding(.bottom, 22)
    }

    private var enableSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                HStack(spacing: 12) {
                    Text("启用状态")
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text("未接线")
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(minHeight: 52)
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
            }

            SkillDetailFooter("iOS 尚未接入 SkillManager 和 assistant.enabledSkills；当前不会修改启用状态。")
        }
    }

    private var triggerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "触发条件")
            Text(triggerText)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
                .padding(.horizontal, 16)

            SkillDetailFooter("真实触发条件需要读取 SKILL.md；iOS 详情页尚未接线。")
        }
    }

    private var toolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具与权限")
            AmberFormGroup {
                SkillDetailValueRow(title: "允许的工具", value: "未读取", monospace: false) {
                    pendingAlert = .allowedTools
                }
            }

            SkillDetailFooter("需要读取真实 SKILL.md frontmatter 后才能展示 allowed-tools。")
        }
    }

    private var infoSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "信息")
            AmberFormGroup {
                SkillStaticValueRow(title: "版本", value: "未知", monospace: true)
                SkillDetailDivider()
                SkillStaticValueRow(title: "来源", value: "未接线")
                SkillDetailDivider()
                SkillDetailValueRow(title: "文件", value: "SKILL.md", monospace: true) {
                    pendingAlert = .file
                }
            }

            SkillDetailFooter("当前不会读取本地 Skill 目录或文件树。")
        }
    }

    private var deleteSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                Button {
                    pendingAlert = .delete
                } label: {
                    Text("删除尚未接线")
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 20)

            SkillDetailFooter("删除需要真实 Skill 文件系统桥和确认流程；当前不会删除任何文件。")
        }
    }

    private var triggerText: String {
        "iOS 详情页尚未接入 SkillManager，无法读取 \(skillName) 的真实触发说明。"
    }
}

private struct SkillEditButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("编辑")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(height: 36)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("编辑 SKILL.md")
    }
}

private struct SkillDetailSwitch: View {
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

private struct SkillDetailValueRow: View {
    let title: String
    let value: String
    var monospace = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Text(value)
                    .font(monospace ? .system(.caption, design: .monospaced) : .subheadline)
                    .foregroundStyle(AmberTheme.muted)

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
    }
}

private struct SkillStaticValueRow: View {
    let title: String
    let value: String
    var monospace = false

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(monospace ? .system(.subheadline, design: .monospaced) : .subheadline)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct SkillDetailDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct SkillDetailFooter: View {
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

private enum SkillDetailAlert: Identifiable {
    case edit
    case allowedTools
    case file
    case delete

    var id: String {
        switch self {
        case .edit: "edit"
        case .allowedTools: "allowed-tools"
        case .file: "file"
        case .delete: "delete"
        }
    }

    var title: String {
        switch self {
        case .edit: "编辑 SKILL.md"
        case .allowedTools: "允许的工具"
        case .file: "文件"
        case .delete: "删除技能"
        }
    }

    var message: String {
        switch self {
        case .edit:
            "编辑 SKILL.md 需要接入文件编辑和校验流程；当前不会修改技能文件。"
        case .allowedTools:
            "工具权限需要读取真实 SKILL.md 的 allowed-tools 声明；当前不会展示或修改权限。"
        case .file:
            "文件查看需要接入 Skill 文件浏览器；当前不会读取本地文件。"
        case .delete:
            "删除技能会修改本地 Skill 目录；当前不会删除任何文件。"
        }
    }
}
