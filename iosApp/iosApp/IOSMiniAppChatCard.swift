import Shared
import SwiftUI
import UIKit

/// Streaming MiniApp payload card: image-gen-like surface by default, tap to
/// watch frontend/code stream, tap again to collapse. Replaces flat markdown
/// while the model is emitting MiniApp JSON/HTML.
struct ChatMiniAppStreamingCard: View {
    let text: String
    var isGenerating: Bool = true

    @State private var showCode = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var hasCode: Bool {
        text.contains { !$0.isWhitespace }
    }

    private var title: String {
        if isGenerating {
            return showCode ? "正在生成前端代码" : "正在生成小应用"
        }
        return showCode ? "小应用代码" : "小应用"
    }

    private var displayCode: String {
        Self.codePreview(from: text)
    }

    var body: some View {
        // One shell both modes: whole card toggles. Use simultaneousGesture so
        // ScrollView drags still work; a discrete tap flips preview ↔ code.
        cardShell {
            headerRow
            if showCode {
                codeBody
            } else {
                previewBody
            }
        }
        .contentShape(Rectangle())
        .simultaneousGesture(TapGesture().onEnded(toggleMode))
        .modifier(ChatGeneratedImageAppearModifier())
        .accessibilityElement(children: showCode ? .contain : .combine)
        .accessibilityLabel(title)
        .accessibilityValue(showCode ? "代码模式" : "预览模式")
        .accessibilityHint("点按切换预览与代码")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: Text(showCode ? "显示预览" : "显示代码"), toggleMode)
    }

    private func cardShell<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var headerRow: some View {
        HStack(spacing: 8) {
            ChatUIKitVariableColorSymbol(
                systemName: showCode ? "chevron.left.forwardslash.chevron.right" : "sparkles",
                pointSize: 12.5,
                weight: .semibold,
                tint: UIColor(AmberTheme.accent),
                isActive: isGenerating && !reduceMotion
            )

            Text(title)
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(1)

            Spacer(minLength: 6)

            Image(systemName: "chevron.down")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(AmberTheme.muted)
                .rotationEffect(.degrees(showCode ? 180 : 0))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func toggleMode() {
        guard hasCode || isGenerating else { return }
        if reduceMotion {
            showCode.toggle()
        } else {
            withAnimation(.easeInOut(duration: 0.28)) {
                showCode.toggle()
            }
        }
    }

    private var previewBody: some View {
        // Pure image-gen surface — no mid-card copy overlay.
        ChatGeneratedImageDotPlaceholder(
            aspectRatio: 16.0 / 10.0,
            cornerRadius: 12,
            showsChrome: false,
            isAnimating: isGenerating
        )
        .frame(maxWidth: .infinity)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 10)
        .padding(.bottom, 10)
        .accessibilityHidden(true)
    }

    private var codeBody: some View {
        ScrollViewReader { proxy in
            ScrollView {
                Text(displayCode.isEmpty ? " " : displayCode)
                    .font(.system(size: 11.5, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // No textSelection: it steals the card-level tap that
                    // collapses code → preview. Final MiniApp card can export.
                    .id("miniapp-code-bottom")
            }
            .frame(maxWidth: .infinity)
            .frame(height: 200)
            .padding(10)
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.horizontal, 10)
            .padding(.bottom, 10)
            // Streaming: disable selection so parent card tap can collapse.
            // (Selection while generating fights the toggle gesture.)
            .onChange(of: displayCode) { _, _ in
                guard isGenerating else { return }
                if reduceMotion {
                    proxy.scrollTo("miniapp-code-bottom", anchor: .bottom)
                } else {
                    withAnimation(.easeOut(duration: 0.12)) {
                        proxy.scrollTo("miniapp-code-bottom", anchor: .bottom)
                    }
                }
            }
            .onAppear {
                proxy.scrollTo("miniapp-code-bottom", anchor: .bottom)
            }
        }
        .mask(codeFadeMask)
        .accessibilityLabel("小应用代码")
    }

    private var codeFadeMask: some View {
        VStack(spacing: 0) {
            LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                .frame(height: 10)
            Rectangle()
            LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 10)
        }
    }

    /// Prefer decoded `"html"` field while JSON is streaming; fall back to a bare HTML
    /// document if present; otherwise show the full payload so partial output still paints.
    static func codePreview(from text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        // JSON payloads embed HTML as escaped text — decode that first so code mode
        // shows real frontend source instead of `\"` / `\n` soup.
        if trimmed.contains("\"html\""), let unescaped = unescapedHTMLField(from: trimmed) {
            return unescaped
        }
        if let doctype = trimmed.range(of: "<!DOCTYPE html", options: .caseInsensitive)
            ?? trimmed.range(of: "<html", options: .caseInsensitive) {
            return String(trimmed[doctype.lowerBound...])
        }
        return trimmed
    }

    private static func unescapedHTMLField(from text: String) -> String? {
        // Cheap scan for `"html":"..."` while the model is still streaming.
        guard let key = text.range(of: "\"html\"") else { return nil }
        var index = key.upperBound
        while index < text.endIndex, text[index].isWhitespace || text[index] == ":" {
            index = text.index(after: index)
        }
        guard index < text.endIndex, text[index] == "\"" else { return nil }
        index = text.index(after: index)
        var result = ""
        result.reserveCapacity(min(text.distance(from: index, to: text.endIndex), 16_384))
        var escaped = false
        while index < text.endIndex {
            let ch = text[index]
            if escaped {
                switch ch {
                case "n": result.append("\n")
                case "r": result.append("\r")
                case "t": result.append("\t")
                case "\"", "\\", "/": result.append(ch)
                case "u":
                    // Skip \uXXXX if complete; otherwise keep raw.
                    let hexStart = text.index(after: index)
                    if let hexEnd = text.index(hexStart, offsetBy: 4, limitedBy: text.endIndex),
                       let scalar = UInt32(text[hexStart..<hexEnd], radix: 16),
                       let unicode = UnicodeScalar(scalar) {
                        result.append(Character(unicode))
                        index = hexEnd
                        escaped = false
                        continue
                    }
                    result.append(ch)
                default:
                    result.append(ch)
                }
                escaped = false
            } else if ch == "\\" {
                escaped = true
            } else if ch == "\"" {
                break
            } else {
                result.append(ch)
            }
            index = text.index(after: index)
            if result.count > 48_000 { break }
        }
        let cleaned = result.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? nil : cleaned
    }
}

