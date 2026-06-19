import SwiftUI
import Shared

struct CouncilView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var runner = CouncilRunner()

    init(sharedSettings: IOSSharedSettingsStore) {
        self.sharedSettings = sharedSettings
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        presetConfigSection
                        rolePresetsSection
                        runnerSection
                        draftActionSection
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
        HStack(spacing: 10) {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer(minLength: 8)

            VStack(spacing: 2) {
                Text("模型议会")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("多模型协作评审")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 8)

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "成员设置", size: 44, symbolSize: 18) {
                router.navigate(to: .councilSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("模型议会会让多个席位从不同角度评审同一个问题。你可以手动启动一次，也可以管理自定义席位。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var presetConfigSection: some View {
        let c = sharedSettings.agentRuntime.modelCouncil
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "默认运行设置")
            AmberFormGroup {
                CouncilPresetKVRow(title: "启用议会", value: c.enabled ? "默认开" : "默认关")
                CouncilStatusDivider()
                CouncilPresetKVRow(title: "默认席位数", value: "\(c.defaultSeats)")
                CouncilStatusDivider()
                CouncilPresetKVRow(title: "最大席位数", value: "\(c.maxSeats)")
                CouncilStatusDivider()
                CouncilPresetKVRow(title: "默认轮数", value: "\(c.defaultRounds)")
                CouncilStatusDivider()
                CouncilPresetKVRow(title: "最大轮数", value: "\(c.maxRounds)")
                CouncilStatusDivider()
                CouncilPresetKVRow(title: "显示各席输出", value: c.showSeatOutputs ? "默认开" : "默认关")
            }
        }
    }

    private var rolePresetsSection: some View {
        let coreSeats = ModelCouncilRolePresets.shared.coreSeats
        let lensPresets = ModelCouncilRolePresets.shared.lensPresets
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "内置席位")

            AmberFormGroup {
                ForEach(Array(coreSeats.enumerated()), id: \.offset) { index, preset in
                    CouncilRolePresetRow(name: preset.name, id: preset.id, prompt: preset.prompt, isCore: true)
                    if index < coreSeats.count - 1 {
                        CouncilStatusDivider()
                    }
                }
            }

            if !lensPresets.isEmpty {
                AmberSectionLabel(text: "视角角色（Lens）")
                AmberFormGroup {
                    ForEach(Array(lensPresets.enumerated()), id: \.offset) { index, preset in
                        CouncilRolePresetRow(name: preset.name, id: preset.id, prompt: preset.prompt, isCore: false)
                        if index < lensPresets.count - 1 {
                            CouncilStatusDivider()
                        }
                    }
                }
            }

            Text("内置席位不可直接修改；需要自己的角色时，可在成员设置里添加自定义席位。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var runnerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "启动")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 8) {
                    if runner.isRunning {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在启动议会…").font(.body).foregroundStyle(AmberTheme.foreground)
                        }
                    } else {
                        Button { runner.runTestCycle() } label: {
                            Label("启动议会", systemImage: "bubble.left.and.bubble.right.fill")
                                .font(.body.weight(.semibold)).foregroundStyle(AmberTheme.accent)
                        }.buttonStyle(.plain)
                    }
                    if runner.lastRunResult != "(未运行)" {
                        Text(runner.lastRunResult).font(.footnote).foregroundStyle(AmberTheme.muted)
                            .lineSpacing(2).fixedSize(horizontal: false, vertical: true)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.vertical, 12)
            }
        }
    }

    private var draftActionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "配置入口")
            AmberFormGroup {
                Button {
                    router.navigate(to: .councilSettings)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "person.3.sequence")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 32, height: 32)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                        VStack(alignment: .leading, spacing: 2) {
                            Text("成员设置")
                                .font(.body.weight(.medium))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("添加或删除自定义席位，调整每个席位使用的模型。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

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
    }
}

private struct CouncilCapabilityEvidence: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct CouncilStatusRow: View {
    let row: CouncilCapabilityEvidence

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

private struct CouncilStatusDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct CouncilPresetKVRow: View {
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

private struct CouncilRolePresetRow: View {
    let name: String
    let id: String
    let prompt: String
    let isCore: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Spacer()
                Text(isCore ? "核心" : "视角")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(isCore ? AmberTheme.accentGreen : AmberTheme.accentAmber)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(
                        (isCore ? AmberTheme.accentGreen : AmberTheme.accentAmber).opacity(0.12),
                        in: RoundedRectangle(cornerRadius: 5, style: .continuous)
                    )
            }
            Text(prompt)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name)，\(isCore ? "核心" : "视角")角色")
    }
}

#Preview {
    NavigationStack {
        CouncilView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
