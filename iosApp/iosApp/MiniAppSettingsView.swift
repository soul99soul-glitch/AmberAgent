import SwiftUI

struct MiniAppSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        presetConfigSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回小应用", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("小应用设置")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("权限与宿主能力")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("控制小应用运行时可申请的能力。每个小应用仍需要在运行页单独允许或拒绝声明的权限。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var presetConfigSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "小应用权限")
            AmberFormGroup {
                MiniAppPresetToggleRow(
                    title: "网络 fetch",
                    subtitle: "允许已授权小应用请求公开 https 页面；本地、私网和带凭证 URL 会被拒绝。",
                    systemImage: "network",
                    tint: AmberTheme.accentGreen,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.networkEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(networkEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "搜索",
                    subtitle: "允许已授权小应用通过内置搜索执行公开检索。",
                    systemImage: "magnifyingglass",
                    tint: AmberTheme.accentCyan,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.searchEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(searchEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "AI 生成",
                    subtitle: "允许已授权小应用调用宿主 AI 生成文本；缺少 API Key 时会返回错误。",
                    systemImage: "sparkles",
                    tint: AmberTheme.accentAmber,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.aiEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(aiEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "复制到剪贴板",
                    subtitle: "允许已授权小应用写入剪贴板。",
                    systemImage: "doc.on.clipboard",
                    tint: AmberTheme.accent,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.clipboardCopyEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(clipboardCopyEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "SharedStore",
                    subtitle: "允许已授权小应用读写自己的共享存储命名空间。",
                    systemImage: "tray.full",
                    tint: AmberTheme.accentIndigo,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.sharedStoreEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(sharedStoreEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "EventBus",
                    subtitle: "允许已授权小应用在自身命名空间内订阅和发布事件。",
                    systemImage: "dot.radiowaves.left.and.right",
                    tint: AmberTheme.accentGreen,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.eventBusEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(eventBusEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "深度阅读摘要",
                    subtitle: "允许已授权小应用更新自己的深度阅读摘要。",
                    systemImage: "book.pages",
                    tint: AmberTheme.accentAmber,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.boardSummaryUpdateEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(boardSummaryUpdateEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "读取宿主上下文",
                    subtitle: "允许 host.context 在前台确认后返回最小化上下文。",
                    systemImage: "text.bubble",
                    tint: AmberTheme.accentCyan,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.hostContextEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(hostContextEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "宿主写回",
                    subtitle: "允许 host.sendToConversation / host.createArtifact 在前台确认后写入草稿或内容卡片。",
                    systemImage: "square.and.pencil",
                    tint: .purple,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.hostWriteEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(hostWriteEnabled: enabled) } }
                    )
                )
            }
        }
    }

}

#Preview {
    NavigationStack {
        MiniAppSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}

private struct MiniAppPresetToggleRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let tint: Color
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .toggleStyle(.switch)
        }
        .frame(minHeight: 54)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}
