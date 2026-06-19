import SwiftUI

struct MemoryOverviewView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    private let memoryEvidence: [MemoryCapabilityEvidence] = [
        .init(
            title: "MemoryRepository",
            subtitle: "Android/KMP 通过 MemoryDAO、MemoryCandidateDAO、MemoryEventDAO 读写真正记忆库。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "记忆召回",
            subtitle: "MemoryRecallStore 按 enableCoreMemory / enableShortTermMemory / enableLongTermMemory 选择记录并构造提示词。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "memory_tool",
            subtitle: "ChatService 可创建、编辑、删除核心 / 短期 / 长期记忆。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "iOS 记忆桥",
            subtitle: "当前 SwiftUI 没有 MemoryRepository、Settings.agentRuntime 或 memory_tool executor bridge。",
            value: "缺桥",
            color: AmberTheme.accentAmber
        )
    ]

    private let settingsRows: [MemoryCapabilityEvidence] = [
        .init(
            title: "核心 / 短期 / 长期记忆开关",
            subtitle: "Android Settings.agentRuntime 已有真实开关；iOS 当前不读写这些字段。",
            value: "只读缺口",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "最近会话参考 / 时间提醒",
            subtitle: "Android Settings.agentRuntime 有 enableRecentChatsReference 和 enableTimeReminder。",
            value: "只读缺口",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "选择性召回 / 自动整理 / 上下文压缩",
            subtitle: "Android 有 memoryRecall、memoryWorker、contextCompaction；iOS 当前没有设置桥。",
            value: "缺设置桥",
            color: AmberTheme.accentAmber
        )
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    presetConfigSection
                    soulSection
                    evidenceSection
                    configurationSection
                    recordsSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("核心记忆")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "新增记忆本地预览", size: 44, symbolSize: 20) {
                router.navigate(to: .memoryEdit(text: "", scope: "核心", pinned: false))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("Android/KMP 已有真实记忆库、召回、自动整理和 memory_tool；iOS 当前还没有把这些能力接进 SettingsStore、ChatViewModel 或本地工具执行器。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    /// Read-only view of the REAL seeded memory settings from
    /// `IOSSharedSettingsStore.agentRuntime` (enableCoreMemory / short / long /
    /// recentChats / timeReminder). Proves the real-settings read path is wired
    /// for this module; does NOT enable memory execution/editing.
    private var presetConfigSection: some View {
        let rt = sharedSettings.agentRuntime
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "KMP 默认记忆配置（只读）")
            AmberFormGroup {
                MemoryPresetRow(title: "核心记忆", isOn: rt.enableCoreMemory)
                MemoryDivider()
                MemoryPresetRow(title: "短期记忆", isOn: rt.enableShortTermMemory)
                MemoryDivider()
                MemoryPresetRow(title: "长期记忆", isOn: rt.enableLongTermMemory)
                MemoryDivider()
                MemoryPresetRow(title: "最近会话参考", isOn: rt.enableRecentChatsReference)
                MemoryDivider()
                MemoryPresetRow(title: "时间提醒", isOn: rt.enableTimeReminder)
            }
        }
    }

    private var soulSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "agents.md")

            Button {
                router.navigate(to: .agentsMd)
            } label: {
                VStack(alignment: .leading, spacing: 11) {
                    HStack(spacing: 9) {
                        Image(systemName: "doc.text")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 30, height: 30)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                        Text("agents.md 本地预览")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)

                        Text("iOS 缺桥")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(AmberTheme.accentAmber)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(AmberTheme.accentAmber.opacity(0.13), in: RoundedRectangle(cornerRadius: 6, style: .continuous))

                        Spacer(minLength: 8)

                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }

                    Text("Android/KMP Settings.agentRuntime.agentSoulMarkdown 会通过 GenerationPrompts 注入；iOS 当前页面只保留预览入口，不会影响 ChatViewModel 请求。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                        .lineSpacing(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(11)
                        .background(AmberTheme.background, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                        }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            }
            .buttonStyle(.plain)
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
        }
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力证据")
            AmberFormGroup {
                ForEach(Array(memoryEvidence.enumerated()), id: \.element.id) { index, row in
                    MemoryStatusRow(row: row)
                    if index < memoryEvidence.count - 1 {
                        MemoryDivider()
                    }
                }
            }
        }
    }

    private var configurationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆配置")
            AmberFormGroup {
                ForEach(Array(settingsRows.enumerated()), id: \.element.id) { index, row in
                    MemoryStatusRow(row: row)
                    if index < settingsRows.count - 1 {
                        MemoryDivider()
                    }
                }
            }

            MemoryNote("这些行对应 Android/KMP 的真实设置字段；iOS 没有桥接前不会写入本地假开关。")
        }
    }

    private var recordsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆库")
            AmberFormGroup {
                MemoryStatusRow(
                    row: .init(
                        title: "核心 / 短期 / 长期条目",
                        subtitle: "Android/KMP 通过 MemoryRepository 读取；iOS 当前没有 DAO/repository bridge，因此不显示样例记忆条数。",
                        value: "缺 repository",
                        color: AmberTheme.accentAmber
                    )
                )
                MemoryDivider()
                MemoryStatusRow(
                    row: .init(
                        title: "新增 / 编辑 / 删除",
                        subtitle: "右上角 + 和详情页只保留本地预览；不会创建、更新或删除真实记忆。",
                        value: "本地预览",
                        color: AmberTheme.muted
                    )
                )
            }

            MemoryNote("Android/KMP 的记忆库能力真实存在；本页当前只记录 iOS bridge 缺口。")
        }
    }
}

/// Read-only toggle row for a real seeded memory setting. Display-only.
private struct MemoryPresetRow: View {
    let title: String
    let isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(isOn ? "默认开" : "默认关")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isOn ? AmberTheme.accentGreen : AmberTheme.muted2)
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel("\(title)\(isOn ? "，默认开" : "，默认关")")
    }
}

private struct MemoryCapabilityEvidence: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct MemoryStatusRow: View {
    let row: MemoryCapabilityEvidence

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
        .contentShape(Rectangle())
    }
}

private struct MemoryDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct MemoryNote: View {
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
