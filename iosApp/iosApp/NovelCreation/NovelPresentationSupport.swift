import Foundation
import SwiftUI
import UIKit

@MainActor
enum NovelTextInputCommitter {
    /// Commits any IME marked text, resigns the first responder, then runs
    /// `action` after SwiftUI bindings have a chance to catch up.
    ///
    /// Call this before reading `@State` / bindings on save, submit, rename,
    /// sheet dismiss, or focus transitions. Never clear `FocusState` *before*
    /// calling this — resigning without `unmarkText` can discard the last
    /// Chinese composition so the subsequent binding read misses those glyphs.
    ///
    /// For multi-field Form editors (本章计划 etc.), prefer
    /// `NovelIMEFieldBank.commitAll()` so UIKit text is written into bindings
    /// synchronously; SwiftUI `TextField` bindings alone remain racy under IME.
    static func perform(
        firstResponder: UIView? = nil,
        fieldBank: NovelIMEFieldBank? = nil,
        _ action: @escaping @MainActor () -> Void
    ) {
        // UIKit-backed fields: flush marked text into @Binding before resign.
        fieldBank?.commitAll()
        // Also capture any remaining first-responder UIKit text (native
        // SwiftUI TextField wraps UITextField/UITextView) into the bank-less path.
        _ = commitAndReadActiveUIKitText(firstResponder: firstResponder)
        commitMarkedText(in: firstResponder)
        // SwiftUI TextField/TextEditor often apply UIKit text → Binding one
        // main turn after `unmarkText`. Two yields cover resign-side bookkeeping
        // without crossing a @Sendable DispatchQueue boundary under Swift 6.
        Task { @MainActor in
            await Task.yield()
            await Task.yield()
            action()
        }
    }

    /// Whether the active text input still has an in-progress IME composition.
    static func hasMarkedText(firstResponder: UIView? = nil) -> Bool {
        let responder = firstResponder ?? activeFirstResponder()
        guard let input = responder as? UITextInput else { return false }
        return input.markedTextRange != nil
    }

    /// Unmark the active field and return its UIKit text immediately.
    /// Prefer this over reading a SwiftUI binding right after a button tap.
    @discardableResult
    static func commitAndReadActiveUIKitText(firstResponder: UIView? = nil) -> String? {
        let responder = firstResponder ?? activeFirstResponder()
        if let textField = responder as? UITextField {
            textField.unmarkText()
            return textField.text
        }
        if let textView = responder as? UITextView {
            textView.unmarkText()
            return textView.text
        }
        if let input = responder as? UITextInput {
            input.unmarkText()
        }
        return nil
    }

    static func commitMarkedText(in firstResponder: UIView? = nil) {
        if let firstResponder {
            (firstResponder as? UITextInput)?.unmarkText()
            firstResponder.resignFirstResponder()
            return
        }
        // unmark before resign: resign alone can drop marked text.
        UIApplication.shared.sendAction(
            #selector(UITextInput.unmarkText),
            to: nil,
            from: nil,
            for: nil
        )
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    static func activeFirstResponder() -> UIView? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            for window in scene.windows where !window.isHidden {
                if let responder = findFirstResponder(in: window) {
                    return responder
                }
            }
        }
        return nil
    }

    private static func findFirstResponder(in view: UIView) -> UIView? {
        if view.isFirstResponder { return view }
        for subview in view.subviews {
            if let responder = findFirstResponder(in: subview) {
                return responder
            }
        }
        return nil
    }
}

// MARK: - UIKit-backed IME-safe fields

/// Tracks UIKit-backed novel form fields so save can flush marked text into
/// SwiftUI bindings **synchronously** (not after a racy Binding update).
@MainActor
final class NovelIMEFieldBank {
    private final class WeakBox {
        weak var host: NovelIMEFieldHosting?
        init(_ host: NovelIMEFieldHosting) { self.host = host }
    }

    private var hosts: [ObjectIdentifier: WeakBox] = [:]

    func register(_ host: NovelIMEFieldHosting) {
        hosts[ObjectIdentifier(host)] = WeakBox(host)
        prune()
    }

    func unregister(_ host: NovelIMEFieldHosting) {
        hosts.removeValue(forKey: ObjectIdentifier(host))
    }

    /// Unmark every registered field and push UIKit text into its binding.
    func commitAll() {
        prune()
        for box in hosts.values {
            box.host?.flushMarkedTextIntoBinding()
        }
    }

    var hasAnyMarkedText: Bool {
        prune()
        return hosts.values.contains { $0.host?.hasMarkedText == true }
    }

    private func prune() {
        hosts = hosts.filter { $0.value.host != nil }
    }
}

@MainActor
protocol NovelIMEFieldHosting: AnyObject {
    var hasMarkedText: Bool { get }
    func flushMarkedTextIntoBinding()
}

