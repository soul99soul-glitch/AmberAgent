package app.amber.agent.data.datastore

/**
 * Legacy alias — PreferencesKeys now lives in :core:app-infra at
 * app.amber.core.infra.PreferencesKeys. Existing call sites continue
 * to compile via this typealias during the package migration.
 *
 * ADR-0001 §3 freezes all DataStore key string identifiers; the on-disk
 * schema is unchanged regardless of which package the constants live in.
 */
typealias PreferencesKeys = app.amber.core.infra.PreferencesKeys
