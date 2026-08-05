import SwiftUI
import UIKit
import UniformTypeIdentifiers
@preconcurrency import WebKit
@preconcurrency import Shared

private let widgetMinHeight: CGFloat = 96
private let widgetMinPartialRenderChars = 16
private let widgetStreamUpdateDelay: TimeInterval = 0.016

@MainActor
struct IOSGenerativeWidgetCard: View {
    let widget: IOSGenerativeWidget
    var generativeUiSetting: GenerativeUiSetting?
    var onAction: (String) -> Void = { _ in }

    @State private var sheet: IOSGenerativeWidgetSheetTarget?
    @State private var measuredHeight: CGFloat = widgetMinHeight
    @State private var svgDocument: IOSGenerativeWidgetSVGDocument?
    @State private var isExportingSVG = false
    @State private var svgExportError: IOSGenerativeWidgetSVGExportError?

    private var settings: IOSGenerativeWidgetSettings {
        IOSGenerativeWidgetSettings(generativeUiSetting)
    }

    private var sanitized: IOSSanitizedGenerativeWidget {
        IOSGenerativeWidgetSanitizer.sanitize(widget.widgetCode, setting: settings)
    }

    private var canOpenExpanded: Bool {
        sanitized.status == .ready &&
            (widget.complete || sanitized.html.count >= widgetMinPartialRenderChars)
    }

    private var svgExport: IOSGenerativeWidgetSVGExportArtifact? {
        IOSGenerativeWidgetSVGExport.artifact(widget: widget, sanitized: sanitized)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            content
            actionRow
            if !widget.complete, sanitized.status == .ready {
                Text("正在生成可视化...")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.accent)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 1)
        }
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .sheet(item: $sheet) { target in
            switch target {
            case .widget(let widget, let html, let settings):
                IOSGenerativeWidgetExpandedSheet(widget: widget, html: html, settings: settings)
            }
        }
        .fileExporter(
            isPresented: $isExportingSVG,
            document: svgDocument,
            contentType: IOSGenerativeWidgetSVGDocument.contentType,
            defaultFilename: svgDocument?.filename ?? "visualization.svg"
        ) { result in
            if case .failure(let error) = result {
                svgExportError = IOSGenerativeWidgetSVGExportError(message: error.localizedDescription)
            }
        }
        .alert(item: $svgExportError) { error in
            Alert(
                title: Text("无法导出 SVG"),
                message: Text(error.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    @ViewBuilder
    private var header: some View {
        if widget.title?.nilIfBlank != nil || svgExport != nil {
            HStack(alignment: .top, spacing: 8) {
                if let title = widget.title?.nilIfBlank {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .layoutPriority(-1)
                }
                Spacer(minLength: 0)
                if svgExport != nil {
                    Button(action: beginSVGExport) {
                        Text("保存")
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                            .foregroundStyle(AmberTheme.accentInk)
                            .padding(.horizontal, 12)
                            .frame(height: 28)
                            .background(AmberTheme.accent, in: Capsule())
                    }
                    .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.94, haptic: .lightImpact))
                    .contentShape(.interaction, Rectangle().inset(by: -8))
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(1)
                    .accessibilityLabel("保存 SVG 到文件")
                }
            }
        }
    }

    private func beginSVGExport() {
        guard let svgExport else {
            svgExportError = IOSGenerativeWidgetSVGExportError(message: "当前可视化没有可导出的 SVG 内容。")
            return
        }
        svgDocument = IOSGenerativeWidgetSVGDocument(svg: svgExport.svg, filename: svgExport.filename)
        isExportingSVG = true
    }

    @ViewBuilder
    private var content: some View {
        switch sanitized.status {
        case .ready:
            if widget.complete || sanitized.html.count >= widgetMinPartialRenderChars {
                IOSSafeGenerativeWidgetWebView(
                    html: sanitized.html,
                    minHeight: widgetMinHeight,
                    maxHeight: CGFloat(settings.maxWidgetHeight),
                    interactive: false,
                    fillContainer: false,
                    height: $measuredHeight,
                    onTap: {
                        guard canOpenExpanded else { return }
                        sheet = .widget(widget, sanitized.html, settings)
                    }
                )
                .frame(height: measuredHeight)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            } else {
                IOSGenerativeWidgetLoadingView()
            }
        case .empty:
            Text("可视化为空，已忽略。")
                .font(.caption)
                .foregroundStyle(.secondary)
        case .tooLarge:
            Text("可视化过大，已转为代码块。")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(String(widget.widgetCode.prefix(4_000)))
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
        case .unsafe:
            Text("可视化包含不安全内容，已转为代码块。")
                .font(.caption)
                .foregroundStyle(AmberTheme.accentRed)
            Text(String(widget.widgetCode.prefix(4_000)))
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    @ViewBuilder
    private var actionRow: some View {
        let isRich = widget.complete &&
            (widget.renderer == "vchart" ||
             widget.renderer == "slides" ||
             widget.renderer == IOSGuizangHtmlDeckValidator.renderer) &&
            widget.specJson != nil &&
            canOpenExpanded
        if isRich || (settings.enableActions && widget.complete && !widget.actions.isEmpty) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    actionButtons(isRich: isRich, stacked: false)
                }
                VStack(alignment: .leading, spacing: 6) {
                    actionButtons(isRich: isRich, stacked: true)
                }
            }
        }
    }

    @ViewBuilder
    private func actionButtons(isRich: Bool, stacked: Bool) -> some View {
        if isRich {
            Button {
                sheet = .widget(widget, sanitized.html, settings)
            } label: {
                Label(richButtonTitle, systemImage: "play.circle")
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: stacked ? .infinity : nil, minHeight: 44, alignment: .leading)
            .fixedSize(horizontal: !stacked, vertical: false)
        }
        ForEach(widget.actions.prefix(3)) { action in
            Button {
                onAction(action.toUserPrompt(widgetTitle: widget.title))
            } label: {
                Text(action.label)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: stacked ? .infinity : nil, minHeight: 44, alignment: .leading)
            .fixedSize(horizontal: !stacked, vertical: false)
        }
    }

    private var richButtonTitle: String {
        switch widget.renderer {
        case "slides":
            "打开演示"
        case IOSGuizangHtmlDeckValidator.renderer:
            "打开 Live 演示"
        default:
            "交互式图表"
        }
    }
}

