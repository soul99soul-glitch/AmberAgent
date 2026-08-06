import SwiftUI
import UIKit
@preconcurrency import Shared
import UniformTypeIdentifiers

/// A full set of canvas tokens for one appearance (warm paper / neutral / dark).
struct AmberPalette {
    let background, surface, surface2, foreground, foreground2, muted, muted2, border, borderSoft: UInt32
}

// Theme tokens are dynamic: the canvas (paper vs neutral) + accent come from AmberThemeRuntime,
// and light/dark is resolved by a UIColor dynamicProvider. Because the color getters read the
// @Observable runtime, any SwiftUI body that reads AmberTheme.* auto-tracks theme changes and
// re-renders — with zero changes at the ~1500 existing call sites.
enum AmberTheme {
    // 暖纸（设计 E 版变体）：画布 #EFE7D6、卡片 #FFFDF7，投影偏暖棕。
    static let paperLight = AmberPalette(
        background: 0xEFE7D6, surface: 0xFFFDF7, surface2: 0xF0EBE2,
        foreground: 0x1B1813, foreground2: 0x5B5449, muted: 0x746D62, muted2: 0x918A80,
        border: 0xDBCEBC, borderSoft: 0xECE3D6
    )
    // 默认主题（设计 E 版）：中性暖灰画布 #ECE8E4（禁止纯白）+ 暖白卡片 #F6F5F3，
    // figure/ground 靠「卡片亮一档 + 贴身接触投影」建立，不靠描边。
    static let neutralLight = AmberPalette(
        background: 0xECE8E4, surface: 0xF6F5F3, surface2: 0xEDEBE7,
        foreground: 0x161514, foreground2: 0x55524D, muted: 0x716D67, muted2: 0x8F8B85,
        border: 0xD9D5CF, borderSoft: 0xE4E1DC
    )
    // 深色（设计 E 版）：画布 #0E0D10、卡片 #1F1D23，玻璃改 10% 白、投影改黑系。
    static let darkPalette = AmberPalette(
        background: 0x0E0D10, surface: 0x1F1D23, surface2: 0x2B2930,
        foreground: 0xF4F1ED, foreground2: 0xC3BEC5, muted: 0xAAA5AD, muted2: 0x6E6760,
        border: 0x3A3741, borderSoft: 0x2A2830
    )

    // ── Immersive single-hue canvases (Apple-Music-style full-bleed color). Each is a
    // fixed color in BOTH light & dark (the theme IS the color), with text tuned for AA
    // contrast on its own ground: deep grounds get pale ink, light grounds get dark ink.
    //
    // ⚠️ CURRENTLY HIDDEN from the picker (AppearanceSettingsView filters out `isImmersive`
    // canvases) — full-bleed color read poorly app-wide. These palettes + their `Paper`
    // cases are kept as PLACEHOLDERS so re-enabling or swapping in new colors later is a
    // one-liner: edit the hex values below (or add a new palette + `Paper` case), then drop
    // the `!$0.isImmersive` filter in `backgroundCards`. Everything else (base()/picker)
    // auto-resolves. Wiring is proven-good; only the color choices were the problem.
    // 绛红 — deep wine, pale rose ink.
    static let garnetPalette = AmberPalette(
        background: 0x5B1A1C, surface: 0x6F282A, surface2: 0x843234,
        foreground: 0xF7E6E4, foreground2: 0xE4C7C5, muted: 0xC89C9B, muted2: 0xAC7E7C,
        border: 0x7C3436, borderSoft: 0x682A2C
    )
    // 赭橙 — rust sienna, cream ink.
    static let ochrePalette = AmberPalette(
        background: 0xAE5230, surface: 0xBC6440, surface2: 0xC8714C,
        foreground: 0xFBF1E8, foreground2: 0xF1DECC, muted: 0xE7C5A9, muted2: 0xD7A988,
        border: 0xC26C47, borderSoft: 0xB45E3B
    )
    // 姜黄 — mustard gold, dark espresso ink.
    static let turmericPalette = AmberPalette(
        background: 0xC18D1A, surface: 0xCE9B2C, surface2: 0xD9A83E,
        foreground: 0x3A2C06, foreground2: 0x5B4612, muted: 0x856A1E, muted2: 0xA08238,
        border: 0xAD7C16, borderSoft: 0xBC8A1C
    )
    // 品红 — rose magenta, pale blush ink.
    static let magentaPalette = AmberPalette(
        background: 0xB23A66, surface: 0xC04874, surface2: 0xCC5682,
        foreground: 0xFCE6EE, foreground2: 0xF3CCDB, muted: 0xE6ABC3, muted2: 0xD68BAA,
        border: 0xC25180, borderSoft: 0xB4426E
    )
    // 藕荷 — pale dusty blush, dark ink.
    static let lotusPalette = AmberPalette(
        background: 0xE7D5D2, surface: 0xF2E5E3, surface2: 0xDBC8C5,
        foreground: 0x2E2422, foreground2: 0x4F413E, muted: 0x7D6D69, muted2: 0xA4918D,
        border: 0xD7C1BD, borderSoft: 0xE3D0CD
    )

    private static func base(_ key: KeyPath<AmberPalette, UInt32>, alpha: Double = 1) -> Color {
        let paper = AmberThemeRuntime.shared.paper
        let lightHex = paper.lightPalette[keyPath: key]
        let darkHex = paper.darkPalette[keyPath: key]
        return Color(uiColor: UIColor { trait in
            UIColor(hex: trait.userInterfaceStyle == .dark ? darkHex : lightHex, alpha: alpha)
        })
    }

    static var background: Color { base(\.background) }
    static var surface: Color { base(\.surface) }
    static var surface2: Color { base(\.surface2) }
    static var card: Color { base(\.surface) }
    static var foreground: Color { base(\.foreground) }
    static var foreground2: Color { base(\.foreground2) }
    static var muted: Color { base(\.muted) }
    static var muted2: Color { base(\.muted2) }
    static var border: Color { base(\.border) }
    static var borderSoft: Color { base(\.borderSoft) }

    // Runtime accent (the user's swatch). accentInk = on-accent text/icon color.
    static var accent: Color { Color(hex: AmberThemeRuntime.shared.accentHex) }
    static var accentTint: Color { Color(hex: AmberThemeRuntime.shared.accentHex, alpha: 0.12) }
    static var accentInk: Color { Color(hex: AmberThemeRuntime.shared.accentInkHex) }

    // Semantic status colors stay fixed (status is always color + symbol/label elsewhere).
    static let accentIndigo = Color(hex: 0x5856D6)
    static let accentAmber = Color(hex: 0xD98324)
    static let accentGreen = Color(hex: 0x3DA35D)
    static let accentCyan = Color(hex: 0x2AA0BC)
    static let accentRed = Color(hex: 0xC8402F)

    static var glass: Color { base(\.background, alpha: 0.72) }
    static var glassStrong: Color { base(\.background, alpha: 0.85) }

    static let radiusSmall: CGFloat = 6
    static let radiusMedium: CGFloat = 8
    static let radiusLarge: CGFloat = 12
    static let radiusXLarge: CGFloat = 18
    static let radiusPill: CGFloat = 980

    // ── E 版首页设计令牌 ─────────────────────────────────────────────
    // 所有色值均为设计稿像素实测值，直接使用，不要取整或「优化」。
    // 单一 accent：琥珀金 #B9863A 只允许出现在 FAB、设置齿轮、激活头像/墨色、focus 环。

    /// 全 App 唯一分隔线语言：1px hairline。
    static var separator: Color { homeColor(\.sep, alpha: \.sepAlpha) }
    /// hover/按压垫底（前景只允许加深，禁止变浅变灰）。
    static var press: Color { homeColor(\.press, alpha: \.pressAlpha) }
    static var hoverCard: Color { homeColor(\.hoverCard) }
    /// 激活会话行通栏色带（无内圆角、无内描边、无内缩，由外层卡片圆角裁切）。
    static var activeCard: Color { homeColor(\.activeCard) }
    /// 节标题墨（设计令牌 sec，与 foreground2 数值同构但语义独立，防止联动漂移）。
    static var section: Color { homeColor(\.section) }
    static var avatarActive: Color { homeColor(\.avatarActive) }
    static var avatarActiveInk: Color { homeColor(\.avatarActiveInk) }
    static var avatarIdle: Color { homeColor(\.avatarIdle) }
    static var avatarIdleInk: Color { homeColor(\.avatarIdleInk) }
    /// 全 App 唯一彩色：琥珀金 FAB 底 / 齿轮 / 激活态。与用户可选 accent 解耦，恒定 #B9863A。
    static var fab: Color { homeColor(\.fab) }
    static var fabInk: Color { homeColor(\.fabInk) }
    /// focus-visible 焦点环。
    static let focusRing = Color(hex: 0xB9863A, alpha: 0.55)
    /// 首页控制层玻璃配方（仅搜索胶囊钮/展开搜索条/齿轮按钮三个控件，设计 §2）。
    /// 浅色（含暖纸）：白 .78→.58 纵向渐变；深色：白 .14→.08。
    /// 描边 .5px 白 .5（深色 .16）、顶部内高光白 .9（深色 .16）、投影 rgba(40,36,28) .10/.06（深色黑 .30/.22）。
    static var homeGlassTop: Color { homeGlassWhite(\.glassTopAlpha) }
    static var homeGlassBottom: Color { homeGlassWhite(\.glassBottomAlpha) }
    static var homeGlassEdge: Color { homeGlassWhite(\.glassEdgeAlpha) }
    static var homeGlassHighlight: Color { homeGlassWhite(\.glassHighlightAlpha) }
    static var homeGlassShadowAmbient: Color { homeColor(\.glassShadow, alpha: \.glassShadowAmbientAlpha) }
    static var homeGlassShadowContact: Color { homeColor(\.glassShadow, alpha: \.glassShadowContactAlpha) }
    /// 贴身接触线投影（卡片「坐」在画布上的关键，不要飘）。
    static var cardShadowContact: Color { homeColor(\.shadowContact, alpha: \.shadowContactAlpha) }
    /// 弱环境光投影。
    static var cardShadowAmbient: Color { homeColor(\.shadowAmbient, alpha: \.shadowAmbientAlpha) }

    /// 环境光投影的几何随主题变化（浅色 0 5px 14px -6px，深色 0 8px 20px -8px），
    /// 颜色已通过上面的动态令牌解析，这里只提供几何。
    static func cardShadowAmbientGeometry(for colorScheme: ColorScheme) -> (radius: CGFloat, y: CGFloat) {
        colorScheme == .dark ? (10, 8) : (7, 5)
    }

    private struct AmberHomeTokens {
        let sep, press, hoverCard, activeCard: UInt32
        let sepAlpha, pressAlpha: Double
        let section: UInt32
        let avatarActive, avatarActiveInk, avatarIdle, avatarIdleInk: UInt32
        let fab, fabInk: UInt32
        let shadowContact, shadowAmbient: UInt32
        let shadowContactAlpha, shadowAmbientAlpha: Double
        let glassTopAlpha, glassBottomAlpha, glassEdgeAlpha, glassHighlightAlpha: Double
        let glassShadow: UInt32
        let glassShadowAmbientAlpha, glassShadowContactAlpha: Double
    }

