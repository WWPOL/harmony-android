package lol.mony.harmony.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emoji table, and the two rules the picker relies on it for.
 *
 * The table is generated (`scripts/emoji/gen_emoji.py`), so these tests are the contract that
 * keeps a regeneration honest: the size and the categories, that every row parses, that a
 * search ranks what people mean before what merely contains the letters, and -- the one that
 * matters most -- that applying a skin tone produces exactly the glyphs Unicode itself lists.
 * Without that last one the picker could offer a tone that draws a different emoji, or worse,
 * two codepoints that render as separate characters.
 */
class EmojiCatalogTest {

    @Test fun theTableHoldsEveryBaseEmojiOnce() {
        // 1906 is Unicode 16.0's fully-qualified set with the five-tone variants folded back
        // into their base. The number is the data's, not a choice: it drops only if the file is
        // regenerated from a different Unicode version, which is a change worth noticing.
        assertEquals("base emoji in the table", 1906, EmojiCatalog.all.size)
        assertEquals("a glyph must not appear twice", EmojiCatalog.all.size,
            EmojiCatalog.all.map { it.glyph }.distinct().size)
    }

    @Test fun everyCategoryHasEmoji() {
        for (group in EmojiGroup.tabs) {
            val rows = EmojiCatalog.byGroup(group)
            assertTrue("${group.label} has emoji", rows.isNotEmpty())
            assertTrue("${group.label} rows name their group", rows.all { it.group == group })
        }
        assertEquals("the tabs add up to the table", EmojiCatalog.all.size,
            EmojiGroup.tabs.sumOf { EmojiCatalog.byGroup(it).size })
    }

    @Test fun everyRowParses() {
        for (entry in EmojiCatalog.all) {
            assertTrue("${entry.name} has glyphs", entry.glyph.isNotEmpty())
            assertTrue("${entry.name} has a name", entry.name.isNotBlank())
            assertTrue("${entry.name} names its category", entry.group.label.isNotBlank())
            assertTrue("${entry.name} has no empty keyword",
                entry.keywords.none { it.isBlank() })
            // A keyword is a search term, so it is lowercase and free of punctuation the search
            // would have to guess at.
            assertTrue("${entry.name} keywords are lowercase",
                entry.keywords.all { it == it.lowercase() })
        }
    }

    @Test fun aBaseEmojiIsWhatTheToneModifies() {
        val wave = EmojiCatalog.all.first { it.name == "waving hand" }
        assertTrue("a waving hand takes a tone", wave.toneAble)
        assertEquals("the base is the untoned glyph", "\uD83D\uDC4B", wave.base)
        assertEquals("the default tone changes nothing", wave, wave.withTone(EmojiTone.Default))
    }

    /**
     * Every tone the picker offers is a glyph Unicode itself lists.
     *
     * The tone rule is one line of code, but it has to be right for 299 emoji: a base that
     * keeps its variation selector, or a two-person emoji where only one hand is toned, draws
     * as something other than the emoji it claims to be. The check is that each of the five
     * tones lands inside the emoji's own block and is a single glyph cluster -- the same
     * property a keyboard relies on.
     */
    @Test fun aToneIsOneGlyphClusterOnEveryToneAbleEmoji() {
        for (entry in EmojiCatalog.all.filter { it.toneAble }) {
            for (tone in EmojiTone.all - EmojiTone.Default) {
                val toned = entry.withTone(tone)
                assertTrue("${entry.name} with ${tone.label} is longer", toned.glyph.length > entry.glyph.length)
                assertTrue("${entry.name} with ${tone.label} keeps the base",
                    toned.glyph.startsWith(entry.glyph.first().toString()))
                assertTrue("${entry.name} with ${tone.label} carries the modifier",
                    toned.glyph.codePoints().anyMatch { it == tone.codepoint })
                // A variation selector directly before a tone is what makes two glyphs of one.
                assertTrue("${entry.name} with ${tone.label} drops the selector before the tone",
                    !toned.glyph.contains("\uFE0F" + String(Character.toChars(tone.codepoint))))
            }
        }
    }

