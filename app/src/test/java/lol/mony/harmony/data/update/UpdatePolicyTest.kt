package lol.mony.harmony.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the check runs, and how it waits.
 *
 * This is the part of the feature that spends somebody else's bandwidth, so it is tested as a
 * schedule rather than by waiting: every case below is a state, a clock and the answer the
 * policy owes. If any of these were wrong, the mistake would be a fleet of phones asking GitHub
 * the same question far more often than anybody intended, which is exactly what a test that only
 * checked "an update was found" would not notice.
 */
class UpdatePolicyTest {
    private val day = UpdatePolicy.INTERVAL_MILLIS
    private val seed = 0x5EED_1234L

    private fun fresh(now: Long = 1_000_000L) =
        UpdateState().copy(firstSeenMillis = now, seed = seed)

    @Test fun aFreshInstallDoesNotAskOnItsFirstFrame() {
        val now = 1_000_000L
        val state = fresh(now)
        val due = UpdatePolicy.dueAt(state, seed)
        // A minute at the earliest, and spread over half an hour after that: the check must not
        // compete with a launch that is restoring a session and opening a socket.
        assertTrue("the first check waits at least a minute", due >= now + UpdatePolicy.FIRST_DELAY_MILLIS)
        assertTrue(
            "and no longer than the spread allows",
            due <= now + UpdatePolicy.FIRST_DELAY_MILLIS + UpdatePolicy.FIRST_JITTER_MILLIS,
        )
        assertFalse(UpdatePolicy.isDue(state, now, seed))
        // The spread is up to half an hour, so ten minutes in is usually too early; what must
        // hold whatever the offset is, is that the delay itself is never skipped.
        assertFalse("a moment after launch is too early", UpdatePolicy.isDue(state, now + 1_000L, seed))
        assertTrue("but it is due once the delay has passed", UpdatePolicy.isDue(state, due, seed))
    }

    @Test fun aSuccessfulCheckIsNotRepeatedForADay() {
        val now = 1_000_000L
        val checked = UpdatePolicy.succeeded(fresh(now), now)
        assertFalse("a second check the same day does nothing", UpdatePolicy.isDue(checked, now + 1_000L, seed))
        assertFalse("nor an hour later", UpdatePolicy.isDue(checked, now + 60 * 60 * 1000L, seed))
        val due = UpdatePolicy.dueAt(checked, seed)
        assertTrue("a day at the earliest", due >= now + day)
        assertTrue("and never more than a day and the spread", due <= now + day + UpdatePolicy.JITTER_MILLIS)
        assertTrue(UpdatePolicy.isDue(checked, due, seed))
    }

    @Test fun theSpreadIsStableForOneInstallAndDifferentBetweenThem() {
        // The same install asks at the same offset every time: the point of the seed is that it
        // is stored, not that it changes.
        assertEquals(UpdatePolicy.offset(seed, 2, UpdatePolicy.JITTER_MILLIS), UpdatePolicy.offset(seed, 2, UpdatePolicy.JITTER_MILLIS))
        // Two purposes on one install are not the same number, so the first check and the daily
        // one do not stack into a single moment.
        assertTrue(UpdatePolicy.offset(seed, 1, UpdatePolicy.JITTER_MILLIS) != UpdatePolicy.offset(seed, 2, UpdatePolicy.JITTER_MILLIS))
        // Many installs spread across the whole window rather than clustering on one value.
        val offsets = (0 until 500).map { UpdatePolicy.offset(it * 7919L + 13, 2, UpdatePolicy.JITTER_MILLIS) }
        assertTrue("every offset lies inside the window", offsets.all { it in 0 until UpdatePolicy.JITTER_MILLIS })
        assertTrue("they are spread, not one value", offsets.toSet().size > 400)
        assertTrue("and not all in one tenth of the window", offsets.count { it < UpdatePolicy.JITTER_MILLIS / 10 } < 80)
        // A bound of zero is no spread at all rather than a division by it.
        assertEquals(0L, UpdatePolicy.offset(seed, 2, 0))
    }

