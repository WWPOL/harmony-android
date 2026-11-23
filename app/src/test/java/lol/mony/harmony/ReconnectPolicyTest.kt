package lol.mony.harmony

import lol.mony.harmony.data.api.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The schedule a dropped live connection retries on.
 *
 * The bug this pins is that there was no schedule at all: a failed socket completed the flow and
 * the room stayed dead until somebody pressed a button. A schedule that is too eager is the
 * other failure -- a phone in a tunnel would hammer an unreachable instance and flatten the
 * battery -- and one that grows without a cap leaves a room dead for minutes after a network
 * that was gone for an hour comes back.
 */
class ReconnectPolicyTest {
    /** No jitter: these assert the schedule itself, not the spread around it. */
    private val exact = ReconnectPolicy(jitter = 0.0, random = Random(1))

    @Test fun theFirstRetryIsQuick() {
        assertEquals(1_000, exact.delayFor(1))
    }

    @Test fun eachFailureWaitsTwiceAsLong() {
        assertEquals(1_000, exact.delayFor(1))
        assertEquals(2_000, exact.delayFor(2))
        assertEquals(4_000, exact.delayFor(3))
        assertEquals(8_000, exact.delayFor(4))
        assertEquals(16_000, exact.delayFor(5))
    }

    @Test fun theWaitIsCappedSoARecoveredNetworkIsNoticed() {
        // A night of failures must still retry within the cap, not in an hour.
        for (attempt in 6..500) {
            assertTrue("attempt $attempt waited ${exact.delayFor(attempt)}ms, past the 30s cap",
                exact.delayFor(attempt) <= 30_000)
        }
        assertEquals(30_000, exact.delayFor(6))
        assertEquals(30_000, exact.delayFor(100_000))
    }

    /**
     * An enormous attempt number must not overflow into a negative delay.
     *
     * `initial * 2.0.pow(step).toLong()` saturates at `Long.MAX_VALUE`, and a negative result
     * would be a tight loop -- the exact opposite of a backoff. The clamp happens in `Double`
     * before the conversion, which is what this pins.
     */
    @Test fun anImpossibleAttemptCountNeverProducesATightLoop() {
        for (attempt in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1, 64, 1_000)) {
            val wait = exact.delayFor(attempt)
            assertTrue("attempt $attempt produced ${wait}ms", wait in 1..30_000)
        }
    }

    @Test fun theAttemptNumberIsOneBasedAndForgiving() {
        // A caller that has not failed yet asks for attempt 0; it gets the first delay, not a
        // negative exponent.
        assertEquals(exact.delayFor(1), exact.delayFor(0))
        assertEquals(exact.delayFor(1), exact.delayFor(-5))
    }

    /**
     * Jitter shortens a wait, never lengthens it, so the cap is still a cap and the first retry
     * is still the quickest.
     */
    @Test fun jitterOnlyShortensTheWait() {
        val jittered = ReconnectPolicy(jitter = 0.2, random = Random(7))
        for (attempt in 1..12) {
            val base = exact.delayFor(attempt)
            val wait = jittered.delayFor(attempt)
            assertTrue("attempt $attempt waited ${wait}ms against a base of ${base}ms", wait in 1..base)
        }
    }

    /** Two clients dropped by one outage must not all come back in the same millisecond. */
    @Test fun jitterSpreadsClientsThatWereDroppedTogether() {
        val waits = (1..40).map {
            ReconnectPolicy(jitter = 0.2, random = Random(it.toLong())).delayFor(5)
        }.toSet()
        assertTrue("every client waited the same time: $waits", waits.size > 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anImmediateFirstRetryIsRefused() {
        ReconnectPolicy(initialDelayMillis = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCapBelowTheFirstDelayIsRefused() {
        ReconnectPolicy(initialDelayMillis = 5_000, maxDelayMillis = 1_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aScheduleThatShrinksIsRefused() {
        ReconnectPolicy(multiplier = 0.5)
    }
}
