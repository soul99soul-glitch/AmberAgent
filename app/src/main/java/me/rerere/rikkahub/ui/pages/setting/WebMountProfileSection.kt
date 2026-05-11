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
import androidx.compose.material3.OutlinedTextField
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
    var customSiteDialogOpen by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.setting_webmount_profile_add_custom),
                enabled = true,
                primary = true,
                onClick = { customSiteDialogOpen = true },
            )
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_profile_import),
                enabled = true,
                onClick = { launcher.launch(arrayOf("application/json", "text/json", "*/*")) },
            )
        }
    }

    if (customSiteDialogOpen) {
        CustomSiteDialog(
            onDismiss = { customSiteDialogOpen = false },
            onAdd = { name, url, cookieName ->
                val result = registry.importJson(buildCustomSiteJson(name, url, cookieName))
                when (result) {
                    is ImportResult.Imported -> {
                        onToast(importedToast.format(result.profile.name))
                        customSiteDialogOpen = false
                    }
                    is ImportResult.ConflictWithBuiltIn -> onToast(conflictTemplate.format(result.id))
                    is ImportResult.ParseError -> onToast(parseErrorTemplate.format(result.message))
                    is ImportResult.ValidationError -> onToast(parseErrorTemplate.format(result.message))
                }
            },
        )
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
                // Phase 2 holistic review W-3: localized name for built-in
                // profiles so the Profile section matches the Station section
                // across locales (en sees "Feishu Docs", zh sees "飞书云文档").
                // User-imported profiles keep their manifest's `name` verbatim
                // since that's what the user signed up for.
                val localizedRes = profileDisplayNameRes(entry.profile.id)
                Text(
                    text = if (localizedRes != null && entry is SiteProfileEntry.BuiltIn) {
                        stringResource(localizedRes)
                    } else {
                        entry.profile.name
                    },
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

/**
 * Mirrors `stationDisplayNameRes` in [SettingExperimentalWebMountPage] —
 * keep both in sync if a new built-in station ships.
 */
@androidx.annotation.StringRes
private fun profileDisplayNameRes(profileId: String): Int? = when (profileId) {
    "hackernews" -> R.string.station_hackernews_name
    "reddit" -> R.string.station_reddit_name
    "github" -> R.string.station_github_name
    "bilibili" -> R.string.station_bilibili_name
    "juejin" -> R.string.station_juejin_name
    "zhihu" -> R.string.station_zhihu_name
    "feishu_docs" -> R.string.station_feishu_docs_name
    else -> null
}

/**
 * Phase 2 post-review UX fix: minimal "Add custom site" dialog so users
 * don't need to write profile JSON by hand. Three fields: a display
 * name, the site's homepage URL, and an optional login-cookie name.
 *
 * Submit triggers a regular [ProfileRegistry.importJson] with an
 * auto-generated manifest under the `user_<slug>` id namespace — so it
 * lives alongside imported profiles and inherits the same L4 trust
 * downgrade (read-only by default).
 */
@Composable
private fun CustomSiteDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, cookieName: String?) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }
    val nameLabel = stringResource(R.string.setting_webmount_custom_name_label)
    val urlLabel = stringResource(R.string.setting_webmount_custom_url_label)
    val cookieLabel = stringResource(R.string.setting_webmount_custom_cookie_label)
    val title = stringResource(R.string.setting_webmount_custom_dialog_title)
    val helper = stringResource(R.string.setting_webmount_custom_dialog_helper)
    val nameError = nameInput.isNotEmpty() && nameInput.isBlank()
    val urlError = urlInput.isNotEmpty() &&
        !(urlInput.startsWith("http://") || urlInput.startsWith("https://"))
    val canSubmit = nameInput.isNotBlank() && !urlError && urlInput.startsWith("http")

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text(
                    helper,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { androidx.compose.material3.Text(nameLabel) },
                    singleLine = true,
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { androidx.compose.material3.Text(urlLabel) },
                    placeholder = { androidx.compose.material3.Text("https://weibo.com") },
                    singleLine = true,
                    isError = urlError,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { androidx.compose.material3.Text(cookieLabel) },
                    placeholder = { androidx.compose.material3.Text("SUB") },
                    singleLine = true,
                    supportingText = {
                        androidx.compose.material3.Text(
                            stringResource(R.string.setting_webmount_custom_cookie_supporting),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = canSubmit,
                onClick = { onAdd(nameInput.trim(), urlInput.trim(), cookieInput.trim().ifBlank { null }) },
            ) { androidx.compose.material3.Text(stringResource(R.string.setting_webmount_custom_confirm)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(stringResource(R.string.setting_webmount_profile_import_dialog_cancel))
            }
        },
    )
}

/**
 * Build a minimal SiteProfile JSON from the custom-site dialog inputs.
 * Naming: `user_<slug-of-name>` so user-added entries are easy to spot
 * in the list and can't collide with built-in ids ([a-z0-9_]+ ≥ 5 chars).
 *
 * The origin is the URL's scheme + host (port if present). Path /
 * query / fragment are stripped.
 */
private fun buildCustomSiteJson(
    name: String,
    url: String,
    cookieName: String?,
): String {
    val origin = extractOriginForCustomSite(url) ?: error("invalid URL: $url")
    val slug = name.trim()
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "site" }
        .take(40)
    val id = "user_$slug"
    val cookiePerm = cookieName?.takeIf { it.isNotBlank() }
        ?.let { "\"read_cookie:$it\",\n    \"detect_login\"" }
    val hints = if (cookieName.isNullOrBlank()) {
        "{}"
    } else {
        """
        {
            "login_cookie": ${escapeJsonString(cookieName)}
          }
        """.trimIndent()
    }
    val perms = if (cookieName.isNullOrBlank()) "" else cookiePerm.orEmpty()
    return """
    {
      "id": ${escapeJsonString(id)},
      "name": ${escapeJsonString(name.trim())},
      "version": 1,
      "origins": [${escapeJsonString(origin)}],
      "capabilities": ["read"],
      "hints": $hints,
      "permissions": [$perms],
      "notes": "User-added via the WebMount Stations panel."
    }
    """.trimIndent()
}

private fun extractOriginForCustomSite(url: String): String? {
    val match = Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
    val raw = match.groupValues[1]
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd < 0) return raw.lowercase(java.util.Locale.ROOT)
    val scheme = raw.substring(0, schemeEnd).lowercase(java.util.Locale.ROOT)
    val hostAndPort = raw.substring(schemeEnd + 3).lowercase(java.util.Locale.ROOT)
    return "$scheme://$hostAndPort"
}

private fun escapeJsonString(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

private data class ProfileImportPreview(
    val profile: SiteProfile,
    val permissions: List<ProfilePermission>,
    val rawJson: String,
)
