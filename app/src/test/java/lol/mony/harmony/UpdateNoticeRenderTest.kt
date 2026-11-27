package lol.mony.harmony

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import lol.mony.harmony.data.update.AvailableUpdate
import lol.mony.harmony.ui.screens.you.UpdateAvailableDialog
import lol.mony.harmony.ui.screens.you.lastCheckedLabel
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The update notice, drawn: a newer build, its size, and one button that downloads it.
 *
 * The point of the feature is that a tap goes straight to the APK, so what these assert is the
 * link that would be opened and the wording around it -- the version is named, "Not now" is a
 * real choice, and "What changed" is there only when the release published a page to read.
 *
 * A capture of the dialog is the evidence: this is what a person sees when a release lands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class UpdateNoticeRenderTest {
    @get:Rule val compose = createComposeRule()

    private val update = AvailableUpdate(
        versionCode = 2_000_200,
        versionName = "0.1.0-def5678",
        tag = "v0.1.0-def5678",
        apkName = "Brapmony-0.1.0-def5678-debug.apk",
        sha256 = "a".repeat(64),
        size = 72_920_972,
        downloadUrl = "https://github.com/WWPol/harmony-android/releases/download/" +
            "v0.1.0-def5678/Brapmony-0.1.0-def5678-debug.apk",
        notesUrl = "https://github.com/WWPol/harmony-android/releases/tag/v0.1.0-def5678",
    )

    @Test fun theNoticeNamesTheBuildAndDownloadsTheApkItself() {
        val opened = mutableListOf<String>()
        var dismissed = 0
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("Behind the notice", Modifier.padding(16.dp))
                        UpdateAvailableDialog(
                            update = update,
                            onDownload = { opened += update.downloadUrl },
                            onNotes = { opened += update.notesUrl!! },
                            onDismiss = { dismissed++ },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("update-available-dark") }

        // The build is named, and the download is the APK rather than a page to find it on.
        compose.onAllNodes(hasText("Update to 0.1.0-def5678", substring = true)).fetchSemanticsNodes()
            .let { assertTrue("the notice does not name the build", it.isNotEmpty()) }
        compose.onAllNodes(hasText("72 MB", substring = true)).fetchSemanticsNodes()
            .let { assertTrue("the notice does not say how big the download is", it.isNotEmpty()) }
        compose.onNodeWithTag("update-download").performClick()
        assertEquals(listOf(update.downloadUrl), opened)
        // "What changed" opens the release page, which is where the changelog is.
        compose.onAllNodes(hasText("What changed")).fetchSemanticsNodes()
            .let { assertTrue("the release notes are not offered", it.isNotEmpty()) }
        compose.onNodeWithTag("update-later").performClick()
        assertEquals("Not now is a real choice", 1, dismissed)
    }

    /** A release that published no notes page has no "What changed" to press. */
    @Test fun aReleaseWithoutAPageOffersNoLinkToIt() {
        var dismissed = 0
        compose.setContent {
            HarmonyTheme(VisualThemeSettings(theme = "light")) {
                Surface(Modifier.fillMaxSize()) {
                    UpdateAvailableDialog(
                        update = update.copy(notesUrl = null),
                        onDownload = {},
                        onNotes = null,
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        compose.waitForIdle()
        RenderHarness.run { compose.capture("update-available-light") }
        assertTrue(
            "a missing notes link must not draw a dead button",
            compose.onAllNodes(hasText("What changed")).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "the download is still the thing to press",
            compose.onAllNodesWithTag("update-download").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("update-later").performClick()
        assertEquals(1, dismissed)
    }

    /**
     * The settings line for when the last check ran, which is the only place the app says how
     * often it asks GitHub.
     */
    @Test fun theLastCheckIsReadTheWayAPersonReadsADate() {
        // Nothing checked yet, and a check today: a date a person has to work out from a raw
        // millisecond count is not an answer.
        assertEquals("Not checked yet", lastCheckedLabel(0L))
        val today = java.time.LocalDate.now().atTime(9, 30)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(
            "today is not read as a date: ${lastCheckedLabel(today)}",
            lastCheckedLabel(today).startsWith("Last checked today at 09:30"),
        )
        val weekAgo = java.time.LocalDate.now().minusDays(7).atTime(9, 30)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(
            "an older check is a date: ${lastCheckedLabel(weekAgo)}",
            lastCheckedLabel(weekAgo).startsWith("Last checked ") && !lastCheckedLabel(weekAgo).contains("today"),
        )
    }
}
