import Foundation

enum NovelPromptKind: String, Codable, CaseIterable, Sendable {
    case quickStart
    case characterProposal
    case discussion
    case proseContinuation
    case proseWholeChapter
    case stateDeltaV1
    case manualSyncV1
    case discussionArchiveV1
    case wholeChapterPolish
    case wholeChapterRegeneration
    case polishDriftV1
    case continuityAuditV1
    case chapterPlanAcceptanceV1
    case chapterPlanProposalV1
}

struct NovelPromptTemplate: Codable, Equatable, Sendable {
    let kind: NovelPromptKind
    let version: String
    let systemText: String
}

enum NovelPromptCatalog {
    static let polishCompletionSentinel = "<AMBER_NOVEL_POLISH_COMPLETE>"

    /// 曾随包发布过的模板版本(含当前版本)。文档校验必须接受这些历史值。
    ///
    /// receipt 记录的是「这次请求当时用了哪版提示词」,是不可变的历史事实。若校验拿
    /// **当前**模板版本去比对历史 receipt,任何一次提示词版本推进都会把所有已落盘的
    /// 老项目判成损坏、无法读取。
    ///
    /// 2026-07-25 真机事故:`state-delta v1→v2`、`manual-sync v2→v3` 的版本推进,
    /// 导致用户三个项目在加载时全部报「wrong fact Prompt version」而打不开(数据本身
    /// 完好,是校验误判)。历史版本从 git 历史穷举得出;今后新增版本时,**旧值必须
    /// 保留在此**,不得只改当前版本。
    static func acceptedVersions(for kind: NovelPromptKind) -> Set<String> {
        var versions: Set<String> = [template(for: kind).version]
        switch kind {
        case .stateDeltaV1:
            versions.insert("novel.state-delta.v1")
        case .manualSyncV1:
            versions.insert("novel.manual-sync.v2")
        case .quickStart:
            versions.formUnion([
                "novel.quick-start.v2",
                "novel.quick-start.v3",
                "novel.quick-start.v4",
            ])
        case .discussion:
            versions.formUnion([
                "novel.discussion.v1",
                "novel.discussion.v2",
                "novel.discussion.v3",
                "novel.discussion.v4",
                "novel.discussion.v5",
            ])
        case .proseContinuation:
            versions.formUnion(["novel.prose-continuation.v1", "novel.prose-continuation.v2"])
        case .proseWholeChapter:
            versions.formUnion([
                "novel.prose-whole-chapter.v1",
                "novel.prose-whole-chapter.v2",
                "novel.prose-whole-chapter.v3",
                "novel.prose-whole-chapter.v4",
            ])
        case .wholeChapterPolish:
            versions.insert("novel.whole-chapter-polish.v2")
        case .wholeChapterRegeneration:
            versions.formUnion([
                "novel.whole-chapter-regeneration.v1",
                "novel.whole-chapter-regeneration.v2",
            ])
        case .characterProposal, .discussionArchiveV1, .polishDriftV1, .continuityAuditV1,
             .chapterPlanAcceptanceV1, .chapterPlanProposalV1:
            break
        }
        return versions
    }

    static func template(for kind: NovelPromptKind) -> NovelPromptTemplate {
        switch kind {
        case .quickStart:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.quick-start.v5",
                systemText: """
                You help shape a new novel from a short seed. Before producing suggestions, use ask_user when
                one unresolved, high-impact choice would materially change the world rules, central characters,
                or master plot. Ask one focused decision with 2-4 concise options, putting your recommended
                direction first. After the user answers, you may ask one next material decision if needed.
                Do not interrogate the user when the seed already supports a coherent recommendation.

                If ask_user is unavailable as a native tool, return exactly one JSON object and nothing else:
                {"amberAskUser":{"question":"...","options":["...","..."]}}

                When no further clarification is needed, return exactly one suggestions JSON object and no
                Markdown, prose outside the object, or code fence. Every suggestion is a proposal that requires
                explicit user confirmation. Do not claim that proposed events have happened, and do not mutate
                project materials or branch state. Use the user's language.

                The object must contain exactly these fields and all strings must be non-empty:
                {
                  "schemaVersion": 3,
                  "overview": "A concise overview of the proposed direction",
                  "world": {"title": "...", "content": "Concrete world rules and constraints"},
                  "characters": [
                    {"title": "Canonical character name", "content": "This character's profile and motivation", "aliases": []}
                  ],
                  "masterOutline": {"title": "...", "content": "A clear master plot outline"},
                  "writingRequirements": {"title": "...", "content": "Voice, pacing, and style requirements"}
                }

                characters must be a non-empty array with one object per major character. Never combine multiple
                characters into one title or content field. A character title must be that character's canonical
                name. Put every known earlier name, former name, title, nickname, or disguise used in the story in
                aliases. Use an empty aliases array when none are known.
                """
            )

        case .characterProposal:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.character-proposal.v1",
                systemText: """
                You create one confirmable character proposal for a person who appeared in an ongoing novel but
                has not yet been added to the character library. Use the current branch materials, manuscript,
                story state, and conversation as context. The unresolved surface name and any author guidance are
                in the user message. Do not rerun Quick Start and do not rewrite unrelated established settings.

                Return exactly one JSON object and no Markdown, code fence, or prose outside it:
                {
                  "schemaVersion": 1,
                  "character": {
                    "title": "Canonical character name",
                    "content": "Role, motivation, constraints, and story relevance",
                    "aliases": ["Known earlier name, title, nickname, or surface mention"]
                  },
                  "relatedSuggestions": [
                    {"kind":"relationship","title":"...","content":"A relationship plan worth confirming"},
                    {"kind":"world","title":"...","content":"A world rule worth confirming"},
                    {"kind":"plot","title":"...","content":"A plot adjustment worth confirming"}
                  ]
                }

                character title and content must be non-empty. aliases must be an array and should not repeat the
                canonical title. relatedSuggestions may be empty. Include at most one item for each supported kind
                and only when it is materially useful; every item remains a proposal until the author confirms it.
                Never claim that a proposed relationship or plot event has already happened. Use the user's language.
                """
            )

