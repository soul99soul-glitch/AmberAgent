import SwiftUI
import PhotosUI
import UIKit
import Shared

struct AccountView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @State private var displayName = ""
    @State private var avatarImage: UIImage?
    @State private var avatarItem: PhotosPickerItem?
    @State private var isRenaming = false
    @State private var renameDraft = ""

    private var avatarInitial: String {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? "A" : trimmed).prefix(1)).uppercased()
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hero
                        statsSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onChange(of: avatarItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let ui = UIImage(data: data) {
                    let resized = AccountAvatarStore.downscaled(ui)
                    AccountAvatarStore.save(resized)
                    await MainActor.run { avatarImage = resized }
                }
            }
        }
        .alert("修改昵称", isPresented: $isRenaming) {
            TextField("输入你的称呼", text: $renameDraft)
            Button("取消", role: .cancel) {}
            Button("保存") {
                let trimmed = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                displayName = trimmed
                sharedSettings.updateUserNickname(trimmed)
            }
        } message: {
            Text("昵称会保存到本机，重启后保留。")
        }
        .onAppear {
            // Seed the field from the persisted nickname (empty → "Amber" display).
            if displayName.isEmpty {
                displayName = sharedSettings.displaySetting.userNickname
            }
            avatarImage = AccountAvatarStore.load()
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("我的账户")
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

    private var hero: some View {
        VStack(spacing: 10) {
            PhotosPicker(selection: $avatarItem, matching: .images) {
                avatarCircle
            }
            .buttonStyle(.plain)

            VStack(spacing: 4) {
                Button {
                    renameDraft = displayName
                    isRenaming = true
                } label: {
                    Text(displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Amber" : displayName)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
                .buttonStyle(.plain)

                Text("点按头像换照片，点按昵称可修改")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 18)
    }

    private var avatarCircle: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if let avatarImage {
                    Image(uiImage: avatarImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 72, height: 72)
                        .clipShape(Circle())
                } else {
                    Text(avatarInitial)
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(width: 72, height: 72)
                        .background(AmberTheme.surface2, in: Circle())
                }
            }
            .overlay {
                Circle().stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }

            Image(systemName: "camera.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(AmberTheme.accent, in: Circle())
                .overlay { Circle().stroke(AmberTheme.background, lineWidth: 2) }
        }
        .frame(width: 72, height: 72)
    }

    private var statsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "统计")
            AccountStatsPanel(sharedSettings: sharedSettings)
                .padding(.horizontal, 16)
        }
    }
}

extension Notification.Name {
    /// 自定义头像变更时广播,供会话列表等处即时刷新。
    static let accountAvatarChanged = Notification.Name("app.amber.ios.accountAvatarChanged")
}

/// 本机自定义头像存储(账户页用)。下采样后存为 Documents/account-avatar.jpg。
enum AccountAvatarStore {
    private static var fileURL: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("account-avatar.jpg", isDirectory: false)
    }

    static func load() -> UIImage? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return UIImage(data: data)
    }

    static func save(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.85) else { return }
        try? data.write(to: fileURL, options: [.atomic])
        NotificationCenter.default.post(name: .accountAvatarChanged, object: nil)
    }

    static func downscaled(_ image: UIImage, maxDimension: CGFloat = 256) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxDimension, longest > 0 else { return image }
        let scale = maxDimension / longest
        let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: target)
        return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
    }
}

private struct AccountStatsPanel: View {
    let sharedSettings: IOSSharedSettingsStore

    private var overviewStats: [AccountStatItem] {
        [
            .init(systemImage: "bubble.left.and.bubble.right", label: "助手数", value: "\(sharedSettings.snapshot.assistants.count)"),
            .init(systemImage: "person.2", label: "默认助手", value: sharedSettings.snapshot.assistants.first?.name ?? "Amber"),
        ]
    }

    private var detailStats: [AccountStatItem] {
        [
            .init(systemImage: "server.rack", label: "服务商模板", value: "\(sharedSettings.snapshot.providers.count)"),
            .init(systemImage: "speaker.wave.2", label: "TTS 引擎", value: "\(sharedSettings.snapshot.ttsProviders.count)"),
            .init(systemImage: "magnifyingglass", label: "搜索服务", value: "\(sharedSettings.snapshot.searchServices.count)"),
            .init(systemImage: "power", label: "启动次数", value: "\(sharedSettings.snapshot.launchCount)"),
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            AccountHeatmapBlock()

            AccountDivider()

            AccountStatsOverview(items: overviewStats)

            AccountDivider()

            VStack(spacing: 0) {
                ForEach(detailStats.indices, id: \.self) { index in
                    AccountStatLine(item: detailStats[index])

                    if index < detailStats.count - 1 {
                        AccountDivider()
                            .padding(.leading, 40)
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }
}

private struct AccountStatsOverview: View {
    let items: [AccountStatItem]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { index in
                AccountPrimaryMetric(item: items[index])

                if index < items.count - 1 {
                    Divider()
                        .frame(height: 54)
                        .overlay(AmberTheme.borderSoft)
                        .padding(.horizontal, 12)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 14)
    }
}

private struct AccountPrimaryMetric: View {
    let item: AccountStatItem

    var body: some View {
        VStack(alignment: .center, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: item.systemImage)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)

                Text(item.label)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }

            Text(item.value)
                .font(.system(size: 28, weight: .semibold, design: .rounded))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.76)
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

private struct AccountHeatmapBlock: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "calendar")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 28, height: 28)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text("使用热力图统计")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    // [Slice 5] 真接：AccountHeatmap 从 AgentRuntimeDao.listAllRuns()
                    // 加载真实 run，按 startedAt 的日分桶着色。
                    Text("近 21 周每日运行次数（来自 agent_runtime.db）")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }

            AccountHeatmap()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
    }
}

