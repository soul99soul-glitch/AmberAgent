import XCTest
@testable import iosApp
import Shared

final class IOSSyncBackupTests: XCTestCase {
    func testExportCreatesAndroidShapedEncryptedArchiveAndRoundTripsSettings() throws {
        let settings = IosSettingsDefaults.shared.defaultSeededSettings()

        let archive = try IOSSyncBackup.export(settings: settings, passphrase: "correct horse battery staple")
        let outerEntries = try TestZipReader.read(data: archive)
        let manifestData = try XCTUnwrap(outerEntries["manifest.json"])
        let encryptedPayload = try XCTUnwrap(outerEntries["payload.enc"])
        let manifest = try JSONDecoder().decode(IOSSyncManifest.self, from: manifestData)

        XCTAssertEqual(manifest.archiveVersion, 1)
        XCTAssertEqual(manifest.kdf.name, "PBKDF2WithHmacSHA256")
        XCTAssertEqual(manifest.kdf.iterations, 210_000)
        XCTAssertEqual(manifest.kdf.keySizeBits, 256)
        XCTAssertEqual(Data(base64Encoded: manifest.kdf.saltBase64)?.count, 16)
        XCTAssertEqual(manifest.cipher.name, "AES/GCM/NoPadding")
        XCTAssertEqual(Data(base64Encoded: manifest.cipher.ivBase64)?.count, 12)
        XCTAssertEqual(manifest.cipher.tagSizeBits, 128)
        XCTAssertTrue(manifest.passphraseProtected)
        XCTAssertGreaterThan(encryptedPayload.count, 16)
        XCTAssertNil(String(data: encryptedPayload, encoding: .utf8)?.contains("settings"))

        let imported = try IOSSyncBackup.import(data: archive, passphrase: "correct horse battery staple")
        // Scheme B: the backup redacts credentials before encryption (parity
        // with Android BackupSettingsRedactor). So the round-tripped settings
        // equal the REDACTED original, not the raw original — credentials come
        // back as the mask sentinel. Compare parsed JSON (not raw bytes) because
        // the redactor and kotlinx use different serializers/formatting.
        let redactedOriginalJson = IOSCredentialRedactor.redact(IosSettingsJsonBridge.shared.encode(settings: settings))
        let importedJson = IosSettingsJsonBridge.shared.encode(settings: imported.settings)
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: Data(importedJson.utf8)) as? NSObject,
            try JSONSerialization.jsonObject(with: Data(redactedOriginalJson.utf8)) as? NSObject,
            "Round-tripped settings must equal the redacted original (credentials masked)."
        )
        XCTAssertEqual(imported.preview.manifest.payloadSha256, manifest.payloadSha256)
    }

    func testExportWithEmptyPassphraseUsesFallbackAndImportsWithoutPrompt() throws {
        let settings = IosSettingsDefaults.shared.defaultSeededSettings()

        let archive = try IOSSyncBackup.export(settings: settings, passphrase: "")
        let manifestData = try XCTUnwrap(TestZipReader.read(data: archive)["manifest.json"])
        let manifest = try JSONDecoder().decode(IOSSyncManifest.self, from: manifestData)

        XCTAssertFalse(manifest.passphraseProtected)
        XCTAssertNoThrow(try IOSSyncBackup.import(data: archive, passphrase: nil))
    }

    func testManifestDecodeKeepsKotlinDefaultFieldsCompatible() throws {
        let data = Data("""
        {
          "appVersionName": "1.0",
          "appVersionCode": 1,
          "createdAt": 1800000000000,
          "deviceId": "device",
          "mode": "STANDARD",
          "kdf": { "iterations": 210000, "saltBase64": "MTIzNDU2Nzg5MDEyMzQ1Ng==" },
          "cipher": { "ivBase64": "MTIzNDU2Nzg5MDE=" },
          "payloadSha256": "abc123"
        }
        """.utf8)

        let manifest = try JSONDecoder().decode(IOSSyncManifest.self, from: data)

        XCTAssertEqual(manifest.archiveVersion, 1)
        XCTAssertEqual(manifest.deviceLabel, "")
        XCTAssertEqual(manifest.remoteRevision, "")
        XCTAssertTrue(manifest.encrypted)
        XCTAssertTrue(manifest.passphraseProtected)
        XCTAssertEqual(manifest.kdf.name, "PBKDF2WithHmacSHA256")
        XCTAssertEqual(manifest.kdf.keySizeBits, 256)
        XCTAssertEqual(manifest.cipher.name, "AES/GCM/NoPadding")
        XCTAssertEqual(manifest.cipher.tagSizeBits, 128)
    }

    func testImportRejectsWrongPassphraseForProtectedArchive() throws {
        let settings = IosSettingsDefaults.shared.defaultSeededSettings()
        let archive = try IOSSyncBackup.export(settings: settings, passphrase: "right")

        XCTAssertThrowsError(try IOSSyncBackup.import(data: archive, passphrase: "wrong"))
    }

    func testRestorePreviewReadsManifestAndDatasetSummaryWithoutApplying() throws {
        let suiteName = "IOSSyncBackupPreview-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let originalJson = IosSettingsJsonBridge.shared.encode(settings: store.snapshot)

        let archive = try IOSSyncBackup.export(settings: store.snapshot, passphrase: "preview")
        store.addCustomModel(name: "Preview Mutation", modelId: "preview-model")
        let mutatedJson = IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        XCTAssertNotEqual(mutatedJson, originalJson)

        let preview = try IOSSyncBackup.restorePreview(data: archive, passphrase: "preview", fileName: "preview.amberbackup")
        XCTAssertEqual(preview.fileName, "preview.amberbackup")
        XCTAssertEqual(preview.datasets.first?.id, "settings")
        XCTAssertEqual(preview.datasets.first?.recordCount, 1)
        XCTAssertGreaterThan(preview.datasets.first?.byteCount ?? 0, 0)
        XCTAssertEqual(IosSettingsJsonBridge.shared.encode(settings: store.snapshot), mutatedJson)
    }

    func testRestoreApplyWritesSharedSettingsAndRemoteStatus() throws {
        let suiteName = "IOSSyncBackupApply-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let originalSettings = store.snapshot
        let originalJson = IosSettingsJsonBridge.shared.encode(settings: originalSettings)
        let archive = try IOSSyncBackup.export(settings: originalSettings, passphrase: "apply")

        store.addCustomModel(name: "Apply Mutation", modelId: "apply-model")
        XCTAssertNotEqual(IosSettingsJsonBridge.shared.encode(settings: store.snapshot), originalJson)

        let result = try IOSSyncBackup.import(data: archive, passphrase: "apply")
        store.restoreSnapshot(result.settings)
        let snapshot = makeSnapshot(fileName: "remote.amberbackup", revision: "remote-rev", manifest: result.preview.manifest)
        store.recordRemoteDownload(snapshot: snapshot, preview: result.preview)

        // Scheme B: the backup redacts credentials. After import+restore the
        // in-memory snapshot equals the REDACTED original (credentials come back
        // as the mask sentinel); P2's Keychain side-table will rehydrate the real
        // values into the in-memory snapshot on load. Compare parsed JSON (not
        // raw bytes) because the redactor and kotlinx use different serializers.
        let redactedOriginalJson = IOSCredentialRedactor.redact(originalJson)
        let restoredJson = IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: Data(restoredJson.utf8)) as? NSObject,
            try JSONSerialization.jsonObject(with: Data(redactedOriginalJson.utf8)) as? NSObject,
            "Restored snapshot must equal the redacted original (credentials masked)."
        )
        XCTAssertEqual(store.remoteSyncStatus.remoteRevision, "remote-rev")
        XCTAssertGreaterThan(store.remoteSyncStatus.lastDownloadAt, 0)
    }

    func testLocalFolderProviderUploadListDownloadDeleteRoundTrip() async throws {
        let folder = temporaryDirectory().appendingPathComponent("local-provider", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let settings = IosSettingsDefaults.shared.defaultSeededSettings()
        let archive = try IOSSyncBackup.export(settings: settings, passphrase: "local")
        let preview = try IOSSyncBackup.inspectManifest(data: archive, fileName: "local.amberbackup")
        let provider = IOSLocalFolderSyncProvider(folderURL: folder)

        let uploaded = try await provider.uploadSnapshot(
            data: archive,
            fileName: "local.amberbackup",
            manifest: preview.manifest
        )
        XCTAssertEqual(uploaded.fileName, "local.amberbackup")
        XCTAssertEqual(uploaded.provider, .localFolder)
        XCTAssertFalse(uploaded.remoteRevision.isEmpty)

        let listed = try await provider.listSnapshots()
        XCTAssertEqual(listed.map(\.fileName), ["local.amberbackup"])
        let downloaded = try await provider.downloadSnapshot(uploaded)
        XCTAssertEqual(downloaded, archive)

        try await provider.deleteSnapshot(uploaded)
        let remaining = try await provider.listSnapshots()
        XCTAssertTrue(remaining.isEmpty)
    }

    func testWebDAVProviderBuildsPropfindPutGetDeleteRequestsWithMockTransport() async throws {
        let settings = IosSettingsDefaults.shared.defaultSeededSettings()
        let archive = try IOSSyncBackup.export(settings: settings, passphrase: "webdav")
        let preview = try IOSSyncBackup.inspectManifest(data: archive)
        let transport = MockWebDAVTransport(responses: [
            .empty(405),
            .xml(207, webDAVListXML(fileName: "remote.amberbackup", etag: "remote-etag", size: archive.count)),
            .empty(405),
            .empty(201, headers: ["ETag": "\"uploaded-etag\""]),
            .payload(200, archive),
            .empty(204),
        ])
        let provider = IOSWebDAVSyncProvider(
            config: IOSWebDAVConfig(
                baseURL: "https://example.com/dav",
                path: "Amber Agent/sync",
                username: "user",
                password: "pass"
            ),
            transport: transport
        )

        let listed = try await provider.listSnapshots()
        XCTAssertEqual(listed.count, 1)
        XCTAssertEqual(listed.first?.fileName, "remote.amberbackup")
        XCTAssertEqual(listed.first?.remoteRevision, "remote-etag")

        let uploaded = try await provider.uploadSnapshot(
            data: archive,
            fileName: "remote.amberbackup",
            manifest: preview.manifest
        )
        XCTAssertEqual(uploaded.remoteRevision, "uploaded-etag")
        let downloaded = try await provider.downloadSnapshot(uploaded)
        XCTAssertEqual(downloaded, archive)
        try await provider.deleteSnapshot(uploaded)

        XCTAssertEqual(transport.requests.map(\.httpMethod), ["MKCOL", "PROPFIND", "MKCOL", "PUT", "GET", "DELETE"])
        XCTAssertEqual(transport.requests[1].value(forHTTPHeaderField: "Depth"), "1")
        XCTAssertEqual(transport.requests[3].value(forHTTPHeaderField: "Content-Type"), IOSSyncBackup.mimeType)
        XCTAssertEqual(
            transport.requests.first?.value(forHTTPHeaderField: "Authorization"),
            "Basic \(Data("user:pass".utf8).base64EncodedString())"
        )
        XCTAssertTrue(transport.requests[3].url?.absoluteString.contains("Amber%20Agent/sync/remote.amberbackup") == true)
        XCTAssertEqual(transport.bodies[3], archive)
    }

    func testConflictDetectionFlagsRemoteRevisionMismatch() throws {
        var status = IOSRemoteSyncStatus()
        status.setRemoteRevision("local-revision", for: .localFolder)
        let manifest = try IOSSyncBackup.inspectManifest(
            data: IOSSyncBackup.export(settings: IosSettingsDefaults.shared.defaultSeededSettings(), passphrase: "conflict")
        ).manifest
        let remote = makeSnapshot(fileName: "remote.amberbackup", revision: "remote-revision", manifest: manifest)

        let conflict = IOSSyncConflictResolver.conflict(status: status, remoteSnapshots: [remote])
        XCTAssertEqual(conflict?.localRevision, "local-revision")
        XCTAssertEqual(conflict?.remoteSnapshot.remoteRevision, "remote-revision")

        status.setRemoteRevision("remote-revision", for: .localFolder)
        XCTAssertNil(IOSSyncConflictResolver.conflict(status: status, remoteSnapshots: [remote]))
    }

    func testConflictDetectionIsScopedToProvider() throws {
        var status = IOSRemoteSyncStatus()
        status.setRemoteRevision("local-folder-revision", for: .localFolder)
        let manifest = try IOSSyncBackup.inspectManifest(
            data: IOSSyncBackup.export(settings: IosSettingsDefaults.shared.defaultSeededSettings(), passphrase: "provider")
        ).manifest
        let webDAVSnapshot = makeSnapshot(
            fileName: "remote.amberbackup",
            provider: .webDAV,
            revision: "webdav-revision",
            manifest: manifest
        )

        XCTAssertNil(IOSSyncConflictResolver.conflict(status: status.scoped(to: .webDAV), remoteSnapshots: [webDAVSnapshot]))

        status.setRemoteRevision("old-webdav-revision", for: .webDAV)
        XCTAssertNotNil(IOSSyncConflictResolver.conflict(status: status.scoped(to: .webDAV), remoteSnapshots: [webDAVSnapshot]))
        XCTAssertNil(IOSSyncConflictResolver.conflict(status: status.scoped(to: .localFolder), remoteSnapshots: [webDAVSnapshot]))
    }

    func testLegacySyncStatusDecodeScopesExistingRevisionToLocalFolder() throws {
        let data = Data(#"{"lastUploadAt":10,"remoteRevision":"legacy-revision"}"#.utf8)

        let status = try JSONDecoder().decode(IOSRemoteSyncStatus.self, from: data)

        XCTAssertEqual(status.remoteRevision(for: .localFolder), "legacy-revision")
        XCTAssertEqual(status.remoteRevision(for: .webDAV), "")
    }

    func testSyncStatusPersistsAcrossStoreInstances() throws {
        let suiteName = "IOSSyncStatus-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let preview = try IOSSyncBackup.inspectManifest(
            data: IOSSyncBackup.export(settings: store.snapshot, passphrase: "status")
        )
        let snapshot = makeSnapshot(fileName: "status.amberbackup", revision: "status-revision", manifest: preview.manifest)

        store.recordRemoteUpload(snapshot: snapshot, preview: preview)
        let restored = IOSSharedSettingsStore(userDefaults: defaults)

        XCTAssertEqual(restored.remoteSyncStatus.remoteRevision, "status-revision")
        XCTAssertEqual(restored.remoteSyncStatus.remoteRevision(for: .localFolder), "status-revision")
        XCTAssertEqual(restored.remoteSyncStatus.deviceLabel, preview.manifest.deviceLabel)
        XCTAssertGreaterThan(restored.remoteSyncStatus.lastUploadAt, 0)
        XCTAssertEqual(restored.remoteSyncStatus.lastError, "")
    }
}

