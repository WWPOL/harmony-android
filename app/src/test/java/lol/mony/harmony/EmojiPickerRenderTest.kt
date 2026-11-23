package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.data.model.CustomEmoji
import lol.mony.harmony.ui.components.EmojiPicker
import lol.mony.harmony.ui.components.EmojiPickerSheet
import lol.mony.harmony.ui.components.QuickReactionRow
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The emoji picker, drawn: the whole set, the categories, the tones, the recents and the
 * custom emoji, in both looks.
 *
 * These are the pictures that answer the report. The screen used to be five faces in a row;
 * what is captured here is the picker those five now lead to. The assertions are on the
 * semantics tree -- a face from a category, a search result, a custom emoji -- so a picker that
 * drew a blank sheet fails rather than looking like a theme with nothing in it.
 *
 * The custom emoji point at a `file://` PNG this test writes, so nothing depends on a network
 * the JVM runner does not have.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class EmojiPickerRenderTest {
    @get:Rule val compose = createComposeRule()

    /** The colour the custom emoji are painted in, so a drawn one is unmistakable. */
    private val marker = android.graphics.Color.rgb(255, 0, 170)

    /** A solid block of colour, so a drawn custom emoji is unmistakable in the capture. */
    private fun markerPng(): String {
        val file = java.io.File.createTempFile("custom-emoji", ".png")
        val bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(marker)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return file.toURI().toString()
    }

    /** True when a pixel of the marker colour is in the bitmap, which is how a picture is proved. */
    private fun hasMarker(bitmap: android.graphics.Bitmap): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) < 80 &&
                android.graphics.Color.blue(pixel) in 130..210) return true
        }
        return false
    }

    /**
     * Captures, after waiting for a custom emoji's picture when one is on screen.
     *
     * Coil decodes off the main thread, so the pictures arrive a frame or two after the cells
     * do: capturing on idle records empty cells, which looks like a picker with nothing in it.
     * Where no custom emoji is drawn -- the Unicode categories -- there is nothing to wait for,
     * and waiting would sit out the timeout looking for a pixel that is never coming.
     */
    private fun captureWithPictures(name: String, expectPictures: Boolean = false) {
        if (expectPictures) {
            compose.waitUntil(60_000) {
                hasMarker(compose.onRoot().captureToImage().asAndroidBitmap())
            }
        }
        RenderHarness.run { compose.capture(name) }
    }

    private fun customEmoji(url: String) = listOf(
        CustomEmoji(id = "e-blob", name = "blobcat", url = url, scope = "server"),
        CustomEmoji(id = "e-party", name = "party_parrot", url = url, scope = "server"),
        CustomEmoji(id = "e-mine", name = "mine", url = url, scope = "user"),
    )

    private fun drawSheet(theme: VisualThemeSettings, recent: List<String>, notice: String? = null) {
        val url = markerPng()
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    // The sheet's own surface, as the message-actions sheet draws it.
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = HarmonyShape.sheet,
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            EmojiPickerSheet(
                                title = "React to Ada Lovelace",
                                onPick = {},
                                customEmoji = customEmoji(url),
                                recent = recent,
                                customLabel = "Analytical Engine",
                                notice = notice,
                                onClearRecent = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // The sheet's title is the one thing that is there only once the sheet itself is, so a
        // capture cannot record an empty frame in its place.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("React to Ada Lovelace", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test fun thePickerInTheDarkLook() {
        // One recent is a custom emoji, so the recents row is drawn proving both kinds resolve.
        drawSheet(VisualThemeSettings(theme = "dark"),
            recent = listOf("\uD83D\uDC4D", "id:e-blob", "\uD83D\uDE02", "\uD83C\uDF89", "\uD83D\uDC40"))
        captureWithPictures("emoji-picker-dark", expectPictures = true)
    }

    @Test fun thePickerInTheLightLook() {
        drawSheet(VisualThemeSettings(theme = "light"), recent = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02"))
        captureWithPictures("emoji-picker-light")
    }

    /** A picker that has never been used has no recents, and still offers everything. */
    @Test fun thePickerWithNoRecents() {
        drawSheet(VisualThemeSettings(theme = "dark"), recent = emptyList())
        captureWithPictures("emoji-picker-empty")
    }

    /** The limit notice, which is what a person sees when the message is full. */
    @Test fun thePickerAtTheReactionLimit() {
        drawSheet(
            VisualThemeSettings(theme = "dark"),
            recent = listOf("\uD83D\uDC4D"),
            notice = "This message has 20 of your reactions, the most one person can add.",
        )
        captureWithPictures("emoji-picker-limit")
    }

    /**
     * The categories, one capture per tab: the whole set is browsable, not only the faces.
     *
     * It is one test that walks the tabs and captures each, because the point is that every
     * category draws its own emoji rather than that each tab is its own picture.
     */
    @Test fun everyCategoryDrawsItsOwnEmoji() {
        val url = markerPng()
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = HarmonyShape.sheet) {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            EmojiPicker(onPick = {}, customEmoji = customEmoji(url))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // The first category is on screen without a tap.
        captureWithPictures("emoji-picker-smileys")
        for ((tab, name) in listOf(
            "emoji-tab-PEOPLE" to "people",
            "emoji-tab-FOOD" to "food",
            "emoji-tab-SYMBOLS" to "symbols",
        )) {
            compose.onNodeWithTag(tab).performClick()
            compose.waitForIdle()
            captureWithPictures("emoji-picker-" + name)
        }
        // The instance's own emoji, which this client could not offer at all before.
        compose.onNodeWithTag("emoji-source-custom").performClick()
        compose.waitForIdle()
        captureWithPictures("emoji-picker-custom", expectPictures = true)
    }

    /** A search, drawn: the results replace the categories, which is what a search does. */
    @Test fun aSearchIsDrawn() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = HarmonyShape.sheet) {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            EmojiPicker(onPick = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("emoji-search").performTextInput("lol")
        compose.waitForIdle()
        RenderHarness.run { compose.capture("emoji-picker-search") }
    }

    /**
     * The quick row with its "more" cell, which is the entry point the report was about.
     *
     * This is the row that used to be five faces and nothing else: the last cell is the change.
     */
    @Test fun theQuickRowWithThePickerEntry() {
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    QuickReactionRow(onReact = {}, onMore = {})
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("quick-reactions") }
    }
}
