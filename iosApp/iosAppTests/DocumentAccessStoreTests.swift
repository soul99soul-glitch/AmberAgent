import XCTest
import UIKit
@testable import iosApp

@MainActor
final class DocumentAccessStoreTests: XCTestCase {
    func testExpiredGrantIsRejected() async throws {
        let store = DocumentAccessStore()
        let createdAt = Date(timeIntervalSince1970: 100)
        let grant = store.registerPickedFile(try makeTempFile(size: 16), now: createdAt)
        let executor = makeExecutor(documentStore: store)
        let request = IOSToolInvocationRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: request.toolName,
                operation: request.operation,
                scopeDigest: request.scopeDigest,
                payloadDigest: request.payloadDigest,
                isUserInitiated: request.isUserInitiated
            ),
            now: createdAt.addingTimeInterval(store.ttlSeconds + 1)
        )

        guard case .denied(let reason) = result else {
            return XCTFail("Expected denied, got \(result)")
        }
        XCTAssertTrue(reason.contains("expired"))
    }

    func testFilesLargerThanImportLimitAreRejected() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeTempFile(size: Int(store.maxReadableBytes + 1)))
        let executor = makeExecutor(documentStore: store)
        let request = IOSLocalToolExecutionRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let result = await executor.execute(request)

        guard case .denied(let reason) = result else {
            return XCTFail("Expected denied, got \(result)")
        }
        XCTAssertTrue(reason.contains("file-context import limit"))
    }

    func testTextPreviewIsCappedAndMarkedTruncated() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeTempFile(size: store.maxPreviewBytes + 4096))
        let executor = makeExecutor(documentStore: store)
        let request = IOSLocalToolExecutionRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let result = await executor.execute(request)

        guard case .selectedFilePreview(let readResult) = result else {
            return XCTFail("Expected success, got \(result)")
        }
        XCTAssertLessThanOrEqual(readResult.bytesRead, store.maxPreviewBytes)
        XCTAssertEqual(readResult.bytesRead, store.maxPreviewBytes)
        XCTAssertTrue(readResult.isTruncated)
        XCTAssertTrue(readResult.statusSummary.contains("截断"))
        XCTAssertEqual(readResult.fileName, grant.fileName)
        XCTAssertEqual(readResult.totalBytes, grant.fileSize)
    }

    func testMarkdownTextFileExtractsReadableContext() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeTempFile(text: "# Title\nReadable markdown body.", extension: "md"))
        let executor = makeExecutor(documentStore: store)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .selectedFilePreview(let readResult) = result else {
            return XCTFail("Expected success, got \(result)")
        }
        XCTAssertTrue(readResult.preview.contains("Readable markdown body."))
        XCTAssertFalse(readResult.isTruncated)
        XCTAssertEqual(readResult.statusSummary, "完整读取")
    }

    func testJsonAndCsvFilesExtractReadableContext() async throws {
        for sample in [
            ("json", #"{"title":"Readable JSON","count":2}"#),
            ("csv", "name,value\namber,agent")
        ] {
            let store = DocumentAccessStore()
            let grant = store.registerPickedFile(try makeTempFile(text: sample.1, extension: sample.0))
            let executor = makeExecutor(documentStore: store)

            let result = await executor.execute(
                IOSLocalToolExecutionRequest(
                    toolName: grant.toolName,
                    operation: grant.operation,
                    scopeDigest: grant.scopeDigest,
                    payloadDigest: grant.payloadDigest,
                    isUserInitiated: true
                )
            )

            guard case .selectedFilePreview(let readResult) = result else {
                return XCTFail("Expected success for \(sample.0), got \(result)")
            }
            XCTAssertTrue(readResult.preview.contains(sample.0 == "json" ? "Readable JSON" : "amber,agent"))
        }
    }

    func testPDFExtractsReadableText() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makePDFFile(text: "PDF readable text for AmberAgent"))
        let executor = makeExecutor(documentStore: store)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .selectedFilePreview(let readResult) = result else {
            return XCTFail("Expected success, got \(result)")
        }
        XCTAssertTrue(readResult.preview.contains("PDF readable text"))
        XCTAssertFalse(readResult.preview.contains("OCR"))
    }

    func testDocxExtractsReadableText() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeDocxFile(text: "DOCX readable body & details"))
        let executor = makeExecutor(documentStore: store)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .selectedFilePreview(let readResult) = result else {
            return XCTFail("Expected success, got \(result)")
        }
        XCTAssertTrue(readResult.preview.contains("DOCX readable body & details"))
    }

    func testUnsupportedBinaryFileReturnsUserReadableError() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeTempFile(text: "\u{0}\u{1}\u{2}", extension: "bin"))
        let executor = makeExecutor(documentStore: store)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .failed(let message) = result else {
            return XCTFail("Expected failed, got \(result)")
        }
        XCTAssertTrue(message.contains("暂不支持"))
    }

    func testImageFileDoesNotPretendToOCR() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeBinaryFile(bytes: [
            0x89, 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A
        ], extension: "png"))
        let executor = makeExecutor(documentStore: store)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .failed(let message) = result else {
            return XCTFail("Expected failed, got \(result)")
        }
        XCTAssertTrue(message.contains("OCR"))
    }

    func testFileSizeIsCheckedAgainAtReadTime() async throws {
        let store = DocumentAccessStore()
        let url = try makeTempFile(size: 16)
        let grant = store.registerPickedFile(url)
        let executor = makeExecutor(documentStore: store)
        try Data(repeating: 65, count: Int(store.maxReadableBytes + 1)).write(to: url)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .failed(let message) = result else {
            return XCTFail("Expected failed, got \(result)")
        }
        XCTAssertTrue(message.contains("larger"))
    }

    private func makeExecutor(documentStore: DocumentAccessStore) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
    }

    private func makeTempFile(size: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data(repeating: 65, count: size).write(to: url)
        return url
    }

    private func makeTempFile(text: String, extension fileExtension: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(fileExtension)
        try Data(text.utf8).write(to: url)
        return url
    }

    private func makeBinaryFile(bytes: [UInt8], extension fileExtension: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(fileExtension)
        try Data(bytes).write(to: url)
        return url
    }

    private func makePDFFile(text: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        try renderer.writePDF(to: url) { context in
            context.beginPage()
            text.draw(
                at: CGPoint(x: 48, y: 48),
                withAttributes: [.font: UIFont.systemFont(ofSize: 16)]
            )
        }
        return url
    }

    private func makeDocxFile(text: String) throws -> URL {
        let escaped = text
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        let documentXML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:p><w:r><w:t>\(escaped)</w:t></w:r></w:p>
          </w:body>
        </w:document>
        """
        let contentTypes = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="xml" ContentType="application/xml"/>
        </Types>
        """
        let zip = makeStoredZip(entries: [
            ("[Content_Types].xml", Data(contentTypes.utf8)),
            ("word/document.xml", Data(documentXML.utf8))
        ])
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("docx")
        try zip.write(to: url)
        return url
    }

    private func makeStoredZip(entries: [(String, Data)]) -> Data {
        var output = Data()
        var central = Data()
        var records: [(name: String, data: Data, offset: UInt32)] = []

        for entry in entries {
            let nameData = Data(entry.0.utf8)
            let offset = UInt32(output.count)
            output.appendUInt32LE(0x04034b50)
            output.appendUInt16LE(20)
            output.appendUInt16LE(0x0800)
            output.appendUInt16LE(0)
            output.appendUInt16LE(0)
            output.appendUInt16LE(0)
            output.appendUInt32LE(0)
            output.appendUInt32LE(UInt32(entry.1.count))
            output.appendUInt32LE(UInt32(entry.1.count))
            output.appendUInt16LE(UInt16(nameData.count))
            output.appendUInt16LE(0)
            output.append(nameData)
            output.append(entry.1)
            records.append((entry.0, entry.1, offset))
        }

        let centralOffset = UInt32(output.count)
        for record in records {
            let nameData = Data(record.name.utf8)
            central.appendUInt32LE(0x02014b50)
            central.appendUInt16LE(20)
            central.appendUInt16LE(20)
            central.appendUInt16LE(0x0800)
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt16LE(0)
            central.appendUInt32LE(0)
            central.appendUInt32LE(UInt32(record.data.count))
            central.appendUInt32LE(UInt32(record.data.count))
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

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

private extension Data {
    mutating func appendUInt16LE(_ value: UInt16) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
    }

    mutating func appendUInt32LE(_ value: UInt32) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 24) & 0xff))
    }
}
