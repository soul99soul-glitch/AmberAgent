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
        Text("控制小应用运行时可申请的能力。桥接能力首次使用时会弹窗确认，选择会记住；也可在运行页手动改授权。")
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
            AmberSectionLabel(text: "小应用总开关")
            AmberFormGroup {
                MiniAppPresetToggleRow(
                    title: "启用小应用",
                    subtitle: "关闭后不再注入小应用生成指令，也不会保存或运行新的小应用。",
                    systemImage: "square.grid.2x2",
                    tint: AmberTheme.accent,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.enabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(enabled: enabled) } }
                    )
                )
            }

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
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "外链图片",
                    subtitle: "允许声明 externalImages 权限的小应用加载 HTTPS 图片；未声明或已拒绝时仍会阻止。",
                    systemImage: "photo.on.rectangle.angled",
                    tint: AmberTheme.accentCyan,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.externalImagesEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(externalImagesEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
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
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "宿主写回",
                    subtitle: "允许 host.sendToConversation / host.createArtifact 在前台确认后写入草稿或内容卡片。",
                    systemImage: "square.and.pencil",
                    tint: AmberTheme.accentIndigo,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.hostWriteEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(hostWriteEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "打开其他小应用",
                    subtitle: "允许声明 launch 权限的小应用按 appId 打开另一个已保存的小应用。",
                    systemImage: "square.grid.3x3.square",
                    tint: AmberTheme.accentIndigo,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.launchEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(launchEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "设备传感器",
                    subtitle: "允许声明 sensor 权限的小应用订阅加速度计或陀螺仪；iOS 不提供环境光传感器数据。",
                    systemImage: "gyroscope",
                    tint: AmberTheme.accentGreen,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.sensorEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(sensorEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "当前位置",
                    subtitle: "允许声明 location 权限的小应用在系统授权后读取一次当前位置。",
                    systemImage: "location",
                    tint: AmberTheme.accentCyan,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.locationEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(locationEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "读取剪贴板",
                    subtitle: "允许声明 clipboard.read 权限的小应用读取当前剪贴板文本。",
                    systemImage: "clipboard",
                    tint: AmberTheme.accentAmber,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.clipboardReadEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(clipboardReadEnabled: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "显示源码",
                    subtitle: "在小应用运行页显示源码和手动版本编辑入口。",
                    systemImage: "chevron.left.forwardslash.chevron.right",
                    tint: AmberTheme.accent,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.showSourceButton },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(showSourceButton: enabled) } }
                    )
                )
                MiniAppCapabilityDivider(leading: 54)
                MiniAppPresetToggleRow(
                    title: "WebView 调试",
                    subtitle: "允许 Safari Web Inspector 检查小应用，仅建议开发时开启。",
                    systemImage: "ladybug",
                    tint: AmberTheme.accentRed,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.webViewDebugEnabled },
                        set: { enabled in sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(webViewDebugEnabled: enabled) } }
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
        Toggle(isOn: $isOn) {
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
            }
        }
        .toggleStyle(.switch)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint(subtitle)
        .frame(minHeight: 54)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}
