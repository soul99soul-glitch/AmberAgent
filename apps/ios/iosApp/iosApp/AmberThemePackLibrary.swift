import Foundation

/// 本机导入主题库（不含三个内置角色包）。
/// 持久化：`Documents/theme-packs/library.json`。
@Observable
@MainActor
final class AmberThemePackLibrary {
    static let shared = AmberThemePackLibrary()

    private(set) var installed: [AmberThemePackDocument] = []

    private let fileURL: URL
    private let fileManager: FileManager

    enum UpsertOutcome: Equatable {
        /// 写入/覆盖库条目。
        case installed
        /// id 与内置冲突：只可应用，不进库。
        case builtinIdentity
    }

    init(fileURL: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.fileURL = fileURL ?? Self.defaultLibraryURL(fileManager: fileManager)
        load()
    }

    static func defaultLibraryURL(fileManager: FileManager = .default) -> URL {
        let dir = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("theme-packs", isDirectory: true)
        return dir.appendingPathComponent("library.json")
    }

    static func isBuiltinId(_ id: String) -> Bool {
        AmberThemePack.builtins.contains { $0.id == id }
    }

    /// 校验后入库；同 id 覆盖。内置 id 不入库。写盘失败则回滚内存。
    @discardableResult
    func upsert(_ document: AmberThemePackDocument) throws -> UpsertOutcome {
        try AmberThemePackTransfer.validate(document)
        if Self.isBuiltinId(document.id) {
            return .builtinIdentity
        }
        let previous = installed
        if let index = installed.firstIndex(where: { $0.id == document.id }) {
            installed[index] = document
        } else {
            installed.append(document)
        }
        do {
            try persist()
        } catch {
            installed = previous
            throw error
        }
        return .installed
    }

    /// 仅移除库内条目；内置 id 忽略。写盘失败则回滚内存。
    @discardableResult
    func remove(ids: Set<String>) throws -> Int {
        let removable = ids.filter { !Self.isBuiltinId($0) }
        guard !removable.isEmpty else { return 0 }
        let previous = installed
        installed.removeAll { removable.contains($0.id) }
        let removed = previous.count - installed.count
        guard removed > 0 else { return 0 }
        do {
            try persist()
        } catch {
            installed = previous
            throw error
        }
        return removed
    }

    func contains(id: String) -> Bool {
        installed.contains { $0.id == id }
    }

    // MARK: - Persistence

    private struct Envelope: Codable, Equatable {
        static let currentVersion = 1
        var version: Int
        var packs: [AmberThemePackDocument]
    }

    private func load() {
        guard fileManager.fileExists(atPath: fileURL.path) else {
            installed = []
            return
        }
        do {
            let data = try Data(contentsOf: fileURL)
            let envelope = try JSONDecoder().decode(Envelope.self, from: data)
            // Drop any accidental builtin ids from older files.
            installed = envelope.packs.filter { !Self.isBuiltinId($0.id) }
        } catch {
            installed = []
        }
    }

    private func persist() throws {
        if installed.isEmpty {
            if fileManager.fileExists(atPath: fileURL.path) {
                try fileManager.removeItem(at: fileURL)
            }
            return
        }
        let dir = fileURL.deletingLastPathComponent()
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        let envelope = Envelope(version: Envelope.currentVersion, packs: installed)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(envelope)
        try data.write(to: fileURL, options: .atomic)
    }
}

extension AmberThemePackDocument {
    /// 与 `AmberThemePack.matches` 同槽位；缺省可选槽与 apply 默认一致。
    func matches(runtime: AmberThemeRuntime) -> Bool {
        guard
            let paper = AmberThemeRuntime.Paper(rawValue: paper),
            !paper.isImmersive,
            let accent = try? AmberThemePackTransfer.parseHex(accentHex),
            let ink = try? AmberThemePackTransfer.parseHex(inkHex),
            let canvas = AmberCanvasStyle(rawValue: canvasStyle),
            let brand = AmberBrandMarkStyle(rawValue: brandMark),
            let shortcut = AmberShortcutIconStyle(rawValue: shortcutIconStyle),
            let chrome = AmberChromeTypeface(rawValue: chromeTypeface)
        else {
            return false
        }
        let scope = canvasScope.flatMap(AmberCanvasScope.init(rawValue:)) ?? .homeOnly
        let bubble = bubbleChrome.flatMap(AmberBubbleChrome.init(rawValue:)) ?? .standard
        let glass = glassChrome.flatMap(AmberGlassChrome.init(rawValue:)) ?? .standard
        let empty = emptyArt.flatMap(AmberEmptyArtStyle.init(rawValue:)) ?? .none
        let settings = settingsChrome ?? false
        let launch = launchBrand.flatMap(AmberLaunchBrandStyle.init(rawValue:)) ?? .none
        let asset = assetMode.flatMap(AmberThemeAssetMode.init(rawValue:)) ?? .builtinOnly
        let immersive = immersivePolicy.flatMap(AmberImmersivePolicy.init(rawValue:)) ?? .hidden

        return paper == runtime.paper
            && accent == runtime.accentHex
            && ink == runtime.accentInkHex
            && canvas == runtime.canvasStyle
            && brand == runtime.brandMarkStyle
            && shortcut == runtime.shortcutIconStyle
            && chrome == runtime.chromeTypeface
            && scope == runtime.canvasScope
            && bubble == runtime.bubbleChrome
            && glass == runtime.glassChrome
            && empty == runtime.emptyArt
            && settings == runtime.settingsChrome
            && launch == runtime.launchBrand
            && asset == runtime.assetMode
            && immersive == runtime.immersivePolicy
    }
}
