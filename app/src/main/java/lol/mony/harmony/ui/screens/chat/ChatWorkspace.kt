@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.*
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.VisualThemeSettings
import lol.mony.harmony.ui.screens.server.CreateOrJoinServerDialog
import lol.mony.harmony.ui.screens.server.ServerManagementScreen

@Composable
fun ChatWorkspace(state: AppState, appearance: VisualThemeSettings) {
    val serverId by state.selectedServerId.collectAsState()
    val showingDms by state.showingDms.collectAsState()
    val channelId by state.openChannelId.collectAsState()
    val conversationId by state.openConversationId.collectAsState()
    val createServer by state.showCreateServer.collectAsState()
    if (createServer) CreateOrJoinServerDialog(state,
        onDismiss = { state.showCreateServer.value = false },
        onJoined = { id ->
            state.showCreateServer.value = false
            state.selectedServerId.value = id
            state.showingDms.value = false
            state.serversRevision.value++
        })
    when {
        channelId != null -> RoomScreen(state, channelId!!, false, appearance,
            onBack = { state.openChannelId.value = null })
        conversationId != null -> RoomScreen(state, conversationId!!, true, appearance,
            onBack = { state.openConversationId.value = null })
        else -> if (showingDms || serverId == null) DirectMessages(state, Modifier.fillMaxSize())
            else ChannelList(state, serverId!!, Modifier.fillMaxSize())
    }
}

/** How far in a channel's open threads start, in the column. */
private val ThreadIndent = 56.dp

