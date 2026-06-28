import Foundation

enum IOSMiniAppPermission: String, CaseIterable, Codable, Identifiable {
    case storage
    case toast
    case theme
    case network
    case externalImages
    case search
    case clipboardCopy = "clipboard.copy"
    case hostUpdateBoardSummary = "host.updateBoardSummary"
    case hostContext = "host.context"
    case hostSendToConversation = "host.sendToConversation"
    case hostCreateArtifact = "host.createArtifact"
    case aiGenerate = "ai.generate"
    case sharedStore
    case eventBus
    case launch
    case sensor
    case location
    case clipboardRead = "clipboard.read"

    var id: String { rawValue }

    static func normalized(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowered = trimmed.lowercased()
        return aliases[trimmed] ?? aliases[lowered] ?? trimmed
    }

    private static let aliases: [String: String] = [
        "fetch": "network",
        "http": "network",
        "https": "network",
        "internet": "network",
        "联网": "network",
        "externalimages": "externalImages",
        "external-images": "externalImages",
        "external_images": "externalImages",
        "remoteImages": "externalImages",
        "gyro": "sensor",
        "gyroscope": "sensor",
        "accelerometer": "sensor",
        "accel": "sensor",
        "light": "sensor",
        "ambientLight": "sensor",
        "ambientlight": "sensor",
        "ambient-light": "sensor",
        "ambient_light": "sensor",
        "illuminance": "sensor",
    ]
}

enum IOSMiniAppGrantDecision: String, Codable, Equatable {
    case allow = "ALLOW"
    case deny = "DENY"

    var title: String {
        switch self {
        case .allow: "允许"
        case .deny: "拒绝"
        }
    }
}

enum IOSMiniAppStoreError: LocalizedError, Equatable {
    case notFound(String)
    case staleVersion(expected: Int, actual: Int)
    case invalidOutput(String)
    case invalidSharedKey(String)
    case crossAppNamespace
    case sharedValueTooLarge
    case sharedNamespaceFull
    case storeCorrupt(String)

    var errorDescription: String? {
        switch self {
        case .notFound(let id):
            return "MiniApp not found: \(id)"
        case .staleVersion(let expected, let actual):
            return "MiniApp version changed from v\(expected) to v\(actual)."
        case .invalidOutput(let reason):
            return reason
        case .invalidSharedKey(let key):
            return "Invalid SharedStore key: \(key)"
        case .crossAppNamespace:
            return "Cross-app SharedStore namespaces are not granted yet."
        case .sharedValueTooLarge:
            return "SharedStore value is too large."
        case .sharedNamespaceFull:
            return "SharedStore namespace is full."
        case .storeCorrupt(let message):
            return "MiniApp store is corrupt; refusing to overwrite it: \(message)"
        }
    }
}

struct IOSMiniAppGeneratedOutput: Codable, Equatable {
    var title: String
    var description: String
    var icon: String?
    var category: String
    var permissions: [String]
    var html: String

    init(
        title: String,
        description: String,
        icon: String? = nil,
        category: String = "tool",
        permissions: [String] = [],
        html: String
    ) {
        self.title = title
        self.description = description
        self.icon = icon
        self.category = category
        self.permissions = permissions
        self.html = html
    }

    func normalized() -> IOSMiniAppGeneratedOutput {
        var seen = Set<String>()
        let normalizedPermissions = permissions.compactMap { permission -> String? in
            let normalized = IOSMiniAppPermission.normalized(permission)
            guard !normalized.isEmpty, !seen.contains(normalized) else { return nil }
            seen.insert(normalized)
            return normalized
        }
        return IOSMiniAppGeneratedOutput(
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
            icon: icon?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank,
            category: category.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank ?? "tool",
            permissions: normalizedPermissions,
            html: html
        )
    }
}

struct IOSMiniAppRecord: Codable, Identifiable, Equatable, Hashable {
    var id: String
    var title: String
    var description: String
    var htmlContent: String
    var sourceConversationId: String?
    var sourceMessageId: String?
    var iconEmoji: String?
    var category: String
    var permissions: [String]
    var pinned: Bool
    var runCount: Int
    var boardSummary: String?
    var version: Int
    var htmlHash: String
    var createdAt: Int64
    var updatedAt: Int64
    var lastRunAt: Int64?
}

struct IOSMiniAppVersionRecord: Codable, Identifiable, Equatable, Hashable {
    var id: String { "\(appId):\(versionNumber)" }
    var appId: String
    var versionNumber: Int
    var htmlContent: String
    var htmlHash: String
    var changeNote: String?
    var createdAt: Int64
}

