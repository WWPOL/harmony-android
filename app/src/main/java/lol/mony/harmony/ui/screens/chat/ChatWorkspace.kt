@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * One row of the channel column: a channel.
 *
 * A channel is a glyph in the column's icon gutter and its name in the name column, which is the
 * shape every row in the column has -- a thread's too ([ThreadRow]).
 */
@Composable
private fun ChannelRow(
    name: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    description: String? = null,
    unread: Int = 0,
) {
    ListItem(
        headlineContent = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal)
        },
        leadingContent = { Icon(icon, null) },
        supportingContent = description?.let { text -> ({ Text(text, maxLines = 2) }) },
        trailingContent = if (unread > 0) ({ Badge { Text("" + unread) } }) else null,
        modifier = Modifier.testTag(testTag).clickable(onClick = onClick),
    )
}

/**
 * The x of the branch: the middle of the channel's 24dp glyph in its 16dp of row padding, which is
 * where Discord hangs a channel's threads.
 */
private val ThreadBranchX = 28.dp

/**
 * Where the branch's arm stops: short of the name.
 *
 * The name starts at 56dp -- 16dp of row padding, the glyph's 24dp slot and its 16dp gap -- so the
 * arm stops at 46dp, clear of the first letter and inside the name's own indent.
 */
private val ThreadBranchArm = 46.dp

/** How far the branch turns from the spine into the arm, and how thick its hairline is. */
private val ThreadBranchTurn = 5.dp
private val ThreadBranchStroke = 1.5.dp

/**
 * A thread nested under its channel, drawn the way Discord draws one.
 *
 * The row is a channel's row, drawn by the same Material `ListItem`: the same container colour,
 * the same paddings, the same headline type, the same height and the same ripple. Two things
 * differ, and they are the two things Discord changes. Its name starts one level in -- a 24dp
 * leading slot with nothing in it, which is the channel's glyph and its gap, so the name lands in
 * the channel's own name column -- and a hairline branch is drawn in the gutter: down from the
 * channel's glyph, turning into this row's middle, and on to the next thread when there is one
 * (`├` for every thread but the last, `└` for the last).
 *
 * The branch is what says the row belongs to the channel above it. An indent alone reads as a
 * narrower row, which is what a channel that lost its glyph looks like; a glyph of its own would
 * be a second glyph under a column that already has one.
 */
@Composable
private fun ThreadRow(
    name: String,
    testTag: String,
    last: Boolean,
    onClick: () -> Unit,
) {
    val branch = LocalHarmonyColors.current.borderStrong
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // The channel's glyph's slot, empty: the name lands where a channel's name does.
        leadingContent = { Spacer(Modifier.size(24.dp)) },
        modifier = Modifier.testTag(testTag)
            // Over the row's own fill, not behind it: a ListItem paints its container colour
            // inside this modifier, so a branch drawn behind would be covered by it.
            .drawWithContent { drawContent(); drawThreadBranch(branch, last) }
            .clickable(onClick = onClick),
    )
}

/**
 * The branch in the gutter: a spine down from the channel's glyph, turning into the row's middle,
 * and running on to the next thread unless this is the last one.
 *
 * Measured from Discord's own sidebar: a hairline hanging from the glyph's centre, a rounded
 * quarter turn into the row at its vertical middle, and the arm stopping short of the name. The
 * last thread's branch is `└` -- the turn with nothing below it -- so the group ends where it ends
 * rather than running into the next channel.
 */
private fun DrawScope.drawThreadBranch(color: Color, last: Boolean) {
    val spine = ThreadBranchX.toPx()
    val arm = ThreadBranchArm.toPx()
    val turn = ThreadBranchTurn.toPx()
    val stroke = ThreadBranchStroke.toPx()
    val middle = size.height / 2
    val branch = Path().apply {
        moveTo(spine, 0f)
        lineTo(spine, middle - turn)
        // The corner is tangent to both the spine and the arm: a quarter of a circle of [turn].
        arcTo(Rect(spine, middle - 2 * turn, spine + 2 * turn, middle),
            startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false)
        lineTo(arm, middle)
    }
    drawPath(branch, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    if (!last) drawLine(color, Offset(spine, middle), Offset(spine, size.height),
        strokeWidth = stroke, cap = StrokeCap.Round)
}

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
                            val openThreads = threads[channel.id].orEmpty()
                            ChannelRow(
                                name = channel.name,
                                icon = if (channel.channelType.isVoice) Icons.Default.VolumeUp else Icons.Default.Tag,
                                testTag = "channel-" + channel.name,
                                description = channel.description,
                                unread = unread.channel(channel.id),
                                onClick = {
                                    if (channel.channelType.isVoice) voice = channel else state.openChannelId.value = channel.id
                                },
                            )
                            // A channel's open threads follow it as the column's own rows, one
                            // level in: the name in the channel's name column, the channel's
                            // glyph as the thing above them, and a branch from that glyph into
                            // each one. That is the shape Discord gives a thread, and the one
                            // the report asked for -- indented, and not a second kind of row.
                            for ((position, openThread) in openThreads.withIndex()) {
                                ThreadRow(
                                    name = openThread.name ?: "Thread",
                                    testTag = "thread-" + openThread.id,
                                    last = position == openThreads.lastIndex,
                                    onClick = {
                                        state.openChannelId.value = channel.id
                                        state.openThreadId.value = openThread.id
                                    },
                                )
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
