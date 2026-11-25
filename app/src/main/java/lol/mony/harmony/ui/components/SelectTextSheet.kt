package lol.mony.harmony.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.model.ContentPart
import lol.mony.harmony.data.model.Emoji
import lol.mony.harmony.data.model.Message
import lol.mony.harmony.ui.theme.HarmonySize

/**
 * A message's body, in the one place it can be selected: a sheet the actions open.
 *
 * The body itself is not a selection surface ([MessageContent] says why): a message row's long
 * press opens the actions, and a `SelectionContainer` claims that same press, so one gesture used
 * to both select a word and open the sheet. Selection is therefore a verb of the message rather
 * than a property of its text -- the actions sheet's **Copy text** takes the whole body and
 * **Select text** opens this, where a long press is meant to select and nothing else competes
 * with it.
 *
 * It is a composable of its own rather than a block inside `RoomScreen` so it can be drawn and
 * pressed without an instance: [lol.mony.harmony.MessageSelectionTest] proves here that a long
 * press still paints a selection, which is the other half of proving it no longer paints one on
 * the row.
 */
@Composable
fun SelectTextSheetContent(
    message: Message,
    fontSize: Double,
    modifier: Modifier = Modifier,
    baseUrl: String? = null,
    signedUrl: ((String) -> String?)? = null,
    onOpenAttachment: ((ContentPart) -> Unit)? = null,
    emojis: Map<String, Emoji> = emptyMap(),
    onOpenLink: ((String) -> Unit)? = null,
) {
    Column(modifier.padding(horizontal = HarmonySize.gutterLarge)) {
        Text("Select text", style = MaterialTheme.typography.titleMedium)
        Text(
            "Hold a word, then drag the handles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        SelectionContainer {
            MessageContent(
                message = message,
                fontSize = fontSize,
                baseUrl = baseUrl,
                signedUrl = signedUrl,
                onOpenAttachment = onOpenAttachment,
                emojis = emojis,
                onOpenLink = onOpenLink,
                modifier = Modifier.testTag("select-text-body"),
            )
        }
        Spacer(Modifier.height(HarmonySize.gutter))
    }
}