private struct AccountHeatmap: View {
    private let weeks = 21
    private let rows = 7

    // [Slice 5] 真实运行数据：从 AgentRuntimeDao.listAllRuns() 加载所有 run，
    // 按 startedAt 的"日"分桶计数。空时所有格为 level 0（诚实，不造假）。
    @State private var dailyCounts: [Date: Int] = [:]
    @State private var totalRuns: Int = 0
    @State private var didLoad = false

    var body: some View {
        GeometryReader { geometry in
            let cellGap: CGFloat = 3
            let maxCellSize: CGFloat = 12
            let availableWidth = max(geometry.size.width, 0)
            let cellSize = max(6, min(maxCellSize, (availableWidth - CGFloat(weeks - 1) * cellGap) / CGFloat(weeks)))
            let gridWidth = CGFloat(weeks) * cellSize + CGFloat(weeks - 1) * cellGap
            let gridHeight = CGFloat(rows) * cellSize + CGFloat(rows - 1) * cellGap

            ZStack {
                heatmapGrid(cellSize: cellSize, cellGap: cellGap)
                    .frame(width: gridWidth, height: gridHeight)

                if !didLoad {
                    Label("加载中…", systemImage: "arrow.clockwise")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(AmberTheme.surface.opacity(0.92), in: Capsule())
                } else if totalRuns == 0 {
                    Label("暂无运行记录", systemImage: "chart.bar.xaxis")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(AmberTheme.surface.opacity(0.92), in: Capsule())
                }
            }
            .frame(maxWidth: .infinity, minHeight: gridHeight)
        }
        .frame(height: 112)
        .task {
            guard !didLoad else { return }
            await loadRuns()
            didLoad = true
        }
    }

    // [Slice 5] 调 IosDatabaseFactory.createDatabase().agentRuntimeDao()
    // .listAllRuns(completionHandler:)（AgentRuntimeDao.kt:42），把每个 run
    // 的 startedAt (epoch ms) 折算成"日"key 累加。在 callback 内就把
    // non-Sendable 的 [AgentRunEntity] 降级成 Sendable 的 [Date:Int]/Int，
    // 再 resume continuation，避免数据竞争。dao 失败时按空数据渲染（诚实）。
    private func loadRuns() async {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let calendar = Calendar.current
        let result: (counts: [Date: Int], total: Int) = await withCheckedContinuation { (cont: CheckedContinuation<(counts: [Date: Int], total: Int), Never>) in
            dao.listAllRuns { result, _ in
                let runs = result ?? []
                var counts: [Date: Int] = [:]
                for run in runs {
                    let date = Date(timeIntervalSince1970: TimeInterval(run.startedAt) / 1000.0)
                    let day = calendar.startOfDay(for: date)
                    counts[day, default: 0] += 1
                }
                cont.resume(returning: (counts, runs.count))
            }
        }
        dailyCounts = result.counts
        totalRuns = result.total
    }

    private func heatmapGrid(cellSize: CGFloat, cellGap: CGFloat) -> some View {
        Canvas { context, _ in
            let columnPitch = cellSize + cellGap
            let rowPitch = cellSize + cellGap
            let calendar = Calendar.current
            let today = calendar.startOfDay(for: Date())
            // 网格右下角是"今天"，往左回溯 weeks*rows 天。
            for week in 0..<weeks {
                for row in 0..<rows {
                    // 该格距今天的偏移天数（右下角 = 0）
                    let offsetFromToday = (weeks - 1 - week) * rows + (rows - 1 - row)
                    guard let cellDay = calendar.date(byAdding: .day, value: -offsetFromToday, to: today) else { continue }
                    let count = dailyCounts[cellDay] ?? 0
                    let rect = CGRect(
                        x: CGFloat(week) * columnPitch,
                        y: CGFloat(row) * rowPitch,
                        width: cellSize,
                        height: cellSize
                    )
                    context.fill(
                        Path(roundedRect: rect, cornerRadius: 2.6),
                        with: .color(legendColor(level(for: count)))
                    )
                }
            }
        }
    }

    /// 把单日 run 数映射到 0..4 的强度档。
    private func level(for count: Int) -> Int {
        switch count {
        case 0: return 0
        case 1: return 1
        case 2...3: return 2
        case 4...6: return 3
        default: return 4
        }
    }

    private func legendColor(_ level: Int) -> Color {
        switch level {
        case 0:
            return AmberTheme.surface2.opacity(0.36)
        case 1:
            return AmberTheme.accent.opacity(0.24)
        case 2:
            return AmberTheme.accent.opacity(0.42)
        case 3:
            return AmberTheme.accent.opacity(0.66)
        default:
            return AmberTheme.accent
        }
    }
}

private struct AccountStatItem {
    let systemImage: String
    let label: String
    let value: String
}

private struct AccountStatLine: View {
    let item: AccountStatItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 28, height: 28)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            Text(item.label)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(item.value)
                .font(.system(size: 17, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .frame(minHeight: 54)
        .padding(.horizontal, 16)
        .padding(.vertical, 3)
    }
}

private struct AccountDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

#Preview {
    NavigationStack {
        AccountView(sharedSettings: IOSSharedSettingsStore())
    }
}
