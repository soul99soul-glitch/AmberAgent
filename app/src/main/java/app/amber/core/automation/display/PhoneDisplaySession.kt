package app.amber.core.automation.display

import app.amber.core.automation.AccessibilityController

enum class PhoneDisplayMode {
    PHYSICAL,
    VIRTUAL,
}

enum class VirtualDisplayCompatibility {
    UNSUPPORTED,
    UNVERIFIED,
    VERIFIED,
    FAILED,
}

sealed interface DisplaySessionOutcome {
    data class Ready(val session: PhoneDisplaySession) : DisplaySessionOutcome
    data class Unavailable(val reason: String) : DisplaySessionOutcome
}

enum class VirtualDisplaySessionState {
    OPEN,
    SAFE_PAUSED,
    UNOBSERVABLE,
    OUTCOME_UNKNOWN,
    CLOSED,
}

interface PhoneDisplaySession {
    val mode: PhoneDisplayMode
    val displayId: Int
    val generation: Long
    val controller: AccessibilityController

    suspend fun launchTarget(): Boolean

    suspend fun close(reason: String)
}