/// Chat-inline MiniApp card: run / modify / export / versions.
struct IOSMiniAppChatCard: View {
    let part: UIMessagePart.MiniApp
    var onRun: () -> Void = {}
    var onOpenList: () -> Void = {}
    /// Returns false when the modify request was rejected (e.g. generation already active).
    var onModify: (String) -> Bool = { _ in true }

    @State private var showModifySheet = false
    @State private var modifyPrompt = ""
    @State private var modifyBusyRejected = false
    @State private var exportShare: MiniAppExportShare?
    @State private var exportError: MiniAppExportError?
    @State private var showVersionHistory = false
    @State private var versions: [IOSMiniAppVersionRecord] = []
    @State private var cardTitle: String = ""
    @State private var cardVersion: Int = 1

    private var repository: IOSMiniAppRepository { .shared }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Text(part.iconEmoji?.nilIfBlank ?? "▣")
                    .font(.system(size: 28))
                    .frame(width: 44, height: 44)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text(displayTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(2)
                    Text("v\(displayVersion) · \(part.category?.nilIfBlank ?? "tool")")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                // Top-align with title line; 44pt hit box without centering on the icon.
                Button(action: onOpenList) {
                    Text("全部")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
                .frame(minWidth: 44, minHeight: 44, alignment: .topTrailing)
                .contentShape(Rectangle())
                .padding(.top, 1)
                .accessibilityLabel("全部小应用")
            }

            if !part.description_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(part.description_)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }

