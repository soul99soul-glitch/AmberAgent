import SwiftUI

struct MiniAppListView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var menuApp: MiniAppListItem?

    private let columns = [
        GridItem(.flexible(), spacing: 11),
        GridItem(.flexible(), spacing: 11)
    ]

    private let apps: [MiniAppListItem] = [
        .init(icon: "番", title: "番茄钟", description: "25 分钟专注计时，休息提醒与连续记录", pinned: true),
        .init(icon: "汇", title: "汇率换算", description: "实时多币种汇率，离线也能换算"),
        .init(icon: "配", title: "配色板", description: "从图片提取主色，导出 HEX / RGB"),
        .init(icon: "待", title: "待办清单", description: "极简待办，本地保存，支持分组"),
        .init(icon: "J", title: "JSON 工具", description: "格式化 / 压缩 / 校验 JSON"),
        .init(icon: "抽", title: "抽签骰子", description: "掷骰子、抓阄、随机决定")
    ]

    private var menuPresented: Binding<Bool> {
        Binding(
            get: { menuApp != nil },
            set: { if !$0 { menuApp = nil } }
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

                        LazyVGrid(columns: columns, spacing: 11) {
                            ForEach(apps) { app in
                                MiniAppTile(app: app) {
                                    router.navigate(to: .miniAppRunner(title: app.title))
                                } onMore: {
                                    menuApp = app
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 4)
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .confirmationDialog(menuApp?.title ?? "小应用", isPresented: menuPresented, titleVisibility: .visible) {
            Button("打开小应用") {
                if let menuApp {
                    router.navigate(to: .miniAppRunner(title: menuApp.title))
                }
                menuApp = nil
            }
            Button(menuApp?.pinned == true ? "取消置顶" : "置顶") {
                menuApp = nil
            }
            Button("版本历史") {
                menuApp = nil
            }
            Button("导出 HTML") {
                menuApp = nil
            }
            Button("重命名") {
                menuApp = nil
            }
            Button("删除", role: .destructive) {
                menuApp = nil
            }
            Button("取消", role: .cancel) {
                menuApp = nil
            }
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("小应用")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

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
        Text("Amber 为你生成并保存的轻量小工具，点开即用。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 8)
    }
}

private struct MiniAppListItem: Identifiable {
    let id = UUID()
    let icon: String
    let title: String
    let description: String
    var pinned = false
}

private struct MiniAppTile: View {
    let app: MiniAppListItem
    let onOpen: () -> Void
    let onMore: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(app.icon)
                        .font(.system(size: 17, weight: .semibold, design: .monospaced))
                        .foregroundStyle(app.pinned ? AmberTheme.accent : AmberTheme.foreground2)
                        .frame(width: 40, height: 40)
                        .background(
                            app.pinned ? AmberTheme.accentTint : AmberTheme.surface2,
                            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                        )

                    HStack(spacing: 5) {
                        Text(app.title)
                            .font(.system(size: 14.5, weight: .semibold))
                            .foregroundStyle(AmberTheme.foreground)
                            .lineLimit(1)
                            .minimumScaleFactor(0.82)
                        if app.pinned {
                            Image(systemName: "star.fill")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(AmberTheme.accent)
                        }
                    }
                    .padding(.top, 11)

                    Text(app.description)
                        .font(.system(size: 12))
                        .lineSpacing(2)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .padding(.top, 3)

                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, minHeight: 122, alignment: .topLeading)
                .padding(14)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
                .contentShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            }
            .buttonStyle(.plain)

            Button(action: onMore) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(AmberTheme.muted2)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            .padding(.trailing, 8)
            .accessibilityLabel("管理 \(app.title)")
        }
    }
}

#Preview {
    NavigationStack {
        MiniAppListView()
    }
    .environment(RouterPath())
}
