import SwiftUI

/// 剧情矛盾检查在「设定 → 剧情」页里的那一段。发起前先给预估(扫几章、几次模型
/// 调用),扫完把每条问题的双方原文并排列出来,点一下跳到对应章节。
struct NovelContinuityAuditSection: View {
    let viewModel: NovelCreationViewModel
    let onOpenChapter: (NovelChapterSelection) -> Void

    var body: some View {
        Section("剧情矛盾检查") {
            // 失败必须说出来。这条链路上没有任何全局错误横幅,不显示就等于
            // 「点了没反应」——那正是上一轮功能被判定为「没做」的原因。
            if let failure = viewModel.continuityAuditFailure {
                Text(failure)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
            }

            if viewModel.isContinuityOperationRunning {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(viewModel.continuityOperationTitle)
                        .foregroundStyle(AmberTheme.muted)
                }
                .padding(.vertical, 3)
                Button("停止扫描", role: .destructive) {
                    viewModel.cancelContinuityAudit()
                }
            } else if let report = viewModel.continuityAudit {
                reportHeader(report)
                if report.issues.isEmpty {
                    Text("没有发现前后打架的地方。")
                        .foregroundStyle(AmberTheme.muted)
                } else {
                    ForEach(report.issues) { issue in
                        NovelContinuityIssueRow(
                            issue: issue,
                            selection: selection(for:),
                            onOpenChapter: onOpenChapter
                        )
                    }
                }
                if report.droppedIssueCount > 0 {
                    Text("另有 \(report.droppedIssueCount) 条被丢弃：模型给的章节号和原文都对不上，无法核实。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                if report.failedChunkCount > 0 {
                    Text("有 \(report.failedChunkCount) 段正文没扫成功，这份结果不完整，可以重新检查。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.accentAmber)
                }
                Button("重新检查") {
                    viewModel.clearContinuityAudit()
                    viewModel.startContinuityAuditPlanning()
                }
                .disabled(!viewModel.canMutate)
            } else if let plan = viewModel.continuityAuditPlan {
                // 预估直接摊在页面上,不弹窗:用户先看清这一趟要读多少、要发几次请求,
                // 再决定要不要开始。
                Text(
                    "要通读 \(plan.chapterCount) 章、约 \(plan.totalCharacterCount) 字，"
                        + "会分 \(plan.chunkCount) 次交给模型。检查期间不能生成正文。"
                )
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                Button("确认开始") {
                    viewModel.startContinuityAudit()
                }
                .disabled(!viewModel.canMutate)
                Button("取消") { viewModel.clearContinuityAuditPlan() }
            } else {
                Text("通读当前分支的全部正文，找出重复写过的情节、前后互相矛盾的说法，以及明明见过却写成初次见面这类问题。只检查，不改动一个字。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                Button {
                    viewModel.startContinuityAuditPlanning()
                } label: {
                    Label("开始检查", systemImage: "text.magnifyingglass")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .disabled(!viewModel.canMutate)
            }
        }
    }

    @ViewBuilder
    private func reportHeader(_ report: NovelContinuityAuditReport) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("扫描了 \(report.scannedChapterCount) 章，发现 \(report.issues.count) 处问题")
                .foregroundStyle(AmberTheme.foreground)
            if isStale(report) {
                Text("正文在这次扫描之后又改过了，结果可能已经过期。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentAmber)
            }
        }
        .padding(.vertical, 3)
    }

    private func isStale(_ report: NovelContinuityAuditReport) -> Bool {
        guard let branch = viewModel.branchSnapshot?.branch else { return true }
        let discarded = Set(
            (viewModel.projectSnapshot?.chapters ?? [])
                .filter { $0.discardedAt != nil }
                .map(\.id)
        )
        return report.isStale(against: branch, discardedChapterIDs: discarded)
    }

    /// 报告里只有 chapterID —— 阅读器要的是「章 + 版本」这一对，版本以当前分支
    /// 工作区选中的那一版为准。查不到就说明这一章已经不在分支上了。
    private func selection(for chapterID: NovelChapterID) -> NovelChapterSelection? {
        viewModel.branchSnapshot?.branch.workingChapterSelections
            .first { $0.chapterID == chapterID }
    }
}

private struct NovelContinuityIssueRow: View {
    let issue: NovelContinuityIssue
    let selection: (NovelChapterID) -> NovelChapterSelection?
    let onOpenChapter: (NovelChapterSelection) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(issue.category.displayName)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(issue.severity.displayName)
                    .font(.caption)
                    .foregroundStyle(issue.severity.tint)
            }
            Text(issue.summary)
                .foregroundStyle(AmberTheme.foreground)
            ForEach(Array(issue.references.enumerated()), id: \.offset) { _, reference in
                if let target = selection(reference.chapterID) {
                    Button {
                        onOpenChapter(target)
                    } label: {
                        referenceBody(reference, isReachable: true)
                    }
                    .buttonStyle(.plain)
                } else {
                    // 跳不过去就别装成可点的:这一章已经不在当前分支上了。
                    VStack(alignment: .leading, spacing: 2) {
                        referenceBody(reference, isReachable: false)
                        Text("这一章已不在当前分支")
                            .font(.caption2)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func referenceBody(
        _ reference: NovelContinuityReference,
        isReachable: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("第 \(reference.chapterOrdinal) 章「\(reference.chapterTitle)」")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isReachable ? AmberTheme.accent : AmberTheme.muted)
            Text(reference.evidence)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .multilineTextAlignment(.leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension NovelContinuityIssueCategoryV1 {
    var displayName: String {
        switch self {
        case .duplicatedPlot: "情节重复"
        case .contradiction: "前后矛盾"
        case .identityDrift: "人物关系对不上"
        case .chronology: "时间线错乱"
        case .statusConflict: "状态冲突"
        case .other: "其他"
        }
    }
}

extension NovelContinuityIssueSeverityV1 {
    var displayName: String {
        switch self {
        case .blocking: "严重"
        case .major: "明显"
        case .minor: "轻微"
        }
    }

    var tint: Color {
        switch self {
        case .blocking: AmberTheme.accentRed
        case .major: AmberTheme.accentAmber
        case .minor: AmberTheme.muted
        }
    }
}
