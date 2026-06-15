import SwiftUI

struct SubAgentRoleView: View {
    @Environment(\.dismiss) private var dismiss

    private let detail: SubAgentRoleDetail

    @State private var selectedModel: String
    @State private var reasoning: String
    @State private var prompt: String

    init(name: String, roleId: String) {
        let resolved = SubAgentRoleDetail.resolve(name: name, roleId: roleId)
        detail = resolved
        _selectedModel = State(initialValue: resolved.model)
        _reasoning = State(initialValue: resolved.reasoning)
        _prompt = State(initialValue: "")
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hero
                        modelSection
                        promptSection
                        routingSection
                        actionSection
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

            Text(detail.name)
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

    private var modelSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模型与推理")
            AmberFormGroup {
                Menu {
                    ForEach(SubAgentRoleDetail.modelOptions, id: \.self) { option in
                        Button(option) { selectedModel = option }
                    }
                } label: {
                    SubAgentRoleOptionRow(
                        systemImage: "cpu",
                        iconColor: AmberTheme.accentIndigo,
                        title: "模型",
                        subtitle: "该角色专用，可跟随主助手",
                        value: selectedModel
                    )
                }
                .buttonStyle(.plain)

                SubAgentRoleDivider()

                Menu {
                    ForEach(SubAgentRoleDetail.reasoningOptions, id: \.self) { option in
                        Button(option) { reasoning = option }
                    }
                } label: {
                    SubAgentRoleOptionRow(
                        systemImage: "brain.head.profile",
                        iconColor: AmberTheme.muted,
                        title: "推理强度",
                        subtitle: nil,
                        value: reasoning
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var promptSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "系统提示词")
            AmberFormGroup {
                TextEditor(text: $prompt)
                    .font(.system(size: 14))
                    .foregroundStyle(AmberTheme.foreground)
                    .scrollContentBackground(.hidden)
                    .background(Color.clear)
                    .frame(minHeight: 112)
                    .overlay(alignment: .topLeading) {
                        if prompt.isEmpty {
                            Text("留空则使用该角色的默认提示词；在此输入可覆盖…")
                                .font(.system(size: 14))
                                .foregroundStyle(AmberTheme.muted2)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 8)
                                .allowsHitTesting(false)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
            }
            SubAgentRoleFootnote(text: "覆盖该角色的默认提示词，清空即恢复默认。")
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

    private var actionSection: some View {
        VStack(spacing: 10) {
            if detail.isCustom {
                AmberFormGroup {
                    Button {
                        dismiss()
                    } label: {
                        Text("删除角色")
                            .font(.body)
                            .foregroundStyle(AmberTheme.accentRed)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 54)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            } else {
                AmberFormGroup {
                    Button {
                        resetDraft()
                    } label: {
                        Text("恢复默认")
                            .font(.body)
                            .foregroundStyle(AmberTheme.accent)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 54)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.top, 20)
    }

    private func resetDraft() {
        selectedModel = detail.model
        reasoning = detail.reasoning
        prompt = ""
    }
}

private struct SubAgentRoleDetail {
    let roleId: String
    let name: String
    let description: String
    let model: String
    let reasoning: String
    let routing: String
    let isCustom: Bool

    var initials: String {
        String(name.prefix(1))
    }

    static let modelOptions = [
        "跟随主助手",
        "GPT-5.2",
        "Gemini-3-pro",
        "GLM-4.6",
        "Kimi K2",
        "Claude Haiku 4.5"
    ]

    static let reasoningOptions = ["跟随", "低", "中", "高", "极高", "最高"]

    static func resolve(name: String, roleId: String) -> SubAgentRoleDetail {
        if let builtIn = builtIns[roleId] {
            return builtIn
        }
        if let custom = customRoles[roleId] {
            return custom
        }
        return SubAgentRoleDetail(
            roleId: roleId,
            name: name,
            description: "自定义子代理角色。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: "何时调用：由主 Agent 根据保存的角色说明判断。\n何时不要：边界不清、预算过大或需要人工确认时。",
            isCustom: true
        )
    }

    private static let builtIns: [String: SubAgentRoleDetail] = [
        "explorer": .init(
            roleId: "explorer",
            name: "Explorer",
            description: "跨多源快速并行侦察，回答「在哪 / 大概有什么」，速度优先。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: """
            何时调用：需要在多个来源快速并行侦察；范围广或不确定时；决策前要先摸清都有些什么。
            何时不要：你已经知道具体文件/路径只想读；一次性具体查找；即将立刻执行下一步。
            经验：「X 大概有些什么？」→ @explorer。「读这个具体文件」→ 自己干。
            """,
            isCustom: false
        ),
        "historian": .init(
            roleId: "historian",
            name: "Historian",
            description: "历史会话搜索 / 主题挖掘 / 跨分片综合。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: """
            何时调用：需要回忆过去对话/决策；跨多个会话挖某个主题；合并多个分片的会话摘要。
            何时不要：当前对话已经有答案；在当前会话里查单条消息。
            经验：「我们之前是不是聊过 X？」→ @historian。
            """,
            isCustom: false
        ),
        "oracle": .init(
            roleId: "oracle",
            name: "Oracle",
            description: "深度推理与评审：架构决策、bug 根因、方案 review、风险。",
            model: "GPT-5.2",
            reasoning: "高",
            routing: """
            何时调用：长期影响大的决定；同一问题改了 2+ 次还没好；高风险重构；提交前复议；代码/架构 review；destructive 操作前评估风险。
            何时不要：日常普通选择；时间紧、足够好就行；你已经很有把握；只读或常规操作。
            经验：「这是架构层判断」→ @oracle。「重写还是打补丁？」→ @oracle。「直接打补丁」→ 自己干。
            """,
            isCustom: false
        ),
        "designer": .init(
            roleId: "designer",
            name: "Designer",
            description: "视觉产出：SVG / HTML PPT / widget 的版式、配色、信息密度。",
            model: "Gemini-3-pro",
            reasoning: "跟随",
            routing: """
            何时调用：要生成 SVG/PPT/HTML 卡片且在意视觉质量；需要配色/版式规格；评审视觉产物。
            何时不要：随手草图；纯数据图表，不在意美感。
            经验：「用户会看且会评判」→ @designer。
            """,
            isCustom: false
        ),
        "writer": .init(
            roleId: "writer",
            name: "Writer",
            description: "中文写作：公众号 / 小红书 / 邮件 / 文学性改写与润色。",
            model: "GLM-4.6",
            reasoning: "跟随",
            routing: """
            何时调用：中文写作 / 文案 / 故事 / 公众号 / 邮件，且对质量有要求；给现有文字润色调气质节奏。
            何时不要：纯事实总结；通顺翻译；只要英文输出。
            经验：「写得打动人」→ @writer。「翻译这段公告」→ @fixer 或自己干。
            """,
            isCustom: false
        ),
        "fixer": .init(
            roleId: "fixer",
            name: "Fixer",
            description: "便宜模型做边界清晰的执行：翻译、格式转换、抽取、命名。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: """
            何时调用：边界清晰的机械变换；批量翻译 / 格式化 / 抽取；便宜模型显然能搞定。
            何时不要：需要研究 / 决策 / 审美判断 / 强写作 / 中文文笔。
            经验：「把这堆改成 Markdown」→ @fixer。「这段重写得更有调性」→ @writer。
            """,
            isCustom: false
        )
    ]

    private static let customRoles: [String: SubAgentRoleDetail] = [
        "release-notes": .init(
            roleId: "release-notes",
            name: "ReleaseNotes",
            description: "把 git 提交整理成面向用户的更新日志。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: "何时调用：要从提交记录生成可读的发布说明时。\n何时不要：需要判断该不该发布 → @oracle。",
            isCustom: true
        ),
        "meeting-notes": .init(
            roleId: "meeting-notes",
            name: "MeetingNotes",
            description: "会议转录 → 决议 / 待办 / 一句话纪要。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: "何时调用：把会议记录或转录整理成结构化纪要时。\n何时不要：要对会议内容做判断或决策 → @oracle。",
            isCustom: true
        ),
        "sql-explain": .init(
            roleId: "sql-explain",
            name: "SqlExplain",
            description: "逐句拆解复杂 SQL，标出代价与风险。",
            model: "跟随主助手",
            reasoning: "跟随",
            routing: "何时调用：看不懂或想 review 一段复杂 SQL 时。\n何时不要：要直接改库/跑迁移 → 自己干并先评估。",
            isCustom: true
        )
    ]
}

private struct SubAgentRoleOptionRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String?
    let value: String

    var body: some View {
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
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
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
        SubAgentRoleView(name: "Oracle", roleId: "oracle")
    }
}
