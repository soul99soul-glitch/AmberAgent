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
        Text("控制小应用能否读取当前上下文，以及是否允许它把内容写回聊天。敏感操作仍需要前台确认。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var presetConfigSection: some View {
        let m = sharedSettings.agentRuntime.miniApp
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "小应用权限")
            AmberFormGroup {
                MiniAppPresetKVRow(title: "启用 MiniApp", value: m.enabled ? "已开" : "已关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "AI 能力", value: m.aiEnabled ? "已开" : "已关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "网络访问", value: m.networkEnabled ? "已开" : "已关")
                MiniAppCapabilityDivider()
                MiniAppPresetToggleRow(
                    title: "读取宿主上下文",
                    subtitle: "允许 host.context 在前台确认后返回最小化上下文。",
                    systemImage: "text.bubble",
                    tint: AmberTheme.accentCyan,
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.miniApp.hostContextEnabled },
                        set: { sharedSettings.setMiniAppHostContextEnabled($0) }
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
                        set: { sharedSettings.setMiniAppHostWriteEnabled($0) }
                    )
                )
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "剪贴板读取", value: m.clipboardReadEnabled ? "已开" : "已关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "启动", value: m.launchEnabled ? "已开" : "已关")
            }
        }
    }

}

#Preview {
    NavigationStack {
        MiniAppSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}

/// Read-only key/value row for a real seeded MiniApp setting.
private struct MiniAppPresetKVRow: View {
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
