import SwiftUI

struct PermissionsApprovalView: View {
    @Bindable var permissionStore: IOSPermissionStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private var approvalCapabilities: [IOSPlatformCapability] {
        [
            "ios.files.selected_read"
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

                    Text("控制已实现 iOS Agent 工具的使用策略，并进入完整能力页申请系统权限。")
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
            AmberSectionLabel(text: "已实现工具策略")
            AmberFormGroup {
                ForEach(Array(approvalCapabilities.enumerated()), id: \.element.id) { index, capability in
                    PermissionPolicyRow(
                        capability: capability,
                        policy: displayedPolicy(for: capability),
                        availablePolicies: displayedPolicies(for: capability),
                        decisionSummary: displayedDecisionSummary(for: capability)
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

            Text("当前只有 file_read_selected 这条 Agent 本地工具执行链会读取这里的策略。照片、定位、相机、通知等系统权限仍在完整能力页申请或查看；没有 iOS executor 的能力不会在这里承诺可自动批准。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
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
            "Agent use disabled"
        case .askEveryTime:
            "Foreground user action required"
        case .allowOncePerRun:
            "Foreground user action required"
        }
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
        let base = capability.modelToolNames.isEmpty
            ? capability.requestKind.title
            : capability.modelToolNames.joined(separator: ", ")
        return "\(base) · \(decisionSummary)"
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
