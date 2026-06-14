import SwiftUI
@preconcurrency import Shared

struct SettingsView: View {

    @Bindable var settingsStore: SettingsStore
    @Environment(RouterPath.self) private var router
    @State private var connectionStatus: ConnectionStatus = .idle
    @State private var terminalSmokeResult: IOSTerminalJobSnapshot?
    @State private var sshProfileDraft = IOSSSHProfile()
    @State private var sshPasswordDraft = ""
    @State private var loadedSSHPasswordDraft = ""
    @State private var sshPortDraft = "22"
    @State private var sshStatus: SSHStatus = .idle

    enum ConnectionStatus {
        case idle
        case testing
        case success(String)
        case failure(String)

        var isTesting: Bool {
            if case .testing = self {
                return true
            }
            return false
        }
    }

    enum SSHStatus {
        case idle
        case testing
        case needsTrust(profileId: String, fingerprint: String)
        case success(String)
        case failure(String)

        var isTesting: Bool {
            if case .testing = self {
                return true
            }
            return false
        }
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
                .disabled(connectionStatus.isTesting || !isBaseUrlValid)

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

            Section {
                Button {
                    router.navigate(to: .toolPermissions)
                } label: {
                    Label("Tool Permissions", systemImage: "checkmark.shield")
                }
            } header: {
                Text("Platform Capabilities")
            }

            Section {
                Picker("Default Runtime", selection: $settingsStore.terminalDefaultRuntime) {
                    ForEach(IOSTerminalBuildPolicy.selectableRuntimes) { runtime in
                        Text(runtime.displayName).tag(runtime)
                    }
                }

                Toggle("Enable Experimental Runtimes", isOn: $settingsStore.terminalExperimentalRuntimesEnabled)
                    .disabled(!IOSTerminalBuildPolicy.experimentalRuntimesLinked)

                if !IOSTerminalBuildPolicy.experimentalRuntimesLinked {
                    Text("Experimental runtime code is not linked in this stable build.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button("Run Terminal Smoke Test") {
                    testTerminalRuntime()
                }

                if let terminalSmokeResult {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(terminalSmokeResult.runtime.displayName): \(terminalSmokeResult.status)")
                            .font(.subheadline.weight(.semibold))
                        Text(terminalSmokeResult.outputTail)
                            .font(.caption)
                            .foregroundStyle(terminalSmokeResult.exitCode == 0 ? Color.secondary : Color.red)
                    }
                }
            } header: {
                Text("Terminal Runtime")
            } footer: {
                Text("Stable builds treat SSH as the full CLI runtime and local iOS tools as lightweight file/script helpers. Mosh and iSH stay behind the experimental gate until license and review requirements are satisfied.")
            }

            Section {
                TextField("Profile Name", text: $sshProfileDraft.name)
                    .autocorrectionDisabled()
                TextField("Host", text: $sshProfileDraft.host)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField("Port", text: $sshPortDraft)
                    .keyboardType(.numberPad)
                TextField("Username", text: $sshProfileDraft.username)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                SecureField("Password", text: $sshPasswordDraft)
                    .textInputAutocapitalization(.never)

                Text("Password only in this MVP.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button("Save SSH Profile") {
                    saveSSHProfile()
                }

                HStack {
                    Button("New Profile") {
                        resetSSHProfileDraft()
                    }

                    if settingsStore.sshProfiles.contains(where: { $0.id == sshProfileDraft.id }) {
                        Button("Clear Password", role: .destructive) {
                            clearSSHPassword()
                        }
                    }
                }

                if !settingsStore.sshProfiles.isEmpty {
                    Picker("Default SSH Profile", selection: Binding(
                        get: { settingsStore.sshDefaultProfileId ?? "" },
                        set: { settingsStore.sshDefaultProfileId = $0.isEmpty ? nil : $0 }
                    )) {
                        ForEach(settingsStore.sshProfiles) { profile in
                            Text(profile.displayName).tag(profile.id)
                        }
                    }

                    ForEach(settingsStore.sshProfiles) { profile in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(profile.displayName)
                                        .font(.subheadline.weight(.semibold))
                                    Text("\(profile.username)@\(profile.host):\(profile.port)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button("Edit") {
                                    loadSSHProfile(profile)
                                }
                                .buttonStyle(.borderless)
                            }
                            Text(profile.knownHostSHA256 ?? "Host fingerprint not trusted")
                                .font(.caption)
                                .foregroundStyle(profile.knownHostSHA256 == nil ? Color.orange : Color.secondary)
                        }
                    }
                }

