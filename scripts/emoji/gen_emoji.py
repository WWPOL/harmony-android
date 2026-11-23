#!/usr/bin/env python3
"""Generate `EmojiCatalog.kt` from Unicode's emoji-test.txt and CLDR's annotations.

Both inputs are Unicode data files under the Unicode licence, not source code from another
client: this writes a data table, one row per emoji, and the Kotlin file only parses it.

    curl -sO https://unicode.org/Public/16.0.0/ucd/emoji/emoji-test.txt
    curl -sO https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml
    python3 scripts/emoji/gen_emoji.py emoji-test.txt en.xml \\
      > app/src/main/java/lol/mony/harmony/data/model/EmojiCatalog.kt

`en.xml` is CLDR's English annotation file (`common/annotations/en.xml` of a CLDR release).
The JSON form of the same data holds the same annotations and can be read the same way, but
this reads the XML because that is what CLDR publishes for annotations.

It prints one line of counts to stderr, so a regeneration says what it wrote.
"""

import collections
import re
import sys
import xml.etree.ElementTree as ET

TONES = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF]
FE0F = 0xFE0F

# The picker's tabs, in Unicode's group order, with the glyph each tab draws.
GROUPS = [
    ("Smileys & Emotion", "SMILEYS", "Smileys", "\U0001F600"),
    ("People & Body", "PEOPLE", "People", "\U0001F44B"),
    ("Animals & Nature", "NATURE", "Nature", "\U0001F43E"),
    ("Food & Drink", "FOOD", "Food", "\U0001F354"),
    ("Travel & Places", "TRAVEL", "Travel", "\U0001F697"),
    ("Activities", "ACTIVITIES", "Activities", "\u26BD"),
    ("Objects", "OBJECTS", "Objects", "\U0001F4A1"),
    ("Symbols", "SYMBOLS", "Symbols", "\u2764\uFE0F"),
    ("Flags", "FLAGS", "Flags", "\U0001F3F3\uFE0F"),
]

# Filled in by `main` from the data: every codepoint Unicode itself puts a tone after.
MODIFIER_BASES = set()


def parse_emoji_test(path):
    """Every fully-qualified emoji, in the file's own (CLDR) order."""
    out = []
    group = None
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("# group:"):
                group = line.split(":", 1)[1].strip()
                continue
            if not line or line.startswith("#"):
                continue
            match = re.match(r"^([0-9A-F ]+?)\s*;\s*(\S+)\s*#\s*(\S+)\s+(\S+)\s+(.*)$", line)
            if not match:
                continue
            cps, status, _glyph, _version, name = match.groups()
            if status != "fully-qualified":
                continue
            out.append({"cps": [int(c, 16) for c in cps.split()], "name": name.strip(), "group": group})
    return out


def parse_annotations(path):
    """glyph -> searchable words, from CLDR's English annotations.

    CLDR keys an annotation by the glyphs as they are written, and writes the *unqualified*
    form: `☺` rather than `☺️`. The table lists fully-qualified emoji, so the keys are
    normalised here by dropping the variation selectors, and looked up the same way. Flags
    carry no annotation at all -- CLDR derives them from region names -- which is why a flag
    is searched by the name the data file already gives it ("flag: Japan").
    """
    words = {}
    for element in ET.parse(path).getroot().iter():
        if not element.tag.endswith("annotation"):
            continue
        if element.get("type") == "tts":
            continue
        key = (element.get("cp") or "").replace(chr(FE0F), "")
        words[key] = (element.text or "").strip()
    return words


def modified_utf8_length(text):
    """How many bytes the JVM spends on [text] in a class file's constant pool.

    A class file's CONSTANT_Utf8 is *modified* UTF-8: an astral codepoint is written as its
    surrogate pair, so it costs six bytes where UTF-8 spends four. The 65535-byte limit is
    measured in these bytes, not in the file's.
    """
    total = 0
    for char in text:
        point = ord(char)
        if point < 0x80:
            total += 1
        elif point < 0x800:
            total += 2
        else:
            total += 3
    return total


def tone_variant(cps, tone):
    """A base emoji with a skin tone: the tone after each tone-able codepoint.

    A modifier base followed by a variation selector drops the selector, which is how
    Unicode spells the toned form (`261D FE0F` -> `261D 1F3FB`).
    """
    out = []
    index = 0
    while index < len(cps):
        cp = cps[index]
        out.append(cp)
        index += 1
        if cp in MODIFIER_BASES:
            out.append(tone)
            if index < len(cps) and cps[index] == FE0F:
                index += 1
    return out


