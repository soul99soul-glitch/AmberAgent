import SwiftUI
import XCTest
@testable import iosApp

@MainActor
final class AmberThemePackTests: XCTestCase {
    private var runtime: AmberThemeRuntime { .shared }
    private var savedPaper: AmberThemeRuntime.Paper!
    private var savedAccent: UInt32 = 0
    private var savedInk: UInt32 = 0
    private var savedCanvas: AmberCanvasStyle = .flat
    private var savedBrand: AmberBrandMarkStyle = .systemWordmark
    private var savedShortcut: AmberShortcutIconStyle = .phosphorFill
    private var savedChrome: AmberChromeTypeface = .system
    private var savedCanvasScope: AmberCanvasScope = .homeOnly
    private var savedBubble: AmberBubbleChrome = .standard
    private var savedGlass: AmberGlassChrome = .standard
    private var savedEmpty: AmberEmptyArtStyle = .none
    private var savedSettingsChrome = false
    private var savedLaunch: AmberLaunchBrandStyle = .none
    private var savedAsset: AmberThemeAssetMode = .builtinOnly
    private var savedImmersive: AmberImmersivePolicy = .hidden

    override func setUp() {
        super.setUp()
        savedPaper = runtime.paper
        savedAccent = runtime.accentHex
        savedInk = runtime.accentInkHex
        savedCanvas = runtime.canvasStyle
        savedBrand = runtime.brandMarkStyle
        savedShortcut = runtime.shortcutIconStyle
        savedChrome = runtime.chromeTypeface
        savedCanvasScope = runtime.canvasScope
        savedBubble = runtime.bubbleChrome
        savedGlass = runtime.glassChrome
        savedEmpty = runtime.emptyArt
        savedSettingsChrome = runtime.settingsChrome
        savedLaunch = runtime.launchBrand
        savedAsset = runtime.assetMode
        savedImmersive = runtime.immersivePolicy
    }

    override func tearDown() {
        runtime.paper = savedPaper
        runtime.accentHex = savedAccent
        runtime.accentInkHex = savedInk
        runtime.canvasStyle = savedCanvas
        runtime.brandMarkStyle = savedBrand
        runtime.shortcutIconStyle = savedShortcut
        runtime.chromeTypeface = savedChrome
        runtime.canvasScope = savedCanvasScope
        runtime.bubbleChrome = savedBubble
        runtime.glassChrome = savedGlass
        runtime.emptyArt = savedEmpty
        runtime.settingsChrome = savedSettingsChrome
        runtime.launchBrand = savedLaunch
        runtime.assetMode = savedAsset
        runtime.immersivePolicy = savedImmersive
        super.tearDown()
    }

    func testBuiltinsUniqueIdsAndPaperAccentPairs() {
        XCTAssertEqual(AmberThemePack.builtins.count, 3)
        XCTAssertEqual(
            Set(AmberThemePack.builtins.map(\.id)),
            Set(["sit-terracotta", "pi-steel", "notion-blue"])
        )
        var ids = Set<String>()
        var pairs = Set<String>()
        for pack in AmberThemePack.builtins {
            XCTAssertFalse(pack.paper.isImmersive, pack.id)
            XCTAssertTrue(ids.insert(pack.id).inserted, "duplicate id \(pack.id)")
            let pair = [
                pack.paper.rawValue,
                String(pack.accent.accentHex),
                pack.canvasStyle.rawValue,
                pack.brandMark.rawValue,
                pack.shortcutIconStyle.rawValue,
                pack.chromeTypeface.rawValue,
            ].joined(separator: "|")
            XCTAssertTrue(pairs.insert(pair).inserted, "duplicate full slot recipe \(pair)")
        }
    }

    func testBuiltinsAreCharacterPacksOnly() {
        for pack in AmberThemePack.builtins {
            // Classic paper×accent grids are custom; builtins must carry a distinctive recipe.
            XCTAssertTrue(
                pack.hasCharacterStyles
                    || pack.glassChrome != .standard
                    || pack.paper == .notion
                    || pack.paper == .pi,
                "\(pack.id) should remain a character / branded recipe"
            )
        }
    }

    func testSitTerracottaCharacterPackSlots() throws {
        let sit = try XCTUnwrap(AmberThemePack.builtins.first { $0.id == "sit-terracotta" })
        XCTAssertEqual(sit.displayName, "点阵 · 陶土")
        XCTAssertEqual(sit.paper, .paper)
        XCTAssertEqual(sit.accent, .terracotta)
        XCTAssertEqual(sit.canvasStyle, .dotGrid)
        XCTAssertEqual(sit.brandMark, .paintAMBER)
        XCTAssertEqual(sit.shortcutIconStyle, .pixelSit)
        XCTAssertEqual(sit.chromeTypeface, .rounded)
        XCTAssertEqual(sit.canvasScope, .shell)
        XCTAssertEqual(sit.bubbleChrome, .soft)
        XCTAssertEqual(sit.glassChrome, .quieter)
        XCTAssertEqual(sit.emptyArt, .character)
        XCTAssertFalse(sit.settingsChrome)
        XCTAssertEqual(sit.launchBrand, .none)
        XCTAssertTrue(sit.hasCharacterStyles)
    }

