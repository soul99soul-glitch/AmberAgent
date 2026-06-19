import SwiftUI

struct MemoryOverviewView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    presetConfigSection
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
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆开关")
            AmberFormGroup {
                MemoryPresetRow(
                    title: "核心记忆",
                    subtitle: "长期偏好与重要事实。",
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.enableCoreMemory },
                        set: { sharedSettings.setMemoryRuntimeEnabled(core: $0) }
                    )
                )
                MemoryDivider()
                MemoryPresetRow(
                    title: "短期记忆",
                    subtitle: "近期项目上下文。",
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.enableShortTermMemory },
                        set: { sharedSettings.setMemoryRuntimeEnabled(shortTerm: $0) }
                    )
                )
                MemoryDivider()
                MemoryPresetRow(
                    title: "长期记忆",
                    subtitle: "跨会话保留的背景信息。",
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.enableLongTermMemory },
                        set: { sharedSettings.setMemoryRuntimeEnabled(longTerm: $0) }
                    )
                )
            }
        }
    }

}

private struct MemoryPresetRow: View {
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(AmberTheme.accent)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel(title)
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
