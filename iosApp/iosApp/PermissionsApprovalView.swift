import SwiftUI

struct PermissionsApprovalView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.globalAutoApproval") private var globalAutoApproval = false
    @AppStorage("app.amber.ios.highRiskAutoApproval") private var highRiskAutoApproval = false
    @State private var showsHighRiskConfirmation = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header

                    Text("控制 Agent 能调用哪些系统权限，以及工具调用要不要每次都经你批准。")
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
        .alert("确认开启高风险自动批准？", isPresented: $showsHighRiskConfirmation) {
            Button("取消", role: .cancel) {}
            Button("开启", role: .destructive) {
                highRiskAutoApproval = true
            }
        } message: {
            Text("开启后，若全局自动批准也开启，AmberAgent 可无人值守执行原本需审批的高风险工具。ask_user 仍会等待你的回答。")
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
            AmberSectionLabel(text: "批准策略")
            AmberFormGroup {
                PermissionApprovalToggleRow(
                    systemImage: "checkmark.circle",
                    iconColor: AmberTheme.accentGreen,
                    title: "全局自动批准",
                    subtitle: "自动批准常规工具调用。和高风险自动批准同时开启时，除需要你回答的 ask_user 外，审批工具会无人值守执行。",
                    isOn: globalAutoApproval
                ) {
                    globalAutoApproval.toggle()
                }

                Divider()
                    .overlay(AmberTheme.borderSoft)
                    .padding(.leading, 58)

                PermissionApprovalToggleRow(
                    systemImage: "exclamationmark.triangle",
                    iconColor: AmberTheme.accentAmber,
                    title: "高风险自动批准",
                    subtitle: "配合全局自动批准，允许短信、拨号、文件写入、终端/屏幕动作、SubAgent 内部工具等审批工具无人值守执行。",
                    isOn: highRiskAutoApproval
                ) {
                    if highRiskAutoApproval {
                        highRiskAutoApproval = false
                    } else {
                        showsHighRiskConfirmation = true
                    }
                }
            }

            Text("ask_user 永远会等待你的回答，不受自动批准影响。仅在你完全信任当前设备、模型与会话时，才建议开启高风险自动批准。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }
}

private struct PermissionApprovalToggleRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 28, height: 28)

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

                AmberSwitch(isOn: isOn)
            }
            .frame(minHeight: 64)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct AmberSwitch: View {
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
            .accessibilityLabel(isOn ? "已开启" : "已关闭")
    }
}
