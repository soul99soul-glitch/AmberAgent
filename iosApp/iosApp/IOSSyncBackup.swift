import CommonCrypto
import CryptoKit
import Foundation
import Security
import Shared
import UIKit

enum IOSSyncBackupError: LocalizedError {
    case emptyPayload
    case invalidArchive(String)
    case invalidPassphrase
    case providerNotConfigured(String)
    case remoteProviderUnavailable(String)
    case unsupportedCrypto(String)

    var errorDescription: String? {
        switch self {
        case .emptyPayload:
            return "备份内容为空"
        case .invalidArchive(let message):
            return message
        case .invalidPassphrase:
            return "同步口令不能为空"
        case .providerNotConfigured(let message):
            return message
        case .remoteProviderUnavailable(let message):
            return message
        case .unsupportedCrypto(let message):
            return message
        }
    }
}

struct IOSSyncBackup {
    static let fileExtension = "amberbackup"
    static let mimeType = "application/vnd.amberagent.backup+zip"

    private static let currentArchiveVersion = 1
    private static let pbkdf2Iterations = 210_000
    private static let keySizeBytes = 32
    private static let saltBytes = 16
    private static let ivBytes = 12
    private static let tagBytes = 16
    private static let noPassphraseFallback = "AmberAgent-NoPassphrase-v1"

    private static let manifestEntry = "manifest.json"
    private static let payloadEntry = "payload.enc"
    private static let settingsEntry = "settings.json"
    private static let payloadManifestEntry = "payload_manifest.json"
    private static let conversationsEntry = "conversations.zip"

    /// Exports an encrypted backup archive. Includes settings (always) plus an
    /// optional conversations bundle (Android SyncArchiveManager parity — iOS
    /// was settings-only before, so iOS→iOS conversation continuity was
    /// impossible). The conversations bundle is a zip of the on-disk
    /// `conversations/` directory passed by the caller.
    static func export(
        settings: Settings,
        passphrase: String?,
        remoteRevision: String? = nil,
        conversationsZip: Data? = nil
    ) throws -> Data {
        // Scheme B: redact credentials before capturing into the backup payload
        // (parity with Android BackupSettingsRedactor; iOS uses
        // IOSCredentialRedactor). Encryption is not a substitute for redaction —
        // a passphrase-protected backup decrypts back to plaintext credentials.
        let settingsJson = IOSCredentialRedactor.redact(
            IosSettingsJsonBridge.shared.encode(settings: settings)
        )
        let settingsData = Data(settingsJson.utf8)
        var datasets: [IOSSyncDatasetSummary] = [
            IOSSyncDatasetSummary(id: "settings", recordCount: 1, byteCount: Int64(settingsData.count))
        ]
        var zipEntries: [IOSStoredZipArchive.Entry] = [
            .init(name: settingsEntry, data: settingsData)
        ]
        if let conversationsZip {
            zipEntries.append(.init(name: conversationsEntry, data: conversationsZip))
            datasets.append(IOSSyncDatasetSummary(
                id: "conversations",
                recordCount: -1, // count unknown without parsing; -1 = "all"
                byteCount: Int64(conversationsZip.count)
            ))
        }
        let payloadManifest = IOSSyncPayloadManifest(datasets: datasets)
        let payloadManifestData = try JSONEncoder().encode(payloadManifest)
        zipEntries.append(.init(name: payloadManifestEntry, data: payloadManifestData))
        let payloadZip = try IOSStoredZipArchive.write(entries: zipEntries)

        let salt = Data.secureRandom(count: saltBytes)
        let iv = Data.secureRandom(count: ivBytes)
        let effectivePassphrase = try normalizedPassphrase(passphrase)
        let key = try deriveKey(passphrase: effectivePassphrase, salt: salt)
        let sealedBox = try AES.GCM.seal(payloadZip, using: key, nonce: AES.GCM.Nonce(data: iv))
        let encryptedPayload = sealedBox.ciphertext + sealedBox.tag
        let sha256 = SHA256.hash(data: encryptedPayload).map { String(format: "%02x", $0) }.joined()

        let manifest = IOSSyncManifest(
            archiveVersion: currentArchiveVersion,
            appVersionName: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0",
            appVersionCode: Int64((Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String).flatMap(Int.init) ?? 1),
            createdAt: Int64(Date().timeIntervalSince1970 * 1000),
            deviceId: settings.syncSettings.deviceId,
            deviceLabel: IOSDeviceLabel.current,
            mode: String(describing: settings.syncSettings.mode),
            remoteRevision: remoteRevision ?? settings.syncSettings.lastRemoteRevision,
            encrypted: true,
            kdf: IOSSyncKdfInfo(iterations: pbkdf2Iterations, saltBase64: salt.base64EncodedString()),
            cipher: IOSSyncCipherInfo(ivBase64: iv.base64EncodedString()),
            payloadSha256: sha256,
            passphraseProtected: !(passphrase?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        )
        let manifestData = try JSONEncoder().encode(manifest)

        return try IOSStoredZipArchive.write(entries: [
            .init(name: manifestEntry, data: manifestData),
            .init(name: payloadEntry, data: encryptedPayload),
        ])
    }

    static func `import`(data: Data, passphrase: String?) throws -> (settings: Settings, preview: IOSSyncPreview) {
        let decoded = try decodeArchive(data: data, passphrase: passphrase, fileName: nil)
        return (decoded.settings, decoded.preview)
    }

    /// Bundles every `{id}.json` conversation file in the given directory into a
    /// zip for inclusion in the backup payload. Returns nil if the directory
    /// is missing or empty (the caller then exports settings-only, honestly).
    static func conversationsZip(fromDirectory directoryURL: URL) -> Data? {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: directoryURL, includingPropertiesForKeys: nil) else {
            return nil
        }
        let jsonFiles = entries.filter { $0.pathExtension.lowercased() == "json" }
        guard !jsonFiles.isEmpty else { return nil }
        var zipEntries: [IOSStoredZipArchive.Entry] = []
        for file in jsonFiles {
            guard let data = try? Data(contentsOf: file) else { continue }
            zipEntries.append(.init(name: file.lastPathComponent, data: data))
        }
        guard !zipEntries.isEmpty else { return nil }
        return try? IOSStoredZipArchive.write(entries: zipEntries)
    }