private func makeSnapshot(
    fileName: String,
    provider: IOSRemoteProviderKind = .localFolder,
    revision: String,
    manifest: IOSSyncManifest
) -> IOSRemoteSnapshot {
    IOSRemoteSnapshot(
        fileName: fileName,
        provider: provider,
        manifest: manifest,
        modifiedAt: manifest.createdAt,
        sizeBytes: 128,
        remoteRevision: revision
    )
}

private func temporaryDirectory() -> URL {
    FileManager.default.temporaryDirectory
        .appendingPathComponent("IOSSyncBackupTests-\(UUID().uuidString)", isDirectory: true)
}

private func webDAVListXML(fileName: String, etag: String, size: Int) -> Data {
    Data("""
    <?xml version="1.0" encoding="utf-8"?>
    <D:multistatus xmlns:D="DAV:">
      <D:response>
        <D:href>/dav/Amber%20Agent/sync/</D:href>
        <D:propstat>
          <D:prop>
            <D:displayname>sync</D:displayname>
            <D:resourcetype><D:collection/></D:resourcetype>
          </D:prop>
        </D:propstat>
      </D:response>
      <D:response>
        <D:href>/dav/Amber%20Agent/sync/\(fileName)</D:href>
        <D:propstat>
          <D:prop>
            <D:displayname>\(fileName)</D:displayname>
            <D:getcontentlength>\(size)</D:getcontentlength>
            <D:getlastmodified>Wed, 19 Jun 2026 08:12:31 GMT</D:getlastmodified>
            <D:getetag>"\(etag)"</D:getetag>
            <D:resourcetype/>
          </D:prop>
        </D:propstat>
      </D:response>
    </D:multistatus>
    """.utf8)
}

