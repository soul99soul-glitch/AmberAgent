import SwiftUI

struct SeatEditorView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var name = "工程"
    @State private var role = "工程"
    @State private var runner: SeatRunnerType = .provider
    @State private var model = "kimi-k2"
    @State private var reasoning = "高"
    @State private var cliTool = "Codex"
    @State private var cliModel = ""
    @State private var prompt = ""
    @State private var showRemoveInfo = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        noticeSection
                        identitySection
                        runnerSection
                        promptSection
                        previewSection
                        deleteSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert("移除尚未接线", isPresented: $showRemoveInfo) {
            Button("知道了", role: .cancel) { }
        } message: {
            Text("当前 iOS 页面没有读取真实 defaultSeats，因此不会删除任何模型议会席位。")
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回模型议会", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("席位草稿")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button("关闭") {
                dismiss()
            }
            .font(.system(size: 14.5, weight: .semibold))
            .foregroundStyle(AmberTheme.foreground2)
            .frame(height: 30)
            .padding(.horizontal, 13)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var noticeSection: some View {
        SeatEditorFootnote(text: "Android/KMP 通过 ModelCouncilRuntimeSetting.defaultSeats 和 AgentPromptConfigRepository 保存席位与 prompt；iOS 当前没有这条设置/文件桥。本页只保留草稿预览，关闭后不会保存。")
            .padding(.bottom, 10)
    }

    private var identitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "席位草稿")
            AmberFormGroup {
                HStack(spacing: 12) {
                    Text("名称")
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(width: 68, alignment: .leading)

                    TextField("未命名席位", text: $name)
                        .font(.system(size: 15))
                        .foregroundStyle(AmberTheme.foreground)
                        .multilineTextAlignment(.trailing)
                        .textFieldStyle(.plain)
                }
                .frame(minHeight: 56)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)

                SeatEditorDivider()

                Menu {
                    ForEach(["产品", "营销", "公关", "工程", "用户体验", "风险"], id: \.self) { option in
                        Button(option) {
                            role = option
                            if name.isEmpty || ["产品", "营销", "公关", "工程", "用户体验", "风险"].contains(name) {
                                name = option
                            }
                        }
                    }
                } label: {
                    SeatEditorRow(
                        systemImage: nil,
                        title: "角色预设",
                        subtitle: "决定这位席位的评审立场",
                        trailing: role,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }
            SeatEditorFootnote(text: "这些字段只影响本页草稿；不会写入 Android/KMP 的 ModelCouncilSeat。")
        }
    }

    private var runnerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "运行方式草稿")

            HStack(spacing: 4) {
                ForEach(SeatRunnerType.allCases) { type in
                    Button {
                        runner = type
                    } label: {
                        Text(type.title)
                            .font(.system(size: 14, weight: runner == type ? .semibold : .regular))
                            .foregroundStyle(runner == type ? AmberTheme.accent : AmberTheme.muted)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(runner == type ? AmberTheme.background : Color.clear, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3)
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if runner == .provider {
                providerRunnerGroup
            } else {
                cliRunnerGroup
            }

            SeatEditorFootnote(text: "Android/KMP 支持 provider_model 和 external_cli；iOS 当前不会保存 runnerType、modelId、externalTool 或 reasoningLevel。")
        }
    }

    private var providerRunnerGroup: some View {
        AmberFormGroup {
            Menu {
                ForEach(["kimi-k2", "codex", "gemini-3-pro", "glm-4.6", "claude-haiku-4.5"], id: \.self) { option in
                    Button(option) { model = option }
                }
            } label: {
                SeatEditorRow(
                    systemImage: "cpu",
                    iconColor: AmberTheme.accentIndigo,
                    title: "模型",
                    trailing: model,
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)

            SeatEditorDivider()

            Menu {
                ForEach(["默认", "低", "中", "高"], id: \.self) { option in
                    Button(option) { reasoning = option }
                }
            } label: {
                SeatEditorRow(
                    systemImage: "brain.head.profile",
                    iconColor: AmberTheme.muted,
                    title: "思考档位",
                    subtitle: "推理强度，越高越慢",
                    trailing: reasoning,
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)
        }
    }

    private var cliRunnerGroup: some View {
        AmberFormGroup {
            Menu {
                ForEach(["Codex", "Claude Code", "Gemini CLI"], id: \.self) { option in
                    Button(option) { cliTool = option }
                }
            } label: {
                SeatEditorRow(
                    systemImage: "terminal",
                    iconColor: AmberTheme.muted,
                    title: "CLI 工具",
                    trailing: cliTool,
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)

            SeatEditorDivider()

            HStack(spacing: 12) {
                Text("模型参数")
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(width: 88, alignment: .leading)

                TextField("默认（可选）", text: $cliModel)
                    .font(.system(size: 15))
                    .foregroundStyle(AmberTheme.foreground)
                    .multilineTextAlignment(.trailing)
                    .textFieldStyle(.plain)
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
        }
    }

    private var promptSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "席位提示词草稿")
            AmberFormGroup {
                TextEditor(text: $prompt)
                    .font(.system(size: 14))
                    .foregroundStyle(AmberTheme.foreground)
                    .scrollContentBackground(.hidden)
                    .background(Color.clear)
                    .frame(minHeight: 104)
                    .overlay(alignment: .topLeading) {
                        if prompt.isEmpty {
                            Text("留空则使用角色预设的默认提示词…")
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
            SeatEditorFootnote(text: "填写内容只留在本页草稿；不会写入 AgentPromptConfigRepository 的 modelCouncilPromptFile。")
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "处理方式")
            AmberFormGroup {
                SeatEditorStatusLine(label: "保存", value: "未接线")
                SeatEditorDivider()
                SeatEditorStatusLine(label: "写入位置", value: "本页草稿")
                SeatEditorDivider()
                SeatEditorStatusLine(label: "真实后端", value: "defaultSeats 未桥接")
            }
        }
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                showRemoveInfo = true
            } label: {
                Text("移除席位尚未接线")
                    .font(.body)
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 54)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
    }
}

private struct SeatEditorStatusLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }
}

private enum SeatRunnerType: String, CaseIterable, Identifiable {
    case provider
    case cli

    var id: String { rawValue }

    var title: String {
        switch self {
        case .provider: "Provider 模型"
        case .cli: "外部 CLI"
        }
    }
}

private struct SeatEditorRow: View {
    let systemImage: String?
    var iconColor = AmberTheme.accent
    let title: String
    var subtitle: String?
    var trailing: String?
    var showsChevron = false

    var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let trailing {
                Text(trailing)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SeatEditorDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SeatEditorFootnote: View {
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
        SeatEditorView()
    }
}
