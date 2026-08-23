import Foundation

/// Per-discussion-run context for the novel project write tools. Built by the
/// adapter for discussion runs only and threaded through
/// `NovelLiveTransportRequest`, so the tool-engine factory can construct
/// executors that reach the owning project document without global state.
struct NovelProjectToolRunContext: Sendable, Equatable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

struct NovelProjectToolIssue: Error, Equatable {
    let message: String
    init(_ message: String) { self.message = message }
}

/// Executes the novel project field-write tools inside the discussion agent loop.
/// Every tool decodes JSON arguments, validates them, translates them into an
/// existing `NovelAction`, and runs it through `DefaultNovelCreation.perform` —
/// the single reducer transaction path. There is no parallel write channel, and
/// nothing here bypasses the domain layer.
///
/// Guard: material/plan/chapter-title tools refuse while ghostwrite is advancing
/// (aligned with the UI's contract-editing disable rules).
final class IOSNovelProjectToolExecutor: IOSToolExecutor {
    static let supportedToolNames: [String] = [
        "novel_rename_project",
        "novel_set_polish_preference",
        "novel_upsert_upcoming_arc",
        "novel_clear_upcoming_arc",
        "novel_revise_material",
        "novel_propose_chapter_plan",
        "novel_set_chapter_title",
        "novel_list_chapters",
        "novel_read_chapter",
        "novel_revise_chapter",
        "novel_revert_recent_chapters",
        "novel_list_setting_proposals",
        "novel_reject_setting_proposals",
        "novel_workspace_list",
        "novel_workspace_read",
        "novel_workspace_grep",
        "novel_workspace_status",
        "novel_workspace_write",
    ]

    static let readOutputCharacterLimit = 24_000
    static let reviseNewTextCharacterLimit = 32_000

    weak var creation: DefaultNovelCreation?
    let projectID: NovelProjectID
    let branchID: NovelBranchID

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
        case "novel_set_chapter_title":
            return await setChapterTitle(arguments)
        case "novel_list_chapters":
            return await listChapters()
        case "novel_read_chapter":
            return await readChapter(arguments)
        case "novel_revise_chapter":
            return await reviseChapter(arguments, isUserInitiated: isUserInitiated)
        case "novel_revert_recent_chapters":
            return await revertRecentChapters(arguments, isUserInitiated: isUserInitiated)
        case "novel_list_setting_proposals":
            return await listSettingProposals()
        case "novel_reject_setting_proposals":
            return await rejectSettingProposals(arguments)
        case "novel_workspace_list":
            return await workspaceList(arguments)
        case "novel_workspace_read":
            return await workspaceRead(arguments)
        case "novel_workspace_grep":
            return await workspaceGrep(arguments)
        case "novel_workspace_status":
            return await workspaceStatus()
        case "novel_workspace_write":
            return await workspaceWrite(arguments, isUserInitiated: isUserInitiated)
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

