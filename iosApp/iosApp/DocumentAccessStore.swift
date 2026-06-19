import CryptoKit
import Foundation
import PDFKit
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
    let fileType: String
    let totalBytes: Int64
    let bytesRead: Int
    let characterCount: Int
    let preview: String
    let isTruncated: Bool
    let note: String?

    var byteSummary: String {
        guard totalBytes > 0 else { return "\(bytesRead) bytes" }
        return "\(bytesRead)/\(totalBytes) bytes"
    }

    var statusSummary: String {
        if let note, !note.isEmpty { return note }
        return isTruncated ? "内容已截断" : "完整读取"
    }
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
    let maxReadableBytes: Int64 = 20 * 1024 * 1024
    let maxPreviewBytes = 64 * 1024
    let maxPreviewCharacters = 60_000

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
        let fileSize = Int64(values?.fileSize ?? Self.fileSize(for: url) ?? 0)
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
            errorMessage = "Selected file is larger than the file-context import limit of \(Self.formatBytes(maxReadableBytes))."
            return .failure(.fileTooLarge)
        }
        guard !isReading else {
            return .failure(.alreadyReading)
        }

        isReading = true
        errorMessage = nil
        let grantId = current.id
        let url = current.url
        let fileType = current.fileType
        let maxReadableBytes = maxReadableBytes
        let maxPreviewBytes = maxPreviewBytes
        let maxPreviewCharacters = maxPreviewCharacters

        let result = await Self.readPreview(
            url: url,
            fileType: fileType,
            maxReadableBytes: maxReadableBytes,
            maxPreviewBytes: maxPreviewBytes,
            maxPreviewCharacters: maxPreviewCharacters
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
        fileType: String,
        maxReadableBytes: Int64,
        maxPreviewBytes: Int,
        maxPreviewCharacters: Int
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
                let kind = selectedDocumentKind(url: url, fileType: fileType)
                switch kind {
                case .text:
                    let result = try readTextPreview(
                        url: url,
                        fileType: fileType,
                        fileSize: fileSize,
                        maxPreviewBytes: maxPreviewBytes,
                        maxPreviewCharacters: maxPreviewCharacters
                    )
                    return .success(result)
                case .pdf:
                    let result = try readPDFPreview(
                        url: url,
                        fileType: fileType,
                        fileSize: fileSize,
                        maxPreviewCharacters: maxPreviewCharacters
                    )
                    return .success(result)
                case .docx:
                    let result = try readDocxPreview(
                        url: url,
                        fileType: fileType,
                        fileSize: fileSize,
                        maxPreviewCharacters: maxPreviewCharacters
                    )
                    return .success(result)
                case .image:
                    return .failure(DocumentAccessError.unsupportedFileType("图片文件暂不能作为文本上下文读取；iOS 端不会假装 OCR。"))
                case .unsupported:
                    return .failure(DocumentAccessError.unsupportedFileType("暂不支持此文件类型的文本上下文。支持 txt、md、json、csv、pdf、docx。"))
                }
            } catch {
                return Result<SelectedDocumentReadResult, Error>.failure(error)
            }
        }.value
    }

    private enum SelectedDocumentKind {
        case text
        case pdf
        case docx
        case image
        case unsupported
    }

    nonisolated private static func selectedDocumentKind(url: URL, fileType: String) -> SelectedDocumentKind {
        let ext = url.pathExtension.lowercased()
        if ["txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm", "yaml", "yml"].contains(ext) {
            return .text
        }
        if ext == "pdf" { return .pdf }
        if ext == "docx" { return .docx }

        let lowerType = fileType.lowercased()
        if lowerType.contains("pdf") { return .pdf }
        if lowerType.contains("wordprocessingml.document") || lowerType.contains("officedocument.wordprocessingml") {
            return .docx
        }
        if lowerType.hasPrefix("text/") ||
            lowerType.contains("json") ||
            lowerType.contains("csv") ||
            lowerType.contains("markdown") {
            return .text
        }
        if lowerType.hasPrefix("image/") {
            return .image
        }

        if let type = UTType(filenameExtension: ext) {
            if type.conforms(to: .pdf) { return .pdf }
            if type.conforms(to: .text) || type.conforms(to: .json) || type.conforms(to: .commaSeparatedText) {
                return .text
            }
            if type.conforms(to: .image) { return .image }
        }
        return .unsupported
    }

    nonisolated private static func readTextPreview(
        url: URL,
        fileType: String,
        fileSize: Int64,
        maxPreviewBytes: Int,
        maxPreviewCharacters: Int
    ) throws -> SelectedDocumentReadResult {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: maxPreviewBytes + 1) ?? Data()
        let truncatedByBytes = data.count > maxPreviewBytes || fileSize > Int64(maxPreviewBytes)
        let previewData = data.prefix(maxPreviewBytes)
        guard let decoded = decodeText(previewData) else {
            throw DocumentAccessError.unsupportedFileType("此文件不是可解码的文本，无法作为文件上下文读取。")
        }
        return buildReadResult(
            fileName: url.lastPathComponent,
            fileType: fileType,
            fileSize: fileSize,
            bytesRead: previewData.count,
            text: decoded,
            truncatedBySource: truncatedByBytes,
            maxPreviewBytes: maxPreviewBytes,
            maxPreviewCharacters: maxPreviewCharacters
        )
    }

    nonisolated private static func readPDFPreview(
        url: URL,
        fileType: String,
        fileSize: Int64,
        maxPreviewCharacters: Int
    ) throws -> SelectedDocumentReadResult {
        guard let document = PDFDocument(url: url) else {
            throw DocumentAccessError.unsupportedFileType("无法打开此 PDF 文件。")
        }
        var chunks: [String] = []
        var truncated = false
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index),
                  let text = page.string?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !text.isEmpty else {
                continue
            }
            chunks.append(text)
            if chunks.joined(separator: "\n\n").count > maxPreviewCharacters {
                truncated = true
                break
            }
        }
        let text = chunks.joined(separator: "\n\n")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw DocumentAccessError.noReadableText("PDF 中没有可提取文本；扫描版 PDF 需要 OCR，iOS 文件上下文暂不假装 OCR。")
        }
        return buildReadResult(
            fileName: url.lastPathComponent,
            fileType: fileType,
            fileSize: fileSize,
            bytesRead: Int(fileSize),
            text: text,
            truncatedBySource: truncated,
            maxPreviewBytes: Int(fileSize),
            maxPreviewCharacters: maxPreviewCharacters
        )
    }

    nonisolated private static func readDocxPreview(
        url: URL,
        fileType: String,
        fileSize: Int64,
        maxPreviewCharacters: Int
    ) throws -> SelectedDocumentReadResult {
        let data = try Data(contentsOf: url)
        let entryNames = ["word/document.xml", "word/footnotes.xml", "word/endnotes.xml"]
        let xmlTexts = try entryNames.compactMap { name -> String? in
            guard let entryData = try IOSDocumentZipReader.entry(named: name, in: data) else { return nil }
            return String(data: entryData, encoding: .utf8)
        }
        guard !xmlTexts.isEmpty else {
            throw DocumentAccessError.noReadableText("DOCX 中没有找到 word/document.xml，无法提取正文。")
        }
        let text = xmlTexts
            .map(extractDocxText)
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw DocumentAccessError.noReadableText("DOCX 正文为空，或当前文档结构暂不支持文本提取。")
        }
        return buildReadResult(
            fileName: url.lastPathComponent,
            fileType: fileType,
            fileSize: fileSize,
            bytesRead: Int(fileSize),
            text: text,
            truncatedBySource: text.count > maxPreviewCharacters,
            maxPreviewBytes: Int(fileSize),
            maxPreviewCharacters: maxPreviewCharacters
        )
    }

    nonisolated private static func buildReadResult(
        fileName: String,
        fileType: String,
        fileSize: Int64,
        bytesRead: Int,
        text: String,
        truncatedBySource: Bool,
        maxPreviewBytes: Int,
        maxPreviewCharacters: Int
    ) -> SelectedDocumentReadResult {
        let normalized = normalizeExtractedText(text)
        guard !normalized.isEmpty else {
            return SelectedDocumentReadResult(
                fileName: fileName,
                fileType: fileType,
                totalBytes: fileSize,
                bytesRead: bytesRead,
                characterCount: 0,
                preview: "",
                isTruncated: false,
                note: "文件中没有可读取文本。"
            )
        }
        let truncatedByCharacters = normalized.count > maxPreviewCharacters
        let preview = truncatedByCharacters ? String(normalized.prefix(maxPreviewCharacters)) : normalized
        let isTruncated = truncatedBySource || truncatedByCharacters
        let note = isTruncated
            ? "内容已截断：最多读取 \(formatBytes(Int64(maxPreviewBytes))) / \(maxPreviewCharacters) 字符。"
            : nil
        return SelectedDocumentReadResult(
            fileName: fileName,
            fileType: fileType,
            totalBytes: fileSize,
            bytesRead: bytesRead,
            characterCount: preview.count,
            preview: preview,
            isTruncated: isTruncated,
            note: note
        )
    }

    nonisolated private static func decodeText(_ data: Data.SubSequence) -> String? {
        let value = Data(data)
        let encodings: [String.Encoding] = [.utf8, .utf16, .utf16LittleEndian, .utf16BigEndian, .isoLatin1, .ascii]
        for encoding in encodings {
            if let text = String(data: value, encoding: encoding) {
                return text
            }
        }
        return nil
    }

    nonisolated private static func normalizeExtractedText(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\u{0}", with: "")
            .replacingOccurrences(of: #"[ \t\r\f\v]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\n\s*\n\s*\n+"#, with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    nonisolated private static func extractDocxText(_ xml: String) -> String {
        var prepared = xml
            .replacingOccurrences(of: "<w:tab/>", with: "<w:t>\t</w:t>")
            .replacingOccurrences(of: "<w:tab />", with: "<w:t>\t</w:t>")
            .replacingOccurrences(of: "<w:br/>", with: "<w:t>\n</w:t>")
            .replacingOccurrences(of: "<w:br />", with: "<w:t>\n</w:t>")
            .replacingOccurrences(of: "</w:p>", with: "<w:t>\n</w:t>")
        prepared = prepared.replacingOccurrences(of: "</w:tr>", with: "<w:t>\n</w:t>")

        let pattern = #"<w:t(?:\s[^>]*)?>(.*?)</w:t>"#
        guard let regex = try? NSRegularExpression(
            pattern: pattern,
            options: [.dotMatchesLineSeparators, .caseInsensitive]
        ) else {
            return ""
        }
        let matches = regex.matches(in: prepared, range: NSRange(prepared.startIndex..., in: prepared))
        let pieces = matches.compactMap { match -> String? in
            guard match.numberOfRanges >= 2,
                  let range = Range(match.range(at: 1), in: prepared) else {
                return nil
            }
            return decodeXMLEntities(String(prepared[range]))
        }
        return normalizeExtractedText(pieces.joined())
    }

    nonisolated private static func decodeXMLEntities(_ text: String) -> String {
        var decoded = text
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")

        let pattern = #"&#(x?[0-9A-Fa-f]+);"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return decoded }
        for match in regex.matches(in: decoded, range: NSRange(decoded.startIndex..., in: decoded)).reversed() {
            guard match.numberOfRanges >= 2,
                  let fullRange = Range(match.range(at: 0), in: decoded),
                  let valueRange = Range(match.range(at: 1), in: decoded) else {
                continue
            }
            let value = String(decoded[valueRange])
            let radix = value.lowercased().hasPrefix("x") ? 16 : 10
            let digits = radix == 16 ? String(value.dropFirst()) : value
            guard let scalarValue = UInt32(digits, radix: radix),
                  let scalar = UnicodeScalar(scalarValue) else {
                continue
            }
            decoded.replaceSubrange(fullRange, with: String(Character(scalar)))
        }
        return decoded
    }

    nonisolated private static func fileSize(for url: URL) -> Int? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue
    }

    nonisolated static func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowedUnits = bytes >= 1024 * 1024 ? [.useMB] : [.useKB, .useBytes]
        return formatter.string(fromByteCount: bytes)
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

