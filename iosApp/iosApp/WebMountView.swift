import SwiftUI
import WebKit

struct WebMountSiteRoute: Hashable, Identifiable {
    let name: String
    let host: String

    var id: String { host }
}

struct WebMountView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var previewUrl: String = "https://www.apple.com"
    @State private var loadedUrl: String = ""

    private let evidenceRows: [WebMountCapabilityRow] = [
        .init(
            title: "WebMountManager",
            subtitle: "Android owns global enabled/eval flags, station state flows, adapter registration, probe state, and the agent tool catalog.",
            status: "Android 存在",
            tint: AmberTheme.accent
        ),
        .init(
            title: "UserSiteRegistry",
            subtitle: "Android keeps the editable site list, seed restore, user-added sites, auth kind, cookie hints, and active native adapter ids.",
            status: "Android 存在",
            tint: .blue
        ),
        .init(
            title: "Cookie / OAuth login",
            subtitle: "Android uses WebMountCookieProvider, InlineLoginActivity, WebMountLoginController, WebMountOAuthClient, and encrypted token storage.",
            status: "Android 存在",
            tint: .purple
        ),
        .init(
            title: "WebView runtime",
            subtitle: "Android WebViewPool injects bridge.js, tracks sessions, and routes bridge calls through SessionHandle, JsBridge, and ProfileBridge.",
            status: "Android 存在",
            tint: .green
        ),
        .init(
            title: "Tool adapters",
            subtitle: "Android exposes primitive browser tools plus signed fetch/profile tools and site adapters such as Feishu Docs, GitHub, Bilibili, Zhihu, and Juejin.",
            status: "Android 存在",
            tint: .orange
        ),
        .init(
            title: "iOS WebMount bridge",
            subtitle: "iOS 已有 WKWebView 预览能力（本页可加载网页）。完整 WebMount（OAuth/cookie/JS bridge/agent tool）仍待开发。",
            status: "WKWebView 可用",
            tint: AmberTheme.accentGreen
        )
    ]

    private let handlingRows: [WebMountCapabilityRow] = [
        .init(
            title: "Agent 集成开关",
            subtitle: "不再显示本地启用、/webmount 安装或任意 JavaScript 开关；iOS 没有可消费这些开关的 agent tool catalog。",
            status: "执行待接",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "我的网站列表",
            subtitle: "不再展示飞书、X、知乎、B 站、Stack Overflow 等硬编码登录态或公开状态。真实列表应来自 UserSiteRegistry。",
            status: "执行待接",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "登录 / Cookie / OAuth",
            subtitle: "不打开网页登录、不捕获 cookie、不写 token store，也不清除任何真实登录态。",
            status: "禁用",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "测试连接 / 工具调用",
            subtitle: "不向站点发起网络请求，不调用 wm_open、wm_extract、wm_eval、signed fetch 或站点专用工具。",
            status: "禁用",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "添加 / 删除 / 恢复示例",
            subtitle: "不创建、删除或恢复站点配置，因为 iOS 没有 UserSiteRegistry 或 WebMount 持久化事务。",
            status: "执行待接",
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
                        webViewPreviewSection
                        evidenceSection
                        handlingSection
                        WebMountCapabilityNote("iOS 已有 WKWebView 预览（上方）。完整 WebMount（站点配置/OAuth/cookie/JS bridge/agent tool）仍需开发。")
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("WebMount")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("Android 已实现 · iOS WebMount 桥执行待接")
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
        Text("Android 的 WebMount 会把登录网站和公开站点注册为 agent 工具。iOS 已有 WKWebView 预览能力（本页可加载任意网页），完整 WebMount（OAuth/cookie/JS bridge/agent tool）仍待开发。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    /// Real WKWebView preview — lets the user load any URL. This is a real
    /// iOS-native WebView, not a mock. Proves the WKWebView rendering chain
    /// works; full WebMount (site registry/OAuth/agent tools) still pending.
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
                    Image(systemName: "arrow.forward.circle.fill")
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

            Text("真实的 WKWebView，可加载任意网页。完整 WebMount（站点注册/OAuth 登录/cookie 持久化/agent 工具桥）仍待开发。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "真实能力证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    WebMountCapabilityStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        WebMountCapabilityDivider()
                    }
                }
            }
        }
    }

    private var handlingSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                ForEach(Array(handlingRows.enumerated()), id: \.element.id) { index, row in
                    WebMountCapabilityStatusRow(row: row)
                    if index < handlingRows.count - 1 {
                        WebMountCapabilityDivider()
                    }
                }
            }
        }
    }
}

struct WebMountSiteView: View {
    @Environment(\.dismiss) private var dismiss

    let site: WebMountSiteRoute

    private let routeRows: [WebMountCapabilityRow] = [
        .init(
            title: "Route payload",
            subtitle: "This iOS route only carries display name and host. It does not carry a real UserSite id, adapter id, OAuth provider, cookie hints, or station state.",
            status: "执行待接",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "Login / sign out",
            subtitle: "No iOS InlineLoginActivity/WebMountLoginController equivalent exists, so this page cannot open login, capture cookies, or clear sessions.",
            status: "禁用",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "Probe / tools",
            subtitle: "No iOS WebMountManager or WebView bridge exists, so this page cannot probe the host or expose agent tools.",
            status: "禁用",
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
                        routeSection
                        WebMountCapabilityNote("This detail route is retained only so existing navigation types compile. The main WebMount page no longer exposes hardcoded site rows.")
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回 WebMount", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text(site.name)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text("站点详情执行待接")
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
        Text("\(site.host) 的 iOS 详情页没有真实站点配置、登录态、OAuth token、cookie 或 adapter state。为避免误导，本页不提供登录、测试连接、删除或工具调用。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var routeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                ForEach(Array(routeRows.enumerated()), id: \.element.id) { index, row in
                    WebMountCapabilityStatusRow(row: row)
                    if index < routeRows.count - 1 {
                        WebMountCapabilityDivider()
                    }
                }
            }
        }
    }
}

struct WebMountCapabilityRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let status: String
    let tint: Color
}

struct WebMountCapabilityStatusRow: View {
    let row: WebMountCapabilityRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(row.status)
                .font(.caption.weight(.semibold))
                .foregroundStyle(row.tint)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

struct WebMountCapabilityDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

struct WebMountCapabilityNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
    }
}

#Preview {
    NavigationStack {
        WebMountView()
    }
}

#Preview("WebMount Site") {
    NavigationStack {
        WebMountSiteView(site: .init(name: "Example", host: "example.com"))
    }
}

/// Minimal WKWebView wrapper for SwiftUI. Loads a URL when created.
struct SimpleWebView: UIViewRepresentable {
    let urlString: String

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.navigationDelegate = context.coordinator
        if let url = URL(string: urlString) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if let url = URL(string: urlString),
           webView.url?.absoluteString != url.absoluteString {
            webView.load(URLRequest(url: url))
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, WKNavigationDelegate {}
}