    // 默认主题（中性暖灰 light）
    private static let homeNeutral = AmberHomeTokens(
        sep: 0x161410, press: 0x463A28, hoverCard: 0xF0EEEA, activeCard: 0xEFE9DF,
        sepAlpha: 0.045, pressAlpha: 0.06,
        section: 0x55524D,
        avatarActive: 0xE8DDC6, avatarActiveInk: 0x6F5019, avatarIdle: 0xEDEBE7, avatarIdleInk: 0x8F8B85,
        fab: 0xB9863A, fabInk: 0x231602,
        shadowContact: 0x3A342C, shadowAmbient: 0x3A342C,
        shadowContactAlpha: 0.09, shadowAmbientAlpha: 0.05,
        glassTopAlpha: 0.78, glassBottomAlpha: 0.58, glassEdgeAlpha: 0.5, glassHighlightAlpha: 0.9,
        glassShadow: 0x28241C, glassShadowAmbientAlpha: 0.10, glassShadowContactAlpha: 0.06
    )
    // 暖纸（投影偏暖棕）
    private static let homePaper = AmberHomeTokens(
        sep: 0x261E14, press: 0x594223, hoverCard: 0xF7F1E6, activeCard: 0xF4EAD8,
        sepAlpha: 0.05, pressAlpha: 0.055,
        section: 0x5B5449,
        avatarActive: 0xEADCBC, avatarActiveInk: 0x6F5019, avatarIdle: 0xF0EBE2, avatarIdleInk: 0x918A80,
        fab: 0xB9863A, fabInk: 0x231602,
        shadowContact: 0x4C3B22, shadowAmbient: 0x4C3B22,
        shadowContactAlpha: 0.09, shadowAmbientAlpha: 0.06,
        glassTopAlpha: 0.78, glassBottomAlpha: 0.58, glassEdgeAlpha: 0.5, glassHighlightAlpha: 0.9,
        glassShadow: 0x28241C, glassShadowAmbientAlpha: 0.10, glassShadowContactAlpha: 0.06
    )
    // 深色（玻璃改 10% 白、投影改黑系，accent 墨色提亮一档保证 ≥4.5:1）
    private static let homeDark = AmberHomeTokens(
        sep: 0xFFFFFF, press: 0xFFFFFF, hoverCard: 0x29262D, activeCard: 0x302A25,
        sepAlpha: 0.055, pressAlpha: 0.055,
        section: 0xC3BEC5,
        avatarActive: 0x443824, avatarActiveInk: 0xE0BA72, avatarIdle: 0x2B2930, avatarIdleInk: 0xAAA5AD,
        fab: 0xB9863A, fabInk: 0x211402,
        shadowContact: 0x000000, shadowAmbient: 0x000000,
        shadowContactAlpha: 0.58, shadowAmbientAlpha: 0.76,
        glassTopAlpha: 0.14, glassBottomAlpha: 0.08, glassEdgeAlpha: 0.16, glassHighlightAlpha: 0.16,
        glassShadow: 0x000000, glassShadowAmbientAlpha: 0.30, glassShadowContactAlpha: 0.22
    )

    private static func homeTokens(for paper: AmberThemeRuntime.Paper, dark: Bool) -> AmberHomeTokens {
        switch paper {
        case .neutral:
            return dark ? homeDark : homeNeutral
        case .paper:
            return dark ? homeDark : homePaper
        case .garnet, .ochre, .turmeric, .magenta, .lotus:
            // 沉浸式单色画布目前是隐藏的占位主题：从各自调色板派生中性令牌，
            // 重新启用时按 E 版同构关系补一套实测值即可。
            let palette = dark ? paper.darkPalette : paper.lightPalette
            return AmberHomeTokens(
                sep: palette.foreground, press: palette.foreground,
                hoverCard: palette.surface2, activeCard: palette.surface2,
                sepAlpha: 0.18, pressAlpha: 0.08,
                section: palette.foreground2,
                avatarActive: palette.surface2, avatarActiveInk: palette.foreground,
                avatarIdle: palette.surface2, avatarIdleInk: palette.muted,
                fab: 0xB9863A, fabInk: 0x231602,
                shadowContact: 0x000000, shadowAmbient: 0x000000,
                shadowContactAlpha: 0.25, shadowAmbientAlpha: 0.30,
                glassTopAlpha: 0.14, glassBottomAlpha: 0.08, glassEdgeAlpha: 0.16, glassHighlightAlpha: 0.16,
                glassShadow: 0x000000, glassShadowAmbientAlpha: 0.30, glassShadowContactAlpha: 0.22
            )
        }
    }

    /// 玻璃用的动态白（alpha 随主题表解析）。
    private static func homeGlassWhite(_ alpha: KeyPath<AmberHomeTokens, Double>) -> Color {
        let paper = AmberThemeRuntime.shared.paper
        return Color(uiColor: UIColor { trait in
            let tokens = homeTokens(for: paper, dark: trait.userInterfaceStyle == .dark)
            return UIColor(hex: 0xFFFFFF, alpha: tokens[keyPath: alpha])
        })
    }

    private static func homeColor(
        _ key: KeyPath<AmberHomeTokens, UInt32>,
        alpha: KeyPath<AmberHomeTokens, Double>? = nil
    ) -> Color {
        let paper = AmberThemeRuntime.shared.paper
        return Color(uiColor: UIColor { trait in
            let tokens = homeTokens(for: paper, dark: trait.userInterfaceStyle == .dark)
            return UIColor(hex: tokens[keyPath: key], alpha: alpha.map { tokens[keyPath: $0] } ?? 1)
        })
    }
}

/// Persisted, observable theme state: canvas (paper vs neutral) + accent. Light/dark is handled
/// separately via `IOSAppearanceMode` → `.preferredColorScheme` (+ the dynamicProvider above).
@Observable
final class AmberThemeRuntime {
    // Read/written only from main-actor view bodies and tap handlers; the dynamicProvider color
    // closure does not touch it. nonisolated(unsafe) keeps the shared singleton accessible from
    // AmberTheme.* getters without forcing @MainActor onto all ~1500 call sites.
    nonisolated(unsafe) static let shared = AmberThemeRuntime()

    enum Paper: String, CaseIterable {
        case paper, neutral, garnet, ochre, turmeric, magenta, lotus

        /// Light-appearance palette. Neutral canvases (paper/neutral) adapt to system dark;
        /// the immersive single-hue canvases keep their color in both appearances.
        var lightPalette: AmberPalette {
            switch self {
            case .paper: AmberTheme.paperLight
            case .neutral: AmberTheme.neutralLight
            case .garnet: AmberTheme.garnetPalette
            case .ochre: AmberTheme.ochrePalette
            case .turmeric: AmberTheme.turmericPalette
            case .magenta: AmberTheme.magentaPalette
            case .lotus: AmberTheme.lotusPalette
            }
        }

        var darkPalette: AmberPalette {
            switch self {
            case .paper, .neutral: AmberTheme.darkPalette
            case .garnet: AmberTheme.garnetPalette
            case .ochre: AmberTheme.ochrePalette
            case .turmeric: AmberTheme.turmericPalette
            case .magenta: AmberTheme.magentaPalette
            case .lotus: AmberTheme.lotusPalette
            }
        }

        var displayName: String {
            switch self {
            case .paper: "暖纸"
            case .neutral: "暖灰"
            case .garnet: "绛红"
            case .ochre: "赭橙"
            case .turmeric: "姜黄"
            case .magenta: "品红"
            case .lotus: "藕荷"
            }
        }

        var isImmersive: Bool {
            switch self {
            case .paper, .neutral: false
            default: true
            }
        }
    }

    var paper: Paper { didSet { UserDefaults.standard.set(paper.rawValue, forKey: Keys.paper) } }
    var accentHex: UInt32 { didSet { UserDefaults.standard.set(Int(accentHex), forKey: Keys.accent) } }
    var accentInkHex: UInt32 { didSet { UserDefaults.standard.set(Int(accentInkHex), forKey: Keys.accentInk) } }

    private enum Keys {
        static let paper = "app.amber.ios.theme.paper"
        static let accent = "app.amber.ios.theme.accentHex"
        static let accentInk = "app.amber.ios.theme.accentInkHex"
    }

    private init() {
        let d = UserDefaults.standard
        // 默认主题 = 中性暖灰 × 琥珀金（E 版定稿）；用户显式选择过的偏好仍以持久化值为准。
        paper = Paper(rawValue: d.string(forKey: Keys.paper) ?? "") ?? .neutral
        accentHex = (d.object(forKey: Keys.accent) as? Int).map { UInt32($0) } ?? AmberAccentOption.amberGold.accentHex
        accentInkHex = (d.object(forKey: Keys.accentInk) as? Int).map { UInt32($0) } ?? AmberAccentOption.amberGold.inkHex
    }

    func apply(_ option: AmberAccentOption) {
        accentHex = option.accentHex
        accentInkHex = option.inkHex
    }
}

/// Authoritative accent set + paired ink (redesign/aa-base.jsx ACCENT_INK). High-luminance hues
/// (sage, amber gold) pair with dark ink; the rest with white — never a blanket white.
enum AmberAccentOption: String, CaseIterable, Identifiable {
    case amberGold, terracotta, sage, mistBlue, wisteria, rose, ink

    var id: String { rawValue }

    var accentHex: UInt32 {
        switch self {
        case .amberGold:  0xB9863A
        case .terracotta: 0xB8623A
        case .sage:       0x5E9C6E
        case .mistBlue:   0x4F86D6
        case .wisteria:   0x9277C4
        case .rose:       0xC2607A
        case .ink:        0x222226
        }
    }

    var inkHex: UInt32 {
        switch self {
        case .sage:      0x0F150E
        case .amberGold: 0x231602
        default:         0xFFFFFF
        }
    }

    var displayName: String {
        switch self {
        case .terracotta: "陶土"
        case .sage:       "鼠尾草绿"
        case .mistBlue:   "雾蓝"
        case .wisteria:   "紫藤"
        case .rose:       "玫红"
        case .amberGold:  "琥珀金"
        case .ink:        "墨黑"
        }
    }
}

enum IOSAppearancePreferenceKeys {
    static let mode = "app.amber.ios.appearance.mode"
}

enum IOSAppearanceMode: String, CaseIterable, Identifiable {
    case light
    case dark
    case system

    var id: String { rawValue }

    var title: String {
        switch self {
        case .light: "浅色"
        case .dark: "深色"
        case .system: "跟随系统"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .light: .light
        case .dark: .dark
        case .system: nil
        }
    }
}

enum IOSDisplayPreferenceKeys {
    static let fontScale = "app.amber.ios.display.fontScale"
    static let chatFont = "app.amber.ios.display.chatFont"
    static let agentName = "app.amber.ios.display.agentName"
    static let followGeneration = "app.amber.ios.display.followGeneration"
    static let activityIslandEdgeGlow = "app.amber.ios.display.activityIslandEdgeGlow"
    static let streamingBlockMarkdown = "app.amber.ios.display.streamingBlockMarkdown"
    static let coalescedTextBlocks = "app.amber.ios.display.coalescedTextBlocks"
}

enum IOSChatFont: String, CaseIterable, Identifiable {
    case `default`
    case serif
    case monospace

    var id: String { rawValue }

    var title: String {
        switch self {
        case .default: "默认"
        case .serif: "衬线体"
        case .monospace: "等宽字体"
        }
    }

    var design: Font.Design {
        switch self {
        case .default: .default
        case .serif: .serif
        case .monospace: .monospaced
        }
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            red: Double((hex >> 16) & 0xff) / 255.0,
            green: Double((hex >> 8) & 0xff) / 255.0,
            blue: Double(hex & 0xff) / 255.0,
            opacity: alpha
        )
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            red: CGFloat((hex >> 16) & 0xff) / 255.0,
            green: CGFloat((hex >> 8) & 0xff) / 255.0,
            blue: CGFloat(hex & 0xff) / 255.0,
            alpha: CGFloat(alpha)
        )
    }
}

enum AmberHapticEvent {
    case lightImpact
    case mediumImpact
    case selection
    case success
    case warning
    case error
}

enum AmberHaptics {
    @MainActor
    static func trigger(_ event: AmberHapticEvent) {
        switch event {
        case .lightImpact:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .mediumImpact:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .selection:
            UISelectionFeedbackGenerator().selectionChanged()
        case .success:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .warning:
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case .error:
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }
}

struct AmberPressFeedbackStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.96
    var haptic: AmberHapticEvent? = .lightImpact

    func makeBody(configuration: Configuration) -> some View {
        AmberPressFeedbackBody(
            configuration: configuration,
            pressedScale: pressedScale,
            haptic: haptic
        )
    }
}

private struct AmberPressFeedbackBody: View {
    let configuration: ButtonStyleConfiguration
    let pressedScale: CGFloat
    let haptic: AmberHapticEvent?

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var wasPressed = false

    var body: some View {
        configuration.label
            .scaleEffect(isEnabled && configuration.isPressed ? pressedScale : 1)
            .animation(
                reduceMotion ? nil : .spring(response: 0.24, dampingFraction: 0.72),
                value: configuration.isPressed
            )
            .onChange(of: configuration.isPressed) { _, isPressed in
                defer { wasPressed = isPressed }
                guard isEnabled, isPressed, !wasPressed, let haptic else { return }
                AmberHaptics.trigger(haptic)
            }
    }
}

private struct AmberGlassModifier: ViewModifier {
    let cornerRadius: CGFloat
    let interactive: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)

        if #available(iOS 26.0, *) {
            if interactive {
                content
                    .background(AmberTheme.glass.opacity(0.35), in: shape)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
            } else {
                content
                    .background(AmberTheme.glass.opacity(0.35), in: shape)
                    .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay {
                    shape
                        .stroke(.white.opacity(0.65), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.10), radius: 12, y: 2)
        }
    }
}