    /// Extracts a conversations bundle (from a restored backup) into the given
    /// directory. Returns the number of conversation files written. Used by the
    /// restore path to rehydrate conversation history.
    @discardableResult
    static func restoreConversations(zipData: Data, intoDirectory directoryURL: URL) throws -> Int {
        let entries = try IOSStoredZipArchive.read(data: zipData)
        let fm = FileManager.default
        try fm.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        var written = 0
        for (name, data) in entries where name.hasSuffix(".json") {
            // Sanitize the name to a single component (no path traversal).
            let safeName = (name as NSString).lastPathComponent
            guard !safeName.isEmpty else { continue }
            try data.write(to: directoryURL.appendingPathComponent(safeName), options: [.atomic])
            written += 1
        }
        return written
    }

    static func restorePreview(data: Data, passphrase: String?, fileName: String? = nil) throws -> IOSSyncPreview {
        try decodeArchive(data: data, passphrase: passphrase, fileName: fileName).preview
    }

    static func inspectManifest(data: Data, fileName: String? = nil) throws -> IOSSyncPreview {
        let archiveEntries = try IOSStoredZipArchive.read(data: data)
        guard let manifestData = archiveEntries[manifestEntry] else {
            throw IOSSyncBackupError.invalidArchive("同步备份缺少 manifest.json")
        }
        guard let encryptedPayload = archiveEntries[payloadEntry] else {
            throw IOSSyncBackupError.invalidArchive("同步备份缺少 payload.enc")
        }
        let manifest = try JSONDecoder().decode(IOSSyncManifest.self, from: manifestData)
        try validate(manifest: manifest)

        guard encryptedPayload.count > tagBytes else {
            throw IOSSyncBackupError.emptyPayload
        }
        let actualSha256 = SHA256.hash(data: encryptedPayload).map { String(format: "%02x", $0) }.joined()
        guard actualSha256 == manifest.payloadSha256 else {
            throw IOSSyncBackupError.invalidArchive("同步备份 payload 校验失败")
        }
        return IOSSyncPreview(manifest: manifest, fileName: fileName, sizeBytes: Int64(data.count), datasets: [])
    }