private struct IOSDocumentZipReader {
    static func entry(named expectedName: String, in data: Data) throws -> Data? {
        guard let eocd = data.lastRange(of: Data([0x50, 0x4b, 0x05, 0x06]))?.lowerBound else {
            throw DocumentAccessError.unsupportedFileType("DOCX 不是有效的 ZIP 文档。")
        }
        let entryCount = Int(try data.doc_uint16LE(at: eocd + 10))
        let centralOffset = Int(try data.doc_uint32LE(at: eocd + 16))
        var cursor = centralOffset

        for _ in 0..<entryCount {
            guard try data.doc_uint32LE(at: cursor) == 0x02014b50 else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 中央目录损坏。")
            }
            let method = try data.doc_uint16LE(at: cursor + 10)
            let compressedSize = Int(try data.doc_uint32LE(at: cursor + 20))
            let uncompressedSize = Int(try data.doc_uint32LE(at: cursor + 24))
            let nameLength = Int(try data.doc_uint16LE(at: cursor + 28))
            let extraLength = Int(try data.doc_uint16LE(at: cursor + 30))
            let commentLength = Int(try data.doc_uint16LE(at: cursor + 32))
            let localOffset = Int(try data.doc_uint32LE(at: cursor + 42))
            let nameStart = cursor + 46
            let nameEnd = nameStart + nameLength
            guard nameEnd <= data.count,
                  let name = String(data: data[nameStart..<nameEnd], encoding: .utf8) else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 条目名损坏。")
            }