struct IOSGenerativeWidgetSVGExportArtifact: Equatable {
    let svg: String
    let filename: String
}

enum IOSGenerativeWidgetSVGExport {
    static func artifact(
        widget: IOSGenerativeWidget,
        sanitized: IOSSanitizedGenerativeWidget
    ) -> IOSGenerativeWidgetSVGExportArtifact? {
        guard widget.complete,
              sanitized.status == .ready,
              widget.renderer != "slides",
              widget.renderer != IOSGuizangHtmlDeckValidator.renderer,
              let svg = extractSVG(from: sanitized.html) else {
            return nil
        }
        return IOSGenerativeWidgetSVGExportArtifact(svg: svg, filename: filename(for: widget.title))
    }

    static func extractSVG(from source: String) -> String? {
        guard let start = source.range(of: "<svg\\b", options: [.regularExpression, .caseInsensitive]),
              let end = source.range(of: "</svg\\s*>", options: [.regularExpression, .caseInsensitive], range: start.lowerBound..<source.endIndex) else {
            return nil
        }
        return String(source[start.lowerBound..<end.upperBound])
    }

    static func filename(for title: String?) -> String {
        let rawTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let titleWithoutControls = rawTitle.components(separatedBy: .controlCharacters)
            .joined()
        let forbidden = CharacterSet(charactersIn: "/:\\?%*|\"<>")
        let stem = titleWithoutControls.components(separatedBy: forbidden)
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = stem.isEmpty ? "visualization" : stem
        return normalized.lowercased().hasSuffix(".svg") ? normalized : "\(normalized).svg"
    }
}

struct IOSGenerativeWidgetSVGDocument: FileDocument {
    static let contentType = UTType(filenameExtension: "svg") ?? .xml
    static var readableContentTypes: [UTType] { [contentType] }
    static var writableContentTypes: [UTType] { [contentType] }

    let svg: String
    let filename: String

