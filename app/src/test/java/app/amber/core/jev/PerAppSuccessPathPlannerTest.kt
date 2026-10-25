package app.amber.core.jev

import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppSuccessPathPlannerTest {

    @Test
    fun reviewGuidancePreservesBothScrollDirectionsAndPromotesMatchingKind() {
        val snap = snapshot("fresh", node("list", "Items", viewId = "id/list").copy(scrollable = true))
        val live = listOf(
            Cand("Forward", ScreenAction(ScreenActionKind.SCROLL_FORWARD, "list")),
            Cand("Backward", ScreenAction(ScreenActionKind.SCROLL_BACKWARD, "list")),
        )
        val path = remembered().copy(steps = listOf(PerAppSuccessPathStep("scroll_backward", "Items", "id/list")))
        val guided = PerAppSuccessPathPlanner.guide(
            "com.example.app", snap, listOf(path), "open the draft", live,
            identityOf = { identity(it) },
        )
        assertEquals("guidance may reorder but cannot remove live candidates", live.toSet(), guided.candidates.toSet())
        assertEquals(ScreenActionKind.SCROLL_BACKWARD, guided.candidates.first().action.kind)
    }

    private data class Cand(val label: String, val action: ScreenAction)

    private fun identity(cand: Cand) = PerAppCandidateIdentity(
        kind = cand.action.kind,
        ref = cand.action.ref,
        text = cand.action.text,
    )

    private fun node(
        ref: String,
        label: String,
        viewId: String = "id/$ref",
        clickable: Boolean = true,
        enabled: Boolean = true,
        editable: Boolean = false,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = viewId,
        className = "android.view.View",
        left = 0,
        top = 0,
        right = 40,
        bottom = 20,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = enabled,
    )

    private fun snapshot(id: String, vararg nodes: ScreenNode) = ScreenSnapshot(
        id = id,
        packageName = "com.example.app",
        windowId = 1,
        nodes = nodes.toList(),
    )

    private fun remembered(label: String = "Open draft", viewId: String = "id/open"): PerAppSuccessPath =
        PerAppSuccessPath(
            packageName = "com.example.app",
            goalFingerprint = PerAppSuccessPathSanitizer.fingerprint("com.example.app", "open the draft"),
            goalHint = "open the draft",
            steps = listOf(PerAppSuccessPathStep(kind = "click", label = label, semanticId = viewId)),
            recordedAtMs = 1,
        )

    @Test
    fun uniqueTargetResolvesToFreshRef() {
        val snap = snapshot(
            "snap-new",
            node("old-should-not-appear", "Other", viewId = "id/other"),
            node("fresh-open", "Open draft", viewId = "id/open"),
        )
        val hints = PerAppSuccessPathPlanner.resolveUniqueHints(
            packageName = "com.example.app",
            snapshot = snap,
            remembered = listOf(remembered()),
            goal = "open the draft",
        )
        assertEquals(listOf("fresh-open"), hints.map { it.freshRef })
        assertTrue(hints.none { it.freshRef.contains("old") })
    }

    @Test
    fun ambiguousTargetsProduceNoHint() {
        val snap = snapshot(
            "snap-amb",
            node("a", "Open draft", viewId = "id/open"),
            node("b", "Open draft", viewId = "id/open"),
        )
        val hints = PerAppSuccessPathPlanner.resolveUniqueHints(
            "com.example.app",
            snap,
            listOf(remembered()),
            "open the draft",
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun pageDriftFallsBack() {
        val snap = snapshot("snap-drift", node("home", "Home", viewId = "id/home"))
        val hints = PerAppSuccessPathPlanner.resolveUniqueHints(
            "com.example.app",
            snap,
            listOf(remembered()),
            "open the draft",
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun packageMismatchAndDisabledNodesDoNotResolve() {
        val otherPkg = snapshot("snap-pkg", node("fresh-open", "Open draft")).copy(packageName = "com.other.app")
        assertTrue(
            PerAppSuccessPathPlanner.resolveUniqueHints(
                "com.example.app",
                otherPkg,
                listOf(remembered()),
                "open the draft",
            ).isEmpty(),
        )
        val disabled = snapshot("snap-dis", node("fresh-open", "Open draft", enabled = false))
        assertTrue(
            PerAppSuccessPathPlanner.resolveUniqueHints(
                "com.example.app",
                disabled,
                listOf(remembered()),
                "open the draft",
            ).isEmpty(),
        )
    }

    @Test
    fun guideOnlyReordersLiveCandidatesAndNeverInventClicks() {
        val snap = snapshot(
            "snap-guide",
            node("other", "Other", viewId = "id/other"),
            node("fresh-open", "Open draft", viewId = "id/open"),
        )
        val live = listOf(
            Cand("Click Other", ScreenAction(ScreenActionKind.CLICK, "other")),
            Cand("Click Open draft", ScreenAction(ScreenActionKind.CLICK, "fresh-open")),
        )
        val guided = PerAppSuccessPathPlanner.guide(
            packageName = "com.example.app",
            snapshot = snap,
            remembered = listOf(remembered()),
            goal = "open the draft",
            candidates = live,
            identityOf = { identity(it) },
        )
        assertEquals(listOf("fresh-open", "other"), guided.candidates.map { it.action.ref })
        assertTrue(guided.hint!!.contains("Open draft"))

        val blockedOnly = listOf(Cand("Click Other", ScreenAction(ScreenActionKind.CLICK, "other")))
        val noInject = PerAppSuccessPathPlanner.guide(
            packageName = "com.example.app",
            snapshot = snap,
            remembered = listOf(remembered()),
            goal = "open the draft",
            candidates = blockedOnly,
            identityOf = { identity(it) },
        )
        assertEquals(listOf("other"), noInject.candidates.map { it.action.ref })
        assertNull(noInject.hint)
    }

    @Test
    fun sameRefClickAndTypePromoteMatchingKindAndKeepTheOther() {
        val snap = snapshot(
            "fresh",
            node("field", "Search", viewId = "id/search", clickable = true, editable = true),
        )
        val live = listOf(
            Cand("Click Search", ScreenAction(ScreenActionKind.CLICK, "field")),
            Cand("Type alpha", ScreenAction(ScreenActionKind.TYPE, "field", "alpha")),
        )
        val clickPath = remembered(label = "Search", viewId = "id/search")
        val clickGuided = PerAppSuccessPathPlanner.guide(
            "com.example.app", snap, listOf(clickPath), "open the draft", live,
            identityOf = { identity(it) },
        )
        assertEquals(live.toSet(), clickGuided.candidates.toSet())
        assertEquals(ScreenActionKind.CLICK, clickGuided.candidates.first().action.kind)
        assertEquals("alpha", clickGuided.candidates.last().action.text)

        val typePath = remembered(label = "Search", viewId = "id/search").copy(
            steps = listOf(PerAppSuccessPathStep("type", "Search", "id/search")),
        )
        val typeGuided = PerAppSuccessPathPlanner.guide(
            "com.example.app", snap, listOf(typePath), "open the draft", live,
            identityOf = { identity(it) },
        )
        assertEquals(live.toSet(), typeGuided.candidates.toSet())
        assertEquals(ScreenActionKind.TYPE, typeGuided.candidates.first().action.kind)
        assertEquals("alpha", typeGuided.candidates.first().action.text)
        assertEquals(ScreenActionKind.CLICK, typeGuided.candidates.last().action.kind)
    }

    @Test
    fun multipleTypedTextsOnSameRefStayAndDoNotGuide() {
        val snap = snapshot(
            "fresh",
            node("field", "Search", viewId = "id/search", clickable = false, editable = true),
        )
        val live = listOf(
            Cand("Type alpha", ScreenAction(ScreenActionKind.TYPE, "field", "alpha")),
            Cand("Type beta", ScreenAction(ScreenActionKind.TYPE, "field", "beta")),
        )
        val path = remembered(label = "Search", viewId = "id/search").copy(
            steps = listOf(PerAppSuccessPathStep("type", "Search", "id/search")),
        )
        val guided = PerAppSuccessPathPlanner.guide(
            "com.example.app", snap, listOf(path), "open the draft", live,
            identityOf = { identity(it) },
        )
        assertEquals(live, guided.candidates)
        assertNull(guided.hint)
        assertEquals(listOf("alpha", "beta"), guided.candidates.map { it.action.text })
    }

    @Test
    fun missingRememberedKindKeepsOriginalOrder() {
        val snap = snapshot("fresh", node("list", "Items", viewId = "id/list").copy(scrollable = true))
        val live = listOf(
            Cand("Click Items", ScreenAction(ScreenActionKind.CLICK, "list")),
            Cand("Forward", ScreenAction(ScreenActionKind.SCROLL_FORWARD, "list")),
        )
        val path = remembered().copy(steps = listOf(PerAppSuccessPathStep("scroll_backward", "Items", "id/list")))
        val guided = PerAppSuccessPathPlanner.guide(
            "com.example.app", snap, listOf(path), "open the draft", live,
            identityOf = { identity(it) },
        )
        assertEquals(live, guided.candidates)
        assertNull(guided.hint)
    }

    @Test
    fun packageScopeDoesNotGuideForeignMemory() {
        val snap = snapshot("fresh", node("open", "Open draft", viewId = "id/open"))
        val live = listOf(
            Cand("Click Other", ScreenAction(ScreenActionKind.CLICK, "other")),
            Cand("Click Open draft", ScreenAction(ScreenActionKind.CLICK, "open")),
        )
        val foreign = remembered().copy(packageName = "com.other.app")
        val guided = PerAppSuccessPathPlanner.guide(
            "com.example.app", snap, listOf(foreign), "open the draft", live,
            identityOf = { identity(it) },
        )
        assertEquals(live, guided.candidates)
        assertNull(guided.hint)
    }
}
