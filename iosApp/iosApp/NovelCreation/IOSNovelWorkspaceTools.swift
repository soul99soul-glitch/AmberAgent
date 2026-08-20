import Foundation

extension IOSNovelProjectToolExecutor {
    func workspaceList(_ arguments: String) async -> IOSAgentToolOutcome {
        let prefix = decodePrefix(arguments)
        guard let files = await workspaceFiles() else {
            return .failed("当前小说项目不可用，无法列出工作区。")
        }
        let filtered = files.filter { prefix == nil || $0.path.hasPrefix(prefix!) }
        if filtered.isEmpty {
            return .filled("工作区在该前缀下没有文件。")
        }
        let lines = filtered.map { "\($0.path)  \($0.contents.count)字" }
        return .filled(lines.joined(separator: "\n"))
    }

    func workspaceRead(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: WorkspacePathArguments = decodeWorkspace(arguments),
              let path = normalizePath(args.path) else {
            return .failed("novel_workspace_read 参数无效：需要 path。")
        }
        guard let files = await workspaceFiles() else {
            return .failed("当前小说项目不可用，无法读取工作区。")
        }
        guard let file = files.first(where: { $0.path == path }) else {
            return .failed("找不到工作区文件：\(path)")
        }
        var body = file.contents
        if body.count > Self.readOutputCharacterLimit {
            body = String(body.prefix(Self.readOutputCharacterLimit)) + "\n\n… 已截断。换 prefix/path 分段读。"
        }
        return .filled(body)
    }