struct IOSMiniAppGrantRecord: Codable, Identifiable, Equatable, Hashable {
    var id: String { "\(appId):\(permission)" }
    var appId: String
    var permission: String
    var decision: IOSMiniAppGrantDecision
    var updatedAt: Int64
}

struct IOSMiniAppAuditRecord: Codable, Identifiable, Equatable, Hashable {
    var id: String
    var appId: String
    var method: String
    var permission: String
    var summary: String
    var payloadHash: String
    var createdAt: Int64
}

struct IOSMiniAppSharedDataRecord: Codable, Identifiable, Equatable, Hashable {
    var id: String { "\(namespace):\(key)" }
    var namespace: String
    var key: String
    var value: IOSMiniAppJSONValue
    var lastWriterId: String
    var updatedAt: Int64
}

enum IOSMiniAppJSONValue: Codable, Equatable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([IOSMiniAppJSONValue])
    case object([String: IOSMiniAppJSONValue])

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([IOSMiniAppJSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: IOSMiniAppJSONValue].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:
            try container.encodeNil()
        case .bool(let value):
            try container.encode(value)
        case .number(let value):
            try container.encode(value)
        case .string(let value):
            try container.encode(value)
        case .array(let value):
            try container.encode(value)
        case .object(let value):
            try container.encode(value)
        }
    }

    init(any value: Any?) throws {
        guard let value else {
            self = .null
            return
        }
        switch value {
        case is NSNull:
            self = .null
        case let value as Bool:
            self = .bool(value)
        case let value as Int:
            self = .number(Double(value))
        case let value as Int64:
            self = .number(Double(value))
        case let value as Double:
            self = .number(value)
        case let value as String:
            self = .string(value)
        case let value as [Any]:
            self = .array(try value.map { try IOSMiniAppJSONValue(any: $0) })
        case let value as [String: Any]:
            self = .object(try value.mapValues { try IOSMiniAppJSONValue(any: $0) })
        case let value as [String: String]:
            self = .object(value.mapValues { .string($0) })
        case let value as [String: Int]:
            self = .object(value.mapValues { .number(Double($0)) })
        case let value as [String: Double]:
            self = .object(value.mapValues { .number($0) })
        case let value as [String: Bool]:
            self = .object(value.mapValues { .bool($0) })
        case let value as [String: IOSMiniAppJSONValue]:
            self = .object(value)
        default:
            throw IOSMiniAppStoreError.invalidOutput("Unsupported JSON value: \(type(of: value))")
        }
    }

    var anyValue: Any {
        switch self {
        case .null:
            return NSNull()
        case .bool(let value):
            return value
        case .number(let value):
            return value
        case .string(let value):
            return value
        case .array(let values):
            return values.map(\.anyValue)
        case .object(let values):
            return values.mapValues(\.anyValue)
        }
    }

    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    var intValue: Int? {
        if case .number(let value) = self { return Int(value) }
        return nil
    }

    var byteCount: Int {
        (try? JSONEncoder().encode(self)).map(\.count) ?? 0
    }
}

struct IOSMiniAppOutputParser {
    static let miniAppInstruction = """
    请按 AmberAgent MiniApp V3 输出一个严格 JSON 对象，不要输出 Markdown 解释或代码围栏。
    Schema: {"title":"1-20 字标题","description":"1-80 字描述","icon":"最多 2 个字符","category":"tool|game|info|custom","permissions":["storage","toast","theme","network","externalImages","search","clipboard.copy","host.updateBoardSummary","host.context","host.sendToConversation","host.createArtifact","ai.generate","sharedStore","eventBus","launch","sensor","location","clipboard.read"],"html":"<!DOCTYPE html>..."}
    约束：只生成单文件 HTML；不要使用 script src、iframe、form、eval、new Function、import()、XMLHttpRequest、WebSocket、localStorage、sessionStorage、geolocation。图片允许 data:image/... 或 https:// 图片 URL；网络、搜索、AI、剪贴板、宿主写回和共享存储只能通过 Amber bridge，并声明对应权限。最终只输出一个可解析 JSON 对象。
    """

    private let decoder: JSONDecoder

    init(decoder: JSONDecoder = JSONDecoder()) {
        self.decoder = decoder
    }

    func parseOrNull(_ text: String) -> IOSMiniAppGeneratedOutput? {
        try? parse(text)
    }