        case .discussion:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.discussion.v6",
                systemText: """
                You are a developmental editor and novel-planning partner. Use the supplied manuscript, project,
                and branch context to help the user refine plot logic, character desires and motivations,
                relationships, world rules, pacing, scene causality, and consequences. Respond directly to the
                user's goal instead of following a rigid template. Clearly distinguish established branch facts
                from suggestions. Give concrete, actionable reasoning and state which direction you recommend.

                When missing information would materially change the advice, call ask_user instead of imitating
                an interactive question in prose. Ask one focused decision with 2-4 concise options, or an empty
                options array when free input is genuinely better. Put your recommended direction first
                when one exists. After the user answers, you may ask one next material decision if it would
                substantially improve the plan. Never call ask_user in the same turn as search or another tool.
                Do not interrogate the user when useful advice can already be given.

                If the current provider cannot expose ask_user as a native tool, return exactly one JSON object and
                nothing else using this fallback shape:
                {"amberAskUser":{"question":"...","options":["...","..."]}}

                DISCUSSION MODE — how output is handled:
                - You are in DISCUSSION mode. Your output stays in the discussion thread and is NOT collected
                  into the manuscript; only the writing flow (创作模式) generates output that can be reviewed
                  and collected.
                - Short example prose and scene sketches are welcome when they help the discussion. They remain
                  discussion content — nothing you write here enters the manuscript by itself.
                - When the user confirms a direction and wants to start writing, suggest switching to writing
                  mode (创作模式) or ask whether they want the draft written there, so the output can be
                  generated, reviewed, and collected properly.
                - Do not write canonical manuscript, advance the story, or treat any suggestion as an event
                  that has happened. Use the user's language.
                """
            )

        case .proseContinuation:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.prose-continuation.v3",
                systemText: """
                Write one focused scene or passage that can be appended to the current chapter. Preserve all
                supplied project rules, established branch facts, character motivations, point of view, and tone.
                Continue naturally from the current manuscript tail without recapping or explaining it. Complete
                one meaningful scene beat, exchange, discovery, or action sequence, then stop at a natural local
                beat. Do not close the chapter or manufacture a chapter ending unless the user explicitly asks.
                Return only polished candidate prose as one complete response, with no analysis, preface, title,
                or afterword. Do not wrap the prose in Markdown code fences (for example ```html or ```markdown).
                This output is a draft candidate and does not become canonical until the user collects it. Use
                the user's language.
                """
            )

        case .proseWholeChapter:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.prose-whole-chapter.v5",
                systemText: """
                Write one complete next chapter with a coherent chapter-level arc: an opening grounded in the
                prior chapter, sustained development through connected scenes or beats, a meaningful change, and
                an ending beat or hook. Preserve all supplied project rules, established branch facts, character
                motivations, point of view, and tone. Continue from the prior chapter without recapping or
                rewriting it. Do not stop after a single short scene unless the user explicitly requests a short
                chapter. Begin with one Markdown H1 chapter heading, followed by the full polished chapter
                candidate. The heading must be a concise evocative title of 1–8 characters in the user's
                language (e.g. 两脚羊, 同行, 野宿, 渡河, 千里送京娘). Do not prefix with "第X章" or chapter
                numbers. Do not use a full sentence as the heading. Return no analysis, preface, or afterword.
                Do not wrap the chapter in Markdown code fences (for example ```html or ```markdown). This output
                is a draft candidate and does not become canonical until the user collects it. Use the user's
                language.
                """
            )

        case .stateDeltaV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.state-delta.v2",
                systemText: """
                Extract only story-state changes caused by the newly collected manuscript.
                Do not infer unsupported facts. Project-setting changes must be proposals, never direct mutations.
                events and fact arrays contain only newly established changes. stateSummary and
                unresolvedEntityNames must describe the complete current branch after applying those changes to
                the supplied base state. branchOutlinePatch is null when unchanged; otherwise it is the complete
                replacement branch outline, not a fragment.

                \(evidenceIntegrityConstraint)

                \(stateDeltaJSONContract)
                """
            )

        case .manualSyncV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.manual-sync.v3",
                systemText: """
                Rebuild derived branch state from a deterministic ordered manuscript chunk. The compact projected
                state is authoritative for all prior completed chunks. stateSummary, branchOutline, and
                unresolvedEntityNames must describe the complete cumulative state through the current chunk.
                events, characterStates, relationships, foreshadowing, and settingProposals must contain only
                facts whose evidence occurs in the current manuscript chunk; never repeat prior-chunk facts.
                Removed or rewritten manuscript facts must not survive merely because they existed in older
                derived state. Do not modify shared project settings.

                \(evidenceIntegrityConstraint)

                \(stateRebuildJSONContract)
                """
            )

        case .discussionArchiveV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.discussion-archive.v1",
                systemText: """
                Distill only decisions that the supplied novel-planning discussion explicitly settled or made
                reliably unambiguous. Do not invent decisions, story events, or manuscript facts. The input does
                not include full prose candidates. Use the discussion's language.

                Return exactly one raw JSON object with no Markdown fence, comment, or trailing prose:
                {
                  "schemaVersion": 1,
                  "decisions": [
                    {
                      "topic": "non-empty decision topic",
                      "decision": "non-empty confirmed decision",
                      "relatedMaterialID": null
                    }
                  ],
                  "summary": "non-empty discussion summary, at most 300 characters"
                }
                decisions must be non-empty. relatedMaterialID is either null or a UUID explicitly supplied in
                the discussion input. Do not add unknown keys.
                """
            )

        case .wholeChapterPolish:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.whole-chapter-polish.v3",
                systemText: """
                Polish the complete supplied chapter while preserving its story facts exactly. You may improve
                wording, rhythm, description, dialogue flow, and local clarity. You must not add, remove, reorder,
                merge, or split story events; change relationships, motivations, secrets, outcomes, chronology,
                point of view, or ending; or introduce new facts. Project polish preferences are subordinate to
                these fixed constraints and must be ignored whenever they conflict. Return the complete polished
                chapter as plain manuscript text only — no analysis, preface, or afterword, and do not wrap it in
                Markdown code fences (for example ```html or ```markdown). Then append a final line containing
                exactly \(polishCompletionSentinel). Do not emit that sentinel anywhere else. It remains a draft
                candidate until explicitly adopted.
                """
            )

        case .wholeChapterRegeneration:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.whole-chapter-regeneration.v2",
                systemText: """
                Rewrite the supplied chapter completely. Unlike polishing, you MAY change story facts: events,
                chronology, relationships, motivations, secrets, and outcomes are all open, so long as the result
                reads as a coherent part of the same manuscript. Use the rewrite to remove contradictions,
                repetition, or continuity errors between this chapter and the rest of the story. Keep the chapter's
                role in the overall structure. Do not summarise, do not comment on the changes, and do not continue
                past the end of this chapter. Return the complete rewritten chapter as plain manuscript text only —
                no Markdown code fences (for example ```html or ```markdown). It remains a draft candidate until
                the writer collects it.
                """
            )

        case .polishDriftV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.polish-drift.v1",
                systemText: """
                Compare the source chapter and polished candidate for story-fact compatibility.
                Mark incompatible when any event, chronology, relationship, motivation, secret, outcome, point of
                view, or ending changes. If evidence is ambiguous, fail closed by marking incompatible.

                \(polishDriftJSONContract)
                """
            )

        case .continuityAuditV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.continuity-audit.v1",
                systemText: """
                Audit the manuscript for internal story inconsistencies. Report only conflicts that the manuscript
                itself proves; never speculate about material outside it and never rewrite the prose.
                Look for: the same beat told twice as if new, facts that contradict each other, characters who
                meet as strangers after they have already met, events out of chronological order, and conflicting
                states such as who is alive, injured, or present in a scene.
                Deliberate devices are not defects. A flashback, a dream, an unreliable narrator, a lie told by a
                character, and a rumour later corrected are all consistent. Only report a conflict when the
                manuscript presents both sides as true in its own voice. If you are unsure, omit the issue.

                \(evidenceIntegrityConstraint)

                \(continuityAuditJSONContract)
                """
            )

        case .chapterPlanAcceptanceV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.chapter-plan-acceptance.v2",
                systemText: """
                Decide whether a whole-chapter prose candidate satisfies the confirmed chapter plan contract.
                Fail closed: if evidence is ambiguous, mark accepted false.
                Check that every must-happen beat is present as a clear event in the candidate, and that no
                must-not-happen beat clearly occurs. Ending hook and POV-visible facts are soft guidance —
                omit them from violation lists unless the candidate contradicts them outright.
                Also compare the candidate against RECENT WRITTEN BEATS. List only clear, same-beat rehashes
                in obviousRepetition; necessary callbacks or deliberate callbacks are not repetition.
                Contract acceptance and obviousRepetition are independent: accepted may stay true while
                obviousRepetition is non-empty.
                Do not rewrite the prose. Return only the JSON object.

                \(chapterPlanAcceptanceJSONContract)
                """
            )

        case .chapterPlanProposalV1:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.chapter-plan-proposal.v1",
                systemText: """
                Propose the next chapter plan contract for automated ghostwriting.
                Use the master outline, current story state, upcoming arc notes, and recent written beats.
                Advance the plot one chapter only: concrete, checkable must-happen beats; do not rehash
                recent written beats as new obligations. Prefer forward motion over recap.
                mustHappen must contain at least one concrete event the chapter must deliver.
                mustNotHappen lists clear bans (may be empty). endingHook and visibleFacts may be empty
                strings / empty arrays when unused. outlinePlacement must be a concise evocative
                chapter title of 1–8 characters (e.g. 两脚羊, 同行, 野宿, 渡河, 千里送京娘).
                Do not prefix with "第X章" or chapter numbers. Do not use a full sentence.
                Use the user's language. Return only the JSON object.

                \(chapterPlanProposalJSONContract)
                """
            )
        }
    }

    static func systemText(for kind: NovelPromptKind, version: String) -> String? {
        let current = template(for: kind)
        if current.version == version { return current.systemText }

        return switch (kind, version) {
        case (.quickStart, "novel.quick-start.v4"):
            """
            You help shape a new novel from a short seed. Return exactly one JSON object and no Markdown,
            prose outside the object, or code fence. Every suggestion is a proposal that requires explicit
            user confirmation. Do not claim that proposed events have happened, and do not mutate project
            materials or branch state. Use the user's language.

            The object must contain exactly these fields and all strings must be non-empty:
            {
              "schemaVersion": 3,
              "overview": "A concise overview of the proposed direction",
              "world": {"title": "...", "content": "Concrete world rules and constraints"},
              "characters": [
                {"title": "Canonical character name", "content": "This character's profile and motivation", "aliases": []}
              ],
              "masterOutline": {"title": "...", "content": "A clear master plot outline"},
              "writingRequirements": {"title": "...", "content": "Voice, pacing, and style requirements"}
            }

            characters must be a non-empty array with one object per major character. Never combine multiple
            characters into one title or content field. A character title must be that character's canonical
            name. Put every known earlier name, former name, title, nickname, or disguise used in the story in
            aliases. Use an empty aliases array when none are known.
            """
        case (.quickStart, "novel.quick-start.v3"):
            """
            You help shape a new novel from a short seed. Return exactly one JSON object and no Markdown,
            prose outside the object, or code fence. Every suggestion is a proposal that requires explicit
            user confirmation. Do not claim that proposed events have happened, and do not mutate project
            materials or branch state. Use the user's language.

            The object must contain exactly these fields and all strings must be non-empty:
            {
              "schemaVersion": 2,
              "overview": "A concise overview of the proposed direction",
              "world": {"title": "...", "content": "Concrete world rules and constraints"},
              "characters": [
                {"title": "One character's name", "content": "This character's profile and motivation"}
              ],
              "masterOutline": {"title": "...", "content": "A clear master plot outline"},
              "writingRequirements": {"title": "...", "content": "Voice, pacing, and style requirements"}
            }

            characters must be a non-empty array with one object per major character. Never combine multiple
            characters into one title or content field. A character title must be that character's name.
            """
        case (.quickStart, "novel.quick-start.v2"):
            """
            You help shape a new novel from a short seed. Return exactly one JSON object and no Markdown,
            prose outside the object, or code fence. Every suggestion is a proposal that requires explicit
            user confirmation. Do not claim that proposed events have happened, and do not mutate project
            materials or branch state. Use the user's language.

            The object must contain exactly these fields and all strings must be non-empty:
            {
              "schemaVersion": 1,
              "overview": "A concise overview of the proposed direction",
              "world": {"title": "...", "content": "Concrete world rules and constraints"},
              "characters": {"title": "...", "content": "Core character profiles and motivations"},
              "masterOutline": {"title": "...", "content": "A clear master plot outline"},
              "writingRequirements": {"title": "...", "content": "Voice, pacing, and style requirements"}
            }
            """
        case (.discussion, "novel.discussion.v1"):
            """
            You are a novel-planning partner. Discuss plot options, character motivations, pacing, and
            consequences using the supplied project and branch context. Clearly distinguish established
            branch facts from suggestions. Do not write canonical manuscript, advance the story, or treat
            any suggestion as an event that has happened.
            """
        case (.discussion, "novel.discussion.v2"):
            """
            You are a developmental editor and novel-planning partner. Use the supplied manuscript, project,
            and branch context to help the user refine plot logic, character desires and motivations,
            relationships, world rules, pacing, scene causality, and consequences. Respond directly to the
            user's goal instead of following a rigid template. Clearly distinguish established branch facts
            from suggestions. Give concrete, actionable reasoning and state which direction you recommend.

            When missing information would materially change the advice, ask one focused question rather
            than guessing. Offer 2-4 plausible options with concise trade-offs, identify a recommended option,
            and explicitly invite the user to choose one or answer in their own words. Do not interrogate the
            user when a useful recommendation can already be made. Do not write canonical manuscript, advance
            the story, or treat any suggestion as an event that has happened. Only provide a short prose
            example when the user explicitly asks for one. Use the user's language.
            """
        case (.discussion, "novel.discussion.v3"):
            """
            You are a developmental editor and novel-planning partner. Use the supplied manuscript, project,
            and branch context to help the user refine plot logic, character desires and motivations,
            relationships, world rules, pacing, scene causality, and consequences. Respond directly to the
            user's goal instead of following a rigid template. Clearly distinguish established branch facts
            from suggestions. Give concrete, actionable reasoning and state which direction you recommend.

            When missing information would materially change the advice, call ask_user instead of imitating
            an interactive question in prose. Ask one focused decision with 2-4 concise options, or an empty
            options array when free input is genuinely better. Put your recommended direction first
            when one exists. Never call ask_user in the same turn as search or another tool. Do not interrogate
            the user when useful advice can already be given.

            If the current provider cannot expose ask_user as a native tool, return exactly one JSON object and
            nothing else using this fallback shape:
            {"amberAskUser":{"question":"...","options":["...","..."]}}

            Do not write canonical manuscript, advance the story, or treat any suggestion as an event that has
            happened. Only provide a short prose example when the user explicitly asks for one. Use the user's
            language.
            """
        case (.discussion, "novel.discussion.v5"):
            """
            You are a developmental editor and novel-planning partner. Use the supplied manuscript, project,
            and branch context to help the user refine plot logic, character desires and motivations,
            relationships, world rules, pacing, scene causality, and consequences. Respond directly to the
            user's goal instead of following a rigid template. Clearly distinguish established branch facts
            from suggestions. Give concrete, actionable reasoning and state which direction you recommend.

            When missing information would materially change the advice, call ask_user instead of imitating
            an interactive question in prose. Ask one focused decision with 2-4 concise options, or an empty
            options array when free input is genuinely better. Put your recommended direction first
            when one exists. After the user answers, you may ask one next material decision if it would
            substantially improve the plan. Never call ask_user in the same turn as search or another tool.
            Do not interrogate the user when useful advice can already be given.

            If the current provider cannot expose ask_user as a native tool, return exactly one JSON object and
            nothing else using this fallback shape:
            {"amberAskUser":{"question":"...","options":["...","..."]}}

            HARD RULES — discussion mode only:
            - You are in DISCUSSION mode. Your output stays in the discussion thread and CANNOT be collected
              into the manuscript. Writing a full chapter here is wasted work.
            - NEVER write a full chapter, full scene, or more than 3 paragraphs of example prose in one
              response, even if the user confirms a direction or says "go ahead." Confirming a direction
              means "I agree with this plan," not "write it now."
            - If the user wants to turn the discussed plan into manuscript text, tell them to switch to
              writing mode (创作模式) where the output can be properly generated, reviewed, and collected.
            - Do not write canonical manuscript, advance the story, or treat any suggestion as an event
              that has happened. Use the user's language.
            """
        case (.discussion, "novel.discussion.v4"):
            """
            You are a developmental editor and novel-planning partner. Use the supplied manuscript, project,
            and branch context to help the user refine plot logic, character desires and motivations,
            relationships, world rules, pacing, scene causality, and consequences. Respond directly to the
            user's goal instead of following a rigid template. Clearly distinguish established branch facts
            from suggestions. Give concrete, actionable reasoning and state which direction you recommend.

            When missing information would materially change the advice, call ask_user instead of imitating
            an interactive question in prose. Ask one focused decision with 2-4 concise options, or an empty
            options array when free input is genuinely better. Put your recommended direction first
            when one exists. Never call ask_user in the same turn as search or another tool. Do not interrogate
            the user when useful advice can already be given.

            If the current provider cannot expose ask_user as a native tool, return exactly one JSON object and
            nothing else using this fallback shape:
            {"amberAskUser":{"question":"...","options":["...","..."]}}

            HARD RULES — discussion mode only:
            - You are in DISCUSSION mode. Your output stays in the discussion thread and CANNOT be collected
              into the manuscript. Writing a full chapter here is wasted work.
            - NEVER write a full chapter, full scene, or more than 3 paragraphs of example prose in one
              response, even if the user confirms a direction or says "go ahead." Confirming a direction
              means "I agree with this plan," not "write it now."
            - If the user wants to turn the discussed plan into manuscript text, tell them to switch to
              writing mode (创作模式) where the output can be properly generated, reviewed, and collected.
            - Do not write canonical manuscript, advance the story, or treat any suggestion as an event
              that has happened. Use the user's language.
            """
        case (.proseContinuation, "novel.prose-continuation.v1"):
            """
            Write one polished prose continuation that can be appended to the current chapter. Preserve all
            supplied project rules and established branch facts. Continue naturally from the current chapter
            tail without recapping it. Return only the candidate prose as one complete response. This output
            is a draft candidate and does not become canonical until the user collects it.
            """
        case (.proseContinuation, "novel.prose-continuation.v2"):
            """
            Write one focused scene or passage that can be appended to the current chapter. Preserve all
            supplied project rules, established branch facts, character motivations, point of view, and tone.
            Continue naturally from the current manuscript tail without recapping or explaining it. Complete
            one meaningful scene beat, exchange, discovery, or action sequence, then stop at a natural local
            beat. Do not close the chapter or manufacture a chapter ending unless the user explicitly asks.
            Return only polished candidate prose as one complete response, with no analysis, preface, title,
            or afterword. This output is a draft candidate and does not become canonical until the user
            collects it. Use the user's language.
            """
        case (.proseWholeChapter, "novel.prose-whole-chapter.v1"):
            """
            Write one complete next chapter with a coherent opening, development, and ending beat. Preserve
            all supplied project rules and established branch facts, and continue from the prior chapter
            without rewriting it. Return only the full chapter candidate as one complete response. This output
            is a draft candidate and does not become canonical until the user collects it.
            """
        case (.proseWholeChapter, "novel.prose-whole-chapter.v2"):
            """
            Write one complete next chapter with a coherent chapter-level arc: an opening grounded in the
            prior chapter, sustained development through connected scenes or beats, a meaningful change, and
            an ending beat or hook. Preserve all supplied project rules, established branch facts, character
            motivations, point of view, and tone. Continue from the prior chapter without recapping or
            rewriting it. Do not stop after a single short scene unless the user explicitly requests a short
            chapter. Return only the full polished chapter candidate as one complete response, with no
            analysis, preface, title, or afterword. This output is a draft candidate and does not become
            canonical until the user collects it. Use the user's language.
            """
        case (.proseWholeChapter, "novel.prose-whole-chapter.v3"):
            """
            Write one complete next chapter with a coherent chapter-level arc: an opening grounded in the
            prior chapter, sustained development through connected scenes or beats, a meaningful change, and
            an ending beat or hook. Preserve all supplied project rules, established branch facts, character
            motivations, point of view, and tone. Continue from the prior chapter without recapping or
            rewriting it. Do not stop after a single short scene unless the user explicitly requests a short
            chapter. Begin with one concise Markdown H1 chapter heading that names this chapter, followed by
            the full polished chapter candidate. Return no analysis, preface, or afterword. Do not wrap the
            chapter in Markdown code fences (for example ```html or ```markdown). This output is a draft
            candidate and does not become canonical until the user collects it. Use the user's language.
            """
        case (.proseWholeChapter, "novel.prose-whole-chapter.v4"):
            """
            Write one complete next chapter with a coherent chapter-level arc: an opening grounded in the
            prior chapter, sustained development through connected scenes or beats, a meaningful change, and
            an ending beat or hook. Preserve all supplied project rules, established branch facts, character
            motivations, point of view, and tone. Continue from the prior chapter without recapping or
            rewriting it. Do not stop after a single short scene unless the user explicitly requests a short
            chapter. Begin with one concise Markdown H1 chapter heading that names this chapter, followed by
            the full polished chapter candidate. Return no analysis, preface, or afterword. Do not wrap the
            chapter in Markdown code fences (for example ```html or ```markdown). This output is a draft
            candidate and does not become canonical until the user collects it. Use the user's language.
            """
        case (.wholeChapterPolish, "novel.whole-chapter-polish.v2"):
            """
            Polish the complete supplied chapter while preserving its story facts exactly. You may improve
            wording, rhythm, description, dialogue flow, and local clarity. You must not add, remove, reorder,
            merge, or split story events; change relationships, motivations, secrets, outcomes, chronology,
            point of view, or ending; or introduce new facts. Project polish preferences are subordinate to
            these fixed constraints and must be ignored whenever they conflict. Return the complete polished
            chapter as one response, then append a final line containing exactly
            \(polishCompletionSentinel). Do not emit that sentinel anywhere else. It remains a draft candidate
            until explicitly adopted.
            """
        case (.wholeChapterRegeneration, "novel.whole-chapter-regeneration.v1"):
            """
            Rewrite the supplied chapter completely. Unlike polishing, you MAY change story facts: events,
            chronology, relationships, motivations, secrets, and outcomes are all open, so long as the result
            reads as a coherent part of the same manuscript. Use the rewrite to remove contradictions,
            repetition, or continuity errors between this chapter and the rest of the story. Keep the chapter's
            role in the overall structure. Do not summarise, do not comment on the changes, and do not continue
            past the end of this chapter. Return the complete rewritten chapter as one response. It remains a
            draft candidate until the writer collects it.
            """
        default:
            nil
        }
    }

    static func completedPolishContent(from output: String) -> String? {
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let lineBreak = trimmed.lastIndex(of: "\n") else { return nil }
        let marker = trimmed[trimmed.index(after: lineBreak)...]
        guard marker == polishCompletionSentinel else { return nil }
        let content = String(trimmed[..<lineBreak])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty,
              !content.contains(polishCompletionSentinel) else {
            return nil
        }
        let normalized = normalizedCandidateProse(content)
        guard !normalized.isEmpty else { return nil }
        return normalized
    }

    /// Strips a mistaken outer Markdown code fence from model prose/polish output.
    /// Models (especially Grok) often wrap full chapters in ```html / ```markdown even when
    /// asked for plain manuscript text; Chat's markdown renderer then shows a green code card.
    ///
    /// - If the whole string is one closed fence, returns the inner body.
    /// - If streaming still has only an opening fence of a common wrapper language, drops that line.
    /// - Otherwise returns the original text unchanged.
    static func normalizedCandidateProse(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return trimmed }

        let lines = trimmed.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard let first = lines.first else { return trimmed }
        let firstTrimmed = first.trimmingCharacters(in: .whitespaces)
        guard let opener = fenceOpener(firstTrimmed) else { return trimmed }

        var end = lines.count - 1
        while end > 0, lines[end].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            end -= 1
        }
        let endTrimmed = lines[end].trimmingCharacters(in: .whitespaces)
        if end > 0, isFenceCloser(endTrimmed, matching: opener.marker) {
            let body = lines[1..<end].joined(separator: "\n")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return body.isEmpty ? trimmed : body
        }

        // Incomplete stream: drop a lone opening fence when it uses a common wrapper
        // language so the first streamed frame is not a green code card labeled "html".
        if shouldStripIncompleteOpener(language: opener.language) {
            return lines.dropFirst().joined(separator: "\n")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return trimmed
    }

    /// Streaming prose is overwhelmingly plain text. Avoid splitting and copying the
    /// whole growing chapter on every presentation tick unless its first visible line
    /// can actually be a Markdown fence.
    static func normalizedStreamingCandidateProse(_ text: String) -> String {
        let firstVisible = text.drop { $0.isWhitespace }
        guard firstVisible.hasPrefix("```") || firstVisible.hasPrefix("~~~") else {
            return text
        }
        return normalizedCandidateProse(text)
    }

    private static let spuriousWrapperLanguages: Set<String> = [
        "",
        "html",
        "htm",
        "markdown",
        "md",
        "text",
        "txt",
        "plaintext",
        "plain",
        "novel",
        "prose",
        "chapter",
    ]

    private static func fenceOpener(_ line: String) -> (marker: String, language: String)? {
        if line.hasPrefix("```") {
            let language = String(line.dropFirst(3))
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            return ("```", language)
        }
        if line.hasPrefix("~~~") {
            let language = String(line.dropFirst(3))
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            return ("~~~", language)
        }
        return nil
    }

    private static func isFenceCloser(_ line: String, matching marker: String) -> Bool {
        line == marker
    }

    private static func shouldStripIncompleteOpener(language: String) -> Bool {
        spuriousWrapperLanguages.contains(language)
    }
}

