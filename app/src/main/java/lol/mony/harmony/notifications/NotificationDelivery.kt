package lol.mony.harmony.notifications

import java.time.Instant
import lol.mony.harmony.data.model.Notification

/**
 * What one poll of the account's inbox should do, and what it should remember afterwards.
 *
 * This is the rule that stops the tray repeating itself, and it is a pure function so every rule
 * can be tested without a device. Three facts decide everything:
 *
 * - [DeliveryRecord.primed] and [DeliveryRecord.cutoff]: the newest row that existed the first
 *   time this account was watched. Anything at or before it was already in the inbox before this
 *   device started watching, which is what a person means by "I saw that days ago": it is
 *   remembered and never delivered. Without it, installing or updating the app posts every unread
 *   row of the last month at once -- including the sign-in notice the person already dismissed.
 * - [DeliveryRecord.delivered]: the rows already dealt with. A row is posted **once**, so
 *   dismissing a notification is final even though the row is still unread on the server. Posting
 *   every unread row on every poll is what made a days-old sign-in notice come back, and alert
 *   again, every thirty seconds.
 * - The rows the poll just read: a delivered row that is now read, has expired, or is gone from a
 *   page that holds the whole inbox was dealt with elsewhere, so its notification is withdrawn
 *   and forgotten.
 */
internal object NotificationDelivery {
    /**
     * One poll's decisions.
     *
     * [post] is what to show in the tray, newest first, each row at most once. [withdraw] is the
     * tray entries whose row was read, has expired or is gone. [record] is what to remember: the
     * withdrawals applied, and the account primed on its first poll.
     */
    data class Plan(
        val post: List<Notification>,
        val withdraw: List<String>,
        val record: DeliveryRecord,
    )

    /**
     * The plan for one poll.
     *
     * [rows] is the newest page of the account's inbox, read rows included, and [complete] says
     * whether that page holds *every* row the account has. A page that was cut short cannot prove
     * a row is gone -- an old row can simply have been pushed past the page by newer ones -- so it
     * withdraws only what it can see has been read. The first poll for an account only takes the
     * cutoff: it deliberately delivers nothing, because every row in it predates this device's
     * watching.
     */
    fun plan(
        rows: List<Notification>,
        record: DeliveryRecord,
        complete: Boolean = true,
        now: Instant = Instant.now(),
    ): Plan {
        val byId = rows.associateBy { it.id }
        val withdraw = record.delivered.filter { id ->
            val row = byId[id]
            when {
                row == null -> complete
                row.isRead -> true
                row.hasExpired(now) -> true
                else -> false
            }
        }
        val remaining = record.copy(delivered = record.delivered - withdraw.toSet())
        val fresh = rows.filter { !it.isRead && !it.hasExpired(now) }
        if (!remaining.primed) {
            // Watching starts now: every row that already exists is history, whatever its own
            // timestamp says. Using the newest row's timestamp instead would still deliver the
            // rows written between that row and the first poll.
            return Plan(
                post = emptyList(),
                withdraw = withdraw,
                record = remaining.copy(primed = true, cutoff = now),
            )
        }
        return Plan(
            post = fresh.filter { accepts(remaining, it) },
            withdraw = withdraw,
            record = remaining,
        )
    }

    /**
     * Whether a row is new enough to be delivered, and has not been dealt with before.
     *
     * A row whose timestamp cannot be read is not delivered: the alternative is a notification
     * whose age is unknown, which is how rows from days ago came back in the first place.
     */
    fun accepts(record: DeliveryRecord, notification: Notification): Boolean {
        if (notification.id in record.delivered) return false
        val created = notification.createdInstant ?: return false
        return record.cutoff == null || created.isAfter(record.cutoff)
    }

    /**
     * The record after these rows were dealt with -- posted, or deliberately not posted because
     * the system would not show them.
     *
     * A row whose post actually failed is left out, so the next poll tries it again; the tray is
     * not the source of truth, and the inbox still has the row.
     */
    fun handled(record: DeliveryRecord, ids: Collection<String>): DeliveryRecord =
        if (ids.isEmpty()) record else record.copy(delivered = (record.delivered + ids).takeLast(LIMIT))

    /**
     * How many dealt-with ids are remembered. A poll reads the newest page of twenty, so this is
     * several pages of history: an id that falls off the end can no longer be in the page a poll
     * reads, which is the only way it could be posted again.
     */
    const val LIMIT = 200
}
