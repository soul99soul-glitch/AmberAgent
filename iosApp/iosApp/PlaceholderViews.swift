import SwiftUI
import UIKit
@preconcurrency import Shared
import UniformTypeIdentifiers

/// A full set of canvas tokens for one appearance (warm paper / warm gray / neutral white / dark).
struct AmberPalette {
    let background, surface, surface2, foreground, foreground2, muted, muted2, border, borderSoft: UInt32
}

// Theme tokens are dynamic: the canvas (paper / warm gray / white) + accent come from
// AmberThemeRuntime, and light/dark is resolved by a UIColor dynamicProvider. Because the color
// getters read the @Observable runtime, any SwiftUI body that reads AmberTheme.* auto-tracks
// theme changes and re-renders — with zero changes at the ~1500 existing call sites.
enum AmberTheme {
    // 暖纸：画布 #EFE7D6、卡片 #FFFDF7，投影偏暖棕。
    static let paperLight = AmberPalette(
        background: 0xEFE7D6, surface: 0xFFFDF7, surface2: 0xF0EBE2,
        foreground: 0x1B1813, foreground2: 0x5B5449, muted: 0x746D62, muted2: 0x918A80,
        border: 0xDBCEBC, borderSoft: 0xECE3D6
    )
    // 暖灰（E 版默认）：画布 #ECE8E4 + 暖白卡片 #F6F5F3。
    static let neutralLight = AmberPalette(
        background: 0xECE8E4, surface: 0xF6F5F3, surface2: 0xEDEBE7,
        foreground: 0x161514, foreground2: 0x55524D, muted: 0x716D67, muted2: 0x8F8B85,
        border: 0xD9D5CF, borderSoft: 0xE4E1DC
    )
    // 中性白：冷中性画布 + 真白分组面（background ≠ surface ≠ surface2，避免塌层级）。
    static let whiteLight = AmberPalette(
        background: 0xF5F5F4, surface: 0xFFFFFF, surface2: 0xEEEEED,
        foreground: 0x1A1A1A, foreground2: 0x5C5C5C, muted: 0x737373, muted2: 0x8E8E8E,
        border: 0xD4D4D4, borderSoft: 0xE5E5E5
    )
    // 点阵 · 钢蓝（pi-dotgrid / Open Design brand-spec）：奶油稿纸 + 实底卡片。
    static let piLight = AmberPalette(
        background: 0xF3F0EB, surface: 0xFAF9F7, surface2: 0xEBE7E0,
        foreground: 0x1C1B19, foreground2: 0x4A4640, muted: 0x6A6560, muted2: 0x9A948C,
        border: 0xD4CFC7, borderSoft: 0xE8E4DC
    )
    // 空白 · 暖白（Open Design blank-workspace / Notion 工作台）：暖中性 + 真白 surface。
    static let notionLight = AmberPalette(
        background: 0xF6F5F4, surface: 0xFFFFFF, surface2: 0xEFEEEC,
        foreground: 0x1A1918, foreground2: 0x31302E, muted: 0x615D59, muted2: 0xA39E98,
        border: 0xE6E5E3, borderSoft: 0xF0EFED
    )
    // 深色 · 暖灰工作台（E 版 / neutral）：画布 #0E0D10、卡片 #1F1D23。
    static let darkPalette = AmberPalette(
        background: 0x0E0D10, surface: 0x1F1D23, surface2: 0x2B2930,
        foreground: 0xF4F1ED, foreground2: 0xC3BEC5, muted: 0xAAA5AD, muted2: 0x6E6760,
        border: 0x3A3741, borderSoft: 0x2A2830
    )
    // 深色 · 暖纸：偏棕墨底，保留纸本气质。
    static let paperDark = AmberPalette(
        background: 0x14110E, surface: 0x221E19, surface2: 0x2E2822,
        foreground: 0xF5F0E8, foreground2: 0xC8BDB0, muted: 0xA89888, muted2: 0x6E6258,
        border: 0x3D342C, borderSoft: 0x2A241E
    )
    // 深色 · 中性白：冷中性灰阶，避免偏紫底。
    static let whiteDark = AmberPalette(
        background: 0x111111, surface: 0x1C1C1C, surface2: 0x282828,
        foreground: 0xF5F5F5, foreground2: 0xBDBDBD, muted: 0x8E8E8E, muted2: 0x6B6B6B,
        border: 0x383838, borderSoft: 0x2A2A2A
    )
    // 深色 · Pi 稿纸：暖橄榄底，对齐奶油稿纸角色。
    static let piDark = AmberPalette(
        background: 0x12110F, surface: 0x1E1C18, surface2: 0x2A2722,
        foreground: 0xF3F0EB, foreground2: 0xC4BEB4, muted: 0x9A948C, muted2: 0x6A6560,
        border: 0x3A3630, borderSoft: 0x28251F
    )
    // 深色 · Notion 暖白：冷灰工作台（近 Notion dark）。
    static let notionDark = AmberPalette(
        background: 0x191919, surface: 0x252525, surface2: 0x2F2F2F,
        foreground: 0xEBEBEB, foreground2: 0xB4B4B4, muted: 0x9B9B9B, muted2: 0x6F6F6F,
        border: 0x373737, borderSoft: 0x2C2C2C
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
    // `accentAmber` / `statusAmber` = warning · running · attention chrome — NOT the user theme accent.
    // Brand / selected / primary interactive chrome must use `accent` + `accentInk`.
    static let statusAmberHex: UInt32 = 0xD98324
    static let accentIndigo = Color(hex: 0x5856D6)
    static let accentAmber = Color(hex: statusAmberHex)
    /// Alias for call sites that want the semantic name (same as `accentAmber`).
    static var statusAmber: Color { accentAmber }
    static let accentGreen = Color(hex: 0x3DA35D)
    static let accentCyan = Color(hex: 0x2AA0BC)
    static let accentRed = Color(hex: 0xC8402F)

    static var glass: Color { base(\.background, alpha: 0.72) }
    static var glassStrong: Color { base(\.background, alpha: 0.85) }

    static let radiusSmall: CGFloat = 6
    static let radiusMedium: CGFloat = 8
    /// Chat / tool card radius — follows optional `bubbleChrome` theme slot.
    static var radiusLarge: CGFloat {
        switch AmberThemeRuntime.shared.bubbleChrome {
        case .standard: 12
        case .soft: 14
        case .crisp: 10
        }
    }
    static var radiusXLarge: CGFloat {
        switch AmberThemeRuntime.shared.bubbleChrome {
        case .standard: 18
        case .soft: 22
        case .crisp: 14
        }
    }
    static let radiusPill: CGFloat = 980

    // ── 首页设计令牌 ────────────────────────────────────────────────
    // 画布相关（sep/activeCard/avatar…）随 Paper 变；彩色强调走 runtime accent。

    /// 全 App 唯一分隔线语言：1px hairline。
    static var separator: Color { homeColor(\.sep, alpha: \.sepAlpha) }
    /// hover/按压垫底（前景只允许加深，禁止变浅变灰）。
    static var press: Color { homeColor(\.press, alpha: \.pressAlpha) }
    static var hoverCard: Color { homeColor(\.hoverCard) }
    /// 激活会话行通栏色带。
    /// Notion 暖白：中性浅墨晕（≈ rgba(0,0,0,0.04)），蓝只留在图标/头像，避免整行「皮肤」蓝。
    /// 其它 paper：仍随 accent 浅染。
    static var activeCard: Color {
        Color(uiColor: UIColor { trait in
            let dark = trait.userInterfaceStyle == .dark
            if AmberThemeRuntime.shared.paper == .notion {
                return UIColor(hex: dark ? 0xFFFFFF : 0x000000, alpha: dark ? 0.08 : 0.04)
            }
            let hex = AmberThemeRuntime.shared.accentHex
            return UIColor(hex: hex, alpha: dark ? 0.16 : 0.09)
        })
    }
    /// 节标题墨（设计令牌 sec，与 foreground2 数值同构但语义独立，防止联动漂移）。
    static var section: Color { homeColor(\.section) }
    /// 当前/激活头像底：随 accent 浅染（Continue 功能方块、会话当前行共用）。
    static var avatarActive: Color {
        Color(uiColor: UIColor { trait in
            let hex = AmberThemeRuntime.shared.accentHex
            let alpha: Double = trait.userInterfaceStyle == .dark ? 0.32 : 0.18
            return UIColor(hex: hex, alpha: alpha)
        })
    }
    /// 当前/激活头像墨：浅色用 accent 本体；深色用 on-accent 墨（高亮色走白/浅，琥珀等走深墨时抬亮一档）。
    static var avatarActiveInk: Color {
        Color(uiColor: UIColor { trait in
            let accent = AmberThemeRuntime.shared.accentHex
            if trait.userInterfaceStyle == .dark {
                let ink = AmberThemeRuntime.shared.accentInkHex
                // 深色画布上：若 ink 是白/浅则用 ink；若 ink 是深色（琥珀/鼠尾草）则用 accent 提亮可读性。
                let r = Double((ink >> 16) & 0xFF)
                let g = Double((ink >> 8) & 0xFF)
                let b = Double(ink & 0xFF)
                let luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
                return UIColor(hex: luminance > 0.6 ? ink : accent)
            }
            return UIColor(hex: accent)
        })
    }
    static var avatarIdle: Color { homeColor(\.avatarIdle) }
    static var avatarIdleInk: Color { homeColor(\.avatarIdleInk) }
    /// 首页/浮层强调色别名：绑定用户可选 accent（齿轮、新建图标、旧 fab 调用点）。
    static var fab: Color { accent }
    static var fabInk: Color { accentInk }
    /// focus-visible 焦点环（随 accent）。
    static var focusRing: Color {
        Color(hex: AmberThemeRuntime.shared.accentHex, alpha: 0.55)
    }
    /// 当前会话头像呼吸光晕（色随 accent；alpha 刻意压低，只余光提示）。
    static var activeAvatarGlow: Color {
        Color(uiColor: UIColor { trait in
            let hex = AmberThemeRuntime.shared.accentHex
            if trait.userInterfaceStyle == .dark {
                return UIColor(hex: hex, alpha: 0.14)
            }
            let alpha: Double
            switch AmberThemeRuntime.shared.paper {
            case .paper: alpha = 0.14
            case .white: alpha = 0.12
            default: alpha = 0.15
            }
            return UIColor(hex: hex, alpha: alpha)
        })
    }
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
        let shadowContact, shadowAmbient: UInt32
        let shadowContactAlpha, shadowAmbientAlpha: Double
        let glassTopAlpha, glassBottomAlpha, glassEdgeAlpha, glassHighlightAlpha: Double
        let glassShadow: UInt32
        let glassShadowAmbientAlpha, glassShadowContactAlpha: Double
    }

