package lol.mony.harmony.ui.screens.you

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import lol.mony.harmony.BuildConfig
import lol.mony.harmony.data.model.AccountProfile
import lol.mony.harmony.data.model.AccountPrivacy
import lol.mony.harmony.data.repo.AccountRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar
import lol.mony.harmony.ui.components.HarmonyDivider
import lol.mony.harmony.ui.components.ReadingColumn
import lol.mony.harmony.ui.components.SectionHeader
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.VisualThemeSettings

/**
 * You: one page of settings, not five tabs.
 *
 * The screen this replaces put Profile, Appearance, Privacy, Notifications and Security in a
 * `ScrollableTabRow` above a scrolling column. That is a tab row doing a settings list's job: on a
 * phone the five titles do not fit, so the last two are off-screen with nothing to say they exist;
 * a person cannot see what there is to change without swiping through it; and every tab's content
 * began with its own heading, so the page had two titles.
 *
 * Here the identity is the top of the page -- a face, a name, a handle and a bio, at heading size,
 * because who you are signed in as is the first thing this screen should answer -- and the
 * settings are a list of rows, each with an icon, a title, a line saying what is inside, and a
 * chevron. A row opens one section; back returns to the list. That is the shape a person already
 * knows from Android's own settings, and it scales: a section that grows taller does not push
 * another one off the screen.
 *
 * Every panel composable is unchanged, so this is an information-architecture change and not a
 * rewrite of the settings themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouScreen(
    state: AppState,
    appearance: VisualThemeSettings,
    onAppearanceChange: (VisualThemeSettings, Boolean) -> Unit,
    onSignOut: () -> Unit,
    /** Whether the device's wallpaper colours are in use instead of the account's own look. */
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
) {
    val auth by state.auth.state.collectAsState()
    val signedIn = auth as? AuthState.SignedIn
    val repository = remember(state.container.rest) { AccountRepository(state.container.rest) }
    var profile by remember(signedIn?.user?.id, signedIn?.endpoint?.baseUrl) {
        mutableStateOf<AccountProfile?>(null)
    }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var drive by rememberSaveable { mutableStateOf(false) }

    if (drive) {
        BackHandler { drive = false }
        lol.mony.harmony.ui.screens.drive.DriveScreen(state, onBack = { drive = false })
        return
    }

    LaunchedEffect(signedIn?.user?.id, signedIn?.endpoint?.baseUrl, reload) {
        loading = true
        loadError = null
        try {
            val userId = signedIn?.user?.id ?: error("Sign in to manage your account.")
            profile = accountRequest(state) { endpoint, token ->
                repository.profile(endpoint, token, userId).getOrThrow()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "Your account could not be loaded."
        } finally {
            loading = false
        }
    }

    // Back leaves an open section before it leaves the destination, which is what a person
    // expects after tapping a row in a settings list.
    BackHandler(enabled = open != null) { open = null }

    val section = open?.let { name -> YouSection.entries.firstOrNull { it.name == name } }
    val name = profile?.displayName ?: signedIn?.profile?.shownName ?: "You"
    val handle = profile?.username ?: signedIn?.profile?.username

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        section?.title ?: "You",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (section != null) {
                        IconButton(onClick = { open = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (section == null) {
                SettingsList(
                    state = state,
                    profile = profile,
                    name = name,
                    handle = handle,
                    avatarUrl = profile?.avatarUrl?.let { url ->
                        if (url.startsWith('/')) signedIn?.endpoint?.baseUrl + url else url
                    },
                    loading = loading,
                    loadError = loadError,
                    appearance = appearance,
                    onRetry = { reload++ },
                    onOpen = { open = it.name },
                    onFiles = { drive = true },
                    onSignOut = { confirmSignOut = true },
                    onSource = { uriHandler.openUri("https://github.com/WWPOL/harmony-android") },
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .widthIn(max = HarmonySize.readingMaxWidth)
                        .padding(horizontal = HarmonySize.gutterLarge, vertical = 8.dp)
                        .testTag("you-section"),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    val loaded = profile
                    if (loaded == null) {
                        AccountStatus(loadError, null)
                        if (loadError != null) {
                            androidx.compose.material3.TextButton(onClick = { reload++ }) {
                                Text("Retry loading account")
                            }
                        }
                    } else when (section) {
                        YouSection.Profile -> AccountProfilePanel(state, repository, loaded) { saved ->
                            profile = saved
                            signedIn?.profile?.let { old ->
                                state.rememberPeople(listOf(old.copy(
                                    displayName = saved.displayName, bio = saved.bio,
                                    pronouns = saved.pronouns, avatarUrl = saved.avatarUrl,
                                    bannerUrl = saved.bannerUrl,
                                )))
                            }
                        }
                        YouSection.Appearance -> AccountAppearancePanel(
                            state, repository, loaded, appearance, onAppearanceChange,
                            dynamicColor = dynamicColor, onDynamicColorChange = onDynamicColorChange,
                        )
                        YouSection.Privacy -> AccountPrivacyPanel(state, repository, loaded) { profile = it }
                        YouSection.Notifications -> AccountNotificationsPanel(state, repository, loaded.id)
                        YouSection.Security -> AccountSecurityPanel(state)
                    }
                }
            }
        }
    }

    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of this device?") },
            text = {
                Text(
                    "Other signed-in devices will stay connected. " +
                        "Your saved account settings will not be removed.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { confirmSignOut = false; onSignOut() },
                ) { Text("Sign out") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmSignOut = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * The settings a person can change, in the order they are worth reading.
 *
 * Each one carries the line the list shows under its title, so a row says what is inside before it
 * is opened. That is the whole reason this replaced the tab row: a tab named "Privacy" tells you
 * nothing about what you can do there, and a person should not have to open five of them to find
 * out what the screen can do.
 */
private enum class YouSection(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val tag: String,
) {
    Profile("Profile", "Name, bio, pronouns and avatar", Icons.Outlined.PersonOutline, "you-profile"),
    Appearance("Appearance", "Theme, accent, text size and motion", Icons.Outlined.Palette, "you-appearance"),
    Privacy("Privacy", "Who can follow you and message you", Icons.Outlined.Lock, "you-privacy"),
    Notifications("Notifications", "What this device tells you about", Icons.Outlined.Notifications, "you-notifications"),
    Security("Security", "Password, sessions and devices", Icons.Outlined.Badge, "you-security"),
}

/**
 * The identity at the top and the settings under it.
 *
 * The identity is deliberately large: it is the one thing this screen knows that no other screen
 * does, and the old version drew it at `titleMedium` in a 48dp row beside two text buttons, which
 * is the size of a list item rather than the size of the answer to "who am I signed in as".
 */
@Composable
private fun SettingsList(
    state: AppState,
    profile: AccountProfile?,
    name: String,
    handle: String?,
    avatarUrl: String?,
    loading: Boolean,
    loadError: String?,
    appearance: VisualThemeSettings,
    onRetry: () -> Unit,
    onOpen: (YouSection) -> Unit,
    onFiles: () -> Unit,
    onSignOut: () -> Unit,
    onSource: () -> Unit,
) {
    // A settings row is a label and a chevron; at 1000dp they would sit a thousand pixels
    // apart, which is a row a person reads by turning their head. `ReadingColumn` is the app's
    // one statement of that cap.
    ReadingColumn {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("you-settings"),
        ) {
            Identity(name, handle, avatarUrl, profile?.bio, profile?.pronouns, loading, loadError, onRetry)

            SectionHeader("Account")
            YouSection.entries.forEach { item ->
                SettingsRow(
                    title = item.title,
                    summary = item.summary,
                    icon = item.icon,
                    tag = item.tag,
                    onClick = { onOpen(item) },
                )
            }

            SectionHeader("On this device")
            SettingsRow(
                title = "Files",
                summary = "Your Drive, without leaving the account screen",
                icon = Icons.Outlined.FolderOpen,
                tag = "you-files",
                onClick = onFiles,
            )
            SettingsRow(
                title = "Appearance on this device",
                summary = "${appearance.theme.replaceFirstChar { it.uppercase() }} · " +
                    "${appearance.fontSize}sp text",
                icon = Icons.Outlined.Palette,
                tag = "you-device-look",
                onClick = { onOpen(YouSection.Appearance) },
            )

            SectionHeader("About")
            // The version this build reports, read from the APK's own manifest rather than
            // written here: a branch build says `0.1.0-<sha7>-debug`, a release says
            // `0.1.0`. It is the number to quote in a bug report, and the only place a
            // person can read it without unpacking the file (CONTRIBUTING.md says so).
            SettingsRow(
                title = "Version",
                summary = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                icon = Icons.Outlined.Info,
                tag = "you-version",
                onClick = {},
            )
            SettingsRow(
                title = "Source and licence",
                summary = "GPLv3 · a native client for Harmony instances",
                icon = Icons.Outlined.Badge,
                tag = "you-source",
                onClick = onSource,
            )
            SettingsRow(
                title = "Sign out",
                summary = "Leave this account on this device",
                icon = Icons.AutoMirrored.Outlined.Logout,
                tag = "you-signout",
                onClick = onSignOut,
                destructive = true,
            )
            Spacer(Modifier.height(HarmonySize.gutterLarge))
        }
        }
}

@Composable
private fun Identity(
    name: String,
    handle: String?,
    avatarUrl: String?,
    bio: String?,
    pronouns: String?,
    loading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = HarmonySize.gutterLarge, end = HarmonySize.gutterLarge, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(name = name, url = avatarUrl, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    buildString {
                        append("@").append(handle.orEmpty())
                        if (!pronouns.isNullOrBlank()) append(" · ").append(pronouns)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!bio.isNullOrBlank()) {
            Text(
                bio,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            loading && bio == null -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading your account", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            loadError != null -> {
                AccountStatus(loadError, null)
                androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/**
 * One settings row: icon, title, a line of what is inside, and a chevron.
 *
 * The whole row is the target and it is at least 56dp tall, so it is reachable with a thumb and
 * readable at a glance. The chevron is what says "this opens something" -- a row that navigates
 * and looks like a row that does not is the small dishonesty that makes a settings screen
 * confusing.
 */
@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = HarmonySize.gutterLarge, end = HarmonySize.gutter, top = 14.dp, bottom = 14.dp)
                .semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (destructive) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (destructive) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    HarmonyDivider(Modifier.padding(start = HarmonySize.gutterLarge + 56.dp))
}
