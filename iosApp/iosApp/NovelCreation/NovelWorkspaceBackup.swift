import Foundation

enum NovelWorkspaceBackup {
    static let format = "amber.novel.workspace"
    static let formatVersion = 1

    struct File: Equatable, Sendable {
        let path: String
        let contents: String
    }

    static func export(
        _ document: NovelProjectDocumentV1,
        exportedAt: Date = Date()
    ) throws -> [File] {
        try NovelDocumentValidator.validate(document)
        var files: [File] = []
        var usedPaths: Set<String> = []

        let activeBranches = document.branches.filter { $0.lifecycle == .active }
        let mainBranch = activeBranches.first { $0.id == document.project.mainBranchID }
        let mainSlug = reservedPath(
            slug(mainBranch?.name ?? "main"),
            used: &usedPaths,
            fallback: document.project.mainBranchID.description
        )
        usedPaths.removeAll()

        files.append(
            File(
                path: "manifest.yaml",
                contents: yamlMapping([
                    "format": format,
                    "formatVersion": String(formatVersion),
                    "exportedAt": iso8601(exportedAt),
                    "source.projectID": document.project.id.description,
                    "source.projectRevision": String(document.project.revision),
                    "source.schemaVersion": String(document.schemaVersion),
                    "mainBranch": mainSlug,
                ])
            )
        )
        files.append(
            File(
                path: "project.md",
                contents: render(
                    fields: [
                        "id": document.project.id.description,
                        "kind": "project",
                        "title": document.project.name,
                        "collaborationMode": document.project.collaborationMode.rawValue,
                        "polishPreference": document.project.polishPreference,
                    ],
                    body: ""
                )
            )
        )

        let liveMaterials = document.materials.filter { !$0.isDeleted }
        for material in liveMaterials {
            guard let revision = document.materialRevisions.first(where: {
                $0.id == material.currentRevisionID
            }) else {
                continue
            }
            let uniqueKind = liveMaterials.filter { $0.kind == material.kind }.count == 1
            let relative = materialPath(
                material: material,
                revision: revision,
                uniqueKind: uniqueKind
            )
            let path = reservedPath(relative, used: &usedPaths, fallback: material.id.description)
            files.append(
                File(
                    path: path,
                    contents: render(
                        fields: materialFields(material: material, revision: revision, override: false),
                        body: revision.content
                    )
                )
            )
        }

        var branchSlugs: [NovelBranchID: String] = [:]
        var usedBranchSlugs: Set<String> = []
        for branch in activeBranches {
            let slugValue = reservedPath(
                slug(branch.name),
                used: &usedBranchSlugs,
                fallback: branch.id.description
            )
            branchSlugs[branch.id] = slugValue
        }

        for branch in activeBranches {
            let branchSlug = branchSlugs[branch.id] ?? slug(branch.name)
            let prefix = "branches/\(branchSlug)"
            files.append(
                File(
                    path: "\(prefix)/branch.md",
                    contents: render(
                        fields: [
                            "id": branch.id.description,
                            "kind": "branch",
                            "title": branch.name,
                            "syncStatus": branch.syncStatus.rawValue,
                        ],
                        body: ""
                    )
                )
            )

            var usedChapterNames: Set<String> = []
            var ordinal = 0
            for selection in branch.workingChapterSelections {
                let chapter = document.chapters.first { $0.id == selection.chapterID }
                guard chapter?.discardedAt == nil,
                      let version = document.chapterVersions.first(where: {
                          $0.id == selection.versionID && $0.chapterID == selection.chapterID
                      }) else {
                    continue
                }
                ordinal += 1
                let name = reservedPath(
                    slug(version.title),
                    used: &usedChapterNames,
                    fallback: selection.chapterID.description
                )
                files.append(
                    File(
                        path: "\(prefix)/chapters/\(String(format: "%03d", ordinal))-\(name).md",
                        contents: render(
                            fields: [
                                "id": selection.chapterID.description,
                                "kind": "chapter",
                                "title": version.title,
                                "ordinal": String(ordinal),
                                "sourceVersionID": version.id.description,
                            ],
                            body: version.content
                        )
                    )
                )
            }

            var usedDiscardedNames: Set<String> = []
            for chapter in document.chapters where chapter.discardedAt != nil {
                let version = discardedVersion(for: chapter.id, branch: branch, in: document)
                guard let version else { continue }
                let name = reservedPath(
                    slug(version.title),
                    used: &usedDiscardedNames,
                    fallback: chapter.id.description
                )
                files.append(
                    File(
                        path: "\(prefix)/discarded/\(name).md",
                        contents: render(
                            fields: [
                                "id": chapter.id.description,
                                "kind": "chapter",
                                "title": version.title,
                                "sourceVersionID": version.id.description,
                            ],
                            body: version.content
                        )
                    )
                )
            }

            if let snapshot = document.stateSnapshots.first(where: {
                $0.id == branch.currentStateSnapshotID
            }) {
                var currentBody = snapshot.summary
                let highlights = snapshot.recentWrittenHighlights.filter {
                    !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                }
                if !highlights.isEmpty {
                    currentBody += "\n\n## 近期已写\n\n" + highlights.map { "- \($0)" }.joined(separator: "\n")
                }
                files.append(
                    File(
                        path: "\(prefix)/plot/current.md",
                        contents: render(
                            fields: [
                                "id": snapshot.id.description,
                                "kind": "plot",
                                "title": "当前状态",
                            ],
                            body: currentBody
                        )
                    )
                )
                files.append(
                    File(
                        path: "\(prefix)/plot/outline.md",
                        contents: render(
                            fields: [
                                "id": snapshot.id.description,
                                "kind": "plot",
                                "title": "分支大纲",
                            ],
                            body: snapshot.branchOutline
                        )
                    )
                )
                let events = snapshot.eventIDs.compactMap { eventID in
                    document.events.first { $0.id == eventID }
                }
                let eventLines = events.map { event in
                    let summary = event.summary.trimmingCharacters(in: .whitespacesAndNewlines)
                    return summary.hasPrefix("- ") ? summary : "- \(summary)"
                }
                files.append(
                    File(
                        path: "\(prefix)/plot/events.md",
                        contents: render(
                            fields: [
                                "id": snapshot.id.description,
                                "kind": "plot",
                                "title": "事件",
                            ],
                            body: eventLines.joined(separator: "\n")
                        )
                    )
                )
            }

            if let plan = document.chapterPlans.first(where: { $0.branchID == branch.id }) {
                files.append(
                    File(
                        path: "\(prefix)/plan/this-chapter.md",
                        contents: render(
                            fields: [
                                "id": plan.id.description,
                                "kind": "plan",
                                "title": "本章计划",
                                "status": plan.status.rawValue,
                            ],
                            body: planMarkdown(plan)
                        )
                    )
                )
            }
            if let arc = document.upcomingArcs.first(where: { $0.branchID == branch.id }) {
                files.append(
                    File(
                        path: "\(prefix)/plan/upcoming.md",
                        contents: render(
                            fields: [
                                "id": branch.id.description,
                                "kind": "plan",
                                "title": "往后几章",
                            ],
                            body: arc.beats.map { "- \($0)" }.joined(separator: "\n")
                        )
                    )
                )
            }

            var usedOverrideNames: Set<String> = []
            for revisionID in branch.overrideRevisionIDs {
                guard let revision = document.materialRevisions.first(where: { $0.id == revisionID }),
                      let material = document.materials.first(where: { $0.id == revision.materialID })
                else {
                    continue
                }
                let leaf = reservedPath(
                    slug(revision.title),
                    used: &usedOverrideNames,
                    fallback: revision.id.description
                )
                let folder = materialFolder(for: material.kind)
                files.append(
                    File(
                        path: "\(prefix)/setting/\(folder)/\(leaf).md",
                        contents: render(
                            fields: materialFields(
                                material: material,
                                revision: revision,
                                override: true
                            ),
                            body: revision.content
                        )
                    )
                )
            }
        }

        var usedInboxNames: Set<String> = []
        for proposal in document.settingProposals where !proposal.isResolved {
            let name = reservedPath(
                slug(proposal.title),
                used: &usedInboxNames,
                fallback: proposal.id.description
            )
            files.append(
                File(
                    path: "inbox/\(name).md",
                    contents: render(
                        fields: [
                            "id": proposal.id.description,
                            "kind": "material",
                            "title": proposal.title,
                            "materialKind": "custom",
                        ],
                        body: proposal.content
                    )
                )
            )
        }

        var usedDraftNames: Set<String> = []
        for candidate in document.candidates where candidate.status == .available {
            let name = reservedPath(
                String(candidate.id.description.prefix(8)),
                used: &usedDraftNames,
                fallback: candidate.id.description
            )
            files.append(
                File(
                    path: "drafts/\(name).md",
                    contents: render(
                        fields: [
                            "id": candidate.id.description,
                            "kind": "chapter",
                            "title": "未收录草稿",
                        ],
                        body: candidate.content
                    )
                )
            )
        }

        return files.sorted { $0.path < $1.path }
    }

