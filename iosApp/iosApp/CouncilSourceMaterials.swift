import Foundation
import UIKit
import UniformTypeIdentifiers
@preconcurrency import Shared

// MARK: - Limits

enum CouncilMaterialLimits {
    static let maxFiles = 3
    static let maxImages = 4
    /// Align with Chat workspace import budget for a single picked document.
    static let maxReadableBytes: Int64 = 8 * 1024 * 1024
    static let maxPreviewBytes = 64 * 1024
    static let maxPreviewCharacters = 60_000
    /// Hard cap on materials text injected into council prompts.
    static let maxMaterialsPromptCharacters = 40_000
}

// MARK: - Pending composer attachments

struct CouncilPendingImage: Identifiable, Equatable {
    let id: UUID
    let dataUrl: String
    let previewData: Data
    let displayName: String

    init(
        id: UUID = UUID(),
        dataUrl: String,
        previewData: Data,
        displayName: String = "图片"
    ) {
        self.id = id
        self.dataUrl = dataUrl
        self.previewData = previewData
        self.displayName = displayName
    }
}

struct CouncilPendingFile: Identifiable, Equatable {
    let id: UUID
    let fileName: String
    let fileType: String
    let preview: String
    let characterCount: Int
    let isTruncated: Bool
    let statusSummary: String
    let totalBytes: Int64

    init(
        id: UUID = UUID(),
        fileName: String,
        fileType: String,
        preview: String,
        characterCount: Int,
        isTruncated: Bool,
        statusSummary: String,
        totalBytes: Int64
    ) {
        self.id = id
        self.fileName = fileName
        self.fileType = fileType
        self.preview = preview
        self.characterCount = characterCount
        self.isTruncated = isTruncated
        self.statusSummary = statusSummary
        self.totalBytes = totalBytes
    }

    init(from read: SelectedDocumentReadResult, id: UUID = UUID()) {
        self.id = id
        self.fileName = read.fileName
        self.fileType = read.fileType
        self.preview = read.preview
        self.characterCount = read.characterCount
        self.isTruncated = read.isTruncated
        self.statusSummary = read.statusSummary
        self.totalBytes = read.totalBytes
    }
}

// MARK: - Resolved materials (post-parse / post-vision)

struct CouncilResolvedMaterials: Equatable {
    struct ImageContext: Equatable {
        let displayName: String
        let text: String
    }

    var files: [CouncilPendingFile]
    var imageContexts: [ImageContext]

    var isEmpty: Bool { files.isEmpty && imageContexts.isEmpty }

    var attachmentLabels: [String] {
        files.map(\.fileName) + imageContexts.map(\.displayName)
    }

    var displaySummary: String {
        let labels = attachmentLabels
        guard !labels.isEmpty else { return "" }
        if labels.count <= 3 {
            return labels.joined(separator: "、")
        }
        return "\(labels.prefix(3).joined(separator: "、")) 等 \(labels.count) 项"
    }

    /// Text block injected into host topic / research prompts.
    func promptBlock(
        budget: Int = CouncilMaterialLimits.maxMaterialsPromptCharacters
    ) -> String {
        guard !isEmpty else { return "" }
        var sections: [String] = []
        for (index, file) in files.enumerated() {
            sections.append(
                """
                [文件 \(index + 1)] \(file.fileName)
                类型：\(file.fileType)
                大小：\(file.totalBytes) bytes
                状态：\(file.statusSummary)
                内容：
                \(file.preview)
                """
            )
        }
        for (index, image) in imageContexts.enumerated() {
            sections.append(
                """
                [图片 \(index + 1)] \(image.displayName)
                视觉识别结果：
                \(image.text)
                """
            )
        }
        let joined = sections.joined(separator: "\n\n")
        if joined.count <= budget { return joined }
        let end = joined.index(joined.startIndex, offsetBy: budget)
        return String(joined[..<end]) + "\n…（材料过长，已截断）"
    }
}

// MARK: - Prompt composition (pure, testable)

enum CouncilMaterialsComposer {
    /// Default objective when the user only attaches materials and leaves the field empty.
    static let materialsOnlyObjective = "请根据上传材料提炼并完善一个有讨论价值的议题"

    static func displayObjective(userText: String, hasMaterials: Bool) -> String {
        let trimmed = userText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        return hasMaterials ? materialsOnlyObjective : ""
    }

    /// User-bubble body: short human text + attachment summary (not full material dump).
    static func userBubbleBody(userText: String, materials: CouncilResolvedMaterials?) -> String {
        let trimmed = userText.trimmingCharacters(in: .whitespacesAndNewlines)
        let labels = materials?.displaySummary ?? ""
        if labels.isEmpty { return trimmed }
        if trimmed.isEmpty {
            return "📎 材料：\(labels)"
        }
        return """
        \(trimmed)

        📎 材料：\(labels)
        """
    }

    /// Research query: keep it compact so search APIs stay useful.
    static func researchObjective(userText: String, materials: CouncilResolvedMaterials?) -> String {
        let objective = displayObjective(userText: userText, hasMaterials: materials.map { !$0.isEmpty } ?? false)
        guard let materials, !materials.isEmpty else { return objective }
        let labels = materials.attachmentLabels.joined(separator: "、")
        let previewHints = materials.files
            .prefix(2)
            .map { String($0.preview.prefix(200)) }
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        if previewHints.isEmpty {
            return "\(objective)\n相关材料：\(labels)"
        }
        return """
        \(objective)
        相关材料：\(labels)
        材料摘录：
        \(previewHints.joined(separator: "\n---\n"))
        """
    }
}

