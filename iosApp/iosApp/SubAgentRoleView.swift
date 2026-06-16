import SwiftUI
import Shared

struct SubAgentRoleView: View {
    let sharedSettings: IOSSharedSettingsStore
    @Environment(\.dismiss) private var dismiss

    private let detail: SubAgentRoleDetail

    init(sharedSettings: IOSSharedSettingsStore, name: String, roleId: String) {
        self.sharedSettings = sharedSettings
        detail = SubAgentRoleDetail.resolve(name: name, roleId: roleId)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hero
                        presetRolesSection
                        sourceSection
                        settingMapSection
                        toolsSection
                        routingSection
                        savedOverridesSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回 SubAgent", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text(detail.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                Text("@\(detail.roleId) · 字段映射")
                    .font(.system(size: 11.5, design: .monospaced))
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

    private var hero: some View {
        VStack(spacing: 8) {
            Text(detail.initials)
                .font(.system(size: 20, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 52, height: 52)
                .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 15, style: .continuous))

            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text(detail.name)
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("@\(detail.roleId)")
                    .font(.system(size: 12, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted2)
            }

            Text(detail.description)
                .font(.caption)
                .lineSpacing(3)
                .foregroundStyle(AmberTheme.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, 6)
        .padding(.bottom, 16)
    }

    /// Read-only list of the REAL KMP built-in subagent roles from
    /// `SubAgentDefinitions.shared.builtIns` (now exported via commonMain).
    /// Shows explorer/historian/oracle/designer/writer/fixer with real names +
    /// descriptions + tool counts.
    private var presetRolesSection: some View {
        let builtIns = SubAgentDefinitions.shared.builtIns
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "真实内置角色（KMP · 只读）")
            AmberFormGroup {
                ForEach(Array(builtIns.enumerated()), id: \.offset) { index, role in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text(role.name)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Spacer()
                            Text("\(role.toolAllowlist.count) 工具")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(AmberTheme.accentAmber)
                        }
                        Text(role.description_)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(3)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("id: \(role.id)")
                            .font(.system(size: 10, weight: .regular, design: .monospaced))
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)

                    if index < builtIns.count - 1 {
                        SubAgentRoleDivider()
                    }
                }
            }
        }
    }

    private var sourceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "来源")
            AmberFormGroup {
                SubAgentRoleStatusRow(
                    row: .init(
                        title: detail.isKnownBuiltIn ? "KMP SubAgentDefinitions" : "customDefinitions",
                        subtitle: detail.isKnownBuiltIn
                            ? "此角色来自 KMP 内置角色表；iOS 当前只展示源码证据，不读取用户 override。"
                            : "iOS 当前没有读取 Settings.agentRuntime.subAgent.customDefinitions。",
                        value: detail.isKnownBuiltIn ? "存在" : "未读取",
                        color: detail.isKnownBuiltIn ? AmberTheme.accentGreen : AmberTheme.accentAmber
                    )
                )
            }
        }
    }

    private var settingMapSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "可覆盖字段")
            AmberFormGroup {
                ForEach(Array(detail.settingRows.enumerated()), id: \.element.id) { index, row in
                    SubAgentRoleStatusRow(row: row)
                    if index < detail.settingRows.count - 1 {
                        SubAgentRoleDivider()
                    }
                }
            }

            SubAgentRoleFootnote(text: "Android/KMP 的角色页会写 SubAgentOverride 并同步 subagent markdown；iOS 当前没有 Settings.agentRuntime.subAgent bridge，因此不显示保存、恢复默认或删除操作。")
        }
    }

    private var toolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具边界")
            AmberFormGroup {
                Text(detail.toolSummary)
                    .font(.system(size: 12.5, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
            }

            SubAgentRoleFootnote(text: "真实运行时会从父工具集合里过滤 toolAllowlist，并禁止 subagent_* 嵌套调用；iOS 当前没有启动运行。")
        }
    }



    /// Saved subagent role overrides (UserDefaults persisted).
    private var savedOverridesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "角色覆盖（UserDefaults · 可读写）")
            AmberFormGroup {
                let overrides = sharedSettings.savedSubAgentOverrides.filter { $0["roleId"] == detail.roleId }
                ForEach(Array(overrides.enumerated()), id: \.offset) { index, override in
                    HStack(spacing: 10) {
                        Text(override["systemPrompt"] ?? "?").font(.caption).foregroundStyle(AmberTheme.muted)
                            .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                        Button { sharedSettings.removeSubAgentOverride(at: index) } label: {
                            Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(AmberTheme.accentRed)
                        }.buttonStyle(.plain)
                    }.frame(minHeight: 40).padding(.horizontal, 14).padding(.vertical, 4)
                }
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                Button {
                    sharedSettings.addSubAgentOverride(roleId: detail.roleId, systemPrompt: detail.description)
                } label: {
                    Label("保存角色覆盖", systemImage: "plus.circle.fill").font(.body.weight(.semibold)).foregroundStyle(AmberTheme.accent)
                }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.vertical, 8)
            }
        }
    }
    private var routingSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "何时调用")
            AmberFormGroup {
                Text(detail.routing)
                    .font(.caption)
                    .lineSpacing(4)
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
            }
        }
    }
}

private struct SubAgentRoleStatus: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct SubAgentRoleDetail {
    let roleId: String
    let name: String
    let description: String
    let toolSummary: String
    let routing: String
    let isKnownBuiltIn: Bool

