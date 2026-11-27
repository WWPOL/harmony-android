package lol.mony.harmony

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.notifications.HarmonyNotifications
import lol.mony.harmony.notifications.NotificationCoordinator
import lol.mony.harmony.ui.AppState

class HarmonyApp : Application() {
    val container by lazy { AppContainer(this) }
    val state by lazy { AppState(container) }

    /**
     * Notification channels and the inbox poller.
     *
     * Channels are registered here, before anything is signed in, so system settings shows a
     * complete, meaningful list from the first launch -- which was missing when the app had no
     * channels at all.
     */
    val notifications: NotificationCoordinator by lazy {
        NotificationCoordinator(this, container.auth, { rest -> NotificationRepository(rest) })
    }

    /** The update check's own scope: it outlives any one screen, and it never blocks a launch. */
    private val updatesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        HarmonyNotifications.ensureChannels(this)
        // The stored state is read once, before anything is drawn, so the settings screen can
        // say whether the check is on without waiting for a network answer.
        updatesScope.launch { runCatching { container.updates.restore() } }
    }

    /**
     * The check the app runs on launch and whenever it comes back to the foreground.
     *
     * [UpdateChecker.check] does nothing at all when the next check is not due yet, so this is
     * cheap to call on every resume, and it is called from the activity rather than from a
     * timer: a check nobody is watching for costs battery and asks GitHub a question the person
     * will not see the answer to until they open the app anyway.
     *
     * The first call of a process waits a moment before asking, which is what keeps the check
     * out of the way of a launch that is already reading a session and opening a socket.
     */
    fun checkForUpdates() {
        updatesScope.launch {
            if (!settled) {
                // Only the first call of the process waits; a later resume checks at once.
                settled = true
                delay(FIRST_CHECK_SETTLE_MILLIS)
            }
            runCatching { container.updates.check() }
        }
    }

    @Volatile
    private var settled = false

    private companion object {
        /** How long after launch the first check may ask, on top of the policy's own delay. */
        const val FIRST_CHECK_SETTLE_MILLIS = 3_000L
    }
}