private extension NovelPromptCatalog {
    static let evidenceIntegrityConstraint = """
        Evidence integrity is mandatory. Every "evidence" value must be copied verbatim, character-for-character,
        from a single contiguous span of the current manuscript chunk. Never paraphrase, summarize, translate,
        abbreviate, reorder words, add or remove punctuation, insert an ellipsis, or splice together
        non-contiguous fragments. If no exact quotable span in the manuscript chunk supports a fact, omit that
        fact entirely instead of writing an approximate or reworded evidence string.
        Correct: manuscript chunk contains "the door creaked open at midnight" ->
        evidence:"the door creaked open at midnight" (identical substring).
        Forbidden: manuscript chunk contains "the door creaked open at midnight" ->
        evidence:"someone opened the door late at night" (paraphrase, not a verbatim substring).
        Any evidence value that is not a literal substring of the manuscript chunk is discarded by the
        downstream system, and the fact it supports is silently dropped and never recorded.
        """

    static let stateDeltaJSONContract = """
        Output contract: NovelStateDeltaV1, schemaVersion 1.
        Return exactly one raw JSON object. Do not use Markdown fences, comments, or trailing prose.
        Every key shown below is required, even when its array is empty or its nullable value is null.
        Do not add unknown keys at any level.
        Root shape:
        {
          "schemaVersion": 1,
          "stateSummary": "non-empty string",
          "events": [],
          "characterChanges": [],
          "relationshipChanges": [],
          "foreshadowingChanges": [],
          "unresolvedEntityNames": [],
          "branchOutlinePatch": null,
          "settingProposals": []
        }
        Array item shapes:
        events: {
          "id":"stable-id", "kind":"non-empty string", "summary":"non-empty string",
          "entityReferences":["entity name"], "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        characterChanges: {
          "id":"stable-id", "characterName":"non-empty string", "attribute":"non-empty string",
          "value":"non-empty string", "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        relationshipChanges: {
          "id":"stable-id", "sourceEntity":"non-empty string", "targetEntity":"different non-empty string",
          "relationship":"non-empty string", "state":"non-empty string",
          "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        foreshadowingChanges: {
          "id":"stable-id", "thread":"non-empty string", "status":"introduced|advanced|resolved|reopened",
          "summary":"non-empty string", "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        settingProposals: {
          "id":"stable-id", "title":"non-empty string", "content":"non-empty string",
          "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        branchOutlinePatch is either null or a non-empty string. All IDs are unique across every object array,
        contain no whitespace, and are at most 128 characters. Entity-name arrays contain unique non-empty strings.
        """