private struct AmberProminentGlassModifier: ViewModifier {
    let cornerRadius: CGFloat
    let tint: Color
    let interactive: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)

        if #available(iOS 26.0, *) {
            // Solid accent fill + a full-tint glass sheen so prominent buttons (the new-chat FAB,
            // prominent icon/pill buttons) actually read as the standard accent instead of the
            // washed-out 0.24/0.34-opacity tint they had before.
            if interactive {
                content
                    .background(tint, in: shape)
                    .glassEffect(.regular.tint(tint).interactive(), in: .rect(cornerRadius: cornerRadius))
            } else {
                content
                    .background(tint, in: shape)
                    .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            content
                .background(tint, in: shape)
                .shadow(color: tint.opacity(0.32), radius: 18, y: 4)
        }
    }
}

extension View {
    func amberGlass(cornerRadius: CGFloat, interactive: Bool = true) -> some View {
        modifier(AmberGlassModifier(cornerRadius: cornerRadius, interactive: interactive))
    }

    func amberProminentGlass(
        cornerRadius: CGFloat,
        tint: Color = AmberTheme.accent,
        interactive: Bool = true
    ) -> some View {
        modifier(AmberProminentGlassModifier(cornerRadius: cornerRadius, tint: tint, interactive: interactive))
    }
}

struct AmberGlassGroup<Content: View>: View {
    let spacing: CGFloat
    let content: Content

    init(spacing: CGFloat = 16, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) {
                content
            }
        } else {
            content
        }
    }
}

struct AmberGlassIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat = 32
    var symbolSize: CGFloat = 15
    var tint: Color = AmberTheme.foreground2
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            styledLabel
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.92, haptic: .lightImpact))
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder
    private var styledLabel: some View {
        if prominent {
            iconLabel
                .amberProminentGlass(cornerRadius: size / 2, tint: tint)
        } else {
            iconLabel
                .amberGlass(cornerRadius: size / 2)
        }
    }

    private var iconLabel: some View {
        Image(systemName: systemImage)
            .font(.system(size: symbolSize, weight: .semibold))
            .foregroundStyle(prominent ? Color.white : tint)
            .frame(width: size, height: size)
            .contentShape(Circle())
    }
}

struct AmberGlassTextChip: View {
    let title: String
    var isSelected = false
    var tint: Color = AmberTheme.accent
    var height: CGFloat = 30
    var horizontalPadding: CGFloat = 12
    var fillsWidth = false
    var font: Font = .caption.weight(.semibold)

    var body: some View {
        if isSelected {
            label
                .foregroundStyle(Color.white)
                .amberProminentGlass(cornerRadius: height / 2, tint: tint)
        } else {
            label
                .foregroundStyle(AmberTheme.foreground2)
                .amberGlass(cornerRadius: height / 2)
        }
    }

    private var label: some View {
        Text(title)
            .font(font)
            .lineLimit(1)
            .minimumScaleFactor(0.78)
            .frame(maxWidth: fillsWidth ? .infinity : nil)
            .frame(height: height)
            .padding(.horizontal, horizontalPadding)
    }
}

struct AmberGlassCircleButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat = 44
    var symbolSize: CGFloat = 17
    var tint: Color = AmberTheme.foreground2
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            styledLabel
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.92, haptic: .lightImpact))
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder
    private var styledLabel: some View {
        if #available(iOS 26.0, *) {
            iconLabel
                .background(AmberTheme.glass.opacity(0.16), in: Circle())
                .glassEffect(.regular.interactive(), in: Circle())
        } else {
            iconLabel
                .background(.ultraThinMaterial, in: Circle())
                .overlay {
                    Circle()
                        .stroke(AmberTheme.border.opacity(0.28), lineWidth: 0.5)
                }
        }
    }

    private var iconLabel: some View {
        Image(systemName: systemImage)
            .font(.system(size: symbolSize, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .contentShape(Circle())
    }
}

struct AmberSectionLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(AmberTheme.muted)
            .textCase(.uppercase)
            .tracking(0.4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 7)
            .accessibilityAddTraits(.isHeader)
    }
}

struct AmberFormGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            content
        }
        .background(AmberTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
    }
}

struct AmberFormRow: View {
    let systemImage: String?
    let iconColor: Color
    let title: String
    let subtitle: String?
    let trailing: String?
    let showsChevron: Bool
    let action: (() -> Void)?

    init(
        systemImage: String? = nil,
        iconColor: Color = AmberTheme.accent,
        title: String,
        subtitle: String? = nil,
        trailing: String? = nil,
        showsChevron: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.systemImage = systemImage
        self.iconColor = iconColor
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing
        self.showsChevron = showsChevron
        self.action = action
    }

    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: 12) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(iconColor)
                        .frame(width: 28, height: 28)
                }

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

                if let trailing {
                    Text(trailing)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                }

                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: action == nil ? 1 : 0.985, haptic: .selection))
        .disabled(action == nil)
    }
}

/// 会话列表右上角的账户头像:有自定义头像则显示图片,否则显示昵称首字母。
/// 出现时加载,并监听 `.accountAvatarChanged` 在换头像后即时刷新。
private struct HomeAccountAvatar: View {
    let initial: String
    var size: CGFloat = 40
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else {
                Text(initial)
                    .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                    .foregroundStyle(AmberTheme.avatarIdleInk)
                    .frame(width: size, height: size)
                    .background(AmberTheme.avatarIdle, in: Circle())
            }
        }
        .contentShape(Circle())
        .onAppear { image = AccountAvatarStore.load() }
        .onReceive(NotificationCenter.default.publisher(for: .accountAvatarChanged)) { _ in
            image = AccountAvatarStore.load()
        }
    }
}

enum HomeConversationIcon {
    static let fallback: HomePhosphor = .chatCircle

    /// 按会话标题语义取 Phosphor fill 实心字形（设计 §4 九行映射 + 置顶图钉 + 实心气泡回退）。
    static func icon(forTitle title: String, isPinned: Bool) -> HomePhosphor {
        if isPinned { return .pushPin }
        let normalized = title.lowercased()
        let mappings: [(HomePhosphor, [String])] = [
            (.moon, ["月光", "月亮", "夜色", "夜晚", "晚安", "晚上", "星空", "半夜", "晚年", "梦"]),
            (.wine, ["酒", "酿", "醉", "干杯"]),
            (.sword, ["剑", "武侠", "江湖", "打仗", "战争", "战役", "战术", "兵法", "武将", "将军", "军队"]),
            (.crown, ["皇帝", "帝王", "王冠", "君主", "国王", "女王", "皇后", "皇室", "王位", "登基", "在位"]),
            (.list, ["顺序", "排行", "清单", "列表", "目录", "步骤", "流程", "时间表", "年表"]),
            (.musicNotes, ["音乐", "歌曲", "歌单", "bgm", "配乐", "旋律", "专辑", "歌手", "歌词", "钢琴", "吉他"]),
            (.mapPin, ["在哪", "哪里", "哪儿", "地址", "地图", "路线", "都城", "城市", "旅行", "旅游", "景点"]),
            (.pill, ["药", "症状", "治疗", "医院", "看病", "疾病", "感冒", "发烧", "痛风", "健康"]),
            (.scales, ["谁", "对比", "比较", "哪个好", "排名", "评价", "厉害", "更强"])
        ]
        return mappings.first(where: { _, words in words.contains { normalized.contains($0) } })?.0 ?? fallback
    }
}

struct HomeNovelProjectRef: Equatable {
    let id: NovelProjectID
    let name: String
    let updatedAt: Date
    let isDegraded: Bool
    let isRunning: Bool

    init(
        id: NovelProjectID,
        name: String,
        updatedAt: Date,
        isDegraded: Bool,
        isRunning: Bool = false
    ) {
        self.id = id
        self.name = name
        self.updatedAt = updatedAt
        self.isDegraded = isDegraded
        self.isRunning = isRunning
    }

    init(_ summary: NovelProjectSummary) {
        id = summary.id
        name = summary.name
        updatedAt = summary.updatedAt
        isDegraded = summary.isDegraded
        isRunning = summary.hasRunningRun
    }
}

struct HomeCouncilTaskRef: Equatable {
    let id: String
    let title: String
    let status: IOSAdvancedTaskStatus
    let updatedAt: Date
    let canContinue: Bool

    init(
        id: String,
        title: String,
        status: IOSAdvancedTaskStatus,
        updatedAt: Date,
        canContinue: Bool
    ) {
        self.id = id
        self.title = title
        self.status = status
        self.updatedAt = updatedAt
        self.canContinue = canContinue
    }

    init(_ context: CouncilHomeResumeContext) {
        id = context.id
        title = context.title
        status = context.status
        updatedAt = context.updatedAt
        canContinue = context.canContinue
    }
}

struct HomeDeepReadTaskRef: Equatable {
    let id: String
    let title: String
    let status: IOSDeepReadTaskStatus
    let updatedAt: Date
    let workspaceSyncFailed: String?

    init(
        id: String,
        title: String,
        status: IOSDeepReadTaskStatus,
        updatedAt: Date,
        workspaceSyncFailed: String?
    ) {
        self.id = id
        self.title = title
        self.status = status
        self.updatedAt = updatedAt
        self.workspaceSyncFailed = workspaceSyncFailed
    }

    init(_ task: IOSDeepReadTask) {
        id = task.id
        title = task.title
        status = task.status
        updatedAt = Date(timeIntervalSince1970: TimeInterval(task.updatedAt) / 1_000)
        workspaceSyncFailed = task.workspaceSyncFailed
    }
}

struct HomeMiniAppRef: Equatable {
    let id: String
    let title: String
    let latestVersionCreatedAt: Date
    let lastRunAt: Date?
}

struct HomeImageGenerationRef: Equatable {
    let id: String
    let conversationID: String
    let messageID: String
    let toolCallID: String
    let prompt: String
    let state: ChatImageGenerationResumeState
    let updatedAt: Date

    init(
        id: String,
        conversationID: String,
        messageID: String,
        toolCallID: String,
        prompt: String,
        state: ChatImageGenerationResumeState,
        updatedAt: Date
    ) {
        self.id = id
        self.conversationID = conversationID
        self.messageID = messageID
        self.toolCallID = toolCallID
        self.prompt = prompt
        self.state = state
        self.updatedAt = updatedAt
    }

    init(_ context: ChatImageGenerationResumeContext) {
        id = context.id
        conversationID = context.conversationID
        messageID = context.messageID
        toolCallID = context.toolCallID
        prompt = context.prompt
        state = context.state
        updatedAt = context.updatedAt
    }
}

/// 首页按压态 ButtonStyle：scale 回弹 + 把 isPressed 通过 Binding 回传，
/// 让行内容可以成对切换按压垫底/前景（设计 §5：hover/按压前景背景成对定义）。
private struct HomePressStateStyle: ButtonStyle {
    @Binding var pressed: Bool
    let scale: CGFloat
    var haptic: AmberHapticEvent? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(
                reduceMotion ? nil : .spring(response: 0.24, dampingFraction: 0.72),
                value: configuration.isPressed
            )
            .onChange(of: configuration.isPressed) { _, isPressed in
                pressed = isPressed
                guard isPressed, let haptic else { return }
                AmberHaptics.trigger(haptic)
            }
            // 行在按压中被回收/重建时，避免 pressed 残留导致按压垫底卡住。
            .onDisappear { pressed = false }
    }
}

struct HomeContinueCardModel: Equatable {
    enum Feature: Equatable {
        case deepRead
        case novel
        case council
        case miniApp
        case imageGeneration

        var icon: HomePhosphor {
            switch self {
            case .deepRead: .bookOpen
            case .novel: .notebook
            case .council: .chatCircleDots
            case .miniApp: .squaresFour
            case .imageGeneration: .imageSquare
            }
        }
    }

    enum Destination: Equatable {
        case openCouncil
        case deepReadTask(String)
        case resumeProject(NovelProjectID)
        case miniAppRunner(String)
        case generatedImage(ChatMessageAnchor)
    }

    private enum Priority: Int {
        case draft = 1
        case readyResult = 2
        case recoverable = 3
        case active = 4
        case actionRequired = 5
    }

    private struct Candidate {
        let stableID: String
        let priority: Priority
        let updatedAt: Date
        let model: HomeContinueCardModel
    }

    let feature: Feature
    let title: String
    let meta: String
    let ctaTitle: String
    let destination: Destination

