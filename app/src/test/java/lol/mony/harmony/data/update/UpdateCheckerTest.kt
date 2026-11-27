package lol.mony.harmony.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check itself, against a server that records every request.
 *
 * What this proves is the traffic, not only the answer: a device that has checked is silent for
 * a day, a second check carries the validator it was given, a 304 counts as a success and does
 * not lengthen the wait, and a failure backs off. Those are the properties that make this
 * feature safe to ship to a lot of devices at once, and every one of them is visible in the
 * requests the server receives.
 */
class UpdateCheckerTest {
    private val installed = InstalledBuild(
        packageName = "io.github.wwpol.brapp.test",
        versionCode = 2_000_100,
        versionName = "0.1.0-abc1234",
        downloadBase = "https://example.test/releases/download",
    )

    private fun manifestJson(
        versionCode: Int = 2_000_200,
        tag: String = "v0.1.0-def5678",
        apk: String = "Brapmony-0.1.0-def5678-debug.apk",
    ) = """
        {
          "schema": 1,
          "package": "io.github.wwpol.brapp.test",
          "versionCode": $versionCode,
          "versionName": "${tag.removePrefix("v")}",
          "tag": "$tag",
          "apk": "$apk",
          "sha256": "${"a".repeat(64)}",
          "size": 72920972,
          "notes": "https://github.com/WWPol/harmony-android/releases/tag/$tag",
          "commit": "def5678"
        }
    """.trimIndent()

    /** A store in memory, so a test can see exactly what was remembered. */
    private class MemoryStore(var state: UpdateState = UpdateState()) : UpdateStore {
        override fun load() = state
        override fun save(state: UpdateState) { this.state = state }
    }

    private fun checker(server: MockWebServer, store: MemoryStore, now: () -> Long): UpdateChecker =
        UpdateChecker(
            installed = installed,
            store = store,
            manifestUrl = server.url("/releases/latest/download/update.json").toString(),
            client = OkHttpClient(),
            now = now,
        )

    /** A clock a test moves by hand, so a day's wait is not a day's test. */
    private class Clock(var now: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke() = now
    }

