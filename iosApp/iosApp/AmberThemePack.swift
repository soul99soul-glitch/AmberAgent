import SwiftUI
import UIKit

// MARK: - Style slots (layers other than list layout)

/// Canvas surface treatment on top of paper palette colors.
enum AmberCanvasStyle: String, CaseIterable, Hashable, Identifiable {
    /// Solid color only (current default).
    case flat
    /// Soft dots (sit-style).
    case dotGrid
    /// Square graph paper (Pi draft): 1pt h/v lines + light intersection dots.
    case lineGrid
    /// Fine paper grain (sparse noise; quieter than dotGrid).
    case paperGrain

    var id: String { rawValue }

    /// Whether this style draws a texture overlay (for previews / empty art).
    var hasTexture: Bool {
        switch self {
        case .flat: false
        case .dotGrid, .lineGrid, .paperGrain: true
        }
    }
}

/// Home header brand mark treatment.
enum AmberBrandMarkStyle: String, CaseIterable, Hashable, Identifiable {
    /// System `Text("Amber")` wordmark (current default).
    case systemWordmark
    /// Pixel / sit-grid uppercase AMBER (sit pack brand mark).
    case paintAMBER
    /// Serif italic “Amber” (pi-dotgrid / engineer notebook mark).
    case serifWordmark

    var id: String { rawValue }
}

/// Home shortcut row icon skin (5 entries only; conversation list icons stay independent).
enum AmberShortcutIconStyle: String, CaseIterable, Hashable, Identifiable {
    /// Phosphor fill (current default).
    case phosphorFill
    /// Pixel / sit glyphs for the five home shortcuts.
    case pixelSit

    var id: String { rawValue }
}

/// Chrome / brand typeface for home shell only.
/// **Does not** override chat body fonts (`IOSChatFont` / DisplayFontSettings).
enum AmberChromeTypeface: String, CaseIterable, Hashable, Identifiable {
    /// System UI default (current default packs).
    case system
    /// Rounded UI — sit / playful character packs.
    case rounded
    /// Serif chrome (optional).
    case serif
    /// Monospaced chrome — section labels / meta (Pi: system mono; Jersey 15 可后续打包).
    case monospace

    var id: String { rawValue }

    var design: Font.Design {
        switch self {
        case .system: .default
        case .rounded: .rounded
        case .serif: .serif
        case .monospace: .monospaced
        }
    }

    func font(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: design)
    }
}

/// Convenience: chrome fonts always read live theme runtime (chat body never uses this).
enum AmberChromeFont {
    private static var typeface: AmberChromeTypeface {
        AmberThemeRuntime.shared.chromeTypeface
    }

    static func system(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        typeface.font(size: size, weight: weight)
    }

    /// Settings/shell labels: only follows pack when `settingsChrome` is on.
    /// Fixed-size path (section captions that intentionally stay 12pt).
    static func settings(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        if AmberThemeRuntime.shared.settingsChrome {
            return system(size: size, weight: weight)
        }
        return .system(size: size, weight: weight)
    }

    /// Dynamic Type path for Appearance header / disclosure (restores Text Style scaling).
    static func settings(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        if AmberThemeRuntime.shared.settingsChrome {
            return .system(style, design: typeface.design).weight(weight)
        }
        return .system(style).weight(weight)
    }

    /// Pack chrome design used by `settings` when the gate is on; `nil` when off.
    static var settingsPackDesign: Font.Design? {
        AmberThemeRuntime.shared.settingsChrome ? typeface.design : nil
    }
}

// MARK: - Optional surface slots (defaults = current app behavior)

/// Where canvas texture (dot grid / grain) is drawn. Color tokens still app-wide via paper.
enum AmberCanvasScope: String, CaseIterable, Hashable, Identifiable {
    /// Home only (Phase 1–3 default).
    case homeOnly
    /// Home + settings / appearance shell pages.
    case shell
    /// Most full-bleed pages that opt in via `AmberThemePageBackground`.
    case appWide

    var id: String { rawValue }
}

/// Chat bubble / tool card chrome (radius only — list structure frozen).
enum AmberBubbleChrome: String, CaseIterable, Hashable, Identifiable {
    case standard
    case soft
    case crisp

    var id: String { rawValue }
}

/// Home Liquid Glass pad strength (does not remove glass).
enum AmberGlassChrome: String, CaseIterable, Hashable, Identifiable {
    case standard
    case quieter
    case solid

    var id: String { rawValue }
}

/// Empty-state decoration on home empty card.
enum AmberEmptyArtStyle: String, CaseIterable, Hashable, Identifiable {
    case none
    /// Soft sit cue (e.g. faint dots inside empty card).
    case character

    var id: String { rawValue }
}

/// Account / about hero brand treatment.
enum AmberLaunchBrandStyle: String, CaseIterable, Hashable, Identifiable {
    case none
    /// Reuse active `brandMark` treatment.
    case matchBrand

    var id: String { rawValue }
}

/// Asset resolution mode. Only `builtinOnly` ships today; zip packages are Future (P4).
/// Kept in the pack schema so imports stay stable — not a user-facing control.
enum AmberThemeAssetMode: String, CaseIterable, Hashable, Identifiable {
    case builtinOnly

    var id: String { rawValue }
}

/// Immersive full-bleed papers in pickers. Only `hidden` until P4 contrast work ships.
enum AmberImmersivePolicy: String, CaseIterable, Hashable, Identifiable {
    case hidden

    var id: String { rawValue }
}

/// Page role for canvas-scope checks.
enum AmberPageSurface: Hashable {
    case home
    case shell
    case app
}

