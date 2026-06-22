import SwiftUI
import Shared

struct CouncilSettingsView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let providerRegistry: ProviderRegistryStore?

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var roomSettingsStore = IOSCouncilRoomSettingsStore.shared

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        hostSection
                        limitsSection
                        seatDraftSection
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
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("模型议会")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("配置主持人、默认席位和议会运行限制。模型议会使用当前激活的 OpenAI-compatible 服务商，不混用其他服务商密钥。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var hostSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "主持人")
            AmberFormGroup {
                modelMenu(
                    title: "主持模型",
                    value: roomSettingsStore.settings.host.modelId,
                    setValue: { roomSettingsStore.updateHost(modelId: $0) }
                )
                CouncilSettingsDivider()
                reasoningMenu(
                    title: "思考档位",
                    value: roomSettingsStore.settings.host.reasoning,
                    setValue: { roomSettingsStore.updateHost(reasoning: $0) }
                )
                CouncilSettingsDivider()
                TextField("主持人提示词", text: Binding(
                    get: { roomSettingsStore.settings.host.prompt },
                    set: { roomSettingsStore.updateHost(prompt: $0) }
                ), axis: .vertical)
                .lineLimit(2...5)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            CouncilFootnote(text: "主持人会先进行本轮调研和议题完善，再按顺序拉起席位。")
        }
    }

    private var limitsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "功能限制")
            AmberFormGroup {
                Stepper(
                    "最大席位 \(roomSettingsStore.settings.limits.maxSeats)",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.maxSeats },
                        set: { roomSettingsStore.updateLimits(maxSeats: $0) }
                    ),
                    in: 2...8
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                CouncilSettingsDivider()
                Stepper(
                    "默认轮数 \(roomSettingsStore.settings.limits.defaultRounds)",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.defaultRounds },
                        set: { roomSettingsStore.updateLimits(defaultRounds: $0) }
                    ),
                    in: 1...6
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                CouncilSettingsDivider()
                Stepper(
                    "席位超时 \(roomSettingsStore.settings.limits.seatTimeoutSeconds)s",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.seatTimeoutSeconds },
                        set: { roomSettingsStore.updateLimits(seatTimeoutSeconds: $0) }
                    ),
                    in: 15...180,
                    step: 15
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                CouncilSettingsDivider()
                Stepper(
                    "输出预算 \(roomSettingsStore.settings.limits.outputBudgetCharacters)",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.outputBudgetCharacters },
                        set: { roomSettingsStore.updateLimits(outputBudgetCharacters: $0) }
                    ),
                    in: 2_000...40_000,
                    step: 2_000
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            CouncilFootnote(text: "辩论模式使用默认轮数；自由群聊固定一轮席位发言后由主持人总结。")
        }
    }

    private var seatDraftSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "默认席位")
            AmberFormGroup {
                let seats = roomSettingsStore.settings.seats
                if seats.isEmpty {
                    Text("暂无席位。点「添加席位」进入编辑页添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(seats.enumerated()), id: \.offset) { index, seat in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(seat.name).font(.body.weight(.semibold))
                                Text("\(seat.modelId.trimmedOr(currentModelId)) · \(seat.reasoning.title)")
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted2)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { seat.isDefault },
                                set: { _ in roomSettingsStore.toggleDefaultSeat(id: seat.id) }
                            ))
                            .labelsHidden()
                            Button { roomSettingsStore.removeSeat(id: seat.id) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 44).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < seats.count - 1 { CouncilSettingsDivider() }
                    }
                }
            }
            Button {
                router.navigate(to: .seatEditor)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus").font(.system(size: 17, weight: .semibold)).foregroundStyle(AmberTheme.accent).frame(width: 30, height: 30)
                    Text("添加席位").font(.body.weight(.medium)).foregroundStyle(AmberTheme.accent).frame(maxWidth: .infinity, alignment: .leading)
                }.frame(minHeight: 56).padding(.horizontal, 14).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            CouncilFootnote(text: "席位配置会保存在本机，并作为实时议会 Room 的执行输入。")
        }
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

    private func modelMenu(title: String, value: String, setValue: @escaping (String) -> Void) -> some View {
        Menu {
            ForEach(availableModelIds, id: \.self) { modelId in
                Button(modelId) { setValue(modelId) }
            }
        } label: {
            CouncilSettingsRow(systemImage: "cpu", title: title, trailing: value.trimmedOr(currentModelId))
        }
        .buttonStyle(.plain)
    }

    private func reasoningMenu(title: String, value: IOSCouncilReasoningPreset, setValue: @escaping (IOSCouncilReasoningPreset) -> Void) -> some View {
        Menu {
            ForEach(IOSCouncilReasoningPreset.allCases) { option in
                Button(option.title) { setValue(option) }
            }
        } label: {
            CouncilSettingsRow(systemImage: "brain.head.profile", title: title, trailing: value.title)
        }
        .buttonStyle(.plain)
    }

}

private struct CouncilSettingsRow: View {
    let systemImage: String
    let title: String
    let trailing: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 32, height: 32)
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
            Spacer()
            Text(trailing)
                .font(.caption.weight(.medium))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .contentShape(Rectangle())
    }
}

private struct CouncilSettingsDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct CouncilFootnote: View {
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
        CouncilSettingsView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