def main():
    global MODIFIER_BASES
    entries = parse_emoji_test(sys.argv[1])
    words_by_cp = parse_annotations(sys.argv[2])

    bases = collections.OrderedDict()
    variants = collections.defaultdict(list)
    for entry in entries:
        stripped = tuple(c for c in entry["cps"] if c not in TONES)
        if stripped == tuple(entry["cps"]):
            bases.setdefault(stripped, entry)
        else:
            variants[stripped].append(entry)

    for toned in variants.values():
        for entry in toned:
            seq = entry["cps"]
            for index in range(len(seq) - 1):
                if seq[index + 1] in TONES:
                    MODIFIER_BASES.add(seq[index])

    # A base offers skin tones only when the modifier rule reproduces every toned form
    # Unicode lists for it, so the picker can never offer a tone that draws something else.
    tone_able = set()
    for stripped, toned in variants.items():
        if stripped not in bases:
            continue
        expected = {tuple(e["cps"]) for e in toned}
        produced = {tuple(tone_variant(list(stripped), tone)) for tone in TONES}
        if produced == expected:
            tone_able.add(stripped)

    rows = collections.OrderedDict((name, []) for name, _, _, _ in GROUPS)
    for cps, entry in bases.items():
        group = entry["group"]
        if group not in rows:
            continue
        name = entry["name"]
        glyph = "".join(chr(c) for c in cps)
        found = [w.strip().lower() for w in words_by_cp.get(glyph.replace(chr(FE0F), ""), "").split("|") if w.strip()]
        found = [w for w in found if re.fullmatch(r"[a-z0-9+' ._-]{1,24}", w)]
        found = [w for w in dict.fromkeys(found) if w not in name]
        fields = [glyph, name, ",".join(found)]
        if cps in tone_able:
            fields.append("t")
        rows[group].append("\t".join(fields))

    # One string constant per 40000 bytes: the JVM caps a constant at 65535 bytes of
    # modified UTF-8, where an astral emoji costs six bytes per codepoint.
    blocks = []
    for group, enum, _label, _icon in GROUPS:
        chunk, size = [], 0
        for row in rows[group]:
            length = modified_utf8_length(row) + 1
            if chunk and size + length > 40000:
                blocks.append((enum, "\n".join(chunk)))
                chunk, size = [], 0
            chunk.append(row)
            size += length
        if chunk:
            blocks.append((enum, "\n".join(chunk)))

    sys.stderr.write("bases=%d tone-able=%d modifier-bases=%d blocks=%d\n" % (
        len(bases), len(tone_able), len(MODIFIER_BASES), len(blocks)))
    sys.stderr.write("rows per group: %s\n" % {g: len(v) for g, v in rows.items()})
    write(blocks)


HEADER = '''package lol.mony.harmony.data.model

/**
 * Every emoji this client can offer, as a table generated from Unicode's own data.
 *
 * The sources are `emoji-test.txt` (Unicode 16.0) for the emoji, their names and their order,
 * and CLDR's English annotations for the words people search by -- which is why "lol" finds
 * \U0001F602 and "japan" finds the flag. Both are Unicode data files under the Unicode licence,
 * not another client's source: `scripts/emoji/gen_emoji.py` regenerates this file, and
 * `EmojiCatalogTest` checks what the table promises.
 *
 * Only fully-qualified *base* emoji are listed. A skin tone is applied when one is picked
 * ([EmojiEntry.withTone]), which is one table instead of six for the same set of glyphs: a base
 * is marked tone-able only when Unicode's own toned forms are exactly what that rule produces,
 * so the picker can never offer a tone that draws something else.
 *
 * Each block is one category's rows -- `glyph<TAB>name<TAB>keywords<TAB>tone-able` -- and they
 * are separate constants because the JVM caps one string constant at 65535 bytes.
 */

'''

TAIL = '''
/** One emoji as the picker lists it. */
internal data class EmojiEntry(
    /** The glyphs to insert, already carrying any skin tone. */
    val glyph: String,
    /** Unicode's own name, in words a person can read and search. */
    val name: String,
    /** The words CLDR says people search this emoji by, minus those already in [name]. */
    val keywords: List<String>,
    val group: EmojiGroup,
    /** Whether [withTone] produces a real emoji for this one. */
    val toneAble: Boolean,
    /** The glyphs without a tone, which is what [withTone] modifies. */
    val base: String,
) {
    /** This emoji with a skin tone applied, or this emoji when it takes no tone. */
    fun withTone(tone: EmojiTone): EmojiEntry {
        if (!toneAble || tone == EmojiTone.Default) return this
        val points = base.codePoints().toArray()
        val toned = StringBuilder()
        var index = 0
        while (index < points.size) {
            val point = points[index]
            toned.appendCodePoint(point)
            index++
            if (point in EmojiTone.MODIFIER_BASES) {
                toned.appendCodePoint(tone.codepoint)
                // A variation selector before a tone is dropped, as Unicode spells the pair.
                if (index < points.size && points[index] == 0xFE0F) index++
            }
        }
        return copy(glyph = toned.toString())
    }

    /** The words this emoji is found by: its name and its keywords, for a screen reader. */
    val searchable: String get() = if (keywords.isEmpty()) name else name + " " + keywords.joinToString(" ")
}

/**
 * The categories the picker's tabs name, in the order Unicode lists them.
 *
 * The labels are the ones people know the groups by, not the data file's long headings, and
 * [icon] is the glyph the tab draws.
 */
internal enum class EmojiGroup(val label: String, val icon: String) {
'''