// MARK: - File parse

enum CouncilFileMaterialLoadOutcome {
    case success(CouncilPendingFile)
    case failure(String)
}

enum CouncilFileMaterialLoader {
    static func load(url: URL) async -> CouncilFileMaterialLoadOutcome {
        let type = UTType(filenameExtension: url.pathExtension)?.identifier
            ?? "application/octet-stream"
        let result = await DocumentAccessStore.previewFileForWorkspace(
            url: url,
            fileType: type,
            maxReadableBytes: CouncilMaterialLimits.maxReadableBytes,
            maxPreviewBytes: CouncilMaterialLimits.maxPreviewBytes,
            maxPreviewCharacters: CouncilMaterialLimits.maxPreviewCharacters
        )
        switch result {
        case .success(let read):
            return .success(CouncilPendingFile(from: read))
        case .failure(let error):
            return .failure(Self.userFacingMessage(for: error))
        }
    }

    private static func userFacingMessage(for error: Error) -> String {
        if let access = error as? DocumentAccessError {
            switch access {
            case .missingGrant:
                return "请先选择文件。"
            case .grantMismatch:
                return "文件授权与当前请求不匹配，请重新选择文件。"
            case .expiredGrant:
                return "文件授权已过期，请重新选择文件。"
            case .fileMissing:
                return "所选文件已不存在，请重新选择。"
            case .fileTooLarge:
                return "文件过大，超出可解析上限。"
            case .unknownFileSize:
                return "无法读取文件大小，请选择普通文件后重试。"
            case .alreadyReading:
                return "文件正在读取中，请稍候。"
            case .unsupportedFileType(let message),
                 .noReadableText(let message),
                 .readFailed(let message):
                return message
            }
        }
        return error.localizedDescription
    }
}

// MARK: - Image vision recognition

enum CouncilVisionMaterialError: LocalizedError, Equatable {
    case missingVisionModel
    case visionProviderUnavailable
    case emptyRecognition
    case recognitionFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingVisionModel:
            "请先在「默认模型 → 辅助任务」配置视觉识别模型"
        case .visionProviderUnavailable:
            "视觉识别模型的服务商不可用"
        case .emptyRecognition:
            "视觉识别模型没有返回可用内容"
        case .recognitionFailed(let message):
            "视觉识别失败：\(message)"
        }
    }
}

struct CouncilVisionMaterialRecognizer {
    private let textProvider: any IOSAgentTextProvider

    init(textProvider: any IOSAgentTextProvider = OpenAIKmpProviderAdapter()) {
        self.textProvider = textProvider
    }

    func recognize(
        images: [CouncilPendingImage],
        settings: Settings
    ) async -> Result<[CouncilResolvedMaterials.ImageContext], CouncilVisionMaterialError> {
        guard !images.isEmpty else { return .success([]) }
        guard let visionModel = settings.findModelById(uuid: settings.ocrModelId) else {
            return .failure(.missingVisionModel)
        }
        guard let providerSetting = ChatProviderConfiguration.provider(
            for: visionModel,
            providers: settings.providers
        ), ChatProviderConfiguration.issue(for: visionModel, provider: providerSetting) == nil else {
            return .failure(.visionProviderUnavailable)
        }

        let prompt = OcrPromptKt.resolveVisionRecognitionPrompt(prompt: settings.ocrPrompt)
        let assistant = settings.getCurrentAssistant()
        let params = TextGenerationParams(
            model: visionModel,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: assistant.customHeaders + visionModel.customHeaders,
            customBody: assistant.customBodies + visionModel.customBodies
        )

        var contexts: [CouncilResolvedMaterials.ImageContext] = []
        for (index, image) in images.enumerated() {
            if Task.isCancelled {
                return .failure(.recognitionFailed("已取消"))
            }
            let requestMessages = [
                UIMessage.companion.system(prompt: prompt),
                Self.imageOnlyUserMessage(dataUrl: image.dataUrl),
            ]
            do {
                let chunk = try await textProvider.generateText(
                    providerSetting: providerSetting,
                    messages: requestMessages,
                    params: params
                )
                let text = chunk.choices.first?.message?.toText()
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !text.isEmpty else {
                    return .failure(.emptyRecognition)
                }
                let name = image.displayName == "图片"
                    ? "图片 \(index + 1)"
                    : image.displayName
                contexts.append(
                    CouncilResolvedMaterials.ImageContext(
                        displayName: name,
                        text: """
                        <image_context>
                        \(text)
                        </image_context>
                        * 以上 image_context 是对用户上传图片的视觉识别结果，不是用户的提问。
                        """
                    )
                )
            } catch {
                return .failure(.recognitionFailed((error as NSError).localizedDescription))
            }
        }
        return .success(contexts)
    }

    private static func imageOnlyUserMessage(dataUrl: String) -> UIMessage {
        let now = chatNowLocalDateTime()
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Image(url: dataUrl, metadata: nil)],
            annotations: [],
            createdAt: now,
            finishedAt: now,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }
}
