import SwiftUI
import Shared

struct CouncilSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private var mc: ModelCouncilRuntimeSetting { sharedSettings.agentRuntime.modelCouncil }

    private var runtimeRows: [CouncilSettingsEvidence] {
        [
            .init(
                title: "enabled",
                subtitle: "KMP Settings.agentRuntime.modelCouncil.enabled 控制 ChatService 是否注入 model_council_* 工具。",
                value: mc.enabled ? "启用" : "关闭",
                color: mc.enabled ? AmberTheme.accentGreen : AmberTheme.muted2
            ),
            .init(
                title: "showSeatOutputs",
                subtitle: "KMP modelCouncil.showSeatOutputs 控制席位输出是否展示。",
                value: mc.showSeatOutputs ? "显示" : "隐藏",
                color: AmberTheme.foreground2
            ),
        ]
    }

    private var rolePresetRows: [CouncilSettingsEvidence] {
        [
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
                subtitle: "KMP modelCouncil.defaultSeats 真实种子值，只读展示。",
                value: "\(mc.defaultSeats.count) 席",
                color: AmberTheme.foreground2
            ),
        ]
    }

    private var limitRows: [CouncilSettingsEvidence] {
        [
            .init(
                title: "maxSeats / rounds",
                subtitle: "KMP modelCouncil 真实种子值，只读展示。",
                value: "最多 \(mc.maxSeats) 席 · 默认 \(mc.defaultRounds) 轮 · 最多 \(mc.maxRounds) 轮",
                color: AmberTheme.foreground2
            ),
            .init(
                title: "timeout / outputBudget",
                subtitle: "KMP modelCouncil 真实种子值，只读展示。",
                value: "席位超时 \(mc.seatTimeoutMs / 1000)s · 输出预算 \(mc.outputBudgetChars) 字",
                color: AmberTheme.foreground2
            ),
        ]
    }

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
        Text("以下字段来自 KMP Settings.agentRuntime.modelCouncil 真实种子值（只读展示）。席位编辑保存到 UserDefaults，重启后保留。")
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
                        subtitle: "ChatViewModel 已注入 model_council_run；IosCouncilFactory 可构造 Manager 并验证调用链。",
                        value: "Chat 链路已接",
                        color: AmberTheme.accentAmber
                    )
                )
            }
            CouncilFootnote(text: "议会席位只做纯文本生成，不继承工具或完整聊天记录。配置 API Key 时可走真实 OpenAI-compatible provider；无 Key 时只使用诚实 stub 验证调用链。")
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
            AmberSectionLabel(text: "自定义席位（UserDefaults · 可读写）")
            AmberFormGroup {
                let seats = sharedSettings.savedCouncilSeats
                if seats.isEmpty {
                    Text("暂无自定义席位。点「添加席位」进入编辑页添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(seats.enumerated()), id: \.offset) { index, seat in
                        HStack(spacing: 10) {
                            Text(seat["name"] ?? "?").font(.body.weight(.semibold))
                            Spacer()
                            Text(seat["role"] ?? "?").font(.caption).foregroundStyle(AmberTheme.muted2)
                            Button { sharedSettings.removeCouncilSeat(at: index) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 44).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < seats.count - 1 { CouncilSettingsDivider() }
                    }
                }
            }
            Button {
                router.navigate(to: .seatEditor)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus").font(.system(size: 17, weight: .semibold)).foregroundStyle(AmberTheme.accent).frame(width: 30, height: 30)
                    Text("添加席位").font(.body.weight(.medium)).foregroundStyle(AmberTheme.accent).frame(maxWidth: .infinity, alignment: .leading)
                }.frame(minHeight: 56).padding(.horizontal, 14).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            CouncilFootnote(text: "自定义席位保存到 UserDefaults，重启后保留。KMP seed 席位不可编辑。")
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
        CouncilSettingsView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
