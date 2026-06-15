import SwiftUI

struct ModelEditView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    let isAdding: Bool

    @State private var modelID: String
    @State private var modelName: String
    @State private var contextLength: String
    @State private var modelType: ModelEditType
    @State private var abilities: Set<ModelEditAbility>
    @State private var inputModalities: Set<ModelEditModality>
    @State private var outputModalities: Set<ModelEditModality>
    @State private var searchEnabled: Bool
    @State private var urlContextEnabled: Bool
    @State private var imageGenerationEnabled: Bool
    @State private var alert: ModelEditAlert?

    init(isAdding: Bool = false) {
        self.isAdding = isAdding
        _modelID = State(initialValue: isAdding ? "" : "gpt-4o")
        _modelName = State(initialValue: isAdding ? "" : "gpt-4o")
        _contextLength = State(initialValue: "")
        _modelType = State(initialValue: .chat)
        _abilities = State(initialValue: isAdding ? [.tool] : [.tool, .reasoning])
        _inputModalities = State(initialValue: [.text])
        _outputModalities = State(initialValue: [.text])
        _searchEnabled = State(initialValue: false)
        _urlContextEnabled = State(initialValue: false)
        _imageGenerationEnabled = State(initialValue: false)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        basicSection
                        abilitiesSection
                        modalitiesSection
                        builtInToolsSection
                        advancedSection
                        footerSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回模型列表", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text(isAdding ? "添加模型" : "编辑模型")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text("关闭")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("关闭")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var basicSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "基本设置")
            AmberFormGroup {
                ModelTextFieldRow(
                    title: "模型 ID",
                    text: $modelID,
                    placeholder: "例如：gpt-3.5-turbo",
                    monospace: true
                )
                ModelEditDivider()
                ModelTextFieldRow(
                    title: "模型名称",
                    text: $modelName,
                    placeholder: "用于界面显示"
                )
                ModelEditDivider()
                ModelTextFieldRow(
                    title: "上下文长度",
                    text: $contextLength,
                    placeholder: "留空自动识别，例如 128K / 1M",
                    monospace: true
                )
                ModelEditDivider()
                Menu {
                    ForEach(ModelEditType.allCases) { option in
                        Button(option.title) {
                            modelType = option
                        }
                    }
                } label: {
                    ModelPickerRow(title: "模型类型", value: modelType.title)
                }
            }

            ModelEditNote("当前只编辑本地草稿；真实聊天请求只读取 SettingsStore.modelId。")
        }
    }

    private var abilitiesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力")
            AmberFormGroup {
                ModelChipField(
                    title: "该模型支持的能力",
                    chips: ModelEditAbility.allCases,
                    selection: $abilities
                )
            }

            ModelEditNote("能力标记尚未写入 KMP Model；ChatViewModel 当前不读取这些草稿能力。")
        }
    }

    private var modalitiesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模态")
            AmberFormGroup {
                ModelChipField(
                    title: "输入模态",
                    chips: ModelEditModality.allCases,
                    selection: $inputModalities
                )
                ModelEditDivider()
                ModelChipField(
                    title: "输出模态",
                    chips: ModelEditModality.allCases,
                    selection: $outputModalities
                )
            }
        }
    }

    private var builtInToolsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置工具")
            AmberFormGroup {
                ModelToggleRow(
                    title: "搜索",
                    subtitle: "启用 Google 搜索集成",
                    isOn: searchEnabled
                ) {
                    searchEnabled.toggle()
                }
                ModelEditDivider()
                ModelToggleRow(
                    title: "URL 上下文",
                    subtitle: "启用 URL 内容处理",
                    isOn: urlContextEnabled
                ) {
                    urlContextEnabled.toggle()
                }
                ModelEditDivider()
                ModelToggleRow(
                    title: "图像生成",
                    subtitle: "启用图像生成功能",
                    isOn: imageGenerationEnabled
                ) {
                    imageGenerationEnabled.toggle()
                }
            }

            ModelEditNote("内置工具配置尚未写入真实 Model；当前不会改变 Provider 请求体。")
        }
    }

    private var advancedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "高级")
            AmberFormGroup {
                ModelValueRow(
                    title: "自定义 Headers",
                    subtitle: "附加到该模型的请求头",
                    value: "草稿"
                ) {
                    router.navigate(to: .modelCustomHeaders)
                }
                ModelEditDivider()
                ModelValueRow(
                    title: "自定义 Body",
                    subtitle: "合并进请求体的 JSON 字段",
                    value: "草稿"
                ) {
                    router.navigate(to: .modelCustomBody)
                }
            }
        }
    }

    @ViewBuilder
    private var footerSection: some View {
        if isAdding {
            ModelEditNote("当前只保留本地草稿；关闭后返回模型列表，不创建真实模型配置。")
                .padding(.top, 4)
        } else {
            AmberFormGroup {
                Button {
                    alert = .delete
                } label: {
                    Text("删除尚未接线")
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 20)
            ModelEditNote("当前不会保存或删除真实模型配置；请在默认模型页修改实际使用的模型 ID。")
        }
    }
}

