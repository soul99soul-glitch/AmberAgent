package app.amber.core.jev

import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PerAppSuccessPathStoreTest {

    @Test
    fun reviewStandaloneCardNumberCannotBecomePersistableStep() {
        val step = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("card", "6222021234567890", viewId = "id/item"),
        )
        org.junit.Assert.assertNull("payment digits without a keyword must not be remembered", step)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(
        limits: PerAppSuccessPathLimits = PerAppSuccessPathLimits.DEFAULT,
        file: File = File(tmp.root, "jev/per_app_success_paths.json"),
    ) = FilePerAppSuccessPathStore(file, limits = limits)

    private fun node(
        ref: String,
        label: String,
        viewId: String = "id/$ref",
        clickable: Boolean = true,
        editable: Boolean = false,
        left: Int = 10,
        top: Int = 20,
        right: Int = 110,
        bottom: Int = 80,
        editableValue: String? = null,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = viewId,
        className = "android.widget.Button",
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = true,
        editableValue = editableValue,
    )

    private fun clickPath(
        packageName: String,
        label: String = "Open draft",
        ref: String = "open",
        recordedAtMs: Long = 1_000L,
        goal: String = "open the draft",
    ): PerAppSuccessPath {
        val step = PerAppSuccessPathSanitizer.stepFrom(
            kind = ScreenActionKind.CLICK,
            node = node(ref, label),
        )!!
        return PerAppSuccessPathSanitizer.buildPath(
            packageName = packageName,
            goal = goal,
            steps = listOf(step),
            recordedAtMs = recordedAtMs,
        )!!
    }

    @Test
    fun packageIsolationAndReopen() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        assertTrue(store.recordSuccess(0, clickPath("com.example.one", recordedAtMs = 1)))
        assertTrue(store.recordSuccess(0, clickPath("com.example.two", label = "Search", ref = "search", recordedAtMs = 2)))

        assertEquals(listOf("Open draft"), store.pathsFor("com.example.one").flatMap { it.steps.map { step -> step.label } })
        assertEquals(listOf("Search"), store.pathsFor("com.example.two").flatMap { it.steps.map { step -> step.label } })
        assertTrue(store.pathsFor("com.example.other").isEmpty())

        val reopened = store(file = file)
        assertEquals(0, reopened.currentEpoch())
        assertEquals("Open draft", reopened.pathsFor("com.example.one").single().steps.single().label)
        assertEquals("Search", reopened.pathsFor("com.example.two").single().steps.single().label)
    }

    @Test
    fun capacityDropsOldestPackagesAndPathsAndClipsSteps() {
        val limits = PerAppSuccessPathLimits(
            maxPackages = 2,
            maxPathsPerPackage = 2,
            maxStepsPerPath = 2,
            maxTextChars = 8,
            maxFileBytes = 64 * 1024,
        )
        val store = store(limits)
        assertTrue(store.recordSuccess(0, clickPath("com.example.a", recordedAtMs = 1)))
        assertTrue(store.recordSuccess(0, clickPath("com.example.b", recordedAtMs = 2)))
        assertTrue(store.recordSuccess(0, clickPath("com.example.c", recordedAtMs = 3)))
        assertTrue(store.pathsFor("com.example.a").isEmpty())
        assertEquals(1, store.pathsFor("com.example.b").size)
        assertEquals(1, store.pathsFor("com.example.c").size)

        val longLabel = "Open draft extra text"
        val manySteps = listOf(longLabel, "two", "three").mapIndexed { index, label ->
            PerAppSuccessPathSanitizer.stepFrom(
                ScreenActionKind.CLICK,
                node("n$index", label, viewId = "id/btn$index"),
            )!!
        }
        val packed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.b",
            goal = "keep going",
            steps = manySteps,
            recordedAtMs = 4,
            limits = limits,
        )!!
        assertEquals(2, packed.steps.size)
        assertTrue(packed.steps.first().label.length <= 8)
        assertTrue(store.recordSuccess(0, packed))
        assertTrue(store.recordSuccess(0, clickPath("com.example.b", label = "Later", ref = "later", recordedAtMs = 5)))
        assertEquals(2, store.pathsFor("com.example.b").size)
        assertEquals("Later", store.pathsFor("com.example.b").first().steps.first().label)
    }

    @Test
    fun diskOmitsCoordsRefsAndSensitiveOriginals() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        val typeNode = node(
            ref = "n0_10_20_110_80",
            label = "Search",
            viewId = "id/search",
            clickable = false,
            editable = true,
            editableValue = "hunter2-secret-otp",
        )
        val typeStep = PerAppSuccessPathSanitizer.stepFrom(
            kind = ScreenActionKind.TYPE,
            node = typeNode,
            typedValue = "hunter2-secret-otp",
        )
        assertTrue(typeStep == null)

        val safeType = PerAppSuccessPathSanitizer.stepFrom(
            kind = ScreenActionKind.TYPE,
            node = typeNode.copy(editableValue = null),
            typedValue = "hello query",
        )!!
        val click = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("open", "Open draft", viewId = "id/open"),
        )!!
        val password = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("pw", "Password", viewId = "id/password"),
        )
        assertTrue(password == null)

        val path = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.app",
            goal = "search then open",
            steps = listOf(safeType, click),
            recordedAtMs = 9,
        )!!
        assertTrue(store.recordSuccess(0, path))
        val disk = file.readText()
        assertTrue(disk.contains("com.example.app"))
        assertTrue(disk.contains("\"kind\":\"type\""))
        assertTrue(disk.contains("Search"))
        assertFalse(disk.contains("hunter2-secret-otp"))
        assertFalse(disk.contains("hello query"))
        assertFalse(disk.contains("n0_10_20_110_80"))
        assertFalse(disk.contains("\"ref\""))
        assertFalse(disk.contains("snapshot"))
        assertFalse(disk.contains("observation"))
        assertFalse(disk.contains("\"left\""))
        assertFalse(disk.contains("\"top\""))
        assertFalse(disk.contains("\"right\""))
        assertFalse(disk.contains("\"bottom\""))
        assertFalse(disk.contains("Password"))
    }

    @Test
    fun corruptFileFallsBackWithoutCrossingPackages() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        assertTrue(store.recordSuccess(0, clickPath("com.example.app")))
        file.writeText(
            """{"version":1,"epoch":7,"packages":{"not a pkg":[{"package_name":"com.evil.app","steps":[{"kind":"click","label":"Pay","semantic_id":"id/pay","ref":"old","left":1}]}]}}""",
        )
        val recovered = store(file = file)
        assertEquals(7, recovered.currentEpoch())
        assertTrue(recovered.pathsFor("com.example.app").isEmpty())
        assertTrue(recovered.pathsFor("com.evil.app").isEmpty())
        assertFalse(recovered.hasPaths())

        file.writeText("{not json, \"ref\":\"snap-1\"}")
        val broken = store(file = file)
        assertEquals(0, broken.currentEpoch())
        assertTrue(broken.pathsFor("com.example.app").isEmpty())
    }

    @Test
    fun settingsClearBumpsEpochAndBlocksInflightWrite() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        val epoch = store.currentEpoch()
        assertTrue(store.recordSuccess(epoch, clickPath("com.example.app")))
        assertTrue(store.hasPaths())

        val cleared = clearPerAppSuccessPaths(store)
        assertTrue(cleared is PerAppSuccessPathClearResult.Cleared)
        assertFalse(cleared.showRetryError())
        val next = cleared.clearedEpochOrNull()!!
        assertTrue(next > epoch)
        assertTrue(store.pathsFor("com.example.app").isEmpty())
        assertFalse(store.hasPaths())
        assertFalse(store.recordSuccess(epoch, clickPath("com.example.app", recordedAtMs = 99)))
        assertTrue(store.pathsFor("com.example.app").isEmpty())

        val reopened = store(file = file)
        assertEquals(next, reopened.currentEpoch())
        assertTrue(reopened.pathsFor("com.example.app").isEmpty())
        assertTrue(reopened.recordSuccess(next, clickPath("com.example.app", recordedAtMs = 100)))
        assertEquals(1, reopened.pathsFor("com.example.app").size)
    }

    @Test
    fun clearOnSecondInstanceBlocksCachedInflightWrite() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val inflight = store(file = file)
        val settings = store(file = file)
        val epoch = inflight.currentEpoch()
        assertTrue(inflight.recordSuccess(epoch, clickPath("com.example.app")))
        val cleared = clearPerAppSuccessPaths(settings)
        assertTrue(cleared is PerAppSuccessPathClearResult.Cleared)
        assertTrue(cleared.clearedEpochOrNull()!! > epoch)
        assertFalse(inflight.recordSuccess(epoch, clickPath("com.example.app", label = "Resurrect", ref = "res")))
        assertTrue(inflight.pathsFor("com.example.app").isEmpty())
        assertTrue(settings.pathsFor("com.example.app").isEmpty())
        val disk = file.readText()
        assertFalse(disk.contains("Resurrect"))
        assertFalse(disk.contains("Open draft"))
    }

    @Test
    fun diskOmitsBareCardSeparatedDigitsAndMirroredInput() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        val card = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("card", "6222021234567890", viewId = "id/item"),
        )
        assertNull(card)
        val grouped = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("pan", "6222 0212 3456 7890", viewId = "id/item"),
        )
        assertNull(grouped)
        val hyphen = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("pan2", "6222-0212-3456-7890", viewId = "id/item"),
        )
        assertNull(hyphen)
        val otp = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("otp", "123456", viewId = "id/code"),
        )
        assertNull(otp)

        val mirroredDrop = PerAppSuccessPathSanitizer.stepFrom(
            kind = ScreenActionKind.TYPE,
            node = node("secret", "hunter2", viewId = "", clickable = false, editable = true),
            typedValue = "hunter2",
        )
        assertNull("unknown field mirroring typed input must drop the step", mirroredDrop)

        val mirroredKeepId = PerAppSuccessPathSanitizer.stepFrom(
            kind = ScreenActionKind.TYPE,
            node = node("search", "hunter2", viewId = "id/search", clickable = false, editable = true),
            typedValue = "hunter2",
        )!!
        assertEquals("", mirroredKeepId.label)
        assertEquals("id/search", mirroredKeepId.semanticId)

        val path = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.app",
            goal = "type hunter2 into search",
            steps = listOf(mirroredKeepId),
            recordedAtMs = 11,
            inputSecrets = listOf("hunter2"),
            taskSensitive = false,
        )!!
        assertEquals("", path.goalHint)
        assertEquals("", path.goalFingerprint)
        assertTrue(store.recordSuccess(0, path))
        val disk = file.readText()
        assertFalse(disk.contains("6222021234567890"))
        assertFalse(disk.contains("6222 0212"))
        assertFalse(disk.contains("hunter2"))
        assertTrue(disk.contains("id/search"))
    }

    @Test
    fun taskSensitiveGoalIsNotStoredEvenWithoutKeyword() {
        val step = PerAppSuccessPathSanitizer.stepFrom(
            ScreenActionKind.CLICK,
            node("open", "Open draft", viewId = "id/open"),
        )!!
        val path = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.app",
            goal = "open the draft",
            steps = listOf(step),
            recordedAtMs = 3,
            taskSensitive = true,
        )!!
        assertEquals("", path.goalHint)
        assertEquals("", path.goalFingerprint)
        assertEquals("Open draft", path.steps.single().label)
    }

    @Test
    fun readDiskScrubsLegacyCardAndOtpLabels() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"epoch":4,"packages":{"com.example.app":[
              {"package_name":"com.example.app","goal_fingerprint":"abcdef0123456789","goal_hint":"pay 6222021234567890",
               "recorded_at_ms":1,"steps":[{"kind":"click","label":"6222021234567890","semantic_id":"id/item"}]},
              {"package_name":"com.example.app","goal_fingerprint":"fedcba9876543210","goal_hint":"enter 123456",
               "recorded_at_ms":2,"steps":[{"kind":"click","label":"123456","semantic_id":"id/code"}]},
              {"package_name":"com.example.app","goal_fingerprint":"aaaaaaaaaaaaaaaa","goal_hint":"open the draft",
               "recorded_at_ms":3,"steps":[{"kind":"click","label":"Open draft","semantic_id":"id/open"}]}
            ]}}
            """.trimIndent(),
        )
        val reopened = store(file = file)
        assertEquals(4, reopened.currentEpoch())
        val kept = reopened.pathsFor("com.example.app")
        assertEquals(1, kept.size)
        assertEquals("Open draft", kept.single().steps.single().label)
        assertFalse(kept.any { path -> path.steps.any { it.label.contains("6222") || it.label == "123456" } })
        val disk = file.readText()
        assertFalse(disk.contains("6222021234567890"))
        assertFalse(disk.contains("123456"))
        assertTrue(disk.contains("Open draft"))
        assertNull(PerAppSuccessPathSanitizer.sanitizeStep(PerAppSuccessPathStep("click", "6222-0212-3456-7890", "id/item")))
    }

    @Test
    fun failedClearResultShowsRetryErrorNotSuccess() {
        val failed = PerAppSuccessPathClearResult.Failed("persist_failed")
        assertTrue(failed.showRetryError())
        assertNull(failed.clearedEpochOrNull())
        val ok = PerAppSuccessPathClearResult.Cleared(3)
        assertFalse(ok.showRetryError())
        assertEquals(3L, ok.clearedEpochOrNull())
    }

    @Test
    fun clearWriteFailureDoesNotReportSuccessOrBumpEpoch() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        assertTrue(store.recordSuccess(0, clickPath("com.example.app")))
        val original = file.readText()
        val epoch = store.currentEpoch()
        val failing = FilePerAppSuccessPathStore(
            file = file,
            writeText = { _, _ -> throw java.io.IOException("disk full") },
        )
        val result = clearPerAppSuccessPaths(failing)
        assertTrue(result is PerAppSuccessPathClearResult.Failed)
        assertTrue(result.showRetryError())
        assertEquals(epoch, failing.currentEpoch())
        assertEquals(original, file.readText())
        assertTrue(failing.hasPaths())
        assertTrue(failing.pathsFor("com.example.app").isNotEmpty())
        assertTrue(store.recordSuccess(epoch, clickPath("com.example.app", recordedAtMs = 50)))
        assertTrue(file.readText().contains("Open draft"))
    }

    @Test
    fun clearRenameFailureLeavesOriginalAndDoesNotFence() {
        val file = File(tmp.root, "jev/per_app_success_paths.json")
        val store = store(file = file)
        assertTrue(store.recordSuccess(0, clickPath("com.example.app")))
        val original = file.readText()
        val epoch = store.currentEpoch()
        val failing = FilePerAppSuccessPathStore(
            file = file,
            renameTo = { _, _ -> false },
        )
        val result = clearPerAppSuccessPaths(failing)
        assertTrue(result is PerAppSuccessPathClearResult.Failed)
        assertEquals(epoch, failing.currentEpoch())
        assertEquals(original, file.readText())
        assertFalse(file.readText().contains("\"epoch\":1"))
        assertTrue(store.recordSuccess(epoch, clickPath("com.example.app", label = "Later", ref = "later", recordedAtMs = 77)))
        assertTrue(file.readText().contains("Later"))
        val success = clearPerAppSuccessPaths(store)
        assertTrue(success is PerAppSuccessPathClearResult.Cleared)
        val next = success.clearedEpochOrNull()!!
        assertTrue(next > epoch)
        assertFalse(store.recordSuccess(epoch, clickPath("com.example.app", label = "Resurrect", ref = "res")))
        assertTrue(store.pathsFor("com.example.app").isEmpty())
    }
}
