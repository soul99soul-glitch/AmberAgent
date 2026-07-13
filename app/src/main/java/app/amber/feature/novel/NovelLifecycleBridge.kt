package app.amber.feature.novel

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Application-owned background interrupt. Started from AmberAgentApp after Koin.
 */
class NovelLifecycleBridge(
    private val novelCreation: NovelCreation,
    private val appScope: CoroutineScope,
) : DefaultLifecycleObserver {
    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        appScope.launch {
            for (summary in novelCreation.projectList.value) {
                novelCreation.interrupt(
                    NovelInterruptRequest(
                        projectId = summary.id,
                        reason = NovelInterruptReason.Background,
                    ),
                )
            }
        }
    }
}
