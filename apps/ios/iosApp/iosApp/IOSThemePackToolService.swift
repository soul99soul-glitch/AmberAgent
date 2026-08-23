import Foundation

/// Host-side theme pack tools. Status is a pure catalog; import try-on is
/// in-memory until the user taps 套用. Library writes happen only on commit.
@MainActor
final class IOSThemePackToolService {
    private let runtime: AmberThemeRuntime
    private let library: AmberThemePackLibrary
    private var prepared: AmberThemePackDocument?

    init(
        runtime: AmberThemeRuntime = .shared,
        library: AmberThemePackLibrary = .shared
    ) {
        self.runtime = runtime
        self.library = library
    }

    func execute(toolName: String, argumentsJSON: String) -> String {
        switch toolName {
        case "theme_pack_status":
            return status()
        case "theme_pack_import":
            return importNow(argumentsJSON: argumentsJSON)
        default:
            return fail(toolName, "未知的主题工具。")
        }
    }

    func status() -> String {
        let currentDocument: AmberThemePackDocument
        var tryOn: [String: Any]?
        if let session = runtime.tryOnSession {
            currentDocument = session.baseline
            tryOn = Self.slotJSON(session.candidate)
        } else {
            currentDocument = AmberThemePackTransfer.document(from: runtime)
        }
        return Self.ok([
            "tool": "theme_pack_status",
            "current": Self.slotJSON(currentDocument),
            "try_on": tryOn as Any? ?? NSNull(),
            "installed_ids": library.installed.map(\.id),
            "builtin_ids": AmberThemePack.builtins.map(\.id),
            "allowed": Self.allowedSlots,
            "contrast_min": AmberColorContrast.minimumAccentInkRatio,
            "rules": Self.rules,
        ])
    }

    /// Validate, try-on immediately, stash for 套用. Does not persist.
    func prepareImport(argumentsJSON: String) throws -> AmberThemePackDocument {
        let args = Self.jsonObject(argumentsJSON) ?? [:]
        let document = try AmberThemePackTransfer.document(fromToolArguments: args)
        try runtime.beginTryOn(document)
        prepared = document
        return document
    }

    func commitPreparedImport() throws -> String {
        guard let document = prepared ?? runtime.tryOnSession?.candidate else {
            throw AmberThemeTryOnError.noActiveTryOn
        }
        try library.upsert(document)
        try runtime.commitTryOn()
        prepared = nil
        return Self.ok([
            "tool": "theme_pack_import",
            "id": document.id,
            "display_name": document.displayName,
            "persisted": true,
            "installed": true,
        ])
    }

    func discardPreparedImport() {
        prepared = nil
        runtime.discardTryOn()
    }

    /// Recipe / dispatch fallback: try-on then persist in one shot.
    private func importNow(argumentsJSON: String) -> String {
        do {
            _ = try prepareImport(argumentsJSON: argumentsJSON)
            return try commitPreparedImport()
        } catch {
            discardPreparedImport()
            return fail(
                "theme_pack_import",
                (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            )
        }
    }

    private static let allowedSlots: [String: Any] = [
        "paper": ["paper", "neutral", "white", "pi", "notion"],
        "canvas_style": AmberCanvasStyle.allCases.map(\.rawValue),
        "brand_mark": AmberBrandMarkStyle.allCases.map(\.rawValue),
        "shortcut_icon_style": AmberShortcutIconStyle.allCases.map(\.rawValue),
        "chrome_typeface": AmberChromeTypeface.allCases.map(\.rawValue),
        "canvas_scope": AmberCanvasScope.allCases.map(\.rawValue),
        "bubble_chrome": AmberBubbleChrome.allCases.map(\.rawValue),
        "glass_chrome": AmberGlassChrome.allCases.map(\.rawValue),
        "empty_art": AmberEmptyArtStyle.allCases.map(\.rawValue),
        "launch_brand": AmberLaunchBrandStyle.allCases.map(\.rawValue),
    ]

    private static let rules: [String] = [
        "Theme packs change color, texture, brand mark, and shortcut skins only.",
        "Do not change list layout, appearance mode, or chat body fonts.",
        "Default canvas_scope to shell so chat stays untextured.",
        "High-luminance accent_hex needs a dark ink_hex; contrast must be at least 3.0.",
        "id must be a new slug, not sit-terracotta, pi-steel, or notion-blue.",
        "Try-on is visible immediately but not saved until the user taps 套用.",
    ]

    static func slotJSON(_ document: AmberThemePackDocument) -> [String: Any] {
        [
            "id": document.id,
            "display_name": document.displayName,
            "paper": document.paper,
            "accent_hex": document.accentHex,
            "ink_hex": document.inkHex,
            "canvas_style": document.canvasStyle,
            "brand_mark": document.brandMark,
            "shortcut_icon_style": document.shortcutIconStyle,
            "chrome_typeface": document.chromeTypeface,
            "canvas_scope": document.canvasScope ?? AmberCanvasScope.shell.rawValue,
            "bubble_chrome": document.bubbleChrome ?? AmberBubbleChrome.standard.rawValue,
            "glass_chrome": document.glassChrome ?? AmberGlassChrome.standard.rawValue,
            "empty_art": document.emptyArt ?? AmberEmptyArtStyle.none.rawValue,
            "settings_chrome": document.settingsChrome ?? false,
            "launch_brand": document.launchBrand ?? AmberLaunchBrandStyle.none.rawValue,
        ]
    }

    private static func jsonObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return obj
    }

    private static func ok(_ payload: [String: Any]) -> String {
        var body = payload
        body["ok"] = true
        body["status"] = body["status"] ?? "ok"
        return IOSWorkspaceStore.json(body)
    }

    private func fail(_ tool: String, _ reason: String) -> String {
        ChatToolOutputFormatter.toolFailureJSON(toolName: tool, reason: reason, status: "failed")
    }
}

enum IOSThemePackToolCatalog {
    static let toolNames: Set<String> = [
        "theme_pack_status",
        "theme_pack_import",
    ]
    static let mutatingToolNames: Set<String> = [
        "theme_pack_import",
    ]
    /// Import always requires a foreground try-on card (even with high-risk auto-approve).
    static let highRiskToolNames: Set<String> = [
        "theme_pack_import",
    ]
    static let backgroundAllowedToolNames: Set<String> = [
        "theme_pack_status",
    ]

    static func approvalReason(displayName: String) -> String {
        "整 app 已换成「\(displayName)」，尚未保存。套用后写入主题库；还原回到试穿前。"
    }

    static func argumentsPreview(for document: AmberThemePackDocument) -> String {
        "\(document.displayName) · \(document.paper) · \(document.accentHex)"
    }
}