/// Semantic home shortcut entry — layout/identity fixed; glyph skin is style-swappable.
enum HomeShortcutEntry: String, CaseIterable, Identifiable {
    case deepRead
    case novel
    case council
    case miniApps
    case webMount

    var id: String { rawValue }

    var title: String {
        switch self {
        case .deepRead: "深度阅读"
        case .novel: "小说创作"
        case .council: "模型议会"
        case .miniApps: "小应用"
        case .webMount: "WebMount"
        }
    }

    /// Phosphor glyph used by `.phosphorFill`.
    var phosphor: HomePhosphor {
        switch self {
        case .deepRead: .bookOpen
        case .novel: .notebook
        case .council: .chatCircleDots
        case .miniApps: .squaresFour
        case .webMount: .globe
        }
    }
}

// MARK: - Theme pack

/// 命名主题配方：核心槽 + 可选表面槽。
/// 浅/深、列表排版、聊天正文字体均不在 pack 范围。
struct AmberThemePack: Identifiable, Equatable, Hashable {
    let id: String
    let displayName: String
    let paper: AmberThemeRuntime.Paper
    let accent: AmberAccentOption
    let canvasStyle: AmberCanvasStyle
    let brandMark: AmberBrandMarkStyle
    let shortcutIconStyle: AmberShortcutIconStyle
    let chromeTypeface: AmberChromeTypeface
    // Optional surface slots (defaults = pre-extension behavior).
    let canvasScope: AmberCanvasScope
    let bubbleChrome: AmberBubbleChrome
    let glassChrome: AmberGlassChrome
    let emptyArt: AmberEmptyArtStyle
    let settingsChrome: Bool
    let launchBrand: AmberLaunchBrandStyle
    let assetMode: AmberThemeAssetMode
    let immersivePolicy: AmberImmersivePolicy

    init(
        id: String,
        displayName: String,
        paper: AmberThemeRuntime.Paper,
        accent: AmberAccentOption,
        canvasStyle: AmberCanvasStyle = .flat,
        brandMark: AmberBrandMarkStyle = .systemWordmark,
        shortcutIconStyle: AmberShortcutIconStyle = .phosphorFill,
        chromeTypeface: AmberChromeTypeface = .system,
        canvasScope: AmberCanvasScope = .homeOnly,
        bubbleChrome: AmberBubbleChrome = .standard,
        glassChrome: AmberGlassChrome = .standard,
        emptyArt: AmberEmptyArtStyle = .none,
        settingsChrome: Bool = false,
        launchBrand: AmberLaunchBrandStyle = .none,
        assetMode: AmberThemeAssetMode = .builtinOnly,
        immersivePolicy: AmberImmersivePolicy = .hidden
    ) {
        self.id = id
        self.displayName = displayName
        self.paper = paper
        self.accent = accent
        self.canvasStyle = canvasStyle
        self.brandMark = brandMark
        self.shortcutIconStyle = shortcutIconStyle
        self.chromeTypeface = chromeTypeface
        self.canvasScope = canvasScope
        self.bubbleChrome = bubbleChrome
        self.glassChrome = glassChrome
        self.emptyArt = emptyArt
        self.settingsChrome = settingsChrome
        self.launchBrand = launchBrand
        self.assetMode = assetMode
        self.immersivePolicy = immersivePolicy
    }

    /// 是否携带非默认风格槽（设置预览可示意点阵等）。
    var hasCharacterStyles: Bool {
        canvasStyle != .flat
            || brandMark != .systemWordmark
            || shortcutIconStyle != .phosphorFill
            || chromeTypeface != .system
            || canvasScope != .homeOnly
            || bubbleChrome != .standard
            || glassChrome != .standard
            || emptyArt != .none
            || settingsChrome
            || launchBrand != .none
    }

    /// 内置主题包（仅非沉浸画布）。经典 6 色 + 角色包。
    static let builtins: [AmberThemePack] = [
        .init(id: "warm-amber", displayName: "暖灰 · 琥珀", paper: .neutral, accent: .amberGold),
        .init(id: "paper-amber", displayName: "暖纸 · 琥珀", paper: .paper, accent: .amberGold),
        .init(id: "white-mist", displayName: "中性白 · 雾蓝", paper: .white, accent: .mistBlue),
        .init(id: "warm-sage", displayName: "暖灰 · 鼠尾草", paper: .neutral, accent: .sage),
        .init(id: "paper-rose", displayName: "暖纸 · 玫红", paper: .paper, accent: .rose),
        .init(id: "white-ink", displayName: "中性白 · 墨", paper: .white, accent: .ink),
        // Character: sit on home (+ appearance shell). Chat stays quiet paper.
        .init(
            id: "sit-terracotta",
            displayName: "点阵 · 陶土",
            paper: .paper,
            accent: .terracotta,
            canvasStyle: .dotGrid,
            brandMark: .paintAMBER,
            shortcutIconStyle: .pixelSit,
            chromeTypeface: .rounded,
            canvasScope: .shell,
            bubbleChrome: .soft,
            glassChrome: .quieter,
            emptyArt: .character,
            settingsChrome: false,
            launchBrand: .none,
            assetMode: .builtinOnly,
            immersivePolicy: .hidden
        ),
        // Open Design pi-dotgrid：奶油稿纸 + 方格点阵 + 衬线品牌 + mono chrome；chat 也吃纹理。
        .init(
            id: "pi-steel",
            displayName: "点阵 · Pi",
            paper: .pi,
            accent: .steelBlue,
            canvasStyle: .lineGrid,
            brandMark: .serifWordmark,
            shortcutIconStyle: .phosphorFill,
            chromeTypeface: .monospace,
            canvasScope: .appWide,
            bubbleChrome: .standard,
            glassChrome: .quieter,
            emptyArt: .character,
            settingsChrome: true,
            launchBrand: .none,
            assetMode: .builtinOnly,
            immersivePolicy: .hidden
        ),
        // Open Design blank-workspace：Notion 式暖白工作台 + Notion 蓝点缀（无点阵）。
        .init(
            id: "notion-blue",
            displayName: "Notion · 暖白",
            paper: .notion,
            accent: .notionBlue,
            canvasStyle: .flat,
            brandMark: .systemWordmark,
            shortcutIconStyle: .phosphorFill,
            chromeTypeface: .system,
            canvasScope: .homeOnly,
            bubbleChrome: .standard,
            glassChrome: .quieter,
            emptyArt: .none,
            settingsChrome: false,
            launchBrand: .none,
            assetMode: .builtinOnly,
            immersivePolicy: .hidden
        ),
    ]

