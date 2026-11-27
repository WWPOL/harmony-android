package lol.mony.harmony

import android.content.Context
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.data.api.Http
import lol.mony.harmony.data.api.NetworkMonitor
import lol.mony.harmony.data.api.Realtime
import lol.mony.harmony.data.api.RestApi
import lol.mony.harmony.data.repo.BotRepository
import lol.mony.harmony.data.repo.MessageMediaRepository
import lol.mony.harmony.data.repo.ChatRepository
import lol.mony.harmony.data.repo.ProfileRepository
import lol.mony.harmony.data.repo.ServerRepository
import lol.mony.harmony.data.repo.SettingsRepository
import lol.mony.harmony.data.session.AuthRepository
import lol.mony.harmony.data.session.SessionStore
import lol.mony.harmony.data.update.InstalledBuild
import lol.mony.harmony.data.update.PreferencesUpdateStore
import lol.mony.harmony.data.update.UpdateChecker

/**
 * The app's object graph, built once.
 *
 * A single container rather than a DI framework: the graph is small and explicit, and the tests
 * build the same one with a MockWebServer endpoint by hand.
 */
class AppContainer(context: Context, store: SessionStore = SessionStore(context.applicationContext)) {
    val sessionStore: SessionStore = store
    val rest: RestApi = RestApi(Http.client)
    val authApi: AuthApi = AuthApi(Http.client)

    val profiles: ProfileRepository = ProfileRepository(rest)
    val servers: ServerRepository = ServerRepository(rest)
    val chats: ChatRepository = ChatRepository(rest)
    val media: MessageMediaRepository = MessageMediaRepository(rest)
    val bots: BotRepository = BotRepository(rest)
    val settings: SettingsRepository = SettingsRepository(rest)

    /**
     * The live connection every room shares, and the device's own network changes.
     *
     * Both are built here rather than per screen: one socket at a time is what a phone can keep
     * alive, and the connectivity callback is a process-wide thing that must not be registered
     * and unregistered on every navigation.
     */
    val realtime: Realtime = Realtime(Http.client)
    val network: NetworkMonitor = NetworkMonitor(context.applicationContext)

    val auth: AuthRepository = AuthRepository(sessionStore, authApi, profiles)

    /**
     * The automatic update check.
     *
     * It is built here, once per process, because it owns the device's own memory of when it
     * last asked: two instances would each think a check was due and ask twice. It talks to
     * GitHub and to nothing on the instance, so it exists before anybody signs in.
     */
    val updates: UpdateChecker = UpdateChecker(
        installed = InstalledBuild(
            packageName = BuildConfig.APPLICATION_ID,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            downloadBase = BuildConfig.RELEASE_DOWNLOAD_BASE,
        ),
        store = PreferencesUpdateStore(context.applicationContext),
        manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
        client = Http.client,
    )
}