    init(svg: String, filename: String) {
        self.svg = svg
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        guard configuration.file.isRegularFile,
              let data = configuration.file.regularFileContents,
              let svg = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.svg = svg
        self.filename = "visualization.svg"
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(svg.utf8))
    }
}

private struct IOSGenerativeWidgetSVGExportError: Identifiable {
    let id = UUID()
    let message: String
}

@MainActor
struct WidgetCodePreviewButton: View {
    let code: String

    @State private var preview: IOSWidgetCodePreview?

    var body: some View {
        Button {
            preview = IOSWidgetCodePreview(code: code)
        } label: {
            Label("预览", systemImage: "eye")
                .labelStyle(.titleAndIcon)
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
        .sheet(item: $preview) { item in
            IOSWidgetCodePreviewSheet(code: item.code)
        }
    }
}

private struct IOSWidgetCodePreview: Identifiable {
    let id = UUID()
    let code: String
}

private enum IOSGenerativeWidgetSheetTarget: Identifiable {
    case widget(IOSGenerativeWidget, String, IOSGenerativeWidgetSettings)

    var id: String {
        switch self {
        case .widget(let widget, _, _):
            "widget-\(widget.id)"
        }
    }
}

@MainActor
private struct IOSGenerativeWidgetExpandedSheet: View {
    let widget: IOSGenerativeWidget
    let html: String
    let settings: IOSGenerativeWidgetSettings

    @Environment(\.dismiss) private var dismiss
    @State private var measuredHeight: CGFloat = 520

