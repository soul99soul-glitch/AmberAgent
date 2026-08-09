import Foundation
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
    static func perform(
        firstResponder: UIView? = nil,
        _ action: @escaping @MainActor () -> Void
    ) {
        commitMarkedText(in: firstResponder)
        // SwiftUI TextField/TextEditor often apply UIKit text → Binding one
        // main turn after `unmarkText`. A second turn covers cases where the
        // first only runs resign-side bookkeeping.
        DispatchQueue.main.async {
            DispatchQueue.main.async {
                action()
            }
        }
    }

    /// Whether the active text input still has an in-progress IME composition.
    static func hasMarkedText(firstResponder: UIView? = nil) -> Bool {
        let responder = firstResponder ?? activeFirstResponder()
        guard let input = responder as? UITextInput else { return false }
        return input.markedTextRange != nil
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

    private static func activeFirstResponder() -> UIView? {
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
        guard case .invalidInput(let detail) = error as? NovelError else {
            return (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
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
        return "当前操作的内容或项目状态不匹配，请重新载入后再试。"
    }

    static func failureMessage(_ failure: NovelFailure) -> String {
        switch failure.code {
        case "cancelled", "polish_abandoned":
            return "生成已取消。"
        case "global_model_missing", "global_provider_missing", "fixed_provider_missing",
             "fixed_model_missing", "effective_provider_missing", "provider_disabled",
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
        case "provider_stream_failed", "grok_web_stream_failed":
            return "模型上游服务在生成过程中中断，已保留当前回复，可以重试。"
        default:
            let message = failure.message.trimmingCharacters(in: .whitespacesAndNewlines)
            if message.unicodeScalars.contains(where: { (0x4E00...0x9FFF).contains($0.value) }) {
                return message
            }
            return failure.isRetryable
                ? "生成暂时失败，请稍后重试。"
                : "生成没有完成，请检查项目模型或输入后重试。"
        }
    }

    static func stateSyncFailureMessage(_ message: String) -> String {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed == "The model returned malformed JSON." {
            return "剧情同步模型返回的格式无法读取，请重试；若反复出现，请更换剧情同步模型。"
        }
        if trimmed == "The fact synchronization was cancelled and can be retried." {
            return "剧情状态同步已取消，可以重试。"
        }
        if trimmed.isEmpty {
            return "剧情状态同步失败，请重试。"
        }
        let containsChinese = trimmed.unicodeScalars.contains {
            (0x4E00...0x9FFF).contains($0.value)
        }
        let containsASCIILetter = trimmed.unicodeScalars.contains {
            (0x41...0x5A).contains($0.value) || (0x61...0x7A).contains($0.value)
        }
        if containsChinese, !containsASCIILetter {
            return trimmed
        }
        return "剧情状态同步失败，请重试。"
    }

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
