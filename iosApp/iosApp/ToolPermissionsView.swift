import SwiftUI
import UniformTypeIdentifiers

struct ToolPermissionsView: View {

    @Bindable var permissionStore: IOSPermissionStore
    @Bindable var documentStore: DocumentAccessStore
    @Bindable var systemPermissionCoordinator: IOSSystemPermissionCoordinator
    let localToolExecutor: IOSLocalToolExecutor

    @Environment(\.dismiss) private var dismiss
    @State private var isImportingFile = false
    @State private var requestingCapabilityId: String?

    private let capabilities = IOSCapabilityRegistry.capabilities

    private var permissionSections: [PermissionRequestSection] {
        [
            PermissionRequestSection(
                title: "常用权限",
                items: [
                    selectedFileItem,
                    capabilityItem(
                        id: "ios.photos.library_read",
                        title: "照片",
                        subtitle: "申请访问照片和视频图库，也可以只允许部分照片",
                        systemImage: "photo.on.rectangle",
                        color: AmberTheme.accentAmber
                    ),
                    capabilityItem(
                        id: "ios.camera.capture",
                        title: "相机",
                        subtitle: "用于拍照、摄像、扫描或视觉输入",
                        systemImage: "camera",
                        color: AmberTheme.accent
                    ),
                    capabilityItem(
                        id: "ios.microphone.record",
                        title: "麦克风",
                        subtitle: "用于录音、语音输入或音视频采集",
                        systemImage: "mic",
                        color: AmberTheme.accentRed
                    ),
                    capabilityItem(
                        id: "ios.location.when_in_use",
                        title: "定位",
                        subtitle: "仅在使用 Amber 时读取当前位置",
                        systemImage: "location",
                        color: AmberTheme.accentGreen
                    ),
                    capabilityItem(
                        id: "ios.notifications.alerts",
                        title: "通知",
                        subtitle: "允许 Amber 发送提醒、声音和角标",
                        systemImage: "bell",
                        color: AmberTheme.accentIndigo
                    )
                ].compactMap { $0 }
            ),
            PermissionRequestSection(
                title: "个人数据",
                items: [
                    capabilityItem(
                        id: "ios.contacts.full",
                        title: "通讯录",
                        subtitle: "读取或更新联系人前会先申请系统授权",
                        systemImage: "person.crop.circle.badge.checkmark",
                        color: AmberTheme.accentCyan
                    ),
                    capabilityItem(
                        id: "ios.calendar.full",
                        title: "日历",
                        subtitle: "读取和创建日历事件需要 EventKit 授权",
                        systemImage: "calendar",
                        color: AmberTheme.accentAmber
                    ),
                    capabilityItem(
                        id: "ios.reminders.full",
                        title: "提醒事项",
                        subtitle: "读取和创建提醒事项需要系统授权",
                        systemImage: "checklist",
                        color: AmberTheme.accentGreen
                    ),
                    capabilityItem(
                        id: "ios.speech.recognition",
                        title: "语音识别",
                        subtitle: "将语音转换成文字前申请 Apple Speech 授权",
                        systemImage: "waveform",
                        color: AmberTheme.accent
                    ),
                    capabilityItem(
                        id: "ios.authentication.face_id",
                        title: "Face ID",
                        subtitle: "用系统生物识别完成一次本机身份验证",
                        systemImage: "faceid",
                        color: AmberTheme.accentIndigo
                    )
                ].compactMap { $0 }
            ),
            PermissionRequestSection(
                title: "设备与网络",
                items: [
                    capabilityItem(
                        id: "ios.bluetooth.ble",
                        title: "蓝牙",
                        subtitle: "扫描、连接或访问附近蓝牙设备前申请",
                        systemImage: "dot.radiowaves.left.and.right",
                        color: AmberTheme.accentCyan
                    ),
                    capabilityItem(
                        id: "ios.network.local",
                        title: "本地网络",
                        subtitle: "访问局域网设备时由 iOS 弹出确认",
                        systemImage: "network",
                        color: AmberTheme.accentGreen
                    )
                ].compactMap { $0 }
            )
        ]
    }

    private var selectedFileItem: PermissionRequestItem {
        PermissionRequestItem(
            id: "selected-file",
            title: "文件",
            subtitle: selectedFileSubtitle,
            systemImage: "doc",
            color: AmberTheme.accent,
            target: .selectedFile
        )
    }

    private var selectedFileSubtitle: String {
        if let error = documentStore.errorMessage {
            return error
        }
        if let grant = documentStore.grantSummary {
            return "已选择 \(grant.fileName)"
        }
        return "打开系统文件选择器，只允许读取你选中的单个文件"
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    permissionList
                    footerNote
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
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                documentStore.registerPickedFile(url)
            case .failure(let error):
                documentStore.recordSelectionError("文件选择失败：\(error.localizedDescription)")
            }
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("权限与能力")
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

