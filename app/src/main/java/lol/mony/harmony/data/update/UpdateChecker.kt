package lol.mony.harmony.data.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Asks the release's own `update.json` whether there is a newer build, and says what it found.
 *
 * The whole point of this class is to be **uninteresting to GitHub**: it asks a static file on a
 * release, at most once a day per install, with the validator GitHub gave it last time, and it
 * stops asking for longer each time it fails. See [UpdatePolicy] for the schedule and
 * [UpdateManifest] for why the file rather than the API.
 *
 * What it does not do is download or install anything. A newer build is offered as a link to the
 * APK on the release, which the person taps: Android's browser downloads it, and tapping the
 * downloaded file installs it over this one. That is the whole of FairEmail's behaviour and it
 * needs no `REQUEST_INSTALL_PACKAGES`, no `FileProvider`, and no download service of our own.
 *
 * [update] is what the UI draws: the newer build while one is on offer, and null when there is
 * nothing to say. A failed check leaves it as it was -- an update that was found is still there
 * -- and only changes when the next successful check replaces it.
 */
class UpdateChecker(
    private val installed: InstalledBuild,
    private val store: UpdateStore,
    private val manifestUrl: String,
    client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Its own client, with its own timeouts.
     *
     * The app's client waits 20 s to connect and 30 s to read, which is right for a message send
     * and far too patient for a check nobody is waiting for. A check that hangs must give up
     * quickly: it is holding one of the person's sockets to ask a question that can wait until
     * tomorrow.
     */
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _update = MutableStateFlow<AvailableUpdate?>(null)

    /** The newer build on offer, or null. */
    val update: StateFlow<AvailableUpdate?> = _update.asStateFlow()

    private val _checking = MutableStateFlow(false)

    /** True while a check is in flight, so the settings row can say so. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** The last check's failure, for the settings row; null after a success. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _state = MutableStateFlow<UpdateState?>(null)

    /** What this device remembers, once it has been read. */
    val state: StateFlow<UpdateState?> = _state.asStateFlow()

    /** One check at a time: two would be two requests where one answer is the same. */
    private val mutex = Mutex()

    /**
     * The check the app runs when it is opened, and when it comes back to the foreground.
     *
     * It is silent about the schedule: a launch that is too soon for the next check does nothing
     * at all, which is the common case and the reason this is cheap to call on every resume.
     * [force] is the settings screen's "Check now", which skips the wait but not the conditional
     * request.
     */
    suspend fun check(force: Boolean = false) = mutex.withLock {
        // A build with no manifest URL -- a fork that has not published one -- asks nothing at
        // all, rather than asking an empty address.
        if (manifestUrl.isBlank()) return@withLock
        val current = load().initialised(now(), random)
        _state.value = current
        // The switch turns off the *automatic* check. A check the person asked for is not
        // automatic, so it runs with the switch off -- the settings row offers it for exactly
        // that case: "I turned the checking off, but tell me now".
        if (!force && !UpdatePolicy.isDue(current, now(), current.seed)) return@withLock
        // A manual check a moment after an automatic one is the same request with the same
        // validator; GitHub would answer 304 and nothing would be learnt.
        if (force && current.lastCheckMillis != 0L &&
            now() - current.lastCheckMillis < MANUAL_MIN_INTERVAL_MILLIS
        ) {
            return@withLock
        }
        _checking.value = true
        try {
            when (val answer = fetch(current)) {
                is Answer.Unchanged -> remember(UpdatePolicy.succeeded(current, now()))
                is Answer.Manifest -> {
                    remember(UpdatePolicy.succeeded(current, now(), answer.etag, answer.lastModified))
                    offer(answer.manifest)
                }
                is Answer.NoManifest -> {
                    // The release exists but carries no manifest: an older release, or a publish
                    // that predates it. Nothing to offer, and nothing wrong: the wait resets.
                    remember(UpdatePolicy.succeeded(current, now()))
                    _update.value = null
                }
            }
            _lastError.value = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The app closing is not GitHub failing: nothing is recorded, and the check the next
            // launch runs is the one this one was going to run.
            throw cancelled
        } catch (failure: Exception) {
            remember(UpdatePolicy.failed(current, now()))
            _lastError.value = failure.message ?: "The update check could not reach GitHub."
            // A found update stays on offer: the check failing is not the update going away.
        } finally {
            _checking.value = false
        }
    }

    /** Records that the person said "not now" to [versionCode], so it is not offered again. */
    fun dismiss(versionCode: Int) {
        val current = load()
        remember(current.copy(dismissedVersionCode = versionCode))
        if (_update.value?.versionCode == versionCode) _update.value = null
    }

    /** Whether the notice for [update] should be shown, given what the person has dismissed. */
    fun shouldNotify(update: AvailableUpdate, state: UpdateState = load()): Boolean =
        update.versionCode > state.dismissedVersionCode

    /** Turns the automatic check on or off, which the settings screen owns. */
    fun setEnabled(enabled: Boolean) {
        val current = load().initialised(now(), random)
        remember(current.copy(enabled = enabled))
        if (!enabled) {
            _update.value = null
            _lastError.value = null
        }
    }

    /**
     * The stored state, with an install's own values filled in.
     *
     * Reading it is how the settings row knows whether the check is on before anything has been
     * checked, which is the first thing that screen shows. It deliberately does not write: it is
     * read while composing, and a composition that saves is a composition with a side effect.
     * [restore] and [check] are what persist, and both run before this screen can be opened.
     */
    fun stateNow(): UpdateState = load().initialised(now(), random)

    /** Reads the stored state and publishes what it says, without asking GitHub anything. */
    fun restore() {
        val current = load().initialised(now(), random)
        remember(current)
        // Nothing is offered until a check says so: an offer from the previous process would be
        // a version this build has not seen the manifest for, and it would be re-checked anyway.
        _update.value = null
    }

    private sealed interface Answer {
        /** GitHub answered 304: the manifest this device has is still the newest. */
        data object Unchanged : Answer
        class Manifest(val manifest: UpdateManifest, val etag: String?, val lastModified: String?) : Answer
        /** The manifest is not there: a release from before it existed, or no release at all. */
        data object NoManifest : Answer
    }

    private suspend fun fetch(state: UpdateState): Answer = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            // The app's name and version, so the traffic is identifiable in a log and a request
            // from this app can be told from a browser's. Nothing here identifies a person.
            .header("User-Agent", "Brapmony/${installed.versionName} (Android)")
            .apply {
                // The validator GitHub gave the last time, so an unchanged manifest is a 304
                // with no body rather than a download. `no-cache` says "ask, do not reuse": the
                // check's own schedule is the cache policy, and a stale local copy must not
                // stand in for asking.
                state.etag?.let { header("If-None-Match", it) }
                state.lastModified?.let { header("If-Modified-Since", it) }
                cacheControl(CacheControl.Builder().noCache().build())
            }
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> Answer.Unchanged
                response.code == 404 || response.code == 410 -> Answer.NoManifest
                response.isSuccessful -> {
                    val text = response.body?.string().orEmpty()
                    val manifest = UpdateManifest.parse(text)
                        ?: throw IOException("The update file is not one this app understands.")
                    Answer.Manifest(manifest, response.header("ETag"), response.header("Last-Modified"))
                }
                // A 403 from GitHub's CDN is what a rate limit looks like from here; it is a
                // failed check like any other, and the backoff is the answer to it.
                else -> throw IOException("The update check was answered ${response.code}.")
            }
        }
    }

    /**
     * Offers what [manifest] describes, or nothing when it is not a newer build of this app.
     *
     * Nothing is logged here on purpose: the answer is in [update], [lastError] and the settings
     * screen, and a check that can fail because a log call did is a check whose failure would be
     * reported as the network's.
     */
    private fun offer(manifest: UpdateManifest) {
        val found = manifest.availableUpdate(installed)
        _update.value = found
        if (found != null) {
            remember(load().copy(offeredVersionCode = found.versionCode))
        }
    }

    private fun load(): UpdateState = _state.value ?: runCatching { store.load() }.getOrDefault(UpdateState())

    private fun remember(state: UpdateState) {
        _state.value = state
        runCatching { store.save(state) }
    }

    private companion object {
        /** A manual check closer than this to the last one would only be answered 304. */
        const val MANUAL_MIN_INTERVAL_MILLIS = 60L * 1000
    }
}