    func workspaceGrep(_ arguments: String) async -> IOSAgentToolOutcome {
        guard let args: WorkspaceGrepArguments = decodeWorkspace(arguments) else {
            return .failed("novel_workspace_grep 参数无效：需要 query。")
        }
        let query = args.query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            return .failed("novel_workspace_grep 的 query 不能为空。")
        }
        guard let files = await workspaceFiles() else {
            return .failed("当前小说项目不可用，无法搜索工作区。")
        }
        let prefix = args.prefix.flatMap(normalizePath)
        let matches = files.compactMap { file -> String? in
            if let prefix, !file.path.hasPrefix(prefix) { return nil }
            guard let range = file.contents.range(of: query, options: .caseInsensitive) else {
                return nil
            }
            let start = file.contents.index(range.lowerBound, offsetBy: -24, limitedBy: file.contents.startIndex)
                ?? file.contents.startIndex
            let end = file.contents.index(range.upperBound, offsetBy: 24, limitedBy: file.contents.endIndex)
                ?? file.contents.endIndex
            let excerpt = file.contents[start..<end]
                .replacingOccurrences(of: "\n", with: " ")
            return "\(file.path): …\(excerpt)…"
        }
        if matches.isEmpty {
            return .filled("没有匹配「\(query)」的工作区文件。")
        }
        return .filled(matches.prefix(40).joined(separator: "\n"))
    }

    func workspaceStatus() async -> IOSAgentToolOutcome {
        guard let snapshot = await loadSnapshot(),
              let branch = snapshot.branches.first(where: { $0.id == branchID }) else {
            return .failed("当前小说项目不可用。")
        }
        let working = branch.workingChapterSelections.filter { selection in
            snapshot.chapters.first { $0.id == selection.chapterID }?.discardedAt == nil
        }
        let state = snapshot.stateSnapshots.first {
            $0.id == branch.currentStateSnapshotID
        }
        var lines = [
            "project: \(snapshot.project.name)",
            "branch: \(branch.name)",
            "head: \(branch.headCheckpointID)",
            "sync: \(branch.syncStatus.rawValue)",
            "chapters: \(working.count)",
            "mode: \(snapshot.project.collaborationMode.rawValue)",
        ]
        if state?.hasStaleChapterPlots == true {
            // Contract v1.1 D-D: unresolved forward-writing gate.
            lines.append("plot_stale: true")
            lines.append("unresolved: true（后续章节剧情指针未解开，写后续/收录/代笔被拦，直到确认无碍、Fork 或重写后续章节）")
        }
        if branch.syncStatus == .needsSync {
            lines.append("dirty: plot/")
        }
        return .filled(lines.joined(separator: "\n"))
    }

    func workspaceWriteApprovalPrompt(
        from arguments: String
    ) async -> Result<NovelAskUserPrompt, NovelProjectToolIssue> {
        guard let args: WorkspaceWriteArguments = decodeWorkspace(arguments),
              let path = normalizePath(args.path) else {
            return .failure(.init("novel_workspace_write 参数无效：需要 path、content。"))
        }
        let parsed = NovelWorkspaceMarkdown.parseFile(args.content)
        let body = parsed.body.isEmpty
            ? args.content.trimmingCharacters(in: .whitespacesAndNewlines)
            : parsed.body
        guard !body.isEmpty else {
            return .failure(.init("novel_workspace_write 的 content 不能为空。"))
        }
        guard let snapshot = await loadSnapshot() else {
            return .failure(.init("当前小说项目不可用，无法写入工作区。"))
        }
        if path.contains("/chapters/"), path.hasSuffix(".md") {
            guard let chapter = matchChapter(path: path, snapshot: snapshot) else {
                return .failure(.init("找不到对应章节：\(path)"))
            }
            let paragraphs = NovelParagraphParser.paragraphs(in: chapter.version.content)
            guard !paragraphs.isEmpty else {
                return .failure(.init("该章没有可替换的段落。"))
            }
            let encodedArgs = ReviseChapterArguments(
                chapter_ordinal: chapter.ordinal,
                chapter_id: chapter.chapterID.description,
                start_paragraph: 1,
                end_paragraph: paragraphs.count,
                new_text: body,
                reason: args.reason ?? parsed.fields["title"]
            )
            let encoded = String(data: try! JSONEncoder().encode(encodedArgs), encoding: .utf8)!
            return await revisionApprovalPrompt(from: encoded)
        }
        if path.contains("/plot/") {
            return .success(NovelAskUserPrompt(
                question: "将 \(path) 写入当前剧情状态？",
                options: NovelWorkspacePlotApproval.options,
                workspacePlot: NovelWorkspacePlotProposal(
                    path: path,
                    body: body,
                    reason: args.reason
                )
            ))
        }
        return .failure(.init("该路径不需要审批，请直接写入。"))
    }

    func workspaceWrite(
        _ arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        guard let args: WorkspaceWriteArguments = decodeWorkspace(arguments),
              let path = normalizePath(args.path) else {
            return .failed("novel_workspace_write 参数无效：需要 path、content。")
        }
        let parsed = NovelWorkspaceMarkdown.parseFile(args.content)
        let body = parsed.body.isEmpty ? args.content.trimmingCharacters(in: .whitespacesAndNewlines) : parsed.body
        guard !body.isEmpty else {
            return .failed("novel_workspace_write 的 content 不能为空。")
        }
        guard let snapshot = await loadSnapshot() else {
            return .failed("当前小说项目不可用，无法写入工作区。")
        }
        if path.contains("/chapters/"), path.hasSuffix(".md") {
            if !isUserInitiated {
                return .needsApproval(args.reason ?? "等待作者确认写入正文")
            }
            return await writeChapter(
                path: path,
                body: body,
                title: parsed.fields["title"],
                snapshot: snapshot,
                isUserInitiated: true,
                reason: args.reason
            )
        }
        if path.contains("/plot/foreshadowing/") {
            // Contract v1.1 D-F: iOS preserves foreshadowing nodes opaque but
            // does not maintain them yet; never misroute them into the plot
            // draft path.
            return .failed("暂不支持写入伏笔节点（\(path)）：iOS 目前只按契约保留伏笔文件，节点维护能力尚未接入。")
        }
        if path.contains("/plot/") {
            if !isUserInitiated {
                return .needsApproval(args.reason ?? "等待作者确认写入剧情文件")
            }
            return await writePlot(
                path: path,
                body: body,
                snapshot: snapshot,
                isUserInitiated: true,
                reason: args.reason
            )
        }
        if path.hasPrefix("setting/") {
            return await writeSetting(path: path, parsed: parsed, body: body, snapshot: snapshot)
        }
        if path.hasSuffix("/plan/upcoming.md") {
            let beats = NovelWorkspaceMarkdown.bullets(body)
            let command = NovelUpsertUpcomingArcCommand(
                context: mutationContext(projectRevision: snapshot.project.revision),
                projectID: projectID,
                branchID: branchID,
                beats: beats
            )
            if let failure = await perform(.upsertUpcomingArc(command)) {
                return .failed(failure)
            }
            return .filled("已写入 \(path)。")
        }
        return .failed("尚不支持写入 \(path)。先改 setting/、chapters/、plot/ 或 plan/upcoming.md。")
    }

    private func writeChapter(
        path: String,
        body: String,
        title: String?,
        snapshot: NovelProjectSnapshot,
        isUserInitiated: Bool,
        reason: String?
    ) async -> IOSAgentToolOutcome {
        guard let chapter = matchChapter(path: path, snapshot: snapshot) else {
            return .failed("找不到对应章节：\(path)")
        }
        let paragraphs = NovelParagraphParser.paragraphs(in: chapter.version.content)
        guard !paragraphs.isEmpty else {
            return .failed("该章没有可替换的段落。")
        }
        let args = ReviseChapterArguments(
            chapter_ordinal: chapter.ordinal,
            chapter_id: chapter.chapterID.description,
            start_paragraph: 1,
            end_paragraph: paragraphs.count,
            new_text: body,
            reason: reason ?? title
        )
        let encoded = String(data: try! JSONEncoder().encode(args), encoding: .utf8)!
        return await reviseChapter(encoded, isUserInitiated: isUserInitiated)
    }

    private func writePlot(
        path: String,
        body: String,
        snapshot: NovelProjectSnapshot,
        isUserInitiated: Bool,
        reason: String?
    ) async -> IOSAgentToolOutcome {
        if !isUserInitiated {
            return .needsApproval(reason ?? "等待作者确认写入剧情文件")
        }
        guard let creation else {
            return .failed("小说创作服务当前不可用。")
        }
        do {
            try await creation.applyWorkspacePlot(
                projectID: projectID,
                branchID: branchID,
                path: path,
                body: body
            )
            return .filled("已写入 \(path)，剧情状态已同步。")
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    private func writeSetting(
        path: String,
        parsed: NovelWorkspaceMarkdown.ParsedFile,
        body: String,
        snapshot: NovelProjectSnapshot
    ) async -> IOSAgentToolOutcome {
        let kind: NovelMaterialKind
        if path.contains("/characters/") {
            kind = .character
        } else if path.contains("/relationships/") {
            kind = .relationship
        } else if path.contains("/outline/") {
            kind = .masterOutline
        } else if path.contains("/writing/") {
            kind = .writingRequirements
        } else if path.contains("/log/") {
            kind = .decisionLog
        } else if path.contains("/custom/") {
            kind = .custom(parsed.fields["customName"] ?? "自定义")
        } else if path.contains("/world/") {
            kind = .world
        } else {
            // Unknown setting subfolders may be opaque cross-platform node
            // directories (contract v1.1 §3.6); never reinterpret them.
            return .failed("暂不支持写入 \(path)：未知设定目录按契约只透传保留，不能改写。")
        }
        let materialID: NovelMaterialID
        if let raw = parsed.fields["id"], let uuid = UUID(uuidString: raw) {
            materialID = NovelMaterialID(rawValue: uuid)
        } else if let matched = matchMaterial(path: path, snapshot: snapshot) {
            materialID = matched
        } else {
            materialID = NovelMaterialID()
        }
        let command = NovelReviseMaterialCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: projectID,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: kind,
            title: parsed.fields["title"] ?? URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent,
            content: body,
            tags: [],
            injectionMode: NovelInjectionMode(rawValue: parsed.fields["injection"] ?? "") ?? .smart,
            aliases: parsed.lists["aliases"] ?? []
        )
        if let failure = await perform(.reviseMaterial(command)) {
            return .failed(failure)
        }
        return .filled("已写入 \(path)。")
    }

    private func workspaceFiles() async -> [NovelWorkspaceBackup.File]? {
        guard let snapshot = await loadSnapshot() else { return nil }
        return try? NovelWorkspaceBackup.export(snapshot.document)
    }

    private func matchChapter(path: String, snapshot: NovelProjectSnapshot) -> WorkingChapter? {
        let name = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
        let ordinal = Int(name.prefix(while: \.isNumber))
        let chapters = workingChapters(in: snapshot)
        if let ordinal, let match = chapters.first(where: { $0.ordinal == ordinal }) {
            return match
        }
        return chapters.first { chapter in
            path.contains(chapter.chapterID.description) || path.contains(chapter.version.title)
        }
    }

    private func matchMaterial(path: String, snapshot: NovelProjectSnapshot) -> NovelMaterialID? {
        snapshot.materials.first { material in
            path.contains(material.id.description)
        }?.id
    }

    private func decodePrefix(_ arguments: String) -> String? {
        decodeWorkspace(WorkspacePrefixArguments.self, arguments)?.prefix.flatMap(normalizePath)
    }

    private func decodeWorkspace<T: Decodable>(_ type: T.Type, _ arguments: String) -> T? {
        let data = Data(arguments.utf8)
        return try? JSONDecoder().decode(type, from: data)
    }

    private func decodeWorkspace<T: Decodable>(_ arguments: String) -> T? {
        decodeWorkspace(T.self, arguments)
    }

    private func normalizePath(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !trimmed.isEmpty, !trimmed.contains("..") else { return nil }
        return trimmed
    }
}

