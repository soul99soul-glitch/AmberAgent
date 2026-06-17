import SwiftUI
@preconcurrency import Shared

enum AmberTheme {
    static let background = Color(hex: 0xFBF7F1)
    static let surface = Color(hex: 0xF2EADE)
    static let surface2 = Color(hex: 0xE7DBCB)
    static let card = surface
    static let foreground = Color(hex: 0x2A2320)
    static let foreground2 = Color(hex: 0x4A4039)
    static let muted = Color(hex: 0x6E6254)
    static let muted2 = Color(hex: 0xA89A88)
    static let border = Color(hex: 0xDBCEBC)
    static let borderSoft = Color(hex: 0xECE3D6)
    static let accent = Color(hex: 0xB5532C)
    static let accentTint = Color(hex: 0xB5532C, alpha: 0.12)
    static let accentIndigo = Color(hex: 0x5856D6)
    static let accentAmber = Color(hex: 0xD98324)
    static let accentGreen = Color(hex: 0x3DA35D)
    static let accentCyan = Color(hex: 0x2AA0BC)
    static let accentRed = Color(hex: 0xC8402F)
    static let glass = Color(hex: 0xFBF7F1, alpha: 0.72)
    static let glassStrong = Color(hex: 0xFBF7F1, alpha: 0.85)

    static let radiusSmall: CGFloat = 6
    static let radiusMedium: CGFloat = 8
    static let radiusLarge: CGFloat = 12
    static let radiusXLarge: CGFloat = 18
    static let radiusPill: CGFloat = 980
}

enum IOSAppearancePreferenceKeys {
    static let mode = "app.amber.ios.appearance.mode"
}

enum IOSAppearanceMode: String, CaseIterable, Identifiable {
    case light
    case dark
    case system

    var id: String { rawValue }

    var title: String {
        switch self {
        case .light: "浅色"
        case .dark: "深色"
        case .system: "跟随系统"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .light: .light
        case .dark: .dark
        case .system: nil
        }
    }
}

enum IOSDisplayPreferenceKeys {
    static let fontScale = "app.amber.ios.display.fontScale"
    static let chatFont = "app.amber.ios.display.chatFont"
    static let agentName = "app.amber.ios.display.agentName"
    static let followGeneration = "app.amber.ios.display.followGeneration"
}

enum IOSChatFont: String, CaseIterable, Identifiable {
    case `default`
    case serif
    case monospace

    var id: String { rawValue }

    var title: String {
        switch self {
        case .default: "默认"
        case .serif: "衬线体"
        case .monospace: "等宽字体"
        }
    }

    var design: Font.Design {
        switch self {
        case .default: .default
        case .serif: .serif
        case .monospace: .monospaced
        }
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            red: Double((hex >> 16) & 0xff) / 255.0,
            green: Double((hex >> 8) & 0xff) / 255.0,
            blue: Double(hex & 0xff) / 255.0,
            opacity: alpha
        )
    }
}

private struct AmberGlassModifier: ViewModifier {
    let cornerRadius: CGFloat
    let interactive: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)

        if #available(iOS 26.0, *) {
            if interactive {
                content
                    .background(AmberTheme.glass.opacity(0.35), in: shape)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
            } else {
                content
                    .background(AmberTheme.glass.opacity(0.35), in: shape)
                    .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay {
                    shape
                        .stroke(.white.opacity(0.65), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.10), radius: 12, y: 2)
        }
    }
}

extension View {
    func amberGlass(cornerRadius: CGFloat, interactive: Bool = true) -> some View {
        modifier(AmberGlassModifier(cornerRadius: cornerRadius, interactive: interactive))
    }
}

struct AmberGlassCircleButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat = 44
    var symbolSize: CGFloat = 17
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: size / 2)
        .accessibilityLabel(accessibilityLabel)
    }
}

struct AmberSectionLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(AmberTheme.muted)
            .textCase(.uppercase)
            .tracking(0.4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 7)
    }
}

struct AmberFormGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            content
        }
        .background(AmberTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
    }
}

struct AmberFormRow: View {
    let systemImage: String?
    let iconColor: Color
    let title: String
    let subtitle: String?
    let trailing: String?
    let showsChevron: Bool
    let action: (() -> Void)?