/// Single-line field: UITextField that ignores external binding writes while
/// Chinese (or any) IME composition is active.
struct NovelIMETextField: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String = ""
    var isEnabled: Bool = true
    var bank: NovelIMEFieldBank? = nil

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text, bank: bank)
    }

    func makeUIView(context: Context) -> UITextField {
        let textField = UITextField()
        textField.delegate = context.coordinator
        textField.placeholder = placeholder
        textField.text = text
        textField.font = .preferredFont(forTextStyle: .body)
        textField.adjustsFontForContentSizeCategory = true
        textField.textColor = .label
        textField.tintColor = UIColor(AmberTheme.accent)
        textField.borderStyle = .none
        textField.clearButtonMode = .never
        textField.autocorrectionType = .no
        textField.spellCheckingType = .no
        textField.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        textField.addTarget(
            context.coordinator,
            action: #selector(Coordinator.editingChanged(_:)),
            for: .editingChanged
        )
        context.coordinator.textField = textField
        bank?.register(context.coordinator)
        return textField
    }

    func updateUIView(_ textField: UITextField, context: Context) {
        context.coordinator.text = $text
        context.coordinator.bank = bank
        bank?.register(context.coordinator)
        textField.placeholder = placeholder
        textField.isEnabled = isEnabled
        // Never clobber an in-progress composition, and never replace equal text
        // (avoids caret jumps that also break IME).
        if textField.markedTextRange == nil, textField.text != text {
            textField.text = text
        }
        if !isEnabled, textField.isFirstResponder {
            textField.resignFirstResponder()
        }
    }

    static func dismantleUIView(_ uiView: UITextField, coordinator: Coordinator) {
        coordinator.bank?.unregister(coordinator)
        if coordinator.textField === uiView {
            coordinator.textField = nil
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate, NovelIMEFieldHosting {
        var text: Binding<String>
        var bank: NovelIMEFieldBank?
        weak var textField: UITextField?

        init(text: Binding<String>, bank: NovelIMEFieldBank?) {
            self.text = text
            self.bank = bank
        }

        var hasMarkedText: Bool { textField?.markedTextRange != nil }

        func flushMarkedTextIntoBinding() {
            guard let textField else { return }
            textField.unmarkText()
            let value = textField.text ?? ""
            if text.wrappedValue != value {
                text.wrappedValue = value
            }
        }

        func textFieldDidChangeSelection(_ textField: UITextField) {
            // Selection changes during IME; keep binding in sync with provisional text
            // so the UI never "snaps back" to a stale @State when composition ends.
            let value = textField.text ?? ""
            if text.wrappedValue != value {
                text.wrappedValue = value
            }
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            flushMarkedTextIntoBinding()
        }

        func textField(
            _ textField: UITextField,
            shouldChangeCharactersIn range: NSRange,
            replacementString string: String
        ) -> Bool {
            true
        }

        @objc func editingChanged(_ textField: UITextField) {
            let value = textField.text ?? ""
            if text.wrappedValue != value {
                text.wrappedValue = value
            }
        }
    }
}

/// Multi-line field: UITextView with the same marked-text safety as the composer.
struct NovelIMETextEditor: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String = ""
    var isEnabled: Bool = true
    var minHeight: CGFloat = 88
    var bank: NovelIMEFieldBank? = nil

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text, bank: bank, placeholder: placeholder)
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.delegate = context.coordinator
        textView.text = text
        textView.font = .preferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = true
        textView.textColor = .label
        textView.backgroundColor = .clear
        textView.tintColor = UIColor(AmberTheme.accent)
        textView.textContainerInset = UIEdgeInsets(top: 6, left: 0, bottom: 6, right: 0)
        textView.textContainer.lineFragmentPadding = 0
        textView.isScrollEnabled = true
        textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        context.coordinator.textView = textView
        context.coordinator.installPlaceholder(in: textView)
        bank?.register(context.coordinator)
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        context.coordinator.text = $text
        context.coordinator.bank = bank
        context.coordinator.placeholder = placeholder
        bank?.register(context.coordinator)
        textView.isEditable = isEnabled
        textView.isSelectable = isEnabled
        if textView.markedTextRange == nil, textView.text != text {
            textView.text = text
        }
        context.coordinator.refreshPlaceholder()
        if !isEnabled, textView.isFirstResponder {
            textView.resignFirstResponder()
        }
    }

    static func dismantleUIView(_ uiView: UITextView, coordinator: Coordinator) {
        coordinator.bank?.unregister(coordinator)
        if coordinator.textView === uiView {
            coordinator.textView = nil
        }
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: UITextView,
        context: Context
    ) -> CGSize? {
        let width = proposal.width ?? uiView.bounds.width
        guard width > 0 else {
            return CGSize(width: proposal.width ?? 0, height: minHeight)
        }
        let fitting = uiView.sizeThatFits(
            CGSize(width: width, height: .greatestFiniteMagnitude)
        )
        return CGSize(width: width, height: max(minHeight, ceil(fitting.height)))
    }

    final class Coordinator: NSObject, UITextViewDelegate, NovelIMEFieldHosting {
        var text: Binding<String>
        var bank: NovelIMEFieldBank?
        var placeholder: String
        weak var textView: UITextView?
        private let placeholderLabel = UILabel()

        init(text: Binding<String>, bank: NovelIMEFieldBank?, placeholder: String) {
            self.text = text
            self.bank = bank
            self.placeholder = placeholder
        }

        var hasMarkedText: Bool { textView?.markedTextRange != nil }

        func flushMarkedTextIntoBinding() {
            guard let textView else { return }
            textView.unmarkText()
            let value = textView.text ?? ""
            if text.wrappedValue != value {
                text.wrappedValue = value
            }
            refreshPlaceholder()
        }

        func installPlaceholder(in textView: UITextView) {
            placeholderLabel.font = textView.font
            placeholderLabel.textColor = .placeholderText
            placeholderLabel.numberOfLines = 0
            placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
            textView.addSubview(placeholderLabel)
            NSLayoutConstraint.activate([
                placeholderLabel.topAnchor.constraint(
                    equalTo: textView.topAnchor,
                    constant: textView.textContainerInset.top
                ),
                placeholderLabel.leadingAnchor.constraint(
                    equalTo: textView.leadingAnchor,
                    constant: textView.textContainerInset.left
                        + textView.textContainer.lineFragmentPadding
                ),
                placeholderLabel.trailingAnchor.constraint(
                    equalTo: textView.trailingAnchor,
                    constant: -(textView.textContainerInset.right
                        + textView.textContainer.lineFragmentPadding)
                ),
            ])
            refreshPlaceholder()
        }

        func refreshPlaceholder() {
            placeholderLabel.text = placeholder
            let isEmpty = (textView?.text ?? "").isEmpty
            placeholderLabel.isHidden = !isEmpty || placeholder.isEmpty
        }

        func textViewDidChange(_ textView: UITextView) {
            let value = textView.text ?? ""
            if text.wrappedValue != value {
                text.wrappedValue = value
            }
            refreshPlaceholder()
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            flushMarkedTextIntoBinding()
        }
    }
}

