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
    case unsupportedCrypto(String)

    var errorDescription: String? {
        switch self {
        case .emptyPayload:
            return "备份内容为空"
        case .invalidArchive(let message):
            return message
        case .invalidPassphrase:
            return "同步口令不能为空"
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

    static func export(settings: Settings, passphrase: String?) throws -> Data {
        let settingsJson = IosSettingsJsonBridge.shared.encode(settings: settings)
        let settingsData = Data(settingsJson.utf8)
        let payloadManifest = IOSSyncPayloadManifest(datasets: [
            IOSSyncDatasetSummary(id: "settings", recordCount: 1, byteCount: Int64(settingsData.count))
        ])
        let payloadManifestData = try JSONEncoder().encode(payloadManifest)
        let payloadZip = try IOSStoredZipArchive.write(entries: [
            .init(name: settingsEntry, data: settingsData),
            .init(name: payloadManifestEntry, data: payloadManifestData),
        ])

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
            remoteRevision: "",
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
        let settings = IosSettingsJsonBridge.shared.decode(json: settingsJson)
        return (settings, IOSSyncPreview(manifest: manifest, sizeBytes: Int64(data.count)))
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

struct IOSSyncPreview {
    let manifest: IOSSyncManifest
    let sizeBytes: Int64
}

struct IOSSyncManifest: Codable {
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
}

struct IOSSyncKdfInfo: Codable {
    var name: String = "PBKDF2WithHmacSHA256"
    let iterations: Int
    let saltBase64: String
    var keySizeBits: Int = 256
}

struct IOSSyncCipherInfo: Codable {
    var name: String = "AES/GCM/NoPadding"
    let ivBase64: String
    var tagSizeBits: Int = 128
}

private struct IOSSyncPayloadManifest: Codable {
    let datasets: [IOSSyncDatasetSummary]
}

private struct IOSSyncDatasetSummary: Codable {
    let id: String
    let recordCount: Int
    let byteCount: Int64
}

private enum IOSDeviceLabel {
    static var current: String {
        let device = UIDevice.current
        return [device.systemName, device.model].filter { !$0.isEmpty }.joined(separator: " ")
    }
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