    var body: some View {
        NavigationStack {
            Group {
                if widget.renderer == IOSGuizangHtmlDeckValidator.renderer, let specJson = widget.specJson {
                    IOSFullHTMLDeckWebView(specJson: specJson)
                } else if shouldUseRichSandbox, let specJson = widget.specJson {
                    IOSRichSandboxWebView(
                        renderer: widget.renderer,
                        specJson: specJson,
                        maxHeight: CGFloat(max(settings.maxWidgetHeight, 520)),
                        fillContainer: widget.renderer == "slides"
                    )
                } else {
                    IOSSafeGenerativeWidgetWebView(
                        html: html,
                        minHeight: 240,
                        maxHeight: 1_600,
                        interactive: true,
                        fillContainer: true,
                        height: $measuredHeight
                    )
                }
            }
            .background(AmberTheme.background)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle(widget.title?.nilIfBlank ?? "可视化卡片")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    private var shouldUseRichSandbox: Bool {
        switch widget.renderer {
        case "slides":
            return true
        case "vchart":
            return settings.enableInteractiveCharts
        default:
            return false
        }
    }
}

@MainActor
private struct IOSWidgetCodePreviewSheet: View {
    let code: String

    @Environment(\.dismiss) private var dismiss
    @State private var measuredHeight: CGFloat = 520

    private var sanitized: IOSSanitizedGenerativeWidget {
        IOSGenerativeWidgetSanitizer.sanitize(code)
    }

    var body: some View {
        NavigationStack {
            Group {
                if sanitized.status == .ready {
                    IOSSafeGenerativeWidgetWebView(
                        html: sanitized.html,
                        minHeight: 240,
                        maxHeight: 1_600,
                        interactive: true,
                        fillContainer: true,
                        height: $measuredHeight
                    )
                } else {
                    VStack(spacing: 10) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.title2)
                            .foregroundStyle(AmberTheme.accentRed)
                        Text(previewErrorText)
                            .font(.body)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding()
                }
            }
            .background(AmberTheme.background)
            .navigationTitle("代码预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    private var previewErrorText: String {
        switch sanitized.status {
        case .ready:
            ""
        case .empty:
            "预览内容为空。"
        case .tooLarge:
            "预览内容过大。"
        case .unsafe:
            "预览内容包含不安全片段。"
        }
    }
}

@MainActor
struct IOSGenerativeWidgetLoadingView: View {
    var body: some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.small)
            Text("正在生成可视化...")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

@MainActor
private struct IOSSafeGenerativeWidgetWebView: UIViewRepresentable {
    let html: String
    let minHeight: CGFloat
    let maxHeight: CGFloat
    let interactive: Bool
    let fillContainer: Bool
    @Binding var height: CGFloat
    var onTap: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(
            minHeight: minHeight,
            maxHeight: maxHeight,
            onResize: { nextHeight in
                let clamped = min(max(nextHeight, minHeight), maxHeight)
                if abs(clamped - height) >= 1.5 {
                    height = clamped
                }
            },
            onTap: onTap
        )
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        let userContent = WKUserContentController()
        userContent.add(context.coordinator, name: "amberWidget")
        config.userContentController = userContent

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = interactive || fillContainer
        webView.scrollView.bounces = interactive || fillContainer
        webView.navigationDelegate = context.coordinator
        context.coordinator.attach(webView: webView)
        webView.loadHTMLString(receiverHTML(interactive: interactive, fillContainer: fillContainer), baseURL: URL(string: "https://amberagent.widget.local/"))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.minHeight = minHeight
        context.coordinator.maxHeight = maxHeight
        context.coordinator.onTap = onTap
        context.coordinator.push(html: stableStreamingHTML(html) ?? html)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "amberWidget")
        webView.navigationDelegate = nil
        webView.stopLoading()
        webView.loadHTMLString("", baseURL: nil)
        coordinator.close()
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var minHeight: CGFloat
        var maxHeight: CGFloat
        var onResize: (CGFloat) -> Void
        var onTap: (() -> Void)?
        private weak var webView: WKWebView?
        private var latestHTML: String = ""
        private var pendingPush: DispatchWorkItem?

        init(minHeight: CGFloat, maxHeight: CGFloat, onResize: @escaping (CGFloat) -> Void, onTap: (() -> Void)?) {
            self.minHeight = minHeight
            self.maxHeight = maxHeight
            self.onResize = onResize
            self.onTap = onTap
        }

        func attach(webView: WKWebView) {
            self.webView = webView
        }

        func close() {
            pendingPush?.cancel()
            pendingPush = nil
            webView = nil
        }

        func push(html: String) {
            guard html != latestHTML else { return }
            latestHTML = html
            pendingPush?.cancel()
            let work = DispatchWorkItem { [weak self] in
                self?.flushLatestHTML()
            }
            pendingPush = work
            DispatchQueue.main.asyncAfter(deadline: .now() + widgetStreamUpdateDelay, execute: work)
        }

        private func flushLatestHTML() {
            pendingPush = nil
            guard let webView, !latestHTML.isEmpty else { return }
            webView.evaluateJavaScript(
                "window.__amberWidgetSetHtml && window.__amberWidgetSetHtml(\(jsStringLiteral(latestHTML)));"
            )
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any],
                  let type = body["type"] as? String else {
                return
            }
            switch type {
            case "resize":
                if let number = body["height"] as? NSNumber {
                    onResize(CGFloat(number.doubleValue))
                }
            case "tap":
                onTap?()
            case "openUrl":
                if let raw = body["url"] as? String, let url = URL(string: raw), isSafeExternalURL(url) {
                    UIApplication.shared.open(url)
                }
            default:
                break
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            pendingPush?.cancel()
            flushLatestHTML()
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            let scheme = url.scheme?.lowercased() ?? ""
            if scheme == "about" || scheme == "data" || scheme == "blob" || url.host == "amberagent.widget.local" {
                decisionHandler(.allow)
                return
            }
            if isSafeExternalURL(url) {
                UIApplication.shared.open(url)
            }
            decisionHandler(.cancel)
        }
    }
}

@MainActor
private struct IOSRichSandboxWebView: UIViewRepresentable {
    let renderer: String
    let specJson: String
    let maxHeight: CGFloat
    var fillContainer: Bool = false

    @State private var height: CGFloat = 520

