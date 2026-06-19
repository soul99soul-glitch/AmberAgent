import SwiftUI

struct SkillDetailView: View {
    @Environment(\.dismiss) private var dismiss

    let sharedSettings: IOSSharedSettingsStore
    let skillName: String
    let dirName: String?
    private let skillStore: IOSSkillFileStore

    @State private var pendingAlert: SkillDetailAlert?
    @State private var snapshot: SkillDetailSnapshot?
    @State private var loadError: String?
    @State private var isEnabled = false
    @State private var editorDraft: SkillEditorDraft?
    @State private var deleteConfirmationPresented = false

    init(
        sharedSettings: IOSSharedSettingsStore,
        skillName: String,
        dirName: String? = nil,
        skillStore: IOSSkillFileStore = IOSSkillFileStore()
    ) {
        self.sharedSettings = sharedSettings
        self.skillName = skillName
        self.dirName = dirName
        self.skillStore = skillStore
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    hero
                    enableSection
                    triggerSection
                    toolsSection
                    infoSection
                    deleteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $pendingAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
        .sheet(item: $editorDraft) { draft in
            SkillMarkdownEditorSheet(initialText: draft.content) { content in
                saveEditedMarkdown(content)
            }
        }
        .confirmationDialog("删除技能", isPresented: $deleteConfirmationPresented, titleVisibility: .visible) {
            Button("删除 \(snapshot?.name ?? skillName)", role: .destructive) {
                deleteSkill()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("会删除 Documents/skills/\(dirName ?? "") 目录，并从所有 assistant.enabledSkills 中移除该技能。")
        }
        .task(id: dirName ?? skillName) {
            loadSnapshot()
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回技能", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("技能详情")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            SkillEditButton {
                if let content = snapshot?.content {
                    editorDraft = SkillEditorDraft(content: content)
                } else {
                    pendingAlert = .file
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var hero: some View {
        VStack(spacing: 8) {
            Image(systemName: "wrench.and.screwdriver")
                .font(.system(size: 27, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 64, height: 64)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }

            Text(skillName)
                .font(.title3.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            HStack(spacing: 6) {
                Circle()
                    .fill(AmberTheme.muted2)
                    .frame(width: 7, height: 7)
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
        .padding(.bottom, 22)
    }

    private var enableSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                HStack(spacing: 12) {
                    Text("启用状态")
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Button {
                        setSkillEnabled(!isEnabled)
                    } label: {
                        SkillDetailSwitch(isOn: isEnabled)
                    }
                    .buttonStyle(.plain)
                }
                .frame(minHeight: 52)
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
            }

            SkillDetailFooter("启用状态写入当前 assistant.enabledSkills；聊天工具选择会读取这个集合。")
        }
    }

    private var triggerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "触发条件")
            Text(triggerText)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
                .padding(.horizontal, 16)

            SkillDetailFooter(snapshot == nil ? "未能读取真实 SKILL.md；请从技能扫描列表进入详情。" : "内容来自 Documents/skills/\(snapshot?.dirName ?? "")/SKILL.md。")
        }
    }

    private var toolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具与权限")
            AmberFormGroup {
                SkillStaticValueRow(title: "允许的工具", value: allowedToolsSummary)
            }

            SkillDetailFooter("权限来自 SKILL.md frontmatter 中的 allowed-tools / tools 字段；可通过编辑 SKILL.md 修改。")
        }
    }

    private var infoSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "信息")
            AmberFormGroup {
                SkillStaticValueRow(title: "版本", value: snapshot?.version ?? "未声明", monospace: true)
                SkillDetailDivider()
                SkillStaticValueRow(title: "来源", value: snapshot?.dirName ?? "未读取")
                SkillDetailDivider()
                SkillStaticValueRow(title: "文件", value: snapshot?.relativePath ?? "SKILL.md", monospace: true)
            }

            SkillDetailFooter(loadError ?? "已读取真实本地 SKILL.md；编辑按钮会直接写回主文件。")
        }
    }

    private var deleteSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                Button {
                    deleteConfirmationPresented = true
                } label: {
                    Text("删除技能")
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 20)

            SkillDetailFooter("删除会移除本地 Skill 目录，并同步清理所有 assistant.enabledSkills。")
        }
    }

    private var triggerText: String {
        snapshot?.description.nonEmpty
            ?? snapshot?.bodyPreview.nonEmpty
            ?? "未能读取 \(skillName) 的真实触发说明。"
    }

    private var statusText: String {
        if snapshot != nil {
            return isEnabled ? "已启用 · 已读取 SKILL.md" : "未启用 · 已读取 SKILL.md"
        }
        if loadError != nil {
            return "未读取 SKILL.md"
        }
        return "读取中"
    }

    private var allowedToolsSummary: String {
        guard let tools = snapshot?.allowedTools, !tools.isEmpty else {
            return "未声明"
        }
        return tools.joined(separator: ", ")
    }

    private var enabledSkillName: String {
        snapshot?.name ?? skillName
    }

    private func loadSnapshot() {
        guard let dirName, !dirName.isEmpty else {
            snapshot = nil
            loadError = "未收到技能目录名；请从技能扫描列表进入详情。"
            return
        }
        do {
            let content = try skillStore.readSkillMarkdown(dirName: dirName)
            let next = SkillDetailSnapshot(dirName: dirName, content: content)
            snapshot = next
            loadError = nil
            isEnabled = sharedSettings.isSkillEnabled(next.name ?? skillName)
        } catch {
            snapshot = nil
            loadError = "读取 Documents/skills/\(dirName)/SKILL.md 失败：\(error.localizedDescription)"
            isEnabled = false
        }
    }

    private func setSkillEnabled(_ enabled: Bool) {
        sharedSettings.setSkillEnabled(name: enabledSkillName, enabled: enabled)
        isEnabled = enabled
    }

    private func saveEditedMarkdown(_ content: String) -> String? {
        guard let dirName, !dirName.isEmpty else {
            return "未收到技能目录名；请从技能扫描列表进入详情。"
        }
        do {
            try skillStore.saveSkillMarkdown(
                dirName: dirName,
                expectedName: enabledSkillName,
                content: content
            )
            loadSnapshot()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    private func deleteSkill() {
        guard let dirName, !dirName.isEmpty else {
            pendingAlert = .operationFailed("未收到技能目录名；请从技能扫描列表进入详情。")
            return
        }
        do {
            try skillStore.deleteSkill(dirName: dirName)
            sharedSettings.removeSkillFromAllAssistants(name: enabledSkillName)
            dismiss()
        } catch {
            pendingAlert = .operationFailed(error.localizedDescription)
        }
    }
}

private struct SkillDetailSnapshot {
    let dirName: String
    let content: String
    let name: String?
    let description: String
    let allowedTools: [String]
    let version: String?
    let bodyPreview: String

    var relativePath: String {
        "skills/\(dirName)/SKILL.md"
    }

    init(dirName: String, content: String) {
        self.dirName = dirName
        self.content = content
        let parsed = SkillDetailSnapshot.parse(content: content)
        self.name = parsed.frontmatter["name"]
        self.description = parsed.frontmatter["description"] ?? ""
        self.allowedTools = SkillDetailSnapshot.parseList(parsed.frontmatter["allowed-tools"] ?? parsed.frontmatter["tools"])
        self.version = parsed.frontmatter["version"]
        self.bodyPreview = parsed.body
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .prefix(8)
            .joined(separator: "\n")
    }

    private static func parse(content: String) -> (frontmatter: [String: String], body: String) {
        guard content.hasPrefix("---"),
              let endRange = content.range(of: "\n---", range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex) else {
            return ([:], content)
        }

        let yaml = String(content[content.index(content.startIndex, offsetBy: 3)..<endRange.lowerBound])
        let bodyStart = content.index(endRange.upperBound, offsetBy: 0, limitedBy: content.endIndex) ?? endRange.upperBound
        var frontmatter: [String: String] = [:]
        var activeKey: String?
        var activeValues: [String] = []

        func flushActive() {
            guard let key = activeKey else { return }
            frontmatter[key] = activeValues.joined(separator: ", ")
            activeKey = nil
            activeValues = []
        }

        for line in yaml.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("- "), let key = activeKey {
                activeValues.append(String(trimmed.dropFirst(2)).trimmingQuotes)
                frontmatter[key] = activeValues.joined(separator: ", ")
                continue
            }
            flushActive()
            guard let separator = trimmed.firstIndex(of: ":") else { continue }
            let key = String(trimmed[..<separator]).trimmingCharacters(in: .whitespaces)
            let value = String(trimmed[trimmed.index(after: separator)...]).trimmingCharacters(in: .whitespaces).trimmingQuotes
            if value.isEmpty {
                activeKey = key
                activeValues = []
            } else {
                frontmatter[key] = value
            }
        }
        flushActive()

        return (frontmatter, String(content[bodyStart...]))
    }

    private static func parseList(_ raw: String?) -> [String] {
        guard let raw, !raw.isEmpty else { return [] }
        return raw
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .components(separatedBy: CharacterSet(charactersIn: ", \n\t"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).trimmingQuotes }
            .filter { !$0.isEmpty }
    }
}

private extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }

    var trimmingQuotes: String {
        trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
    }
}

private struct SkillEditButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("编辑")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(height: 36)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("编辑 SKILL.md")
    }
}

private struct SkillDetailSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private struct SkillDetailValueRow: View {
    let title: String
    let value: String
    var monospace = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Text(value)
                    .font(monospace ? .system(.caption, design: .monospaced) : .subheadline)
                    .foregroundStyle(AmberTheme.muted)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SkillStaticValueRow: View {
    let title: String
    let value: String
    var monospace = false

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(monospace ? .system(.subheadline, design: .monospaced) : .subheadline)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct SkillDetailDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct SkillDetailFooter: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private struct SkillEditorDraft: Identifiable {
    let id = UUID()
    let content: String
}

private struct SkillMarkdownEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var text: String
    @State private var saveError: String?

    let save: (String) -> String?

    init(initialText: String, save: @escaping (String) -> String?) {
        self._text = State(initialValue: initialText)
        self.save = save
    }

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .scrollContentBackground(.hidden)
                .background(AmberTheme.background)
                .padding(.horizontal, 12)
                .navigationTitle("编辑 SKILL.md")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") {
                            dismiss()
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("保存") {
                            if let error = save(text) {
                                saveError = error
                            } else {
                                dismiss()
                            }
                        }
                    }
                }
                .alert("保存失败", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
                    Button("知道了", role: .cancel) {
                        saveError = nil
                    }
                } message: {
                    Text(saveError ?? "")
                }
        }
    }
}

private enum SkillDetailAlert: Identifiable {
    case file
    case operationFailed(String)

    var id: String {
        switch self {
        case .file: "file"
        case .operationFailed(let message): "operationFailed-\(message)"
        }
    }

    var title: String {
        switch self {
        case .file: "文件"
        case .operationFailed: "操作失败"
        }
    }

    var message: String {
        switch self {
        case .file:
            "未能读取 SKILL.md；请从技能扫描列表进入详情。"
        case .operationFailed(let message):
            message
        }
    }
}
