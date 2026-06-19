import SwiftUI

struct MiniAppSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    private let coreRows: [MiniAppCapabilityRow] = [
        .init(
            title: "enabled",
            subtitle: "iOS ChatViewModel、Runner 和 bridge policy 已读取 KMP 默认 MiniAppSetting；本页仍是只读展示。",
            status: "已消费",
            tint: AmberTheme.accentGreen
        ),
        .init(
            title: "network / externalImages / search / clipboard.copy",
            subtitle: "iOS bridge 会按 network/search/clipboard.copy 设置与 per-app grant 决策放行或返回诚实错误。",
            status: "Runner 消费",
            tint: AmberTheme.accent
        ),
        .init(
            title: "ai.generate / host.context / host.write",
            subtitle: "ai.generate 已接当前聊天模型调用，并检查 grant、设置和 API Key；host.context/send/createArtifact 仍因敏感确认链缺失返回错误。",
            status: "部分接入",
            tint: .purple
        ),
        .init(
            title: "host.updateBoardSummary",
            subtitle: "iOS bridge 已写入 MiniApp record 的 boardSummary metadata，并记录 audit。",
            status: "已接",
            tint: .green
        )
    ]

    private let advancedRows: [MiniAppCapabilityRow] = [
        .init(
            title: "sharedStore / eventBus",
            subtitle: "iOS sharedStore 写入 Documents repository；eventBus 仅在 Runner 生命周期内本地分发。",
            status: "已接",
            tint: AmberTheme.accent
        ),
        .init(
            title: "launch",
            subtitle: "Android resolves target apps through MiniAppRepository and navigates to MiniAppRunner(appId).",
            status: "Android 存在",
            tint: .blue
        ),
        .init(
            title: "sensor / location / clipboard.read",
            subtitle: "Android routes device-sensitive reads through MiniAppSystemBridge and per-call confirmation/permission checks.",
            status: "Android 存在",
            tint: .orange
        ),
        .init(
            title: "showSourceButton / webViewDebug",
            subtitle: "Android settings control source visibility and WebView debugging for the native runner.",
            status: "Android 存在",
            tint: .gray
        )
    ]

    private let persistenceRows: [MiniAppCapabilityRow] = [
        .init(
            title: "Settings.agentRuntime.miniApp",
            subtitle: "iOS 已读取 KMP seed/default snapshot；本页还没有编辑并保存这些开关的 UI。",
            status: "只读",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "MiniApp grants",
            subtitle: "per-app grant 已持久化到 iOS MiniApp repository；允许/拒绝入口在 Runner 页。",
            status: "已接",
            tint: AmberTheme.accentGreen
        ),
        .init(
            title: "Runner consumption",
            subtitle: "WKWebView Runner 已消费 appId、HTML validator、grant、sharedData、audit 和 bridge policy。",
            status: "已接",
            tint: AmberTheme.accentGreen
        )
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        presetConfigSection
                        coreSection
                        advancedSection
                        persistenceSection
                        MiniAppCapabilityNote("本页仍不写 MiniAppSetting 开关；真实小应用记录、grant、版本和 sharedData 请在小应用列表与 Runner 中管理。")
                            .padding(.top, 14)
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
                Text("字段映射 · Runner 消费")
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
        Text("Android 设置页会直接写 Settings.agentRuntime.miniApp。iOS 当前读取 KMP 默认值并由 MiniApp Runner/bridge 消费，但本页仍不提供保存这些全局开关的编辑入口。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    /// Read-only view of the REAL seeded MiniApp defaults from
    /// `IOSSharedSettingsStore.agentRuntime.miniApp`. Proves the read path;
    /// does NOT enable MiniApp generation/runner.
    private var presetConfigSection: some View {
        let m = sharedSettings.agentRuntime.miniApp
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "KMP 默认 MiniApp 配置（只读）")
            AmberFormGroup {
                MiniAppPresetKVRow(title: "启用 MiniApp", value: m.enabled ? "默认开" : "默认关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "AI 能力", value: m.aiEnabled ? "默认开" : "默认关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "网络访问", value: m.networkEnabled ? "默认开" : "默认关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "剪贴板读取", value: m.clipboardReadEnabled ? "默认开" : "默认关")
                MiniAppCapabilityDivider()
                MiniAppPresetKVRow(title: "启动", value: m.launchEnabled ? "默认开" : "默认关")
            }
        }
    }

    private var coreSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "MiniAppSetting 核心字段")
            AmberFormGroup {
                ForEach(Array(coreRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < coreRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }

    private var advancedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "高级与调试字段")
            AmberFormGroup {
                ForEach(Array(advancedRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < advancedRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }

    private var persistenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 持久化与消费")
            AmberFormGroup {
                ForEach(Array(persistenceRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < persistenceRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
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
