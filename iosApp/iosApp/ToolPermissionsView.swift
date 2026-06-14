import SwiftUI
import UniformTypeIdentifiers

struct ToolPermissionsView: View {

    @Bindable var permissionStore: IOSPermissionStore
    @Bindable var documentStore: DocumentAccessStore
    @Bindable var systemPermissionCoordinator: IOSSystemPermissionCoordinator
    let localToolExecutor: IOSLocalToolExecutor

    @Environment(\.dismiss) private var dismiss
    @State private var isImportingFile = false
    @State private var lastGateDecision: String?
    @State private var isReadRequestInFlight = false
    @State private var requestingCapabilityId: String?
    @State private var expandedCapabilityIds: Set<String> = ["ios.files.selected_read"]

    private let capabilities = IOSCapabilityRegistry.capabilities

    private var capabilityGroups: [CapabilityDomainGroup] {
        IOSCapabilityDomain.allCases.compactMap { domain in
            let items = capabilities.filter { $0.domain == domain }
            return items.isEmpty ? nil : CapabilityDomainGroup(domain: domain, capabilities: items)
        }
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    selectedFileGrantSection
                    capabilitySections
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
                documentStore.recordSelectionError("File selection failed: \(error.localizedDescription)")
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
        Text("iOS 权限按「能力」管理。部分能力需要系统授权，部分只能由你在前台选择或确认。AmberAgent 不会静默读取文件、照片、通讯录、健康数据、定位、通知或其它 App 内容。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
    }

    private var selectedFileGrantSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已选文件")
            SelectedFileGrantCard(
                grant: documentStore.grantSummary,
                lastRead: documentStore.lastRead,
                errorMessage: documentStore.errorMessage,
                lastGateDecision: lastGateDecision,
                isReading: documentStore.isReading || isReadRequestInFlight,
                onChooseFile: { isImportingFile = true },
                onReadOnce: readSelectedFileOnce,
                onClear: {
                    documentStore.clearGrant()
                    lastGateDecision = nil
                }
            )

            Text("只能读取你刚选择的单个文件 · 内存态授权 · 默认 10 分钟过期 · 最多 1 次 · 文件 ≤ 2MB · preview ≤ 64KB。不支持目录、持久 bookmark、后台选择或写入 / 删除。Chat 只附加你显式 attach 的 preview。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .lineSpacing(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var capabilitySections: some View {
        VStack(spacing: 0) {
            ForEach(capabilityGroups) { group in
                AmberSectionLabel(text: group.domain.title)
                AmberFormGroup {
                    ForEach(Array(group.capabilities.enumerated()), id: \.element.id) { index, capability in
                        CapabilityPolicyCard(
                            capability: capability,
                            systemResult: systemPermissionCoordinator.cachedStatus(for: capability),
                            selectedPolicy: Binding(
                                get: { permissionStore.policy(for: capability) },
                                set: { permissionStore.setPolicy($0, for: capability) }
                            ),
                            availablePolicies: permissionStore.availablePolicies(for: capability),
                            decisionSummary: permissionStore.decisionSummary(for: capability),
                            isExpanded: expandedCapabilityIds.contains(capability.id),
                            isRequesting: requestingCapabilityId == capability.id,
                            onToggle: {
                                toggleCapability(capability.id)
                            },
                            onRequest: {
                                requestSystemPermission(capability)
                            },
                            onRefresh: {
                                refreshSystemPermission(capability)
                            },
                            onOpenSettings: {
                                systemPermissionCoordinator.openAppSettings()
                            }
                        )

                        if index < group.capabilities.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 15)
                        }
                    }
                }
            }
        }
    }

    private func readSelectedFileOnce() {
        guard !isReadRequestInFlight else { return }
        guard documentStore.grantSummary != nil else {
            lastGateDecision = "Needs user action: choose a file first"
            return
        }

        isReadRequestInFlight = true
        Task {
            defer { isReadRequestInFlight = false }
            guard !Task.isCancelled else { return }
            let request = localToolExecutor.requestForCurrentSelectedFile(isUserInitiated: true)
            let result = await localToolExecutor.execute(request)
            guard !Task.isCancelled else { return }
            switch result {
            case .selectedFilePreview(let readResult):
                lastGateDecision = "Allowed: \(readResult.bytesRead) bytes read"
            case .needsUserAction(let reason):
                lastGateDecision = "Needs user action: \(reason)"
            case .denied(let reason):
                lastGateDecision = "Denied: \(reason)"
            case .failed(let message):
                lastGateDecision = message
            case .permissionsStatus:
                lastGateDecision = "Unexpected permissions status result"
            }
        }
    }

    private func toggleCapability(_ id: String) {
        if expandedCapabilityIds.contains(id) {
            expandedCapabilityIds.remove(id)
        } else {
            expandedCapabilityIds.insert(id)
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

private struct CapabilityDomainGroup: Identifiable {
    let domain: IOSCapabilityDomain
    let capabilities: [IOSPlatformCapability]

    var id: String { domain.id }
}

private struct SelectedFileGrantCard: View {
    let grant: SelectedDocumentGrantSummary?
    let lastRead: SelectedDocumentReadResult?
    let errorMessage: String?
    let lastGateDecision: String?
    let isReading: Bool
    let onChooseFile: () -> Void
    let onReadOnce: () -> Void
    let onClear: () -> Void

    private var canRead: Bool {
        guard let grant else { return false }
        return !grant.isExpired() && !isReading
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            if let grant {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Text(grant.fileName)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)
                            .lineLimit(2)

                        FileGrantStateBadge(grant: grant)
                    }

                    VStack(spacing: 5) {
                        GrantMetaRow(label: "类型", value: grant.fileType)
                        GrantMetaRow(label: "大小", value: ByteCountFormatter.string(fromByteCount: grant.fileSize, countStyle: .file))
                        GrantMetaRow(label: "用量", value: "\(grant.usedCount)/\(grant.maxUses)")
                        GrantMetaRow(label: "scope digest", value: String(grant.scopeDigest.prefix(12)))
                        GrantMetaRow(label: "payload digest", value: String(grant.payloadDigest.prefix(12)))
                    }

                    if let lastRead {
                        DisclosureGroup("最近读取：\(lastRead.bytesRead) bytes") {
                            Text(lastRead.preview)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(AmberTheme.foreground2)
                                .textSelection(.enabled)
                                .lineLimit(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.top, 6)
                        }
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .tint(AmberTheme.accent)
                    } else {
                        Text("最近读取：—")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }
            } else {
                Text("尚未选择文件。文件访问必须从前台选择器开始——点「选择文件」打开 iOS 系统文件选择器，选择单个文件。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineSpacing(2)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
            }

            if let lastGateDecision {
                Text(lastGateDecision)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }

            HStack(spacing: 8) {
                Button(action: onChooseFile) {
                    Label("选择文件", systemImage: "folder")
                }
                .buttonStyle(FileGrantButtonStyle(kind: .filled))

                Button(action: onReadOnce) {
                    Text(isReading ? "读取中..." : "读取一次")
                }
                .buttonStyle(FileGrantButtonStyle(kind: .glass))
                .disabled(!canRead)

                Button(action: onClear) {
                    Text("清除授权")
                }
                .buttonStyle(FileGrantButtonStyle(kind: .danger))
                .disabled(grant == nil)
            }
        }
        .padding(14)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
    }
}

