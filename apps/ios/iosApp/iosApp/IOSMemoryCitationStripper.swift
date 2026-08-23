import Foundation
@preconcurrency import Shared

/// P2-c：模型驱动的记忆引用隐藏标记。
///
/// 格式：`<amber-mem-cite>{"ids":[1,2],"note":"可选"}</amber-mem-cite>`
///
/// 模型在引用注入记忆时附上该标记；harness 在消息文本到达渲染/持久化之前剥离
/// 标记，并把 body 解析为结构化引用（供 `IOSMemoryPersistence.markUsed` 记录
/// 模型驱动使用，与 P2-b 召回侧标记叠加）。
///
/// 语义镜像 codex `InlineHiddenTagParser`（`codex-rs/utils/stream-parser`）：
/// 字面匹配、不嵌套；跨 chunk 的部分标签进缓冲；EOF flush 时未闭合标签自动闭合
/// （缓冲体作为 citation 提取、标签本身从可见文本剥离）；相似但不完全匹配的
/// 前缀（如 `<amber-mem-cit` 后接不成立字符）原样吐出缓冲，不吞字。
struct IOSMemoryCitation: Equatable {
    /// 被引用的 MemoryRecord id（body 中数字与数字字符串均容忍）。
    let ids: [Int32]
    /// 模型可选的附注。
    let note: String?
}

/// 纯 Swift 流式状态机。线程非安全：由调用方（`IOSMemoryCitationTracker`）串行。
final class IOSMemoryCitationStripper {

    static let openTag = "<amber-mem-cite>"
    static let closeTag = "</amber-mem-cite>"

    /// 尚未决定去向的文本：可能是普通可见文本，也可能以 open/close 的前缀结尾。
    private var pending = ""
    /// 非 nil 表示已进入标签体内，缓冲的 body 内容。
    private var activeContent: String?
    private var didFinish = false

    private(set) var citations: [IOSMemoryCitation] = []

    /// 逐 chunk 喂入文本，返回该 chunk 剥离后的可见输出（字节级不变的普通文本）。
    func feed(_ text: String) -> String {
        pending += text
        var visible = ""
        while true {
            if let active = activeContent {
                // 在标签体内：找 close。
                if let closeRange = pending.range(of: Self.closeTag) {
                    let body = active + pending[..<closeRange.lowerBound]
                    pending = String(pending[closeRange.upperBound...])
                    activeContent = nil
                    record(String(body))
                    continue
                }
                // close 未到：只保留可能是 close 前缀的尾部，其余进 body。
                let keep = Self.longestSuffixPrefixLen(pending, Self.closeTag)
                if pending.count > keep {
                    let split = pending.index(pending.startIndex, offsetBy: pending.count - keep)
                    activeContent = active + String(pending[..<split])
                    pending = String(pending[split...])
                }
                break
            } else {
                // 空闲态：找 open。
                if let openRange = pending.range(of: Self.openTag) {
                    visible += String(pending[..<openRange.lowerBound])
                    pending = String(pending[openRange.upperBound...])
                    activeContent = ""
                    continue
                }
                // open 未到：只保留可能是 open 前缀的尾部，其余为可见文本。
                let keep = Self.longestSuffixPrefixLen(pending, Self.openTag)
                if pending.count > keep {
                    let split = pending.index(pending.startIndex, offsetBy: pending.count - keep)
                    visible += String(pending[..<split])
                    pending = String(pending[split...])
                }
                break
            }
        }
        return visible
    }

    /// 流结束 flush：返回剩余可见文本。未闭合标签在此自动闭合——缓冲体作为
    /// citation 提取，不从可见文本输出（codex 同款 auto-close）。幂等。
    @discardableResult
    func finish() -> String {
        guard !didFinish else { return "" }
        didFinish = true
        if let active = activeContent {
            let body = active + pending
            pending = ""
            activeContent = nil
            record(body)
            return ""
        }
        let visible = pending
        pending = ""
        return visible
    }

    /// body 解析为结构化引用。
    ///
    /// 设计决策：body 非 JSON（或结构不符）时标签仍剥离——模型漏出的残缺标记
    /// 不泄漏到可见/持久化文本——但不产生引用记录，不把垃圾 body 记成一次使用。
    private func record(_ body: String) {
        guard let data = body.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return
        }
        var ids: [Int32] = []
        if let rawIds = object["ids"] as? [Any] {
            for item in rawIds {
                if let number = item as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() {
                    ids.append(number.int32Value)
                } else if let string = item as? String, let value = Int32(string) {
                    ids.append(value)
                }
            }
        }
        let note: String?
        if let rawNote = object["note"] as? String, !rawNote.isEmpty {
            note = rawNote
        } else {
            note = nil
        }
        citations.append(IOSMemoryCitation(ids: ids, note: note))
    }

    /// 最长「text 后缀 == needle 前缀」长度。上限 needle.count - 1：完整 needle
    /// 已被 `range(of:)` 分支处理，这里只处理跨 chunk 的部分前缀。
    private static func longestSuffixPrefixLen(_ text: String, _ needle: String) -> Int {
        guard text.contains("<") else { return 0 }
        let maxK = min(text.count, needle.count - 1)
        guard maxK > 0 else { return 0 }
        for k in stride(from: maxK, through: 1, by: -1) {
            if text.hasSuffix(String(needle.prefix(k))) { return k }
        }
        return 0
    }
}

