package lol.mony.harmony.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The file a release publishes to say what its newest build is: `update.json`.
 *
 * The app could ask GitHub's REST API for the latest release instead, and it deliberately does
 * not. That answer is a 15–20 KB document, it is rate limited per client address (60 requests an
 * hour unauthenticated, shared by everyone behind one carrier's NAT), and every install would be
 * asking for the same two facts. This is a few hundred bytes of static file, served from the
 * release's own assets through a CDN that answers `If-None-Match` with a 304, and it is the same
 * document for every install in the world, so GitHub's cache absorbs the traffic rather than its
 * API counting it.
 *
 * [tag] and [apk] are path segments of the download URL, so both are checked against the shapes
 * the release tooling can produce before anything is built from them: a manifest is data from
 * the network, and a manifest that could name `../` or another host would be a manifest that
 * points the app's "Download" button somewhere else.
 */
@Serializable
data class UpdateManifest(
    /** The format's version. A manifest this build does not know is ignored rather than guessed at. */
    val schema: Int = 1,
    @SerialName("package") val packageName: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    /** The release tag, e.g. `v0.1.0-abc1234`. */
    val tag: String = "",
    /** The APK's file name on that release, e.g. `Brapmony-0.1.0-abc1234-debug.apk`. */
    val apk: String = "",
    /** The APK's SHA-256, so a download can be checked against what the release published. */
    val sha256: String = "",
    val size: Long = 0,
    /** The release page, for "What changed". */
    val notes: String = "",
    /** The commit the build is of, for a bug report. */
    val commit: String = "",
) {
    /**
     * The update this manifest offers, or null when it is not one this build can take.
     *
     * The rules are the ones that make an update an *update*: the same package, a format this
     * build understands, a version code strictly higher than the installed one (Android's own
     * rule for an APK that installs over another), and a tag and file name that cannot escape the
     * release's own download path. Anything else is not offered, and the app carries on rather
     * than sending someone to a link that would not install.
     */
    fun availableUpdate(installed: InstalledBuild): AvailableUpdate? {
        if (schema != SUPPORTED_SCHEMA) return null
        if (packageName != installed.packageName) return null
        if (versionCode <= installed.versionCode) return null
        if (!TAG.matches(tag)) return null
        if (!APK_NAME.matches(apk)) return null
        if (!SHA256.matches(sha256)) return null
        if (size <= 0) return null
        val notesUrl = notes.takeIf { NOTES.matches(it) }
        return AvailableUpdate(
            versionCode = versionCode,
            versionName = versionName.ifBlank { tag.removePrefix("v") },
            tag = tag,
            apkName = apk,
            sha256 = sha256,
            size = size,
            // The tag is in the URL rather than `latest`, so the link a person is shown keeps
            // pointing at the build it named even after the next release is published.
            downloadUrl = installed.downloadBase.trimEnd('/') + "/" + tag + "/" + apk,
            notesUrl = notesUrl,
        )
    }

    companion object {
        /** The only schema this build reads. A newer one is ignored, not half-understood. */
        const val SUPPORTED_SCHEMA = 1

        /** A release tag: `vX.Y.Z`, or a commit's `vX.Y.Z-<sha7>`. */
        private val TAG = Regex("^v\\d+\\.\\d+\\.\\d+(-[0-9a-f]{7,40})?$")

        /**
         * An APK's file name: the release tooling's own naming scheme, one path segment, no
         * separators. `Brapmony-0.1.0-abc1234-debug.apk`, or `Brapmony-0.1.0.apk`.
         */
        private val APK_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.apk$")

        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** The release page's own URL: https, on GitHub, and nothing else. */
        private val NOTES = Regex("^https://[A-Za-z0-9.-]+/[A-Za-z0-9._~/-]*$")

        /**
         * The JSON dialect this file is read with.
         *
         * Unknown keys are ignored so a release that adds a field does not break an installed
         * build, and nothing is coerced: a field of the wrong type is a malformed manifest, and
         * [parse] answers null for it rather than inventing a value.
         */
        private val Format = Json { ignoreUnknownKeys = true }

        private val NUMBER_KEYS = listOf("schema", "versionCode", "size")
        private val STRING_KEYS = listOf("package", "versionName", "tag", "apk", "sha256", "notes", "commit")
        private val DIGITS = Regex("^-?\\d+$")

        /**
         * The manifest [text] describes, or null when it is not a manifest this build reads.
         *
         * The field types are checked before the document is decoded. The JSON reader will
         * happily read a quoted `"2000200"` into an `Int`, and a manifest whose numbers are
         * strings is not a manifest this release tooling wrote -- so it is refused rather than
         * interpreted, on the same principle as the checks in [availableUpdate].
         */
        fun parse(text: String): UpdateManifest? {
            val root = try {
                Format.parseToJsonElement(text) as? JsonObject ?: return null
            } catch (_: Exception) {
                return null
            }
            for (key in NUMBER_KEYS) {
                val value = root[key] ?: continue
                if (value !is JsonPrimitive || value.isString || !DIGITS.matches(value.content)) return null
            }
            for (key in STRING_KEYS) {
                val value = root[key] ?: continue
                if (value !is JsonPrimitive || !value.isString) return null
            }
            return try {
                Format.decodeFromJsonElement(serializer(), root)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** The build that is installed, and where its releases' downloads live. */
data class InstalledBuild(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    /** `https://github.com/<owner>/<repo>/releases/download/`, without the trailing slash's need. */
    val downloadBase: String,
)

/** A newer build the person can download and install over this one. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val apkName: String,
    val sha256: String,
    val size: Long,
    /** The APK itself, so a tap downloads it rather than opening a page to find it on. */
    val downloadUrl: String,
    /** The release page, which says what changed, when the manifest names one. */
    val notesUrl: String?,
) {
    /**
     * The download's size, for the notice: "72 MB", not "75497472".
     *
     * In decimal megabytes, which is what GitHub's release page, the browser's download list and
     * the file manager all show: a size that disagreed with the number beside the download
     * would read as a different file.
     */
    val sizeLabel: String
        get() {
            val megabytes = size / 1_000_000.0
            return if (megabytes >= 10) "${megabytes.toInt()} MB"
            else String.format(java.util.Locale.US, "%.1f MB", megabytes)
        }
}