    /// 整包匹配：核心槽 + 可选表面槽。
    func matches(runtime: AmberThemeRuntime) -> Bool {
        paper == runtime.paper
            && accent.accentHex == runtime.accentHex
            && accent.inkHex == runtime.accentInkHex
            && canvasStyle == runtime.canvasStyle
            && brandMark == runtime.brandMarkStyle
            && shortcutIconStyle == runtime.shortcutIconStyle
            && chromeTypeface == runtime.chromeTypeface
            && canvasScope == runtime.canvasScope
            && bubbleChrome == runtime.bubbleChrome
            && glassChrome == runtime.glassChrome
            && emptyArt == runtime.emptyArt
            && settingsChrome == runtime.settingsChrome
            && launchBrand == runtime.launchBrand
            && assetMode == runtime.assetMode
            && immersivePolicy == runtime.immersivePolicy
    }
}

extension AmberThemeRuntime {
    var matchingPack: AmberThemePack? {
        AmberThemePack.builtins.first { $0.matches(runtime: self) }
    }

    var isCustomCombination: Bool {
        matchingPack == nil
    }

    func apply(_ pack: AmberThemePack) {
        paper = pack.paper
        apply(pack.accent)
        canvasStyle = pack.canvasStyle
        brandMarkStyle = pack.brandMark
        shortcutIconStyle = pack.shortcutIconStyle
        chromeTypeface = pack.chromeTypeface
        canvasScope = pack.canvasScope
        bubbleChrome = pack.bubbleChrome
        glassChrome = pack.glassChrome
        emptyArt = pack.emptyArt
        settingsChrome = pack.settingsChrome
        launchBrand = pack.launchBrand
        assetMode = pack.assetMode
        immersivePolicy = pack.immersivePolicy
    }

    /// Apply a portable document (import). Never touches appearance mode or chat fonts.
    func apply(_ document: AmberThemePackDocument) throws {
        try AmberThemePackTransfer.apply(document, to: self)
    }

    /// Whether texture canvas should paint for this page surface.
    func showsCanvasTexture(on surface: AmberPageSurface) -> Bool {
        switch canvasScope {
        case .homeOnly: surface == .home
        case .shell: surface == .home || surface == .shell
        case .appWide: true
        }
    }
}

// MARK: - Import / export (JSON v1)

/// Portable theme recipe. Assets are **not** embedded — only enum slots + color hex.
/// Optional surface keys may be omitted (legacy exports) → defaults.
struct AmberThemePackDocument: Codable, Equatable, Sendable {
    static let formatID = "amber.theme.pack"
    static let currentVersion = 1

    var format: String
    var version: Int
    var id: String
    var displayName: String
    var paper: String
    var accentHex: String
    var inkHex: String
    var canvasStyle: String
    var brandMark: String
    var shortcutIconStyle: String
    var chromeTypeface: String
    // Optional surface slots (absent in older files → defaults on apply).
    var canvasScope: String?
    var bubbleChrome: String?
    var glassChrome: String?
    var emptyArt: String?
    var settingsChrome: Bool?
    var launchBrand: String?
    var assetMode: String?
    var immersivePolicy: String?
}

enum AmberThemePackTransferError: LocalizedError, Equatable {
    case fileTooLarge
    case invalidJSON
    case invalidFormat(String)
    case unsupportedVersion(Int)
    case unknownPaper(String)
    case immersivePaper(String)
    case unknownCanvasStyle(String)
    case unknownBrandMark(String)
    case unknownShortcutIconStyle(String)
    case unknownChromeTypeface(String)
    case unknownOptionalSlot(String)
    case invalidHex(String)
    /// Accent vs on-accent ink contrast below `AmberColorContrast.minimumAccentInkRatio`.
    case insufficientContrast(Double)

    var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            "主题文件不能超过 1 MB"
        case .invalidJSON:
            "无法解析 JSON"
        case .invalidFormat(let value):
            "不是 Amber 主题文件（format: \(value)）"
        case .unsupportedVersion(let v):
            "不支持的主题版本 \(v)"
        case .unknownPaper(let value):
            "未知画布 paper：\(value)"
        case .immersivePaper(let value):
            "沉浸色画布暂不可导入：\(value)"
        case .unknownCanvasStyle(let value):
            "未知 canvasStyle：\(value)"
        case .unknownBrandMark(let value):
            "未知 brandMark：\(value)"
        case .unknownShortcutIconStyle(let value):
            "未知 shortcutIconStyle：\(value)"
        case .unknownChromeTypeface(let value):
            "未知 chromeTypeface：\(value)"
        case .unknownOptionalSlot(let value):
            "未知可选槽：\(value)"
        case .invalidHex(let value):
            "无效颜色：\(value)"
        case .insufficientContrast(let ratio):
            String(
                format: "强调色与上墨色对比度不足（%.2f:1，至少 %.1f:1）",
                ratio,
                AmberColorContrast.minimumAccentInkRatio
            )
        }
    }
}

