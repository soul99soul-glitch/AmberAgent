import SwiftUI
import UIKit

struct RuntimeEnvironmentView: View {
    @Bindable var settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    @Environment(\.dismiss) private var dismiss

    @State private var terminalSmokeResult: IOSTerminalJobSnapshot?
    @State private var sshProfileDraft = IOSSSHProfile()
    @State private var sshPasswordDraft = ""
    @State private var loadedSSHPasswordDraft = ""
    @State private var sshPortDraft = "22"
    @State private var sshStatus: SSHStatus = .idle
    @State private var remoteCommand = "echo amber-remote-task"
    @State private var remoteCommandResult: IOSTerminalJobSnapshot?
    @State private var remoteCommandJobId: String?
    @State private var remoteCommandTaskId: String?
    @State private var taskStore = IOSAdvancedTaskStore.shared
    @State private var permissionStore = IOSPermissionStore()
    @State private var showsCapabilityMatrix = false
    @State private var activeSheet: RuntimeEnvironmentSheet?

    private enum RuntimeEnvironmentSheet: String, Identifiable {
        case runtime
        case sshProfile
        case ishTools
        case diagnostics

        var id: String { rawValue }

        var title: String {
            switch self {
            case .runtime: "默认 Runtime"
            case .sshProfile: "SSH Profile"
            case .ishTools: "Agent iSH 工具"
            case .diagnostics: "验证与命令"
            }
        }

        var subtitle: String {
            switch self {
            case .runtime: "选择前台测试和远程命令使用的执行环境。"
            case .sshProfile: "编辑 Remote SSH 连接信息，并完成 Host 信任检查。"
            case .ishTools: "查看聊天中可暴露给 Agent 的 iSH 工具边界。"
            case .diagnostics: "运行 Smoke Test、查看能力矩阵，或手动触发一次远程命令。"
            }
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

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    runtimeStatusSection
                    runtimeOverviewSection
                    connectionOverviewSection
                    agentToolsOverviewSection
                    diagnosticsOverviewSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if let selected = settingsStore.defaultSSHProfile {
                loadSSHProfile(selected)
            }
        }
        .sheet(item: $activeSheet) { sheet in
            RuntimeSheetChrome(title: sheet.title, subtitle: sheet.subtitle) {
                sheetContent(for: sheet)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("运行环境")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 22)
    }

    private var intro: some View {
        Text("配置 Amber 执行命令与 iSH 工具的边界。默认 Runtime 负责前台验证和远程命令；聊天里的 Agent 工具会独立走审批。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
    }

    private var runtimeStatusSection: some View {
        RuntimeStatusCard(
            defaultRuntime: settingsStore.terminalDefaultRuntime,
            sshProfileName: settingsStore.defaultSSHProfile?.displayName,
            embeddedIshAvailable: embeddedIshAvailable,
            externalIshAvailable: externalIshAvailable,
            experimentalEnabled: settingsStore.terminalExperimentalRuntimesEnabled
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var runtimeOverviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "默认 Runtime")
            AmberFormGroup {
                ForEach(Array(primaryRuntimeChoices.enumerated()), id: \.element.id) { index, runtime in
                    RuntimeChoiceRow(
                        runtime: runtime,
                        isSelected: settingsStore.terminalDefaultRuntime == runtime,
                        isEnabled: runtimeIsEnabled(runtime),
                        isRecommended: runtime == .remoteSSH
                    ) {
                        guard runtimeIsEnabled(runtime) else { return }
                        settingsStore.terminalDefaultRuntime = runtime
                    }

                    if index < primaryRuntimeChoices.count - 1 {
                        RuntimeDivider()
                    }
                }

                if shouldShowRuntimeOptionsRow {
                    RuntimeDivider()
                    RuntimeNavigationRow(
                        title: "实验 Runtime",
                        subtitle: experimentalRuntimeSubtitle,
                        value: experimentalRuntimeValue,
                        systemImage: "sparkles",
                        accent: AmberTheme.accentAmber
                    ) {
                        activeSheet = .runtime
                    }
                }
            }

            Text("默认 Runtime 只影响前台测试和 Remote SSH 命令；聊天里的 iSH 工具在下方单独控制。")
                .runtimeFootnote()
        }
    }