enum NovelWorkspaceSection: String, CaseIterable, Identifiable {
    case creation
    case manuscript
    case compendium

    var id: String { rawValue }

    var title: String {
        switch self {
        case .creation: "创作"
        case .manuscript: "正文"
        case .compendium: "设定"
        }
    }
}

enum NovelCompendiumSection: String, CaseIterable, Identifiable {
    case characters
    case world
    case story
    case more

    var id: String { rawValue }

    var title: String {
        switch self {
        case .characters: "角色"
        case .world: "世界观"
        case .story: "剧情"
        case .more: "更多"
        }
    }
}

extension NovelProjectCreationMode {
    var displayName: String {
        switch self {
        case .blank: "空白项目"
        case .quickStart: "快速开始"
        }
    }
}

extension NovelMaterialKind {
    var displayName: String {
        switch self {
        case .world: "世界观"
        case .character: "人物档案"
        case .relationship: "人物关系"
        case .masterOutline: "总剧情大纲"
        case .writingRequirements: "写作要求"
        case .decisionLog: "讨论决定"
        case .custom(let name): name.isEmpty ? "自定义" : name
        }
    }

    var systemImage: String {
        switch self {
        case .world: "globe.asia.australia"
        case .character: "person.text.rectangle"
        case .relationship: "person.line.dotted.person"
        case .masterOutline: "point.3.connected.trianglepath.dotted"
        case .writingRequirements: "text.badge.checkmark"
        case .decisionLog: "checklist"
        case .custom: "doc.text"
        }
    }
}

extension NovelInjectionMode {
    var displayName: String {
        switch self {
        case .always: "常驻"
        case .smart: "智能"
        case .off: "关闭"
        }
    }

    var systemImage: String {
        switch self {
        case .always: "pin.fill"
        case .smart: "sparkles"
        case .off: "eye.slash"
        }
    }
}

extension NovelGenerationGranularity {
    var displayName: String {
        switch self {
        case .continuation: "续写片段"
        case .wholeChapter: "生成整章"
        }
    }
}

extension NovelBranchSyncStatus {
    var displayName: String {
        switch self {
        case .synchronized: "已同步"
        case .needsSync: "资料待整理"
        }
    }
}

extension NovelCheckpointKind {
    var displayName: String {
        switch self {
        case .initial: "初始"
        case .collection: "正文收录"
        case .manualSync: "手动同步"
        case .discussionArchive: "讨论归档"
        case .identityClarification: "人物说明"
        case .polish: "整章润色"
        case .restore: "版本恢复"
        }
    }
}

extension NovelChapterVersionKind {
    var displayName: String {
        switch self {
        case .collected: "正文收录"
        case .manualEdit: "手动编辑"
        case .polish: "整章润色"
        case .restore: "版本恢复"
        }
    }
}

