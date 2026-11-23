@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package lol.mony.harmony.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import lol.mony.harmony.data.api.*
import lol.mony.harmony.data.model.*
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.LiveStatus
import lol.mony.harmony.ui.components.*
import lol.mony.harmony.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One channel, DM or thread.
 *
 * Three things here are load-bearing:
 *
 * - The rows come from [AppState.rooms], which is painted before the network is asked, so a
 *   channel a person has already read opens on its history rather than a spinner, and the
 *   refresh merges into that history instead of replacing it.
 * - Sending is optimistic: the row appears greyed with "Sending" the moment Send is tapped,
 *   then becomes the instance's own row. A failed send stays in the list with Retry, and its
 *   text is still in the composer, so nothing is lost.
 * - A horizontal drag on a row is a reply gesture, with the arrow and the row's own offset as
 *   the feedback. It travels away from the start edge, the opposite way to a navigation drawer,
 *   so the two gestures cannot be confused. See [SwipeToReplyRow].
 */
@Composable
fun RoomScreen(state: AppState, roomId: String, direct: Boolean, appearance: VisualThemeSettings, onBack: () -> Unit) {
    val session = state.auth.state.collectAsState().value as? AuthState.SignedIn ?: return
    val myId = session.profile?.id ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    var title by remember(roomId) { mutableStateOf(if (direct) "Conversation" else "Channel") }
    var thread by remember(roomId) { mutableStateOf<Thread?>(null) }
    val cacheKey = state.roomKey(roomId, direct, thread?.id)
    val draftKey = roomId + (thread?.id ?: "")
    val snapshot by state.rooms.rooms.collectAsState()
    val room = snapshot[cacheKey] ?: RoomSnapshot()
    var loading by remember(cacheKey) { mutableStateOf(!room.loaded) }
    var refreshing by remember(cacheKey) { mutableStateOf(false) }
    var draft by remember(draftKey) { mutableStateOf("") }
    var draftReady by remember(draftKey) { mutableStateOf(false) }
    var reply by remember(draftKey) { mutableStateOf<Message?>(null) }
    var failure by remember(cacheKey) { mutableStateOf<String?>(null) }
    var selected by remember(cacheKey) { mutableStateOf<Message?>(null) }
    var edit by remember(cacheKey) { mutableStateOf<Message?>(null) }
    var editText by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Message?>(null) }
    var pins by remember { mutableStateOf<List<Message>?>(null) }
    var threads by remember { mutableStateOf<List<Thread>?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var roomMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchRows by remember { mutableStateOf<List<Message>?>(null) }
    val people by state.people.collectAsState()
    val bots by state.bots.collectAsState()
    val emojiRows by state.emojis.collectAsState()
    var selectedProfile by remember(cacheKey) { mutableStateOf<String?>(null) }
    var reactors by remember(cacheKey) { mutableStateOf<Pair<Message, String>?>(null) }
    val live by state.live.collectAsState()
    val sizes = LocalSizes.current
    // A notification can name a message far older than the page this room opens on. The id is
    // taken once, the room pages back until it is in hand, and it is highlighted until the
    // person scrolls away from it.
    var focusId by remember(cacheKey) { mutableStateOf(state.focusMessageId.value) }
    var highlighted by remember(cacheKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(cacheKey) {
        val wanted = state.focusMessageId.value ?: return@LaunchedEffect
        state.focusMessageId.value = null
        focusId = wanted
    }

    suspend fun load(older: Boolean = false) {
        val key = state.roomKey(roomId, direct, thread?.id)
        try {
            if (!older) refreshing = true
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            // Every surface pages backwards from the newest message, so `before` is the
            // oldest row held. A thread paged the other way round returned its oldest
            // messages and looked as though it had stopped receiving replies.
            val before = if (older) state.rooms.snapshot(key)?.oldest else null
            val activeThread = thread
            val page = when {
                activeThread != null -> state.chats.threadMessages(session.endpoint, token, activeThread.id, before).getOrThrow()
                direct -> state.chats.directMessages(session.endpoint, token, roomId, before).getOrThrow()
                else -> state.chats.channelMessages(session.endpoint, token, roomId, before).getOrThrow()
            }
            state.rooms.update(key) { current ->
                val rows = if (older) (page + current.rows.filter { it.state is RowState.Sent }.map { it.message })
                    .distinctBy { it.id }.sortedBy { it.createdAt }
                    .map { message -> current.rows.firstOrNull { it.message.id == message.id } ?: RoomRow(message.id, message) }
                    .let { merged -> merged + current.rows.filter { it.state !is RowState.Sent } }
                    .sortedWith(compareBy({ it.message.createdAt }, { it.message.id }))
                else RoomCache.reconcile(current.rows, page, replaceTail = true)
                current.copy(
                    rows = rows,
                    oldest = rows.filter { it.state is RowState.Sent }.minOfOrNull { it.message.createdAt } ?: current.oldest,
                    moreOlder = page.size >= ChatRepositoryPage,
                    loaded = true,
                )
            }
            state.resolveAuthors(session.endpoint, token, page)
            // A message part that names an emoji by id alone gets its picture from here, so a
            // bridged or round-tripped emoji is not the one shape that draws nothing.
            state.resolveMessageEmojis(session.endpoint, token, page)
            val ids = state.rooms.snapshot(key)?.rows.orEmpty().takeLast(50).map { it.message.id }
            runCatching { state.chats.reactionsFor(session.endpoint, token, ids).getOrThrow() }
                .onSuccess {
                    state.rooms.reactions(key, it)
                    // A reaction row carries an emoji's id and no picture; the picture is one
                    // `emojis` read for the page rather than one per pill.
                    state.resolveReactionEmojis(session.endpoint, token, it.values.flatten())
                }
            if (!older && page.isNotEmpty()) {
                val last = page.last().id
                if (direct) {
                    runCatching { state.chats.markConversationRead(session.endpoint, token, roomId, last) }
                    state.clearConversationUnread(roomId)
                } else {
                    runCatching { state.chats.markChannelRead(session.endpoint, token, roomId, last) }
                    state.clearChannelUnread(roomId)
                }
            }
            failure = null
        } catch (error: Exception) {
            // A failed read says this page did not load. Whether the *connection* is down is the
            // socket's to report: it retries by itself, and claiming an outage from one REST
            // failure is what used to put a Retry button in front of a person for a blip the app
            // was already handling.
            failure = error.message ?: "Could not load messages."
        } finally { loading = false; refreshing = false }
    }
    /**
     * Toggles this account's reaction with [key] on [message].
     *
     * [key] is the reaction's own identity (`Reaction.key`), not the display label: a custom
     * emoji's label is `:name:`, and the old comparison could not tell two of them apart, so
     * tapping the second custom pill removed the first. The row the instance stores carries
     * the same key back, which is what makes a second tap remove rather than add.
     *
     * The write follows the reference client's rule (`Reaction.toggleEmojiId`): a pill that names
     * an `emojis` row writes `emoji_id`, anything else writes `custom_emoji_content`. Writing the
     * row id into the content column -- which this used to do for a pill whose row stored no
     * content -- is refused by the instance's own trigger, and removing by the wrong column
     * deleted nothing.
     */
    suspend fun react(message: Message, key: String) {
        val cacheKey = state.roomKey(roomId, direct, thread?.id)
        val token = state.auth.freshToken() ?: error("Sign in again.")
        val rows = state.rooms.snapshot(cacheKey)?.reactions?.get(message.id).orEmpty()
        val existing = rows.firstOrNull { it.key == key }
        val mine = rows.any { it.userId == myId && it.key == key }
        // A pill already on the message keeps its own shape, so joining it writes what it carries.
        val emojiId = existing?.toggleEmojiId ?: key.takeIf { isEmojiRowId(it) }
        val content = if (emojiId != null) null else (existing?.content ?: key)
        if (mine) state.chats.removeReaction(session.endpoint, token, message.id, myId, emojiId, content).getOrThrow()
        else state.chats.addReaction(session.endpoint, token, message.id, myId, emojiId, content).getOrThrow()
        runCatching { state.chats.reactionsFor(session.endpoint, token, listOf(message.id)).getOrThrow() }
            .onSuccess {
                state.rooms.reactions(cacheKey, it)
                state.resolveReactionEmojis(session.endpoint, token, it[message.id].orEmpty())
            }
    }
    fun action(work: suspend () -> Unit) { scope.launch {
        try { work() } catch (error: Exception) { failure = error.message ?: "The action failed." }
    } }

    /**
     * Puts the message in the list and sends it. The row is the person's own until the instance
     * answers; a failure leaves it in place with Retry rather than deleting what they wrote.
     */
    fun send(text: String, parent: String?, nonce: String, retryKey: String?) {
        val key = state.roomKey(roomId, direct, thread?.id)
        val threadId = thread?.id
        val draftMessage = Message(
            id = retryKey ?: nonce, channelId = if (direct) null else roomId,
            conversationId = if (direct) roomId else null, threadId = threadId, userId = myId,
            replyTo = parent, content = listOf(ContentPart.text(text)), createdAt = Instant.now().toString(),
        )
        if (retryKey == null) {
            state.rooms.appendPending(key, RoomRow(nonce, draftMessage, RowState.Sending, nonce))
        } else {
            state.rooms.replace(key, retryKey, RoomRow(retryKey, draftMessage, RowState.Sending, nonce))
        }
        scope.launch {
            try {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                // A retry of a send that actually landed adopts the stored row rather than
                // posting it twice: the instance has no uniqueness rule on the nonce, so the
                // check belongs here, as it is in the web client's outbox.
                val already = if (retryKey == null) null else state.chats.findByNonce(
                    session.endpoint, token, if (direct) null else roomId, if (direct) roomId else null, nonce,
                )
                if (already != null) {
                    state.rooms.update(key) { current ->
                        current.copy(
                            rows = RoomCache.reconcile(
                                current.rows.filterNot { it.key == (retryKey ?: nonce) },
                                listOf(already), replaceTail = false),
                            loaded = true)
                    }
                    state.resolveAuthors(session.endpoint, token, listOf(already))
                    failure = null
                    return@launch
                }
                val sent = if (direct)
                    state.chats.sendDirectMessage(session.endpoint, token, roomId, text, parent, threadId, nonce)
                else state.chats.sendChannelMessage(session.endpoint, token, roomId, text, parent, threadId, nonce)
                val acknowledged = sent.getOrThrow()
                state.rooms.update(key) { current ->
                    current.copy(rows = current.rows
                        .filterNot { it.key == (retryKey ?: nonce) }
                        .let { rows -> RoomCache.reconcile(rows, listOf(acknowledged), replaceTail = false) },
                        loaded = true)
                }
                state.resolveAuthors(session.endpoint, token, listOf(acknowledged))
            } catch (error: Exception) {
                val reason = error.message ?: "Not sent."
                state.rooms.replace(key, retryKey ?: nonce, RoomRow(
                    retryKey ?: nonce, draftMessage, RowState.Failed(reason), nonce,
                ))
                // The text goes back into the composer only if the person has not started
                // writing something else, so a failed send can never overwrite a new draft.
                if (draft.isBlank()) { draft = text; reply = parent?.let { id -> room.rows.firstOrNull { it.message.id == id }?.message } }
                failure = reason
            }
        }
    }

    // A thread opened from the channel list is picked up here, then cleared so returning to
    // the channel later opens the channel itself.
    LaunchedEffect(roomId) {
        val wanted = state.openThreadId.value ?: return@LaunchedEffect
        state.openThreadId.value = null
        runCatching {
            val token = state.auth.freshToken() ?: return@LaunchedEffect
            state.chats.thread(session.endpoint, token, wanted).getOrThrow()
        }.getOrNull()?.let { thread = it }
    }
    LaunchedEffect(draftKey) {
        draft = state.sessionStore.draft(draftKey); draftReady = true
    }
    LaunchedEffect(cacheKey) {
        // The cached rows paint first; the refresh then merges into them. The scroll goes to
        // the newest row only when the room is opened, never on a later refresh.
        if (room.rows.isNotEmpty() && state.focusMessageId.value == null) list.scrollToItem(room.rows.lastIndex)
        load()
    }
    // Pages back until the target message is on screen, then scrolls to it and marks it. A
    // message the account can no longer see is reported once, without its text.
    LaunchedEffect(cacheKey, focusId, room.rows.size) {
        val wanted = focusId ?: return@LaunchedEffect
        var pages = 0
        while (room.rows.none { it.message.id == wanted } && pages < lol.mony.harmony.notifications.NotificationTargets.MAX_TARGET_PAGES &&
            (room.moreOlder || pages == 0)) {
            pages++
            val before = room.rows.size
            load(older = true)
            if (room.rows.size == before) break
        }
        val index = room.rows.indexOfFirst { it.message.id == wanted }
        if (index >= 0) {
            list.scrollToItem(index)
            highlighted = wanted
            kotlinx.coroutines.delay(2_500)
            highlighted = null
            focusId = null
        } else if (pages >= lol.mony.harmony.notifications.NotificationTargets.MAX_TARGET_PAGES || !room.moreOlder) {
            failure = "That message is no longer available."
            focusId = null
        }
    }
    LaunchedEffect(roomId) {
        try {
            val token = state.auth.freshToken() ?: return@LaunchedEffect
            if (direct) title = state.chats.conversation(session.endpoint, token, roomId, myId, state.profiles)?.title ?: "Conversation"
            else title = state.container.rest.select(session.endpoint, token, "channels", filters = listOf("id" to "eq.$roomId"),
                serializer = ListSerializer(Channel.serializer())).getOrThrow().firstOrNull()?.name ?: "Channel"
        } catch (error: Exception) { failure = error.message }
    }
    LaunchedEffect(draftKey, draft) {
        if (draftReady) { delay(250); state.sessionStore.saveDraft(draftKey, draft) }
    }
    LaunchedEffect(roomId) {
        // The token is asked for on every attempt, not taken once: a reconnect after the
        // access token was refreshed must join with the token that is valid now.
        val realtime = state.realtime
        realtime.room(
            session.endpoint,
            token = { state.auth.freshToken() },
            roomId = roomId,
            direct = direct,
            retryNow = state.retryNow,
        ).collect { event ->
            when (event) {
                LiveUpdate.Connected -> { state.markLive(LiveStatus.Connected); load() }
                LiveUpdate.Changed -> load()
                is LiveUpdate.Reconnecting -> state.markLive(LiveStatus.Reconnecting(event.attempt, event.reason))
                is LiveUpdate.Unavailable -> state.markLive(LiveStatus.Unavailable(event.reason))
            }
        }
    }
    BackHandler { if (thread != null) thread = null else onBack() }

    // Deferred content -- a bottom sheet's body, a lazy list's items, a dialog's buttons -- runs
    // after this composable body has returned, so each one closes over an immutable snapshot of
    // the state it draws. Reading the mutable `var` there is how opening a thread from the sheet
    // crashed: the row's click set `threads = null`, and the list's next recomposition then
    // dereferenced it.
    val openFailure = failure
    val openReply = reply
    val openSelected = selected
    val openEdit = edit
    val openDelete = delete
    val openPins = pins
    val openThreads = threads
    val openSearchRows = searchRows
    val openSearchOpen = searchOpen
    val rows = room.rows
    val reactions = room.reactions

    Column(Modifier.fillMaxSize().imePadding()) {
        // One app bar, with the title and its kind stacked, and the two actions a person uses
        // while reading kept: search and the overflow. Refresh, pins and threads are in the
        // overflow rather than four icons competing with the channel's name, which is what the
        // old bar did on a phone.
        TopAppBar(
            title = {
                Column {
                    Text(thread?.name ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (thread != null) "Thread in " + title
                        else if (direct) "Direct message" else "Channel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { if (thread != null) thread = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = {
                IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Search") }
                Box {
                    IconButton(onClick = { roomMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                        DropdownMenuItem(text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { roomMenu = false; action { load() } })
                        if (!direct) {
                            DropdownMenuItem(text = { Text("Pinned messages") },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                onClick = { roomMenu = false; action { val token = state.auth.freshToken() ?: return@action
                                    pins = state.chats.pinned(session.endpoint, token, roomId).getOrThrow() } })
                            DropdownMenuItem(text = { Text("Threads") },
                                leadingIcon = { Icon(Icons.Default.Forum, null) },
                                onClick = { roomMenu = false; action { val token = state.auth.freshToken() ?: return@action
                                    threads = state.chats.threads(session.endpoint, token, roomId).getOrThrow()
                                    state.rememberThreads(roomId, threads.orEmpty()) } })
                        }
                    }
                }
            },
        )
        // The connection banner is drawn only while the instance cannot be reached. A healthy
        // connection says nothing: an always-on label is noise that hides a real outage.
        ConnectionBanner(live) { state.retryNow(); action { load() } }
        if (openFailure != null) ErrorBanner(openFailure) { failure = null }
        if (refreshing && rows.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && rows.isEmpty()) LoadingState("Loading messages")
            else if (rows.isEmpty()) EmptyState("No messages yet", "Send the first message in this " + (if (direct) "conversation" else "channel") + ".")
            else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("message-list"),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                if (room.moreOlder) item(key = "older") {
                    TextButton(onClick = { action { load(true) } }, modifier = Modifier.testTag("load-older")) { Text("Load older") }
                }
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    val previous = rows.getOrNull(index - 1)?.message
                    MessageRow(
                        row = row,
                        author = row.message.author(people, bots),
                        showHeader = showsAuthorHeader(row.message, previous),
                        baseUrl = session.endpoint.baseUrl,
                        appearance = appearance,
                        reactions = reactions[row.message.id].orEmpty(),
                        emojis = emojiRows,
                        myId = myId,
                        onActions = { selected = row.message },
                        onReact = { key -> if (!row.isPending) action { react(row.message, key) } },
                        onAuthor = { profileId -> selectedProfile = profileId },
                        onShowReactors = { key -> reactors = row.message to key },
                        onReply = { reply = row.message },
                        onRetry = { if (row.isFailed) send(row.message.plainText, row.message.replyTo, row.nonce ?: row.key, row.key) },
                        highlighted = highlighted == row.message.id,
                    )
                }
            }
        }
        if (openReply != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp).testTag("reply-banner"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Replying to " + openReply.author(people, bots).name + ": " + openReply.plainText.take(60),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { reply = null }) { Icon(Icons.Default.Close, "Cancel reply") }
            }
        }
        // The composer is a docked surface, not a text field with a button beside it: the field
        // and its send action share one rounded container, which is what makes it read as a
        // single thing at the bottom of the screen instead of two controls that happen to be
        // adjacent. The container takes the top corners only, because its bottom edge is the
        // screen's own.
        Surface(
            color = LocalHarmonyColors.current.surfaceRaised,
            shape = HarmonyShape.sheet,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().testTag("composer-surface"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // A long message is not truncated as it is typed: pasting one keeps every
                // character, the counter turns red, and Send waits. Swallowing the keystrokes
                // instead would look like a broken keyboard, and the instance would refuse the
                // message in a trigger anyway -- which reads as a network failure.
                OutlinedTextField(
                    draft, { draft = it },
                    placeholder = { Text("Message " + (if (direct) title else "#" + title)) },
                    modifier = Modifier.weight(1f).testTag("message-composer"), maxLines = 5,
                    shape = HarmonyShape.large,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    supportingText = if (draft.length > ChatRepository.DEFAULT_MAX_LENGTH - 200) ({
                        Text(draft.length.toString() + " / " + ChatRepository.DEFAULT_MAX_LENGTH,
                            Modifier.testTag("message-length"))
                    }) else null,
                    isError = draft.length > ChatRepository.DEFAULT_MAX_LENGTH,
                )
                // The send action is a filled circle: the one thing at the bottom of a channel
                // that must be unmissable. It never turns into a spinner, because the row in the
                // list is the progress and keeping the button usable is what lets a second
                // message be written.
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = draft.isNotBlank() && draft.length <= ChatRepository.DEFAULT_MAX_LENGTH,
                    onClick = {
                        val text = draft
                        val parent = reply?.id
                        draft = ""; reply = null
                        scope.launch { state.sessionStore.saveDraft(draftKey, "") }
                        send(text, parent, UUID.randomUUID().toString(), null)
                        scope.launch { if (rows.isNotEmpty()) list.animateScrollToItem(rows.lastIndex) }
                    },
                    modifier = Modifier.size(52.dp).testTag("message-send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
    // The message-actions sheet. `message` is captured once, so a click that closes the sheet
    // cannot leave the body reading a cleared state.
    if (openSelected != null) {
        val message = openSelected
        val author = message.author(people, bots).name
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            // The quick reactions stay a row of faces above the list: a reaction is the one
            // action a person takes without reading anything, so it does not belong in the
            // list of verbs below it.
            Row(Modifier.fillMaxWidth().padding(horizontal = HarmonySize.gutter), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("👍", "❤️", "😂", "🎉", "👀").forEach { emoji ->
                    TextButton(onClick = { selected = null; action { react(message, emoji) } }) { Text(emoji, fontSize = 24.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = HarmonySize.gutterLarge))
            ActionSheetContent(
                title = "Message from $author",
                actions = buildList {
                    add(ActionItem("Reply", Icons.AutoMirrored.Filled.Reply, onClick = { reply = message; selected = null }))
                    add(ActionItem("Copy text", Icons.Default.ContentCopy, onClick = {
                        clipboard.setText(AnnotatedString(message.plainText)); selected = null
                    }))
                    if (!direct) {
                        add(ActionItem("Start thread", Icons.Default.Forum,
                            detail = "Replies collect away from the channel",
                            onClick = {
                                selected = null
                                action {
                                    val token = state.auth.freshToken() ?: error("Sign in again.")
                                    thread = state.chats.createThread(session.endpoint, token, roomId, message.id, "Discussion").getOrThrow()
                                }
                            }))
                        add(ActionItem(
                            if (message.isPinned) "Unpin from this channel" else "Pin to this channel",
                            Icons.Default.PushPin,
                            onClick = {
                                selected = null
                                action {
                                    val token = state.auth.freshToken() ?: error("Sign in again.")
                                    state.chats.pin(session.endpoint, token, message.id, !message.isPinned).getOrThrow(); load()
                                }
                            },
                        ))
                    }
                    if (message.userId == myId) {
                        if (message.isEditable) {
                            add(ActionItem("Edit", Icons.Default.Edit, onClick = { edit = message; editText = message.plainText; selected = null }))
                        }
                        add(ActionItem("Delete", Icons.Default.Delete, detail = "This cannot be undone", destructive = true,
                            onClick = { delete = message; selected = null }))
                    }
                },
            )
        }
    }
    if (openEdit != null) {
        val message = openEdit
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit message") },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.testTag("message-edit-text")) },
            confirmButton = { TextButton(enabled = editText.isNotBlank(), onClick = { edit = null; action {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                state.chats.editMessage(session.endpoint, token, message.id, editText).getOrThrow(); load()
            } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    if (openDelete != null) {
        val message = openDelete
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete message?") },
            text = { Text("This cannot be undone.") }, confirmButton = { TextButton(onClick = { delete = null; action {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                state.chats.deleteMessage(session.endpoint, token, message.id).getOrThrow()
                val key = state.roomKey(roomId, direct, thread?.id)
                state.rooms.remove(key, message.id)
            } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
    if (openPins != null) {
        val pinned = openPins
        ModalBottomSheet(onDismissRequest = { pins = null }) {
            Text("Pinned", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.testTag("pinned-list")) {
                if (pinned.isEmpty()) item { Text("No pinned messages", Modifier.padding(16.dp)) }
                items(pinned, key = { it.id }) { row ->
                    Column(Modifier.padding(16.dp)) {
                        Text(row.author(people, bots).name, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                        MessageContent(row, sizes.messageSp)
                    }
                }
            }
        }
    }
    if (openThreads != null) {
        val listed = openThreads
        ModalBottomSheet(onDismissRequest = { threads = null }) {
            Text("Threads", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.testTag("thread-list")) {
                val active = listed.filter { it.isActive() }
                if (active.isEmpty()) item { Text("No open threads. Long-press a message to start one.", Modifier.padding(16.dp)) }
                items(active, key = { it.id }) { row -> ListItem(headlineContent = { Text(row.name ?: "Thread") },
                    supportingContent = { Text(countLabel(row.messageCount, "message")) },
                    modifier = Modifier.combinedClickable(onClick = { thread = row; threads = null })) }
            }
        }
    }
    // Who reacted: the pill shows a count, this names them. The identities come from the
    // reaction rows the page already loaded, resolved through the same people map the row
    // drew its author from, so a reactor is never a placeholder.
    val openReactors = reactors
    if (openReactors != null) {
        val (reactedMessage, emoji) = openReactors
        // The same key the pill was drawn under, so the sheet lists exactly that pill's people.
        val who = reactions[reactedMessage.id].orEmpty().filter { it.key == emoji }
        // The title draws the same emoji the pill drew: its picture when the row was read, else
        // the shortcode it stores -- never the id the row is keyed by.
        val pill = reactionPills(who, emojiRows, myId).firstOrNull()
            ?: reactionPills(listOf(Reaction(messageId = reactedMessage.id, customEmojiContent = emoji)), emojiRows, myId).first()
        ModalBottomSheet(onDismissRequest = { reactors = null }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("reactors-title")) {
                    EmojiGlyph(emoji = pill.emoji, label = pill.label, size = 24.dp,
                        baseUrl = session.endpoint.baseUrl, modifier = Modifier.testTag("reactors-emoji"))
                    Spacer(Modifier.width(8.dp))
                    Text(pill.count.toString(), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                if (who.isEmpty()) Text("Nobody has reacted with this yet.", style = MaterialTheme.typography.bodyMedium)
                for (reaction in who) {
                    val person = reaction.userId?.let { people[it] }
                    val name = person?.shownName ?: reaction.userId?.let { "Account " + it.take(8) } ?: "Someone"
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Avatar(name, person?.avatarUrl, size = 32.dp, baseUrl = session.endpoint.baseUrl)
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
    // Tapping an author opens who they are, as the web client's profile card does.
    val openProfileId = selectedProfile
    if (openProfileId != null) {
        val person = people[openProfileId]
        ModalBottomSheet(onDismissRequest = { selectedProfile = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (person != null) {
                    Avatar(person.shownName, person.avatarUrl, size = 88.dp, baseUrl = session.endpoint.baseUrl,
                        modifier = Modifier.testTag("author-profile-avatar"))
                    Spacer(Modifier.height(12.dp))
                    Text(person.shownName, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("author-profile-name"))
                    Text(person.handle(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!person.pronouns.isNullOrBlank()) Text(person.pronouns!!,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!person.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(person.bio!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (person.authUserId != null && person.authUserId != myId) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            val other = person.id
                            selectedProfile = null
                            action {
                                val token = state.auth.freshToken() ?: error("Sign in again.")
                                val conversation = state.chats.openDirectConversation(session.endpoint, token, myId, other).getOrThrow()
                                state.openConversationId.value = conversation
                                state.showingDms.value = true
                            }
                        }) { Text("Message") }
                    }
                } else {
                    Text("This account is unavailable.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { selectedProfile = null }) { Text("Close") }
            }
        }
    }
    if (openSearchOpen) ModalBottomSheet(onDismissRequest = { searchOpen = false }) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { action { val token = state.auth.freshToken() ?: return@action
                searchRows = state.chats.search(session.endpoint, token, query, channelId = if (direct) null else roomId).getOrThrow() } },
                enabled = query.isNotBlank()) { Text("Search") }
            LazyColumn { items(openSearchRows.orEmpty(), key = { it.id }) { row ->
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(row.author(people, bots).name, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    MessageContent(row, sizes.messageSp)
                }
            }
                if (openSearchRows?.isEmpty() == true) item { Text("No results") } }
        }
    }
}

private const val ChatRepositoryPage = 50

/** `1 message`, `2 messages`: a count and its noun, as the web client writes it. */
internal fun countLabel(count: Int, noun: String): String =
    count.toString() + " " + noun + (if (count == 1) "" else "s")

/**
 * The connection banner: what the live connection is doing when it is not simply connected.
 *
 * It has two states, and the difference is what the app is doing about it:
 *
 * - [LiveStatus.Reconnecting] -- the socket dropped and the app is putting it back by itself.
 *   The banner says so, and its button *skips the wait* rather than being the way to connect:
 *   a person who has just walked out of a tunnel should not have to wait out a backoff, and a
 *   person who does nothing must still get their room back.
 * - [LiveStatus.Unavailable] -- the instance refused the connection, so no retry on a timer
 *   can help and the banner says why instead of offering one that would fail.
 *
 * A connected client draws nothing here. The old always-on "Live updates connected" line took
 * a strip of every screen and made the one state worth noticing -- being offline -- look like
 * every other.
 */
@Composable
internal fun ConnectionBanner(status: LiveStatus, onRetry: () -> Unit) {
    val reason: String
    val action: String
    val container: androidx.compose.ui.graphics.Color
    val content: androidx.compose.ui.graphics.Color
    when (status) {
        is LiveStatus.Reconnecting -> {
            // The reason says what happened; this says what the app is doing about it. The
            // attempt count only appears once it is worth knowing -- "Retrying (1)" would draw
            // attention to the first try of a blip that is already over.
            reason = status.reason + " Reconnecting" +
                (if (status.attempt > 1) " (attempt ${status.attempt})" else "") + "."
            action = "Retry now"
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.onSecondaryContainer
        }
        is LiveStatus.Unavailable -> {
            reason = status.reason
            action = "Refresh"
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        // Connected, and the first attempt before it has answered: nothing worth a strip.
        LiveStatus.Connected, LiveStatus.Connecting -> return
    }
    Surface(color = container, modifier = Modifier.fillMaxWidth().testTag("connection-banner")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status is LiveStatus.Reconnecting)
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
            else Icon(Icons.Default.CloudOff, null, Modifier.size(16.dp), tint = content)
            Spacer(Modifier.width(8.dp))
            Text(reason, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = content)
            TextButton(onClick = onRetry, modifier = Modifier.testTag("connection-retry")) { Text(action) }
        }
    }
}

/**
 * Whether a row repeats the author's header, as the web client's `shouldShowHeader` decides.
 *
 * One author's consecutive messages group under a single header until five minutes pass, the
 * author changes, the message answers something, or a system event interrupts. Grouping is what
 * makes a busy channel readable; drawing the header on every row wastes the width a phone has.
 */
internal fun showsAuthorHeader(message: Message, previous: Message?): Boolean {
    if (previous == null) return true
    if (message.isSystem || previous.isSystem) return true
    if (message.authorKey() != previous.authorKey()) return true
    if (message.replyTo != null) return true
    val gap = runCatching {
        Instant.parse(message.createdAt).toEpochMilli() - Instant.parse(previous.createdAt).toEpochMilli()
    }.getOrDefault(Long.MAX_VALUE)
    return gap > 5 * 60 * 1000
}

/**
 * One message: its author, its body and its reactions, plus the two gestures a messaging app
 * has here -- long-press for actions and a horizontal drag to reply.
 *
 * The drag itself lives in [SwipeToReplyRow], which owns its direction: it travels *away* from
 * the start edge, because a drawer opens the other way. The same reply is reachable from the
 * actions sheet and from a custom accessibility action, so a person who cannot perform a drag
 * is not locked out of it.
 */
@Composable
private fun MessageRow(
    row: RoomRow, author: MessageAuthor, showHeader: Boolean, baseUrl: String?,
    appearance: VisualThemeSettings, reactions: List<Reaction>, emojis: Map<String, Emoji>, myId: String,
    onActions: () -> Unit, onReact: (String) -> Unit, onAuthor: (String?) -> Unit,
    onShowReactors: (String) -> Unit, onReply: () -> Unit, onRetry: () -> Unit,
    highlighted: Boolean = false,
) {
    val message = row.message
    val sizes = LocalSizes.current
    val name = if (message.isSystem) "System" else author.name
    val time = runCatching { DateTimeFormatter.ofPattern(if (appearance.use24HourTime) "HH:mm" else "h:mm a")
        .withZone(ZoneId.systemDefault()).format(Instant.parse(message.createdAt)) }.getOrDefault("")

    SwipeToReplyRow(
        key = row.key,
        onReply = onReply,
        modifier = Modifier
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) else Modifier)
            .testTag(if (highlighted) "message-highlighted" else "message-row-" + row.key),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onActions)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Reply") { onReply(); true },
                        CustomAccessibilityAction("Message actions") { onActions(); true },
                    )
                }
                .alpha(if (row.isPending) 0.55f else 1f)
                .testTag(if (row.isPending) "message-pending" else "message-" + message.id)
                .padding(horizontal = 12.dp, vertical = sizes.rowVerticalPadding),
        ) {
            if (!appearance.compactMode) {
                if (showHeader) {
                    Avatar(name, author.avatarPath, size = 40.dp, baseUrl = baseUrl,
                        modifier = Modifier.combinedClickable(onClick = { onAuthor(author.profileId) }, onLongClick = onActions))
                } else {
                    // A grouped row keeps the avatar's width so the text column stays aligned.
                    Spacer(Modifier.size(40.dp))
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                if (showHeader) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1,
                        modifier = Modifier.combinedClickable(onClick = { onAuthor(author.profileId) }, onLongClick = onActions))
                    if (author.isBot) BotBadge()
                    if (message.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(12.dp))
                    if (appearance.showTimestamps) Text(time, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.replyTo != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("Reply", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MessageContent(message, sizes.messageSp, baseUrl = baseUrl, emojis = emojis,
                    encryptedNotice = if (message.encrypted) "Encrypted message. Keys are not available in this client." else null)
                if (row.isPending) Text("Sending…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sending-label"))
                if (row.state is RowState.Failed) Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("send-failed")) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(row.state.reason, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("send-retry")) { Text("Retry") }
                }
                if (reactions.isNotEmpty()) MessageReactions(
                    message = message, reactions = reactions, myId = myId,
                    onToggle = onReact, onShowReactors = onShowReactors,
                    emojis = emojis, baseUrl = baseUrl,
                )
            }
        }
    }
}

/**
 * The badge the web client puts after a bot's name: a small primary-coloured tag.
 *
 * It is what tells a person the account is a bot before they read the message, so it is
 * drawn with the same weight and colour as the reference rather than as plain text.
 */
@Composable
private fun BotBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(3.dp),
    ) {
        Text(
            "BOT",
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