    private var intro: some View {
        Text("点击一项来申请系统权限或打开系统选择器。Amber 只能通过这些入口访问数据，不能绕过 iOS 授权。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
    }

    private var permissionList: some View {
        VStack(spacing: 0) {
            ForEach(permissionSections) { section in
                AmberSectionLabel(text: section.title)
                AmberFormGroup {
                    ForEach(Array(section.items.enumerated()), id: \.element.id) { index, item in
                        PermissionRequestRow(
                            item: item,
                            status: status(for: item),
                            isBusy: isBusy(item),
                            action: { handleTap(item) }
                        )

                        if index < section.items.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
        }
    }

    private var footerNote: some View {
        Text("如果某项权限已经被拒绝，点它会带你去系统设置重新开启。高风险工具调用仍会在使用时单独确认。")
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 16)
    }

    private func capabilityItem(
        id: String,
        title: String,
        subtitle: String,
        systemImage: String,
        color: Color
    ) -> PermissionRequestItem? {
        guard let capability = capabilities.first(where: { $0.id == id }) else {
            return nil
        }
        return PermissionRequestItem(
            id: id,
            title: title,
            subtitle: subtitle,
            systemImage: systemImage,
            color: color,
            target: .capability(capability)
        )
    }

    private func status(for item: PermissionRequestItem) -> PermissionRequestStatus {
        switch item.target {
        case .selectedFile:
            guard let grant = documentStore.grantSummary else {
                return .init(text: "未选择", tone: .ready)
            }
            if grant.usedCount >= grant.maxUses {
                return .init(text: "已使用", tone: .neutral)
            }
            if grant.isExpired() {
                return .init(text: "已过期", tone: .warning)
            }
            return .init(text: "已选择", tone: .allowed)

        case .capability(let capability):
            if requestingCapabilityId == capability.id {
                return .init(text: "申请中", tone: .working)
            }
            let result = systemPermissionCoordinator.cachedStatus(for: capability)
            switch result.status {
            case .authorized:
                return .init(text: "已允许", tone: .allowed)
            case .limited:
                return .init(text: "部分允许", tone: .warning)
            case .denied:
                return .init(text: "已拒绝", tone: .denied)
            case .restricted:
                return .init(text: "受限制", tone: .denied)
            case .notDetermined:
                return .init(text: "可申请", tone: .ready)
            case .unknown:
                return .init(text: unknownStatusText(for: capability), tone: .ready)
            case .requiresSystemSettings:
                return .init(text: "去设置", tone: .warning)
            case .requiresEntitlement, .requiresExtensionTarget, .missingUsageDescription, .unavailableOnDevice:
                return .init(text: "不可用", tone: .neutral)
            }
        }
    }

    private func unknownStatusText(for capability: IOSPlatformCapability) -> String {
        switch capability.requestKind {
        case .picker:
            "可选择"
        case .foregroundSystemUI:
            "可打开"
        case .authenticationOperation:
            "可验证"
        default:
            "可申请"
        }
    }

    private func isBusy(_ item: PermissionRequestItem) -> Bool {
        guard case .capability(let capability) = item.target else {
            return false
        }
        return requestingCapabilityId == capability.id
    }

    private func handleTap(_ item: PermissionRequestItem) {
        switch item.target {
        case .selectedFile:
            isImportingFile = true

        case .capability(let capability):
            let result = systemPermissionCoordinator.cachedStatus(for: capability)
            if IOSSystemPermissionCoordinator.canRequestInApp(for: capability, systemStatus: result.status) {
                requestSystemPermission(capability)
            } else if capability.canOpenSettings && (result.status == .denied || result.status == .requiresSystemSettings) {
                systemPermissionCoordinator.openAppSettings()
            } else {
                refreshSystemPermission(capability)
            }
        }
    }

    private func requestSystemPermission(_ capability: IOSPlatformCapability) {
        guard requestingCapabilityId == nil else { return }
        requestingCapabilityId = capability.id
        Task {
            _ = await systemPermissionCoordinator.request(capability)
            requestingCapabilityId = nil
        }
    }

    private func refreshSystemPermission(_ capability: IOSPlatformCapability) {
        guard requestingCapabilityId == nil else { return }
        requestingCapabilityId = capability.id
        Task {
            _ = await systemPermissionCoordinator.refreshStatus(for: capability)
            requestingCapabilityId = nil
        }
    }
}

private struct PermissionRequestSection: Identifiable {
    let title: String
    let items: [PermissionRequestItem]

    var id: String { title }
}

private struct PermissionRequestItem: Identifiable {
    enum Target {
        case selectedFile
        case capability(IOSPlatformCapability)
    }

    let id: String
    let title: String
    let subtitle: String
    let systemImage: String
    let color: Color
    let target: Target
}

private struct PermissionRequestStatus {
    enum Tone {
        case ready
        case allowed
        case warning
        case denied
        case neutral
        case working

        var color: Color {
            switch self {
            case .ready: AmberTheme.accent
            case .allowed: AmberTheme.accentGreen
            case .warning: AmberTheme.accentAmber
            case .denied: AmberTheme.accentRed
            case .neutral: AmberTheme.muted
            case .working: AmberTheme.accentCyan
            }
        }
    }

    let text: String
    let tone: Tone
}

private struct PermissionRequestRow: View {
    let item: PermissionRequestItem
    let status: PermissionRequestStatus
    let isBusy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: item.systemImage)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(item.color)
                    .frame(width: 32, height: 32)
                    .background(item.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)

                    Text(item.subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isBusy {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Text(status.text)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(status.tone.color)
                        .lineLimit(1)
                }

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 60)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(item.title)，\(status.text)")
    }
}
