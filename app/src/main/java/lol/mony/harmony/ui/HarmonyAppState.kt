package lol.mony.harmony.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.DirectConversation
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.ServerTree
import lol.mony.harmony.data.model.Thread
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.UnreadCountRow
import lol.mony.harmony.data.repo.BotRepository
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.repo.ProfileRepository
import lol.mony.harmony.data.repo.ServerRepository
import lol.mony.harmony.data.repo.SettingsRepository
import lol.mony.harmony.data.session.AuthRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.ui.components.TextToken
import lol.mony.harmony.ui.components.textTokens
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * Process-wide state the screens share: who is signed in, what is selected, unread badges,
 * typing and the people already resolved.
 *
 * It is deliberately plain state rather than an event bus: the app has one window, and every
 * screen already reads through the same repositories.
 */
class AppState(
    val container: lol.mony.harmony.AppContainer,
    val auth: AuthRepository = container.auth,
    val servers: ServerRepository = container.servers,
    val chats: ChatRepository = container.chats,
    val botRepo: BotRepository = container.bots,
    val profiles: ProfileRepository = container.profiles,
    val settings: SettingsRepository = container.settings,
    val sessionStore: SessionStore = container.sessionStore,
    val realtime: lol.mony.harmony.data.api.Realtime = container.realtime,
    private val network: lol.mony.harmony.data.api.NetworkMonitor = container.network,
) {
    /** The signed-in account's profile id, resolved once per session. */
    val profileId = MutableStateFlow<String?>(null)

    /** The server the channel column shows; null means the DM list. */
    val selectedServerId = MutableStateFlow<String?>(null)
    val showingDms = MutableStateFlow(true)

    /** A thread the channel list opened, which the room reads and then clears. */
    val openThreadId = MutableStateFlow<String?>(null)

    /**
     * A message a notification asked for, which the room scrolls to and highlights once, then
     * clears. It survives the room not being open yet: the room reads it when it opens.
     */
    val focusMessageId = MutableStateFlow<String?>(null)

    /** The destination a tapped notification named, for the shell to open. */
    val pendingDestination = MutableStateFlow<lol.mony.harmony.data.model.NotificationDestination?>(null)

    /** The open conversation: a channel id, or a DM conversation id. */
    val openChannelId = MutableStateFlow<String?>(null)
    val openConversationId = MutableStateFlow<String?>(null)

    /** Bumped when the server list changes shape, so the rail reloads. */
    val serversRevision = MutableStateFlow(0)

    /**
     * The servers this account is a member of, filled once by [loadServers] and read by every
     * place that needs the list: the space switcher, the channel list's header, the create-server
     * sheet. One read, one place, so two lists cannot disagree about which servers exist.
     */
    private val _serversByMembership = MutableStateFlow<List<lol.mony.harmony.data.model.ServerWithMembership>>(emptyList())
    val serversByMembership: StateFlow<List<lol.mony.harmony.data.model.ServerWithMembership>> =
        _serversByMembership.asStateFlow()

    /**
     * Reads the server list, keeping the previous one on failure so a network blip does not
     * empty the switcher. The result says whether the read succeeded, so a caller can tell a
     * real empty list from a failed one.
     */
    suspend fun loadServers(): Boolean {
        val session = auth.state.value as? AuthState.SignedIn ?: return false
        val token = auth.freshToken() ?: return false
        val authUserId = session.profile?.id ?: return false
        return servers.servers(session.endpoint, token, authUserId)
            .onSuccess { _serversByMembership.value = it }
            .isSuccess
    }

    /** Channels per server, filled by the channel column and read by the rail's badges. */
    val channelsByServer = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())

    /**
     * The server trees read so far, so moving between channels does not re-read a server@AP@s
     * channels and categories on every visit. Cleared on sign-out and by a pull to refresh.
     */
    val serverTrees = MutableStateFlow<Map<String, ServerTree>>(emptyMap())

    /** The DM list read so far; null until it has been read once. */
    val conversations = MutableStateFlow<List<DirectConversation>?>(null)

    /** Set by the rail's "+", cleared by the create-server sheet. */
    val showCreateServer = MutableStateFlow(false)

    private val _unread = MutableStateFlow(UnreadBadges())
    val unread: StateFlow<UnreadBadges> = _unread.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> = _typing.asStateFlow()

    private val _people = MutableStateFlow<Map<String, Profile>>(emptyMap())

    /**
     * The profiles resolved so far, keyed by both `profiles.id` and `profiles.auth_user_id`.
     *
     * A message names its author by `profiles.id`, but the session, membership rows and the JWT
     * speak `auth_user_id`; for an account made before its profile they differ, so both keys
     * point at the same row and either spelling finds the person.
     */
    val people: StateFlow<Map<String, Profile>> = _people.asStateFlow()

    private val _bots = MutableStateFlow<Map<String, Bot>>(emptyMap())

    /** The bots resolved so far, keyed by `bots.id`, which is what `messages.bot_id` points at. */
    val bots: StateFlow<Map<String, Bot>> = _bots.asStateFlow()

    fun rememberPeople(profiles: Collection<Profile>) {
        if (profiles.isEmpty()) return
        _people.update { current ->
            val next = current.toMutableMap()
            for (profile in profiles) {
                next[profile.id] = profile
                profile.authUserId?.takeIf { it.isNotBlank() }?.let { next[it] = profile }
            }
            next
        }
    }

    fun rememberBots(bots: Collection<Bot>) {
        if (bots.isEmpty()) return
        _bots.update { current -> current + bots.associateBy { it.id } }
    }

    private val _emojis = MutableStateFlow<Map<String, Emoji>>(emptyMap())

    /**
     * The `emojis` rows resolved so far, keyed by `emojis.id`.
     *
     * A reaction row names a custom emoji by id and carries no picture, and a message part may
     * carry a stale row; both are drawn from here. Without it a custom emoji reaction is a uuid,
     * which is the bug this map exists to close.
     */
    val emojis: StateFlow<Map<String, Emoji>> = _emojis.asStateFlow()

    fun rememberEmojis(rows: Collection<Emoji>) {
        val keyed = rows.mapNotNull { row -> row.id?.takeIf { it.isNotBlank() }?.let { it to row } }
        if (keyed.isEmpty()) return
        _emojis.update { current -> current + keyed.toMap() }
    }

    fun person(authUserId: String?): Profile? = authUserId?.let { _people.value[it] }

    /**
     * Resolves every emoji a page's reactions name, so a custom emoji is drawn as its picture.
     *
     * The page's reaction rows are read first and the ids they name are looked up once, which is
     * one request per page rather than one per pill. A failed lookup is swallowed for the same
     * reason [resolveAuthors] swallows one: a pill then draws the shortcode the reaction stores
     * rather than an id, and the rest of the page is unaffected.
     */
    suspend fun resolveReactionEmojis(
        endpoint: lol.mony.harmony.data.api.Endpoint,
        token: String,
        reactions: Collection<lol.mony.harmony.data.model.Reaction>,
    ) {
        val known = _emojis.value
        val ids = reactions.mapNotNull { it.emojiId }.filter { it !in known }.distinct()
        if (ids.isEmpty()) return
        runCatching { rememberEmojis(chats.emojis(endpoint, token, ids).values) }
    }

    /**
     * Resolves the emoji parts of a page of messages.
     *
     * A part carries the `emojis` row it was written from, so most parts need nothing; an id-only
     * part (one a bridged client wrote, or one whose row a sender stripped) gets its picture here.
     * An emoji a page already names is not read again.
     */
    suspend fun resolveMessageEmojis(
        endpoint: lol.mony.harmony.data.api.Endpoint,
        token: String,
        messages: Collection<lol.mony.harmony.data.model.Message>,
    ) {
        val known = _emojis.value
        val parts = messages.flatMap { message -> message.content }
        val ids = parts.mapNotNull { part -> part.emoji?.takeIf { !it.hasImage }?.id }
            .filter { it !in known }.distinct()
        if (ids.isNotEmpty()) runCatching { rememberEmojis(chats.emojis(endpoint, token, ids).values) }
        // A `:shortcode:` written into the text is how most custom emoji are used: this client has
        // no picker, so the name is what a person types, and the reference draws its picture.
        val knownNames = _emojis.value.values.mapNotNull { it.name }.toSet()
        val names = parts.filter { it.type == "text" }
            .flatMap { part -> textTokens(part.text.orEmpty()) }
            .filterIsInstance<TextToken.Shortcode>()
            .map { it.name }.filter { it !in knownNames }.distinct()
        if (names.isEmpty()) return
        runCatching { rememberEmojis(chats.emojisNamed(endpoint, token, names).values) }
    }

    /**
     * Resolves every author a page of messages names, so no row draws a name it has not looked
     * up. A failed lookup is swallowed on purpose: the row then draws what the message itself
     * carries, which is better than emptying the page over one unreadable profile.
     */
    suspend fun resolveAuthors(
        endpoint: lol.mony.harmony.data.api.Endpoint,
        token: String,
        messages: Collection<lol.mony.harmony.data.model.Message>,
    ) {
        val known = _people.value
        val userIds = messages.mapNotNull { it.userId }.filter { it !in known }.distinct()
        val botIds = messages.mapNotNull { it.botId }.filter { it !in _bots.value }.distinct()
        if (userIds.isNotEmpty()) runCatching { rememberPeople(container.profiles.byIds(endpoint, token, userIds).values) }
        if (botIds.isNotEmpty()) runCatching { rememberBots(botRepo.byIds(endpoint, token, botIds).values) }
    }

    private val _threadsByChannel = MutableStateFlow<Map<String, List<Thread>>>(emptyMap())
    val threadsByChannel: StateFlow<Map<String, List<Thread>>> = _threadsByChannel.asStateFlow()

    // ---- private attachments --------------------------------------------------------------

    /**
     * Signed URLs for the `message_media` objects already asked for, keyed by object name.
     *
     * An attachment part carries a `path` into the instance's private bucket, and that path
     * resolves for nobody without a token. Signing is a network call, so one page of history is
     * signed in one batch and every row that names an object reads its answer here; a path that
     * could not be signed is remembered as a failure with the time it happened, so a channel of
     * gone pictures does not retry on every recomposition.
     */
    private val _signedMedia = MutableStateFlow<Map<String, SignedMedia>>(emptyMap())
    val signedMedia: StateFlow<Map<String, SignedMedia>> = _signedMedia.asStateFlow()

    /**
     * Signs every object in [paths] that is not already signed, in one request.
     *
     * A path signed within the last [SIGNED_MEDIA_REUSE_MS] is left alone: a channel refresh
     * must not re-sign a page it already has. A path that failed is retried after
     * [SIGNED_MEDIA_RETRY_MS], so a URL that merely expired is picked up again.
     */
    suspend fun signMedia(endpoint: lol.mony.harmony.data.api.Endpoint, token: String, paths: Collection<String>) {
        val now = System.currentTimeMillis()
        val wanted = paths.filter { path ->
            val known = _signedMedia.value[path]
            known == null ||
                (known.url != null && now - known.at > SIGNED_MEDIA_REUSE_MS) ||
                (known.url == null && now - known.at > SIGNED_MEDIA_RETRY_MS)
        }.distinct()
        if (wanted.isEmpty()) return
        val signed = runCatching { container.media.sign(endpoint, token, wanted) }.getOrDefault(emptyMap())
        _signedMedia.update { current ->
            current + wanted.associateWith { path ->
                SignedMedia(signed[path], System.currentTimeMillis())
            }
        }
    }

    fun rememberThread(thread: Thread) {
        _threadsByChannel.update { current ->
            val existing = current[thread.channelId].orEmpty()
            current + (thread.channelId to (listOf(thread) + existing.filter { it.id != thread.id }))
        }
    }

    /** Forgets every cached list, so nothing from one account can show under another. */
    fun forgetCachedLists() {
        _threadsByChannel.value = emptyMap()
        _signedMedia.value = emptyMap()
        channelsByServer.value = emptyMap()
        serverTrees.value = emptyMap()
        conversations.value = null
        rooms.clear()
    }

    // ---- open rooms -----------------------------------------------------------------------

    /**
     * The messages already read, per room, so a channel paints its history at once instead of
     * showing a spinner every time it is opened.
     *
     * A room's key carries the account and instance it was read under, so two accounts on one
     * device can never share a row. The cache is dropped on sign-out and on an account change.
     */
    val rooms = lol.mony.harmony.ui.screens.chat.RoomCache()

    /** The room key a cache entry uses: account, instance and room, in that order. */
    fun roomKey(roomId: String, direct: Boolean, threadId: String? = null): String {
        val session = auth.state.value as? AuthState.SignedIn
        val account = session?.profile?.id ?: profileId.value ?: "anonymous"
        val instance = session?.endpoint?.baseUrl ?: ""
        return listOf(account, instance, if (direct) "dm" else "channel", roomId, threadId ?: "").joinToString("|")
    }

    /**
     * Only the *open* threads of a channel, by the reference's rule: archived threads and
     * threads whose auto-archive clock has run out are not in the sidebar.
     *
     * The channel list used to nest every thread the instance returned, which put closed
     * conversations back in front of the reader on every visit.
     */
    fun rememberThreads(channelId: String, threads: List<Thread>) {
        val active = threads.filter { it.isActive() }
            .sortedWith(compareByDescending<Thread> { it.lastActivityAt ?: it.createdAt ?: "" })
        _threadsByChannel.update { it + (channelId to active) }
    }

    // ---- live connection ------------------------------------------------------------------

    /**
     * Whether the instance is currently reachable, and why not when it is not.
     *
     * The socket is the only thing that reports this: it is the one component that both knows
     * when the connection is up and puts it back when it drops. A REST call that fails says
     * something about that one request, not about the connection, so it does not write here.
     *
     * The app shows a banner for [LiveStatus.Reconnecting] and nothing at all while it is
     * connected: an always-visible "connected" label is noise that makes a real outage harder
     * to notice.
     */
    private val _live = MutableStateFlow<LiveStatus>(LiveStatus.Connecting)
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    fun markLive(status: LiveStatus) { _live.value = status }

    /**
     * Something to skip the reconnect backoff: the person pressed Retry, or the device regained
     * a network.
     *
     * The room's loop listens to it, so a wait that would have lasted half a minute ends now.
     * It is deliberately only an accelerator -- the loop reconnects without it, which is what
     * "auto reconnect" has to mean, and a phone that comes out of a tunnel with nobody looking
     * at it still gets its room back.
     */
    private val retryNudges = MutableStateFlow(0)

    /**
     * The nudges themselves: the person's button, and the device regaining a network.
     *
     * One flow rather than two arguments because a room only ever wants "try now"; where that
     * came from does not change what it does.
     */
    val retryNow: kotlinx.coroutines.flow.Flow<Unit> =
        kotlinx.coroutines.flow.merge(retryNudges.drop(1).map { }, network.regained())

    fun retryNow() { retryNudges.value = retryNudges.value + 1 }

    /** True while the connection is down and the app is putting it back. */
    val isReconnecting: Boolean get() = _live.value is LiveStatus.Reconnecting

    fun setTyping(channelId: String, names: List<String>) {
        _typing.update { it + (channelId to names) }
    }

    fun clearTyping(channelId: String) {
        _typing.update { it - channelId }
    }

    fun applyUnread(rows: List<UnreadCountRow>) {
        val channels = rows.mapNotNull { row -> row.channelId?.takeIf { row.unreadMessages > 0 }?.let { it to row.unreadMessages } }.toMap()
        val mentions = rows.mapNotNull { row -> row.channelId?.takeIf { row.unreadMentions > 0 }?.let { it to row.unreadMentions } }.toMap()
        val conversations = rows.mapNotNull { row ->
            row.conversationId?.takeIf { row.unreadMessages > 0 }?.let { it to row.unreadMessages }
        }.toMap()
        _unread.value = UnreadBadges(channels, conversations, mentions)
    }

    fun clearChannelUnread(channelId: String) {
        _unread.update { current ->
            current.copy(channels = current.channels - channelId, mentions = current.mentions - channelId)
        }
    }

    fun clearConversationUnread(conversationId: String) {
        _unread.update { current -> current.copy(conversations = current.conversations - conversationId) }
    }

    /** Refreshes the badges from the database's own counts. */
    suspend fun refreshUnread() {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        val token = auth.freshToken() ?: return
        applyUnread(chats.unreadCounts(session.endpoint, token))
    }

    /** Sets up the signed-in session: profile, people, badges. */
    suspend fun onSignedIn(session: AuthState.SignedIn) {
        val token = auth.freshToken() ?: return
        val profile = session.profile ?: return
        // A different account must start from an empty cache, never the previous one's lists.
        if (profileId.value != null && profileId.value != profile.id) forgetCachedLists()
        profileId.value = profile.id
        rememberPeople(listOf(profile))
        refreshUnread()
    }

    // ---- appearance ---------------------------------------------------------------------

    /**
     * The device's own copy of the look, kept in the same JSON shape the account stores, so a
     * device that keeps its own look - or a signed-out app - has something to wear.
     */
    private val _deviceAppearance = MutableStateFlow<VisualThemeSettings?>(null)
    val deviceAppearance: StateFlow<VisualThemeSettings?> = _deviceAppearance.asStateFlow()

    fun rememberDeviceAppearance(settings: VisualThemeSettings) {
        _deviceAppearance.value = settings
    }

    suspend fun saveDeviceAppearance(settings: VisualThemeSettings) {
        rememberDeviceAppearance(settings)
        val id = profileId.value ?: return
        sessionStore.saveAppearance(id, JsonObject(VisualThemeSettings.toJsonMap(settings)))
    }

    /** Writes the look to the account, so it follows to other devices and to the web client. */
    suspend fun persistAccountAppearance(settings: VisualThemeSettings) {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        val token = auth.freshToken() ?: return
        val profileId = profileId.value ?: session.profile?.id ?: return
        this.settings.saveAppearance(session.endpoint, token, profileId, settings)
    }

    /** True while this device keeps its own look (the web client's `appearance-sync`). */
    suspend fun appearanceIsDeviceOnly(): Boolean =
        runCatching { sessionStore.appearanceScopeFlow.first() }.getOrNull() == "device"

    suspend fun setAppearanceDeviceOnly(deviceOnly: Boolean) {
        sessionStore.setAppearanceScope(deviceOnly)
    }

    suspend fun signOut() {
        auth.signOut()
        _unread.value = UnreadBadges()
        _people.value = emptyMap()
        _bots.value = emptyMap()
        // Every cached list goes with the session, so the next account can never be shown the
        // previous one's channels, conversations or threads.
        forgetCachedLists()
        selectedServerId.value = null
        openChannelId.value = null
        openConversationId.value = null
        openThreadId.value = null
        profileId.value = null
    }
}