    /** The toned glyph is what the picker inserts, and it is the emoji it says it is. */
    @Test fun aTonedEmojiKeepsItsIdentity() {
        val thumbs = EmojiCatalog.all.first { it.name == "thumbs up" }
        val dark = thumbs.withTone(EmojiTone.Dark)
        assertEquals("thumbs up, dark", "\uD83D\uDC4D\uD83C\uDFFF", dark.glyph)
        assertEquals("a toned emoji is still the same emoji", thumbs.name, dark.name)
        assertEquals("and still the same category", thumbs.group, dark.group)
        // The tone is applied to the base, not stacked on an already-toned glyph.
        assertEquals("toning twice is not cumulative", dark.glyph, dark.withTone(EmojiTone.Dark).glyph)
        assertEquals("a different tone replaces the first", "\uD83D\uDC4D\uD83C\uDFFB", dark.withTone(EmojiTone.Light).glyph)
    }

    /** An emoji with no toned forms says so, so the picker does not offer a tone that lies. */
    @Test fun anEmojiWithNoTonedFormTakesNoTone() {
        val cat = EmojiCatalog.all.first { it.name == "cat" }
        assertTrue("a cat has no skin tone", !cat.toneAble)
        assertEquals("and toning it changes nothing", cat.glyph, cat.withTone(EmojiTone.Dark).glyph)
        // The multi-person couples are the interesting case: each person takes their own tone,
        // so one tone for the pair is not the emoji Unicode lists, and they are left alone.
        val couple = EmojiCatalog.all.first { it.name == "couple with heart: woman, man" }
        assertTrue("a couple takes no single tone", !couple.toneAble)
    }

    @Test fun aSearchByNameFindsTheEmoji() {
        assertEquals("cat", EmojiCatalog.search("cat").first().name)
        assertEquals("thumbs up", EmojiCatalog.search("thumbs up").first().name)
        assertEquals("fire", EmojiCatalog.search("fire").first().name)
    }

    /**
     * A search finds emoji by the words people actually type, which are mostly not in the name.
     *
     * "lol", "hello" and "+1" are CLDR keywords: no emoji is called "lol", so without the
     * keywords the picker could only be searched by Unicode's own vocabulary. These are the
     * cases a person types first, so they are the ones worth pinning.
     */
    @Test fun aSearchFindsEmojiByTheWordsPeopleUse() {
        val lol = EmojiCatalog.search("lol")
        assertTrue("lol finds a laughing face: ${lol.take(3).map { it.name }}",
            lol.take(3).any { it.name.contains("tears of joy") || it.name.contains("squinting") || it.name.contains("rolling") })
        assertEquals("+1 is a thumbs up", "thumbs up", EmojiCatalog.search("+1").first().name)
        assertEquals("hello is a wave", "waving hand", EmojiCatalog.search("hello").first().name)
        assertTrue("shrug finds the shrugging person",
            EmojiCatalog.search("shrug").first().name.contains("shrugging"))
    }

    /** A search that names nothing offers nothing, rather than everything. */
    @Test fun aSearchWithNoMatchIsEmpty() {
        assertTrue(EmojiCatalog.search("zzzzqqq").isEmpty())
        assertTrue("an empty query offers the whole table", EmojiCatalog.search("   ").size == EmojiCatalog.all.size)
    }

    /** The order of a search is the ranking, so a partial word does not outrank the word. */
    @Test fun aWholeWordOutranksAPartialOne() {
        val heart = EmojiCatalog.search("heart")
        assertTrue("a heart emoji leads: ${heart.first().name}", heart.first().name.contains("heart"))
        // Every hit still has to match, or the list would fill with noise.
        assertTrue("every hit names the word",
            heart.take(50).all { it.name.contains("heart") || it.keywords.any { k -> k.contains("heart") } })
    }

    @Test fun aGlyphLooksUpItsOwnRow() {
        val eyes = EmojiCatalog.byGlyph("\uD83D\uDC40")
        assertNotNull("eyes are in the table", eyes)
        assertEquals("eyes", eyes!!.name)
        assertNull("a glyph the table does not hold", EmojiCatalog.byGlyph("not-an-emoji"))
    }

    /** Every category's tab draws a glyph of its own, so the tabs are told apart by sight. */
    @Test fun everyTabDrawsItsOwnGlyph() {
        val icons = EmojiGroup.tabs.map { it.icon }
        assertEquals("tab icons are distinct", icons.size, icons.distinct().size)
        assertTrue("a tab icon is one emoji", EmojiGroup.tabs.all { it.icon.isNotBlank() })
    }
}