/// WCAG relative-luminance contrast for theme import gates (accent ↔ ink).
enum AmberColorContrast {
    /// UI / large-text AA floor. Keeps existing builtin accent/ink pairs importable.
    static let minimumAccentInkRatio: Double = 3.0

    static func contrastRatio(_ a: UInt32, _ b: UInt32) -> Double {
        let la = relativeLuminance(a)
        let lb = relativeLuminance(b)
        let lighter = max(la, lb)
        let darker = min(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    static func relativeLuminance(_ hex: UInt32) -> Double {
        let r = srgbLinear(Double((hex >> 16) & 0xFF) / 255)
        let g = srgbLinear(Double((hex >> 8) & 0xFF) / 255)
        let b = srgbLinear(Double(hex & 0xFF) / 255)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private static func srgbLinear(_ channel: Double) -> Double {
        channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
    }
}

enum AmberThemePackTransfer {
    /// Snapshot current runtime (uses matching builtin id/name when possible).
    static func document(from runtime: AmberThemeRuntime) -> AmberThemePackDocument {
        let match = runtime.matchingPack
        return AmberThemePackDocument(
            format: AmberThemePackDocument.formatID,
            version: AmberThemePackDocument.currentVersion,
            id: match?.id ?? "custom",
            displayName: match?.displayName ?? "自定义",
            paper: runtime.paper.rawValue,
            accentHex: hexString(runtime.accentHex),
            inkHex: hexString(runtime.accentInkHex),
            canvasStyle: runtime.canvasStyle.rawValue,
            brandMark: runtime.brandMarkStyle.rawValue,
            shortcutIconStyle: runtime.shortcutIconStyle.rawValue,
            chromeTypeface: runtime.chromeTypeface.rawValue,
            canvasScope: runtime.canvasScope.rawValue,
            bubbleChrome: runtime.bubbleChrome.rawValue,
            glassChrome: runtime.glassChrome.rawValue,
            emptyArt: runtime.emptyArt.rawValue,
            settingsChrome: runtime.settingsChrome,
            launchBrand: runtime.launchBrand.rawValue,
            assetMode: runtime.assetMode.rawValue,
            immersivePolicy: runtime.immersivePolicy.rawValue
        )
    }

    static func document(from pack: AmberThemePack) -> AmberThemePackDocument {
        AmberThemePackDocument(
            format: AmberThemePackDocument.formatID,
            version: AmberThemePackDocument.currentVersion,
            id: pack.id,
            displayName: pack.displayName,
            paper: pack.paper.rawValue,
            accentHex: hexString(pack.accent.accentHex),
            inkHex: hexString(pack.accent.inkHex),
            canvasStyle: pack.canvasStyle.rawValue,
            brandMark: pack.brandMark.rawValue,
            shortcutIconStyle: pack.shortcutIconStyle.rawValue,
            chromeTypeface: pack.chromeTypeface.rawValue,
            canvasScope: pack.canvasScope.rawValue,
            bubbleChrome: pack.bubbleChrome.rawValue,
            glassChrome: pack.glassChrome.rawValue,
            emptyArt: pack.emptyArt.rawValue,
            settingsChrome: pack.settingsChrome,
            launchBrand: pack.launchBrand.rawValue,
            assetMode: pack.assetMode.rawValue,
            immersivePolicy: pack.immersivePolicy.rawValue
        )
    }

    static func encode(_ document: AmberThemePackDocument) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(document)
    }

    static func decode(_ data: Data) throws -> AmberThemePackDocument {
        let decoder = JSONDecoder()
        let document: AmberThemePackDocument
        do {
            document = try decoder.decode(AmberThemePackDocument.self, from: data)
        } catch {
            throw AmberThemePackTransferError.invalidJSON
        }
        try validate(document)
        return document
    }

    static func validate(_ document: AmberThemePackDocument) throws {
        guard document.format == AmberThemePackDocument.formatID else {
            throw AmberThemePackTransferError.invalidFormat(document.format)
        }
        guard document.version == AmberThemePackDocument.currentVersion else {
            throw AmberThemePackTransferError.unsupportedVersion(document.version)
        }
        _ = try resolvedPaper(document.paper)
        let accent = try parseHex(document.accentHex)
        let ink = try parseHex(document.inkHex)
        let accentInkRatio = AmberColorContrast.contrastRatio(accent, ink)
        guard accentInkRatio >= AmberColorContrast.minimumAccentInkRatio else {
            throw AmberThemePackTransferError.insufficientContrast(accentInkRatio)
        }
        guard AmberCanvasStyle(rawValue: document.canvasStyle) != nil else {
            throw AmberThemePackTransferError.unknownCanvasStyle(document.canvasStyle)
        }
        guard AmberBrandMarkStyle(rawValue: document.brandMark) != nil else {
            throw AmberThemePackTransferError.unknownBrandMark(document.brandMark)
        }
        guard AmberShortcutIconStyle(rawValue: document.shortcutIconStyle) != nil else {
            throw AmberThemePackTransferError.unknownShortcutIconStyle(document.shortcutIconStyle)
        }
        guard AmberChromeTypeface(rawValue: document.chromeTypeface) != nil else {
            throw AmberThemePackTransferError.unknownChromeTypeface(document.chromeTypeface)
        }
        try validateOptionalEnum(document.canvasScope, as: AmberCanvasScope.self, label: "canvasScope")
        try validateOptionalEnum(document.bubbleChrome, as: AmberBubbleChrome.self, label: "bubbleChrome")
        try validateOptionalEnum(document.glassChrome, as: AmberGlassChrome.self, label: "glassChrome")
        try validateOptionalEnum(document.emptyArt, as: AmberEmptyArtStyle.self, label: "emptyArt")
        try validateOptionalEnum(document.launchBrand, as: AmberLaunchBrandStyle.self, label: "launchBrand")
        try validateOptionalEnum(document.assetMode, as: AmberThemeAssetMode.self, label: "assetMode")
        try validateOptionalEnum(document.immersivePolicy, as: AmberImmersivePolicy.self, label: "immersivePolicy")
    }

    static func apply(_ document: AmberThemePackDocument, to runtime: AmberThemeRuntime) throws {
        try validate(document)
        let paper = try resolvedPaper(document.paper)
        let accent = try parseHex(document.accentHex)
        let ink = try parseHex(document.inkHex)
        guard let canvas = AmberCanvasStyle(rawValue: document.canvasStyle) else {
            throw AmberThemePackTransferError.unknownCanvasStyle(document.canvasStyle)
        }
        guard let brand = AmberBrandMarkStyle(rawValue: document.brandMark) else {
            throw AmberThemePackTransferError.unknownBrandMark(document.brandMark)
        }
        guard let shortcut = AmberShortcutIconStyle(rawValue: document.shortcutIconStyle) else {
            throw AmberThemePackTransferError.unknownShortcutIconStyle(document.shortcutIconStyle)
        }
        guard let chrome = AmberChromeTypeface(rawValue: document.chromeTypeface) else {
            throw AmberThemePackTransferError.unknownChromeTypeface(document.chromeTypeface)
        }

        runtime.paper = paper
        runtime.accentHex = accent
        runtime.accentInkHex = ink
        runtime.canvasStyle = canvas
        runtime.brandMarkStyle = brand
        runtime.shortcutIconStyle = shortcut
        runtime.chromeTypeface = chrome
        runtime.canvasScope = optional(document.canvasScope, AmberCanvasScope.self, default: .homeOnly)
        runtime.bubbleChrome = optional(document.bubbleChrome, AmberBubbleChrome.self, default: .standard)
        runtime.glassChrome = optional(document.glassChrome, AmberGlassChrome.self, default: .standard)
        runtime.emptyArt = optional(document.emptyArt, AmberEmptyArtStyle.self, default: .none)
        runtime.settingsChrome = document.settingsChrome ?? false
        runtime.launchBrand = optional(document.launchBrand, AmberLaunchBrandStyle.self, default: .none)
        runtime.assetMode = optional(document.assetMode, AmberThemeAssetMode.self, default: .builtinOnly)
        runtime.immersivePolicy = optional(document.immersivePolicy, AmberImmersivePolicy.self, default: .hidden)
    }

    private static func validateOptionalEnum<T: RawRepresentable>(
        _ raw: String?,
        as _: T.Type,
        label: String
    ) throws where T.RawValue == String {
        guard let raw else { return }
        guard T(rawValue: raw) != nil else {
            throw AmberThemePackTransferError.unknownOptionalSlot("\(label)=\(raw)")
        }
    }

    private static func optional<T: RawRepresentable>(
        _ raw: String?,
        _ type: T.Type,
        default def: T
    ) -> T where T.RawValue == String {
        raw.flatMap { T(rawValue: $0) } ?? def
    }

    /// Write JSON to a temp file for the share sheet.
    static func writeExportFile(from runtime: AmberThemeRuntime) throws -> URL {
        let document = document(from: runtime)
        let data = try encode(document)
        let safeName = document.id
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-theme-\(safeName).json")
        try data.write(to: url, options: .atomic)
        return url
    }

    // MARK: Helpers

    static func hexString(_ value: UInt32) -> String {
        String(format: "0x%06X", value & 0x00FF_FFFF)
    }

    static func parseHex(_ raw: String) throws -> UInt32 {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        if s.lowercased().hasPrefix("0x") { s = String(s.dropFirst(2)) }
        guard s.count == 6, let value = UInt32(s, radix: 16) else {
            throw AmberThemePackTransferError.invalidHex(raw)
        }
        return value
    }

    private static func resolvedPaper(_ raw: String) throws -> AmberThemeRuntime.Paper {
        guard let paper = AmberThemeRuntime.Paper(rawValue: raw) else {
            throw AmberThemePackTransferError.unknownPaper(raw)
        }
        if paper.isImmersive {
            throw AmberThemePackTransferError.immersivePaper(raw)
        }
        return paper
    }
}

// MARK: - Canvas

/// Full-bleed canvas: paper color + optional texture overlay (always draws; ignores scope).
struct AmberCanvasBackground: View {
    private let runtime = AmberThemeRuntime.shared

    var body: some View {
        ZStack {
            AmberTheme.background
            canvasOverlay
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .ignoresSafeArea()
    }

    @ViewBuilder
    private var canvasOverlay: some View {
        switch runtime.canvasStyle {
        case .flat:
            EmptyView()
        case .dotGrid:
            AmberDotGridOverlay()
        case .lineGrid:
            AmberLineGridOverlay()
        case .paperGrain:
            AmberPaperGrainOverlay()
        }
    }
}

/// Page background that respects `canvasScope` (home / shell / appWide).
struct AmberThemePageBackground: View {
    let surface: AmberPageSurface
    private let runtime = AmberThemeRuntime.shared

    var body: some View {
        if runtime.showsCanvasTexture(on: surface) {
            AmberCanvasBackground()
        } else {
            AmberTheme.background.ignoresSafeArea()
        }
    }
}

/// Shared lattice origin so page + stacked cards + empty art share one grid (no phase seams).
private enum AmberCanvasGridPhase {
    /// Local coordinate of the first stroke at-or-after the view's leading/top edge,
    /// such that global positions land on `base + n * spacing`.
    static func firstLocal(globalOrigin: CGFloat, spacing: CGFloat, base: CGFloat = 0) -> CGFloat {
        let shifted = globalOrigin - base
        let rem = shifted.truncatingRemainder(dividingBy: spacing)
        let positive = rem >= 0 ? rem : rem + spacing
        // First global lattice point ≥ globalOrigin, expressed in local coords.
        return positive == 0 ? 0 : spacing - positive
    }
}

/// Soft sit / dot grid. Trait-aware ink; kept quiet so glass chrome and section labels stay clean.
struct AmberDotGridOverlay: View {
    /// Brand-spec cream draft cell size (page / card / empty share this).
    var spacing: CGFloat = 18
    var radius: CGFloat = 0.7
    static let lightAlpha: Double = 0.055
    static let darkAlpha: Double = 0.08

    var body: some View {
        let color = Self.dotColor
        // GeometryReader so each clip phases to the same global lattice as the page canvas.
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let half = spacing * 0.5
            let x0 = AmberCanvasGridPhase.firstLocal(globalOrigin: origin.x, spacing: spacing, base: half)
            let y0 = AmberCanvasGridPhase.firstLocal(globalOrigin: origin.y, spacing: spacing, base: half)
            Canvas { context, size in
                var y = y0 - spacing
                while y < size.height + spacing {
                    var x = x0 - spacing
                    while x < size.width + spacing {
                        let rect = CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)
                        context.fill(Path(ellipseIn: rect), with: .color(color))
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }

    /// Warm brown dots on light cream; pale dots on dark grounds (brand-spec grid ink).
    static var dotColor: Color {
        Color(uiColor: UIColor { trait in
            let dark = trait.userInterfaceStyle == .dark
            // Light: rgba(40,32,20,0.055) ≈ warm draft ink; dark: soft pale.
            let hex: UInt32 = dark ? 0xF4F1ED : 0x281F14
            return UIColor(hex: hex, alpha: dark ? darkAlpha : lightAlpha)
        })
    }
}

/// Fine paper grain: deterministic sparse 1pt flecks, quieter than `dotGrid`.
struct AmberPaperGrainOverlay: View {
    /// Sample cell; only a subset of cells receive a fleck (hash gate).
    var cell: CGFloat = 4
    static let lightAlpha: Double = 0.04
    static let darkAlpha: Double = 0.065

    var body: some View {
        let color = Self.grainColor
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let x0 = AmberCanvasGridPhase.firstLocal(globalOrigin: origin.x, spacing: cell)
            let y0 = AmberCanvasGridPhase.firstLocal(globalOrigin: origin.y, spacing: cell)
            Canvas { context, size in
                var y = y0 - cell
                while y < size.height + cell {
                    var x = x0 - cell
                    while x < size.width + cell {
                        // Stable sparse pattern (≈1/5 cells) — no per-frame random.
                        let gx = Int(((origin.x + x) / cell).rounded(.down))
                        let gy = Int(((origin.y + y) / cell).rounded(.down))
                        let h = (gx &* 374_761) &+ (gy &* 668_265)
                        if h & 0x7 == 0 {
                            context.fill(
                                Path(CGRect(x: x, y: y, width: 1, height: 1)),
                                with: .color(color)
                            )
                        }
                        x += cell
                    }
                    y += cell
                }
            }
        }
    }

    static var grainColor: Color {
        Color(uiColor: UIColor { trait in
            let dark = trait.userInterfaceStyle == .dark
            let hex: UInt32 = dark ? 0xF4F1ED : 0x281F14
            return UIColor(hex: hex, alpha: dark ? darkAlpha : lightAlpha)
        })
    }
}

/// Faint texture for **solid cards** (session rows / control card) when canvas is gridded.
/// Same 18pt lattice as the page; opacity only softens ink so session stack / gutters stay one grid.
struct HomeCardCanvasTexture: View {
    var body: some View {
        switch AmberThemeRuntime.shared.canvasStyle {
        case .lineGrid:
            AmberLineGridOverlay()
                .opacity(0.55)
        case .dotGrid:
            AmberDotGridOverlay()
                .opacity(0.42)
        case .paperGrain:
            AmberPaperGrainOverlay()
                .opacity(0.55)
        case .flat:
            EmptyView()
        }
    }
}

/// Square graph paper (Pi): 1pt horizontal + vertical lines @ 18pt, plus soft intersection dots.
/// Matches Open Design dual linear-gradient grid — more visible on device than dots-only.
struct AmberLineGridOverlay: View {
    var spacing: CGFloat = 18
    var lineWidth: CGFloat = 1

    var body: some View {
        let line = Self.lineColor
        let dot = Self.dotColor
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let x0 = AmberCanvasGridPhase.firstLocal(globalOrigin: origin.x, spacing: spacing)
            let y0 = AmberCanvasGridPhase.firstLocal(globalOrigin: origin.y, spacing: spacing)
            Canvas { context, size in
                // Vertical lines (phase-aligned to global)
                var x = x0 - spacing
                while x <= size.width + spacing {
                    var path = Path()
                    path.move(to: CGPoint(x: x, y: 0))
                    path.addLine(to: CGPoint(x: x, y: size.height))
                    context.stroke(path, with: .color(line), lineWidth: lineWidth)
                    x += spacing
                }
                // Horizontal lines
                var y = y0 - spacing
                while y <= size.height + spacing {
                    var path = Path()
                    path.move(to: CGPoint(x: 0, y: y))
                    path.addLine(to: CGPoint(x: size.width, y: y))
                    context.stroke(path, with: .color(line), lineWidth: lineWidth)
                    y += spacing
                }
                // Intersection dots = 点阵 cue on the 方格
                let r: CGFloat = 0.65
                y = y0 - spacing
                while y <= size.height + spacing {
                    x = x0 - spacing
                    while x <= size.width + spacing {
                        let rect = CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)
                        context.fill(Path(ellipseIn: rect), with: .color(dot))
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
    }

    /// Line ink: a notch more visible than pure brand 0.055 so it reads on-device.
    static var lineColor: Color {
        Color(uiColor: UIColor { trait in
            let dark = trait.userInterfaceStyle == .dark
            let hex: UInt32 = dark ? 0xF4F1ED : 0x281F14
            return UIColor(hex: hex, alpha: dark ? 0.11 : 0.08)
        })
    }

    static var dotColor: Color {
        Color(uiColor: UIColor { trait in
            let dark = trait.userInterfaceStyle == .dark
            let hex: UInt32 = dark ? 0xF4F1ED : 0x281F14
            return UIColor(hex: hex, alpha: dark ? 0.14 : 0.10)
        })
    }
}

// MARK: - Brand mark

/// Home header brand mark — header HStack structure frozen; only the mark swaps.
struct AmberBrandMarkView: View {
    private let runtime = AmberThemeRuntime.shared

    var body: some View {
        switch runtime.brandMarkStyle {
        case .systemWordmark:
            systemWordmark
        case .paintAMBER:
            pixelSitMark
        case .serifWordmark:
            serifMark
        }
    }

    private var systemWordmark: some View {
        Text("Amber")
            .font(AmberChromeFont.system(size: 32, weight: .bold))
            .tracking(-0.64)
            .foregroundStyle(AmberTheme.foreground)
            .accessibilityLabel("Amber")
    }

    /// Pixel / sit-grid “AMBER” — same language as home pixel shortcuts (not SF rounded text).
    /// Height locked so search / settings / avatar cluster stays uncrushed.
    private var pixelSitMark: some View {
        AmberPixelWordmarkShape(bits: AmberPixelWordmark.amber)
            .frame(width: AmberPixelWordmark.displayWidth, height: AmberPixelWordmark.displayHeight)
            .foregroundStyle(AmberTheme.foreground)
            .accessibilityLabel("Amber")
    }

    /// Pi-dotgrid brand: serif italic (Iowan/Charter-like via system serif). Accent never on the mark.
    private var serifMark: some View {
        Text("Amber")
            .font(.system(size: 30, weight: .regular, design: .serif).italic())
            .tracking(-0.5)
            .foregroundStyle(AmberTheme.foreground)
            .frame(height: 34, alignment: .center)
            .accessibilityLabel("Amber")
    }
}

/// 5×7 pixel capitals for the sit brand mark (MSB = left column per row).
enum AmberPixelWordmark {
    /// Display size: ~28pt tall (7 rows × 4pt); width from 5 letters × 5 cols + 4 gaps.
    static let cell: CGFloat = 4
    static let letterCols = 5
    static let letterRows = 7
    static let letterGap = 1 // empty columns between glyphs
    static var displayHeight: CGFloat { cell * CGFloat(letterRows) }
    /// 5 letters × 5 cols + 4 single-col gaps = 29 units (~116pt).
    static var displayWidth: CGFloat {
        cell * CGFloat(5 * letterCols + 4 * letterGap)
    }

    /// Full “AMBER” grid: 7 rows × 29 columns packed as bit rows (UInt32 holds 29 bits).
    static let amber: [UInt32] = compose(letters: [a, m, b, e, r])

    // Each letter: 7 rows, only low 5 bits used (bit4 = left).
    private static let a: [UInt8] = [
        0b01110,
        0b10001,
        0b10001,
        0b11111,
        0b10001,
        0b10001,
        0b10001,
    ]
    private static let m: [UInt8] = [
        0b10001,
        0b11011,
        0b10101,
        0b10001,
        0b10001,
        0b10001,
        0b10001,
    ]
    private static let b: [UInt8] = [
        0b11110,
        0b10001,
        0b10001,
        0b11110,
        0b10001,
        0b10001,
        0b11110,
    ]
    private static let e: [UInt8] = [
        0b11111,
        0b10000,
        0b10000,
        0b11110,
        0b10000,
        0b10000,
        0b11111,
    ]
    private static let r: [UInt8] = [
        0b11110,
        0b10001,
        0b10001,
        0b11110,
        0b10100,
        0b10010,
        0b10001,
    ]

    private static func compose(letters: [[UInt8]]) -> [UInt32] {
        let gap = letterGap
        let colsPerLetter = letterCols
        var rows = [UInt32](repeating: 0, count: letterRows)
        for row in 0..<letterRows {
            var packed: UInt32 = 0
            var shiftFromLeft = 0
            for (li, letter) in letters.enumerated() {
                let bits = UInt32(letter[row] & 0b11111)
                // Place 5 bits at current left offset within 29-col word (MSB first).
                let totalWidth = letters.count * colsPerLetter + (letters.count - 1) * gap
                let col = shiftFromLeft
                // Pack so bit (totalWidth-1) is leftmost.
                for c in 0..<colsPerLetter {
                    if (bits & (1 << (colsPerLetter - 1 - c))) != 0 {
                        let bitIndex = totalWidth - 1 - (col + c)
                        packed |= 1 << bitIndex
                    }
                }
                shiftFromLeft += colsPerLetter
                if li < letters.count - 1 {
                    shiftFromLeft += gap
                }
            }
            rows[row] = packed
        }
        return rows
    }
}

struct AmberPixelWordmarkShape: Shape {
    let bits: [UInt32]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let rows = AmberPixelWordmark.letterRows
        let cols = 5 * AmberPixelWordmark.letterCols + 4 * AmberPixelWordmark.letterGap
        guard bits.count >= rows else { return path }
        let cellW = rect.width / CGFloat(cols)
        let cellH = rect.height / CGFloat(rows)
        for r in 0..<rows {
            let rowBits = bits[r]
            for c in 0..<cols {
                let bitIndex = cols - 1 - c
                guard (rowBits & (1 << bitIndex)) != 0 else { continue }
                // Slight inset so cells read as a soft grid, not a solid bar.
                let inset = min(cellW, cellH) * 0.12
                path.addRect(CGRect(
                    x: rect.minX + CGFloat(c) * cellW + inset,
                    y: rect.minY + CGFloat(r) * cellH + inset,
                    width: max(cellW - inset * 2, 0.5),
                    height: max(cellH - inset * 2, 0.5)
                ))
            }
        }
        return path
    }
}

// MARK: - Shortcut icons

/// Resolves a home shortcut glyph for the active icon style.
struct HomeShortcutIconView: View {
    let entry: HomeShortcutEntry
    var size: CGFloat = 20
    private let runtime = AmberThemeRuntime.shared

    var body: some View {
        switch runtime.shortcutIconStyle {
        case .phosphorFill:
            HomePhosphorIcon(entry.phosphor, size: size)
        case .pixelSit:
            HomePixelSitIcon(entry: entry, size: size)
        }
    }
}

/// 16×16 pixel-art glyphs for the five home shortcuts (sit pack).
/// `Shape` so color follows parent `.foregroundStyle` (same as Phosphor icons).
struct HomePixelSitIcon: View {
    let entry: HomeShortcutEntry
    var size: CGFloat = 20

    var body: some View {
        HomePixelSitShape(bits: HomePixelSitGlyph.bits(for: entry))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct HomePixelSitShape: Shape {
    let bits: [UInt16]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let cell = min(rect.width, rect.height) / 16
        let originX = rect.minX + (rect.width - cell * 16) / 2
        let originY = rect.minY + (rect.height - cell * 16) / 2
        guard bits.count >= 16 else { return path }
        for row in 0..<16 {
            let rowBits = bits[row]
            for col in 0..<16 {
                guard (rowBits & (1 << (15 - col))) != 0 else { continue }
                path.addRect(CGRect(
                    x: originX + CGFloat(col) * cell,
                    y: originY + CGFloat(row) * cell,
                    width: cell,
                    height: cell
                ))
            }
        }
        return path
    }
}

/// Packed 16-bit rows (MSB = left). Each glyph is a simple sit-style silhouette.
enum HomePixelSitGlyph {
    /// Returns 16 rows of UInt16 bitmasks.
    static func bits(for entry: HomeShortcutEntry) -> [UInt16] {
        switch entry {
        case .deepRead: book
        case .novel: notebook
        case .council: chat
        case .miniApps: grid
        case .webMount: globe
        }
    }

    // Open book (deep read) — filled pages so weight matches phosphor fill.
    private static let book: [UInt16] = [
        0b0000000000000000,
        0b0001111111111000,
        0b0011111111111100,
        0b0111110011111110,
        0b0111110011111110,
        0b0110010010011110,
        0b0110010010011110,
        0b0111110011111110,
        0b0110010010011110,
        0b0110010010011110,
        0b0111110011111110,
        0b0111110011111110,
        0b0011111111111100,
        0b0001111111111000,
        0b0000000000000000,
        0b0000000000000000,
    ]

    // Notebook with spine (novel)
    private static let notebook: [UInt16] = [
        0b0000000000000000,
        0b0011111111111100,
        0b0011000000001100,
        0b0011011111101100,
        0b0011000000001100,
        0b0011011111101100,
        0b0011000000001100,
        0b0011011111101100,
        0b0011000000001100,
        0b0011011111101100,
        0b0011000000001100,
        0b0011011111101100,
        0b0011000000001100,
        0b0011111111111100,
        0b0000000000000000,
        0b0000000000000000,
    ]

    // Chat bubble + dots (council)
    private static let chat: [UInt16] = [
        0b0000000000000000,
        0b0001111111111000,
        0b0011111111111100,
        0b0110000000000110,
        0b0110000000000110,
        0b0110011011000110,
        0b0110000000000110,
        0b0110011011000110,
        0b0110000000000110,
        0b0011111111111100,
        0b0001111111111000,
        0b0000011000000000,
        0b0000110000000000,
        0b0001100000000000,
        0b0000000000000000,
        0b0000000000000000,
    ]

    // 2×2 app grid (mini apps) — larger tiles, vertical balance.
    private static let grid: [UInt16] = [
        0b0000000000000000,
        0b0000000000000000,
        0b0011110000111100,
        0b0011110000111100,
        0b0011110000111100,
        0b0011110000111100,
        0b0000000000000000,
        0b0000000000000000,
        0b0011110000111100,
        0b0011110000111100,
        0b0011110000111100,
        0b0011110000111100,
        0b0000000000000000,
        0b0000000000000000,
        0b0000000000000000,
        0b0000000000000000,
    ]

    // Globe / ring (web mount)
    private static let globe: [UInt16] = [
        0b0000000000000000,
        0b0000011111100000,
        0b0001111111111000,
        0b0011100000011100,
        0b0011001111001100,
        0b0110011111100110,
        0b0110001111000110,
        0b0110000110000110,
        0b0110001111000110,
        0b0110011111100110,
        0b0011001111001100,
        0b0011100000011100,
        0b0001111111111000,
        0b0000011111100000,
        0b0000000000000000,
        0b0000000000000000,
    ]
}