    init(
        systemImage: String? = nil,
        iconColor: Color = AmberTheme.accent,
        title: String,
        subtitle: String? = nil,
        trailing: String? = nil,
        showsChevron: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.systemImage = systemImage
        self.iconColor = iconColor
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing
        self.showsChevron = showsChevron
        self.action = action
    }

    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: 12) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(iconColor)
                        .frame(width: 28, height: 28)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if let trailing {
                    Text(trailing)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                }

                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
    }
}

struct ConversationsView: View {
    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore

    @State private var searchQuery: String = ""
    @State private var renamingConversationId: KotlinUuid?
    @State private var renameDraft: String = ""
    @State private var deletingConversationId: KotlinUuid?

    private let shortcuts: [ConversationShortcut] = [
        .init(title: "今日看板", systemImage: "rectangle.grid.2x2", color: AmberTheme.accentAmber, route: .board),
        .init(title: "小应用", systemImage: "square.grid.2x2", color: AmberTheme.accent, route: .miniApps),
        .init(title: "工作区", systemImage: "folder", color: AmberTheme.accentGreen, route: .sandbox),
        .init(title: "核心记忆", systemImage: "brain.head.profile", color: AmberTheme.accentCyan, route: .memory),
        .init(title: "模型议会", systemImage: "bubble.left.and.bubble.right", color: AmberTheme.accentIndigo, route: .council)
    ]

