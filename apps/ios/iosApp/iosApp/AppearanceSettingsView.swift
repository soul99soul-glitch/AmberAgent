import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct AppearanceSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.system.rawValue
    private let runtime = AmberThemeRuntime.shared
    private let library = AmberThemePackLibrary.shared

    @State private var showThemeImporter = false
    @State private var exportItem: ThemeExportItem?
    @State private var transferBanner: String?
    @State private var transferError: String?
    @State private var isManagingThemes = false
    @State private var selectedRemovableIds: Set<String> = []
    @State private var showRemoveConfirm = false

    private var selectedMode: IOSAppearanceMode {
        IOSAppearanceMode(rawValue: appearanceMode) ?? .system
    }

    private var matchingThemeId: String? {
        runtime.matchingThemeId
    }

    private var canManageThemes: Bool {
        !library.installed.isEmpty
    }

    var body: some View {
        ZStack {
            AmberThemePageBackground(surface: .shell)

            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    // 外观模式置顶：先定浅/深/跟随系统，再选主题配方。
                    section("外观模式") { appearanceSegmented }

                    section("主题") {
                        themePackGrid
                        if matchingThemeId == nil {
                            Text("当前为自定义组合")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .padding(.top, 4)
                        }
                    }

                    if !isManagingThemes {
                        DisclosureGroup {
                            VStack(alignment: .leading, spacing: 22) {
                                section("背景色") { backgroundCards }
                                section("强调色") { accentSwatches }
                            }
                            .padding(.top, 12)
                        } label: {
                            Text("自定义画布与强调色")
                                .font(AmberChromeFont.settings(.subheadline, weight: .semibold))
                                .foregroundStyle(AmberTheme.foreground)
                        }
                        .tint(AmberTheme.muted)

                        section("主题文件") {
                            themeTransferRow
                            Text("JSON 配方（颜色与风格槽）。导入会加入主题库；不含图片资源；不改浅深模式与聊天字体。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                            if let transferBanner {
                                Text(transferBanner)
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, isManagingThemes ? 16 : 48)
            }
            .scrollEdgeEffectStyle(.soft, for: .top)
            .scrollIndicators(.hidden)
        }
        .safeAreaBar(edge: .top, spacing: 0) { header }
        .safeAreaBar(edge: .bottom, spacing: 0) {
            if isManagingThemes {
                manageRemoveBar
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .fileImporter(
            isPresented: $showThemeImporter,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false
        ) { result in
            handleThemeImport(result)
        }
        .sheet(item: $exportItem) { item in
            AppearanceThemeShareSheet(url: item.url) { completed in
                if completed {
                    transferBanner = "已导出：\(item.displayName)"
                }
            }
        }
        .alert("主题传输失败", isPresented: Binding(
            get: { transferError != nil },
            set: { if !$0 { transferError = nil } }
        )) {
            Button("好", role: .cancel) { transferError = nil }
        } message: {
            Text(transferError ?? "")
        }
        .alert("移除主题", isPresented: $showRemoveConfirm) {
            Button("取消", role: .cancel) {}
            Button("移除", role: .destructive) { confirmRemoveSelectedThemes() }
        } message: {
            Text("将从主题库移除 \(selectedRemovableIds.count) 个导入配方。可随时重新导入。")
        }
        .onChange(of: library.installed.count) { _, count in
            if count == 0 { exitThemeManagement() }
        }
    }

    // MARK: Chrome

    private var header: some View {
        ZStack {
            Text("外观")
                .font(AmberChromeFont.settings(.headline, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground)

            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                    dismiss()
                }
                Spacer()
                if isManagingThemes {
                    Button("完成") { exitThemeManagement() }
                        .font(AmberChromeFont.settings(.subheadline, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityLabel("完成管理主题")
                } else if canManageThemes {
                    Button("管理") {
                        selectedRemovableIds = []
                        isManagingThemes = true
                    }
                    .font(AmberChromeFont.settings(.subheadline, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityLabel("管理导入主题")
                }
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 54)
    }

    private var manageRemoveBar: some View {
        let count = selectedRemovableIds.count
        return Button {
            showRemoveConfirm = true
        } label: {
            Text(count == 0 ? "选择要移除的主题" : "移除 \(count) 个")
                .font(AmberChromeFont.settings(.subheadline, weight: .semibold))
                .foregroundStyle(count == 0 ? AmberTheme.muted : Color.white)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(
                    count == 0 ? AmberTheme.surface2 : AmberTheme.accentRed,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                )
        }
        .buttonStyle(.plain)
        .disabled(count == 0)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityLabel(count == 0 ? "选择要移除的主题" : "移除已选主题")
    }

    private func exitThemeManagement() {
        isManagingThemes = false
        selectedRemovableIds = []
        showRemoveConfirm = false
    }

    private func confirmRemoveSelectedThemes() {
        do {
            let removed = try library.remove(ids: selectedRemovableIds)
            selectedRemovableIds = []
            if removed > 0 {
                transferBanner = "已移除 \(removed) 个主题"
            }
            if library.installed.isEmpty {
                exitThemeManagement()
            }
        } catch {
            transferError = userFacingTransferError(error)
        }
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(AmberChromeFont.settings(size: 12, weight: .semibold))
                .foregroundStyle(AmberTheme.muted)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }

    // MARK: 1 · 外观模式 / 2 · 命名主题

    private var themePackGrid: some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
            spacing: 12
        ) {
            ForEach(AmberThemePack.builtins) { pack in
                themePackCard(pack)
            }
            ForEach(library.installed, id: \.id) { document in
                installedThemePackCard(document)
            }
        }
    }

    private func themePackCard(_ pack: AmberThemePack) -> some View {
        let isSel = matchingThemeId == pack.id
        // Previews always use the pack's light recipe; footer must match that light surface
        // so dark mode doesn't split cream preview + dark footer.
        let palette = pack.paper.lightPalette
        return Button {
            guard !isManagingThemes else { return }
            applyTakingOverTryOn { runtime.apply(pack) }
        } label: {
            themeCardChrome(
                isSelected: isSel && !isManagingThemes,
                footerTitle: pack.displayName,
                footerBackground: Color(hex: palette.surface),
                footerForeground: Color(hex: palette.foreground),
                accessibilityLabel: "主题：\(pack.displayName)"
                    + (isManagingThemes ? "，内置不可移除" : (isSel ? "，已选中" : "")),
                showsLock: isManagingThemes
            ) {
                AmberThemePackMiniPreview(
                    palette: palette,
                    accent: Color(hex: pack.accent.accentHex),
                    canvasStyle: pack.canvasStyle,
                    paintBrandHint: pack.brandMark == .paintAMBER,
                    serifBrandHint: pack.brandMark == .serifWordmark
                )
            }
        }
        .buttonStyle(.plain)
        .disabled(isManagingThemes)
    }

    private func installedThemePackCard(_ document: AmberThemePackDocument) -> some View {
        let isActive = matchingThemeId == document.id
        let isPicked = selectedRemovableIds.contains(document.id)
        let paper = AmberThemeRuntime.Paper(rawValue: document.paper) ?? .neutral
        let palette = paper.lightPalette
        let accent = (try? AmberThemePackTransfer.parseHex(document.accentHex)) ?? AmberAccentOption.amberGold.accentHex
        let canvas = AmberCanvasStyle(rawValue: document.canvasStyle) ?? .flat
        let brand = AmberBrandMarkStyle(rawValue: document.brandMark) ?? .systemWordmark
        return Button {
            if isManagingThemes {
                if isPicked {
                    selectedRemovableIds.remove(document.id)
                } else {
                    selectedRemovableIds.insert(document.id)
                }
            } else {
                applyTakingOverTryOn {
                    try runtime.apply(document)
                }
            }
        } label: {
            themeCardChrome(
                isSelected: isManagingThemes ? isPicked : isActive,
                footerTitle: document.displayName,
                footerBackground: Color(hex: palette.surface),
                footerForeground: Color(hex: palette.foreground),
                accessibilityLabel: "主题：\(document.displayName)"
                    + (isManagingThemes
                        ? (isPicked ? "，已选中待移除" : "，点按选择移除")
                        : (isActive ? "，已选中" : "")),
                // 管理多选不用 accent，避免和「当前主题」混淆。
                useAccentSelection: !isManagingThemes
            ) {
                AmberThemePackMiniPreview(
                    palette: palette,
                    accent: Color(hex: accent),
                    canvasStyle: canvas,
                    paintBrandHint: brand == .paintAMBER,
                    serifBrandHint: brand == .serifWordmark
                )
            }
        }
        .buttonStyle(.plain)
    }

    private var appearanceSegmented: some View {
        HStack(spacing: 0) {
            ForEach(IOSAppearanceMode.allCases) { mode in
                let isSel = selectedMode == mode
                Text(mode.title)
                    .font(.subheadline.weight(isSel ? .semibold : .regular))
                    .foregroundStyle(isSel ? AmberTheme.foreground : AmberTheme.muted)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background {
                        if isSel {
                            // `surface` sits above `surface2` track (paper tokens nearly equal bg/surface2).
                            RoundedRectangle(cornerRadius: 11, style: .continuous)
                                .fill(AmberTheme.surface)
                                .shadow(color: .black.opacity(0.08), radius: 4, y: 1)
                                .padding(3)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { appearanceMode = mode.rawValue }
                    .accessibilityElement()
                    .accessibilityLabel(mode.title)
                    .accessibilityAddTraits(isSel ? [.isButton, .isSelected] : .isButton)
            }
        }
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: 3 · 背景色

    private var backgroundCards: some View {
        // Non-immersive papers only (includes cream draft + Notion warm-white).
        let canvases: [AmberThemeRuntime.Paper] = [.paper, .neutral, .white, .pi, .notion]
        return LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
            spacing: 12
        ) {
            ForEach(canvases, id: \.self) { paper in
                backgroundCard(paper, palette: paper.lightPalette, name: paper.displayName)
            }
        }
    }

    private func backgroundCard(_ paper: AmberThemeRuntime.Paper, palette: AmberPalette, name: String) -> some View {
        let isSel = runtime.paper == paper
        // 暖白 vs 中性白预览接近：暖白卡固定示意 Notion 蓝点，避免只靠 footer 文案区分。
        let previewAccent = paper == .notion
            ? Color(hex: AmberAccentOption.notionBlue.accentHex)
            : AmberTheme.accent
        return Button {
            applyTakingOverTryOn { runtime.paper = paper }
        } label: {
            themeCardChrome(
                isSelected: isSel,
                footerTitle: name,
                footerBackground: Color(hex: palette.surface),
                footerForeground: Color(hex: palette.foreground),
                accessibilityLabel: "背景色：\(name)" + (isSel ? "，已选中" : "")
            ) {
                AmberThemePackMiniPreview(palette: palette, accent: previewAccent)
            }
        }
        .buttonStyle(.plain)
    }

    /// Agent try-on yields to an explicit Appearance pick. Persist the currently
    /// visible slots first so a paper/accent tweak does not leave a mixed
    /// baseline, then apply the user's change. Deny of the pending import is a
    /// no-op because the session is already gone.
    private func applyTakingOverTryOn(_ apply: () throws -> Void) {
        if runtime.isTryOnActive {
            let visible = AmberThemePackTransfer.document(from: runtime)
            runtime.endTryOnWithoutRestore()
            NotificationCenter.default.post(name: .amberThemeTryOnTakenOver, object: nil)
            do {
                try runtime.apply(visible)
            } catch {
                transferError = userFacingTransferError(error)
                return
            }
        }
        do {
            try apply()
        } catch {
            transferError = userFacingTransferError(error)
        }
    }

    // Shared card shell for pack + background grids (same padding / chrome rhythm).
    private func themeCardChrome<Preview: View>(
        isSelected: Bool,
        footerTitle: String,
        footerBackground: Color,
        footerForeground: Color,
        accessibilityLabel: String,
        showsLock: Bool = false,
        useAccentSelection: Bool = true,
        @ViewBuilder preview: () -> Preview
    ) -> some View {
        let mark = useAccentSelection ? AmberTheme.accent : AmberTheme.foreground
        let ink = useAccentSelection ? AmberTheme.accentInk : AmberTheme.background
        return VStack(spacing: 0) {
            preview()
                .frame(maxWidth: .infinity)
                .frame(height: 120)
                .background(Color.clear)

            HStack {
                Text(footerTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(footerForeground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 4)
                if showsLock {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted2)
                        .frame(width: 24, height: 24)
                        .accessibilityHidden(true)
                } else {
                    selectionIndicator(isSelected, mark: mark, ink: ink)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 48)
            .background(footerBackground)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            // Always 2pt so selection doesn't jump the tile's optical size by 1pt.
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(isSelected ? mark : AmberTheme.borderSoft, lineWidth: 2)
        }
        .accessibilityLabel(accessibilityLabel)
    }

    private func selectionIndicator(
        _ isSel: Bool,
        mark: Color = AmberTheme.accent,
        ink: Color = AmberTheme.accentInk
    ) -> some View {
        ZStack {
            if isSel {
                Circle().fill(mark).frame(width: 24, height: 24)
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(ink)
            } else {
                Circle().stroke(AmberTheme.muted2, lineWidth: 1.5).frame(width: 24, height: 24)
            }
        }
        .frame(width: 24, height: 24)
    }

    // MARK: 4 · 强调色

    private var accentSwatches: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 56, maximum: 72), spacing: 0)], spacing: 0) {
            ForEach(AmberAccentOption.allCases) { option in
                let isSel = runtime.accentHex == option.accentHex
                Button {
                    applyTakingOverTryOn { runtime.apply(option) }
                } label: {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Color(hex: option.accentHex))
                            .frame(width: 46, height: 46)

                        if isSel {
                            Image(systemName: "checkmark")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(Color(hex: option.inkHex))
                        }
                    }
                    .overlay {
                        if isSel {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .stroke(AmberTheme.foreground, lineWidth: 2)
                                .frame(width: 54, height: 54)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("强调色：\(option.displayName)" + (isSel ? "，已选中" : ""))
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    // MARK: 5 · 主题文件（导入 / 导出）

    private var themeTransferRow: some View {
        HStack(spacing: 10) {
            transferButton(title: "导出配方", systemImage: "square.and.arrow.up") {
                exportCurrentTheme()
            }
            transferButton(title: "导入配方…", systemImage: "square.and.arrow.down") {
                transferBanner = nil
                showThemeImporter = true
            }
        }
    }

    private func transferButton(title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 14, weight: .semibold))
                Text(title)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(AmberTheme.foreground)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private func exportCurrentTheme() {
        transferBanner = nil
        do {
            let doc = AmberThemePackTransfer.document(from: runtime)
            let url = try AmberThemePackTransfer.writeExportFile(from: runtime)
            exportItem = ThemeExportItem(url: url, displayName: doc.displayName)
        } catch {
            transferError = userFacingTransferError(error)
        }
    }

    private func handleThemeImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            // 用户取消 picker 时系统常带 NSUserCancelledError；不当作失败弹窗。
            let ns = error as NSError
            if ns.domain == NSCocoaErrorDomain, ns.code == NSUserCancelledError { return }
            transferError = userFacingTransferError(error)
        case .success(let urls):
            guard let url = urls.first else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer {
                if access { url.stopAccessingSecurityScopedResource() }
            }
            do {
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                let data = try handle.read(upToCount: 1_048_577) ?? Data()
                guard data.count <= 1_048_576 else {
                    throw AmberThemePackTransferError.fileTooLarge
                }
                let document = try AmberThemePackTransfer.decode(data)
                var outcome: AmberThemePackLibrary.UpsertOutcome = .installed
                applyTakingOverTryOn {
                    outcome = try library.upsert(document)
                    try runtime.apply(document)
                }
                if transferError != nil { return }
                switch outcome {
                case .installed:
                    transferBanner = "已导入并加入主题库：\(document.displayName)"
                case .builtinIdentity:
                    transferBanner = "已应用内置配方：\(document.displayName)"
                }
            } catch {
                transferError = userFacingTransferError(error)
            }
        }
    }

    private func userFacingTransferError(_ error: Error) -> String {
        guard let e = error as? AmberThemePackTransferError else {
            return error.localizedDescription
        }
        switch e {
        case .fileTooLarge:
            return e.localizedDescription
        case .invalidJSON, .invalidFormat:
            return "不是 Amber 主题配方"
        case .unsupportedVersion:
            return "主题版本不支持"
        case .immersivePaper:
            return "沉浸色画布暂不可导入"
        case .unknownPaper, .unknownCanvasStyle, .unknownBrandMark,
                .unknownShortcutIconStyle, .unknownChromeTypeface, .unknownOptionalSlot:
            return "配方含不支持的选项"
        case .invalidHex:
            return "配方颜色无效"
        case .insufficientContrast:
            return e.localizedDescription
        case .missingField:
            return "配方字段不完整"
        }
    }
}

/// Share payload for `.sheet(item:)` — non-nil URL by construction.
private struct ThemeExportItem: Identifiable {
    let id = UUID()
    let url: URL
    let displayName: String
}

/// Minimal share sheet; `onFinished(true)` only when the user completes an activity (not cancel).
private struct AppearanceThemeShareSheet: UIViewControllerRepresentable {
    let url: URL
    var onFinished: (Bool) -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, completed, _, _ in
            DispatchQueue.main.async {
                onFinished(completed)
            }
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