    static func write(
        _ document: NovelProjectDocumentV1,
        to directory: URL,
        exportedAt: Date = Date(),
        fileManager: FileManager = .default
    ) throws {
        let existingLedger = NovelWorkspaceLedger.load(from: directory, fileManager: fileManager)
        let files = try export(document, exportedAt: exportedAt)
        if fileManager.fileExists(atPath: directory.path) {
            try fileManager.removeItem(at: directory)
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        for file in files {
            let url = directory.appendingPathComponent(file.path)
            try fileManager.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(file.contents.utf8).write(to: url, options: .atomic)
        }
        let store = NovelWorkspaceLedger.record(document, into: existingLedger)
        try NovelWorkspaceLedger.save(store, to: directory, fileManager: fileManager)
    }

    static func exportPackage(
        at packageDirectory: URL,
        projectID: NovelProjectID,
        to destination: URL,
        exportedAt: Date = Date(),
        fileManager: FileManager = .default
    ) throws {
        let loaded = try NovelProjectShardedStorage.loadDocument(
            packageDirectory: packageDirectory,
            projectID: projectID,
            decoder: JSONDecoder(),
            fileManager: fileManager
        )
        try write(loaded.document, to: destination, exportedAt: exportedAt, fileManager: fileManager)
    }
}

private extension NovelWorkspaceBackup {
    static func discardedVersion(
        for chapterID: NovelChapterID,
        branch: NovelBranchRecord,
        in document: NovelProjectDocumentV1
    ) -> NovelChapterVersionRecord? {
        if let selection = branch.workingChapterSelections.first(where: { $0.chapterID == chapterID }),
           let version = document.chapterVersions.first(where: {
               $0.id == selection.versionID && $0.chapterID == chapterID
           }) {
            return version
        }
        return document.chapterVersions
            .filter { $0.chapterID == chapterID }
            .max(by: { $0.createdAt < $1.createdAt })
    }

