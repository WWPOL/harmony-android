package lol.mony.harmony

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.repo.NotificationRepository
import lol.mony.harmony.notifications.DeliveryAccount
import lol.mony.harmony.notifications.DeliveryRecord
import lol.mony.harmony.notifications.DeliveryRecords
import lol.mony.harmony.notifications.NotificationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reported bug, end to end: a days-old sign-in notice must not keep coming back.
 *
 * These drive the real [NotificationCoordinator] and read the tray back from Robolectric's own
 * [NotificationManager], so "posted" means a notification the system would show rather than a flag
 * the test set. They are the check [NotificationDeliveryTest] cannot make: that the coordinator
 * wires the rule to the tray, and that the tray it leaves behind is empty.
 *
 * Nothing here signs in. A sign-in writes the shared `brapmony-session` DataStore, and
 * `SessionStoreRecoveryTest` truncates that same file to prove a corrupt store recovers; a session
 * left behind by this test would make that one see a fixture instance instead of an empty store.
 * Delivery reads the account from the caller, so a [DeliveryAccount] and a page of rows are all
 * these need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** An in-memory [DeliveryRecords], so one test cannot see another's memory. */
    private class MemoryRecords : DeliveryRecords {
        val saved = mutableMapOf<String, DeliveryRecord>()
        override fun load(scope: String): DeliveryRecord = saved[scope] ?: DeliveryRecord()
        override fun save(scope: String, record: DeliveryRecord) { saved[scope] = record }
    }

    /** The titles currently in the tray, which is what the person would see. */
    private fun tray(): List<String> = shadowOf(context.getSystemService(NotificationManager::class.java))
        .allNotifications
        .map { it.extras.getCharSequence("android.title")?.toString().orEmpty() }

    private fun emptyTray() {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        assertTrue("the tray starts empty", tray().isEmpty())
    }

    /**
     * A coordinator over this process's real graph.
     *
     * It is never signed in, and that is deliberate: [NotificationCoordinator.deliver] takes the
     * account from its caller, so the poll's own session lookup is the only part that needs one,
     * and no test here runs a poll.
     */
    private fun coordinator(records: DeliveryRecords): NotificationCoordinator {
        val container = RenderHarness.container()
        return NotificationCoordinator(
            context, container.auth, { rest: RestApi -> NotificationRepository(rest) }, records)
    }

    private val account = DeliveryAccount(Endpoint("http://fixture.invalid/", "anon"), PROFILE_ID)

    private fun primed(records: MemoryRecords, cutoff: Instant = Instant.now().minusSeconds(60)) {
        records.save(DeliveryRecords.scope(account), DeliveryRecord(primed = true, cutoff = cutoff))
    }

    @Test fun theFirstPagePrimesTheAccountAndShowsNothingFromTheBacklog() {
        emptyTray()
        val records = MemoryRecords()
        val coordinator = coordinator(records)

        coordinator.deliver(account, listOf(signIn(daysAgo = 9), message("m1", daysAgo = 2)))

        assertTrue("a fresh install must not replay the inbox into the tray", tray().isEmpty())
        val saved = records.load(DeliveryRecords.scope(account))
        assertTrue("the account is primed by its first page", saved.primed)
        assertTrue("and nothing was remembered as delivered", saved.delivered.isEmpty())
    }

    @Test fun theReportedRowIsShownOnceAndNeverAgainWhileItStaysUnread() {
        emptyTray()
        val records = MemoryRecords()
        val coordinator = coordinator(records)
        primed(records)

        // A sign-in notice that arrives after the cutoff and stays unread on the server, because
        // dismissing a notification does not mark its row read. This is the reported case.
        val notice = signIn(daysAgo = 0)
        coordinator.deliver(account, listOf(notice))
        assertEquals("the row is shown once", 1, tray().size)
        assertEquals("New sign-in to your account", tray().single())

        // Poll after poll, the same unread row must not come back.
        repeat(5) { coordinator.deliver(account, listOf(notice)) }
        assertEquals("an unread row that was already shown is not shown again", 1, tray().size)
    }

    @Test fun aGenuinelyNewRowStillArrivesAfterTheFix() {
        // The other side of the rule: suppressing history must not suppress news.
        emptyTray()
        val records = MemoryRecords()
        val coordinator = coordinator(records)
        primed(records)

        // Two rows arrive after the cutoff in one page; both are shown, and a later page with a
        // third shows only that third.
        coordinator.deliver(account, listOf(signIn(daysAgo = 0), message("m1", daysAgo = 0)))
        assertEquals("both new rows are shown", 2, tray().size)

        val newer = message("m2", daysAgo = 0)
        coordinator.deliver(account, listOf(newer, signIn(daysAgo = 0), message("m1", daysAgo = 0)))
        assertEquals("only the row that is actually new is added", 3, tray().size)
    }

    @Test fun aDaysOldRowIsNeverShownAtAll() {
        emptyTray()
        val records = MemoryRecords()
        val coordinator = coordinator(records)
        // An account that has been watched for a while, so the cutoff is not what suppresses it:
        // the row itself is older than the moment this device started watching.
        primed(records, cutoff = Instant.now())

        coordinator.deliver(account, listOf(signIn(daysAgo = 9)))

        assertTrue("a days-old row is history, not news", tray().isEmpty())
    }

    @Test fun aRowReadOnAnotherDeviceLeavesTheTray() {
        emptyTray()
        val records = MemoryRecords()
        val coordinator = coordinator(records)
        primed(records)
        val notice = signIn(daysAgo = 0)

        coordinator.deliver(account, listOf(notice))
        assertEquals(1, tray().size)

        // The same row, now read: its tray entry goes, and it is forgotten.
        coordinator.deliver(account, listOf(notice.copy(isRead = true)))

        assertTrue("a row read elsewhere is withdrawn", tray().isEmpty())
        assertTrue(
            "and no longer remembered",
            records.load(DeliveryRecords.scope(account)).delivered.isEmpty(),
        )
    }

    @Test fun aTappedNotificationIsWithdrawnFromTheTray() {
        emptyTray()
        val records = MemoryRecords()
        val coordinator = coordinator(records)
        primed(records)
        val notice = signIn(daysAgo = 0)
        coordinator.deliver(account, listOf(notice))
        assertEquals(1, tray().size)

        coordinator.opened(notice.id)

        assertTrue("the tapped row's tray entry goes at once", tray().isEmpty())
    }

    private fun signIn(daysAgo: Long) = Notification(
        id = "signin-$daysAgo",
        userId = PROFILE_ID,
        type = "security",
        data = JsonObject(mapOf("event" to JsonPrimitive("new_sign_in"))),
        createdAt = Instant.now().minusSeconds(daysAgo * 86_400).toString(),
    )

    private fun message(id: String, daysAgo: Long) = Notification(
        id = id,
        userId = PROFILE_ID,
        type = "dm",
        createdAt = Instant.now().minusSeconds(daysAgo * 86_400).toString(),
    )

    private companion object { const val PROFILE_ID = "11111111-1111-4111-8111-111111111111" }
}
