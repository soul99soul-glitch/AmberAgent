import SwiftUI
@preconcurrency import Shared
import UniformTypeIdentifiers

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
    static let microsoftStreamingMarkdown = "app.amber.ios.display.microsoftStreamingMarkdown"
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

private struct AmberProminentGlassModifier: ViewModifier {
    let cornerRadius: CGFloat
    let tint: Color
    let interactive: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)

        if #available(iOS 26.0, *) {
            // Solid accent fill + a full-tint glass sheen so prominent buttons (the new-chat FAB,
            // prominent icon/pill buttons) actually read as the standard accent instead of the
            // washed-out 0.24/0.34-opacity tint they had before.
            if interactive {
                content
                    .background(tint, in: shape)
                    .glassEffect(.regular.tint(tint).interactive(), in: .rect(cornerRadius: cornerRadius))
            } else {
                content
                    .background(tint, in: shape)
                    .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            content
                .background(tint, in: shape)
                .shadow(color: tint.opacity(0.32), radius: 18, y: 4)
        }
    }
}

extension View {
    func amberGlass(cornerRadius: CGFloat, interactive: Bool = true) -> some View {
        modifier(AmberGlassModifier(cornerRadius: cornerRadius, interactive: interactive))
    }

    func amberProminentGlass(
        cornerRadius: CGFloat,
        tint: Color = AmberTheme.accent,
        interactive: Bool = true
    ) -> some View {
        modifier(AmberProminentGlassModifier(cornerRadius: cornerRadius, tint: tint, interactive: interactive))
    }
}

struct AmberGlassGroup<Content: View>: View {
    let spacing: CGFloat
    let content: Content

    init(spacing: CGFloat = 16, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) {
                content
            }
        } else {
            content
        }
    }
}

struct AmberGlassIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat = 32
    var symbolSize: CGFloat = 15
    var tint: Color = AmberTheme.foreground2
    var prominent = false
    let action: () -> Void

    var body: some View {
        if prominent {
            buttonLabel
                .amberProminentGlass(cornerRadius: size / 2, tint: tint)
                .accessibilityLabel(accessibilityLabel)
        } else {
            buttonLabel
                .amberGlass(cornerRadius: size / 2)
                .accessibilityLabel(accessibilityLabel)
        }
    }

    private var buttonLabel: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(prominent ? Color.white : tint)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

struct AmberGlassTextChip: View {
    let title: String
    var isSelected = false
    var tint: Color = AmberTheme.accent
    var height: CGFloat = 30
    var horizontalPadding: CGFloat = 12
    var fillsWidth = false
    var font: Font = .caption.weight(.semibold)

    var body: some View {
        if isSelected {
            label
                .foregroundStyle(Color.white)
                .amberProminentGlass(cornerRadius: height / 2, tint: tint)
        } else {
            label
                .foregroundStyle(AmberTheme.foreground2)
                .amberGlass(cornerRadius: height / 2)
        }
    }

