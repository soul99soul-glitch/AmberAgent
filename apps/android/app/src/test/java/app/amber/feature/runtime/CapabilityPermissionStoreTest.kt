package app.amber.feature.runtime

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.amber.core.infra.PreferencesKeys
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapabilityPermissionStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (error: Throwable) {
            error
        }

    private fun createDataStore() = PreferenceDataStoreFactory.create {
        File(tempFolder.newFolder(), "capability-permissions.preferences_pb")
    }

    @Test
    fun malformedScopedJsonThrowsInsteadOfFallingBackToGlobalPolicy() = runBlocking {
        val dataStore = createDataStore()
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.CAPABILITY_POLICIES] = """{"mcp.import":"AUTO"}"""
            prefs[PreferencesKeys.CAPABILITY_POLICY_OVERRIDES] = """{"conversation":"""
        }

        val failure = captureFailure { CapabilityPermissionStore(dataStore).state() }

        assertTrue(failure is CapabilityPermissionDecodeException)
        assertEquals(
            PreferencesKeys.CAPABILITY_POLICY_OVERRIDES.name,
            (failure as CapabilityPermissionDecodeException).preferenceKey,
        )
    }

    @Test
    fun invalidScopedPolicyEntryThrowsInsteadOfBeingDropped() = runBlocking {
        val dataStore = createDataStore()
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.CAPABILITY_POLICY_OVERRIDES] =
                """{"conversation":{"conversation-1":{"mcp.import":"NOT_A_POLICY"}}}"""
        }

        val failure = captureFailure { CapabilityPermissionStore(dataStore).scopedPolicies() }

        assertTrue(failure is CapabilityPermissionDecodeException)
        assertTrue(failure?.message?.contains("mcp.import") == true)
    }
}
