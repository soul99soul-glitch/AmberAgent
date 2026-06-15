import SwiftUI

struct WebMountSiteRoute: Hashable, Identifiable {
    let name: String
    let host: String
    let initial: String
    let colorHex: UInt32
    let status: WebMountStatus
    let subtitle: String
    let hint: String

    var id: String { host }
}

enum WebMountStatus: String, Hashable {
    case signed
    case off
    case publicAccess
    case limited
    case error

    var label: String {
        switch self {
        case .signed: "已登录"
        case .off: "未登录"
        case .publicAccess: "公开"
        case .limited: "限流中"
        case .error: "错误"
        }
    }

    var foreground: Color {
        switch self {
        case .signed: AmberTheme.accentGreen
        case .off: AmberTheme.muted
        case .publicAccess: AmberTheme.accentCyan
        case .limited: AmberTheme.accentAmber
        case .error: AmberTheme.accentRed
        }
    }

    var background: Color {
        switch self {
        case .signed: AmberTheme.accentGreen.opacity(0.12)
        case .off: AmberTheme.surface2
        case .publicAccess: AmberTheme.accentCyan.opacity(0.12)
        case .limited: AmberTheme.accentAmber.opacity(0.12)
        case .error: AmberTheme.accentRed.opacity(0.10)
        }
    }
}

struct WebMountView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var webMountEnabled = true
    @State private var slashCommandEnabled = true
    @State private var arbitraryJavaScript = false
    @State private var isAddAlertPresented = false

    private let sites: [WebMountSiteRoute] = [
        .init(
            name: "飞书文档",
            host: "feishu.cn",
            initial: "飞",
            colorHex: 0x3370FF,
            status: .signed,
            subtitle: "文档 / 多维表格 / 知识库",
            hint: "已登录的飞书账号，可读取云文档 / 多维表格 / 知识库内容。"
        ),
        .init(
            name: "X",
            host: "x.com",
            initial: "X",
            colorHex: 0x000000,
            status: .signed,
            subtitle: "时间线 / 发推 / 搜索",
            hint: "已登录的 X 账号，可读取时间线、发推、搜索。"
        ),
        .init(
            name: "知乎",
            host: "zhihu.com",
            initial: "知",
            colorHex: 0x0084FF,
            status: .off,
            subtitle: "问答 / 文章 / 想法",
            hint: "未登录。点击登录捕获 cookie 后，agent 才能访问你的知乎内容。"
        ),
        .init(
            name: "哔哩哔哩",
            host: "bilibili.com",
            initial: "哔",
            colorHex: 0xFB7299,
            status: .limited,
            subtitle: "视频 / 动态 / 收藏",
            hint: "该站点触发限流，agent 暂时无法访问。稍后会自动恢复。"
        ),
        .init(
            name: "Stack Overflow",
            host: "stackoverflow.com",
            initial: "S",
            colorHex: 0xF48024,
            status: .publicAccess,
            subtitle: "公开问答",
            hint: "公开站点，无需登录。agent 可直接读取问答内容。"
        ),
        .init(
            name: "Hacker News",
            host: "news.ycombinator.com",
            initial: "H",
            colorHex: 0xFF6600,
            status: .publicAccess,
            subtitle: "科技讨论",
            hint: "公开站点，无需登录。"
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
                        integrationSection
                        sitesSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert("添加网站", isPresented: $isAddAlertPresented) {
            Button("好", role: .cancel) {}
        } message: {
            Text("网页登录与 cookie 捕获稍后接入。")
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("WebMount")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("把网站变成 agent 工具。登录后挂载，agent 即可读取内容、调用该站点的专用能力。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var integrationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Agent 集成")
            AmberFormGroup {
                WebMountToggleRow(
                    systemImage: "globe",
                    iconColor: AmberTheme.accent,
                    title: "启用 WebMount",
                    subtitle: "所有 Assistant 获得 wm_open / wm_extract 与站点专用工具",
                    isOn: $webMountEnabled
                )

                WebMountDivider(leading: 58)

                WebMountToggleRow(
                    systemImage: "list.bullet",
                    iconColor: AmberTheme.muted,
                    title: "安装 /webmount 斜杠命令",
                    subtitle: nil,
                    isOn: $slashCommandEnabled
                )

                WebMountDivider(leading: 58)

                WebMountToggleRow(
                    systemImage: "exclamationmark.triangle",
                    iconColor: AmberTheme.accentRed,
                    title: "允许任意 JavaScript 执行",
                    subtitle: "高风险 · 在已登录页面执行脚本，可读 cookie。每次调用仍需确认",
                    isOn: $arbitraryJavaScript
                )
            }
        }
    }

    private var sitesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "我的网站")

            Text("登录后挂载，agent 自动获得该站点的专用工具。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 2)
                .padding(.bottom, 8)

            AmberFormGroup {
                ForEach(Array(sites.enumerated()), id: \.element.id) { index, site in
                    WebMountSiteRow(site: site) {
                        router.navigate(to: .webMountSite(site: site))
                    }

                    if index < sites.count - 1 {
                        WebMountDivider(leading: 58)
                    }
                }

                WebMountDivider(leading: 58)

                WebMountAddRow {
                    isAddAlertPresented = true
                }
            }

            Text("内置 10 个常用站点配置（飞书 / X / 知乎 / B 站 / 微博 / 豆瓣 / Stack Overflow / Hacker News / Medium / V2EX）。自定义网站点「添加」，或恢复示例。")
                .font(.caption)
                .lineSpacing(3)
                .foregroundStyle(AmberTheme.muted2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.top, 8)
        }
    }
}

