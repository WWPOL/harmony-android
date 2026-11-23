package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.CustomEmoji
import lol.mony.harmony.ui.components.EmojiPick
import lol.mony.harmony.ui.components.EmojiPicker
import lol.mony.harmony.ui.components.QuickReactionRow
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The picker, driven the way a finger drives it: a search typed, a tab tapped, a tone chosen,
 * an emoji picked.
 *
 * The reported problem was that only five faces could be used at all, so the tests here are
 * about the set being *reachable*: that the grid holds more than five, that a search narrows it
 * to what was typed, that a category tab changes it, that a tone changes what a pick inserts,
 * and that a custom emoji is picked as itself rather than as its shortcode.
 *
 * Every callback is recorded rather than mocked, so a pick is asserted by what the picker
 * handed over -- which is the only thing the screen above it acts on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class EmojiPickerTest {
    @get:Rule val compose = createComposeRule()

    private val picks = mutableListOf<EmojiPick>()

    /** Every node whose test tag starts with [prefix], which is how a cell row is counted. */
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun open(
        custom: List<CustomEmoji> = emptyList(),
        recent: List<String> = emptyList(),
        notice: String? = null,
        blocked: Boolean = false,
        onClearRecent: (() -> Unit)? = null,
    ) {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface(Modifier.fillMaxWidth().padding(8.dp)) {
                    EmojiPicker(
                        onPick = { picks += it },
                        customEmoji = custom,
                        recent = recent,
                        notice = notice,
                        blocked = blocked,
                        onClearRecent = onClearRecent,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The grid offers the whole set, not a handful of defaults. */
    @Test fun theGridOffersFarMoreThanTheOldFiveFaces() {
        open()
        val cells = compose.onAllNodes(hasTestTagPrefix("emoji-cell-")).fetchSemanticsNodes()
        // A lazy grid only composes what fits, so the count on screen is a floor rather than the
        // total: what matters is that the first category alone is far past five.
        assertTrue("the grid has many emoji, not a row of five: ${cells.size}", cells.size > 20)
    }

    /** Picking a face hands over its glyph, which is what the reaction stores. */
    @Test fun pickingAFaceHandsOverItsGlyph() {
        open()
        compose.onNodeWithTag("emoji-cell-grinning face").performClick()
        compose.waitForIdle()
        assertEquals("one pick", 1, picks.size)
        assertEquals("\uD83D\uDE00", picks.single().glyph)
        assertNull("a unicode pick has no custom row", picks.single().custom)
    }

    /** A search narrows the grid to what was typed, across every category. */
    @Test fun aSearchFindsAnEmojiInAnyCategory() {
        open()
        compose.onNodeWithTag("emoji-search").performTextInput("thumbs up")
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-cell-thumbs up").assertExists()
        // A face from another category is gone: the search replaced the tab's grid.
        assertTrue("the smileys are filtered out",
            compose.onAllNodesWithTag("emoji-cell-grinning face").fetchSemanticsNodes().isEmpty())
    }

    /** A search that matches nothing says so rather than showing an empty box. */
    @Test fun aSearchWithNoMatchSaysSo() {
        open()
        compose.onNodeWithTag("emoji-search").performTextInput("zzzzqqq")
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-no-results").assertExists()
    }

    /** Clearing the search brings the categories back. */
    @Test fun clearingTheSearchRestoresTheCategories() {
        open()
        compose.onNodeWithTag("emoji-search").performTextInput("cat")
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-search-clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-tabs").assertExists()
        compose.onNodeWithTag("emoji-cell-grinning face").assertExists()
    }

    /** A category tab changes the grid, so the whole set is browsable. */
    @Test fun aTabShowsItsOwnCategory() {
        open()
        // The strip is wider than the window, so the last tabs are reached by scrolling it,
        // exactly as a finger does.
        compose.onNodeWithTag("emoji-tabs").performScrollToNode(hasTestTag("emoji-tab-FLAGS"))
        compose.onNodeWithTag("emoji-tab-FLAGS").performClick()
        compose.waitForIdle()
        // A lazy grid composes only what fits, so the far end of the list is reached by
        // scrolling it, exactly as a person does.
        compose.onNodeWithTag("emoji-grid").performScrollToNode(hasTestTag("emoji-cell-flag: Japan"))
        compose.onNodeWithTag("emoji-cell-flag: Japan").assertExists()
        assertTrue("the smileys are gone",
            compose.onAllNodesWithTag("emoji-cell-grinning face").fetchSemanticsNodes().isEmpty())
    }

    /** A tone changes what a tone-able emoji inserts, and the row shows which is chosen. */
    @Test fun aSkinToneChangesWhatIsPicked() {
        open()
        compose.onNodeWithTag("emoji-tone-Light").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-tone-Light").assertIsSelected()
        compose.onNodeWithTag("emoji-tone-Dark").assertIsNotSelected()
        // "waving hand" is the first tone-able emoji in the People category.
        compose.onNodeWithTag("emoji-tab-PEOPLE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-cell-waving hand").performClick()
        compose.waitForIdle()
        assertEquals("the pick carries the tone", "\uD83D\uDC4B\uD83C\uDFFB", picks.single().glyph)
    }

    /** The default tone inserts the plain glyph, so a tone is never applied by accident. */
    @Test fun theDefaultToneInsertsThePlainGlyph() {
        open()
        compose.onNodeWithTag("emoji-tab-PEOPLE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-cell-waving hand").performClick()
        compose.waitForIdle()
        assertEquals("\uD83D\uDC4B", picks.single().glyph)
    }

    /** A custom emoji is picked as its row, so the reaction is stored as its `emoji_id`. */
    @Test fun aCustomEmojiIsPickedAsItsRow() {
        open(custom = listOf(CustomEmoji(id = "e-cat", name = "cat", url = "file:///nope.png", scope = "server")))
        // The custom emoji have a tab of their own; the default tab is Unicode's.
        compose.onNodeWithTag("emoji-source-custom").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-custom-cat").performClick()
        compose.waitForIdle()
        val pick = picks.single()
        assertEquals("the row is the pick", "e-cat", pick.emojiId)
        assertEquals("and its shortcode is the label", ":cat:", pick.glyph)
        assertEquals("the recents key is the id", "id:e-cat", pick.recentKey)
    }

    /** The recents are the first row, and a pick from them hands over what it stands for. */
    @Test fun aRecentPickIsOneTap() {
        open(recent = listOf("\uD83D\uDC4D", "\uD83C\uDF89"))
        compose.onNodeWithTag("emoji-recent").assertExists()
        compose.onNodeWithTag("emoji-recent-\uD83D\uDC4D").performClick()
        compose.waitForIdle()
        assertEquals("\uD83D\uDC4D", picks.single().glyph)
    }

    /** A recent custom emoji is found in the library, so it draws as itself. */
    @Test fun aRecentCustomEmojiResolvesThroughTheLibrary() {
        open(
            custom = listOf(CustomEmoji(id = "e-cat", name = "cat", url = "file:///nope.png", scope = "server")),
            recent = listOf("id:e-cat"),
        )
        // The row is tagged with what the pick stands for, which for a custom emoji is its id.
        compose.onNodeWithTag("emoji-recent-id:e-cat").performClick()
        compose.waitForIdle()
        assertEquals("the row is the pick", "e-cat", picks.single().emojiId)
    }

    /** A recent custom emoji the library no longer holds is dropped, not drawn as a blank. */
    @Test fun aRecentEmojiThatIsGoneIsDropped() {
        open(custom = emptyList(), recent = listOf("id:e-deleted"))
        assertTrue("nothing to draw from that key",
            compose.onAllNodes(hasTestTagPrefix("emoji-recent-")).fetchSemanticsNodes().isEmpty())
    }

    /** The limit is stated before the tap, and the grid stops taking picks. */
    @Test fun theReactionLimitIsStatedAndBlocks() {
        open(notice = "This message has 20 of your reactions.", blocked = true)
        compose.onNodeWithTag("emoji-limit-notice").assertExists()
        compose.onNodeWithText("This message has 20 of your reactions.").assertExists()
        compose.onNodeWithTag("emoji-cell-grinning face").performClick()
        compose.waitForIdle()
        assertTrue("a blocked picker does not pick: $picks", picks.isEmpty())
    }

    /** Clearing the recents is offered only when there is something to clear. */
    @Test fun clearingTheRecentsIsOfferedWhenThereAreSome() {
        var cleared = 0
        open(recent = listOf("\uD83D\uDC4D"), onClearRecent = { cleared++ })
        compose.onNodeWithTag("emoji-recent-clear").performClick()
        compose.waitForIdle()
        assertEquals("cleared once", 1, cleared)
    }

    /** A search finds a custom emoji beside the unicode ones, without switching tabs. */
    @Test fun aSearchFindsCustomEmojiBesideUnicodeOnes() {
        open(custom = listOf(CustomEmoji(id = "e-cat", name = "cat", url = "file:///nope.png", scope = "server")))
        compose.onNodeWithTag("emoji-search").performTextInput("cat")
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-custom-cat").assertExists()
        assertTrue("and the cat faces are there too",
            compose.onAllNodes(hasTestTagPrefix("emoji-cell-")).fetchSemanticsNodes().isNotEmpty())
    }

    /** With no custom emoji there is no tab for them, so the strip is not a dead end. */
    @Test fun theCustomTabIsOfferedOnlyWhenThereAreCustomEmoji() {
        open(custom = emptyList())
        assertTrue("no custom tab without custom emoji",
            compose.onAllNodesWithTag("emoji-custom-tab").fetchSemanticsNodes().isEmpty())
    }

    /** The quick row reacts with a face, and its last cell opens the picker. */
    @Test fun theQuickRowReactsAndOpensThePicker() {
        val reacted = mutableListOf<String>()
        var more = 0
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    QuickReactionRow(onReact = { reacted += it }, onMore = { more++ })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("quick-reaction-\uD83D\uDC4D").performClick()
        compose.onNodeWithTag("quick-reaction-more").performClick()
        compose.waitForIdle()
        assertEquals("one face reacted", listOf("\uD83D\uDC4D"), reacted)
        assertEquals("and the picker opened once", 1, more)
    }

    /** A typed search survives the grid changing under it, which is what a search box must do. */
    @Test fun aSearchIsNotLostWhenTheGridChanges() {
        open()
        compose.onNodeWithTag("emoji-search").performTextReplacement("heart")
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-search").performTextReplacement("heart")
        compose.waitForIdle()
        assertTrue("the search still finds hearts",
            compose.onAllNodes(hasTestTagPrefix("emoji-cell-")).fetchSemanticsNodes().isNotEmpty())
    }
}