            defer {
                cursor = nameEnd + extraLength + commentLength
            }
            guard name == expectedName else { continue }
            guard try data.doc_uint32LE(at: localOffset) == 0x04034b50 else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 本地条目损坏。")
            }
            let localNameLength = Int(try data.doc_uint16LE(at: localOffset + 26))
            let localExtraLength = Int(try data.doc_uint16LE(at: localOffset + 28))
            let dataStart = localOffset + 30 + localNameLength + localExtraLength
            let dataEnd = dataStart + compressedSize
            guard dataEnd <= data.count else {
                throw DocumentAccessError.unsupportedFileType("DOCX ZIP 条目大小损坏。")
            }
            let payload = Data(data[dataStart..<dataEnd])
            switch method {
            case 0:
                return payload
            case 8:
                let decompressed: Data
                do {
                    decompressed = try (payload as NSData).decompressed(using: .zlib) as Data
                } catch {
                    throw DocumentAccessError.unsupportedFileType("DOCX ZIP 解压失败，暂不能提取此文档文本。")
                }
                if uncompressedSize > 0, decompressed.count > max(uncompressedSize * 2, uncompressedSize + 1024) {
                    throw DocumentAccessError.unsupportedFileType("DOCX ZIP 解压结果异常。")
                }
                return decompressed
            default:
                throw DocumentAccessError.unsupportedFileType("DOCX 使用了暂不支持的 ZIP 压缩方式：\(method)。")
            }
        }
        return nil
    }
}

