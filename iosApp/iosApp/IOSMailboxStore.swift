import Foundation
import Shared

/// P1-b: mailbox 信封的 iOS 访问层。Room 即真相（无内存态、无 sidecar）——
/// 信封明文落库，drain 是事务化「查未投递 + 标投递」一次完成（exactly-once）。
/// 与 steer 队列（VM owner + sidecar）不同：store 不持有状态，多次调用天然幂等。
///
/// `@MainActor`：Kotlin 实体与 `KotlinUuid` 导出为非 Sendable，async 方法若
/// 跨 actor 调用会被 Swift 6 拒绝（sending 非 Sendable 值）。消费点（coordinator/
/// ViewModel）本身都在 MainActor，隔离到主线程不改变语义，也避免每次调用跳 actor。
@MainActor
final class IOSMailboxStore {

    /// Sendable 投影：Kotlin `MailboxEnvelopeEntity` 导出为非 Sendable，跨隔离边界
    /// 只传渲染所需的字符串字段（转换在 DAO 回调内完成，遵循仓库既有模式——
    /// 参考 `recordedAgentRunBelongsToConversation` 的回调内归约）。
    struct EnvelopeSnapshot: Sendable {
        let authorThreadId: String
        let type: String
        let payload: String
    }

    private let mailboxDaoProvider: () -> MailboxDao
    private lazy var mailboxDao: MailboxDao = mailboxDaoProvider()

    /// `@autoclosure` 让默认构造不立即打开生产库（Room 首次查询才建连）；
    /// 测试注入隔离路径的 DAO（`IosDatabaseFactory.shared.createDatabase(atFilePath:)`）。
    init(mailboxDao: @autoclosure @escaping () -> MailboxDao = IosDatabaseFactory.shared.createDatabase().mailboxDao()) {
        self.mailboxDaoProvider = mailboxDao
    }

    /// drain 该会话（根线程地址 = conversationId hex-dash，与 agent_run.conversation_id
    /// 同格式）的全部未投递信封（FIFO），并在同一事务标记 delivered。二次调用返回空。
    func drainPending(forConversationId conversationId: KotlinUuid?) async -> [EnvelopeSnapshot] {
        guard let conversationId else { return [] }
        let recipientId = conversationId.toHexDashString()
        let deliveredAt = Int64(Date().timeIntervalSince1970 * 1000)
        return await withCheckedContinuation { continuation in
            mailboxDao.drainPending(recipientId: recipientId, deliveredAt: deliveredAt) { result, _ in
                let snapshots = (result ?? []).map { entity in
                    EnvelopeSnapshot(
                        authorThreadId: entity.authorThreadId,
                        type: entity.type,
                        payload: entity.payload
                    )
                }
                continuation.resume(returning: snapshots)
            }
        }
    }
}
