package lol.mony.harmony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.SessionSealer
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.data.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a channel was left, remembered across a restart.
 *
 * The report: "the app does not seem to remember where you were most recently in a channel and
 * will often start you scrolled up a month before the most recent messages, even if you've
 * already scrolled all the way down before". The anchor is a message id rather than an index,
 * because messages arrive while a channel is closed -- the index that was the top is a
 * different message by the time the channel reopens, while the id still names the same one.
 *
 * These tests are the store's contract: an anchor survives a restart, it is scoped to the
 * account and the room, and a channel left at its end is remembered as *no* anchor so the next
 * visit opens at the newest message rather than at the last one that was visible.
 */
/**
 * A Robolectric sandbox of its own.
 *
 * `preferencesDataStore` caches one DataStore per file per classloader, and Robolectric reuses a
 * sandbox across every test class that resolves to the same configuration -- so a session this
 * class writes would be read back by the next class in the same sandbox. `SessionStoreRecoveryTest`
 * is that next class: it deliberately starts from a corrupt file and asserts an empty store, and
 * a fixture session left behind here makes it fail with a real instance instead of null. (It says
 * so itself, and `NotificationCoordinatorTest` avoids signing in for the same reason.)
 *
 * This test cannot avoid signing in -- an anchor is scoped to an account, so an account is what
 * it has to have. A sandbox is keyed on the SDK, the modes and the *instrumented packages*, so
 * declaring one extra package here is what puts this class in a sandbox of its own; `sdk` and
 * `qualifiers` alone do not, and neither does `application`, which is applied inside an existing
 * sandbox rather than creating one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], instrumentedPackages = ["lol.mony.harmony.anchor"])
class ReadAnchorTest {
    /** The JVM has no Android Keystore; production never uses this sealer. */
    private class TestSealer : SessionSealer {
        private val key = javax.crypto.spec.SecretKeySpec(
            java.util.Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZg=="), "AES")
        override fun seal(plaintext: String): String {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return java.util.Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray()))
        }
        override fun unseal(sealed: String): String {
            val bytes = java.util.Base64.getDecoder().decode(sealed)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }

    private fun store(): SessionStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionStore(context, TestSealer())
        runBlocking {
            store.saveInstance(Endpoint("https://harmony.test", "anon"), "Harmony")
            store.saveSession(
                StoredSession(
                    endpoint = Endpoint("https://harmony.test", "anon"),
                    accessToken = "token", refreshToken = "refresh",
                    expiresAtMillis = Long.MAX_VALUE, userId = "p1", email = "ada@example.test",
                ),
            )
        }
        return store
    }

    @Test fun anAnchorSurvivesAReadBack() = runBlocking {
        val store = store()
        assertNull("a channel never read has no anchor", store.lastReadMessage("channel|c1"))
        store.saveLastReadMessage("channel|c1", "m-42")
        assertEquals("m-42", store.lastReadMessage("channel|c1"))
    }

    /**
     * A channel left at its end forgets the anchor rather than storing the last visible row.
     *
     * That is the difference between "opens where I was" and "opens where I was the last time I
     * scrolled up": a person who has scrolled all the way down wants the newest message, and an
     * anchor pointing at the newest one would strand them a page back as soon as something new
     * arrives.
     */
    @Test fun leavingAtTheEndClearsTheAnchor() = runBlocking {
        val store = store()
        store.saveLastReadMessage("channel|c1", "m-42")
        store.saveLastReadMessage("channel|c1", null)
        assertNull(store.lastReadMessage("channel|c1"))
    }

    /** Two rooms, two anchors: reading one must not move the other. */
    @Test fun anchorsArePerRoom() = runBlocking {
        val store = store()
        store.saveLastReadMessage("channel|c1", "m-1")
        store.saveLastReadMessage("channel|c2", "m-2")
        store.saveLastReadMessage("dm|d1", "m-3")
        assertEquals("m-1", store.lastReadMessage("channel|c1"))
        assertEquals("m-2", store.lastReadMessage("channel|c2"))
        assertEquals("m-3", store.lastReadMessage("dm|d1"))
    }

    /**
     * An anchor belongs to the account that read it.
     *
     * The key is hashed from the instance and the account id, so a second account on the same
     * phone starts at the newest message of a channel it has never opened rather than at a
     * stranger's place in it.
     */
    @Test fun anchorsArePerAccount() = runBlocking {
        val store = store()
        store.saveLastReadMessage("channel|c1", "m-1")
        runBlocking {
            store.saveSession(
                StoredSession(
                    endpoint = Endpoint("https://harmony.test", "anon"),
                    accessToken = "other", refreshToken = "other",
                    expiresAtMillis = Long.MAX_VALUE, userId = "p2", email = "bob@example.test",
                ),
            )
        }
        assertNull(
            "the second account inherited the first account's reading position",
            store.lastReadMessage("channel|c1"),
        )
    }

    /**
     * A draft and an anchor are different keys over one room.
     *
     * They are written by different parts of the room at different times, so a shared key would
     * have one overwrite the other: a person would lose the draft they were typing by scrolling.
     */
    @Test fun aDraftAndAnAnchorDoNotCollide() = runBlocking {
        val store = store()
        store.saveDraft("c1", "half a sentence")
        store.saveLastReadMessage("c1", "m-9")
        assertEquals("half a sentence", store.draft("c1"))
        assertEquals("m-9", store.lastReadMessage("c1"))
    }
}
