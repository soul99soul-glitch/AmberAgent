import XCTest
@testable import iosApp

final class NovelStructuredOutputTests: XCTestCase {
    func testQuickStartSuggestionsRequireIndependentCharacterSections() throws {
        let valid = """
        {
          "schemaVersion": 2,
          "overview": "A clear direction.",
          "world": {"title": "World", "content": "Rules"},
          "characters": [
            {"title": "Mara", "content": "An advocate who risks her memories."},
            {"title": "Ivo", "content": "A witness hiding the first trade."}
          ],
          "masterOutline": {"title": "Outline", "content": "Arc"},
          "writingRequirements": {"title": "Style", "content": "Voice"}
        }
        """
        let decoded = try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(from: valid)
        XCTAssertEqual(decoded.world.title, "World")
        XCTAssertEqual(decoded.characters.map(\.title), ["Mara", "Ivo"])

        assertFailure(
            category: .missingField,
            try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(
                from: valid.replacingOccurrences(
                    of: ",\n  \"writingRequirements\": {\"title\": \"Style\", \"content\": \"Voice\"}",
                    with: ""
                )
            )
        )
        assertFailure(
            category: .unknownField,
            try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(
                from: valid.replacingOccurrences(
                    of: "\"overview\": \"A clear direction.\"",
                    with: "\"overview\": \"A clear direction.\", \"extra\": true"
                )
            )
        )
        assertFailure(
            category: .invalidValue,
            try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(
                from: valid.replacingOccurrences(
                    of: "\"content\": \"Rules\"",
                    with: "\"content\": \"   \""
                )
            )
        )
        assertFailure(
            category: .invalidValue,
            try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(
                from: valid.replacingOccurrences(
                    of: "[\n    {\"title\": \"Mara\", \"content\": \"An advocate who risks her memories.\"},\n    {\"title\": \"Ivo\", \"content\": \"A witness hiding the first trade.\"}\n  ]",
                    with: "[]"
                )
            )
        )
    }

    func testQuickStartDecoderKeepsLegacySingleCharacterOutputCompatible() throws {
        let legacy = """
        {
          "schemaVersion": 1,
          "overview": "A clear direction.",
          "world": {"title": "World", "content": "Rules"},
          "characters": {"title": "Mara", "content": "An advocate."},
          "masterOutline": {"title": "Outline", "content": "Arc"},
          "writingRequirements": {"title": "Style", "content": "Voice"}
        }
        """

        let decoded = try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(from: legacy)

        XCTAssertEqual(decoded.characters.map(\.title), ["Mara"])
    }

    func testStateDeltaDecodesCompleteVersionedPayload() throws {
        let decoded = try NovelStructuredOutputDecoder.decodeStateDelta(
            from: try data(deltaObject())
        )

        XCTAssertEqual(decoded.schemaVersion, 1)
        XCTAssertEqual(decoded.events.map(\.id), ["event-1"])
        XCTAssertEqual(decoded.characterChanges.map(\.characterName), ["Lin"])
        XCTAssertEqual(decoded.relationshipChanges.map(\.targetEntity), ["Mara"])
        XCTAssertEqual(decoded.foreshadowingChanges.map(\.status), [.introduced])
        XCTAssertEqual(decoded.unresolvedEntityNames, ["The Bell Keeper"])
        XCTAssertEqual(decoded.branchOutlinePatch, "Lin now owes Mara an answer.")
        XCTAssertEqual(decoded.settingProposals.map(\.id), ["proposal-1"])
    }

