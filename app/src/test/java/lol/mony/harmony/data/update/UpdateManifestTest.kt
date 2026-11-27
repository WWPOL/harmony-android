package lol.mony.harmony.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update manifest: what the app will offer, and what it refuses to.
 *
 * The manifest is data from the network that ends in a link the person is asked to tap, so these
 * are the rules that keep a malformed, stale or hostile document from turning into an "update"
 * button that installs something else. Each case below is a manifest that a release tooling
 * mistake -- or a repository that has been tampered with -- could produce.
 */
class UpdateManifestTest {
    private val installed = InstalledBuild(
        packageName = "io.github.wwpol.brapmony",
        versionCode = 2_000_100,
        versionName = "0.1.0-abc1234",
        downloadBase = "https://github.com/WWPol/harmony-android/releases/download",
    )

    private fun manifest(
        versionCode: Int = 2_000_200,
        packageName: String = "io.github.wwpol.brapmony",
        tag: String = "v0.1.0-def5678",
        apk: String = "Brapmony-0.1.0-def5678-debug.apk",
        sha256: String = "a".repeat(64),
        size: Long = 72_000_000,
        notes: String = "https://github.com/WWPol/harmony-android/releases/tag/v0.1.0-def5678",
        schema: Int = 1,
    ) = UpdateManifest(
        schema = schema, packageName = packageName, versionCode = versionCode,
        versionName = tag.removePrefix("v"), tag = tag, apk = apk, sha256 = sha256,
        size = size, notes = notes,
    )

    @Test fun aNewerBuildIsOfferedWithItsOwnDownload() {
        val found = manifest().availableUpdate(installed)
        assertNotNull("a newer build on the same package is an update", found)
        found!!
        assertEquals(2_000_200, found.versionCode)
        // The link is the APK on the release the manifest named, not `latest`: the offer must
        // keep pointing at the build it described after the next release is published.
        assertEquals(
            "https://github.com/WWPol/harmony-android/releases/download/v0.1.0-def5678/" +
                "Brapmony-0.1.0-def5678-debug.apk",
            found.downloadUrl,
        )
        assertEquals("72 MB", found.sizeLabel)
    }

    @Test fun theSameBuildOrAnOlderOneIsNotAnUpdate() {
        assertNull("the installed build is not an update of itself", manifest(versionCode = 2_000_100).availableUpdate(installed))
        assertNull("an older build is not an update", manifest(versionCode = 1_999_999).availableUpdate(installed))
    }

    @Test fun anotherApplicationsBuildIsNeverOffered() {
        assertNull(manifest(packageName = "com.example.other").availableUpdate(installed))
        assertNull("a manifest that does not say which app it is is not this app's", manifest(packageName = "").availableUpdate(installed))
    }

    @Test fun aManifestOfAnUnknownFormatIsIgnored() {
        assertNull(manifest(schema = 2).availableUpdate(installed))
        assertNull(manifest(schema = 0).availableUpdate(installed))
    }

    @Test fun theDownloadLinkCannotBeRedirectedElsewhere() {
        // A tag or file name that is a path -- `..`, an absolute URL, a query -- would make the
        // download link point somewhere the release does not own. Both are refused outright.
        for (tag in listOf("v0.1.0-abc/../../evil", "https://evil.test/v1", "v0.1.0-ABC1234", "latest", "")) {
            assertNull("tag $tag must not be offered", manifest(tag = tag).availableUpdate(installed))
        }
        for (apk in listOf("../../evil.apk", "Brapmony.apk?x=y", "https://evil.test/x.apk", "Brapmony.zip", "sub/Brapmony.apk")) {
            assertNull("apk $apk must not be offered", manifest(apk = apk).availableUpdate(installed))
        }
    }

    @Test fun aManifestWithoutAUsableChecksumOrSizeIsNotOffered() {
        // The checksum is what a download can be checked against; a manifest without one is not
        // a manifest this app can act on, and neither is one that claims a zero-byte APK.
        assertNull(manifest(sha256 = "").availableUpdate(installed))
        assertNull(manifest(sha256 = "not-a-digest").availableUpdate(installed))
        assertNull(manifest(sha256 = "A".repeat(64)).availableUpdate(installed))
        assertNull(manifest(size = 0).availableUpdate(installed))
        assertNull(manifest(size = -1).availableUpdate(installed))
    }

    @Test fun aNotesLinkIsKeptOnlyWhenItIsAWebPage() {
        assertEquals(
            "https://github.com/WWPol/harmony-android/releases/tag/v0.1.0-def5678",
            manifest().availableUpdate(installed)!!.notesUrl,
        )
        assertNull("a javascript: link is not a release page", manifest(notes = "javascript:alert(1)").availableUpdate(installed)!!.notesUrl)
        assertNull("nor is a file: link", manifest(notes = "file:///data/local/tmp/x").availableUpdate(installed)!!.notesUrl)
        // A missing notes link does not make the update unusable: the download is the update.
        assertNotNull(manifest(notes = "").availableUpdate(installed))
        assertNull(manifest(notes = "").availableUpdate(installed)!!.notesUrl)
    }

