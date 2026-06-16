import SwiftUI
import WebKit

struct MiniAppRunnerView: View {
    @Environment(\.dismiss) private var dismiss

    let title: String
    @State private var previewUrl: String = "https://www.example.com"
    @State private var loadedUrl: String = ""

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
            status: "执行待接",
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
            status: "执行待接",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "菜单操作",
            subtitle: "不提供打开、置顶、版本历史、导出、重命名或删除，因为没有 iOS MiniAppRepository 事务。",
            status: "执行待接",
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
                        webViewPreviewSection
                        evidenceSection
                        blockedSection
                        MiniAppCapabilityNote("iOS 已有 WKWebView 渲染能力（上方预览）。完整 MiniApp Runner（HTML 校验/bridge 注入/沙箱/权限）仍待开发。")
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
                Text("Runner 执行待接")
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
        Text("iOS 已有 WKWebView 渲染能力（本页可加载网页/HTML）。完整 MiniApp Runner（HTML 校验/bridge 注入/沙箱/权限）仍待开发。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    /// Real WKWebView preview — loads a URL entered by the user. Proves the
    /// WebView rendering chain for MiniApp works on iOS.
    private var webViewPreviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "WebView 预览（WKWebView · 真实）")

            HStack(spacing: 8) {
                TextField("https://", text: $previewUrl)
                    .font(.system(size: 14, design: .monospaced))
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10))
                    .autocorrectionDisabled()

                Button {
                    loadedUrl = previewUrl
                } label: {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(AmberTheme.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if !loadedUrl.isEmpty {
                SimpleWebView(urlString: loadedUrl)
                    .frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            Text("真实的 WKWebView。MiniApp 的 HTML 校验/bridge 注入/沙箱权限仍待开发。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
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