    static func resolve(
        novelProjects: [HomeNovelProjectRef] = [],
        councilTask: HomeCouncilTaskRef? = nil,
        deepReadTasks: [HomeDeepReadTaskRef] = [],
        miniApps: [HomeMiniAppRef] = [],
        imageGeneration: HomeImageGenerationRef? = nil,
        now: Date = Date()
    ) -> HomeContinueCardModel? {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated

        var candidates = novelProjects.compactMap { project -> Candidate? in
            guard !project.isDegraded else { return nil }
            let state = project.isRunning ? "生成中" : formatter.localizedString(
                for: project.updatedAt,
                relativeTo: now
            )
            return Candidate(
                stableID: "novel:\(project.id)",
                priority: project.isRunning ? .active : .draft,
                updatedAt: project.updatedAt,
                model: .init(
                    feature: .novel,
                    title: "小说创作",
                    meta: "《\(project.name)》· \(state)",
                    ctaTitle: project.isRunning ? "查看" : "继续",
                    destination: .resumeProject(project.id)
                )
            )
        }

        if let councilTask,
           let priority = councilPriority(for: councilTask) {
            let state = councilStateTitle(for: councilTask)
            candidates.append(Candidate(
                stableID: "council:\(councilTask.id)",
                priority: priority,
                updatedAt: councilTask.updatedAt,
                model: .init(
                    feature: .council,
                    title: "模型议会",
                    meta: "\(councilTask.title) · \(state)",
                    ctaTitle: priority == .actionRequired ? "处理" : (priority == .active ? "查看" : "继续"),
                    destination: .openCouncil
                )
            ))
        }

        candidates.append(contentsOf: deepReadTasks.compactMap { task -> Candidate? in
            let priority: Priority
            let state: String
            let ctaTitle: String
            if task.status == .succeeded, task.workspaceSyncFailed != nil {
                priority = .actionRequired
                state = "Workspace 同步失败"
                ctaTitle = "处理"
            } else {
                switch task.status {
                case .queued, .running:
                    priority = .active
                    state = task.status.title
                    ctaTitle = "查看"
                case .failed, .unsupported:
                    priority = .recoverable
                    state = "\(task.status.title) · 可重试"
                    ctaTitle = "重试"
                case .succeeded:
                    return nil
                }
            }
            return Candidate(
                stableID: "deep-read:\(task.id)",
                priority: priority,
                updatedAt: task.updatedAt,
                model: .init(
                    feature: .deepRead,
                    title: "深度阅读",
                    meta: "\(task.title) · \(state)",
                    ctaTitle: ctaTitle,
                    destination: .deepReadTask(task.id)
                )
            )
        })

        candidates.append(contentsOf: miniApps.compactMap { app -> Candidate? in
            if let lastRunAt = app.lastRunAt,
               app.latestVersionCreatedAt <= lastRunAt {
                return nil
            }
            let state = app.lastRunAt == nil ? "已生成，尚未打开" : "新版本尚未打开"
            return Candidate(
                stableID: "mini-app:\(app.id)",
                priority: .draft,
                updatedAt: app.latestVersionCreatedAt,
                model: .init(
                    feature: .miniApp,
                    title: "小应用",
                    meta: "「\(app.title)」· \(state)",
                    ctaTitle: "打开",
                    destination: .miniAppRunner(app.id)
                )
            )
        })

        if let imageGeneration {
            let isCompleted = imageGeneration.state == .completed
            let prompt = imageGeneration.prompt.isEmpty ? "未命名图片" : imageGeneration.prompt
            candidates.append(Candidate(
                stableID: "image:\(imageGeneration.id)",
                priority: isCompleted ? .readyResult : .active,
                updatedAt: imageGeneration.updatedAt,
                model: .init(
                    feature: .imageGeneration,
                    title: "AI 生图",
                    meta: "\(isCompleted ? "图片已生成" : "正在生成") · \(prompt)",
                    ctaTitle: isCompleted ? "查看图片" : "查看",
                    destination: .generatedImage(
                        ChatMessageAnchor(
                            conversationID: imageGeneration.conversationID,
                            messageID: imageGeneration.messageID,
                            toolCallID: imageGeneration.toolCallID
                        )
                    )
                )
            ))
        }

        return candidates.sorted { lhs, rhs in
            if lhs.priority != rhs.priority { return lhs.priority.rawValue > rhs.priority.rawValue }
            if lhs.updatedAt != rhs.updatedAt { return lhs.updatedAt > rhs.updatedAt }
            return lhs.stableID < rhs.stableID
        }.first?.model
    }

    private static func councilPriority(for task: HomeCouncilTaskRef) -> Priority? {
        switch task.status {
        case .approvalRequired:
            .actionRequired
        case .queued, .running:
            .active
        case .failed, .cancelled, .timedOut, .interrupted:
            task.canContinue ? .recoverable : nil
        case .completed:
            nil
        }
    }

    private static func councilStateTitle(for task: HomeCouncilTaskRef) -> String {
        switch task.status {
        case .failed, .cancelled, .timedOut, .interrupted:
            "\(task.status.title) · 可继续"
        default:
            task.status.title
        }
    }
}

private enum HomeCardSlice { case top, middle, bottom, single }

private struct HomeSliceShape: Shape {
    let slice: HomeCardSlice
    func path(in rect: CGRect) -> Path {
        let radius: CGFloat = 22
        switch slice {
        case .top: return UnevenRoundedRectangle(topLeadingRadius: radius, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: radius, style: .continuous).path(in: rect)
        case .bottom: return UnevenRoundedRectangle(topLeadingRadius: 0, bottomLeadingRadius: radius, bottomTrailingRadius: radius, topTrailingRadius: 0, style: .continuous).path(in: rect)
        case .single: return RoundedRectangle(cornerRadius: radius, style: .continuous).path(in: rect)
        case .middle: return Rectangle().path(in: rect)
        }
    }
}

private struct HomeEmptyCard: View {
    let title: String
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        let ambient = AmberTheme.cardShadowAmbientGeometry(for: colorScheme)
        Text(title).font(.system(size: 13, weight: .regular)).foregroundStyle(AmberTheme.muted)
            .frame(maxWidth: .infinity, minHeight: 144)
            .background(HomeSliceShape(slice: .single).fill(AmberTheme.card))
            .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
            .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
            .padding(.horizontal, 16)
    }
}

private struct HomeShortcut: View {
    let title: String
    let icon: HomePhosphor
    let action: () -> Void
    @State private var hovering = false
    @State private var pressed = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .caption2) private var shortcutLabelSize: CGFloat = 11
    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                HomePhosphorIcon(icon, size: 20)
                Text(title)
                    .font(.system(size: shortcutLabelSize, weight: .semibold))
                    .tracking(0.11)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(hovering || pressed ? AmberTheme.foreground : AmberTheme.muted)
            .frame(
                minWidth: dynamicTypeSize.isAccessibilitySize ? 144 : nil,
                maxWidth: dynamicTypeSize.isAccessibilitySize ? 144 : .infinity,
                minHeight: 44
            )
            .background(hovering || pressed ? AmberTheme.press : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(HomePressStateStyle(pressed: $pressed, scale: 0.92, haptic: .selection))
        .onHover { hovering = $0 }
    }
}

private struct HomeContinueButton: View {
    let model: HomeContinueCardModel
    let action: (HomeContinueCardModel.Destination) -> Void
    @State private var hovering = false
    @State private var pressed = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .subheadline) private var continueTitleSize: CGFloat = 15
    @ScaledMetric(relativeTo: .caption2) private var continueMetaSize: CGFloat = 11
    @ScaledMetric(relativeTo: .caption) private var continueCTAFontSize: CGFloat = 13
    var body: some View {
        Button { action(model.destination) } label: {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top, spacing: 14) {
                            featureIcon
                            titleBlock
                        }
                        continueCTA(expands: true)
                    }
                } else {
                    HStack(spacing: 14) {
                        featureIcon
                        titleBlock
                        continueCTA(expands: false)
                    }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 18)
            .background(pressed ? AmberTheme.press : (hovering ? AmberTheme.hoverCard : Color.clear))
        }
        .buttonStyle(HomePressStateStyle(pressed: $pressed, scale: 0.985, haptic: .lightImpact))
        .onHover { hovering = $0 }
        .accessibilityLabel("\(model.title)，\(model.meta)，\(model.ctaTitle)")
    }

    private var featureIcon: some View {
        HomePhosphorIcon(model.feature.icon, size: 22)
            .foregroundStyle(AmberTheme.avatarActiveInk)
            .frame(width: 44, height: 44)
            .background(
                AmberTheme.avatarActive,
                in: RoundedRectangle(cornerRadius: 15, style: .continuous)
            )
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(model.title)
                .font(.system(size: continueTitleSize, weight: .semibold))
                .tracking(0.075)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
            Text(model.meta)
                .font(.system(size: continueMetaSize, weight: .regular))
                .tracking(0.11)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 2)
        }
        .foregroundStyle(AmberTheme.foreground)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func continueCTA(expands: Bool) -> some View {
        let label = Text(model.ctaTitle)
            .font(.system(size: continueCTAFontSize, weight: .semibold))
            .tracking(0.26)
            .foregroundStyle(AmberTheme.background)
        if expands {
            label
                .padding(.vertical, 7)
                .padding(.horizontal, 18)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(
                    hovering || pressed ? AmberTheme.foreground2 : AmberTheme.foreground,
                    in: Capsule()
                )
        } else {
            label
                .padding(.vertical, 7)
                .padding(.horizontal, 18)
                .frame(minHeight: 32)
                .background(
                    hovering || pressed ? AmberTheme.foreground2 : AmberTheme.foreground,
                    in: Capsule()
                )
                .fixedSize(horizontal: true, vertical: false)
        }
    }
}

private struct HomeCascade: ViewModifier {
    let delay: Double
    /// false 时跳过动画直接呈现：级联是一次性入场，List 行回收/搜索重建不得重播。
    var enabled: Bool = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    func body(content: Content) -> some View {
        content
            .opacity(appeared || reduceMotion ? 1 : 0)
            .offset(y: appeared || reduceMotion ? 0 : 8)
            .onAppear {
                guard !reduceMotion else { appeared = true; return }
                guard enabled else { appeared = true; return }
                withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.48).delay(delay)) { appeared = true }
            }
    }
}

private extension View {
    func homeCascade(delay: Double, enabled: Bool = true) -> some View { modifier(HomeCascade(delay: delay, enabled: enabled)) }
}

/// 首页控制层专用玻璃（设计 §1.3：仅搜索胶囊钮/展开搜索条/齿轮按钮三个控件）。
/// 配方 = 白渐变垫底（浅 .78→.58 / 深 .14→.08）+ 系统玻璃材质 + 贴身双投影；
/// 与全局 `amberGlass`（内页通用）完全隔离，内页材质不随首页设计令牌变化。
private struct HomeGlassControlModifier: ViewModifier {
    let cornerRadius: CGFloat

    private var gradient: LinearGradient {
        LinearGradient(
            colors: [AmberTheme.homeGlassTop, AmberTheme.homeGlassBottom],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        // CSS blur 28/8 ≈ SwiftUI radius 14/4；颜色与 alpha 为主题表内的设计实测值。
        if #available(iOS 26.0, *) {
            content
                .background(gradient, in: shape)
                .glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
                .shadow(color: AmberTheme.homeGlassShadowAmbient, radius: 14, y: 10)
                .shadow(color: AmberTheme.homeGlassShadowContact, radius: 4, y: 2)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .background(gradient, in: shape)
                .overlay { shape.strokeBorder(AmberTheme.homeGlassEdge, lineWidth: 0.5) }
                .overlay(alignment: .top) {
                    shape.strokeBorder(AmberTheme.homeGlassHighlight, lineWidth: 0.5)
                        .frame(height: 1)
                        .clipShape(shape)
                }
                .shadow(color: AmberTheme.homeGlassShadowAmbient, radius: 14, y: 10)
                .shadow(color: AmberTheme.homeGlassShadowContact, radius: 4, y: 2)
        }
    }
}

private extension View {
    func homeGlassControl(cornerRadius: CGFloat) -> some View {
        modifier(HomeGlassControlModifier(cornerRadius: cornerRadius))
    }
}

/// 首页齿轮钮：38 圆形玻璃 + Phosphor 实心图标（与内页 AmberGlassCircleButton 隔离）。
private struct HomeGlassCircleButton: View {
    let icon: HomePhosphor
    let accessibilityLabel: String
    var size: CGFloat = 38
    var iconSize: CGFloat = 20
    var tint: Color = AmberTheme.muted
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HomePhosphorIcon(icon, size: iconSize)
                .foregroundStyle(tint)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.92, haptic: .lightImpact))
        .homeGlassControl(cornerRadius: size / 2)
        .accessibilityLabel(accessibilityLabel)
    }
}

