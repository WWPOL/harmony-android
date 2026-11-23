package lol.mony.harmony.data.repo

import java.util.zip.CRC32

/**
 * Removal of location and identifying metadata from photos and videos, on the device, before
 * an upload. Lossless: no pixel or sample data is decoded or re-encoded, so the bytes that are
 * stored are the bytes the camera wrote minus the parts that describe where and how.
 *
 * This follows the reference client's `src/utils/stripMetadata.ts` for the containers a phone
 * actually hands to a chat composer:
 *
 * - **JPEG** — APP0 other than JFIF, APP1 (Exif, XMP), APP2 other than `ICC_PROFILE`, APP13
 *   (IPTC), APP14 other than Adobe, the other APPn, COM, and every byte after EOI are dropped.
 *   A JFIF APP0 that carries no thumbnail is kept whole; a thumbnail is a second copy of the
 *   picture and a maker's own APP0 is data about the camera. An Exif orientation other than 1 is
 *   kept as a 36-byte APP1 holding IFD0's Orientation alone, so a portrait photo stays portrait.
 * - **PNG** — every chunk outside [PNG_KEEP] (`eXIf`, `tEXt`, `iTXt`, `zTXt`, `tIME` and private
 *   chunks) and the bytes after IEND. APNG's animation chunks are kept. An eXIf orientation
 *   other than 1 is kept as an `eXIf` chunk holding IFD0's Orientation alone.
 * - **WebP** — chunks outside [WEBP_KEEP] (`EXIF`, `XMP`, unknown), with the VP8X `E` and `X`
 *   flags cleared and the RIFF size rewritten.
 * - **GIF** — comment extensions, application extensions other than the three that carry
 *   animation and colour information, and the bytes after the trailer.
 * - **ISOBMFF video** (MP4, MOV, 3GP) — `udta`/`meta` boxes anywhere in the tree, and the
 *   XMP/USMT/PROF `uuid` boxes, become `free` boxes of the same size with a zeroed payload. No
 *   byte moves, so the sample tables' absolute offsets stay valid.
 *
 * **Not handled, uploaded unchanged and reported as unchecked:** HEIF still images
 * (HEIC/AVIF), Matroska/WebM tags, TIFF/DNG, JPEG XL, and audio files. Their metadata lives in
 * structures this walker cannot rewrite without moving bytes that address the image itself, and
 * guessing would corrupt the file. A person is told the details were *not checked* rather than
 * being told they were removed -- the reference's `unknown` result, which its UI also reports.
 *
 * The result is a [Plan]: the bytes to upload, what was found, and whether the file was
 * understood at all. A file this module leaves unchanged returns the *same* array, so a caller
 * can tell "nothing to do" from "copied".
 */
object MediaMetadata {
    /** Rasters this module reads in memory; a larger one is uploaded as it is. */
    const val MAX_BYTES = 256L * 1024 * 1024

    /**
     * What a file carries, as far as it was read.
     *
     * [details] is true when anything a person might not want to publish was present, and also
     * when what a recognised file carries could not be read through. [unknown] says the file was
     * not walked to its end, so [location] is only what was seen before that point.
     */
    data class Details(
        val container: String?,
        val location: Boolean,
        val details: Boolean,
        val unknown: Boolean = false,
    ) {
        /** True when there is nothing to say: no recognised container, nothing found. */
        val quiet: Boolean get() = container == null && !details && !location
    }

    data class Plan(val bytes: ByteArray, val details: Details, val changed: Boolean) {
        /** True when the output is the input: nothing was removed. */
        val unchanged: Boolean get() = !changed
    }

    /**
     * The bytes to upload for [input], with the metadata removed unless [keep].
     *
     * With [keep] the file is still read, so the caller can say what the person chose to send
     * rather than claiming nothing is there.
     */
    fun strip(input: ByteArray, keep: Boolean = false): Plan {
        val read = read(input)
        if (keep) return Plan(input, read.details, changed = false)
        val output = read.bytes
        if (output == null || output.size == input.size && output.contentEquals(input)) {
            return Plan(input, read.details, changed = false)
        }
        return Plan(output, read.details, changed = true)
    }

    /** What [input] carries, without changing it. */
    fun details(input: ByteArray): Details = read(input).details

    private class Read(val bytes: ByteArray?, val details: Details)