extension NovelInjectionSelectionReason {
    var displayName: String {
        switch self {
        case .requiredPrompt: "系统指令"
        case .requiredPolishPreference: "润色偏好"
        case .confirmedChapterPlan: "本章计划"
        case .recentWrittenHighlights: "近期已写要点"
        case .upcomingArc: "往后几章"
        case .requiredUserInput: "本次输入"
        case .requiredCurrentState: "当前分支状态"
        case .requiredQuickStartSeed: "快速开始信息"
        case .currentChapterTail: "当前章尾"
        case .previousChapterTail: "上一章尾"
        case .fullSourceChapter: "完整来源章节"
        case .archivedDiscussion: "归档讨论摘要"
        case .recentSession: "近期对话"
        case .branchEventHistory: "分支事件"
        case .branchOverride: "分支覆盖"
        case .always: "常驻资料"
        case .forceIncluded: "本次加入"
        case .smartMatch: "智能匹配"
        case .forceExcluded: "本次排除"
        case .disabled: "默认关闭"
        case .noSmartMatch: "未匹配"
        case .budgetTrimmed: "预算裁剪"
        }
    }
}

enum NovelPresentation {
    static func chapterDisplayTitle(
        storedTitle: String,
        content: String,
        ordinal: Int
    ) -> String {
        let stored = storedTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard stored.isEmpty || isGenericChapterTitle(stored) else { return stored }

        return chapterHeadingTitle(from: content) ?? (stored.isEmpty ? "第 \(ordinal) 章" : stored)
    }

    static func operationErrorMessage(_ error: Error) -> String {
        if let failure = error as? NovelStructuredModelExecutionFailure {
            return failureMessage(failure.failure)
        }
        // NovelFailure is a value type (not Error); transport throws NovelModelFailure.
        if let failure = error as? NovelModelFailure {
            return failureMessage(NovelFailure(
                code: failure.code,
                message: failure.message,
                isRetryable: failure.isRetryable
            ))
        }
        guard case .invalidInput(let detail) = error as? NovelError else {
            let text = (error as? LocalizedError)?.errorDescription
                ?? error.localizedDescription
            if looksLikeTechnicalFailureDump(text) {
                return "网络或模型连接中断，请重试。"
            }
            return text
        }
        if detail.contains("evidence outside the authoritative manuscript") {
            return "模型提取的事实依据与正文不一致，候选正文仍然保留，可以重新同步。"
        }
        if detail.contains("Unknown entity") ||
            detail.contains("newly unresolved entity") ||
            detail.contains("known project material") {
            return "模型提取的人物称谓没有和资料对齐，候选正文仍然保留，可以重新同步。"
        }
        if detail.contains("without evidence-backed facts") {
            return "模型更新了剧情摘要，但没有给出对应正文依据，候选正文仍然保留，可以重新同步。"
        }
        if detail.contains("pending novel operation changed") {
            return "同步期间项目内容发生了变化，请重新载入后再试。"
        }
        if detail.contains("no pending terminal state") {
            return "这次生成的重试状态已失效，请重新发送请求。"
        }
        if detail.contains("cannot be empty") {
            return "请求内容为空，请重新输入后再试。"
        }
        if detail.contains("input budget must be positive") {
            return "模型输入预算无效，请检查项目模型设置后重试。"
        }
        if detail.contains("already been answered") {
            return "这个问题已经回答过了。请直接发送新消息继续，或换个问法。"
        }
        if detail.contains("Ask User prompt no longer belongs") {
            return "原来的追问已失效，请直接发送新消息继续。"
        }
        if detail.contains("discussion run shape is invalid") {
            return "这次讨论请求格式无效，请直接重新发送。"
        }
        if detail.contains("while deleting the chapter") {
            return "删除章节时正文已变化，请重新载入后再试。"
        }
        if detail.contains("not in the working manuscript") {
            return "这一章已经不在当前正文目录里。"
        }
        if detail.contains("before deleting a chapter") {
            return "请先完成当前分支未完成的正文操作，再删除章节。"
        }
        // 代笔等链路直接抛中文 invalidInput：原样透传，不抹成「重新载入」。
        if detail.unicodeScalars.contains(where: { (0x4E00...0x9FFF).contains($0.value) }),
           !looksLikeTechnicalFailureDump(detail) {
            return detail
        }
        return "当前操作的内容或项目状态不匹配，请重新载入后再试。"
    }