    // 暖灰 light
    private static let homeNeutral = AmberHomeTokens(
        sep: 0x161410, press: 0x463A28, hoverCard: 0xF0EEEA, activeCard: 0xEFE9DF,
        sepAlpha: 0.045, pressAlpha: 0.06,
        section: 0x55524D,
        avatarActive: 0xE8DDC6, avatarActiveInk: 0x6F5019, avatarIdle: 0xEDEBE7, avatarIdleInk: 0x8F8B85,
        shadowContact: 0x3A342C, shadowAmbient: 0x3A342C,
        shadowContactAlpha: 0.09, shadowAmbientAlpha: 0.05,
        glassTopAlpha: 0.78, glassBottomAlpha: 0.58, glassEdgeAlpha: 0.5, glassHighlightAlpha: 0.9,
        glassShadow: 0x28241C, glassShadowAmbientAlpha: 0.10, glassShadowContactAlpha: 0.06
    )
    // 暖纸 light
    private static let homePaper = AmberHomeTokens(
        sep: 0x261E14, press: 0x594223, hoverCard: 0xF7F1E6, activeCard: 0xF4EAD8,
        sepAlpha: 0.05, pressAlpha: 0.055,
        section: 0x5B5449,
        avatarActive: 0xEADCBC, avatarActiveInk: 0x6F5019, avatarIdle: 0xF0EBE2, avatarIdleInk: 0x918A80,
        shadowContact: 0x4C3B22, shadowAmbient: 0x4C3B22,
        shadowContactAlpha: 0.09, shadowAmbientAlpha: 0.06,
        glassTopAlpha: 0.78, glassBottomAlpha: 0.58, glassEdgeAlpha: 0.5, glassHighlightAlpha: 0.9,
        glassShadow: 0x28241C, glassShadowAmbientAlpha: 0.10, glassShadowContactAlpha: 0.06
    )
    // 中性白 light：冷灰分隔/投影，激活带浅中性灰
    private static let homeWhite = AmberHomeTokens(
        sep: 0x000000, press: 0x000000, hoverCard: 0xF0F0EF, activeCard: 0xEBEBEA,
        sepAlpha: 0.08, pressAlpha: 0.05,
        section: 0x5C5C5C,
        avatarActive: 0xE8E8E7, avatarActiveInk: 0x3A3A3A, avatarIdle: 0xEEEEED, avatarIdleInk: 0x8E8E8E,
        shadowContact: 0x000000, shadowAmbient: 0x000000,
        shadowContactAlpha: 0.06, shadowAmbientAlpha: 0.04,
        glassTopAlpha: 0.82, glassBottomAlpha: 0.62, glassEdgeAlpha: 0.45, glassHighlightAlpha: 0.9,
        glassShadow: 0x000000, glassShadowAmbientAlpha: 0.08, glassShadowContactAlpha: 0.05
    )
    // 深色（玻璃改 10% 白、投影改黑系）
    private static let homeDark = AmberHomeTokens(
        sep: 0xFFFFFF, press: 0xFFFFFF, hoverCard: 0x29262D, activeCard: 0x302A25,
        sepAlpha: 0.055, pressAlpha: 0.055,
        section: 0xC3BEC5,
        avatarActive: 0x443824, avatarActiveInk: 0xE0BA72, avatarIdle: 0x2B2930, avatarIdleInk: 0xAAA5AD,
        shadowContact: 0x000000, shadowAmbient: 0x000000,
        shadowContactAlpha: 0.58, shadowAmbientAlpha: 0.76,
        glassTopAlpha: 0.14, glassBottomAlpha: 0.08, glassEdgeAlpha: 0.16, glassHighlightAlpha: 0.16,
        glassShadow: 0x000000, glassShadowAmbientAlpha: 0.30, glassShadowContactAlpha: 0.22
    )

    private static func homeTokens(for paper: AmberThemeRuntime.Paper, dark: Bool) -> AmberHomeTokens {
        if dark {
            switch paper {
            case .neutral:
                // E-edition warm-gray chrome stays the canonical dark home table.
                return homeDark
            case .paper, .white, .pi, .notion:
                // Only retint idle/hover/section against that paper's dark surfaces;
                // glass/shadow geometry stay on homeDark (no full per-paper home tables).
                return homeDarkAligned(to: paper.darkPalette)
            case .garnet, .ochre, .turmeric, .magenta, .lotus:
                break
            }
        } else {
            switch paper {
            case .neutral: return homeNeutral
            case .paper: return homePaper
            case .white: return homeWhite
            case .pi: return homePaper // cream draft paper shares warm-home chrome
            case .notion: return homeWhite // warm-white workspace
            case .garnet, .ochre, .turmeric, .magenta, .lotus:
                break
            }
        }
        // 沉浸式单色画布目前是隐藏的占位主题：从各自调色板派生中性令牌。
        let palette = dark ? paper.darkPalette : paper.lightPalette
        return AmberHomeTokens(
            sep: palette.foreground, press: palette.foreground,
            hoverCard: palette.surface2, activeCard: palette.surface2,
            sepAlpha: 0.18, pressAlpha: 0.08,
            section: palette.foreground2,
            avatarActive: palette.surface2, avatarActiveInk: palette.foreground,
            avatarIdle: palette.surface2, avatarIdleInk: palette.muted,
            shadowContact: 0x000000, shadowAmbient: 0x000000,
            shadowContactAlpha: 0.25, shadowAmbientAlpha: 0.30,
            glassTopAlpha: 0.14, glassBottomAlpha: 0.08, glassEdgeAlpha: 0.16, glassHighlightAlpha: 0.16,
            glassShadow: 0x000000, glassShadowAmbientAlpha: 0.30, glassShadowContactAlpha: 0.22
        )
    }