struct ConversationsView: View {
    let sharedSettings: IOSSharedSettingsStore
    let chatViewModel: ChatViewModel
    let councilChatViewModel: CouncilChatViewModel
    let novelCreationViewModel: NovelCreationViewModel?

    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.displayScale) private var displayScale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var searchQuery: String = ""
    @State private var deepReadStore = IOSDeepReadStore.shared
    @State private var miniAppRepository = IOSMiniAppRepository.shared
    @State private var renamingConversationId: KotlinUuid?
    @State private var renameDraft: String = ""
    @State private var deletingConversationId: KotlinUuid?
    @State private var backgroundGenerationRevision = 0
    @State private var homeImageGenerationContext: ChatImageGenerationResumeContext?
    @State private var homeContinueError: String?
    @State private var isSearchExpanded = false
    @State private var conversationNavigationTask: Task<Void, Never>?
    @AppStorage(ChatImageGenerationResumeConsumption.viewedCompletionIDKey)
    private var viewedImageGenerationID = ""
    @FocusState private var searchFocused: Bool
    @AccessibilityFocusState private var deepReadShortcutFocused: Bool
    /// 展开搜索后的延迟聚焦任务：取消/离场必须可撤销，否则 170ms 内收起会残留 FocusState。
    @State private var searchFocusTask: Task<Void, Never>?
    /// 入场级联只播放一次：最晚一级 delay .22 + 时长 .48，0.9s 后全部按已入场处理。
    @State private var cascadeComplete = false
    @ScaledMetric(relativeTo: .caption2) private var sectionLabelSize: CGFloat = 11

    /// 本地标题过滤后的会话摘要（summaries 已按 updateAt 倒序/置顶优先）。
    private var filteredSummaries: [ConversationSummary] {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return conversationStore.summaries }
        return conversationStore.summaries.filter { summary in
            // 空标题会话用占位串参与匹配，避免搜索框里全是空白行。
            let title = summary.title.isEmpty ? "新对话" : summary.title
            return title.localizedCaseInsensitiveContains(trimmed)
        }
    }

    private var homeImageGenerationScanSignature: Int {
        var hasher = Hasher()
        hasher.combine(conversationStore.backgroundContentRevision)
        hasher.combine(backgroundGenerationRevision)
        hasher.combine(chatViewModel.isLoading)
        for summary in conversationStore.summaries {
            hasher.combine(summary.id.toHexDashString())
            hasher.combine(summary.updateAt.toEpochMilliseconds())
        }
        return hasher.finalize()
    }

    var body: some View {
        GeometryReader { geometry in
        ZStack(alignment: .bottomTrailing) {
            AmberTheme.background.ignoresSafeArea()

            // 用原生 List 承载整屏，会话行才能挂 .swipeActions(Apple Music 同款左右滑动)。
            // 顶部 header/搜索/快捷区作为清空背景的 List 行铺在上面，玻璃风格不受影响:
            // .scrollContentBackground(.hidden) + 每行 .listRowBackground(.clear) 让 List 自身
            // 不画任何底色，保留 AmberTheme.background。
            List {
                header.listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden).homeCascade(delay: 0.06, enabled: !cascadeComplete)
                controlCard.listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden).homeCascade(delay: 0.10, enabled: !cascadeComplete)
                Text("会话")
                    .font(.system(size: sectionLabelSize, weight: .semibold))
                    .tracking(0.11).foregroundStyle(AmberTheme.section)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.top, 26).padding(.bottom, 12)
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden).homeCascade(delay: 0.14, enabled: !cascadeComplete)

                if filteredSummaries.isEmpty {
                    HomeEmptyCard(title: searchQuery.isEmpty ? "还没有会话" : "没有匹配的会话")
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .homeCascade(delay: 0.18, enabled: !cascadeComplete)
                } else {
                    conversationList
                }

                // 底部留白，避免最后一行被右下角悬浮「新建」按钮压住。
                Color.clear
                    .frame(height: 112)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            // 顶部原生 Liquid Glass 渐变模糊:滚动内容滑到顶端安全区时柔和虚化(iOS 26 系统效果)。
            .scrollEdgeEffectStyle(.soft, for: .top)

            Button {
                startNewConversation()
            } label: {
                ZStack {
                    Circle().fill(AmberTheme.fab)
                    RadialGradient(colors: [.white.opacity(0.30), .clear], center: UnitPoint(x: 0.31, y: 0.25), startRadius: 0, endRadius: 21)
                    Circle().strokeBorder(.white.opacity(0.22), lineWidth: 1)
                    Circle().fill(Color(hex: 0x07190D, alpha: 0.16)).blur(radius: 6).offset(y: 6).mask(Circle())
                    HomePhosphorIcon(.pencil, size: 22).foregroundStyle(AmberTheme.fabInk)
                }.frame(width: 56, height: 56)
            }
            .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.91, haptic: .lightImpact))
            // CSS 0 8px 20px ≈ SwiftUI radius 10/y 8（渲染实测与设计一致，勿按字面改 20）。
            .shadow(color: Color(hex: 0x92681E, alpha: 0.32), radius: 10, y: 8)
            .accessibilityLabel("新建聊天")
            // 设计 bottom=67 是相对屏幕底缘：无 Home Indicator 设备安全区为 0 时也要保持 67。
            .padding(.trailing, 21)
            .padding(
                .bottom,
                dynamicTypeSize.isAccessibilitySize
                    ? 12
                    : max(67 - geometry.safeAreaInsets.bottom, 12)
            )
            .homeCascade(delay: 0.22, enabled: !cascadeComplete)
        }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onReceive(NotificationCenter.default.publisher(for: .amberChatBackgroundJobDidTerminate)) { _ in
            backgroundGenerationRevision &+= 1
        }
        .onAppear {
            Task { await novelCreationViewModel?.loadProjects(restoresSelection: false) }
            guard !cascadeComplete else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { cascadeComplete = true }
        }
        .task(id: homeImageGenerationScanSignature) {
            let context = await chatViewModel.latestImageGenerationResumeContext()
            guard !Task.isCancelled else { return }
            homeImageGenerationContext = context
        }
        .onChange(of: homeContinueModel) { oldValue, newValue in
            announceHomeContinueChange(from: oldValue, to: newValue)
        }
        .onChange(of: router.path) { _, path in
            if !path.isEmpty {
                conversationNavigationTask?.cancel()
            }
        }
        .onDisappear {
            searchFocusTask?.cancel()
            conversationNavigationTask?.cancel()
        }
        .alert("无法打开任务", isPresented: Binding(
            get: { homeContinueError != nil },
            set: { if !$0 { homeContinueError = nil } }
        )) {
            Button("好") { homeContinueError = nil }
        } message: {
            Text(homeContinueError ?? "图片所在会话暂不可用。")
        }
        .alert("重命名会话", isPresented: Binding(
            get: { renamingConversationId != nil },
            set: { if !$0 { renamingConversationId = nil } }
        )) {
            TextField("会话标题", text: $renameDraft)
            Button("取消", role: .cancel) { renamingConversationId = nil }
            Button("保存") {
                if let id = renamingConversationId {
                    let trimmed = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty {
                        Task { @MainActor in
                            await conversationStore.renameConversation(id: id, title: trimmed)
                        }
                    }
                }
                renamingConversationId = nil
            }
        }
        .alert("删除会话？", isPresented: Binding(
            get: { deletingConversationId != nil },
            set: { if !$0 { deletingConversationId = nil } }
        )) {
            Button("取消", role: .cancel) { deletingConversationId = nil }
            Button("删除", role: .destructive) {
                if let id = deletingConversationId {
                    Task { @MainActor in
                        await conversationStore.deleteConversation(id: id) {
                            chatViewModel.prepareForConversationDeletion(id)
                        }
                    }
                }
                deletingConversationId = nil
            }
        } message: {
            Text("此操作不可撤销，会话内的全部消息将被删除。")
        }
    }

    private var accountInitial: String {
        let trimmed = sharedSettings.displaySetting.userNickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? "A" : trimmed).prefix(1)).uppercased()
    }

    private var header: some View {
        HStack(spacing: 10) {
            if !isSearchExpanded {
                Text("Amber")
                    .font(.system(size: 32, weight: .bold, design: .default))
                    .tracking(-0.64).foregroundStyle(AmberTheme.foreground)
                    .transition(.opacity)
            }

            Spacer()
            if isSearchExpanded {
                expandedSearch
            } else {
                Button { expandSearch() } label: {
                    HStack(spacing: 7) { HomePhosphorIcon(.magnifyingGlass, size: 14); Text("搜索").font(.system(size: 13, weight: .semibold)).tracking(0.26) }
                    .foregroundStyle(AmberTheme.muted).frame(width: 78, height: 38)
                }.buttonStyle(.plain).homeGlassControl(cornerRadius: 19).accessibilityLabel("搜索")
                HomeGlassCircleButton(icon: .gear, accessibilityLabel: "设置", size: 38, iconSize: 20, tint: AmberTheme.fab) { router.navigate(to: .settings) }
                Button { router.navigate(to: .account) } label: { HomeAccountAvatar(initial: accountInitial, size: 42) }
                    .buttonStyle(.plain).accessibilityLabel("我的账户")
            }
        }
        .frame(height: 42).padding(.horizontal, 16)
    }

    private var expandedSearch: some View {
        HStack(spacing: 7) {
            HomePhosphorIcon(.magnifyingGlass, size: 14).foregroundStyle(AmberTheme.muted)
            TextField("搜索会话", text: $searchQuery)
                .font(.system(size: 13, weight: .regular)).tracking(0.13).foregroundStyle(AmberTheme.foreground).focused($searchFocused)
                .submitLabel(.search).textInputAutocapitalization(.never).autocorrectionDisabled().onSubmit { router.navigate(to: .search(initialQuery: searchQuery)) }
            Button("取消", action: collapseSearch)
                .font(.system(size: 12, weight: .semibold)).tracking(0.24).foregroundStyle(AmberTheme.muted)
                .frame(height: 30).padding(.horizontal, 8)
        }
        .frame(maxWidth: .infinity).frame(height: 38).padding(.leading, 12).padding(.trailing, 6)
        .homeGlassControl(cornerRadius: 19)
        // 设计 focus-visible：聚焦时玻璃条外 3px 琥珀金环。
        .overlay { Capsule(style: .continuous).strokeBorder(AmberTheme.focusRing, lineWidth: 3).opacity(searchFocused ? 1 : 0).allowsHitTesting(false) }
        .onKeyPress(.escape) { collapseSearch(); return .handled }
    }

    private func expandSearch() {
        withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.32)) { isSearchExpanded = true }
        searchFocusTask?.cancel()
        searchFocusTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 170_000_000)
            // 170ms 内取消/收起/离场都会取消本任务，不会再写 FocusState。
            guard !Task.isCancelled, isSearchExpanded else { return }
            searchFocused = true
        }
    }

    private func collapseSearch() {
        searchFocusTask?.cancel()
        searchFocusTask = nil
        searchQuery = ""
        searchFocused = false
        withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.32)) { isSearchExpanded = false }
    }

    private var conversationList: some View {
        // `isLoading` is the observable foreground transition; background jobs
        // publish their terminal transition through `backgroundGenerationRevision`.
        // Reading both here keeps each row derived from the current owners rather
        // than preserving the off-screen NavigationStack snapshot.
        _ = chatViewModel.isLoading
        _ = backgroundGenerationRevision
        return ForEach(Array(filteredSummaries.enumerated()), id: \.element.id) { index, summary in
            ConversationSummaryRow(
                summary: summary,
                isCurrent: conversationStore.currentConversation?.id == summary.id,
                isGenerating: chatViewModel.isGenerationActive(conversationId: summary.id),
                slice: homeSlice(index: index, count: filteredSummaries.count),
                hidesSeparator: index == filteredSummaries.count - 1 || conversationStore.currentConversation?.id == summary.id || (index + 1 < filteredSummaries.count && conversationStore.currentConversation?.id == filteredSummaries[index + 1].id),
                onTap: {
                    openConversation(summary.id)
                },
                onRename: {
                    renameDraft = summary.title
                    renamingConversationId = summary.id
                },
                onTogglePin: {
                    Task { @MainActor in
                        await conversationStore.togglePin(id: summary.id)
                    }
                },
                onDelete: {
                    deletingConversationId = summary.id
                }
            )
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .homeCascade(delay: 0.18, enabled: !cascadeComplete)
        }
    }

    private func homeSlice(index: Int, count: Int) -> HomeCardSlice {
        if count == 1 { return .single }
        if index == 0 { return .top }
        return index == count - 1 ? .bottom : .middle
    }

    private var homeContinueModel: HomeContinueCardModel? {
        let novelProjects = novelCreationViewModel?.projects.map(HomeNovelProjectRef.init) ?? []
        let councilTask = councilChatViewModel.homeResumeContext.map(HomeCouncilTaskRef.init)
        let deepReadTasks = deepReadStore.history.map(HomeDeepReadTaskRef.init)
        let imageGeneration = homeImageGenerationContext.flatMap { context -> HomeImageGenerationRef? in
            if context.state == .completed, context.id == viewedImageGenerationID {
                return nil
            }
            return HomeImageGenerationRef(context)
        }
        let miniApps = miniAppRepository.apps.compactMap { app -> HomeMiniAppRef? in
            guard let latestVersion = miniAppRepository.versions(appId: app.id).first else { return nil }
            return HomeMiniAppRef(
                id: app.id,
                title: app.title,
                latestVersionCreatedAt: Date(
                    timeIntervalSince1970: TimeInterval(latestVersion.createdAt) / 1_000
                ),
                lastRunAt: app.lastRunAt.map {
                    Date(timeIntervalSince1970: TimeInterval($0) / 1_000)
                }
            )
        }

        return HomeContinueCardModel.resolve(
            novelProjects: novelProjects,
            councilTask: councilTask,
            deepReadTasks: deepReadTasks,
            miniApps: miniApps,
            imageGeneration: imageGeneration
        )
    }

    private var controlCard: some View {
        let ambient = AmberTheme.cardShadowAmbientGeometry(for: colorScheme)
        return VStack(spacing: 0) {
            if let model = homeContinueModel {
                HomeContinueButton(model: model) { destination in
                    switch destination {
                    case .openCouncil: router.navigate(to: .council)
                    case .deepReadTask(let id): router.navigate(to: .deepReadTask(id: id))
                    case .resumeProject(let id): router.navigate(to: .novelProject(id: id))
                    case .miniAppRunner(let id): router.navigate(to: .miniAppRunner(appId: id))
                    case .generatedImage(let anchor):
                        openGeneratedImage(anchor)
                    }
                }
                Rectangle().fill(AmberTheme.separator).frame(height: 1 / displayScale).padding(.horizontal, 16)
            }
            shortcutRow
        }
        .background(AmberTheme.card).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
        .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
        .padding(.horizontal, 16).padding(.top, 20)
    }

    @ViewBuilder
    private var shortcutRow: some View {
        if dynamicTypeSize.isAccessibilitySize {
            ScrollView(.horizontal) {
                HStack(spacing: 0) { shortcutButtons }
                    .padding(.horizontal, 8)
            }
            .scrollIndicators(.hidden)
            .padding(.vertical, 9)
            .padding(.bottom, 2)
        } else {
            HStack(spacing: 0) { shortcutButtons }
                .padding(.vertical, 9)
                .padding(.bottom, 2)
        }
    }

    @ViewBuilder
    private var shortcutButtons: some View {
        HomeShortcut(title: "深度阅读", icon: .bookOpen) { router.navigate(to: .board) }
            .accessibilityFocused($deepReadShortcutFocused)
        HomeShortcut(title: "小说创作", icon: .notebook) { router.navigate(to: .novelCreation) }
        HomeShortcut(title: "模型议会", icon: .chatCircleDots) { router.navigate(to: .council) }
        HomeShortcut(title: "小应用", icon: .squaresFour) { router.navigate(to: .miniApps) }
        HomeShortcut(title: "WebMount", icon: .globe) { router.navigate(to: .webMount) }
    }

    private func announceHomeContinueChange(
        from oldValue: HomeContinueCardModel?,
        to newValue: HomeContinueCardModel?
    ) {
        guard router.path.isEmpty,
              UIAccessibility.isVoiceOverRunning,
              oldValue != newValue else { return }
        if newValue == nil {
            deepReadShortcutFocused = true
        }
        let announcement = newValue.map {
            "待继续任务更新：\($0.title)，\($0.meta)，\($0.ctaTitle)"
        } ?? "没有待继续任务"
        UIAccessibility.post(notification: .announcement, argument: announcement)
    }

    private func openGeneratedImage(_ anchor: ChatMessageAnchor) {
        guard let conversationID = conversationStore.summaries.first(where: {
            $0.id.toHexDashString() == anchor.conversationID
        })?.id else {
            homeImageGenerationContext = nil
            homeContinueError = "图片所在会话已不存在。"
            return
        }
        conversationNavigationTask?.cancel()
        conversationNavigationTask = Task { @MainActor in
            guard chatViewModel.prepareForConversationChange(to: conversationID) else {
                homeContinueError = "当前生成任务暂时无法切换会话，请稍后重试。"
                return
            }
            if conversationStore.currentConversation?.id != conversationID {
                guard await conversationStore.selectConversationIfAvailable(
                    id: conversationID,
                    commitIf: { !Task.isCancelled }
                ) else {
                    guard !Task.isCancelled else { return }
                    homeImageGenerationContext = nil
                    homeContinueError = "图片所在会话已不存在。"
                    return
                }
            }
            guard !Task.isCancelled,
                  conversationStore.currentConversation?.id == conversationID,
                  let toolCallID = anchor.toolCallID else {
                homeContinueError = "无法切换到图片所在会话。"
                return
            }

            guard let context = ChatImageGenerationResumeProjection.matching(
                in: conversationStore.currentMessages,
                conversationID: anchor.conversationID,
                messageID: anchor.messageID,
                toolCallID: toolCallID,
                isGenerationActive: chatViewModel.isGenerationActive(conversationId: conversationID)
            ) else {
                homeImageGenerationContext = nil
                homeContinueError = "图片记录已不存在或生成没有成功。"
                return
            }

            router.navigate(to: .chatMessage(anchor: ChatMessageAnchor(
                conversationID: context.conversationID,
                messageID: context.messageID,
                toolCallID: context.toolCallID
            )))
        }
    }

    private func openConversation(_ conversationID: KotlinUuid) {
        conversationNavigationTask?.cancel()
        conversationNavigationTask = Task { @MainActor in
            guard chatViewModel.prepareForConversationChange(to: conversationID) else { return }
            if conversationStore.currentConversation?.id != conversationID {
                guard await conversationStore.selectConversationIfAvailable(
                    id: conversationID,
                    commitIf: { !Task.isCancelled }
                ) else {
                    guard !Task.isCancelled else { return }
                    homeContinueError = "该会话暂时无法打开。"
                    return
                }
            }
            guard !Task.isCancelled,
                  conversationStore.currentConversation?.id == conversationID else { return }
            router.navigate(to: .chat)
        }
    }

    private func startNewConversation() {
        conversationNavigationTask?.cancel()
        conversationNavigationTask = Task { @MainActor in
            guard chatViewModel.prepareForConversationChange() else { return }
            await conversationStore.startNewConversationReusingEmpty()
            guard !Task.isCancelled else { return }
            router.navigate(to: .chat)
        }
    }
}

