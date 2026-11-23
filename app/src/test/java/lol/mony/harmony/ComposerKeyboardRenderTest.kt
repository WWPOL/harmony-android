package lol.mony.harmony

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lol.mony.harmony.ui.navigation.HarmonyShell
import lol.mony.harmony.ui.navigation.Space
import lol.mony.harmony.ui.screens.chat.composerMaxLines
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a room looks like with the keyboard up, as pictures -- before the fix and after it.
 *
 * [KeyboardInsetTest] says the content reaches the keyboard's top edge and the bar stands down;
 * these captures say what that looks like, which is the evidence a person reads rather than the
 * numbers. Every case is drawn twice: once the way the app drew it before this change, and once
 * the way it draws it now, so the difference is visible in one picture instead of described.
 *
 * The "before" case is not a mutation of the source. It is the old arithmetic expressed through
 * the frame's own parameters, which is exactly what the bug was: the bar was given the whole
 * `safeDrawing` area -- keyboard included -- and the room padded for the keyboard again with
 * `imePadding()` on top of the space the frame had already given it. Reproducing it this way means
 * the picture of the bug cannot drift from the picture of the fix.
 *
 * The keyboard is synthetic, like the notch in [SpaceRailTest]: Robolectric runs no input method.
 * It is drawn as a strip at the bottom so the deadspace -- or its absence -- is visible rather
 * than being an area of background colour the reader has to interpret.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h740dp-xhdpi")
class ComposerKeyboardRenderTest {
    @get:Rule val compose = createComposeRule()

    /** A status bar and a navigation bar: what the platform reports with no cutout. */
    private val bars = WindowInsets(left = 0.dp, top = 36.dp, right = 0.dp, bottom = 24.dp)

    /** The keyboard: on a 740dp screen this is the ~300dp a phone's keyboard takes. */
    private val keyboard = WindowInsets(bottom = 300.dp)

    private val noInsets = WindowInsets(0, 0, 0, 0)

    private val spaces = listOf(Space.Home, Space("s1", "Brapcord", badge = 84))

    private val messages = listOf(
        "phat" to "if consumers are not receiving go…",
        "ada" to "The redesign is in. Have a look at the frame on a wide window.",
        "grace" to "The list stays put while I read now, which is the whole point.",
        "alan" to "Tabs or spaces, but the composer should show what I type.",
    )

