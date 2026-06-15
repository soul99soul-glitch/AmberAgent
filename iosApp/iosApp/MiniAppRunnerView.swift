import SwiftUI

struct MiniAppRunnerView: View {
    @Environment(\.dismiss) private var dismiss

    let title: String

    private let evidenceRows: [MiniAppCapabilityRow] = [
        .init(
            title: "MiniAppRunnerPage(appId)",
            subtitle: "Android receives a real appId, loads MiniAppEntity from MiniAppRepository, marks run count, and handles missing/error states.",
            status: "Android 存在",
            tint: AmberTheme.accent
        ),
        .init(
            title: "MiniAppShell + miniapp_bridge.js",
            subtitle: "Android injects a session token and native bridge into validated HTML before loading it into WebView.",
            status: "Android 存在",
            tint: .blue
        ),
        .init(
            title: "MiniAppBridge",
            subtitle: "Android handles storage, toast, theme, network, search, AI, host writes, shared store, event bus, launch, location, sensor, and clipboard calls.",
            status: "Android 存在",
            tint: .purple
        ),
        .init(
            title: "iOS runner",
            subtitle: "SwiftUI route currently receives only a display title, not a persisted appId, and has no repository, WebView, bridge, sandbox, or grant store.",
            status: "未接线",
            tint: AmberTheme.accentAmber
        )
    ]

    private let blockedRows: [MiniAppCapabilityRow] = [
        .init(
            title: "HTML 渲染",
            subtitle: "不加载任何 generated HTML，也不创建 WebView 或注入 AmberNative bridge。",
            status: "禁用",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "桥接能力调用",
            subtitle: "Amber.fetch/search/ai/host/sharedStore/eventBus/location/sensor/clipboard 在 iOS 没有 MiniAppBridge executor。",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "菜单操作",
            subtitle: "不提供打开、置顶、版本历史、导出、重命名或删除，因为没有 iOS MiniAppRepository 事务。",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "本地示例",
            subtitle: "已移除 SwiftUI 番茄钟计时器，避免把本地 demo 当作真实 MiniApp runner。",
            status: "已移除",
            tint: .gray
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
                        evidenceSection
                        blockedSection
                        MiniAppCapabilityNote("启用真实 Runner 前，需要先定义 iOS MiniAppEntity 存储、HTML validator/output parser、Chat transformer、WebView shell、native bridge、permission grant、shared store、system bridge 和审计日志链路。")
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
                Text(title)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text("Runner 未接线")
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
        Text("这个 iOS 路由保留用于未来接入真实 MiniApp runner，但当前没有 appId、MiniAppRepository、WebView shell 或 native bridge。为避免伪造可运行小应用，本页只展示 Android/KMP 运行链路和 iOS 缺失项。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "真实 Runner 证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }

    private var blockedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                ForEach(Array(blockedRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < blockedRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        MiniAppRunnerView(title: "MiniApp")
    }
}