private struct GrantMetaRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted2)
                .frame(width: 92, alignment: .leading)

            Text(value)
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground2)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct FileGrantStateBadge: View {
    let grant: SelectedDocumentGrantSummary

    private var text: String {
        if grant.usedCount >= grant.maxUses {
            return "Used"
        }
        if grant.isExpired() {
            return "Expired"
        }
        return "In memory"
    }

    private var color: Color {
        if grant.usedCount >= grant.maxUses {
            return AmberTheme.muted
        }
        if grant.isExpired() {
            return AmberTheme.accentAmber
        }
        return AmberTheme.accentGreen
    }

    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(color.opacity(0.13), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

private struct CapabilityPolicyCard: View {
    let capability: IOSPlatformCapability
    let systemResult: IOSSystemPermissionResult
    @Binding var selectedPolicy: IOSAgentPermissionPolicy
    let availablePolicies: [IOSAgentPermissionPolicy]
    let decisionSummary: String
    let isExpanded: Bool
    let isRequesting: Bool
    let onToggle: () -> Void
    let onRequest: () -> Void
    let onRefresh: () -> Void
    let onOpenSettings: () -> Void

    private var canRequestNow: Bool {
        IOSSystemPermissionCoordinator.canRequestInApp(
            for: capability,
            systemStatus: systemResult.status
        )
    }

    private var executableText: String {
        capability.status == .unsupported || selectedPolicy == .disabled ? "No" : "Yes"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggle) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(spacing: 6) {
                            Text(capability.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                                .lineLimit(2)

                            StatusBadge(status: capability.status)
                            RiskBadge(risk: capability.risk)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Text(capability.summary)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(isExpanded ? nil : 2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    if !isExpanded {
                        Text(selectedPolicy.title)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)
                            .lineLimit(1)
                    }

                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AmberTheme.muted2)
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                        .padding(.top, 3)
                }
                .padding(.horizontal, 15)
                .padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    CapabilityFlags(
                        executable: executableText,
                        requiresFreshPresence: capability.gate.requiresFreshUserPresence,
                        reusable: capability.gate.allowRunScopedReuse
                    )

                    CapabilityToolRows(capability: capability)

                    CapabilityRequirementRows(capability: capability, systemResult: systemResult)

                    actionButtons

                    policySegment

                    Text(decisionSummary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted2)
                        .lineSpacing(2)
                }
                .padding(.horizontal, 15)
                .padding(.top, 2)
                .padding(.bottom, 14)
            }
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        HStack(spacing: 8) {
            if canRequestNow {
                Button {
                    onRequest()
                } label: {
                    Label(isRequesting ? "Requesting..." : "Request", systemImage: "hand.tap")
                }
                .buttonStyle(CapabilityActionButtonStyle())
                .disabled(isRequesting)
            } else if capability.status != .unsupported {
                Button {
                    onRefresh()
                } label: {
                    Label(isRequesting ? "Checking..." : "Check Status", systemImage: "arrow.clockwise")
                }
                .buttonStyle(CapabilityActionButtonStyle())
                .disabled(isRequesting)
            }

            if capability.canOpenSettings {
                Button {
                    onOpenSettings()
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
                .buttonStyle(CapabilityActionButtonStyle())
            }
        }
    }

    private var policySegment: some View {
        HStack(spacing: 4) {
            ForEach(IOSAgentPermissionPolicy.allCases) { policy in
                let isAvailable = availablePolicies.contains(policy)
                Button {
                    guard isAvailable else { return }
                    selectedPolicy = policy
                } label: {
                    Text(policy.title)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(selectedPolicy == policy ? AmberTheme.accent : AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                        .background(
                            selectedPolicy == policy ? AmberTheme.background : .clear,
                            in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                        )
                        .opacity(isAvailable ? 1 : 0.45)
                }
                .buttonStyle(.plain)
                .disabled(!isAvailable)
            }
        }
        .padding(3)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct CapabilityFlags: View {
    let executable: String
    let requiresFreshPresence: Bool
    let reusable: Bool

    var body: some View {
        HStack(spacing: 12) {
            CapabilityFlag(label: "Executable", value: executable, isPositive: executable == "Yes")
            CapabilityFlag(label: "Fresh user presence", value: requiresFreshPresence ? "Yes" : "No", isPositive: requiresFreshPresence)
            CapabilityFlag(label: "Run-scoped reuse", value: reusable ? "Yes" : "No", isPositive: reusable)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CapabilityFlag: View {
    let label: String
    let value: String
    let isPositive: Bool

    var body: some View {
        HStack(spacing: 4) {
            Text(label)
            Text(value)
                .fontWeight(.semibold)
                .foregroundStyle(isPositive ? AmberTheme.accentGreen : AmberTheme.muted2)
        }
        .font(.caption2)
        .foregroundStyle(AmberTheme.muted)
    }
}

private struct CapabilityToolRows: View {
    let capability: IOSPlatformCapability

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ToolChipRow(label: "UI Actions", values: capability.uiActionNames, kind: .ui)
            ToolChipRow(label: "Model Tools", values: capability.modelToolNames, kind: .model)
            ToolChipRow(label: "Blocked Tools", values: capability.blockedToolNames, kind: .blocked)
        }
    }
}

private struct CapabilityRequirementRows: View {
    let capability: IOSPlatformCapability
    let systemResult: IOSSystemPermissionResult

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            DetailLine(label: "Request", value: capability.requestKind.title)
            DetailLine(label: "Entry point", value: capability.requestEntryPoint)

            if !capability.requiredInfoPlistKeys.isEmpty {
                ToolChipRow(label: "Info.plist", values: capability.requiredInfoPlistKeys, kind: .plain)
            }
            if !capability.requiredEntitlements.isEmpty {
                ToolChipRow(label: "Entitlements", values: capability.requiredEntitlements, kind: .plain)
            }
            if !capability.requiredBackgroundModes.isEmpty {
                ToolChipRow(label: "Background", values: capability.requiredBackgroundModes, kind: .plain)
            }
            if !capability.requiredExtensionTargets.isEmpty {
                ToolChipRow(label: "Extensions", values: capability.requiredExtensionTargets, kind: .plain)
            }

            HStack(spacing: 6) {
                SystemStatusBadge(status: systemResult.status)
                Text(systemResult.message)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }

            if let reason = capability.unavailableReason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(capability.status == .unsupported ? AmberTheme.accentRed : AmberTheme.muted)
            }
        }
    }
}

private struct DetailLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted2)
                .frame(width: 72, alignment: .leading)
            Text(value)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct ToolChipRow: View {
    let label: String
    let values: [String]
    let kind: ToolChipKind

    var body: some View {
        if !values.isEmpty {
            HStack(alignment: .top, spacing: 5) {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted2)
                    .padding(.top, 2)

                FlowLine(spacing: 5) {
                    ForEach(values, id: \.self) { value in
                        ToolChip(text: value, kind: kind)
                    }
                }
            }
        }
    }
}

