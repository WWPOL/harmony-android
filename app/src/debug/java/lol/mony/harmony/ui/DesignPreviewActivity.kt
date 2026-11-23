package lol.mony.harmony.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lol.mony.harmony.ui.components.HarmonyDivider
import lol.mony.harmony.ui.navigation.Destination
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.LocalWindowWidth
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.navigation.showsTwoPanesAt
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * A debug-only view of the redesigned frame, with fixture rows.
 *
 * It exists so the redesign can be seen on a device without a Harmony instance: the signed-in
 * screens read from one, and the server is a different project that may not be running. This
 * draws the same shell, the same space switcher, the same app bar, the same message rows and the
 * same composer the app draws, with text in place of an instance's rows.
 *
 * It is in the debug source set only, so a release APK cannot start it and it never ships.
 */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The look is an argument so the same APK can be captured in the app's dark and light
        // presets on one device, instead of the evidence being a single theme:
        //   adb shell am start -n <pkg>/lol.mony.harmony.ui.DesignPreviewActivity --es theme light
        val theme = intent?.getStringExtra("theme") ?: "dark"
        setContent {
            HarmonyTheme(VisualThemeSettings(theme = theme)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PreviewChats()
                }
            }
        }
    }
}

@Composable
private fun PreviewChats() {
    // The real frame, driven by the real spaces: the preview is only worth having if it is the
    // app's own navigation, not a drawing of it. The rows inside the content are fixtures
    // because the signed-in screens read an instance and this one may not be running.
    val current = remember { mutableStateOf<String?>(null) }
    val spaces = listOf(
        Space(null, "Home", badge = 3),
        Space("s1", "Design"),
        Space("s2", "E2E Server", badge = 1),
        Space("s3", "Book club"),
        Space("s4", "Homelab", badge = 12, mentions = 2),
    )
    HarmonyShell(
        spaces = spaces,
        currentSpace = spaces.firstOrNull { it.id == current.value } ?: spaces.first(),
        onSpace = { current.value = it.id },
        onAddServer = {},
        onMore = {},
    ) { _ -> PreviewChatsContent() }
}

/**
 * The Chats destination, laid out the way `ChatsDestination` lays it out.
 *
 * The real composable reads an `AppState` and therefore needs a session; this draws its layout
 * with the same components and the same measurements, so the frame, the space switcher, the
 * list, the room and the composer can be judged at both widths.
 */
@Composable
private fun PreviewChatsContent() {
    // The same decision the real destination makes: two panes from 600dp, and the room taking
    // what the list does not. The servers are the frame's, so there is no third pane.
    if (showsTwoPanesAt(LocalWindowWidth.current)) {
        Row(Modifier.fillMaxSize()) {
            Surface(
                Modifier.width(HarmonySize.paneListWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) { PreviewRoomList(Modifier.fillMaxSize()) }
            HarmonyDivider(Modifier.fillMaxHeight().width(1.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) { PreviewRoom() }
        }
    } else {
        Column(Modifier.fillMaxSize()) { PreviewRoomList(Modifier.weight(1f)) }
    }
}

/** The room list: a server's channels, grouped under their categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewRoomList(modifier: Modifier = Modifier) {
    val channels = listOf(
        "welcome" to "Start here",
        "general" to "Everything and nothing",
        "design" to "Type, colour and motion",
        "random" to "Off topic",
    )
    Column(modifier) {
        TopAppBar(
            title = {
                Column {
                    Text("E2E Server", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text("4 channels", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = {}) { Icon(Icons.Outlined.Search, "Search") }
                IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "More") }
            },
        )
        LazyColumn(Modifier.fillMaxSize().testTag("channel-list")) {
            item {
                Text("Text channels", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(channels) { (name, description) ->
                Row(
                    Modifier.fillMaxWidth().heightIn48().testTag("channel-$name")
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("#$name", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (name == "general") androidx.compose.material3.Badge { Text("2") }
                }
            }
        }
    }
}

/** The room: its app bar, its messages and its composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewRoom() {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("general", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text("Channel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = {}) { Icon(Icons.Outlined.Search, "Search") }
                IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "More") }
            },
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("message-list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                listOf(
                    Triple("Ada", "The redesign is in. Have a look at the frame on a wide window.", "9:12"),
                    Triple("Grace", "The list stays put while I read now, which is the whole point.", "9:14"),
                    Triple("Alan", "Composer reads as one thing instead of a field and a button.", "9:15"),
                )
            ) { (name, text, time) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(36.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(name.take(1), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer) }
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Text(time, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // The docked composer: one surface, a field and a filled circular send.
        Surface(
            color = LocalHarmonyColors.current.surfaceRaised,
            shape = HarmonyShape.sheet,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().testTag("composer-surface"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = HarmonyShape.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.weight(1f).heightIn48(),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Message #general", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = {}, modifier = Modifier.size(52.dp).testTag("message-send")) {
                    Icon(Icons.Outlined.Edit, "Send")
                }
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** A 48dp touch-target floor, stated once here so the preview matches the app's own rule. */
private fun Modifier.heightIn48(): Modifier = this.then(Modifier.height(HarmonySize.minTouchTarget))