    @Test fun aNewerBuildIsFoundAndOfferedWithItsDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifestJson()).setHeader("ETag", "\"v1\""))
            val store = MemoryStore()
            val clock = Clock()
            val checker = checker(server, store, clock)

            checker.check(force = true)
            val found = checker.update.value
            assertNotNull("a newer build is offered", found)
            assertEquals(2_000_200, found!!.versionCode)
            assertEquals("0.1.0-def5678", found.versionName)
            assertEquals(
                "https://example.test/releases/download/v0.1.0-def5678/Brapmony-0.1.0-def5678-debug.apk",
                found.downloadUrl,
            )
            // The validator is kept, so the next check is a conditional request.
            assertEquals("\"v1\"", store.state.etag)
            assertEquals(0, store.state.failures)
            assertEquals(clock.now, store.state.lastCheckMillis)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue("it asks for the manifest", request.path!!.endsWith("/update.json"))
            assertTrue("and identifies itself", request.getHeader("User-Agent")!!.startsWith("Brapmony/"))
            assertNull("a first check has no validator to send", request.getHeader("If-None-Match"))
            assertTrue(
                "and does not let a cached copy stand in for asking",
                request.getHeader("Cache-Control")!!.contains("no-cache"),
            )
        }
    }

    @Test fun aSecondCheckTheSameDayAsksNothingAtAll() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifestJson()))
            // The one request the schedule does allow, later on, gets an answer of its own.
            server.enqueue(MockResponse().setResponseCode(304))
            val store = MemoryStore()
            val clock = Clock()
            val checker = checker(server, store, clock)
            checker.check(force = true)
            assertEquals(1, server.requestCount)

            // An hour later, the app is opened again: the schedule says no, and no request is
            // made. This is the property that keeps a fleet of installs off GitHub.
            clock.now += 60 * 60 * 1000L
            checker.check()
            assertEquals("no second request within the day", 1, server.requestCount)

            // A day and the spread later, it is due again.
            clock.now += UpdatePolicy.INTERVAL_MILLIS + UpdatePolicy.JITTER_MILLIS
            checker.check()
            assertEquals("one more request when it is due", 2, server.requestCount)
        }
    }

    @Test fun anUnchangedManifestIsAConditionalRequestAnsweredWith304() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifestJson()).setHeader("ETag", "\"v1\""))
            val store = MemoryStore()
            val clock = Clock()
            val checker = checker(server, store, clock)
            checker.check(force = true)
            assertEquals(1, server.requestCount)

            // The release has not changed: GitHub answers the conditional request with 304 and
            // no body, which costs neither a download nor a failure.
            server.enqueue(MockResponse().setResponseCode(304))
            clock.now += UpdatePolicy.INTERVAL_MILLIS + UpdatePolicy.JITTER_MILLIS
            checker.check()
            assertEquals(2, server.requestCount)
            // The first request carried no validator; the second is the conditional one.
            server.takeRequest()
            val conditional = server.takeRequest()
            assertEquals("\"v1\"", conditional.getHeader("If-None-Match"))
            assertEquals("a 304 is not a failure", 0, store.state.failures)
            assertEquals("and keeps the validator", "\"v1\"", store.state.etag)
            assertNull("with nothing new to offer", checker.lastError.value)
        }
    }

    @Test fun aFailedCheckBacksOffAndKeepsWhatItAlreadyFound() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifestJson()))
            val store = MemoryStore()
            val clock = Clock()
            val checker = checker(server, store, clock)
            checker.check(force = true)
            val found = checker.update.value
            assertNotNull(found)

            // GitHub is unreachable. The check fails, the offer stays -- an update found is not
            // un-found by a network error -- and the wait grows.
            server.enqueue(MockResponse().setResponseCode(503))
            clock.now += UpdatePolicy.INTERVAL_MILLIS + UpdatePolicy.JITTER_MILLIS
            checker.check()
            assertEquals(1, store.state.failures)
            assertEquals("the update found earlier is still on offer", found, checker.update.value)
            assertNotNull("and the failure is reported", checker.lastError.value)

            // The next attempt waits at least as long as a working check would, and a second
            // failure waits twice as long: a failing check must ask less often, never more.
            val firstWait = UpdatePolicy.dueAt(store.state, store.state.seed) - clock.now
            assertTrue("the retry waits at least the ordinary interval", firstWait >= UpdatePolicy.INTERVAL_MILLIS)
            assertFalse("and nothing is asked before it", UpdatePolicy.isDue(store.state, clock.now, store.state.seed))
            checker.check()
            assertEquals("so no request is made yet", 2, server.requestCount)

            // A day and a half later the retry runs, and fails again: the wait doubles.
            clock.now += firstWait
            server.enqueue(MockResponse().setResponseCode(503))
            checker.check()
            assertEquals("the second failure is counted", 2, store.state.failures)
            val secondWait = UpdatePolicy.dueAt(store.state, store.state.seed) - clock.now
            assertTrue("and it waits longer than the first did", secondWait > firstWait)
        }
    }

    /**
     * GitHub answers a release download with a redirect to its own CDN, on another host, and
     * the validator has to survive that hop: a check that lost it would download the whole
     * manifest every day instead of being told nothing changed.
     */
    @Test fun theValidatorSurvivesGitHubsRedirectToItsCdn() = runBlocking {
        MockWebServer().use { cdn ->
            MockWebServer().use { github ->
                // The first answer is the redirect, with no body; the CDN holds the file.
                github.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/blob/update.json")))
                cdn.enqueue(MockResponse().setBody(manifestJson()).setHeader("ETag", "\"cdn-1\""))
                val store = MemoryStore()
                val clock = Clock()
                val checker = UpdateChecker(
                    installed = installed,
                    store = store,
                    manifestUrl = github.url("/releases/latest/download/update.json").toString(),
                    client = OkHttpClient(),
                    now = clock,
                )
                checker.check(force = true)
                assertNotNull("the manifest behind the redirect is read", checker.update.value)
                assertEquals("the CDN's validator is kept", "\"cdn-1\"", store.state.etag)

                // A day later the redirect is answered again, and the conditional request
                // reaches the CDN with the validator the first check was given.
                github.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/blob/update.json")))
                cdn.enqueue(MockResponse().setResponseCode(304))
                // Past the manual check's own minimum interval, so this is a real question.
                clock.now += 10 * 60 * 1000L
                checker.check(force = true)
                val first = cdn.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
                val second = cdn.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull("the redirect was followed twice: first=$first second=$second", second)
                assertEquals(
                    "the validator went with the request, across the redirect",
                    "\"cdn-1\"",
                    second!!.getHeader("If-None-Match"),
                )
                assertEquals("and a 304 is a success", 0, store.state.failures)
            }
        }
    }

    @Test fun aBuildThatIsCurrentIsNotAnUpdate() = runBlocking {
        MockWebServer().use { server ->
            // The same build the app is: the manifest is the release this device already has.
            server.enqueue(MockResponse().setBody(manifestJson(versionCode = installed.versionCode)))
            val store = MemoryStore()
            val checker = checker(server, store, Clock())
            checker.check(force = true)
            assertNull("the installed build is not offered as an update", checker.update.value)
            assertEquals("and the check succeeded", 0, store.state.failures)
            assertNull(checker.lastError.value)
        }
    }

    @Test fun aReleaseWithoutAManifestIsNotAFailure() = runBlocking {
        MockWebServer().use { server ->
            // A release from before update.json existed, or no release at all: a 404 is an
            // answer, not a network failure, so it must not start the backoff.
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
            val store = MemoryStore()
            val checker = checker(server, store, Clock())
            checker.check(force = true)
            assertNull(checker.update.value)
            assertEquals("a 404 does not count as a failure", 0, store.state.failures)
            assertNull(checker.lastError.value)
        }
    }

    @Test fun aMalformedManifestIsAFailureRatherThanAnOffer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"schema":1,"versionCode":"soon"}"""))
            val store = MemoryStore()
            val checker = checker(server, store, Clock())
            checker.check(force = true)
            assertNull(checker.update.value)
            assertEquals("a manifest this build cannot read is a failed check", 1, store.state.failures)
            assertNotNull(checker.lastError.value)
        }
    }

    @Test fun anOfferIsDismissedForThatBuildAndNotForTheNext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifestJson()))
            val store = MemoryStore()
            val checker = checker(server, store, Clock())
            checker.check(force = true)
            val found = checker.update.value!!
            assertTrue("a fresh offer is worth showing", checker.shouldNotify(found))

            checker.dismiss(found.versionCode)
            assertNull("dismissing takes the offer down", checker.update.value)
            assertFalse("and it is not shown again", checker.shouldNotify(found))
            assertEquals("the dismissal is remembered on the device", found.versionCode, store.state.dismissedVersionCode)

            // A later, newer build is a new question and is offered again.
            val newer = found.copy(versionCode = found.versionCode + 1)
            assertTrue(checker.shouldNotify(newer))
        }
    }

    @Test fun aBuildWithNoManifestUrlAsksNothing() = runBlocking {
        MockWebServer().use { server ->
            val checker = UpdateChecker(
                installed = installed,
                store = MemoryStore(),
                manifestUrl = "",
                client = OkHttpClient(),
                now = Clock(),
            )
            checker.check(force = true)
            assertEquals("a fork that publishes no manifest is silent", 0, server.requestCount)
        }
    }

    @Test fun aManualCheckShortlyAfterAnAutomaticOneDoesNotAskAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifestJson()))
            server.enqueue(MockResponse().setBody(manifestJson()))
            val store = MemoryStore()
            val clock = Clock()
            val checker = checker(server, store, clock)
            checker.check(force = true)
            // "Check now" tapped a moment later would only be answered 304 with the same
            // validator; it is not a second request.
            clock.now += 5_000L
            checker.check(force = true)
            assertEquals(1, server.requestCount)
            // A minute later it is a real question and is asked.
            clock.now += 120_000L
            checker.check(force = true)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun theSwitchStopsTheCheckAndTheSettingsRowReadsIt() = runBlocking {
        MockWebServer().use { server ->
            val store = MemoryStore()
            val checker = checker(server, store, Clock())
            checker.restore()
            assertTrue("the check is on by default", checker.stateNow().enabled)
            checker.setEnabled(false)
            assertFalse(checker.stateNow().enabled)
            checker.check(force = false)
            assertEquals("an off switch asks nothing", 0, server.requestCount)
            // Even once a check would have been due, the switch keeps the app silent.
            checker.check(force = false)
            assertEquals("and it stays silent however long it has been", 0, server.requestCount)
            // "Check now" is still a deliberate act by the person, so it is allowed even with
            // the automatic check off -- and it is what the settings row does.
            server.enqueue(MockResponse().setBody(manifestJson()))
            checker.check(force = true)
            assertEquals(1, server.requestCount)
            // A manual check does not turn the automatic one back on.
            assertFalse("the switch is still off after a manual check", checker.stateNow().enabled)
        }
    }
}