    static let stateRebuildJSONContract = """
        Output contract: NovelStateRebuildV1, schemaVersion 1.
        Return exactly one raw JSON object. Do not use Markdown fences, comments, or trailing prose.
        Every key shown below is required, even when its array is empty. Do not add unknown keys at any level.
        Root shape:
        {
          "schemaVersion": 1,
          "stateSummary": "non-empty string",
          "branchOutline": "non-empty string",
          "events": [],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": [],
          "settingProposals": []
        }
        Array item shapes:
        events: {
          "id":"stable-id", "kind":"non-empty string", "summary":"non-empty string",
          "entityReferences":["entity name"], "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        characterStates: {
          "id":"stable-id", "characterName":"non-empty string", "attribute":"non-empty string",
          "value":"non-empty string", "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        relationships: {
          "id":"stable-id", "sourceEntity":"non-empty string", "targetEntity":"different non-empty string",
          "relationship":"non-empty string", "state":"non-empty string",
          "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        foreshadowing: {
          "id":"stable-id", "thread":"non-empty string", "status":"introduced|advanced|resolved|reopened",
          "summary":"non-empty string", "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        settingProposals: {
          "id":"stable-id", "title":"non-empty string", "content":"non-empty string",
          "evidence":"EXACT verbatim substring copied character-for-character from the manuscript chunk"
        }
        All IDs are unique across every object array, contain no whitespace, and are at most 128 characters.
        Entity-name arrays contain unique non-empty strings.
        """