    /// 本地标题过滤后的会话摘要（summaries 已按 updateAt 倒序/置顶优先）。
    private var filteredSummaries: [ConversationSummary] {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return conversationStore.summaries }
        return conversationStore.summaries.filter { summary in
            // 空标题会话用占位串参与匹配，避免搜索框里全是空白行。
            let title = summary.title.isEmpty ? "新对话" : summary.title
            return title.localizedCaseInsensitiveContains(trimmed)
        }
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    searchField
                    shortcutStrip
                    AmberSectionLabel(text: "会话")
                    if filteredSummaries.isEmpty {
                        emptyState
                    } else {
                        conversationList
                    }
                }
                .padding(.bottom, 120)
            }
            .scrollIndicators(.hidden)

            Button {
                Task { @MainActor in
                    await conversationStore.newConversation()
                    router.navigate(to: .chat)
                }
            } label: {
                Image(systemName: "pencil")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 56)
            }
            .buttonStyle(.plain)
            .background(AmberTheme.accent, in: Circle())
            .shadow(color: AmberTheme.accent.opacity(0.38), radius: 20, y: 4)
            .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
            .accessibilityLabel("新建聊天")
            .padding(.trailing, 20)
            .padding(.bottom, 32)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert("重命名会话", isPresented: Binding(
            get: { renamingConversationId != nil },
            set: { if !$0 { renamingConversationId = nil } }
        )) {
            TextField("会话标题", text: $renameDraft)
            Button("取消", role: .cancel) { renamingConversationId = nil }
            Button("保存") {
                if let id = renamingConversationId {
                    let trimmed = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty {
                        Task { @MainActor in
                            await conversationStore.renameConversation(id: id, title: trimmed)
                        }
                    }
                }
                renamingConversationId = nil
            }
        }
        .alert("删除会话？", isPresented: Binding(
            get: { deletingConversationId != nil },
            set: { if !$0 { deletingConversationId = nil } }
        )) {
            Button("取消", role: .cancel) { deletingConversationId = nil }
            Button("删除", role: .destructive) {
                if let id = deletingConversationId {
                    Task { @MainActor in
                        await conversationStore.deleteConversation(id: id)
                    }
                }
                deletingConversationId = nil
            }
        } message: {
            Text("此操作不可撤销，会话内的全部消息将被删除。")
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text("Amber")
                .font(.system(size: 34, weight: .bold, design: .default))
                .foregroundStyle(AmberTheme.foreground)
                .tracking(-0.7)

            Spacer()

            HStack(spacing: 8) {
                AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "设置", size: 34, symbolSize: 16) {
                    router.navigate(to: .settings)
                }

                Button {
                    router.navigate(to: .account)
                } label: {
                    Text("A")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(width: 34, height: 34)
                        .background(AmberTheme.surface2, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("我的账户")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.muted2)
            TextField("搜索会话标题", text: $searchQuery)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(AmberTheme.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索")
            }
        }
        .frame(height: 38)
        .padding(.horizontal, 14)
        .background(AmberTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var shortcutStrip: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(shortcuts.enumerated()), id: \.element.id) { index, shortcut in
                shortcutButton(shortcut)

                if index < shortcuts.count - 1 {
                    Spacer(minLength: 8)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, 2)
        .padding(.bottom, 16)
    }

    private func shortcutButton(_ shortcut: ConversationShortcut) -> some View {
        Button {
            router.navigate(to: shortcut.route)
        } label: {
            VStack(alignment: .center, spacing: 7) {
                Image(systemName: shortcut.systemImage)
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(shortcut.color)
                    .frame(width: 54, height: 54)
                    .background(shortcut.color.opacity(0.14), in: RoundedRectangle(cornerRadius: 15, style: .continuous))

                Text(shortcut.title)
                    .font(.system(size: 11, weight: .regular))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.88)
            }
            .frame(width: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(AmberTheme.muted2)
            Text(searchQuery.isEmpty ? "还没有会话" : "没有匹配的会话")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }

    private var conversationList: some View {
        LazyVStack(spacing: 0) {
            ForEach(filteredSummaries, id: \.id) { summary in
                ConversationSummaryRow(
                    summary: summary,
                    isCurrent: conversationStore.currentConversation?.id == summary.id,
                    onTap: {
                        Task { @MainActor in
                            await conversationStore.selectConversation(id: summary.id)
                            router.navigate(to: .chat)
                        }
                    },
                    onRename: {
                        renameDraft = summary.title
                        renamingConversationId = summary.id
                    },
                    onTogglePin: {
                        Task { @MainActor in
                            await conversationStore.togglePin(id: summary.id)
                        }
                    },
                    onDelete: {
                        deletingConversationId = summary.id
                    }
                )
            }
        }
    }
}

/// 真实会话摘要行：标题 / 相对时间 / 消息数 / 置顶标记 / 当前高亮 / 左滑操作。
private struct ConversationSummaryRow: View {
    let summary: ConversationSummary
    let isCurrent: Bool
    let onTap: () -> Void
    let onRename: () -> Void
    let onTogglePin: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(isCurrent ? AmberTheme.accent.opacity(0.16) : AmberTheme.surface2)
                    if summary.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AmberTheme.accentAmber)
                    } else {
                        Image(systemName: "bubble.left.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(isCurrent ? AmberTheme.accent : AmberTheme.muted2)
                    }
                }
                .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 3) {
                    Text(summary.title.isEmpty ? "新对话" : summary.title)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Text(relativeTime)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                        Text("·")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted2)
                        Text("\(summary.messageCount) 条")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }

                Spacer(minLength: 8)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 16)
            .padding(.vertical, 2)
            .background(isCurrent ? AmberTheme.accentTint : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("会话 \(summary.title.isEmpty ? "新对话" : summary.title)，\(summary.messageCount) 条消息\(summary.isPinned ? "，已置顶" : "")")
        // contextMenu 在 LazyVStack 里可用（swipeActions 仅 List 支持，与玻璃风格背景冲突）。
        // 长按行弹出：置顶 / 重命名 / 删除。
        .contextMenu {
            Button {
                onTogglePin()
            } label: {
                Label(summary.isPinned ? "取消置顶" : "置顶",
                      systemImage: summary.isPinned ? "pin.slash" : "pin")
            }
            Button {
                onRename()
            } label: {
                Label("重命名", systemImage: "pencil")
            }
            Button(role: .destructive) {
                onDelete()
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
    }

    /// 相对时间：updateAt -> "刚刚 / N分钟前 / N小时前 / 昨天 / M月D日"。
    private var relativeTime: String {
        let ms = summary.updateAt.toEpochMilliseconds()
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

struct SearchView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var selectedFilter: SearchFilter = .all
    @FocusState private var searchFocused: Bool

    private let recentSearches = [
        "iOS 液态玻璃设计",
        "贪吃蛇应用",
        "流式输出测试",
        "威尔史密斯子女"
    ]

    private let results: [SearchResultRowModel] = [
        .init(
            filter: .conversation,
            group: "会话",
            initial: "I",
            title: "iOS 液态玻璃设计应用推荐",
            preview: "以下是几款适配 iOS 26 液态玻璃风格的设计参考应用...",
            highlight: "iOS",
            time: "刚刚",
            color: AmberTheme.accent
        ),
        .init(
            filter: .message,
            group: "消息",
            initial: "流",
            title: "流式输出测试",
            preview: "首字节延迟约 120ms，整体表现正常",
            highlight: "120ms",
            time: "上周五",
            color: AmberTheme.accentCyan
        )
    ]

    private var visibleResults: [SearchResultRowModel] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return []
        }
        return results.filter { row in
            selectedFilter.includes(row.filter)
        }
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                searchNavigation
                filterStrip

                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    recentSearchList
                } else {
                    resultList
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            searchFocused = true
        }
    }

    private var searchNavigation: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AmberTheme.muted)

                TextField("搜索任何内容...", text: $query)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .tint(AmberTheme.accent)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 18, height: 18)
                        .background(AmberTheme.muted2, in: Circle())
                }
                .buttonStyle(.plain)
                .opacity(query.isEmpty ? 0 : 1)
                .accessibilityLabel("清空搜索")
            }
            .frame(height: 38)
            .padding(.horizontal, 12)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .shadow(color: .white.opacity(0.55), radius: 0, y: -1)

            Button("取消") {
                dismiss()
            }
            .font(.body)
            .foregroundStyle(AmberTheme.accent)
            .buttonStyle(.plain)
            .accessibilityLabel("取消搜索")
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }

    private var filterStrip: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(SearchFilter.allCases) { filter in
                    Button {
                        selectedFilter = filter
                    } label: {
                        Text(filter.title)
                            .font(.system(size: 13.5, weight: .medium))
                            .foregroundStyle(selectedFilter == filter ? .white : AmberTheme.foreground2)
                            .frame(height: 30)
                            .padding(.horizontal, 13)
                            .background(
                                selectedFilter == filter ? AmberTheme.accent : AmberTheme.surface,
                                in: Capsule()
                            )
                            .overlay {
                                Capsule()
                                    .stroke(selectedFilter == filter ? .clear : AmberTheme.borderSoft, lineWidth: 0.5)
                            }
                            .shadow(
                                color: selectedFilter == filter ? AmberTheme.accent.opacity(0.28) : .black.opacity(0.04),
                                radius: selectedFilter == filter ? 6 : 2,
                                y: selectedFilter == filter ? 2 : 1
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selectedFilter == filter ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
        .padding(.bottom, 10)
    }

    private var recentSearchList: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "最近搜索")
                    .padding(.top, -8)

                ForEach(recentSearches, id: \.self) { term in
                    Button {
                        query = term
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "clock")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(AmberTheme.muted2)
                                .frame(width: 16)

                            Text(term)
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(minHeight: 42)
                        .padding(.horizontal, 16)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var resultList: some View {
        ScrollView {
            if visibleResults.isEmpty {
                ContentUnavailableView("没有结果", systemImage: "magnifyingglass", description: Text("换个关键词或筛选范围再试一次"))
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.top, 72)
            } else {
                VStack(spacing: 0) {
                    ForEach(groupedResults, id: \.group) { group in
                        SearchResultGroup(title: group.group, rows: group.rows) { _ in
                            router.navigate(to: .chat)
                        }
                    }
                }
                .padding(.bottom, 36)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var groupedResults: [(group: String, rows: [SearchResultRowModel])] {
        let orderedGroups = ["会话", "消息", "文件"]
        return orderedGroups.compactMap { group in
            let rows = visibleResults.filter { $0.group == group }
            return rows.isEmpty ? nil : (group, rows)
        }
    }
}

private enum SearchFilter: String, CaseIterable, Identifiable {
    case all
    case conversation
    case message
    case file

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "全部"
        case .conversation: "会话"
        case .message: "消息"
        case .file: "文件"
        }
    }

    func includes(_ rowFilter: SearchFilter) -> Bool {
        self == .all || self == rowFilter
    }
}

private struct SearchResultRowModel: Identifiable {
    let id = UUID()
    let filter: SearchFilter
    let group: String
    let initial: String
    let title: String
    let preview: String
    let highlight: String
    let time: String
    let color: Color
}

private struct SearchResultGroup: View {
    let title: String
    let rows: [SearchResultRowModel]
    let action: (SearchResultRowModel) -> Void

    var body: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)
                .padding(.top, -8)

            AmberFormGroup {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    SearchResultRow(row: row) {
                        action(row)
                    }

                    if index < rows.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 66)
                    }
                }
            }
        }
        .padding(.bottom, 4)
    }
}

