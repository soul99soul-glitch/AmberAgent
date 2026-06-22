import SwiftUI
import Shared

struct SeatEditorView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let providerRegistry: ProviderRegistryStore?

    @Environment(\.dismiss) private var dismiss
    @State private var roomSettingsStore = IOSCouncilRoomSettingsStore.shared

    @State private var name = "工程"
    @State private var role = "工程"
    @State private var model = ""
    @State private var reasoning: IOSCouncilReasoningPreset = .medium
    @State private var prompt = ""
    @State private var isDefaultSeat = true
    @State private var showRemoveInfo = false
    @State private var isRemoving = false
    @State private var removeResultMessage: String?

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
        self._model = State(initialValue: settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "gpt-4o" : settingsStore.modelId)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        noticeSection
                        identitySection
                        modelSection
                        promptSection
                        previewSection
                        deleteSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            roomSettingsStore.bootstrapLegacySeatsIfNeeded(sharedSettings.savedCouncilSeats, currentModelId: currentModelId)
        }
        .alert("移除席位", isPresented: $showRemoveInfo) {
            Button("移除", role: .destructive) {
                removeAllCustomSeats()
            }
            Button("取消", role: .cancel) { }
        } message: {
            Text("将删除当前保存的全部 \(customSeatCount) 个席位配置。此操作不可恢复。")
        }
        .alert("移除结果", isPresented: Binding(
            get: { removeResultMessage != nil },
            set: { if !$0 { removeResultMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { }
        } message: {
            Text(removeResultMessage ?? "")
        }
    }

    private func removeAllCustomSeats() {
        isRemoving = true
        let count = customSeatCount
        roomSettingsStore.removeAllCustomSeats()
        let legacyCount = sharedSettings.savedCouncilSeats.count
        if legacyCount > 0 {
            for index in stride(from: legacyCount - 1, through: 0, by: -1) {
                sharedSettings.removeCouncilSeat(at: index)
            }
        }
        isRemoving = false
        removeResultMessage = count > 0 ? "已移除 \(count) 个席位配置。" : "没有可移除的席位配置。"
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回模型议会", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("席位设置")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button("关闭") {
                dismiss()
            }
            .font(.system(size: 14.5, weight: .semibold))
            .foregroundStyle(AmberTheme.foreground2)
            .frame(height: 30)
            .padding(.horizontal, 13)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var noticeSection: some View {
        SeatEditorFootnote(text: "自定义席位会保存名称、模型、思考档位和提示词，重启后保留。")
            .padding(.bottom, 10)
    }

    private var identitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "席位")
            AmberFormGroup {
                HStack(spacing: 12) {
                    Text("名称")
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(width: 68, alignment: .leading)

                    TextField("未命名席位", text: $name)
                        .font(.system(size: 15))
                        .foregroundStyle(AmberTheme.foreground)
                        .multilineTextAlignment(.trailing)
                        .textFieldStyle(.plain)
                }
                .frame(minHeight: 56)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)

                SeatEditorDivider()

                Menu {
                    ForEach(["产品", "营销", "公关", "工程", "用户体验", "风险"], id: \.self) { option in
                        Button(option) {
                            role = option
                            if name.isEmpty || ["产品", "营销", "公关", "工程", "用户体验", "风险"].contains(name) {
                                name = option
                            }
                        }
                    }
                } label: {
                    SeatEditorRow(
                        systemImage: nil,
                        title: "角色预设",
                        subtitle: "决定这位席位的评审立场",
                        trailing: role,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }
            SeatEditorFootnote(text: "名称和角色会保存到自定义席位；内置预设席位不可直接修改。")
        }
    }

    private var modelSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模型")
            providerRunnerGroup

            SeatEditorFootnote(text: "模型列表只来自当前激活服务商；也可以手动填写同一服务商支持的请求模型 ID。")
        }
    }

    private var providerRunnerGroup: some View {
        AmberFormGroup {
            Menu {
                ForEach(availableModelIds, id: \.self) { option in
                    Button(option) { model = option }
                }
            } label: {
                SeatEditorRow(
                    systemImage: "cpu",
                    iconColor: AmberTheme.accentIndigo,
                    title: "模型",
                    trailing: model,
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)

            SeatEditorDivider()

            HStack(spacing: 12) {
                Text("请求模型 ID")
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(width: 92, alignment: .leading)
                TextField("例如 gpt-4o", text: $model)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .multilineTextAlignment(.trailing)
                    .textFieldStyle(.plain)
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)

            SeatEditorDivider()

            Menu {
                ForEach(IOSCouncilReasoningPreset.allCases) { option in
                    Button(option.title) { reasoning = option }
                }
            } label: {
                SeatEditorRow(
                    systemImage: "brain.head.profile",
                    iconColor: AmberTheme.accentCyan,
                    title: "思考档位",
                    trailing: reasoning.title,
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)

            SeatEditorDivider()

            Toggle(isOn: $isDefaultSeat) {
                Text("默认加入")
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 56)
        }
    }

    private var promptSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "提示词")
            AmberFormGroup {
                TextField("补充这个席位的发言边界和偏好", text: $prompt, axis: .vertical)
                    .lineLimit(3...7)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
            }
            SeatEditorFootnote(text: "提示词会直接进入该席位的 system prompt。")
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已保存席位")
            AmberFormGroup {
                let seats = roomSettingsStore.settings.seats
                if seats.isEmpty {
                    Text("暂无自定义席位。填写上方名称、角色和模型后点「保存席位」添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(seats.enumerated()), id: \.offset) { index, seat in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(seat.name).font(.body.weight(.semibold))
                                Text("\(seat.rolePrompt) · \(seat.modelId.trimmedOr(currentModelId)) · \(seat.reasoning.title)")
                                    .font(.system(size: 11, design: .monospaced)).foregroundStyle(AmberTheme.muted2)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Button { roomSettingsStore.removeSeat(id: seat.id) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 18)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 48).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < seats.count - 1 { SeatEditorDivider() }
                    }
                }
                SeatEditorDivider()
                Button {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    let normalizedModel = model.trimmedOr(currentModelId)
                    roomSettingsStore.addOrUpdateSeat(
                        IOSCouncilRoomSeatConfig(
                            name: trimmed,
                            rolePrompt: role,
                            modelId: normalizedModel,
                            reasoning: reasoning,
                            prompt: prompt,
                            isDefault: isDefaultSeat
                        ),
                        currentModelId: currentModelId
                    )
                    sharedSettings.addCouncilSeat(name: trimmed, role: role, modelId: normalizedModel)
                    name = ""
                    prompt = ""
                } label: {
                    Label("保存席位", systemImage: "plus.circle.fill").font(.body.weight(.semibold)).foregroundStyle(AmberTheme.accent)
                }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.vertical, 8)
            }
            SeatEditorFootnote(text: "席位会保存在本机，重启后仍然保留。")
        }
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button(role: .destructive) {
                showRemoveInfo = true
            } label: {
                HStack(spacing: 8) {
                    if isRemoving {
                        ProgressView().controlSize(.small)
                    }
                    Text(customSeatCount == 0 ? "没有可移除的席位配置" : "移除全部 \(customSeatCount) 个席位配置")
                        .font(.body)
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 54)
                        .contentShape(Rectangle())
                }
            }
            .buttonStyle(.plain)
            .disabled(customSeatCount == 0 || isRemoving)
        }
        .padding(.top, 20)
    }

    private var customSeatCount: Int {
        roomSettingsStore.settings.seats.count
    }

    private var currentModelId: String {
        settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "gpt-4o" : settingsStore.modelId
    }

    private var availableModelIds: [String] {
        var seen = Set<String>()
        var ids: [String] = []
        // All chat models across every configured provider (shared settings),
        // not just the legacy registry's single selected provider.
        for option in sharedSettings.availableChatModels() {
            guard seen.insert(option.modelId).inserted else { continue }
            ids.append(option.modelId)
        }
        if seen.insert(currentModelId).inserted {
            ids.insert(currentModelId, at: 0)
        }
        return ids
    }
}

private struct SeatEditorStatusLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }
}

private struct SeatEditorRow: View {
    let systemImage: String?
    var iconColor = AmberTheme.accent
    let title: String
    var subtitle: String?
    var trailing: String?
    var showsChevron = false

    var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let trailing {
                Text(trailing)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SeatEditorDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SeatEditorFootnote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private extension String {
    func trimmedOr(_ fallback: String) -> String {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }
}

#Preview {
    NavigationStack {
        SeatEditorView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
    }
}
