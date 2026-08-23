import Foundation

/// P1-d: 进程内 mailbox 活动广播。生产全进程共用一个实例（`.shared`），
/// 测试注入独立实例隔离信号。
///
/// 信号点（信号必然发生在对应信封入 Room 之后，保证「订阅前的事件由
/// pending 检查兜住、订阅后的事件由缓冲流兜住」两个窗口都不丢）：
/// - `send_message` / `followup_task` 的 enqueue 成功后 → signal(收件方 hex)
/// - `notifyRunTerminal`（FINAL_ANSWER 投递）→ signal(父线程 hex)
/// - `ChatViewModel.enqueueSteerMessage`（steer 打断 wait）→ signal(当前会话 hex)
///
/// 订阅语义：`events(for:)` 在 actor 隔离方法内同步注册——await 返回时订阅
/// 已生效（后续 `signal` 与该注册按 actor 串行序排列，无丢失窗口）。流带
/// `bufferingNewest(1)` 缓冲：订阅后、消费者开始迭代前到达的信号不丢。
actor IOSMailboxActivityCenter {

    static let shared = IOSMailboxActivityCenter()

    private var listeners: [String: [UUID: AsyncStream<Void>.Continuation]] = [:]

    /// 订阅指定会话 hex 的活动事件流。每个订阅者独立缓冲 1 个最新事件。
    func events(for conversationIdHex: String) -> AsyncStream<Void> {
        AsyncStream<Void>(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            listeners[conversationIdHex, default: [:]][id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { await self?.detach(id: id, conversationIdHex: conversationIdHex) }
            }
        }
    }

    /// 向指定会话 hex 广播一次活动（幂等：无订阅者时零开销）。
    func signal(conversationIdHex: String) {
        guard let values = listeners[conversationIdHex]?.values else { return }
        for continuation in values {
            continuation.yield(())
        }
    }

    /// 测试/诊断用：当前 hex 的订阅者数（wait_agent 测试用它同步「订阅已生效」）。
    func listenerCount(for conversationIdHex: String) -> Int {
        listeners[conversationIdHex]?.count ?? 0
    }

    private func detach(id: UUID, conversationIdHex: String) {
        listeners[conversationIdHex]?.removeValue(forKey: id)
        if listeners[conversationIdHex]?.isEmpty == true {
            listeners[conversationIdHex] = nil
        }
    }
}