    static func materialPath(
        material: NovelMaterialRecord,
        revision: NovelMaterialRevisionRecord,
        uniqueKind: Bool
    ) -> String {
        _ = uniqueKind
        let name = slug(revision.title)
        return "setting/\(materialFolder(for: material.kind))/\(name).md"
    }

    static func materialFolder(for kind: NovelMaterialKind) -> String {
        switch kind {
        case .world:
            return "world"
        case .masterOutline:
            return "outline"
        case .writingRequirements:
            return "writing"
        case .decisionLog:
            return "log"
        case .character:
            return "characters"
        case .relationship:
            return "relationships"
        case .custom:
            return "custom"
        }
    }

    static func materialFields(
        material: NovelMaterialRecord,
        revision: NovelMaterialRevisionRecord,
        override: Bool
    ) -> [(String, String)] {
        var fields: [(String, String)] = [
            ("id", material.id.description),
            ("kind", "material"),
            ("title", revision.title),
            ("materialKind", materialKindName(material.kind)),
            ("injection", revision.injectionMode.rawValue),
            ("sourceVersionID", revision.id.description),
        ]
        if case .custom(let name) = material.kind {
            fields.append(("customName", name))
        }
        if override {
            fields.append(("override", "true"))
        }
        if !material.aliases.isEmpty {
            fields.append(("aliases", yamlInlineArray(material.aliases)))
        }
        return fields
    }

    static func materialKindName(_ kind: NovelMaterialKind) -> String {
        switch kind {
        case .world: "world"
        case .character: "character"
        case .relationship: "relationship"
        case .masterOutline: "masterOutline"
        case .writingRequirements: "writingRequirements"
        case .decisionLog: "decisionLog"
        case .custom: "custom"
        }
    }

