import SwiftUI
import Shared

// MARK: - 自进化设置（Phase 2 Wave C；§13.4 / iosApp/AGENTS.md Settings 规则）
//
// 可见控件（自治级别三档 + 全局 kill switch）→ 持久化（UserDefaults keys
// `IOSEvolutionPreferenceKeys.*`）→ 运行时消费（policy engine 的
// `currentAutonomyLevel()` / `killSwitchEnabled()` 读同一 keys）。
// 三件套缺一不可；接线断言见 `IOSSettingsWiringTests`。

struct EvolutionSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSEvolutionPreferenceKeys.autonomyLevel)
    private var autonomyLevel = IOSEvolutionAutonomyLevel.t0T1Auto.rawValue
    @AppStorage(IOSEvolutionPreferenceKeys.killSwitch)
    private var killSwitch = false
    /// Phase 3 Wave 2: 经验管理模型（active 列表 + 建议批准/拒绝 + 归档折叠）。
    @State private var experiencesModel = IOSExperienceSettingsModel()

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    autonomySection
                    killSwitchSection
                    experiencesSection
                    footnoteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task { experiencesModel.reload() }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("自进化")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var autonomySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "自治级别")
            AmberFormGroup {
                ForEach(IOSEvolutionAutonomyLevel.allCases, id: \.rawValue) { level in
                    Button {
                        autonomyLevel = level.rawValue
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: isSelected(level) ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(isSelected(level) ? AmberTheme.accent : AmberTheme.muted2)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(level.title)
                                    .font(.body)
                                    .foregroundStyle(AmberTheme.foreground)
                                Text(level.subtitle)
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            if level == .t0T1Auto {
                                Text("默认")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(AmberTheme.accentGreen)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(AmberTheme.accentGreen.opacity(0.12), in: Capsule())
                            }
                        }
                        .frame(minHeight: 58)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(level.title)
                    .accessibilityValue(isSelected(level) ? "已选择" : "未选择")

                    if level != IOSEvolutionAutonomyLevel.allCases.last {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 14)
                    }
                }
            }

            EvolutionSettingsFooter(
                "T0：只读纯文本 Skill/Playbook delta 或全 pure step 的 Recipe；T1：仅 networkRead 或本地可逆副作用 step。T2（外部副作用 / 权限扩大 / 新 MCP 绑定）始终人工批准。"
            )
        }
    }

    private var killSwitchSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "全局开关")
            AmberFormGroup {
                EvolutionSettingsToggleRow(
                    systemImage: "bolt.slash",
                    title: "暂停自治发布",
                    subtitle: "打开后所有候选（含 T0/T1）都走人工批准；已有制品与回退不受影响",
                    isOn: killSwitch
                ) {
                    killSwitch.toggle()
                }
            }
        }
    }

    private var footnoteSection: some View {
        Text("自动发布只发生在评测硬门禁全部通过时：报告匹配候选哈希、建议 promote、0 条受保护样例回归、预算/冷却/熔断放行。每个自动发布都有回执、通知卡与一键回退。")
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }

    /// Phase 3 Wave 2: 经验管理区（active 条数 / 建议批准拒绝 / 归档折叠）。
    private var experiencesSection: some View {
        ExperiencesSettingsSection(model: experiencesModel)
    }

    private func isSelected(_ level: IOSEvolutionAutonomyLevel) -> Bool {
        level.rawValue == autonomyLevel
    }
}

private struct EvolutionSettingsFooter: View {
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

private struct EvolutionSettingsToggleRow: View {
    let systemImage: String
    let title: String
    var subtitle: String?
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                EvolutionSettingsSwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct EvolutionSettingsSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accentRed : AmberTheme.surface2)
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
