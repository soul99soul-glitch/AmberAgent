import SwiftUI
import Shared

/// Edits the current Amber Assistant's generation-prompt fields: system prompt,
/// message template, temperature, topP, maxTokens, context window (message count).
///
/// Android parity for AssistantBasicPage / AssistantPromptPage. Previously iOS
/// hardcoded temperature=0.7 and ignored systemPrompt entirely; these controls
/// now back the real values consumed by `ChatViewModel.makeTextGenerationParams`.
struct AssistantParamsSection: View {
    @Bindable var sharedSettings: IOSSharedSettingsStore

    @State private var systemPromptDraft: String = ""
    @State private var messageTemplateDraft: String = ""
    @State private var temperatureDraft: String = ""
    @State private var topPDraft: String = ""
    @State private var maxTokensDraft: String = ""
    @State private var contextMessageSizeDraft: String = ""
    @State private var hasLoadedDrafts = false

    private var assistant: Assistant {
        sharedSettings.snapshot.getCurrentAssistant()
    }

    var body: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "助手提示词与参数")
            AmberFormGroup {
                promptRow
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                templateRow
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                paramRow(title: "Temperature", hint: "0.0–2.0，留空使用默认", draft: $temperatureDraft)
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                paramRow(title: "Top P", hint: "0.0–1.0，留空使用默认", draft: $topPDraft)
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                paramRow(title: "Max Tokens", hint: "生成上限，留空使用模型默认", draft: $maxTokensDraft)
                Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                paramRow(title: "上下文消息数", hint: "注入最近 N 条消息，0 使用默认", draft: $contextMessageSizeDraft)
            }
        }
        .onAppear { loadDraftsIfNeeded() }
    }

    private func loadDraftsIfNeeded() {
        guard !hasLoadedDrafts else { return }
        hasLoadedDrafts = true
        systemPromptDraft = assistant.systemPrompt
        messageTemplateDraft = assistant.messageTemplate
        // temperature/topP are KotlinFloat?; convert via Float() then String.
        temperatureDraft = assistant.temperature.map { String(Float($0)) } ?? ""
        topPDraft = assistant.topP.map { String(Float($0)) } ?? ""
        maxTokensDraft = assistant.maxTokens.map { String(Int($0)) } ?? ""
        contextMessageSizeDraft = String(Int(assistant.contextMessageSize))
    }

    private var promptRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("系统提示词")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            TextEditor(text: $systemPromptDraft)
                .font(.body)
                .frame(minHeight: 100)
                .padding(6)
                .background(AmberTheme.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .onChange(of: systemPromptDraft) { _, newValue in
                    sharedSettings.updateCurrentAssistantParams(systemPrompt: newValue)
                }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private var templateRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("消息模板")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            Text("用 {{ message }} 代表用户输入。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
            TextField("{{ message }}", text: $messageTemplateDraft)
                .font(.body)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(8)
                .background(AmberTheme.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .onChange(of: messageTemplateDraft) { _, newValue in
                    sharedSettings.updateCurrentAssistantParams(messageTemplate: newValue)
                }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private func paramRow(title: String, hint: String, draft: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            Text(hint)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
            TextField("默认", text: draft)
                .font(.body.monospacedDigit())
                .keyboardType(.decimalPad)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(8)
                .background(AmberTheme.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .onChange(of: draft.wrappedValue) { _, _ in
                    commitParams()
                }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    /// Parse the four numeric drafts and write them back. Empty string → clear
    /// (null) so the model default applies, mirroring Android.
    private func commitParams() {
        let temp = Float(temperatureDraft.trimmingCharacters(in: .whitespacesAndNewlines))
        let topPVal = Float(topPDraft.trimmingCharacters(in: .whitespacesAndNewlines))
        let maxTok = Int(maxTokensDraft.trimmingCharacters(in: .whitespacesAndNewlines))
        let ctxMsg = Int(contextMessageSizeDraft.trimmingCharacters(in: .whitespacesAndNewlines))

        sharedSettings.updateCurrentAssistantParams(
            temperature: temp,
            topP: topPVal,
            maxTokens: maxTok,
            contextMessageSize: ctxMsg,
            clearTemperature: temp == nil,
            clearTopP: topPVal == nil,
            clearMaxTokens: maxTok == nil
        )
    }
}
