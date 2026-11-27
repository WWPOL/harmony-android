package lol.mony.harmony.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lol.mony.harmony.data.update.AvailableUpdate
import lol.mony.harmony.data.update.UpdateChecker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "A newer build is available", with one button that downloads it.
 *
 * This is the whole of the update feature from a person's side, and it is deliberately two
 * things: the *download* opens the APK's own address, so Android's browser downloads the file
 * and tapping it installs the update -- there is no release page to find the file on, and no
 * second app's download manager to work out -- and the *release notes* are a quiet text button
 * beside it, for anyone who wants to know what changed before they install.
 *
 * The version is shown, not just the word "update": a person deciding whether to install
 * something wants to know which build it is and how big the download is. "Not now" is a real
 * choice and it is remembered for that build, so the same notice does not come back tomorrow.
 */
@Composable
fun UpdateAvailableDialog(
    update: AvailableUpdate,
    onDownload: () -> Unit,
    onNotes: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
        title = { Text("Update to ${update.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Brapmony ${update.versionName} is available. Download it and tap the file " +
                        "to install it over this build; your account and messages stay where they are.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${update.apkName} · ${update.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // "What changed" sits in the body rather than beside the actions: it is not a
                // third answer to "install this?", and three text buttons in a row on a phone
                // is a row where the one that matters is the same weight as the two that do not.
                if (onNotes != null) {
                    TextButton(
                        onClick = onNotes,
                        modifier = Modifier.testTag("update-notes"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("What changed") }
                }
            }
        },
        confirmButton = {
            // The one thing this dialog is for, drawn as the one thing this dialog is for: a
            // text button beside "Not now" would read as one of two equal answers, and it is not.
            androidx.compose.material3.Button(
                onClick = onDownload,
                modifier = Modifier.testTag("update-download"),
            ) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("update-later")) {
                Text("Not now")
            }
        },
    )
}

/**
 * The Updates section of the account screen: the switch, the state, and "Check now".
 *
 * It is one section rather than a row in the About list because it is a setting with an answer
 * attached -- whether the check is on, what it last found, and when it last ran -- and a row
 * whose summary is "On" would have to open something to say the rest anyway.
 *
 * The text is honest about the traffic, because that is what the switch is really about: a
 * person turning it off should know it is one small request a day, and a person leaving it on
 * should know that is all it is.
 */
@Composable
internal fun AccountUpdatesPanel(checker: UpdateChecker) {
    val update by checker.update.collectAsState()
    val checking by checker.checking.collectAsState()
    val lastError by checker.lastError.collectAsState()
    var state by remember { mutableStateOf(checker.stateNow()) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    // The stored state is read here as well as at launch, so a check this screen starts shows
    // its new timestamps without waiting for a restart.
    LaunchedEffect(checking) { if (!checking) state = checker.stateNow() }

    AccountSection(
        "Updates",
        "The app asks GitHub's release page whether there is a newer build: one small request a " +
            "day at most, and only while the app is open. Nothing about you or your account is sent.",
    ) {
        AccountToggle(
            label = "Check for updates automatically",
            description = if (state.enabled) "On · at most once a day"
            else "Off · this build will not look for newer ones",
            checked = state.enabled,
            enabled = !checking,
            onChange = { wanted ->
                checker.setEnabled(wanted)
                state = checker.stateNow()
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = { scope.launch { checker.check(force = true) } },
                // Enabled with the switch off: the switch governs the automatic check, and
                // this is the person asking, which is what the switch being off does not forbid.
                enabled = !checking,
                modifier = Modifier.testTag("update-check-now"),
            ) { Text(if (checking) "Checking…" else "Check now") }
            if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                lastCheckedLabel(state.lastCheckMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lastError != null) {
            Text(
                lastError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val offered = update
        if (offered != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("update-offered")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Version ${offered.versionName} is available",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${offered.apkName} · ${offered.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Button(
                        onClick = { uriHandler.openUri(offered.downloadUrl) },
                        modifier = Modifier.testTag("update-download-apk"),
                    ) { Text("Download ${offered.versionName}") }
                    offered.notesUrl?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("What changed") }
                    }
                }
            }
        } else if (lastError == null && state.lastCheckMillis != 0L) {
            // Only when the check actually reached GitHub: "this is the newest build" beside a
            // failure would be the app claiming to know something it just said it could not
            // find out.
            Text(
                "This is the newest build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Updates install over this build, so Android keeps your sign-in and your drafts. " +
                "A build signed with a different key cannot be installed over this one, and " +
                "Android will say so when you tap the file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** When the last check ran, in the person's own timezone, or a line saying it has not. */
internal fun lastCheckedLabel(lastCheckMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (lastCheckMillis == 0L) return "Not checked yet"
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(zone)
    val whenText = formatter.format(Instant.ofEpochMilli(lastCheckMillis))
    // Within the last day, "today" reads better than a date the person has to work out.
    val sameDay = Instant.ofEpochMilli(lastCheckMillis).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (sameDay) "Last checked today at ${whenText.substringAfter(", ")}" else "Last checked $whenText"
}