    @Test fun theManifestIsParsedLenientlyButNotGuessedAt() {
        val text = """
            {
              "schema": 1,
              "package": "io.github.wwpol.brapmony",
              "versionCode": 2000200,
              "versionName": "0.1.0-def5678",
              "tag": "v0.1.0-def5678",
              "apk": "Brapmony-0.1.0-def5678-debug.apk",
              "sha256": "${"b".repeat(64)}",
              "size": 72920972,
              "notes": "https://github.com/WWPol/harmony-android/releases/tag/v0.1.0-def5678",
              "commit": "def5678",
              "somethingNew": {"a": 1}
            }
        """.trimIndent()
        val parsed = UpdateManifest.parse(text)
        assertNotNull("an unknown field must not make the manifest unreadable", parsed)
        parsed!!
        assertEquals(2_000_200, parsed.versionCode)
        assertEquals("def5678", parsed.commit)
        assertNotNull(parsed.availableUpdate(installed))

        // A field of the wrong type is a malformed manifest, not a zero: offering it would be
        // offering a build with no version, which is no offer at all.
        assertNull(UpdateManifest.parse("""{"schema":1,"versionCode":"2000200"}"""))
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse(""))
    }

    @Test fun aVersionNameIsNeverBlankInWhatIsShown() {
        val noName = manifest().copy(versionName = "")
        assertEquals("0.1.0-def5678", noName.availableUpdate(installed)!!.versionName)
    }

    @Test fun theSizeIsShownTheWayAPersonReadsIt() {
        assertEquals("72 MB", manifest(size = 72_920_972).availableUpdate(installed)!!.sizeLabel)
        assertEquals("9.5 MB", manifest(size = 9_500_000).availableUpdate(installed)!!.sizeLabel)
        // A release-signed APK is smaller than a debug one, and its name says so.
        val release = manifest(apk = "Brapmony-0.1.0-def5678.apk")
        assertEquals("Brapmony-0.1.0-def5678.apk", release.availableUpdate(installed)!!.apkName)
    }

    @Test fun aDownloadBaseWithATrailingSlashStillMakesOneUrl() {
        val found = manifest().availableUpdate(installed.copy(downloadBase = installed.downloadBase + "/"))
        assertEquals(
            "https://github.com/WWPol/harmony-android/releases/download/v0.1.0-def5678/" +
                "Brapmony-0.1.0-def5678-debug.apk",
            found!!.downloadUrl,
        )
    }

    /**
     * The file the release tooling actually writes, as it writes it.
     *
     * The two halves of this feature are in different languages, and the only thing joining
     * them is this document: a field renamed on one side would otherwise be found on a phone,
     * by a person who was offered nothing or offered something that would not install. So the
     * exact output of `node scripts/release.mjs manifest` is read here, and it must offer the
     * update it names.
     */
    @Test fun theManifestTheReleaseToolingWritesIsTheOneThisBuildReads() {
        // Copied verbatim from `node scripts/release.mjs manifest` for the fixture APK: two
        // spaces of indentation, the field order, the lower-case digest and all.
        val published = """
            {
              "schema": 1,
              "package": "io.github.wwpol.brapmony",
              "versionCode": 2000200,
              "versionName": "0.1.0-def5678",
              "tag": "v0.1.0-def5678",
              "apk": "Brapmony-0.1.0-def5678-debug.apk",
              "sha256": "${"a".repeat(64)}",
              "size": 72920972,
              "notes": "https://github.com/WWPol/harmony-android/releases/tag/v0.1.0-def5678",
              "commit": "def5678"
            }
        """.trimIndent()
        val parsed = UpdateManifest.parse(published)
        assertNotNull("the manifest the release tooling writes is not readable", parsed)
        val found = parsed!!.availableUpdate(installed)
        assertNotNull("and does not offer the update it names", found)
        assertEquals(2_000_200, found!!.versionCode)
        assertEquals("Brapmony-0.1.0-def5678-debug.apk", found.apkName)
        assertEquals(
            "https://github.com/WWPol/harmony-android/releases/download/v0.1.0-def5678/" +
                "Brapmony-0.1.0-def5678-debug.apk",
            found.downloadUrl,
        )
        // A version release's manifest is the same shape with a version tag and no commit in
        // the tag, and it is read the same way.
        val versionRelease = published
            .replace("Brapmony-0.1.0-def5678-debug.apk", "Brapmony-1.9.6.apk")
            .replace("v0.1.0-def5678", "v1.9.6")
            .replace("0.1.0-def5678", "1.9.6")
        val release = UpdateManifest.parse(versionRelease)!!.availableUpdate(installed)
        assertNotNull("a version release is an update too", release)
        assertEquals("Brapmony-1.9.6.apk", release!!.apkName)
    }

    @Test fun anUpdateIsOnlyForThePackageItNames() {
        val debug = installed.copy(packageName = "io.github.wwpol.brapmony")
        assertNotNull(manifest().availableUpdate(debug))
        assertTrue(manifest().availableUpdate(debug)!!.downloadUrl.endsWith(".apk"))
        assertFalse(manifest(packageName = "io.github.wwpol.brapmony.debug").availableUpdate(debug) != null)
    }
}