private struct SearchResultRow: View {
    let row: SearchResultRowModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                Text(row.initial)
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .foregroundStyle(row.color)
                    .frame(width: 40, height: 40)
                    .background(row.color.opacity(0.14), in: Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text(row.title)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)

                    HighlightedPreview(text: row.preview, highlight: row.highlight)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(row.time)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.top, 1)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct HighlightedPreview: View {
    let text: String
    let highlight: String

    var body: some View {
        if let range = text.range(of: highlight), !highlight.isEmpty {
            HStack(spacing: 0) {
                Text(String(text[..<range.lowerBound]))
                Text(highlight)
                    .foregroundStyle(AmberTheme.accent)
                    .padding(.horizontal, 2)
                    .background(AmberTheme.accent.opacity(0.18), in: RoundedRectangle(cornerRadius: 3, style: .continuous))
                Text(String(text[range.upperBound...]))
            }
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineLimit(1)
        } else {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
        }
    }
}

private struct ConversationShortcut: Identifiable {
    let id = UUID()
    let title: String
    let systemImage: String
    let color: Color
    let route: Route
}

struct SettingsHomeView: View {
    let settingsStore: SettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = "light"
    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var displayFontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var displayChatFont = "default"
    @AppStorage(IOSExecutionPreferenceKeys.liveActivity) private var executionLiveActivity = true

