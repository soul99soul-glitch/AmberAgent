import Foundation

/// 后台生成任务的磁盘文件命名。payload 与挂起态共用同一套 requestId 净化规则，
/// 两者必须一致，否则挂起记录会指向一个找不到 payload 的孤儿任务。
enum IOSChatBackgroundJobFileNaming {
    static func sanitized(_ requestId: String) -> String {
        String(requestId.map { character -> Character in
            character.isLetter || character.isNumber || character == "." || character == "-"
                ? character
                : "-"
        })
    }
}

/// 后台生成被系统中断（BGContinuedProcessingTask 到期）后的可恢复挂起态。
///
/// 到期不等于失败：payload 继续留在磁盘上，这里只额外记录「已经流出来的正文」
/// 和「已经自动恢复过几次」。回到前台时重新投递一次后台任务把这轮跑完；
/// 超过恢复次数上限才降级成一条带残文的失败消息，交回给用户手动重试。
struct IOSChatBackgroundSuspensionRecord: Codable, Equatable {
    /// 允许自动恢复的最大次数。防止「到期 → 恢复 → 又到期」无限打转。
    static let maxResumeAttempts = 2

    let requestId: String
    let runId: String
    var partialAssistantText: String
    var suspendedAt: Int64
    var resumeCount: Int

    init(
        requestId: String,
        runId: String,
        partialAssistantText: String,
        suspendedAt: Int64,
        resumeCount: Int = 0
    ) {
        self.requestId = requestId
        self.runId = runId
        self.partialAssistantText = partialAssistantText
        self.suspendedAt = suspendedAt
        self.resumeCount = resumeCount
    }

    var canResume: Bool { resumeCount < Self.maxResumeAttempts }

    func markingResumeAttempt() -> Self {
        var next = self
        next.resumeCount += 1
        return next
    }
}

/// 挂起记录的落盘读写。与 payload 同目录，文件名为 `<sanitized requestId>.suspended.json`。
struct IOSChatBackgroundSuspensionStore {
    static let fileSuffix = ".suspended.json"

    let directory: URL

    init(directory: URL) {
        self.directory = directory
    }

    func url(for requestId: String) -> URL {
        directory.appendingPathComponent(
            IOSChatBackgroundJobFileNaming.sanitized(requestId) + Self.fileSuffix
        )
    }

    func save(_ record: IOSChatBackgroundSuspensionRecord) {
        do {
            let data = try JSONEncoder().encode(record)
            try data.write(to: url(for: record.requestId), options: [.atomic])
        } catch {
            NSLog("[AmberChatBG] Failed to persist suspension record \(record.requestId): \(error)")
        }
    }

    func load(requestId: String) -> IOSChatBackgroundSuspensionRecord? {
        guard let data = try? Data(contentsOf: url(for: requestId)) else { return nil }
        return try? JSONDecoder().decode(IOSChatBackgroundSuspensionRecord.self, from: data)
    }

    func remove(requestId: String) {
        try? FileManager.default.removeItem(at: url(for: requestId))
    }

    /// 目录里所有挂起记录，按挂起时间升序（先被中断的先恢复）。
    func allRecords() -> [IOSChatBackgroundSuspensionRecord] {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        )) ?? []
        return contents
            .filter { $0.lastPathComponent.hasSuffix(Self.fileSuffix) }
            .compactMap { url -> IOSChatBackgroundSuspensionRecord? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? JSONDecoder().decode(IOSChatBackgroundSuspensionRecord.self, from: data)
            }
            .sorted { $0.suspendedAt < $1.suspendedAt }
    }
}
