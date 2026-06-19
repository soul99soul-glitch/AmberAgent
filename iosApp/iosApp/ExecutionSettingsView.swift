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
                    operationPreviewSection
                    runSection
                    liveActivitySection
                    stabilitySection
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
        Text("这里仅保留已经被 iOS Chat 运行链消费的实时活动开关；其他 Android/KMP 执行字段先作为只读缺口映射展示。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var operationPreviewSection: some View {
        let rt = sharedSettings.agentRuntime
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "执行字段（KMP 默认值 · 只读）")
            AmberFormGroup {
                ExecutionStatusRow(
                    systemImage: "rectangle.stack",
                    title: "操作预览模式",
                    subtitle: "KMP agentRuntime.operationPreviewMode；iOS 工具运行时间线 UI 尚未消费",
                    value: rt.operationPreviewMode.name
                )

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "sparkles",
                    title: "生成式 UI",
                    subtitle: "KMP agentRuntime.generativeUi；iOS Chat widget 渲染链尚未消费",
                    value: rt.generativeUi.enabled ? "启用" : "关闭"
                )

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "arrow.clockwise",
                    title: "工具循环上限",
                    subtitle: "KMP agentRuntime.maxToolLoopSteps；iOS 工具循环调度器未消费",
                    value: "\(rt.maxToolLoopSteps)"
                )
            }
        }
    }

    private var runSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "运行链路")
            AmberFormGroup {
                // [Slice 3] 工具执行已接：ChatViewModel.makeTextGenerationParams 注入
                // mcp_call/subagent_dispatch/model_council_run 工具声明，onComplete 检测
                // pending 调用后 dispatch（IOSMcpManager.callTool/SubAgentRunner.run/
                // CouncilRunner.run），结果回填并 resume stream。逻辑闭环；真链路运行时
                // 验证需 API key（编译级+逻辑已就绪）。
                ExecutionStatusRow(
                    systemImage: "terminal",
                    title: "工具执行",
                    subtitle: "已注入 mcp_call / subagent_dispatch / model_council_run 工具；onComplete dispatch + resume。真链路验证需 API key",
                    value: "已接(逻辑)",
                    valueColor: AmberTheme.accentGreen
                )

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "checkmark.shield",
                    title: "批准策略",
                    subtitle: "selected-file read 使用 IOSPermissionStore；其他工具策略等 executor 存在后再开放",
                    value: "已接一项",
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
                    subtitle: "Chat 生成与 selected-file 读取会读取这个开关",
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
                    subtitle: "AgentActivityPresentation 只允许白名单状态文案；没有正文暴露开关",
                    value: "默认脱敏",
                    valueColor: AmberTheme.accentGreen
                )
            }

            ExecutionNote("实时活动由 ActivityKit 驱动；系统级权限不可用时，控制器会保持静默。")
        }
    }

    private var stabilitySection: some View {
        let rt = sharedSettings.agentRuntime
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "生成稳定性（KMP 默认值 · 只读）")
            AmberFormGroup {
                ExecutionStatusRow(
                    systemImage: "clock.arrow.circlepath",
                    title: "自动重试生成",
                    subtitle: "KMP agentRuntime.generationRetry；iOS provider streaming 未消费重试策略",
                    value: rt.generationRetry.enabled ? "启用" : "关闭"
                )

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "arrow.triangle.2.circlepath",
                    title: "重试上限",
                    subtitle: "KMP generationRetry.maxRetries；iOS GenerationHandler 尚未消费",
                    value: "\(rt.generationRetry.maxRetries)"
                )

                ExecutionDivider(leading: 54)

                ExecutionStatusRow(
                    systemImage: "bell",
                    title: "后台生成保活",
                    subtitle: "KMP keepGenerationAliveInBackground；iOS 前台通知保活尚未实现",
                    value: rt.keepGenerationAliveInBackground ? "启用" : "关闭"
                )
            }
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