    private static func decodeArchive(
        data: Data,
        passphrase: String?,
        fileName: String?
    ) throws -> (settings: Settings, preview: IOSSyncPreview) {
        let manifestPreview = try inspectManifest(data: data, fileName: fileName)
        let archiveEntries = try IOSStoredZipArchive.read(data: data)
        let manifest = manifestPreview.manifest
        guard let encryptedPayload = archiveEntries[payloadEntry] else {
            throw IOSSyncBackupError.invalidArchive("同步备份缺少 payload.enc")
        }

        let salt = try Data(base64: manifest.kdf.saltBase64, field: "saltBase64")
        let iv = try Data(base64: manifest.cipher.ivBase64, field: "ivBase64")
        let key = try deriveKey(passphrase: normalizedPassphrase(passphrase, protected: manifest.passphraseProtected), salt: salt)
        let ciphertext = encryptedPayload.prefix(encryptedPayload.count - tagBytes)
        let tag = encryptedPayload.suffix(tagBytes)
        let sealedBox = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv), ciphertext: ciphertext, tag: tag)
        let payloadZip = try AES.GCM.open(sealedBox, using: key)
        let payloadEntries = try IOSStoredZipArchive.read(data: payloadZip)
        guard let settingsData = payloadEntries[settingsEntry],
              let settingsJson = String(data: settingsData, encoding: .utf8) else {
            throw IOSSyncBackupError.invalidArchive("同步备份缺少 settings.json")
        }
        let payloadManifest = try payloadEntries[payloadManifestEntry].map {
            try JSONDecoder().decode(IOSSyncPayloadManifest.self, from: $0)
        } ?? IOSSyncPayloadManifest()
        let settings = IosSettingsJsonBridge.shared.decode(json: settingsJson)
        let preview = IOSSyncPreview(
            manifest: manifest,
            fileName: fileName,
            sizeBytes: Int64(data.count),
            datasets: payloadManifest.datasets
        )
        return (settings, preview)
    }

    private static func normalizedPassphrase(_ passphrase: String?, protected: Bool = false) throws -> String {
        let trimmed = passphrase?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmed.isEmpty {
            if protected { throw IOSSyncBackupError.invalidPassphrase }
            return noPassphraseFallback
        }
        return trimmed
    }

    private static func deriveKey(passphrase: String, salt: Data) throws -> SymmetricKey {
        guard let passphraseData = passphrase.data(using: .utf8) else {
            throw IOSSyncBackupError.invalidPassphrase
        }
        var key = Data(repeating: 0, count: keySizeBytes)
        let status = key.withUnsafeMutableBytes { keyBytes in
            salt.withUnsafeBytes { saltBytes in
                passphraseData.withUnsafeBytes { passphraseBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passphraseBytes.bindMemory(to: Int8.self).baseAddress,
                        passphraseData.count,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(pbkdf2Iterations),
                        keyBytes.bindMemory(to: UInt8.self).baseAddress,
                        keySizeBytes
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw IOSSyncBackupError.unsupportedCrypto("PBKDF2-HMAC-SHA256 派生失败：\(status)")
        }
        return SymmetricKey(data: key)
    }

    private static func validate(manifest: IOSSyncManifest) throws {
        guard manifest.archiveVersion == currentArchiveVersion else {
            throw IOSSyncBackupError.invalidArchive("不支持的同步备份版本：\(manifest.archiveVersion)")
        }
        guard manifest.kdf.name == "PBKDF2WithHmacSHA256",
              manifest.kdf.iterations == pbkdf2Iterations,
              manifest.kdf.keySizeBits == 256 else {
            throw IOSSyncBackupError.unsupportedCrypto("Unsupported KDF: \(manifest.kdf.name)")
        }
        guard manifest.cipher.name == "AES/GCM/NoPadding",
              manifest.cipher.tagSizeBits == 128 else {
            throw IOSSyncBackupError.unsupportedCrypto("Unsupported cipher: \(manifest.cipher.name)")
        }
    }
}

struct IOSSyncPreview: Equatable, Sendable {
    let manifest: IOSSyncManifest
    let fileName: String?
    let sizeBytes: Int64
    let datasets: [IOSSyncDatasetSummary]
}

struct IOSSyncManifest: Codable, Equatable, Sendable {
    let archiveVersion: Int
    let appVersionName: String
    let appVersionCode: Int64
    let createdAt: Int64
    let deviceId: String
    let deviceLabel: String
    let mode: String
    let remoteRevision: String
    let encrypted: Bool
    let kdf: IOSSyncKdfInfo
    let cipher: IOSSyncCipherInfo
    let payloadSha256: String
    let passphraseProtected: Bool

    init(
        archiveVersion: Int = 1,
        appVersionName: String,
        appVersionCode: Int64,
        createdAt: Int64,
        deviceId: String,
        deviceLabel: String = "",
        mode: String,
        remoteRevision: String = "",
        encrypted: Bool = true,
        kdf: IOSSyncKdfInfo,
        cipher: IOSSyncCipherInfo,
        payloadSha256: String,
        passphraseProtected: Bool = true
    ) {
        self.archiveVersion = archiveVersion
        self.appVersionName = appVersionName
        self.appVersionCode = appVersionCode
        self.createdAt = createdAt
        self.deviceId = deviceId
        self.deviceLabel = deviceLabel
        self.mode = mode
        self.remoteRevision = remoteRevision
        self.encrypted = encrypted
        self.kdf = kdf
        self.cipher = cipher
        self.payloadSha256 = payloadSha256
        self.passphraseProtected = passphraseProtected
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.archiveVersion = try container.decodeIfPresent(Int.self, forKey: .archiveVersion) ?? 1
        self.appVersionName = try container.decode(String.self, forKey: .appVersionName)
        self.appVersionCode = try container.decode(Int64.self, forKey: .appVersionCode)
        self.createdAt = try container.decode(Int64.self, forKey: .createdAt)
        self.deviceId = try container.decode(String.self, forKey: .deviceId)
        self.deviceLabel = try container.decodeIfPresent(String.self, forKey: .deviceLabel) ?? ""
        self.mode = try container.decode(String.self, forKey: .mode)
        self.remoteRevision = try container.decodeIfPresent(String.self, forKey: .remoteRevision) ?? ""
        self.encrypted = try container.decodeIfPresent(Bool.self, forKey: .encrypted) ?? true
        self.kdf = try container.decode(IOSSyncKdfInfo.self, forKey: .kdf)
        self.cipher = try container.decode(IOSSyncCipherInfo.self, forKey: .cipher)
        self.payloadSha256 = try container.decode(String.self, forKey: .payloadSha256)
        self.passphraseProtected = try container.decodeIfPresent(Bool.self, forKey: .passphraseProtected) ?? true
    }
}

struct IOSSyncKdfInfo: Codable, Equatable, Sendable {
    var name: String
    let iterations: Int
    let saltBase64: String
    var keySizeBits: Int

    init(
        name: String = "PBKDF2WithHmacSHA256",
        iterations: Int,
        saltBase64: String,
        keySizeBits: Int = 256
    ) {
        self.name = name
        self.iterations = iterations
        self.saltBase64 = saltBase64
        self.keySizeBits = keySizeBits
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.name = try container.decodeIfPresent(String.self, forKey: .name) ?? "PBKDF2WithHmacSHA256"
        self.iterations = try container.decode(Int.self, forKey: .iterations)
        self.saltBase64 = try container.decode(String.self, forKey: .saltBase64)
        self.keySizeBits = try container.decodeIfPresent(Int.self, forKey: .keySizeBits) ?? 256
    }
}

struct IOSSyncCipherInfo: Codable, Equatable, Sendable {
    var name: String
    let ivBase64: String
    var tagSizeBits: Int

    init(
        name: String = "AES/GCM/NoPadding",
        ivBase64: String,
        tagSizeBits: Int = 128
    ) {
        self.name = name
        self.ivBase64 = ivBase64
        self.tagSizeBits = tagSizeBits
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.name = try container.decodeIfPresent(String.self, forKey: .name) ?? "AES/GCM/NoPadding"
        self.ivBase64 = try container.decode(String.self, forKey: .ivBase64)
        self.tagSizeBits = try container.decodeIfPresent(Int.self, forKey: .tagSizeBits) ?? 128
    }
}

struct IOSSyncPayloadManifest: Codable, Equatable, Sendable {
    var datasets: [IOSSyncDatasetSummary] = []
}

struct IOSSyncDatasetSummary: Codable, Equatable, Sendable {
    let id: String
    let recordCount: Int
    let byteCount: Int64
}

private enum IOSDeviceLabel {
    static var current: String {
        if Thread.isMainThread {
            return MainActor.assumeIsolated {
                let device = UIDevice.current
                return [device.systemName, device.model].filter { !$0.isEmpty }.joined(separator: " ")
            }
        }
        return "iOS Device"
    }
}

enum IOSRemoteProviderKind: String, Codable, CaseIterable, Identifiable, Sendable {
    case localFolder
    case webDAV
    case googleDrive
    case s3

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .localFolder:
            return "本机文件夹"
        case .webDAV:
            return "WebDAV"
        case .googleDrive:
            return "Google Drive"
        case .s3:
            return "S3"
        }
    }
}

