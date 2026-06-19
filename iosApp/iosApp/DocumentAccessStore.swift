import CryptoKit
import Foundation
import UniformTypeIdentifiers

struct SelectedDocumentGrant: Identifiable, Hashable {
    let id: String
    let capabilityId: String
    let toolName: String
    let operation: String
    let fileName: String
    let fileType: String
    let fileSize: Int64
    let scopeDigest: String
    let payloadDigest: String
    let createdAt: Date
    let expiresAt: Date
    let maxUses: Int
    var usedCount: Int
    fileprivate let url: URL

    func isExpired(now: Date = Date()) -> Bool {
        now >= expiresAt || usedCount >= maxUses
    }
}

struct SelectedDocumentGrantSummary: Identifiable, Hashable {
    let id: String
    let capabilityId: String
    let toolName: String
    let operation: String
    let fileName: String
    let fileType: String
    let fileSize: Int64
    let scopeDigest: String
    let payloadDigest: String
    let createdAt: Date
    let expiresAt: Date
    let maxUses: Int
    let usedCount: Int

    func isExpired(now: Date = Date()) -> Bool {
        now >= expiresAt || usedCount >= maxUses
    }
}

struct SelectedDocumentReadResult: Hashable {
    let fileName: String
    let bytesRead: Int
    let preview: String
}

@MainActor
@Observable
final class DocumentAccessStore {
    fileprivate var grant: SelectedDocumentGrant?
    private(set) var lastRead: SelectedDocumentReadResult?
    private(set) var errorMessage: String?
    private(set) var isReading: Bool = false

    let ttlSeconds: TimeInterval = 10 * 60
    let maxUses = 1
    let maxReadableBytes: Int64 = 2 * 1024 * 1024
    let maxPreviewBytes = 64 * 1024

    var grantSummary: SelectedDocumentGrantSummary? {
        grant.map { current in
            SelectedDocumentGrantSummary(
                id: current.id,
                capabilityId: current.capabilityId,
                toolName: current.toolName,
                operation: current.operation,
                fileName: current.fileName,
                fileType: current.fileType,
                fileSize: current.fileSize,
                scopeDigest: current.scopeDigest,
                payloadDigest: current.payloadDigest,
                createdAt: current.createdAt,
                expiresAt: current.expiresAt,
                maxUses: current.maxUses,
                usedCount: current.usedCount
            )
        }
    }