    private var connectionOverviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "连接配置")
            AmberFormGroup {
                RuntimeNavigationRow(
                    title: "SSH Profile",
                    subtitle: sshConnectionSummary,
                    value: settingsStore.defaultSSHProfile?.displayName ?? "未配置",
                    systemImage: "desktopcomputer",
                    accent: AmberTheme.accent
                ) {
                    activeSheet = .sshProfile
                }
                RuntimeDivider()
                RuntimeNavigationRow(
                    title: "Host 信任",
                    subtitle: "连接前校验 host key；未信任时不会发送密码",
                    value: sshStatusValue,
                    systemImage: "checkmark.shield",
                    accent: sshStatusAccent
                ) {
                    activeSheet = .sshProfile
                }
            }
        }
    }

    private var agentToolsOverviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Agent iSH 工具")
            AmberFormGroup {
                RuntimeNavigationRow(
                    title: "iSH 工具能力",
                    subtitle: "聊天中审批后调用；内置执行可回传，外部 iSH 为手动交接",
                    value: ishToolsSummary,
                    systemImage: "shippingbox",
                    accent: embeddedIshAvailable ? AmberTheme.accentGreen : AmberTheme.accentAmber
                ) {
                    activeSheet = .ishTools
                }
            }
        }
    }

    private var diagnosticsOverviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "验证")
            AmberFormGroup {
                RuntimeNavigationRow(
                    title: "Smoke Test 与远程命令",
                    subtitle: diagnosticsSummary,
                    value: diagnosticsValue,
                    systemImage: "play.circle",
                    accent: diagnosticsAccent
                ) {
                    activeSheet = .diagnostics
                }
            }
        }
    }

    @ViewBuilder
    private func sheetContent(for sheet: RuntimeEnvironmentSheet) -> some View {
        switch sheet {
        case .runtime:
            runtimeSection
        case .sshProfile:
            sshProfileSection
            hostFingerprintSection
        case .ishTools:
            ishHandoffSection
        case .diagnostics:
            diagnosticsSection
            remoteCommandSection
        }
    }

    private var runtimeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "默认 Runtime")
            AmberFormGroup {
                ForEach(Array(runtimeChoices.enumerated()), id: \.element.id) { index, runtime in
                    RuntimeChoiceRow(
                        runtime: runtime,
                        isSelected: settingsStore.terminalDefaultRuntime == runtime,
                        isEnabled: runtimeIsEnabled(runtime),
                        isRecommended: runtime == .remoteSSH
                    ) {
                        guard runtimeIsEnabled(runtime) else { return }
                        settingsStore.terminalDefaultRuntime = runtime
                    }

                    if index < runtimeChoices.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 14)
                    }
                }
            }

            if IOSTerminalBuildPolicy.experimentalRuntimesLinked {
                AmberFormGroup {
                    RuntimeToggleRow(
                        title: "显示实验 Runtime",
                        subtitle: "允许把 Remote Mosh / iSH Experimental 设为默认 Runtime",
                        isOn: settingsStore.terminalExperimentalRuntimesEnabled,
                        isEnabled: true
                    ) {
                        settingsStore.terminalExperimentalRuntimesEnabled.toggle()
                        if !settingsStore.terminalExperimentalRuntimesEnabled,
                           IOSTerminalRuntimeCapabilities.capability(for: settingsStore.terminalDefaultRuntime).tier == .experimental {
                            settingsStore.terminalDefaultRuntime = .remoteSSH
                        }
                    }
                }
                .padding(.top, 10)
            }

            Text(IOSTerminalBuildPolicy.experimentalRuntimesLinked
                ? "默认 Runtime 只影响前台测试和远程命令入口；聊天里的 iSH 工具是否暴露，由下方 Agent 工具能力和权限闸门决定。"
                : "当前稳定 target 只提供 Remote SSH 和本地轻量工具作为默认 Runtime；聊天里的 iSH 交接能力在下方单独说明。")
                .runtimeFootnote()
        }
    }

    private var sshProfileSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Remote SSH 配置")

            if !settingsStore.sshProfiles.isEmpty {
                AmberFormGroup {
                    Menu {
                        ForEach(settingsStore.sshProfiles) { profile in
                            Button(profile.displayName) {
                                settingsStore.sshDefaultProfileId = profile.id
                                loadSSHProfile(profile)
                            }
                        }
                    } label: {
                        RuntimeValueRow(
                            title: "默认 SSH Profile",
                            subtitle: "Remote SSH Smoke Test 使用此 profile",
                            value: settingsStore.defaultSSHProfile?.displayName ?? "未选择",
                            systemImage: "desktopcomputer"
                        )
                    }
                }
            }

            AmberFormGroup {
                RuntimeTextFieldRow(title: "Profile 名称", text: $sshProfileDraft.name, placeholder: "未命名")
                RuntimeDivider()
                RuntimeTextFieldRow(title: "Host", text: $sshProfileDraft.host, placeholder: "example.com", monospace: true)
                RuntimeDivider()
                RuntimeTextFieldRow(title: "端口", text: $sshPortDraft, placeholder: "22", monospace: true, keyboardType: .numberPad)
                RuntimeDivider()
                RuntimeTextFieldRow(title: "用户名", text: $sshProfileDraft.username, placeholder: "root", monospace: true)
                RuntimeDivider()
                RuntimeSecureFieldRow(title: "密码", text: $sshPasswordDraft, placeholder: "留空则不修改")
            }
            .padding(.top, settingsStore.sshProfiles.isEmpty ? 0 : 10)

            AmberFormGroup {
                RuntimeActionRow(title: "保存 SSH Profile", color: AmberTheme.accent) {
                    saveSSHProfile()
                }
                RuntimeDivider()
                RuntimeActionRow(title: "新建 Profile", color: AmberTheme.accent) {
                    resetSSHProfileDraft()
                }
                if settingsStore.sshProfiles.contains(where: { $0.id == sshProfileDraft.id }) {
                    RuntimeDivider()
                    RuntimeActionRow(title: "清除密码", color: AmberTheme.accentRed) {
                        clearSSHPassword()
                    }
                }
            }
            .padding(.top, 10)

            Text("密码会保存在本机钥匙串。留空保存不会覆盖已存密码；新建 Profile 会清空表单，避免误覆盖。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var ishHandoffSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Agent iSH 工具")
            AmberFormGroup {
                RuntimeInfoRow(
                    title: "内置 iSH 执行",
                    subtitle: embeddedIshAvailable
                        ? "聊天中审批后调用 ios_ish_execute，在 Amber 沙盒内执行短命令并回传 stdout、stderr、exit code。"
                        : "当前 target 未链接 embedded iSH，不会向模型暴露 ios_ish_execute。",
                    value: embeddedIshAvailable ? "可回传" : "未链接",
                    systemImage: embeddedIshAvailable ? "shippingbox" : "lock",
                    accent: embeddedIshAvailable ? AmberTheme.accentGreen : AmberTheme.muted
                )
                RuntimeDivider()
                RuntimeInfoRow(
                    title: "外部 iSH 交接",
                    subtitle: "聊天中审批后调用 ish_handoff，复制可粘贴脚本并写入 Documents/ish-handoff；需要你切到 iSH 手动执行。",
                    value: externalIshAvailable ? "手动交接" : "不可用",
                    systemImage: "terminal",
                    accent: AmberTheme.accentAmber
                )
                RuntimeDivider()
                RuntimeInfoRow(
                    title: "安全边界",
                    subtitle: "两条 iSH 链路都需要前台审批；外部 iSH 不回传结果，内置 iSH 没有 PTY、stdin、长会话和即时取消。",
                    value: "每次审批",
                    systemImage: "hand.raised",
                    accent: AmberTheme.accentAmber
                )
            }

            Text("这两项是聊天工具能力，不等同于默认 Runtime。Remote SSH 仍是稳定远程任务主线；iSH 更适合短命令、手动交接或本地 Linux 能力验证。")
                .runtimeFootnote()
        }
    }

    private var hostFingerprintSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Host 指纹信任")
            HostFingerprintCard(
                status: sshStatus,
                onTrust: trustSSHHost,
                onRetry: testSSHConnection
            )

            HStack {
                Button {
                    testSSHConnection()
                } label: {
                    Label(sshStatus.isTesting ? "检查中..." : "检查 Host 指纹", systemImage: "checkmark.shield")
                }
                .buttonStyle(RuntimeGlassButtonStyle())
                .disabled(sshStatus.isTesting)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)

            Text("这是连接前的安全闸门。未信任或指纹不匹配时，密码不会被发送，远程命令也不会执行。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var diagnosticsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "验证与诊断")
            HStack(spacing: 10) {
                Button {
                    testTerminalRuntime()
                } label: {
                    Label("运行 Smoke Test", systemImage: "play.fill")
                }
                .buttonStyle(RuntimeFilledButtonStyle())

                Button {
                    withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                        showsCapabilityMatrix.toggle()
                    }
                } label: {
                    Label(showsCapabilityMatrix ? "收起矩阵" : "能力矩阵", systemImage: "square.grid.2x2")
                }
                .buttonStyle(RuntimeGlassButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)

            if let terminalSmokeResult {
                SmokeResultCard(result: terminalSmokeResult)
                    .padding(.top, 10)
            }

            Text("Smoke Test 会验证当前默认 Runtime：Remote SSH 执行 echo amber-terminal-smoke，本地工具执行 pwd。")
                .runtimeFootnote()

            if showsCapabilityMatrix {
                VStack(spacing: 8) {
                    ForEach(IOSTerminalRuntimeCapabilities.all) { capability in
                        RuntimeMatrixCard(capability: capability)
                    }
                }
                .padding(.top, 10)
                .transition(.opacity)
            }
        }
    }

    private var remoteCommandSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "远程命令任务")
            AmberFormGroup {
                RuntimeTextFieldRow(
                    title: "命令",
                    text: $remoteCommand,
                    placeholder: "echo amber-remote-task",
                    monospace: true
                )
                RuntimeDivider()
                RuntimeValueRow(
                    title: "连接",
                    subtitle: "只使用默认 Remote SSH profile",
                    value: settingsStore.defaultSSHProfile?.displayName ?? "未选择",
                    systemImage: "terminal"
                )
                RuntimeDivider()
                RuntimeActionRow(
                    title: isRemoteCommandRunning ? "运行中..." : "运行命令",
                    color: isRemoteCommandRunning ? AmberTheme.muted : AmberTheme.accent
                ) {
                    guard !isRemoteCommandRunning else { return }
                    runRemoteCommand()
                }
                if isRemoteCommandRunning {
                    RuntimeDivider()
                    RuntimeActionRow(title: "取消命令", color: AmberTheme.accentRed) {
                        cancelRemoteCommand()
                    }
                }
            }

            if let remoteCommandResult {
                SmokeResultCard(result: remoteCommandResult)
                    .padding(.top, 10)
            }

            let recent = taskStore.recent(kind: .remoteCommand, limit: 3)
            if !recent.isEmpty {
                AmberFormGroup {
                    ForEach(Array(recent.enumerated()), id: \.element.id) { index, task in
                        RemoteTaskRow(task: task)
                        if index < recent.count - 1 {
                            RuntimeDivider()
                        }
                    }
                }
                .padding(.top, 10)
            }

            Text("远程命令只在前台按钮触发；未信任 host、缺少密码或命中危险命令片段时会失败并记录原因。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var embeddedIshAvailable: Bool {
        !IOSEmbeddedIshToolCatalog.supportedToolNames.isEmpty
    }

    private var externalIshAvailable: Bool {
        !IOSIshToolCatalog.supportedToolNames.isEmpty
    }

    private var primaryRuntimeChoices: [IOSTerminalRuntimeKind] {
        [.remoteSSH, .localIOSTools]
    }

    private var runtimeChoices: [IOSTerminalRuntimeKind] {
        guard IOSTerminalBuildPolicy.experimentalRuntimesLinked else {
            return [.remoteSSH, .localIOSTools]
        }
        return IOSTerminalRuntimeKind.allCases
    }

    private var shouldShowRuntimeOptionsRow: Bool {
        IOSTerminalBuildPolicy.experimentalRuntimesLinked ||
            !primaryRuntimeChoices.contains(settingsStore.terminalDefaultRuntime)
    }

    private var experimentalRuntimeSubtitle: String {
        IOSTerminalBuildPolicy.experimentalRuntimesLinked
            ? "Remote Mosh / iSH Experimental 默认隐藏，按需打开"
            : "当前构建没有链接实验 Runtime"
    }

    private var experimentalRuntimeValue: String {
        if !primaryRuntimeChoices.contains(settingsStore.terminalDefaultRuntime) {
            return settingsStore.terminalDefaultRuntime.displayName
        }
        return settingsStore.terminalExperimentalRuntimesEnabled ? "已显示" : "已隐藏"
    }

    private var sshConnectionSummary: String {
        guard let profile = settingsStore.defaultSSHProfile else {
            return "为 Remote SSH 添加 host、端口、用户名和密码"
        }
        let host = profile.host.trimmingCharacters(in: .whitespacesAndNewlines)
        let user = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        let endpoint = host.isEmpty ? "Host 未填写" : "\(host):\(profile.port)"
        return user.isEmpty ? endpoint : "\(user)@\(endpoint)"
    }

    private var sshStatusValue: String {
        switch sshStatus {
        case .idle:
            return settingsStore.defaultSSHProfile?.knownHostSHA256?.isEmpty == false ? "已保存" : "待检查"
        case .testing:
            return "检查中"
        case .needsTrust:
            return "需确认"
        case .success:
            return "已信任"
        case .failure:
            return "异常"
        }
    }

    private var sshStatusAccent: Color {
        switch sshStatus {
        case .success:
            return AmberTheme.accentGreen
        case .needsTrust, .testing:
            return AmberTheme.accentAmber
        case .failure:
            return AmberTheme.accentRed
        case .idle:
            return settingsStore.defaultSSHProfile?.knownHostSHA256?.isEmpty == false
                ? AmberTheme.accentGreen
                : AmberTheme.muted
        }
    }

    private var ishToolsSummary: String {
        if embeddedIshAvailable && externalIshAvailable {
            return "内置 + 交接"
        }
        if embeddedIshAvailable {
            return "内置可用"
        }
        if externalIshAvailable {
            return "外部交接"
        }
        return "未启用"
    }

    private var diagnosticsSummary: String {
        if isRemoteCommandRunning {
            return "远程命令正在运行，可进入查看输出或取消"
        }
        if let remoteCommandResult {
            return "最近远程命令：\(remoteCommandResult.status)"
        }
        if let terminalSmokeResult {
            return "最近 Smoke Test：\(terminalSmokeResult.status)"
        }
        return "验证当前 Runtime，或手动运行一次 Remote SSH 命令"
    }

    private var diagnosticsValue: String {
        if isRemoteCommandRunning {
            return "运行中"
        }
        if let remoteCommandResult {
            return remoteCommandResult.status
        }
        if let terminalSmokeResult {
            return terminalSmokeResult.status
        }
        return "打开"
    }

    private var diagnosticsAccent: Color {
        if isRemoteCommandRunning {
            return AmberTheme.accentAmber
        }
        let status = remoteCommandResult?.status ?? terminalSmokeResult?.status
        switch IOSTerminalJobStatus(rawValue: status ?? "") {
        case .completed:
            return AmberTheme.accentGreen
        case .failed, .timedOut, .cancelled:
            return AmberTheme.accentRed
        case .queued, .running:
            return AmberTheme.accentAmber
        case nil:
            return AmberTheme.accent
        }
    }

    private func runtimeIsEnabled(_ runtime: IOSTerminalRuntimeKind) -> Bool {
        guard IOSTerminalBuildPolicy.selectableRuntimes.contains(runtime) else { return false }
        let tier = IOSTerminalRuntimeCapabilities.capability(for: runtime).tier
        return tier == .stable || settingsStore.terminalExperimentalRuntimesEnabled
    }

    private var isRemoteCommandRunning: Bool {
        guard let remoteCommandResult else { return false }
        return remoteCommandResult.status == IOSTerminalJobStatus.running.rawValue
    }

    private func testTerminalRuntime() {
        guard sharedSettings.isCapabilityGateEnabled(.remoteRuntime) else {
            terminalSmokeResult = IOSTerminalJobSnapshot(
                id: "remote-runtime-disabled",
                runtime: settingsStore.terminalDefaultRuntime,
                status: IOSTerminalJobStatus.failed.rawValue,
                exitCode: nil,
                outputTail: "",
                startedAt: Date(),
                updatedAt: Date(),
                error: IOSCapabilityGate.remoteRuntime.disabledReason
            )
            return
        }
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

    private func runRemoteCommand() {
        let validatedCommand: String
        switch IOSRemoteCommandPolicy.validate(remoteCommand) {
        case .success(let command):
            validatedCommand = command
        case .failure(let message):
            let now = Date()
            let task = taskStore.startTask(
                kind: .remoteCommand,
                title: "Remote SSH · blocked",
                objective: remoteCommand,
                connectionSummary: settingsStore.defaultSSHProfile?.displayName ?? "no profile",
                commandPreview: remoteCommand,
                sourceToolName: "remote_command_run"
            )
            remoteCommandTaskId = task.id
            remoteCommandResult = IOSTerminalJobSnapshot(
                id: task.id,
                runtime: .remoteSSH,
                status: IOSTerminalJobStatus.failed.rawValue,
                exitCode: nil,
                outputTail: message,
                startedAt: now,
                updatedAt: now,
                error: message
            )
            _ = taskStore.updateTask(
                id: task.id,
                status: .failed,
                resultSummary: message,
                logTail: message,
                error: message,
                retryable: true,
                cancelCapability: false
            )
            return
        }

        let profile = settingsStore.defaultSSHProfile
        let password = profile.flatMap { settingsStore.passwordForSSHProfile(id: $0.id) }
        let task = taskStore.startTask(
            kind: .remoteCommand,
            title: "Remote SSH · \(validatedCommand.prefix(34))",
            objective: validatedCommand,
            connectionSummary: profile?.displayName ?? "no profile",
            commandPreview: validatedCommand,
            sourceToolName: "remote_command_run",
            metadata: ["runtime": IOSTerminalRuntimeKind.remoteSSH.rawValue]
        )
        remoteCommandTaskId = task.id
        permissionStore.recordApproval(
            capabilityId: "ios.remote.command",
            toolName: "remote_command_run",
            action: .allowed,
            reason: "User started a foreground Remote SSH command.",
            runId: task.id,
            payloadDigest: "\(validatedCommand.hashValue)"
        )

        Task {
            let started = await IOSTerminalRuntime.shared.startJob(
                command: validatedCommand,
                runtime: .remoteSSH,
                experimentalEnabled: false,
                sshProfile: profile,
                sshPassword: password,
                timeoutSeconds: 60
            )
            remoteCommandResult = started
            remoteCommandJobId = started.id
            _ = taskStore.updateTask(
                id: task.id,
                status: mapTerminalStatus(started.status),
                resultSummary: started.error ?? started.status,
                logTail: started.outputTail,
                error: started.error ?? "",
                retryable: started.status != IOSTerminalJobStatus.completed.rawValue,
                cancelCapability: started.status == IOSTerminalJobStatus.running.rawValue,
                metadata: ["terminal_job_id": started.id]
            )
            guard started.status == IOSTerminalJobStatus.running.rawValue else { return }

            let finished = await IOSTerminalRuntime.shared.waitJob(id: started.id, timeoutSeconds: 65)
            if let finished {
                remoteCommandResult = finished
                _ = taskStore.updateTask(
                    id: task.id,
                    status: mapTerminalStatus(finished.status),
                    resultSummary: finished.error ?? "Remote command finished with status \(finished.status).",
                    logTail: finished.outputTail,
                    error: finished.error ?? "",
                    retryable: finished.status != IOSTerminalJobStatus.completed.rawValue,
                    cancelCapability: false
                )
            }
        }
    }

    private func cancelRemoteCommand() {
        guard let jobId = remoteCommandJobId else { return }
        let stopped = IOSTerminalRuntime.shared.stopJob(id: jobId)
        if let stopped {
            remoteCommandResult = stopped
        }
        if let remoteCommandTaskId {
            _ = taskStore.updateTask(
                id: remoteCommandTaskId,
                status: .cancelled,
                resultSummary: "Remote command cancelled.",
                logTail: stopped?.outputTail ?? "",
                error: stopped?.error ?? IOSSSHError.commandCancelled.localizedDescription,
                retryable: true,
                cancelCapability: false
            )
            permissionStore.recordApproval(
                capabilityId: "ios.remote.command",
                toolName: "remote_command_cancel",
                action: .allowed,
                reason: "User cancelled a foreground Remote SSH command.",
                runId: remoteCommandTaskId
            )
        }
    }

    private func mapTerminalStatus(_ status: String) -> IOSAdvancedTaskStatus {
        switch IOSTerminalJobStatus(rawValue: status) {
        case .queued:
            return .queued
        case .running:
            return .running
        case .completed:
            return .completed
        case .failed:
            return .failed
        case .cancelled:
            return .cancelled
        case .timedOut:
            return .timedOut
        case nil:
            return .failed
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
        guard sharedSettings.isCapabilityGateEnabled(.remoteRuntime) else {
            sshStatus = .failure(IOSCapabilityGate.remoteRuntime.disabledReason)
            return
        }
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
        guard sharedSettings.isCapabilityGateEnabled(.remoteRuntime) else { return false }
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

private struct RuntimeSheetChrome<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let content: Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(title)
                                .font(.title3.weight(.bold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(subtitle)
                                .font(.footnote)
                                .foregroundStyle(AmberTheme.muted)
                                .lineSpacing(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        AmberGlassCircleButton(systemImage: "xmark", accessibilityLabel: "关闭", size: 38, symbolSize: 15) {
                            dismiss()
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 28)
                    .padding(.bottom, 4)

                    content
                }
                .padding(.bottom, 34)
            }
            .scrollIndicators(.hidden)
        }
    }
}

private struct RuntimeStatusCard: View {
    let defaultRuntime: IOSTerminalRuntimeKind
    let sshProfileName: String?
    let embeddedIshAvailable: Bool
    let externalIshAvailable: Bool
    let experimentalEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "terminal")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 34, height: 34)
                    .background(AmberTheme.accentTint, in: Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text("当前执行策略")
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(strategySummary)
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack(spacing: 8) {
                RuntimeStatusMetric(
                    title: "默认",
                    value: defaultRuntime.displayName,
                    color: AmberTheme.accent
                )
                RuntimeStatusMetric(
                    title: "内置 iSH",
                    value: embeddedIshAvailable ? "可回传" : "未链接",
                    color: embeddedIshAvailable ? AmberTheme.accentGreen : AmberTheme.muted
                )
                RuntimeStatusMetric(
                    title: "外部 iSH",
                    value: externalIshAvailable ? "交接" : "不可用",
                    color: externalIshAvailable ? AmberTheme.accentAmber : AmberTheme.muted
                )
            }
        }
        .padding(14)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }

    private var strategySummary: String {
        let profile = sshProfileName ?? "未选择 SSH Profile"
        let experimental = experimentalEnabled ? "实验 Runtime 已显示" : "实验 Runtime 已隐藏"
        return "\(profile) · \(experimental)。聊天中的 iSH 工具会单独走前台审批。"
    }
}

