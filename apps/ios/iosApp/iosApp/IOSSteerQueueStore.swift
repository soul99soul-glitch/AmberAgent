import Foundation
import Shared

/// 排队图（与 composer `PendingChatImage` 同构；preview 用 base64 落盘）。
struct IOSSteerQueueImage: Codable, Equatable {
    let dataUrl: String
    let previewBase64: String

    init(dataUrl: String, previewData: Data) {
        self.dataUrl = dataUrl
        self.previewBase64 = previewData.base64EncodedString()
    }

    var previewData: Data {
        Data(base64Encoded: previewBase64) ?? Data()
    }
}

/// 排队选中文件预览（镜像 `SelectedDocumentReadResult`，供 sidecar Codable）。
struct IOSSteerQueuedFile: Codable, Equatable {
    let fileName: String
    let fileType: String
    let totalBytes: Int64
    let bytesRead: Int
    let characterCount: Int
    let preview: String
    let isTruncated: Bool
    let note: String?

    init(_ result: SelectedDocumentReadResult) {
        fileName = result.fileName
        fileType = result.fileType
        totalBytes = result.totalBytes
        bytesRead = result.bytesRead
        characterCount = result.characterCount
        preview = result.preview
        isTruncated = result.isTruncated
        note = result.note
    }

    var asSelectedDocument: SelectedDocumentReadResult {
        SelectedDocumentReadResult(
            fileName: fileName,
            fileType: fileType,
            totalBytes: totalBytes,
            bytesRead: bytesRead,
            characterCount: characterCount,
            preview: preview,
            isTruncated: isTruncated,
            note: note
        )
    }
}

/// 一条生成中排队的 steer 消息。旧 sidecar 仅有 text 时 images/selectedFile 缺省为空。
struct IOSSteerQueueEntry: Codable, Equatable, Identifiable {
    let id: String
    let text: String
    let createdAt: Date
    var images: [IOSSteerQueueImage]
    var selectedFile: IOSSteerQueuedFile?

    var hasAttachments: Bool {
        !images.isEmpty || selectedFile != nil
    }

    init(
        id: String,
        text: String,
        createdAt: Date,
        images: [IOSSteerQueueImage] = [],
        selectedFile: IOSSteerQueuedFile? = nil
    ) {
        self.id = id
        self.text = text
        self.createdAt = createdAt
        self.images = images
        self.selectedFile = selectedFile
    }

    private enum CodingKeys: String, CodingKey {
        case id, text, createdAt, images, selectedFile
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        text = try c.decode(String.self, forKey: .text)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        images = try c.decodeIfPresent([IOSSteerQueueImage].self, forKey: .images) ?? []
        selectedFile = try c.decodeIfPresent(IOSSteerQueuedFile.self, forKey: .selectedFile)
    }
}

/// P1-a: per-conversation steer 队列 sidecar（对齐 Android `PendingMessageStore`：
/// `<filesDir>/amberagent/message-queue/{conversationId}.json`）。
///
/// 文件：`Documents/steer-queue/{conversationId}.json`；原子写；空队列删除文件。
/// 写法沿用仓库 sidecar 先例（`IOSConversationStore` 的 list-previews.json）：
/// `JSONEncoder` + `data.write(options: .atomic)`。纯文件 IO，无跨状态；内存队列的
/// 唯一 owner 是 `ChatViewModel`，本类只维护磁盘镜像，进程死亡后队列不丢。
final class IOSSteerQueueStore {

    /// 队列上限，对齐 Android `MAX_PENDING_USER_MESSAGES`（`PendingUserMessage.kt`）。
    static let maxPendingUserMessages = 20

    private let directoryURL: URL

    init(directoryURL: URL? = nil) {
        if let directoryURL {
            self.directoryURL = directoryURL
        } else {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.directoryURL = documents.appendingPathComponent("steer-queue")
        }
    }

    func load(conversationId: KotlinUuid?) -> [IOSSteerQueueEntry] {
        guard let url = fileURL(for: conversationId) else { return [] }
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([IOSSteerQueueEntry].self, from: data)) ?? []
    }

    /// 原子写整条队列；空队列删除文件（与 Android `persistBlocking` 一致）。
    func persist(_ entries: [IOSSteerQueueEntry], for conversationId: KotlinUuid?) {
        guard let url = fileURL(for: conversationId) else { return }
        do {
            if entries.isEmpty {
                try? FileManager.default.removeItem(at: url)
                return
            }
            try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(entries)
            try data.write(to: url, options: .atomic)
        } catch {
            NSLog("[AmberChat] steer queue persist failed: \(error)")
        }
    }

    func removeAll(for conversationId: KotlinUuid?) {
        persist([], for: conversationId)
    }

    private func fileURL(for conversationId: KotlinUuid?) -> URL? {
        guard let conversationId else { return nil }
        return directoryURL.appendingPathComponent("\(conversationId.toHexDashString()).json")
    }
}