private enum ModelEditType: String, CaseIterable, Identifiable {
    case chat
    case image
    case embedding

    var id: String { rawValue }

    var title: String {
        switch self {
        case .chat: "聊天"
        case .image: "图像"
        case .embedding: "嵌入"
        }
    }
}

private enum ModelEditAbility: String, CaseIterable, Identifiable, ModelChipOption {
    case tool
    case reasoning

    var id: String { rawValue }

    var title: String {
        switch self {
        case .tool: "工具"
        case .reasoning: "推理"
        }
    }
}

private enum ModelEditModality: String, CaseIterable, Identifiable, ModelChipOption {
    case text
    case image
    case audio

    var id: String { rawValue }

    var title: String {
        switch self {
        case .text: "文本"
        case .image: "图片"
        case .audio: "音频"
        }
    }
}

private enum ModelEditAlert: Identifiable {
    case delete

    var id: String {
        switch self {
        case .delete: "delete"
        }
    }

    var title: String {
        switch self {
        case .delete: "删除模型尚未接线"
        }
    }

    var message: String {
        switch self {
        case .delete:
            "当前页面不会删除真实模型配置，避免在迁移期间误改现有服务商数据。"
        }
    }
}

enum ModelCustomFieldsKind {
    case headers
    case body

    var title: String {
        switch self {
        case .headers: "自定义 Headers"
        case .body: "自定义 Body"
        }
    }

    var summary: String {
        switch self {
        case .headers: "随模型请求附加的 HTTP header。"
        case .body: "合并进请求体的额外 JSON 字段。"
        }
    }

    var nameTitle: String {
        switch self {
        case .headers: "Header"
        case .body: "字段"
        }
    }

    var namePlaceholder: String {
        switch self {
        case .headers: "例如 X-Title"
        case .body: "例如 temperature"
        }
    }

    var valuePlaceholder: String {
        switch self {
        case .headers: "例如 AmberAgent"
        case .body: "例如 0.7 / true / \"value\""
        }
    }

    fileprivate var defaultFields: [ModelCustomFieldDraft] {
        switch self {
        case .headers:
            [
                .init(name: "X-Title", value: "AmberAgent"),
                .init(name: "HTTP-Referer", value: "https://github.com")
            ]
        case .body:
            [
                .init(name: "temperature", value: "0.7"),
                .init(name: "max_tokens", value: "4096")
            ]
        }
    }
}

struct ModelCustomFieldsView: View {
    @Environment(\.dismiss) private var dismiss

    let kind: ModelCustomFieldsKind
    @State private var fields: [ModelCustomFieldDraft]

    init(kind: ModelCustomFieldsKind) {
        self.kind = kind
        _fields = State(initialValue: kind.defaultFields)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        summarySection
                        fieldsSection
                        previewSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回编辑模型", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text(kind.title)
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text("关闭")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("关闭")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var summarySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "作用范围")
            AmberFormGroup {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: kind == .headers ? "curlybraces" : "doc.badge.gearshape")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(width: 32, height: 32)
                        .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(kind.title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)

                        Text("\(kind.summary) 当前只做草稿预览，不写入 SettingsStore 或 ChatViewModel。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
        }
    }

    private var fieldsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "字段")
            AmberFormGroup {
                ForEach(fields.indices, id: \.self) { index in
                    ModelCustomFieldRow(
                        kind: kind,
                        field: $fields[index]
                    ) {
                        fields.remove(at: index)
                    }

                    if index < fields.count - 1 {
                        ModelEditDivider()
                    }
                }

                if !fields.isEmpty {
                    ModelEditDivider()
                }

                Button {
                    fields.append(.init(name: "", value: ""))
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "plus.circle.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)

                        Text("添加字段")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)

                        Spacer()
                    }
                    .frame(minHeight: 52)
                    .padding(.horizontal, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            ModelCustomValidationNote(text: validationText, isWarning: true)
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "草稿预览")
            AmberFormGroup {
                Text(previewText)
                    .font(.system(size: 13, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineSpacing(4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                    .textSelection(.enabled)
            }
        }
    }

    private var validFields: [ModelCustomFieldDraft] {
        fields.filter { !$0.name.trimmed.isEmpty && !$0.value.trimmed.isEmpty }
    }

    private var hasWarnings: Bool {
        fields.contains { $0.name.trimmed.isEmpty || $0.value.trimmed.isEmpty } || hasDuplicateNames
    }

    private var hasDuplicateNames: Bool {
        let names = validFields.map { $0.name.trimmed.lowercased() }
        return Set(names).count != names.count
    }

    private var validationText: String {
        if hasDuplicateNames {
            return "\(kind.nameTitle) 名称重复；当前只保留本地草稿，关闭后不会写入真实服务商配置。"
        }

        if fields.contains(where: { $0.name.trimmed.isEmpty || $0.value.trimmed.isEmpty }) {
            return "空名称或空值会在真实写入前被拦截；当前先保留草稿用于编辑。"
        }

        return "\(validFields.count) 个字段仅用于预览；当前不会保存到真实模型配置。"
    }

    private var previewText: String {
        if validFields.isEmpty {
            return kind == .headers ? "No custom headers" : "{\n  \n}"
        }

        switch kind {
        case .headers:
            return validFields
                .map { "\($0.name.trimmed): \($0.value.trimmed)" }
                .joined(separator: "\n")
        case .body:
            let lines = validFields.map { "  \"\($0.name.trimmed)\": \($0.value.trimmed)" }
            return "{\n\(lines.joined(separator: ",\n"))\n}"
        }
    }
}

