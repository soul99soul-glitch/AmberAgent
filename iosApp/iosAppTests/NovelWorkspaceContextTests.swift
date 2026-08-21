import Foundation
import XCTest
@testable import iosApp

/// Phase 3 workspace-consistency contract: per-chapter plot modules on disk,
/// the four-section brief, and its injection through the real planner.
final class NovelWorkspaceContextTests: XCTestCase {

    // MARK: - Plot modules on disk

    func testPlotModulesPrintToTreeAndRoundTrip() throws {
        var document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let chapterID = document.branches[0].workingChapterSelections[0].chapterID
        if let index = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) {
            let old = document.stateSnapshots[index]
            document.stateSnapshots[index] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: old.summary,
                branchOutline: old.branchOutline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: old.recentWrittenHighlights,
                chapterPlots: [
                    NovelChapterPlotModule(
                        chapterID: chapterID,
                        text: "山呼：陈桥驿的风先到，军心已附。",
                        stale: true
                    ),
                ]
            )
        }

        let files = try NovelWorkspaceBackup.export(document)
        let moduleFile = try XCTUnwrap(files.first {
            $0.path.contains("/plot/chapters/") && $0.path.hasSuffix(".md")
        }, "plot module file must be printed")
        XCTAssertTrue(moduleFile.contents.contains("stale: true"))
        XCTAssertTrue(moduleFile.contents.contains("山呼：陈桥驿的风先到，军心已附。"))

        // Round trip: the importer preserves the module text and stale flag
        // instead of reseeding from chapter text.
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)
        let snapshot = try XCTUnwrap(imported.stateSnapshots.first {
            $0.id == imported.branches[0].currentStateSnapshotID
        })
        // The importer remaps ids; match by single-module structure.
        XCTAssertEqual(snapshot.chapterPlots.count, 1)
        XCTAssertEqual(snapshot.chapterPlots[0].text, "山呼：陈桥驿的风先到，军心已附。")
        XCTAssertTrue(snapshot.chapterPlots[0].stale, "D-D stale flag must round-trip")
    }

    // MARK: - Brief assembly

    func testBriefAssemblesSectionsAndBudget() throws {
        var document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        document.chapterPlans = []
        document.upcomingArcs = []
        // An always material the plan mentions + one it doesn't.
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                materialID: NovelMaterialID(),
                revisionID: NovelMaterialRevisionID(),
                kind: .character,
                title: "赵大",
                content: "赵匡胤坐在马上，掌禁军。",
                tags: [],
                injectionMode: .always,
                aliases: ["点检"]
            )),
            to: document
        ).document
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                materialID: NovelMaterialID(),
                revisionID: NovelMaterialRevisionID(),
                kind: .world,
                title: "军制",
                content: String(repeating: "禁军诸班直。", count: 260),
                tags: [],
                injectionMode: .always,
                aliases: ["班直"]
            )),
            to: document
        ).document
        let plan = NovelChapterPlanRecord(
            id: NovelChapterPlanID(),
            branchID: document.branches[0].id,
            status: .confirmed,
            outlinePlacement: "第 2 章",
            goalAndConflict: "赵大要在军中立足",
            mustHappen: ["点检出面"],
            mustNotHappen: ["提前入汴"],
            endingHook: "军士呼喊",
            visibleFacts: [],
            contentDigest: "",
            updatedAt: document.project.updatedAt,
            confirmedAt: document.project.updatedAt
        )
        document.chapterPlans.append(plan)
        // An open foreshadowing node in the passthrough area.
        document.workspacePassthrough.opaqueFiles["branches/main/plot/foreshadowing/yellow-robe.md"] =
            "---\nkind: foreshadowing\ntitle: 黄袍\nstatus: open\n---\n\n军中有人私藏黄袍。"

        let branch = document.branches[0]
        let state = try XCTUnwrap(document.stateSnapshots.first {
            $0.id == branch.currentStateSnapshotID
        })
        let brief = NovelWorkspaceContextAssembler.brief(
            document: document,
            state: state,
            branch: branch,
            characterIdentities: [],
            includeUnsynchronizedWarning: false
        )

        XCTAssertTrue(brief.contains("## 当前剧情状态"))
        XCTAssertTrue(brief.contains("## 未回收伏笔"))
        XCTAssertTrue(brief.contains("黄袍"))
        XCTAssertTrue(brief.contains("## 本章相关节点"))
        XCTAssertTrue(brief.contains("赵大（点检）"), "plan-matching material card with aliases")
        XCTAssertTrue(brief.contains("## 已确认决定"))
        XCTAssertTrue(brief.contains("赵大"))

        // Budget from the tail: a big outline (~4500 chars) plus a fat
        // material card pushes sections past the budget; the canonical
        // section 1 stays intact while tail sections drop.
        var huge = document
        if let index = huge.stateSnapshots.firstIndex(where: { $0.id == branch.currentStateSnapshotID }) {
            let old = huge.stateSnapshots[index]
            huge.stateSnapshots[index] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: old.summary,
                branchOutline: String(repeating: "北征。", count: 1950),
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: old.recentWrittenHighlights,
                chapterPlots: old.chapterPlots
            )
        }
        let hugeState = try XCTUnwrap(huge.stateSnapshots.first {
            $0.id == branch.currentStateSnapshotID
        })
        let budgeted = NovelWorkspaceContextAssembler.brief(
            document: huge,
            state: hugeState,
            branch: branch,
            characterIdentities: [],
            includeUnsynchronizedWarning: false
        )
        XCTAssertTrue(budgeted.contains("## 当前剧情状态"), "section 1 never dropped")
        XCTAssertFalse(budgeted.contains("## 已确认决定"), "tail sections dropped at budget")
        XCTAssertLessThanOrEqual(budgeted.count, NovelWorkspaceContextAssembler.budget + 200)
    }

    // MARK: - Injection wiring

    func testPlannedInjectionCarriesTheBrief() throws {
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let request = NovelInjectionPlanningRequest(
            branchID: document.branches[0].id,
            promptKind: .discussion,
            userText: "下一章怎么走？",
            overrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: [],
                forceExcludeMaterialIDs: []
            )
        )
        let plan = try NovelInjectionPlanner.plan(document: document, request: request)
        let stateSection = try XCTUnwrap(plan.sections.first {
            if case .currentState = $0.kind { return true }
            return false
        })
        XCTAssertEqual(stateSection.label, NovelWorkspaceContextAssembler.label)
        XCTAssertTrue(stateSection.content.contains("## 当前剧情状态"))
    }
}