/// 流级封装：线程安全（内部锁串行化 provider 回调线程与 MainActor 终结路径），
/// 按 assistant 消息维护一个 stripper。
///
/// 接入契约：只让 assistant 文本 delta 通过 stripper；user/tool 文本（工具输出、
/// harness 构造的消息）不经此路径——工具输出里可能有用户内容文本，不能误剥离。
///
/// public：`IOSAgentToolEngine.run`（P2-c 后台接线）以 @Sendable 盒传入，
/// 需要跨模块可见（成员保持 internal，仅类声明公开）。
public final class IOSMemoryCitationTracker: @unchecked Sendable {
    private let lock = NSLock()
    private let stripper = IOSMemoryCitationStripper()
    private var capturedIds: Set<Int32> = []

    /// 剥离 chunk 中 assistant 文本 delta 的隐藏标记；无标记时原样返回同一 chunk。
    func stripped(_ chunk: MessageChunk) -> MessageChunk {
        lock.lock()
        defer { lock.unlock() }
        let result = Self.stripping(chunk, stripper: stripper)
        capturedIds = Set(stripper.citations.flatMap(\.ids))
        return result
    }

    /// 流终结 flush（幂等）：返回剩余可见文本，调用后再次调用返回空。
    @discardableResult
    func finish() -> String {
        lock.lock()
        defer { lock.unlock() }
        let remainder = stripper.finish()
        capturedIds = Set(stripper.citations.flatMap(\.ids))
        return remainder
    }

    /// 已收集的引用记忆 id（JSON body 解析所得；非 JSON body 不产生）。
    var citationIds: Set<Int32> {
        lock.lock()
        defer { lock.unlock() }
        return capturedIds
    }

    /// 把 `finish()` 返回的剩余可见文本并入消息快照：追加到最后一条 assistant
    /// 消息的最后一个文本 part 末尾（找不到则丢弃并保持原样）。
    static func appendingCitationRemainder(_ remainder: String, to messages: [UIMessage]) -> [UIMessage] {
        guard !remainder.isEmpty,
              let assistantIndex = messages.indices.reversed().first(where: { messages[$0].role == MessageRole.assistant }) else {
            return messages
        }
        var updated = messages
        let message = messages[assistantIndex]
        var parts = message.parts
        if let lastTextIndex = parts.indices.reversed().first(where: { parts[$0] is UIMessagePart.Text }),
           let text = parts[lastTextIndex] as? UIMessagePart.Text {
            parts[lastTextIndex] = UIMessagePart.Text(text: text.text + remainder, metadata: text.metadata)
        } else {
            parts.append(UIMessagePart.Text(text: remainder, metadata: nil))
        }
        updated[assistantIndex] = UIMessage(
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
        return updated
    }

    private static func stripping(_ chunk: MessageChunk, stripper: IOSMemoryCitationStripper) -> MessageChunk {
        var changed = false
        var newChoices: [UIMessageChoice] = []
        newChoices.reserveCapacity(chunk.choices.count)
        for choice in chunk.choices {
            var newDelta: UIMessage?
            var newMessage: UIMessage?
            if let delta = choice.delta {
                newDelta = strippingAssistantText(delta, stripper: stripper)
            }
            // delta == nil 的非流式终态消息也做一次性剥离：同一 per-message
            // stripper 的连续状态，尾部由终结路径 finish() 收口。
            if choice.delta == nil, let message = choice.message {
                newMessage = strippingAssistantText(message, stripper: stripper)
            }
            if newDelta == nil, newMessage == nil {
                newChoices.append(choice)
            } else {
                newChoices.append(UIMessageChoice(
                    index: choice.index,
                    delta: newDelta ?? choice.delta,
                    message: newMessage ?? choice.message,
                    finishReason: choice.finishReason
                ))
                changed = true
            }
        }
        guard changed else { return chunk }
        return MessageChunk(id: chunk.id, model: chunk.model, choices: newChoices, usage: chunk.usage)
    }

    /// 只处理 assistant 文本 part（reasoning/tool/image 等 part 原样保留）。
    /// 无变化时返回 nil，调用方复用原对象（零拷贝路径）。
    private static func strippingAssistantText(_ message: UIMessage, stripper: IOSMemoryCitationStripper) -> UIMessage? {
        guard message.role == MessageRole.assistant else { return nil }
        var changed = false
        var newParts: [UIMessagePart] = []
        newParts.reserveCapacity(message.parts.count)
        for part in message.parts {
            if let text = part as? UIMessagePart.Text {
                let stripped = stripper.feed(text.text)
                if stripped != text.text {
                    changed = true
                    newParts.append(UIMessagePart.Text(text: stripped, metadata: text.metadata))
                } else {
                    newParts.append(part)
                }
            } else {
                newParts.append(part)
            }
        }
        guard changed else { return nil }
        return UIMessage(
            id: message.id,
            role: message.role,
            parts: newParts,
            annotations: message.annotations,
            createdAt: message.createdAt,
            finishedAt: message.finishedAt,
            modelId: message.modelId,
            usage: message.usage,
            translation: message.translation
        )
    }
}