private struct ModelCustomFieldDraft: Identifiable, Hashable {
    let id = UUID()
    var name: String
    var value: String
}

private struct ModelCustomFieldRow: View {
    let kind: ModelCustomFieldsKind
    @Binding var field: ModelCustomFieldDraft
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 10) {
                TextField(kind.namePlaceholder, text: $field.name)
                    .font(.system(size: 14, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(width: 32, height: 32)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("删除字段")
            }

            TextField(kind.valuePlaceholder, text: $field.value)
                .font(.system(size: 14, weight: .regular, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .background(AmberTheme.surface2.opacity(0.58), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct ModelCustomValidationNote: View {
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

private extension String {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private protocol ModelChipOption: Hashable, Identifiable {
    var title: String { get }
}

private struct ModelTextFieldRow: View {
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

private struct ModelPickerRow: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            HStack {
                Text(value)
                    .font(.body)
                    .foregroundStyle(AmberTheme.accent)

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct ModelChipField<Option: ModelChipOption>: View {
    let title: String
    let chips: [Option]
    @Binding var selection: Set<Option>

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            FlowLayout(spacing: 7) {
                ForEach(chips) { chip in
                    ModelChip(
                        title: chip.title,
                        isSelected: selection.contains(chip)
                    ) {
                        if selection.contains(chip) {
                            selection.remove(chip)
                        } else {
                            selection.insert(chip)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 15)
        .padding(.vertical, 12)
    }
}

private struct ModelChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? AmberTheme.accent : AmberTheme.muted)
                .padding(.horizontal, 13)
                .padding(.vertical, 5)
                .background(
                    isSelected ? AmberTheme.accentTint : AmberTheme.surface2,
                    in: RoundedRectangle(cornerRadius: 9, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .stroke(isSelected ? AmberTheme.accent.opacity(0.28) : AmberTheme.borderSoft, lineWidth: 0.5)
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct ModelToggleRow: View {
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

                ModelEditSwitch(isOn: isOn)
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

private struct ModelValueRow: View {
    let title: String
    let subtitle: String
    let value: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
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

                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ModelEditSwitch: View {
    let isOn: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(isOn ? AmberTheme.accent : AmberTheme.border)
            .frame(width: 48, height: 30)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(.white)
                    .frame(width: 26, height: 26)
                    .shadow(color: .black.opacity(0.14), radius: 3, y: 1)
                    .padding(2)
            }
    }
}

private struct ModelEditDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct ModelEditNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(proposal: proposal, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = arrange(proposal: ProposedViewSize(width: bounds.width, height: proposal.height), subviews: subviews).rows

        for row in rows {
            for item in row.items {
                subviews[item.index].place(
                    at: CGPoint(x: bounds.minX + item.origin.x, y: bounds.minY + item.origin.y),
                    proposal: ProposedViewSize(item.size)
                )
            }
        }
    }

    private func arrange(proposal: ProposedViewSize, subviews: Subviews) -> Arrangement {
        let maxWidth = proposal.width ?? 320
        var rows: [FlowRow] = []
        var currentItems: [FlowItem] = []
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var currentHeight: CGFloat = 0

        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let nextX = currentItems.isEmpty ? 0 : currentX + spacing

            if nextX + size.width > maxWidth, !currentItems.isEmpty {
                rows.append(FlowRow(items: currentItems))
                currentY += currentHeight + spacing
                currentItems = []
                currentX = 0
                currentHeight = 0
            }

            let originX = currentItems.isEmpty ? 0 : currentX + spacing
            currentItems.append(FlowItem(index: index, origin: CGPoint(x: originX, y: currentY), size: size))
            currentX = originX + size.width
            currentHeight = max(currentHeight, size.height)
        }

        if !currentItems.isEmpty {
            rows.append(FlowRow(items: currentItems))
        }

        let height = rows.last?.items.map { $0.origin.y + $0.size.height }.max() ?? 0
        return Arrangement(size: CGSize(width: maxWidth, height: height), rows: rows)
    }

    private struct Arrangement {
        let size: CGSize
        let rows: [FlowRow]
    }

    private struct FlowRow {
        let items: [FlowItem]
    }

    private struct FlowItem {
        let index: Int
        let origin: CGPoint
        let size: CGSize
    }
}

#Preview {
    NavigationStack {
        ModelEditView()
    }
}