    private var generalRows: [SettingsHomeRow] {
        [
            .init(title: "外观", subtitle: nil, value: appearanceModeTitle, systemImage: "circle.lefthalf.filled", color: AmberTheme.accent, route: .appearance),
            .init(title: "显示与字体", subtitle: displayFontSummary, value: nil, systemImage: "slider.horizontal.3", color: AmberTheme.accentAmber, route: .displayFont)
        ]
    }

    private var runtimeRows: [SettingsHomeRow] {
        [
            .init(title: "核心记忆", subtitle: "默认配置只读 · 记忆库执行待接", value: nil, systemImage: "cylinder.split.1x2", color: AmberTheme.accent, route: .memory),
            .init(title: "技能", subtitle: "Skill/MCP 配置桥待接", value: nil, systemImage: "hexagon", color: AmberTheme.accentAmber, route: .skills),
            .init(title: "执行与任务", subtitle: executionSummary, value: nil, systemImage: "waveform.path.ecg", color: AmberTheme.accentGreen, route: .execution),
            .init(title: "工具权限", subtitle: "系统权限 · 批准策略", value: nil, systemImage: "shield", color: AmberTheme.accentCyan, route: .toolPermissions),
            .init(title: "运行环境", subtitle: runtimeSummary, value: nil, systemImage: "square.grid.2x2", color: AmberTheme.accentIndigo, route: .sandbox)
        ]
    }

    private var providerRows: [SettingsHomeRow] {
        [
            .init(title: "服务商", subtitle: nil, value: "OpenAI-compatible", systemImage: "server.rack", color: AmberTheme.accent, route: .providers),
            .init(title: "默认模型", subtitle: nil, value: settingsStore.modelId.isEmpty ? "gpt-4o" : settingsStore.modelId, systemImage: "cpu", color: AmberTheme.accentAmber, route: .modelDefaults),
            .init(title: "搜索服务", subtitle: nil, value: "真实默认只读", systemImage: "magnifyingglass", color: AmberTheme.accentGreen, route: .searchServices),
            .init(title: "语音 TTS", subtitle: nil, value: "系统 TTS", systemImage: "speaker.wave.2", color: AmberTheme.accentCyan, route: .ttsSettings)
        ]
    }

    private var dataRows: [SettingsHomeRow] {
        [
            .init(title: "同步与备份", subtitle: "iOS 同步桥待接", value: nil, systemImage: "icloud", color: AmberTheme.accentCyan, route: .syncBackup),
            .init(title: "对话存储", subtitle: nil, value: "待接", systemImage: "tray.full", color: AmberTheme.accent, route: .conversationStorage)
        ]
    }