                Button("Test SSH Connection") {
                    testSSHConnection()
                }
                .disabled(sshStatus.isTesting)

                switch sshStatus {
                case .idle:
                    EmptyView()
                case .testing:
                    Text("Testing SSH...")
                        .foregroundStyle(.secondary)
                case .needsTrust(_, let fingerprint):
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Host fingerprint requires trust")
                            .font(.subheadline.weight(.semibold))
                        Text(fingerprint)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Button("Trust Host") {
                            trustSSHHost()
                        }
                    }
                case .success(let message):
                    Text(message)
                        .foregroundStyle(.green)
                case .failure(let message):
                    Text(message)
                        .foregroundStyle(.red)
                }
            } header: {
                Text("Remote SSH")
            } footer: {
                Text("Test Connection discovers the server SHA256 fingerprint. Remote commands only run after you explicitly trust the host.")
            }

            Section {
                ForEach(IOSTerminalRuntimeCapabilities.all) { capability in
                    TerminalRuntimeCapabilityRow(capability: capability)
                }
            } header: {
                Text("Runtime Matrix")
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

    private func testTerminalRuntime() {
        terminalSmokeResult = nil
        let command = settingsStore.terminalDefaultRuntime == .localIOSTools ? "pwd" : "echo amber-terminal-smoke"
        Task {
            let started = await IOSTerminalRuntime.shared.startJob(
                command: command,
                runtime: settingsStore.terminalDefaultRuntime,
                experimentalEnabled: settingsStore.terminalExperimentalRuntimesEnabled,
                sshProfile: settingsStore.defaultSSHProfile,
                sshPassword: settingsStore.defaultSSHProfile.flatMap { settingsStore.passwordForSSHProfile(id: $0.id) }
            )
            if started.status == IOSTerminalJobStatus.running.rawValue {
                terminalSmokeResult = await IOSTerminalRuntime.shared.waitJob(id: started.id, timeoutSeconds: 65)
            } else {
                terminalSmokeResult = started
            }
        }
    }

    private func saveSSHProfile() {
        do {
            var draft = sshProfileDraft
            draft.port = Int(sshPortDraft) ?? 0
            let validated = try draft.validated()
            let isExistingProfile = settingsStore.sshProfiles.contains { $0.id == validated.id }
            try settingsStore.upsertSSHProfile(validated, password: nil)
            if sshPasswordDraft.isEmpty || sshPasswordDraft != loadedSSHPasswordDraft {
                settingsStore.clearSSHPassword(profileId: validated.id)
                loadedSSHPasswordDraft = ""
            }
            sshProfileDraft = validated
            sshPortDraft = String(validated.port)
            if !sshPasswordDraft.isEmpty && sshPasswordDraft != loadedSSHPasswordDraft {
                sshStatus = .success("SSH profile saved. Test SSH Connection to verify and save the password.")
            } else {
                sshStatus = .success(isExistingProfile ? "SSH profile saved." : "SSH profile saved. Test SSH Connection before running commands.")
            }
        } catch {
            sshStatus = .failure(error.localizedDescription)
        }
    }

    private func loadSSHProfile(_ profile: IOSSSHProfile) {
        sshProfileDraft = profile
        sshPortDraft = String(profile.port)
        sshPasswordDraft = settingsStore.passwordForSSHProfile(id: profile.id) ?? ""
        loadedSSHPasswordDraft = sshPasswordDraft
        sshStatus = .idle
    }

    private func testSSHConnection() {
        do {
            var draft = sshProfileDraft
            draft.port = Int(sshPortDraft) ?? 0
            let profile = try draft.validated()
            guard !sshPasswordDraft.isEmpty else { throw IOSSSHError.missingPassword }

            sshStatus = .testing
            Task {
                do {
                    let result = try await IOSTerminalRuntime.shared.testSSHConnection(
                        profile: profile,
                        password: sshPasswordDraft
                    )
                    switch result.trustState {
                    case .trusted:
                        guard await verifySSHPassword(profile: profile, password: sshPasswordDraft) else {
                            settingsStore.clearSSHPassword(profileId: profile.id)
                            sshStatus = .failure("Host trusted, but password authentication failed. Check the password and try again.")
                            return
                        }
                        try settingsStore.upsertSSHProfile(profile, password: sshPasswordDraft)
                        loadedSSHPasswordDraft = sshPasswordDraft
                        sshStatus = .success("SSH host trusted and password verified.")
                    case .needsTrust(let fingerprint):
                        try settingsStore.upsertSSHProfile(profile, password: nil)
                        settingsStore.clearSSHPassword(profileId: profile.id)
                        sshStatus = .needsTrust(profileId: profile.id, fingerprint: fingerprint)
                    case .mismatch(let expected, let actual):
                        sshStatus = .failure("Host fingerprint mismatch. Expected \(expected), got \(actual).")
                    }
                } catch {
                    sshStatus = .failure(error.localizedDescription)
                }
            }
        } catch {
            sshStatus = .failure(error.localizedDescription)
        }
    }

    private func trustSSHHost() {
        guard case .needsTrust(let profileId, let fingerprint) = sshStatus else { return }
        do {
            try settingsStore.trustHost(profileId: profileId, fingerprint: fingerprint)
            guard let profile = settingsStore.sshProfiles.first(where: { $0.id == profileId }) else {
                throw IOSSSHError.invalidProfile("SSH profile was not found.")
            }
            sshProfileDraft = profile
            guard !sshPasswordDraft.isEmpty else {
                settingsStore.clearSSHPassword(profileId: profileId)
                sshStatus = .success("Host trusted. Add a password before running remote SSH commands.")
                return
            }
            sshStatus = .testing
            Task {
                guard await verifySSHPassword(profile: profile, password: sshPasswordDraft) else {
                    settingsStore.clearSSHPassword(profileId: profileId)
                    sshStatus = .failure("Host trusted, but password authentication failed. Check the password and try again.")
                    return
                }
                do {
                    try settingsStore.upsertSSHProfile(profile, password: sshPasswordDraft)
                    loadedSSHPasswordDraft = sshPasswordDraft
                    sshStatus = .success("Host trusted and password verified. Remote SSH commands can now run.")
                } catch {
                    sshStatus = .failure(error.localizedDescription)
                }
            }
        } catch {
            sshStatus = .failure(error.localizedDescription)
        }
    }

    private func verifySSHPassword(profile: IOSSSHProfile, password: String) async -> Bool {
        let started = await IOSTerminalRuntime.shared.startJob(
            command: "echo amber-terminal-auth-check",
            runtime: .remoteSSH,
            experimentalEnabled: false,
            sshProfile: profile,
            sshPassword: password,
            timeoutSeconds: 15
        )
        let finished = started.status == IOSTerminalJobStatus.running.rawValue
            ? await IOSTerminalRuntime.shared.waitJob(id: started.id, timeoutSeconds: 20)
            : started
        return finished?.status == IOSTerminalJobStatus.completed.rawValue && finished?.exitCode == 0
    }

    private func resetSSHProfileDraft() {
        sshProfileDraft = IOSSSHProfile()
        sshPasswordDraft = ""
        loadedSSHPasswordDraft = ""
        sshPortDraft = "22"
        sshStatus = .idle
    }

    private func clearSSHPassword() {
        settingsStore.clearSSHPassword(profileId: sshProfileDraft.id)
        sshPasswordDraft = ""
        loadedSSHPasswordDraft = ""
        sshStatus = .success("SSH password cleared.")
    }
}

private struct TerminalRuntimeCapabilityRow: View {
    let capability: IOSTerminalRuntimeCapability

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(capability.runtime.displayName)
                    .font(.headline)
                Spacer()
                Text(capability.tier.rawValue)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(capability.tier == .stable ? .green : .orange)
            }

            Text(capability.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 4) {
                capabilityLine("External CLI", capability.supportsExternalCLIByDefault)
                capabilityLine("PTY", capability.supportsPTY)
                capabilityLine("Package install", capability.supportsPackageInstall)
                capabilityLine("Long jobs", capability.supportsLongRunningJobs)
                capabilityLine("Interactive login", capability.supportsInteractiveLogin)
                capabilityLine("File sync", capability.supportsFileSync)
                capabilityLine("App Store safe by default", capability.appStoreSafeByDefault)
                Text("License: \(capability.licenseClass.rawValue)")
                    .font(.caption)
                    .foregroundStyle(capability.licenseClass == .permissive ? Color.secondary : Color.orange)
            }
        }
        .padding(.vertical, 4)
    }

    private func capabilityLine(_ title: String, _ enabled: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: enabled ? "checkmark.circle.fill" : "xmark.circle")
                .foregroundStyle(enabled ? .green : .secondary)
            Text(title)
            Spacer()
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