    @discardableResult
    func registerPickedFile(_ url: URL, now: Date = Date()) -> SelectedDocumentGrant {
        errorMessage = nil
        lastRead = nil

        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey, .localizedTypeDescriptionKey])
        let fileSize = Int64(values?.fileSize ?? 0)
        let contentType = values?.contentType?.preferredMIMEType
            ?? values?.contentType?.identifier
            ?? values?.localizedTypeDescription
            ?? "unknown"
        let scopeDigest = Self.scopeDigest(for: url)
        let payloadDigest = Self.payloadDigest(
            toolName: "file_read_selected",
            operation: "read_preview",
            scopeDigest: scopeDigest
        )

        let newGrant = SelectedDocumentGrant(
            id: UUID().uuidString,
            capabilityId: "ios.files.selected_read",
            toolName: "file_read_selected",
            operation: "read_preview",
            fileName: url.lastPathComponent,
            fileType: contentType,
            fileSize: fileSize,
            scopeDigest: scopeDigest,
            payloadDigest: payloadDigest,
            createdAt: now,
            expiresAt: now.addingTimeInterval(ttlSeconds),
            maxUses: maxUses,
            usedCount: 0,
            url: url
        )
        grant = newGrant
        return newGrant
    }

    func clearGrant() {
        grant = nil
        lastRead = nil
        errorMessage = nil
    }

    func recordSelectionError(_ message: String) {
        grant = nil
        lastRead = nil
        errorMessage = message
    }

    func requestForCurrentGrant(isUserInitiated: Bool) -> IOSToolInvocationRequest {
        guard let current = grant else {
            return IOSToolInvocationRequest(
                toolName: "file_read_selected",
                operation: "read_preview",
                scopeDigest: "",
                payloadDigest: "",
                isUserInitiated: isUserInitiated
            )
        }

        return IOSToolInvocationRequest(
            toolName: current.toolName,
            operation: current.operation,
            scopeDigest: current.scopeDigest,
            payloadDigest: current.payloadDigest,
            isUserInitiated: isUserInitiated
        )
    }

    fileprivate func consumeSelectedFileRead(request: IOSToolInvocationRequest, now: Date = Date()) async -> Result<SelectedDocumentReadResult, DocumentAccessError> {
        guard let current = grant else {
            errorMessage = "Choose a file before reading."
            return .failure(.missingGrant)
        }
        guard current.toolName == request.toolName,
              current.operation == request.operation,
              current.scopeDigest == request.scopeDigest,
              current.payloadDigest == request.payloadDigest else {
            errorMessage = "The selected-file grant does not match this request."
            return .failure(.grantMismatch)
        }
        guard !current.isExpired(now: now) else {
            grant = nil
            lastRead = nil
            errorMessage = "The in-memory file grant expired. Choose the file again."
            return .failure(.expiredGrant)
        }
        guard current.fileSize <= maxReadableBytes else {
            errorMessage = "Selected file is larger than the v1 preview limit of 2 MB."
            return .failure(.fileTooLarge)
        }
        guard !isReading else {
            return .failure(.alreadyReading)
        }

        isReading = true
        errorMessage = nil
        let grantId = current.id
        let url = current.url
        let maxReadableBytes = maxReadableBytes
        let maxPreviewBytes = maxPreviewBytes

        let result = await Self.readPreview(
            url: url,
            maxReadableBytes: maxReadableBytes,
            maxPreviewBytes: maxPreviewBytes
        )
        guard var latest = grant, latest.id == grantId else {
            isReading = false
            return .failure(.grantMismatch)
        }
        switch result {
        case .success(let readResult):
            latest.usedCount += 1
            grant = latest
            lastRead = readResult
            errorMessage = nil
            isReading = false
            return .success(readResult)
        case .failure(let error):
            let accessError = (error as? DocumentAccessError) ?? .readFailed(error.localizedDescription)
            errorMessage = accessError.userMessage
            isReading = false
            return .failure(accessError)
        }
    }

    private static func readPreview(
        url: URL,
        maxReadableBytes: Int64,
        maxPreviewBytes: Int
    ) async -> Result<SelectedDocumentReadResult, Error> {
        await Task.detached(priority: .utility) {
            let shouldStopAccessing = url.startAccessingSecurityScopedResource()
            defer {
                if shouldStopAccessing {
                    url.stopAccessingSecurityScopedResource()
                }
            }

            do {
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                guard let fileSize = (attributes[.size] as? NSNumber)?.int64Value else {
                    return Result<SelectedDocumentReadResult, Error>.failure(DocumentAccessError.unknownFileSize)
                }
                guard fileSize <= maxReadableBytes else {
                    return Result<SelectedDocumentReadResult, Error>.failure(DocumentAccessError.fileTooLarge)
                }

                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                let data = try handle.read(upToCount: maxPreviewBytes) ?? Data()
                let preview = String(data: data, encoding: .utf8)
                    ?? "\(data.count) bytes selected. Binary preview is unavailable."
                return Result<SelectedDocumentReadResult, Error>.success(
                    SelectedDocumentReadResult(
                        fileName: url.lastPathComponent,
                        bytesRead: data.count,
                        preview: preview
                    )
                )
            } catch {
                return Result<SelectedDocumentReadResult, Error>.failure(error)
            }
        }.value
    }

    static func scopeDigest(for url: URL) -> String {
        let canonical = url.standardizedFileURL.path
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func payloadDigest(toolName: String, operation: String, scopeDigest: String) -> String {
        let payload = "\(toolName)\n\(operation)\n\(scopeDigest)"
        let digest = SHA256.hash(data: Data(payload.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

enum DocumentAccessError: Error, Equatable {
    case missingGrant
    case grantMismatch
    case expiredGrant
    case fileTooLarge
    case unknownFileSize
    case alreadyReading
    case readFailed(String)
}

struct IOSToolInvocationRequest: Equatable {
    let toolName: String
    let operation: String
    let scopeDigest: String
    let payloadDigest: String
    let isUserInitiated: Bool
}

enum IOSPlatformGateDecision: Equatable {
    case allow(capabilityId: String)
    case needsUserAction(reason: String)
    case deny(reason: String)

    var summary: String {
        switch self {
        case .allow:
            "Allowed"
        case .needsUserAction(let reason):
            "Needs user action: \(reason)"
        case .deny(let reason):
            "Denied: \(reason)"
        }
    }
}

enum IOSToolExecutionResult: Equatable {
    case success(SelectedDocumentReadResult)
    case needsUserAction(String)
    case denied(String)
    case failed(String)
}

@MainActor
final class IOSToolRuntime {
    private let permissionStore: IOSPermissionStore
    private let documentStore: DocumentAccessStore

    init(permissionStore: IOSPermissionStore, documentStore: DocumentAccessStore) {
        self.permissionStore = permissionStore
        self.documentStore = documentStore
    }

    func resolve(request: IOSToolInvocationRequest, now: Date = Date()) -> IOSPlatformGateDecision {
        guard let capability = IOSCapabilityRegistry.capability(forToolName: request.toolName) else {
            return .deny(reason: "Unknown iOS tool: \(request.toolName)")
        }

        if capability.status == .unsupported {
            return .deny(reason: "Unsupported on iOS")
        }
        if capability.blockedToolNames.contains(request.toolName) {
            return .deny(reason: "Tool is blocked on iOS")
        }

        let policy = permissionStore.policy(for: capability)
        if policy == .disabled {
            return .deny(reason: "Disabled by AmberAgent policy")
        }
        if policy == .askEveryTime && !request.isUserInitiated {
            return .needsUserAction(reason: "Ask every time requires a foreground user action")
        }
        if capability.gate.requiresFreshUserPresence && !request.isUserInitiated {
            return .needsUserAction(reason: "Fresh user presence required")
        }

        if request.toolName == "file_read_selected" {
            return resolveSelectedFileRead(request: request, capability: capability, now: now)
        }

        return .needsUserAction(reason: "No iOS runtime implementation for \(request.toolName)")
    }

    func executeFileReadSelected(
        request: IOSToolInvocationRequest,
        now: Date = Date()
    ) async -> IOSToolExecutionResult {
        let decision = resolve(request: request, now: now)
        switch decision {
        case .allow:
            let result = await documentStore.consumeSelectedFileRead(request: request, now: now)
            switch result {
            case .success(let readResult):
                return .success(readResult)
            case .failure(let error):
                return .failed(error.userMessage)
            }
        case .needsUserAction(let reason):
            return .needsUserAction(reason)
        case .deny(let reason):
            return .denied(reason)
        }
    }

    private func resolveSelectedFileRead(
        request: IOSToolInvocationRequest,
        capability: IOSPlatformCapability,
        now: Date
    ) -> IOSPlatformGateDecision {
        guard let grant = documentStore.grant else {
            return .needsUserAction(reason: "Choose a file in the foreground picker")
        }
        guard grant.capabilityId == capability.id,
              grant.toolName == request.toolName,
              grant.operation == request.operation,
              grant.scopeDigest == request.scopeDigest,
              grant.payloadDigest == request.payloadDigest else {
            return .deny(reason: "Grant does not match tool, operation, scope, and payload")
        }
        guard !grant.isExpired(now: now) else {
            return .deny(reason: "Grant expired or was already used")
        }
        guard grant.fileSize <= documentStore.maxReadableBytes else {
            return .deny(reason: "Selected file is larger than 2 MB")
        }
        guard capability.gate.allowRunScopedReuse else {
            return .needsUserAction(reason: "Run-scoped reuse is not allowed")
        }
        return .allow(capabilityId: capability.id)
    }
}

private extension DocumentAccessError {
    var userMessage: String {
        switch self {
        case .missingGrant:
            "Choose a file before reading."
        case .grantMismatch:
            "The selected-file grant does not match this request."
        case .expiredGrant:
            "The in-memory file grant expired. Choose the file again."
        case .fileTooLarge:
            "Selected file is larger than the v1 preview limit of 2 MB."
        case .unknownFileSize:
            "Selected file size is unavailable. Choose a regular file and try again."
        case .alreadyReading:
            "The selected file is already being read."
        case .readFailed(let message):
            "Failed to read selected file: \(message)"
        }
    }
}
