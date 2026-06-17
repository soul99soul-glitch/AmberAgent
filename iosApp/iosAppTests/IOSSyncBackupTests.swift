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
        let originalJson = IosSettingsJsonBridge.shared.encode(settings: settings)
        let importedJson = IosSettingsJsonBridge.shared.encode(settings: imported.settings)
        XCTAssertEqual(importedJson, originalJson)
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

    func testImportRejectsWrongPassphraseForProtectedArchive() throws {
        let settings = IosSettingsDefaults.shared.defaultSeededSettings()
        let archive = try IOSSyncBackup.export(settings: settings, passphrase: "right")

        XCTAssertThrowsError(try IOSSyncBackup.import(data: archive, passphrase: "wrong"))
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
