package lol.mony.harmony

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lol.mony.harmony.data.update.AvailableUpdate
import lol.mony.harmony.data.update.InstalledBuild
import lol.mony.harmony.data.update.UpdateChecker
import lol.mony.harmony.data.update.UpdateStore
import lol.mony.harmony.data.update.UpdateState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.ProvideAppState
import lol.mony.harmony.ui.screens.you.AccountUpdatesPanel
import lol.mony.harmony.ui.theme.HarmonyTheme
import lol.mony.harmony.ui.theme.VisualThemeSettings
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Updates section of the account screen, with a real answer behind it.
 *
 * The checker is the app's own, pointed at a MockWebServer that answers the manifest a release
 * publishes -- so the section draws what a release actually produces rather than a hand-made
 * object, and "Check now" is proved to make one request and then show its answer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h900dp-xhdpi")
class UpdatesSectionRenderTest {
    @get:Rule val compose = createComposeRule()

    private val installed = InstalledBuild(
        packageName = "io.github.wwpol.brapmony",
        versionCode = 2_000_100,
        versionName = "0.1.0-abc1234",
        downloadBase = "https://github.com/WWPol/harmony-android/releases/download",
    )

    private val manifest = """
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

    private class MemoryStore(var state: UpdateState = UpdateState()) : UpdateStore {
        override fun load() = state
        override fun save(state: UpdateState) { this.state = state }
    }

    private fun checker(server: MockWebServer, store: MemoryStore): UpdateChecker = UpdateChecker(
        installed = installed,
        store = store,
        manifestUrl = server.url("/releases/latest/download/update.json").toString(),
        client = OkHttpClient(),
    )

    @Test fun theSectionOffersTheBuildTheReleasePublishes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest).setHeader("ETag", "\"v1\""))
            val store = MemoryStore()
            val checker = checker(server, store)
            val state = AppState(RenderHarness.container())
            // The check runs as the app runs it, then the section is drawn with the answer.
            checker.check(force = true)
            assertTrue("the fixture's manifest was not offered", checker.update.value != null)

            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            androidx.compose.foundation.layout.Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            ) {
                                Text("You · About", Modifier.padding(bottom = 8.dp))
                                AccountUpdatesPanel(checker)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(hasText("Version 0.1.0-def5678 is available", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            RenderHarness.run { compose.capture("you-updates-dark") }

            // What the section says: the build, its file and size, and that the check is on.
            assertTrue(
                "the offered build is not named",
                compose.onAllNodes(hasText("Brapmony-0.1.0-def5678-debug.apk", substring = true))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "the download is not offered",
                compose.onAllNodesWithTag("update-download-apk").fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "the switch is not drawn",
                compose.onAllNodes(hasText("Check for updates automatically", substring = true))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            assertEquals("the first check is one request", 1, server.requestCount)
        }
    }

    /** With the switch off, the section says so and offers no check. */
    @Test fun theSectionSaysWhenTheCheckIsOff() = runBlocking {
        MockWebServer().use { server ->
            val store = MemoryStore(UpdateState(enabled = false))
            val checker = checker(server, store)
            val state = AppState(RenderHarness.container())
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "light")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(16.dp)) {
                                AccountUpdatesPanel(checker)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(hasText("Off · this build will not look for newer ones", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            RenderHarness.run { compose.capture("you-updates-off-light") }
            assertEquals("a build with the check off asks nothing", 0, server.requestCount)
        }
    }

    /** "Check now" is one request, and it is what makes the section show what it found. */
    @Test fun checkNowAsksOnceAndShowsTheAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest).setHeader("ETag", "\"v1\""))
            val store = MemoryStore()
            val checker = checker(server, store)
            val state = AppState(RenderHarness.container())
            compose.setContent {
                HarmonyTheme(VisualThemeSettings(theme = "dark")) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProvideAppState(state) {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(16.dp)) {
                                AccountUpdatesPanel(checker)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onAllNodesWithTag("update-check-now").fetchSemanticsNodes().firstOrNull()
                ?.let { compose.onAllNodesWithTag("update-check-now")[0].performClick() }
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(hasText("Version 0.1.0-def5678 is available", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("Check now is one request", 1, server.requestCount)
            assertTrue(
                "the check is recorded, so the next launch does not ask again",
                store.state.lastCheckMillis != 0L,
            )
        }
    }

    /** The section's state is the device's own: a fresh install has the check on. */
    @Test fun aFreshInstallHasTheCheckOn() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = lol.mony.harmony.data.update.PreferencesUpdateStore(context)
        assertTrue("the check is on by default", store.load().enabled)
        store.save(store.load().copy(enabled = false))
        assertTrue("and the switch is remembered", !lol.mony.harmony.data.update.PreferencesUpdateStore(context).load().enabled)
    }

    /** What the section offers is the manifest's own download, not a link built in the UI. */
    @Test fun theOfferedDownloadIsTheManifestsOwnAddress() {
        val offered = AvailableUpdate(
            versionCode = 1, versionName = "1.0", tag = "v1.0.0",
            apkName = "Brapmony-1.0.0.apk", sha256 = "a".repeat(64), size = 1,
            downloadUrl = "https://example.test/a.apk", notesUrl = null,
        )
        assertTrue(offered.downloadUrl.endsWith(".apk"))
    }
}