private extension Data {
    func doc_uint16LE(at offset: Int) throws -> UInt16 {
        guard offset + 2 <= count else {
            throw DocumentAccessError.unsupportedFileType("DOCX ZIP 读取越界。")
        }
        return UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func doc_uint32LE(at offset: Int) throws -> UInt32 {
        guard offset + 4 <= count else {
            throw DocumentAccessError.unsupportedFileType("DOCX ZIP 读取越界。")
        }
        return UInt32(self[offset]) |
            (UInt32(self[offset + 1]) << 8) |
            (UInt32(self[offset + 2]) << 16) |
            (UInt32(self[offset + 3]) << 24)
    }
}

enum DocumentAccessError: Error, Equatable {
    case missingGrant
    case grantMismatch
    case expiredGrant
    case fileTooLarge
    case unknownFileSize
    case alreadyReading
    case unsupportedFileType(String)
    case noReadableText(String)
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
            return .deny(reason: "Selected file is larger than the file-context import limit of \(DocumentAccessStore.formatBytes(documentStore.maxReadableBytes))")
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
            "Selected file is larger than the file-context import limit."
        case .unknownFileSize:
            "Selected file size is unavailable. Choose a regular file and try again."
        case .alreadyReading:
            "The selected file is already being read."
        case .unsupportedFileType(let message):
            message
        case .noReadableText(let message):
            message
        case .readFailed(let message):
            "Failed to read selected file: \(message)"
        }
    }
}
