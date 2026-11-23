package lol.mony.harmony.data.api

import kotlin.math.min
import kotlin.random.Random

/**
 * How long to wait before each attempt to bring the live connection back.
 *
 * A dropped socket is the normal case on a phone: a lift ride, a tunnel, a Wi-Fi handover, a
 * screen that slept. The app is expected to come back on its own, so the wait doubles from
 * [initialDelayMillis] up to [maxDelayMillis] rather than hammering an instance that is down or
 * spending a minute between attempts on an instance that is up.
 *
 * Two properties matter and are pinned by `ReconnectPolicyTest`:
 *
 * - The first retry is quick, because a blip that lasted a second should not leave a room dead
 *   for thirty.
 * - The wait is capped, because an instance that is unreachable for an hour must still be
 *   noticed within [maxDelayMillis] of coming back.
 *
 * [jitter] spreads the retries of many clients that were dropped by one outage, so an instance
 * coming back does not receive all of them in the same instant. It is zero in tests, where the
 * schedule is the subject.
 */
data class ReconnectPolicy(
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000,
    val multiplier: Double = 2.0,
    val jitter: Double = 0.2,
    val random: Random = Random.Default,
) {
    init {
        require(initialDelayMillis > 0) { "the first retry must not be immediate" }
        require(maxDelayMillis >= initialDelayMillis) { "the cap must not be below the first delay" }
        require(multiplier >= 1.0) { "a schedule that shrinks would retry faster the longer it fails" }
        require(jitter in 0.0..1.0) { "jitter is a fraction of the delay" }
    }

    /**
     * The wait before attempt number [attempt], counting the first retry as 1.
     *
     * The exponential part is computed in `Double` and clamped before it is turned back into a
     * `Long`, so a connection that has failed all night cannot overflow into a negative delay --
     * which would be a tight retry loop, the opposite of what a backoff is for.
     */
    fun delayFor(attempt: Int): Long {
        val step = attempt.coerceAtLeast(1) - 1
        val growth = Math.pow(multiplier, step.toDouble())
        val base = min(maxDelayMillis.toDouble(), initialDelayMillis.toDouble() * growth).toLong()
        if (jitter <= 0.0) return base
        // Jitter only ever shortens the wait, so the cap is still a cap.
        val spread = (base * jitter).toLong()
        return if (spread <= 0L) base else base - random.nextLong(spread + 1)
    }
}