struct IOSRemoteSnapshot: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let fileName: String
    let provider: IOSRemoteProviderKind
    let createdAt: Int64
    let modifiedAt: Int64
    let sizeBytes: Int64
    let remoteRevision: String
    let deviceId: String
    let deviceLabel: String
    let appVersionName: String
    let appVersionCode: Int64
    let mode: String
    let encrypted: Bool
    let passphraseProtected: Bool
    let payloadSha256: String

    static func fileName(for manifest: IOSSyncManifest) -> String {
        "amber-sync-\(manifest.createdAt).\(IOSSyncBackup.fileExtension)"
    }

    init(
        id: String? = nil,
        fileName: String,
        provider: IOSRemoteProviderKind,
        createdAt: Int64,
        modifiedAt: Int64,
        sizeBytes: Int64,
        remoteRevision: String,
        deviceId: String,
        deviceLabel: String,
        appVersionName: String,
        appVersionCode: Int64,
        mode: String,
        encrypted: Bool,
        passphraseProtected: Bool,
        payloadSha256: String
    ) {
        self.id = id ?? "\(provider.rawValue):\(fileName):\(remoteRevision)"
        self.fileName = fileName
        self.provider = provider
        self.createdAt = createdAt
        self.modifiedAt = modifiedAt
        self.sizeBytes = sizeBytes
        self.remoteRevision = remoteRevision
        self.deviceId = deviceId
        self.deviceLabel = deviceLabel
        self.appVersionName = appVersionName
        self.appVersionCode = appVersionCode
        self.mode = mode
        self.encrypted = encrypted
        self.passphraseProtected = passphraseProtected
        self.payloadSha256 = payloadSha256
    }

    init(
        fileName: String,
        provider: IOSRemoteProviderKind,
        manifest: IOSSyncManifest,
        modifiedAt: Int64,
        sizeBytes: Int64,
        remoteRevision: String
    ) {
        self.init(
            fileName: fileName,
            provider: provider,
            createdAt: manifest.createdAt,
            modifiedAt: modifiedAt,
            sizeBytes: sizeBytes,
            remoteRevision: remoteRevision,
            deviceId: manifest.deviceId,
            deviceLabel: manifest.deviceLabel,
            appVersionName: manifest.appVersionName,
            appVersionCode: manifest.appVersionCode,
            mode: manifest.mode,
            encrypted: manifest.encrypted,
            passphraseProtected: manifest.passphraseProtected,
            payloadSha256: manifest.payloadSha256
        )
    }
}

protocol IOSRemoteSyncProvider: Sendable {
    var kind: IOSRemoteProviderKind { get }
    var displayName: String { get }
    var isConfigured: Bool { get }

    func testConnection() async throws
    func listSnapshots() async throws -> [IOSRemoteSnapshot]
    func uploadSnapshot(data: Data, fileName: String, manifest: IOSSyncManifest) async throws -> IOSRemoteSnapshot
    func downloadSnapshot(_ snapshot: IOSRemoteSnapshot) async throws -> Data
    func deleteSnapshot(_ snapshot: IOSRemoteSnapshot) async throws
}

extension IOSRemoteSyncProvider {
    var displayName: String { kind.displayName }

