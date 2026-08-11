import Foundation

/// Per-discussion-run context for the novel project write tools. Built by the
/// adapter for discussion runs only and threaded through
/// `NovelLiveTransportRequest`, so the tool-engine factory can construct
/// executors that reach the owning project document without global state.
struct NovelProjectToolRunContext: Sendable, Equatable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

/// Executes the six novel project field-write tools inside the discussion
/// agent loop. Every tool decodes JSON arguments, validates them, translates
/// them into an existing `NovelAction`, and runs it through
/// `DefaultNovelCreation.perform` — the single reducer transaction path. There
/// is no parallel write channel, and nothing here bypasses the domain layer.
///
/// Guard: `novel_revise_material` and `novel_propose_chapter_plan` refuse to
/// run while the project has a running generation or an active ghostwrite
/// pipeline (aligned with the UI's contract-editing disable rules).
final class IOSNovelProjectToolExecutor: IOSToolExecutor {
    static let supportedToolNames: [String] = [
        "novel_rename_project",
        "novel_set_polish_preference",
        "novel_upsert_upcoming_arc",
        "novel_clear_upcoming_arc",
        "novel_revise_material",
        "novel_propose_chapter_plan",
    ]

    private weak var creation: DefaultNovelCreation?
    private let projectID: NovelProjectID
    private let branchID: NovelBranchID