private enum ToolChipKind {
    case plain
    case ui
    case model
    case blocked

    var foreground: Color {
        switch self {
        case .plain: AmberTheme.foreground2
        case .ui: AmberTheme.accentCyan
        case .model: AmberTheme.accentIndigo
        case .blocked: AmberTheme.accentRed
        }
    }

    var background: Color {
        switch self {
        case .plain: AmberTheme.surface2
        case .ui: AmberTheme.accentCyan.opacity(0.10)
        case .model: AmberTheme.accentIndigo.opacity(0.10)
        case .blocked: AmberTheme.accentRed.opacity(0.10)
        }
    }
}

private struct ToolChip: View {
    let text: String
    let kind: ToolChipKind

    var body: some View {
        Text(text)
            .font(.system(.caption2, design: .monospaced))
            .foregroundStyle(kind.foreground)
            .lineLimit(1)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(kind.background, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

private struct FlowLine<Content: View>: View {
    let spacing: CGFloat
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: spacing) {
                content
            }
        }
        .scrollIndicators(.hidden)
    }
}

private struct StatusBadge: View {
    let status: IOSCapabilityStatus

    var body: some View {
        BadgeText(text: status.title, tone: tone)
    }

    private var tone: BadgeTone {
        switch status {
        case .supported: .green
        case .degraded, .requiresSystemSettings: .amber
        case .requiresEntitlement, .requiresExtensionTarget: .cyan
        case .unsupported: .red
        }
    }
}

