import SwiftUI

struct SubAgentsView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var isEnabled = true
    @State private var mode: SubAgentModeChoice = .roster
    @State private var allowsDynamicRoles = true
    @State private var maxConcurrentRuns = 2
    @State private var maxTurns = 4
    @State private var timeout: SubAgentTimeoutChoice = .fiveMinutes
    @State private var outputBudget: SubAgentBudgetChoice = .standard

    private let builtInRoles: [SubAgentRoleSummary] = [
        .init(name: "Explorer", handle: "explorer", summary: "跨多源快速并行侦察，回答「在哪 / 大概有什么」", value: "跟随"),
        .init(name: "Historian", handle: "historian", summary: "历史会话搜索 / 主题挖掘 / 跨分片综合", value: "跟随"),
        .init(name: "Oracle", handle: "oracle", summary: "深度推理与评审：架构决策、bug 根因、方案 review、风险", value: "GPT-5.2"),
        .init(name: "Designer", handle: "designer", summary: "视觉产出：SVG / HTML PPT / widget 的版式、配色、信息密度", value: "Gemini-3-pro"),
        .init(name: "Writer", handle: "writer", summary: "中文写作：公众号 / 小红书 / 邮件 / 文学性改写与润色", value: "GLM-4.6"),
        .init(name: "Fixer", handle: "fixer", summary: "便宜模型做边界清晰的执行：翻译、格式转换、抽取、命名", value: "跟随")
    ]

    private let customRoles: [SubAgentRoleSummary] = [
        .init(name: "ReleaseNotes", handle: "release-notes", summary: "把 git 提交整理成面向用户的更新日志", value: "跟随"),
        .init(name: "MeetingNotes", handle: "meeting-notes", summary: "会议转录 → 决议 / 待办 / 一句话纪要", value: "跟随"),
        .init(name: "SqlExplain", handle: "sql-explain", summary: "逐句拆解复杂 SQL，标出代价与风险", value: "跟随")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        masterSection
                        runtimeSection
                        limitsSection
                        builtInRolesSection
                        customRolesSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("SubAgent")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("主 Agent 为边界清楚的子任务启动隔离子代理——各自独立的模型与上下文，互不干扰，用 @ 在对话里直接点名。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var masterSection: some View {
        AmberFormGroup {
            SubAgentToggleRow(
                systemImage: "person.2",
                iconColor: AmberTheme.accentGreen,
                title: "启用子代理",
                subtitle: "在对话里用 @ 调用专项子代理",
                isOn: $isEnabled
            )
        }
    }

    private var runtimeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "运行")

            HStack(spacing: 4) {
                ForEach(SubAgentModeChoice.allCases) { choice in
                    Button {
                        mode = choice
                        if choice == .smart {
                            allowsDynamicRoles = true
                        }
                    } label: {
                        Text(choice.title)
                            .font(.system(size: 14, weight: mode == choice ? .semibold : .regular))
                            .foregroundStyle(mode == choice ? AmberTheme.accent : AmberTheme.muted)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(mode == choice ? AmberTheme.background : Color.clear, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3)
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            AmberFormGroup {
                SubAgentToggleRow(
                    systemImage: "sparkles",
                    iconColor: AmberTheme.accentAmber,
                    title: "允许动态角色",
                    subtitle: "主 Agent 可临时定义窄角色，需通过边界与预算校验",
                    isOn: dynamicBinding,
                    isEnabled: mode != .smart
                )
            }

            if mode == .smart {
                SubAgentFootnote(text: "智能模式下，普通内置角色不再暴露给主 Agent；你保存的自定义角色仍然可用。")
            }
        }
    }

    private var dynamicBinding: Binding<Bool> {
        Binding(
            get: { mode == .smart || allowsDynamicRoles },
            set: { allowsDynamicRoles = $0 }
        )
    }

    private var limitsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "限制")
            AmberFormGroup {
                Menu {
                    ForEach([1, 2, 3, 4, 5], id: \.self) { value in
                        Button("\(value)") { maxConcurrentRuns = value }
                    }
                } label: {
                    SubAgentOptionRow(
                        title: "最大并发数",
                        subtitle: "同时运行的子代理数",
                        value: "\(maxConcurrentRuns)"
                    )
                }
                .buttonStyle(.plain)

                SubAgentDivider()

                Menu {
                    ForEach([2, 4, 6, 8], id: \.self) { value in
                        Button("\(value)") { maxTurns = value }
                    }
                } label: {
                    SubAgentOptionRow(
                        title: "单任务轮数",
                        subtitle: "单个子代理最多来回的轮次",
                        value: "\(maxTurns)"
                    )
                }
                .buttonStyle(.plain)

                SubAgentDivider()

                Menu {
                    ForEach(SubAgentTimeoutChoice.allCases) { option in
                        Button(option.title) { timeout = option }
                    }
                } label: {
                    SubAgentOptionRow(
                        title: "任务超时",
                        subtitle: "超时未返回即结束",
                        value: timeout.title
                    )
                }
                .buttonStyle(.plain)

                SubAgentDivider()

                Menu {
                    ForEach(SubAgentBudgetChoice.allCases) { option in
                        Button(option.title) { outputBudget = option }
                    }
                } label: {
                    SubAgentOptionRow(
                        title: "输出上限",
                        subtitle: "单个子代理输出的长度上限",
                        value: outputBudget.title
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var builtInRolesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置角色")

            if mode == .smart {
                AmberFormGroup {
                    SubAgentEmptyRow(text: "智能模式会隐藏普通内置角色，由主 Agent 按任务边界临时选择或定义角色。")
                }
            } else {
                SubAgentFootnote(text: "每个角色都是一个特化子代理，可单独选模型——把“快/便宜”和“强/慢”混搭着用。")
                AmberFormGroup {
                    ForEach(Array(builtInRoles.enumerated()), id: \.element.id) { index, role in
                        SubAgentRoleRow(role: role) {
                            router.navigate(to: .subAgentRole(name: role.name, roleId: role.handle))
                        }

                        if index != builtInRoles.indices.last {
                            SubAgentDivider()
                        }
                    }
                }
            }
        }
    }

    private var customRolesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "自定义角色")
            SubAgentFootnote(text: "由主 Agent 按需智能创建并保存，在这里管理或删除。")

            AmberFormGroup {
                ForEach(Array(customRoles.enumerated()), id: \.element.id) { index, role in
                    SubAgentRoleRow(role: role) {
                        router.navigate(to: .subAgentRole(name: role.name, roleId: role.handle))
                    }

                    if index != customRoles.indices.last {
                        SubAgentDivider()
                    }
                }
            }
        }
    }
}