struct WebMountSiteView: View {
    @Environment(\.dismiss) private var dismiss

    let site: WebMountSiteRoute

    @State private var alert: WebMountSiteAlert?
    @State private var isDeleteConfirmationPresented = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hero
                        statusSection
                        hint
                        deleteSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("好"))
            )
        }
        .confirmationDialog("删除网站", isPresented: $isDeleteConfirmationPresented, titleVisibility: .visible) {
            Button("删除网站", role: .destructive) {
                alert = .init(title: "删除网站", message: "本轮只验证界面，不删除真实 WebMount 配置。")
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("删除后 agent 将无法使用该站点工具。")
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回 WebMount", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text(site.name)
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.78)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var hero: some View {
        VStack(spacing: 0) {
            Text(site.initial)
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(Color(hex: site.colorHex), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(.bottom, 11)

            Text(site.name)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.82)

            Text(site.host)
                .font(.system(size: 12.5, weight: .regular, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, 6)
        .padding(.bottom, 16)
    }

    private var statusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "连接状态")
            AmberFormGroup {
                WebMountStatusRow(status: site.status)

                WebMountDivider()

                WebMountActionRow(title: signInTitle, subtitle: signInSubtitle) {
                    alert = signInAlert
                }

                if site.status.showsConnectionTest {
                    WebMountDivider()

                    WebMountActionRow(title: "测试连接", subtitle: "验证 agent 能否访问该站点") {
                        alert = .init(title: "测试连接", message: "本轮只验证界面，不向 \(site.host) 发起网络请求。")
                    }
                }
            }
        }
    }

    private var hint: some View {
        Text(site.hint)
            .font(.system(size: 13))
            .lineSpacing(4)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 13)
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                isDeleteConfirmationPresented = true
            } label: {
                Text("删除网站")
                    .font(.body)
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
    }

    private var signInTitle: String {
        switch site.status {
        case .signed: "退出登录"
        case .publicAccess: "无需登录"
        case .limited: "等待恢复"
        case .off, .error: "登录"
        }
    }

    private var signInSubtitle: String {
        switch site.status {
        case .signed: "清除已保存的 cookie"
        case .publicAccess: "公开站点，agent 可直接访问"
        case .limited: "站点限流中，稍后重试"
        case .off, .error: "打开网页登录后捕获 cookie"
        }
    }

    private var signInAlert: WebMountSiteAlert {
        switch site.status {
        case .signed:
            .init(title: "退出登录", message: "本轮只验证界面，不清除已保存的 cookie。")
        case .publicAccess:
            .init(title: "无需登录", message: "\(site.name) 是公开站点，agent 可直接访问。")
        case .limited:
            .init(title: "等待恢复", message: "该站点当前限流中，本轮不发起恢复请求。")
        case .off, .error:
            .init(title: "登录", message: "本轮只验证界面，不打开网页登录或捕获 cookie。")
        }
    }
}

private struct WebMountSiteAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

private struct WebMountStatusRow: View {
    let status: WebMountStatus

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("登录态")
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            WebMountStatusBadge(status: status)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct WebMountActionRow: View {
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct WebMountToggleRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String?
    @Binding var isOn: Bool

    var body: some View {
        Button {
            isOn.toggle()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
                    .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                WebMountSwitch(isOn: isOn)
            }
            .frame(minHeight: subtitle == nil ? 56 : 66)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "开启" : "关闭")
    }
}

private struct WebMountSiteRow: View {
    let site: WebMountSiteRoute
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(site.initial)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(Color(hex: site.colorHex), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(site.name)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                    Text("\(site.host) · \(site.subtitle)")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                WebMountStatusBadge(status: site.status)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct WebMountStatusBadge: View {
    let status: WebMountStatus

    var body: some View {
        Text(status.label)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(status.foreground)
            .lineLimit(1)
            .padding(.horizontal, 9)
            .padding(.vertical, 3)
            .background(status.background, in: Capsule())
    }
}

private struct WebMountAddRow: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                Text("添加网站")
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct WebMountSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private extension WebMountStatus {
    var showsConnectionTest: Bool {
        switch self {
        case .limited: false
        case .signed, .off, .publicAccess, .error: true
        }
    }
}

private struct WebMountDivider: View {
    var leading: CGFloat = 14

    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, leading)
    }
}

#Preview {
    NavigationStack {
        WebMountView()
    }
    .environment(RouterPath())
}

#Preview("WebMount Site") {
    NavigationStack {
        WebMountSiteView(
            site: .init(
                name: "飞书文档",
                host: "feishu.cn",
                initial: "飞",
                colorHex: 0x3370FF,
                status: .signed,
                subtitle: "文档 / 多维表格 / 知识库",
                hint: "已登录的飞书账号，可读取云文档 / 多维表格 / 知识库内容。"
            )
        )
    }
}
