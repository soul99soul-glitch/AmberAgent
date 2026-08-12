import SwiftUI

// MARK: - 演化工作流卡片（Phase 2 Wave C；§14.2 / 不变量 17）
//
// T2 人工批准卡与 T0/T1 通知卡复用现有审批卡的视觉结构
// （amberGlass + 图标 + 摘要块 + 胶囊按钮）。长报告/完整候选进可滚动
// disclosure，不把评测摘要塞进不可滚动的大卡（§14.2）。

// MARK: - T2 人工批准卡（或「仅通知」档下 T0/T1 人工卡，带完整自动评估结论）

struct IOSEvolutionApprovalCard: View {
    let model: IOSEvolutionApprovalCardModel
    let onApprove: () -> Void
    let onDeny: () -> Void
    @State private var showsFullReport = false
    @State private var showsFullCandidate = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "wand.and.stars")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentAmber.opacity(0.13), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(model.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("请核对观察、诊断、变更与评测结果；批准后从下一模型轮生效。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: 10) {
                    // 观察到了什么 + 引用（§14.2）
                    summaryBlock(
                        title: "观察到了什么",
                        systemImage: "eye",
                        text: model.observedSummary
                    )

                    // 诊断与替代解释
                    summaryBlock(
                        title: "诊断",
                        systemImage: "stethoscope",
                        text: model.diagnosis
                    )
                    if !model.alternatives.isEmpty {
                        summaryBlock(
                            title: "替代解释",
                            systemImage: "arrow.triangle.branch",
                            text: model.alternatives.joined(separator: "\n")
                        )
                    }
                    if !model.falsifier.isEmpty {
                        summaryBlock(
                            title: "反证",
                            systemImage: "xmark.octagon",
                            text: model.falsifier
                        )
                    }

                    // 新建/更新 + 变更摘要
                    summaryBlock(
                        title: "变更",
                        systemImage: "doc.badge.plus",
                        text: "\(model.mutationKind == .new ? "新建" : "更新") \(model.artifactName)（v\(model.artifactVersion.isEmpty ? "?" : model.artifactVersion)）\n\(model.changeSummary)"
                    )

                    // 步骤 + 权限变化
                    if !model.stepsSummary.isEmpty {
                        summaryBlock(
                            title: "步骤",
                            systemImage: "list.number",
                            text: model.stepsSummary.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
                        )
                    }
                    summaryBlock(
                        title: "权限",
                        systemImage: "lock.shield",
                        text: "\(model.permissionSummary)（包络：\(model.permissionEnvelopeRaw)）"
                    )

                    // 评测数据范围（§18.2：真实输入进入评测前必须显示数据范围）
                    if let dataScopeSummary = model.dataScopeSummary, !dataScopeSummary.isEmpty {
                        summaryBlock(
                            title: "评测数据范围",
                            systemImage: "doc.text",
                            text: dataScopeSummary
                        )
                    }

                    // 三短 hash（base / candidate / report）
                    Text("哈希  base \(shortHash(model.baseHash) ?? "无") → candidate \(shortHash(model.candidateHash) ?? model.candidateHash) · report \(shortHash(model.reportHash) ?? "无")")
                        .font(.caption2.monospaced().weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                        .textSelection(.enabled)

                    // 评测结果（与其余审阅内容共用一个滚动区，避免嵌套滚动）。
                    DisclosureGroup(isExpanded: $showsFullReport) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(model.evaluationResultsText)
                                .font(.caption2.monospaced())
                                .foregroundStyle(AmberTheme.foreground2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                            if !model.unresolvedRisks.isEmpty {
                                Text("未验证项 / 残余风险：")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(AmberTheme.accentAmber)
                                    .padding(.top, 2)
                                Text(model.unresolvedRisks.joined(separator: "\n"))
                                    .font(.caption2)
                                    .foregroundStyle(AmberTheme.muted)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            if !model.skippedTiersText.isEmpty {
                                Text("本版未运行的评测层：\(model.skippedTiersText)")
                                    .font(.caption2)
                                    .foregroundStyle(AmberTheme.muted)
                            }
                        }
                        .padding(.bottom, 4)
                    } label: {
                        Label("评测报告（\(model.reportResultsSummary)）", systemImage: "checklist")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.accentCyan)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                    .tint(AmberTheme.accentCyan)

                    // 完整候选（diff 入口）
                    DisclosureGroup(isExpanded: $showsFullCandidate) {
                        Text(model.candidateJSONPreview)
                            .font(.caption2.monospaced())
                            .foregroundStyle(AmberTheme.foreground2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                            .padding(.bottom, 4)
                    } label: {
                        Label("完整候选（diff 入口）", systemImage: "doc.text.magnifyingglass")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.accentCyan)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                    .tint(AmberTheme.accentCyan)

                    if let assessment = model.policyAssessment {
                        Text(assessment)
                            .font(.caption2)
                            .foregroundStyle(
                                model.policyAssessmentEvidenceGatesPassed
                                    ? AmberTheme.accentGreen
                                    : AmberTheme.accentAmber
                            )
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(maxHeight: 320)
            .scrollIndicators(.visible)

            Label("批准后从下一模型轮生效；可随时回退上一次发布。", systemImage: "arrow.triangle.2.circlepath")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.accentGreen)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                Spacer()
                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatEvolutionHitTarget()
                .accessibilityLabel("拒绝演化候选")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatEvolutionHitTarget()
                .accessibilityLabel("批准演化候选")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentAmber.opacity(0.34), lineWidth: 0.7)
        }
    }

    private func summaryBlock(title: String, systemImage: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: systemImage)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
            Text(text)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }

    private func shortHash(_ hash: String?) -> String? {
        guard let hash, !hash.isEmpty else { return nil }
        return String(hash.prefix(10))
    }
}