    static func planMarkdown(_ plan: NovelChapterPlanRecord) -> String {
        var sections: [String] = []
        if !plan.outlinePlacement.isEmpty {
            sections.append("## 位置\n\n\(plan.outlinePlacement)")
        }
        if !plan.goalAndConflict.isEmpty {
            sections.append("## 目标与冲突\n\n\(plan.goalAndConflict)")
        }
        if !plan.mustHappen.isEmpty {
            sections.append("## 必须发生\n\n" + plan.mustHappen.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !plan.mustNotHappen.isEmpty {
            sections.append("## 不可发生\n\n" + plan.mustNotHappen.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !plan.visibleFacts.isEmpty {
            sections.append("## 可见事实\n\n" + plan.visibleFacts.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !plan.endingHook.isEmpty {
            sections.append("## 收束\n\n\(plan.endingHook)")
        }
        return sections.joined(separator: "\n\n")
    }

    static func render(fields: [(String, String)], body: String) -> String {
        var lines = ["---"]
        for (key, value) in fields {
            if key == "aliases" {
                lines.append("aliases:")
                let aliases = value.split(separator: "\u{1e}", omittingEmptySubsequences: false)
                if aliases.isEmpty {
                    // value is already yaml inline or we used yamlInlineArray differently
                }
                // aliases field is preformatted as yamlInlineArray marker; write block list
            }
            if key == "aliases" {
                continue
            }
            lines.append("\(key): \(yamlScalar(value))")
        }
        if let aliases = fields.first(where: { $0.0 == "aliases" })?.1 {
            lines.removeAll { $0 == "aliases:" }
            if let idx = lines.firstIndex(of: "---") {
                _ = idx
            }
            lines.append("aliases:")
            for item in parseInlineArray(aliases) {
                lines.append("  - \(yamlScalar(item))")
            }
        }
        lines.append("---")
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return lines.joined(separator: "\n") + "\n"
        }
        return lines.joined(separator: "\n") + "\n\n" + trimmed + "\n"
    }

    static func render(fields: [String: String], body: String) -> String {
        render(fields: fields.map { ($0.key, $0.value) }.sorted { $0.0 < $1.0 }, body: body)
    }

    static func yamlMapping(_ pairs: [String: String]) -> String {
        var lines: [String] = []
        var nested: [String: [(String, String)]] = [:]
        var top: [(String, String)] = []
        for (key, value) in pairs.sorted(by: { $0.key < $1.key }) {
            if let dot = key.firstIndex(of: ".") {
                let parent = String(key[..<dot])
                let child = String(key[key.index(after: dot)...])
                nested[parent, default: []].append((child, value))
            } else {
                top.append((key, value))
            }
        }
        for (key, value) in top {
            if key == "formatVersion", let number = Int(value) {
                lines.append("\(key): \(number)")
            } else if key == "exportedAt" {
                lines.append("\(key): \(value)")
            } else {
                lines.append("\(key): \(yamlScalar(value))")
            }
        }
        for (parent, children) in nested.sorted(by: { $0.key < $1.key }) {
            lines.append("\(parent):")
            for (child, value) in children.sorted(by: { $0.0 < $1.0 }) {
                if child == "projectRevision" || child == "schemaVersion", let number = Int(value) {
                    lines.append("  \(child): \(number)")
                } else {
                    lines.append("  \(child): \(yamlScalar(value))")
                }
            }
        }
        return lines.joined(separator: "\n") + "\n"
    }

    static func yamlInlineArray(_ values: [String]) -> String {
        values.joined(separator: "\u{1e}")
    }

    static func parseInlineArray(_ packed: String) -> [String] {
        packed.split(separator: "\u{1e}", omittingEmptySubsequences: false).map(String.init)
    }

    static func yamlScalar(_ value: String) -> String {
        if value.isEmpty { return "\"\"" }
        let needsQuotes = value.hasPrefix(" ")
            || value.hasSuffix(" ")
            || value.contains(where: { ":#{}[],&*?|>!%@`'\"\n".contains($0) })
        if !needsQuotes {
            return value
        }
        let escaped = value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return "\"\(escaped)\""
    }

    static func slug(_ raw: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:?%*|\"<>")
            .union(.newlines)
            .union(.controlCharacters)
        let mapped = raw.unicodeScalars.map { scalar -> String in
            if forbidden.contains(scalar) || scalar == " " {
                return "-"
            }
            return String(scalar)
        }.joined()
        let collapsed = mapped
            .replacingOccurrences(of: "-{2,}", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        if collapsed.unicodeScalars.allSatisfy({ $0.isASCII && ($0.properties.isAlphabetic || $0 == "-") }) {
            return collapsed.lowercased()
        }
        return collapsed.isEmpty ? "" : collapsed
    }

    static func reservedPath(_ preferred: String, used: inout Set<String>, fallback: String) -> String {
        let leafPreferred = preferred.split(separator: "/").last.map(String.init) ?? preferred
        let prefix = preferred.contains("/")
            ? preferred.split(separator: "/").dropLast().joined(separator: "/") + "/"
            : ""
        var base = leafPreferred.isEmpty ? String(fallback.prefix(8)) : leafPreferred
        if base.isEmpty { base = "untitled" }
        var candidate = base
        var index = 2
        while used.contains(prefix + candidate) {
            candidate = "\(base)-\(index)"
            index += 1
        }
        used.insert(prefix + candidate)
        return prefix.isEmpty ? candidate : prefix + candidate
    }

    static func iso8601(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: date)
    }
}