    func makeCoordinator() -> Coordinator {
        Coordinator(renderer: renderer, specJson: specJson, maxHeight: maxHeight)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        let userContent = WKUserContentController()
        userContent.add(context.coordinator, name: "amberWidget")
        config.userContentController = userContent
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.navigationDelegate = context.coordinator
        webView.scrollView.bounces = renderer != "slides"
        webView.scrollView.isScrollEnabled = renderer != "slides"
        context.coordinator.attach(webView: webView)
        context.coordinator.loadSandbox(in: webView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.specJson = specJson
        context.coordinator.render(in: webView)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "amberWidget")
        webView.navigationDelegate = nil
        webView.stopLoading()
        webView.loadHTMLString("", baseURL: nil)
        coordinator.close()
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        let renderer: String
        var specJson: String
        let maxHeight: CGFloat
        private weak var webView: WKWebView?

        init(renderer: String, specJson: String, maxHeight: CGFloat) {
            self.renderer = renderer
            self.specJson = specJson
            self.maxHeight = maxHeight
        }

        func attach(webView: WKWebView) {
            self.webView = webView
        }

        func close() {
            webView = nil
        }

        func loadSandbox(in webView: WKWebView) {
            guard let html = sandboxHTML() else {
                webView.loadHTMLString(errorPage("缺少本地渲染沙箱。"), baseURL: nil)
                return
            }
            let baseURL = Bundle.main.resourceURL
            webView.loadHTMLString(html, baseURL: baseURL)
        }

        func render(in webView: WKWebView? = nil) {
            guard validate().valid else { return }
            let target = webView ?? self.webView
            let fn = renderer == "slides" ? "__renderSlides" : "__renderChart"
            let safeJson = specJson.toScriptSafeJsonString()
            target?.evaluateJavaScript("""
            window.__setAmberFontCss && window.__setAmberFontCss("");
            window.\(fn) && window.\(fn)(\(jsStringLiteral(safeJson)));
            """)
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            // Rich sandbox height is mostly fixed by the sheet; keep the bridge only
            // for sandbox compatibility with Android assets.
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            render(in: webView)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            let scheme = url.scheme?.lowercased() ?? ""
            if ["about", "data", "blob", "file"].contains(scheme) {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
        }

        private func validate() -> IOSVChartSpecValidator.ValidationResult {
            if renderer == "slides" {
                return IOSVChartSpecValidator.validateSlidesSpec(specJson)
            }
            return IOSVChartSpecValidator.validateChartSpec(specJson)
        }

        private func sandboxHTML() -> String? {
            let fileName = renderer == "slides" ? "slides-sandbox" : "vchart-sandbox"
            guard let url = bundleResourceURL(fileName, extension: "html", subdirectory: "generative-libs"),
                  var html = try? String(contentsOf: url, encoding: .utf8) else {
                return nil
            }
            if renderer == "vchart",
               let jsURL = bundleResourceURL("vchart.min", extension: "js", subdirectory: "generative-libs"),
               let js = try? String(contentsOf: jsURL, encoding: .utf8) {
                html = html.replacingOccurrences(of: #"<script src="./vchart.min.js"></script>"#, with: "<script>\n\(js)\n</script>")
            }
            html = html
                .replacingOccurrences(of: "https://amberagent.local/builtin-fonts/PlayfairDisplay-Regular.ttf", with: "PlayfairDisplay-Regular.ttf")
                .replacingOccurrences(of: "https://amberagent.local/builtin-fonts/Inter-Regular.ttf", with: "Inter-Regular.ttf")
            return html
        }
    }
}

@MainActor
private struct IOSFullHTMLDeckWebView: UIViewRepresentable {
    let specJson: String

    func makeCoordinator() -> Coordinator {
        Coordinator(specJson: specJson)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.setURLSchemeHandler(IOSFullHTMLRuntimeSchemeHandler(), forURLScheme: "amber-full-html")
        config.setURLSchemeHandler(
            IOSFullHTMLRemoteResourceSchemeHandler(),
            forURLScheme: IOSGuizangHtmlDeckValidator.remoteResourceScheme
        )
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.scrollView.bounces = false
        context.coordinator.attach(webView: webView)
        context.coordinator.load(in: webView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.specJson = specJson
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = nil
        webView.stopLoading()
        webView.loadHTMLString("", baseURL: nil)
        coordinator.close()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var specJson: String
        private weak var webView: WKWebView?

        init(specJson: String) {
            self.specJson = specJson
        }

        func attach(webView: WKWebView) {
            self.webView = webView
        }

        func close() {
            webView = nil
        }

        func load(in webView: WKWebView) {
            guard let deck = IOSGuizangHtmlDeckValidator.normalizeSpecJson(specJson) else {
                webView.loadHTMLString(errorPage("全功能 HTML 数据格式不正确。"), baseURL: nil)
                return
            }
            let validation = IOSGuizangHtmlDeckValidator.validateDeck(deck)
            guard validation.valid else {
                webView.loadHTMLString(errorPage("全功能 HTML 校验失败：\(validation.reason ?? "")"), baseURL: nil)
                return
            }
            let html = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(deck)
            webView.loadHTMLString(html, baseURL: URL(string: "amber-full-html://deck/"))
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            let scheme = url.scheme?.lowercased() ?? ""
            if ["about", "data", "blob", "amber-full-html", IOSGuizangHtmlDeckValidator.remoteResourceScheme].contains(scheme) {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
        }
    }
}

private final class IOSFullHTMLRuntimeSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url,
              let asset = IOSGuizangHtmlDeckValidator.runtimeAssetForURL(url.absoluteString),
              let resourceURL = bundleResourceURL(
                asset.resourceName,
                extension: "js",
                subdirectory: "generative-libs/guizang"
              ),
              let data = try? Data(contentsOf: resourceURL) else {
            let response = URLResponse(url: urlSchemeTask.request.url ?? URL(string: "amber-full-html://runtime/missing")!, mimeType: "text/plain", expectedContentLength: 0, textEncodingName: "utf-8")
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didFinish()
            return
        }
        let response = URLResponse(url: url, mimeType: "application/javascript", expectedContentLength: data.count, textEncodingName: "utf-8")
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
}

private final class IOSFullHTMLRemoteResourceSchemeHandler: NSObject, WKURLSchemeHandler {
    private let taskRegistry = IOSFullHTMLRemoteResourceTaskRegistry()

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let requestURL = urlSchemeTask.request.url,
              let resource = IOSGuizangHtmlDeckValidator.remoteResourceOriginalURL(from: requestURL),
              IOSGuizangHtmlDeckValidator.isAllowedRemoteResource(kind: resource.kind, url: resource.originalURL),
              let remoteURL = URL(string: resource.originalURL) else {
            respondEmpty(to: urlSchemeTask)
            return
        }

        var request = URLRequest(url: remoteURL)
        request.cachePolicy = .returnCacheDataElseLoad
        request.timeoutInterval = 15
        let key = ObjectIdentifier(urlSchemeTask as AnyObject)
        let taskBox = IOSFullHTMLURLSchemeTaskBox(urlSchemeTask)
        let registry = taskRegistry
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            // Extract only Sendable values here; deliver the response on the main
            // queue so the stopped-check and the WKURLSchemeTask callbacks are
            // serialized with WebKit's `stop` (also delivered on the main thread).
            // The registry guard alone races with WebKit invalidating the task,
            // which would crash with "This task has already been stopped".
            let responseMimeType = response?.mimeType
            let hadError = error != nil
            DispatchQueue.main.async {
                guard registry.removeTask(for: key) != nil, !hadError else { return }
                var payload = data ?? Data()
                var mimeType = responseMimeType ?? fullHTMLRemoteResourceMimeType(for: remoteURL, kind: resource.kind)
                var encodingName: String? = nil

                if resource.kind == .stylesheet, let css = String(data: payload, encoding: .utf8) {
                    let rewrittenCSS = IOSGuizangHtmlDeckValidator.rewriteRemoteResourceUrls(
                        css,
                        allowRemoteImages: false,
                        allowRemoteFonts: true
                    )
                    payload = Data(rewrittenCSS.utf8)
                    mimeType = "text/css"
                    encodingName = "utf-8"
                }

                let schemeResponse = URLResponse(
                    url: requestURL,
                    mimeType: mimeType,
                    expectedContentLength: payload.count,
                    textEncodingName: encodingName
                )
                taskBox.task.didReceive(schemeResponse)
                if !payload.isEmpty {
                    taskBox.task.didReceive(payload)
                }
                taskBox.task.didFinish()
            }
        }
        taskRegistry.store(task, for: key)
        task.resume()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        let key = ObjectIdentifier(urlSchemeTask as AnyObject)
        taskRegistry.removeTask(for: key)?.cancel()
    }

    private func respondEmpty(to urlSchemeTask: WKURLSchemeTask) {
        let url = urlSchemeTask.request.url ?? URL(string: "\(IOSGuizangHtmlDeckValidator.remoteResourceScheme)://blocked")!
        let response = URLResponse(url: url, mimeType: "text/plain", expectedContentLength: 0, textEncodingName: "utf-8")
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didFinish()
    }
}

private final class IOSFullHTMLURLSchemeTaskBox: @unchecked Sendable {
    let task: WKURLSchemeTask

