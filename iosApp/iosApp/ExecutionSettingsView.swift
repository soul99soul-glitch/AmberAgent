import SwiftUI
import Shared

struct ExecutionSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSExecutionPreferenceKeys.liveActivity) private var liveActivity = true

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    runSection
                    liveActivitySection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
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

            Text("执行与任务")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("管理聊天生成时的执行展示、工具调用和实时活动。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var runSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具执行")
            AmberFormGroup {
                ExecutionStatusRow(
                    systemImage: "terminal",
                    title: "可用工具",
                    subtitle: "聊天可以使用搜索、记忆、网页、MCP、模型议会和子代理工具。",
                    value: "可用",
                    valueColor: AmberTheme.accentGreen
                )

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "checkmark.shield",
                    title: "批准策略",
                    subtitle: "涉及文件、记忆写入和网页会话的动作会在前台请求确认。",
                    value: "可用",
                    valueColor: AmberTheme.accentGreen
                )
            }
        }
    }

    private var liveActivitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "灵动岛")
            AmberFormGroup {
                ExecutionToggleRow(
                    systemImage: "capsule",
                    title: "灵动岛实时活动",
                    subtitle: "生成中在系统实时活动里显示简短状态。",
                    isOn: liveActivity
                ) {
                    liveActivity.toggle()
                    if !liveActivity {
                        Task {
                            await AgentLiveActivityController.shared.stopCurrent()
                        }
                    }
                }

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "lock",
                    title: "隐藏敏感内容",
                    subtitle: "实时活动只显示概览，不显示完整聊天正文。",
                    value: "默认脱敏",
                    valueColor: AmberTheme.accentGreen
                )
            }

            ExecutionNote("实时活动由 ActivityKit 驱动；系统级权限不可用时，控制器会保持静默。")
        }
    }

}

private struct ExecutionStatusRow: View {
    let systemImage: String
    let title: String
    let subtitle: String
    let value: String
    var valueColor: Color = AmberTheme.muted

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(valueColor)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(valueColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct ExecutionToggleRow: View {
    let systemImage: String
    let title: String
    let subtitle: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                ExecutionSwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ExecutionSwitch: View {
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

private struct ExecutionDivider: View {
    var leading: CGFloat

    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, leading)
    }
}

private struct ExecutionNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}
