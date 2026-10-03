package app.amber.core.di

import app.amber.core.memory.dream.MemoryDreamApplier
import app.amber.core.memory.dream.MemoryDreamNotifier
import app.amber.core.memory.dream.MemoryDreamPlanApplier
import app.amber.core.memory.dream.MemoryDreamPlanStore
import app.amber.core.memory.dream.MemoryDreamPlanProvider
import app.amber.core.memory.dream.MemoryDreamPlanner
import app.amber.core.memory.dream.MemoryDreamReviewNotifier
import app.amber.core.memory.dream.MemoryDreamRunCoordinator
import app.amber.core.memory.dream.MemoryDreamScheduler
import app.amber.core.memory.export.MemoryFrontmatterCodec
import app.amber.core.memory.export.MemoryImportExportManager
import app.amber.core.memory.extraction.MemoryCandidateFilter
import app.amber.core.memory.extraction.MemoryExtractor
import app.amber.core.memory.store.MemoryProfileStore
import app.amber.core.memory.telemetry.MemoryEventLogger
import org.koin.dsl.module
import android.content.Context
import java.io.File

/**
 * Memory-domain Koin module — covers extraction, dream-mode planning/applying,
 * persistence (PlanStore), notification, scheduling, telemetry, and the
 * import/export codec.
 *
 * Extracted from AppModule in M1.5 continuation (memoryModule sibling of
 * the previously-extracted chatModule).
 */
val memoryModule = module {
    single { MemoryEventLogger(get()) }

    single { MemoryCandidateFilter() }

    single { MemoryExtractor(get(), get(), get(), get(), get(), get(), get(), restoreWriteGate = get()) }

    single { MemoryDreamPlanner(get(), get(), get(), get(), get(), get(), planStore = get(), profileStore = get()) }
    single<MemoryDreamPlanProvider> { get<MemoryDreamPlanner>() }

    single { MemoryDreamApplier(get(), get(), get(), profileStore = get()) }
    single<MemoryDreamPlanApplier> { get<MemoryDreamApplier>() }

    single { MemoryDreamPlanStore(get(), get(), get()) }

    single {
        MemoryProfileStore(File(get<Context>().filesDir, "memory_profile.json"), get())
    }

    single { MemoryDreamNotifier(get()) }
    single<MemoryDreamReviewNotifier> { get<MemoryDreamNotifier>() }

    single { MemoryDreamRunCoordinator(get(), get(), get(), get(), get()) }

    single { MemoryDreamScheduler(get(), get()) }

    single { MemoryFrontmatterCodec() }

    single { MemoryImportExportManager(get(), get(), get()) }
}