private struct RuntimeStatusMetric: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(color.opacity(0.09), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct RuntimeChoiceRow: View {
    let runtime: IOSTerminalRuntimeKind
    let isSelected: Bool
    let isEnabled: Bool
    let isRecommended: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: runtimeIcon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(isEnabled ? runtimeColor : AmberTheme.muted2)
                    .frame(width: 30, height: 30)
                    .background((isEnabled ? runtimeColor : AmberTheme.muted2).opacity(0.10), in: Circle())

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(runtime.displayName)
                            .font(.body.weight(.medium))
                            .foregroundStyle(isEnabled ? AmberTheme.foreground : AmberTheme.muted2)
                        if isRecommended {
                            RuntimePill(text: "推荐", color: AmberTheme.accent)
                        }
                        RuntimePill(text: runtimeTier == .stable ? "稳定" : "实验", color: runtimeTier == .stable ? AmberTheme.accentGreen : AmberTheme.accentAmber)
                        if !isEnabled {
                            RuntimePill(text: "未启用", color: AmberTheme.muted)
                        }
                    }

                    Text(runtimeSummary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(AmberTheme.accent)
                }
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: isEnabled ? 0.985 : 1, haptic: isEnabled ? .selection : nil))
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.62)
    }

    private var runtimeTier: IOSTerminalRuntimeTier {
        IOSTerminalRuntimeCapabilities.capability(for: runtime).tier
    }

    private var runtimeSummary: String {
        switch runtime {
        case .remoteSSH: "稳定远程命令主线；需要 SSH Profile、密码和 Host 信任"
        case .localIOSTools: "本地轻量验证与沙盒工具；不提供 Linux shell"
        case .remoteMosh: "预留的移动会话方向；当前不建议作为默认环境"
        case .ishExperimental: "内置 iSH 短命令 runner；无 PTY、stdin 和长会话"
        }
    }

    private var runtimeIcon: String {
        switch runtime {
        case .remoteSSH: "desktopcomputer"
        case .localIOSTools: "iphone"
        case .remoteMosh: "antenna.radiowaves.left.and.right"
        case .ishExperimental: "shippingbox"
        }
    }

    private var runtimeColor: Color {
        switch runtime {
        case .remoteSSH: AmberTheme.accent
        case .localIOSTools: AmberTheme.accentGreen
        case .remoteMosh: AmberTheme.accentCyan
        case .ishExperimental: AmberTheme.accentAmber
        }
    }
}

