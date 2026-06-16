import SwiftUI

struct SubAgentsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var runner = SubAgentRunner()

    private let evidenceRows: [SubAgentEvidenceRow] = [
        .init(
            title: "SubAgentRuntimeSetting",
            subtitle: "Android/KMP 通过 Settings.agentRuntime.subAgent 保存 enabled、mode、并发、超时、预算、overrides 与 customDefinitions。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "SubAgentDefinitions",
            subtitle: "KMP 内置 explorer、historian、oracle、designer、writer、fixer 六个只读/专项角色。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "SubAgentTools",
            subtitle: "Android ChatService 在 conversationId 存在且 subAgent.enabled 时注入 list/start/read/wait/cancel 工具。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "iOS 运行桥",
            subtitle: "IosSubAgentFactory 可构造 SubAgentManager，并从 SwiftUI 手动触发 start/read/wait/cancel 调用链。",
            value: "已接",
            color: AmberTheme.accentGreen
        )
    ]

    private let iOSRows: [SubAgentEvidenceRow] = [
        .init(
            title: "启用子代理",
            subtitle: "iOS SettingsStore 没有 subAgent.enabled；ChatViewModel 生成请求也不会注入 subagent_* 工具。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "@ 角色调用",
            subtitle: "iOS 输入框没有 SubAgentDefinitions.extractMentions 或 SubAgentTools system prompt 注入路径。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "运行结果 / 实时面板",
            subtitle: "没有 runId、liveTextFlow、livePartsFlow、transcriptPath 或 AgentTask 状态来源。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "角色覆盖与自定义角色",
            subtitle: "角色详情页只展示字段映射；不会写入 overrides、customDefinitions 或 prompt markdown。",
            value: "本地预览",
            color: AmberTheme.muted
        )
    ]

    private let settingRows: [SubAgentEvidenceRow] = [
        .init(
            title: "enabled / mode",
            subtitle: "Android/KMP 支持 ROSTER 与 SMART_DYNAMIC；iOS 当前不读写这些值。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "allowDynamicSubAgents",
            subtitle: "动态角色需要 SubAgentValidator 校验边界、工具白名单和预算；iOS 未接该校验/保存链。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "maxConcurrentRuns / maxTurns",
            subtitle: "KMP 默认并发 2、单任务 4 轮；SubAgentManager admissionLock 会按真实设置限流。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "timeout / outputBudget",
            subtitle: "KMP 默认 5 分钟、12k 字符，并有扩展档位；iOS 当前不会保存选择。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "overrides / customDefinitions",
            subtitle: "KMP 支持角色 prompt、modelId、temperature、reasoning、预算覆盖和自定义角色。",
            value: "存在",
            color: AmberTheme.accentGreen
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
                        evidenceSection
                        iOSStatusSection
                        settingMapSection
                        builtInRolesSection
                        customDefinitionsSection
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
                Text("SubAgent")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("Android/KMP 已实现 · iOS 可手动启动运行桥")
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
        Text("Android/KMP 已有真实 SubAgent 设置、内置角色、运行管理器和 subagent_* 工具；iOS 现在可从本页手动构造 IosSubAgentFactory 并启动 start/read/wait/cancel 调用链。ChatViewModel 自动注入 subagent_* 工具、SettingsStore 写回和角色持久化仍未接。")
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
            AmberSectionLabel(text: "执行（真实调用链）")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        if runner.isRunning {
                            ProgressView()
                            Text("正在启动 SubAgent…")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                        } else {
                            Button { runner.runTestCycle() } label: {
                                Label("启动 SubAgent", systemImage: "person.2.wave.2.fill")
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

    /// Read-only view of the REAL seeded SubAgent runtime defaults from
    /// `IOSSharedSettingsStore.agentRuntime.subAgent`. Proves the read path;
    /// does NOT enable subagent execution.
    private var presetConfigSection: some View {
        let s = sharedSettings.agentRuntime.subAgent
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "KMP 默认 SubAgent 配置（只读）")
            AmberFormGroup {
                SubAgentPresetKVRow(title: "启用 SubAgent", value: s.enabled ? "默认开" : "默认关")
                SubAgentDivider()
                SubAgentPresetKVRow(title: "允许动态子代理", value: s.allowDynamicSubAgents ? "默认开" : "默认关")
                SubAgentDivider()
                SubAgentPresetKVRow(title: "最大并发运行", value: "\(s.maxConcurrentRuns)")
                SubAgentDivider()
                SubAgentPresetKVRow(title: "最大轮数", value: "\(s.maxTurns)")
            }
        }
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    SubAgentStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        SubAgentDivider()
                    }
                }
            }
        }
    }

    private var iOSStatusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
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

    private var settingMapSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "设置字段")
            AmberFormGroup {
                ForEach(Array(settingRows.enumerated()), id: \.element.id) { index, row in
                    SubAgentStatusRow(row: row)
                    if index < settingRows.count - 1 {
                        SubAgentDivider()
                    }
                }
            }

            SubAgentFootnote(text: "Android 设置页每次 update 都会写 settings.agentRuntime.subAgent，并通过 AgentPromptConfigRepository.writeSubAgentMarkdown 同步 prompt；iOS 当前没有这条桥。")
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

            SubAgentFootnote(text: "这些角色来自 KMP SubAgentDefinitions；iOS 当前不会读取或保存 per-role 模型、推理强度、提示词覆盖。")
        }
    }

    private var customDefinitionsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "自定义角色")
            AmberFormGroup {
                SubAgentStatusRow(
                    row: .init(
                        title: "customDefinitions",
                        subtitle: "KMP 支持持久化自定义角色，但必须先通过 SubAgentValidator 校验边界、工具白名单和预算。",
                        value: "未读取",
                        color: AmberTheme.accentAmber
                    )
                )
            }

            SubAgentFootnote(text: "本页不再展示 ReleaseNotes、MeetingNotes、SqlExplain 等本地假自定义角色，避免误导为已保存配置。")
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

            Text("KMP 存在")
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

/// Read-only key/value row for a real seeded SubAgent setting.
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
