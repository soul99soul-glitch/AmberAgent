package app.amber.agent.perffixture

import android.content.Context

/**
 * Fixture persistence must be synchronous: the prep process is force-stopped
 * before the measured process reads these values. The package is isolated, so
 * using the normal "amber_agent.preferences" file is safe and lets Activity
 * ContextImpl, receiver Context and Application resolve the same file.
 */
internal fun Context.writeFixtureString(key: String, value: String?) {
    getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        .edit()
        .putString(key, value)
        .commit()
}
