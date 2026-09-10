package app.amber.feature.ui.pages.miniapp

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAppSourceEditorStateTest {

    @Test
    fun cleanEditorCanDismissFromAnyClosePath() {
        assertEquals(
            MiniAppEditorDismissAction.DISMISS,
            miniAppEditorDismissAction(unsaved = false, saving = false),
        )
    }

    @Test
    fun unsavedEditorRequiresConfirmation() {
        assertEquals(
            MiniAppEditorDismissAction.CONFIRM,
            miniAppEditorDismissAction(unsaved = true, saving = false),
        )
    }

    @Test
    fun savingEditorIgnoresDismissToAvoidWriteRace() {
        assertEquals(
            MiniAppEditorDismissAction.IGNORE,
            miniAppEditorDismissAction(unsaved = true, saving = true),
        )
        assertEquals(
            MiniAppEditorDismissAction.IGNORE,
            miniAppEditorDismissAction(unsaved = false, saving = true),
        )
    }
}
