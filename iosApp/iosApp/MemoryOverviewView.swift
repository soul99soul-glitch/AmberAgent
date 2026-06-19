import SwiftUI

struct MemoryOverviewView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    private let settingsRows: [MemoryCapabilityEvidence] = [
        .init(
            title: "核心 / 短期 / 长期记忆开关",
            subtitle: "聊天会按记忆范围选择可用条目。",
            value: "可用",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "最近会话参考 / 时间提醒",
            subtitle: "还没有作为独立设置开放。",
            value: "未开放",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "自动整理 / 上下文压缩",
            subtitle: "自动整理、候选审核和上下文压缩还没有开放。",
            value: "未开放",
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

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "新增记忆", size: 44, symbolSize: 20) {
                router.navigate(to: .memoryEdit(text: "", scope: "核心", pinned: false))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("把重要偏好、事实或上下文保存为记忆。聊天时会自动参考已启用范围内的记忆。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var presetConfigSection: some View {
        let rt = sharedSettings.agentRuntime
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆开关")
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

    private var configurationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "更多记忆选项")
            AmberFormGroup {
                ForEach(Array(settingsRows.enumerated()), id: \.element.id) { index, row in
                    MemoryStatusRow(row: row)
                    if index < settingsRows.count - 1 {
                        MemoryDivider()
                    }
                }
            }

            MemoryNote("暂未开放的选项不会显示假开关；开放后会变成可操作设置。")
        }
    }

    private var recordsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆库")
            AmberFormGroup {
                MemoryStatusRow(
                    row: .init(
                        title: "核心 / 短期 / 长期条目",
                        subtitle: "可以新增并在聊天中使用，重启后仍然保留。",
                        value: "可用",
                        color: AmberTheme.accentGreen
                    )
                )
                MemoryDivider()
                MemoryStatusRow(
                    row: .init(
                        title: "新增 / 编辑 / 删除",
                        subtitle: "当前支持新增和删除；编辑现有条目稍后开放。",
                        value: "部分可用",
                        color: AmberTheme.accentGreen
                    )
                )
            }

            MemoryNote("自动整理和候选审核暂未开放。")
        }
    }
}

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
