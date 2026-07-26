import Foundation

/// 一条矛盾在正文里的落点。模型报的是「第几章」(`chapterOrdinal`),这里已经映射回
/// 内部 `NovelChapterID`,界面才能直接跳章。
struct NovelContinuityReference: Equatable, Sendable {
    let chapterID: NovelChapterID
    let chapterOrdinal: Int
    let chapterTitle: String
    let evidence: String
}

struct NovelContinuityIssue: Identifiable, Equatable, Sendable {
    let id: String
    let category: NovelContinuityIssueCategoryV1
    let severity: NovelContinuityIssueSeverityV1
    let summary: String
    let references: [NovelContinuityReference]
}

/// 一次矛盾检查的结果。**不写盘**:它是一份诊断报告,不是故事状态的一部分,写进
/// 项目文档会把 `NovelDocumentValidator` 的不变量面积白白扩大一圈。界面把它留在
/// ViewModel 里,退出项目后需要重扫。
struct NovelContinuityAuditReport: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    /// 扫描时那份章节清单本身。判断「结果是否过期」只能比对它,不能比对分支版本号
    /// ——丢弃/恢复章节只翻 `discardedAt` 并推进 `project.revision`,既不动
    /// `workingRevision` 也不产生新检查点(`NovelChapterDiscardReducer`),
    /// 只看版本号会给出「结果仍然有效」的假答案。
    let auditedChapterSelections: [NovelChapterSelection]
    let promptVersion: String
    let scannedChapterCount: Int
    let chunkCount: Int
    /// 没扫成功的块数。一块失败不作废其余块的结果,但必须如实上报。
    let failedChunkCount: Int
    let issues: [NovelContinuityIssue]
    /// 模型报出来但被丢弃的条数:章节号与标题都对不上,或者证据不是那一章的原文。
    /// 单独计数上报,**不静默吞**——界面要把它显示出来。
    let droppedIssueCount: Int
    let createdAt: Date

    func isStale(
        against branch: NovelBranchRecord,
        discardedChapterIDs: Set<NovelChapterID>
    ) -> Bool {
        branch.id != branchID ||
            auditedChapterSelections != Self.eligibleSelections(
                in: branch,
                discardedChapterIDs: discardedChapterIDs
            )
    }

    /// 一次扫描覆盖的章节清单。执行入口与过期判断必须用同一个函数算,
    /// 否则两边口径一飘,「过期」就会变成随机结果。
    static func eligibleSelections(
        in branch: NovelBranchRecord,
        discardedChapterIDs: Set<NovelChapterID>
    ) -> [NovelChapterSelection] {
        branch.workingChapterSelections.filter { !discardedChapterIDs.contains($0.chapterID) }
    }
}

/// 发起前给用户看的预估:扫几章、切几块。块数直接决定这次要花多少次模型调用。
struct NovelContinuityAuditPlan: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let chapterCount: Int
    let chunkCount: Int
    let totalCharacterCount: Int
}

struct NovelContinuityAuditChapter: Equatable, Sendable {
    let chapterID: NovelChapterID
    /// 从 1 起的全书序号,取自分支章节选择里的**原始位置**(废弃章也占号)。
    /// 与正文页 `NovelChapterReaderView` / `NovelChapterViews` 的章号是同一口径,
    /// 用户拿报告里的「第 N 章」去正文页核对才对得上。
    let ordinal: Int
    let title: String
    let content: String

    var manuscriptBlock: String {
        "# Chapter \(ordinal): \(title)\n\n\(content)"
    }

    /// 校验证据用的源。提示词允许模型「从本块正文的任一连续片段」逐字摘录,
    /// 而标头也在块里,所以证据源必须连标头一起覆盖,否则引用章节标题的条目会被
    /// 冤枉丢掉。
    var evidenceSource: String { manuscriptBlock }
}

struct NovelContinuityAuditChunk: Equatable, Sendable {
    let index: Int
    let chapters: [NovelContinuityAuditChapter]

    var manuscript: String {
        chapters.map(\.manuscriptBlock).joined(separator: "\n\n")
    }
}

enum NovelContinuityAuditPlanner {
    /// 按整章打包成块 —— 刻意不在章内切断。矛盾检查报的是「第几章」,一章被拦腰
    /// 切开就会出现半截章节没有标头、序号对不上的块,报回来的落点无法映射。
    static func chunks(
        chapters: [NovelContinuityAuditChapter],
        maximumChunkTokens: Int
    ) throws -> [NovelContinuityAuditChunk] {
        guard !chapters.isEmpty else { return [] }
        var result: [NovelContinuityAuditChunk] = []
        var current: [NovelContinuityAuditChapter] = []
        var currentTokens = 0
        for chapter in chapters {
            let tokens = estimatedTokens(chapter.manuscriptBlock)
            guard tokens <= maximumChunkTokens else {
                throw NovelError.injectionBudgetExceeded(
                    required: tokens,
                    limit: maximumChunkTokens,
                    items: [NovelInjectionBudgetItem(
                        label: "第 \(chapter.ordinal) 章「\(chapter.title)」",
                        estimatedTokens: tokens
                    )]
                )
            }
            if !current.isEmpty, currentTokens + tokens > maximumChunkTokens {
                result.append(NovelContinuityAuditChunk(
                    index: result.count,
                    chapters: current
                ))
                current = []
                currentTokens = 0
            }
            current.append(chapter)
            currentTokens += tokens
        }
        if !current.isEmpty {
            result.append(NovelContinuityAuditChunk(index: result.count, chapters: current))
        }
        return result
    }

    static func estimatedTokens(_ text: String) -> Int {
        guard !text.isEmpty else { return 0 }
        return max(1, (text.utf8.count + 3) / 4)
    }
}

