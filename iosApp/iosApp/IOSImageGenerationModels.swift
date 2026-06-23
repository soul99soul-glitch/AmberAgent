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

// 图片生成的独立配置 store 已移除:生图模型改为在「默认模型 → 辅助任务 → 生图模型」
// (Settings.imageGenerationModelId)指定,apiKey/baseURL 从该模型所属 provider 解析。
// 本文件仅保留生成引擎(IOSImageGenerationRepository)共用的请求/记录/错误/比例类型。
