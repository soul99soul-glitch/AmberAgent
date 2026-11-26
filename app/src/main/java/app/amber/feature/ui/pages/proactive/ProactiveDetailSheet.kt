package app.amber.feature.ui.pages.proactive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.ProactiveCard
import app.amber.feature.macgateway.ProactiveFeedbackAction
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

sealed interface ProactiveDetailLoadState {
    data object Loading : ProactiveDetailLoadState
    data class Loaded(val card: ProactiveCard) : ProactiveDetailLoadState
    data object NotFound : ProactiveDetailLoadState
    data class Error(val message: String?) : ProactiveDetailLoadState
}

/**
 * Pure coordinator for loading a proactive signal detail and reporting `opened` feedback once.
 * Never inserts into the dialogue timeline or Room DAO.
 */
internal class ProactiveDetailSession(
    private val loadSignal: suspend (String) -> ProactiveCard?,
    private val sendOpenedFeedback: suspend (gatewayId: String, signalId: String) -> Boolean,
) {
    private val reportedOpenedSignalIds = HashSet<String>()

    suspend fun load(signalId: String): ProactiveDetailLoadState {
        val card = try {
            loadSignal(signalId)
        } catch (error: Throwable) {
            return ProactiveDetailLoadState.Error(error.message)
        } ?: return ProactiveDetailLoadState.NotFound

        if (reportedOpenedSignalIds.add(card.signalId)) {
            runCatching { sendOpenedFeedback(card.gatewayId, card.signalId) }
        }
        return ProactiveDetailLoadState.Loaded(card)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveDetailSheet(
    signalId: String,
    conversationId: String?,
    repository: CloudProactiveRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tokens = LocalAmberTokens.current
    val detailSession = remember(repository) {
        ProactiveDetailSession(
            loadSignal = repository::loadSignal,
            sendOpenedFeedback = { gatewayId, id ->
                repository.sendFeedback(
                    gatewayId = gatewayId,
                    signalId = id,
                    action = ProactiveFeedbackAction.OPENED,
                )
            },
        )
    }

    var retryTick by remember(signalId) { mutableIntStateOf(0) }
    var loadState by remember(signalId) { mutableStateOf<ProactiveDetailLoadState>(ProactiveDetailLoadState.Loading) }

    LaunchedEffect(signalId, retryTick) {
        loadState = ProactiveDetailLoadState.Loading
        loadState = detailSession.load(signalId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("proactive-detail-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.proactive_detail_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("proactive-detail-close"),
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.proactive_detail_close),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            when (val current = loadState) {
                ProactiveDetailLoadState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Text(
                                text = stringResource(R.string.proactive_detail_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.ink3,
                            )
                        }
                    }
                }

                ProactiveDetailLoadState.NotFound -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Lucide.CircleAlert,
                            contentDescription = null,
                            tint = tokens.ink3,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource(R.string.proactive_detail_not_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.ink3,
                        )
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.proactive_detail_close))
                        }
                    }
                }

                is ProactiveDetailLoadState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Lucide.CircleAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = current.message?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.proactive_detail_load_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { retryTick++ },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.proactive_detail_retry))
                        }
                    }
                }

                is ProactiveDetailLoadState.Loaded -> {
                    val card = current.card
                    ProactiveCardView(
                        card = card,
                        maxBodyLines = Int.MAX_VALUE,
                        showDetailHint = false,
                        onOpenDetail = null,
                        onAct = { target ->
                            repository.act(target, conversationId = conversationId)
                        },
                        onSnooze = { target ->
                            repository.sendFeedback(
                                gatewayId = target.gatewayId,
                                signalId = target.signalId,
                                action = ProactiveFeedbackAction.SNOOZE,
                                snoozeMinutes = DEFAULT_SNOOZE_MINUTES,
                            )
                        },
                        onMute = { target ->
                            repository.sendFeedback(
                                gatewayId = target.gatewayId,
                                signalId = target.signalId,
                                action = ProactiveFeedbackAction.MUTE_TOPIC,
                                muteHours = DEFAULT_MUTE_HOURS,
                            )
                        },
                    )
                    if (card.meta.topicKey.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.proactive_detail_topic_label, card.meta.topicKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.ink3,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
