package app.amber.feature.ui.pages.backup

import app.amber.core.settings.WebDavConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupWebDavDraftTest {
    @Test
    fun syncFrom_rehydratesDraftAfterSettingsInitialization() {
        val draft = WebDavDraft("", "", "", "")
        val config = WebDavConfig(
            url = "https://dav.example",
            username = "user",
            password = "secret",
            path = "backups",
        )

        assertEquals(
            WebDavDraft("https://dav.example", "user", "secret", "backups"),
            draft.syncFrom(config),
        )
    }

    @Test
    fun syncFrom_updatesOnlyFieldsThatAreNotDirty() {
        val draft = WebDavDraft(
            url = "user-edited-url",
            username = "",
            password = "user-edited-password",
            path = "",
            urlDirty = true,
            passwordDirty = true,
        )
        val config = WebDavConfig(
            url = "https://dav.example",
            username = "user",
            password = "stored-password",
            path = "backups",
        )

        assertEquals(
            WebDavDraft(
                url = "user-edited-url",
                username = "user",
                password = "user-edited-password",
                path = "backups",
                urlDirty = true,
                passwordDirty = true,
            ),
            draft.syncFrom(config),
        )
    }
}
