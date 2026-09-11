package app.amber.feature.novel.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.amber.agent.RouteActivity
import app.amber.agent.Screen
import java.util.UUID

object NovelWorkspaceNotificationRoute {
    private const val OPEN = "openNovelWorkspace"
    private const val PROJECT = "novelWorkspaceProjectId"
    private const val BRANCH = "novelWorkspaceBranchSlug"
    private const val JOB = "novelWorkspaceJobId"

    fun intent(context: Context, projectId: String, branchSlug: String, jobId: String): Intent =
        Intent(context, RouteActivity::class.java).apply {
            data = Uri.Builder().scheme("amberagent").authority("novel")
                .appendPath(projectId).appendPath(branchSlug).appendPath(jobId).build()
            putExtra(OPEN, true)
            putExtra(PROJECT, projectId)
            putExtra(BRANCH, branchSlug)
            putExtra(JOB, jobId)
        }

    fun screenFrom(intent: Intent): Screen.NovelMarkdown? {
        if (!intent.getBooleanExtra(OPEN, false)) return null
        val projectId = intent.getStringExtra(PROJECT) ?: return null
        if (projectId.length != 36 || runCatching { UUID.fromString(projectId) }.isFailure) return null
        val branch = intent.getStringExtra(BRANCH)?.takeIf(::singleComponent) ?: return null
        val job = intent.getStringExtra(JOB)?.takeIf(::singleComponent) ?: return null
        // The page resolves existence and branch/job ownership against durable state.
        return Screen.NovelMarkdown(projectId, branch, job)
    }

    private fun singleComponent(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' }
}