    private var experimentalRows: [SettingsHomeRow] {
        [
            .init(title: "今日看板", subtitle: "默认配置只读 · 采集待接", value: nil, systemImage: "rectangle.grid.2x2", color: AmberTheme.accentAmber, route: .board),
            .init(title: "模型议会", subtitle: "start 调用链已通(stub) · 真实推理待接", value: nil, systemImage: "bubble.left.and.bubble.right", color: AmberTheme.accent, route: .council),
            .init(title: "SubAgent", subtitle: "默认配置只读 · 运行待接", value: nil, systemImage: "person.2", color: AmberTheme.accentGreen, route: .subagents),
            .init(title: "小应用", subtitle: "默认配置只读 · 运行待接(WKWebView)", value: nil, systemImage: "square.grid.2x2", color: AmberTheme.accentCyan, route: .miniApps),
            .init(title: "WebMount", subtitle: "待接(WKWebView)", value: nil, systemImage: "globe", color: AmberTheme.accentIndigo, route: .webMount)
        ]
    }

    private var appearanceModeTitle: String {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).title
    }

    private var displayFontSummary: String {
        "字号 \(displayFontScaleTitle) · \(displayChatFontTitle)"
    }

    private var displayFontScaleTitle: String {
        switch displayFontScale {
        case ..<0.95: "较小"
        case 0.95..<1.08: "标准"
        case 1.08..<1.20: "较大"
        default: "特大"
        }
    }

    private var displayChatFontTitle: String {
        switch displayChatFont {
        case "serif": "衬线体"
        case "monospace": "等宽字体"
        default: "默认字体"
        }
    }

    private var executionSummary: String {
        "灵动岛 \(executionLiveActivity ? "开" : "关") · 执行字段只读"
    }

    private var runtimeSummary: String {
        switch settingsStore.terminalDefaultRuntime {
        case .remoteSSH:
            if settingsStore.sshProfiles.isEmpty {
                "Remote SSH · 未配置 Profile"
            } else {
                "Remote SSH · \(settingsStore.sshProfiles.count) 个 Profile"
            }
        case .localIOSTools:
            "Local iOS Tools"
        case .remoteMosh, .ishExperimental:
            settingsStore.terminalDefaultRuntime.displayName
        }
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    settingsSection("通用", rows: generalRows)
                    settingsSection("Agent 运行时", rows: runtimeRows)
                    settingsSection("模型与服务", rows: providerRows)
                    settingsSection("数据", rows: dataRows)
                    settingsSection("实验性", rows: experimentalRows)
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("设置")
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

    private func settingsSection(_ title: String, rows: [SettingsHomeRow]) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)
            AmberFormGroup {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    AmberFormRow(
                        systemImage: row.systemImage,
                        iconColor: row.color,
                        title: row.title,
                        subtitle: row.subtitle,
                        trailing: row.value,
                        showsChevron: true
                    ) {
                        router.navigate(to: row.route)
                    }

                    if index < rows.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                            .padding(.leading, 58)
                    }
                }
            }
        }
    }

    private func placeholder(_ title: String, _ subtitle: String, _ systemImage: String) -> Route {
        .settingsPlaceholder(title: title, subtitle: subtitle, systemImage: systemImage)
    }
}

private struct SettingsHomeRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String?
    let value: String?
    let systemImage: String
    let color: Color
    let route: Route
}

struct WorkspaceView: View {
    var body: some View {
        PlaceholderListView(
            title: "Workspace",
            systemImage: "square.grid.2x2",
            rows: [
                "Live tasks",
                "Artifacts",
                "Files",
                "Web previews"
            ]
        )
    }
}

struct AssistantsView: View {
    var body: some View {
        PlaceholderListView(
            title: "Assistants",
            systemImage: "person.2",
            rows: [
                "Assistant profiles",
                "Prompt and behavior",
                "Model defaults",
                "Memory settings"
            ]
        )
    }
}

struct PlaceholderListView: View {

    let title: String
    let systemImage: String
    let rows: [String]

    var body: some View {
        List {
            Section {
                Label(title, systemImage: systemImage)
                    .font(.headline)
                ForEach(rows, id: \.self) { row in
                    Text(row)
                }
            }
        }
        .navigationTitle(title)
    }
}

struct PlaceholderDetailView: View {

    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        ContentUnavailableView(title, systemImage: systemImage, description: Text(subtitle))
            .navigationTitle(title)
    }
}
