package app.amber.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import app.amber.core.infra.PreferencesKeys

class CapabilityFlagsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun releaseDefaultsEnableOnlyDurableRuntimeCore() = runBlocking {
        val flags = CapabilityFlags(createStore())
        val expected = setOf(Capability.DurableToolEffects, Capability.TypedRunTerminal)
        assertEquals(expected, flags.flow.first().enabled)
        Capability.entries.filterNot { it.defaultEnabled }.forEach { capability ->
            assertFalse("$capability must default off", flags.isEnabled(capability))
        }
    }

    @Test
    fun setEnabledRoundTrip() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.RecipeRuntime, true)
        assertTrue(flags.isEnabled(Capability.RecipeRuntime))
        assertTrue(Capability.RecipeRuntime in flags.flow.first().enabled)

        flags.setEnabled(Capability.RecipeRuntime, false)
        assertFalse(flags.isEnabled(Capability.RecipeRuntime))
        assertFalse(Capability.RecipeRuntime in flags.flow.first().enabled)
    }

    @Test
    fun explicitFalseIsPersistedAndSurvivesReinstantiation() = runBlocking {
        val store = createStore()
        val flags = CapabilityFlags(store)
        flags.setEnabled(Capability.DurableToolEffects, false)

        assertEquals(
            false,
            store.data.first()[PreferencesKeys.capabilityFlag(Capability.DurableToolEffects.id)],
        )
        assertFalse(flags.isEnabled(Capability.DurableToolEffects))

        val restarted = CapabilityFlags(store)
        assertFalse(restarted.isEnabled(Capability.DurableToolEffects))
        assertFalse(Capability.DurableToolEffects in restarted.flow.first().enabled)
    }

    @Test
    fun togglingOneCapabilityDoesNotAffectOthers() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.NovelPackageV2, true)
        Capability.entries.filter { it != Capability.NovelPackageV2 }.forEach { capability ->
            assertEquals(
                "$capability must keep its default",
                capability.defaultEnabled,
                flags.isEnabled(capability),
            )
        }
    }

    @Test
    fun togglingFlagKeepsUnrelatedSettings() = runBlocking {
        val store = createStore()
        store.edit { it[PreferencesKeys.LAUNCH_COUNT] = 7 }

        CapabilityFlags(store).setEnabled(Capability.SyncProviderV2, true)

        assertEquals(7, store.data.first()[PreferencesKeys.LAUNCH_COUNT])
    }

    private fun createStore() = PreferenceDataStoreFactory.create {
        File(tempFolder.newFolder(), "capability-flags.preferences_pb")
    }
}