    func parse(_ text: String) throws -> IOSMiniAppGeneratedOutput {
        if let json = extractJSONObject(from: text) {
            let data = Data(json.utf8)
            let output = try decoder.decode(IOSMiniAppGeneratedOutput.self, from: data).normalized()
            try Self.validate(output)
            return output
        }
        if let html = extractHTMLDocument(from: text) {
            let output = IOSMiniAppGeneratedOutput(
                title: inferTitle(from: html),
                description: "从聊天输出解析保存的小应用",
                icon: nil,
                category: "custom",
                permissions: [],
                html: html
            )
            try Self.validate(output)
            return output
        }
        throw IOSMiniAppStoreError.invalidOutput("No MiniApp JSON or HTML document found.")
    }

    static func isExplicitMiniAppRequest(_ text: String) -> Bool {
        let normalized = text.lowercased()
        let mentionsMiniApp = normalized.contains("miniapp") ||
            normalized.contains("mini app") ||
            text.contains("小应用") ||
            text.contains("小程序")
        guard mentionsMiniApp else { return false }
        if miniAppNegationPattern.firstMatch(in: text) != nil { return false }
        if isPresentationRequest(text), positiveMiniAppIntentPattern.firstMatch(in: text) == nil {
            return false
        }
        return true
    }

    static func validate(_ output: IOSMiniAppGeneratedOutput) throws {
        let normalized = output.normalized()
        if normalized.title.isEmpty || normalized.title.count > 20 {
            throw IOSMiniAppStoreError.invalidOutput("Title must be 1-20 characters.")
        }
        if normalized.description.isEmpty || normalized.description.count > 80 {
            throw IOSMiniAppStoreError.invalidOutput("Description must be 1-80 characters.")
        }
        if let icon = normalized.icon, icon.count > 2 {
            throw IOSMiniAppStoreError.invalidOutput("Icon must be at most 2 characters.")
        }
        if !categories.contains(normalized.category) {
            throw IOSMiniAppStoreError.invalidOutput("Unsupported category: \(normalized.category)")
        }
        let known = Set(IOSMiniAppPermission.allCases.map(\.rawValue))
        let unknown = normalized.permissions.filter { !known.contains($0) }
        if !unknown.isEmpty {
            throw IOSMiniAppStoreError.invalidOutput("Unsupported MiniApp permissions: \(unknown.joined(separator: ", "))")
        }
        try MiniAppHtmlValidator.validate(normalized.html)
    }

    private func extractJSONObject(from text: String) -> String? {
        extractFencedBlock(from: text, preferredLanguage: "json")?.takeIfJSONObject ??
            extractBalancedObject(from: text)
    }

    private func extractHTMLDocument(from text: String) -> String? {
        if let fenced = extractFencedBlock(from: text, preferredLanguage: "html"),
           looksLikeHTML(fenced) {
            return fenced.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return looksLikeHTML(trimmed) ? trimmed : nil
    }

    private func extractFencedBlock(from text: String, preferredLanguage: String) -> String? {
        let pattern = #"```([A-Za-z0-9_-]*)\s*\n([\s\S]*?)```"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        for match in regex.matches(in: text, range: range) {
            guard match.numberOfRanges >= 3,
                  let languageRange = Range(match.range(at: 1), in: text),
                  let bodyRange = Range(match.range(at: 2), in: text) else { continue }
            let language = text[languageRange].lowercased()
            let body = String(text[bodyRange]).trimmingCharacters(in: .whitespacesAndNewlines)
            if language == preferredLanguage || (preferredLanguage == "json" && language.isEmpty) {
                return body
            }
        }
        return nil
    }

    private func extractBalancedObject(from text: String) -> String? {
        guard let start = text.firstIndex(of: "{") else { return nil }
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while index < text.endIndex {
            let char = text[index]
            if inString {
                if escaped {
                    escaped = false
                } else if char == "\\" {
                    escaped = true
                } else if char == "\"" {
                    inString = false
                }
            } else {
                if char == "\"" {
                    inString = true
                } else if char == "{" {
                    depth += 1
                } else if char == "}" {
                    depth -= 1
                    if depth == 0 {
                        return String(text[start...index])
                    }
                }
            }
            index = text.index(after: index)
        }
        return nil
    }

    private func looksLikeHTML(_ text: String) -> Bool {
        let lowered = text.lowercased()
        return lowered.contains("<!doctype html") || lowered.contains("<html")
    }