private struct SystemStatusBadge: View {
    let status: IOSSystemPermissionStatus

    var body: some View {
        BadgeText(text: status.title, tone: tone)
    }

    private var tone: BadgeTone {
        switch status {
        case .authorized: .green
        case .limited, .notDetermined, .unknown: .amber
        case .requiresEntitlement, .requiresExtensionTarget, .requiresSystemSettings, .missingUsageDescription: .cyan
        case .denied, .restricted, .unavailableOnDevice: .red
        }
    }
}

private struct RiskBadge: View {
    let risk: IOSCapabilityRisk

    var body: some View {
        BadgeText(text: risk.title, tone: tone, small: true)
    }

    private var tone: BadgeTone {
        switch risk {
        case .normal: .neutral
        case .sensitive: .amber
        case .high: .red
        }
    }
}

private enum BadgeTone {
    case green
    case amber
    case cyan
    case red
    case neutral

    var foreground: Color {
        switch self {
        case .green: AmberTheme.accentGreen
        case .amber: AmberTheme.accentAmber
        case .cyan: AmberTheme.accentCyan
        case .red: AmberTheme.accentRed
        case .neutral: AmberTheme.muted
        }
    }
}

private struct BadgeText: View {
    let text: String
    let tone: BadgeTone
    var small = false

    var body: some View {
        Text(text)
            .font(.system(size: small ? 9.5 : 10, weight: .semibold))
            .foregroundStyle(tone.foreground)
            .lineLimit(1)
            .padding(.horizontal, small ? 6 : 7)
            .padding(.vertical, 2)
            .background(tone.foreground.opacity(0.13), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

private struct FileGrantButtonStyle: ButtonStyle {
    enum Kind {
        case filled
        case glass
        case danger
    }

    let kind: Kind
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(foreground)
            .frame(height: 34)
            .padding(.horizontal, 14)
            .background(background, in: Capsule())
            .overlay {
                if kind == .glass {
                    Capsule()
                        .stroke(.white.opacity(0.65), lineWidth: 0.5)
                }
            }
            .opacity(isEnabled ? 1 : 0.42)
            .scaleEffect(configuration.isPressed && isEnabled ? 0.97 : 1)
    }

    private var foreground: Color {
        switch kind {
        case .filled: .white
        case .glass: AmberTheme.accent
        case .danger: AmberTheme.accentRed
        }
    }

    private var background: Color {
        switch kind {
        case .filled: AmberTheme.accent
        case .glass: AmberTheme.glass
        case .danger: .clear
        }
    }
}

private struct CapabilityActionButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.caption.weight(.semibold))
            .foregroundStyle(AmberTheme.accent)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(AmberTheme.glass, in: Capsule())
            .overlay {
                Capsule()
                    .stroke(.white.opacity(0.65), lineWidth: 0.5)
            }
            .opacity(isEnabled ? 1 : 0.42)
            .scaleEffect(configuration.isPressed && isEnabled ? 0.97 : 1)
    }
}