    private fun read(input: ByteArray): Read {
        if (input.isEmpty()) return Read(null, Details(null, false, false))
        if (input.size > MAX_BYTES) {
            // A file this large is not read into memory again: say so rather than claim it is clean.
            return Read(null, Details(containerOf(input), false, true, unknown = true))
        }
        return runCatching { walk(input) }
            .getOrElse { Read(null, Details(containerOf(input), false, true, unknown = true)) }
    }

    private fun walk(input: ByteArray): Read = when (containerOf(input)) {
        "jpeg" -> jpeg(input)
        "png" -> png(input)
        "webp" -> webp(input)
        "gif" -> gif(input)
        "isobmff" -> isobmff(input)
        // A container this module leaves alone: nothing is claimed about what it carries.
        else -> Read(null, Details(containerOf(input), false, false))
    }

    /** The container [input] is, or null when it is one this module does not recognise. */
    fun containerOf(input: ByteArray): String? {
        fun at(index: Int) = if (index < input.size) input[index].toInt() and 0xFF else -1
        if (at(0) == 0xFF && at(1) == 0xD8) return "jpeg"
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return "gif"
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50) return "webp"
        if (input.size >= 12 && ascii(input, 4, 4) == "ftyp") {
            val brand = ascii(input, 8, 4).trim()
            return if (brand in HEIF_BRANDS) "heif" else "isobmff"
        }
        return null
    }

    // ---- JPEG -----------------------------------------------------------------------------

    private fun jpeg(b: ByteArray): Read {
        val out = ByteSink()
        out.emit(b, 0, 2) // SOI
        var p = 2
        var location = false
        var removed = false
        while (p < b.size) {
            if (u8(b, p) != 0xFF) {
                // Bytes between segments that start no marker are not ours to drop.
                out.emit(b, p, b.size)
                return Read(out.finish(), Details("jpeg", location, true, unknown = true))
            }
            var marker = u8(b, p + 1)
            var at = p
            // Fill bytes: 0xFF runs before a marker are padding.
            while (marker == 0xFF && at + 2 < b.size) { at += 1; marker = u8(b, at + 1) }
            when {
                marker == 0xD9 -> { // EOI: everything after it is a trailer
                    out.emit(b, at, at + 2)
                    if (at + 2 < b.size) removed = true
                    return Read(out.finish(), Details("jpeg", location, removed))
                }
                marker == 0xDA -> { // SOS: entropy-coded data follows; copy to the end
                    out.emit(b, at, b.size)
                    return Read(out.finish(), Details("jpeg", location, removed))
                }
                marker == 0x01 || marker in 0xD0..0xD7 -> { // standalone markers
                    out.emit(b, at, at + 2); p = at + 2
                }
                else -> {
                    val length = u16(b, at + 2)
                    val end = at + 2 + length
                    if (length < 2 || end > b.size) {
                        out.emit(b, at, b.size)
                        return Read(out.finish(), Details("jpeg", location, true, unknown = true))
                    }
                    val payload = at + 4
                    when (marker) {
                        0xE0 -> { // APP0
                            // A JFIF header with no thumbnail describes the encoding and nothing
                            // else, so it is kept whole; a JFXX thumbnail is a second copy of the
                            // picture, and anything else in an APP0 is a maker's own data.
                            val identifier = ascii(b, payload, minOf(5, end - payload))
                            val thumbnails = identifier == "JFIF\u0000" && length >= 16 &&
                                u8(b, payload + 12) == 0 && u8(b, payload + 13) == 0
                            if (thumbnails) out.emit(b, at, end) else removed = true
                        }
                        0xE1 -> { // APP1: Exif or XMP
                            removed = true
                            if (ascii(b, payload, minOf(6, end - payload)) == "Exif\u0000\u0000") {
                                val exif = exifInfo(b, payload + 6, end)
                                location = location || exif.gps
                                orientationApp1(exif.orientation)?.let(out::emit)
                            }
                        }
                        0xE2 -> { // APP2: the ICC colour profile is not identifying
                            if (ascii(b, payload, minOf(12, end - payload)) == "ICC_PROFILE\u0000") {
                                out.emit(b, at, end)
                            } else removed = true
                        }
                        0xEE -> { // APP14 Adobe: the colour transform
                            if (ascii(b, payload, minOf(5, end - payload)) == "Adobe") out.emit(b, at, end)
                            else removed = true
                        }
                        0xFE -> removed = true // COM
                        in 0xE3..0xEF -> removed = true // the other APPn
                        else -> out.emit(b, at, end)
                    }
                    p = end
                }
            }
        }
        return Read(out.finish(), Details("jpeg", location, removed, unknown = true))
    }

    /** A 36-byte APP1 holding IFD0's Orientation alone, or null when the orientation is normal. */
    private fun orientationApp1(orientation: Int): ByteArray? {
        if (orientation !in 2..8) return null
        val bytes = ByteArray(36)
        fun put(index: Int, value: Int) { bytes[index] = value.toByte() }
        put(0, 0xFF); put(1, 0xE1); put(2, 0x00); put(3, 0x22) // APP1, length 34
        "Exif\u0000\u0000".forEachIndexed { i, c -> put(4 + i, c.code) }
        // A TIFF header, little endian, with IFD0 at offset 8.
        put(10, 0x49); put(11, 0x49); put(12, 0x2A); put(13, 0x00)
        put(14, 0x08); put(15, 0x00); put(16, 0x00); put(17, 0x00)
        put(18, 0x01); put(19, 0x00) // one entry
        put(20, 0x12); put(21, 0x01) // 0x0112 Orientation
        put(22, 0x03); put(23, 0x00) // SHORT
        put(24, 0x01); put(25, 0x00); put(26, 0x00); put(27, 0x00) // count 1
        put(28, orientation); put(29, 0x00); put(30, 0x00); put(31, 0x00)
        put(32, 0x00); put(33, 0x00); put(34, 0x00); put(35, 0x00) // no next IFD
        return bytes
    }

    // ---- PNG ------------------------------------------------------------------------------

    private val PNG_KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP",
        "mDCV", "cLLI", "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT",
    )

    private fun png(b: ByteArray): Read {
        val out = ByteSink()
        out.emit(b, 0, 8) // signature
        var p = 8
        var location = false
        var removed = false
        var orientation = 0
        while (p + 12 <= b.size) {
            val length = u32(b, p)
            val type = ascii(b, p + 4, 4)
            val end = p + 12 + length
            if (length < 0 || end > b.size) {
                out.emit(b, p, b.size)
                return Read(out.finish(), Details("png", location, true, unknown = true))
            }
            val data = p + 8
            if (type in PNG_KEEP) {
                out.emit(b, p, end)
            } else {
                removed = true
                if (type == "eXIf") {
                    val info = exifInfo(b, data, data + length)
                    location = location || info.gps
                    if (orientation == 0) orientation = info.orientation
                } else if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                    location = location || textMentionsLocation(b, data, data + length)
                }
            }
            p = end
            if (type == "IEND") {
                if (p < b.size) removed = true
                return Read(out.finish(), Details("png", location, removed))
            }
        }
        return Read(out.finish(), Details("png", location, removed, unknown = true))
    }

    private fun textMentionsLocation(b: ByteArray, from: Int, to: Int): Boolean {
        val text = String(b, from, (to - from).coerceAtMost(4096), Charsets.ISO_8859_1).lowercase()
        return text.contains("gps") || text.contains("latitude") || text.contains("longitude")
    }

    // ---- WebP -----------------------------------------------------------------------------

    private val WEBP_KEEP = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

    private fun webp(b: ByteArray): Read {
        if (b.size < 12) return Read(null, Details("webp", false, false, unknown = true))
        val out = ByteSink()
        out.emit(b, 0, 12) // RIFF + size (rewritten below) + WEBP
        var p = 12
        var removed = false
        var location = false
        while (p + 8 <= b.size) {
            val type = ascii(b, p, 4)
            // RIFF is little-endian: reading these as big-endian made every chunk look a
            // hundred megabytes long, so the walker gave up on the first one and the file went
            // out with its EXIF intact.
            val size = u32le(b, p + 4)
            if (size < 0 || p + 8 + size > b.size) {
                out.emit(b, p, b.size)
                return Read(out.finish(), Details("webp", location, true, unknown = true))
            }
            val end = p + 8 + size + (size and 1)
            if (type in WEBP_KEEP) {
                if (type == "VP8X" && size >= 1) {
                    // The E (Exif) and X (XMP) flags describe what this module is removing.
                    val flags = b[p + 8].toInt() and 0xFF
                    if (flags and 0x0C != 0) removed = true
                    val copy = b.copyOfRange(p, minOf(end, b.size))
                    copy[8] = (flags and 0x0C.inv()).toByte()
                    out.emit(copy)
                } else {
                    out.emit(b, p, minOf(end, b.size))
                }
            } else {
                removed = true
                if (type == "EXIF") {
                    val info = exifInfo(b, p + 8, p + 8 + size)
                    location = location || info.gps
                }
            }
            p = end
        }
        val finished = out.finish()
        if (finished.size < 12) return Read(null, Details("webp", location, removed, unknown = true))
        writeU32le(finished, 4, finished.size - 8)
        return Read(finished, Details("webp", location, removed))
    }

    // ---- GIF ------------------------------------------------------------------------------

    private val GIF_KEEP_APPS = setOf("NETSCAPE2.0", "ANIMEXTS1.0", "ICCRGBG1012")

    private fun gif(b: ByteArray): Read {
        if (b.size < 13) return Read(null, Details("gif", false, false, unknown = true))
        val out = ByteSink()
        // Header, logical screen descriptor, and the global colour table it declares.
        val packed = u8(b, 10)
        val tableSize = if (packed and 0x80 != 0) 3 * (1 shl ((packed and 0x07) + 1)) else 0
        val start = 13 + tableSize
        out.emit(b, 0, minOf(start, b.size))
        var p = start
        var removed = false
        while (p < b.size) {
            when (u8(b, p)) {
                0x3B -> { // trailer
                    out.emit(b, p, p + 1)
                    if (p + 1 < b.size) removed = true
                    return Read(out.finish(), Details("gif", false, removed))
                }
                0x21 -> { // extension
                    val label = u8(b, p + 1)
                    var q = p + 2
                    var keep = label != 0xFE // a comment extension
                    if (label == 0xFF) {
                        val identifier = ascii(b, q, minOf(11, b.size - q))
                        if (identifier !in GIF_KEEP_APPS) keep = false
                        q += 11
                    }
                    q = skipSubBlocks(b, q)
                    if (q < 0) {
                        out.emit(b, p, b.size)
                        return Read(out.finish(), Details("gif", false, true, unknown = true))
                    }
                    if (keep) out.emit(b, p, q) else removed = true
                    p = q
                }
                0x2C -> { // image descriptor
                    val localPacked = u8(b, p + 9)
                    var q = p + 10
                    if (localPacked and 0x80 != 0) q += 3 * (1 shl ((localPacked and 0x07) + 1))
                    q = skipSubBlocks(b, q + 1) // LZW minimum code size, then the data
                    if (q < 0) {
                        out.emit(b, p, b.size)
                        return Read(out.finish(), Details("gif", false, true, unknown = true))
                    }
                    out.emit(b, p, q)
                    p = q
                }
                // A block this reader does not know ends the stream, as in a browser's decoder.
                else -> {
                    out.emit(b, p, b.size)
                    return Read(out.finish(), Details("gif", false, true, unknown = true))
                }
            }
        }
        return Read(out.finish(), Details("gif", false, removed, unknown = true))
    }

    /** The index just past a GIF sub-block chain, or -1 when it runs past the end. */
    private fun skipSubBlocks(b: ByteArray, from: Int): Int {
        var q = from
        while (q < b.size) {
            val size = u8(b, q)
            if (size == 0) return q + 1
            q += 1 + size
        }
        return -1
    }

    // ---- ISOBMFF video --------------------------------------------------------------------

    /** The still-image brands: their `meta` box addresses the image itself and is left alone. */
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")

    private fun isobmff(b: ByteArray): Read {
        val out = ByteSink()
        var p = 0
        var location = false
        var removed = false
        while (p + 8 <= b.size) {
            val size = boxSize(b, p)
            if (size < 8 || p + size > b.size) {
                out.emit(b, p, b.size)
                return Read(out.finish(), Details("isobmff", location, true, unknown = true))
            }
            val type = ascii(b, p + 4, 4)
            when {
                // The containers are rewritten in place: no byte may move, because the sample
                // tables address their media by absolute offset.
                type == "moov" || type == "trak" -> {
                    val box = b.copyOfRange(p, p + size)
                    if (freeMetadataBoxes(box)) location = true
                    out.emit(box)
                }
                type == "udta" || type == "meta" || type == "uuid" -> {
                    if (carriesLocation(b, p, p + size)) location = true
                    removed = true
                    out.emit(freeBox(b, p, size))
                }
                else -> out.emit(b, p, p + size)
            }
            p += size
        }
        if (p < b.size) { out.emit(b, p, b.size); removed = true }
        return Read(out.finish(), Details("isobmff", location, removed))
    }

    /**
     * Turns every `udta`/`meta`/`uuid` box inside [box] into a `free` box of the same size.
     *
     * [box] is rewritten in place, so the caller's own copy carries the change. The answer is
     * whether a position was named by anything that was removed.
     */
    private fun freeMetadataBoxes(box: ByteArray): Boolean {
        var location = false
        var p = boxHeaderSize(box)
        while (p + 8 <= box.size) {
            val size = boxSize(box, p)
            if (size < 8 || p + size > box.size) break
            val type = ascii(box, p + 4, 4)
            when (type) {
                "udta", "meta", "uuid" -> {
                    if (carriesLocation(box, p, p + size)) location = true
                    freeBox(box, p, size).copyInto(box, p)
                }
                "trak", "moov" -> {
                    val inner = box.copyOfRange(p, p + size)
                    if (freeMetadataBoxes(inner)) location = true
                    inner.copyInto(box, p)
                }
            }
            p += size
        }
        return location
    }

    /** True when a `udta`/`meta` payload names a position in any of the forms cameras write. */
    private fun carriesLocation(b: ByteArray, from: Int, to: Int): Boolean {
        val text = String(b, from, to - from, Charsets.ISO_8859_1)
        return text.contains("\u00a9xyz") || text.contains("loci") || text.contains("ISO6709") ||
            text.contains("com.apple.quicktime.location") || text.contains("GPSCoordinates")
    }

    /** A `free` box of [size] bytes at [at]: the header is kept and the payload is zeroed. */
    private fun freeBox(b: ByteArray, at: Int, size: Int): ByteArray {
        val box = ByteArray(size)
        val header = boxHeaderSize(b.copyOfRange(at, at + size))
        b.copyInto(box, 0, at, at + minOf(header, size))
        "free".forEachIndexed { i, c -> if (4 + i < box.size) box[4 + i] = c.code.toByte() }
        return box
    }

    /** The header a box's own size field declares: 8 bytes, 16 with a 64-bit size, 8 otherwise. */
    private fun boxHeaderSize(box: ByteArray): Int = when (u32(box, 0)) {
        1 -> 16
        0 -> 8
        else -> 8
    }

    private fun boxSize(b: ByteArray, at: Int): Int {
        val size = u32(b, at)
        return when (size) {
            1 -> if (at + 16 <= b.size) {
                val large = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[at + 8 + i].toLong() and 0xFF) }
                if (large > Int.MAX_VALUE) -1 else large.toInt()
            } else -1
            0 -> b.size - at // to the end of the file
            else -> size
        }
    }

    // ---- Exif -----------------------------------------------------------------------------

    private class ExifInfo(val orientation: Int, val gps: Boolean)

    /**
     * The orientation and whether a position is recorded, from a TIFF structure at [from].
     *
     * A GPS *pointer* alone is not a position: an editor may leave the IFD empty. The latitude
     * and longitude tags are what a person would be publishing, so those are what is looked for.
     */
    private fun exifInfo(b: ByteArray, from: Int, to: Int): ExifInfo {
        if (from < 0 || from + 8 > to) return ExifInfo(0, false)
        val little = when {
            b[from] == 0x49.toByte() && b[from + 1] == 0x49.toByte() -> true
            b[from] == 0x4D.toByte() && b[from + 1] == 0x4D.toByte() -> false
            else -> return ExifInfo(0, false)
        }
        fun u16(at: Int): Int = when {
            at < from || at + 2 > to -> -1
            little -> (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
            else -> ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        }
        fun u32(at: Int): Long = when {
            at < from || at + 4 > to -> -1
            little -> (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
            else -> ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
        }
        if (u16(from + 2) != 42) return ExifInfo(0, false)
        val ifd0Offset = u32(from + 4)
        if (ifd0Offset <= 0) return ExifInfo(0, false)
        val ifd0 = from + ifd0Offset.toInt()
        var orientation = 0
        var gpsPointer = -1L
        val entries = u16(ifd0)
        for (i in 0 until entries.coerceIn(0, 512)) {
            val entry = ifd0 + 2 + i * 12
            if (entry + 12 > to) break
            when (u16(entry)) {
                0x0112 -> orientation = u16(entry + 8).coerceIn(0, 8)
                0x8825 -> gpsPointer = u32(entry + 8)
            }
        }
        var gps = false
        if (gpsPointer > 0) {
            val gpsIfd = from + gpsPointer.toInt()
            val count = u16(gpsIfd)
            for (i in 0 until count.coerceIn(0, 512)) {
                val entry = gpsIfd + 2 + i * 12
                if (entry + 12 > to) break
                // GPSLatitude, GPSLongitude: the position itself, not just the section.
                if (u16(entry) == 0x0002 || u16(entry) == 0x0004) gps = true
            }
        }
        return ExifInfo(orientation, gps)
    }

    // ---- bytes ----------------------------------------------------------------------------

    private fun u8(b: ByteArray, at: Int): Int = if (at in b.indices) b[at].toInt() and 0xFF else -1

    private fun u16(b: ByteArray, at: Int): Int =
        if (at < 0 || at + 2 > b.size) -1 else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Int =
        if (at < 0 || at + 4 > b.size) -1 else
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun u32le(b: ByteArray, at: Int): Int =
        if (at < 0 || at + 4 > b.size) -1 else
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun writeU32le(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte(); b[at + 1] = (value ushr 8).toByte()
        b[at + 2] = (value ushr 16).toByte(); b[at + 3] = (value ushr 24).toByte()
    }

    private fun writeU32(b: ByteArray, at: Int, value: Int) {
        b[at] = (value ushr 24).toByte(); b[at + 1] = (value ushr 16).toByte()
        b[at + 2] = (value ushr 8).toByte(); b[at + 3] = value.toByte()
    }

    private fun ascii(b: ByteArray, from: Int, count: Int): String =
        if (from < 0 || from >= b.size || count <= 0) "" else
            String(b, from, minOf(count, b.size - from), Charsets.ISO_8859_1)

    /** A growing byte buffer with a range copy, so a plan is a list of kept slices. */
    private class ByteSink {
        private var bytes = ByteArray(256)
        private var size = 0

        fun emit(source: ByteArray, from: Int, to: Int) {
            if (to <= from) return
            val count = to - from
            if (size + count > bytes.size) {
                bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
            }
            source.copyInto(bytes, size, from, to)
            size += count
        }

        fun emit(source: ByteArray) = emit(source, 0, source.size)

        fun finish(): ByteArray = bytes.copyOf(size)
    }

    /**
     * A PNG `eXIf` chunk holding IFD0's Orientation alone.
     *
     * Kept for callers that rewrite a PNG's orientation; the stripper drops the original chunk
     * because a re-encoded photo's orientation is not worth a chunk of its own, and a camera's
     * EXIF carries far more than the rotation.
     */
    internal fun pngOrientationChunk(orientation: Int): ByteArray? {
        if (orientation !in 2..8) return null
        val tiff = ByteArray(26)
        fun put(index: Int, value: Int) { tiff[index] = value.toByte() }
        put(0, 0x49); put(1, 0x49); put(2, 0x2A); put(3, 0x00)
        put(4, 0x08); put(5, 0x00); put(6, 0x00); put(7, 0x00)
        put(8, 0x01); put(9, 0x00)
        put(10, 0x12); put(11, 0x01); put(12, 0x03); put(13, 0x00)
        put(14, 0x01); put(15, 0x00); put(16, 0x00); put(17, 0x00)
        put(18, orientation); put(19, 0x00); put(20, 0x00); put(21, 0x00)
        put(22, 0x00); put(23, 0x00); put(24, 0x00); put(25, 0x00)
        val chunk = ByteArray(12 + tiff.size)
        writeU32(chunk, 0, tiff.size)
        "eXIf".forEachIndexed { i, c -> chunk[4 + i] = c.code.toByte() }
        tiff.copyInto(chunk, 8)
        val crc = CRC32()
        crc.update(chunk, 4, 4 + tiff.size)
        writeU32(chunk, 8 + tiff.size, crc.value.toInt())
        return chunk
    }
}
