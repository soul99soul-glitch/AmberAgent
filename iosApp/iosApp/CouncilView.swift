import SwiftUI
import Shared

struct CouncilView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var taskStore = IOSAdvancedTaskStore.shared

    init(sharedSettings: IOSSharedSettingsStore) {
        self.sharedSettings = sharedSettings
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
        HStack(spacing: 10) {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer(minLength: 8)

            VStack(spacing: 2) {
                Text("模型议会")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("多模型协作评审")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 8)

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "成员设置", size: 44, symbolSize: 18) {
                router.navigate(to: .councilSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("模型议会会让多个席位从不同角度评审同一个问题。你可以手动启动一次，也可以管理自定义席位。")
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
            AmberSectionLabel(text: "启动")
            AmberFormGroup {
                Button {
                    router.navigate(to: .council)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "bubble.left.and.bubble.right.fill")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 32, height: 32)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                        VStack(alignment: .leading, spacing: 2) {
                            Text("开始议会")
                                .font(.body.weight(.medium))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("进入实时议会，邀请不同席位接力讨论。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .frame(minHeight: 58)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var recentTasksSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "最近讨论")
            AmberFormGroup {
                let tasks = taskStore.recent(kind: .modelCouncil, limit: 5)
                if tasks.isEmpty {
                    Text("暂无模型议会任务。开始议会后，这里会显示席位、预算、状态和结论。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(tasks.enumerated()), id: \.element.id) { index, task in
                        CouncilTaskRow(task: task)
                        if index < tasks.count - 1 {
                            CouncilTaskDivider()
                        }
                    }
                }
            }
        }
    }
}

private struct CouncilTaskRow: View {
    let task: IOSAdvancedTaskRecord

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
                Text("\(task.status.title) · \(task.budgetSummary)\n\(task.compactSummary)")
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
        case .completed: "checkmark.seal.fill"
        case .failed, .timedOut, .interrupted: "exclamationmark.triangle.fill"
        case .cancelled: "xmark.circle.fill"
        default: "bubble.left.and.bubble.right.fill"
        }
    }

    private var iconColor: Color {
        switch task.status {
        case .completed: AmberTheme.accentGreen
        case .failed, .timedOut, .interrupted: AmberTheme.accentRed
        case .cancelled: AmberTheme.muted2
        default: AmberTheme.accent
        }
    }
}

private struct CouncilTaskDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

#Preview {
    NavigationStack {
        CouncilView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