    static func failureMessage(_ failure: NovelFailure) -> String {
        switch failure.code {
        case "cancelled", "polish_abandoned":
            return "生成已取消。"
        case "global_model_missing", "global_provider_missing":
            return "还没有可用的全局聊天模型，请先在设置里配好服务商和默认模型。"
        case "fixed_provider_missing", "fixed_model_missing":
            return "项目绑定的模型已失效（服务商或模型 ID 已变）。请在右上角「项目模型覆盖」重新选择，或改回跟随全局。"
        case "effective_provider_missing", "provider_disabled",
             "model_not_chat", "model_unavailable", "grok_isolation_missing",
             "grok_isolation_unavailable", "grok_provider_invalid":
            return "项目模型当前不可用，请在右上角“设置”的“项目模型覆盖”中重新选择。"
        case "invalid_quick_start_output":
            return "模型返回的创作建议格式不完整，请重新生成。"
        case "invalid_structured_output", "incomplete_polish_output", "invalid_polish_assessment":
            return "模型返回的结果格式不完整，请重新生成。"
        case "empty_completion":
            return "模型没有返回内容，请重新生成。"
        case "terminal_persist_failed":
            return "内容已经生成，但保存失败，请重试保存。"
        case "provider_stream_failed", "grok_web_stream_failed",
             "provider_background_disconnected", "provider_background_failed",
             "discussion_provider_failed":
            return networkOrUpstreamFailureMessage(
                failure.message,
                retainedPartial: failure.code != "discussion_provider_failed"
            )
        default:
            let message = failure.message.trimmingCharacters(in: .whitespacesAndNewlines)
            // NSURLError Domain=/UserInfo= dumps often include a short Chinese phrase
            // like「网络连接已中断」— never pass the raw dump to the bubble/banner.
            if looksLikeTechnicalFailureDump(message) || looksLikeTransportFailure(message) {
                return networkOrUpstreamFailureMessage(message, retainedPartial: false)
            }
            if message.unicodeScalars.contains(where: { (0x4E00...0x9FFF).contains($0.value) }),
               message.count <= 120 {
                return message
            }
            return failure.isRetryable
                ? "生成暂时失败，请稍后重试。"
                : "生成没有完成，请检查项目模型或输入后重试。"
        }
    }

    /// True for Foundation/NSURL stack dumps and similar non-user-facing diagnostics.
    static func looksLikeTechnicalFailureDump(_ message: String) -> Bool {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        if text.count > 180 { return true }
        return text.contains("Domain=")
            || text.contains("UserInfo=")
            || text.contains("NSURLError")
            || text.contains("NSError")
            || text.contains("kCFStreamError")
            || text.contains("_kCFStreamError")
            || text.contains("NSUnderlyingError")
            || text.contains("Exception in http request")
            || (text.contains("Code=") && text.contains("http"))
    }

    /// Connection-class failures that should be retried once at the transport edge.
    static func looksLikeTransportFailure(_ message: String) -> Bool {
        let text = message
        if text.contains("Code=-1005")
            || text.contains("Code=-1001")
            || text.contains("Code=-1009")
            || text.contains("Code=-1200")
            || text.contains("NSURLErrorDomain")
            || text.contains("网络连接已中断")
            || text.contains("请求超时")
            || text.contains("似乎已断开与互联网") {
            return true
        }
        return looksLikeTechnicalFailureDump(text)
    }

    static func networkOrUpstreamFailureMessage(
        _ raw: String,
        retainedPartial: Bool
    ) -> String {
        let text = raw
        if text.contains("Code=-1009") || text.contains("似乎已断开与互联网") {
            return "设备当前无网络，请恢复网络后重试。"
        }
        if text.contains("Code=-1001") || text.contains("请求超时") {
            return retainedPartial
                ? "模型请求超时，已保留当前回复，可以重试。"
                : "模型请求超时，请检查网络后重试。"
        }
        if text.contains("Code=-1200") || text.contains("SSL") {
            return "安全连接失败，请检查网络或代理后重试。"
        }
        if text.contains("Code=-1005") || text.contains("网络连接已中断") {
            return retainedPartial
                ? "网络连接中断，已保留当前回复，可以重试。若反复出现，请换一个创作模型。"
                : "网络连接中断，请重试。若反复出现，请到小说设置换一个创作模型。"
        }
        if retainedPartial {
            return "模型上游服务在生成过程中中断，已保留当前回复，可以重试。"
        }
        return "网络或模型连接中断，请重试。若反复出现，请到小说设置换一个创作模型。"
    }

