import Foundation
import Shared

/// Per-provider request header disguise (User-Agent + extras).
///
/// Coding Plan modes inject `opencode/<version>` at the KMP request layer when
/// no User-Agent is persisted here. A user-picked preset is stored locally so
/// listModels and chat send the same disguise without changing ProviderSetting.
enum IOSProviderRequestHeaderStore {
    private static let defaultsKey = "app.amber.ios.providerRequestHeaders.v1"

    struct Record: Codable, Equatable {
        var userAgent: String?
        var extra: [Item]
    }

    struct Item: Codable, Equatable {
        var name: String
        var value: String
    }

    static func record(for providerId: String, defaults: UserDefaults = .standard) -> Record {
        guard let data = defaults.data(forKey: defaultsKey),
              let all = try? JSONDecoder().decode([String: Record].self, from: data),
              let record = all[providerId] else {
            return Record(userAgent: nil, extra: [])
        }
        return record
    }

    static func save(
        providerId: String,
        userAgent: String?,
        extra: [Item],
        defaults: UserDefaults = .standard
    ) {
        var all: [String: Record] = [:]
        if let data = defaults.data(forKey: defaultsKey),
           let decoded = try? JSONDecoder().decode([String: Record].self, from: data) {
            all = decoded
        }
        let trimmedAgent = userAgent?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedExtra = extra.compactMap { item -> Item? in
            let name = item.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return nil }
            return Item(name: name, value: item.value)
        }
        if trimmedAgent?.isEmpty != false && cleanedExtra.isEmpty {
            all.removeValue(forKey: providerId)
        } else {
            all[providerId] = Record(userAgent: trimmedAgent?.isEmpty == true ? nil : trimmedAgent, extra: cleanedExtra)
        }
        defaults.set(try? JSONEncoder().encode(all), forKey: defaultsKey)
    }

    static func headers(for providerId: String, defaults: UserDefaults = .standard) -> [CustomHeader] {
        let record = record(for: providerId, defaults: defaults)
        var headers: [CustomHeader] = []
        if let userAgent = record.userAgent, !userAgent.isEmpty {
            headers.append(CustomHeader(name: "User-Agent", value: userAgent))
        }
        headers.append(contentsOf: record.extra.map { CustomHeader(name: $0.name, value: $0.value) })
        return headers
    }
}

enum ProviderUserAgentPreset: String, CaseIterable, Identifiable {
    case opencode
    case claudeCode
    case cursor
    case cline
    case custom

    var id: String { rawValue }

    var title: String {
        switch self {
        case .opencode: "OpenCode"
        case .claudeCode: "Claude Code"
        case .cursor: "Cursor"
        case .cline: "Cline"
        case .custom: "自定义"
        }
    }

    var userAgent: String? {
        switch self {
        case .opencode: OpenAICompatUserAgents.shared.OPENCODE
        case .claudeCode: OpenAICompatUserAgents.shared.CLAUDE_CODE
        case .cursor: OpenAICompatUserAgents.shared.CURSOR
        case .cline: OpenAICompatUserAgents.shared.CLINE
        case .custom: nil
        }
    }

    static func matching(userAgent: String?) -> ProviderUserAgentPreset? {
        let trimmed = userAgent?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmed.isEmpty { return nil }
        return allCases.first { preset in
            guard let value = preset.userAgent else { return false }
            return value.caseInsensitiveCompare(trimmed) == .orderedSame
        } ?? .custom
    }
}