    init(_ task: WKURLSchemeTask) {
        self.task = task
    }
}

private final class IOSFullHTMLRemoteResourceTaskRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: URLSessionDataTask] = [:]

    func store(_ task: URLSessionDataTask, for key: ObjectIdentifier) {
        lock.lock()
        tasks[key] = task
        lock.unlock()
    }

    @discardableResult
    func removeTask(for key: ObjectIdentifier) -> URLSessionDataTask? {
        lock.lock()
        defer { lock.unlock() }
        return tasks.removeValue(forKey: key)
    }
}

private func fullHTMLRemoteResourceMimeType(for url: URL, kind: IOSGuizangHtmlDeckValidator.RemoteResourceKind) -> String {
    switch kind {
    case .image:
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "avif": return "image/avif"
        default: return "image/png"
        }
    case .stylesheet:
        return "text/css"
    case .font:
        switch url.pathExtension.lowercased() {
        case "woff": return "font/woff"
        case "ttf": return "font/ttf"
        case "otf": return "font/otf"
        default: return "font/woff2"
        }
    }
}

private func receiverHTML(interactive: Bool, fillContainer: Bool) -> String {
    """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1\(interactive ? ",minimum-scale=0.5,maximum-scale=5,user-scalable=yes" : "")">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; media-src 'none'; font-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'">
    <style>
    html,body{margin:0;padding:0;background:transparent;color:#1c1c1e;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;\(fillContainer ? "height:100%;" : "")}
    *{box-sizing:border-box;max-width:100%;}
    body{overflow:\(interactive ? "auto" : "hidden");\(fillContainer ? "-webkit-overflow-scrolling:touch;" : "")}
    #root{width:100%;\(fillContainer ? "min-height:100%;" : "min-height:1px;")overflow:\(interactive ? "auto" : "hidden");}
    #root>*{max-width:100%!important;}
    svg{display:block;width:100%!important;max-width:100%;height:auto;overflow:hidden;}
    table{width:100%;border-collapse:collapse;}
    td,th{border:1px solid #d8d8dd;padding:6px 8px;}
    a{color:#1F5EFF;text-decoration:none;}
    </style>
    </head>
    <body>
    <div id="root"></div>
    <script>
    (function(){
      var root=document.getElementById('root');
      var timer=null;
      function post(payload){
        try{ window.webkit.messageHandlers.amberWidget.postMessage(payload); }catch(e){}
      }
      function report(){
        if(timer) clearTimeout(timer);
        timer=setTimeout(function(){
          var rect=root.getBoundingClientRect();
          var height=Math.ceil(Math.max(rect.height, root.scrollHeight, document.body.scrollHeight, 1));
          post({type:'resize',height:height});
        }, 40);
      }
      function clampSvgOverflow(){
        var svgs=root.getElementsByTagName('svg');
        for(var i=0;i<svgs.length;i++){
          var s=svgs[i];
          s.setAttribute('overflow','hidden');
          s.style.overflow='hidden';
          var vb=s.getAttribute('viewBox');
          if(vb){
            var parts=vb.split(/[\\s,]+/);
            if(parts.length===4){
              var w=parseFloat(parts[2]);
              var h=parseFloat(parts[3]);
              if(w>0&&h>0){
                s.style.aspectRatio=w+' / '+h;
                if(!s.getAttribute('width')) s.style.width='100%';
                s.style.height='auto';
              }
            }
          }
        }
      }
      window.__amberWidgetSetHtml=function(html){
        if(root.innerHTML!==html){ root.innerHTML=html; clampSvgOverflow(); }
        report();
      };
      new ResizeObserver(report).observe(root);
      document.addEventListener('click',function(event){
        if(!event.isTrusted) return;
        var target=event.target;
        var anchor=target&&target.closest?target.closest('a[href]'):null;
        if(anchor){
          var href=anchor.getAttribute('href')||'';
          if(href.indexOf('http://')===0||href.indexOf('https://')===0){
            event.preventDefault();
            post({type:'openUrl',url:href});
          }
          return;
        }
        if(event.defaultPrevented) return;
        \(interactive ? "" : "event.preventDefault(); post({type:'tap'});")
      });
      report();
    })();
    </script>
    </body>
    </html>
    """
}

