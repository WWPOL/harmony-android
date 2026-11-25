package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lol.mony.harmony.data.model.SocialPost
import lol.mony.harmony.data.model.SocialProfile
import lol.mony.harmony.ui.screens.social.SocialPostCard
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A post's words are not a selection surface either, and copying it is a verb.
 *
 * The same defect as a message body, on the surface that had no alternative: a social post's
 * body was inside a `SelectionContainer`, so a long press selected a word and nothing else
 * happened -- a post body is a tap target for its hashtags and mentions, and the card's own
 * gestures are the avatar, the name and the actions row.
 *
 * So the container is gone, and the post's overflow menu copies the body instead. These tests
 * read pixels for the first claim, because a selection is paint, and the menu for the second.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 27: no selection magnifier, so a selection can only be paint and a pixel count is the
// honest measurement of whether one was drawn (see MessageSelectionTest).
@Config(sdk = [27], qualifiers = "w360dp-h800dp-xhdpi")
class SocialPostSelectionTest {
    @get:Rule val compose = createComposeRule()

    private val body = "a post about a thing that happened"

    private fun post() = SocialPost(
        id = "post-1",
        authorId = "p1",
        author = SocialProfile(id = "p1", username = "noah", displayName = "Noah"),
        content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", body) })
        },
        createdAt = "2026-01-01T00:01:00Z",
    )

    private fun draw() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    SocialPostCard(
                        row = post(), profileId = "p1", baseUrl = "https://example.test",
                        busy = false,
                        onOpen = {}, onProfile = {}, onHashtag = {}, onReply = {}, onEdit = {},
                        onDelete = {}, onFavorite = {}, onBoost = {}, onBookmark = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pixels(): IntArray {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = IntArray(image.width * image.height)
        image.getPixels(out, 0, image.width, 0, 0, image.width, image.height)
        return out
    }

    @Test fun aLongPressOnAPostBodyPaintsNothing() {
        draw()
        val before = pixels()
        compose.onNodeWithText(body).performTouchInput { longClick() }
        compose.waitForIdle()
        // One capture, compared against the one before the press: a capture inside the count
        // would take the whole screen once per pixel.
        val after = pixels()
        val changed = before.indices.count { before[it] != after[it] }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "social-post-long-press-no-selection.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals("a long press on a post must not paint a selection, but $changed pixels changed",
            0, changed)
    }

    /** The copy that used to be the long press is now the menu's own item, and it is offered. */
    @Test fun thePostMenuOffersCopyText() {
        draw()
        compose.onNodeWithContentDescription("Post options").performClick()
        compose.waitForIdle()
        assertTrue("the post's menu must offer Copy text",
            compose.onAllNodesWithText("Copy text").fetchSemanticsNodes().isNotEmpty())
        // The picture of the menu, because "the verb moved into the menu" is a thing a person
        // should be able to look at rather than take on trust.
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"),
            "social-post-menu-copy-text.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