    func latestSnapshot() async throws -> IOSRemoteSnapshot? {
        try await listSnapshots().sorted { lhs, rhs in
            if lhs.createdAt == rhs.createdAt {
                return lhs.modifiedAt > rhs.modifiedAt
            }
            return lhs.createdAt > rhs.createdAt
        }.first
    }
}

struct IOSLocalFolderSyncProvider: IOSRemoteSyncProvider {
    let folderURL: URL

    var kind: IOSRemoteProviderKind { .localFolder }
    var isConfigured: Bool { true }

    init(folderURL: URL = IOSLocalFolderSyncProvider.defaultFolderURL()) {
        self.folderURL = folderURL
    }

    static func defaultFolderURL() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return documents.appendingPathComponent("AmberRemoteSyncLocal", isDirectory: true)
    }

    func testConnection() async throws {
        try ensureFolder()
    }

    func listSnapshots() async throws -> [IOSRemoteSnapshot] {
        try ensureFolder()
        let urls = try FileManager.default.contentsOfDirectory(
            at: folderURL,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )
        return try urls
            .filter { $0.pathExtension == IOSSyncBackup.fileExtension }
            .map(snapshot(from:))
            .sorted { lhs, rhs in
                if lhs.createdAt == rhs.createdAt {
                    return lhs.modifiedAt > rhs.modifiedAt
                }
                return lhs.createdAt > rhs.createdAt
            }
    }

    func uploadSnapshot(data: Data, fileName: String, manifest: IOSSyncManifest) async throws -> IOSRemoteSnapshot {
        try ensureFolder()
        let safeName = try safeSnapshotFileName(fileName)
        let targetURL = folderURL.appendingPathComponent(safeName, isDirectory: false)
        try data.write(to: targetURL, options: [.atomic])
        return try snapshot(from: targetURL, fallbackManifest: manifest, data: data)
    }

    func downloadSnapshot(_ snapshot: IOSRemoteSnapshot) async throws -> Data {
        let safeName = try safeSnapshotFileName(snapshot.fileName)
        return try Data(contentsOf: folderURL.appendingPathComponent(safeName, isDirectory: false))
    }

    func deleteSnapshot(_ snapshot: IOSRemoteSnapshot) async throws {
        let safeName = try safeSnapshotFileName(snapshot.fileName)
        let url = folderURL.appendingPathComponent(safeName, isDirectory: false)
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    private func ensureFolder() throws {
        try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true, attributes: nil)
    }

    private func snapshot(from url: URL) throws -> IOSRemoteSnapshot {
        try snapshot(from: url, fallbackManifest: nil, data: nil)
    }

    private func snapshot(from url: URL, fallbackManifest: IOSSyncManifest?, data cachedData: Data?) throws -> IOSRemoteSnapshot {
        let data: Data
        if let cachedData {
            data = cachedData
        } else {
            data = try Data(contentsOf: url)
        }
        let preview = try IOSSyncBackup.inspectManifest(data: data, fileName: url.lastPathComponent)
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let modified = (attrs[.modificationDate] as? Date) ?? Date(timeIntervalSince1970: TimeInterval(preview.manifest.createdAt) / 1000)
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? Int64(data.count)
        let manifest = fallbackManifest ?? preview.manifest
        return IOSRemoteSnapshot(
            fileName: url.lastPathComponent,
            provider: kind,
            manifest: manifest,
            modifiedAt: Int64(modified.timeIntervalSince1970 * 1000),
            sizeBytes: size,
            remoteRevision: data.sha256Hex()
        )
    }
}

struct IOSWebDAVConfig: Codable, Equatable {
    var baseURL: String = ""
    var path: String = "AmberAgent"
    var username: String = ""
    var password: String = ""

    var isConfigured: Bool {
        URL(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)) != nil
    }
}

protocol IOSWebDAVTransport: Sendable {
    func send(_ request: URLRequest, body: Data?) async throws -> (HTTPURLResponse, Data)
}

struct IOSURLSessionWebDAVTransport: IOSWebDAVTransport {
    func send(_ request: URLRequest, body: Data?) async throws -> (HTTPURLResponse, Data) {
        let result: (Data, URLResponse)
        if let body {
            result = try await URLSession.shared.upload(for: request, from: body)
        } else {
            result = try await URLSession.shared.data(for: request)
        }
        guard let response = result.1 as? HTTPURLResponse else {
            throw IOSSyncBackupError.invalidArchive("WebDAV 响应不是 HTTP")
        }
        return (response, result.0)
    }
}

struct IOSWebDAVSyncProvider: IOSRemoteSyncProvider {
    let config: IOSWebDAVConfig
    let transport: any IOSWebDAVTransport

    var kind: IOSRemoteProviderKind { .webDAV }
    var isConfigured: Bool { config.isConfigured }

    init(config: IOSWebDAVConfig, transport: any IOSWebDAVTransport = IOSURLSessionWebDAVTransport()) {
        self.config = config
        self.transport = transport
    }

    func testConnection() async throws {
        _ = try await propfind(depth: 0)
    }

