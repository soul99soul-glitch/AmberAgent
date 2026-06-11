package app.amber.core.settings.prefs

import android.util.Log
import app.amber.core.agent.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "PrefsDecode"

/**
 * Decode a persisted settings entry, dropping it (so the caller's elvis default
 * applies) instead of crashing the settings flow when the stored JSON is
 * corrupted. A single bad entry must not halt the whole process.
 */
internal inline fun <reified T> String.decodeJsonOrNull(): T? =
    runCatching { JsonInstant.decodeFromString<T>(this) }
        .onFailure {
            Log.e(TAG, "Dropping corrupted settings entry (${T::class.simpleName}): ${it.message}")
        }
        .getOrNull()

internal fun String.parseUuidOrNull(): Uuid? =
    runCatching { Uuid.parse(this) }
        .onFailure { Log.e(TAG, "Dropping corrupted settings uuid: ${it.message}") }
        .getOrNull()