    static func stateSyncFailureMessage(_ message: String) -> String {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return "剧情状态同步失败，请重试。"
        }
        // Already humanized short/medium copy from operationErrorMessage.
        if trimmed == "状态不符，请重试。" ||
            trimmed == "当前操作的内容或项目状态不匹配，请重新载入后再试。" {
            return "剧情同步失败：正文与检查点不一致（常见于删章后）。请点重试；仍失败再点「重新载入」。"
        }
        if trimmed == "The model returned malformed JSON." ||
            trimmed == "The model returned more than one JSON object." ||
            trimmed == "The model output must be one JSON object." ||
            trimmed.localizedCaseInsensitiveContains("missing required fields") {
            return "剧情同步模型返回的格式无法读取，请重试；若反复出现，请更换剧情同步模型。"
        }
        if trimmed == "The fact synchronization was cancelled and can be retried." ||
            trimmed == "剧情状态同步已取消，可以重试。" {
            return "剧情状态同步已取消，可以重试。"
        }
        // 结构化分类文案（stateSyncStructuredFailureMessage）原样透出：其中 4 条含
        // "JSON" 等 ASCII 术语，若落到下方的纯中文判定会被折叠回通用文案。
        if trimmed.hasPrefix("剧情同步模型") {
            return trimmed
        }
        if trimmed.localizedCaseInsensitiveContains("manual-edit suffix") ||
            trimmed.localizedCaseInsensitiveContains("manual synchronization suffix") {
            return "剧情同步失败：目录结构已变（如删过章节），正在按新规则处理。请再点重试。"
        }
        if trimmed.localizedCaseInsensitiveContains("no manual edits") {
            return "没有待同步的改写。"
        }
        if trimmed.localizedCaseInsensitiveContains("working manuscript changed") {
            return "同步前正文又变了，请点「重新载入」后再同步。"
        }
        // Only real stale-guard phrases — not every string containing "revision".
        if trimmed.localizedCaseInsensitiveContains("is stale") {
            return "项目版本已更新，请点「重新载入」后再同步。"
        }
        if trimmed.localizedCaseInsensitiveContains("No valid rebuild base") {
            return "找不到可用的剧情基线，请点「重新载入」后再同步。"
        }
        let containsChinese = trimmed.unicodeScalars.contains {
            (0x4E00...0x9FFF).contains($0.value)
        }
        let containsASCIILetter = trimmed.unicodeScalars.contains {
            (0x41...0x5A).contains($0.value) || (0x61...0x7A).contains($0.value)
        }
        // Pure Chinese timeout copy (not mixed dumps like "请求失败：upstream timeout").
        if containsChinese, !containsASCIILetter,
           trimmed.contains("超时") || trimmed.localizedCaseInsensitiveContains("timeout") {
            return "剧情同步超时，请重试；大项目可换更快的同步模型。"
        }
        if containsChinese, !containsASCIILetter {
            // Keep banner-friendly: one short sentence if possible.
            if trimmed.count <= 28 { return trimmed }
            if trimmed.hasPrefix("剧情") { return trimmed }
            return "剧情同步失败：\(trimmed)"
        }
        // English / mixed technical detail — short, no false "reopen project".
        return "剧情状态同步失败，请重试。"
    }

    /// When some durable chunks already landed, tell the user retry won't reburn them.
    static func stateSyncFailureMessage(
        _ message: String,
        completedChunkCount: Int
    ) -> String {
        let base = stateSyncFailureMessage(message)
        guard completedChunkCount > 0 else { return base }
        return "\(base) 已保存 \(completedChunkCount) 段进度，重试从第 \(completedChunkCount + 1) 段继续。"
    }

    /// 结构化输出失败分类 → banner 可直接展示的中文原因。
    /// 此前这类英文技术细节一律折叠成「剧情状态同步失败，请重试」，
    /// 模型的 schema/指令遵循问题（换快模后最常见）完全不可诊断。
    /// 文案以「剧情」开头，经 `stateSyncFailureMessage(_:)` 字符串路径原样透出。
    static func stateSyncStructuredFailureMessage(
        _ failure: NovelStructuredOutputFailure
    ) -> String {
        let retryHint = "若反复出现，请更换剧情同步模型。"
        switch failure.category {
        case .malformedJSON:
            return "剧情同步模型返回的 JSON 无法解析，输出可能被截断；\(retryHint)"
        case .expectedObject:
            return "剧情同步模型返回的不是 JSON 对象；\(retryHint)"
        case .missingField:
            return "剧情同步模型返回的 JSON 缺少必需字段；\(retryHint)"
        case .unknownField:
            return "剧情同步模型返回了契约之外的字段；\(retryHint)"
        case .duplicateKey:
            return "剧情同步模型返回的 JSON 存在重复字段；\(retryHint)"
        case .typeMismatch:
            return "剧情同步模型返回的字段类型不符合契约；\(retryHint)"
        case .unsupportedVersion:
            return "剧情同步模型返回了不支持的数据版本；\(retryHint)"
        case .invalidValue:
            return "剧情同步模型返回了不符合契约的取值；\(retryHint)"
        case .duplicateIdentifier:
            return "剧情同步模型返回了重复的标识符；\(retryHint)"
        case .invalidReference:
            return "剧情同步模型引用了不存在的条目；\(retryHint)"
        }
    }

    static func stateSyncFailureMessage(for error: Error) -> String {
        // 结构化输出失败优先按类别给出具体原因，不要落到通用文案。
        if let failure = error as? NovelStructuredModelExecutionFailure,
           let outputFailure = failure.structuredOutputFailure {
            return stateSyncStructuredFailureMessage(outputFailure)
        }
        // Prefer the raw invalidInput detail so sync-specific English can be mapped
        // before operationErrorMessage collapses unknowns to a generic line.
        if case .invalidInput(let detail) = error as? NovelError {
            let mapped = stateSyncFailureMessage(detail)
            if mapped != "剧情状态同步失败，请重试。" {
                return mapped
            }
        }
        return stateSyncFailureMessage(operationErrorMessage(error))
    }
}

