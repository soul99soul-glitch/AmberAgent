package app.amber.feature.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.amber.agent.EXTRA_OPEN_PERSONAL_AGENT
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.automation.display.ActiveVirtualDisplayRun
import app.amber.core.automation.display.VirtualDisplayRunRegistry

/**
 * Quick Settings Tile for Amber (R4).
 *
 * Behavior:
 * - If an active virtual display automation run exists in [VirtualDisplayRunRegistry], opens
 *   [RouteActivity] directly to `Screen.PhoneUseLive(runId)`.
 * - Otherwise opens [RouteActivity] to `Screen.PersonalAgent`.
 * - Uses `startActivityAndCollapse(PendingIntent)` on API 34+ and `unlockAndRun` when locked.
 * - Security invariant: NEVER records audio, NEVER approves actions, NEVER runs tools.
 */
class AmberQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val launch = {
            val intent = buildLaunchIntent(
                context = applicationContext,
                activeRun = VirtualDisplayRunRegistry.firstActiveRun(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    tileRequestCode(intent),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) {
            unlockAndRun(launch)
        } else {
            launch()
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val activeRun = VirtualDisplayRunRegistry.firstActiveRun()
        tile.label = getString(R.string.quick_tile_amber_label)
        if (activeRun != null) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.quick_tile_amber_subtitle_live)
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.quick_tile_amber_subtitle_idle)
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val ACTION_TILE_OPEN_PERSONAL_AGENT = "app.amber.action.TILE_OPEN_PERSONAL_AGENT"

        fun buildLaunchIntent(
            context: Context,
            activeRun: ActiveVirtualDisplayRun? = VirtualDisplayRunRegistry.firstActiveRun(),
        ): Intent {
            if (activeRun != null) {
                return activeRun.viewIntent(context)
            }
            return Intent(context, RouteActivity::class.java).apply {
                action = ACTION_TILE_OPEN_PERSONAL_AGENT
                data = Uri.parse("amber://personal-agent")
                putExtra(EXTRA_OPEN_PERSONAL_AGENT, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        }

        internal fun tileRequestCode(intent: Intent): Int =
            "qs-tile|${intent.action}|${intent.dataString}".hashCode()
    }
}
