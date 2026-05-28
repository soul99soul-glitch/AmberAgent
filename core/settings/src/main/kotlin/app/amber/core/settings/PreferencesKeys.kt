package app.amber.core.settings

/**
 * Re-export of the canonical PreferencesKeys in :core:app-infra. Provides
 * the new app.amber.core.settings.PreferencesKeys path that mirrors the
 * legacy me.rerere.rikkahub.data.datastore.PreferencesKeys typealias.
 *
 * ADR-0001 §3 freezes the on-disk DataStore key string identifiers.
 */
typealias PreferencesKeys = app.amber.core.infra.PreferencesKeys