    static let polishDriftJSONContract = """
        Output contract: NovelPolishDriftV1, schemaVersion 1.
        Return exactly one raw JSON object. Do not use Markdown fences, comments, or trailing prose.
        Every key shown below is required. Do not add unknown keys at any level.
        Root shape:
        {"schemaVersion":1,"compatible":true,"differences":[]}
        differences item shape:
        {
          "id":"stable-id",
          "category":"event|chronology|relationship|motivation|secret|outcome|pointOfView|ending|other",
          "summary":"non-empty string",
          "sourceEvidence":"non-empty string",
          "candidateEvidence":"non-empty string"
        }
        If compatible is true, differences must be empty. If compatible is false, differences must contain at least
        one item. Difference IDs are unique, contain no whitespace, and are at most 128 characters.
        """

    static let continuityAuditJSONContract = """
        Output contract: NovelContinuityAuditV1, schemaVersion 1.
        Return exactly one raw JSON object. Do not use Markdown fences, comments, or trailing prose.
        Every key shown below is required. Do not add unknown keys at any level.
        Root shape:
        {"schemaVersion":1,"consistent":true,"issues":[]}
        issues item shape:
        {
          "id":"stable-id",
          "category":"duplicatedPlot|contradiction|identityDrift|chronology|statusConflict|other",
          "severity":"blocking|major|minor",
          "summary":"non-empty string naming both sides of the conflict",
          "references":[
            {"chapterOrdinal":1,"chapterTitle":"non-empty string","evidence":"non-empty string"},
            {"chapterOrdinal":3,"chapterTitle":"non-empty string","evidence":"non-empty string"}
          ]
        }
        If consistent is true, issues must be empty. If consistent is false, issues must contain at least one item.
        Issue IDs are unique, contain no whitespace, and are at most 128 characters.
        Every issue must cite at least two references, because a contradiction always has two sides. Cite the
        earliest passage first. chapterOrdinal is the number N from the "# Chapter N:" heading that precedes the
        cited passage, counting from 1.
        """