private struct RuntimeToggleRow: View {
    let title: String
    let subtitle: String
    let isOn: Bool
    let isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(isEnabled ? AmberTheme.foreground : AmberTheme.muted2)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                RuntimeSwitch(isOn: isOn)
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.58)
    }
}

private struct RuntimeNavigationRow: View {
    let title: String
    let subtitle: String
    let value: String
    let systemImage: String
    var accent: Color = AmberTheme.accent
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(accent)
                    .frame(width: 30, height: 30)
                    .background(accent.opacity(0.10), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted2)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 7) {
                    Text(value)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(accent)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)

                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.985, haptic: .selection))
    }
}

private struct RuntimeValueRow: View {
    let title: String
    let subtitle: String
    let value: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }
}

private struct RuntimeInfoRow: View {
    let title: String
    let subtitle: String
    let value: String
    let systemImage: String
    var accent: Color = AmberTheme.accentAmber

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(accent)
                .frame(width: 28, height: 28)
                .background(accent.opacity(0.10), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(accent)
                .lineLimit(2)
                .multilineTextAlignment(.trailing)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }
}

private struct RemoteTaskRow: View {
    let task: IOSAdvancedTaskRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(task.commandPreview.isEmpty ? task.title : task.commandPreview)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(task.status.title) · \(task.connectionSummary)\n\(task.compactSummary)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 62)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }

    private var iconName: String {
        switch task.status {
        case .completed: "checkmark.circle.fill"
        case .failed, .timedOut, .interrupted: "exclamationmark.triangle.fill"
        case .cancelled: "xmark.circle.fill"
        default: "terminal.fill"
        }
    }

    private var iconColor: Color {
        switch task.status {
        case .completed: AmberTheme.accentGreen
        case .failed, .timedOut, .interrupted: AmberTheme.accentRed
        case .cancelled: AmberTheme.muted2
        default: AmberTheme.accentAmber
        }
    }
}

