package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.webmount.profile.ImportResult
import me.rerere.rikkahub.data.agent.webmount.profile.ProfilePermission
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry
import me.rerere.rikkahub.data.agent.webmount.profile.SiteProfile
import me.rerere.rikkahub.data.agent.webmount.profile.SiteProfileEntry
import me.rerere.rikkahub.data.agent.webmount.profile.isReadOnly
import me.rerere.rikkahub.data.agent.webmount.profile.profileJson

/**
 * Phase 2 M2.1 — Profile list + single-file import + audit dialog.
 *
 * Surface contract:
 *  - Section card shows every loaded profile, grouped visually by trust
 *    (built-in / user-imported) with capability + permission chips.
 *  - Tapping a profile expands to show origins + permission audit + sha.
 *  - "Import" button triggers SAF, parses the file, and only the audit
 *    dialog actually writes to the registry. The dialog spells out every
 *    declared permission in plain language before the user confirms.
 *  - User-imported profiles get per-permission toggles for "high-trust"
 *    capabilities (call_page_fn / send_signed); read-only verbs are
 *    granted by default.
 */
@Composable
fun WebMountProfileSection(
    registry: ProfileRegistry,
    onToast: (String) -> Unit,
) {
    val entries by registry.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var importPreview by remember { mutableStateOf<ProfileImportPreview?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val importedToast = stringResource(R.string.setting_webmount_profile_imported_toast)
    val removedToast = stringResource(R.string.setting_webmount_profile_removed_toast)
    val parseErrorTemplate = stringResource(R.string.setting_webmount_profile_parse_error)
    val conflictTemplate = stringResource(R.string.setting_webmount_profile_conflict)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            onToast(parseErrorTemplate.format("empty file"))
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
            profile.validate()
            val perms = profile.permissions.map { ProfilePermission.parse(it, profile.id) }
            ProfileImportPreview(profile = profile, permissions = perms, rawJson = raw)
        }.fold(
            onSuccess = { importPreview = it },
            onFailure = { onToast(parseErrorTemplate.format(it.message ?: it.toString())) },
        )
    }

    ExperimentSectionCard(
        title = stringResource(R.string.setting_webmount_section_profiles),
    ) {
        ExperimentNote(text = stringResource(R.string.setting_webmount_profiles_desc))
        if (entries.isEmpty()) {
            ExperimentNote(text = stringResource(R.string.setting_webmount_no_profiles))
        } else {
            entries.forEachIndexed { index, entry ->
                ProfileRow(
                    entry = entry,
                    expanded = expandedId == entry.profile.id,
                    onToggleExpand = {
                        expandedId = if (expandedId == entry.profile.id) null else entry.profile.id
                    },
                    onRemove = if (entry is SiteProfileEntry.UserImported) {
                        {
                            if (registry.remove(entry.profile.id)) {
                                onToast(removedToast.format(entry.profile.name))
                                if (expandedId == entry.profile.id) expandedId = null
                            }
                        }
                    } else null,
                    onTogglePermission = { wire, granted ->
                        registry.setNonReadOnlyGranted(entry.profile.id, wire, granted)
                    },
                )
                if (index != entries.lastIndex) ExperimentDivider()
            }
        }
        ExperimentActionRow {
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_profile_import),
                enabled = true,
                primary = true,
                onClick = { launcher.launch(arrayOf("application/json", "text/json", "*/*")) },
            )
        }
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = {
                Text(
                    stringResource(R.string.setting_webmount_profile_import_dialog_title)
                        .format(preview.profile.name)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "id: ${preview.profile.id}  ·  v${preview.profile.version}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.setting_webmount_profile_origins) + ":\n" +
                            preview.profile.origins.joinToString("\n") { "• $it" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (preview.permissions.isNotEmpty()) {
                        // M2.1 review N-1: tag each permission with ✓ (auto-granted,
                        // read-only) or ⚠ (will be withheld until manual opt-in) so the
                        // audit dialog actually conveys the L4 trust downgrade.
                        Text(
                            stringResource(R.string.setting_webmount_profile_permissions) + ":\n" +
                                preview.permissions.joinToString("\n") { perm ->
                                    val marker = if (perm.isReadOnly()) "✓" else "⚠"
                                    "$marker ${perm.describeZh()}"
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        stringResource(R.string.setting_webmount_profile_import_dialog_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val result = registry.importJson(preview.rawJson)) {
                        is ImportResult.Imported -> onToast(importedToast.format(result.profile.name))
                        is ImportResult.ConflictWithBuiltIn ->
                            onToast(conflictTemplate.format(result.id))
                        is ImportResult.ParseError ->
                            onToast(parseErrorTemplate.format(result.message))
                        is ImportResult.ValidationError ->
                            onToast(parseErrorTemplate.format(result.message))
                    }
                    importPreview = null
                }) {
                    Text(stringResource(R.string.setting_webmount_profile_import_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { importPreview = null }) {
                    Text(stringResource(R.string.setting_webmount_profile_import_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProfileRow(
    entry: SiteProfileEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRemove: (() -> Unit)?,
    onTogglePermission: (String, Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        onClick = onToggleExpand,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    entry.profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val trustLabel = when (entry) {
                    is SiteProfileEntry.BuiltIn ->
                        stringResource(R.string.setting_webmount_profile_trust_builtin)
                    is SiteProfileEntry.UserImported ->
                        stringResource(R.string.setting_webmount_profile_trust_user)
                }
                AssistChip(
                    onClick = onToggleExpand,
                    label = { Text(trustLabel, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            Text(
                entry.profile.id + " · " + entry.profile.origins.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                ProfileExpandedDetail(entry, onRemove, onTogglePermission)
            }
        }
    }
}

@Composable
private fun ProfileExpandedDetail(
    entry: SiteProfileEntry,
    onRemove: (() -> Unit)?,
    onTogglePermission: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.setting_webmount_profile_origins) + ":",
            style = MaterialTheme.typography.labelMedium,
        )
        entry.profile.origins.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }

        val granted = entry.effective.granted
        val withheld = entry.effective.withheld
        // M2.1 review W-3 fix: opted-in non-readonly permissions are already
        // rendered with a revocable Switch below — skip them in the upper
        // granted block so they don't render twice.
        val grantedAlsoOptedIn = if (entry is SiteProfileEntry.UserImported) {
            entry.grantedNonReadOnlyWireForms
        } else emptySet()
        if (granted.isNotEmpty() || withheld.isNotEmpty()) {
            Text(
                stringResource(R.string.setting_webmount_profile_permissions) + ":",
                style = MaterialTheme.typography.labelMedium,
            )
            granted.filterNot { it.wire in grantedAlsoOptedIn }.forEach { perm ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "✓ ${perm.describeZh()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                stringResource(R.string.setting_webmount_profile_granted),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        enabled = false,
                    )
                }
            }
            withheld.forEach { perm ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "○ ${perm.describeZh()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry is SiteProfileEntry.UserImported) {
                        Switch(
                            checked = false,
                            onCheckedChange = { onTogglePermission(perm.wire, true) },
                        )
                    } else {
                        Text(
                            stringResource(R.string.setting_webmount_profile_withheld),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Toggles for opted-in (granted-but-non-readonly) permissions on
            // user-imported profiles, so the user can revoke them later.
            if (entry is SiteProfileEntry.UserImported && entry.grantedNonReadOnlyWireForms.isNotEmpty()) {
                entry.grantedNonReadOnlyWireForms.forEach { wire ->
                    val perm = runCatching { ProfilePermission.parse(wire, entry.profile.id) }.getOrNull()
                    if (perm != null) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                "⚠ ${perm.describeZh()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Switch(
                                checked = true,
                                onCheckedChange = { onTogglePermission(wire, false) },
                            )
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.setting_webmount_profile_sha256) +
                ": " + entry.sha256Hex.take(12) + "…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRemove != null) {
            ExperimentActionRow {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_profile_remove),
                    enabled = true,
                    onClick = onRemove,
                )
            }
        }
    }
}

private data class ProfileImportPreview(
    val profile: SiteProfile,
    val permissions: List<ProfilePermission>,
    val rawJson: String,
)
