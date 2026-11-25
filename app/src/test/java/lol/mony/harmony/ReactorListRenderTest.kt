package lol.mony.harmony

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import lol.mony.harmony.data.model.Bot
import lol.mony.harmony.data.model.Profile
import lol.mony.harmony.data.model.Reaction
import lol.mony.harmony.ui.components.ReactorList
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
 * The reactor list, drawn: who reacted with one emoji, named.
 *
 * The reported bug was that every reactor read "Someone". A test on the model alone would pass on
 * a sheet that never used it, so this draws the list the sheet draws and asserts the names that
 * end up on screen -- and that neither the placeholder nor a bare id is among them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h640dp-xhdpi")
class ReactorListRenderTest {
    @get:Rule val compose = createComposeRule()

    private val ada = Profile(id = "p1", authUserId = "a1", username = "ada", displayName = "Ada Lovelace",
        avatarUrl = "/avatars/p1.webp")
    private val grace = Profile(id = "p2", authUserId = "a2", username = "grace", displayName = "Grace Hopper")
    private val blobcat = Bot(id = "b1", username = "blobcat", displayName = "Blobcat")

    private fun reaction(json: String): Reaction = Json.decodeFromString(Reaction.serializer(), json)

    private fun draw(reactions: List<Reaction>, myId: String? = "p1") {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ReactorList(
                            reactions = reactions,
                            people = mapOf("p1" to ada, "p2" to grace),
                            bots = mapOf("b1" to blobcat),
                            myId = myId,
                            baseUrl = "https://harmony.test",
                            onOpenProfile = {},
                        )
                    }
                }
            }
        }
    }

    @Test fun everyReactorIsDrawnByName() {
        draw(listOf(
            reaction("""{"message_id":"m1","user_id":"p1","custom_emoji_content":"👍"}"""),
            reaction("""{"message_id":"m1","user_id":"p2","custom_emoji_content":"👍"}"""),
            reaction("""{"message_id":"m1","bot_id":"b1","custom_emoji_content":"👍"}"""),
            reaction("""{"message_id":"m1","metadata":{"discord_user":
                {"id":"d1","username":"discord_ada","display_name":"Ada of Discord"}}}"""),
        ))

        compose.onNodeWithTag("reactor-list").assertExists()
        compose.onNodeWithText("Ada Lovelace").assertExists()
        compose.onNodeWithText("Grace Hopper").assertExists()
        compose.onNodeWithText("Blobcat").assertExists()
        compose.onNodeWithText("Ada of Discord").assertExists()

        // The bug itself: nothing on this list may be called "Someone", and no row may fall back
        // to the id it is keyed by.
        assertNoText("Someone")
        assertNoText("Account ")
        compose.onNodeWithText("@ada").assertExists()
        compose.onNodeWithText("BOT").assertExists()

        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        write("reactors-named.png", image)
        assertTrue("the list drew something", nonBackground(image) > 500)
    }

    /** Each row is tagged by the person it names, so a tap opens that reactor and not another. */
    @Test fun aReactorRowIsTaggedByThePersonBehindIt() {
        draw(listOf(
            reaction("""{"message_id":"m1","user_id":"p1"}"""),
            reaction("""{"message_id":"m1","bot_id":"b1"}"""),
        ))
        compose.onNodeWithTag("reactor-user:p1").assertExists()
        compose.onNodeWithTag("reactor-bot:b1").assertExists()
    }

    /** A row whose profile could not be read says so, and never the placeholder. */
    @Test fun anUnreadableReactorIsNotCalledSomeone() {
        draw(listOf(reaction("""{"message_id":"m1","user_id":"nobody-read"}""")))
        compose.onNodeWithText("Unknown account").assertExists()
        assertNoText("Someone")
    }

    @Test fun anEmptyPillSaysSoRatherThanDrawingNothing() {
        draw(emptyList())
        compose.onNodeWithText("Nobody has reacted with this yet.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Someone").fetchSemanticsNodes().size)
    }

    private fun assertNoText(text: String) {
        assertEquals(
            "the list must not draw \"$text\"",
            0,
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size,
        )
    }

    private fun write(name: String, bitmap: Bitmap) {
        val out = File(System.getenv("HARMONY_RENDER_OUT") ?: System.getProperty("java.io.tmpdir"), name)
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels that are not the surface's own background, the cheapest proof that something drew. */
    private fun nonBackground(bitmap: Bitmap): Int {
        val background = bitmap.getPixel(0, 0)
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) != background) count++
        }
        return count
    }
}