/** What the live connection is doing, as the banner reports it. */
sealed interface LiveStatus {
    /** Before the first connection attempt has answered; no banner. */
    data object Connecting : LiveStatus
    /** Realtime is attached and the room is being kept current; no banner. */
    data object Connected : LiveStatus
    /**
     * The connection dropped and the app is putting it back by itself.
     *
     * This is a state the app is *acting* on, not a failure to report and wait over: the banner
     * says what happened and that a retry is coming, and its button is an accelerator for
     * someone who does not want to wait the backoff out. [attempt] counts from 1, so the banner
     * can say "Retrying (2)" without pretending the first try never happened.
     */
    data class Reconnecting(val attempt: Int, val reason: String) : LiveStatus
    /**
     * The instance refused the connection outright, so a retry on a timer cannot help: the
     * account lost access to the room, or the session ended. The banner names it.
     */
    data class Unavailable(val reason: String) : LiveStatus
}

/**
 * One `message_media` object's signing result.
 *
 * [url] is null when the instance would not sign it, which is the honest answer for a file that
 * was deleted, whose room this account left, or whose URL has expired. [at] is when the answer
 * arrived, so a failure is retried after a while and a success is reused until it is nearly
 * expired.
 */
data class SignedMedia(val url: String?, val at: Long)

/** How long a signed URL is reused; the reference's refresh margin, in reverse. */
internal const val SIGNED_MEDIA_REUSE_MS = 5L * 60 * 60 * 1000

/** How long a failed signing is left alone before it is tried again. */
internal const val SIGNED_MEDIA_RETRY_MS = 60L * 1000

data class UnreadBadges(
    val channels: Map<String, Int> = emptyMap(),
    val conversations: Map<String, Int> = emptyMap(),
    val mentions: Map<String, Int> = emptyMap(),
) {
    fun channel(channelId: String): Int = channels[channelId] ?: 0
    fun conversation(conversationId: String): Int = conversations[conversationId] ?: 0
    fun serverTotal(channelIds: Collection<String>): Int = channelIds.sumOf { channels[it] ?: 0 }
    fun mentionsIn(channelIds: Collection<String>): Int = channelIds.sumOf { mentions[it] ?: 0 }
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState is provided by HarmonyRoot") }

@Composable
fun ProvideAppState(state: AppState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppState provides state, content = content)
}
