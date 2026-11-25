package lol.mony.harmony

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lol.mony.harmony.RenderHarness.capture
import lol.mony.harmony.ui.components.ActionItem
import lol.mony.harmony.ui.components.ActionSheetContent
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
 * The message-actions sheet, drawn from the sheet's own composable.
 *
 * The sheet is what a long press on a message opens, and it is where every per-message action
 * lives: reply, copy, thread, pin, edit, delete. It used to be a column of full-width
 * `TextButton`s -- every row the same weight, no glyphs, and the destructive one distinguished
 * only by being red -- so the sheet could not be read, only scanned word by word.
 *
 * This captures the sheet's body rather than a `ModalBottomSheet` around it: a sheet is a
 * container the platform draws, and what this work changed is the list inside it. The
 * `ModalBottomSheet` itself is still exercised by `ThreadSheetJourneyTest` against a live
 * instance, which skips when there is none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class ActionSheetRenderTest {
    @get:Rule val compose = createComposeRule()

    /**
     * The actions an account sees on its own message in a channel.
     *
     * Reply, copy, thread and pin are always there in a channel; edit and delete appear only on
     * one's own message, which is why the list is built from `buildList` in the screen and
     * mirrored here rather than duplicated as a constant.
     */
    private fun actions() = listOf(
        ActionItem("Reply", Icons.AutoMirrored.Filled.Reply) {},
        ActionItem("Copy text", Icons.Default.ContentCopy) {},
        // Selection is an action now, not something the body's own long press starts: the sheet
        // offers the whole body and a container for a part of it (docs/ux-fixes.md, report 8).
        ActionItem("Select text", Icons.Default.TextFields) {},
        ActionItem("Start thread", Icons.Default.Forum, detail = "Replies collect away from the channel") {},
        ActionItem("Pin to this channel", Icons.Default.PushPin) {},
        ActionItem("Edit", Icons.Default.Edit) {},
        ActionItem("Delete", Icons.Default.Delete, detail = "This cannot be undone", destructive = true) {},
    )

    private fun draw(theme: VisualThemeSettings) {
        compose.setContent {
            HarmonyTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = HarmonyShape.sheet,
                    ) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp),
                        ) {
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth().padding(horizontal = HarmonySize.gutter),
                            ) {
                                listOf("👍", "❤️", "😂", "🎉", "👀").forEach { emoji ->
                                    TextButton(onClick = {}) { Text(emoji, fontSize = 24.sp) }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = HarmonySize.gutterLarge))
                            ActionSheetContent(title = "Message from Ada Lovelace", actions = actions())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Message from Ada Lovelace", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theSheet() {
        draw(VisualThemeSettings(theme = "dark"))
        RenderHarness.run { compose.capture("actions-sheet-dark") }
    }

    @Test fun theSheetInTheLightLook() {
        draw(VisualThemeSettings(theme = "light"))
        RenderHarness.run { compose.capture("actions-sheet-light") }
    }
}
