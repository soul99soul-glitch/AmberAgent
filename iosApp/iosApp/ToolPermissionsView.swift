import SwiftUI
import UniformTypeIdentifiers

struct ToolPermissionsView: View {

    @Bindable var permissionStore: IOSPermissionStore
    @Bindable var documentStore: DocumentAccessStore
    @Bindable var systemPermissionCoordinator: IOSSystemPermissionCoordinator
    let localToolExecutor: IOSLocalToolExecutor

    @State private var isImportingFile = false
    @State private var lastGateDecision: String?
    @State private var isReadRequestInFlight = false
    @State private var requestingCapabilityId: String?

    private let capabilities = IOSCapabilityRegistry.capabilities

    var body: some View {
        List {
            documentAccessSection

            ForEach(IOSCapabilityDomain.allCases) { domain in
                let items = capabilities.filter { $0.domain == domain }
                if !items.isEmpty {
                    Section(domain.title) {
                        ForEach(items) { capability in
                            CapabilityPolicyRow(
                                capability: capability,
                                systemResult: systemPermissionCoordinator.cachedStatus(for: capability),
                                selectedPolicy: Binding(
                                    get: { permissionStore.policy(for: capability) },
                                    set: { permissionStore.setPolicy($0, for: capability) }
                                ),
                                availablePolicies: permissionStore.availablePolicies(for: capability),
                                decisionSummary: permissionStore.decisionSummary(for: capability),
                                isRequesting: requestingCapabilityId == capability.id,
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
                        }
                    }
                }
            }
        }
        .navigationTitle("Tool Permissions")
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

    private var documentAccessSection: some View {
        Section("Selected File Grant") {
            Button {
                isImportingFile = true
            } label: {
                Label("Choose File", systemImage: "doc.badge.plus")
            }

            if let grant = documentStore.grantSummary {
                LabeledContent("File", value: grant.fileName)
                LabeledContent("Type", value: grant.fileType)
                LabeledContent("Size", value: ByteCountFormatter.string(fromByteCount: grant.fileSize, countStyle: .file))
                LabeledContent("Grant", value: grant.isExpired() ? "Expired" : "In memory")
                LabeledContent("Uses", value: "\(grant.usedCount)/\(grant.maxUses)")
                LabeledContent("Scope digest", value: String(grant.scopeDigest.prefix(12)))
                LabeledContent("Payload digest", value: String(grant.payloadDigest.prefix(12)))

                Button {
                    guard !isReadRequestInFlight else { return }
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
                } label: {
                    Label(
                        documentStore.isReading || isReadRequestInFlight ? "Reading..." : "Read Selected File Once",
                        systemImage: "doc.text.magnifyingglass"
                    )
                }
                .disabled(grant.isExpired() || documentStore.isReading || isReadRequestInFlight)

                Button(role: .destructive) {
                    documentStore.clearGrant()
                } label: {
                    Label("Clear Grant", systemImage: "trash")
                }
            } else {
                Text("No file selected. File access must start from this foreground picker; the agent cannot open it silently.")
                    .foregroundStyle(.secondary)
            }

            if let result = documentStore.lastRead {
                DisclosureGroup("Last read preview") {
                    Text("\(result.bytesRead) bytes read from \(result.fileName)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(result.preview)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                        .lineLimit(12)
                }
            }

            if let errorMessage = documentStore.errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if let lastGateDecision {
                Text(lastGateDecision)
                    .font(.caption)
                    .foregroundStyle(.secondary)
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

private struct CapabilityPolicyRow: View {

    let capability: IOSPlatformCapability
    let systemResult: IOSSystemPermissionResult
    @Binding var selectedPolicy: IOSAgentPermissionPolicy
    let availablePolicies: [IOSAgentPermissionPolicy]
    let decisionSummary: String
    let isRequesting: Bool
    let onRequest: () -> Void
    let onRefresh: () -> Void
    let onOpenSettings: () -> Void

    private var canRequestNow: Bool {
        IOSSystemPermissionCoordinator.canRequestInApp(
            for: capability,
            systemStatus: systemResult.status
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(capability.title)
                        .font(.headline)
                    Text(capability.id)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 12)
                RiskBadge(risk: capability.risk)
            }

            HStack {
                StatusBadge(status: capability.status)
                SystemStatusBadge(status: systemResult.status)
                Text(decisionSummary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(capability.summary)
                .font(.caption)
                .foregroundStyle(.secondary)

            LabeledContent("Request", value: capability.requestKind.title)
                .font(.caption)
            LabeledContent("Entry point", value: capability.requestEntryPoint)
                .font(.caption)

            if !capability.displayActionNames.isEmpty {
                Text(capability.displayActionNames.joined(separator: ", "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            if !capability.requiredInfoPlistKeys.isEmpty {
                Text("Requires \(capability.requiredInfoPlistKeys.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            if !capability.requiredEntitlements.isEmpty {
                Text("Entitlements: \(capability.requiredEntitlements.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            if !capability.requiredBackgroundModes.isEmpty {
                Text("Background modes: \(capability.requiredBackgroundModes.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            if !capability.requiredExtensionTargets.isEmpty {
                Text("Extension targets: \(capability.requiredExtensionTargets.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }

            Text(systemResult.message)
                .font(.caption)
                .foregroundStyle(.secondary)

            if let reason = capability.unavailableReason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(capability.status == .unsupported ? .red : .secondary)
            }

            HStack {
                if canRequestNow {
                    Button {
                        onRequest()
                    } label: {
                        Label(isRequesting ? "Requesting..." : "Request", systemImage: "hand.tap")
                    }
                    .disabled(isRequesting)
                } else if capability.status != .unsupported {
                    Button {
                        onRefresh()
                    } label: {
                        Label(isRequesting ? "Checking..." : "Check Status", systemImage: "arrow.clockwise")
                    }
                    .disabled(isRequesting)
                }

                if capability.canOpenSettings {
                    Button {
                        onOpenSettings()
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
            .buttonStyle(.bordered)

            Picker("Policy", selection: $selectedPolicy) {
                ForEach(availablePolicies) { policy in
                    Text(policy.title).tag(policy)
                }
            }
            .pickerStyle(.segmented)
            .disabled(availablePolicies.count == 1)
        }
        .padding(.vertical, 6)
    }
}

private struct StatusBadge: View {
    let status: IOSCapabilityStatus

    var body: some View {
        Text(status.title)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(backgroundStyle, in: Capsule())
            .foregroundStyle(foregroundStyle)
    }

    private var backgroundStyle: Color {
        switch status {
        case .supported: .green.opacity(0.16)
        case .degraded: .orange.opacity(0.18)
        case .requiresEntitlement: .purple.opacity(0.16)
        case .requiresExtensionTarget: .indigo.opacity(0.16)
        case .requiresSystemSettings: .teal.opacity(0.16)
        case .unsupported: .red.opacity(0.14)
        }
    }

    private var foregroundStyle: Color {
        switch status {
        case .supported: .green
        case .degraded: .orange
        case .requiresEntitlement: .purple
        case .requiresExtensionTarget: .indigo
        case .requiresSystemSettings: .teal
        case .unsupported: .red
        }
    }
}

private struct SystemStatusBadge: View {
    let status: IOSSystemPermissionStatus

    var body: some View {
        Text(status.title)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(backgroundStyle, in: Capsule())
            .foregroundStyle(foregroundStyle)
    }

    private var backgroundStyle: Color {
        switch status {
        case .authorized: .green.opacity(0.16)
        case .limited, .notDetermined, .unknown: .orange.opacity(0.16)
        case .requiresEntitlement, .requiresExtensionTarget, .requiresSystemSettings, .missingUsageDescription: .purple.opacity(0.16)
        case .denied, .restricted, .unavailableOnDevice: .red.opacity(0.14)
        }
    }

    private var foregroundStyle: Color {
        switch status {
        case .authorized: .green
        case .limited, .notDetermined, .unknown: .orange
        case .requiresEntitlement, .requiresExtensionTarget, .requiresSystemSettings, .missingUsageDescription: .purple
        case .denied, .restricted, .unavailableOnDevice: .red
        }
    }
}

private struct RiskBadge: View {
    let risk: IOSCapabilityRisk

    var body: some View {
        Text(risk.title)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(backgroundStyle, in: Capsule())
            .foregroundStyle(foregroundStyle)
    }

    private var backgroundStyle: Color {
        switch risk {
        case .normal: .gray.opacity(0.14)
        case .sensitive: .yellow.opacity(0.18)
        case .high: .red.opacity(0.14)
        }
    }

    private var foregroundStyle: Color {
        switch risk {
        case .normal: .secondary
        case .sensitive: .orange
        case .high: .red
        }
    }
}
