import SwiftUI

struct MiniAppSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var appEnabled = true
    @State private var network = true
    @State private var externalImages = true
    @State private var search = true
    @State private var clipboardCopy = true
    @State private var amberAI = true
    @State private var minimalContext = true
    @State private var hostWrite = true
    @State private var boardSummary = true
    @State private var sharedStore = false
    @State private var eventBus = false
    @State private var launch = false
    @State private var sensors = false
    @State private var location = false
    @State private var clipboardRead = false
    @State private var sourceButton = false
    @State private var webViewDebug = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        masterSection
                        commonSection
                        hostAISection
                        advancedSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回小应用", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("小应用设置")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("允许 Amber 生成、保存并运行轻量 HTML 小工具。每个都在隔离沙箱里运行，按需申请下面这些能力——高权限的每次使用还会再确认。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var masterSection: some View {
        AmberFormGroup {
            MiniAppSettingToggleRow(
                systemImage: "square.grid.2x2",
                iconColor: AmberTheme.accent,
                title: "启用小应用",
                subtitle: "生成、保存并运行轻量 HTML 工具",
                isOn: $appEnabled
            )
        }
    }

    private var commonSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "常用能力")
            AmberFormGroup {
                MiniAppSettingToggleRow(title: "网络请求", subtitle: "通过 Amber.fetch 访问 HTTPS", isOn: $network, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "外链图片", subtitle: "经 Native 代理加载 HTTPS 外链图片", isOn: $externalImages, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "搜索", subtitle: "通过 Amber.search 获取结构化结果", isOn: $search, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "复制到剪贴板", subtitle: "允许小应用写入剪贴板", isOn: $clipboardCopy, isEnabled: appEnabled)
            }
        }
    }

    private var hostAISection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "宿主与 AI")
            AmberFormGroup {
                MiniAppSettingToggleRow(title: "Amber.ai", subtitle: "确认后调用当前聊天模型生成内容", isOn: $amberAI, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "读取最小上下文", subtitle: "确认后读取最小化的会话上下文", isOn: $minimalContext, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "写回宿主", subtitle: "确认后写回对话 / 生成 artifact", isOn: $hostWrite, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "更新看板摘要", subtitle: "更新自己在看板的摘要字段", isOn: $boardSummary, isEnabled: appEnabled)
            }
            MiniAppSettingFootnote(text: "这些涉及模型调用与写回宿主，属于高权限——每次使用仍会单独确认。")
        }
    }

    private var advancedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "高级与调试")
            AmberFormGroup {
                MiniAppSettingToggleRow(title: "共享存储", subtitle: "访问隔离 namespace 的 KV 存储", isOn: $sharedStore, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "事件总线", subtitle: "同 namespace 内收发临时事件", isOn: $eventBus, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "小应用跳转", subtitle: "确认后打开其它已保存的小应用", isOn: $launch, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "传感器", subtitle: "确认后订阅加速度 / 陀螺仪 / 光照", isOn: $sensors, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "定位", subtitle: "确认后读取位置", isOn: $location, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "读取剪贴板", subtitle: "确认后读取剪贴板文本", isOn: $clipboardRead, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "显示源码入口", subtitle: "在 Runner 菜单显示只读源码查看", isOn: $sourceButton, isEnabled: appEnabled)
                MiniAppSettingDivider()
                MiniAppSettingToggleRow(title: "WebView 调试", subtitle: "仅调试用，允许系统 WebView 检查小应用", isOn: $webViewDebug, isEnabled: appEnabled)
            }
            MiniAppSettingFootnote(text: "更偏开发者与实验能力，默认建议谨慎开启。")
        }
    }
}

private struct MiniAppSettingToggleRow: View {
    var systemImage: String?
    var iconColor = AmberTheme.accent
    let title: String
    let subtitle: String
    @Binding var isOn: Bool
    var isEnabled = true

    var body: some View {
        Button {
            guard isEnabled else { return }
            isOn.toggle()
        } label: {
            HStack(spacing: 12) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(iconColor.opacity(isEnabled ? 1 : 0.5))
                        .frame(width: 32, height: 32)
                        .background(iconColor.opacity(isEnabled ? 0.12 : 0.06), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(isEnabled ? AmberTheme.foreground : AmberTheme.muted)
                    Text(subtitle)
                        .font(.caption)
                        .lineLimit(2)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                MiniAppSettingSwitch(isOn: isOn, isEnabled: isEnabled)
            }
            .frame(minHeight: subtitle.count > 24 ? 64 : 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "开启" : "关闭")
    }
}

private struct MiniAppSettingSwitch: View {
    let isOn: Bool
    var isEnabled = true

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .opacity(isEnabled ? 1 : 0.55)
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

private struct MiniAppSettingDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct MiniAppSettingFootnote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

#Preview {
    NavigationStack {
        MiniAppSettingsView()
    }
}
