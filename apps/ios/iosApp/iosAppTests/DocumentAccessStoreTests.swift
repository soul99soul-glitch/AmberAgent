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

    /// 2026-07-27 显式改写(原 `testTextPreviewIsCappedAndMarkedTruncated`):
    /// 原断言锁的是「读到 64KB 就截断」这条**人为**上限。它既不来自模型上下文,也不
    /// 来自内存安全(20MB 的可读上限另有守卫),纯粹是自己给自己设的墙——一份 27 万
    /// 字节、约 9 万汉字的小说导出稿离任何模型的上下文都还很远,却被砍掉四分之三。
    /// 上下文只会越来越长,这道墙是自废武功,故整条移除,断言随之反向。
    func testLargeTextFileIsReadInFullInsteadOfBeingCappedAt64KB() async throws {
        let store = DocumentAccessStore()
        let body = String(repeating: "赵大踏进渡口的雾里，船工没有抬头。\n", count: 8_000)
        let url = try makeTempFile(text: body, extension: "md")
        let grant = store.registerPickedFile(url)
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
        XCTAssertGreaterThan(grant.fileSize, 64 * 1024)
        XCTAssertEqual(readResult.bytesRead, Int(grant.fileSize))
        XCTAssertFalse(readResult.isTruncated)
        XCTAssertEqual(readResult.statusSummary, "完整读取")
        // 首尾都在,中间没被砍掉。
        XCTAssertTrue(readResult.preview.hasPrefix("赵大踏进渡口的雾里"))
        XCTAssertTrue(readResult.preview.hasSuffix("船工没有抬头。"))
    }

    /// 真机症状:一份 27 万字节的中文 .md,读出来整篇是韩文与生僻字。
    /// 根因是按**字节**砍到 65536,把一个汉字劈成两半 → UTF-8 解码失败 → 回退链的
    /// 下一位是 UTF-16,而 UTF-16 对任意偶数长度字节几乎**永不失败**,于是整份文件
    /// 被当成 UTF-16 读,两字节拼一个字符,全成乱码。
    func testChineseTextIsNeverDecodedAsUTF16Garbage() async throws {
        let store = DocumentAccessStore()
        let body = String(repeating: "苏未晚把那封信烧了，灰烬落进河里。", count: 5_000)
        let grant = store.registerPickedFile(try makeTempFile(text: body, extension: "md"))
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
        XCTAssertTrue(readResult.preview.contains("苏未晚把那封信烧了"))
        XCTAssertEqual(readResult.characterCount, body.count)
        // 乱码的判据:韩文音节区。中文稿子里一个都不该出现。
        XCTAssertNil(
            readResult.preview.unicodeScalars.first { (0xAC00...0xD7A3).contains($0.value) },
            "正文被当成 UTF-16 解码了"
        )
    }

    /// 中文 Windows 导出的 txt 常见 GB18030。此前它同样走不通 UTF-8,
    /// 于是也被 UTF-16 兜走,整篇乱码。
    func testGB18030TextIsDecodedAsChinese() async throws {
        let store = DocumentAccessStore()
        let body = "赵大踏进渡口的雾里，船工没有抬头。"
        let encoding = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)
        )
        let data = try XCTUnwrap((body as NSString).data(using: encoding))
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try data.write(to: url)
        let grant = store.registerPickedFile(url)
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
        XCTAssertTrue(readResult.preview.contains("赵大踏进渡口的雾里"))
    }

    /// 带 BOM 的 UTF-16 仍然要认得出来——把 UTF-16 从「万能兜底」里踢掉,
    /// 不等于不再支持真正的 UTF-16 文件。
    func testUTF16FileWithBOMIsStillDecoded() async throws {
        let store = DocumentAccessStore()
        let body = "赵大踏进渡口的雾里，船工没有抬头。"
        let data = try XCTUnwrap(body.data(using: .utf16))
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try data.write(to: url)
        let grant = store.registerPickedFile(url)
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
        XCTAssertTrue(readResult.preview.contains("赵大踏进渡口的雾里"))
    }

    /// 超过 20MB 的可读上限仍然拒收:那不是「人为的墙」,是内存安全——
    /// 把几百 MB 文本读进一个 String 再塞进提示词会直接把 App 撑爆。
    func testFilesLargerThanTheReadableLimitAreStillRejected() async throws {
        let store = DocumentAccessStore()
        XCTAssertEqual(store.maxReadableBytes, 20 * 1024 * 1024)
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

    func testMissingSelectedFileClearsGrantAndReportsRecovery() async throws {
        let store = DocumentAccessStore()
        let url = try makeTempFile(size: 16)
        let grant = store.registerPickedFile(url)
        try FileManager.default.removeItem(at: url)
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
        XCTAssertTrue(message.contains("missing"))
        XCTAssertNil(store.grantSummary)
    }

    func testWorkspaceImportStoresMetadataPreviewAndReloads() async throws {
        let baseDirectory = makeTempDirectory()
        let store = IOSWorkspaceStore(baseDirectory: baseDirectory)
        let importedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let source = try makeTempFile(text: "# Workspace\nReadable markdown body.", extension: "md")

        let record = try await store.importFile(url: source, source: "unit_test", now: importedAt)

        XCTAssertEqual(record.displayName, source.lastPathComponent)
        XCTAssertEqual(record.originalFileName, source.lastPathComponent)
        XCTAssertTrue(record.workspacePath.hasPrefix("uploads/"))
        XCTAssertFalse(record.workspacePath.contains(".."))
        XCTAssertEqual(record.status, .ready)
        XCTAssertEqual(record.source, "unit_test")
        XCTAssertTrue(record.preview.contains("Readable markdown body."))
        XCTAssertEqual(store.files.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.fileURL(for: record).path))

        let reloaded = IOSWorkspaceStore(baseDirectory: baseDirectory)
        XCTAssertEqual(reloaded.files.first?.id, record.id)
        XCTAssertEqual(reloaded.files.first?.preview, record.preview)
    }

    func testWorkspaceReparseMarksMissingAndRemoveDeletesRecord() async throws {
        let store = makeWorkspaceStore()
        let source = try makeTempFile(text: "File that will disappear.", extension: "txt")
        let record = try await store.importFile(url: source, source: "unit_test")
        try FileManager.default.removeItem(at: store.fileURL(for: record))

        let reparsed = try await store.reparseFile(id: record.id)

        XCTAssertEqual(reparsed.status, .missing)
        XCTAssertTrue(reparsed.statusMessage.contains("missing"))
        try store.removeFile(id: record.id)
        XCTAssertTrue(store.files.isEmpty)
    }

    func testWorkspaceArtifactSaveReadReloadAndDelete() throws {
        let baseDirectory = makeTempDirectory()
        let store = IOSWorkspaceStore(baseDirectory: baseDirectory)

        let artifact = try store.saveArtifact(
            title: "Report",
            content: "Artifact body",
            type: .chat,
            sourceKind: "unit_test",
            sourceId: "message-1"
        )

        XCTAssertEqual(artifact.title, "Report")
        XCTAssertEqual(artifact.type, .chat)
        XCTAssertEqual(artifact.sourceKind, "unit_test")
        XCTAssertEqual(try store.artifactContent(id: artifact.id), "Artifact body")

        let reloaded = IOSWorkspaceStore(baseDirectory: baseDirectory)
        XCTAssertEqual(reloaded.artifacts.first?.id, artifact.id)
        XCTAssertEqual(try reloaded.artifactContent(id: artifact.id), "Artifact body")

        try reloaded.deleteArtifact(id: artifact.id)
        XCTAssertTrue(reloaded.artifacts.isEmpty)
        XCTAssertThrowsError(try reloaded.artifactContent(id: artifact.id))
    }

    func testWorkspaceToolWriteReadAndOverwriteProtection() async throws {
        let store = makeWorkspaceStore()
        let write = await store.executeTool(
            toolName: "workspace_file_write",
            input: #"{"path":"/workspace/notes/a.md","content":"hello"}"#
        )
        XCTAssertEqual(try jsonObject(write)["ok"] as? Bool, true)

        let duplicate = await store.executeTool(
            toolName: "workspace_file_write",
            input: #"{"path":"/workspace/notes/a.md","content":"again"}"#
        )
        let duplicateObject = try jsonObject(duplicate)
        XCTAssertEqual(duplicateObject["ok"] as? Bool, false)
        XCTAssertTrue((duplicateObject["error"] as? String)?.contains("already exists") == true)

        let read = await store.executeTool(
            toolName: "workspace_file_read",
            input: #"{"path":"/workspace/notes/a.md"}"#
        )
        let readObject = try jsonObject(read)
        XCTAssertEqual(readObject["ok"] as? Bool, true)
        XCTAssertEqual(readObject["text"] as? String, "hello")
    }

    func testWorkspaceImportRejectsTooLargeFile() async throws {
        let store = makeWorkspaceStore()
        let tooLarge = try makeTempFile(size: Int(store.maxImportBytes) + 1)

        do {
            _ = try await store.importFile(url: tooLarge, source: "unit_test")
            XCTFail("Expected Workspace import to reject files over its local limit.")
        } catch let error as IOSWorkspaceStoreError {
            guard case .fileTooLarge(let message) = error else {
                return XCTFail("Expected fileTooLarge, got \(error).")
            }
            XCTAssertTrue(message.contains("Workspace import limit"))
            XCTAssertTrue(message.contains(DocumentAccessStore.formatBytes(store.maxImportBytes)))
        }
    }

    private func makeExecutor(documentStore: DocumentAccessStore) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
    }

    private func makeWorkspaceStore() -> IOSWorkspaceStore {
        IOSWorkspaceStore(baseDirectory: makeTempDirectory())
    }

    private func makeTempDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
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

    private func jsonObject(_ text: String) throws -> [String: Any] {
        let data = try XCTUnwrap(text.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
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