private enum SubAgentModeChoice: CaseIterable, Identifiable {
    case roster
    case smart

    var id: Self { self }

    var title: String {
        switch self {
        case .roster: "固定角色"
        case .smart: "智能模式"
        }
    }
}

private enum SubAgentTimeoutChoice: CaseIterable, Identifiable {
    case oneMinute
    case threeMinutes
    case fiveMinutes
    case tenMinutes
    case twentyMinutes

    var id: Self { self }

    var title: String {
        switch self {
        case .oneMinute: "1 分钟"
        case .threeMinutes: "3 分钟"
        case .fiveMinutes: "5 分钟"
        case .tenMinutes: "10 分钟"
        case .twentyMinutes: "20 分钟"
        }
    }
}

private enum SubAgentBudgetChoice: CaseIterable, Identifiable {
    case standard
    case extended

    var id: Self { self }

    var title: String {
        switch self {
        case .standard: "标准"
        case .extended: "加长"
        }
    }
}

private struct SubAgentRoleSummary: Identifiable {
    var id: String { handle }
    let name: String
    let handle: String
    let summary: String
    let value: String

    var initials: String {
        String(name.prefix(1))
    }
}

private struct SubAgentToggleRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    @Binding var isOn: Bool
    var isEnabled = true

    var body: some View {
        Button {
            guard isEnabled else { return }
            isOn.toggle()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor.opacity(isEnabled ? 1 : 0.5))
                    .frame(width: 32, height: 32)
                    .background(iconColor.opacity(isEnabled ? 0.12 : 0.06), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(isEnabled ? AmberTheme.foreground : AmberTheme.muted)
                    Text(subtitle)
                        .font(.caption)
                        .lineLimit(2)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                SubAgentSwitch(isOn: isOn, isEnabled: isEnabled)
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

private struct SubAgentOptionRow: View {
    let title: String
    let subtitle: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.system(size: 13, weight: .medium, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.78)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SubAgentRoleRow: View {
    let role: SubAgentRoleSummary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(role.initials)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 7) {
                        Text(role.name)
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("@\(role.handle)")
                            .font(.system(size: 11, weight: .regular, design: .monospaced))
                            .foregroundStyle(AmberTheme.muted2)
                    }

                    Text(role.summary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(role.value)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SubAgentEmptyRow: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
    }
}

private struct SubAgentSwitch: View {
    let isOn: Bool
    var isEnabled = true

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .opacity(isEnabled ? 1 : 0.55)
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

private struct SubAgentDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SubAgentFootnote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

#Preview {
    NavigationStack {
        SubAgentsView()
    }
    .environment(RouterPath())
}
