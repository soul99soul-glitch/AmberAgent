package app.amber.feature.ui.components.webmount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionMetadata

/**
 * The compact, conversation-scoped entry point for live WebMount work.
 *
 * A conversation can own more than one pooled session, but the composer gets
 * one card so a long-running browser task does not push the input controls
 * away. Each row keeps its own action and routes to the session page.
 */
@Composable
fun WebMountTaskCard(
    sessions: List<WebMountSessionMetadata>,
    onOpenSession: (sessionId: String, reopen: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.parity_webmount_task_card_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sessions.size > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    WebMountTaskRows(sessions = sessions, onOpenSession = onOpenSession)
                }
            } else {
                WebMountTaskRows(sessions = sessions, onOpenSession = onOpenSession)
            }
        }
    }
}

@Composable
private fun WebMountTaskRows(
    sessions: List<WebMountSessionMetadata>,
    onOpenSession: (sessionId: String, reopen: Boolean) -> Unit,
) {
    sessions.forEachIndexed { index, session ->
        if (index > 0) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }
        WebMountTaskRow(
            session = session,
            onClick = {
                onOpenSession(session.sessionId, session.needsReopen)
            },
        )
    }
}

@Composable
private fun WebMountTaskRow(
    session: WebMountSessionMetadata,
    onClick: () -> Unit,
) {
    val title = session.title
        ?.takeIf { it.isNotBlank() }
        ?: session.redactedUrl
        ?.takeIf { it.isNotBlank() }
        ?: session.sessionId
    val status = webMountSessionStatusText(session)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = status,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(
                    if (session.needsReopen) {
                        R.string.parity_webmount_session_reopen
                    } else {
                        R.string.parity_webmount_session_watch
                    },
                ),
            )
        }
    }
}

@Composable
internal fun webMountSessionStatusText(session: WebMountSessionMetadata): String {
    if (session.needsReopen) {
        return stringResource(R.string.parity_webmount_session_needs_reopen)
    }
    return when (session.owner) {
        WebMountOwner.AGENT -> stringResource(R.string.parity_webmount_session_agent_owned)
        WebMountOwner.HUMAN -> stringResource(R.string.parity_webmount_session_human_owned)
        WebMountOwner.NONE -> when (session.status) {
            "loading" -> stringResource(R.string.parity_webmount_session_loading)
            "ready" -> stringResource(R.string.parity_webmount_session_ready)
            "failed" -> stringResource(R.string.parity_webmount_session_failed)
            else -> stringResource(R.string.parity_webmount_session_available)
        }
    }
}
