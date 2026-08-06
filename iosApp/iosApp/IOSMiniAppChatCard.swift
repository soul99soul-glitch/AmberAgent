import Shared
import SwiftUI
import UIKit

/// Chat-inline MiniApp card: run / modify / export / versions.
struct IOSMiniAppChatCard: View {
    let part: UIMessagePart.MiniApp
    var onRun: () -> Void = {}
    var onOpenList: () -> Void = {}
    /// Returns false when the modify request was rejected (e.g. generation already active).
    var onModify: (String) -> Bool = { _ in true }

    @State private var showModifySheet = false
    @State private var modifyPrompt = ""
    @State private var modifyBusyRejected = false
    @State private var exportShare: MiniAppExportShare?
    @State private var exportError: MiniAppExportError?
    @State private var showVersionHistory = false
    @State private var versions: [IOSMiniAppVersionRecord] = []
    @State private var cardTitle: String = ""
    @State private var cardVersion: Int = 1

    private var repository: IOSMiniAppRepository { .shared }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                Text(part.iconEmoji?.nilIfBlank ?? "▣")
                    .font(.system(size: 28))
                    .frame(width: 44, height: 44)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text(displayTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(2)
                    Text("v\(displayVersion) · \(part.category?.nilIfBlank ?? "tool")")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }

                Spacer(minLength: 0)

                Button("全部", action: onOpenList)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .buttonStyle(.plain)
                    .frame(minWidth: 44, minHeight: 44)
            }

            if !part.description_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(part.description_)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ViewThatFits(in: .horizontal) {
                actionButtons(stacked: false)
                actionButtons(stacked: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(AmberTheme.border.opacity(0.7), lineWidth: 1)
        )
        .task(id: part.appId) {
            refreshHeaderFromRepository()
        }
        .sheet(isPresented: $showModifySheet) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 12) {
                    Text("描述你想改的地方")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    TextEditor(text: $modifyPrompt)
                        .frame(minHeight: 140)
                        .padding(10)
                        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .scrollContentBackground(.hidden)
                        .accessibilityLabel("小应用修改说明")
                        .accessibilityHint("描述希望修改的内容")
                    if modifyBusyRejected {
                        Text("当前正在生成回复，请稍后再修改。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.accentAmber)
                    }
                    Spacer(minLength: 0)
                }
                .padding(16)
                .navigationTitle("修改小应用")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showModifySheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("发送") {
                            let prompt = modifyPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !prompt.isEmpty else { return }
                            let accepted = onModify(
                                IOSMiniAppChatMessageFactory.revisionPrompt(
                                    appId: part.appId,
                                    title: displayTitle,
                                    version: displayVersion,
                                    request: prompt
                                )
                            )
                            if accepted {
                                showModifySheet = false
                            } else {
                                modifyBusyRejected = true
                            }
                        }
                        .disabled(modifyPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showVersionHistory) {
            NavigationStack {
                Group {
                    if versions.isEmpty {
                        ContentUnavailableView(
                            "暂无历史版本",
                            systemImage: "clock",
                            description: Text("保存或修改小应用后会出现版本记录")
                        )
                    } else {
                        List {
                            ForEach(versions) { version in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text("v\(version.versionNumber)")
                                            .font(.subheadline.weight(.semibold))
                                        if version.versionNumber == displayVersion {
                                            Text("当前")
                                                .font(.caption2.weight(.semibold))
                                                .foregroundStyle(AmberTheme.accent)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(AmberTheme.accentTint, in: Capsule())
                                        }
                                        Spacer()
                                        Text(Self.formatDate(version.createdAt))
                                            .font(.caption)
                                            .foregroundStyle(AmberTheme.muted)
                                    }
                                    Text(version.changeNote ?? "小应用版本")
                                        .font(.caption)
                                        .foregroundStyle(AmberTheme.muted)
                                        .lineLimit(3)
                                }
                                .padding(.vertical, 2)
                            }
                        }
                        .scrollContentBackground(.hidden)
                        .background(AmberTheme.background)
                        .listStyle(.insetGrouped)
                    }
                }
                .navigationTitle("版本历史")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { showVersionHistory = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $exportShare) { share in
            MiniAppActivityShareSheet(items: [share.url])
        }
        .alert(item: $exportError) { error in
            Alert(
                title: Text("无法导出小应用"),
                message: Text(error.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var displayTitle: String {
        let trimmed = cardTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? part.title : trimmed
    }

    private var displayVersion: Int {
        max(cardVersion, Int(part.version))
    }

    @ViewBuilder
    private func actionButtons(stacked: Bool) -> some View {
        Group {
            if stacked {
                VStack(spacing: 8) { actionButtonItems(stacked: true) }
            } else {
                HStack(spacing: 8) { actionButtonItems(stacked: false) }
            }
        }
    }

    @ViewBuilder
    private func actionButtonItems(stacked: Bool) -> some View {
        miniAppActionButton(
            title: "运行",
            systemImage: "play.fill",
            emphasized: true,
            stacked: stacked,
            action: onRun
        )
        miniAppActionButton(title: "修改", systemImage: "pencil", emphasized: false, stacked: stacked) {
            modifyBusyRejected = false
            modifyPrompt = ""
            showModifySheet = true
        }
        miniAppActionButton(
            title: "导出",
            systemImage: "square.and.arrow.up",
            emphasized: false,
            stacked: stacked,
            action: exportHTML
        )
        miniAppActionButton(title: "历史", systemImage: "clock.arrow.circlepath", emphasized: false, stacked: stacked) {
            versions = repository.versions(appId: part.appId)
            showVersionHistory = true
        }
    }

    @ViewBuilder
    private func miniAppActionButton(
        title: String,
        systemImage: String,
        emphasized: Bool,
        stacked: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.caption.weight(.semibold))
                .frame(maxWidth: stacked ? .infinity : nil)
                .frame(minHeight: 34)
                .padding(.horizontal, 4)
        }
        .buttonStyle(.bordered)
        .tint(emphasized ? AmberTheme.accent : AmberTheme.muted)
        .controlSize(.small)
        .fixedSize(horizontal: !stacked, vertical: false)
        .frame(maxWidth: stacked ? .infinity : nil, minHeight: 44)
        .contentShape(Rectangle())
    }

    private func refreshHeaderFromRepository() {
        guard let record = repository.get(part.appId) else {
            cardTitle = part.title
            cardVersion = Int(part.version)
            return
        }
        cardTitle = record.title
        cardVersion = record.version
    }

    private func exportHTML() {
        guard let record = repository.get(part.appId) else {
            exportError = MiniAppExportError(message: "找不到这个小应用的已保存内容。")
            return
        }
        let safeName = record.title
            .replacingOccurrences(of: "/", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let filename = "\(safeName.isEmpty ? "miniapp" : safeName)-v\(record.version).html"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        do {
            try record.htmlContent.write(to: url, atomically: true, encoding: .utf8)
            exportShare = MiniAppExportShare(url: url)
        } catch {
            exportError = MiniAppExportError(message: "无法写入导出文件：\(error.localizedDescription)")
        }
    }

    private static func formatDate(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
            .formatted(date: .abbreviated, time: .shortened)
    }
}

private struct MiniAppExportShare: Identifiable {
    let id = UUID()
    let url: URL
}

private struct MiniAppExportError: Identifiable {
    let id = UUID()
    let message: String
}

private struct MiniAppActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