private struct RuntimeTextFieldRow: View {
    let title: String
    @Binding var text: String
    var placeholder: String
    var monospace = false
    var keyboardType: UIKeyboardType = .default

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            TextField(placeholder, text: $text)
                .font(monospace ? .system(.subheadline, design: .monospaced) : .body)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .keyboardType(keyboardType)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct RuntimeSecureFieldRow: View {
    let title: String
    @Binding var text: String
    var placeholder: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            SecureField(placeholder, text: $text)
                .font(.system(.subheadline, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.never)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct RuntimeActionRow: View {
    let title: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.semibold))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity)
                .frame(minHeight: 52)
        }
        .buttonStyle(.plain)
    }
}

private struct RuntimeDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SmokeResultCard: View {
    let result: IOSTerminalJobSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 9) {
                Text(result.runtime.displayName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                RuntimePill(text: result.status, color: result.exitCode == 0 ? AmberTheme.accentGreen : AmberTheme.accentCyan)
            }

            Text("退出码 \(result.exitCode.map(String.init) ?? "…")")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)

            if !result.outputTail.isEmpty {
                Text(result.outputTail)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .textSelection(.enabled)
            }

            if let error = result.error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
            }
        }
        .padding(14)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
    }
}

private struct HostFingerprintCard: View {
    let status: RuntimeEnvironmentView.SSHStatus
    let onTrust: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch status {
            case .idle:
                FingerprintTitle(systemImage: "checkmark.shield", title: "尚未验证 Host 指纹", color: AmberTheme.foreground)
                Text("连接前需检查服务器可达性与 host key 指纹。未信任或指纹不匹配时不会发送密码。")
                    .fingerprintDescription()
            case .testing:
                FingerprintTitle(systemImage: "arrow.triangle.2.circlepath", title: "正在连接并获取 host key...", color: AmberTheme.foreground)
                Text("读取服务器公钥的 SHA256 指纹，请稍候。")
                    .fingerprintDescription()
            case .needsTrust(_, let fingerprint):
                FingerprintTitle(systemImage: "exclamationmark.triangle", title: "首次连接此 Host", color: AmberTheme.accentAmber)
                FingerprintHash(label: "SERVER KEY · SHA256", value: fingerprint)
                Text("确认这是你信任的服务器后，显式点击「信任此 Host」才会保存指纹。")
                    .fingerprintDescription()
                HStack {
                    Button {
                        onTrust()
                    } label: {
                        Label("信任此 Host", systemImage: "checkmark")
                    }
                    .buttonStyle(RuntimeFilledButtonStyle())
                    Button("取消", action: onRetry)
                        .buttonStyle(RuntimeGlassButtonStyle())
                }
            case .success(let message):
                FingerprintTitle(systemImage: "checkmark.shield", title: "Host 已信任", color: AmberTheme.accentGreen)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentGreen)
            case .failure(let message):
                FingerprintTitle(systemImage: "xmark.shield", title: "Host 指纹不匹配或连接失败", color: AmberTheme.accentRed)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
            }
        }
        .padding(14)
        .background(cardBackground, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(cardStroke, lineWidth: isFailure ? 1 : 0.5)
        }
        .padding(.horizontal, 16)
    }

    private var isFailure: Bool {
        if case .failure = status {
            return true
        }
        return false
    }

    private var cardBackground: Color {
        isFailure ? AmberTheme.accentRed.opacity(0.05) : AmberTheme.surface
    }

    private var cardStroke: Color {
        isFailure ? AmberTheme.accentRed : AmberTheme.borderSoft
    }
}

