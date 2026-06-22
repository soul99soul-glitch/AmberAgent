import SwiftUI

struct SubAgentsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var runner = SubAgentRunner()
    @State private var taskObjective = "请用只读方式快速审计当前任务，并返回要点。"
    @State private var selectedRoleId = "explorer"

    private var builtInRoles: [SubAgentRoleSummary] {
        IOSSubAgentRoleCatalog.builtIns.map {
            .init(name: $0.name, handle: $0.id, summary: $0.summary)
        }
    }

    /// P4 parity fix (truth_matrix: subagent_standalone.exec_real): the
    /// standalone page dispatches through `SubAgentRunner.runViaEngine`
    /// (IOSAgentToolEngine path), not the legacy `run` (SubAgentManager/KMP).
    /// Both buttons call this thin wrapper, which delegates to the testable
    /// `dispatchStandalone` seam so the engine routing is verified end-to-end.
    @MainActor
    private func runStandaloneViaEngine(objective: String, roleId: String) async -> String {
        await Self.dispatchStandalone(
            objective: objective,
            roleId: roleId,
            sharedSettings: sharedSettings,
            runner: runner
        )
    }

    /// Testable dispatch seam for the standalone subagent page. Resolves the
    /// provider + model from the shared settings store (canonical source the
    /// formal Provider UI writes and chat reads — fixes the dual-source bug: the
    /// legacy ProviderRegistryStore returned a key-LESS provider and ignored the
    /// selected model) and runs via `IOSAgentToolEngine` (`runViaEngine`), never
    /// the legacy KMP `run`. The standalone page has no parent tools, so
    /// `parentToolExecutors` is empty; honest degradation when nothing usable.
    /// `test_subagent_standalone_usesEnginePath` drives THIS, so a regression that
    /// re-routes the page to the legacy path is actually caught.
    @MainActor
    static func dispatchStandalone(
        objective: String,
        roleId: String,
        sharedSettings: IOSSharedSettingsStore,
        runner: SubAgentRunner,
        provider: any IOSAgentTextProvider = OpenAIKmpProviderAdapter()
    ) async -> String {
        let modelId = sharedSettings.resolveCurrentModelId()
        guard let resolved = sharedSettings.resolveCurrentProviderSetting(), !modelId.isEmpty else {
            return "SubAgent 不可用：请先配置服务商 API Key 与模型。"
        }
        return await runner.runViaEngine(
            objective: objective,
            roleId: roleId,
            providerSetting: resolved,
            modelId: modelId,
            parentToolExecutors: [:],
            provider: provider
        )
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        runnerSection
                        recentTasksSection
                        builtInRolesSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("子代理")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("多角色分工处理任务")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("子代理可以把任务拆给不同专长的角色处理。你可以在这里试运行一次，也可以进入角色详情调整提示词。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var runnerSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "任务")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 8) {
                    Menu {
                        ForEach(IOSSubAgentRoleCatalog.builtIns) { role in
                            Button(role.name) {
                                selectedRoleId = role.id
                            }
                        }
                    } label: {
                        HStack {
                            Text("角色")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                            Spacer()
                            Text(IOSSubAgentRoleCatalog.resolve(roleId: selectedRoleId).name)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.accent)
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.muted2)
                        }
                        .frame(minHeight: 38)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(AmberTheme.borderSoft)

                    TextField("输入要委派的任务", text: $taskObjective, axis: .vertical)
                        .font(.body)
                        .lineLimit(2...5)
                        .textFieldStyle(.plain)

                    HStack(spacing: 12) {
                        if runner.isRunning {
                            ProgressView()
                            Text("正在启动子代理…")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                        } else {
                            Button {
                                let objective = taskObjective.trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !objective.isEmpty else { return }
                                Task {
                                    runner.lastRunResult = await runStandaloneViaEngine(objective: objective, roleId: selectedRoleId)
                                }
                            } label: {
                                Label("启动任务", systemImage: "person.2.wave.2.fill")
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(AmberTheme.accent)
                            }
                            .buttonStyle(.plain)
                        }

                        Button { runner.cancelCurrentRun() } label: {
                            Label("取消", systemImage: "xmark.circle")
                                .font(.body.weight(.medium))
                                .foregroundStyle(runner.isRunning ? AmberTheme.accentAmber : AmberTheme.muted)
                        }
                        .buttonStyle(.plain)
                    }

                    if runner.lastRunResult != "(未运行)" {
                        Text(runner.lastRunResult)
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
    }

    private var recentTasksSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "最近任务")
            AmberFormGroup {
                let tasks = runner.recentTasks
                if tasks.isEmpty {
                    Text("暂无 SubAgent 任务。启动上方任务后，这里会显示状态、角色、工具范围和结果。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(tasks.enumerated()), id: \.element.id) { index, task in
                        SubAgentTaskRow(task: task) {
                            selectedRoleId = task.roleId ?? "explorer"
                            taskObjective = task.objective
                            Task {
                                runner.lastRunResult = await runStandaloneViaEngine(
                                    objective: task.objective,
                                    roleId: task.roleId ?? "explorer"
                                )
                            }
                        }
                        if index < tasks.count - 1 {
                            SubAgentDivider()
                        }
                    }
                }
            }
        }
    }

    private var builtInRolesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置角色")
            AmberFormGroup {
                ForEach(Array(builtInRoles.enumerated()), id: \.element.id) { index, role in
                    Button {
                        router.navigate(to: .subAgentRole(name: role.name, roleId: role.handle))
                    } label: {
                        SubAgentRoleRowContent(role: role)
                    }
                    .buttonStyle(.plain)

                    if index < builtInRoles.count - 1 {
                        SubAgentDivider()
                    }
                }
            }
        }
    }
}

private struct SubAgentRoleSummary: Identifiable {
    let id = UUID()
    let name: String
    let handle: String
    let summary: String
}

private struct SubAgentRoleRowContent: View {
    let role: SubAgentRoleSummary

    var body: some View {
        HStack(spacing: 12) {
            Text(String(role.name.prefix(1)))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 32, height: 32)
                .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(role.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("@\(role.handle)")
                        .font(.caption.monospaced())
                        .foregroundStyle(AmberTheme.muted2)
                }

                Text(role.summary)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 62)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SubAgentTaskRow: View {
    let task: IOSAdvancedTaskRecord
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(task.title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if task.canRetry {
                Button(action: onRetry) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("重试 SubAgent 任务")
            } else {
                Text(task.status.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(iconColor)
            }
        }
        .frame(minHeight: 62)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }

    private var summary: String {
        let scope = task.toolScope.isEmpty ? "无外部工具" : task.toolScope.joined(separator: ", ")
        return "\(task.status.title) · @\(task.roleId ?? "subagent") · \(scope)\n\(task.compactSummary)"
    }

    private var iconName: String {
        switch task.status {
        case .completed: "checkmark.circle.fill"
        case .failed, .timedOut, .interrupted: "exclamationmark.triangle.fill"
        case .cancelled: "xmark.circle.fill"
        default: "clock.fill"
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

private struct SubAgentDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

#Preview {
    NavigationStack {
        SubAgentsView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