// MARK: - 通知卡（T0/T1 自动发布 + 过程通知；不变量 17：一键回退）

struct IOSEvolutionNotificationCard: View {
    let model: IOSEvolutionNotificationModel
    let onRollback: () -> Void
    let onDismiss: () -> Void
    @State private var showsDisclosure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: iconName)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(iconColor)
                    .frame(width: 30, height: 30)
                    .background(iconColor.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(model.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(model.summary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)

                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭通知")
            }

            if !model.detail.isEmpty {
                Text(model.detail)
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let hashes = shortHashes, !hashes.isEmpty {
                Text(hashes)
                    .font(.caption2.monospaced().weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
                    .textSelection(.enabled)
            }

            if let disclosure = model.disclosureContent {
                DisclosureGroup(isExpanded: $showsDisclosure) {
                    ScrollView(.vertical) {
                        Text(disclosure)
                            .font(.system(size: 11, weight: .regular, design: .monospaced))
                            .foregroundStyle(AmberTheme.foreground2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                            .padding(.bottom, 4)
                    }
                    .frame(maxHeight: 160)
                } label: {
                    Label("详情", systemImage: "doc.text.magnifyingglass")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.accentCyan)
                        .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                }
                .tint(AmberTheme.accentCyan)
            }

            if model.canRollback {
                HStack(spacing: 8) {
                    Spacer()
                    Button(action: onRollback) {
                        Label(
                            model.hasBeenRolledBack ? "已回退" : "一键回退",
                            systemImage: "arrow.uturn.backward"
                        )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(model.hasBeenRolledBack ? AmberTheme.muted2 : AmberTheme.accent)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(
                            AmberTheme.surface2.opacity(0.86),
                            in: Capsule()
                        )
                    }
                    .buttonStyle(.plain)
                    .chatEvolutionHitTarget()
                    .disabled(model.hasBeenRolledBack)
                    .accessibilityLabel("回退这次自动发布")
                }
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(tintColor.opacity(0.3), lineWidth: 0.7)
        }
    }

    private var shortHashes: String? {
        guard model.candidateHash != nil || model.reportHash != nil else { return nil }
        return [
            model.candidateHash.map { "candidate \(String($0.prefix(10)))" },
            model.reportHash.map { "report \(String($0.prefix(10)))" },
        ].compactMap { $0 }.joined(separator: " · ")
    }

    private var iconName: String {
        switch model.kind {
        case .autoPromoted: "sparkles"
        case .circuitBreakerTripped: "bolt.badge.exclamationmark"
        case .draftOnly: "doc.badge.clock"
        case .noOp: "questionmark.circle"
        case .capabilityRequest: "point.3.connected.trianglepath.dotted"
        case .harnessProposal: "hammer"
        case .failed: "exclamationmark.triangle"
        }
    }

    private var iconColor: Color {
        switch model.kind {
        case .autoPromoted: AmberTheme.accentGreen
        case .circuitBreakerTripped: AmberTheme.accentRed
        case .draftOnly: AmberTheme.accentAmber
        case .noOp: AmberTheme.muted2
        case .capabilityRequest, .harnessProposal: AmberTheme.accentCyan
        case .failed: AmberTheme.accentRed
        }
    }

    private var tintColor: Color {
        switch model.kind {
        case .autoPromoted: AmberTheme.accentGreen
        case .circuitBreakerTripped, .failed: AmberTheme.accentRed
        case .draftOnly: AmberTheme.accentAmber
        case .noOp: AmberTheme.muted2
        case .capabilityRequest, .harnessProposal: AmberTheme.accentCyan
        }
    }
}

private extension View {
    func chatEvolutionHitTarget() -> some View {
        frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}
