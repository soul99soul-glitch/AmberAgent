import Foundation

// MARK: - NodeType

/// Symbolic names for the u8 tag codes in the wire format.
/// Direct port of the Kotlin NodeType enum from MarkdownParserNative.kt.
enum NodeType: Int, Equatable {
    case root = 0
    case paragraph = 1
    case heading = 2
    case blockquote = 3
    case codeBlock = 4
    case listOrdered = 5
    case listUnordered = 6
    case listItem = 7
    case table = 8
    case tableHead = 9
    case tableRow = 10
    case tableCell = 11
    case horizontalRule = 12
    case htmlBlock = 13
    case emphasis = 30
    case strong = 31
    case strikethrough = 32
    case link = 33
    case image = 34
    case inlineCode = 35
    case inlineHtml = 36
    case mathInline = 37
    case mathBlock = 38
    case footnoteRef = 39
    case footnoteDef = 40
    case taskListMarker = 41
    case text = 100
    case softBreak = 101
    case hardBreak = 102
    case unknown = -1

    static func fromCode(_ code: Int) -> NodeType {
        NodeType(rawValue: code) ?? .unknown
    }
}

// MARK: - PackedAstReader

/// Decoder for the packed AST format. Lazy: decoding a child only happens when
/// the consumer touches it.
///
/// Wire layout:
/// ```
/// header: 'PMDA' + u8 version + u8 flags + u16 reserved   (8 bytes)
/// body:   depth-first nodes; each = u8 tag + varint start + varint endDelta
///         + varint extrasLen + extras + varint childCount + children
/// ```
class PackedAstReader {
    let isValid: Bool
    let version: Int
    let hasHtmlBlocks: Bool

    private let blob: [UInt8]

    init?(data: Data) {
        let bytes = [UInt8](data)
        self.blob = bytes

        if bytes.count < 8
            || bytes[0] != UInt8(ascii: "P")
            || bytes[1] != UInt8(ascii: "M")
            || bytes[2] != UInt8(ascii: "D")
            || bytes[3] != UInt8(ascii: "A")
        {
            isValid = false
            version = -1
            hasHtmlBlocks = false
            return
        }

        let rawVersion = Int(bytes[4])
        version = rawVersion
        isValid = rawVersion == Self.supportedVersion
        hasHtmlBlocks = (bytes[5] & 0x01) != 0
    }

    /// Root node, or nil if isValid is false / blob truncated.
    func root() -> PackedAstNode? {
        if !isValid || blob.count < 9 { return nil }
        return PackedAstNode(blob: blob, offset: 8, parent: nil, indexInParent: -1)
    }

    /// Eagerly validates the entire packed blob with one bounds-checked walk.
    func validate() -> Bool {
        if !isValid || blob.count < 9 { return false }
        guard let endCursor = try? PackedAstNode.skipNode(blob: blob, start: 8) else {
            return false
        }
        return endCursor <= blob.count
    }

    private static let supportedVersion = 1
}

// MARK: - PackedAstNode

/// Lazy view onto a single node in the packed blob. Constructs child nodes
/// on-demand by re-scanning the blob from this node's offset.
class PackedAstNode: Identifiable {
    let id: Int  // offset is unique within a blob

    private let blob: [UInt8]
    private let offset: Int
    private(set) weak var parent: PackedAstNode?
    private let indexInParent: Int

    private var _header: NodeHeader?

    init(blob: [UInt8], offset: Int, parent: PackedAstNode?, indexInParent: Int) {
        self.id = offset
        self.blob = blob
        self.offset = offset
        self.parent = parent
        self.indexInParent = indexInParent
    }

    /// Raw tag byte; map to NodeType for typed access.
    lazy var typeCode: Int = Int(blob[offset])

    lazy var type: NodeType = NodeType.fromCode(typeCode)

    private var header: NodeHeader {
        if let h = _header { return h }
        let h = readHeader()
        _header = h
        return h
    }

    /// Byte offset into the original markdown text.
    lazy var startOffset: Int = header.start

    /// Byte offset into the original markdown text (start + delta).
    lazy var endOffset: Int = header.start + header.endDelta

    lazy var extras: [UInt8] = header.extras

    lazy var children: [PackedAstNode] = {
        var list = [PackedAstNode]()
        list.reserveCapacity(header.childCount)
        var cursor = header.bodyStart
        for i in 0..<header.childCount {
            guard let next = try? PackedAstNode.skipNode(blob: blob, start: cursor) else {
                return [] // corrupt data — return empty instead of partial list
            }
            let node = PackedAstNode(blob: blob, offset: cursor, parent: self, indexInParent: i)
            list.append(node)
            cursor = next
        }
        return list
    }()