extension NovelProjectSnapshot {
    var document: NovelProjectDocumentV1 {
        NovelProjectDocumentV1(
            schemaVersion: NovelProjectDocumentV1.currentSchemaVersion,
            project: project,
            materials: materials,
            materialRevisions: materialRevisions,
            branches: branches,
            sessions: sessions,
            chapters: chapters,
            chapterVersions: chapterVersions,
            events: events,
            stateSnapshots: stateSnapshots,
            checkpoints: checkpoints,
            candidates: candidates,
            injectionReceipts: injectionReceipts,
            generationReceipts: generationReceipts,
            factAttempts: factAttempts,
            polishTransactions: polishTransactions,
            polishAttempts: polishAttempts,
            polishAssessments: polishAssessments,
            pendingOperations: pendingOperations,
            activeRuns: activeRuns,
            settingProposals: settingProposals,
            chapterPlans: chapterPlans,
            upcomingArcs: upcomingArcs,
            appliedOperations: appliedOperations,
            workspacePassthrough: workspacePassthrough
        )
    }
}

private struct WorkspacePrefixArguments: Decodable {
    var prefix: String?
}

private struct WorkspacePathArguments: Decodable {
    var path: String
}

private struct WorkspaceGrepArguments: Decodable {
    var query: String
    var prefix: String?
}

private struct WorkspaceWriteArguments: Decodable {
    var path: String
    var content: String
    var reason: String?
}