    /// Dark home chrome that tracks `paper.darkPalette` for surfaces next to the card face.
    private static func homeDarkAligned(to palette: AmberPalette) -> AmberHomeTokens {
        AmberHomeTokens(
            sep: homeDark.sep, press: homeDark.press,
            hoverCard: palette.surface2, activeCard: homeDark.activeCard,
            sepAlpha: homeDark.sepAlpha, pressAlpha: homeDark.pressAlpha,
            section: palette.foreground2,
            avatarActive: homeDark.avatarActive, avatarActiveInk: homeDark.avatarActiveInk,
            avatarIdle: palette.surface2, avatarIdleInk: palette.muted,
            shadowContact: homeDark.shadowContact, shadowAmbient: homeDark.shadowAmbient,
            shadowContactAlpha: homeDark.shadowContactAlpha, shadowAmbientAlpha: homeDark.shadowAmbientAlpha,
            glassTopAlpha: homeDark.glassTopAlpha, glassBottomAlpha: homeDark.glassBottomAlpha,
            glassEdgeAlpha: homeDark.glassEdgeAlpha, glassHighlightAlpha: homeDark.glassHighlightAlpha,
            glassShadow: homeDark.glassShadow,
            glassShadowAmbientAlpha: homeDark.glassShadowAmbientAlpha,
            glassShadowContactAlpha: homeDark.glassShadowContactAlpha
        )
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

/// Persisted, observable theme state: canvas (paper vs neutral) + accent + swappable visual
/// style slots (texture / brand mark / shortcut icons). Light/dark is handled separately via
/// `IOSAppearanceMode` → `.preferredColorScheme` (+ the dynamicProvider above).
/// List layout is **not** part of this state.
@Observable
final class AmberThemeRuntime {
    // Read/written only from main-actor view bodies and tap handlers; the dynamicProvider color
    // closure does not touch it. nonisolated(unsafe) keeps the shared singleton accessible from
    // AmberTheme.* getters without forcing @MainActor onto all ~1500 call sites.
    nonisolated(unsafe) static let shared = AmberThemeRuntime()

    enum Paper: String, CaseIterable {
        /// 暖纸 / 暖灰 / 中性白（非沉浸）+ 隐藏的沉浸色画布占位。
        case paper, neutral, white, pi, notion, garnet, ochre, turmeric, magenta, lotus

        /// Light-appearance palette. Neutral canvases adapt to system dark;
        /// the immersive single-hue canvases keep their color in both appearances.
        var lightPalette: AmberPalette {
            switch self {
            case .paper: AmberTheme.paperLight
            case .neutral: AmberTheme.neutralLight
            case .white: AmberTheme.whiteLight
            case .pi: AmberTheme.piLight
            case .notion: AmberTheme.notionLight
            case .garnet: AmberTheme.garnetPalette
            case .ochre: AmberTheme.ochrePalette
            case .turmeric: AmberTheme.turmericPalette
            case .magenta: AmberTheme.magentaPalette
            case .lotus: AmberTheme.lotusPalette
            }
        }

        var darkPalette: AmberPalette {
            switch self {
            case .neutral: AmberTheme.darkPalette
            case .paper: AmberTheme.paperDark
            case .white: AmberTheme.whiteDark
            case .pi: AmberTheme.piDark
            case .notion: AmberTheme.notionDark
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
            case .white: "中性白"
            case .pi: "奶油稿纸"
            case .notion: "暖白"
            case .garnet: "绛红"
            case .ochre: "赭橙"
            case .turmeric: "姜黄"
            case .magenta: "品红"
            case .lotus: "藕荷"
            }
        }

        var isImmersive: Bool {
            switch self {
            case .paper, .neutral, .white, .pi, .notion: false
            default: true
            }
        }
    }

    var paper: Paper { didSet { UserDefaults.standard.set(paper.rawValue, forKey: Keys.paper) } }
    var accentHex: UInt32 { didSet { UserDefaults.standard.set(Int(accentHex), forKey: Keys.accent) } }
    var accentInkHex: UInt32 { didSet { UserDefaults.standard.set(Int(accentInkHex), forKey: Keys.accentInk) } }
    /// Canvas texture overlay style (default `.flat` = solid color only).
    var canvasStyle: AmberCanvasStyle {
        didSet { UserDefaults.standard.set(canvasStyle.rawValue, forKey: Keys.canvasStyle) }
    }
    /// Home brand mark style (default `.systemWordmark`).
    var brandMarkStyle: AmberBrandMarkStyle {
        didSet { UserDefaults.standard.set(brandMarkStyle.rawValue, forKey: Keys.brandMark) }
    }
    /// Home shortcut icon skin (default `.phosphorFill`). Conversation list icons are independent.
    var shortcutIconStyle: AmberShortcutIconStyle {
        didSet { UserDefaults.standard.set(shortcutIconStyle.rawValue, forKey: Keys.shortcutIconStyle) }
    }
    /// Home chrome typeface (brand / section / shortcut labels). Never writes chat body font prefs.
    var chromeTypeface: AmberChromeTypeface {
        didSet { UserDefaults.standard.set(chromeTypeface.rawValue, forKey: Keys.chromeTypeface) }
    }
    var canvasScope: AmberCanvasScope {
        didSet { UserDefaults.standard.set(canvasScope.rawValue, forKey: Keys.canvasScope) }
    }
    var bubbleChrome: AmberBubbleChrome {
        didSet { UserDefaults.standard.set(bubbleChrome.rawValue, forKey: Keys.bubbleChrome) }
    }
    var glassChrome: AmberGlassChrome {
        didSet { UserDefaults.standard.set(glassChrome.rawValue, forKey: Keys.glassChrome) }
    }
    var emptyArt: AmberEmptyArtStyle {
        didSet { UserDefaults.standard.set(emptyArt.rawValue, forKey: Keys.emptyArt) }
    }
    var settingsChrome: Bool {
        didSet { UserDefaults.standard.set(settingsChrome, forKey: Keys.settingsChrome) }
    }
    var launchBrand: AmberLaunchBrandStyle {
        didSet { UserDefaults.standard.set(launchBrand.rawValue, forKey: Keys.launchBrand) }
    }
    var assetMode: AmberThemeAssetMode {
        didSet { UserDefaults.standard.set(assetMode.rawValue, forKey: Keys.assetMode) }
    }
    var immersivePolicy: AmberImmersivePolicy {
        didSet { UserDefaults.standard.set(immersivePolicy.rawValue, forKey: Keys.immersivePolicy) }
    }

    private enum Keys {
        static let paper = "app.amber.ios.theme.paper"
        static let accent = "app.amber.ios.theme.accentHex"
        static let accentInk = "app.amber.ios.theme.accentInkHex"
        static let canvasStyle = "app.amber.ios.theme.canvasStyle"
        static let brandMark = "app.amber.ios.theme.brandMarkStyle"
        static let shortcutIconStyle = "app.amber.ios.theme.shortcutIconStyle"
        static let chromeTypeface = "app.amber.ios.theme.chromeTypeface"
        static let canvasScope = "app.amber.ios.theme.canvasScope"
        static let bubbleChrome = "app.amber.ios.theme.bubbleChrome"
        static let glassChrome = "app.amber.ios.theme.glassChrome"
        static let emptyArt = "app.amber.ios.theme.emptyArt"
        static let settingsChrome = "app.amber.ios.theme.settingsChrome"
        static let launchBrand = "app.amber.ios.theme.launchBrand"
        static let assetMode = "app.amber.ios.theme.assetMode"
        static let immersivePolicy = "app.amber.ios.theme.immersivePolicy"
    }

    private init() {
        let d = UserDefaults.standard
        // 默认主题 = 中性暖灰 × 琥珀金（E 版定稿）；用户显式选择过的偏好仍以持久化值为准。
        // Style 槽缺省 = 现状观感，旧安装升级后仍匹配原 6 色 pack。
        paper = Paper(rawValue: d.string(forKey: Keys.paper) ?? "") ?? .neutral
        accentHex = (d.object(forKey: Keys.accent) as? Int).map { UInt32($0) } ?? AmberAccentOption.amberGold.accentHex
        accentInkHex = (d.object(forKey: Keys.accentInk) as? Int).map { UInt32($0) } ?? AmberAccentOption.amberGold.inkHex
        canvasStyle = AmberCanvasStyle(rawValue: d.string(forKey: Keys.canvasStyle) ?? "") ?? .flat
        brandMarkStyle = AmberBrandMarkStyle(rawValue: d.string(forKey: Keys.brandMark) ?? "") ?? .systemWordmark
        shortcutIconStyle = AmberShortcutIconStyle(rawValue: d.string(forKey: Keys.shortcutIconStyle) ?? "") ?? .phosphorFill
        chromeTypeface = AmberChromeTypeface(rawValue: d.string(forKey: Keys.chromeTypeface) ?? "") ?? .system
        canvasScope = AmberCanvasScope(rawValue: d.string(forKey: Keys.canvasScope) ?? "") ?? .homeOnly
        bubbleChrome = AmberBubbleChrome(rawValue: d.string(forKey: Keys.bubbleChrome) ?? "") ?? .standard
        glassChrome = AmberGlassChrome(rawValue: d.string(forKey: Keys.glassChrome) ?? "") ?? .standard
        emptyArt = AmberEmptyArtStyle(rawValue: d.string(forKey: Keys.emptyArt) ?? "") ?? .none
        settingsChrome = d.object(forKey: Keys.settingsChrome) as? Bool ?? false
        launchBrand = AmberLaunchBrandStyle(rawValue: d.string(forKey: Keys.launchBrand) ?? "") ?? .none
        assetMode = AmberThemeAssetMode(rawValue: d.string(forKey: Keys.assetMode) ?? "") ?? .builtinOnly
        immersivePolicy = AmberImmersivePolicy(rawValue: d.string(forKey: Keys.immersivePolicy) ?? "") ?? .hidden
    }

    func apply(_ option: AmberAccentOption) {
        accentHex = option.accentHex
        accentInkHex = option.inkHex
    }
}

/// Authoritative accent set + paired ink (redesign/aa-base.jsx ACCENT_INK). High-luminance hues
/// (sage, amber gold) pair with dark ink; the rest with white — never a blanket white.
enum AmberAccentOption: String, CaseIterable, Identifiable {
    case amberGold, terracotta, sage, mistBlue, steelBlue, notionBlue, wisteria, rose, ink

    var id: String { rawValue }

    var accentHex: UInt32 {
        switch self {
        case .amberGold:  0xB9863A
        case .terracotta: 0xB8623A
        case .sage:       0x5E9C6E
        case .mistBlue:   0x4F86D6
        case .steelBlue:  0x6B8CAD // pi-dotgrid / Open Design
        case .notionBlue: 0x0075DE // Notion Blue (blank-workspace)
        case .wisteria:   0x9277C4
        case .rose:       0xC2607A
        case .ink:        0x222226
        }
    }

    var inkHex: UInt32 {
        switch self {
        case .sage:       0x0F150E
        case .amberGold:  0x231602
        case .steelBlue:  0xFAF9F7 // cream ink on steel blue (brand-spec)
        case .notionBlue: 0xFFFFFF
        default:          0xFFFFFF
        }
    }

    var displayName: String {
        switch self {
        case .terracotta: "陶土"
        case .sage:       "鼠尾草绿"
        case .mistBlue:   "雾蓝"
        case .steelBlue:  "钢蓝"
        case .notionBlue: "Notion 蓝"
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
    /// 设置首页方案 B：统一 accent 浅底圆角方块 + 图标色（默认关，免影响 Workspace 等状态色行）。
    var iconUsesAccentPlate: Bool = false
    let title: String
    let subtitle: String?
    let trailing: String?
    let showsChevron: Bool
    let action: (() -> Void)?

    init(
        systemImage: String? = nil,
        iconColor: Color = AmberTheme.accent,
        iconUsesAccentPlate: Bool = false,
        title: String,
        subtitle: String? = nil,
        trailing: String? = nil,
        showsChevron: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.systemImage = systemImage
        self.iconColor = iconColor
        self.iconUsesAccentPlate = iconUsesAccentPlate
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
                        .font(.system(size: iconUsesAccentPlate ? 15 : 16, weight: .medium))
                        .foregroundStyle(iconColor)
                        .frame(width: 28, height: 28)
                        .background {
                            if iconUsesAccentPlate {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(AmberTheme.accentTint)
                            }
                        }
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

    /// 给标题 LLM 的短 key 表（稳定英文 slug → 实心字形 + 中文提示）。
    /// 生成标题时让模型选一个 key，列表优先用它，而不是事后猜标题文案。
    static let llmCatalog: [(key: String, icon: HomePhosphor, hint: String)] = [
        ("moon", .moon, "夜晚/梦"),
        ("sun", .sun, "白天/阳光"),
        ("wine", .wine, "酒"),
        ("coffee", .coffee, "咖啡"),
        ("sword", .sword, "武侠/战争"),
        ("crown", .crown, "帝王/王室"),
        ("castle", .castleTurret, "王朝/宫廷"),
        ("list", .list, "清单/顺序"),
        ("checklist", .listChecks, "待办"),
        ("music", .musicNotes, "音乐"),
        ("headphones", .headphones, "播客/耳机"),
        ("map", .mapPin, "地点/旅行"),
        ("globe", .globe, "世界/国际"),
        ("plane", .airplane, "飞机/出行"),
        ("car", .car, "汽车"),
        ("train", .train, "火车/地铁"),
        ("pill", .pill, "医疗/健康"),
        ("heart_pulse", .heartbeat, "心脏/体检"),
        ("scales", .scales, "对比/评价"),
        ("law", .gavel, "法律"),
        ("book", .bookOpen, "小说/阅读"),
        ("books", .books, "历史/典籍"),
        ("notebook", .notebook, "笔记"),
        ("pencil", .pencil, "写作"),
        ("code", .code, "编程/模型"),
        ("robot", .robot, "AI/机器人"),
        ("brain", .brain, "思考/心理"),
        ("idea", .lightbulb, "想法/原理"),
        ("science", .flask, "科学/实验"),
        ("game", .gameController, "游戏"),
        ("trophy", .trophy, "比赛/冠军"),
        ("football", .football, "足球"),
        ("basketball", .basketball, "篮球"),
        ("heart", .heart, "感情/恋爱"),
        ("smile", .smiley, "搞笑/轻松"),
        ("fire", .fire, "热门/燃"),
        ("bolt", .lightning, "速度/性能"),
        ("water", .drop, "水/海洋"),
        ("snow", .snowflake, "雪/冬天"),
        ("mountain", .mountains, "山"),
        ("tree", .tree, "树/自然"),
        ("flower", .flower, "花"),
        ("dog", .dog, "狗"),
        ("cat", .cat, "猫"),
        ("fish", .fish, "鱼/海鲜"),
        ("food", .forkKnife, "美食"),
        ("pizza", .pizza, "披萨"),
        ("burger", .hamburger, "汉堡"),
        ("cake", .cake, "蛋糕/生日"),
        ("home", .house, "家/住房"),
        ("office", .buildings, "公司/都市"),
        ("money", .wallet, "钱/消费"),
        ("finance", .currencyCny, "理财/汇率"),
        ("chart", .chartLineUp, "数据/趋势"),
        ("shop", .shoppingCart, "购物"),
        ("gift", .gift, "礼物"),
        ("calendar", .calendar, "日程"),
        ("clock", .clock, "时间"),
        ("study", .graduationCap, "考试/学习"),
        ("student", .student, "学生/上课"),
        ("camera", .camera, "拍照"),
        ("movie", .filmSlate, "电影/剧"),
        ("video", .videoCamera, "视频/直播"),
        ("phone", .phone, "电话/手机"),
        ("mail", .envelope, "邮件"),
        ("bell", .bell, "提醒"),
        ("lock", .lock, "安全/密码"),
        ("key", .key, "密钥"),
        ("translate", .translate, "翻译/语言"),
        ("quote", .quotes, "名言"),
        ("people", .users, "团队/社交"),
        ("baby", .baby, "育儿"),
        ("rocket", .rocket, "创业/发布"),
        ("work", .briefcase, "职场/面试"),
        ("deal", .handshake, "合作"),
        ("zen", .yinYang, "哲学"),
        ("ghost", .ghost, "灵异"),
        ("alien", .alien, "科幻"),
        ("drama", .maskHappy, "戏剧/角色"),
        ("design", .palette, "设计/配色"),
        ("paint", .paintBrush, "绘画"),
        ("search", .magnifyingGlass, "搜索/研究"),
        ("settings", .gear, "设置"),
        ("chat", .chatCircle, "闲聊/一般"),
    ]

    /// 按会话标题 / 可选 LLM 图标 key 取 Phosphor fill。
    /// 1) 置顶 → 图钉
    /// 2) 标题 LLM 写入的 preferredKey
    /// 3) 标题关键词表
    /// 4) 标题稳定哈希
    static func icon(forTitle title: String, isPinned: Bool, preferredKey: String? = nil) -> HomePhosphor {
        if isPinned { return .pushPin }
        if let preferredKey,
           let icon = resolveLLMKey(preferredKey) {
            return icon
        }
        let normalized = title.lowercased()
        if let mapped = semanticIcon(for: normalized) {
            return mapped
        }
        return hashedIcon(for: title)
    }

    /// 只认 `llmCatalog` 里的 slug；返回落盘用的规范化 key。
    static func canonicalLLMKey(_ raw: String) -> String? {
        let key = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: " ", with: "_")
        guard !key.isEmpty else { return nil }
        return llmCatalog.first(where: { $0.key == key })?.key
    }

    static func resolveLLMKey(_ raw: String) -> HomePhosphor? {
        guard let key = canonicalLLMKey(raw) else { return nil }
        return llmCatalog.first(where: { $0.key == key })?.icon
    }

    /// 塞进标题 prompt 的 icon 说明（短、可解析）。
    static func llmIconInstructionBlock() -> String {
        let keys = llmCatalog.map(\.key).joined(separator: ", ")
        return """

        Also pick ONE icon key that best matches the topic.
        Reply in exactly two lines:
        line1 = the title only (rules above still apply)
        line2 = icon:<key>
        Allowed <key> values: \(keys)
        """
    }

    /// 关键词命中；顺序即优先级（先匹配先生效）。
    private static func semanticIcon(for normalized: String) -> HomePhosphor? {
        let mappings: [(HomePhosphor, [String])] = [
            (.moon, ["月光", "月亮", "夜色", "夜晚", "晚安", "晚上", "星空", "半夜", "晚年", "梦", "失眠"]),
            (.sun, ["白天", "阳光", "日出", "日落", "晴天", "夏天", "夏日"]),
            (.wine, ["酒", "酿", "醉", "干杯", "白酒", "红酒", "啤酒", "威士忌"]),
            (.coffee, ["咖啡", "拿铁", "美式", "espresso", "café"]),
            (.sword, ["剑", "武侠", "江湖", "打仗", "战争", "战役", "战术", "兵法", "武将", "将军", "军队", "项羽", "吕布", "关羽"]),
            (.crown, ["皇帝", "帝王", "王冠", "君主", "国王", "女王", "皇后", "皇室", "王位", "登基", "在位", "宋太祖", "赵匡胤", "天子"]),
            (.castleTurret, ["宫殿", "城堡", "王朝", "帝国", "朝廷"]),
            (.list, ["顺序", "排行", "清单", "列表", "目录", "步骤", "流程", "时间表", "年表"]),
            (.listChecks, ["todo", "待办", "checklist", "勾选", "任务列表"]),
            (.musicNotes, ["音乐", "歌曲", "歌单", "bgm", "配乐", "旋律", "专辑", "歌手", "歌词", "钢琴", "吉他", "rap"]),
            (.headphones, ["耳机", "播客", "podcast", "听歌"]),
            (.mapPin, ["在哪", "哪里", "哪儿", "地址", "地图", "路线", "都城", "城市", "旅行", "旅游", "景点", "定位"]),
            (.globe, ["世界", "国际", "全球", "地球", "国家", "海外", "跨国"]),
            (.airplane, ["飞机", "航班", "机场", "航空", "出差", "飞去"]),
            (.car, ["开车", "汽车", "驾车", "高速", "路况", "停车"]),
            (.train, ["火车", "高铁", "地铁", "动车", "站台"]),
            (.pill, ["药", "症状", "治疗", "医院", "看病", "疾病", "感冒", "发烧", "痛风", "健康", "养生"]),
            (.heartbeat, ["心脏", "血压", "体检", "心率"]),
            (.scales, ["谁", "对比", "比较", "哪个好", "排名", "评价", "厉害", "更强", "哪个厉害"]),
            (.gavel, ["法律", "法院", "律师", "判决", "合同", "合规"]),
            (.bookOpen, ["小说", "读书", "阅读", "章节", "写书", "连载", "故事", "剧本", "剧情"]),
            (.books, ["历史", "史料", "文献", "典籍", "通史"]),
            (.notebook, ["笔记", "备忘", "日记", "手账"]),
            (.pencil, ["写作", "作文", "改写", "润色", "文案", "起草"]),
            (.code, ["代码", "编程", "程序", "bug", "api", "swift", "python", "前端", "后端", "算法", "模型", "训练", "llm", "gpt"]),
            (.robot, ["机器人", "ai", "人工智能", "智能体", "agent"]),
            (.brain, ["思考", "推理", "认知", "心理", "脑"]),
            (.lightbulb, ["想法", "创意", "灵感", "点子", "方案"]),
            (.flask, ["化学", "实验", "科学", "物理", "公式"]),
            (.gameController, ["游戏", "电玩", "通关", "副本", "rpg", "steam"]),
            (.trophy, ["冠军", "夺冠", "奖杯", "比赛", "胜负"]),
            (.football, ["足球", "世界杯", "联赛"]),
            (.basketball, ["篮球", "nba", "扣篮"]),
            (.heart, ["爱情", "恋爱", "喜欢", "表白", "女朋友", "男朋友", "结婚", "暗恋"]),
            (.smiley, ["开心", "搞笑", "笑话", "幽默", "段子"]),
            (.fire, ["火", "热门", "爆", "燃", "热情"]),
            (.lightning, ["闪电", "速度", "性能", "加速"]),
            (.drop, ["水", "下雨", "喝水", "饮水", "海洋"]),
            (.snowflake, ["雪", "冬天", "冰冷", "霜"]),
            (.mountains, ["山", "登山", "爬山", "高原"]),
            (.tree, ["树", "森林", "植物", "环保"]),
            (.flower, ["花", "玫瑰", "花园"]),
            (.dog, ["狗", "犬", "汪"]),
            (.cat, ["猫", "喵"]),
            (.fish, ["鱼", "海鲜", "钓鱼", "三文鱼", "帝王鲑"]),
            (.pizza, ["披萨", "pizza"]),
            (.hamburger, ["汉堡", "快餐"]),
            (.cake, ["蛋糕", "生日", "甜品"]),
            (.forkKnife, ["美食", "餐厅", "做饭", "菜谱", "吃什么", "下厨"]),
            (.house, ["家", "房子", "居住", "装修", "租房"]),
            (.buildings, ["公司", "办公", "写字楼", "都市"]),
            (.wallet, ["钱", "理财", "存款", "消费", "省钱"]),
            (.currencyCny, ["人民币", "汇率", "日元", "美元", "炒股", "股票", "基金"]),
            (.chartLineUp, ["增长", "趋势", "数据", "分析", "报表", "kpi"]),
            (.shoppingCart, ["购物", "网购", "下单", "淘宝", "买东西"]),
            (.gift, ["礼物", "送礼", "红包"]),
            (.calendar, ["日程", "日历", "约会", "会议", "安排"]),
            (.clock, ["时间", "几点", "迟到", "闹钟", "倒计时"]),
            (.graduationCap, ["考试", "高考", "考研", "留学", "大学", "学习", "课程", "作业"]),
            (.student, ["学生", "同学", "老师", "上课"]),
            (.camera, ["拍照", "摄影", "相机", "照片"]),
            (.filmSlate, ["电影", "影视", "剧集", "追剧", "导演"]),
            (.videoCamera, ["视频", "直播", "剪辑"]),
            (.phone, ["电话", "手机", "通话"]),
            (.envelope, ["邮件", "email", "写信"]),
            (.bell, ["提醒", "通知", "闹钟提醒"]),
            (.lock, ["密码", "加密", "隐私", "安全", "登录"]),
            (.key, ["钥匙", "密钥", "token"]),
            (.translate, ["翻译", "英文", "日语", "语法", "单词"]),
            (.quotes, ["名言", "引用", "摘抄"]),
            (.users, ["团队", "同事", "朋友", "群", "社交"]),
            (.baby, ["宝宝", "婴儿", "育儿", "怀孕"]),
            (.rocket, ["创业", "上线", "发布", "起飞", "航天"]),
            (.briefcase, ["工作", "职业", "面试", "简历", "职场"]),
            (.handshake, ["合作", "商务", "谈判", "签约"]),
            (.yinYang, ["哲学", "道家", "阴阳", "禅"]),
            (.ghost, ["鬼", "灵异", "恐怖", "玄学"]),
            (.alien, ["外星", "ufo", "科幻"]),
            (.maskHappy, ["戏剧", "表演", "话剧", "角色", "喜剧"]),
            (.palette, ["设计", "配色", "画画", "美术", "ui"]),
            (.paintBrush, ["绘画", "水彩", "油画"]),
            (.magnifyingGlass, ["搜索", "查找", "检索", "研究"]),
            (.gear, ["设置", "配置", "参数", "系统"]),
            (.lightbulb, ["为什么", "怎么做", "如何", "解释", "原理"]),
        ]
        return mappings.first(where: { _, words in words.contains { normalized.contains($0) } })?.0
    }

    /// 无关键词时：用标题 utf8 稳定哈希映射到 **会话语义池**（llmCatalog），
    /// 避免抽到 pin/gear 等 chrome 形看起来像「置顶/设置」。
    private static func hashedIcon(for title: String) -> HomePhosphor {
        let pool = llmCatalog.map(\.icon)
        guard !pool.isEmpty else { return fallback }
        var hash: UInt64 = 5381
        for byte in title.utf8 {
            hash = 127 &* hash &+ UInt64(byte)
        }
        let index = Int(hash % UInt64(pool.count))
        return pool[index]
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
    /// E 版会话行 `transform-origin: left center`；其它控件保持中心。
    var scaleAnchor: UnitPoint = .center
    var haptic: AmberHapticEvent? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1, anchor: scaleAnchor)
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
            // Primary line is the task topic so the continue card matches what
            // the user just launched — not a generic "深度阅读" label that looks
            // like a different entry after failure.
            let topic = task.title.trimmingCharacters(in: .whitespacesAndNewlines)
            return Candidate(
                stableID: "deep-read:\(task.id)",
                priority: priority,
                updatedAt: task.updatedAt,
                model: .init(
                    feature: .deepRead,
                    title: topic.isEmpty ? "深度阅读" : topic,
                    meta: "深度阅读 · \(state)",
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

/// 会话空态：与列表一体卡同圆角/投影。
private struct HomeEmptyCard: View {
    let title: String
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        let ambient = AmberTheme.cardShadowAmbientGeometry(for: colorScheme)
        Text(title)
            .font(.system(size: 15, weight: .regular))
            .foregroundStyle(AmberTheme.muted)
            .frame(maxWidth: .infinity, minHeight: 120, alignment: .center)
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
            .background {
                if AmberThemeRuntime.shared.emptyArt == .character {
                    Group {
                        switch AmberThemeRuntime.shared.canvasStyle {
                        case .lineGrid:
                            // Same 18pt lattice as page/cards; opacity only softens ink.
                            AmberLineGridOverlay()
                                .opacity(0.65)
                        case .paperGrain:
                            AmberPaperGrainOverlay()
                                .opacity(0.7)
                        case .dotGrid, .flat:
                            AmberDotGridOverlay()
                                .opacity(0.55)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                }
            }
            .background(HomeSliceShape(slice: .single).fill(AmberTheme.card))
            .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
            .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
            .padding(.horizontal, 16)
    }
}

private struct HomeShortcut: View {
    let entry: HomeShortcutEntry
    let action: () -> Void
    @State private var hovering = false
    @State private var pressed = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .caption2) private var shortcutLabelSize: CGFloat = 11
    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                // Icon skin from theme pack; VStack layout frozen.
                HomeShortcutIconView(entry: entry, size: 20)
                Text(entry.title)
                    // Chrome typeface from theme pack; not chat body IOSChatFont.
                    .font(AmberChromeFont.system(size: shortcutLabelSize, weight: .semibold))
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

/// 首页控制层专用玻璃（搜索胶囊 / 展开条 / 齿轮 / 右下新建浮层胶囊）。
/// iOS 26+：原生 Liquid Glass（skill: 真 `glassEffect`，轻垫底保证暖灰画布上可读，不做假 solid chip）。
/// 更早系统：ultraThinMaterial + E 版描边/投影回退。
private struct HomeGlassControlModifier: ViewModifier {
    let cornerRadius: CGFloat
    var interactive: Bool = true

    private var padOpacity: Double {
        switch AmberThemeRuntime.shared.glassChrome {
        case .standard: 0.28
        case .quieter: 0.18
        case .solid: 0.52
        }
    }

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26.0, *) {
            // 垫底极轻：帮助折射；强度由主题 glassChrome 弱控。
            content
                .background(AmberTheme.homeGlassTop.opacity(padOpacity), in: shape)
                .glassEffect(
                    interactive ? .regular.interactive() : .regular,
                    in: .rect(cornerRadius: cornerRadius)
                )
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .background(
                    LinearGradient(
                        colors: [
                            AmberTheme.homeGlassTop.opacity(padOpacity / 0.28),
                            AmberTheme.homeGlassBottom.opacity(padOpacity / 0.28),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    in: shape
                )
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
    func homeGlassControl(cornerRadius: CGFloat, interactive: Bool = true) -> some View {
        modifier(HomeGlassControlModifier(cornerRadius: cornerRadius, interactive: interactive))
    }

    /// iOS 26 原生 glass morph 标记；旧系统 no-op。
    @ViewBuilder
    func homeGlassEffectID(_ id: String, in namespace: Namespace.ID) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
    }
}

/// 首页齿轮钮：38 圆形玻璃 + Phosphor 实心图标（与内页 AmberGlassCircleButton 隔离）。
private struct HomeGlassCircleButton: View {
    let icon: HomePhosphor
    let accessibilityLabel: String
    var size: CGFloat = 38
    var iconSize: CGFloat = 20
    var tint: Color = AmberTheme.muted
    var glassNamespace: Namespace.ID? = nil
    var glassEffectID: String? = nil
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
        .modifier(HomeOptionalGlassEffectID(id: glassEffectID, namespace: glassNamespace))
        .accessibilityLabel(accessibilityLabel)
    }
}

private struct HomeOptionalGlassEffectID: ViewModifier {
    let id: String?
    let namespace: Namespace.ID?

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), let id, let namespace {
            content.glassEffectID(id, in: namespace)
        } else {
            content
        }
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var searchQuery: String = ""
    /// Liquid Glass morph namespace（skill: glassEffectID + hierarchy change）。
    @Namespace private var homeSearchNamespace
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
    /// 展开后是否已真正拿到焦点；避免 expand 后 170ms 内 focused==false 误触发点外收起。
    @State private var searchHadFocus = false
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
        // snapshot 为 @ObservationIgnored；读 revision 才能在改昵称后刷新头像首字。
        let _ = sharedSettings.revision
        let visibleSummaries = filteredSummaries
        GeometryReader { geometry in
        ZStack(alignment: .bottomTrailing) {
            // Canvas layer (color + optional texture). List structure unchanged.
            AmberCanvasBackground()

            // 用原生 List 承载整屏，会话行才能挂 .swipeActions(Apple Music 同款左右滑动)。
            // 顶部 header/搜索/快捷区作为清空背景的 List 行铺在上面，玻璃风格不受影响:
            // .scrollContentBackground(.hidden) + 每行 .listRowBackground(.clear) 让 List 自身
            // 不画任何底色，保留 AmberTheme.background。
            // 新建：右下拇指区真浮层胶囊（非顶栏、非圆 FAB、非 safeAreaInset 假底栏）。
            List {
                header.listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden).homeCascade(delay: 0.06, enabled: !cascadeComplete)
                controlCard
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .homeCascade(delay: 0.10, enabled: !cascadeComplete)
                    .simultaneousGesture(dismissSearchOutsideTap)
                Text("会话")
                    // Section chrome from theme pack; list row layout frozen; chat body font independent.
                    .font(AmberChromeFont.system(size: sectionLabelSize, weight: .semibold))
                    .tracking(0.11).foregroundStyle(AmberTheme.section)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.top, 26).padding(.bottom, 12)
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden).homeCascade(delay: 0.14, enabled: !cascadeComplete)
                    .contentShape(Rectangle())
                    .simultaneousGesture(dismissSearchOutsideTap)

                if visibleSummaries.isEmpty {
                    HomeEmptyCard(title: searchQuery.isEmpty ? "还没有会话" : "没有匹配的会话")
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .homeCascade(delay: 0.18, enabled: !cascadeComplete)
                        .simultaneousGesture(dismissSearchOutsideTap)
                } else {
                    conversationList(visibleSummaries)
                }

                // 滚到底时末行让过右下胶囊（仅 scroll 留白，无实色底栏）。
                Color.clear
                    .frame(height: homeNewChatCapsuleListClearance)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .contentShape(Rectangle())
                    .simultaneousGesture(dismissSearchOutsideTap)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .scrollEdgeEffectStyle(.soft, for: .top)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            // 点到非搜索区导致失焦时收起（与 Esc/取消一致）。
            .onChange(of: searchFocused) { _, focused in
                if focused {
                    searchHadFocus = true
                } else if searchHadFocus, isSearchExpanded {
                    collapseSearch()
                }
            }

            // 右下拇指区：内容贴合胶囊浮在内容上（局部琥珀；非满幅条）。
            // trailing 28（非卡边 16）：相对会话外框内缩 12pt，避免胶囊右缘与卡边相切。
            homeNewChatCapsule
                .padding(.trailing, homeNewChatCapsuleTrailingInset)
                .padding(
                    .bottom,
                    dynamicTypeSize.isAccessibilitySize
                        ? 12
                        : max(homeNewChatCapsuleBottomInset - geometry.safeAreaInsets.bottom, 12)
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

    /// Continue 显隐：0.30s 与搜索 expand 同曲线族；Reduce Motion 缩短。
    private var homeContinuePresenceMotion: Animation {
        if reduceMotion {
            return .easeOut(duration: 0.15)
        }
        return .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.30)
    }

    /// 右下新建胶囊：略小于搜索 38 的「宽版」控制，仍 ≥ 拇指舒适区。
    private var homeNewChatCapsuleHeight: CGFloat {
        dynamicTypeSize.isAccessibilitySize ? 48 : 42
    }

    /// 相对屏右 inset：大于会话卡 16，使胶囊相对外框内缩约 12pt。
    private var homeNewChatCapsuleTrailingInset: CGFloat { 28 }

    /// 相对屏底 inset（拇指区）。
    private var homeNewChatCapsuleBottomInset: CGFloat { 52 }

    /// 列表底留白：胶囊高 + 余量（只滚空白，不铺实色栏）。
    private var homeNewChatCapsuleListClearance: CGFloat {
        homeNewChatCapsuleHeight + 36
    }

    /// 首页右下「新对话」浮层胶囊。
    /// skill 门禁：
    /// - taste：accent 只点缀图标；壳子黑白/中性玻璃，不铺大面琥珀
    /// - liquid glass：与搜索同族 `homeGlassControl` regular glass（非 glassProminent 实心）
    /// - ui-patterns：拇指区 bottomTrailing 真浮层；非 top 难够、非 inset 假底栏
    private var homeNewChatCapsule: some View {
        let height = homeNewChatCapsuleHeight
        return Button {
            startNewConversation()
        } label: {
            HStack(spacing: 6) {
                HomePhosphorIcon(.pencil, size: 14)
                    .foregroundStyle(AmberTheme.fab)
                Text("新对话")
                    .font(AmberChromeFont.system(size: 14, weight: .semibold))
                    .tracking(0.2)
                    .foregroundStyle(AmberTheme.foreground)
            }
            .padding(.horizontal, 14)
            .frame(height: height)
            .contentShape(Capsule())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.94, haptic: .lightImpact))
        .homeGlassControl(cornerRadius: height / 2)
        .accessibilityLabel("新建聊天")
        .accessibilityAddTraits(.isButton)
    }

    /// E 版 + Liquid Glass skill：
    /// - 玻璃只上控制层（搜索/齿轮 + 展开条 + 右下新建胶囊）
    /// - iOS 26：同一 `GlassEffectContainer` 内用 `glassEffectID("homeSearch")` 做胶囊→全宽条 morph
    /// - container spacing 8、横向 layout 10 → 静态不永久 blob
    /// - 展开 0.32s 对齐原型 cubic-bezier(0.2,.8,.2,1)；Reduce Motion 缩短
    private var header: some View {
        Group {
            if #available(iOS 26.0, *) {
                GlassEffectContainer(spacing: 8) {
                    homeHeaderStack(useGlassEffectID: true)
                }
            } else {
                homeHeaderStack(useGlassEffectID: false)
            }
        }
        .animation(homeSearchMotion, value: isSearchExpanded)
    }

    @ViewBuilder
    private func homeHeaderStack(useGlassEffectID: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                // Brand mark layer — HStack chrome layout frozen.
                AmberBrandMarkView()

                Spacer(minLength: 8)

                if !isSearchExpanded {
                    homeSearchCapsuleButton
                        .modifier(HomeOptionalGlassEffectID(
                            id: useGlassEffectID ? "homeSearch" : nil,
                            namespace: useGlassEffectID ? homeSearchNamespace : nil
                        ))
                }

                homeSettingsGlassButton
                    .modifier(HomeOptionalGlassEffectID(
                        id: useGlassEffectID ? "homeSettings" : nil,
                        namespace: useGlassEffectID ? homeSearchNamespace : nil
                    ))

                Button {
                    collapseSearchIfNeeded()
                    router.navigate(to: .account)
                } label: {
                    // 与搜索 38 / 齿轮 38 同高，顶栏控制簇尺度一致。
                    HomeAccountAvatar(initial: accountInitial, size: 38)
                }
                .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.92, haptic: .lightImpact))
                .accessibilityLabel("我的账户")
            }
            .frame(minHeight: 38)
            .padding(.horizontal, 16)

            if isSearchExpanded {
                expandedSearchBar
                    .padding(.horizontal, 16)
                    .padding(.top, 15)
                    .modifier(HomeOptionalGlassEffectID(
                        id: useGlassEffectID ? "homeSearch" : nil,
                        namespace: useGlassEffectID ? homeSearchNamespace : nil
                    ))
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private var homeSearchCapsuleButton: some View {
        Button {
            expandSearch()
        } label: {
            HStack(spacing: 6) {
                HomePhosphorIcon(.magnifyingGlass, size: 14)
                Text("搜索")
                    .font(AmberChromeFont.system(size: 13, weight: .semibold))
                    .tracking(0.26)
            }
            .foregroundStyle(AmberTheme.muted)
            .frame(width: 78, height: 38)
            .contentShape(Capsule())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.92, haptic: .lightImpact))
        .homeGlassControl(cornerRadius: 19)
        .accessibilityLabel("搜索")
        .accessibilityAddTraits(.isButton)
    }

    private var homeSettingsGlassButton: some View {
        HomeGlassCircleButton(
            icon: .gear,
            accessibilityLabel: "设置",
            size: 38,
            iconSize: 20,
            tint: AmberTheme.fab
        ) {
            collapseSearchIfNeeded()
            router.navigate(to: .settings)
        }
    }

    /// 展开后的全宽玻璃条：高 41、圆角 14（E 版实测）；与胶囊共用 glassEffectID 做 morph。
    private var expandedSearchBar: some View {
        HStack(spacing: 14) {
            HomePhosphorIcon(.magnifyingGlass, size: 14)
                .foregroundStyle(AmberTheme.muted2)
            TextField("搜索会话", text: $searchQuery)
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(AmberTheme.foreground)
                .focused($searchFocused)
                .submitLabel(.search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { router.navigate(to: .search(initialQuery: searchQuery)) }
            Button("取消", action: collapseSearch)
                .font(AmberChromeFont.system(size: 13, weight: .semibold))
                .tracking(0.26)
                .foregroundStyle(AmberTheme.muted)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
        .frame(height: 41)
        .homeGlassControl(cornerRadius: 14)
        .homeGlassEffectID("homeSearch", in: homeSearchNamespace)
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(AmberTheme.focusRing, lineWidth: 1.5)
                .opacity(searchFocused ? 1 : 0)
                .allowsHitTesting(false)
        }
        .onKeyPress(.escape) {
            collapseSearch()
            return .handled
        }
    }

    /// 原型 expand 0.32s；Reduce Motion 用短 ease，避免 glass morph 晃眼。
    private var homeSearchMotion: Animation {
        if reduceMotion {
            return .easeOut(duration: 0.15)
        }
        // E 版 searchslot：cubic-bezier(0.2, 0.8, 0.2, 1) 0.32s
        return .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.32)
    }

    /// 点控制卡 / 会话区 / 空白留白时收起（不抢子按钮点击，用 simultaneousGesture）。
    private var dismissSearchOutsideTap: some Gesture {
        TapGesture().onEnded { collapseSearchIfNeeded() }
    }

    private func expandSearch() {
        searchHadFocus = false
        // skill: hierarchy 变化时 withAnimation，才能触发 glass morph
        withAnimation(homeSearchMotion) {
            isSearchExpanded = true
        }
        searchFocusTask?.cancel()
        searchFocusTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 170_000_000)
            // 170ms 内取消/收起/离场都会取消本任务，不会再写 FocusState。
            guard !Task.isCancelled, isSearchExpanded else { return }
            searchFocused = true
        }
    }

    private func collapseSearchIfNeeded() {
        guard isSearchExpanded else { return }
        collapseSearch()
    }

    private func collapseSearch() {
        searchFocusTask?.cancel()
        searchFocusTask = nil
        searchQuery = ""
        searchFocused = false
        searchHadFocus = false
        withAnimation(homeSearchMotion) {
            isSearchExpanded = false
        }
    }

    private func conversationList(_ visibleSummaries: [ConversationSummary]) -> some View {
        // `isLoading` is the observable foreground transition; background jobs
        // publish their terminal transition through `backgroundGenerationRevision`.
        // Reading both here keeps each row derived from the current owners rather
        // than preserving the off-screen NavigationStack snapshot.
        _ = chatViewModel.isLoading
        _ = backgroundGenerationRevision
        // 观察浓缩预览字典，生成完成后 meta 第二态能刷新到首页。
        _ = conversationStore.listPreviewsByConversationId
        _ = conversationStore.listIconsByConversationId
        let count = visibleSummaries.count
        return ForEach(Array(visibleSummaries.enumerated()), id: \.element.id) { index, summary in
            let isLast = index == count - 1
            ConversationSummaryRow(
                summary: summary,
                isCurrent: conversationStore.currentConversation?.id == summary.id,
                isGenerating: chatViewModel.isGenerationActive(conversationId: summary.id),
                listPreview: conversationStore.listPreview(for: summary.id),
                listIconKey: conversationStore.listIconKey(for: summary.id),
                slice: homeSlice(index: index, count: count),
                // 一体卡内：末行无底线；当前行与下一行若为当前则让 hairline 让位，避免与 accent 色带打架。
                hidesSeparator: isLast
                    || conversationStore.currentConversation?.id == summary.id
                    || (index + 1 < count
                        && conversationStore.currentConversation?.id == visibleSummaries[index + 1].id),
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
                VStack(spacing: 0) {
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
                        .accessibilityHidden(true)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            shortcutRow
        }
        // Continue 候选出现/消失：高度随 VStack 收放 + opacity（taste：离散状态 0.3s，非 spring 列表）。
        .animation(homeContinuePresenceMotion, value: homeContinueModel)
        .background {
            ZStack {
                AmberTheme.card
                // Pi/sit：卡内淡网格，避免只有露边画布有纹理、卡面一片板。
                HomeCardCanvasTexture()
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
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
        HomeShortcut(entry: .deepRead) { router.navigate(to: .board) }
            .accessibilityFocused($deepReadShortcutFocused)
        HomeShortcut(entry: .novel) { router.navigate(to: .novelCreation) }
        HomeShortcut(entry: .council) { router.navigate(to: .council) }
        HomeShortcut(entry: .miniApps) { router.navigate(to: .miniApps) }
        HomeShortcut(entry: .webMount) { router.navigate(to: .webMount) }
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
        collapseSearchIfNeeded()
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
        collapseSearchIfNeeded()
        conversationNavigationTask?.cancel()
        conversationNavigationTask = Task { @MainActor in
            guard chatViewModel.prepareForConversationChange() else { return }
            await conversationStore.startNewConversationReusingEmpty()
            guard !Task.isCancelled else { return }
            router.navigate(to: .chat)
        }
    }
}

/// 真实会话摘要行：切片一体卡（外框 + 顶/底投影），激活行 accent 色带，行间 hairline。
private struct ConversationSummaryRow: View {
    let summary: ConversationSummary
    let isCurrent: Bool
    let isGenerating: Bool
    /// LLM 浓缩预览；空则 meta 只显示时间·条数（不交错）。
    let listPreview: String
    /// 标题 LLM 选出的图标 key；nil 则回退标题关键词 / 哈希。
    var listIconKey: String? = nil
    let slice: HomeCardSlice
    let hidesSeparator: Bool
    let onTap: () -> Void
    let onRename: () -> Void
    let onTogglePin: () -> Void
    let onDelete: () -> Void
    @Environment(\.displayScale) private var displayScale
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pressed = false
    /// true = 显示浓缩预览；false = 时间·条数。仅 isCurrent 且有 preview 时轮播。
    @State private var showingListPreview = false
    @State private var metaOpacity: Double = 1
    @State private var metaCycleTask: Task<Void, Never>?
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
                    metaLine
                        .opacity(metaOpacity)
                        .padding(.trailing, dynamicTypeSize.isAccessibilitySize ? 28 : 0)
                }

                Spacer(minLength: 8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            .frame(minHeight: 72)
            .padding(.leading, 17).padding(.trailing, 16)
            .background {
                // 卡面实色 +（纹理主题）卡内淡网格 + 当前行浅染。
                // 网格叠在实色上、选中晕之下，字仍坐在不透明面上，但 session 框内有方格/点阵感。
                let fill = ZStack {
                    HomeSliceShape(slice: slice).fill(AmberTheme.card)
                    HomeCardCanvasTexture()
                        .clipShape(HomeSliceShape(slice: slice))
                    HomeSliceShape(slice: slice)
                        .fill(isCurrent ? AmberTheme.activeCard : Color.clear)
                        .animation(homeCurrentBandMotion, value: isCurrent)
                }
                // 一体卡投影：仅 top/bottom/single 携带，middle 无影防接缝。
                switch slice {
                case .single:
                    fill
                        .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
                        .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
                case .bottom:
                    fill
                        .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
                        .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
                        .mask(
                            Rectangle()
                                .padding(.horizontal, -48)
                                .padding(.bottom, -48)
                                .padding(.top, 0)
                        )
                case .top:
                    fill
                        .shadow(color: AmberTheme.cardShadowContact, radius: 1, y: 1)
                        .shadow(color: AmberTheme.cardShadowAmbient, radius: ambient.radius, y: ambient.y)
                        .mask(
                            Rectangle()
                                .padding(.horizontal, -48)
                                .padding(.top, -48)
                                .padding(.bottom, 0)
                        )
                case .middle:
                    fill
                }
            }
            .overlay {
                if pressed {
                    HomeSliceShape(slice: slice)
                        .fill(AmberTheme.press)
                        .allowsHitTesting(false)
                }
            }
            .overlay(alignment: .bottom) {
                if !hidesSeparator {
                    // 卡内 hairline：略强于旧 sep token，仍左缩进对齐标题。
                    Rectangle()
                        .fill(AmberTheme.foreground.opacity(0.08))
                        .frame(height: max(1 / displayScale, 0.5))
                        .padding(.leading, 70)
                        .padding(.trailing, 16)
                }
            }
            .padding(.horizontal, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(HomePressStateStyle(pressed: $pressed, scale: 0.98, scaleAnchor: .leading))
        .accessibilityLabel(accessibilityRowLabel)
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
        .onAppear { restartMetaCycleIfNeeded() }
        .onChange(of: isCurrent) { _, _ in restartMetaCycleIfNeeded() }
        .onChange(of: listPreview) { _, _ in restartMetaCycleIfNeeded() }
        .onChange(of: reduceMotion) { _, _ in restartMetaCycleIfNeeded() }
        .onDisappear {
            metaCycleTask?.cancel()
            metaCycleTask = nil
        }
    }

    private var accessibilityRowLabel: String {
        var label = "会话 \(displayTitle)，\(summary.messageCount) 条消息"
        if summary.isPinned { label += "，已置顶" }
        if isGenerating { label += "，正在生成" }
        // 不跟 4.2s 轮播抢读：有浓缩预览时固定附带一句，避免动态切换。
        if isCurrent, !listPreview.isEmpty { label += "，\(listPreview)" }
        return label
    }

    @ViewBuilder
    private var metaLine: some View {
        if showingListPreview, !listPreview.isEmpty, isCurrent {
            Text(listPreview)
                .font(.system(size: conversationMetadataSize, weight: .regular))
                .tracking(0.11)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
        } else {
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
        }
    }

    /// 当前行色带 / 头像色切换：短 ease，不 spring。
    private var homeCurrentBandMotion: Animation? {
        reduceMotion ? nil : .easeInOut(duration: 0.16)
    }

    private var iconView: some View {
        ZStack {
            Circle()
                .fill(isCurrent ? AmberTheme.avatarActive : AmberTheme.avatarIdle)
                .animation(homeCurrentBandMotion, value: isCurrent)
            if summary.isPinned {
                HomePhosphorIcon(.pushPin, size: 20)
                    .foregroundStyle(isCurrent ? AmberTheme.avatarActiveInk : AmberTheme.avatarIdleInk)
                    .animation(homeCurrentBandMotion, value: isCurrent)
            } else {
                HomePhosphorIcon(
                    HomeConversationIcon.icon(
                        forTitle: displayTitle,
                        isPinned: false,
                        preferredKey: listIconKey
                    ),
                    size: 20
                )
                    .foregroundStyle(isCurrent ? AmberTheme.avatarActiveInk : AmberTheme.avatarIdleInk)
                    .animation(homeCurrentBandMotion, value: isCurrent)
                    .animation(homeCurrentBandMotion, value: listIconKey)
            }
            if isGenerating {
                ConversationGeneratingRing()
            }
        }
        .frame(width: 40, height: 40)
        // 光晕在 background 且自裁 64pt：余光可见，仍尽量少渗邻行。
        .background {
            if isCurrent {
                CurrentConversationAvatarGlow()
                    .frame(
                        width: HomeCurrentAvatarBreath.clipSize,
                        height: HomeCurrentAvatarBreath.clipSize
                    )
                    .clipped()
            }
        }
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

    /// E 版 meta 淡切：~4.2s 一轮，0.4s 淡出换文；仅当前会话且有浓缩预览时启用。
    private func restartMetaCycleIfNeeded() {
        metaCycleTask?.cancel()
        metaCycleTask = nil
        showingListPreview = false
        metaOpacity = 1
        guard isCurrent, !listPreview.isEmpty, !reduceMotion else { return }
        metaCycleTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 4_200_000_000)
                guard !Task.isCancelled else { return }
                // 与 E 版 `.hm-m` 0.4s ease 淡切对齐
                withAnimation(.easeInOut(duration: 0.4)) { metaOpacity = 0 }
                try? await Task.sleep(nanoseconds: 400_000_000)
                guard !Task.isCancelled else { return }
                showingListPreview.toggle()
                withAnimation(.easeInOut(duration: 0.4)) { metaOpacity = 1 }
            }
        }
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

/// E 版原型 `hm-breathe`：当前会话头像光晕呼吸（已压低，避免 accent 下过曝）。
/// 时序仍 1.6s delay / 3.4s period；视觉只做轻余光。
enum HomeCurrentAvatarBreath {
    static let delaySeconds: TimeInterval = 1.6
    static let periodSeconds: TimeInterval = 3.4
    static let blurRadius: CGFloat = 5
    /// 相对 40pt 头像外扩（收紧，少渗邻行）。
    static let spread: CGFloat = 2
    static let clipSize: CGFloat = 52
    /// 峰值再压一层，强度曲线仍 0…1。
    static let peakOpacity: Double = 0.55

    /// 0…1。Reduce Motion 时恒为 0。
    static func intensity(elapsed: TimeInterval, reduceMotion: Bool) -> Double {
        if reduceMotion { return 0 }
        guard elapsed >= delaySeconds else { return 0 }
        let phase = ((elapsed - delaySeconds) / periodSeconds)
            .truncatingRemainder(dividingBy: 1)
        return 0.5 - 0.5 * cos(phase * 2 * .pi)
    }
}

/// 仅挂在 `isCurrent` 会话头像后：轻量外溢光晕，不参与布局命中。
private struct CurrentConversationAvatarGlow: View {
    @State private var start = Date()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { context in
            let intensity = HomeCurrentAvatarBreath.intensity(
                elapsed: context.date.timeIntervalSince(start),
                reduceMotion: reduceMotion
            )
            let diameter = 40 + HomeCurrentAvatarBreath.spread * 2
            // 单环 soft blur，去掉双层叠晕（叠晕在 accent 下易过曝）。
            Circle()
                .fill(AmberTheme.activeAvatarGlow)
                .frame(width: diameter, height: diameter)
                .blur(radius: HomeCurrentAvatarBreath.blurRadius)
                .opacity(intensity * HomeCurrentAvatarBreath.peakOpacity)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
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

struct SettingsHomeView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.system.rawValue

    // 方案 B：全表统一 accent 图标 + accentTint 浅底，取消彩虹 per-row 色。
    private var generalEntries: [SettingsHomeEntry] {
        [
            .init(title: "外观", value: appearanceModeTitle, systemImage: "circle.lefthalf.filled", route: .appearance),
            .init(title: "显示与字体", systemImage: "slider.horizontal.3", route: .displayFont)
        ]
    }

    private var agentEntries: [SettingsHomeEntry] {
        [
            .init(title: "核心记忆", systemImage: "cylinder.split.1x2", route: .memory),
            .init(title: "运行环境", systemImage: "terminal", route: .execution),
            .init(title: "技能", systemImage: "wrench.and.screwdriver", route: .skills),
            .init(title: "权限与批准", systemImage: "shield", route: .toolPermissions)
        ]
    }

    private var modelServiceEntries: [SettingsHomeEntry] {
        [
            .init(title: "服务商", systemImage: "server.rack", route: .providers),
            .init(title: "模型与提示词", systemImage: "cpu", route: .modelDefaults),
            .init(title: "搜索服务", systemImage: "magnifyingglass", route: .searchServices),
            .init(title: "语音服务", systemImage: "speaker.wave.2", route: .ttsSettings)
        ]
    }

    private var advancedFeatureEntries: [SettingsHomeEntry] {
        [
            .init(title: "WebMount", systemImage: "globe", route: .webMount),
            .init(title: "子代理", systemImage: "person.2", route: .subagents),
            .init(title: "模型议会", systemImage: "bubble.left.and.bubble.right", route: .council),
            .init(title: "小应用", systemImage: "square.grid.2x2", route: .miniApps),
            .init(title: "小说创作", systemImage: "text.book.closed", route: .novelCreation),
            .init(title: "深度阅读", systemImage: "book.pages", route: .board)
        ]
    }

    private var dataEntries: [SettingsHomeEntry] {
        [
            .init(title: "Workspace", systemImage: "folder.badge.gearshape", route: .workspace),
            .init(title: "同步备份", systemImage: "icloud", route: .syncBackup),
            .init(title: "对话存储", systemImage: "tray.full", route: .conversationStorage)
        ]
    }

    private var appearanceModeTitle: String {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).title
    }

    /// 与首页顶栏账户入口同源：昵称首字，空昵称回落 "A"。
    private var accountInitial: String {
        let trimmed = sharedSettings.displaySetting.userNickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? "A" : trimmed).prefix(1)).uppercased()
    }

    var body: some View {
        // snapshot 为 @ObservationIgnored；读 revision 才能在改昵称后刷新头像首字。
        let _ = sharedSettings.revision
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

            // 右上点缀：与首页头像同组件、同 .account 路由；44 占位与返回钮对称，头像本体 38。
            Button {
                router.navigate(to: .account)
            } label: {
                HomeAccountAvatar(initial: accountInitial, size: 38)
            }
            .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.92, haptic: .lightImpact))
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .accessibilityLabel("我的账户")
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
                        iconColor: AmberTheme.accent,
                        iconUsesAccentPlate: true,
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
                            // 与行内文字起点对齐：hPad 14 + icon 28 + spacing 12
                            .padding(.leading, 54)
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
    /// 标题在本页唯一，作稳定 id（避免每次 entries 重算换 UUID）。
    var id: String { title }
    let title: String
    let subtitle: String?
    let value: String?
    let systemImage: String
    let route: Route

    init(
        title: String,
        subtitle: String? = nil,
        value: String? = nil,
        systemImage: String,
        route: Route
    ) {
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.systemImage = systemImage
        self.route = route
    }
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
