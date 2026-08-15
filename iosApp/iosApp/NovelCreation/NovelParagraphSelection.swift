import Foundation

struct NovelParagraphID: RawRepresentable, Codable, Hashable, Sendable {
    let rawValue: String
    init(rawValue: String) { self.rawValue = rawValue }
}

struct NovelParagraphRecord: Codable, Equatable, Sendable {
    let id: NovelParagraphID
    let text: String
}

struct NovelParagraphSelection: Codable, Equatable, Sendable {
    let paragraphIDs: [NovelParagraphID]
    let editedText: String?

    init(paragraphIDs: [NovelParagraphID], editedText: String? = nil) {
        self.paragraphIDs = paragraphIDs
        self.editedText = editedText
    }
}

enum NovelParagraphParser {
    static func paragraphs(in content: String) -> [NovelParagraphRecord] {
        let normalized = normalizeLineEndings(content)
        var result: [NovelParagraphRecord] = []
        var lines: [Substring] = []
        var occurrences: [String: Int] = [:]

        func flush() {
            guard !lines.isEmpty else { return }
            let text = lines.joined(separator: "\n")
            let digest = NovelDocumentValidator.sha256(text)
            let occurrence = occurrences[digest, default: 0]
            occurrences[digest] = occurrence + 1
            result.append(NovelParagraphRecord(
                id: NovelParagraphID(rawValue: "paragraph-\(digest)-\(occurrence)"),
                text: text
            ))
            lines.removeAll(keepingCapacity: true)
        }

        for line in normalized.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.trimmingCharacters(in: .whitespaces).isEmpty {
                flush()
            } else {
                lines.append(line)
            }
        }
        flush()
        return result
    }

    static func selectedText(
        for selection: NovelParagraphSelection,
        in content: String
    ) throws -> String {
        guard !selection.paragraphIDs.isEmpty else {
            throw NovelError.invalidInput("At least one paragraph must be selected.")
        }
        guard Set(selection.paragraphIDs).count == selection.paragraphIDs.count else {
            throw NovelError.invalidInput("A paragraph selection cannot contain duplicate IDs.")
        }
        let selected = Set(selection.paragraphIDs)
        let paragraphs = paragraphs(in: content)
        guard selected.isSubset(of: Set(paragraphs.map(\.id))) else {
            throw NovelError.invalidInput("The paragraph selection is stale for this candidate.")
        }

        let text: String
        if let editedText = selection.editedText {
            text = normalizeLineEndings(editedText)
        } else {
            text = paragraphs
                .filter { selected.contains($0.id) }
                .map(\.text)
                .joined(separator: "\n\n")
        }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("Collected manuscript text cannot be empty.")
        }
        return text
    }

    /// 1-based inclusive paragraph range. `newText` may itself contain blank-line
    /// paragraph breaks; the surrounding paragraphs stay untouched.
    static func replacingParagraphs(
        in content: String,
        start: Int,
        end: Int,
        with newText: String
    ) throws -> (oldText: String, newContent: String) {
        let paragraphs = paragraphs(in: content)
        guard start >= 1, end >= start, end <= paragraphs.count else {
            throw NovelError.invalidInput(
                "Paragraph range \(start)–\(end) is out of bounds for \(paragraphs.count) paragraphs."
            )
        }
        let replacement = normalizeLineEndings(newText)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !replacement.isEmpty else {
            throw NovelError.invalidInput("Replacement manuscript text cannot be empty.")
        }
        let oldText = paragraphs[(start - 1)..<end].map(\.text).joined(separator: "\n\n")
        var next = Array(paragraphs.prefix(start - 1).map(\.text))
        next.append(replacement)
        next.append(contentsOf: paragraphs.suffix(paragraphs.count - end).map(\.text))
        return (oldText, next.joined(separator: "\n\n"))
    }

    static func numberedExcerpt(
        in content: String,
        start: Int? = nil,
        end: Int? = nil
    ) throws -> (total: Int, text: String) {
        let paragraphs = paragraphs(in: content)
        guard !paragraphs.isEmpty else {
            throw NovelError.invalidInput("This chapter has no paragraphs to read.")
        }
        let lower = start ?? 1
        let upper = end ?? paragraphs.count
        guard lower >= 1, upper >= lower, upper <= paragraphs.count else {
            throw NovelError.invalidInput(
                "Paragraph range \(lower)–\(upper) is out of bounds for \(paragraphs.count) paragraphs."
            )
        }
        let body = paragraphs[(lower - 1)..<upper].enumerated().map { offset, paragraph in
            "[\(lower + offset)] \(paragraph.text)"
        }.joined(separator: "\n\n")
        return (paragraphs.count, body)
    }

    private static func normalizeLineEndings(_ value: String) -> String {
        value.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
    }
}