    func listSnapshots() async throws -> [IOSRemoteSnapshot] {
        try await ensureCollectionExists()
        let resources = try await propfind(depth: 1)
        return resources
            .filter { !$0.isCollection && $0.fileName.hasSuffix(".\(IOSSyncBackup.fileExtension)") }
            .map { resource in
                IOSRemoteSnapshot(
                    fileName: resource.fileName,
                    provider: kind,
                    createdAt: resource.modifiedAt,
                    modifiedAt: resource.modifiedAt,
                    sizeBytes: resource.sizeBytes,
                    remoteRevision: resource.etag.ifBlank(resource.href),
                    deviceId: "",
                    deviceLabel: "",
                    appVersionName: "",
                    appVersionCode: 0,
                    mode: "",
                    encrypted: true,
                    passphraseProtected: true,
                    payloadSha256: ""
                )
            }
            .sorted { lhs, rhs in lhs.modifiedAt > rhs.modifiedAt }
    }

    func uploadSnapshot(data: Data, fileName: String, manifest: IOSSyncManifest) async throws -> IOSRemoteSnapshot {
        try await ensureCollectionExists()
        let safeName = try safeSnapshotFileName(fileName)
        let request = try makeRequest(method: "PUT", fileName: safeName, headers: [
            "Content-Type": IOSSyncBackup.mimeType,
            "Content-Length": "\(data.count)",
        ])
        let (response, _) = try await transport.send(request, body: data)
        try validate(response: response, accepted: 200, 201, 204)
        return IOSRemoteSnapshot(
            fileName: safeName,
            provider: kind,
            manifest: manifest,
            modifiedAt: Int64(Date().timeIntervalSince1970 * 1000),
            sizeBytes: Int64(data.count),
            remoteRevision: response.value(forHTTPHeaderField: "ETag")?.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                ?? data.sha256Hex()
        )
    }

    func downloadSnapshot(_ snapshot: IOSRemoteSnapshot) async throws -> Data {
        let safeName = try safeSnapshotFileName(snapshot.fileName)
        let request = try makeRequest(method: "GET", fileName: safeName)
        let (response, data) = try await transport.send(request, body: nil)
        try validate(response: response, accepted: 200)
        return data
    }

    func deleteSnapshot(_ snapshot: IOSRemoteSnapshot) async throws {
        let safeName = try safeSnapshotFileName(snapshot.fileName)
        let request = try makeRequest(method: "DELETE", fileName: safeName)
        let (response, _) = try await transport.send(request, body: nil)
        try validate(response: response, accepted: 200, 202, 204, 404)
    }

    private func ensureCollectionExists() async throws {
        guard !config.path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let request = try makeRequest(method: "MKCOL")
        let (response, _) = try await transport.send(request, body: nil)
        try validate(response: response, accepted: 200, 201, 204, 405)
    }