    private func setChapterTitle(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: SetChapterTitleArguments = decode(arguments) else {
            return .failed(
                "novel_set_chapter_title 参数无效：需要 title（可选 chapter_ordinal / chapter_id）。"
            )
        }
        let title = args.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else {
            return .failed("novel_set_chapter_title 的 title 不能为空。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法修改章节标题。")
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failed(reason)
        }
        let chapter: WorkingChapter
        switch resolveWorkingChapter(
            snapshot: snapshot,
            chapterID: args.chapter_id,
            chapterOrdinal: args.chapter_ordinal,
            toolName: "novel_set_chapter_title"
        ) {
        case .failure(let issue):
            return .failed(issue.message)
        case .success(let resolved):
            chapter = resolved
        }
        let oldTitle = chapter.version.title
        guard oldTitle != title else {
            return .failed("章节标题已是「\(title)」，无需修改。")
        }
        guard let branch = snapshot.branches.first(where: { $0.id == branchID }) else {
            return .failed("当前分支不可用，无法修改章节标题。")
        }

        let command = NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: snapshot.project.revision,
                expectedConfigRevision: snapshot.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: projectID,
            branchID: branchID,
            chapterID: chapter.chapterID,
            versionID: NovelChapterVersionID(),
            title: title,
            content: chapter.version.content,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )
        if let failure = await perform(.saveManualEdit(command)) {
            return .failed("章节标题保存失败：\(failure)")
        }
        return .filled(
            "已将第 \(chapter.ordinal) 章标题「\(oldTitle)」→「\(title)」。"
                + " 正文未改。"
        )
    }

    private func listChapters() async -> IOSAgentToolOutcome {
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法列出章节。")
        }
        let chapters = workingChapters(in: snapshot)
        guard !chapters.isEmpty else {
            return .filled("当前分支还没有收录正文。")
        }
        let lines = chapters.map { chapter in
            let paragraphs = NovelParagraphParser.paragraphs(in: chapter.version.content)
            return "\(chapter.ordinal). 《\(chapter.version.title)》 \(chapter.version.content.count) 字 / \(paragraphs.count) 段 (id: \(chapter.chapterID))"
        }
        return .filled("工作章节 \(chapters.count) 章：\n" + lines.joined(separator: "\n"))
    }

    private func readChapter(_ arguments: String) async -> IOSAgentToolOutcome {
        let args: ReadChapterArguments = decode(arguments) ?? ReadChapterArguments(
            chapter_ordinal: nil,
            chapter_id: nil,
            start_paragraph: nil,
            end_paragraph: nil
        )
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法读取正文。")
        }
        let chapter: WorkingChapter
        switch resolveWorkingChapter(
            snapshot: snapshot,
            chapterID: args.chapter_id,
            chapterOrdinal: args.chapter_ordinal,
            toolName: "novel_read_chapter"
        ) {
        case .failure(let issue):
            return .failed(issue.message)
        case .success(let resolved):
            chapter = resolved
        }
        let excerpt: (total: Int, text: String)
        do {
            excerpt = try NovelParagraphParser.numberedExcerpt(
                in: chapter.version.content,
                start: args.start_paragraph,
                end: args.end_paragraph
            )
        } catch {
            return .failed("novel_read_chapter 无法读取段落：\(error.localizedDescription)")
        }
        let header = "第 \(chapter.ordinal) 章 《\(chapter.version.title)》(id: \(chapter.chapterID)) 共 \(excerpt.total) 段 / \(chapter.version.content.count) 字"
        var body = header + "\n---\n" + excerpt.text
        if body.count > Self.readOutputCharacterLimit {
            let prefix = String(body.prefix(Self.readOutputCharacterLimit))
            body = prefix
                + "\n\n… 正文已截断。请用 start_paragraph / end_paragraph 分段读取。"
        }
        return .filled(body)
    }

    func reviseChapter(
        _ arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        switch await revisionApprovalPrompt(from: arguments) {
        case .failure(let issue):
            return .failed(issue.message)
        case .success(let prompt):
            if isUserInitiated {
                return await applyRevision(prompt.chapterRevision)
            }
            return .needsApproval("等待作者确认改正文")
        }
    }

    func revisionApprovalPrompt(from arguments: String) async -> Result<NovelAskUserPrompt, NovelProjectToolIssue> {
        guard let args: ReviseChapterArguments = decode(arguments) else {
            return .failure(.init(
                "novel_revise_chapter 参数无效：需要 start_paragraph、end_paragraph、new_text。"
            ))
        }
        let newText = args.new_text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !newText.isEmpty else {
            return .failure(.init("novel_revise_chapter 的 new_text 不能为空。"))
        }
        guard newText.count <= Self.reviseNewTextCharacterLimit else {
            return .failure(.init(
                "novel_revise_chapter 的 new_text 过长（最多 \(Self.reviseNewTextCharacterLimit) 字）。"
            ))
        }
        guard args.start_paragraph >= 1, args.end_paragraph >= args.start_paragraph else {
            return .failure(.init("novel_revise_chapter 的段落区间无效。"))
        }
        guard let snapshot = await loadSnapshot() else {
            return .failure(.init("当前小说项目不可用，无法改正文。"))
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failure(.init(reason))
        }
        let chapter: WorkingChapter
        switch resolveWorkingChapter(
            snapshot: snapshot,
            chapterID: args.chapter_id,
            chapterOrdinal: args.chapter_ordinal,
            toolName: "novel_revise_chapter"
        ) {
        case .failure(let issue):
            return .failure(issue)
        case .success(let resolved):
            chapter = resolved
        }
        let replaced: (oldText: String, newContent: String)
        do {
            replaced = try NovelParagraphParser.replacingParagraphs(
                in: chapter.version.content,
                start: args.start_paragraph,
                end: args.end_paragraph,
                with: newText
            )
        } catch {
            return .failure(.init("novel_revise_chapter 无法替换段落：\(error.localizedDescription)"))
        }
        let reason = args.reason?.trimmingCharacters(in: .whitespacesAndNewlines)
        let proposal = NovelChapterRevisionProposal(
            chapterID: chapter.chapterID,
            chapterOrdinal: chapter.ordinal,
            chapterTitle: chapter.version.title,
            startParagraph: args.start_paragraph,
            endParagraph: args.end_paragraph,
            oldText: replaced.oldText,
            newText: newText,
            reason: reason?.isEmpty == true ? nil : reason
        )
        let rangeLabel = args.start_paragraph == args.end_paragraph
            ? "第 \(args.start_paragraph) 段"
            : "第 \(args.start_paragraph)–\(args.end_paragraph) 段"
        let prompt = NovelAskUserPrompt(
            question: "将第 \(chapter.ordinal) 章《\(chapter.version.title)》\(rangeLabel)写入正文？",
            options: NovelChapterRevisionApproval.options,
            chapterRevision: proposal
        )
        return .success(prompt)
    }

    private func revertRecentChapters(
        _ arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        switch await revertApprovalPrompt(from: arguments) {
        case .failure(let issue):
            return .failed(issue.message)
        case .success(let prompt):
            if isUserInitiated {
                return await applyRevert(prompt.manuscriptRevert)
            }
            return .needsApproval("等待作者确认回退章节")
        }
    }

    private func listSettingProposals() async -> IOSAgentToolOutcome {
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法列出设定建议。")
        }
        let proposals = snapshot.activeSettingProposals(for: branchID)
        guard !proposals.isEmpty else {
            return .filled("当前没有待确认的设定建议。")
        }
        let lines = proposals.map { proposal in
            let preview = proposal.content.trimmingCharacters(in: .whitespacesAndNewlines)
            let clipped = preview.count > 80 ? String(preview.prefix(80)) + "…" : preview
            return "- \(proposal.title) (id: \(proposal.id))\n  \(clipped)"
        }
        return .filled(
            "待确认设定建议 \(proposals.count) 条。值得留下的用 novel_revise_material 写入；其余用 novel_reject_setting_proposals 清掉（省略 proposal_ids 即全部拒绝）。\n"
                + lines.joined(separator: "\n")
        )
    }

    private func rejectSettingProposals(_ arguments: String) async -> IOSAgentToolOutcome {
        let trimmed = arguments.trimmingCharacters(in: .whitespacesAndNewlines)
        let args: RejectSettingProposalsArguments
        if trimmed.isEmpty {
            args = RejectSettingProposalsArguments(proposal_ids: nil)
        } else {
            guard let decoded: RejectSettingProposalsArguments = decode(arguments) else {
                return .failed(
                    "novel_reject_setting_proposals 参数无效：proposal_ids 需为 UUID 数组，可省略以全部拒绝。"
                )
            }
            args = decoded
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法拒绝设定建议。")
        }
        let active = snapshot.activeSettingProposals(for: branchID)
        guard !active.isEmpty else {
            return .filled("当前没有待确认的设定建议。")
        }
        let requestedIDs: [NovelProposalID]
        if let rawIDs = args.proposal_ids, !rawIDs.isEmpty {
            var parsed: [NovelProposalID] = []
            for raw in rawIDs {
                guard let uuid = UUID(uuidString: raw) else {
                    return .failed("novel_reject_setting_proposals 的 proposal_ids 不是合法 UUID：\(raw)。")
                }
                parsed.append(NovelProposalID(uuid))
            }
            requestedIDs = parsed
        } else {
            requestedIDs = active.map(\.id)
        }
        let activeIDs = Set(active.map(\.id))
        if let missing = requestedIDs.first(where: { !activeIDs.contains($0) }) {
            return .failed("找不到待确认的设定建议：\(missing)。")
        }
        var rejectedTitles: [String] = []
        for proposalID in requestedIDs {
            guard let current = await loadSnapshot() else {
                return .failed("拒绝设定建议时项目状态丢失。")
            }
            guard let branch = current.branches.first(where: { $0.id == branchID }) else {
                return .failed("当前分支不可用，无法拒绝设定建议。")
            }
            guard let proposal = current.activeSettingProposals(for: branchID).first(where: {
                $0.id == proposalID
            }) else {
                continue
            }
            let command = NovelResolveSettingProposalCommand(
                context: mutationContext(
                    projectRevision: current.project.revision,
                    configRevision: current.project.configRevision,
                    branchHeadRevision: branch.headRevision
                ),
                projectID: projectID,
                proposalID: proposalID,
                resolution: .reject
            )
            if let failure = await perform(.resolveSettingProposal(command)) {
                return .failed("拒绝「\(proposal.title)」失败：\(failure)")
            }
            rejectedTitles.append(proposal.title)
        }
        if rejectedTitles.isEmpty {
            return .filled("当前没有待确认的设定建议。")
        }
        return .filled("已拒绝 \(rejectedTitles.count) 条设定建议：\(rejectedTitles.joined(separator: "、"))。")
    }

    func revertApprovalPrompt(from arguments: String) async -> Result<NovelAskUserPrompt, NovelProjectToolIssue> {
        guard let args: RevertRecentChaptersArguments = decode(arguments) else {
            return .failure(.init(
                "novel_revert_recent_chapters 参数无效：需要 chapter_count。"
            ))
        }
        guard let snapshot = await loadSnapshot() else {
            return .failure(.init("当前小说项目不可用，无法回退章节。"))
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failure(.init(reason))
        }
        switch revertPlan(chapterCount: args.chapter_count, snapshot: snapshot) {
        case .failure(let issue):
            return .failure(issue)
        case .success(let planned):
            let reason = args.reason?.trimmingCharacters(in: .whitespacesAndNewlines)
            let titles = planned.plan.chapters.map { "第 \($0.ordinal) 章《\($0.title)》" }
            let question = titles.count == 1
                ? "回退\(titles[0])？正文和剧情同步点会一起回到这一章之前。"
                : "回退最近 \(titles.count) 章（\(titles.joined(separator: "、"))）？正文和剧情同步点会一起回到这几章之前。"
            let prompt = NovelAskUserPrompt(
                question: question,
                options: NovelManuscriptRevertApproval.options,
                manuscriptRevert: NovelManuscriptRevertProposal(
                    chapterCount: planned.plan.chapters.count,
                    chapterIDs: planned.plan.chapters.map(\.chapterID),
                    chapterTitles: planned.plan.chapters.map(\.title),
                    chapterOrdinals: planned.plan.chapters.map(\.ordinal),
                    targetCheckpointID: planned.plan.targetCheckpointID,
                    expectedHeadRevision: planned.branch.headRevision,
                    expectedWorkingRevision: planned.branch.workingRevision,
                    reason: reason?.isEmpty == true ? nil : reason
                )
            )
            return .success(prompt)
        }
    }

    // MARK: - Helpers

    /// Returns nil on success; a human-readable failure reason otherwise.
    func perform(_ action: NovelAction) async -> String? {
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

    func loadSnapshot() async -> NovelProjectSnapshot? {
        guard let creation else { return nil }
        guard case .project(let snapshot) = try? await creation.snapshot(.project(projectID)) else {
            return nil
        }
        return snapshot
    }

    struct WorkingChapter {
        let ordinal: Int
        let chapterID: NovelChapterID
        let version: NovelChapterVersionRecord
    }

    func workingChapters(in snapshot: NovelProjectSnapshot) -> [WorkingChapter] {
        guard let branch = snapshot.branches.first(where: { $0.id == branchID }) else {
            return []
        }
        let discarded = Set(
            snapshot.chapters.compactMap { chapter -> NovelChapterID? in
                chapter.discardedAt == nil ? nil : chapter.id
            }
        )
        return branch.workingChapterSelections.enumerated().compactMap { index, selection in
            guard !discarded.contains(selection.chapterID),
                  let version = snapshot.chapterVersions.first(where: {
                      $0.id == selection.versionID && $0.chapterID == selection.chapterID
                  }) else {
                return nil
            }
            return WorkingChapter(
                ordinal: index + 1,
                chapterID: selection.chapterID,
                version: version
            )
        }
    }

    private func resolveWorkingChapter(
        snapshot: NovelProjectSnapshot,
        chapterID: String?,
        chapterOrdinal: Int?,
        toolName: String
    ) -> Result<WorkingChapter, NovelProjectToolIssue> {
        let chapters = workingChapters(in: snapshot)
        guard !chapters.isEmpty else {
            return .failure(.init("当前分支还没有收录正文，无法使用 \(toolName)。"))
        }
        if let rawID = chapterID?.trimmingCharacters(in: .whitespacesAndNewlines),
           !rawID.isEmpty {
            guard let uuid = UUID(uuidString: rawID) else {
                return .failure(.init("\(toolName) 的 chapter_id 不是合法 UUID：\(rawID)。"))
            }
            let id = NovelChapterID(uuid)
            guard let match = chapters.first(where: { $0.chapterID == id }) else {
                return .failure(.init("找不到 chapter_id=\(rawID) 的工作章节。"))
            }
            return .success(match)
        }
        if let ordinal = chapterOrdinal {
            guard let match = chapters.first(where: { $0.ordinal == ordinal }) else {
                return .failure(.init(
                    "\(toolName) 的 chapter_ordinal 越界：当前共 \(chapters.count) 章，收到 \(ordinal)。"
                ))
            }
            return .success(match)
        }
        return .success(chapters[chapters.count - 1])
    }

    private func applyRevision(_ proposal: NovelChapterRevisionProposal?) async -> IOSAgentToolOutcome {
        guard let proposal else {
            return .failed("改正文审批缺少替换内容。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法写入正文。")
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failed(reason)
        }
        guard let chapter = workingChapters(in: snapshot).first(where: {
            $0.chapterID == proposal.chapterID
        }) else {
            return .failed("目标章节已不在工作正文里，无法写入。")
        }
        let replaced: (oldText: String, newContent: String)
        do {
            replaced = try NovelParagraphParser.replacingParagraphs(
                in: chapter.version.content,
                start: proposal.startParagraph,
                end: proposal.endParagraph,
                with: proposal.newText
            )
        } catch {
            return .failed("改正文写入失败：\(error.localizedDescription)")
        }
        guard replaced.oldText == proposal.oldText else {
            return .failed("正文已变化，请重新读取后再改。")
        }
        guard let branch = snapshot.branches.first(where: { $0.id == branchID }) else {
            return .failed("当前分支不可用，无法写入正文。")
        }
        let command = NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: snapshot.project.revision,
                expectedConfigRevision: snapshot.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: projectID,
            branchID: branchID,
            chapterID: proposal.chapterID,
            versionID: NovelChapterVersionID(),
            title: chapter.version.title,
            content: replaced.newContent,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )
        if let failure = await perform(.saveManualEdit(command)) {
            return .failed("改正文保存失败：\(failure)")
        }
        let rangeLabel = proposal.startParagraph == proposal.endParagraph
            ? "第 \(proposal.startParagraph) 段"
            : "第 \(proposal.startParagraph)–\(proposal.endParagraph) 段"
        return .filled(
            "已写入第 \(proposal.chapterOrdinal) 章《\(proposal.chapterTitle)》\(rangeLabel)。"
                + " 剧情要点已随正文同一笔更新。"
        )
    }

    private func applyRevert(_ proposal: NovelManuscriptRevertProposal?) async -> IOSAgentToolOutcome {
        guard let proposal else {
            return .failed("回退审批缺少章节目标。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法回退章节。")
        }
        if let reason = await ghostwriteBlockReason(snapshot: snapshot) {
            return .failed(reason)
        }
        switch revertPlan(chapterCount: proposal.chapterCount, snapshot: snapshot) {
        case .failure(let issue):
            return .failed(issue.message)
        case .success(let planned):
            guard planned.branch.headRevision == proposal.expectedHeadRevision,
                  planned.branch.workingRevision == proposal.expectedWorkingRevision,
                  planned.plan.targetCheckpointID == proposal.targetCheckpointID,
                  planned.plan.chapters.map(\.chapterID) == proposal.chapterIDs else {
                return .failed("当前分支已经变化，请重新发起回退。")
            }
            for step in 1...planned.plan.undoStepCount {
                guard let current = await loadSnapshot(),
                      let branch = current.branches.first(where: { $0.id == branchID }) else {
                    if step > 1 {
                        await reconcileGhostwriteProgressAfterRevert()
                    }
                    return .failed("回退进行到第 \(step) 步时项目不可用。")
                }
                let command = NovelUndoBranchHeadCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: current.project.revision,
                        expectedConfigRevision: current.project.configRevision,
                        expectedBranchHeadRevision: branch.headRevision
                    ),
                    projectID: projectID,
                    branchID: branchID,
                    expectedWorkingRevision: branch.workingRevision
                )
                if let failure = await perform(.undoBranchHead(command)) {
                    if step > 1 {
                        await reconcileGhostwriteProgressAfterRevert()
                    }
                    return .failed("已回退 \(step - 1)/\(planned.plan.undoStepCount) 步后失败：\(failure)")
                }
            }
            await reconcileGhostwriteProgressAfterRevert()
            let titles = proposal.chapterTitles.enumerated().map { index, title in
                "第 \(proposal.chapterOrdinals[index]) 章《\(title)》"
            }
            return .filled("已回退\(titles.joined(separator: "、"))。正文和剧情同步点已回到这几章之前。")
        }
    }

    private func revertPlan(
        chapterCount: Int,
        snapshot: NovelProjectSnapshot
    ) -> Result<(plan: NovelRecentChapterRevertPlan, branch: NovelBranchRecord), NovelProjectToolIssue> {
        guard let branch = snapshot.branches.first(where: { $0.id == branchID }) else {
            return .failure(.init("当前分支不可用，无法回退章节。"))
        }
        switch NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: chapterCount,
            branch: branch,
            chapters: snapshot.chapters,
            chapterVersions: snapshot.chapterVersions,
            checkpoints: snapshot.checkpoints
        ) {
        case .failure(let failure):
            return .failure(.init(failure.localizedDescription))
        case .success(let plan):
            return .success((plan, branch))
        }
    }

    private func reconcileGhostwriteProgressAfterRevert() async {
        guard let creation,
              let snapshot = await loadSnapshot(),
              let branch = snapshot.branches.first(where: { $0.id == branchID }),
              let record = try? await creation.loadGhostwriteBatchProgress(
                projectID: projectID,
                branchID: branchID
              ) else {
            return
        }
        let reconciled = record.reconciledAfterManuscriptRevert(
            chapterVersions: snapshot.chapterVersions,
            workingChapterIDs: Set(
                NovelBranchSemantics.workingManuscriptChapters(
                    branch: branch,
                    chapters: snapshot.chapters,
                    chapterVersions: snapshot.chapterVersions
                ).map(\.chapterID)
            ),
            now: Date()
        )
        guard reconciled != record else { return }
        try? await creation.saveGhostwriteBatchProgress(reconciled)
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
            return "代笔正在推进本章（阶段：\(phase.rawValue)），项目字段暂不可改；请先暂停代笔。"
        }
        return nil
    }

    func mutationContext(
        projectRevision: Int64? = nil,
        configRevision: Int64? = nil,
        branchHeadRevision: Int64? = nil
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: projectRevision,
            expectedConfigRevision: configRevision,
            expectedBranchHeadRevision: branchHeadRevision
        )
    }

    /// 白名单与 KMP schema/prompt discussion v8 一致。decisionLog 刻意不开放：UI 四个设定
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

    func decode<T: Decodable>(_ arguments: String) -> T? {
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

private struct SetChapterTitleArguments: Decodable {
    let title: String
    let chapter_ordinal: Int?
    let chapter_id: String?
}

private struct ReadChapterArguments: Decodable {
    let chapter_ordinal: Int?
    let chapter_id: String?
    let start_paragraph: Int?
    let end_paragraph: Int?
}

struct ReviseChapterArguments: Codable {
    let chapter_ordinal: Int?
    let chapter_id: String?
    let start_paragraph: Int
    let end_paragraph: Int
    let new_text: String
    let reason: String?
}

private struct RevertRecentChaptersArguments: Decodable {
    let chapter_count: Int
    let reason: String?
}

private struct RejectSettingProposalsArguments: Decodable {
    let proposal_ids: [String]?
}