    func testRejectsMalformedJSONAndNonObjectRootWithClassifiedMessages() throws {
        assertFailure(
            category: .malformedJSON,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: "{not-json")
        )
        assertFailure(
            category: .expectedObject,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data([]))
        )
    }

    func testRejectsDuplicateKeysAtRootAndNestedObjectBoundaries() {
        let duplicateCompatible = """
        {
          "schemaVersion": 1,
          "compatible": true,
          "compatible": false,
          "differences": []
        }
        """
        assertFailure(
            category: .duplicateKey,
            try NovelStructuredOutputDecoder.decodePolishDrift(from: duplicateCompatible)
        )
        XCTAssertFalse(
            NovelStructuredOutputDecoder.polishDriftVerdict(from: duplicateCompatible).allowsAdoption
        )

        let duplicateNestedCategory = """
        {
          "schemaVersion": 1,
          "compatible": false,
          "differences": [{
            "id": "difference-1",
            "category": "ending",
            "category": "event",
            "summary": "Changed fact",
            "sourceEvidence": "Before",
            "candidateEvidence": "After"
          }]
        }
        """
        assertFailure(
            category: .duplicateKey,
            try NovelStructuredOutputDecoder.decodePolishDrift(from: duplicateNestedCategory)
        )

        let escapedDuplicate = """
        {
          "schemaVersion": 1,
          "compatible": true,
          "differ\\u0065nces": [],
          "differences": []
        }
        """
        assertFailure(
            category: .duplicateKey,
            try NovelStructuredOutputDecoder.decodePolishDrift(from: escapedDuplicate)
        )
    }

    func testRejectsUnknownAndMissingFieldsAtEveryObjectBoundary() throws {
        var unknown = deltaObject()
        var events = try XCTUnwrap(unknown["events"] as? [[String: Any]])
        events[0]["invented"] = true
        unknown["events"] = events
        assertFailure(
            category: .unknownField,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(unknown))
        )

        var missing = deltaObject()
        missing.removeValue(forKey: "stateSummary")
        assertFailure(
            category: .missingField,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(missing))
        )
    }

    func testRejectsWrongTypeUnsupportedVersionAndEmptyRequiredValue() throws {
        var wrongType = deltaObject()
        wrongType["events"] = "not-an-array"
        assertFailure(
            category: .typeMismatch,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(wrongType))
        )

        var future = deltaObject()
        future["schemaVersion"] = 2
        assertFailure(
            category: .unsupportedVersion,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(future))
        )

        var empty = deltaObject()
        empty["stateSummary"] = " \n "
        assertFailure(
            category: .invalidValue,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(empty))
        )
    }

    func testRejectsDuplicateIdentifiersAndInvalidEntityReferences() throws {
        var duplicateID = deltaObject()
        var characters = try XCTUnwrap(
            duplicateID["characterChanges"] as? [[String: Any]]
        )
        characters[0]["id"] = "event-1"
        duplicateID["characterChanges"] = characters
        assertFailure(
            category: .duplicateIdentifier,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(duplicateID))
        )

        var duplicateReference = deltaObject()
        var events = try XCTUnwrap(duplicateReference["events"] as? [[String: Any]])
        events[0]["entityReferences"] = ["Lin", "lin"]
        duplicateReference["events"] = events
        assertFailure(
            category: .invalidReference,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(duplicateReference))
        )

        var selfRelationship = deltaObject()
        var relationships = try XCTUnwrap(
            selfRelationship["relationshipChanges"] as? [[String: Any]]
        )
        relationships[0]["targetEntity"] = "lin"
        selfRelationship["relationshipChanges"] = relationships
        assertFailure(
            category: .invalidReference,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(selfRelationship))
        )
    }

    func testRejectsIllegalEnumValueAndInvalidIdentifier() throws {
        var illegalStatus = deltaObject()
        var threads = try XCTUnwrap(
            illegalStatus["foreshadowingChanges"] as? [[String: Any]]
        )
        threads[0]["status"] = "forgotten"
        illegalStatus["foreshadowingChanges"] = threads
        assertFailure(
            category: .invalidValue,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(illegalStatus))
        )

        var invalidID = deltaObject()
        var events = try XCTUnwrap(invalidID["events"] as? [[String: Any]])
        events[0]["id"] = "event with spaces"
        invalidID["events"] = events
        assertFailure(
            category: .invalidValue,
            try NovelStructuredOutputDecoder.decodeStateDelta(from: try data(invalidID))
        )
    }

    func testManualRebuildDecodesFullReplacementPayloadStrictly() throws {
        let decoded = try NovelStructuredOutputDecoder.decodeStateRebuild(
            from: try data(rebuildObject())
        )

        XCTAssertEqual(decoded.stateSummary, "Lin has entered the archive.")
        XCTAssertEqual(decoded.branchOutline, "The archive investigation is active.")
        XCTAssertEqual(decoded.events.map(\.id), ["event-rebuilt-1"])
        XCTAssertEqual(decoded.characterStates.map(\.id), ["character-rebuilt-1"])

        var unknown = rebuildObject()
        var relationships = try XCTUnwrap(unknown["relationships"] as? [[String: Any]])
        relationships[0]["confidence"] = 0.8
        unknown["relationships"] = relationships
        assertFailure(
            category: .unknownField,
            try NovelStructuredOutputDecoder.decodeStateRebuild(from: try data(unknown))
        )
    }

    func testManualRebuildAcceptsCommonModelJSONWrappers() throws {
        var object = rebuildObject()
        object["stateSummary"] = "Lin entered the {sealed} archive."
        let json = try XCTUnwrap(String(data: try data(object), encoding: .utf8))

        let fenced = try NovelStructuredOutputDecoder.decodeStateRebuild(
            from: "```json\n\(json)\n```"
        )
        let explained = try NovelStructuredOutputDecoder.decodeStateRebuild(
            from: "好的，以下是整理结果：\n\(json)\n以上为本次同步。"
        )

        XCTAssertEqual(fenced.stateSummary, "Lin entered the {sealed} archive.")
        XCTAssertEqual(explained.events.map(\.id), ["event-rebuilt-1"])
    }

    func testStructuredDecoderRejectsMultipleJSONObjects() throws {
        let json = try XCTUnwrap(
            String(data: try data(rebuildObject()), encoding: .utf8)
        )

        assertFailure(
            category: .malformedJSON,
            try NovelStructuredOutputDecoder.decodeStateRebuild(
                from: "\(json)\n\(json)"
            )
        )
    }

    func testWrappedPayloadStillUsesStrictSchemaValidation() throws {
        var unknown = rebuildObject()
        unknown["invented"] = true
        let json = try XCTUnwrap(String(data: try data(unknown), encoding: .utf8))

        assertFailure(
            category: .unknownField,
            try NovelStructuredOutputDecoder.decodeStateRebuild(
                from: "Result:\n```json\n\(json)\n```"
            )
        )
    }

    func testPolishDriftAcceptsConsistentCompatibleAndIncompatiblePayloads() throws {
        let compatible = try NovelStructuredOutputDecoder.decodePolishDrift(
            from: try data(driftObject(compatible: true, differences: []))
        )
        XCTAssertTrue(compatible.compatible)
        XCTAssertEqual(
            NovelStructuredOutputDecoder.polishDriftVerdict(
                from: try data(driftObject(compatible: true, differences: []))
            ),
            .compatible
        )

        let difference = polishDifference()
        let verdict = NovelStructuredOutputDecoder.polishDriftVerdict(
            from: try data(driftObject(compatible: false, differences: [difference]))
        )
        guard case .incompatible(let differences) = verdict else {
            return XCTFail("Expected an incompatible drift verdict")
        }
        XCTAssertEqual(differences.map(\.category), [.ending])
        XCTAssertFalse(verdict.allowsAdoption)
    }

    func testPolishDriftValidationAndParseFailuresAlwaysFailClosed() throws {
        let malformed = NovelStructuredOutputDecoder.polishDriftVerdict(from: "not-json")
        guard case .invalidOutput(let malformedFailure) = malformed else {
            return XCTFail("Malformed drift output must fail closed")
        }
        XCTAssertEqual(malformedFailure.category, .malformedJSON)
        XCTAssertFalse(malformed.allowsAdoption)

        let contradictory = NovelStructuredOutputDecoder.polishDriftVerdict(
            from: try data(driftObject(
                compatible: true,
                differences: [polishDifference()]
            ))
        )
        guard case .invalidOutput(let validationFailure) = contradictory else {
            return XCTFail("Contradictory drift output must fail closed")
        }
        XCTAssertEqual(validationFailure.category, .invalidValue)
        XCTAssertFalse(contradictory.allowsAdoption)

        let unsupportedCategory = NovelStructuredOutputDecoder.polishDriftVerdict(
            from: """
            {
              "schemaVersion": 1,
              "compatible": false,
              "differences": [{
                "id": "difference-1",
                "category": "style-only",
                "summary": "Changed ending",
                "sourceEvidence": "The door stayed closed.",
                "candidateEvidence": "The door opened."
              }]
            }
            """
        )
        XCTAssertFalse(unsupportedCategory.allowsAdoption)
        guard case .invalidOutput(let categoryFailure) = unsupportedCategory else {
            return XCTFail("Unknown drift categories must fail closed")
        }
        XCTAssertEqual(categoryFailure.category, .invalidValue)
        XCTAssertFalse(categoryFailure.localizedDescription.isEmpty)
    }
}

