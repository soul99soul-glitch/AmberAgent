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
    let providerName: String

    @Environment(\.dismiss) private var dismiss

    @State private var keyInput: String = ""
    @State private var didSave: Bool = false
    @State private var hasExistingKey: Bool = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                if let preset = matchedPreset, providerRegistry.canActivate(preset) {
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
        .onAppear { hydrateFromKeychain() }
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
                Label("API Key 已保存。返回列表可将这个服务商设为当前。",
                      systemImage: "checkmark.seal")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.accentGreen)
            }

            Text("API Key 只保存在本机钥匙串。保存不会自动切换当前服务商，也不会发起测试请求。")
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

    private var canDelete: Bool {
        hasExistingKey
    }

    private var saveButtonTitle: String {
        hasExistingKey ? "替换 Keychain 中的 Key" : "保存到 Keychain"
    }

    private var deleteButtonTitle: String {
        "清除已保存的 Key"
    }

    private var saveFooterText: String {
        if hasExistingKey {
            return "已保存 API Key。输入新值并保存会替换它；清除后需要重新填写才能设为当前。"
        }
        return "保存后即可在服务商列表将该模板设为当前。"
    }

    // MARK: - Actions

    private func hydrateFromKeychain() {
        guard let preset = matchedPreset else {
            hasExistingKey = false
            return
        }
        let stored = providerRegistry.storedKey(for: preset) ?? ""
        hasExistingKey = !stored.isEmpty
        keyInput = ""  // never prefill the key into the editable field
        didSave = false
    }

    private func save(preset: ProviderSetting) {
        let trimmed = keyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        providerRegistry.saveKey(trimmed, for: preset)
        hasExistingKey = true
        keyInput = ""
        didSave = true
    }

    private func delete(preset: ProviderSetting) {
        providerRegistry.saveKey("", for: preset)  // saveKey skips empty writes (delete semantics)
        hasExistingKey = providerRegistry.hasStoredKey(preset)
        keyInput = ""
        didSave = false
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
            providerName: "DeepSeek"
        )
            .environment(RouterPath())
    }
}