            // Equal-width capsules edge-to-edge (生图 action row density).
            actionButtonsRow
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(AmberTheme.border.opacity(0.7), lineWidth: 1)
        )
        .task(id: part.appId) {
            refreshHeaderFromRepository()
        }
        .sheet(isPresented: $showModifySheet) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 12) {
                    Text("描述你想改的地方")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    TextEditor(text: $modifyPrompt)
                        .frame(minHeight: 140)
                        .padding(10)
                        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .scrollContentBackground(.hidden)
                        .accessibilityLabel("小应用修改说明")
                        .accessibilityHint("描述希望修改的内容")
                    if modifyBusyRejected {
                        Text("当前正在生成回复，请稍后再修改。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.accentAmber)
                    }
                    Spacer(minLength: 0)
                }
                .padding(16)
                .navigationTitle("修改小应用")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showModifySheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("发送") {
                            let prompt = modifyPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !prompt.isEmpty else { return }
                            let accepted = onModify(
                                IOSMiniAppChatMessageFactory.revisionPrompt(
                                    appId: part.appId,
                                    title: displayTitle,
                                    version: displayVersion,
                                    request: prompt
                                )
                            )
                            if accepted {
                                showModifySheet = false
                            } else {
                                modifyBusyRejected = true
                            }
                        }
                        .disabled(modifyPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showVersionHistory) {
            NavigationStack {
                Group {
                    if versions.isEmpty {
                        ContentUnavailableView(
                            "暂无历史版本",
                            systemImage: "clock",
                            description: Text("保存或修改小应用后会出现版本记录")
                        )
                    } else {
                        List {
                            ForEach(versions) { version in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text("v\(version.versionNumber)")
                                            .font(.subheadline.weight(.semibold))
                                        if version.versionNumber == displayVersion {
                                            Text("当前")
                                                .font(.caption2.weight(.semibold))
                                                .foregroundStyle(AmberTheme.accent)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(AmberTheme.accentTint, in: Capsule())
                                        }
                                        Spacer()
                                        Text(Self.formatDate(version.createdAt))
                                            .font(.caption)
                                            .foregroundStyle(AmberTheme.muted)
                                    }
                                    Text(version.changeNote ?? "小应用版本")
                                        .font(.caption)
                                        .foregroundStyle(AmberTheme.muted)
                                        .lineLimit(3)
                                }
                                .padding(.vertical, 2)
                            }
                        }
                        .scrollContentBackground(.hidden)
                        .background(AmberTheme.background)
                        .listStyle(.insetGrouped)
                    }
                }
                .navigationTitle("版本历史")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { showVersionHistory = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $exportShare) { share in
            MiniAppActivityShareSheet(items: [share.url])
        }
        .alert(item: $exportError) { error in
            Alert(
                title: Text("无法导出小应用"),
                message: Text(error.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var displayTitle: String {
        let trimmed = cardTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? part.title : trimmed
    }

    private var displayVersion: Int {
        max(cardVersion, Int(part.version))
    }

    /// Visual capsule height matches chat image actions (~28–30), not chunky
    /// `.bordered` system controls. Four equal columns span the card content width.
    private var actionButtonsRow: some View {
        HStack(spacing: 8) {
            miniAppActionButton(
                title: "运行",
                systemImage: "play.fill",
                emphasized: true,
                action: onRun
            )
            miniAppActionButton(title: "修改", systemImage: "pencil", emphasized: false) {
                modifyBusyRejected = false
                modifyPrompt = ""
                showModifySheet = true
            }
            miniAppActionButton(
                title: "导出",
                systemImage: "square.and.arrow.up",
                emphasized: false,
                action: exportHTML
            )
            miniAppActionButton(title: "历史", systemImage: "clock.arrow.circlepath", emphasized: false) {
                versions = repository.versions(appId: part.appId)
                showVersionHistory = true
            }
        }
    }

    private func miniAppActionButton(
        title: String,
        systemImage: String,
        emphasized: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label {
                Text(title)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            } icon: {
                Image(systemName: systemImage)
                    .font(.system(size: 11, weight: .semibold))
            }
            .labelStyle(.titleAndIcon)
            .font(.caption.weight(.semibold))
            .foregroundStyle(emphasized ? AmberTheme.accent : AmberTheme.foreground2)
            .frame(maxWidth: .infinity)
            .frame(height: 30)
            .background(
                emphasized ? AmberTheme.accentTint : AmberTheme.surface2,
                in: Capsule()
            )
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
        // Visual capsule stays ~30; expand hit area without restyling.
        .frame(maxWidth: .infinity, minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityLabel(title)
    }

    private func refreshHeaderFromRepository() {
        guard let record = repository.get(part.appId) else {
            cardTitle = part.title
            cardVersion = Int(part.version)
            return
        }
        cardTitle = record.title
        cardVersion = record.version
    }

    private func exportHTML() {
        guard let record = repository.get(part.appId) else {
            exportError = MiniAppExportError(message: "找不到这个小应用的已保存内容。")
            return
        }
        let safeName = record.title
            .replacingOccurrences(of: "/", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let filename = "\(safeName.isEmpty ? "miniapp" : safeName)-v\(record.version).html"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        do {
            try record.htmlContent.write(to: url, atomically: true, encoding: .utf8)
            exportShare = MiniAppExportShare(url: url)
        } catch {
            exportError = MiniAppExportError(message: "无法写入导出文件：\(error.localizedDescription)")
        }
    }

    private static func formatDate(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
            .formatted(date: .abbreviated, time: .shortened)
    }
}

private struct MiniAppExportShare: Identifiable {
    let id = UUID()
    let url: URL
}

private struct MiniAppExportError: Identifiable {
    let id = UUID()
    let message: String
}

private struct MiniAppActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
