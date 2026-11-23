package lol.mony.harmony

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.notifications.DeliveryRecord
import lol.mony.harmony.notifications.NotificationDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops the tray repeating itself, as a pure function.
 *
 * The bug these pin was reported from the phone: a "New sign-in to your account" notice the
 * person had dismissed days earlier came back, and kept coming back. `NotificationCoordinator`
 * posted every unread row on every poll, so a row that was never marked read on the server -- and
 * dismissing a notification does not mark a row read -- was re-posted and re-alerted every thirty
 * seconds. The rules below are what replaces that loop.
 */
class NotificationDeliveryTest {
    private val now: Instant = Instant.parse("2026-11-23T12:00:00Z")

    @Test
    fun theFirstPollPrimesTheAccountAndDeliversNothing() {
        // Everything already in the inbox predates this device watching: it was read, dismissed or
        // simply not worth a notification long ago, and replaying it is the reported bug.
        val plan = NotificationDelivery.plan(
            rows = listOf(
                signIn(daysAgo = 9),
                message(id = "m1", daysAgo = 2),
            ),
            record = DeliveryRecord(),
            now = now,
        )

        assertTrue("nothing is delivered from the first poll", plan.post.isEmpty())
        assertTrue(plan.record.primed)
        assertEquals(now, plan.record.cutoff)
    }

    @Test
    fun aRowNewerThanTheCutoffIsDeliveredExactlyOnce() {
        val record = primed(cutoff = now.minusSeconds(3600))
        val fresh = message(id = "m1", daysAgo = 0)

        val first = NotificationDelivery.plan(listOf(fresh), record, now = now)
        assertEquals(listOf("m1"), first.post.map { it.id })

        // The same row, one poll later, with the record the first poll left behind.
        val after = NotificationDelivery.handled(first.record, first.post.map { it.id })
        val second = NotificationDelivery.plan(listOf(fresh), after, now = now)
        assertTrue("a delivered row is never posted twice", second.post.isEmpty())
    }

    @Test
    fun aDaysOldUnreadRowIsNeverDelivered() {
        // The reported row: unread on the server, days old, dismissed on the phone. A poll that
        // posts every unread row is what brought it back.
        val record = primed(cutoff = now.minusSeconds(3600))

        val plan = NotificationDelivery.plan(listOf(signIn(daysAgo = 9)), record, now = now)

        assertTrue(plan.post.isEmpty())
    }

    @Test
    fun aRowReadElsewhereWithdrawsItsTrayEntryAndIsForgotten() {
        val record = DeliveryRecord(
            primed = true,
            cutoff = now.minusSeconds(86_400),
            delivered = listOf("read-elsewhere", "still-unread"),
        )

        val plan = NotificationDelivery.plan(
            rows = listOf(
                signIn(daysAgo = 1).copy(id = "read-elsewhere", isRead = true),
                message(id = "still-unread", daysAgo = 0),
            ),
            record = record,
            now = now,
        )

        assertEquals(listOf("read-elsewhere"), plan.withdraw)
        assertEquals(listOf("still-unread"), plan.record.delivered)
        assertTrue("a read row is not posted", plan.post.isEmpty())
    }

    @Test
    fun anExpiredRowIsWithdrawnAndNotPosted() {
        val record = DeliveryRecord(primed = true, cutoff = now.minusSeconds(86_400), delivered = listOf("old"))
        val expired = message(id = "old", daysAgo = 0).copy(expiresAt = now.minusSeconds(1).toString())

        val plan = NotificationDelivery.plan(listOf(expired), record, now = now)

        assertEquals(listOf("old"), plan.withdraw)
        assertTrue(plan.post.isEmpty())
    }

    @Test
    fun aPartialPageWithdrawsOnlyWhatItCanSeeHasBeenRead() {
        // A page that was cut short cannot prove an old row is gone: newer rows can have pushed it
        // off the page. Only a row the page shows as read is a withdrawal it can justify.
        val record = DeliveryRecord(
            primed = true,
            cutoff = now.minusSeconds(86_400),
            delivered = listOf("off-the-page", "shown-read"),
        )

        val plan = NotificationDelivery.plan(
            rows = listOf(message(id = "shown-read", daysAgo = 0).copy(isRead = true)),
            record = record,
            complete = false,
            now = now,
        )

        assertEquals(listOf("shown-read"), plan.withdraw)
    }

    @Test
    fun aRowWithAnUnreadableTimestampIsNotDelivered() {
        val record = primed(cutoff = now.minusSeconds(3600))
        val broken = message(id = "broken", daysAgo = 0).copy(createdAt = "not-a-timestamp")

        val plan = NotificationDelivery.plan(listOf(broken), record, now = now)

        assertTrue("a row of unknown age is not news", plan.post.isEmpty())
        assertFalse(NotificationDelivery.accepts(record, broken))
    }

    @Test
    fun aRowWithNoCutoffYetIsDeliveredOnceTheAccountIsPrimed() {
        // A record saved before there was a cutoff (an older install) still delivers by id alone.
        val record = DeliveryRecord(primed = true, cutoff = null)
        val fresh = message(id = "m1", daysAgo = 30)

        assertTrue(NotificationDelivery.accepts(record, fresh))
        assertFalse(NotificationDelivery.accepts(NotificationDelivery.handled(record, listOf("m1")), fresh))
    }

    @Test
    fun theRememberedIdsAreBoundedButKeepTheNewest() {
        val record = DeliveryRecord(primed = true, cutoff = now)
        val many = (1..(NotificationDelivery.LIMIT + 25)).map { "id-$it" }

        val bounded = NotificationDelivery.handled(record, many)

        assertEquals(NotificationDelivery.LIMIT, bounded.delivered.size)
        assertEquals("id-${NotificationDelivery.LIMIT + 25}", bounded.delivered.last())
        assertFalse("the oldest ids fall off", bounded.delivered.contains("id-1"))
    }

    private fun primed(cutoff: Instant) = DeliveryRecord(primed = true, cutoff = cutoff)

    private fun signIn(daysAgo: Long) = notification("security", "signin-$daysAgo", daysAgo)
        .copy(data = JsonObject(mapOf("event" to kotlinx.serialization.json.JsonPrimitive("new_sign_in"))))

    private fun message(id: String, daysAgo: Long) = notification("dm", id, daysAgo)

    private fun notification(type: String, id: String, daysAgo: Long) = Notification(
        id = id,
        userId = "profile-owner",
        type = type,
        createdAt = now.minusSeconds(daysAgo * 86_400).toString(),
    )
}
