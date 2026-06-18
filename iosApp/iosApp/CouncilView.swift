import SwiftUI
import Shared

struct CouncilView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var runner = CouncilRunner()

    private let evidenceRows: [CouncilCapabilityEvidence] = [
        .init(
            title: "ModelCouncilRuntimeSetting",
            subtitle: "Android/KMP 通过 Settings.agentRuntime.modelCouncil 保存 enabled、席位、轮数、超时和输出预算。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "ModelCouncilManager",
            subtitle: "Android/KMP 可启动、读取、等待、取消议会 run，并记录 transcript 与 AgentTask 状态。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "model_council_* 工具",
            subtitle: "ChatService 在 modelCouncil.enabled 时注入 start/read/wait/cancel/report 工具。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "iOS 运行桥",
            // [Slice 1] 标记已升级为"已接"：CouncilRunner.swift:38(IosCouncilFactory.createWithRealProvider)
            // 在有 API key 时走真 OpenAI-compatible 推理；CouncilRunner.swift:60(startInput)+:62(m.start)
            // 构成完整 start 调用链。无 key 时回退 stub（仅验证调用链）。
            subtitle: "CouncilRunner 通过 IosCouncilFactory.createWithRealProvider 构造 ModelCouncilManager 并执行 start()。有 API key 时真推理；无 key 时 stub。",
            value: "已接",
            color: AmberTheme.accentGreen
        )
    ]

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
                        evidenceSection
                        iOSStatusSection
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

                Text("Android/KMP 已实现 · iOS 运行链已通（带 key 真推理；无 key stub）")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 8)

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "成员设置本地预览", size: 44, symbolSize: 18) {
                router.navigate(to: .councilSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("Android/KMP 已有真实模型议会运行时、工具族和设置页；iOS 通过下方\"执行（真实调用链）\"区可手动启动议会，且聊天里模型可调用 model_council_run 工具触发（Slice 3 已接：onComplete dispatch → CouncilRunner.run → resume，逻辑闭环；真链路需 API key）。席位增删已持久化。实时 liveText/transcript 快照来源仍待接。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    /// Read-only view of the REAL seeded ModelCouncil runtime defaults from
    /// `IOSSharedSettingsStore.agentRuntime.modelCouncil`. Proves the read path;
    /// does NOT enable council execution.
    private var presetConfigSection: some View {
        let c = sharedSettings.agentRuntime.modelCouncil
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "KMP 默认议会配置（只读）")
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

    /// Real KMP council role presets (coreSeats + lensPresets), read live from
    /// `ModelCouncilRolePresets.shared` (already exported, pure commonMain data).
    /// Shows the actual default council roles — supporters/opponents/judge + lens
    /// roles — instead of prose descriptions. Read-only; running a council still
    /// needs the ModelCouncilManager execution bridge (blocked on Android-only
    /// upstream modules — see audit).
    private var rolePresetsSection: some View {
        let coreSeats = ModelCouncilRolePresets.shared.coreSeats
        let lensPresets = ModelCouncilRolePresets.shared.lensPresets
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "真实议会角色预设（KMP · 只读）")

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

            Text("这些角色来自 Android/KMP ModelCouncilRolePresets 真实默认预设（只读展示）。真正运行议会（start/read）仍需 ModelCouncilManager 执行桥，iOS 当前不可执行。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    CouncilStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        CouncilStatusDivider()
                    }
                }
            }
        }
    }

    private var iOSStatusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                CouncilStatusRow(
                    row: .init(
                        // [Slice 3] 已接：ChatViewModel.makeTextGenerationParams 注入
                        // model_council_run 工具，onComplete dispatch → CouncilRunner.run
                        // → resume stream。逻辑闭环；真链路需 API key。
                        title: "从聊天触发议会",
                        subtitle: "已接：聊天里模型可调用 model_council_run 工具，经 CouncilRunner.run 触发议会并 resume（逻辑闭环；真链路需 API key）。手动按钮仍可用。",
                        value: "已接(逻辑)",
                        color: AmberTheme.accentGreen
                    )
                )
                CouncilStatusDivider()
                CouncilStatusRow(
                    row: .init(
                        title: "实时席位 / 转录",
                        subtitle: "手动 run 只能读 start() 返回的 runId/status；聊天里实时 liveTextFlow/transcriptPath 快照来源仍待接。",
                        value: "待接",
                        color: AmberTheme.accentAmber
                    )
                )
                CouncilStatusDivider()
                CouncilStatusRow(
                    row: .init(
                        title: "成员配置",
                        subtitle: "设置页和席位编辑页仅保留本地预览；不会写入默认席位或 prompt 文件。",
                        value: "本地预览",
                        color: AmberTheme.muted
                    )
                )
            }
        }
    }
    /// Council execution entry — calls CouncilRunner.runTestCycle() to start
    /// a council via IosCouncilFactory. Real provider if API key configured.
    private var runnerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "执行（真实调用链）")
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
            AmberSectionLabel(text: "预览入口")
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
                            Text("成员设置本地预览")
                                .font(.body.weight(.medium))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("查看 Android/KMP 字段映射；当前不会保存席位。")
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

/// Read-only key/value row for a real seeded ModelCouncil setting.
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

/// Read-only row for a real KMP council role preset (coreSeat or lens).
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
            Text("id: \(id)")
                .font(.system(size: 10, weight: .regular, design: .monospaced))
                .foregroundStyle(AmberTheme.muted2)
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
