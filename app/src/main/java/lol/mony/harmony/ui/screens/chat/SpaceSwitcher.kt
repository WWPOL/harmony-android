package lol.mony.harmony.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import lol.mony.harmony.ui.AppState

/**
 * The app bar of the room list: what space this is, and what can be done to it.
 *
 * It used to sit under a horizontally scrolling strip of server pills, which is where the space
 * switcher lived before the frame took it over. Now the bar is the first thing in the list pane,
 * which is what it should always have been: the title of the list, not a third row of chrome
 * above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsAppBar(
    title: String,
    subtitle: String?,
    onRefresh: () -> Unit,
    onNewMessage: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            actions()
            // Refresh and "new message" are frequent enough to keep; everything else is one
            // tap away instead of five icons competing with the title.
            if (onNewMessage != null) {
                IconButton(onClick = onNewMessage, modifier = Modifier.testTag("new-message")) {
                    Icon(Icons.Outlined.Edit, "New message")
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menu = false; onRefresh() },
                    )
                }
            }
        },
    )
}

private fun AppState.baseUrl(): String? =
    (auth.state.value as? lol.mony.harmony.data.session.AuthState.SignedIn)?.endpoint?.baseUrl
