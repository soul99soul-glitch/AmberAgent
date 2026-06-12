import SwiftUI

struct SettingsView: View {

    @Bindable var settingsStore: SettingsStore

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Base URL", text: $settingsStore.baseUrl)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    SecureField("API Key", text: $settingsStore.apiKey)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Model ID", text: $settingsStore.modelId)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } header: {
                    Text("OpenAI Provider")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
