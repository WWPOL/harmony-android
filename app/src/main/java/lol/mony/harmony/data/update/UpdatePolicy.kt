package lol.mony.harmony.data.update

/**
 * When the app may ask GitHub whether there is a newer build, and how it waits after failing.
 *
 * This is the part of the update check that is about *other people's servers*, so it is written
 * as a pure function of the clock and the stored state: the rules can be tested without a
 * network, a device or a wait, and the thing that runs them cannot quietly do something else.
 *
 * The rules, and why each one is there:
 *
 * - **At most one check a day.** An app that checks every launch is a fleet of installs asking
 *   one repository the same question thousands of times an hour. The question changes when a
 *   release is published, which is a handful of times a month, so a day is already generous.
 * - **A per-install spread.** The day's interval carries up to six hours of jitter derived from
 *   a value this install generates once. Without it, every device that installed the app in the
 *   same week asks at the same minute of the day for ever, and the release's first hour is a
 *   spike against a CDN that only ever sees the same small file.
 * - **The first check is not the first frame.** A launch is already busy -- restoring the
 *   session, reading servers, opening a socket -- and a check competing with that is a check
 *   that makes the app feel slow. It runs a minute after the app has been opened, spread over
 *   half an hour, so a fresh install neither asks while it is being set up nor joins a crowd.
 * - **Failure backs off.** A device with no usable network, a repository that is briefly down,
 *   or a captive portal that answers nonsense would otherwise ask again the next day, every
 *   day, for ever. The wait doubles from a day to a week, and a success puts it back.
 * - **A 304 is a success.** A conditional request that GitHub answers "not modified" means the
 *   app has the newest manifest already; that is not a failure and must not lengthen the wait.
 */
object UpdatePolicy {
    /** How long a successful check keeps the next one away: a day. */
    const val INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

    /** The most a successful check's interval is spread by, per install. */
    const val JITTER_MILLIS: Long = 6L * 60 * 60 * 1000

    /** How long after the first launch the first check may run. */
    const val FIRST_DELAY_MILLIS: Long = 60L * 1000

    /** The most the first check is spread by, so a release week is not a stampede. */
    const val FIRST_JITTER_MILLIS: Long = 30L * 60 * 1000

    /** The wait after the first failed check; it doubles from here. */
    const val RETRY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** The longest wait a failing check reaches: a week. */
    const val RETRY_MAX_MILLIS: Long = 7L * 24L * 60 * 60 * 1000

    /**
     * A stable offset in `[0, bound)` for one install, one purpose.
     *
     * [seed] is the install's own random value, stored the first time it is needed, and [salt]
     * tells the purposes apart so the first check's spread and the daily one are not the same
     * number. The arithmetic is deliberately plain: this only has to be spread out, not
     * unpredictable, and it must give the same answer on every launch of the same install.
     */
    fun offset(seed: Long, salt: Int, bound: Long): Long {
        if (bound <= 0) return 0
        var mixed = seed + salt * GOLDEN
        // splitmix64's finaliser: a cheap way to turn a counter-like seed into a spread. Long
        // multiplication wraps in Kotlin, which is what this wants.
        mixed = (mixed xor (mixed ushr 30)) * MIX_A
        mixed = (mixed xor (mixed ushr 27)) * MIX_B
        mixed = mixed xor (mixed ushr 31)
        // `ushr` first, so the result is never negative and the offset always lies in [0, bound).
        return (mixed ushr 1) % bound
    }

    private const val GOLDEN = -7046029254386353131L // 0x9E3779B97F4A7C15
    private const val MIX_A = -4658895280553007687L // 0xBF58476D1CE4E5B9
    private const val MIX_B = -7723592293110705685L // 0x94D049BB133111EB

    /** The earliest time the next check may run, from the state a check left behind. */
    fun dueAt(state: UpdateState, seed: Long): Long {
        if (state.lastCheckMillis == 0L) {
            // Never checked on this install: a minute, spread over half an hour.
            return state.firstSeenMillis + FIRST_DELAY_MILLIS +
                offset(seed, SALT_FIRST, FIRST_JITTER_MILLIS)
        }
        val wait = if (state.failures == 0) {
            INTERVAL_MILLIS + offset(seed, SALT_DAILY, JITTER_MILLIS)
        } else {
            retryDelay(state.failures)
        }
        return state.lastCheckMillis + wait
    }

    /** The wait after [failures] consecutive failed checks: a day, then double, up to a week. */
    fun retryDelay(failures: Int): Long {
        if (failures <= 0) return RETRY_MILLIS
        // Doubling, capped. `1L shl 30` is far past the cap, so the shift is bounded first.
        val doublings = failures.coerceAtMost(8)
        val wait = RETRY_MILLIS shl (doublings - 1)
        return if (wait <= 0 || wait > RETRY_MAX_MILLIS) RETRY_MAX_MILLIS else wait
    }

    /** Whether a check may run now. */
    fun isDue(state: UpdateState, now: Long, seed: Long): Boolean =
        state.enabled && now >= dueAt(state, seed)

    /** The state after a check that reached GitHub and got an answer, 304 included. */
    fun succeeded(
        state: UpdateState,
        now: Long,
        etag: String? = state.etag,
        lastModified: String? = state.lastModified,
    ): UpdateState = state.copy(
        lastCheckMillis = now,
        failures = 0,
        etag = etag ?: state.etag,
        lastModified = lastModified ?: state.lastModified,
    )

    /** The state after a check that got no usable answer: the wait grows, the validator is kept. */
    fun failed(state: UpdateState, now: Long): UpdateState =
        state.copy(lastCheckMillis = now, failures = (state.failures + 1).coerceAtMost(8))

    private const val SALT_FIRST = 1
    private const val SALT_DAILY = 2
}

/**
 * What this device remembers about the update check.
 *
 * It is device state, not account state: an update is a fact about the APK on the phone, and
 * signing out of an instance does not make the app a different build. It is stored in the app's
 * own preferences beside the notification delivery records, for the same reason -- it must
 * survive a restart, and it is small enough that a DataStore would be more machinery than the
 * thing it holds.
 *
 * [firstSeenMillis] is written once, the first time the app has an opinion about updates at all,
 * and never again: the first check's delay is counted from it, so reinstalling an app on a phone
 * that is opened twice in a day does not ask twice.
 */
data class UpdateState(
    val enabled: Boolean = true,
    val firstSeenMillis: Long = 0,
    val lastCheckMillis: Long = 0,
    val failures: Int = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    /** The install's own random value, which spreads its checks from every other install's. */
    val seed: Long = 0,
    /** The newest build this device has been told about, so a notice is not repeated for it. */
    val offeredVersionCode: Int = 0,
    /** The build the person said "not now" to; the notice is not shown for it again. */
    val dismissedVersionCode: Int = 0,
)