    private func propfind(depth: Int) async throws -> [IOSWebDAVResource] {
        let body = Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <D:propfind xmlns:D="DAV:">
          <D:prop>
            <D:displayname/>
            <D:getcontentlength/>
            <D:getcontenttype/>
            <D:getlastmodified/>
            <D:getetag/>
            <D:resourcetype/>
          </D:prop>
        </D:propfind>
        """.utf8)
        let request = try makeRequest(method: "PROPFIND", headers: [
            "Content-Type": "application/xml; charset=utf-8",
            "Depth": "\(depth)",
        ])
        let (response, data) = try await transport.send(request, body: body)
        try validate(response: response, accepted: 200, 207)
        return IOSWebDAVPropfindParser.parse(data: data)
    }

    func makeRequest(method: String, fileName: String? = nil, headers: [String: String] = [:]) throws -> URLRequest {
        guard let url = buildURL(fileName: fileName) else {
            throw IOSSyncBackupError.providerNotConfigured("WebDAV URL 未配置")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if !config.username.isEmpty || !config.password.isEmpty {
            let raw = "\(config.username):\(config.password)"
            let encoded = Data(raw.utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        }
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        return request
    }

    private func buildURL(fileName: String?) -> URL? {
        guard var url = URL(string: config.baseURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return nil
        }
        let pathParts = config.path
            .split(separator: "/")
            .map(String.init)
            .filter { !$0.isEmpty }
        for part in pathParts {
            url.appendPathComponent(part, isDirectory: true)
        }
        if let fileName {
            url.appendPathComponent(fileName, isDirectory: false)
        }
        return url
    }

    private func validate(response: HTTPURLResponse, accepted codes: Int...) throws {
        guard codes.contains(response.statusCode) || (200...299).contains(response.statusCode) else {
            throw IOSSyncBackupError.remoteProviderUnavailable("WebDAV 请求失败：HTTP \(response.statusCode)")
        }
    }
}

struct IOSUnavailableRemoteSyncProvider: IOSRemoteSyncProvider {
    let kind: IOSRemoteProviderKind
    let reason: String

    var isConfigured: Bool { false }

    func testConnection() async throws { throw unavailableError() }
    func listSnapshots() async throws -> [IOSRemoteSnapshot] { throw unavailableError() }
    func uploadSnapshot(data: Data, fileName: String, manifest: IOSSyncManifest) async throws -> IOSRemoteSnapshot {
        throw unavailableError()
    }
    func downloadSnapshot(_ snapshot: IOSRemoteSnapshot) async throws -> Data { throw unavailableError() }
    func deleteSnapshot(_ snapshot: IOSRemoteSnapshot) async throws { throw unavailableError() }

    private func unavailableError() -> IOSSyncBackupError {
        .remoteProviderUnavailable("\(kind.displayName) 尚未配置：\(reason)")
    }
}

struct IOSSyncConflict: Equatable {
    let localRevision: String
    let remoteSnapshot: IOSRemoteSnapshot
}

enum IOSSyncConflictResolver {
    static func conflict(status: IOSRemoteSyncStatus, remoteSnapshots: [IOSRemoteSnapshot]) -> IOSSyncConflict? {
        guard !status.remoteRevision.isEmpty,
              let latest = remoteSnapshots.filter({ $0.provider == status.provider }).sorted(by: { lhs, rhs in
                  if lhs.createdAt == rhs.createdAt {
                      return lhs.modifiedAt > rhs.modifiedAt
                  }
                  return lhs.createdAt > rhs.createdAt
              }).first,
              latest.remoteRevision != status.remoteRevision else {
            return nil
        }
        return IOSSyncConflict(localRevision: status.remoteRevision, remoteSnapshot: latest)
    }
}

private struct IOSWebDAVResource {
    let href: String
    let displayName: String
    let sizeBytes: Int64
    let modifiedAt: Int64
    let etag: String
    let isCollection: Bool

    var fileName: String {
        if !displayName.isEmpty {
            return displayName
        }
        return href.trimmingCharacters(in: CharacterSet(charactersIn: "/")).split(separator: "/").last.map(String.init) ?? href
    }
}

private final class IOSWebDAVPropfindParser: NSObject, XMLParserDelegate {
    private var resources: [IOSWebDAVResource] = []
    private var currentHref = ""
    private var currentDisplayName = ""
    private var currentSize: Int64 = 0
    private var currentModifiedAt: Int64 = 0
    private var currentEtag = ""
    private var currentIsCollection = false
    private var currentText = ""
    private var inResponse = false

    static func parse(data: Data) -> [IOSWebDAVResource] {
        let delegate = IOSWebDAVPropfindParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.resources
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = elementName.localXMLName
        currentText = ""
        if name == "response" {
            inResponse = true
            currentHref = ""
            currentDisplayName = ""
            currentSize = 0
            currentModifiedAt = 0
            currentEtag = ""
            currentIsCollection = false
        } else if name == "collection", inResponse {
            currentIsCollection = true
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
        let name = elementName.localXMLName
        let text = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        if inResponse {
            switch name {
            case "href":
                currentHref = text
            case "displayname":
                currentDisplayName = text
            case "getcontentlength":
                currentSize = Int64(text) ?? 0
            case "getlastmodified":
                currentModifiedAt = Self.parseWebDAVDate(text)
            case "getetag":
                currentEtag = text.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            case "response":
                resources.append(IOSWebDAVResource(
                    href: currentHref,
                    displayName: currentDisplayName,
                    sizeBytes: currentSize,
                    modifiedAt: currentModifiedAt,
                    etag: currentEtag,
                    isCollection: currentIsCollection
                ))
                inResponse = false
            default:
                break
            }
        }
        currentText = ""
    }

    private static func parseWebDAVDate(_ value: String) -> Int64 {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss z"
        if let date = formatter.date(from: value) {
            return Int64(date.timeIntervalSince1970 * 1000)
        }
        if let date = ISO8601DateFormatter().date(from: value) {
            return Int64(date.timeIntervalSince1970 * 1000)
        }
        return 0
    }
}

private func safeSnapshotFileName(_ fileName: String) throws -> String {
    guard !fileName.isEmpty, fileName == URL(fileURLWithPath: fileName).lastPathComponent,
          !fileName.contains("\\"),
          fileName.hasSuffix(".\(IOSSyncBackup.fileExtension)") else {
        throw IOSSyncBackupError.invalidArchive("Invalid snapshot file name: \(fileName)")
    }
    return fileName
}

private struct IOSStoredZipArchive {
    struct Entry {
        let name: String
        let data: Data
    }

    static func write(entries: [Entry]) throws -> Data {
        var output = Data()
        var central = Data()
        var records: [(entry: Entry, crc: UInt32, offset: UInt32)] = []

        for entry in entries {
            guard let nameData = entry.name.data(using: .utf8) else { continue }
            let offset = UInt32(output.count)
            let crc = entry.data.crc32()
            output.appendUInt32LE(0x04034b50)
            output.appendUInt16LE(20)
            output.appendUInt16LE(0x0800)
            output.appendUInt16LE(0)
            output.appendUInt16LE(0)
            output.appendUInt16LE(0)
            output.appendUInt32LE(crc)
            output.appendUInt32LE(UInt32(entry.data.count))
            output.appendUInt32LE(UInt32(entry.data.count))
            output.appendUInt16LE(UInt16(nameData.count))
            output.appendUInt16LE(0)
            output.append(nameData)
            output.append(entry.data)
            records.append((entry, crc, offset))
        }

        let centralOffset = UInt32(output.count)
        for record in records {
            let nameData = Data(record.entry.name.utf8)
            central.appendUInt32LE(0x02014b50)
            central.appendUInt16LE(20)
            central.appendUInt16LE(20)
            central.appendUInt16LE(0x0800)
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt32LE(record.crc)
            central.appendUInt32LE(UInt32(record.entry.data.count))
            central.appendUInt32LE(UInt32(record.entry.data.count))
            central.appendUInt16LE(UInt16(nameData.count))
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt32LE(0)
            central.appendUInt32LE(record.offset)
            central.append(nameData)
        }
        output.append(central)
        output.appendUInt32LE(0x06054b50)
        output.appendUInt16LE(0)
        output.appendUInt16LE(0)
        output.appendUInt16LE(UInt16(records.count))
        output.appendUInt16LE(UInt16(records.count))
        output.appendUInt32LE(UInt32(central.count))
        output.appendUInt32LE(centralOffset)
        output.appendUInt16LE(0)
        return output
    }

    static func read(data: Data) throws -> [String: Data] {
        guard let eocd = data.lastRange(of: Data([0x50, 0x4b, 0x05, 0x06]))?.lowerBound else {
            throw IOSSyncBackupError.invalidArchive("Invalid ZIP: missing central directory")
        }
        let entryCount = Int(try data.uint16LE(at: eocd + 10))
        let centralOffset = Int(try data.uint32LE(at: eocd + 16))
        var cursor = centralOffset
        var result: [String: Data] = [:]

        for _ in 0..<entryCount {
            guard try data.uint32LE(at: cursor) == 0x02014b50 else {
                throw IOSSyncBackupError.invalidArchive("Invalid ZIP central directory")
            }
            let method = try data.uint16LE(at: cursor + 10)
            guard method == 0 else {
                throw IOSSyncBackupError.invalidArchive("Unsupported ZIP compression method: \(method)")
            }
            let compressedSize = Int(try data.uint32LE(at: cursor + 20))
            let nameLength = Int(try data.uint16LE(at: cursor + 28))
            let extraLength = Int(try data.uint16LE(at: cursor + 30))
            let commentLength = Int(try data.uint16LE(at: cursor + 32))
            let localOffset = Int(try data.uint32LE(at: cursor + 42))
            let nameStart = cursor + 46
            let nameEnd = nameStart + nameLength
            guard nameEnd <= data.count,
                  let name = String(data: data[nameStart..<nameEnd], encoding: .utf8) else {
                throw IOSSyncBackupError.invalidArchive("Invalid ZIP entry name")
            }
            try requireSafeRelativePath(name)
            guard try data.uint32LE(at: localOffset) == 0x04034b50 else {
                throw IOSSyncBackupError.invalidArchive("Invalid ZIP local header")
            }
            let localNameLength = Int(try data.uint16LE(at: localOffset + 26))
            let localExtraLength = Int(try data.uint16LE(at: localOffset + 28))
            let dataStart = localOffset + 30 + localNameLength + localExtraLength
            let dataEnd = dataStart + compressedSize
            guard dataEnd <= data.count else {
                throw IOSSyncBackupError.invalidArchive("Invalid ZIP entry size")
            }
            result[name] = Data(data[dataStart..<dataEnd])
            cursor = nameEnd + extraLength + commentLength
        }
        return result
    }

    private static func requireSafeRelativePath(_ path: String) throws {
        guard !path.isEmpty,
              !path.contains("\\"),
              !path.hasPrefix("/"),
              !path.contains("//"),
              !path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else {
            throw IOSSyncBackupError.invalidArchive("Invalid archive path: \(path)")
        }
    }
}

private extension Data {
    static func secureRandom(count: Int) -> Data {
        var bytes = Data(repeating: 0, count: count)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return bytes
    }

    init(base64: String, field: String) throws {
        guard let data = Data(base64Encoded: base64) else {
            throw IOSSyncBackupError.invalidArchive("Invalid base64 field: \(field)")
        }
        self = data
    }

    mutating func appendUInt16LE(_ value: UInt16) {
        append(contentsOf: [UInt8(value & 0xff), UInt8((value >> 8) & 0xff)])
    }

    mutating func appendUInt32LE(_ value: UInt32) {
        append(contentsOf: [
            UInt8(value & 0xff),
            UInt8((value >> 8) & 0xff),
            UInt8((value >> 16) & 0xff),
            UInt8((value >> 24) & 0xff),
        ])
    }

    func uint16LE(at offset: Int) throws -> UInt16 {
        guard offset + 2 <= count else { throw IOSSyncBackupError.invalidArchive("Unexpected ZIP EOF") }
        return UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func uint32LE(at offset: Int) throws -> UInt32 {
        guard offset + 4 <= count else { throw IOSSyncBackupError.invalidArchive("Unexpected ZIP EOF") }
        return UInt32(self[offset]) |
            (UInt32(self[offset + 1]) << 8) |
            (UInt32(self[offset + 2]) << 16) |
            (UInt32(self[offset + 3]) << 24)
    }

    func crc32() -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in self {
            let index = Int((crc ^ UInt32(byte)) & 0xff)
            crc = (crc >> 8) ^ IOSCRC32.table[index]
        }
        return crc ^ 0xffffffff
    }

    func sha256Hex() -> String {
        SHA256.hash(data: self).map { String(format: "%02x", $0) }.joined()
    }
}

private extension String {
    var localXMLName: String {
        split(separator: ":").last.map(String.init) ?? self
    }

    func ifBlank(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }
}

private enum IOSCRC32 {
    static let table: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 {
            crc = (crc & 1) == 1 ? (0xedb88320 ^ (crc >> 1)) : (crc >> 1)
        }
        return crc
    }
}
