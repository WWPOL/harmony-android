package lol.mony.harmony

import lol.mony.harmony.data.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's recents list: newest first, no duplicates, one row long.
 *
 * The rules are pure functions on the stored list, so they are tested without a DataStore. That
 * is deliberate: `SessionStore`'s preferences file is a process-wide singleton, and a test that
 * wrote to it would leave the next test in the same JVM reading this one's emoji -- which is
 * exactly what happened when these tests first drove the store directly.
 *
 * What is being pinned is what makes the row useful the second time the picker opens: the face
 * a person uses is the one they used last, so a repeat moves to the front rather than appearing
 * twice, and the list never grows past a single row.
 */
class EmojiRecentsTest {

    @Test fun aPickGoesToTheFront() {
        var list = emptyList<String>()
        for (pick in listOf("\uD83D\uDC4D", "\uD83D\uDE02", "\uD83C\uDF89")) {
            list = SessionStore.addRecentEmoji(list, pick)
        }
        assertEquals(listOf("\uD83C\uDF89", "\uD83D\uDE02", "\uD83D\uDC4D"), list)
    }

    /** Picking the same face again moves it to the front instead of listing it twice. */
    @Test fun aRepeatedPickMovesToTheFrontRatherThanRepeating() {
        val list = listOf("\uD83D\uDE02", "\uD83D\uDC4D")
        assertEquals(listOf("\uD83D\uDC4D", "\uD83D\uDE02"), SessionStore.addRecentEmoji(list, "\uD83D\uDC4D"))
    }

    /** The list is capped, so the row stays a row however long the account has been used. */
    @Test fun theListIsCapped() {
        var list = emptyList<String>()
        // Distinct entries, so nothing is deduplicated: the cap is what is being measured.
        repeat(SessionStore.RECENT_EMOJI + 10) { index ->
            list = SessionStore.addRecentEmoji(list, "pick-" + index)
        }
        assertEquals("the cap holds", SessionStore.RECENT_EMOJI, list.size)
        assertEquals("and the newest is first", "pick-" + (SessionStore.RECENT_EMOJI + 9), list.first())
    }

    /** A custom emoji is remembered by its id, so a rename on the instance does not lose it. */
    @Test fun aCustomPickIsRememberedById() {
        val id = "id:8c1f0e2a-0000-4000-8000-000000000001"
        assertEquals(listOf(id), SessionStore.addRecentEmoji(emptyList(), id))
    }

    /** A pick carrying a newline would split the stored list, so it is refused. */
    @Test fun aPickThatWouldSplitTheListIsRefused() {
        assertFalse(SessionStore.isRecordableEmoji("\uD83D\uDC4D\n\uD83D\uDE02"))
        assertFalse("a blank pick is not an emoji", SessionStore.isRecordableEmoji("   "))
        assertFalse("and neither is nothing", SessionStore.isRecordableEmoji(""))
        assertTrue(SessionStore.isRecordableEmoji("\uD83D\uDC4D"))
        assertEquals("the list is untouched",
            listOf("\uD83D\uDC4D"), SessionStore.addRecentEmoji(listOf("\uD83D\uDC4D"), "\uD83D\uDE02\n\uD83C\uDF89"))
    }

    /** A stored list round-trips, and a blank line in it is not an entry. */
    @Test fun theStoredListRoundTrips() {
        assertEquals(listOf("\uD83D\uDC4D", "id:abc"), SessionStore.parseRecentEmoji("\uD83D\uDC4D\nid:abc"))
        assertEquals("a blank line is not an entry", listOf("\uD83D\uDC4D"),
            SessionStore.parseRecentEmoji("\uD83D\uDC4D\n\n"))
        assertTrue("nothing stored is nothing", SessionStore.parseRecentEmoji(null).isEmpty())
        assertTrue("and so is an empty string", SessionStore.parseRecentEmoji("").isEmpty())
        // The two halves agree: what is written is what is read back.
        val written = SessionStore.addRecentEmoji(emptyList(), "id:abc").joinToString("\n")
        assertEquals(listOf("id:abc"), SessionStore.parseRecentEmoji(written))
    }
}
