import Foundation
import Shared

/// 一条生成中排队的 steer 消息（v1 仅文本；对齐 Android `PendingUserMessage` 的
/// STEER 语义，但条目精简为 {id, text, createdAt}）。
struct IOSSteerQueueEntry: Codable, Equatable, Identifiable {
    let id: String
    let text: String
    let createdAt: Date
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