    private func inferTitle(from html: String) -> String {
        for pattern in [#"<title[^>]*>(.*?)</title>"#, #"<h[1-3][^>]*>(.*?)</h[1-3]>"#] {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]),
                  let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..<html.endIndex, in: html)),
                  match.numberOfRanges >= 2,
                  let range = Range(match.range(at: 1), in: html) else { continue }
            let title = String(html[range])
                .replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
                .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !title.isEmpty {
                return String(title.prefix(20))
            }
        }
        return "聊天小应用"
    }

    private static func isPresentationRequest(_ text: String) -> Bool {
        let normalized = text.lowercased()
        return presentationKeywords.contains { normalized.contains($0) }
    }

    private static let categories = Set(["tool", "game", "info", "custom"])
    private static let presentationKeywords = [
        "ppt", "幻灯片", "演示文稿", "演示稿", "slide", "slides", "slide deck",
        "presentation", "deck", "guizang", "guizang-ppt", "归藏", "简报",
    ]
    private static let miniAppNegationPattern = RegexPattern(
        #"(?:不要|别|别再|不要再|不是|无需|不用|别给我|不要给我).{0,16}(?:小应用|小程序|mini\s*app|miniapp)|(?:小应用|小程序|mini\s*app|miniapp).{0,12}(?:不要|别|不需要|不用|别做|别生成)"#
    )
    private static let positiveMiniAppIntentPattern = RegexPattern(
        #"(?:做成|做为|作为|做一个|做个|生成|创建|开发|实现|改成|转换成|变成).{0,12}(?:小应用|小程序|mini\s*app|miniapp)|(?:小应用|小程序|mini\s*app|miniapp).{0,6}(?:版|形式|形态)"#
    )
}

enum IOSMiniAppFixtures {
    static let sampleId = "ios-miniapp-mvp-sample"

    static let sampleOutput = IOSMiniAppGeneratedOutput(
        title: "Amber 小应用示例",
        description: "本地样例：试用按钮、提示、主题和存储能力。",
        icon: "🧩",
        category: "tool",
        permissions: [
            "storage",
            "toast",
            "theme",
            "clipboard.copy",
            "host.updateBoardSummary",
            "sharedStore",
            "eventBus",
        ],
        html: sampleHtml
    )

    static let sampleHtml = """
    <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;padding:14px;color:#1f2937}button{font-size:15px;padding:8px 12px;margin:4px;border:0;border-radius:9px;background:#2563eb;color:white}pre{white-space:pre-wrap;background:#f3f4f6;padding:10px;border-radius:10px}</style></head>
    <body>
    <h3>Amber 小应用示例</h3>
    <p>点击按钮试用小应用提供的本机能力。</p>
    <p>
      <button onclick="callInfo()">app.info</button>
      <button onclick="saveStorage()">storage.set</button>
      <button onclick="readTheme()">theme</button>
      <button onclick="toast()">toast</button>
      <button onclick="publishEvent()">eventBus</button>
    </p>
    <pre id="out">等待调用...</pre>
    <script>
      function show(v){ document.getElementById('out').textContent = typeof v === 'string' ? v : JSON.stringify(v, null, 2); }
      function fail(e){ show({error: String(e && e.message ? e.message : e)}); }
      async function callInfo(){ try { show(await AmberNative.postMessage({method:'app.info'})); } catch(e) { fail(e); } }
      async function saveStorage(){ try { await Amber.storage.set('hello','world'); show(await Amber.storage.get('hello')); } catch(e) { fail(e); } }
      async function readTheme(){ try { show(await Amber.host.getTheme()); } catch(e) { fail(e); } }
      async function toast(){ try { show(await Amber.toast('MiniApp bridge 已连接')); } catch(e) { fail(e); } }
      async function publishEvent(){ try { var sub = await Amber.eventBus.subscribe({topic:'demo'}, function(evt){ show(evt); }); await Amber.eventBus.publish({topic:'demo', payload:{ok:true, subscriptionId:sub.subscriptionId}}); } catch(e) { fail(e); } }
    </script>
    </body></html>
    """
}

private struct RegexPattern {
    private let regex: NSRegularExpression?

    init(_ pattern: String) {
        self.regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators])
    }

    func firstMatch(in text: String) -> NSTextCheckingResult? {
        regex?.firstMatch(in: text, range: NSRange(text.startIndex..<text.endIndex, in: text))
    }
}

private extension String {
    var takeIfJSONObject: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") ? self : nil
    }
}
