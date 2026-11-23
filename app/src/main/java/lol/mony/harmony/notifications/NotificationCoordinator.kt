package lol.mony.harmony.notifications

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lol.mony.harmony.data.api.Http
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationPermissionState
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.session.AuthRepository

/**
 * Keeps the system's notification tray in step with the account's inbox.
 *
 * It is the delivery half of notifications: the inbox screen is where a person reads them, and
 * this is what makes one reach them while the app is not open on that screen. It polls the
 * account's own inbox -- the same `get_user_notifications` RPC the screen uses, with the same
 * row-level security -- and posts each **new** row to its channel, withdrawing rows that were
 * read or deleted elsewhere so the tray never disagrees with the inbox.
 *
 * "New" is the part that has to be remembered, and [NotificationDelivery] is where the rule lives:
 * a row is posted once, a row that existed before this device started watching is never posted at
 * all, and a dismissed notification stays dismissed while its row is still unread. The memory is
 * [DeliveryRecords], kept per account and instance on the device, so neither a restart nor an APK
 * update brings a days-old sign-in notice back.
 *
 * That rule is the fix for the report this class carries: it used to post *every* unread row on
 * *every* poll, so one days-old "New sign-in to your account" row was re-posted and re-alerted
 * every thirty seconds, and dismissing it only bought half a minute. `seen` existed but was used
 * only to decide what to withdraw, never what to post.
 *
 * What it is not: a push service. Harmony's real push path is a web push subscription handled
 * by the federation backend and the service worker, which this client does not register. Until
 * that exists, an app that has been killed receives nothing, and this class says so rather than
 * implying otherwise.
 */
class NotificationCoordinator(
    private val context: Context,
    private val auth: AuthRepository,
    private val repositoryFor: (RestApi) -> NotificationRepository,
    private val records: DeliveryRecords = PreferencesDeliveryRecords(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    /** One poll at a time: two interleaved polls would each deliver the rows the other missed. */
    private val pollMutex = Mutex()

    /** The records already read this process, so a poll is not a preferences read. */
    private val cached = mutableMapOf<String, DeliveryRecord>()

    private val _permission = MutableStateFlow(HarmonyNotifications.permissionState(context))

    /** What the system currently allows, for the settings screen to show and explain. */
    val permission: StateFlow<NotificationPermissionState> = _permission.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Re-reads the system's permission, after the person grants or revokes it in settings. */
    fun refreshPermission() { _permission.value = HarmonyNotifications.permissionState(context) }

    /**
     * Starts watching the signed-in account's inbox.
     *
     * The interval is deliberately modest: this is a stand-in for push, and a tight loop would
     * spend the person's battery to learn about messages they will see anyway. Every poll is
     * against the account's own row-level security, so a signed-out or switched account stops
     * seeing the previous one's rows.
     */
    fun start(intervalMillis: Long = 30_000L) {
        if (running) return
        running = true
        ensureChannels()
        scope.launch {
            while (isActive) {
                poll()
                delay(intervalMillis)
            }
        }
    }

    /**
     * Stops watching, and takes the tray down with it.
     *
     * The delivery records are deliberately kept: they are the device's memory of what it has
     * already said, and signing out and back in is not a reason to say it again. They are scoped
     * by account and instance, so the next account cannot inherit them.
     */
    fun stop() {
        running = false
        cached.clear()
        HarmonyNotifications.cancelAll(context)
    }

    private fun ensureChannels() { HarmonyNotifications.ensureChannels(context) }

    /**
     * One poll: read the newest rows of the account's inbox, post the ones this device has not
     * dealt with, and withdraw notifications whose row was read or deleted.
     *
     * The state check happens before the permission check so a refused permission does not stop
     * the poll: the inbox is still read, and rows that arrive while notifications are off are
     * remembered rather than kept, so granting the permission later delivers what happens next
     * instead of a burst of everything that was missed.
     */
    private suspend fun poll() = pollMutex.withLock {
        val session = auth.state.value as? AuthState.SignedIn ?: return@withLock
        val profileId = session.profile?.id ?: return@withLock
        val token = runCatching { auth.freshToken() }.getOrNull() ?: return@withLock
        val inbox = runCatching {
            // Read rows too: a delivered row that has since been read is how the tray learns to
            // withdraw it, and an unread-only page would never mention the rows it must take back.
            repositoryFor(Http.rest)
                .fetchNotifications(session.endpoint, token, profileId, limit = PAGE_SIZE)
        }.getOrElse { error ->
            _lastError.value = error.message
            return@withLock
        }
        _lastError.value = null
        refreshPermission()
        deliver(DeliveryAccount(session.endpoint, profileId), inbox)
    }

    /**
     * One page of the inbox, delivered. The poll's own body, without the session lookup.
     *
     * It is internal so a test can hand it a page and read the tray back, which is the only way
     * to prove "this row was not posted" without a device. Production reaches it through [poll].
     */
    internal fun deliver(account: DeliveryAccount, rows: List<Notification>) {
        val scope = DeliveryRecords.scope(account)
        val record = cached[scope] ?: records.load(scope).also { cached[scope] = it }
        val plan = NotificationDelivery.plan(rows, record, complete = rows.size < PAGE_SIZE)
        for (id in plan.withdraw) HarmonyNotifications.cancel(context, id)
        val canShow = HarmonyNotifications.permissionState(context) == NotificationPermissionState.Granted
        val handled = if (canShow) {
            plan.post.filter { HarmonyNotifications.post(context, it) }.map { it.id }
        } else {
            plan.post.map { it.id }
        }
        remember(scope, NotificationDelivery.handled(plan.record, handled))
    }

    private fun remember(scope: String, record: DeliveryRecord) {
        cached[scope] = record
        records.save(scope, record)
    }

    /**
     * What a tapped notification means: it has been seen, so its row becomes read and its tray
     * entry goes.
     *
     * A row left unread is a row the inbox keeps calling new, which is how a notice someone
     * opened days ago kept coming back. Marking it read here is the same write the inbox screen
     * makes when a row is opened there, done for the tap that arrives through the deep link while
     * no inbox is open. Failure is silent on purpose: the tap still navigates, and the next poll
     * sees the row read or not.
     */
    fun opened(notificationId: String?) {
        val id = notificationId?.takeIf { it.isNotBlank() } ?: return
        HarmonyNotifications.cancel(context, id)
        scope.launch {
            val session = auth.state.value as? AuthState.SignedIn ?: return@launch
            val profileId = session.profile?.id ?: return@launch
            val token = runCatching { auth.freshToken() }.getOrNull() ?: return@launch
            runCatching { repositoryFor(Http.rest).markAsClicked(session.endpoint, token, profileId, id) }
            val account = DeliveryAccount(session.endpoint, profileId)
            val key = DeliveryRecords.scope(account)
            val record = cached[key] ?: records.load(key)
            remember(key, NotificationDelivery.handled(record, listOf(id)))
        }
    }

    private companion object { const val PAGE_SIZE = 20 }
}
