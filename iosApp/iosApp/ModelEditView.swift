import SwiftUI

struct ModelEditView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var modelID = "deepseek-v4-flash"
    @State private var modelName = "DeepSeek V4 Flash"
    @State private var contextLength = "1M"
    @State private var modelType: ModelEditType = .chat
    @State private var abilities: Set<ModelEditAbility> = [.tool, .reasoning]
    @State private var inputModalities: Set<ModelEditModality> = [.text]
    @State private var outputModalities: Set<ModelEditModality> = [.text]
    @State private var searchEnabled = false
    @State private var urlContextEnabled = false
    @State private var imageGenerationEnabled = false
    @State private var alert: ModelEditAlert?

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
                        deleteSection
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

            Text("编辑模型")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text("完成")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("完成")
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

            ModelEditNote("模型 ID 必须与服务商 API 完全一致；名称仅用于界面显示。")
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

            ModelEditNote("「工具」允许模型调用函数；「推理」标记其具备思维链能力。仅聊天模型适用。")
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

            ModelEditNote("由 API 内置、无需 App 提供，当前仅 Gemini 官方 API 支持。")
        }
    }

    private var advancedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "高级")
            AmberFormGroup {
                ModelValueRow(
                    title: "自定义 Headers",
                    subtitle: "附加到该模型的请求头",
                    value: "0 个"
                ) {
                    alert = .headers
                }
                ModelEditDivider()
                ModelValueRow(
                    title: "自定义 Body",
                    subtitle: "合并进请求体的 JSON 字段",
                    value: "0 个"
                ) {
                    alert = .body
                }
            }
        }
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                alert = .delete
            } label: {
                Text("删除此模型")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
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
    case headers
    case body
    case delete

    var id: String {
        switch self {
        case .headers: "headers"
        case .body: "body"
        case .delete: "delete"
        }
    }

    var title: String {
        switch self {
        case .headers: "自定义 Headers 尚未接线"
        case .body: "自定义 Body 尚未接线"
        case .delete: "删除模型尚未接线"
        }
    }

    var message: String {
        switch self {
        case .headers:
            "当前先还原模型编辑原型；请求头编辑会在真实 Provider 写入接通后补上。"
        case .body:
            "当前先还原模型编辑原型；请求体 JSON 字段编辑会在真实 Provider 写入接通后补上。"
        case .delete:
            "当前页面不会删除真实模型配置，避免在迁移期间误改现有服务商数据。"
        }
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
