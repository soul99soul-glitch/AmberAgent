import Foundation
import SwiftUI

struct SkillAddView: View {
    @Environment(\.dismiss) private var dismiss

    let sharedSettings: IOSSharedSettingsStore
    private let skillStore: IOSSkillFileStore

    @State private var skillName = "research-helper"
    @State private var triggerText = "Use when the user asks to investigate a topic, compare sources, or prepare a concise research brief."
    @State private var category: SkillDraftCategory = .task
    @State private var enabled = true
    @State private var allowedTools = "read, rg, web_search"
    @State private var isSaving = false
    @State private var saveError: SkillDraftSaveError?

    init(sharedSettings: IOSSharedSettingsStore, skillStore: IOSSkillFileStore = IOSSkillFileStore()) {
        self.sharedSettings = sharedSettings
        self.skillStore = skillStore
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                SkillDraftHeader(
                    title: "添加技能",
                    doneTitle: isSaving ? "创建中" : "创建",
                    isDoneDisabled: hasBasicsWarning || isSaving,
                    done: saveSkill
                )

                ScrollView {
                    VStack(spacing: 0) {
                        introSection
                        basicsSection
                        permissionsSection
                        previewSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $saveError) { error in
            Alert(
                title: Text("创建失败"),
                message: Text(error.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var introSection: some View {
        AmberFormGroup {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "wrench.and.screwdriver")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 34, height: 34)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text("创建本地 Skill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("填写触发说明和权限边界后，会创建一个本机技能。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
        }
        .padding(.top, 4)
    }

    private var basicsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "基础")
            AmberFormGroup {
                SkillDraftTextFieldRow(title: "技能名称", text: $skillName, placeholder: "skill-name", monospace: true)
                SkillDraftDivider()
                SkillDraftMultilineRow(title: "触发说明", text: $triggerText, placeholder: "说明什么时候使用这个技能")
                SkillDraftDivider()
                Menu {
                    ForEach(SkillDraftCategory.allCases) { option in
                        Button(option.title) {
                            category = option
                        }
                    }
                } label: {
                    SkillDraftPickerRow(title: "类型", value: category.title)
                }
            }

            SkillDraftValidationNote(text: basicsValidationText, isWarning: hasBasicsWarning)
        }
    }

    private var permissionsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "权限")
            AmberFormGroup {
                SkillDraftToggleRow(
                    title: "创建后启用",
                    subtitle: "关闭后只创建技能，不立即启用。",
                    isOn: enabled
                ) {
                    enabled.toggle()
                }
                SkillDraftDivider()
                SkillDraftTextFieldRow(title: "允许的工具", text: $allowedTools, placeholder: "留空表示未限制", monospace: true)
            }

            SkillDraftNote("创建后可在技能详情里继续编辑或删除。")
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "预览")
            AmberFormGroup {
                SkillPreviewLine(label: "目录", value: normalizedName.isEmpty ? "未命名" : normalizedName)
                SkillDraftDivider()
                SkillPreviewLine(label: "文件", value: "SKILL.md")
                SkillDraftDivider()
                SkillPreviewLine(label: "状态", value: enabled ? "创建后启用" : "仅创建文件")
                SkillDraftDivider()
                SkillPreviewBlock(text: previewMarkdown)
            }
        }
    }

    private var normalizedName: String {
        skillName
            .trimmed
            .lowercased()
            .replacingOccurrences(of: " ", with: "-")
    }

    private var hasBasicsWarning: Bool {
        normalizedName.isEmpty || triggerText.trimmed.isEmpty
    }

    private var basicsValidationText: String {
        if normalizedName.isEmpty {
            return "技能名称为空；真实创建前需要一个稳定目录名。"
        }

        if triggerText.trimmed.isEmpty {
            return "触发说明为空；Agent 将无法判断何时使用。"
        }

        return "结构完整；点击创建会写入本地 Skill 目录。"
    }

    private var allowedToolTokens: [String] {
        IOSSkillFileStore.allowedToolTokens(from: allowedTools)
    }

    private var previewMarkdown: String {
        IOSSkillFileStore.makeSkillMarkdown(
            name: normalizedName.isEmpty ? "skill-name" : normalizedName,
            description: triggerText.trimmed,
            allowedTools: allowedToolTokens
        )
    }

    private func saveSkill() {
        guard !hasBasicsWarning else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            let savedName = try skillStore.createSkill(
                name: normalizedName,
                description: triggerText,
                allowedTools: allowedToolTokens
            )
            if enabled {
                sharedSettings.setSkillEnabled(name: savedName, enabled: true)
            }
            dismiss()
        } catch {
            saveError = SkillDraftSaveError(message: error.localizedDescription)
        }
    }
}

private struct SkillDraftHeader: View {
    let title: String
    let doneTitle: String
    var isDoneDisabled = false
    let done: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回技能", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text(title)
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button(action: done) {
                Text(doneTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isDoneDisabled)
            .opacity(isDoneDisabled ? 0.45 : 1)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel(doneTitle)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }
}

private enum SkillDraftCategory: String, CaseIterable, Identifiable {
    case task
    case tool
    case workflow

    var id: String { rawValue }

    var title: String {
        switch self {
        case .task: "任务技能"
        case .tool: "工具技能"
        case .workflow: "工作流"
        }
    }
}

private struct SkillDraftTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var monospace = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            TextField(placeholder, text: $text)
                .font(monospace ? .system(size: 14, weight: .regular, design: .monospaced) : .body)
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
    }
}

private struct SkillDraftMultilineRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            TextField(placeholder, text: $text, axis: .vertical)
                .font(.body)
                .lineLimit(3...6)
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 15)
        .padding(.vertical, 11)
    }
}

private struct SkillDraftPickerRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)

                Text(value)
                    .font(.body)
                    .foregroundStyle(AmberTheme.accent)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct SkillDraftToggleRow: View {
    let title: String
    let subtitle: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)

                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                SkillDraftSwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "开启" : "关闭")
    }
}

private struct SkillDraftSwitch: View {
    let isOn: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 30)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(AmberTheme.background)
                    .frame(width: 26, height: 26)
                    .shadow(color: .black.opacity(0.14), radius: 3, y: 1)
                    .padding(2)
            }
    }
}

private struct SkillPreviewLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.system(size: 14, weight: .medium, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct SkillPreviewBlock: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 12.5, weight: .regular, design: .monospaced))
            .foregroundStyle(AmberTheme.foreground2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(AmberTheme.surface2.opacity(0.58), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
    }
}

private struct SkillDraftValidationNote: View {
    let text: String
    let isWarning: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 7) {
            Image(systemName: isWarning ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isWarning ? AmberTheme.accentAmber : AmberTheme.accentGreen)
                .padding(.top, 2)

            Text(text)
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 7)
    }
}

private struct SkillDraftNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private struct SkillDraftDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private extension String {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

#Preview("Skill Add") {
    SkillAddView(sharedSettings: IOSSharedSettingsStore())
}

private struct SkillDraftSaveError: Identifiable {
    let id = UUID()
    let message: String
}
