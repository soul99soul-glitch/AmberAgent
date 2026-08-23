import Foundation

/// Four-section workspace brief (contract D-C parity with Android's context
/// assembler). The brief IS the injected current-state section — same slot,
/// same receipts — restructured as canonical constraints:
///
///   1. 当前剧情状态 — summary + outline + gate constraints (always kept)
///   2. 未回收伏笔   — open foreshadowing nodes from the passthrough area
///   3. 本章相关节点 — chapter-plan digest + plan-matching material cards
///                     + the character identity map
///   4. 已确认决定   — roll-call of always-injected material cards
///
/// Sections 2–4 drop when empty and are skipped from the tail when the whole
/// brief exceeds the character budget; section 1 is never dropped.
enum NovelWorkspaceContextAssembler {
    static let budget = 6000
    static let label = "WORKSPACE STATE BRIEF"

    static func brief(
        document: NovelProjectDocumentV1?,
        state: NovelStateSnapshotRecord,
        branch: NovelBranchRecord,
        characterIdentities: [NovelCharacterIdentity],
        includeUnsynchronizedWarning: Bool
    ) -> String {
        var sections: [String] = []
        sections.append(currentPlotState(
            state: state,
            branch: branch,
            includeUnsynchronizedWarning: includeUnsynchronizedWarning
        ))

        let foreshadowing = openForeshadowing(document: document)
        if !foreshadowing.isEmpty {
            sections.append("## 未回收伏笔 / Open foreshadowing\n" + foreshadowing.joined(separator: "\n"))
        }

        if let document {
            let nodes = relevantNodes(
                document: document,
                branch: branch,
                state: state,
                characterIdentities: characterIdentities
            )
            if !nodes.isEmpty {
                sections.append("## 本章相关节点 / Nodes for this chapter\n" + nodes)
            }
            let decisions = confirmedDecisions(document: document)
            if !decisions.isEmpty {
                sections.append("## 已确认决定 / Confirmed decisions\n" + decisions)
            }
        }

        // Budget from the tail: section 1 always stays.
        var kept: [String] = []
        var total = 0
        for (index, section) in sections.enumerated() {
            if index == 0 {
                kept.append(section)
                total += section.count
                continue
            }
            guard total + section.count <= budget else { break }
            kept.append(section)
            total += section.count
        }
        return kept.joined(separator: "\n\n")
    }

    // MARK: - Section 1: current plot state (always kept)

