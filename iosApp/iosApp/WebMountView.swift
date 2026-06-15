import SwiftUI

struct WebMountSiteRoute: Hashable, Identifiable {
    let name: String
    let host: String

    var id: String { host }
}

struct WebMountView: View {
    @Environment(\.dismiss) private var dismiss

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
            subtitle: "SwiftUI currently has no WebMountManager, site registry, cookie/OAuth store, login WebView, WebView pool, JS bridge, tool adapter, or permission gate.",
            status: "未接线",
            tint: AmberTheme.accentAmber
        )
    ]

    private let handlingRows: [WebMountCapabilityRow] = [
        .init(
            title: "Agent 集成开关",
            subtitle: "不再显示本地启用、/webmount 安装或任意 JavaScript 开关；iOS 没有可消费这些开关的 agent tool catalog。",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "我的网站列表",
            subtitle: "不再展示飞书、X、知乎、B 站、Stack Overflow 等硬编码登录态或公开状态。真实列表应来自 UserSiteRegistry。",
            status: "未接线",
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
                        evidenceSection
                        handlingSection
                        WebMountCapabilityNote("启用真实 WebMount 前，需要先定义 iOS 侧站点配置存储、cookie/OAuth 安全存储、登录 WebView、WebView session pool、bridge.js 等价层、ProfileBridge、tool executor、权限批准和站点 adapter。")
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
                Text("Android 已实现 · iOS WebMount 桥未接线")
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
        Text("Android 的 WebMount 会把登录网站和公开站点注册为 agent 工具：它维护站点配置、Cookie/OAuth、WebView session、bridge.js、浏览器原语、signed fetch 和站点专用 adapter。iOS 当前没有这些存储、登录、运行或工具桥，本页只展示能力证据，不展示假登录站点或假开关。")
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
            status: "未接线",
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
                Text("站点详情未接线")
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