/// 真实会话摘要行：标题 / 相对时间 / 消息数 / 置顶标记 / 当前高亮 / 左滑操作。
private struct ConversationSummaryRow: View {
    let summary: ConversationSummary
    let isCurrent: Bool
    let isGenerating: Bool
    let slice: HomeCardSlice
    let hidesSeparator: Bool
    let onTap: () -> Void
    let onRename: () -> Void
    let onTogglePin: () -> Void
    let onDelete: () -> Void
    @Environment(\.displayScale) private var displayScale
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var pressed = false
    @ScaledMetric(relativeTo: .body) private var conversationTitleSize: CGFloat = 16
    @ScaledMetric(relativeTo: .caption2) private var conversationMetadataSize: CGFloat = 11

    var body: some View {
        let ambient = AmberTheme.cardShadowAmbientGeometry(for: colorScheme)
        Button(action: onTap) {
            HStack(spacing: 13) {
                iconView

                VStack(alignment: .leading, spacing: 7) {
                    Text(displayTitle)
                        .font(.system(size: conversationTitleSize, weight: .semibold))
                        .tracking(-0.08)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(2)
                    HStack(spacing: 6) {
                        Text(relativeTime)
                            .font(.system(size: conversationMetadataSize, weight: .regular)).tracking(0.11)
                            .foregroundStyle(AmberTheme.muted)
                        Text("·")
                            .font(.system(size: conversationMetadataSize, weight: .regular))
                            .foregroundStyle(AmberTheme.muted2)
                        Text("\(summary.messageCount) 条")
                            .font(.system(size: conversationMetadataSize, weight: .regular)).tracking(0.11)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .monospacedDigit()
                    .padding(.trailing, dynamicTypeSize.isAccessibilitySize ? 28 : 0)
                }

                Spacer(minLength: 8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            .frame(minHeight: 72)
            .padding(.leading, 17).padding(.trailing, 16)
            .background {
                let fill = HomeSliceShape(slice: slice).fill(isCurrent ? AmberTheme.activeCard : AmberTheme.card)
                // 会话卡是「一张整卡」：只有收尾行（bottom/single）携带卡片投影，
                // 行间保持零阴影接缝。这是在保留原生 List swipeActions（行必须独立成 cell）
                // 的前提下最接近「单卡单投影」的方案；卡顶/侧边投影弱于整卡外框，属已知取舍。
                if slice == .bottom || slice == .single {
                    fill
                        .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
                        .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
                        // bottom 行的投影向上越界时会在上一行底部留下接缝带、并压暗行间
                        // hairline——用 mask 只裁行界以上的晕影（左右/下方延伸保留卡侧与
                        // 卡底投影；行内部分被 fill 自身遮盖）。single 上方是画布，向上
                        // 晕影与控制卡外投影一致，不裁。
                        .mask(Rectangle().padding(.horizontal, -48).padding(.bottom, -48).padding(.top, slice == .bottom ? 0 : -48))
                } else {
                    fill
                }
            }
            .overlay { if pressed { HomeSliceShape(slice: slice).fill(AmberTheme.press).allowsHitTesting(false) } }
            .overlay(alignment: .bottom) { if !hidesSeparator { Rectangle().fill(AmberTheme.separator).frame(height: 1 / displayScale).padding(.leading, 70).padding(.trailing, 16) } }
            .padding(.horizontal, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(HomePressStateStyle(pressed: $pressed, scale: 0.98))
        .accessibilityLabel("会话 \(displayTitle)，\(summary.messageCount) 条消息\(summary.isPinned ? "，已置顶" : "")\(isGenerating ? "，正在生成" : "")")
        // 主操作:Apple Music 同款左右滑动(原生 List swipeActions，iOS 26 自带 Liquid Glass 渲染)。
        // 右滑→删除(整行划到底即删) / 重命名;左滑→置顶切换。删除仍走二次确认弹窗，避免误删。
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            // 删除是危险动作:显式 .tint(.red) 强制红色，否则会继承 AppShell 的全局
            // 强调色 tint(.swipeActions 的 destructive 默认色会被环境 tint 覆盖)。
            Button(role: .destructive, action: onDelete) {
                Label("删除", systemImage: "trash")
            }
            .tint(.red)
            Button(action: onRename) {
                Label("重命名", systemImage: "pencil")
            }
            .tint(AmberTheme.muted2)
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button(action: onTogglePin) {
                Label(summary.isPinned ? "取消置顶" : "置顶",
                      systemImage: summary.isPinned ? "pin.slash" : "pin")
            }
            .tint(AmberTheme.muted2)
        }
        // 次操作:保留长按上下文菜单(与 Apple Music 一致，两种入口并存)。
        .contextMenu {
            Button {
                onTogglePin()
            } label: {
                Label(summary.isPinned ? "取消置顶" : "置顶",
                      systemImage: summary.isPinned ? "pin.slash" : "pin")
            }
            Button {
                onRename()
            } label: {
                Label("重命名", systemImage: "pencil")
            }
            Button(role: .destructive) {
                onDelete()
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
    }

    private var iconView: some View {
        ZStack {
            Circle()
                .fill(isCurrent ? AmberTheme.avatarActive : AmberTheme.avatarIdle)
            if summary.isPinned {
                HomePhosphorIcon(.pushPin, size: 20)
                    .foregroundStyle(isCurrent ? AmberTheme.avatarActiveInk : AmberTheme.avatarIdleInk)
            } else {
                HomePhosphorIcon(HomeConversationIcon.icon(forTitle: displayTitle, isPinned: false), size: 20)
                    .foregroundStyle(isCurrent ? AmberTheme.avatarActiveInk : AmberTheme.avatarIdleInk)
            }
            if isGenerating {
                ConversationGeneratingRing()
            }
        }
        .frame(width: 40, height: 40)
    }

    /// 空标题统一走「新对话」占位语义：行文本、搜索匹配与图标映射同一输入。
    private var displayTitle: String {
        summary.title.isEmpty ? "新对话" : summary.title
    }

    /// 相对时间：updateAt -> "刚刚 / N分钟前 / N小时前 / 昨天 / M月D日"。
    private var relativeTime: String {
        let ms = summary.updateAt.toEpochMilliseconds()
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

private struct ConversationGeneratingRing: View {
    @State private var start = Date()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @ViewBuilder
    var body: some View {
        if reduceMotion {
            ring.rotationEffect(.degrees(-90))
        } else {
            TimelineView(.animation) { context in
                ring
                .rotationEffect(.degrees(rotationAngle(at: context.date)))
            }
        }
    }

    private var ring: some View {
        Circle()
            .trim(from: 0.0, to: 0.78)
            .stroke(
                AmberTheme.foreground2,
                style: StrokeStyle(lineWidth: 1.05, lineCap: .round)
            )
            .accessibilityHidden(true)
    }

    private func rotationAngle(at date: Date) -> Double {
        let elapsed = date.timeIntervalSince(start)
        return elapsed.truncatingRemainder(dividingBy: 0.9) / 0.9 * 360
    }
}

struct SearchView: View {
    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(ChatViewModel.self) private var chatViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var query: String
    @State private var selectedFilter: SearchFilter = .all
    @State private var results: [IOSConversationSearchResult] = []
    @State private var isSearching = false
    @FocusState private var searchFocused: Bool

    init(initialQuery: String = "") {
        self._query = State(initialValue: initialQuery)
    }

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var visibleResults: [IOSConversationSearchResult] {
        guard !trimmedQuery.isEmpty else { return [] }
        return results.filter { selectedFilter.includes($0.kind) }
    }

    private var recentSummaries: [ConversationSummary] {
        Array(conversationStore.summaries.prefix(8))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                searchNavigation
                filterStrip

                if trimmedQuery.isEmpty {
                    recentConversationList
                } else {
                    resultList
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            searchFocused = true
            await performSearch()
        }
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 220_000_000)
            if !Task.isCancelled {
                await performSearch()
            }
        }
    }

    private var searchNavigation: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AmberTheme.muted)

                TextField("搜索会话与消息", text: $query)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .tint(AmberTheme.accent)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit {
                        Task { @MainActor in
                            await performSearch()
                        }
                    }

                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 18, height: 18)
                        .background(AmberTheme.muted2, in: Circle())
                }
                .buttonStyle(.plain)
                .opacity(query.isEmpty ? 0 : 1)
                .accessibilityLabel("清空搜索")
            }
            .frame(height: 38)
            .padding(.horizontal, 12)
            .amberGlass(cornerRadius: 12)
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }

            Button("取消") {
                dismiss()
            }
            .font(.body)
            .foregroundStyle(AmberTheme.accent)
            .buttonStyle(.plain)
            .accessibilityLabel("取消搜索")
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }

    private var filterStrip: some View {
        ScrollView(.horizontal) {
            AmberGlassGroup(spacing: 12) {
                HStack(spacing: 6) {
                    ForEach(SearchFilter.allCases) { filter in
                        Button {
                            selectedFilter = filter
                        } label: {
                            AmberGlassTextChip(
                                title: filter.title,
                                isSelected: selectedFilter == filter,
                                height: 30,
                                horizontalPadding: 13,
                                font: .system(size: 13.5, weight: .medium)
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selectedFilter == filter ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .scrollIndicators(.hidden)
        .padding(.bottom, 10)
    }

    private var recentConversationList: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "最近会话")
                    .padding(.top, -8)

                if recentSummaries.isEmpty {
                    ContentUnavailableView("还没有会话", systemImage: "bubble.left.and.bubble.right")
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.top, 72)
                } else {
                    AmberFormGroup {
                        ForEach(Array(recentSummaries.enumerated()), id: \.element.id) { index, summary in
                            RecentConversationSearchRow(summary: summary) {
                                openConversation(summary.id)
                            }

                            if index < recentSummaries.count - 1 {
                                Divider()
                                    .overlay(AmberTheme.borderSoft)
                                    .padding(.leading, 66)
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var resultList: some View {
        ScrollView {
            if isSearching {
                ProgressView()
                    .tint(AmberTheme.accent)
                    .padding(.top, 72)
            } else if visibleResults.isEmpty {
                ContentUnavailableView("没有结果", systemImage: "magnifyingglass", description: Text("换个关键词或筛选范围再试一次"))
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.top, 72)
            } else {
                VStack(spacing: 0) {
                    ForEach(groupedResults, id: \.group) { group in
                        SearchResultGroup(title: group.group, rows: group.rows) { result in
                            openConversation(result.conversationId)
                        }
                    }
                }
                .padding(.bottom, 36)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var groupedResults: [(group: String, rows: [IOSConversationSearchResult])] {
        SearchFilter.resultGroups.compactMap { kind in
            let rows = visibleResults.filter { $0.kind == kind }
            return rows.isEmpty ? nil : (kind.title, rows)
        }
    }

    @MainActor
    private func performSearch() async {
        guard !trimmedQuery.isEmpty else {
            results = []
            isSearching = false
            return
        }
        isSearching = true
        let nextResults = await conversationStore.searchConversations(query: trimmedQuery)
        if !Task.isCancelled {
            results = nextResults
            isSearching = false
        }
    }

    private func openConversation(_ id: KotlinUuid) {
        Task { @MainActor in
            let isAlreadyCurrent = conversationStore.currentConversation?.id == id
            guard chatViewModel.prepareForConversationChange(to: id) else { return }
            if !isAlreadyCurrent {
                await conversationStore.selectConversation(id: id)
            }
            router.navigate(to: .chat)
        }
    }
}

private enum SearchFilter: String, CaseIterable, Identifiable {
    case all
    case conversation
    case message

    static let resultGroups: [IOSConversationSearchResult.Kind] = [.conversation, .message]

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "全部"
        case .conversation: "会话"
        case .message: "消息"
        }
    }

    func includes(_ kind: IOSConversationSearchResult.Kind) -> Bool {
        switch self {
        case .all:
            true
        case .conversation:
            kind == .conversation
        case .message:
            kind == .message
        }
    }
}

private struct RecentConversationSearchRow: View {
    let summary: ConversationSummary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SearchRowChrome(
                systemImage: summary.isPinned ? "pin.fill" : "bubble.left.fill",
                color: AmberTheme.accent,
                title: summary.title.isEmpty ? "新对话" : summary.title,
                preview: "\(summary.messageCount) 条消息",
                highlight: "",
                time: relativeTime(ms: summary.updateAt.toEpochMilliseconds())
            )
        }
        .buttonStyle(.plain)
    }
}

private struct SearchResultGroup: View {
    let title: String
    let rows: [IOSConversationSearchResult]
    let action: (IOSConversationSearchResult) -> Void

    var body: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)
                .padding(.top, -8)

            AmberFormGroup {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    SearchResultRow(row: row) {
                        action(row)
                    }

                    if index < rows.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 66)
                    }
                }
            }
        }
        .padding(.bottom, 4)
    }
}

private struct SearchResultRow: View {
    let row: IOSConversationSearchResult
    let action: () -> Void

    private var systemImage: String {
        row.kind == .conversation ? "bubble.left.fill" : "text.bubble.fill"
    }

    private var color: Color {
        row.kind == .conversation ? AmberTheme.accent : AmberTheme.accentCyan
    }

    var body: some View {
        Button(action: action) {
            SearchRowChrome(
                systemImage: systemImage,
                color: color,
                title: row.title,
                preview: row.preview,
                highlight: row.highlight,
                time: relativeTime(ms: row.updateAt)
            )
        }
        .buttonStyle(.plain)
    }
}

private struct SearchRowChrome: View {
    let systemImage: String
    let color: Color
    let title: String
    let preview: String
    let highlight: String
    let time: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 40, height: 40)
                .background(color.opacity(0.14), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                HighlightedPreview(text: preview, highlight: highlight)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(time)
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .padding(.top, 1)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct HighlightedPreview: View {
    let text: String
    let highlight: String

    var body: some View {
        if let range = text.range(of: highlight, options: [.caseInsensitive, .diacriticInsensitive]), !highlight.isEmpty {
            HStack(spacing: 0) {
                Text(String(text[..<range.lowerBound]))
                Text(String(text[range]))
                    .foregroundStyle(AmberTheme.accent)
                    .padding(.horizontal, 2)
                    .background(AmberTheme.accent.opacity(0.18), in: RoundedRectangle(cornerRadius: 3, style: .continuous))
                Text(String(text[range.upperBound...]))
            }
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineLimit(1)
        } else {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
        }
    }
}

private func relativeTime(ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter.localizedString(for: date, relativeTo: Date())
}

private struct ConversationShortcut: Identifiable {
    let id = UUID()
    let title: String
    let systemImage: String
    let color: Color
    let route: Route
}

struct SettingsHomeView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.system.rawValue

    private var generalEntries: [SettingsHomeEntry] {
        [
            .init(title: "外观", subtitle: nil, value: appearanceModeTitle, systemImage: "circle.lefthalf.filled", color: AmberTheme.accent, route: .appearance),
            .init(title: "显示与字体", subtitle: nil, value: nil, systemImage: "slider.horizontal.3", color: AmberTheme.accentAmber, route: .displayFont)
        ]
    }

    private var agentEntries: [SettingsHomeEntry] {
        [
            .init(title: "核心记忆", subtitle: nil, value: nil, systemImage: "cylinder.split.1x2", color: AmberTheme.accent, route: .memory),
            .init(title: "运行环境", subtitle: nil, value: nil, systemImage: "terminal", color: AmberTheme.accentGreen, route: .execution),
            .init(title: "技能", subtitle: nil, value: nil, systemImage: "wrench.and.screwdriver", color: AmberTheme.accentAmber, route: .skills),
            .init(title: "权限与批准", subtitle: nil, value: nil, systemImage: "shield", color: AmberTheme.accentCyan, route: .toolPermissions)
        ]
    }

    private var modelServiceEntries: [SettingsHomeEntry] {
        [
            .init(title: "服务商", subtitle: nil, value: nil, systemImage: "server.rack", color: AmberTheme.accent, route: .providers),
            .init(title: "模型与提示词", subtitle: nil, value: nil, systemImage: "cpu", color: AmberTheme.accentAmber, route: .modelDefaults),
            .init(title: "搜索服务", subtitle: nil, value: nil, systemImage: "magnifyingglass", color: AmberTheme.accentGreen, route: .searchServices),
            .init(title: "语音服务", subtitle: nil, value: nil, systemImage: "speaker.wave.2", color: AmberTheme.accentCyan, route: .ttsSettings)
        ]
    }

    private var advancedFeatureEntries: [SettingsHomeEntry] {
        [
            .init(title: "WebMount", subtitle: nil, value: nil, systemImage: "globe", color: AmberTheme.accentGreen, route: .webMount),
            .init(title: "子代理", subtitle: nil, value: nil, systemImage: "person.2", color: AmberTheme.accentRed, route: .subagents),
            .init(title: "模型议会", subtitle: nil, value: nil, systemImage: "bubble.left.and.bubble.right", color: AmberTheme.accent, route: .council),
            .init(title: "小应用", subtitle: nil, value: nil, systemImage: "square.grid.2x2", color: AmberTheme.accentCyan, route: .miniApps),
            .init(title: "小说创作", subtitle: nil, value: nil, systemImage: "text.book.closed", color: AmberTheme.accentIndigo, route: .novelCreation),
            .init(title: "深度阅读", subtitle: nil, value: nil, systemImage: "book.pages", color: AmberTheme.accentAmber, route: .board)
        ]
    }

    private var dataEntries: [SettingsHomeEntry] {
        [
            .init(title: "Workspace", subtitle: nil, value: nil, systemImage: "folder.badge.gearshape", color: AmberTheme.accentIndigo, route: .workspace),
            .init(title: "同步备份", subtitle: nil, value: nil, systemImage: "icloud", color: AmberTheme.accentCyan, route: .syncBackup),
            .init(title: "对话存储", subtitle: nil, value: nil, systemImage: "tray.full", color: AmberTheme.accent, route: .conversationStorage)
        ]
    }

    private var appearanceModeTitle: String {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).title
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    settingsSection("通用设置", entries: generalEntries)
                    settingsSection("Agent 设置", entries: agentEntries)
                    settingsSection("模型与服务", entries: modelServiceEntries)
                    settingsSection("高级功能", entries: advancedFeatureEntries)
                    settingsSection("数据设置", entries: dataEntries)
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("设置")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 22)
    }

    private func settingsSection(_ title: String, entries: [SettingsHomeEntry]) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)
            AmberFormGroup {
                ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                    AmberFormRow(
                        systemImage: entry.systemImage,
                        iconColor: entry.color,
                        title: entry.title,
                        subtitle: entry.subtitle,
                        trailing: entry.value,
                        showsChevron: true
                    ) {
                        router.navigate(to: entry.route)
                    }

                    if index < entries.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 58)
                    }
                }
            }
        }
    }

    private func placeholder(_ title: String, _ subtitle: String, _ systemImage: String) -> Route {
        .settingsPlaceholder(title: title, subtitle: subtitle, systemImage: systemImage)
    }
}