    /// O(1) sibling lookup.
    func nextSibling() -> PackedAstNode? {
        guard let p = parent else { return nil }
        let nextIdx = indexInParent + 1
        if nextIdx < 0 || nextIdx >= p.children.count { return nil }
        return p.children[nextIdx]
    }

    // MARK: - Extras helpers

    /// Heading level (1-6). Returns nil if not a heading.
    func headingLevel() -> Int? {
        guard type == .heading, !extras.isEmpty else { return nil }
        let level = Int(extras[0])
        return (1...6).contains(level) ? level : nil
    }

    /// Language string for a code block. Returns nil if not a code block or no lang.
    func codeLang() -> String? {
        guard type == .codeBlock, extras.count >= 2 else { return nil }
        var cursor = 0
        let (langLen, next) = Self.readVarint(blob: extras, start: cursor)
        cursor = next
        let len = Int(langLen)
        guard cursor + len <= extras.count else { return nil }
        let langBytes = Array(extras[cursor..<(cursor + len)])
        return String(bytes: langBytes, encoding: .utf8)
    }

    /// Destination URL for a link or image.
    func linkHref() -> String? {
        guard (type == .link || type == .image), !extras.isEmpty else { return nil }
        var cursor = 0
        let (destLen, next) = Self.readVarint(blob: extras, start: cursor)
        cursor = next
        let len = Int(destLen)
        guard cursor + len <= extras.count else { return nil }
        let destBytes = Array(extras[cursor..<(cursor + len)])
        return String(bytes: destBytes, encoding: .utf8)
    }

    /// Title for a link or image.
    func linkTitle() -> String? {
        guard (type == .link || type == .image) else { return nil }
        var cursor = 0
        // Skip dest
        let (destLen, n1) = Self.readVarint(blob: extras, start: cursor); cursor = n1
        cursor += Int(destLen)
        guard cursor < extras.count else { return nil }
        let (titleLen, n2) = Self.readVarint(blob: extras, start: cursor); cursor = n2
        let len = Int(titleLen)
        guard cursor + len <= extras.count else { return nil }
        let titleBytes = Array(extras[cursor..<(cursor + len)])
        return String(bytes: titleBytes, encoding: .utf8)
    }

    // MARK: - Internal

    private func readHeader() -> NodeHeader {
        var cursor = offset + 1
        let (start, c1) = Self.readVarint(blob: blob, start: cursor); cursor = c1
        let (delta, c2) = Self.readVarint(blob: blob, start: cursor); cursor = c2
        let (extrasLen, c3) = Self.readVarint(blob: blob, start: cursor); cursor = c3
        let extrasStart = cursor
        cursor += Int(extrasLen)
        let (childCount, c4) = Self.readVarint(blob: blob, start: cursor); cursor = c4
        let extras = Array(blob[extrasStart..<(extrasStart + Int(extrasLen))])
        return NodeHeader(
            start: Int(start),
            endDelta: Int(delta),
            extras: extras,
            childCount: Int(childCount),
            bodyStart: cursor
        )
    }

    private struct NodeHeader {
        let start: Int
        let endDelta: Int
        let extras: [UInt8]
        let childCount: Int
        let bodyStart: Int
    }

    // MARK: - Varint & skip utilities

    /// Read a LEB128 varint from the blob at the given offset.
    /// Returns (value, nextCursor).
    static func readVarint(blob: [UInt8], start: Int) -> (value: UInt64, next: Int) {
        var value: UInt64 = 0
        var shift = 0
        var cursor = start
        while true {
            let byte = Int(blob[cursor])
            value |= UInt64(byte & 0x7F) << shift
            cursor += 1
            if byte & 0x80 == 0 { return (value, cursor) }
            shift += 7
            if shift > 63 { fatalError("varint too long at offset \(start)") }
        }
    }

    /// Walk a node and its descendants, return the byte offset just past the final child.
    /// Iterative to avoid stack overflow on deeply-nested markdown.
    static func skipNode(blob: [UInt8], start: Int) throws -> Int {
        var cursor = start
        var remaining: [UInt64] = [1]
        while !remaining.isEmpty {
            let top = remaining.removeLast() - 1
            if top > 0 { remaining.append(top) }

            cursor += 1                                              // tag byte
            let (_, c1) = readVarint(blob: blob, start: cursor); cursor = c1  // start
            let (_, c2) = readVarint(blob: blob, start: cursor); cursor = c2  // end_delta
            let (extrasLen, c3) = readVarint(blob: blob, start: cursor); cursor = c3
            cursor += Int(extrasLen)
            let (childCount, c4) = readVarint(blob: blob, start: cursor); cursor = c4
            if childCount > 0 { remaining.append(childCount) }
        }
        return cursor
    }
}