    var initials: String {
        String(name.prefix(1))
    }

    var settingRows: [SubAgentRoleStatus] {
        [
            .init(
                title: "modelId",
                subtitle: "KMP 支持为角色覆盖聊天模型；iOS 当前没有 provider/model registry bridge。",
                value: "执行待接",
                color: AmberTheme.accentAmber
            ),
            .init(
                title: "reasoningLevel / temperature",
                subtitle: "KMP SubAgentOverride 可覆盖推理强度和采样温度；iOS 当前不会写入。",
                value: "执行待接",
                color: AmberTheme.accentAmber
            ),
            .init(
                title: "systemPrompt",
                subtitle: "KMP 支持角色提示词覆盖并写入 subagent markdown；iOS 当前不保存本地预览。",
                value: "执行待接",
                color: AmberTheme.accentAmber
            ),
            .init(
                title: "turns / timeout / outputBudget",
                subtitle: "KMP 可按角色覆盖最大轮数、超时和输出预算；iOS 当前不暴露保存。",
                value: "执行待接",
                color: AmberTheme.accentAmber
            )
        ]
    }

    static func resolve(name: String, roleId: String) -> SubAgentRoleDetail {
        if let builtIn = builtIns[roleId] {
            return builtIn
        }
        return SubAgentRoleDetail(
            roleId: roleId,
            name: name,
            description: "自定义子代理角色。iOS 当前没有从 Settings.agentRuntime.subAgent.customDefinitions 读取真实定义。",
            toolSummary: "customDefinitions 未桥接",
            routing: "自定义角色需要通过 KMP SubAgentValidator 校验边界、工具白名单和预算后才能保存；iOS 当前只展示未读取状态。",
            isKnownBuiltIn: false
        )
    }

    private static let builtIns: [String: SubAgentRoleDetail] = [
        "explorer": .init(
            roleId: "explorer",
            name: "Explorer",
            description: "跨多源快速并行侦察，回答「在哪 / 大概有什么」，速度优先。",
            toolSummary: "tools_list, search_web, scrape_web\nfile_list, file_read, file_search\nconversation_search, conversation_expand, session_search\nmcp_list, skills_list",
            routing: """
            何时调用：需要在多个来源快速并行侦察；范围广或不确定时；决策前要先摸清都有些什么。
            何时不要：你已经知道具体文件/路径只想读；一次性具体查找；即将立刻执行下一步。
            """,
            isKnownBuiltIn: true
        ),
        "historian": .init(
            roleId: "historian",
            name: "Historian",
            description: "历史会话搜索、主题挖掘、跨分片综合。",
            toolSummary: "tools_list, session_search, session_read, session_expand\nconversation_search, conversation_expand",
            routing: """
            何时调用：需要回忆过去对话/决策；跨多个会话挖某个主题；合并多个分片的会话摘要。
            何时不要：当前对话已经有答案；在当前会话里查单条消息。
            """,
            isKnownBuiltIn: true
        ),
        "oracle": .init(
            roleId: "oracle",
            name: "Oracle",
            description: "深度推理与评审：架构决策、bug 根因、方案 review、风险复议。",
            toolSummary: "tools_list, file_list, file_read, file_search\nconversation_search, conversation_expand, session_search\npermissions_status, apps_list, apps_installed_list",
            routing: """
            何时调用：长期影响大的决定；高风险重构；提交前二次复议；代码/架构 review；破坏性操作前评估风险。
            何时不要：日常普通选择；时间紧、足够好就行；你已经很有把握。
            """,
            isKnownBuiltIn: true
        ),
        "designer": .init(
            roleId: "designer",
            name: "Designer",
            description: "视觉产出专家：SVG、HTML PPT、HTML widget、VChart 的版式与视觉质量。",
            toolSummary: "tools_list, file_read, file_search\nconversation_search, conversation_expand",
            routing: """
            何时调用：要生成或评审视觉产物，且在意配色、版式、字体、信息密度。
            何时不要：随手草图；纯数据图表且不在意美感。
            """,
            isKnownBuiltIn: true
        ),
        "writer": .init(
            roleId: "writer",
            name: "Writer",
            description: "中文写作专家：公众号、小红书、邮件、短文、文学性改写、文案润色。",
            toolSummary: "tools_list, file_read, file_search\nconversation_search, conversation_expand",
            routing: """
            何时调用：中文写作、文案、故事、邮件、润色，且对质量有要求。
            何时不要：纯事实总结；通顺翻译；只要英文输出。
            """,
            isKnownBuiltIn: true
        ),
        "fixer": .init(
            roleId: "fixer",
            name: "Fixer",
            description: "便宜模型做边界清晰的执行：翻译、格式转换、抽取、命名。",
            toolSummary: "tools_list, file_read, file_search\nconversation_search, conversation_expand",
            routing: """
            何时调用：边界清晰的机械变换；批量翻译、格式化、抽取。
            何时不要：需要研究、决策、审美判断、强写作或中文文笔。
            """,
            isKnownBuiltIn: true
        )
    ]
}

private struct SubAgentRoleStatusRow: View {
    let row: SubAgentRoleStatus

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

private struct SubAgentRoleDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SubAgentRoleFootnote: View {
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
        SubAgentRoleView(sharedSettings: IOSSharedSettingsStore(), name: "Oracle", roleId: "oracle")
    }
}
