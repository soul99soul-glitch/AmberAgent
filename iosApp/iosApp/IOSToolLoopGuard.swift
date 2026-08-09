import Foundation
@preconcurrency import Shared

/// I-5(可解释终态)打转守护:检测模型以完全相同的参数重复调用同一工具,先提醒
/// 后停止。docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md §W5。
///
/// 一个检测器、一种停法——刻意不做 Todo 完成度检查、"懒惰"检测、目标续跑。
/// Grok 的 Action Stationarity 本身设计不错,但同时养了 Goal/TodoGate/StopGate/
/// Laziness Detector 好几套自治继续机制,以至于"谁有权让 agent 继续、谁有权让它
/// 停"说不清楚。这里只留一个类型、一条决策路径,别的机制不加。
struct IOSToolLoopGuard {
    enum Verdict: Equatable {
        case proceed
        /// 第 2 次相同签名:仍执行,但结果之后要附带提醒(由调用方把 reminder
        /// 追加进工具输出)。
        case proceedAndRemind(reminder: String)
        /// 第 3 次相同签名:不执行,终止本轮。
        case stop(reason: String)
    }

    /// Grok 用 8/16 次才提醒/停止,是因为它的轮次预算本身宽裕。amber 前台
    /// maxToolResumeCount=6、引擎 maxSteps=8——预算里重复到第 3 次已经烧掉
    /// 大半,没有"慢慢提醒几次"的空间,所以阈值直接定在 2/3。
    private static let remindAtCount = 2

    static let reminderText =
        "你刚以完全相同的参数调用过此工具，结果如上。请改变参数或调整策略，不要重复相同调用。"

    static func stopReason(toolName: String) -> String {
        "模型连续以相同参数重复调用工具 \(toolName)，已停止本轮以避免空耗。"
    }

    /// 签名 → 已出现次数。纯内存、run 内生效,不做任何 I/O 或跨 run 持久化。
    private var counts: [String: Int] = [:]

    /// 签名 = toolName + 规范化参数摘要。严格解析闸门会先拒绝非 object JSON；
    /// 这里再用 sortedKeys 消除空格和键顺序差异，避免同一调用仅换一种序列化
    /// 形式就绕过重复检测。单元测试直接传入的非 JSON 夹具保留原文摘要语义。
    mutating func check(toolName: String, input: String) -> Verdict {
        let signature = toolName + "\u{0}" + chatInputDigest(for: Self.canonicalInput(input))
        let count = (counts[signature] ?? 0) + 1
        counts[signature] = count
        switch count {
        case ..<Self.remindAtCount:
            return .proceed
        case Self.remindAtCount:
            return .proceedAndRemind(reminder: Self.reminderText)
        default:
            return .stop(reason: Self.stopReason(toolName: toolName))
        }
    }

    private static func canonicalInput(_ input: String) -> String {
        guard let data = input.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              object is [String: Any],
              let canonicalData = try? JSONSerialization.data(
                withJSONObject: object,
                options: [.sortedKeys]
              ),
              let canonical = String(data: canonicalData, encoding: .utf8) else {
            return input
        }
        return canonical
    }
}

/// 把 `reminder` 作为追加的 Text part 写进匹配 `toolCallId` 的工具输出——是
/// append,不是替换(原结果仍需可见,提醒只是补充上下文)。纯函数、无 I/O,
/// 形状照抄 `IOSAgentRunLedger.replacingToolOutput` 的消息重建方式,但语义不同。
func appendingToolLoopReminder(
    _ reminder: String,
    toToolCallId toolCallId: String,
    in messages: [UIMessage]
) -> [UIMessage] {
    var didAppend = false
    return messages.map { message in
        guard message.role == MessageRole.assistant, !didAppend else { return message }
        var didChangeMessage = false
        let parts = message.parts.map { part -> UIMessagePart in
            guard !didAppend,
                  let toolPart = part as? UIMessagePart.Tool,
                  toolPart.toolCallId == toolCallId else { return part }
            didAppend = true
            didChangeMessage = true
            return UIMessagePart.Tool(
                toolCallId: toolPart.toolCallId,
                toolName: toolPart.toolName,
                input: toolPart.input,
                output: toolPart.output + [UIMessagePart.Text(text: reminder, metadata: nil)],
                approvalState: toolPart.approvalState,
                streamIndex: toolPart.streamIndex,
                metadata: nil
            )
        }
        guard didChangeMessage else { return message }
        return UIMessage(
            id: message.id,
            role: message.role,
            parts: parts,
            annotations: message.annotations,
            createdAt: message.createdAt,
            finishedAt: message.finishedAt,
            modelId: message.modelId,
            usage: message.usage,
            translation: message.translation
        )
    }
}
