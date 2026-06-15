import SwiftUI

struct CouncilView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

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
            subtitle: "当前 SwiftUI 没有 Settings.agentRuntime、ModelCouncilManager 或 model_council 工具执行桥。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
    ]

    init(settingsStore: SettingsStore? = nil) {
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        evidenceSection
                        iOSStatusSection
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

                Text("Android/KMP 已实现 · iOS 运行桥未接线")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 8)

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "成员设置草稿", size: 44, symbolSize: 18) {
                router.navigate(to: .councilSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("Android/KMP 已有真实模型议会运行时、工具族和设置页；iOS 当前没有把这些能力接入 SettingsStore、ChatViewModel 或本地工具执行器。本页只展示能力证据和未接线边界，不启动议会、不生成转录、不发送模型请求。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
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
                        title: "发起议会",
                        subtitle: "没有 iOS model_council_start 执行路径；聊天输入也不会触发议会 run。",
                        value: "未接线",
                        color: AmberTheme.accentAmber
                    )
                )
                CouncilStatusDivider()
                CouncilStatusRow(
                    row: .init(
                        title: "实时席位 / 转录",
                        subtitle: "没有 runId、liveTextFlow、transcriptPath 或 AgentTask 快照来源。",
                        value: "未接线",
                        color: AmberTheme.accentAmber
                    )
                )
                CouncilStatusDivider()
                CouncilStatusRow(
                    row: .init(
                        title: "成员配置",
                        subtitle: "设置页和席位编辑页仅保留草稿预览；不会写入默认席位或 prompt 文件。",
                        value: "草稿",
                        color: AmberTheme.muted
                    )
                )
            }
        }
    }

    private var draftActionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "草稿入口")
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
                            Text("成员设置草稿")
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

#Preview {
    NavigationStack {
        CouncilView()
            .environment(RouterPath())
    }
}
