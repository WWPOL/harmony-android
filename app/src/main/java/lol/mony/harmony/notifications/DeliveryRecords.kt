package lol.mony.harmony.notifications

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.model.HarmonyJson

/** The account a poll delivers for: the instance and the profile, which is what scopes a record. */
data class DeliveryAccount(val endpoint: Endpoint, val profileId: String)

/**
 * What the tray has already been told about one account, kept across restarts.
 *
 * It is deliberately *not* the inbox's read state: a person can dismiss a notification without
 * the row becoming read, and that dismissal has to be final. The record is the device's memory of
 * what it has already said, and it survives the process, a sign-out and a reinstall of the APK.
 */
data class DeliveryRecord(
    /** Whether the account has been watched at least once; before that there is no cutoff. */
    val primed: Boolean = false,
    /**
     * The newest row that existed on the first poll. Nothing at or before it is delivered: it was
     * already in the inbox before this device started watching, so it is history, not news.
     */
    val cutoff: Instant? = null,
    /** The rows already shown in the tray, oldest first. */
    val delivered: List<String> = emptyList(),
)

/** The records, one per account and instance. */
interface DeliveryRecords {
    fun load(scope: String): DeliveryRecord
    fun save(scope: String, record: DeliveryRecord)

    companion object {
        /**
         * The scope a record is kept under: the instance and the profile, never a token.
         *
         * Two accounts on one device therefore cannot share a record, and the same account on two
         * instances is two records -- a notification id means nothing on another instance.
         */
        fun scope(account: DeliveryAccount): String =
            account.endpoint.baseUrl.trimEnd('/') + "|" + account.profileId
    }
}

/**
 * The delivery records in this device's own preferences, so the tray's memory outlives the app.
 *
 * The value is one small JSON document per account. A record that cannot be read is treated as
 * absent rather than thrown: the worst case is that the account is primed again, which suppresses
 * the old rows instead of replaying them, so a corrupt file costs nothing a person would notice.
 */
class PreferencesDeliveryRecords(context: Context) : DeliveryRecords {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(scope: String): DeliveryRecord {
        val text = preferences.getString(key(scope), null) ?: return DeliveryRecord()
        return runCatching {
            HarmonyJson.decodeFromString(Stored.serializer(), text).toRecord()
        }.getOrDefault(DeliveryRecord())
    }

    override fun save(scope: String, record: DeliveryRecord) {
        val text = HarmonyJson.encodeToString(Stored.serializer(), Stored.from(record))
        preferences.edit { putString(key(scope), text) }
    }

    private fun key(scope: String): String = "delivery." + MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

    @Serializable
    private data class Stored(
        val primed: Boolean = false,
        val cutoff: String? = null,
        val delivered: List<String> = emptyList(),
    ) {
        fun toRecord() = DeliveryRecord(
            primed = primed,
            cutoff = cutoff?.let { runCatching { Instant.parse(it) }.getOrNull() },
            delivered = delivered,
        )

        companion object {
            fun from(record: DeliveryRecord) =
                Stored(record.primed, record.cutoff?.toString(), record.delivered)
        }
    }

    private companion object { const val FILE = "harmony-notification-delivery" }
}
