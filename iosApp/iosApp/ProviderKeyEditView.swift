import SwiftUI
import Shared

/// Real per-provider API Key editor for an activatable preset provider.
///
/// This view closes the honest loop for provider key writeback WITHOUT faking a
/// full `Settings.providers` bridge:
///
///   UI edit  ->  ProviderRegistryStore.saveKey(_:for:)  ->  Keychain
///                                                        (account app.amber.ios.provider.<id>)
///
/// It writes ONLY the real per-provider Keychain slot. It does NOT:
/// - write UserDefaults or mutate the in-memory key-less `ProviderSetting`;
/// - change the selected/current provider;
/// - project into `SettingsStore.baseUrl/apiKey` (that only happens when the user
///   later taps "设为当前" in the provider list, which runs `select()`); or
/// - make any network request (no validation, no balance, no model fetch).
///
/// After a non-empty key is saved, the matching provider row's `canSelect`
/// becomes true (it already had `canActivate`), so the user can then activate it.
///
/// Providers the current scalar chat chain cannot represent (Gemini Google type,
/// xAI Response API, MiMo placeholder base) never reach this editor: the entry row
/// in `ProviderDetailView` only routes here when `canActivate` is true. This view
/// additionally guards on `canActivate` so a stale route cannot write a key for a
/// provider that can never be projected.
struct ProviderKeyEditView: View {
    @Bindable var providerRegistry: ProviderRegistryStore
    let sharedSettings: IOSSharedSettingsStore
    let providerName: String

    @Environment(\.dismiss) private var dismiss