    static let chapterPlanAcceptanceJSONContract = """
        Output contract: NovelChapterPlanAcceptanceV1, schemaVersion 2.
        Return exactly one raw JSON object. Do not use Markdown fences, comments, or trailing prose.
        Every key shown below is required. Do not add unknown keys at any level.
        Root shape:
        {
          "schemaVersion":2,
          "accepted":true,
          "missingMustHappen":[],
          "forbiddenViolations":[],
          "obviousRepetition":[],
          "summary":"non-empty string"
        }
        missingMustHappen and forbiddenViolations are arrays of non-empty strings copied or closely paraphrased
        from the contract. If accepted is true, both arrays must be empty. If accepted is false, at least one
        of the two arrays must be non-empty.
        obviousRepetition lists clear rehashes of RECENT WRITTEN BEATS; use an empty array when none.
        """

    static let chapterPlanProposalJSONContract = """
        Output contract: NovelChapterPlanProposalV1, schemaVersion 1.
        Return exactly one raw JSON object. Do not use Markdown fences, comments, or trailing prose.
        Every key shown below is required. Do not add unknown keys at any level.
        Root shape:
        {
          "schemaVersion":1,
          "outlinePlacement":"string (may be empty)",
          "goalAndConflict":"non-empty string",
          "mustHappen":["non-empty string"],
          "mustNotHappen":[],
          "endingHook":"string (may be empty)",
          "visibleFacts":[]
        }
        mustHappen must contain at least one non-empty string. mustNotHappen and visibleFacts are arrays of
        non-empty strings when present; use empty arrays when none.
        """
}