private final class MockWebDAVTransport: IOSWebDAVTransport, @unchecked Sendable {
    struct Response {
        let statusCode: Int
        let headers: [String: String]
        let data: Data

        static func empty(_ statusCode: Int, headers: [String: String] = [:]) -> Response {
            Response(statusCode: statusCode, headers: headers, data: Data())
        }

        static func payload(_ statusCode: Int, _ data: Data, headers: [String: String] = [:]) -> Response {
            Response(statusCode: statusCode, headers: headers, data: data)
        }

        static func xml(_ statusCode: Int, _ data: Data) -> Response {
            Response(statusCode: statusCode, headers: ["Content-Type": "application/xml"], data: data)
        }
    }

    private var responses: [Response]
    private(set) var requests: [URLRequest] = []
    private(set) var bodies: [Data?] = []

    init(responses: [Response]) {
        self.responses = responses
    }

    func send(_ request: URLRequest, body: Data?) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        bodies.append(body)
        let response = responses.removeFirst()
        let http = try XCTUnwrap(HTTPURLResponse(
            url: try XCTUnwrap(request.url),
            statusCode: response.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: response.headers
        ))
        return (http, response.data)
    }
}

private enum TestZipReader {
    static func read(data: Data) throws -> [String: Data] {
        guard let eocd = data.lastRange(of: Data([0x50, 0x4b, 0x05, 0x06]))?.lowerBound else {
            throw TestZipError.invalid
        }
        let entryCount = Int(try data.uint16LE(at: eocd + 10))
        var cursor = Int(try data.uint32LE(at: eocd + 16))
        var result: [String: Data] = [:]

        for _ in 0..<entryCount {
            guard try data.uint32LE(at: cursor) == 0x02014b50 else { throw TestZipError.invalid }
            let method = try data.uint16LE(at: cursor + 10)
            guard method == 0 else { throw TestZipError.unsupported }
            let compressedSize = Int(try data.uint32LE(at: cursor + 20))
            let nameLength = Int(try data.uint16LE(at: cursor + 28))
            let extraLength = Int(try data.uint16LE(at: cursor + 30))
            let commentLength = Int(try data.uint16LE(at: cursor + 32))
            let localOffset = Int(try data.uint32LE(at: cursor + 42))
            let nameStart = cursor + 46
            let nameEnd = nameStart + nameLength
            guard let name = String(data: data[nameStart..<nameEnd], encoding: .utf8) else {
                throw TestZipError.invalid
            }
            let localNameLength = Int(try data.uint16LE(at: localOffset + 26))
            let localExtraLength = Int(try data.uint16LE(at: localOffset + 28))
            let dataStart = localOffset + 30 + localNameLength + localExtraLength
            let dataEnd = dataStart + compressedSize
            result[name] = Data(data[dataStart..<dataEnd])
            cursor = nameEnd + extraLength + commentLength
        }
        return result
    }
}

private enum TestZipError: Error {
    case invalid
    case unsupported
}

private extension Data {
    func uint16LE(at offset: Int) throws -> UInt16 {
        guard offset + 2 <= count else { throw TestZipError.invalid }
        return UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func uint32LE(at offset: Int) throws -> UInt32 {
        guard offset + 4 <= count else { throw TestZipError.invalid }
        return UInt32(self[offset]) |
            (UInt32(self[offset + 1]) << 8) |
            (UInt32(self[offset + 2]) << 16) |
            (UInt32(self[offset + 3]) << 24)
    }
}