    /**
     * A room drawn the way `RoomScreen` draws one.
     *
     * It is a stand-in rather than the real `RoomScreen` because that screen reads an instance and
     * a session; what is shown here is the *frame's* arithmetic, which is where the bug was. The
     * room's own body is the same in both cases -- only [framePadsForKeyboard] and [roomPadsForIme]
     * change, and those are the two lines the fix is about.
     */
    @Composable
    private fun Room(
        showKeyboard: Boolean,
        showReply: Boolean,
        showBanner: Boolean,
        draft: String,
        /**
         * The old behaviour: hand the frame the whole safe drawing area, keyboard included, so the
         * bottom bar pads itself for a keyboard that is already covering it.
         */
        framePadsForKeyboard: Boolean = false,
        /** The old behaviour: pad the room for the keyboard inside the space the frame gave it. */
        roomPadsForIme: Boolean = false,
    ) {
        // The old behaviour is reproduced through the frame's own parameters, not by mutating the
        // source. The old frame had no `ime` parameter at all: it was handed the whole safe
        // drawing area -- keyboard included -- and padded its bottom bar with it. Passing an empty
        // `ime` and a `safeArea` that already contains the keyboard is exactly that shape, so the
        // picture of the bug cannot drift from the picture of the fix.
        val safeArea = if (framePadsForKeyboard && showKeyboard) bars.union(keyboard) else bars
        val imeInset = if (showKeyboard && !framePadsForKeyboard) keyboard else noInsets
        HarmonyTheme(VisualThemeSettings(theme = "dark")) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                HarmonyShell(
                    spaces = spaces,
                    currentSpace = spaces[1],
                    onSpace = {},
                    onAddServer = {},
                    onMore = {},
                    safeArea = safeArea,
                    ime = imeInset,
                ) {
                    Column(Modifier.fillMaxSize().let { if (roomPadsForIme) it.imePadding() else it }) {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("games", style = MaterialTheme.typography.titleLarge)
                                    Text("Channel", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            navigationIcon = { IconButton({}) { Text("<-") } },
                            actions = {
                                IconButton({}) { Icon(Icons.Default.Search, "Search") }
                                IconButton({}) { Icon(Icons.Default.MoreVert, "More") }
                            },
                        )
                        if (showBanner) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Live connection dropped. Reconnecting.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        if (showReply) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Replying to phat: if consumers are not receiving go…",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({}) { Icon(Icons.Default.Close, "Cancel reply") }
                            }
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("message-list")) {
                            items(messages) { (author, body) ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Box(Modifier.size(32.dp).background(
                                        MaterialTheme.colorScheme.primary, HarmonyShape.pill))
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(author, style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary)
                                        Text(body, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        Composer(draft)
                    }
                }
                // The keyboard, drawn where the system would draw it. It is an overlay on the
                // window, which is why the app has to keep its content above it.
                if (showKeyboard) KeyboardStrip(Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Composable
    private fun Composer(draft: String) {
        Surface(
            color = LocalHarmonyColors.current.surfaceRaised,
            shape = HarmonyShape.sheet,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().testTag("composer-surface"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton({}, Modifier.size(52.dp)) {
                    Icon(Icons.Default.AddCircleOutline, "Attach a file")
                }
                OutlinedTextField(
                    draft, {},
                    placeholder = { Text("Message #games") },
                    modifier = Modifier.weight(1f).testTag("message-composer"),
                    minLines = 1, maxLines = composerMaxLines,
                    shape = HarmonyShape.large,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton({}, Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }

    /** A keyboard-shaped strip, so the picture says which part of the screen it owns. */
    @Composable
    private fun KeyboardStrip(modifier: Modifier = Modifier) {
        Column(
            modifier.fillMaxWidth().height(300.dp).background(Color(0xFFF8BBD0))
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf("and", "is", "in").forEach { word ->
                    Box(Modifier.weight(1f).height(40.dp).background(Color.White, HarmonyShape.small),
                        contentAlignment = Alignment.Center) { Text(word, fontSize = 18.sp) }
                }
            }
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    row.forEach { key ->
                        Box(Modifier.weight(1f).height(52.dp).background(Color.White, HarmonyShape.small),
                            contentAlignment = Alignment.Center) { Text(key.toString(), fontSize = 18.sp) }
                    }
                }
            }
        }
    }

    /** The bug, as the report's first screenshot: a keyboard-sized blank strip under the composer. */
    @Test fun beforeASingleLineWithTheKeyboard() {
        compose.setContent { Room(true, false, false, "", framePadsForKeyboard = true, roomPadsForIme = true) }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("composer-before-single") }
    }

    /** The fix: the composer sits on the keyboard, and nothing is left blank below it. */
    @Test fun afterASingleLineWithTheKeyboard() {
        compose.setContent { Room(true, false, false, "") }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("composer-after-single") }
    }

    /** The bug, as the report's third screenshot: the reply and connection banners plus a keyboard. */
    @Test fun beforeBothBannersAndTheKeyboard() {
        compose.setContent { Room(true, true, true, "", framePadsForKeyboard = true, roomPadsForIme = true) }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("composer-before-banners") }
    }

    /** The fix: the message list is still there, between the banners and the composer. */
    @Test fun afterBothBannersAndTheKeyboard() {
        compose.setContent { Room(true, true, true, "") }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("composer-after-banners") }
    }

    /** A multi-line draft: the field is taller, and the list gave up the height, not the field. */
    @Test fun afterAMultiLineDraft() {
        compose.setContent {
            Room(true, false, false, "the first line\nand a second\nand a third\nand a fourth")
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("composer-after-multiline") }
    }

    /** The same room with the keyboard down: the space bar is back at the bottom. */
    @Test fun afterTheKeyboardDown() {
        compose.setContent { Room(false, false, false, "") }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("composer-after-keyboard-down") }
    }
}
