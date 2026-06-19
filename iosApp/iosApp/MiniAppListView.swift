import SwiftUI

@MainActor
struct MiniAppListView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var repository = IOSMiniAppRepository.shared
    @State private var actionError: String?
    @State private var renameTarget: IOSMiniAppRecord?
    @State private var deleteTarget: IOSMiniAppRecord?

    private let evidenceRows: [MiniAppCapabilityRow] = [
        .init(
            title: "iOS repository",
            subtitle: "小应用记录、HTML、版本、grant、sharedData 和 audit metadata 已落到 Documents/miniapps/miniapps.json。",
            status: "本地已接",
            tint: AmberTheme.accent
        ),
        .init(
            title: "MiniAppRepository / DAOs",
            subtitle: "Android 使用 Room；iOS 先用原子 JSON store 对齐 list/get/saveRevision/rename/pin/delete/markRun 等语义。",
            status: "对齐中",
            tint: .blue
        ),
        .init(
            title: "Prompt / Output transformers",
            subtitle: "显式 MiniApp 请求会追加生成格式，完成后解析符合格式的 assistant 输出并保存。",
            status: "最小链路",
            tint: .purple
        ),
        .init(
            title: "Runner / WebView bridge",
            subtitle: "Runner 从 repository 读取 appId、校验 HTML、加载 WKWebView，并通过 grant-gated bridge dispatch。",
            status: "本地已接",
            tint: .green
        )
    ]

    private let handlingRows: [MiniAppCapabilityRow] = [
        .init(
            title: "持久化位置",
            subtitle: "Documents/miniapps/miniapps.json；解码失败会阻止覆盖，避免清空用户数据。",
            status: "原子写入",
            tint: AmberTheme.accentGreen
        ),
        .init(
            title: "Runner",
            subtitle: "打开真实 appId 后会 markRun，支持保存新版本、恢复历史版本和修改 grant。",
            status: "已接",
            tint: AmberTheme.accentGreen
        ),
        .init(
            title: "受限能力",
            subtitle: "search/fetch/ai/clipboard/host 写回必须过 grant 和设置；无凭证或未实现时返回明确错误。",
            status: "诚实错误",
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
                        repositorySection
                        evidenceSection
                        handlingSection
                        MiniAppCapabilityNote("设置页仍以 KMP 默认字段展示为主；本页的列表、管理动作、grant 和版本历史来自 iOS 本地 MiniApp repository。")
                            .padding(.top, 14)
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            repository.reload()
        }
        .sheet(item: $renameTarget) { app in
            MiniAppRenameSheet(app: app) { title, description in
                perform {
                    try repository.rename(id: app.id, title: title, description: description)
                }
                renameTarget = nil
            }
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .confirmationDialog("删除小应用？", isPresented: isDeleteConfirmationPresented, titleVisibility: .visible) {
            if let app = deleteTarget {
                Button("删除「\(app.title)」", role: .destructive) {
                    perform {
                        try repository.delete(id: app.id)
                    }
                    deleteTarget = nil
                }
            }
            Button("取消", role: .cancel) {
                deleteTarget = nil
            }
        } message: {
            Text("会同时删除版本、grant、audit 和 sharedData，本地操作不可恢复。")
        }
    }

    private var isDeleteConfirmationPresented: Binding<Bool> {
        Binding {
            deleteTarget != nil
        } set: { isPresented in
            if !isPresented {
                deleteTarget = nil
            }
        }
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
                Text("\(repository.apps.count) 个本地小应用 · Documents 持久化")
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
        Text("这里展示 iOS 本地 MiniApp repository 中的真实记录。首次启动会 seed 一个样例；聊天生成或 Runner 保存的新版本会写入同一份 Documents 数据，并在重启后恢复。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var repositorySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已保存小应用")

            if let storageError = repository.storageError {
                MiniAppCapabilityNote("MiniApp store 读取异常：\(storageError)。为保护数据，当前不会覆盖原文件。")
                    .padding(.bottom, 8)
                    .foregroundStyle(AmberTheme.accentRed)
            }

            if let actionError {
                MiniAppCapabilityNote(actionError)
                    .padding(.bottom, 8)
                    .foregroundStyle(AmberTheme.accentRed)
            }

            if repository.apps.isEmpty {
                AmberFormGroup {
                    MiniAppCapabilityStatusRow(row: .init(
                        title: "暂无小应用",
                        subtitle: "聊天中明确要求生成 MiniApp，或恢复可读的 Documents/miniapps 数据后会出现在这里。",
                        status: "空",
                        tint: AmberTheme.muted
                    ))
                }
            } else {
                AmberFormGroup {
                    ForEach(Array(repository.apps.enumerated()), id: \.element.id) { index, app in
                        MiniAppListRow(
                            app: app,
                            grantSummary: grantSummary(for: app),
                            onOpen: { router.navigate(to: .miniAppRunner(appId: app.id)) },
                            onPin: {
                                perform {
                                    try repository.setPinned(id: app.id, pinned: !app.pinned)
                                }
                            },
                            onRename: { renameTarget = app },
                            onDelete: { deleteTarget = app }
                        )

                        if index < repository.apps.count - 1 {
                            MiniAppCapabilityDivider()
                        }
                    }
                }
            }
        }
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

    private func grantSummary(for app: IOSMiniAppRecord) -> String {
        guard !app.permissions.isEmpty else { return "无权限" }
        let allowed = app.permissions.filter { repository.grantDecision(appId: app.id, permission: $0) == .allow }.count
        let denied = app.permissions.filter { repository.grantDecision(appId: app.id, permission: $0) == .deny }.count
        let unset = app.permissions.count - allowed - denied
        if unset > 0 { return "\(allowed) 允许 · \(unset) 未设" }
        if denied > 0 { return "\(allowed) 允许 · \(denied) 拒绝" }
        return "\(allowed) 允许"
    }

    private func perform(_ action: () throws -> Void) {
        do {
            try action()
            actionError = nil
        } catch {
            actionError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
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

private struct MiniAppListRow: View {
    let app: IOSMiniAppRecord
    let grantSummary: String
    let onOpen: () -> Void
    let onPin: () -> Void
    let onRename: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Button(action: onOpen) {
                HStack(alignment: .top, spacing: 12) {
                    Text(app.iconEmoji ?? "▣")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 32, height: 32)
                        .background(AmberTheme.accentCyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                    VStack(alignment: .leading, spacing: 5) {
                        HStack(spacing: 6) {
                            if app.pinned {
                                Image(systemName: "pin.fill")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(AmberTheme.accentAmber)
                            }
                            Text(app.title)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(AmberTheme.foreground)
                                .lineLimit(1)
                        }

                        Text(app.description)
                            .font(.system(size: 12.5))
                            .lineSpacing(3)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)

                        HStack(spacing: 8) {
                            MiniAppPill(text: "v\(app.version)")
                            MiniAppPill(text: "\(app.runCount) 次运行")
                            MiniAppPill(text: grantSummary)
                        }

                        Text("appId: \(app.id)")
                            .font(.system(size: 10.5, design: .monospaced))
                            .foregroundStyle(AmberTheme.muted2)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                Button(action: onPin) {
                    Label(app.pinned ? "取消置顶" : "置顶", systemImage: app.pinned ? "pin.slash" : "pin")
                }
                Button(action: onRename) {
                    Label("重命名", systemImage: "pencil")
                }
                Button(role: .destructive, action: onDelete) {
                    Label("删除", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

private struct MiniAppPill: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 10.5, weight: .semibold))
            .foregroundStyle(AmberTheme.foreground2)
            .padding(.horizontal, 7)
            .frame(height: 22)
            .background(AmberTheme.surface2, in: Capsule())
    }
}

private struct MiniAppRenameSheet: View {
    @Environment(\.dismiss) private var dismiss
    let app: IOSMiniAppRecord
    let onSave: (String, String) -> Void

    @State private var title: String
    @State private var description: String

    init(app: IOSMiniAppRecord, onSave: @escaping (String, String) -> Void) {
        self.app = app
        self.onSave = onSave
        self._title = State(initialValue: app.title)
        self._description = State(initialValue: app.description)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                TextField("名称", text: $title)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1)

                TextField("描述", text: $description, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(2...4)

                Text("标题最多 40 字，描述最多 120 字。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)

                Spacer()
            }
            .padding(18)
            .background(AmberTheme.background)
            .navigationTitle("重命名小应用")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(title, description)
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        MiniAppListView()
    }
    .environment(RouterPath())
}