    private var label: some View {
        Text(title)
            .font(font)
            .lineLimit(1)
            .minimumScaleFactor(0.78)
            .frame(maxWidth: fillsWidth ? .infinity : nil)
            .frame(height: height)
            .padding(.horizontal, horizontalPadding)
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
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore

    @State private var searchQuery: String = ""
    @State private var renamingConversationId: KotlinUuid?
    @State private var renameDraft: String = ""
    @State private var deletingConversationId: KotlinUuid?

    private var shortcuts: [ConversationShortcut] {
        [
            .init(
                title: "深度阅读",
                systemImage: "book.pages",
                color: AmberTheme.accentAmber,
                route: .board
            ),
            .init(
                title: "小应用",
                systemImage: "square.grid.2x2",
                color: AmberTheme.accent,
                route: .miniApps
            ),
            .init(
                title: "核心记忆",
                systemImage: "brain.head.profile",
                color: AmberTheme.accentCyan,
                route: .memory
            ),
            .init(
                title: "WebMount",
                systemImage: "globe",
                color: AmberTheme.accentGreen,
                route: .webMount
            ),
            .init(
                title: "模型议会",
                systemImage: "bubble.left.and.bubble.right",
                color: AmberTheme.accentIndigo,
                route: .council
            )
        ]
    }

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
                    // Pull the 会话 header (and the list below it) up a touch — the shared
                    // AmberSectionLabel bakes in 20pt of top padding.
                    AmberSectionLabel(text: "会话")
                        .padding(.top, -10)
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
            .amberProminentGlass(cornerRadius: 28, tint: AmberTheme.accent)
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

            AmberGlassGroup(spacing: 8) {
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
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .amberGlass(cornerRadius: 17)
                    .accessibilityLabel("我的账户")
                }
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
            TextField("搜索会话与消息", text: $searchQuery)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .submitLabel(.search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit {
                    router.navigate(to: .search(initialQuery: searchQuery))
                }
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
        .amberGlass(cornerRadius: 13)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var shortcutStrip: some View {
        HStack(spacing: 8) {
            ForEach(shortcuts) { shortcut in
                shortcutButton(shortcut)
            }
        }
        .padding(.horizontal, 8)
        // Sit a little lower under the search field and tighten the gap to the 会话 header
        // (which itself adds 20pt top), so the row isn't pushed high with a big void below.
        .padding(.top, 10)
        .padding(.bottom, 4)
    }

    private func shortcutButton(_ shortcut: ConversationShortcut) -> some View {
        Button {
            router.navigate(to: shortcut.route)
        } label: {
            VStack(alignment: .center, spacing: 8) {
                Image(systemName: shortcut.systemImage)
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(shortcut.color)
                    .frame(width: 52, height: 52)
                    .background(shortcut.color.opacity(0.14), in: RoundedRectangle(cornerRadius: 15, style: .continuous))

                Text(shortcut.title)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }
            .frame(maxWidth: .infinity, minHeight: 78)
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
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(\.dismiss) private var dismiss

    @State private var query: String
    @State private var selectedFilter: SearchFilter = .all
    @State private var results: [IOSConversationSearchResult] = []
    @State private var isSearching = false
    @FocusState private var searchFocused: Bool

    init(initialQuery: String = "") {
        self._query = State(initialValue: initialQuery)
    }

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var visibleResults: [IOSConversationSearchResult] {
        guard !trimmedQuery.isEmpty else { return [] }
        return results.filter { selectedFilter.includes($0.kind) }
    }

    private var recentSummaries: [ConversationSummary] {
        Array(conversationStore.summaries.prefix(8))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                searchNavigation
                filterStrip

                if trimmedQuery.isEmpty {
                    recentConversationList
                } else {
                    resultList
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            searchFocused = true
            await performSearch()
        }
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 220_000_000)
            if !Task.isCancelled {
                await performSearch()
            }
        }
    }

    private var searchNavigation: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AmberTheme.muted)

                TextField("搜索会话与消息", text: $query)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .tint(AmberTheme.accent)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit {
                        Task { @MainActor in
                            await performSearch()
                        }
                    }

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
            .amberGlass(cornerRadius: 12)
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }

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
            AmberGlassGroup(spacing: 12) {
                HStack(spacing: 6) {
                    ForEach(SearchFilter.allCases) { filter in
                        Button {
                            selectedFilter = filter
                        } label: {
                            AmberGlassTextChip(
                                title: filter.title,
                                isSelected: selectedFilter == filter,
                                height: 30,
                                horizontalPadding: 13,
                                font: .system(size: 13.5, weight: .medium)
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selectedFilter == filter ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .scrollIndicators(.hidden)
        .padding(.bottom, 10)
    }

    private var recentConversationList: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "最近会话")
                    .padding(.top, -8)

                if recentSummaries.isEmpty {
                    ContentUnavailableView("还没有会话", systemImage: "bubble.left.and.bubble.right")
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.top, 72)
                } else {
                    AmberFormGroup {
                        ForEach(Array(recentSummaries.enumerated()), id: \.element.id) { index, summary in
                            RecentConversationSearchRow(summary: summary) {
                                openConversation(summary.id)
                            }

                            if index < recentSummaries.count - 1 {
                                Divider()
                                    .overlay(AmberTheme.borderSoft)
                                    .padding(.leading, 66)
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var resultList: some View {
        ScrollView {
            if isSearching {
                ProgressView()
                    .tint(AmberTheme.accent)
                    .padding(.top, 72)
            } else if visibleResults.isEmpty {
                ContentUnavailableView("没有结果", systemImage: "magnifyingglass", description: Text("换个关键词或筛选范围再试一次"))
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.top, 72)
            } else {
                VStack(spacing: 0) {
                    ForEach(groupedResults, id: \.group) { group in
                        SearchResultGroup(title: group.group, rows: group.rows) { result in
                            openConversation(result.conversationId)
                        }
                    }
                }
                .padding(.bottom, 36)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var groupedResults: [(group: String, rows: [IOSConversationSearchResult])] {
        SearchFilter.resultGroups.compactMap { kind in
            let rows = visibleResults.filter { $0.kind == kind }
            return rows.isEmpty ? nil : (kind.title, rows)
        }
    }

    @MainActor
    private func performSearch() async {
        guard !trimmedQuery.isEmpty else {
            results = []
            isSearching = false
            return
        }
        isSearching = true
        let nextResults = await conversationStore.searchConversations(query: trimmedQuery)
        if !Task.isCancelled {
            results = nextResults
            isSearching = false
        }
    }

    private func openConversation(_ id: KotlinUuid) {
        Task { @MainActor in
            await conversationStore.selectConversation(id: id)
            router.navigate(to: .chat)
        }
    }
}

private enum SearchFilter: String, CaseIterable, Identifiable {
    case all
    case conversation
    case message

    static let resultGroups: [IOSConversationSearchResult.Kind] = [.conversation, .message]

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "全部"
        case .conversation: "会话"
        case .message: "消息"
        }
    }

    func includes(_ kind: IOSConversationSearchResult.Kind) -> Bool {
        switch self {
        case .all:
            true
        case .conversation:
            kind == .conversation
        case .message:
            kind == .message
        }
    }
}

private struct RecentConversationSearchRow: View {
    let summary: ConversationSummary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SearchRowChrome(
                systemImage: summary.isPinned ? "pin.fill" : "bubble.left.fill",
                color: summary.isPinned ? AmberTheme.accentAmber : AmberTheme.accent,
                title: summary.title.isEmpty ? "新对话" : summary.title,
                preview: "\(summary.messageCount) 条消息",
                highlight: "",
                time: relativeTime(ms: summary.updateAt.toEpochMilliseconds())
            )
        }
        .buttonStyle(.plain)
    }
}

private struct SearchResultGroup: View {
    let title: String
    let rows: [IOSConversationSearchResult]
    let action: (IOSConversationSearchResult) -> Void

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
    let row: IOSConversationSearchResult
    let action: () -> Void

    private var systemImage: String {
        row.kind == .conversation ? "bubble.left.fill" : "text.bubble.fill"
    }

    private var color: Color {
        row.kind == .conversation ? AmberTheme.accent : AmberTheme.accentCyan
    }

    var body: some View {
        Button(action: action) {
            SearchRowChrome(
                systemImage: systemImage,
                color: color,
                title: row.title,
                preview: row.preview,
                highlight: row.highlight,
                time: relativeTime(ms: row.updateAt)
            )
        }
        .buttonStyle(.plain)
    }
}

private struct SearchRowChrome: View {
    let systemImage: String
    let color: Color
    let title: String
    let preview: String
    let highlight: String
    let time: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 40, height: 40)
                .background(color.opacity(0.14), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                HighlightedPreview(text: preview, highlight: highlight)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(time)
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .padding(.top, 1)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct HighlightedPreview: View {
    let text: String
    let highlight: String

    var body: some View {
        if let range = text.range(of: highlight, options: [.caseInsensitive, .diacriticInsensitive]), !highlight.isEmpty {
            HStack(spacing: 0) {
                Text(String(text[..<range.lowerBound]))
                Text(String(text[range]))
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

private func relativeTime(ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter.localizedString(for: date, relativeTo: Date())
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
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.light.rawValue

    private var generalEntries: [SettingsHomeEntry] {
        [
            .init(title: "外观", subtitle: nil, value: appearanceModeTitle, systemImage: "circle.lefthalf.filled", color: AmberTheme.accent, route: .appearance),
            .init(title: "显示与字体", subtitle: nil, value: nil, systemImage: "slider.horizontal.3", color: AmberTheme.accentAmber, route: .displayFont)
        ]
    }

    private var agentEntries: [SettingsHomeEntry] {
        [
            .init(title: "核心记忆", subtitle: nil, value: nil, systemImage: "cylinder.split.1x2", color: AmberTheme.accent, route: .memory),
            .init(title: "执行与任务", subtitle: nil, value: nil, systemImage: "waveform.path.ecg", color: AmberTheme.accentGreen, route: .execution),
            .init(title: "技能", subtitle: nil, value: nil, systemImage: "wrench.and.screwdriver", color: AmberTheme.accentAmber, route: .skills),
            .init(title: "权限与批准", subtitle: nil, value: nil, systemImage: "shield", color: AmberTheme.accentCyan, route: .toolPermissions)
        ]
    }

    private var modelServiceEntries: [SettingsHomeEntry] {
        [
            .init(title: "服务商", subtitle: nil, value: nil, systemImage: "server.rack", color: AmberTheme.accent, route: .providers),
            .init(title: "模型与提示词", subtitle: nil, value: nil, systemImage: "cpu", color: AmberTheme.accentAmber, route: .modelDefaults),
            .init(title: "图片生成", subtitle: nil, value: nil, systemImage: "photo.on.rectangle", color: AmberTheme.accentRed, route: .imageGeneration),
            .init(title: "搜索服务", subtitle: nil, value: nil, systemImage: "magnifyingglass", color: AmberTheme.accentGreen, route: .searchServices),
            .init(title: "语音服务", subtitle: nil, value: nil, systemImage: "speaker.wave.2", color: AmberTheme.accentCyan, route: .ttsSettings)
        ]
    }

    private var advancedFeatureEntries: [SettingsHomeEntry] {
        [
            .init(title: "WebMount", subtitle: nil, value: nil, systemImage: "globe", color: AmberTheme.accentGreen, route: .webMount),
            .init(title: "子代理", subtitle: nil, value: nil, systemImage: "person.2", color: AmberTheme.accentRed, route: .subagents),
            .init(title: "模型议会", subtitle: nil, value: nil, systemImage: "bubble.left.and.bubble.right", color: AmberTheme.accent, route: .council),
            .init(title: "小应用", subtitle: nil, value: nil, systemImage: "square.grid.2x2", color: AmberTheme.accentCyan, route: .miniApps),
            .init(title: "深度阅读", subtitle: nil, value: nil, systemImage: "book.pages", color: AmberTheme.accentAmber, route: .board)
        ]
    }

    private var dataEntries: [SettingsHomeEntry] {
        [
            .init(title: "Workspace", subtitle: nil, value: nil, systemImage: "folder.badge.gearshape", color: AmberTheme.accentIndigo, route: .workspace),
            .init(title: "同步备份", subtitle: nil, value: nil, systemImage: "icloud", color: AmberTheme.accentCyan, route: .syncBackup),
            .init(title: "对话存储", subtitle: nil, value: nil, systemImage: "tray.full", color: AmberTheme.accent, route: .conversationStorage)
        ]
    }

    private var appearanceModeTitle: String {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).title
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    settingsSection("通用设置", entries: generalEntries)
                    settingsSection("Agent 设置", entries: agentEntries)
                    settingsSection("模型与服务", entries: modelServiceEntries)
                    settingsSection("高级功能", entries: advancedFeatureEntries)
                    settingsSection("数据设置", entries: dataEntries)
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

    private func settingsSection(_ title: String, entries: [SettingsHomeEntry]) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)
            AmberFormGroup {
                ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                    AmberFormRow(
                        systemImage: entry.systemImage,
                        iconColor: entry.color,
                        title: entry.title,
                        subtitle: entry.subtitle,
                        trailing: entry.value,
                        showsChevron: true
                    ) {
                        router.navigate(to: entry.route)
                    }

                    if index < entries.count - 1 {
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

private struct SettingsHomeEntry: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String?
    let value: String?
    let systemImage: String
    let color: Color
    let route: Route
}

struct WorkspaceView: View {
    @Bindable var workspaceStore: IOSWorkspaceStore
    let focusedItemId: String?

    @Environment(\.dismiss) private var dismiss
    @State private var isImportingFile = false
    @State private var selectedFile: IOSWorkspaceFileRecord?
    @State private var selectedArtifact: IOSWorkspaceArtifactRecord?
    @State private var alertMessage: String?

    init(workspaceStore: IOSWorkspaceStore = .shared, focusedItemId: String? = nil) {
        self.workspaceStore = workspaceStore
        self.focusedItemId = focusedItemId
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    workspaceStats
                    filesSection
                    artifactsSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .fileImporter(
            isPresented: $isImportingFile,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handleImport(result)
        }
        .sheet(item: $selectedFile) { record in
            WorkspaceFileDetailSheet(
                record: record,
                store: workspaceStore,
                onReparse: {
                    Task { await reparse(record) }
                },
                onRemove: {
                    removeFile(record)
                }
            )
        }
        .sheet(item: $selectedArtifact) { record in
            WorkspaceArtifactDetailSheet(
                record: record,
                store: workspaceStore,
                onDelete: {
                    deleteArtifact(record)
                }
            )
        }
        .alert("Workspace", isPresented: Binding(
            get: { alertMessage != nil },
            set: { if !$0 { alertMessage = nil } }
        )) {
            Button("好", role: .cancel) { alertMessage = nil }
        } message: {
            Text(alertMessage ?? "")
        }
        .onAppear {
            focusInitialItem()
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            VStack(alignment: .leading, spacing: 3) {
                Text("Workspace")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("文件上下文与生成结果")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            Button {
                isImportingFile = true
            } label: {
                Image(systemName: "square.and.arrow.down")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(AmberTheme.accent, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("导入文件")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 14)
    }

    private var workspaceStats: some View {
        HStack(spacing: 10) {
            WorkspaceMetricCard(
                title: "文件",
                value: "\(workspaceStore.files.count)",
                systemImage: "doc.text",
                color: AmberTheme.accentIndigo
            )
            WorkspaceMetricCard(
                title: "Artifacts",
                value: "\(workspaceStore.artifacts.count)",
                systemImage: "sparkles.rectangle.stack",
                color: AmberTheme.accentAmber
            )
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var filesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "文件")
            if workspaceStore.recentFiles.isEmpty {
                WorkspaceEmptyState(
                    systemImage: "doc.badge.plus",
                    title: "还没有导入文件",
                    subtitle: "通过 Files 选择的文件会复制进 AmberAgent 的本地 Workspace，不会自动扫描用户目录。"
                )
            } else {
                AmberFormGroup {
                    ForEach(Array(workspaceStore.recentFiles.enumerated()), id: \.element.id) { index, file in
                        WorkspaceFileRow(record: file) {
                            selectedFile = workspaceStore.fileRecord(idOrPath: file.id) ?? file
                        }
                        if index < workspaceStore.recentFiles.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
        }
    }

    private var artifactsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Artifacts")
            if workspaceStore.recentArtifacts.isEmpty {
                WorkspaceEmptyState(
                    systemImage: "tray",
                    title: "还没有保存的 Artifact",
                    subtitle: "聊天、MiniApp、Deep Read 或工具输出可以保存到这里统一管理。"
                )
            } else {
                AmberFormGroup {
                    ForEach(Array(workspaceStore.recentArtifacts.enumerated()), id: \.element.id) { index, artifact in
                        WorkspaceArtifactRow(record: artifact) {
                            selectedArtifact = workspaceStore.artifacts.first { $0.id == artifact.id } ?? artifact
                        }
                        if index < workspaceStore.recentArtifacts.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else {
                alertMessage = "没有选择文件。"
                return
            }
            Task {
                do {
                    let record = try await workspaceStore.importFile(url: url, source: "workspace_picker")
                    selectedFile = record
                } catch {
                    alertMessage = error.localizedDescription
                }
            }
        case .failure(let error):
            alertMessage = "文件选择失败：\(error.localizedDescription)"
        }
    }

    private func reparse(_ record: IOSWorkspaceFileRecord) async {
        do {
            selectedFile = try await workspaceStore.reparseFile(id: record.id)
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func removeFile(_ record: IOSWorkspaceFileRecord) {
        do {
            try workspaceStore.removeFile(id: record.id)
            selectedFile = nil
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func deleteArtifact(_ record: IOSWorkspaceArtifactRecord) {
        do {
            try workspaceStore.deleteArtifact(id: record.id)
            selectedArtifact = nil
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    private func focusInitialItem() {
        guard let focusedItemId else { return }
        if let file = workspaceStore.fileRecord(idOrPath: focusedItemId) {
            selectedFile = file
            return
        }
        if let artifact = workspaceStore.artifacts.first(where: { $0.id == focusedItemId }) {
            selectedArtifact = artifact
        }
    }
}

struct AssistantsView: View {
    var body: some View {
        PlaceholderDetailView(
            title: "Amber Assistant",
            subtitle: "iOS 只保留一个 Amber Assistant；模型、记忆与工具在设置中管理。",
            systemImage: "sparkles"
        )
    }
}

private struct WorkspaceMetricCard: View {
    let title: String
    let value: String
    let systemImage: String
    let color: Color

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 32, height: 32)
                .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(value)
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(AmberTheme.foreground)
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 62)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }
}

private struct WorkspaceEmptyState: View {
    let systemImage: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(AmberTheme.muted2)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.vertical, 28)
    }
}

private struct WorkspaceFileRow: View {
    let record: IOSWorkspaceFileRecord
    let action: () -> Void

    var body: some View {
        AmberFormRow(
            systemImage: statusIcon,
            iconColor: statusColor,
            title: record.displayName,
            subtitle: "\(record.byteSummary) · \(record.status.title) · /workspace/\(record.workspacePath)",
            trailing: WorkspaceDateFormat.short(record.updatedAtMillis),
            showsChevron: true,
            action: action
        )
    }

    private var statusIcon: String {
        switch record.status {
        case .ready: "doc.text"
        case .missing: "doc.badge.exclamationmark"
        case .parseFailed: "exclamationmark.triangle"
        case .unsupported: "nosign"
        case .tooLarge: "externaldrive.badge.exclamationmark"
        case .needsReauthorization: "lock.open"
        }
    }

    private var statusColor: Color {
        switch record.status {
        case .ready: AmberTheme.accentIndigo
        case .unsupported, .needsReauthorization: AmberTheme.accentAmber
        case .missing, .parseFailed, .tooLarge: AmberTheme.accentRed
        }
    }
}

private struct WorkspaceArtifactRow: View {
    let record: IOSWorkspaceArtifactRecord
    let action: () -> Void

    var body: some View {
        AmberFormRow(
            systemImage: "sparkles.rectangle.stack",
            iconColor: AmberTheme.accentAmber,
            title: record.title,
            subtitle: "\(record.type.title) · \(DocumentAccessStore.formatBytes(record.contentBytes))",
            trailing: WorkspaceDateFormat.short(record.updatedAtMillis),
            showsChevron: true,
            action: action
        )
    }
}

private struct WorkspaceFileDetailSheet: View {
    let record: IOSWorkspaceFileRecord
    @Bindable var store: IOSWorkspaceStore
    let onReparse: () -> Void
    let onRemove: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WorkspaceDetailHeader(
                        systemImage: "doc.text",
                        title: record.displayName,
                        subtitle: "/workspace/\(record.workspacePath)"
                    )

                    WorkspaceInfoGrid(rows: [
                        ("状态", record.status.title),
                        ("大小", record.byteSummary),
                        ("类型", record.mimeType),
                        ("字符", "\(record.characterCount)"),
                        ("来源", record.source),
                        ("更新", WorkspaceDateFormat.long(record.updatedAtMillis))
                    ])

                    if !record.statusMessage.isEmpty {
                        WorkspaceStatusBanner(status: record.status, message: record.statusMessage)
                    }

                    WorkspacePreviewBlock(text: record.preview, emptyText: previewEmptyText)
                }
                .padding(16)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        onReparse()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("重新解析")

                    Button(role: .destructive) {
                        onRemove()
                    } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel("移除文件")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var previewEmptyText: String {
        switch record.status {
        case .ready:
            "没有可预览文本。"
        case .missing:
            "文件副本丢失，请重新导入。"
        case .unsupported:
            "此格式暂不支持文本预览。"
        case .tooLarge:
            "文件超过本地解析上限。"
        case .needsReauthorization:
            "需要从 Files 重新选择文件。"
        case .parseFailed:
            "解析失败，可尝试重新解析。"
        }
    }
}

private struct WorkspaceArtifactDetailSheet: View {
    let record: IOSWorkspaceArtifactRecord
    @Bindable var store: IOSWorkspaceStore
    let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss

    private var content: String {
        (try? store.artifactContent(id: record.id)) ?? "Artifact 内容丢失或读取失败。"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WorkspaceDetailHeader(
                        systemImage: "sparkles.rectangle.stack",
                        title: record.title,
                        subtitle: record.type.title
                    )
                    WorkspaceInfoGrid(rows: [
                        ("大小", DocumentAccessStore.formatBytes(record.contentBytes)),
                        ("来源", record.sourceKind),
                        ("创建", WorkspaceDateFormat.long(record.createdAtMillis)),
                        ("更新", WorkspaceDateFormat.long(record.updatedAtMillis))
                    ])
                    WorkspacePreviewBlock(text: content, emptyText: "Artifact 内容为空。")
                }
                .padding(16)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(role: .destructive) {
                        onDelete()
                    } label: {
                        Image(systemName: "trash")
                    }
                    .accessibilityLabel("删除 Artifact")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct WorkspaceDetailHeader: View {
    let systemImage: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 42, height: 42)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(3)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .textSelection(.enabled)
            }
        }
    }
}

private struct WorkspaceInfoGrid: View {
    let rows: [(String, String)]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(alignment: .top) {
                    Text(row.0)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(width: 56, alignment: .leading)
                    Text(row.1)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .padding(.vertical, 8)
                if index < rows.count - 1 {
                    Divider().overlay(AmberTheme.borderSoft)
                }
            }
        }
        .padding(.horizontal, 12)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
    }
}

private struct WorkspaceStatusBanner: View {
    let status: IOSWorkspaceFileStatus
    let message: String

    var body: some View {
        Label(message, systemImage: status == .ready ? "checkmark.circle" : "exclamationmark.triangle")
            .font(.caption)
            .foregroundStyle(status == .ready ? AmberTheme.accentGreen : AmberTheme.accentAmber)
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background((status == .ready ? AmberTheme.accentGreen : AmberTheme.accentAmber).opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct WorkspacePreviewBlock: View {
    let text: String
    let emptyText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("预览")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .textCase(.uppercase)
            Text(text.isEmpty ? emptyText : text)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(text.isEmpty ? AmberTheme.muted : AmberTheme.foreground2)
                .lineSpacing(3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
        }
    }
}

private enum WorkspaceDateFormat {
    static func short(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    static func long(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
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

struct CapabilityGateLockedView: View {
    let gate: IOSCapabilityGate

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 12) {
                Image(systemName: "lock.shield")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 58, height: 58)
                    .background(AmberTheme.accentAmber.opacity(0.12), in: Circle())

                Text(gate.title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(gate.disabledReason)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 28)

                Text("这是 AmberAgent 的受控能力。默认关闭用于保护工具执行、外部连接和远程操作；可在设置页对应行开启。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted2)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .padding(.horizontal, 28)
            }
            .padding(.horizontal, 18)
        }
        .navigationBarBackButtonHidden(false)
    }
}