    init(projectContext: NovelProjectToolRunContext, creation: DefaultNovelCreation?) {
        self.projectID = projectContext.projectID
        self.branchID = projectContext.branchID
        self.creation = creation
    }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        switch name {
        case "novel_rename_project":
            return await renameProject(arguments)
        case "novel_set_polish_preference":
            return await setPolishPreference(arguments)
        case "novel_upsert_upcoming_arc":
            return await upsertUpcomingArc(arguments)
        case "novel_clear_upcoming_arc":
            return await clearUpcomingArc()
        case "novel_revise_material":
            return await reviseMaterial(arguments)
        case "novel_propose_chapter_plan":
            return await proposeChapterPlan(arguments)
        default:
            return .failed("未知的小说项目工具：\(name)。")
        }
    }

    // MARK: - Tools

    private func renameProject(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: RenameProjectArguments = decode(arguments) else {
            return .failed("novel_rename_project 参数无效：需要 title（新的项目名）。")
        }
        let title = args.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else {
            return .failed("novel_rename_project 的 title 不能为空。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法改名。")
        }
        let oldName = snapshot.project.name
        let command = NovelRenameProjectCommand(
            context: mutationContext(projectRevision: snapshot.project.revision),
            projectID: projectID,
            name: title
        )
        if let failure = await perform(.renameProject(command)) {
            return .failed("项目改名失败：\(failure)")
        }
        return .filled("已重命名项目：「\(oldName)」→「\(title)」。")
    }

    private func setPolishPreference(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: SetPolishPreferenceArguments = decode(arguments) else {
            return .failed("novel_set_polish_preference 参数无效：需要 preference（空串表示清除）。")
        }
        let preference = args.preference.trimmingCharacters(in: .whitespacesAndNewlines)
        guard preference.count <= 8_000 else {
            return .failed("novel_set_polish_preference 的 preference 过长（最多 8000 字）。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法更新润色偏好。")
        }
        let oldPreference = snapshot.project.polishPreference
        let command = NovelSetPolishPreferenceCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: projectID,
            preference: preference
        )
        if let failure = await perform(.setPolishPreference(command)) {
            return .failed("润色偏好保存失败：\(failure)")
        }
        if preference.isEmpty {
            return .filled("已清除润色偏好（原「\(oldPreference)」）。")
        }
        return .filled("已更新润色偏好：「\(oldPreference)」→「\(preference)」。")
    }

    private func upsertUpcomingArc(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: UpsertUpcomingArcArguments = decode(arguments) else {
            return .failed("novel_upsert_upcoming_arc 参数无效：需要 beats（字符串数组）。")
        }
        let beats = args.beats.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard !beats.isEmpty, beats.allSatisfy({ !$0.isEmpty }) else {
            return .failed("novel_upsert_upcoming_arc 的 beats 至少需要一条非空备注。")
        }
        guard beats.count <= NovelUpcomingArcRecord.maxBeats else {
            return .failed(
                "novel_upsert_upcoming_arc 最多 \(NovelUpcomingArcRecord.maxBeats) 条节拍（当前 \(beats.count) 条）。"
            )
        }
        guard beats.allSatisfy({ $0.count <= NovelUpcomingArcRecord.maxBeatCharacterCount }) else {
            return .failed(
                "novel_upsert_upcoming_arc 每条节拍最多 \(NovelUpcomingArcRecord.maxBeatCharacterCount) 字。"
            )
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法保存「往后几章」。")
        }
        let previousBeatCount = snapshot.upcomingArc(for: branchID)?.beats.count ?? 0
        let command = NovelUpsertUpcomingArcCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: projectID,
            branchID: branchID,
            beats: beats
        )
        if let failure = await perform(.upsertUpcomingArc(command)) {
            return .failed("「往后几章」保存失败：\(failure)")
        }
        return .filled("已更新「往后几章」：\(beats.count) 条节拍（原 \(previousBeatCount) 条）。")
    }

    private func clearUpcomingArc() async -> IOSAgentToolOutcome {
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法清除「往后几章」。")
        }
        guard let previous = snapshot.upcomingArc(for: branchID) else {
            return .failed("当前分支没有「往后几章」备注可清除。")
        }
        let command = NovelClearUpcomingArcCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: projectID,
            branchID: branchID
        )
        if let failure = await perform(.clearUpcomingArc(command)) {
            return .failed("「往后几章」清除失败：\(failure)")
        }
        return .filled("已清除「往后几章」（原 \(previous.beats.count) 条）。")
    }

    private func reviseMaterial(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: ReviseMaterialArguments = decode(arguments) else {
            return .failed("novel_revise_material 参数无效：需要 kind、title、content（material_id 可选）。")
        }
        guard var kind = materialKind(from: args.kind) else {
            return .failed(
                "novel_revise_material 的 kind 非法：\(args.kind)。"
                    + " 可选：world / character / relationship / masterOutline / writingRequirements / custom。"
            )
        }
        let title = args.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let content = args.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else {
            return .failed("novel_revise_material 的 title 不能为空。")
        }
        guard !content.isEmpty else {
            return .failed("novel_revise_material 的 content 不能为空。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法修改资料。")
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failed(reason)
        }

        let materialID: NovelMaterialID
        let oldTitle: String?
        if let rawID = args.material_id?.trimmingCharacters(in: .whitespacesAndNewlines),
           !rawID.isEmpty {
            guard let uuid = UUID(uuidString: rawID) else {
                return .failed("novel_revise_material 的 material_id 不是合法的资料 ID：\(rawID)。")
            }
            let id = NovelMaterialID(uuid)
            guard let material = snapshot.materials.first(where: { $0.id == id }),
                  !material.isDeleted else {
                return .failed("找不到指定资料（material_id=\(rawID)），无法更新。")
            }
            // 自定义卡的显示名是关联值（.custom("魔法体系")），schema 只能传 "custom"——
            // 两张 custom 卡不按关联值判等，且更新时保留既有显示名。
            if case .custom = material.kind, case .custom = kind {
                kind = material.kind
            } else {
                guard material.kind == kind else {
                    return .failed("资料类型与指定的 kind 不一致，无法更新。")
                }
            }
            materialID = id
            oldTitle = snapshot.materialRevisions.first(where: {
                $0.id == material.currentRevisionID
            })?.title
        } else {
            materialID = NovelMaterialID()
            oldTitle = nil
            // 新建 custom 卡可用 custom_name 命名（缺省「自定义」），否则多张卡并列无法区分。
            if case .custom = kind,
               let customName = args.custom_name?.trimmingCharacters(in: .whitespacesAndNewlines),
               !customName.isEmpty {
                kind = .custom(customName)
            }
        }

        let command = NovelReviseMaterialCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: projectID,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: kind,
            title: title,
            content: content,
            tags: [],
            injectionMode: .smart,
            aliases: args.aliases ?? []
        )
        if let failure = await perform(.reviseMaterial(command)) {
            return .failed("资料保存失败：\(failure)")
        }
        if let oldTitle {
            return .filled("已更新资料「\(oldTitle)」→「\(title)」（\(args.kind)）。")
        }
        return .filled("已新建资料「\(title)」（\(args.kind)）。")
    }

    private func proposeChapterPlan(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: ProposeChapterPlanArguments = decode(arguments) else {
            return .failed(
                "novel_propose_chapter_plan 参数无效：需要 outline_placement、goal_and_conflict、"
                    + "must_happen、must_not_happen、ending_hook、visible_facts。"
            )
        }
        let goalAndConflict = args.goal_and_conflict.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !goalAndConflict.isEmpty else {
            return .failed("novel_propose_chapter_plan 的 goal_and_conflict 不能为空。")
        }
        guard args.outline_placement.count <= 500 else {
            return .failed("novel_propose_chapter_plan 的 outline_placement 过长（最多 500 字）。")
        }
        guard goalAndConflict.count <= 8_000 else {
            return .failed("novel_propose_chapter_plan 的 goal_and_conflict 过长（最多 8000 字）。")
        }
        guard args.ending_hook.count <= 4_000 else {
            return .failed("novel_propose_chapter_plan 的 ending_hook 过长（最多 4000 字）。")
        }
        guard args.must_happen.count <= 32,
              args.must_not_happen.count <= 32,
              args.visible_facts.count <= 32 else {
            return .failed("novel_propose_chapter_plan 的 must_happen / must_not_happen / visible_facts 每项最多 32 条。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法保存本章计划。")
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failed(reason)
        }

        let existingPlan = snapshot.chapterPlan(for: branchID)
        // 已确认合同不得被讨论工具静默降级为草稿（reducer 只按 planID 判撞，不拦
        // 状态回退）；用户需在面板清除或编辑确认合同。
        if existingPlan?.status == .confirmed {
            return .failed(
                "当前分支已有确认的本章合同，讨论中不能把它改回草稿；请先在「项目控制」面板清除或修改合同。"
            )
        }
        let planID = existingPlan?.id ?? NovelChapterPlanID()
        let command = NovelUpsertChapterPlanCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: projectID,
            branchID: branchID,
            planID: planID,
            status: .draft,
            outlinePlacement: args.outline_placement,
            goalAndConflict: args.goal_and_conflict,
            mustHappen: args.must_happen,
            mustNotHappen: args.must_not_happen,
            endingHook: args.ending_hook,
            visibleFacts: args.visible_facts
        )
        if let failure = await perform(.upsertChapterPlan(command)) {
            return .failed("本章计划保存失败：\(failure)")
        }
        let placement = args.outline_placement.trimmingCharacters(in: .whitespacesAndNewlines)
        let placementText = placement.isEmpty ? "未填定位" : placement
        if existingPlan != nil {
            return .filled(
                "已更新本章计划草稿（\(placementText)）。"
                    + " 草稿需在「项目控制」面板人工确认后才可用于代笔。"
            )
        }
        return .filled(
            "已保存本章计划草稿（\(placementText)）。草稿需在「项目控制」面板人工确认后才可用于代笔。"
        )
    }

    // MARK: - Helpers

    /// Returns nil on success; a human-readable failure reason otherwise.
    private func perform(_ action: NovelAction) async -> String? {
        guard let creation else {
            return "小说创作服务当前不可用。"
        }
        do {
            _ = try await creation.perform(action)
            return nil
        } catch let error as NovelError {
            return error.localizedDescription
        } catch {
            return String(describing: error)
        }
    }

    private func loadSnapshot() async -> NovelProjectSnapshot? {
        guard let creation else { return nil }
        guard case .project(let snapshot) = try? await creation.snapshot(.project(projectID)) else {
            return nil
        }
        return snapshot
    }

    /// 代笔运行中禁止改写合同/资料——对齐 UI 禁用合同编辑的既有判定
    /// （NovelSessionViewModel.isGhostwriting 相位集）。不查 activeRuns：调用方
    /// 本身就是一条进行中的 discussion run，查 activeRuns 会无条件自锁。
    private func ghostwriteBlockReason(snapshot: NovelProjectSnapshot) async -> String? {
        guard let creation else { return nil }
        let progress = try? await creation.loadGhostwriteBatchProgress(
            projectID: projectID,
            branchID: branchID
        )
        if let phase = progress?.phase,
           phase == .writing || phase == .accepting || phase == .collecting ||
           phase == .syncing || phase == .planning || phase == .revising {
            return "代笔正在推进本章（阶段：\(phase.rawValue)），本章计划与设定资料暂不可修改；请先暂停代笔。"
        }
        return nil
    }

    private func mutationContext(
        projectRevision: Int64? = nil,
        configRevision: Int64? = nil
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: projectRevision,
            expectedConfigRevision: configRevision,
            expectedBranchHeadRevision: nil
        )
    }

    /// 白名单与 KMP schema/prompt v7 一致。decisionLog 刻意不开放：UI 四个设定
    /// tab 均不展示该类卡、编辑器也无法保存（agent 写了会"失踪"），留作 pipeline 专用。
    private func materialKind(from raw: String) -> NovelMaterialKind? {
        switch raw {
        case "world": .world
        case "character": .character
        case "relationship": .relationship
        case "masterOutline": .masterOutline
        case "writingRequirements": .writingRequirements
        case "custom": .custom("自定义")
        default: nil
        }
    }

    private func decode<T: Decodable>(_ arguments: String) -> T? {
        guard let data = arguments.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}

// MARK: - Tool argument shapes (snake_case matches the KMP JSON schemas)

private struct RenameProjectArguments: Decodable {
    let title: String
    let reason: String?
}

private struct SetPolishPreferenceArguments: Decodable {
    let preference: String
}

private struct UpsertUpcomingArcArguments: Decodable {
    let beats: [String]
}

private struct ReviseMaterialArguments: Decodable {
    let material_id: String?
    let kind: String
    let title: String
    let content: String
    let aliases: [String]?
    let custom_name: String?
}

private struct ProposeChapterPlanArguments: Decodable {
    let outline_placement: String
    let goal_and_conflict: String
    let must_happen: [String]
    let must_not_happen: [String]
    let ending_hook: String
    let visible_facts: [String]
}