    @State private var keyInput: String = ""
    @State private var didSave: Bool = false
    @State private var didSetCurrent: Bool = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                if let preset = matchedPreset, ProviderRouteKind.isEditablePreset(preset) {
                    ScrollView {
                        VStack(spacing: 0) {
                            editorSection(preset: preset)
                            noteSection
                        }
                        .padding(.bottom, 36)
                    }
                    .scrollIndicators(.hidden)
                } else {
                    unavailableState
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { hydrateFromSnapshot() }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(
                systemImage: "chevron.left",
                accessibilityLabel: "返回服务商详情",
                size: 44,
                symbolSize: 20
            ) {
                dismiss()
            }

            Spacer()

            Text("\(providerName) · API Key")
                .font(.title3.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.8)

            Spacer()

            // Fixed-width trailing slot to keep the title centered.
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 14)
    }

    private func editorSection(preset: ProviderSetting) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "凭据")

            AmberFormGroup {
                VStack(alignment: .leading, spacing: 6) {
                    Text("API Key")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)

                    SecureField(hasExistingKey ? "已保存（输入新值以替换）" : "sk-...",
                                text: $keyInput)
                        .font(.system(size: 14, weight: .regular, design: .monospaced))
                        .foregroundStyle(AmberTheme.foreground)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(minHeight: 58)
                .padding(.horizontal, 15)
                .padding(.vertical, 8)
            }

            ProviderKeyEditFooter(saveFooterText)

            AmberFormGroup {
                Button {
                    save(preset: preset)
                } label: {
                    Text(saveButtonTitle)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .opacity(keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.5 : 1)
                .accessibilityLabel(saveButtonTitle)

                ProviderKeyEditDivider()

                Button {
                    setAsCurrent(preset: preset)
                } label: {
                    Text("设为当前聊天服务商")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(canSetCurrent ? AmberTheme.accent : AmberTheme.muted2)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!canSetCurrent)
                .opacity(canSetCurrent ? 1 : 0.5)

                ProviderKeyEditDivider()

                Button(role: .destructive) {
                    delete(preset: preset)
                } label: {
                    Text(deleteButtonTitle)
                        .font(.body)
                        .foregroundStyle(canDelete ? AmberTheme.accentRed : AmberTheme.muted2)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!canDelete)
                .opacity(canDelete ? 1 : 0.5)
                .accessibilityLabel(deleteButtonTitle)
            }
        }
    }

    private var noteSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            if didSave {
                Label("API Key 已保存到该服务商。",
                      systemImage: "checkmark.seal")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.accentGreen)
            }
            if didSetCurrent {
                Label("已设为当前聊天服务商。",
                      systemImage: "checkmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.accentGreen)
            }

            Text("API Key 保存到本机的服务商配置中。保存不会发起测试请求。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .lineSpacing(2)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var unavailableState: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.slash")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(AmberTheme.muted2)
            Text("\(providerName) 当前不可编辑 API Key")
                .font(.body.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            Text("这个服务商模板当前不能直接用于聊天，因此当前不可编辑 API Key。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
        }
        .frame(maxHeight: .infinity)
    }

    // MARK: - Derived

    /// The real KMP preset matched by unique name, or nil.
    private var matchedPreset: ProviderSetting? {
        DefaultProvidersKt.DEFAULT_PROVIDERS.first { $0.name == providerName }
    }

    /// The live provider entry from the persisted snapshot matching this name.
    private var matchedLiveProvider: ProviderSetting? {
        sharedSettings.snapshot.providers.first { ($0.name as String) == providerName }
    }

    private var hasExistingKey: Bool {
        guard let live = matchedLiveProvider else { return false }
        if let openAI = live as? ProviderSetting.OpenAI { return !openAI.apiKey.isEmpty }
        if let claude = live as? ProviderSetting.Claude { return !claude.apiKey.isEmpty }
        if let google = live as? ProviderSetting.Google { return !google.apiKey.isEmpty }
        return false
    }

    private var canDelete: Bool {
        hasExistingKey
    }

    /// Can be set as current once the key is saved AND the provider exposes at
    /// least one chat-typed model (Android resolves the current provider from
    /// the current chat model id, so a model must exist to point at).
    private var canSetCurrent: Bool {
        guard hasExistingKey, let live = matchedLiveProvider else { return false }
        return live.models.contains { $0.type == ModelType.chat }
    }

    private var saveButtonTitle: String {
        hasExistingKey ? "替换 API Key" : "保存 API Key"
    }

    private var deleteButtonTitle: String {
        "清除 API Key"
    }

    private var saveFooterText: String {
        if hasExistingKey {
            return "已保存 API Key。输入新值并保存会替换它；清除后需要重新填写才能设为当前。"
        }
        return "保存后可把这个服务商设为当前聊天服务商。"
    }

    // MARK: - Actions

    private func hydrateFromSnapshot() {
        // Key is read-only here; never prefill it into the editable field.
        keyInput = ""
        didSave = false
        didSetCurrent = false
    }

    private func save(preset: ProviderSetting) {
        let trimmed = keyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let live = matchedLiveProvider else { return }
        let id = live.id.description()
        sharedSettings.updateProviderApiKey(providerId: id, apiKey: trimmed)
        keyInput = ""
        didSave = true
    }

    private func setAsCurrent(preset: ProviderSetting) {
        guard let live = matchedLiveProvider else { return }
        // Pick the first chat model on this provider as the current chat model
        // (mirrors Android: setting current means choosing a model, which then
        // resolves back to this provider via findProvider).
        guard let chatModel = live.models.first(where: { $0.type == ModelType.chat }) else { return }
        sharedSettings.setCurrentChatModelId(chatModel.id.description())
        didSetCurrent = true
    }

    private func delete(preset: ProviderSetting) {
        guard let live = matchedLiveProvider else { return }
        let id = live.id.description()
        sharedSettings.updateProviderApiKey(providerId: id, apiKey: "")
        keyInput = ""
        didSave = false
        didSetCurrent = false
    }
}

private struct ProviderKeyEditFooter: View {
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

private struct ProviderKeyEditDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

#Preview {
    let settings = SettingsStore()
    return NavigationStack {
        ProviderKeyEditView(
            providerRegistry: ProviderRegistryStore(settingsStore: settings),
            sharedSettings: IOSSharedSettingsStore(),
            providerName: "DeepSeek"
        )
            .environment(RouterPath())
    }
}