    @Test fun aFailedCheckWaitsLongerEachTimeUpToAWeek() {
        var state = fresh()
        val waits = (1..8).map { failures ->
            state = UpdatePolicy.failed(state, 1_000_000L + failures * 1000L)
            UpdatePolicy.retryDelay(state.failures)
        }
        assertEquals(UpdatePolicy.RETRY_MILLIS, waits.first())
        assertTrue("the wait doubles", waits[1] == 2 * UpdatePolicy.RETRY_MILLIS)
        assertTrue("and again", waits[2] == 4 * UpdatePolicy.RETRY_MILLIS)
        assertEquals("and stops at a week", UpdatePolicy.RETRY_MAX_MILLIS, waits.last())
        assertTrue("nothing ever waits longer than a week", waits.all { it <= UpdatePolicy.RETRY_MAX_MILLIS })
        // A failure never waits less than the ordinary interval: a broken network must not make
        // the app ask more often than a working one.
        assertTrue(waits.all { it >= day })
    }

    @Test fun aSuccessPutsTheWaitBackToADay() {
        var state = fresh()
        repeat(5) { state = UpdatePolicy.failed(state, 1_000_000L) }
        val recovered = UpdatePolicy.succeeded(state, 2_000_000L)
        assertEquals(0, recovered.failures)
        val due = UpdatePolicy.dueAt(recovered, seed)
        assertTrue(due >= 2_000_000L + day)
        assertTrue(due <= 2_000_000L + day + UpdatePolicy.JITTER_MILLIS)
    }

    @Test fun theValidatorIsKeptAcrossAFailureAndReplacedByANewOne() {
        val first = UpdatePolicy.succeeded(fresh(), 1_000_000L, etag = "\"v1\"", lastModified = "Fri, 27 Nov 2026 05:33:51 GMT")
        assertEquals("\"v1\"", first.etag)
        assertEquals("Fri, 27 Nov 2026 05:33:51 GMT", first.lastModified)
        // A failure keeps it: the next check asks the same question with the same validator.
        val failed = UpdatePolicy.failed(first, 1_100_000L)
        assertEquals("\"v1\"", failed.etag)
        assertEquals("Fri, 27 Nov 2026 05:33:51 GMT", failed.lastModified)
        // A 304 (which passes no new validator) keeps it too.
        val unchanged = UpdatePolicy.succeeded(failed, 1_200_000L)
        assertEquals("\"v1\"", unchanged.etag)
        // A new manifest replaces it.
        val next = UpdatePolicy.succeeded(unchanged, 1_300_000L, etag = "\"v2\"", lastModified = null)
        assertEquals("\"v2\"", next.etag)
        assertEquals("a validator that was not sent is kept, not cleared", "Fri, 27 Nov 2026 05:33:51 GMT", next.lastModified)
    }

    @Test fun theSwitchStopsTheCheckWithoutForgettingAnything() {
        val checked = UpdatePolicy.succeeded(fresh(), 1_000_000L)
        val off = checked.copy(enabled = false)
        assertFalse("an off switch is off however long it has been", UpdatePolicy.isDue(off, 9_000_000_000L, seed))
        assertTrue("and turning it back on asks again", UpdatePolicy.isDue(off.copy(enabled = true), 9_000_000_000L, seed))
        assertEquals("the switch does not reset the record", checked.lastCheckMillis, off.lastCheckMillis)
    }

    @Test fun anInstallWithoutItsOwnValuesInventsThemOnce() {
        val now = 1_700_000_000_000L
        val first = UpdateState().initialised(now)
        assertEquals(now, first.firstSeenMillis)
        assertTrue("a seed is generated", first.seed != 0L)
        // A second launch keeps both: the first check's delay is counted from the first launch,
        // and the spread belongs to the install.
        val second = first.initialised(now + 5_000L)
        assertEquals(first.firstSeenMillis, second.firstSeenMillis)
        assertEquals(first.seed, second.seed)
        // An install from before the check existed has a state with no first-seen time, which is
        // filled in rather than being treated as 1970.
        val old = UpdateState(seed = 42L)
        assertEquals(now, old.initialised(now).firstSeenMillis)
        assertEquals(42L, old.initialised(now).seed)
    }
}
