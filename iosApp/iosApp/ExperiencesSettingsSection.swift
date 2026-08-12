import SwiftUI

// MARK: - 自进化「经验（Experiences）」管理区（Phase 3 Wave 2；§11.3 /
// §15 Phase 3 验收 3 / iosApp/AGENTS.md Settings 三件套）
//
// 沿 EvolutionSettingsView 的既有视觉结构（AmberSectionLabel + AmberFormGroup
// + AmberTheme 令牌）：active 条数、每条规则的适用条件与帮助/有害计数、
// supersede/delete 建议的批准/拒绝（验收 3：建议仍需批准才生效）、
// superseded/rejected 默认折叠展示。不做复杂编辑 UI（本 wave 无新增/编辑
// 表单；经验进入池子的路径是后续 wave 的演化制品归因）。

struct ExperiencesSettingsSection: View {
    let model: IOSExperienceSettingsModel
    @State private var showsArchived = false

    var body: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "经验")
            AmberFormGroup {
                summaryRow

                if !model.suggestions.isEmpty {
                    Divider()
                        .overlay(AmberTheme.borderSoft)
                        .padding(.leading, 14)
                    ForEach(model.suggestions, id: \.id) { suggestion in
                        suggestionRow(suggestion)
                        if suggestion.id != model.suggestions.last?.id {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 14)
                        }
                    }
                }

                if !model.activeExperiences.isEmpty {
                    Divider()
                        .overlay(AmberTheme.borderSoft)
                        .padding(.leading, 14)
                    ForEach(model.activeExperiences, id: \.id) { experience in
                        experienceRow(experience)
                        if experience.id != model.activeExperiences.last?.id {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 14)
                        }
                    }
                }

                if !model.archivedExperiences.isEmpty {
                    Divider()
                        .overlay(AmberTheme.borderSoft)
                        .padding(.leading, 14)
                    archivedDisclosure
                }

                if let lastError = model.lastError {
                    Text(lastError)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                }
            }

            Text("每轮组装时按当前任务检索相关经验，与技能目录共享同一字节预算（topK 与预算双上限）；无相关经验或检索失败时静默不注入。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var summaryRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "books.vertical")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text("生效经验 \(model.activeExperiences.count) 条")
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(model.suggestions.isEmpty
                     ? "无待处理建议"
                     : "待处理建议 \(model.suggestions.count) 条（批准后才生效）")
                    .font(.caption)
                    .foregroundStyle(model.suggestions.isEmpty ? AmberTheme.muted : AmberTheme.accentAmber)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: 0)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }

    private func suggestionRow(_ suggestion: IOSExperienceActionSuggestion) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Image(systemName: suggestion.kind == .delete ? "trash" : "arrow.down.right.and.arrow.up.left")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(suggestion.kind == .delete ? "建议删除" : "建议停用")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text("帮助 \(suggestion.helpfulCount) · 有害 \(suggestion.harmfulCount)")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text(suggestion.reason)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                Spacer()
                Button {
                    model.reject(suggestion)
                } label: {
                    Text("拒绝")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .accessibilityLabel("拒绝这条经验建议")

                Button {
                    model.approve(suggestion)
                } label: {
                    Text("批准")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accentRed, in: Capsule())
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .accessibilityLabel("批准这条经验建议并生效")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private func experienceRow(_ experience: IOSEvolutionExperience) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(experience.ruleText)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .fixedSize(horizontal: false, vertical: true)
            Text("适用：\(experience.applicability)")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Text("帮助 \(experience.helpfulCount)")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.accentGreen)
                Text("有害 \(experience.harmfulCount)")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(experience.harmfulCount > 0 ? AmberTheme.accentRed : AmberTheme.muted2)
                if !experience.conflicts.isEmpty {
                    Text("存在冲突规则")
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.accentAmber)
                }
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private var archivedDisclosure: some View {
        DisclosureGroup(isExpanded: $showsArchived) {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(model.archivedExperiences, id: \.id) { experience in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(experience.ruleText)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.foreground2)
                            .fixedSize(horizontal: false, vertical: true)
                            .lineLimit(2)
                        Text("\(experience.status == .superseded ? "已取代" : "已拒绝") · 帮助 \(experience.helpfulCount) · 有害 \(experience.harmfulCount)")
                            .font(.caption2)
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.top, 4)
        } label: {
            Text("已归档 \(model.archivedExperiences.count) 条（superseded / rejected）")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
        .tint(AmberTheme.accentCyan)
        .padding(.horizontal, 14)
        .padding(.vertical, 2)
    }
}
