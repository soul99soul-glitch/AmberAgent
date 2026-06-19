import Foundation
import Observation

enum IOSImageAspectRatio: String, CaseIterable, Codable, Identifiable {
    case square
    case landscape
    case portrait

    var id: String { rawValue }

    var title: String {
        switch self {
        case .square: "1:1"
        case .landscape: "16:9"
        case .portrait: "9:16"
        }
    }

    var apiSize: String {
        switch self {
        case .square: "1024x1024"
        case .landscape: "1536x1024"
        case .portrait: "1024x1536"
        }
    }

    init(toolValue: String?) {
        switch toolValue?.trimmingCharacters(in: .whitespacesAndNewlines) {
        case "16:9", "landscape":
            self = .landscape
        case "9:16", "portrait":
            self = .portrait
        default:
            self = .square
        }
    }
}

struct IOSImageGenerationRequest: Equatable {
    var prompt: String
    var model: String
    var aspectRatio: IOSImageAspectRatio
    var count: Int
    var style: String
    var source: String

    var effectivePrompt: String {
        let trimmedPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedStyle = style.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedStyle.isEmpty else { return trimmedPrompt }
        return "\(trimmedPrompt)\nStyle: \(trimmedStyle)"
    }
}

struct IOSGeneratedImageFile: Codable, Identifiable, Equatable {
    var id: String
    var path: String
    var mimeType: String
}

struct IOSImageGenerationRecord: Codable, Identifiable, Equatable {
    var id: String
    var prompt: String
    var model: String
    var aspectRatio: IOSImageAspectRatio
    var count: Int
    var style: String
    var source: String
    var files: [IOSGeneratedImageFile]
    var createdAt: Int64
}

enum IOSImageGenerationError: LocalizedError, Equatable {
    case missingPrompt
    case missingAPIKey
    case missingModel
    case invalidBaseURL
    case invalidResponse
    case httpStatus(Int, String)
    case noImages
    case invalidImageData

    var errorDescription: String? {
        switch self {
        case .missingPrompt:
            "图片提示词不能为空。"
        case .missingAPIKey:
            "当前服务商没有 API Key，无法调用图片生成。"
        case .missingModel:
            "请先填写图片生成模型。"
        case .invalidBaseURL:
            "当前服务商 API 地址不是有效的 http/https URL。"
        case .invalidResponse:
            "图片生成服务返回了无法解析的响应。"
        case .httpStatus(let status, let body):
            "图片生成失败：HTTP \(status)。\(body)"
        case .noImages:
            "图片生成服务没有返回图片。"
        case .invalidImageData:
            "图片数据不是可保存的 base64 或公开 URL。"
        }
    }
}

@MainActor
@Observable
final class IOSImageGenerationSettingsStore {
    static let shared = IOSImageGenerationSettingsStore()

    var model: String {
        didSet { persist() }
    }
    var aspectRatio: IOSImageAspectRatio {
        didSet { persist() }
    }
    var count: Int {
        didSet { persist() }
    }
    var style: String {
        didSet { persist() }
    }

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let key: String

    init(
        userDefaults: UserDefaults = .standard,
        key: String = "app.amber.ios.imageGeneration.settings.v1"
    ) {
        self.defaults = userDefaults
        self.key = key
        if let data = userDefaults.data(forKey: key),
           let decoded = try? JSONDecoder().decode(Storage.self, from: data) {
            self.model = decoded.model
            self.aspectRatio = decoded.aspectRatio
            self.count = decoded.count
            self.style = decoded.style
        } else {
            self.model = "gpt-image-1"
            self.aspectRatio = .square
            self.count = 1
            self.style = ""
        }
    }

    func request(prompt: String, source: String = "gallery") -> IOSImageGenerationRequest {
        IOSImageGenerationRequest(
            prompt: prompt,
            model: model,
            aspectRatio: aspectRatio,
            count: max(1, min(count, 4)),
            style: style,
            source: source
        )
    }

    func configurationIssue(settingsStore: SettingsStore) -> IOSImageGenerationError? {
        if settingsStore.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .missingAPIKey
        }
        guard let url = URL(string: settingsStore.baseUrl),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return .invalidBaseURL
        }
        if model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .missingModel
        }
        return nil
    }

    private func persist() {
        let storage = Storage(model: model, aspectRatio: aspectRatio, count: max(1, min(count, 4)), style: style)
        if let data = try? JSONEncoder().encode(storage) {
            defaults.set(data, forKey: key)
        }
    }

    private struct Storage: Codable {
        var model: String
        var aspectRatio: IOSImageAspectRatio
        var count: Int
        var style: String
    }
}
