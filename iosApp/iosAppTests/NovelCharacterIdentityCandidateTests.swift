import XCTest
@testable import iosApp

/// Person-only identity-card eligibility: place/institution mentions must not
/// open 「确认人物身份」 (e.g. 澶州 from fact sync entityReferences).
final class NovelCharacterIdentityCandidateTests: XCTestCase {

    func testChineseToponymsAreNotCharacterIdentityCandidates() {
        let places = ["澶州", "汴京", "开封府", "东京", "西京", "河北路", "白马寺", "黄河渡"]
        for name in places {
            XCTAssertFalse(
                NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate(name),
                "\(name) is a place/institution, not a character identity card target"
            )
        }
    }

    func testPersonNamesRemainCharacterIdentityCandidates() {
        let people = ["赵匡胤", "赵京娘", "柴荣", "郭威", "沈砚", "赵光义", "京娘", "赵将军", "Mara", "Ivo"]
        for name in people {
            XCTAssertTrue(
                NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate(name),
                "\(name) must stay eligible for identity clarification"
            )
        }
    }

    func testPureJobTitlesAndCrowdLabelsAreNotCharacterIdentityCandidates() {
        let roles = ["军需官", "县令", "店小二", "殿前军需官", "路人", "众人", "捕快", "士卒"]
        for name in roles {
            XCTAssertFalse(
                NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate(name),
                "\(name) is a title/crowd label, not a person dossier target"
            )
        }
    }

    func testAmbiguousShortNamesAreNotOverFiltered() {
        // Do not treat person-name stems ending in 山/河 as places just because
        // those characters appear in geography.
        XCTAssertTrue(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate("金山"))
        XCTAssertTrue(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate("江澄"))
    }

    func testSingleCharacterAndBlankRejected() {
        XCTAssertFalse(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate("州"))
        XCTAssertFalse(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate("  "))
        XCTAssertFalse(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate("赵"))
    }

    func testRecommendedIdentityMatchPrefersSuffixAliasAndIgnoresPlaces() {
        let candidates = [
            (id: "a", title: "赵京娘", aliases: ["京娘"]),
            (id: "b", title: "赵匡胤", aliases: ["官家"]),
            (id: "c", title: "柴荣", aliases: [] as [String]),
        ]
        let match = NovelCharacterIdentityResolver.recommendedIdentityMatch(
            mention: "京娘",
            candidates: candidates
        )
        XCTAssertEqual(match?.id, "a")
        XCTAssertEqual(match?.title, "赵京娘")

        let noPlace = NovelCharacterIdentityResolver.recommendedIdentityMatch(
            mention: "澶州",
            candidates: candidates
        )
        XCTAssertNil(noPlace, "toponym must not get a character recommendation")

        let weak = NovelCharacterIdentityResolver.recommendedIdentityMatch(
            mention: "寄信人",
            candidates: candidates
        )
        XCTAssertNil(weak, "unrelated minor mention should not force a weak default")

        // Same surname alone must not force one-tap default (赵云 ≠ 赵匡胤).
        let sameSurnameOnly = NovelCharacterIdentityResolver.recommendedIdentityMatch(
            mention: "赵云",
            candidates: candidates
        )
        XCTAssertNil(sameSurnameOnly, "bare surname share must stay below recommendation floor")
    }

    func testCollectionDeltaDropsPlaceFromUnresolvedWhileKeepingPerson() throws {
        let document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let baseState = try XCTUnwrap(document.stateSnapshots.first)
        let manuscript = "赵匡胤自澶州起兵，柴荣按剑而立。"
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "赵匡胤与柴荣在澶州。",
            events: [
                NovelStateEventV1(
                    id: "e-place",
                    kind: "travel",
                    summary: "行至澶州",
                    entityReferences: ["澶州", "赵匡胤"],
                    evidence: "赵匡胤自澶州起兵，柴荣按剑而立。"
                )
            ],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["澶州", "赵匡胤", "柴荣"],
            branchOutlinePatch: "赵匡胤在澶州。",
            settingProposals: []
        )
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: delta,
            evidenceSource: manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertFalse(
            sanitized.unresolvedEntityNames.contains(where: {
                NovelCharacterIdentityResolver.normalize($0) ==
                    NovelCharacterIdentityResolver.normalize("澶州")
            }),
            "place must not enter unresolved character list: \(sanitized.unresolvedEntityNames)"
        )
        XCTAssertTrue(
            sanitized.unresolvedEntityNames.contains(where: {
                NovelCharacterIdentityResolver.normalize($0) ==
                    NovelCharacterIdentityResolver.normalize("赵匡胤")
            }) || sanitized.unresolvedEntityNames.contains(where: {
                NovelCharacterIdentityResolver.normalize($0) ==
                    NovelCharacterIdentityResolver.normalize("柴荣")
            }),
            "person mentions from evidence-backed refs should remain eligible: \(sanitized.unresolvedEntityNames)"
        )
    }
}
