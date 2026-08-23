package app.amber.core.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore


val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { emptyList() }
)