private func stableStreamingHTML(_ html: String) -> String? {
    var stable = html.trimmingCharacters(in: .whitespacesAndNewlines)
    if stable.count < widgetMinPartialRenderChars { return nil }
    if let scriptStart = stable.range(of: "<script", options: [.caseInsensitive, .backwards]),
       stable.range(of: "</script", options: .caseInsensitive, range: scriptStart.lowerBound..<stable.endIndex) == nil {
        stable = String(stable[..<scriptStart.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    if let styleStart = stable.range(of: "<style", options: [.caseInsensitive, .backwards]),
       stable.range(of: "</style", options: .caseInsensitive, range: styleStart.lowerBound..<stable.endIndex) == nil {
        stable = String(stable[..<styleStart.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    if let lastOpen = stable.lastIndex(of: "<"),
       let lastClose = stable.lastIndex(of: ">"),
       lastOpen > lastClose {
        stable = String(stable[..<lastOpen]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let compact = closeStreamingSVGIfNeeded(stable)
    return compact.count >= widgetMinPartialRenderChars ? compact : nil
}

private func closeStreamingSVGIfNeeded(_ html: String) -> String {
    let compact = html.trimmingCharacters(in: .whitespacesAndNewlines)
    if compact.lowercased().hasPrefix("<svg"), !compact.lowercased().contains("</svg") {
        return compact + "</svg>"
    }
    return compact
}

private func jsStringLiteral(_ value: String) -> String {
    if let data = try? JSONSerialization.data(withJSONObject: [value]),
       let text = String(data: data, encoding: .utf8),
       text.count >= 2 {
        return String(text.dropFirst().dropLast())
    }
    return "\"\""
}

private func isSafeExternalURL(_ url: URL) -> Bool {
    guard let scheme = url.scheme?.lowercased() else { return false }
    return scheme == "http" || scheme == "https"
}

private func errorPage(_ message: String) -> String {
    """
    <!DOCTYPE html><html><body style="font-family:-apple-system;padding:20px;color:#b91c1c;background:#fff">
    \(escapeXML(message))
    </body></html>
    """
}

private func bundleResourceURL(_ name: String, extension ext: String, subdirectory: String) -> URL? {
    Bundle.main.url(forResource: name, withExtension: ext, subdirectory: subdirectory) ??
        Bundle.main.url(forResource: name, withExtension: ext)
}

private extension String {
    func toScriptSafeJsonString() -> String {
        replacingOccurrences(of: "<", with: "\\u003C")
            .replacingOccurrences(of: ">", with: "\\u003E")
            .replacingOccurrences(of: "&", with: "\\u0026")
            .replacingOccurrences(of: "\u{2028}", with: "\\u2028")
            .replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }
}

#Preview("Generative Widget Card") {
    ScrollView {
        IOSGenerativeWidgetCard(
            widget: IOSGenerativeWidget(
                id: "preview",
                title: "Trend",
                widgetCode: IOSGenerativeWidgetRenderer().render(
                    renderer: "chart",
                    specJson: #"{"type":"line","x":["Mon","Tue","Wed"],"series":[{"name":"Count","data":[1,3,2]}]}"#
                ) ?? "<svg></svg>",
                complete: true,
                renderer: "chart"
            )
        )
        .padding()
    }
    .background(AmberTheme.background)
}
