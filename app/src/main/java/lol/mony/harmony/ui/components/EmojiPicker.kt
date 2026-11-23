package lol.mony.harmony.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import lol.mony.harmony.data.model.CustomEmoji
import lol.mony.harmony.data.model.EmojiCatalog
import lol.mony.harmony.data.model.EmojiEntry
import lol.mony.harmony.data.model.EmojiGroup
import lol.mony.harmony.data.model.EmojiTone
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize

/**
 * What one pick from the picker is: the glyph to store, and the custom emoji's row when the
 * pick was one of the instance's own.
 *
 * It is one type rather than two callbacks because the two are exclusive -- a reaction is
 * either a unicode codepoint in `custom_emoji_content` or an `emoji_id` -- and a caller that
 * had to decide between them would be repeating the picker's own knowledge of which is which.
 */
data class EmojiPick(
    /** The unicode glyph, or the custom emoji's shortcode when [custom] is set. */
    val glyph: String,
    val custom: CustomEmoji? = null,
) {
    /** What the reaction row stores: the custom emoji's `emoji_id`, else null. */
    val emojiId: String? get() = custom?.id

    /**
     * What the recent list keeps: the glyph, or `id:<uuid>` for a custom emoji.
     *
     * The id is what survives a rename: a server emoji renamed `:cat:` to `:kitty:` is still
     * the same row, and a recent list keyed by the shortcode would lose it.
     */
    val recentKey: String get() = if (custom != null) "id:" + custom.id else glyph

    companion object {
        /** The prefix a custom emoji's recent key carries, so a glyph is never mistaken for one. */
        const val CUSTOM_PREFIX = "id:"
    }
}

/**
 * The emoji picker: every emoji, by category or by search, plus the instance's own.
 *
 * The screen this replaces offered five hardcoded faces in a row, so a reaction outside those
 * five could not be made at all. This is the whole of Unicode's emoji set -- base glyphs, with
 * a skin tone applied to the ones that take one -- the server's and the account's custom emoji,
 * and the picks this account made last, in one sheet.
 *
 * Three things are deliberate:
 *
 *  - **Recents come first.** The face a person uses is the one they used last, and reaching it
 *    should not need a search. The row is per account, on this device.
 *  - **Search is by what people type.** Unicode's names ("face with tears of joy") and CLDR's
 *    keywords ("lol", "rofl", "+1", "hello") both match, so the search box is useful without
 *    knowing an emoji's official name.
 *  - **A tone is a choice, not more emoji.** The grid shows each emoji once; the tone row
 *    changes what a tone-able one inserts, which is how a keyboard does it and keeps the list
 *    six times shorter than listing every toned variant.
 *
 * [onPick] is called once per pick, and closing the sheet is the caller's to do.
 */
@Composable
fun EmojiPicker(
    onPick: (EmojiPick) -> Unit,
    modifier: Modifier = Modifier,
    /** The instance's custom emoji, already fetched; empty while loading or when there are none. */
    customEmoji: List<CustomEmoji> = emptyList(),
    /** The picks this account made last, most recent first, as [EmojiPick.recentKey]s. */
    recent: List<String> = emptyList(),
    /** A section heading for the custom emoji, such as the server's name. */
    customLabel: String = "Custom emoji",
    /** Why a pick would fail, such as the 20-reaction limit; drawn as a notice, not an error. */
    notice: String? = null,
    /** Whether [notice] stops new picks, which greys the grid rather than hiding it. */
    blocked: Boolean = false,
    /** Clears the recents; null when the caller keeps no recents to clear. */
    onClearRecent: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tone by rememberSaveable { mutableStateOf(EmojiTone.Default) }
    var group by rememberSaveable { mutableStateOf(EmojiGroup.SMILEYS) }
    // The custom emoji are a tab of their own rather than a section above every category: they
    // are one list, not a heading repeated nine times, and they stay reachable while the person
    // browses. The tab is only offered when there are any to show.
    var onCustom by rememberSaveable { mutableStateOf(false) }
    // A search spans everything, so its results replace the tabs' grid rather than filtering
    // inside one tab: a person looking for "cat" does not know which tab it is in.
    val searching = query.isNotBlank()
    // The two sources are exclusive unless a search is running: a tab shows one list, which is
    // what makes "Custom" mean the instance's emoji rather than those plus a category's.
    val unicode = when {
        searching -> EmojiCatalog.search(query)
        onCustom -> emptyList()
        else -> EmojiCatalog.byGroup(group)
    }
    val custom = when {
        searching -> {
            val wanted = query.trim().lowercase()
            customEmoji.filter { it.searchable.contains(wanted) }
        }
        onCustom -> customEmoji
        else -> emptyList()
    }
    val showCustomTab = customEmoji.isNotEmpty() && !searching

    Column(modifier.fillMaxWidth()) {
        EmojiSearchField(query, onChange = { query = it })
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HarmonySize.gutterLarge, vertical = 4.dp)
                    .testTag("emoji-limit-notice"),
            )
        }
        if (!searching) {
            if (recent.isNotEmpty()) RecentRow(recent, customEmoji, tone, onPick, onClearRecent)
            if (showCustomTab) {
                CustomTab(onCustom) { onCustom = it }
            }
            // A custom emoji has no skin tone, so the tone row belongs to Unicode's list only.
            if (!onCustom) {
                ToneRow(tone) { tone = it }
                GroupTabs(group) { group = it }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = HarmonySize.gutter))
        if (searching && unicode.isEmpty() && custom.isEmpty()) {
            Text(
                "No emoji matches \"" + query.trim() + "\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp).testTag("emoji-no-results"),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 48.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).testTag("emoji-grid"),
            contentPadding = PaddingValues(horizontal = HarmonySize.gutter, vertical = 8.dp),
        ) {
            // A search shows the two kinds together, each under its own heading: a person who
            // typed "cat" wants the server's :cat: beside the cat faces, not behind a tab.
            if (custom.isNotEmpty()) {
                if (searching) {
                    item(key = "custom-heading", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(customLabel, supporting = custom.size.toString() + " custom emoji")
                    }
                }
                items(custom, key = { "custom-" + it.id }) { emoji ->
                    CustomEmojiCell(emoji, blocked) { onPick(EmojiPick(emoji.shortcode, emoji)) }
                }
            }
            if (unicode.isNotEmpty()) {
                item(key = "unicode-heading", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        if (searching) "Unicode emoji" else group.label,
                        supporting = if (searching) null else "Unicode emoji",
                    )
                }
                items(unicode, key = { it.group.name + "-" + it.glyph }) { entry ->
                    EmojiCell(entry, tone, blocked) { onPick(EmojiPick(entry.withTone(tone).glyph)) }
                }
            }
        }
    }
}

