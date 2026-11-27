package lol.mony.harmony.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lol.mony.harmony.BuildConfig
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.components.*
import lol.mony.harmony.ui.screens.instance.InstancePickerScreen
import lol.mony.harmony.ui.screens.you.UpdateAvailableDialog
import lol.mony.harmony.ui.screens.signin.AuthFormScreen
import lol.mony.harmony.ui.theme.*

@Composable
fun HarmonyRoot(state: AppState) {
    val auth by state.auth.state.collectAsState()
    val scope = rememberCoroutineScope()
    // The application holds the notification coordinator; a composition local would need the
    // activity, which the restore path does not have.
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? lol.mony.harmony.HarmonyApp
    var startupError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(VisualThemeSettings()) }
    // Wallpaper colours are a device preference, so they are read from this device's store and
    // never written into the account's shared appearance object.
    var dynamicColor by remember { mutableStateOf(false) }
    var instanceName by remember { mutableStateOf<String?>(null) }
    fun recover(error: Throwable) { startupError = error.message ?: "Could not load this account." }

    LaunchedEffect(retry) {
        if (state.auth.state.value == AuthState.Loading || retry > 0) {
            try {
                startupError = null
                state.auth.restore(Endpoint(BuildConfig.DEFAULT_INSTANCE_URL, BuildConfig.DEFAULT_INSTANCE_ANON_KEY)
                    .takeIf { it.baseUrl.isNotBlank() && it.anonKey.isNotBlank() })
            } catch (error: Exception) { recover(error) }
        }
    }
    val signedIn = auth as? AuthState.SignedIn
    LaunchedEffect(signedIn?.profile?.id) {
        val session = signedIn ?: return@LaunchedEffect
        val profile = session.profile ?: return@LaunchedEffect
        try {
            state.onSignedIn(session)
            // Delivery starts with the session and stops with it, so the tray can never show
            // one account's activity while another is signed in.
            app?.notifications?.start()
            val token = state.auth.freshToken() ?: error("Your session expired. Sign in again.")
            val info = state.servers.instanceInfo(session.endpoint, token)
            instanceName = info.name
            val device = state.sessionStore.appearance(profile.id)
            appearance = if (state.appearanceIsDeviceOnly() && device != null) VisualThemeSettings.fromJson(device)
                else VisualThemeSettings.fromJson(profile.appearanceSettings)
            state.rememberDeviceAppearance(appearance)
            dynamicColor = runCatching { state.sessionStore.dynamicColorFlow.first() }.getOrDefault(false)
        } catch (error: Exception) { recover(error) }
    }
    HarmonyTheme(appearance, dynamicColor = dynamicColor) {
        val activity = LocalActivity.current
        val background = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            activity?.window?.let { window ->
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = appearance.isLight
                bars.isAppearanceLightNavigationBars = appearance.isLight
                @Suppress("DEPRECATION")
                window.navigationBarColor = background
            }
        }
        ProvideAppState(state) {
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                color = MaterialTheme.colorScheme.background) {
                // A newer build, offered once per run of the app. It is over every screen
                // rather than inside one, because it is about the app and not about what is on
                // it: a person who never opens the account screen should still learn that their
                // build is a release behind. The checker's own state decides whether it shows,
                // and "Not now" is remembered for that build.
                UpdateNotice(state.container.updates)
                when {
                    startupError != null -> ErrorState(startupError!!) { startupError = null; retry++ }
                    showPicker || auth == AuthState.NeedsInstance -> InstancePickerScreen(
                        initialUrl = state.auth.endpoint()?.origin,
                        onUse = { endpoint, name -> scope.launch {
                            try {
                                state.auth.chooseInstance(endpoint, name)
                                instanceName = name
                                showPicker = false
                            } catch (error: Exception) { recover(error) }
                        } },
                        onCancel = if (auth == AuthState.NeedsInstance) null else ({ showPicker = false }),
                    )
                    auth is AuthState.Loading -> LoadingState("Connecting to your instance")
                    auth is AuthState.SignedOut -> AuthFormScreen(
                        instanceName, (auth as AuthState.SignedOut).endpoint.origin,
                        onSignIn = state.auth::signIn, onRegister = state.auth::signUp,
                        onForgotPassword = state.auth::requestPasswordReset, onChangeInstance = { showPicker = true },
                    )
                    signedIn != null && signedIn.profile == null -> ProfileSetup(state, signedIn)
                    signedIn != null -> SignedInShell(state, appearance,
                        dynamicColor = dynamicColor,
                        onDynamicColorChange = { wanted ->
                            dynamicColor = wanted
                            scope.launch {
                                runCatching { state.sessionStore.setDynamicColor(wanted) }
                            }
                        },
                        onAppearanceChange = { updated, saveAccount ->
                            appearance = updated
                            scope.launch {
                                try {
                                    state.saveDeviceAppearance(updated)
                                    if (saveAccount) state.persistAccountAppearance(updated)
                                } catch (error: Exception) { recover(error) }
                            }
                        },
                        onSignOut = { scope.launch {
                            app?.notifications?.stop()
                            state.signOut()
                        } },
                    )
                }
            }
        }
    }
}

/**
 * The update notice: the dialog, shown once per run for the newest build not already dismissed.
 *
 * [noticed] is what this run has already put in front of the person -- the version code, or a
 * sentinel before the first offer -- and it is remembered for the composition: a rotation is not
 * a second offer, and neither is coming back from another screen. It is a value rather than a
 * flag because the check can finish after the app is open, and a *newer* release found later in
 * the same run is a new question worth asking once. "Not now" is remembered for the build
 * itself, in the checker's own store, so it is not asked about again on the next launch either.
 */
@Composable
private fun UpdateNotice(checker: lol.mony.harmony.data.update.UpdateChecker) {
    val update by checker.update.collectAsState()
    val uriHandler = LocalUriHandler.current
    // 0 is "nothing offered yet"; a version code is what has been shown.
    var noticed by remember { mutableIntStateOf(0) }
    val offered = update ?: return
    // `noticed == 0` is the first offer of the run; after that only a newer build is shown.
    val unseen = noticed == 0 || offered.versionCode > noticed
    if (!unseen || !checker.shouldNotify(offered)) return
    // Recorded here rather than in a LaunchedEffect so the same build cannot be drawn twice by
    // two recompositions: this is a decision about what is on screen, made while composing it.
    noticed = offered.versionCode
    UpdateAvailableDialog(
        update = offered,
        onDownload = { uriHandler.openUri(offered.downloadUrl) },
        onNotes = offered.notesUrl?.let { url -> { uriHandler.openUri(url) } },
        onDismiss = {
            checker.dismiss(offered.versionCode)
            noticed = offered.versionCode
        },
    )
}

@Composable
private fun ProfileSetup(state: AppState, session: AuthState.SignedIn) {
    var username by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Finish your profile", style = MaterialTheme.typography.headlineMedium)
        Text("Choose how people see you on this instance.", Modifier.padding(vertical = 12.dp))
        OutlinedTextField(username, { username = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(24) }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error != null) ErrorBanner(error!!) { error = null }
        Button(enabled = !busy && username.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try {
                    val token = state.auth.freshToken() ?: error("Sign in again.")
                    state.profiles.createSelf(session.endpoint, token, username, name)
                    state.auth.refreshProfile()
                } catch (failure: Exception) { error = failure.message } finally { busy = false }
            }
        }) { Text(if (busy) "Saving…" else "Save profile") }
        TextButton(onClick = { scope.launch { state.signOut() } }) { Text("Sign out") }
    }
}
