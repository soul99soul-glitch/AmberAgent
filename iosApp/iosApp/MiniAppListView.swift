import SwiftUI

struct MiniAppListView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private let evidenceRows: [MiniAppCapabilityRow] = [
        .init(
            title: "MiniAppSetting",
            subtitle: "common settings define enabled, network/search/clipboard, host/AI, shared store, sensor/location, source and WebView debug flags.",
            status: "KMP 存在",
            tint: AmberTheme.accent
        ),
        .init(
            title: "MiniAppRepository / DAOs",
            subtitle: "Android persists generated apps, grants, versions, audit logs, shared data, pin/rename/delete/run-count state through Room.",
            status: "Android 存在",
            tint: .blue
        ),
        .init(
            title: "Prompt / Output transformers",
            subtitle: "Android injects MiniApp generation instructions only for explicit requests, parses strict JSON, validates HTML, and writes MiniApp cards.",
            status: "Android 存在",
            tint: .purple
        ),
        .init(
            title: "Runner / WebView bridge",
            subtitle: "Android loads saved HTML into a sandboxed WebView and exposes Amber.fetch/search/ai/host/sharedStore/eventBus through MiniAppBridge.",
            status: "Android 存在",
            tint: .green
        ),
        .init(
            title: "iOS MiniApp bridge",
            subtitle: "SwiftUI currently has no MiniAppSetting bridge, repository/DAO, transformer, HTML validator, WebView bridge, permission grant store, or runner.",
            status: "未接线",
            tint: AmberTheme.accentAmber
        )
    ]

    private let handlingRows: [MiniAppCapabilityRow] = [
        .init(
            title: "已保存小应用",
            subtitle: "不再展示硬编码番茄钟、汇率换算、配色板、待办、JSON 工具或抽签骰子。真实列表应来自 MiniAppRepository.observeAll().",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "打开 / 运行",
            subtitle: "iOS 没有 appId -> MiniAppEntity -> WebView runner 链路；本页不提供可点击的小应用卡片。",
            status: "禁用",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "置顶 / 重命名 / 删除 / 导出 / 版本",
            subtitle: "这些操作在 Android 通过 MiniAppRepository 和 HTML exporter 完成；iOS 当前不执行任何存储或文件写入。",
            status: "未接线",
            tint: AmberTheme.accentAmber
        ),
        .init(
            title: "聊天生成 MiniApp",
            subtitle: "iOS ChatViewModel 未接 MiniAppPromptTransformer、MiniAppOutputTransformer、MiniAppOutputParser 或 UIMessagePart.MiniApp 渲染链。",
            status: "未接线",
            tint: AmberTheme.accentAmber
        )
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        evidenceSection
                        handlingSection
                        MiniAppCapabilityNote("设置按钮保留为 Android/KMP 字段映射入口；它不会保存开关、权限、grant、版本、HTML、shared store 或 WebView 调试状态。")
                            .padding(.top, 14)
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
                Text("小应用")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("Android/KMP 已实现 · iOS WKWebView 可用（Runner 待完整 bridge）")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "小应用设置", size: 44, symbolSize: 18) {
                router.navigate(to: .miniAppSettings)
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("Android/KMP 的 MiniApp 会在明确的小应用请求后注入生成指令、解析并校验模型输出、把 HTML 和版本写入数据库，再通过沙箱 WebView 运行并按权限桥接网络、搜索、AI、宿主写回和共享存储。iOS 当前没有这些设置、存储、生成、渲染或权限桥，本页只展示能力证据，不展示假小应用。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "真实能力证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }

    private var handlingSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                ForEach(Array(handlingRows.enumerated()), id: \.element.id) { index, row in
                    MiniAppCapabilityStatusRow(row: row)
                    if index < handlingRows.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }
}

struct MiniAppCapabilityRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let status: String
    let tint: Color
}

struct MiniAppCapabilityStatusRow: View {
    let row: MiniAppCapabilityRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(row.status)
                .font(.caption.weight(.semibold))
                .foregroundStyle(row.tint)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

struct MiniAppCapabilityDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

struct MiniAppCapabilityNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
    }
}

#Preview {
    NavigationStack {
        MiniAppListView()
    }
    .environment(RouterPath())
}