/// 段内流式进度计数：模型流回调在任意线程写入，banner 每秒轮询读取。
/// 纯呈现层遥测，不进入项目文档、receipt 或任何 durable 状态。
final class NovelStateSyncStreamProgress: @unchecked Sendable {
    static let shared = NovelStateSyncStreamProgress()

    private let lock = NSLock()
    private var streamedCharacters: [NovelPendingOperationID: Int] = [:]

    func set(pendingID: NovelPendingOperationID, characters: Int) {
        lock.withLock { streamedCharacters[pendingID] = characters }
    }

    func count(pendingID: NovelPendingOperationID) -> Int {
        lock.withLock { streamedCharacters[pendingID] ?? 0 }
    }

    func clear(pendingID: NovelPendingOperationID) {
        lock.withLock { streamedCharacters.removeValue(forKey: pendingID) }
    }
}

/// Shared strip for manual plot-state sync: title + optional percent bar + live detail.
struct NovelStateSyncProgressBanner: View {
    let title: String
    let activity: NovelStateSyncActivity?
    var secondaryHint: String? = nil
    var canStop: Bool = false
    var onStop: (() -> Void)? = nil
    var usesBorderedStop: Bool = true

    var body: some View {
        if let activity {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let waitingSince = activity.requestStartedAt ?? activity.startedAt
                let elapsed = Int(max(0, context.date.timeIntervalSince(waitingSince)))
                let streamed = NovelStateSyncStreamProgress.shared.count(pendingID: activity.pendingID)
                let progress = activity.progressDetail(
                    elapsedSeconds: elapsed,
                    streamedCharacters: streamed
                )
                content(
                    title: title,
                    detail: progress,
                    hint: secondaryHint,
                    fraction: activity.displayedCompletionFraction,
                    percent: activity.displayedPercent
                )
            }
        } else {
            content(title: title, detail: secondaryHint, hint: nil, fraction: nil, percent: nil)
        }
    }

    @ViewBuilder
    private func content(
        title: String,
        detail: String?,
        hint: String?,
        fraction: Double?,
        percent: Int?
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                    .tint(AmberTheme.accentAmber)

                Text(title)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let percent {
                    Text("\(percent)%")
                        .font(.footnote.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AmberTheme.foreground2)
                        // 9%→10%、99%→100% 位宽变化不推挤停止按钮。
                        .frame(minWidth: 38, alignment: .trailing)
                }

                if canStop, let onStop {
                    Button("停止", action: onStop)
                        .font(.footnote.weight(.semibold))
                        .modifier(NovelStateSyncStopButtonStyle(usesBordered: usesBorderedStop))
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
            }

            if let fraction {
                ProgressView(value: fraction)
                    .tint(AmberTheme.accentAmber)
            }

            if let detail, !detail.isEmpty {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .monospacedDigit()
                    // 流式字数与失败原因让 detail 明显变长：限两行截断，
                    // 避免每秒轮询时折行边界变化造成 banner 高度抖动。
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            // hint（停止中/阻塞切换等可操作解释）独占一行，不被上方的两行截断吃掉。
            if let hint, !hint.isEmpty {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct NovelStateSyncStopButtonStyle: ViewModifier {
    let usesBordered: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if usesBordered {
            content.buttonStyle(.bordered).controlSize(.small)
        } else {
            content.buttonStyle(.plain)
        }
    }
}

// MARK: - NovelPresentation private helpers

extension NovelPresentation {
    private static func chapterHeadingTitle(from content: String) -> String? {
        guard var line = content
            .split(whereSeparator: { $0.isNewline })
            .map(String.init)
            .map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) })
            .first(where: { !$0.isEmpty }) else {
            return nil
        }

        let isMarkdownHeading = line.first == "#"
        if isMarkdownHeading {
            while line.first == "#" {
                line.removeFirst()
            }
            line = line.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        if let marker = line.firstIndex(of: "章") {
            let prefix = String(line[...marker])
            if isGenericChapterTitle(prefix) {
                let remainder = line[line.index(after: marker)...]
                    .drop(while: { $0.isWhitespace || "·•:：-—–_".contains($0) })
                let title = String(remainder).trimmingCharacters(in: .whitespacesAndNewlines)
                return title.isEmpty ? nil : title
            }
        }

        return isMarkdownHeading && !line.isEmpty ? line : nil
    }

    private static func isGenericChapterTitle(_ title: String) -> Bool {
        let compact = title.filter { !$0.isWhitespace }
        let lowercased = compact.lowercased()
        if lowercased.hasPrefix("chapter") {
            let number = lowercased.dropFirst("chapter".count)
            return !number.isEmpty && number.allSatisfy(\.isNumber)
        }

        guard compact.first == "第", compact.last == "章" else { return false }
        let number = compact.dropFirst().dropLast()
        let chineseNumerals = "零〇一二三四五六七八九十百千万两"
        return !number.isEmpty && number.allSatisfy {
            $0.isNumber || chineseNumerals.contains($0)
        }
    }

    static func currentRevision(
        for material: NovelMaterialRecord,
        in snapshot: NovelProjectSnapshot
    ) -> NovelMaterialRevisionRecord? {
        snapshot.materialRevisions.first { $0.id == material.currentRevisionID }
    }

    static func effectiveRevision(
        for material: NovelMaterialRecord,
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot?
    ) -> NovelMaterialRevisionRecord? {
        if let overrideID = branch?.branch.overrideRevisionIDs.first(where: { revisionID in
            project.materialRevisions.contains { revision in
                revision.id == revisionID && revision.materialID == material.id
            }
        }), let revision = project.materialRevisions.first(where: { $0.id == overrideID }) {
            return revision
        }
        return currentRevision(for: material, in: project)
    }

    static func effectiveAliases(
        for material: NovelMaterialRecord,
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot?
    ) -> [String] {
        guard let revision = effectiveRevision(
            for: material,
            project: project,
            branch: branch
        ) else { return material.aliases }
        return NovelMaterialResolver.effectiveAliases(
            for: material,
            effectiveRevision: revision,
            materialRevisions: project.materialRevisions,
            proposals: project.settingProposals,
            appliedOperations: project.appliedOperations
        )
    }

    static func checkpointLineage(
        for branch: NovelBranchRecord,
        in snapshot: NovelProjectSnapshot
    ) -> [NovelBranchCheckpointRecord] {
        let byID = Dictionary(uniqueKeysWithValues: snapshot.checkpoints.map { ($0.id, $0) })
        var lineage: [NovelBranchCheckpointRecord] = []
        var visited: Set<NovelCheckpointID> = []
        var nextID: NovelCheckpointID? = branch.headCheckpointID
        while let checkpointID = nextID,
              visited.insert(checkpointID).inserted,
              let checkpoint = byID[checkpointID] {
            lineage.append(checkpoint)
            nextID = checkpoint.parentCheckpointID
        }
        return lineage
    }

    static func actionCheckpointLineage(
        for branch: NovelBranchRecord,
        in snapshot: NovelProjectSnapshot
    ) -> [NovelBranchCheckpointRecord] {
        guard let boundaryID = branch.forkOrigin?.checkpointID ?? snapshot.checkpoints.first(where: {
            $0.kind == .initial
        })?.id else { return [] }
        let lineage = checkpointLineage(for: branch, in: snapshot)
        guard let boundaryIndex = lineage.firstIndex(where: { $0.id == boundaryID }) else {
            return []
        }
        return Array(lineage[...boundaryIndex])
    }

    static func forkableCheckpoints(
        for branch: NovelBranchRecord,
        in snapshot: NovelProjectSnapshot
    ) -> [NovelBranchCheckpointRecord] {
        actionCheckpointLineage(for: branch, in: snapshot).filter { $0.kind != .initial }
    }

    static func canDirectlyRestore(
        _ target: NovelChapterVersionRecord,
        from current: NovelChapterVersionRecord
    ) -> Bool {
        target.id != current.id &&
            target.chapterID == current.chapterID &&
            target.factCompatibilityID == current.factCompatibilityID
    }

    @MainActor
    static func providerID(
        forModelID modelID: String,
        sharedSettings: IOSSharedSettingsStore
    ) -> String? {
        sharedSettings.snapshot.providers.first { provider in
            provider.models.contains { $0.id.description() == modelID }
        }?.id.description()
    }

    @MainActor
    static func modelDisplayName(
        for policy: NovelProjectModelPolicy,
        sharedSettings: IOSSharedSettingsStore
    ) -> String {
        switch policy {
        case .global:
            guard let model = sharedSettings.snapshot.getCurrentChatModel(),
                  let provider = model.findProvider(
                    providers: sharedSettings.snapshot.providers,
                    checkOverwrite: true
                  ),
                  provider.enabled else {
                return "全局模型不可用"
            }
            let name = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? model.modelId : name
        case .fixed(let providerID, let modelID):
            guard let provider = sharedSettings.snapshot.providers.first(where: {
                $0.id.description() == providerID
            }),
            provider.enabled,
            let model = provider.models.first(where: {
                $0.id.description() == modelID
            }) else {
                return "固定模型不可用"
            }
            let name = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? model.modelId : name
        }
    }

    @MainActor
    static func selectedModelID(
        for policy: NovelProjectModelPolicy,
        sharedSettings: IOSSharedSettingsStore
    ) -> String {
        switch policy {
        case .global:
            return sharedSettings.snapshot.getCurrentChatModel()?.id.description() ?? ""
        case .fixed(_, let modelID):
            return modelID
        }
    }

    static func fileName(_ value: String, fallback: String) -> String {
        let invalid = CharacterSet(charactersIn: "/:\\?%*|\"<>")
        let cleaned = value
            .components(separatedBy: invalid)
            .joined(separator: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? fallback : cleaned
    }
}
