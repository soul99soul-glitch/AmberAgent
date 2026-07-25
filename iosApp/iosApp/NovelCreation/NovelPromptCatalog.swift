import Foundation

enum NovelPromptKind: String, Codable, CaseIterable, Sendable {
    case quickStart
    case discussion
    case proseContinuation
    case proseWholeChapter
    case stateDeltaV1
    case manualSyncV1
    case discussionArchiveV1
    case wholeChapterPolish
    case polishDriftV1
}

struct NovelPromptTemplate: Codable, Equatable, Sendable {
    let kind: NovelPromptKind
    let version: String
    let systemText: String
}

enum NovelPromptCatalog {
    static let polishCompletionSentinel = "<AMBER_NOVEL_POLISH_COMPLETE>"

    static func template(for kind: NovelPromptKind) -> NovelPromptTemplate {
        switch kind {
        case .quickStart:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.quick-start.v4",
                systemText: """
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
            )

        case .discussion:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.discussion.v3",
                systemText: """
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
            )

        case .proseContinuation:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.prose-continuation.v2",
                systemText: """
                Write one focused scene or passage that can be appended to the current chapter. Preserve all
                supplied project rules, established branch facts, character motivations, point of view, and tone.
                Continue naturally from the current manuscript tail without recapping or explaining it. Complete
                one meaningful scene beat, exchange, discovery, or action sequence, then stop at a natural local
                beat. Do not close the chapter or manufacture a chapter ending unless the user explicitly asks.
                Return only polished candidate prose as one complete response, with no analysis, preface, title,
                or afterword. This output is a draft candidate and does not become canonical until the user
                collects it. Use the user's language.
                """
            )

        case .proseWholeChapter:
            NovelPromptTemplate(
                kind: kind,
                version: "novel.prose-whole-chapter.v3",
                systemText: """
                Write one complete next chapter with a coherent chapter-level arc: an opening grounded in the
                prior chapter, sustained development through connected scenes or beats, a meaningful change, and
                an ending beat or hook. Preserve all supplied project rules, established branch facts, character
                motivations, point of view, and tone. Continue from the prior chapter without recapping or
                rewriting it. Do not stop after a single short scene unless the user explicitly requests a short
                chapter. Begin with one concise Markdown H1 chapter heading that names this chapter, followed by
                the full polished chapter candidate. Return no analysis, preface, or afterword. This output is a
                draft candidate and does not become canonical until the user collects it. Use the user's language.
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
                version: "novel.whole-chapter-polish.v2",
                systemText: """
                Polish the complete supplied chapter while preserving its story facts exactly. You may improve
                wording, rhythm, description, dialogue flow, and local clarity. You must not add, remove, reorder,
                merge, or split story events; change relationships, motivations, secrets, outcomes, chronology,
                point of view, or ending; or introduce new facts. Project polish preferences are subordinate to
                these fixed constraints and must be ignored whenever they conflict. Return the complete polished
                chapter as one response, then append a final line containing exactly
                \(polishCompletionSentinel). Do not emit that sentinel anywhere else. It remains a draft candidate
                until explicitly adopted.
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
        }
    }

    static func systemText(for kind: NovelPromptKind, version: String) -> String? {
        let current = template(for: kind)
        if current.version == version { return current.systemText }

        return switch (kind, version) {
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
        case (.proseContinuation, "novel.prose-continuation.v1"):
            """
            Write one polished prose continuation that can be appended to the current chapter. Preserve all
            supplied project rules and established branch facts. Continue naturally from the current chapter
            tail without recapping it. Return only the candidate prose as one complete response. This output
            is a draft candidate and does not become canonical until the user collects it.
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
        return content
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
}