TONES_HEADER = '''
    ;

    companion object {
        /** Every category, in tab order. */
        val tabs: List<EmojiGroup> get() = entries
    }
}

/** A skin tone, as Unicode's modifier codepoints name them. */
internal enum class EmojiTone(val label: String, val swatch: String, val codepoint: Int) {
'''

TONES_TAIL = '''
    ;

    companion object {
        /**
         * The codepoints Unicode marks as emoji modifier bases: a tone follows one of these.
         *
         * It is the data file's own evidence -- every codepoint Unicode puts a tone after --
         * rather than a hand-kept list that could drift from the table above.
         */
        val MODIFIER_BASES: Set<Int> = setOf(
'''

CATALOG = '''        )

        /** Every tone, default first, in the order the picker's row shows them. */
        val all: List<EmojiTone> get() = entries
    }
}

/**
 * The table itself, parsed once.
 *
 * [all] is in Unicode's own order, which is the order the picker draws: the faces people reach
 * for first sit at the top of their category rather than scattered by codepoint.
 */
internal object EmojiCatalog {
    val all: List<EmojiEntry> by lazy { parse() }

    /** The emoji in [group], in Unicode's order. */
    fun byGroup(group: EmojiGroup): List<EmojiEntry> = all.filter { it.group == group }

    /**
     * The emoji a search names, best first.
     *
     * Every word of the query has to match, in the emoji's name or in one of CLDR's keywords,
     * and the best kind of match decides the rank:
     *
     *  1. the name is the words ("cat" finds :cat:)
     *  2. a word of the name is the word ("heart" finds :red heart:)
     *  3. a keyword is the word ("lol" finds :face with tears of joy:)
     *  4. the name starts with the word
     *  5. a keyword starts with the word
     *  6. the name contains the word ("japan" finds :flag: Japan:)
     *  7. a keyword contains the word
     *
     * Ties keep the catalog's own order, which is Unicode's -- the order people reach for
     * emoji in -- so a tie never reshuffles between keystrokes.
     */
    fun search(query: String): List<EmojiEntry> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return all
        return all.withIndex().mapNotNull { (index, entry) ->
            val name = entry.name
            val keywords = entry.keywords
            var rank = 0
            for (term in terms) {
                val keyword = keywords.firstOrNull { it.contains(term) }
                if (!name.contains(term) && keyword == null) return@mapNotNull null
                rank += when {
                    name == term -> 0
                    name.split(' ').any { it == term } -> 1
                    keyword == term -> 2
                    name.startsWith(term) -> 3
                    keyword != null && keyword.startsWith(term) -> 4
                    name.contains(term) -> 5
                    else -> 6
                }
            }
            rank to index to entry
        }.sortedWith(compareBy<Pair<Pair<Int, Int>, EmojiEntry>> { it.first.first }.thenBy { it.first.second })
            .map { it.second }
    }

    /** The emoji whose glyphs are exactly [glyph], for drawing a recent pick back. */
    fun byGlyph(glyph: String): EmojiEntry? = index[glyph]

    private val index: Map<String, EmojiEntry> by lazy { all.associateBy { it.glyph } }

    private fun parse(): List<EmojiEntry> = buildList {
        for ((group, block) in BLOCKS) {
            for (line in block.split('\\n')) {
                if (line.isEmpty()) continue
                val fields = line.split('\\t')
                val glyph = fields[0]
                add(EmojiEntry(
                    glyph = glyph,
                    name = fields[1],
                    keywords = if (fields[2].isEmpty()) emptyList() else fields[2].split(','),
                    group = group,
                    toneAble = fields.size > 3 && fields[3] == "t",
                    base = glyph,
                ))
            }
        }
    }

    private val BLOCKS: List<Pair<EmojiGroup, String>> = listOf(
'''


def write(blocks):
    out = [HEADER, TAIL]
    for _name, enum, label, icon in GROUPS:
        out.append('    %s("%s", "%s"),\n' % (enum, label, icon))
    out.append(TONES_HEADER)
    for enum, label, cp in [
        ("Default", "Default", None),
        ("Light", "Light", 0x1F3FB),
        ("MediumLight", "Medium light", 0x1F3FC),
        ("Medium", "Medium", 0x1F3FD),
        ("MediumDark", "Medium dark", 0x1F3FE),
        ("Dark", "Dark", 0x1F3FF),
    ]:
        # The swatch is a hand in the tone, so the row shows what each tone looks like.
        swatch = "\U0001F44B" + ("" if cp is None else chr(cp))
        out.append('    %s("%s", "%s", %s),\n' % (enum, label, swatch, "0" if cp is None else "0x%04X" % cp))
    out.append(TONES_TAIL)
    points = sorted(MODIFIER_BASES)
    for index in range(0, len(points), 8):
        out.append("            " + ", ".join("0x%04X" % p for p in points[index:index + 8]) + ",\n")
    out.append(CATALOG)
    for enum, text in blocks:
        out.append('        EmojiGroup.%s to """\n%s""",\n' % (enum, text + "\n"))
    out.append("    )\n}\n")
    sys.stdout.write("".join(out))


if __name__ == "__main__":
    main()