enum NovelContinuityAuditMapper {
    struct MappedChunk: Equatable, Sendable {
        let issues: [NovelContinuityIssue]
        let droppedCount: Int
    }

    /// 把模型那份「第几章 + 原文摘录」翻译成可跳转的落点,并顺手把编不出来的条目
    /// 剔掉。
    ///
    /// `chapters` 是**全书**可扫章节,不是本块章节:后一块里模型引用前一块的章节是
    /// 跨块矛盾的正常形态(长篇最需要的就是这一类),只认本块会把真结果整条扔掉,
    /// 还会记进丢弃数误导用户。证据仍然按落点所在章的原文逐条核对,所以放宽映射
    /// 范围不会放过编造的落点。
    ///
    /// 一条 issue 只要有**任何一个**落点对不上就整条丢弃 —— 矛盾天然成对,只剩单侧
    /// 的「矛盾」无从对照,留着比丢掉更误导。丢弃数由调用方累加后展示。
    static func map(
        _ audit: NovelContinuityAuditV1,
        chunkIndex: Int,
        chapters: [NovelContinuityAuditChapter]
    ) -> MappedChunk {
        let chaptersByOrdinal = Dictionary(
            chapters.map { ($0.ordinal, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var normalizedSources: [Int: String] = [:]
        func normalizedSource(for chapter: NovelContinuityAuditChapter) -> String {
            if let cached = normalizedSources[chapter.ordinal] { return cached }
            let source = NovelFactTransactionReducer.normalizedEvidenceSource(
                chapter.evidenceSource
            )
            normalizedSources[chapter.ordinal] = source
            return source
        }
        func resolve(_ reference: NovelContinuityReferenceV1) -> NovelContinuityAuditChapter? {
            if let chapter = chaptersByOrdinal[reference.chapterOrdinal],
               NovelFactTransactionReducer.isEvidenceAnchored(
                   reference.evidence,
                   inNormalizedSource: normalizedSource(for: chapter)
               ) {
                return chapter
            }
            // 章号对不上时按标题回退一次:模型偶尔会把块内第一章当成「第 1 章」。
            // 标题必须唯一命中,且证据仍要在那一章里对得上,才认这个落点。
            let byTitle = chapters.filter { $0.title == reference.chapterTitle }
            guard byTitle.count == 1, let candidate = byTitle.first,
                  NovelFactTransactionReducer.isEvidenceAnchored(
                      reference.evidence,
                      inNormalizedSource: normalizedSource(for: candidate)
                  ) else {
                return nil
            }
            return candidate
        }

        var issues: [NovelContinuityIssue] = []
        var dropped = 0
        for issue in audit.issues {
            var references: [NovelContinuityReference] = []
            var isMappable = true
            for reference in issue.references {
                guard let chapter = resolve(reference) else {
                    isMappable = false
                    break
                }
                references.append(NovelContinuityReference(
                    chapterID: chapter.chapterID,
                    chapterOrdinal: chapter.ordinal,
                    chapterTitle: chapter.title,
                    evidence: reference.evidence
                ))
            }
            // 「至少两处落点」的立意是矛盾天然成对。同一句话复制两遍就能伪造一条
            // 矛盾,所以要按「章 + 证据」去重之后再数一次。
            let distinct = Set(references.map { "\($0.chapterOrdinal)\u{1}\($0.evidence)" })
            guard isMappable,
                  distinct.count >= NovelContinuityAuditV1.minimumReferenceCount else {
                dropped += 1
                continue
            }
            issues.append(NovelContinuityIssue(
                // 块内 id 只在本次请求里唯一,跨块会撞;加块号前缀保证全书唯一。
                id: "chunk-\(chunkIndex)-\(issue.id)",
                category: issue.category,
                severity: issue.severity,
                summary: issue.summary,
                references: references
            ))
        }
        return MappedChunk(issues: issues, droppedCount: dropped)
    }

    /// 交给下一块作为「已报过的问题」的紧凑台账。只给一句话摘要和章号,不给证据原文
    /// —— 台账是用来避免重复报的,不是用来当证据源的。
    ///
    /// 摘要长度由模型决定,条数随扫描推进只增不减,所以必须按 `maximumTokens` 截断,
    /// 否则长篇扫到后段会把台账撑出预留额度、挤掉正文甚至撞窗。保留**最近**报出来的
    /// 若干条(后一块最可能与它们重复),并明说略去了多少条。
    static func priorFindingsDigest(
        _ issues: [NovelContinuityIssue],
        maximumTokens: Int
    ) -> String {
        guard !issues.isEmpty, maximumTokens > 0 else { return "" }
        let lines = issues.map { issue in
            let chapters = issue.references
                .map { "Chapter \($0.chapterOrdinal)" }
                .joined(separator: ", ")
            return "- [\(issue.category.rawValue)] \(issue.summary) (\(chapters))"
        }

        var kept: [String] = []
        var tokens = 0
        for line in lines.reversed() {
            let cost = NovelContinuityAuditPlanner.estimatedTokens(line + "\n")
            guard tokens + cost <= maximumTokens else { break }
            kept.insert(line, at: 0)
            tokens += cost
        }
        guard kept.count < lines.count else { return kept.joined(separator: "\n") }

        // 略去的条数也要占额度,所以从尾部再让出一行的空间来放这句说明。
        var body = kept
        var note = "(\(lines.count - body.count) earlier issues omitted)"
        while !body.isEmpty,
              tokens + NovelContinuityAuditPlanner.estimatedTokens(note + "\n") > maximumTokens {
            let removed = body.removeFirst()
            tokens -= NovelContinuityAuditPlanner.estimatedTokens(removed + "\n")
            note = "(\(lines.count - body.count) earlier issues omitted)"
        }
        return ([note] + body).joined(separator: "\n")
    }
}
