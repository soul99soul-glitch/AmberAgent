import Foundation

enum NovelWorkspaceSection: String, CaseIterable, Identifiable {
    case creation
    case compendium

    var id: String { rawValue }

    var title: String {
        switch self {
        case .creation: "创作"
        case .compendium: "资料"
        }
    }
}

enum NovelCompendiumSection: String, CaseIterable, Identifiable {
    case chapters
    case characters
    case world
    case story
    case more

    var id: String { rawValue }

    var title: String {
        switch self {
        case .chapters: "正文"
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
        case .masterOutline: "总剧情大纲"
        case .writingRequirements: "写作要求"
        case .custom(let name): name.isEmpty ? "自定义" : name
        }
    }

    var systemImage: String {
        switch self {
        case .world: "globe.asia.australia"
        case .character: "person.text.rectangle"
        case .masterOutline: "point.3.connected.trianglepath.dotted"
        case .writingRequirements: "text.badge.checkmark"
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
        case .needsSync: "待同步"
        }
    }
}

extension NovelCheckpointKind {
    var displayName: String {
        switch self {
        case .initial: "初始"
        case .collection: "正文收录"
        case .manualSync: "手动同步"
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
        case .requiredUserInput: "本次输入"
        case .requiredCurrentState: "当前分支状态"
        case .requiredQuickStartSeed: "快速开始信息"
        case .currentChapterTail: "当前章尾"
        case .previousChapterTail: "上一章尾"
        case .fullSourceChapter: "完整来源章节"
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
    static func failureMessage(_ failure: NovelFailure) -> String {
        switch failure.code {
        case "cancelled", "polish_abandoned":
            return "生成已取消。"
        case "global_model_missing", "global_provider_missing", "fixed_provider_missing",
             "fixed_model_missing", "effective_provider_missing", "provider_disabled",
             "model_not_chat", "model_unavailable", "grok_isolation_missing",
             "grok_isolation_unavailable", "grok_provider_invalid":
            return "项目模型当前不可用，请在“资料 > 更多”中检查模型设置。"
        case "invalid_quick_start_output":
            return "模型返回的创作建议格式不完整，请重新生成。"
        case "invalid_structured_output", "incomplete_polish_output", "invalid_polish_assessment":
            return "模型返回的结果格式不完整，请重新生成。"
        case "empty_completion":
            return "模型没有返回内容，请重新生成。"
        case "terminal_persist_failed":
            return "内容已经生成，但保存失败，请重试保存。"
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
            return "跟随全局"
        case .fixed(_, let modelID):
            for provider in sharedSettings.snapshot.providers {
                if let model = provider.models.first(where: { $0.id.description() == modelID }) {
                    let name = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                    return name.isEmpty ? model.modelId : name
                }
            }
            return "固定模型不可用"
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
