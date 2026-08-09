package app.amber.feature.novel

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stops non-ghostwrite novel runs when the application backgrounds. App-owned ghostwrite runs keep their foreground
 * service lease and continue until an explicit pause or terminal result.
 */
class NovelLifecycleBridge(
    private val novelCreation: NovelCreation,
    private val backgroundRunRegistry: NovelBackgroundRunRegistry,
    private val appScope: CoroutineScope,
) : DefaultLifecycleObserver {
    @Volatile
    private var started = false
    private val lifecycleEpoch = AtomicLong(0L)

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        lifecycleEpoch.incrementAndGet()
    }

    override fun onStop(owner: LifecycleOwner) {
        val backgroundEpoch = lifecycleEpoch.incrementAndGet()
        appScope.launch {
            for (summary in novelCreation.projectList.value) {
                if (lifecycleEpoch.get() != backgroundEpoch) return@launch
                backgroundRunRegistry.withOwnedRunIds(summary.id) { ownedRunIds ->
                    novelCreation.interrupt(
                        NovelInterruptRequest(
                            projectId = summary.id,
                            reason = NovelInterruptReason.Background,
                            excludedRunIds = ownedRunIds,
                        ),
                    )
                }
            }
        }
    }
}