private struct SettingsHomeEntry: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String?
    let value: String?
    let systemImage: String
    let color: Color
    let route: Route
}

struct WorkspaceView: View {
    @Bindable var workspaceStore: IOSWorkspaceStore
    let focusedItemId: String?

    @Environment(\.dismiss) private var dismiss
    @State private var isImportingFile = false
    @State private var selectedFile: IOSWorkspaceFileRecord?
    @State private var selectedArtifact: IOSWorkspaceArtifactRecord?
    @State private var alertMessage: String?

    init(workspaceStore: IOSWorkspaceStore = .shared, focusedItemId: String? = nil) {
        self.workspaceStore = workspaceStore
        self.focusedItemId = focusedItemId
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    workspaceStats
                    filesSection
                    artifactsSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .fileImporter(
            isPresented: $isImportingFile,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handleImport(result)
        }
        .sheet(item: $selectedFile) { record in
            WorkspaceFileDetailSheet(
                record: record,
                store: workspaceStore,
                onReparse: {
                    Task { await reparse(record) }
                },
                onRemove: {
                    removeFile(record)
                }
            )
        }
        .sheet(item: $selectedArtifact) { record in
            WorkspaceArtifactDetailSheet(
                record: record,
                store: workspaceStore,
                onDelete: {
                    deleteArtifact(record)
                }
            )
        }
        .alert("Workspace", isPresented: Binding(
            get: { alertMessage != nil },
            set: { if !$0 { alertMessage = nil } }
        )) {
            Button("好", role: .cancel) { alertMessage = nil }
        } message: {
            Text(alertMessage ?? "")
        }
        .onAppear {
            focusInitialItem()
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            VStack(alignment: .leading, spacing: 3) {
                Text("Workspace")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("文件上下文与生成结果")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            Button {
                isImportingFile = true
            } label: {
                Image(systemName: "square.and.arrow.down")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(AmberTheme.accent, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("导入文件")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 14)
    }

    private var workspaceStats: some View {
        HStack(spacing: 10) {
            WorkspaceMetricCard(
                title: "文件",
                value: "\(workspaceStore.files.count)",
                systemImage: "doc.text",
                color: AmberTheme.accentIndigo
            )
            WorkspaceMetricCard(
                title: "Artifacts",
                value: "\(workspaceStore.artifacts.count)",
                systemImage: "sparkles.rectangle.stack",
                color: AmberTheme.accentAmber
            )
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var filesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "文件")
            if workspaceStore.recentFiles.isEmpty {
                WorkspaceEmptyState(
                    systemImage: "doc.badge.plus",
                    title: "还没有导入文件",
                    subtitle: "通过 Files 选择的文件会复制进 AmberAgent 的本地 Workspace，不会自动扫描用户目录。"
                )
            } else {
                AmberFormGroup {
                    ForEach(Array(workspaceStore.recentFiles.enumerated()), id: \.element.id) { index, file in
                        WorkspaceFileRow(record: file) {
                            selectedFile = workspaceStore.fileRecord(idOrPath: file.id) ?? file
                        }
                        if index < workspaceStore.recentFiles.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
        }
    }

    private var artifactsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Artifacts")
            if workspaceStore.recentArtifacts.isEmpty {
                WorkspaceEmptyState(
                    systemImage: "tray",
                    title: "还没有保存的 Artifact",
                    subtitle: "聊天、MiniApp、Deep Read 或工具输出可以保存到这里统一管理。"
                )
            } else {
                AmberFormGroup {
                    ForEach(Array(workspaceStore.recentArtifacts.enumerated()), id: \.element.id) { index, artifact in
                        WorkspaceArtifactRow(record: artifact) {
                            selectedArtifact = workspaceStore.artifacts.first { $0.id == artifact.id } ?? artifact
                        }
                        if index < workspaceStore.recentArtifacts.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else {
                alertMessage = "没有选择文件。"
                return
            }
            Task {
                do {
                    let record = try await workspaceStore.importFile(url: url, source: "workspace_picker")
                    selectedFile = record
                } catch {
                    alertMessage = error.localizedDescription
                }
            }
        case .failure(let error):
            alertMessage = "文件选择失败：\(error.localizedDescription)"
        }
    }

    private func reparse(_ record: IOSWorkspaceFileRecord) async {
        do {
            selectedFile = try await workspaceStore.reparseFile(id: record.id)
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func removeFile(_ record: IOSWorkspaceFileRecord) {
        do {
            try workspaceStore.removeFile(id: record.id)
            selectedFile = nil
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func deleteArtifact(_ record: IOSWorkspaceArtifactRecord) {
        do {
            try workspaceStore.deleteArtifact(id: record.id)
            selectedArtifact = nil
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func focusInitialItem() {
        guard let focusedItemId else { return }
        if let file = workspaceStore.fileRecord(idOrPath: focusedItemId) {
            selectedFile = file
            return
        }
        if let artifact = workspaceStore.artifacts.first(where: { $0.id == focusedItemId }) {
            selectedArtifact = artifact
        }
    }
}

struct AssistantsView: View {
    var body: some View {
        PlaceholderDetailView(
            title: "Amber Assistant",
            subtitle: "iOS 只保留一个 Amber Assistant；模型、记忆与工具在设置中管理。",
            systemImage: "sparkles"
        )
    }
}

private struct WorkspaceMetricCard: View {
    let title: String
    let value: String
    let systemImage: String
    let color: Color

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 32, height: 32)
                .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(value)
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(AmberTheme.foreground)
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 62)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }
}

private struct WorkspaceEmptyState: View {
    let systemImage: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(AmberTheme.muted2)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.vertical, 28)
    }
}

private struct WorkspaceFileRow: View {
    let record: IOSWorkspaceFileRecord
    let action: () -> Void

    var body: some View {
        AmberFormRow(
            systemImage: statusIcon,
            iconColor: statusColor,
            title: record.displayName,
            subtitle: "\(record.byteSummary) · \(record.status.title) · /workspace/\(record.workspacePath)",
            trailing: WorkspaceDateFormat.short(record.updatedAtMillis),
            showsChevron: true,
            action: action
        )
    }

    private var statusIcon: String {
        switch record.status {
        case .ready: "doc.text"
        case .missing: "doc.badge.exclamationmark"
        case .parseFailed: "exclamationmark.triangle"
        case .unsupported: "nosign"
        case .tooLarge: "externaldrive.badge.exclamationmark"
        case .needsReauthorization: "lock.open"
        }
    }

    private var statusColor: Color {
        switch record.status {
        case .ready: AmberTheme.accentIndigo
        case .unsupported, .needsReauthorization: AmberTheme.accentAmber
        case .missing, .parseFailed, .tooLarge: AmberTheme.accentRed
        }
    }
}

private struct WorkspaceArtifactRow: View {
    let record: IOSWorkspaceArtifactRecord
    let action: () -> Void

    var body: some View {
        AmberFormRow(
            systemImage: "sparkles.rectangle.stack",
            iconColor: AmberTheme.accentAmber,
            title: record.title,
            subtitle: "\(record.type.title) · \(DocumentAccessStore.formatBytes(record.contentBytes))",
            trailing: WorkspaceDateFormat.short(record.updatedAtMillis),
            showsChevron: true,
            action: action
        )
    }
}

private struct WorkspaceFileDetailSheet: View {
    let record: IOSWorkspaceFileRecord
    @Bindable var store: IOSWorkspaceStore
    let onReparse: () -> Void
    let onRemove: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WorkspaceDetailHeader(
                        systemImage: "doc.text",
                        title: record.displayName,
                        subtitle: "/workspace/\(record.workspacePath)"
                    )

                    WorkspaceInfoGrid(rows: [
                        ("状态", record.status.title),
                        ("大小", record.byteSummary),
                        ("类型", record.mimeType),
                        ("字符", "\(record.characterCount)"),
                        ("来源", record.source),
                        ("更新", WorkspaceDateFormat.long(record.updatedAtMillis))
                    ])

                    if !record.statusMessage.isEmpty {
                        WorkspaceStatusBanner(status: record.status, message: record.statusMessage)
                    }

                    WorkspacePreviewBlock(text: record.preview, emptyText: previewEmptyText)
                }
                .padding(16)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        onReparse()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("重新解析")

                    Button(role: .destructive) {
                        onRemove()
                    } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel("移除文件")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var previewEmptyText: String {
        switch record.status {
        case .ready:
            "没有可预览文本。"
        case .missing:
            "文件副本丢失，请重新导入。"
        case .unsupported:
            "此格式暂不支持文本预览。"
        case .tooLarge:
            "文件超过本地解析上限。"
        case .needsReauthorization:
            "需要从 Files 重新选择文件。"
        case .parseFailed:
            "解析失败，可尝试重新解析。"
        }
    }
}

private struct WorkspaceArtifactDetailSheet: View {
    let record: IOSWorkspaceArtifactRecord
    @Bindable var store: IOSWorkspaceStore
    let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss

    private var content: String {
        (try? store.artifactContent(id: record.id)) ?? "Artifact 内容丢失或读取失败。"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WorkspaceDetailHeader(
                        systemImage: "sparkles.rectangle.stack",
                        title: record.title,
                        subtitle: record.type.title
                    )
                    WorkspaceInfoGrid(rows: [
                        ("大小", DocumentAccessStore.formatBytes(record.contentBytes)),
                        ("来源", record.sourceKind),
                        ("创建", WorkspaceDateFormat.long(record.createdAtMillis)),
                        ("更新", WorkspaceDateFormat.long(record.updatedAtMillis))
                    ])
                    WorkspacePreviewBlock(text: content, emptyText: "Artifact 内容为空。")
                }
                .padding(16)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(role: .destructive) {
                        onDelete()
                    } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel("删除 Artifact")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct WorkspaceDetailHeader: View {
    let systemImage: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 42, height: 42)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(3)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .textSelection(.enabled)
            }
        }
    }
}

private struct WorkspaceInfoGrid: View {
    let rows: [(String, String)]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(alignment: .top) {
                    Text(row.0)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(width: 56, alignment: .leading)
                    Text(row.1)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .padding(.vertical, 8)
                if index < rows.count - 1 {
                    Divider().overlay(AmberTheme.borderSoft)
                }
            }
        }
        .padding(.horizontal, 12)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
    }
}

private struct WorkspaceStatusBanner: View {
    let status: IOSWorkspaceFileStatus
    let message: String

    var body: some View {
        Label(message, systemImage: status == .ready ? "checkmark.circle" : "exclamationmark.triangle")
            .font(.caption)
            .foregroundStyle(status == .ready ? AmberTheme.accentGreen : AmberTheme.accentAmber)
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background((status == .ready ? AmberTheme.accentGreen : AmberTheme.accentAmber).opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct WorkspacePreviewBlock: View {
    let text: String
    let emptyText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("预览")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .textCase(.uppercase)
            Text(text.isEmpty ? emptyText : text)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(text.isEmpty ? AmberTheme.muted : AmberTheme.foreground2)
                .lineSpacing(3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
        }
    }
}

private enum WorkspaceDateFormat {
    static func short(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    static func long(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

struct PlaceholderListView: View {

    let title: String
    let systemImage: String
    let rows: [String]

    var body: some View {
        List {
            Section {
                Label(title, systemImage: systemImage)
                    .font(.headline)
                ForEach(rows, id: \.self) { row in
                    Text(row)
                }
            }
        }
        .navigationTitle(title)
    }
}

struct PlaceholderDetailView: View {

    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        ContentUnavailableView(title, systemImage: systemImage, description: Text(subtitle))
            .navigationTitle(title)
    }
}

struct CapabilityGateLockedView: View {
    let gate: IOSCapabilityGate

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 12) {
                Image(systemName: "lock.shield")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 58, height: 58)
                    .background(AmberTheme.accentAmber.opacity(0.12), in: Circle())

                Text(gate.title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(gate.disabledReason)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 28)

                Text("这是 AmberAgent 的受控能力。默认关闭用于保护工具执行、外部连接和远程操作；可在设置页对应行开启。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted2)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 28)
            }
            .padding(.horizontal, 18)
        }
        .navigationBarBackButtonHidden(false)
    }
}
