import SwiftUI

struct SubAgentsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var runner = SubAgentRunner()

    private let iOSRows: [SubAgentEvidenceRow] = [
        .init(
            title: "聊天分派",
            subtitle: "聊天中可以把明确的小任务交给专长角色处理；需要可用模型配置。",
            value: "可用",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "@ 角色调用",
            subtitle: "输入框里直接 @ 指定角色还没有开放。",
            value: "未启用",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "实时进度",
            subtitle: "当前只显示最终结果，暂不展示逐步执行过程。",
            value: "未开放",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "角色提示词",
            subtitle: "内置角色的提示词可以编辑并保留；新增自定义角色暂未开放。",
            value: "可编辑",
            color: AmberTheme.accentAmber
        )
    ]

    private let builtInRoles: [SubAgentRoleSummary] = [
        .init(name: "Explorer", handle: "explorer", summary: "跨多源快速并行侦察，速度优先。"),
        .init(name: "Historian", handle: "historian", summary: "历史会话搜索、主题挖掘、跨分片综合。"),
        .init(name: "Oracle", handle: "oracle", summary: "高判断力评审、架构取舍、风险复议。"),
        .init(name: "Designer", handle: "designer", summary: "视觉产出规格、版式、配色和信息密度审查。"),
        .init(name: "Writer", handle: "writer", summary: "中文写作、文案润色、故事与风格改写。"),
        .init(name: "Fixer", handle: "fixer", summary: "边界清晰的机械执行：翻译、格式转换、抽取。")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        runnerSection
                        presetConfigSection
                        iOSStatusSection
                        builtInRolesSection
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

            VStack(spacing: 2) {
                Text("子代理")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("多角色分工处理任务")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("子代理可以把任务拆给不同专长的角色处理。你可以在这里试运行一次，也可以进入角色详情调整提示词。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var runnerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "试运行")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        if runner.isRunning {
                            ProgressView()
                            Text("正在启动子代理…")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                        } else {
                            Button { runner.runTestCycle() } label: {
                                Label("启动子代理", systemImage: "person.2.wave.2.fill")
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(AmberTheme.accent)
                            }
                            .buttonStyle(.plain)
                        }

                        Button { runner.cancelCurrentRun() } label: {
                            Label("取消", systemImage: "xmark.circle")
                                .font(.body.weight(.medium))
                                .foregroundStyle(runner.isRunning ? AmberTheme.accentAmber : AmberTheme.muted)
                        }
                        .buttonStyle(.plain)
                    }

                    if runner.lastRunResult != "(未运行)" {
                        Text(runner.lastRunResult)
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
    }

    private var presetConfigSection: some View {
        let s = sharedSettings.agentRuntime.subAgent
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "默认运行设置")
            AmberFormGroup {
                SubAgentPresetKVRow(title: "启用子代理", value: s.enabled ? "默认开" : "默认关")
                SubAgentDivider()
                SubAgentPresetKVRow(title: "允许动态子代理", value: s.allowDynamicSubAgents ? "默认开" : "默认关")
                SubAgentDivider()
                SubAgentPresetKVRow(title: "最大并发运行", value: "\(s.maxConcurrentRuns)")
                SubAgentDivider()
                SubAgentPresetKVRow(title: "最大轮数", value: "\(s.maxTurns)")
            }
        }
    }

    private var iOSStatusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "当前支持")
            AmberFormGroup {
                ForEach(Array(iOSRows.enumerated()), id: \.element.id) { index, row in
                    SubAgentStatusRow(row: row)
                    if index < iOSRows.count - 1 {
                        SubAgentDivider()
                    }
                }
            }
        }
    }

    private var builtInRolesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置角色")
            AmberFormGroup {
                ForEach(Array(builtInRoles.enumerated()), id: \.element.id) { index, role in
                    Button {
                        router.navigate(to: .subAgentRole(name: role.name, roleId: role.handle))
                    } label: {
                        SubAgentRoleRowContent(role: role)
                    }
                    .buttonStyle(.plain)

                    if index < builtInRoles.count - 1 {
                        SubAgentDivider()
                    }
                }
            }

            SubAgentFootnote(text: "点开角色可调整提示词；自定义角色会在后续版本开放。")
        }
    }
}

private struct SubAgentEvidenceRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct SubAgentRoleSummary: Identifiable {
    let id = UUID()
    let name: String
    let handle: String
    let summary: String
}

private struct SubAgentStatusRow: View {
    let row: SubAgentEvidenceRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(row.value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(row.color)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct SubAgentRoleRowContent: View {
    let role: SubAgentRoleSummary

    var body: some View {
        HStack(spacing: 12) {
            Text(String(role.name.prefix(1)))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 32, height: 32)
                .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(role.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("@\(role.handle)")
                        .font(.caption.monospaced())
                        .foregroundStyle(AmberTheme.muted2)
                }

                Text(role.summary)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text("内置")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.accentGreen)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 62)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SubAgentDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SubAgentPresetKVRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel("\(title)，\(value)")
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
        SubAgentsView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
