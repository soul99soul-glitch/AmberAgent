import SwiftUI

struct PermissionsApprovalView: View {
    @Bindable var permissionStore: IOSPermissionStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private var approvalCapabilities: [IOSPlatformCapability] {
        [
            "ios.files.selected_read",
            "ios.workspace.file_read",
            "ios.workspace.file_write",
            "ios.agent.memory_write",
            "ios.network.search_tools",
            "ios.mcp.tool_call",
            "ios.webmount.browser",
            "ios.remote.command",
            "ios.agent.subagent_dispatch",
            "ios.agent.model_council_run"
        ].compactMap { id in
            IOSCapabilityRegistry.capabilities.first { $0.id == id }
        }
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header

                    Text("管理 Agent 使用文件、记忆和网页会话前是否需要确认。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)

                    systemPermissionsSection
                    approvalPolicySection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            normalizeDisplayedPolicies()
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("权限与批准")
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

    private var systemPermissionsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "系统权限")
            AmberFormGroup {
                AmberFormRow(
                    systemImage: "checkmark.shield",
                    iconColor: AmberTheme.accentCyan,
                    title: "权限与能力",
                    subtitle: "按能力管理：文件 / 媒体 / 健康 / 定位 / 通讯录等",
                    showsChevron: true
                ) {
                    router.navigate(to: .capabilities)
                }
            }
        }
    }

    private var approvalPolicySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具批准")
            AmberFormGroup {
                ForEach(Array(approvalCapabilities.enumerated()), id: \.element.id) { index, capability in
                    PermissionPolicyRow(
                        capability: capability,
                        policy: displayedPolicy(for: capability),
                        availablePolicies: displayedPolicies(for: capability),
                        decisionSummary: displayedDecisionSummary(for: capability),
                        lastApprovalSummary: displayedLastApprovalSummary(for: capability)
                    ) { policy in
                        permissionStore.setPolicy(policy, for: capability)
                    }

                    if index < approvalCapabilities.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 58)
                    }
                }
            }

        }
    }

    private func displayedPolicies(for capability: IOSPlatformCapability) -> [IOSAgentPermissionPolicy] {
        permissionStore.availablePolicies(for: capability).filter { $0 != .allowOncePerRun }
    }

    private func displayedPolicy(for capability: IOSPlatformCapability) -> IOSAgentPermissionPolicy {
        let policy = permissionStore.policy(for: capability)
        return policy == .allowOncePerRun ? .askEveryTime : policy
    }

    private func displayedDecisionSummary(for capability: IOSPlatformCapability) -> String {
        switch displayedPolicy(for: capability) {
        case .disabled:
            "已禁用"
        case .askEveryTime:
            "使用前询问"
        case .allowOncePerRun:
            "使用前询问"
        }
    }

    private func displayedLastApprovalSummary(for capability: IOSPlatformCapability) -> String? {
        guard let record = permissionStore.latestApproval(for: capability) else { return nil }
        return "最近：\(record.action.title) · \(record.toolName)"
    }

    private func normalizeDisplayedPolicies() {
        for capability in approvalCapabilities where permissionStore.policy(for: capability) == .allowOncePerRun {
            permissionStore.setPolicy(.askEveryTime, for: capability)
        }
    }
}

private struct PermissionPolicyRow: View {
    let capability: IOSPlatformCapability
    let policy: IOSAgentPermissionPolicy
    let availablePolicies: [IOSAgentPermissionPolicy]
    let decisionSummary: String
    let lastApprovalSummary: String?
    let onSelect: (IOSAgentPermissionPolicy) -> Void

    var body: some View {
        Menu {
            ForEach(availablePolicies) { option in
                Button(option.displayTitle) {
                    onSelect(option)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 3) {
                    Text(capability.title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(permissionSummary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(policy.displayTitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(policy == .disabled ? AmberTheme.muted : AmberTheme.accent)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(policy == .disabled ? AmberTheme.surface2 : AmberTheme.accentTint, in: Capsule())

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 64)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .disabled(availablePolicies.count <= 1)
        .opacity(availablePolicies.count <= 1 ? 0.72 : 1)
        .accessibilityLabel("\(capability.title)，\(policy.displayTitle)")
    }

    private var systemImage: String {
        switch capability.risk {
        case .normal: "checkmark.circle"
        case .sensitive: "hand.raised"
        case .high: "exclamationmark.triangle"
        }
    }

    private var iconColor: Color {
        switch capability.risk {
        case .normal: AmberTheme.accentGreen
        case .sensitive: AmberTheme.accentAmber
        case .high: AmberTheme.accentRed
        }
    }

    private var permissionSummary: String {
        switch capability.id {
        case "ios.files.selected_read":
            return "读取你主动选择的文件 · \(decisionSummary)"
        case "ios.workspace.file_read":
            return approvalAware("读取 Workspace 文件和 Artifact")
        case "ios.workspace.file_write":
            return approvalAware("写入 Workspace 文件或删除 Artifact")
        case "ios.agent.memory_write":
            return "新增、修改或删除记忆 · \(decisionSummary)"
        case "ios.network.search_tools":
            return approvalAware("搜索和网页读取")
        case "ios.mcp.tool_call":
            return approvalAware("外部 MCP 工具调用")
        case "ios.webmount.browser":
            return approvalAware("读取或操作已打开的网页会话")
        case "ios.remote.command":
            return approvalAware("Remote SSH 单条命令")
        case "ios.agent.subagent_dispatch":
            return approvalAware("委派给受限工具范围的角色")
        case "ios.agent.model_council_run":
            return approvalAware("启动多席位讨论")
        default:
            return "\(capability.requestKind.title) · \(decisionSummary)"
        }
    }

    private func approvalAware(_ prefix: String) -> String {
        if let lastApprovalSummary {
            return "\(prefix) · \(decisionSummary) · \(lastApprovalSummary)"
        }
        return "\(prefix) · \(decisionSummary)"
    }
}

private extension IOSAgentPermissionPolicy {
    var displayTitle: String {
        switch self {
        case .disabled: "禁用"
        case .askEveryTime: "每次询问"
        case .allowOncePerRun: "每次询问"
        }
    }
}
