import SwiftUI

struct CouncilSettingsView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private let runtimeRows: [CouncilSettingsEvidence] = [
        .init(
            title: "enabled",
            subtitle: "Android/KMP 通过 Settings.agentRuntime.modelCouncil.enabled 控制 ChatService 是否注入 model_council_* 工具。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "synthesisModelId / showSeatOutputs",
            subtitle: "Android 设置页可选择综合模型并控制席位输出展示；iOS 当前没有设置桥。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
    ]

    private let rolePresetRows: [CouncilSettingsEvidence] = [
        .init(
            title: "核心席位",
            subtitle: "ModelCouncilRolePresets.coreSeats 包含支持者、反对者、裁判，运行时会自动注入。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "领域视角",
            subtitle: "ModelCouncilRolePresets.lensPresets 包含产品、营销、公关、工程、用户体验、风险等可选视角。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "默认席位列表",
            subtitle: "Android/KMP 存在 defaultSeats；iOS 当前不会读取、保存或删除默认席位。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
    ]

    private let limitRows: [CouncilSettingsEvidence] = [
        .init(
            title: "maxSeats / rounds",
            subtitle: "Android/KMP 默认最多 8 席、默认 2 轮、最大 5 轮；iOS 当前不保存这些值。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "timeout / outputBudget",
            subtitle: "Android/KMP 有 3 分钟默认席位超时和 12k 默认输出预算，以及扩展档位。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
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
                        rolePresetSection
                        seatDraftSection
                        limitsSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("模型议会")
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
        Text("Android/KMP 已有真实 ModelCouncilRuntimeSetting、默认席位、角色预设、综合模型、轮数、超时和输出预算设置；iOS 当前没有 Settings.agentRuntime.modelCouncil 桥。本页只展示字段映射和草稿入口，不保存配置。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var masterSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "运行状态")
            AmberFormGroup {
                CouncilSettingsStatusRow(
                    row: .init(
                        title: "iOS 模型议会",
                        subtitle: "ChatViewModel 当前不会注入 model_council_* 工具，也不会响应 @council。",
                        value: "未接线",
                        color: AmberTheme.accentAmber
                    )
                )
            }
            CouncilFootnote(text: "Android/KMP 中议会席位只做纯文本生成，不继承工具、记忆或完整聊天记录；iOS 尚未接入该运行时。")
        }
    }

    private var runtimeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "设置字段")
            AmberFormGroup {
                ForEach(Array(runtimeRows.enumerated()), id: \.element.id) { index, row in
                    CouncilSettingsStatusRow(row: row)
                    if index < runtimeRows.count - 1 {
                        CouncilSettingsDivider()
                    }
                }
            }
        }
    }

    private var rolePresetSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "角色预设")
            AmberFormGroup {
                ForEach(Array(rolePresetRows.enumerated()), id: \.element.id) { index, row in
                    CouncilSettingsStatusRow(row: row)
                    if index < rolePresetRows.count - 1 {
                        CouncilSettingsDivider()
                    }
                }
            }
            CouncilFootnote(text: "这些预设是真实 Android/KMP 数据模型证据；iOS 当前不会把预设选择写入 defaultSeats。")
        }
    }

    private var seatDraftSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "席位草稿")
            AmberFormGroup {
                Button {
                    router.navigate(to: .seatEditor)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "plus")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 30, height: 30)

                        Text("添加席位")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        Text("草稿")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(minHeight: 56)
                    .padding(.horizontal, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            CouncilFootnote(text: "席位编辑页只保留字段预览；不会新增、更新、删除 defaultSeats，也不会写入 AgentPromptConfigRepository 的 council prompt 文件。")
        }
    }

    private var limitsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "限制")
            AmberFormGroup {
                ForEach(Array(limitRows.enumerated()), id: \.element.id) { index, row in
                    CouncilSettingsStatusRow(row: row)
                    if index < limitRows.count - 1 {
                        CouncilSettingsDivider()
                    }
                }
            }
        }
    }
}

private struct CouncilSettingsEvidence: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct CouncilSettingsStatusRow: View {
    let row: CouncilSettingsEvidence

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

private struct CouncilSettingsDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct CouncilFootnote: View {
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
        CouncilSettingsView()
            .environment(RouterPath())
    }
}
