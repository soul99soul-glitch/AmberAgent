import SwiftUI
import WebKit

struct MiniAppRunnerView: View {
    @Environment(\.dismiss) private var dismiss

    let title: String
    @State private var previewUrl: String = "https://www.example.com"
    @State private var loadedUrl: String = ""
    // [MiniApp MVP] generated-HTML runner state.
    @State private var generatedHtml: String = MiniAppRunnerView.sampleHtml
    @State private var loadedHtml: String = ""
    @State private var runnerError: String?
    @State private var bridgeLog: [String] = []

    /// A self-contained sample that exercises the bridge (calls app.info + log)
    /// so the loop is demonstrable without external dependencies.
    static let sampleHtml = """
    <!DOCTYPE html><html><head><meta charset="utf-8"><style>body{font-family:-apple-system;padding:12px}button{font-size:16px;padding:8px 14px;margin:4px}</style></head>
    <body>
    <h3>iOS MiniApp MVP</h3>
    <div id="out">（点下方按钮调用 AmberNative bridge）</div>
    <p><button onclick="callInfo()">app.info</button>
    <button onclick="callLog()">log</button>
    <button onclick="callAi()">ai.generate (stub)</button></p>
    <script>
      AmberNative.onResponse = function(json){
        var r = typeof json === 'string' ? JSON.parse(json) : json;
        document.getElementById('out').textContent = JSON.stringify(r);
      };
      function callInfo(){ AmberNative.postMessage({method:'app.info'}); }
      function callLog(){ AmberNative.postMessage({method:'log', params:{message:'hello from MiniApp'}}); }
      function callAi(){ AmberNative.postMessage({method:'ai.generate', params:{prompt:'hi'}}); }
    </script>
    </body></html>
    """

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
                        miniAppRunnerSection
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

    /// [MiniApp MVP] Generated-HTML runner: validates the HTML (Android-parity
    /// security gate), loads it into a WKWebView with the AmberNative bridge
    /// injected, and shows the bridge message log. The sample HTML exercises
    /// app.info / log / ai.generate(stub) so the closed loop is demonstrable.
    private var miniAppRunnerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "MiniApp Runner（HTML 校验 + AmberNative bridge · MVP）")

            VStack(alignment: .leading, spacing: 6) {
                Text("生成的 HTML（校验后加载）")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                TextEditor(text: $generatedHtml)
                    .font(.system(size: 11, design: .monospaced))
                    .frame(height: 120)
                    .padding(6)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10))
                    .scrollContentBackground(.hidden)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            HStack(spacing: 8) {
                Button {
                    loadedHtml = generatedHtml
                    runnerError = nil
                } label: {
                    Label("加载并校验", systemImage: "play.circle.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(AmberTheme.accent, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if let runnerError {
                Text("⚠️ \(runnerError)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            if !loadedHtml.isEmpty {
                MiniAppRunnerWebView(
                    html: loadedHtml,
                    onValidationError: { runnerError = $0 },
                    onBridgeLog: { bridgeLog = $0 }
                )
                .frame(height: 240)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

                if !bridgeLog.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Bridge 日志（最近 \(min(bridgeLog.count, 8)) 条）")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                        ForEach(bridgeLog.suffix(8).indices, id: \.self) { i in
                            Text(bridgeLog.suffix(8)[i])
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(AmberTheme.muted2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                }
            }

            Text("HTML 经 MiniAppHtmlValidator 校验（与 Android 同规则：禁外链 script/危险 API）；AmberNative.postMessage → 原生 → onResponse 闭环。完整 bridge（ai/search/host/sensor/权限/沙箱）仍待开发。")
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
