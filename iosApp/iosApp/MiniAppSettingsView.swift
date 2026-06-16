import SwiftUI

struct MiniAppSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    private let coreRows: [MiniAppCapabilityRow] = [
        .init(
            title: "enabled",
            subtitle: "Android/KMP gate for MiniApp prompt/output transformers, saved app list, runner, and sandbox permission checks.",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "network / externalImages / search / clipboard.copy",
            subtitle: "Android MiniAppSandbox gates Amber.fetch, image proxy, Amber.search, and clipboard writes against these global settings.",
            status: "KMP 存在",
            tint: AmberTheme.accent
        ),
        .init(
            title: "ai.generate / host.context / host.write",
            subtitle: "Android bridge can call current chat model, read bounded context, send draft text, and create artifacts after user confirmation.",
            status: "Android 存在",
            tint: .purple
        ),
        .init(
            title: "host.updateBoardSummary",
            subtitle: "Android bridge writes a MiniApp board summary through MiniAppRepository.updateBoardSummary().",
            status: "Android 存在",
            tint: .green
        )
    ]

    private let advancedRows: [MiniAppCapabilityRow] = [
        .init(
            title: "sharedStore / eventBus",
            subtitle: "Android persists shared KV data in mini_app_shared_data and keeps event subscriptions inside the runner lifecycle.",
            status: "Android 存在",
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
            subtitle: "iOS SettingsStore has no MiniAppSetting fields, no SettingsAggregator bridge, and no save path for these toggles.",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "MiniApp grants",
            subtitle: "iOS has no MiniAppGrantDAO or per-app permission decision store; this page cannot grant or deny declared permissions.",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "Runner consumption",
            subtitle: "iOS has no WebView runner or MiniAppSandbox, so setting fields would not be consumed even if local UI state existed.",
            status: "未接线",
            tint: AmberTheme.accentAmber
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
                        MiniAppCapabilityNote("本页不写 UserDefaults、SettingsStore、Keychain、数据库，也不会启用 MiniAppPromptTransformer、MiniAppOutputTransformer、MiniAppRepository 或 WebView runner。")
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
                Text("字段映射 · 不保存")
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
        Text("Android 设置页会直接写 Settings.agentRuntime.miniApp，并由生成 transformers、MiniAppSandbox 和 Runner WebView 消费。iOS 当前没有对应 Settings bridge、repository 或 runner，因此这里不显示开关、分组跳转或保存按钮。")
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