private struct RuntimeMatrixCard: View {
    let capability: IOSTerminalRuntimeCapability

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(spacing: 8) {
                Text(capability.runtime.displayName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                RuntimePill(text: capability.tier.rawValue, color: capability.tier == .stable ? AmberTheme.accentGreen : AmberTheme.muted)
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 7) {
                RuntimeCapabilityLine(title: "外部 CLI", state: capability.supportsExternalCLIByDefault)
                RuntimeCapabilityLine(title: "PTY", state: capability.supportsPTY)
                RuntimeCapabilityLine(title: "安装软件", state: capability.supportsPackageInstall)
                RuntimeCapabilityLine(title: "长任务", state: capability.supportsLongRunningJobs)
                RuntimeCapabilityLine(title: "交互登录", state: capability.supportsInteractiveLogin)
                RuntimeCapabilityLine(title: "文件同步", state: capability.supportsFileSync)
                RuntimeCapabilityLine(title: "上架安全", state: capability.appStoreSafeByDefault)
            }

            Text("License: \(capability.licenseClass.rawValue)")
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(AmberTheme.muted2)
        }
        .padding(14)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
    }
}

private struct RuntimeCapabilityLine: View {
    let title: String
    let state: Bool

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: state ? "checkmark.circle.fill" : "xmark.circle")
                .foregroundStyle(state ? AmberTheme.accentGreen : AmberTheme.muted2)
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
        }
    }
}

private struct FingerprintTitle: View {
    let systemImage: String
    let title: String
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
            Text(title)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(color)
    }
}

private struct FingerprintHash: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted2)
            Text(value)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground2)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct RuntimePill: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(color.opacity(0.13), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

private struct RuntimeSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private struct RuntimeFilledButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .frame(height: 38)
            .padding(.horizontal, 18)
            .background(AmberTheme.accent, in: Capsule())
            .opacity(isEnabled ? 1 : 0.42)
            .scaleEffect(configuration.isPressed && isEnabled ? 0.97 : 1)
    }
}

private struct RuntimeGlassButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AmberTheme.accent)
            .frame(height: 38)
            .padding(.horizontal, 18)
            .background(AmberTheme.glass, in: Capsule())
            .overlay {
                Capsule()
                    .stroke(.white.opacity(0.65), lineWidth: 0.5)
            }
            .opacity(isEnabled ? 1 : 0.42)
            .scaleEffect(configuration.isPressed && isEnabled ? 0.97 : 1)
    }
}

private extension Text {
    func fingerprintDescription() -> some View {
        self
            .font(.caption)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
    }

    func runtimeFootnote() -> some View {
        self
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}