@Composable
internal fun ChannelList(state: AppState, serverId: String, modifier: Modifier) {
    var tree by remember(serverId) { mutableStateOf<ServerTree?>(null) }
    var failure by remember(serverId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var voice by remember { mutableStateOf<Channel?>(null) }
    var management by remember { mutableStateOf(false) }
    val unread by state.unread.collectAsState()
    if (management) {
        BackHandler { management = false }
        ServerManagementScreen(state, serverId, onBack = { management = false },
            onChanged = { retry++; state.serversRevision.value++ })
        return
    }
    val threads by state.threadsByChannel.collectAsState()
    LaunchedEffect(serverId, retry) {
        // A cached tree is shown at once; the channel list only re-reads when the person asks
        // for a refresh, so moving between channels does not re-fetch the server each time.
        val cached = state.serverTrees.value[serverId]
        if (cached != null && retry == 0) { tree = cached; return@LaunchedEffect }
        try {
            failure = null
            val session = state.auth.state.value as? AuthState.SignedIn ?: return@LaunchedEffect
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            val server = state.servers.server(session.endpoint, token, serverId) ?: error("This server is no longer available.")
            val loaded = state.servers.tree(session.endpoint, token, server).getOrThrow()
            tree = loaded
            state.serverTrees.value = state.serverTrees.value + (serverId to loaded)
            state.channelsByServer.value = state.channelsByServer.value + (serverId to loaded.channels)
        } catch (error: Exception) { failure = error.message }
    }
    // The open threads are part of the channel list, as they are on the web: a channel's
    // unarchived threads that are still inside their own auto-archive clock nest under it.
    //
    // They are read whenever the column opens on a server, including one whose channels came
    // from the cache, because they change far more often than the server's shape does. The
    // threads this app starts or answers are put in the list as it goes (`AppState.rememberThread`,
    // `AppState.noteThreadReply`), so the column is current without a read of its own.
    LaunchedEffect(serverId, tree) {
        val channels = tree?.channels ?: return@LaunchedEffect
        val session = state.auth.state.value as? AuthState.SignedIn ?: return@LaunchedEffect
        val token = state.auth.freshToken() ?: return@LaunchedEffect
        for (channel in channels) {
            runCatching { state.chats.threads(session.endpoint, token, channel.id).getOrThrow() }
                .onSuccess { state.rememberThreads(channel.id, it) }
        }
    }
    val baseUrl = state.auth.state.collectAsState().value.let { (it as? AuthState.SignedIn)?.endpoint?.baseUrl }
    Column(modifier.fillMaxHeight()) {
        // One header, not two. The server's cover and icon used to be drawn *above* an app bar
        // that repeated the name, so a server with branding drew its face and name twice in the
        // first 100dp of the list. The branding is inside the bar now: the cover is the bar's
        // backdrop, and the icon and name are its title.
        val branding = tree?.server
        Box {
            if (branding?.banner != null) {
                // A cover behind the bar, dimmed so the title on it stays readable whatever the
                // uploader chose. The gradient is the same one the old header used.
                ServerBannerBackdrop(branding.name, branding.banner, baseUrl)
            }
            TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The server's own face beside its name: the frame's rail is the way a server
                    // is chosen, and this is the confirmation that the choice took, in the same
                    // picture the rail drew.
                    if (branding != null) {
                        ServerAvatar(branding.name, branding.icon, size = 26.dp, selected = true, baseUrl = baseUrl)
                    }
                    Column {
                        Text(branding?.name ?: "Channels", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge)
                        Text(
                            branding?.let { server -> "${server.memberCount} members" } ?: "Server",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            colors = if (branding?.banner != null) {
                TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            } else {
                TopAppBarDefaults.topAppBarColors()
            },
            actions = {
                IconButton(onClick = { state.serverTrees.value = state.serverTrees.value - serverId; retry++ }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                IconButton(onClick = { management = true }) { Icon(Icons.Default.Settings, "Server settings") }
            })
        }
        when {
            failure != null -> ErrorState(failure!!) { retry++ }
            tree == null -> LoadingState("Loading channels")
            else -> {
                val loaded = tree!!
                LazyColumn(Modifier.fillMaxSize().testTag("channel-list"), contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (!loaded.server.description.isNullOrBlank()) item {
                        Text(loaded.server.description!!, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val groups = listOf<ChannelCategory?>(null) + loaded.categories
                    for (category in groups) {
                        val channels = loaded.channelsIn(category?.id)
                        if (channels.isNotEmpty() && category != null) item(key = category.id) {
                            Text(category.name, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(channels, key = { it.id }) { channel ->
                            Column {
                                ListItem(headlineContent = { Text(channel.name,
                                    fontWeight = if (unread.channel(channel.id) > 0) FontWeight.SemiBold else FontWeight.Normal) },
                                    leadingContent = { Icon(if (channel.channelType.isVoice) Icons.Default.VolumeUp else Icons.Default.Tag, null) },
                                    supportingContent = channel.description?.let { description -> ({ Text(description, maxLines = 2) }) },
                                    trailingContent = if (unread.channel(channel.id) > 0) ({ Badge { Text("" + unread.channel(channel.id)) } }) else null,
                                    modifier = Modifier.testTag("channel-" + channel.name).clickable {
                                        if (channel.channelType.isVoice) voice = channel else state.openChannelId.value = channel.id
                                    })
                                // The open threads nest under their channel, as the web client's
                                // sidebar draws them. A hairline down their start edge is what
                                // says they belong to the row above rather than to the list:
                                // an indent alone reads as a narrower row, which is what a
                                // second channel looks like.
                                val openThreads = threads[channel.id].orEmpty()
                                if (openThreads.isNotEmpty()) {
                                    Row(Modifier.padding(start = ThreadIndent).height(IntrinsicSize.Min)) {
                                        Box(
                                            Modifier
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(LocalHarmonyColors.current.borderStrong),
                                        )
                                        Column {
                                            for (openThread in openThreads) {
                                                ListItem(
                                                    headlineContent = {
                                                        Text(openThread.name ?: "Thread", maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.bodyLarge)
                                                    },
                                                    leadingContent = {
                                                        Icon(Icons.Default.Forum, null, Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    },
                                                    supportingContent = {
                                                        Text(countLabel(openThread.messageCount, "reply"),
                                                            style = MaterialTheme.typography.bodySmall)
                                                    },
                                                    modifier = Modifier
                                                        .testTag("thread-" + openThread.id)
                                                        .clickable {
                                                            state.openChannelId.value = channel.id
                                                            state.openThreadId.value = openThread.id
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (loaded.channels.isEmpty()) item {
                        EmptyState("No channels", "You cannot view any channel in this server yet.")
                    }
                }
            }
        }
    }
    // A voice channel opens the call screen, which owns its own permission requests and its
    // own explanation when a call cannot be joined.
    if (voice != null) VoiceChannelScreen(state, voice!!, onClose = { voice = null })
}

/** The direct-message list: the conversations this account is part of. */
@Composable
internal fun DirectMessages(state: AppState, modifier: Modifier) {
    var rows by remember { mutableStateOf<List<DirectConversation>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var start by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unread by state.unread.collectAsState()
    val session = state.auth.state.collectAsState().value as? AuthState.SignedIn
    val baseUrl = session?.endpoint?.baseUrl
    val cachedConversations by state.conversations.collectAsState()
    LaunchedEffect(retry) {
        // The list a person has already seen is shown at once; it re-reads only on a refresh,
        // so going into a DM and back does not reload every conversation.
        val cached = state.conversations.value
        if (cached != null && retry == 0) { rows = cached; return@LaunchedEffect }
        try {
            failure = null
            val signedIn = state.auth.state.value as? AuthState.SignedIn ?: return@LaunchedEffect
            val token = state.auth.freshToken() ?: error("Sign in again.")
            val myId = signedIn.profile?.id ?: return@LaunchedEffect
            val loaded = state.chats.conversations(signedIn.endpoint, token, myId, state.profiles).getOrThrow()
            rows = loaded
            state.conversations.value = loaded
            state.rememberPeople(loaded.flatMap { it.participants })
        } catch (error: Exception) { failure = error.message }
    }
    Column(modifier.fillMaxHeight()) {
        TopAppBar(
            title = {
                Column {
                    Text("Direct messages", style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (rows.isNullOrEmpty()) "Nobody yet"
                        else "${rows.orEmpty().size} conversations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { state.conversations.value = null; retry++ }) {
                    Icon(Icons.Default.Refresh, "Refresh conversations")
                }
                // Writing is the one thing this screen is for, so it is a filled tonal button
                // rather than a third grey icon in a row of them.
                FilledTonalIconButton(
                    onClick = { start = true },
                    modifier = Modifier.padding(end = 8.dp).testTag("new-message"),
                ) { Icon(Icons.Default.Edit, "New message") }
            },
        )
        val openFailure = failure
        if (openFailure != null) ErrorBanner(openFailure) { failure = null }
        when {
            rows == null && failure == null -> LoadingState("Loading conversations")
            rows.isNullOrEmpty() -> EmptyState(
                title = "Your conversations",
                description = "Start a conversation with someone on your instance.",
                icon = Icons.Filled.Chat,
                action = "New message",
                onAction = { start = true },
            )
            // The list is drawn after this body returns, so it closes over the rows it was
            // given rather than re-reading the nullable state they came from.
            else -> LazyColumn(Modifier.fillMaxSize().testTag("conversation-list")) {
                items(rows.orEmpty(), key = { it.conversation.id }) { row ->
                    ListItem(headlineContent = { Text(row.title) },
                        supportingContent = { Text(if (row.conversation.type == "group") "Group conversation" else "Direct message") },
                        leadingContent = { Avatar(row.title, row.participants.firstOrNull()?.avatarUrl,
                            baseUrl = baseUrl) },
                        trailingContent = if (unread.conversation(row.conversation.id) > 0) ({ Badge { Text("${unread.conversation(row.conversation.id)}") } }) else null,
                        modifier = Modifier.clickable { state.openConversationId.value = row.conversation.id })
                }
            }
        }
    }
    if (start) AlertDialog(onDismissRequest = { if (!starting) start = false }, title = { Text("New message") },
        text = { Column {
            OutlinedTextField(handle, { handle = it }, label = { Text("Username or @handle") }, enabled = !starting,
                singleLine = true, modifier = Modifier.testTag("new-message-handle"))
            val dialogFailure = failure
            if (dialogFailure != null) Text(dialogFailure, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = handle.isNotBlank() && !starting, onClick = {
            starting = true
            scope.launch {
                try {
                    val session = state.auth.state.value as? AuthState.SignedIn ?: error("Sign in again.")
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    val other = state.profiles.byHandle(session.endpoint, token, handle) ?: error("No matching profile was found.")
                    val id = state.chats.openDirectConversation(session.endpoint, token, session.profile!!.id, other.id).getOrThrow()
                    start = false; state.openConversationId.value = id
                } catch (error: Exception) { failure = error.message } finally { starting = false }
            }
        }) { Text(if (starting) "Opening…" else "Open conversation") } },
        dismissButton = { TextButton(onClick = { start = false }, enabled = !starting) { Text("Cancel") } })
}
