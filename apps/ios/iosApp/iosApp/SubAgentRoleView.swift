import SwiftUI
import Shared

struct SubAgentRoleView: View {
    let sharedSettings: IOSSharedSettingsStore
    @Environment(\.dismiss) private var dismiss

    private let detail: SubAgentRoleDetail
    @State private var promptDraft: String

    init(sharedSettings: IOSSharedSettingsStore, name: String, roleId: String) {
        self.sharedSettings = sharedSettings
        let resolved = SubAgentRoleDetail.resolve(name: name, roleId: roleId)
        detail = resolved
        _promptDraft = State(initialValue: resolved.description)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hero
                        routingSection
                        toolsSection
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

                Text("@\(detail.roleId) · 角色详情")
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

    private var toolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "可用范围")
            AmberFormGroup {
                Text(detail.toolSummary.isEmpty ? "无外部工具。该角色只能返回模型自身结果。" : detail.toolSummary)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
            }
            SubAgentRoleFootnote(text: "工具范围由 iOS 端只读安全 allowlist 限制；敏感动作仍走权限与批准策略。")
        }
    }



    private var savedOverridesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "角色提示词")
            AmberFormGroup {
                TextField("角色提示词", text: $promptDraft, axis: .vertical)
                    .font(.caption)
                    .lineLimit(3...8)
                    .textFieldStyle(.plain)
                    .foregroundStyle(AmberTheme.foreground)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)

                let overrides = Array(sharedSettings.savedSubAgentOverrides.enumerated())
                    .filter { $0.element["roleId"] == detail.roleId }
                ForEach(Array(overrides.enumerated()), id: \.offset) { index, override in
                    HStack(spacing: 10) {
                        Text(override.element["systemPrompt"] ?? "?").font(.caption).foregroundStyle(AmberTheme.muted)
                            .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                        Button { sharedSettings.removeSubAgentOverride(at: override.offset) } label: {
                            Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(AmberTheme.accentRed)
                        }.buttonStyle(.plain)
                    }.frame(minHeight: 40).padding(.horizontal, 14).padding(.vertical, 4)
                    if index < overrides.count - 1 {
                        SubAgentRoleDivider()
                    }
                }
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                Button {
                    let prompt = promptDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !prompt.isEmpty else { return }
                    sharedSettings.addSubAgentOverride(roleId: detail.roleId, systemPrompt: prompt)
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

    static func resolve(name: String, roleId: String) -> SubAgentRoleDetail {
        if let role = IOSSubAgentRoleCatalog.builtIns.first(where: { $0.id == roleId }) {
            return SubAgentRoleDetail(
                roleId: role.id,
                name: role.name,
                description: role.summary,
                toolSummary: role.toolAllowlist.joined(separator: "\n"),
                routing: role.routing,
                isKnownBuiltIn: true
            )
        }
        if let builtIn = builtIns[roleId] {
            return builtIn
        }
        return SubAgentRoleDetail(
            roleId: roleId,
            name: name,
            description: "自定义子代理角色。",
            toolSummary: "",
            routing: "自定义角色保存属于高级功能，当前不可用。",
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
