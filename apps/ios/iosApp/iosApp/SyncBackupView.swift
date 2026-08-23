import Foundation
import SwiftUI
import Shared
import UniformTypeIdentifiers

struct SyncBackupView: View {
    let sharedSettings: IOSSharedSettingsStore
    let conversationStore: IOSConversationStore

    @Environment(\.dismiss) private var dismiss

    @State private var passphrase = ""
    @State private var exportedFile: IOSSyncBackupDocument?
    @State private var isExportingFile = false
    @State private var isImportingFile = false
    @State private var alert: SyncBackupAlert?
    @State private var remoteStatus: IOSRemoteSyncStatus
    @State private var providerKind: IOSRemoteProviderKind = .localFolder
    @State private var remoteSnapshots: [IOSRemoteSnapshot] = []
    @State private var selectedSnapshotID: String?
    @State private var pendingRestore: IOSPendingSyncRestore?
    @State private var pendingConflict: IOSSyncConflict?
    @State private var isRemoteBusy = false
    @State private var remoteMessage = ""
    @State private var webDAVBaseURL = ""
    @State private var webDAVPath = "AmberAgent"
    @State private var webDAVUsername = ""
    @State private var webDAVPassword = ""

    init(sharedSettings: IOSSharedSettingsStore, conversationStore: IOSConversationStore) {
        self.sharedSettings = sharedSettings
        self.conversationStore = conversationStore
        self._remoteStatus = State(initialValue: sharedSettings.remoteSyncStatus)
    }

    private var headerSubtitle: String {
        sharedSettings.isCapabilityGateEnabled(.remoteSync) ? "本地备份 · 文件夹同步 · WebDAV" : "本地设置备份"
    }

    private var currentRows: [SyncBackupRow] {
        [
            .init(
                title: "同步位置",
                subtitle: "本机文件夹可直接使用；WebDAV 需要填写地址和账号。",
                value: providerKind.displayName,
                color: providerKind == .localFolder || providerKind == .webDAV ? AmberTheme.accentGreen : AmberTheme.accentAmber
            ),
            .init(
                title: "上次上传",
                subtitle: "最近一次成功上传备份的时间。",
                value: formatEpoch(remoteStatus.lastUploadAt),
                color: remoteStatus.lastUploadAt > 0 ? AmberTheme.accentGreen : AmberTheme.muted2
            ),
            .init(
                title: "上次恢复",
                subtitle: "只有手动应用恢复成功后才会更新。",
                value: formatEpoch(remoteStatus.lastDownloadAt),
                color: remoteStatus.lastDownloadAt > 0 ? AmberTheme.accentGreen : AmberTheme.muted2
            ),
            .init(
                title: "远端版本",
                subtitle: "用于判断远端备份是否比本机记录更新。",
                value: providerRemoteRevision.isEmpty ? "(空)" : String(providerRemoteRevision.prefix(12)) + "...",
                color: providerRemoteRevision.isEmpty ? AmberTheme.muted2 : AmberTheme.foreground2
            ),
            .init(
                title: "最近错误",
                subtitle: "上传、下载或恢复失败时会显示在这里。",
                value: remoteStatus.lastError.isEmpty ? "无" : "有错误",
                color: remoteStatus.lastError.isEmpty ? AmberTheme.accentGreen : AmberTheme.accentRed
            )
        ]
    }

    private var providerRemoteRevision: String {
        remoteStatus.remoteRevision(for: providerKind)
    }