private extension NovelStructuredOutputTests {
    func assertFailure<T>(
        category: NovelStructuredOutputErrorCategory,
        _ expression: @autoclosure () throws -> T,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        do {
            _ = try expression()
            XCTFail("Expected structured output decoding to fail", file: file, line: line)
        } catch let failure as NovelStructuredOutputFailure {
            XCTAssertEqual(failure.category, category, file: file, line: line)
            XCTAssertFalse(failure.localizedDescription.isEmpty, file: file, line: line)
            XCTAssertFalse(failure.path.isEmpty, file: file, line: line)
        } catch {
            XCTFail("Unexpected error: \(error)", file: file, line: line)
        }
    }

    func data(_ object: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    func deltaObject() -> [String: Any] {
        [
            "schemaVersion": 1,
            "stateSummary": "Lin heard the archive bell and accepted Mara's help.",
            "events": [[
                "id": "event-1",
                "kind": "discovery",
                "summary": "Lin heard the archive bell.",
                "entityReferences": ["Lin", "The Bell Keeper"],
                "evidence": "A bell rang beneath the locked archive."
            ]],
            "characterChanges": [[
                "id": "character-1",
                "characterName": "Lin",
                "attribute": "goal",
                "value": "Find the hidden archive entrance.",
                "evidence": "Lin promised to search before dawn."
            ]],
            "relationshipChanges": [[
                "id": "relationship-1",
                "sourceEntity": "Lin",
                "targetEntity": "Mara",
                "relationship": "trust",
                "state": "cautious alliance",
                "evidence": "Lin accepted Mara's map."
            ]],
            "foreshadowingChanges": [[
                "id": "thread-1",
                "thread": "The archive bell",
                "status": "introduced",
                "summary": "The bell rings despite the sealed archive.",
                "evidence": "The second ring came from below."
            ]],
            "unresolvedEntityNames": ["The Bell Keeper"],
            "branchOutlinePatch": "Lin now owes Mara an answer.",
            "settingProposals": [[
                "id": "proposal-1",
                "title": "Archive bell rule",
                "content": "Consider defining who can hear the archive bell.",
                "evidence": "Only Lin reacted to both rings."
            ]]
        ]
    }

    func rebuildObject() -> [String: Any] {
        [
            "schemaVersion": 1,
            "stateSummary": "Lin has entered the archive.",
            "branchOutline": "The archive investigation is active.",
            "events": [[
                "id": "event-rebuilt-1",
                "kind": "entry",
                "summary": "Lin entered the archive.",
                "entityReferences": ["Lin"],
                "evidence": "Lin crossed the brass threshold."
            ]],
            "characterStates": [[
                "id": "character-rebuilt-1",
                "characterName": "Lin",
                "attribute": "location",
                "value": "Hidden archive",
                "evidence": "Lin crossed the brass threshold."
            ]],
            "relationships": [[
                "id": "relationship-rebuilt-1",
                "sourceEntity": "Lin",
                "targetEntity": "Mara",
                "relationship": "trust",
                "state": "uneasy alliance",
                "evidence": "Mara waited outside as promised."
            ]],
            "foreshadowing": [[
                "id": "thread-rebuilt-1",
                "thread": "The archive bell",
                "status": "advanced",
                "summary": "The bell stopped when Lin entered.",
                "evidence": "Silence followed the threshold crossing."
            ]],
            "unresolvedEntityNames": [],
            "settingProposals": []
        ]
    }

    func driftObject(
        compatible: Bool,
        differences: [[String: Any]]
    ) -> [String: Any] {
        [
            "schemaVersion": 1,
            "compatible": compatible,
            "differences": differences
        ]
    }

    func polishDifference() -> [String: Any] {
        [
            "id": "difference-1",
            "category": "ending",
            "summary": "The polished chapter opens the sealed door.",
            "sourceEvidence": "The door stayed closed.",
            "candidateEvidence": "The door opened."
        ]
    }
}
