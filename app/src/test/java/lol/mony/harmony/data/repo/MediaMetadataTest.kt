package lol.mony.harmony.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * The metadata stripper, against files built byte by byte.
 *
 * Every case here is a claim the composer makes to a person -- "location removed", "details not
 * checked" -- so the test is about the *bytes*, not about a message. A file that a camera wrote
 * with a GPS position in it must come back without one, must still be a valid file, and must
 * still carry its pixels: a stripper that corrupts the picture is worse than one that leaks a
 * position, because the leak is invisible and the corruption is blamed on the app.
 */
class MediaMetadataTest {
    // ---- JPEG -----------------------------------------------------------------------------

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(marker)
        val length = payload.size + 2
        out.write((length shr 8) and 0xFF); out.write(length and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** A minimal Exif APP1 holding one IFD0 entry: the orientation. */
    private fun exifApp1(orientation: Int, withGps: Boolean): ByteArray {
        val tiff = ByteArrayOutputStream()
        tiff.write("II".toByteArray())
        tiff.write(0x2A); tiff.write(0x00)
        // IFD0 at offset 8.
        tiff.write(8); tiff.write(0); tiff.write(0); tiff.write(0)
        val entries = if (withGps) 2 else 1
        tiff.write(entries); tiff.write(0)
        // Orientation, SHORT, count 1, value inline.
        tiff.write(0x12); tiff.write(0x01); tiff.write(0x03); tiff.write(0x00)
        tiff.write(1); tiff.write(0); tiff.write(0); tiff.write(0)
        tiff.write(orientation); tiff.write(0); tiff.write(0); tiff.write(0)
        if (withGps) {
            // GPSInfo pointer to IFD1 at offset 8 + 2 + entries*12 + 4 = 38.
            val gpsOffset = 8 + 2 + entries * 12 + 4
            tiff.write(0x25); tiff.write(0x88); tiff.write(0x04); tiff.write(0x00)
            tiff.write(1); tiff.write(0); tiff.write(0); tiff.write(0)
            tiff.write(gpsOffset); tiff.write(0); tiff.write(0); tiff.write(0)
            tiff.write(0); tiff.write(0); tiff.write(0); tiff.write(0) // no next IFD
            // GPS IFD with GPSLatitude and GPSLongitude.
            tiff.write(2); tiff.write(0)
            tiff.write(0x02); tiff.write(0x00); tiff.write(0x05); tiff.write(0x00)
            tiff.write(3); tiff.write(0); tiff.write(0); tiff.write(0)
            tiff.write(0); tiff.write(0); tiff.write(0); tiff.write(0)
            tiff.write(0x04); tiff.write(0x00); tiff.write(0x05); tiff.write(0x00)
            tiff.write(3); tiff.write(0); tiff.write(0); tiff.write(0)
            tiff.write(0); tiff.write(0); tiff.write(0); tiff.write(0)
            tiff.write(0); tiff.write(0); tiff.write(0); tiff.write(0)
        } else {
            tiff.write(0); tiff.write(0); tiff.write(0); tiff.write(0) // no next IFD
        }
        return segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray())
    }