/** The search box, which is the picker's fastest path to one emoji. */
@Composable
private fun EmojiSearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search emoji") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.testTag("emoji-search-clear")) {
                    Icon(Icons.Default.Close, "Clear search")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HarmonySize.gutterLarge, vertical = 8.dp)
            .testTag("emoji-search"),
    )
}

/** The faces this account picked last, drawn as one row above the categories. */
@Composable
private fun RecentRow(
    recent: List<String>,
    customEmoji: List<CustomEmoji>,
    tone: EmojiTone,
    onPick: (EmojiPick) -> Unit,
    onClear: (() -> Unit)?,
) {
    val byId = remember(customEmoji) { customEmoji.associateBy { it.id } }
    // A recent custom emoji the library no longer holds -- removed, or a server that left -- is
    // dropped rather than drawn as a blank cell. A unicode pick is found in the catalog so it
    // draws with the tone the row currently has.
    val shown = remember(recent, byId, tone) {
        recent.mapNotNull { key ->
            if (key.startsWith(EmojiPick.CUSTOM_PREFIX)) {
                byId[key.removePrefix(EmojiPick.CUSTOM_PREFIX)]
                    ?.let { RecentPick(key, it.shortcode, it) }
            } else {
                EmojiCatalog.byGlyph(key)?.let { RecentPick(key, it.withTone(tone).glyph, null) }
                    ?: RecentPick(key, key, null)
            }
        }
    }
    if (shown.isEmpty()) return
    Column {
        SectionHeader(
            "Recent",
            supporting = "The emoji you used last",
            trailing = onClear?.let {
                {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onClear() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("emoji-recent-clear"),
                    )
                }
            },
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("emoji-recent"),
            contentPadding = PaddingValues(horizontal = HarmonySize.gutter),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(shown, key = { it.recentKey }) { pick ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onPick(EmojiPick(pick.glyph, pick.custom)) }
                        .testTag("emoji-recent-" + pick.recentKey),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pick.custom != null) {
                        CustomEmojiGlyph(pick.custom, size = 26)
                    } else {
                        Text(pick.glyph, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

/** One recent pick, resolved to something drawable. */
private data class RecentPick(
    /** The stable key this pick is remembered by, which is also its test tag. */
    val recentKey: String,
    /** What a pick inserts: the glyph, or the custom emoji's shortcode. */
    val glyph: String,
    val custom: CustomEmoji?,
)

/** The skin-tone row: one swatch per tone, the chosen one ringed. */
@Composable
private fun ToneRow(tone: EmojiTone, onTone: (EmojiTone) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("emoji-tones"),
        contentPadding = PaddingValues(horizontal = HarmonySize.gutter),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(EmojiTone.all, key = { it.name }) { candidate ->
            val chosen = candidate == tone
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(if (chosen) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                    .clickable { onTone(candidate) }
                    .semantics {
                        selected = chosen
                        contentDescription = candidate.label + " skin tone"
                    }
                    .testTag("emoji-tone-" + candidate.name),
                contentAlignment = Alignment.Center,
            ) {
                Text(candidate.swatch, fontSize = 20.sp)
            }
        }
    }
}

/**
 * The switch between the instance's own emoji and Unicode's.
 *
 * It is a two-cell segmented row rather than a tenth category tab, because the two are
 * different sets: a custom emoji has no skin tone and no Unicode name, and mixing them into one
 * strip would make the tab row mean two things at once.
 */
@Composable
private fun CustomTab(onCustom: Boolean, onChoose: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HarmonySize.gutter).testTag("emoji-custom-tab"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((chosen, label) in listOf(false to "Unicode", true to "Custom")) {
            Surface(
                onClick = { onChoose(chosen) },
                shape = CircleShape,
                color = if (chosen == onCustom) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                modifier = Modifier.semantics {
                    selected = chosen == onCustom
                    contentDescription = label + " emoji"
                }.testTag("emoji-source-" + label.lowercase()),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The category tabs, each a glyph of its own, with the chosen one filled. */
@Composable
private fun GroupTabs(group: EmojiGroup, onGroup: (EmojiGroup) -> Unit) {
    val strip = rememberLazyListState()
    // The strip is wider than a phone, so the chosen tab is scrolled into view: a tab a person
    // cannot see is a category they cannot tell they are in.
    LaunchedEffect(group) {
        val index = EmojiGroup.tabs.indexOf(group)
        if (index >= 0) strip.animateScrollToItem(index)
    }
    LazyRow(
        state = strip,
        modifier = Modifier.fillMaxWidth().testTag("emoji-tabs"),
        contentPadding = PaddingValues(horizontal = HarmonySize.gutter),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(EmojiGroup.tabs, key = { it.name }) { candidate ->
            val chosen = candidate == group
            Surface(
                onClick = { onGroup(candidate) },
                shape = CircleShape,
                color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                modifier = Modifier
                    .semantics {
                        selected = chosen
                        contentDescription = candidate.label + " emoji"
                    }
                    .testTag("emoji-tab-" + candidate.name),
            ) {
                Text(
                    candidate.icon,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One unicode emoji in the grid, drawn in the tone the picker is set to. */
@Composable
private fun EmojiCell(entry: EmojiEntry, tone: EmojiTone, blocked: Boolean, onPick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(HarmonyShape.small)
            .clickable(enabled = !blocked, onClick = onPick)
            .semantics { contentDescription = entry.name + if (entry.toneAble) ", has skin tones" else "" }
            .testTag("emoji-cell-" + entry.name),
        contentAlignment = Alignment.Center,
    ) {
        Text(entry.withTone(tone).glyph, fontSize = 26.sp)
    }
}

/** One custom emoji's picture, or its shortcode when the file is gone. */
@Composable
private fun CustomEmojiGlyph(emoji: CustomEmoji, size: Int) {
    if (emoji.hasImage) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(emoji.url).crossfade(true).build(),
            contentDescription = null,
            modifier = Modifier.size(size.dp),
        )
    } else {
        Text(emoji.shortcode, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** One custom emoji in the grid. */
@Composable
private fun CustomEmojiCell(emoji: CustomEmoji, blocked: Boolean, onPick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(HarmonyShape.small)
            .clickable(enabled = !blocked, onClick = onPick)
            .semantics { contentDescription = "custom emoji " + (emoji.name ?: "emoji") }
            .testTag("emoji-custom-" + (emoji.name ?: emoji.id)),
        contentAlignment = Alignment.Center,
    ) {
        CustomEmojiGlyph(emoji, size = 32)
    }
}

/**
 * The row of faces above the message-actions list.
 *
 * It stays a row rather than becoming the picker: a reaction is the one action a person takes
 * without reading, so the five they reach for most are one tap from the long press. The last
 * cell opens the picker, which is the answer to "I want a different one".
 */
@Composable
fun QuickReactionRow(
    onReact: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    reactions: List<String> = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83C\uDF89", "\uD83D\uDC40"),
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = HarmonySize.gutter),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (emoji in reactions) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onReact(emoji) }
                    .semantics { contentDescription = "React with " + emoji }
                    .testTag("quick-reaction-" + emoji),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 24.sp)
            }
        }
        Surface(
            onClick = onMore,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "More emoji" }
                .testTag("quick-reaction-more"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "+",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The picker as a sheet body: a title, the picker, and what it is for. */
@Composable
fun EmojiPickerSheet(
    title: String,
    onPick: (EmojiPick) -> Unit,
    modifier: Modifier = Modifier,
    customEmoji: List<CustomEmoji> = emptyList(),
    recent: List<String> = emptyList(),
    customLabel: String = "Custom emoji",
    notice: String? = null,
    blocked: Boolean = false,
    onClearRecent: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HarmonySize.gutterLarge, end = HarmonySize.gutterLarge, top = 4.dp)
                .testTag("emoji-picker-title"),
        )
        Spacer(Modifier.height(4.dp))
        EmojiPicker(
            onPick = onPick,
            customEmoji = customEmoji,
            recent = recent,
            customLabel = customLabel,
            notice = notice,
            blocked = blocked,
            onClearRecent = onClearRecent,
        )
    }
}