    private var selectableRemoteProviders: [IOSRemoteProviderKind] {
        [.localFolder, .webDAV]
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        localBackupSection
                        remoteSyncGateSection
                        if sharedSettings.isCapabilityGateEnabled(.remoteSync) {
                            remoteStatusSection
                            remoteProviderSection
                            remoteSnapshotSection
                            restorePreviewSection
                        }
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .fileExporter(
            isPresented: $isExportingFile,
            document: exportedFile,
            contentType: .amberBackup,
            defaultFilename: "amber-settings-\(exportFileStamp()).amberbackup"
        ) { result in
            switch result {
            case .success:
                alert = .success("已导出加密备份")
            case .failure(let error):
                alert = .error("导出文件失败：\(error.localizedDescription)")
            }
        }
        .fileImporter(
            isPresented: $isImportingFile,
            allowedContentTypes: [.amberBackup, .zip, .data, .item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                importSettingsBackup(from: url)
            case .failure(let error):
                alert = .error("选择文件失败：\(error.localizedDescription)")
            }
        }
        .alert(item: $alert) { alert in
            Alert(title: Text(alert.title), message: Text(alert.message), dismissButton: .default(Text("好")))
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("同步与备份")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(headerSubtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("导出一份加密备份（设置 + 会话历史），或预览本地备份文件后手动恢复。远端同步是高级功能，需要单独开启。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var localBackupSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "本地备份")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 10) {
                    SecureField("加密口令（可留空）", text: $passphrase)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    HStack(spacing: 10) {
                        Button(action: exportSettingsBackup) {
                            Label("导出备份", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)

                        Button(action: { isImportingFile = true }) {
                            Label("预览导入", systemImage: "doc.text.magnifyingglass")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            SyncBackupNote("选择本地文件后会先进入恢复预览；点击“应用恢复”才会写入当前设置。")
        }
    }

    private var remoteSyncGateSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "远端同步")
            AmberFormGroup {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(AmberTheme.accentCyan)
                        .frame(width: 28, height: 28)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("启用远端同步")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text(sharedSettings.isCapabilityGateEnabled(.remoteSync) ? "已开启 · 可使用本机文件夹和 WebDAV" : "未开启 · 仅保留本地备份")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Toggle(
                        "",
                        isOn: Binding(
                            get: { sharedSettings.isCapabilityGateEnabled(.remoteSync) },
                            set: { sharedSettings.setCapabilityGate(.remoteSync, enabled: $0) }
                        )
                    )
                    .labelsHidden()
                    .tint(AmberTheme.accent)
                    .accessibilityLabel("启用远端同步")
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)
            }
            if !sharedSettings.isCapabilityGateEnabled(.remoteSync) {
                SyncBackupNote("关闭时不会显示 WebDAV、远端快照或上传下载操作。Google Drive 和 S3 当前不可用。")
            }
        }
    }

    private var remoteStatusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "远端同步状态")
            AmberFormGroup {
                ForEach(Array(currentRows.enumerated()), id: \.element.id) { index, row in
                    SyncBackupStatusRow(row: row)
                    if index < currentRows.count - 1 {
                        SyncBackupDivider()
                    }
                }
            }
            if !remoteStatus.lastError.isEmpty {
                SyncBackupNote("最近错误：\(remoteStatus.lastError)")
            }
        }
    }

    private var remoteProviderSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "同步位置")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("同步位置", selection: $providerKind) {
                        ForEach(selectableRemoteProviders) { kind in
                            Text(kind.displayName).tag(kind)
                        }
                    }
                    .pickerStyle(.segmented)

                    if providerKind == .localFolder {
                        Text(IOSLocalFolderSyncProvider.defaultFolderURL().path)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(3)
                            .textSelection(.enabled)
                    } else if providerKind == .webDAV {
                        VStack(spacing: 8) {
                            TextField("WebDAV Base URL", text: $webDAVBaseURL)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            TextField("Path", text: $webDAVPath)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            TextField("Username", text: $webDAVUsername)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            SecureField("Password", text: $webDAVPassword)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }
                        .textFieldStyle(.roundedBorder)
                    } else {
                        Text("\(providerKind.displayName) 当前不可用。请使用本机文件夹或 WebDAV。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if pendingConflict != nil {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("检测到远端冲突")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.accentAmber)
                            Text("远端已有更新的备份。继续上传会覆盖当前同步位置里的最新快照。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(10)
                        .background(AmberTheme.accentAmber.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }

                    HStack(spacing: 10) {
                        Button {
                            Task { await listRemoteSnapshots() }
                        } label: {
                            Label("列出快照", systemImage: "list.bullet.rectangle")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(isRemoteBusy)

                        Button {
                            Task { await uploadRemoteSnapshot(force: false) }
                        } label: {
                            Label("上传", systemImage: "icloud.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isRemoteBusy)
                    }

                    if pendingConflict != nil {
                        Button {
                            Task { await uploadRemoteSnapshot(force: true) }
                        } label: {
                            Label("确认覆盖上传", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(AmberTheme.accentAmber)
                        .disabled(isRemoteBusy)
                    }

                    if !remoteMessage.isEmpty {
                        Text(remoteMessage)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            SyncBackupNote("WebDAV 只有在你填写配置并点击操作时才会发起网络请求。")
        }
    }

    private var remoteSnapshotSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "远端快照")
            AmberFormGroup {
                if remoteSnapshots.isEmpty {
                    Text(isRemoteBusy ? "正在读取..." : "暂无快照")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 13)
                } else {
                    ForEach(remoteSnapshots) { snapshot in
                        Button {
                            selectedSnapshotID = snapshot.id
                        } label: {
                            SyncRemoteSnapshotRow(
                                snapshot: snapshot,
                                isSelected: snapshot.id == selectedSnapshotID
                            )
                        }
                        .buttonStyle(.plain)
                        if snapshot.id != remoteSnapshots.last?.id {
                            SyncBackupDivider()
                        }
                    }
                }
            }

            if selectedSnapshot != nil {
                HStack(spacing: 10) {
                    Button {
                        Task { await downloadSelectedSnapshotForPreview() }
                    } label: {
                        Label("下载预览", systemImage: "icloud.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isRemoteBusy)

                    Button {
                        Task { await deleteSelectedSnapshot() }
                    } label: {
                        Label("删除快照", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(AmberTheme.accentRed)
                    .disabled(isRemoteBusy)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
    }

    @ViewBuilder
    private var restorePreviewSection: some View {
        if let pendingRestore {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "恢复预览")
                AmberFormGroup {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(pendingRestore.sourceLabel)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)
                        Text(restorePreviewText(pendingRestore.preview))
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                        if !pendingRestore.preview.datasets.isEmpty {
                            VStack(spacing: 6) {
                                ForEach(pendingRestore.preview.datasets, id: \.id) { dataset in
                                    HStack {
                                        Text(dataset.id)
                                            .font(.caption)
                                            .foregroundStyle(AmberTheme.foreground2)
                                        Spacer()
                                        Text("\(dataset.recordCount) / \(ByteCountFormatter.string(fromByteCount: dataset.byteCount, countStyle: .file))")
                                            .font(.caption2.weight(.semibold))
                                            .foregroundStyle(AmberTheme.muted)
                                    }
                                }
                            }
                        }

                        HStack(spacing: 10) {
                            Button {
                                Task { await applyPendingRestore() }
                            } label: {
                                Label("应用恢复", systemImage: "checkmark.circle")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)

                            Button {
                                self.pendingRestore = nil
                            } label: {
                                Label("取消", systemImage: "xmark.circle")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                }
                SyncBackupNote("预览阶段不会修改当前设置。确认内容无误后再应用恢复。")
            }
        }
    }

    private func exportSettingsBackup() {
        do {
            // Include conversations when present (Android SyncArchiveManager
            // parity). The conversations dir is Documents/conversations (the
            // same path IOSConversationStore uses by default).
            let conversationsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
                .appendingPathComponent("conversations")
            let conversationsZip = try conversationsDir.flatMap {
                try IOSSyncBackup.conversationsZip(fromDirectory: $0)
            }
            let data = try IOSSyncBackup.export(
                settings: sharedSettings.snapshot,
                passphrase: passphrase,
                conversationsZip: conversationsZip
            )
            exportedFile = IOSSyncBackupDocument(data: data)
            isExportingFile = true
        } catch {
            alert = .error("导出失败：\(error.localizedDescription)")
        }
    }

    private func importSettingsBackup(from url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        do {
            let data = try Data(contentsOf: url)
            let preview = try IOSSyncBackup.restorePreview(data: data, passphrase: passphrase, fileName: url.lastPathComponent)
            pendingRestore = IOSPendingSyncRestore(
                sourceLabel: "本地文件：\(url.lastPathComponent)",
                data: data,
                snapshot: nil,
                preview: preview
            )
            alert = .success("已读取备份预览，确认后可应用恢复。")
        } catch {
            alert = .error("导入失败：\(error.localizedDescription)")
        }
    }

    @MainActor
    private func listRemoteSnapshots() async {
        await runRemoteOperation(successMessage: "已读取远端快照列表") { provider in
            let snapshots = try await provider.listSnapshots()
            remoteSnapshots = snapshots
            selectedSnapshotID = snapshots.first?.id
            pendingConflict = nil
        }
    }

    @MainActor
    private func uploadRemoteSnapshot(force: Bool) async {
        await runRemoteOperation(successMessage: "已上传远端快照") { provider in
            let existing = try await provider.listSnapshots()
            let scopedStatus = remoteStatus.scoped(to: provider.kind)
            if !force, let conflict = IOSSyncConflictResolver.conflict(status: scopedStatus, remoteSnapshots: existing) {
                remoteSnapshots = existing
                selectedSnapshotID = conflict.remoteSnapshot.id
                pendingConflict = conflict
                remoteMessage = "上传已暂停：请先处理远端冲突。"
                return
            }

            let conversationsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
                .appendingPathComponent("conversations")
            let conversationsZip = try conversationsDir.flatMap {
                try IOSSyncBackup.conversationsZip(fromDirectory: $0)
            }
            let data = try IOSSyncBackup.export(
                settings: sharedSettings.snapshot,
                passphrase: passphrase,
                remoteRevision: scopedStatus.remoteRevision,
                conversationsZip: conversationsZip
            )
            let preview = try IOSSyncBackup.restorePreview(data: data, passphrase: passphrase)
            let fileName = IOSRemoteSnapshot.fileName(for: preview.manifest)
            let snapshot = try await provider.uploadSnapshot(data: data, fileName: fileName, manifest: preview.manifest)
            sharedSettings.recordRemoteUpload(snapshot: snapshot, preview: preview)
            refreshRemoteStatus()
            pendingConflict = nil
            remoteSnapshots = try await provider.listSnapshots()
            selectedSnapshotID = snapshot.id
        }
    }

    @MainActor
    private func downloadSelectedSnapshotForPreview() async {
        guard let snapshot = selectedSnapshot else { return }
        await runRemoteOperation(successMessage: "已下载并解析恢复预览") { provider in
            let data = try await provider.downloadSnapshot(snapshot)
            let preview = try IOSSyncBackup.restorePreview(data: data, passphrase: passphrase, fileName: snapshot.fileName)
            pendingRestore = IOSPendingSyncRestore(
                sourceLabel: "\(snapshot.provider.displayName)：\(snapshot.fileName)",
                data: data,
                snapshot: snapshot,
                preview: preview
            )
        }
    }

    @MainActor
    private func deleteSelectedSnapshot() async {
        guard let snapshot = selectedSnapshot else { return }
        await runRemoteOperation(successMessage: "已删除远端快照") { provider in
            try await provider.deleteSnapshot(snapshot)
            remoteSnapshots.removeAll { $0.id == snapshot.id }
            selectedSnapshotID = remoteSnapshots.first?.id
            if pendingRestore?.snapshot?.id == snapshot.id {
                pendingRestore = nil
            }
        }
    }

    @MainActor
    private func applyPendingRestore() async {
        guard let pendingRestore else { return }
        do {
            let result = try IOSSyncBackup.import(data: pendingRestore.data, passphrase: passphrase)
            var restoredConversationCount = 0
            if let conversationsZip = result.conversationsZip {
                let documents = try IOSSyncBackup.conversationDocuments(zipData: conversationsZip)
                restoredConversationCount = try await conversationStore.importConversationDocuments(documents)
            }
            sharedSettings.restoreSnapshot(result.settings)
            if let snapshot = pendingRestore.snapshot {
                sharedSettings.recordRemoteDownload(snapshot: snapshot, preview: result.preview)
            } else {
                sharedSettings.recordLocalRestore(preview: result.preview)
            }
            refreshRemoteStatus()
            self.pendingRestore = nil
            let conversationNote = restoredConversationCount > 0
                ? "，恢复 \(restoredConversationCount) 个对话"
                : ""
            alert = .success("已应用备份恢复：\(result.preview.manifest.appVersionName)\(conversationNote)")
        } catch {
            sharedSettings.recordRemoteSyncError(error)
            refreshRemoteStatus()
            alert = .error("恢复失败：\(error.localizedDescription)")
        }
    }

    @MainActor
    private func runRemoteOperation(
        successMessage: String,
        operation: @MainActor (any IOSRemoteSyncProvider) async throws -> Void
    ) async {
        isRemoteBusy = true
        remoteMessage = ""
        defer { isRemoteBusy = false }
        do {
            let provider = try makeRemoteProvider()
            try await operation(provider)
            if remoteMessage.isEmpty {
                remoteMessage = successMessage
            }
        } catch {
            sharedSettings.recordRemoteSyncError(error)
            refreshRemoteStatus()
            remoteMessage = error.localizedDescription
            alert = .error(error.localizedDescription)
        }
    }

    private func makeRemoteProvider() throws -> any IOSRemoteSyncProvider {
        switch providerKind {
        case .localFolder:
            return IOSLocalFolderSyncProvider()
        case .webDAV:
            let config = IOSWebDAVConfig(
                baseURL: webDAVBaseURL,
                path: webDAVPath,
                username: webDAVUsername,
                password: webDAVPassword
            )
            guard config.isConfigured else {
                throw IOSSyncBackupError.providerNotConfigured("请先填写 WebDAV Base URL")
            }
            return IOSWebDAVSyncProvider(config: config)
        case .googleDrive:
            return IOSUnavailableRemoteSyncProvider(kind: .googleDrive, reason: "Google Drive 当前不可用")
        case .s3:
            return IOSUnavailableRemoteSyncProvider(kind: .s3, reason: "S3 当前不可用")
        }
    }

    private var selectedSnapshot: IOSRemoteSnapshot? {
        remoteSnapshots.first { $0.id == selectedSnapshotID } ?? remoteSnapshots.first
    }

    private func refreshRemoteStatus() {
        remoteStatus = sharedSettings.remoteSyncStatus
    }

    private func restorePreviewText(_ preview: IOSSyncPreview) -> String {
        [
            "版本 \(preview.manifest.appVersionName) (\(preview.manifest.appVersionCode))",
            "设备 \(preview.manifest.deviceLabel.isEmpty ? "未知设备" : preview.manifest.deviceLabel)",
            "模式 \(preview.manifest.mode)",
            formatEpoch(preview.manifest.createdAt),
            ByteCountFormatter.string(fromByteCount: preview.sizeBytes, countStyle: .file),
            preview.manifest.passphraseProtected ? "需要口令" : "未设置口令",
        ].joined(separator: " · ")
    }

    private func exportFileStamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }

    private func formatEpoch(_ millis: Int64) -> String {
        guard millis > 0 else { return "暂无" }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

private struct SyncBackupRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct IOSPendingSyncRestore {
    let sourceLabel: String
    let data: Data
    let snapshot: IOSRemoteSnapshot?
    let preview: IOSSyncPreview
}

private struct SyncRemoteSnapshotRow: View {
    let snapshot: IOSRemoteSnapshot
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(isSelected ? AmberTheme.accentGreen : AmberTheme.muted2)
                .frame(width: 22)

            VStack(alignment: .leading, spacing: 3) {
                Text(snapshot.fileName)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(snapshotSubtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(ByteCountFormatter.string(fromByteCount: snapshot.sizeBytes, countStyle: .file))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(isSelected ? AmberTheme.accentGreen.opacity(0.08) : Color.clear)
    }

    private var snapshotSubtitle: String {
        let device = snapshot.deviceLabel.isEmpty ? snapshot.provider.displayName : snapshot.deviceLabel
        return [
            device,
            formatEpoch(snapshot.createdAt),
            snapshot.remoteRevision.isEmpty ? "" : String(snapshot.remoteRevision.prefix(12)) + "...",
        ].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func formatEpoch(_ millis: Int64) -> String {
        guard millis > 0 else { return "未知时间" }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

private struct SyncBackupStatusRow: View {
    let row: SyncBackupRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(row.value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(row.color)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct SyncBackupDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct SyncBackupNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}