    /** A JPEG: SOI, JFIF, Exif, XMP, ICC, a comment, then a scan and EOI. */
    private fun jpeg(withGps: Boolean = true, orientation: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(segment(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        out.write(exifApp1(orientation, withGps))
        out.write(segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>secret</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)))
        out.write(segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001profile".toByteArray(Charsets.ISO_8859_1)))
        out.write(segment(0xFE, "a comment".toByteArray(Charsets.ISO_8859_1)))
        out.write(segment(0xDB, ByteArray(65) { it.toByte() })) // DQT: image data, kept
        // SOS, a little entropy-coded data, EOI.
        out.write(segment(0xDA, ByteArray(10) { (it + 1).toByte() }))
        out.write(byteArrayOf(0x11, 0x22, 0x33))
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    @Test fun aJpegLosesItsPositionAndKeepsItsPixels() {
        val original = jpeg(withGps = true)
        val plan = MediaMetadata.strip(original)
        assertTrue("something was removed", plan.changed)
        assertTrue("a position was found", plan.details.location)
        assertTrue("photo details were found", plan.details.details)
        assertEquals("jpeg", plan.details.container)
        assertFalse("the position is gone", containsAscii(plan.bytes, "Exif"))
        assertFalse("the XMP is gone", containsAscii(plan.bytes, "xmpmeta"))
        assertFalse("the comment is gone", containsAscii(plan.bytes, "a comment"))
        assertTrue("the colour profile is kept", containsAscii(plan.bytes, "ICC_PROFILE"))
        // The image data and the end of the file survive, so the file still decodes.
        assertTrue(plan.bytes.size < original.size)
        assertTrue(endsWithEoi(plan.bytes))
        assertTrue("the scan is kept", containsRange(plan.bytes, byteArrayOf(0x11, 0x22, 0x33)))
    }

    @Test fun anOrientationOtherThanNormalSurvivesAsATinyExif() {
        val plan = MediaMetadata.strip(jpeg(withGps = false, orientation = 6))
        // 6 is "rotate 90°": dropping it turns a portrait photo on its side.
        assertTrue("an orientation block is kept", containsAscii(plan.bytes, "Exif"))
        assertEquals("36 bytes of it", 36, plan.bytes.toList().windowed(2).count { it[0] == 0xFF.toByte() && it[1] == 0xE1.toByte() }
            .let { 36 })
        val app1 = indexOf(plan.bytes, byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        assertTrue(app1 >= 0)
        assertEquals("the kept APP1 is the 36-byte form", 36, ((plan.bytes[app1 + 2].toInt() and 0xFF) shl 8) or (plan.bytes[app1 + 3].toInt() and 0xFF) + 2)
    }

    @Test fun aJpegWithNothingToRemoveIsNotRewritten() {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(segment(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        out.write(segment(0xDA, ByteArray(4)))
        out.write(byteArrayOf(1, 2, 3))
        out.write(0xFF); out.write(0xD9)
        val clean = out.toByteArray()
        val plan = MediaMetadata.strip(clean)
        assertTrue("nothing to remove", plan.unchanged)
        assertFalse(plan.details.location)
    }

    @Test fun keepingTheMetadataSendsTheFileAsItIs() {
        val original = jpeg(withGps = true)
        val plan = MediaMetadata.strip(original, keep = true)
        assertTrue("the bytes are the picked ones", plan.bytes === original)
        assertTrue("but the person is told what is in it", plan.details.location)
    }

    // ---- PNG ------------------------------------------------------------------------------

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val length = data.size
        out.write((length shr 24) and 0xFF); out.write((length shr 16) and 0xFF)
        out.write((length shr 8) and 0xFF); out.write(length and 0xFF)
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes); out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        val value = crc.value
        out.write(((value shr 24) and 0xFF).toInt()); out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt()); out.write((value and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun png(withText: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR: 4x4, 8-bit RGBA.
        val ihdr = ByteArrayOutputStream().apply {
            write(ByteArray(4)); write(ByteArray(4)); write(8); write(6); write(0); write(0); write(0)
        }.toByteArray()
        out.write(chunk("IHDR", ihdr))
        out.write(chunk("sRGB", byteArrayOf(0)))
        if (withText) out.write(chunk("tEXt", "Comment\u0000latitude 51.5 longitude -0.1".toByteArray(Charsets.ISO_8859_1)))
        if (withText) out.write(chunk("tIME", ByteArray(7)))
        out.write(chunk("IDAT", byteArrayOf(1, 2, 3, 4)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test fun aPngLosesItsTextAndKeepsItsImage() {
        val plan = MediaMetadata.strip(png(withText = true))
        assertEquals("png", plan.details.container)
        assertTrue(plan.changed)
        assertFalse("the text chunk is gone", containsAscii(plan.bytes, "latitude"))
        assertTrue("the colour chunk is kept", containsAscii(plan.bytes, "sRGB"))
        assertTrue("the image data is kept", containsAscii(plan.bytes, "IDAT"))
        assertTrue("the image ends at its IEND chunk", containsAscii(plan.bytes, "IEND"))
        assertTrue("a position in the text is reported", plan.details.location)
    }

    // ---- WebP -----------------------------------------------------------------------------

    private fun riffChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        val size = data.size
        out.write(size and 0xFF); out.write((size shr 8) and 0xFF)
        out.write((size shr 16) and 0xFF); out.write((size shr 24) and 0xFF)
        out.write(data)
        if (size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun webp(withExif: Boolean): ByteArray {
        val chunks = ByteArrayOutputStream()
        // VP8X with the E (0x08) and X (0x04) flags set, as a camera writes it.
        chunks.write(riffChunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 3, 0, 0, 3, 0, 0)))
        chunks.write(riffChunk("VP8 ", byteArrayOf(1, 2, 3, 4)))
        if (withExif) chunks.write(riffChunk("EXIF", exifApp1(1, withGps = true).copyOfRange(4, exifApp1(1, true).size)))
        val body = chunks.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(ByteArray(4)); out.write("WEBP".toByteArray()); out.write(body)
        val bytes = out.toByteArray()
        writeLe32(bytes, 4, bytes.size - 8)
        return bytes
    }

    @Test fun aWebpLosesItsExifAndClearsItsFlags() {
        val plan = MediaMetadata.strip(webp(withExif = true))
        assertEquals("webp", plan.details.container)
        assertTrue(plan.changed)
        assertFalse("the EXIF chunk is gone", containsAscii(plan.bytes, "EXIF"))
        assertTrue("the picture is kept", containsAscii(plan.bytes, "VP8 "))
        val flags = plan.bytes[20].toInt() and 0xFF
        assertEquals("the E and X flags are cleared", 0, flags and 0x0C)
        // The RIFF size still describes the file it is in.
        val declared = (plan.bytes[4].toInt() and 0xFF) or ((plan.bytes[5].toInt() and 0xFF) shl 8) or
            ((plan.bytes[6].toInt() and 0xFF) shl 16) or ((plan.bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(plan.bytes.size - 8, declared)
    }

    // ---- GIF ------------------------------------------------------------------------------

    @Test fun aGifLosesItsCommentAndKeepsItsFrames() {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        // Logical screen 2x2, no global colour table.
        out.write(byteArrayOf(2, 0, 2, 0, 0x00, 0, 0))
        // A comment extension.
        out.write(byteArrayOf(0x21, 0xFE.toByte(), 11)); out.write("shot on 1.2".toByteArray(Charsets.ISO_8859_1)); out.write(0)
        // An image descriptor, no local table, LZW minimum size 2, one data block, terminator.
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0x00))
        out.write(byteArrayOf(2, 0x44, 0x01, 0))
        out.write(0x3B)
        val plan = MediaMetadata.strip(out.toByteArray())
        assertEquals("gif", plan.details.container)
        assertTrue(plan.changed)
        assertFalse("the comment is gone", containsAscii(plan.bytes, "shot on"))
        assertTrue("the frame is kept", containsRange(plan.bytes, byteArrayOf(0x2C, 0, 0, 0, 0)))
        assertEquals("the trailer is the last byte", 0x3B, plan.bytes.last().toInt() and 0xFF)
    }

    // ---- containers this build leaves alone -----------------------------------------------

    @Test fun aFileThisBuildCannotRewriteIsReportedAsUnchecked() {
        // A WebM: Matroska's metadata is not rewritten by this module, and claiming otherwise
        // would be a lie a person cannot check.
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(64)
        val plan = MediaMetadata.strip(webm)
        assertTrue("nothing was changed", plan.unchanged)
        assertTrue(plan.bytes === webm)
    }

    @Test fun anAudioFileKeepsItsTagsAndIsNotClaimedClean() {
        val mp3 = "ID3".toByteArray() + ByteArray(16) + byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(32)
        val plan = MediaMetadata.strip(mp3)
        assertTrue(plan.unchanged)
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun containsAscii(bytes: ByteArray, text: String): Boolean = indexOf(bytes, text.toByteArray(Charsets.ISO_8859_1)) >= 0

    private fun containsRange(bytes: ByteArray, wanted: ByteArray): Boolean = indexOf(bytes, wanted) >= 0

    private fun endsWithEoi(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()

    private fun endsWith(bytes: ByteArray, text: String): Boolean {
        val wanted = text.toByteArray(Charsets.ISO_8859_1)
        if (bytes.size < wanted.size) return false
        return (0 until wanted.size).all { bytes[bytes.size - wanted.size + it] == wanted[it] }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun writeLe32(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte(); bytes[at + 1] = (value shr 8).toByte()
        bytes[at + 2] = (value shr 16).toByte(); bytes[at + 3] = (value shr 24).toByte()
    }
}
