package app.amber.feature.novel.workspace

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
import app.amber.core.ai.Generator
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NovelWorkspaceControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var manager: WorkManagerImpl
    private lateinit var controller: NovelWorkspaceGhostwriteController

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val configuration = Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                object : ListenableWorker(appContext, workerParameters) {
                    override fun startWork() = SettableFuture.create<Result>()
                }
        }).build()
        manager = WorkManagerImpl(context, configuration)
        WorkManagerImpl.setDelegate(manager)
        // No provider is called by scheduling/recovery; fail if that contract changes.
        val generator = Proxy.newProxyInstance(Generator::class.java.classLoader, arrayOf(Generator::class.java)) {
            _, method, _ -> throw AssertionError("Unexpected provider invocation: ${method.name}")
        } as Generator
        controller = NovelWorkspaceGhostwriteController(context, NovelWorkspaceGhostwriteCoordinator(NovelWorkspaceRuntime(generator)))
    }

    @After fun tearDown() {
        manager.closeDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    @Test fun schedulingAndReconciliationShareTheSameDurableWorkIdentity() = runTest {
        val project = NovelWorkspaceProjectRepository(temporary.newFolder()).createBlank("Scheduling fixture")
        val directory = project.projectDirectory
        val starting = async { controller.startBatch(directory, directory.name, "主线", 2) }
        val recovering = async { controller.reconcile(directory) }
        val job = starting.await()
        recovering.await()
        controller.reconcile(directory)
        assertEquals("running", NovelWorkspaceGhostwriteJobs.load(directory, job.id)?.status)
        val work = withContext(Dispatchers.IO) {
            manager.getWorkInfosByTag("novel_workspace_ghostwrite:${job.id}:${job.executionKey}").get()
        }
        assertEquals(1, work.size)
        assertFalse(work.single().state.isFinished)
        withContext(Dispatchers.IO) { manager.cancelWorkById(work.single().id).result.get() }
        controller.reconcile(directory)
        assertEquals("failed", NovelWorkspaceGhostwriteJobs.load(directory, job.id)?.status)
    }

    @Test fun asynchronousEnqueueDatabaseFailureIsPersistedAsFailure() = runTest {
        val project = NovelWorkspaceProjectRepository(temporary.newFolder()).createBlank("Enqueue failure fixture")
        withContext(Dispatchers.IO) {
            manager.pruneWork().result.get()
            // Fail the real asynchronous WorkManager transaction after job creation.
            manager.workDatabase.openHelper.writableDatabase.execSQL("DROP TABLE WorkName")
        }
        val result = runCatching { controller.startBatch(project.projectDirectory, project.projectDirectory.name, "主线", 2) }
        assertTrue(result.isFailure)
        val job = NovelWorkspaceGhostwriteJobs.snapshot(project.projectDirectory).jobs.single()
        assertEquals("failed", job.status)
        assertNotNull(job.reason)
    }
}
