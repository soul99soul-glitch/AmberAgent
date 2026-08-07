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
        XCTAssertEqual(AmberThemePack.builtins.count, 9)
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

    func testDefaultPacksKeepFlatStyles() {
        let classic = AmberThemePack.builtins.filter {
            $0.id != "sit-terracotta" && $0.id != "pi-steel" && $0.id != "notion-blue"
        }
        XCTAssertEqual(classic.count, 6)
        for pack in classic {
            XCTAssertEqual(pack.canvasStyle, .flat, pack.id)
            XCTAssertEqual(pack.brandMark, .systemWordmark, pack.id)
            XCTAssertEqual(pack.shortcutIconStyle, .phosphorFill, pack.id)
            XCTAssertEqual(pack.chromeTypeface, .system, pack.id)
            XCTAssertEqual(pack.canvasScope, .homeOnly, pack.id)
            XCTAssertEqual(pack.bubbleChrome, .standard, pack.id)
            XCTAssertEqual(pack.glassChrome, .standard, pack.id)
            XCTAssertEqual(pack.emptyArt, .none, pack.id)
            XCTAssertFalse(pack.settingsChrome, pack.id)
            XCTAssertEqual(pack.launchBrand, .none, pack.id)
            XCTAssertFalse(pack.hasCharacterStyles, pack.id)
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
        XCTAssertEqual(pi.canvasScope, .appWide)
        XCTAssertEqual(pi.glassChrome, .quieter)
        XCTAssertEqual(pi.emptyArt, .character)
        XCTAssertEqual(pi.bubbleChrome, .standard)
        XCTAssertEqual(AmberTheme.piLight.background, 0xF3F0EB)
        XCTAssertEqual(AmberTheme.piLight.surface, 0xFAF9F7)
        runtime.apply(pi)
        XCTAssertEqual(runtime.matchingPack?.id, "pi-steel")
        XCTAssertEqual(runtime.canvasStyle, .lineGrid)
        XCTAssertEqual(runtime.chromeTypeface, .monospace)
        XCTAssertTrue(runtime.showsCanvasTexture(on: .home))
        XCTAssertTrue(runtime.showsCanvasTexture(on: .shell))
        XCTAssertTrue(runtime.showsCanvasTexture(on: .app))
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

    func testCustomWhenAccentDiverges() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "warm-amber")
        runtime.apply(.mistBlue)
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testCustomWhenPaperDiverges() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        runtime.paper = .white
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
    }

    func testCustomWhenStyleSlotDiverges() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "warm-amber")
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
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        runtime.apply(.rose)
        XCTAssertTrue(runtime.isCustomCombination)
        runtime.paper = .paper
        runtime.apply(.rose)
        XCTAssertEqual(runtime.matchingPack?.id, "paper-rose")
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
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        try runtime.apply(AmberThemePackTransfer.decode(data))
        XCTAssertEqual(runtime.matchingPack?.id, "notion-blue")
        XCTAssertEqual(runtime.paper, .notion)
        XCTAssertEqual(runtime.accentHex, 0x0075DE)
        XCTAssertEqual(runtime.accentInkHex, 0xFFFFFF)
    }

    func testExportImportRoundTripPiSteel() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "pi-steel" }!)
        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "warm-amber")

        let doc = try AmberThemePackTransfer.decode(data)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.matchingPack?.id, "pi-steel")
        XCTAssertEqual(runtime.paper, .pi)
        XCTAssertEqual(runtime.brandMarkStyle, .serifWordmark)
        XCTAssertEqual(runtime.accentHex, 0x6B8CAD)
        XCTAssertEqual(runtime.accentInkHex, 0xFAF9F7)
        XCTAssertEqual(runtime.canvasStyle, .lineGrid)
        XCTAssertEqual(runtime.canvasScope, .appWide)
        XCTAssertEqual(runtime.chromeTypeface, .monospace)
        XCTAssertEqual(runtime.glassChrome, .quieter)
        XCTAssertEqual(runtime.emptyArt, .character)
    }

    func testExportIncludesOptionalSlotsAndLegacyImportDefaults() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "sit-terracotta" }!)
        let data = try AmberThemePackTransfer.encode(AmberThemePackTransfer.document(from: runtime))
        let doc = try AmberThemePackTransfer.decode(data)
        XCTAssertEqual(doc.canvasScope, "shell")
        XCTAssertEqual(doc.bubbleChrome, "soft")

        // Pi pack after re-apply path covered elsewhere; legacy document without optional keys.
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
        XCTAssertEqual(runtime.matchingPack?.id, "warm-amber")
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
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)

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
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)
        XCTAssertEqual(runtime.matchingPack?.id, "warm-amber")

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
        runtime.apply(AmberThemePack.builtins.first { $0.id == "warm-amber" }!)

        let doc = try AmberThemePackTransfer.decode(data)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.paper, .white)
        XCTAssertEqual(runtime.accentHex, 0x334455)
        XCTAssertEqual(runtime.accentInkHex, 0xFFFFFF)
        XCTAssertNil(runtime.matchingPack)
        XCTAssertTrue(runtime.isCustomCombination)
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
        XCTAssertEqual(runtime.matchingPack?.id, "warm-amber")
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
        runtime.apply(AmberThemePack.builtins.first { $0.id == "paper-rose" }!)
        let url = try AmberThemePackTransfer.writeExportFile(from: runtime)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertTrue(url.lastPathComponent.contains("paper-rose"))
        let data = try Data(contentsOf: url)
        let doc = try AmberThemePackTransfer.decode(data)
        XCTAssertEqual(doc.id, "paper-rose")
        try runtime.apply(AmberThemePack.builtins.first { $0.id == "white-ink" }!)
        try runtime.apply(doc)
        XCTAssertEqual(runtime.matchingPack?.id, "paper-rose")
    }
}