    func testPiSteelDotgridPackFromOpenDesign() throws {
        let pi = try XCTUnwrap(AmberThemePack.builtins.first { $0.id == "pi-steel" })
        XCTAssertEqual(pi.displayName, "点阵 · Pi")
        XCTAssertEqual(pi.paper, .pi)
        XCTAssertEqual(pi.accent, .steelBlue)
        XCTAssertEqual(pi.accent.accentHex, 0x6B8CAD)
        XCTAssertEqual(pi.accent.inkHex, 0xFAF9F7)
        XCTAssertEqual(pi.canvasStyle, .lineGrid)
        XCTAssertTrue(pi.canvasStyle.hasTexture)
        XCTAssertEqual(pi.brandMark, .serifWordmark)
        XCTAssertEqual(pi.shortcutIconStyle, .phosphorFill)
        XCTAssertEqual(pi.chromeTypeface, .monospace)
        XCTAssertEqual(pi.canvasScope, .shell)
        XCTAssertEqual(pi.glassChrome, .quieter)
        XCTAssertEqual(pi.emptyArt, .character)
        XCTAssertEqual(pi.bubbleChrome, .standard)
        // P2: mono chrome also drives Appearance section labels.
        XCTAssertTrue(pi.settingsChrome)
        XCTAssertEqual(AmberTheme.piLight.background, 0xF3F0EB)
        XCTAssertEqual(AmberTheme.piLight.surface, 0xFAF9F7)
        runtime.apply(pi)
        XCTAssertEqual(runtime.matchingPack?.id, "pi-steel")
        XCTAssertEqual(runtime.canvasStyle, .lineGrid)
        XCTAssertEqual(runtime.chromeTypeface, .monospace)
        XCTAssertTrue(runtime.settingsChrome)
        XCTAssertTrue(runtime.showsCanvasTexture(on: .home))
        XCTAssertTrue(runtime.showsCanvasTexture(on: .shell))
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app), "Pi chat 只留纸色，方格不进 app 面")
        XCTAssertFalse(runtime.paper.isImmersive)
    }

    func testApplyMatchAndInkForAllBuiltins() {
        for pack in AmberThemePack.builtins {
            runtime.apply(pack)
            XCTAssertEqual(runtime.paper, pack.paper, pack.id)
            XCTAssertEqual(runtime.accentHex, pack.accent.accentHex, pack.id)
            XCTAssertEqual(runtime.accentInkHex, pack.accent.inkHex, pack.id)
            XCTAssertEqual(runtime.canvasStyle, pack.canvasStyle, pack.id)
            XCTAssertEqual(runtime.brandMarkStyle, pack.brandMark, pack.id)
            XCTAssertEqual(runtime.shortcutIconStyle, pack.shortcutIconStyle, pack.id)
            XCTAssertEqual(runtime.chromeTypeface, pack.chromeTypeface, pack.id)
            XCTAssertEqual(runtime.canvasScope, pack.canvasScope, pack.id)
            XCTAssertEqual(runtime.bubbleChrome, pack.bubbleChrome, pack.id)
            XCTAssertEqual(runtime.glassChrome, pack.glassChrome, pack.id)
            XCTAssertEqual(runtime.emptyArt, pack.emptyArt, pack.id)
            XCTAssertEqual(runtime.settingsChrome, pack.settingsChrome, pack.id)
            XCTAssertEqual(runtime.launchBrand, pack.launchBrand, pack.id)
            XCTAssertEqual(runtime.matchingPack?.id, pack.id, pack.id)
            XCTAssertFalse(runtime.isCustomCombination, pack.id)
        }
    }

    func testCanvasScopeSurfaces() {
        runtime.canvasScope = .homeOnly
        XCTAssertTrue(runtime.showsCanvasTexture(on: .home))
        XCTAssertFalse(runtime.showsCanvasTexture(on: .shell))
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app))
        runtime.canvasScope = .shell
        XCTAssertTrue(runtime.showsCanvasTexture(on: .shell))
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app))
        runtime.canvasScope = .appWide
        XCTAssertTrue(runtime.showsCanvasTexture(on: .app))
    }

    /// Re-applying builtin Pi collapses legacy chat-wide grid to shell (paper color only on `.app`).
    func testPiBuiltinClearsAppWideChatTexture() {
        runtime.paper = .pi
        runtime.canvasStyle = .lineGrid
        runtime.canvasScope = .appWide
        XCTAssertTrue(runtime.showsCanvasTexture(on: .app))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "pi-steel" }!)
        XCTAssertEqual(runtime.canvasScope, .shell)
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app))
        XCTAssertTrue(runtime.showsCanvasTexture(on: .home))
        XCTAssertEqual(runtime.matchingPack?.id, "pi-steel")
    }

    func testCustomWhenAccentDiverges() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")
        runtime.apply(.mistBlue)
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testCustomWhenPaperDiverges() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        runtime.paper = .white
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testCustomWhenStyleSlotDiverges() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")
        runtime.canvasStyle = .dotGrid
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)

        runtime.canvasStyle = .flat
        runtime.brandMarkStyle = .paintAMBER
        XCTAssertNil(runtime.matchingPack)

        runtime.brandMarkStyle = .systemWordmark
        runtime.shortcutIconStyle = .pixelSit
        XCTAssertNil(runtime.matchingPack)

        runtime.shortcutIconStyle = .phosphorFill
        runtime.chromeTypeface = .rounded
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testCustomWhenChromeTypefaceDivergesFromSit() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "sit-terracotta")
        runtime.chromeTypeface = .system
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testSitPackColorOnlyIsCustom() {
        runtime.paper = .paper
        runtime.apply(.terracotta)
        runtime.canvasStyle = .flat
        runtime.brandMarkStyle = .systemWordmark
        runtime.shortcutIconStyle = .phosphorFill
        runtime.chromeTypeface = .system
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)

        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "sit-terracotta")
    }

    func testRematchAfterCustomReturnsToBuiltin() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        runtime.apply(.rose)
        XCTAssertTrue(runtime.isCustomCombination)
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")
    }

    func testApplyStylePackWritesAllSlotsIncludingChrome() {
        let sit = AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!
        runtime.apply(sit)
        XCTAssertEqual(runtime.paper, .paper)
        XCTAssertEqual(runtime.accentHex, AmberAccentOption.terracotta.accentHex)
        XCTAssertEqual(runtime.canvasStyle, .dotGrid)
        XCTAssertEqual(runtime.brandMarkStyle, .paintAMBER)
        XCTAssertEqual(runtime.shortcutIconStyle, .pixelSit)
        XCTAssertEqual(runtime.chromeTypeface, .rounded)
        XCTAssertEqual(runtime.canvasScope, .shell)
        XCTAssertEqual(runtime.matchingPack?.id, "sit-terracotta")
    }

    func testNotionBlueBlankWorkspacePack() throws {
        let pack = try XCTUnwrap(AmberThemePack.builtins.first { $0.id == "notion-blue" })
        XCTAssertEqual(pack.displayName, "Notion · 暖白")
        XCTAssertEqual(pack.paper, .notion)
        XCTAssertEqual(pack.accent, .notionBlue)
        XCTAssertEqual(pack.accent.accentHex, 0x0075DE)
        XCTAssertEqual(pack.accent.inkHex, 0xFFFFFF)
        XCTAssertEqual(pack.canvasStyle, .flat)
        XCTAssertEqual(pack.brandMark, .systemWordmark)
        XCTAssertEqual(pack.shortcutIconStyle, .phosphorFill)
        XCTAssertEqual(pack.canvasScope, .homeOnly)
        XCTAssertEqual(pack.glassChrome, .quieter)
        // quieter glass is surface polish, not sit/pixel character — still flat + system mark.
        XCTAssertEqual(pack.canvasStyle, .flat)
        XCTAssertEqual(pack.brandMark, .systemWordmark)
        XCTAssertEqual(AmberTheme.notionLight.background, 0xF6F5F4)
        XCTAssertEqual(AmberTheme.notionLight.surface, 0xFFFFFF)
        XCTAssertEqual(AmberTheme.notionLight.surface2, 0xEFEEEC)
        XCTAssertEqual(AmberTheme.notionLight.foreground, 0x1A1918)
        XCTAssertEqual(AmberTheme.notionLight.muted, 0x615D59)
        XCTAssertEqual(AmberTheme.notionLight.muted2, 0xA39E98)
        XCTAssertFalse(pack.paper.isImmersive)

        runtime.apply(pack)
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")
        XCTAssertEqual(runtime.glassChrome, .quieter)
        XCTAssertEqual(runtime.paper, .notion)

        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        try runtime.apply(AmberThemePackTransfer.decode(data))
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")
        XCTAssertEqual(runtime.paper, .notion)
        XCTAssertEqual(runtime.accentHex, 0x0075DE)
        XCTAssertEqual(runtime.accentInkHex, 0xFFFFFF)
    }

    func testExportImportRoundTripPiSteel() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "pi-steel" }!)
        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")

        let doc = try AmberThemePackTransfer.decode(data)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.matchingPack?.id, "pi-steel")
        XCTAssertEqual(runtime.paper, .pi)
        XCTAssertEqual(runtime.brandMarkStyle, .serifWordmark)
        XCTAssertEqual(runtime.accentHex, 0x6B8CAD)
        XCTAssertEqual(runtime.accentInkHex, 0xFAF9F7)
        XCTAssertEqual(runtime.canvasStyle, .lineGrid)
        XCTAssertEqual(runtime.canvasScope, .shell)
        XCTAssertEqual(runtime.chromeTypeface, .monospace)
        XCTAssertTrue(runtime.settingsChrome)
        XCTAssertEqual(runtime.glassChrome, .quieter)
        XCTAssertEqual(runtime.emptyArt, .character)
    }

    func testExportIncludesOptionalSlotsAndLegacyImportDefaults() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        let doc = try AmberThemePackTransfer.decode(data)
        XCTAssertEqual(doc.canvasScope, "shell")
        XCTAssertEqual(doc.bubbleChrome, "soft")

        // Legacy classic paper×accent document: still imports as custom (no longer a builtin).
        let legacy = """
        {
          "format": "amber.theme.pack",
          "version": 1,
          "id": "warm-amber",
          "displayName": "暖灰 · 琥珀",
          "paper": "neutral",
          "accentHex": "0xB9863A",
          "inkHex": "0x231602",
          "canvasStyle": "flat",
          "brandMark": "systemWordmark",
          "shortcutIconStyle": "phosphorFill",
          "chromeTypeface": "system"
        }
        """
        let legacyDoc = try AmberThemePackTransfer.decode(Data(legacy.utf8))
        try runtime.apply(legacyDoc)
        XCTAssertEqual(runtime.canvasScope, .homeOnly)
        XCTAssertEqual(runtime.bubbleChrome, .standard)
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
        XCTAssertEqual(runtime.paper, .neutral)
        XCTAssertEqual(runtime.accentHex, 0xB9863A)
    }

    func testChromeTypefaceDesigns() {
        XCTAssertEqual(AmberChromeTypeface.system.design, Font.Design.default)
        XCTAssertEqual(AmberChromeTypeface.rounded.design, Font.Design.rounded)
        XCTAssertEqual(AmberChromeTypeface.serif.design, Font.Design.serif)
        XCTAssertEqual(AmberChromeTypeface.monospace.design, Font.Design.monospaced)
        // Smoke: factory returns a Font without trapping.
        _ = AmberChromeTypeface.rounded.font(size: 12, weight: .semibold)
        _ = AmberChromeTypeface.monospace.font(size: 11, weight: .semibold)
        _ = AmberChromeFont.system(size: 11, weight: .semibold)
    }

    // MARK: - P2 reserved slots

    func testEveryCanvasStyleHasVisibleTextureOrIsFlat() throws {
        for style in AmberCanvasStyle.allCases {
            switch style {
            case .flat:
                XCTAssertFalse(style.hasTexture)
            case .dotGrid, .lineGrid, .paperGrain:
                XCTAssertTrue(style.hasTexture, "\(style.rawValue) must paint a texture")
            }
        }
        let packSource = try source("iosApp/AmberThemePack.swift")
        XCTAssertTrue(packSource.contains("AmberPaperGrainOverlay()"))
        XCTAssertFalse(
            packSource.contains("case .paperGrain:\n            EmptyView()"),
            "paperGrain must not be an empty canvas overlay"
        )
    }

    func testPaperGrainInkAlphaStaysQuiet() {
        // Below dot-grid dark 0.08 / light 0.055 so grain never dirties session titles.
        XCTAssertLessThan(AmberPaperGrainOverlay.lightAlpha, AmberDotGridOverlay.lightAlpha)
        XCTAssertLessThan(AmberPaperGrainOverlay.darkAlpha, AmberDotGridOverlay.darkAlpha)
        XCTAssertGreaterThan(AmberPaperGrainOverlay.lightAlpha, 0.02)
        XCTAssertGreaterThan(AmberPaperGrainOverlay.darkAlpha, 0.03)
    }

    /// Session / control card in-card lattice must stay quieter than page gutters (Pi “框透太多”).
    func testHomeCardCanvasTextureStaysQuiet() throws {
        XCTAssertLessThanOrEqual(HomeCardCanvasTexture.lineGridOpacity, 0.28)
        XCTAssertLessThanOrEqual(HomeCardCanvasTexture.dotGridOpacity, 0.35)
        XCTAssertLessThanOrEqual(HomeCardCanvasTexture.paperGrainOpacity, 0.40)
        let home = try source("iosApp/PlaceholderViews.swift")
        XCTAssertTrue(
            home.contains("themeRuntime.canvasStyle.hasTexture ? .hard : .soft")
                || home.contains("AmberThemeRuntime.shared.canvasStyle.hasTexture ? .hard : .soft"),
            "textured home must use hard bottom scroll edge so session shell stays opaque"
        )
    }

    func testSettingsChromeGateFollowsRuntimeFlag() {
        runtime.chromeTypeface = .monospace
        runtime.settingsChrome = false
        XCTAssertNil(AmberChromeFont.settingsPackDesign)
        runtime.settingsChrome = true
        XCTAssertEqual(AmberChromeFont.settingsPackDesign, .monospaced)
        runtime.chromeTypeface = .rounded
        XCTAssertEqual(AmberChromeFont.settingsPackDesign, .rounded)
    }

    func testAppearanceSettingsUsesGatedChromeFont() throws {
        let text = try source("iosApp/AppearanceSettingsView.swift")
        XCTAssertTrue(text.contains("AmberChromeFont.settings(size: 12, weight: .semibold)"))
        XCTAssertTrue(text.contains("AmberChromeFont.settings(.headline, weight: .semibold)"))
        XCTAssertTrue(text.contains("AmberChromeFont.settings(.subheadline, weight: .semibold)"))
        let settingsFontCount = text.components(separatedBy: "AmberChromeFont.settings(").count - 1
        XCTAssertGreaterThanOrEqual(settingsFontCount, 3)
    }

    func testAppearanceModeSectionComesBeforeThemeSection() throws {
        let text = try source("iosApp/AppearanceSettingsView.swift")
        guard
            let modeRange = text.range(of: "section(\"外观模式\")"),
            let themeRange = text.range(of: "section(\"主题\")")
        else {
            return XCTFail("AppearanceSettingsView must declare 外观模式 and 主题 sections")
        }
        XCTAssertLessThan(
            modeRange.lowerBound,
            themeRange.lowerBound,
            "外观模式应置顶，主题在其下"
        )
    }

    func testAppearanceMiniPreviewPinsLightColorSchemeForCanvasInk() throws {
        // Light-recipe cards must not resolve overlay ink against dark Appearance traits.
        let text = try source("iosApp/AppearanceSettingsView.swift")
        XCTAssertTrue(text.contains("AmberDotGridOverlay()"))
        XCTAssertTrue(text.contains("AmberPaperGrainOverlay()"))
        XCTAssertTrue(
            text.contains(".environment(\\.colorScheme, .light)"),
            "miniPreview canvas overlays must pin light so grain/grid stay visible in dark Appearance"
        )
    }

    func testAssetModeRemainsBuiltinOnly() {
        XCTAssertEqual(AmberThemeAssetMode.allCases.map(\.rawValue), ["builtinOnly"])
    }

    func testImmersivePolicyRemainsHiddenOnly() {
        XCTAssertEqual(AmberImmersivePolicy.allCases.map(\.rawValue), ["hidden"])
    }

    func testLaunchBrandMatchIsWiredOnAccount() throws {
        let account = try source("iosApp/AccountView.swift")
        XCTAssertTrue(account.contains("launchBrand == .matchBrand"))
        XCTAssertTrue(account.contains("AmberBrandMarkView()"))
        // Packs keep none (dual-brand risk); import of matchBrand still works.
        for pack in AmberThemePack.builtins {
            XCTAssertEqual(pack.launchBrand, .none, pack.id)
        }
    }

    func testImportPaperGrainAppliesCanvasAndScope() throws {
        let json = """
        {"format":"amber.theme.pack","version":1,"id":"grain","displayName":"grain","paper":"paper",
         "accentHex":"0xB9863A","inkHex":"0x231602","canvasStyle":"paperGrain","canvasScope":"shell",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system"}
        """
        try runtime.apply(try AmberThemePackTransfer.decode(Data(json.utf8)))
        XCTAssertEqual(runtime.canvasStyle, .paperGrain)
        XCTAssertTrue(runtime.canvasStyle.hasTexture)
        XCTAssertEqual(runtime.canvasScope, .shell)
        XCTAssertTrue(runtime.showsCanvasTexture(on: .home))
        XCTAssertTrue(runtime.showsCanvasTexture(on: .shell))
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app))
    }

    func testImportSettingsChromeMonospaceWithoutBuiltinPack() throws {
        let json = """
        {"format":"amber.theme.pack","version":1,"id":"custom-mono","displayName":"custom","paper":"neutral",
         "accentHex":"0xB9863A","inkHex":"0x231602","canvasStyle":"flat",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"monospace",
         "settingsChrome":true}
        """
        try runtime.apply(try AmberThemePackTransfer.decode(Data(json.utf8)))
        XCTAssertTrue(runtime.settingsChrome)
        XCTAssertEqual(runtime.chromeTypeface, .monospace)
        XCTAssertEqual(AmberChromeFont.settingsPackDesign, .monospaced)
        XCTAssertNil(runtime.matchingPack)
    }

    func testImportLaunchBrandMatchBrand() throws {
        let json = """
        {"format":"amber.theme.pack","version":1,"id":"branded","displayName":"branded","paper":"paper",
         "accentHex":"0xB8623A","inkHex":"0xFFFFFF","canvasStyle":"dotGrid",
         "brandMark":"paintAMBER","shortcutIconStyle":"phosphorFill","chromeTypeface":"rounded",
         "launchBrand":"matchBrand"}
        """
        try runtime.apply(try AmberThemePackTransfer.decode(Data(json.utf8)))
        XCTAssertEqual(runtime.launchBrand, .matchBrand)
        XCTAssertEqual(runtime.brandMarkStyle, .paintAMBER)
    }

    func testImportRejectsUnknownAssetMode() {
        let json = """
        {"format":"amber.theme.pack","version":1,"id":"x","displayName":"x","paper":"neutral",
         "accentHex":"0xB9863A","inkHex":"0x231602","canvasStyle":"flat",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system",
         "assetMode":"zipPack"}
        """
        XCTAssertThrowsError(try AmberThemePackTransfer.decode(Data(json.utf8))) { error in
            XCTAssertEqual(
                error as? AmberThemePackTransferError,
                .unknownOptionalSlot("assetMode=zipPack")
            )
        }
    }

    func testApplyThemeDoesNotTouchChatBodyFontKeys() {
        let d = UserDefaults.standard
        let scaleKey = IOSDisplayPreferenceKeys.fontScale
        let fontKey = IOSDisplayPreferenceKeys.chatFont
        let prevScale = d.object(forKey: scaleKey)
        let prevFont = d.object(forKey: fontKey)
        defer {
            if let prevScale { d.set(prevScale, forKey: scaleKey) } else { d.removeObject(forKey: scaleKey) }
            if let prevFont { d.set(prevFont, forKey: fontKey) } else { d.removeObject(forKey: fontKey) }
        }

        d.set(1.18, forKey: scaleKey)
        d.set(IOSChatFont.serif.rawValue, forKey: fontKey)

        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)

        XCTAssertEqual(d.double(forKey: scaleKey), 1.18, accuracy: 0.0001)
        XCTAssertEqual(d.string(forKey: fontKey), IOSChatFont.serif.rawValue)
    }

    func testHomeShortcutEntriesCoverFiveRoutes() {
        XCTAssertEqual(HomeShortcutEntry.allCases.count, 5)
        let phosphors = Set(HomeShortcutEntry.allCases.map(\.phosphor))
        XCTAssertEqual(phosphors.count, 5, "five distinct phosphor glyphs")
        XCTAssertFalse(HomeShortcutEntry.deepRead.title.isEmpty)
    }

    func testPixelSitGlyphsNonEmptyAndDistinct() {
        var signatures = Set<String>()
        for entry in HomeShortcutEntry.allCases {
            let bits = HomePixelSitGlyph.bits(for: entry)
            XCTAssertEqual(bits.count, 16, entry.rawValue)
            let on = bits.reduce(0) { $0 + $1.nonzeroBitCount }
            XCTAssertGreaterThan(on, 8, "\(entry) glyph too sparse")
            let sig = bits.map { String($0, radix: 16) }.joined(separator: ",")
            XCTAssertTrue(signatures.insert(sig).inserted, "duplicate pixel glyph for \(entry)")
        }
    }

    func testPixelWordmarkAMBERNonEmpty() {
        let bits = AmberPixelWordmark.amber
        XCTAssertEqual(bits.count, AmberPixelWordmark.letterRows)
        let on = bits.reduce(0) { $0 + $1.nonzeroBitCount }
        // 5 letters × ~12–20 pixels each → well above empty.
        XCTAssertGreaterThan(on, 40)
        XCTAssertGreaterThan(AmberPixelWordmark.displayWidth, 80)
        XCTAssertGreaterThan(AmberPixelWordmark.displayHeight, 20)
    }

    func testUnknownPersistedStyleFallsBackToDefault() {
        XCTAssertEqual(AmberCanvasStyle(rawValue: "not-a-style") ?? .flat, .flat)
        XCTAssertEqual(AmberBrandMarkStyle(rawValue: "nope") ?? .systemWordmark, .systemWordmark)
        XCTAssertEqual(AmberShortcutIconStyle(rawValue: "nope") ?? .phosphorFill, .phosphorFill)
        XCTAssertEqual(AmberChromeTypeface(rawValue: "nope") ?? .system, .system)
    }

    // MARK: - Import / export

    func testExportImportRoundTripSitPack() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")

        let doc = try AmberThemePackTransfer.decode(data)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.matchingPack?.id, "sit-terracotta")
        XCTAssertEqual(runtime.canvasStyle, .dotGrid)
        XCTAssertEqual(runtime.brandMarkStyle, .paintAMBER)
        XCTAssertEqual(runtime.shortcutIconStyle, .pixelSit)
        XCTAssertEqual(runtime.chromeTypeface, .rounded)
        XCTAssertEqual(runtime.accentHex, AmberAccentOption.terracotta.accentHex)
    }

    func testExportImportRoundTripCustomHexAccent() throws {
        runtime.paper = .white
        runtime.accentHex = 0x334455
        runtime.accentInkHex = 0xFFFFFF
        runtime.canvasStyle = .flat
        runtime.brandMarkStyle = .systemWordmark
        runtime.shortcutIconStyle = .phosphorFill
        runtime.chromeTypeface = .system

        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)

        let doc = try AmberThemePackTransfer.decode(data)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.paper, .white)
        XCTAssertEqual(runtime.accentHex, 0x334455)
        XCTAssertEqual(runtime.accentInkHex, 0xFFFFFF)
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testMatchingPackIncludesAccentInkColor() {
        let pack = AmberThemePack.builtins[0]
        runtime.apply(pack)
        runtime.accentInkHex ^= 0x000001

        XCTAssertFalse(pack.matches(runtime: runtime))
    }

    func testImportAcceptsHashAndBareHex() throws {
        let json = """
        {
          "format": "amber.theme.pack",
          "version": 1,
          "id": "hex-variants",
          "displayName": "Hex",
          "paper": "neutral",
          "accentHex": "#B9863A",
          "inkHex": "231602",
          "canvasStyle": "flat",
          "brandMark": "systemWordmark",
          "shortcutIconStyle": "phosphorFill",
          "chromeTypeface": "system"
        }
        """
        let doc = try AmberThemePackTransfer.decode(Data(json.utf8))
        try runtime.apply(doc)
        XCTAssertEqual(runtime.accentHex, 0xB9863A)
        XCTAssertEqual(runtime.accentInkHex, 0x231602)
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testImportRejectsBadFormatImmersiveAndUnknownSlots() {
        let badFormat = """
        {"format":"other","version":1,"id":"x","displayName":"x","paper":"neutral",
         "accentHex":"0x111111","inkHex":"0xFFFFFF","canvasStyle":"flat",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system"}
        """
        XCTAssertThrowsError(try AmberThemePackTransfer.decode(Data(badFormat.utf8))) { error in
            XCTAssertEqual(error as? AmberThemePackTransferError, .invalidFormat("other"))
        }

        let immersive = """
        {"format":"amber.theme.pack","version":1,"id":"x","displayName":"x","paper":"garnet",
         "accentHex":"0x111111","inkHex":"0xFFFFFF","canvasStyle":"flat",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system"}
        """
        XCTAssertThrowsError(try AmberThemePackTransfer.decode(Data(immersive.utf8))) { error in
            XCTAssertEqual(error as? AmberThemePackTransferError, .immersivePaper("garnet"))
        }

        let badStyle = """
        {"format":"amber.theme.pack","version":1,"id":"x","displayName":"x","paper":"neutral",
         "accentHex":"0x111111","inkHex":"0xFFFFFF","canvasStyle":"neonGrid",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system"}
        """
        XCTAssertThrowsError(try AmberThemePackTransfer.decode(Data(badStyle.utf8))) { error in
            XCTAssertEqual(error as? AmberThemePackTransferError, .unknownCanvasStyle("neonGrid"))
        }
    }

    func testWriteExportFileRoundTrip() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        let url = try AmberThemePackTransfer.writeExportFile(from: runtime)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertTrue(url.lastPathComponent.contains("sit-terracotta"))
        let data = try Data(contentsOf: url)
        let doc = try AmberThemePackTransfer.decode(data)
        XCTAssertEqual(doc.id, "sit-terracotta")
        runtime.apply(AmberThemePack.builtins.first { $0.id == "pi-steel" }!)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.matchingPack?.id, "sit-terracotta")
    }

    // MARK: - P1 dark palettes + import contrast

    func testNonImmersivePapersHaveDistinctDarkBackgrounds() {
        let papers: [AmberThemeRuntime.Paper] = [.paper, .neutral, .white, .pi, .notion]
        var backgrounds = Set<UInt32>()
        for paper in papers {
            let dark = paper.darkPalette
            XCTAssertNotEqual(dark.background, dark.surface, "\(paper.rawValue) dark bg≠surface")
            XCTAssertNotEqual(dark.surface, dark.surface2, "\(paper.rawValue) dark surface≠surface2")
            XCTAssertNotEqual(dark.background, dark.surface2, "\(paper.rawValue) dark bg≠surface2")
            XCTAssertGreaterThan(
                AmberColorContrast.contrastRatio(dark.foreground, dark.background),
                4.5,
                "\(paper.rawValue) dark text AA"
            )
            XCTAssertTrue(backgrounds.insert(dark.background).inserted, "duplicate dark bg \(paper.rawValue)")
        }
        // E-edition canonical dark remains the warm-gray / neutral workbench.
        XCTAssertEqual(AmberThemeRuntime.Paper.neutral.darkPalette.background, AmberTheme.darkPalette.background)
        XCTAssertEqual(AmberTheme.darkPalette.background, 0x0E0D10)
    }

    func testNonNeutralDarkPaletteDesignValues() {
        // Lock the four split tables so they cannot silent-drift to neutral.
        XCTAssertEqual(AmberTheme.paperDark.background, 0x14110E)
        XCTAssertEqual(AmberTheme.paperDark.surface, 0x221E19)
        XCTAssertEqual(AmberTheme.paperDark.surface2, 0x2E2822)
        XCTAssertEqual(AmberTheme.whiteDark.background, 0x111111)
        XCTAssertEqual(AmberTheme.whiteDark.surface2, 0x282828)
        XCTAssertEqual(AmberTheme.piDark.background, 0x12110F)
        XCTAssertEqual(AmberTheme.piDark.surface2, 0x2A2722)
        XCTAssertEqual(AmberTheme.notionDark.background, 0x191919)
        XCTAssertEqual(AmberTheme.notionDark.surface2, 0x2F2F2F)
        XCTAssertEqual(AmberTheme.notionDark.foreground2, 0xB4B4B4)
    }

    func testDarkHomeChromeTracksNonNeutralPaperSurfaces() {
        let dark = UITraitCollection(userInterfaceStyle: .dark)

        runtime.paper = .neutral
        XCTAssertEqual(resolvedHex(AmberTheme.avatarIdle, traits: dark), 0x2B2930)
        XCTAssertEqual(resolvedHex(AmberTheme.hoverCard, traits: dark), 0x29262D)

        runtime.paper = .notion
        XCTAssertEqual(resolvedHex(AmberTheme.avatarIdle, traits: dark), AmberTheme.notionDark.surface2)
        XCTAssertEqual(resolvedHex(AmberTheme.hoverCard, traits: dark), AmberTheme.notionDark.surface2)
        XCTAssertEqual(resolvedHex(AmberTheme.section, traits: dark), AmberTheme.notionDark.foreground2)
        XCTAssertEqual(resolvedHex(AmberTheme.avatarIdleInk, traits: dark), AmberTheme.notionDark.muted)

        runtime.paper = .pi
        XCTAssertEqual(resolvedHex(AmberTheme.avatarIdle, traits: dark), AmberTheme.piDark.surface2)
        XCTAssertEqual(resolvedHex(AmberTheme.hoverCard, traits: dark), AmberTheme.piDark.surface2)

        runtime.paper = .paper
        XCTAssertEqual(resolvedHex(AmberTheme.avatarIdle, traits: dark), AmberTheme.paperDark.surface2)

        runtime.paper = .white
        XCTAssertEqual(resolvedHex(AmberTheme.avatarIdle, traits: dark), AmberTheme.whiteDark.surface2)
    }

    func testBuiltinPacksSurviveExportImportContrastGate() throws {
        for pack in AmberThemePack.builtins {
            let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: pack))
            let doc = try AmberThemePackTransfer.decode(data)
            XCTAssertEqual(doc.id, pack.id, pack.id)
            try runtime.apply(doc)
            XCTAssertEqual(runtime.matchingPack?.id, pack.id, pack.id)
        }
    }

    func testMiniAppThemeBridgeReadsPaletteHexNotNeutralFallback() throws {
        let text = try source("iosApp/MiniAppRunnerView.swift")
        XCTAssertTrue(text.contains("IOSMiniAppThemeBridge.payload"))
        XCTAssertTrue(text.contains("accentInkHex: AmberThemeRuntime.shared.accentInkHex"))
        XCTAssertFalse(
            text.contains("return colorScheme == .dark ? \"#0E0D10\""),
            "MiniApp theme bridge must not fall back to neutral-only hex"
        )
    }

    func testImportRejectsLowAccentInkContrast() {
        let ok = """
        {"format":"amber.theme.pack","version":1,"id":"ok","displayName":"ok","paper":"neutral",
         "accentHex":"0xB9863A","inkHex":"0x231602","canvasStyle":"flat",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system"}
        """
        XCTAssertNoThrow(try AmberThemePackTransfer.decode(Data(ok.utf8)))

        let bad = """
        {"format":"amber.theme.pack","version":1,"id":"bad","displayName":"bad","paper":"neutral",
         "accentHex":"0x808080","inkHex":"0x909090","canvasStyle":"flat",
         "brandMark":"systemWordmark","shortcutIconStyle":"phosphorFill","chromeTypeface":"system"}
        """
        XCTAssertThrowsError(try AmberThemePackTransfer.decode(Data(bad.utf8))) { error in
            guard let err = error as? AmberThemePackTransferError,
                  case .insufficientContrast(let ratio) = err else {
                return XCTFail("expected insufficientContrast, got \(error)")
            }
            XCTAssertLessThan(ratio, AmberColorContrast.minimumAccentInkRatio)
        }
    }

    func testBuiltinAccentInkPairsMeetImportContrastGate() {
        for option in AmberAccentOption.allCases {
            XCTAssertGreaterThanOrEqual(
                AmberColorContrast.contrastRatio(option.accentHex, option.inkHex),
                AmberColorContrast.minimumAccentInkRatio,
                "\(option.rawValue) accent/ink must remain importable"
            )
        }
    }

    // MARK: - P0 consistency (theme advancement)

    /// Status amber is a fixed semantic ink; brand accent follows the active pack.
    func testStatusAmberStaysFixedWhileBrandAccentFollowsRuntime() {
        runtime.paper = .neutral
        runtime.apply(.amberGold)
        XCTAssertEqual(runtime.accentHex, AmberAccentOption.amberGold.accentHex)
        XCTAssertNil(runtime.matchingPack)

        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        XCTAssertEqual(runtime.accentHex, AmberAccentOption.terracotta.accentHex)
        // Fixed semantic status amber `#D98324` must not track pack accent.
        XCTAssertEqual(AmberTheme.statusAmberHex, 0xD98324)
        XCTAssertNotEqual(runtime.accentHex, AmberTheme.statusAmberHex)
    }

    /// Home-entry work surfaces keep `AmberThemePageBackground(surface: .app)` so a future /
    /// imported `appWide` pack can still paint; builtins (sit / Pi) stay shell-quiet on `.app`.
    func testAppWideWorkSurfacesUsePageBackground() throws {
        let surfaces: [(path: String, needle: String)] = [
            ("iosApp/NovelCreation/NovelSessionView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/NovelCreation/NovelProjectListView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/NovelCreation/NovelProjectWorkspaceView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/NovelCreation/NovelChapterReaderView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/CouncilView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/CouncilChatRuntimeView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/WebMountView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/MiniAppListView.swift", "AmberThemePageBackground(surface: .app)"),
            ("iosApp/MiniAppRunnerView.swift", "AmberThemePageBackground(surface: .app)"),
        ]
        for surface in surfaces {
            let text = try source(surface.path)
            XCTAssertTrue(
                text.contains(surface.needle),
                "\(surface.path) must use \(surface.needle) for optional appWide canvas texture"
            )
        }

        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        // Tab content must not paint an opaque full-bleed paper over the page canvas.
        XCTAssertFalse(
            workspace.contains("return ZStack {\n            AmberTheme.background\n            if let mounted"),
            "Novel workspace content ZStack must not cover PageBackground with AmberTheme.background"
        )

        // Builtin character packs: texture on home/shell only — chat stays flat paper color.
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "pi-steel" }!)
        XCTAssertFalse(runtime.showsCanvasTexture(on: .app))
        XCTAssertTrue(runtime.showsCanvasTexture(on: .home))
    }

    /// Settings / provider primary chrome that is not a warning/running status uses runtime accent.
    func testBrandInteractiveSettingsUseRuntimeAccentNotFixedAmber() throws {
        let miniAppSettings = try source("iosApp/MiniAppSettingsView.swift")
        XCTAssertFalse(
            miniAppSettings.contains("tint: AmberTheme.accentAmber"),
            "MiniApp settings toggles should follow runtime accent"
        )
        XCTAssertTrue(miniAppSettings.contains("tint: AmberTheme.accent"))

        let provider = try source("iosApp/ProviderDetailView.swift")
        XCTAssertTrue(
            provider.contains("ProviderActionRow(systemImage: \"plus.circle\", title: \"手动添加\", tint: AmberTheme.accent)"),
            "手动添加 is a primary action — brand accent, not status amber"
        )
        // Keep attention/testing semantics on fixed status amber.
        XCTAssertTrue(provider.contains("tint: AmberTheme.accentAmber") || provider.contains("AmberTheme.statusAmber"))

        let webMount = try source("iosApp/WebMountView.swift")
        XCTAssertTrue(
            webMount.contains("title: \"需要登录\"") && webMount.contains("tint: AmberTheme.accent"),
            "WebMount add-site login toggle row icon follows runtime accent"
        )
        // Narrow: the login-toggle row must not still pin status amber.
        if let toggleRange = webMount.range(of: "title: \"需要登录\"") {
            let window = webMount[toggleRange.lowerBound...].prefix(280)
            XCTAssertFalse(
                window.contains("tint: AmberTheme.accentAmber"),
                "需要登录 row tint must not be fixed status amber"
            )
        } else {
            XCTFail("missing 需要登录 toggle")
        }
    }

    // MARK: - Installed theme library

    func testThemeLibraryUpsertRemoveAndBuiltinSkip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-theme-lib-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let library = AmberThemePackLibrary(
            fileURL: dir.appendingPathComponent("library.json")
        )

        let custom = AmberThemePackTransfer.document(from: AmberThemePack(
            id: "fixture-rose",
            displayName: "暖纸 · 玫红",
            paper: .paper,
            accent: .rose
        ))
        XCTAssertEqual(try library.upsert(custom), .installed)
        XCTAssertEqual(library.installed.map(\.id), ["fixture-rose"])

        var renamed = custom
        renamed.displayName = "玫红改名"
        XCTAssertEqual(try library.upsert(renamed), .installed)
        XCTAssertEqual(library.installed.count, 1)
        XCTAssertEqual(library.installed[0].displayName, "玫红改名")

        let builtinDoc = AmberThemePackTransfer.document(from: AmberThemePack.builtins[0])
        XCTAssertEqual(try library.upsert(builtinDoc), .builtinIdentity)
        XCTAssertFalse(library.contains(id: builtinDoc.id))

        XCTAssertEqual(try library.remove(ids: ["fixture-rose", "sit-terracotta"]), 1)
        XCTAssertTrue(library.installed.isEmpty)

        // Reload from disk after empty persist (file removed).
        let reloaded = AmberThemePackLibrary(fileURL: dir.appendingPathComponent("library.json"))
        XCTAssertTrue(reloaded.installed.isEmpty)
    }

    func testThemeLibraryPersistsAcrossInstances() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-theme-lib-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("library.json")

        let writer = AmberThemePackLibrary(fileURL: url)
        let doc = AmberThemePackTransfer.document(from: AmberThemePack(
            id: "fixture-mist",
            displayName: "雾蓝",
            paper: .white,
            accent: .mistBlue
        ))
        try writer.upsert(doc)

        let reader = AmberThemePackLibrary(fileURL: url)
        XCTAssertEqual(reader.installed.map(\.id), ["fixture-mist"])
    }

    func testMatchingThemeIdIncludesInstalledLibraryPack() throws {
        let shared = AmberThemePackLibrary.shared
        let previous = shared.installed
        defer {
            // Restore shared library so other tests / device state stay isolated.
            _ = try? shared.remove(ids: Set(shared.installed.map(\.id)))
            for pack in previous {
                _ = try? shared.upsert(pack)
            }
        }
        _ = try? shared.remove(ids: Set(shared.installed.map(\.id)))

        let doc = AmberThemePackTransfer.document(from: AmberThemePack(
            id: "fixture-ink",
            displayName: "中性白 · 墨",
            paper: .white,
            accent: .ink
        ))
        XCTAssertEqual(try shared.upsert(doc), .installed)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.matchingThemeId, "fixture-ink")
        XCTAssertNil(runtime.matchingPack)
        XCTAssertFalse(runtime.isCustomCombination)

        let exported = AmberThemePackTransfer.document(from: runtime)
        XCTAssertEqual(exported.id, "fixture-ink")
        XCTAssertEqual(exported.displayName, "中性白 · 墨")

        XCTAssertEqual(try shared.remove(ids: ["fixture-ink"]), 1)
        XCTAssertNil(runtime.matchingThemeId)
        XCTAssertTrue(runtime.isCustomCombination)
        let afterRemove = AmberThemePackTransfer.document(from: runtime)
        XCTAssertEqual(afterRemove.id, "custom")
    }

    func testThemeLibraryUpsertRollsBackWhenPersistFails() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-theme-lib-missing-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        // Place a regular file where persist expects a directory parent.
        let blocker = root.appendingPathComponent("no-such-dir")
        try Data("not-a-dir".utf8).write(to: blocker)
        let libraryURL = blocker.appendingPathComponent("library.json")

        let library = AmberThemePackLibrary(fileURL: libraryURL)
        let doc = AmberThemePackTransfer.document(from: AmberThemePack(
            id: "fixture-fail",
            displayName: "失败",
            paper: .white,
            accent: .rose
        ))
        XCTAssertThrowsError(try library.upsert(doc))
        XCTAssertTrue(library.installed.isEmpty, "persist failure must not leave dirty memory")
    }

    func testAppearanceManageChromeContract() throws {
        let text = try source("iosApp/AppearanceSettingsView.swift")
        XCTAssertTrue(text.contains("Button(\"管理\")"))
        XCTAssertTrue(text.contains("confirmRemoveSelectedThemes"))
        XCTAssertTrue(text.contains("library.upsert"))
        XCTAssertTrue(text.contains("useAccentSelection: !isManagingThemes"))
        XCTAssertTrue(text.contains("AmberTheme.accentRed"))
        XCTAssertTrue(text.contains("padding(.bottom, isManagingThemes ? 16 : 48)"))
    }

    private func source(_ relativePath: String) throws -> String {
        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        return try String(
            contentsOf: testsDirectory.deletingLastPathComponent().appendingPathComponent(relativePath),
            encoding: .utf8
        )
    }

    private func resolvedHex(_ color: Color, traits: UITraitCollection) -> UInt32 {
        let resolved = UIColor(color).resolvedColor(with: traits)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        XCTAssertTrue(resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        let r = UInt32((red * 255).rounded())
        let g = UInt32((green * 255).rounded())
        let b = UInt32((blue * 255).rounded())
        return (r << 16) | (g << 8) | b
    }
}
