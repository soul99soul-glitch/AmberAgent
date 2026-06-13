import SwiftUI
@preconcurrency import Shared

struct SettingsView: View {

    @Bindable var settingsStore: SettingsStore
    @State private var connectionStatus: ConnectionStatus = .idle

    enum ConnectionStatus {
        case idle
        case testing
        case success(String)
        case failure(String)
    }

    var isBaseUrlValid: Bool {
        URL(string: settingsStore.baseUrl) != nil
    }

    var body: some View {
        Form {
            Section {
                TextField("Base URL", text: $settingsStore.baseUrl)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                if !isBaseUrlValid {
                    Text("Invalid URL format")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                SecureField("API Key", text: $settingsStore.apiKey)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField("Model ID", text: $settingsStore.modelId)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("OpenAI Provider")
            }

            Section {
                Button("Test Connection") {
                    testConnection()
                }
                .disabled(connectionStatus == .testing || !isBaseUrlValid)

                switch connectionStatus {
                case .idle:
                    EmptyView()
                case .testing:
                    Text("Testing...")
                        .foregroundStyle(.secondary)
                case .success(let msg):
                    Text(msg)
                        .foregroundStyle(.green)
                case .failure(let msg):
                    Text(msg)
                        .foregroundStyle(.red)
                }
            } header: {
                Text("Connection")
            }
        }
        .navigationTitle("Settings")
    }

    private func testConnection() {
        connectionStatus = .testing
        let provider = OpenAIKmpProvider()
        let setting = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: settingsStore.apiKey,
            baseUrl: settingsStore.baseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        Task {
            do {
                let models = try await provider.listModels(providerSetting: setting)
                connectionStatus = .success("Connected — \(models.count) models available")
            } catch {
                connectionStatus = .failure("Failed: \(error.localizedDescription)")
            }
        }
    }
}
