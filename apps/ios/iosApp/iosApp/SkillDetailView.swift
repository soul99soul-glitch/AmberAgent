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
    @State private var restoreConfirmationPresented = false
    @State private var rollbackConfirmationPresented = false
    @State private var rollbackAvailability: IOSSkillRollbackAvailability = .unavailable(
        "正在检查可回退版本。"
    )

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
                    rollbackSection
                    if hasFactoryBackup {
                        restoreSection
                    }
                    if !isRequiredBuiltinSkill {
                        deleteSection
                    }
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
            Text("会删除这个本机技能，并从已启用它的助手中移除。")
        }
        .confirmationDialog("恢复出厂", isPresented: $restoreConfirmationPresented, titleVisibility: .visible) {
            Button("恢复出厂备份", role: .destructive) {
                restoreFactorySkill()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("会用应用内嵌的出厂文案覆盖当前本机内容，你或 agent 做过的修改会丢失。")
        }
        .confirmationDialog(
            "回退上一次导入",
            isPresented: $rollbackConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button("确认回退", role: .destructive) {
                rollbackLastImport()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text(rollbackAvailability.reason)
        }
        .task(id: dirName ?? skillName) {
            loadSnapshot()
        }
    }

    private var header: some View {
        ZStack {
            Text("技能详情")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回技能", size: 44, symbolSize: 20) {
                    dismiss()
                }

                Spacer()

                SkillEditButton {
                    if let content = snapshot?.content {
                        editorDraft = SkillEditorDraft(content: content)
                    } else {
                        pendingAlert = .file
                    }
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
                Toggle(
                    "启用状态",
                    isOn: Binding(
                        get: { isEnabled },
                        set: { setSkillEnabled($0) }
                    )
                )
                .toggleStyle(.switch)
                .tint(AmberTheme.accent)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .disabled(snapshot == nil)
                .frame(minHeight: 52)
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
            }

            SkillDetailFooter("启用后，聊天可以按这个技能的触发说明使用它。")
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

            SkillDetailFooter(snapshot == nil ? "未能读取这个技能；请从技能列表重新进入。" : "触发说明来自本机技能文件。")
        }
    }

    private var toolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具声明")
            AmberFormGroup {
                SkillStaticValueRow(title: "声明的工具", value: allowedToolsSummary)
            }

            SkillDetailFooter("空表示未限制。此处只展示 frontmatter 声明，不会在运行时强制裁剪可用工具。")
        }
    }

    private var infoSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "信息")
            AmberFormGroup {
                SkillStaticValueRow(title: "版本", value: snapshot?.version ?? "未声明", monospace: true)
                SkillDetailDivider()
                SkillStaticValueRow(title: "本机目录", value: snapshot?.dirName ?? "未读取")
                SkillDetailDivider()
                SkillStaticValueRow(title: "文件", value: snapshot?.relativePath ?? "SKILL.md", monospace: true)
            }

            SkillDetailFooter(
                loadError
                    ?? footerHint
            )
        }
    }

    private var rollbackSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                Button {
                    rollbackConfirmationPresented = true
                } label: {
                    Text("回退上一次导入")
                        .font(.body.weight(.medium))
                        .foregroundStyle(
                            rollbackAvailability.canRollback ? AmberTheme.accent : AmberTheme.muted2
                        )
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!rollbackAvailability.canRollback)
            }
            .padding(.top, 20)

            SkillDetailFooter(rollbackAvailability.reason)
        }
    }

    private var restoreSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                Button {
                    restoreConfirmationPresented = true
                } label: {
                    Text("恢复出厂备份")
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 20)

            SkillDetailFooter("出厂文案嵌在应用内，恢复不会删除这个技能，只会覆盖 SKILL.md。")
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

            SkillDetailFooter("删除后，聊天不会再使用这个技能。")
        }
    }

    private var triggerText: String {
        snapshot?.description.nonEmpty
            ?? snapshot?.bodyPreview.nonEmpty
            ?? "未能读取 \(skillName) 的触发说明。"
    }

    private var statusText: String {
        if snapshot != nil {
            return isEnabled ? "已启用" : "未启用"
        }
        if loadError != nil {
            return "读取失败"
        }
        return "读取中"
    }

    private var allowedToolsSummary: String {
        guard let tools = snapshot?.allowedTools, !tools.isEmpty else {
            return "未限制"
        }
        return tools.joined(separator: ", ")
    }

    private var enabledSkillName: String {
        // Frontmatter display name — used for SKILL.md rename guard only.
        snapshot?.name ?? skillName
    }

    private var enableKey: String {
        // Enable / injection / use_skill all key off the on-disk directory name.
        dirName ?? skillName
    }

    private var isRequiredBuiltinSkill: Bool {
        IOSBuiltinSkills.requiredNames.contains(IOSSkillFileStore.normalizedSkillName(enableKey))
    }

    private var hasFactoryBackup: Bool {
        IOSBuiltinSkills.factorySeedNames.contains(IOSSkillFileStore.normalizedSkillName(enableKey))
    }

    private var footerHint: String {
        if isRequiredBuiltinSkill {
            return "这是 AmberAgent 必需技能：可以编辑或由 agent 迭代；不可删除；需要时可恢复出厂备份。"
        }
        if hasFactoryBackup {
            return "这是 AmberAgent 可选出厂技能，默认不启用。"
        }
        return "编辑按钮会保存到这个本机技能。"
    }

    private func loadSnapshot() {
        refreshRollbackAvailability()
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
            isEnabled = sharedSettings.isSkillEnabled(dirName)
        } catch {
            snapshot = nil
            loadError = "读取技能失败：\(error.localizedDescription)"
            isEnabled = false
        }
    }

    private func setSkillEnabled(_ enabled: Bool) {
        sharedSettings.setSkillEnabled(name: enableKey, enabled: enabled)
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
            sharedSettings.removeSkillFromAllAssistants(name: enableKey)
            IOSBuiltinSkills.markOptionalSeedRemoved(dirName, store: skillStore)
            dismiss()
        } catch {
            pendingAlert = .operationFailed(error.localizedDescription)
        }
    }

    private func restoreFactorySkill() {
        guard let dirName, !dirName.isEmpty else {
            pendingAlert = .operationFailed("未收到技能目录名；请从技能扫描列表进入详情。")
            return
        }
        do {
            try IOSBuiltinSkills.restoreFactoryContent(name: dirName, into: skillStore)
            loadSnapshot()
        } catch {
            pendingAlert = .operationFailed(error.localizedDescription)
        }
    }

    private func refreshRollbackAvailability() {
        guard let dirName, !dirName.isEmpty else {
            rollbackAvailability = .unavailable("未收到技能目录名，无法检查可回退版本。")
            return
        }
        do {
            rollbackAvailability = try skillStore.rollbackAvailability(name: dirName)
        } catch {
            rollbackAvailability = .unavailable("检查可回退版本失败：\(error.localizedDescription)")
        }
    }

    private func rollbackLastImport() {
        guard let dirName, !dirName.isEmpty else {
            pendingAlert = .operationFailed("未收到技能目录名；请从技能扫描列表进入详情。")
            return
        }
        guard case .available(let expectedManifest) = rollbackAvailability else {
            refreshRollbackAvailability()
            pendingAlert = .operationFailed("可回退版本已经失效，请刷新后重试。")
            return
        }
        do {
            let receipt = try skillStore.rollbackSkillPackage(
                name: dirName,
                expectedManifest: expectedManifest
            )
            let manifest = receipt.manifest
            if manifest.optionalSeedWasRemoved {
                IOSBuiltinSkills.markOptionalSeedRemoved(manifest.name, store: skillStore)
            }
            switch manifest.kind {
            case .update:
                sharedSettings.setSkillEnabled(name: manifest.name, enabled: manifest.enabledBefore)
                loadSnapshot()
            case .new:
                sharedSettings.removeSkillFromAllAssistants(name: manifest.name)
                dismiss()
            }
        } catch {
            refreshRollbackAvailability()
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
                .frame(minHeight: 44)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("编辑 SKILL.md")
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