    private static func currentPlotState(
        state: NovelStateSnapshotRecord,
        branch: NovelBranchRecord,
        includeUnsynchronizedWarning: Bool
    ) -> String {
        var lines = [
            "## 当前剧情状态 / Current plot state (canonical)",
            state.summary,
            "",
            "分支大纲 / Branch outline:",
            state.branchOutline,
        ]
        if includeUnsynchronizedWarning, branch.syncStatus == .needsSync {
            lines.append("\n约束：正文工作稿有未同步的手改；以下派生状态可能过期，以正文为准。")
        }
        if state.hasStaleChapterPlots {
            lines.append("\n约束：改过前面的章节后，后续章节的剧情指针可能过期；写后续时以正文为准，不要复述过期的剧情摘要。")
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - Section 2: open foreshadowing

    /// Foreshadowing nodes live in the passthrough area until iOS maintains
    /// them natively (contract D-F); `status: open` is the node schema.
    /// Capped so one bloat section cannot eat the brief budget.
    private static let foreshadowingLineCap = 20

    private static func openForeshadowing(document: NovelProjectDocumentV1?) -> [String] {
        guard let document else { return [] }
        var lines: [String] = []
        for (path, contents) in document.workspacePassthrough.opaqueFiles
            .filter({ $0.key.contains("/plot/foreshadowing/") && $0.key.hasSuffix(".md") })
            .sorted(by: { $0.key < $1.key }) {
            guard lines.count < foreshadowingLineCap else { break }
            let parsed = NovelWorkspaceMarkdown.parseFile(contents)
            guard parsed.fields["status"] != "resolved" else { continue }
            let title = parsed.fields["title"]
                ?? URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
            let firstLine = parsed.body
                .split(separator: "\n", omittingEmptySubsequences: true)
                .first.map(String.init) ?? ""
            lines.append("- \(title)：\(firstLine)")
        }
        return lines
    }

    // MARK: - Section 3: nodes for this chapter

    private static func relevantNodes(
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord,
        state: NovelStateSnapshotRecord,
        characterIdentities: [NovelCharacterIdentity]
    ) -> String {
        var lines: [String] = []

        if let plan = document.chapterPlans.first(where: {
            $0.branchID == branch.id && $0.status == .confirmed
        }) {
            lines.append("本章计划 / Chapter plan:")
            lines.append("- 定位：\(plan.outlinePlacement)")
            lines.append("- 目标与冲突：\(plan.goalAndConflict)")
            if !plan.mustHappen.isEmpty {
                lines.append("- 必须发生：\(plan.mustHappen.joined(separator: "；"))")
            }
            if !plan.mustNotHappen.isEmpty {
                lines.append("- 不得发生：\(plan.mustNotHappen.joined(separator: "；"))")
            }
            lines.append("- 收尾钩子：\(plan.endingHook)")
            lines.append("")
        }

        // Materials whose title/alias the plan text mentions — the one-hop
        // neighborhood degenerates to direct alias matches until relations
        // exist natively.
        let planText = document.chapterPlans
            .first(where: { $0.branchID == branch.id && $0.status == .confirmed })?
            .planText
        if let planText, !planText.isEmpty {
            for card in materialCards(document: document) where card.matches(planText) {
                lines.append("- \(card.title)\(card.aliasSuffix)：\(card.firstLine)")
            }
            if lines.contains(where: { $0.hasPrefix("- ") }) {
                lines.append("")
            }
        }

        let identityResolver = NovelCharacterIdentityResolver(identities: characterIdentities)
        let clarifiedKeys = Set(
            state.characterIdentityClarifications.map {
                NovelCharacterIdentityResolver.normalize($0.mention)
            }
        )
        let identityMap = characterIdentities.map { identity in
            let aliases = identity.aliases.isEmpty
                ? "(none)"
                : identity.aliases.joined(separator: ", ")
            return "- \(identity.canonicalName) | aliases: \(aliases)"
        }
        if !identityMap.isEmpty {
            lines.append("人物身份映射（跨分支权威；新输出用正名）/ Character identity map:")
            lines.append(contentsOf: identityMap)
            lines.append("")
        }
        let clarifications = state.characterIdentityClarifications
            .map { "- \($0.mention): \($0.clarification)" }
            .joined(separator: "\n")
        if !state.characterIdentityClarifications.isEmpty {
            lines.append("作者人物身份澄清（权威；除非作者改判，不要再列为未解决）/ Clarifications:")
            lines.append(clarifications)
            lines.append("")
        }
        let effectiveUnresolved = state.unresolvedEntityNames.filter {
            NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate($0) &&
                !identityResolver.isKnown($0) &&
                !clarifiedKeys.contains(NovelCharacterIdentityResolver.normalize($0))
        }
        if !effectiveUnresolved.isEmpty {
            lines.append("未定身份提及（如需点名请先与作者确认）/ Unresolved entities:")
            lines.append(contentsOf: effectiveUnresolved.map { "- \($0)" })
        }
        return lines.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    // MARK: - Section 4: confirmed decisions

    private static func confirmedDecisions(document: NovelProjectDocumentV1) -> String {
        let titles = materialCards(document: document)
            .filter { $0.alwaysInjected }
            .map { "\($0.title)\($0.aliasSuffix)" }
        guard !titles.isEmpty else { return "" }
        return titles.map { "- \($0)" }.joined(separator: "\n") +
            "\n（以上设定卡以完整材料段注入为准）"
    }

    private struct MaterialCard {
        let title: String
        let aliases: [String]
        let firstLine: String
        let alwaysInjected: Bool

        var aliasSuffix: String {
            aliases.isEmpty ? "" : "（\(aliases.joined(separator: "、"))）"
        }

        func matches(_ planText: String) -> Bool {
            ([title] + aliases).contains { name in
                name.count >= 2 && planText.contains(name)
            }
        }
    }

    private static func materialCards(document: NovelProjectDocumentV1) -> [MaterialCard] {
        document.materials
            .filter { !$0.isDeleted }
            .compactMap { material -> MaterialCard? in
                guard let revision = document.materialRevisions.last(where: {
                    material.revisionIDs.contains($0.id)
                }) else { return nil }
                let firstLine = revision.content
                    .split(separator: "\n", omittingEmptySubsequences: true)
                    .first.map(String.init) ?? ""
                return MaterialCard(
                    title: revision.title,
                    aliases: material.aliases,
                    firstLine: firstLine,
                    alwaysInjected: revision.injectionMode == .always
                )
            }
    }
}

private extension NovelChapterPlanRecord {
    /// Plain text used for alias matching.
    var planText: String {
        ([outlinePlacement, goalAndConflict, endingHook] + mustHappen + mustNotHappen + visibleFacts)
            .joined(separator: "\n")
    }
}
