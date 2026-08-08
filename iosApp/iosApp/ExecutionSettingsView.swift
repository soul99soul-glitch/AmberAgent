import SwiftUI
import Shared

struct ExecutionSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @AppStorage(IOSExecutionPreferenceKeys.liveActivity) private var liveActivity = true
    @AppStorage(IOSExecutionPreferenceKeys.chatMaxToolResumeCount)
    private var chatMaxToolResumeCount = SettingsStore.defaultChatMaxToolResumeCount
    @State private var taskStore = IOSAdvancedTaskStore.shared

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    runSection
                    toolLoopSection
                    recentTasksSection
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

            Text("运行环境")
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

    private var runSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "环境配置")
            AmberFormGroup {
                ExecutionNavigationRow(
                    systemImage: "terminal",
                    title: "Runtime 与任务",
                    subtitle: "默认 Runtime、Remote SSH、iSH 工具与任务记录"
                ) {
                    router.navigate(to: .sandbox)
                }
            }
        }
    }

    private var toolLoopSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "工具循环")
            AmberFormGroup {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(width: 28, height: 28)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("单轮工具调用上限")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("达到上限后模型会用现有信息总结收尾，并说明未完成的步骤")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Stepper(
                        "\(SettingsStore.clampChatMaxToolResumeCount(chatMaxToolResumeCount)) 次",
                        value: $chatMaxToolResumeCount,
                        in: SettingsStore.chatMaxToolResumeCountRange
                    )
                    .fixedSize()
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
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
                    isOn: liveActivity
                ) {
                    liveActivity.toggle()
                    if !liveActivity {
                        Task {
                            await AgentLiveActivityController.shared.stopCurrent()
                        }
                    }
                }

            }
        }
    }

    private var recentTasksSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "最近任务")
            AmberFormGroup {
                let tasks = taskStore.recent(limit: 6)
                if tasks.isEmpty {
                    Text("暂无高级执行任务。SubAgent、模型议会和远程命令运行后会出现在这里。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(tasks.enumerated()), id: \.element.id) { index, task in
                        ExecutionTaskRow(task: task)
                        if index < tasks.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
        }
    }

}

private struct ExecutionTaskRow: View {
    let task: IOSAdvancedTaskRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(task.title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(task.kind.title) · \(task.status.title) · \(task.compactSummary)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(task.status.title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(iconColor)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }

    private var iconName: String {
        switch task.kind {
        case .subAgent: "person.2.wave.2.fill"
        case .modelCouncil: "bubble.left.and.bubble.right.fill"
        case .remoteCommand: "terminal.fill"
        case .toolApproval: "hand.raised.fill"
        }
    }

    private var iconColor: Color {
        switch task.status {
        case .completed: AmberTheme.accentGreen
        case .failed, .timedOut, .interrupted: AmberTheme.accentRed
        case .cancelled: AmberTheme.muted2
        case .approvalRequired: AmberTheme.accentAmber
        default: AmberTheme.accent
        }
    }
}

private struct ExecutionNavigationRow: View {
    let systemImage: String
    let title: String
    var subtitle: String?
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
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ExecutionToggleRow: View {
    let systemImage: String
    let title: String
    var subtitle: String?
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
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
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
