package lol.mony.harmony.data.update

import android.content.Context
import kotlinx.serialization.Serializable
import lol.mony.harmony.data.model.HarmonyJson
import java.security.SecureRandom

/**
 * Where [UpdateState] lives: the app's own preferences, one file, one entry.
 *
 * It is a small, write-rarely record -- a timestamp, a counter, an ETag -- so it does not need a
 * DataStore's transactions; what it does need is to survive a restart, which a preferences file
 * does. It is deliberately *not* per account: an update is a fact about the installed APK, and
 * two accounts on one phone share it.
 *
 * A record that cannot be read is replaced with a fresh one rather than thrown: a truncated
 * write must not stop the app from starting, and the cost of starting over is one check.
 */
interface UpdateStore {
    fun load(): UpdateState
    fun save(state: UpdateState)
}

class PreferencesUpdateStore(context: Context) : UpdateStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): UpdateState {
        val text = preferences.getString(KEY, null) ?: return UpdateState()
        return runCatching { HarmonyJson.decodeFromString(Stored.serializer(), text).toState() }
            .getOrDefault(UpdateState())
    }

    override fun save(state: UpdateState) {
        val text = HarmonyJson.encodeToString(Stored.serializer(), Stored.from(state))
        preferences.edit().putString(KEY, text).apply()
    }

    @Serializable
    private data class Stored(
        val enabled: Boolean = true,
        val firstSeen: Long = 0,
        val lastCheck: Long = 0,
        val failures: Int = 0,
        val etag: String? = null,
        val lastModified: String? = null,
        val seed: Long = 0,
        val offered: Int = 0,
        val dismissed: Int = 0,
    ) {
        fun toState() = UpdateState(
            enabled = enabled,
            firstSeenMillis = firstSeen,
            lastCheckMillis = lastCheck,
            failures = failures.coerceAtLeast(0),
            etag = etag,
            lastModified = lastModified,
            seed = seed,
            offeredVersionCode = offered,
            dismissedVersionCode = dismissed,
        )

        companion object {
            fun from(state: UpdateState) = Stored(
                enabled = state.enabled,
                firstSeen = state.firstSeenMillis,
                lastCheck = state.lastCheckMillis,
                failures = state.failures,
                etag = state.etag,
                lastModified = state.lastModified,
                seed = state.seed,
                offered = state.offeredVersionCode,
                dismissed = state.dismissedVersionCode,
            )
        }
    }

    private companion object {
        const val FILE = "harmony-update"
        const val KEY = "update.state"
    }
}

/**
 * Fills in the two values an install has to invent for itself.
 *
 * [UpdateState.firstSeenMillis] is when this install first had an opinion about updates, and
 * [UpdateState.seed] is what spreads its checks from every other install's. Both are written
 * once: a state that was read with them missing is a fresh install (or one from before the
 * check existed), and giving it "now" is right for both.
 *
 * It is a function rather than part of the check so a test can hand it a fixed clock and see
 * exactly what a first launch stores.
 */
fun UpdateState.initialised(now: Long, random: SecureRandom = SecureRandom()): UpdateState {
    if (firstSeenMillis != 0L && seed != 0L) return this
    return copy(
        firstSeenMillis = if (firstSeenMillis == 0L) now else firstSeenMillis,
        seed = if (seed == 0L) random.nextLong() else seed,
    )
}
