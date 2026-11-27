@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lol.mony.harmony.ui.screens.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.launch
import lol.mony.harmony.data.model.Channel
import lol.mony.harmony.data.model.VoiceParticipant
import lol.mony.harmony.data.model.VoiceToken
import lol.mony.harmony.data.repo.VoiceRepository
import lol.mony.harmony.data.session.AuthState
import lol.mony.harmony.data.voice.CallParticipant
import lol.mony.harmony.data.voice.CallState
import lol.mony.harmony.data.voice.SpatialAudio
import lol.mony.harmony.data.voice.SpatialAudioSettings
import lol.mony.harmony.data.voice.UserPosition
import lol.mony.harmony.data.voice.VoiceEngine
import lol.mony.harmony.ui.AppState
import lol.mony.harmony.ui.components.Avatar
import lol.mony.harmony.ui.components.ErrorBanner
import lol.mony.harmony.ui.components.SectionHeader
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize
import lol.mony.harmony.ui.theme.LocalHarmonyColors
import java.time.Instant

/**
 * A voice channel's call: joining, the roster, the controls, video tiles and screen share.
 *
 * Permissions are requested here, at the point the person asks to speak or to show their
 * camera, never at launch. A refusal is answered with what the person can still do rather than
 * a dead screen: a microphone-less join is a listen-only join, and the screen says so.
 *
 * Screen sharing is the one capture that needs the system's own consent. That consent is a
 * separate per-session grant, so the capture session starts only after the system's dialog
 * returns it, and stopping is what tells the engine to unpublish.
 */
@Composable
fun VoiceChannelScreen(state: AppState, channel: Channel, onClose: () -> Unit) {
    val session = state.auth.state.collectAsState().value as? AuthState.SignedIn ?: return
    val myId = session.profile?.id ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { VoiceEngine(context) }
    val callState by engine.state.collectAsState()
    val controls by engine.controls.collectAsState()
    val remoteVideo by engine.remoteVideo.collectAsState()
    val screens by engine.screenShares.collectAsState()
    val spatial by engine.spatial.collectAsState()
    val positions by engine.positions.collectAsState()
    val repository = remember(state.container.rest) { VoiceRepository(state.container.rest, lol.mony.harmony.data.api.Http.client) }

    var config by remember { mutableStateOf<lol.mony.harmony.data.model.VoiceConfig?>(null) }
    var configLoaded by remember { mutableStateOf(false) }
    var roster by remember { mutableStateOf<List<VoiceParticipant>>(emptyList()) }
    var joined by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var microphoneGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var pendingCamera by remember { mutableStateOf(false) }
    var spatialOpen by remember { mutableStateOf(false) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    val people by state.people.collectAsState()

    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (!granted) permissionNotice = "Microphone access is off. You can listen, but nobody can hear you. Allow it in system settings to speak."
        else {
            if (pendingCamera) { pendingCamera = false }
            // The microphone was refused at join and is granted now, so the call has a capture
            // to hold in the foreground where a moment ago it had none.
            if (joined) lol.mony.harmony.voice.CallService.start(context, channel.name)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) {
            if (joined) lol.mony.harmony.voice.CallService.start(context, channel.name)
            scope.launch { engine.setCamera(true) }
        }
        else permissionNotice = "Camera access is off, so your camera stays unpublished."
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data: Intent? = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            permissionNotice = "Screen sharing was not allowed, so nothing is being shared."
        } else scope.launch {
            // Screen capture is a per-session grant, so the service claims the projection type
            // only now that the system has returned the consent that starts it.
            lol.mony.harmony.voice.CallService.start(context, channel.name, screenSharing = true)
            engine.setScreenSharing(true, data) {
                scope.launch { engine.setScreenSharing(false) }
            }.onFailure { failure = it.message }
        }
    }

    suspend fun refreshRoster() {
        runCatching { repository.participants(session.endpoint, state.auth.freshToken() ?: return, channel.id).getOrThrow() }
            .onSuccess { roster = it }
    }

    LaunchedEffect(channel.id) {
        runCatching { repository.config(session.endpoint, state.auth.freshToken() ?: "").getOrThrow() }
            .onSuccess { config = it }
        configLoaded = true
        refreshRoster()
        state.resolveAuthors(session.endpoint, state.auth.freshToken() ?: "", emptyList())
        while (true) { kotlinx.coroutines.delay(8_000); refreshRoster() }
    }
    // Every leave path releases the media. Closing the screen, backing out or the process going
    // away must never leave the microphone publishing, and the foreground service that holds
    // the microphone is stopped with it. The stop is synchronous on purpose: the effect's scope
    // is cancelled as the screen leaves composition, so a launched stop could never run.
    DisposableEffect(channel.id) {
        onDispose {
            lol.mony.harmony.voice.CallService.stop(context)
            scope.launch { engine.leave() }
        }
    }
    // A call the server ended -- the room closed, this account was removed, the connection
    // dropped -- leaves nobody in the call, so its notification must not outlive it.
    LaunchedEffect(callState) {
        if (callState is CallState.Ended) lol.mony.harmony.voice.CallService.stop(context)
    }
    BackHandler {
        scope.launch {
            if (joined) {
                runCatching { repository.leaveRoster(session.endpoint, state.auth.freshToken() ?: "", channel.id, myId) }
                engine.leave()
            }
            onClose()
        }
    }

    fun join() {
        joining = true
        failure = null
        scope.launch {
            try {
                val token = state.auth.freshToken() ?: error("Sign in again.")
                val liveKit = repository.token(session.endpoint, token,
                    VoiceRepository.roomName(channel.id, direct = false), VoiceRepository.roomType(direct = false)).getOrThrow()
                engine.join(liveKit, session.profile?.shownName ?: "You").getOrThrow()
                // Android mutes or stops the capture as soon as the app leaves the foreground
                // unless a foreground service of the right type holds it. The service is started
                // with the call and stopped with it, so the microphone is never left running.
                lol.mony.harmony.voice.CallService.start(context, channel.name)
                engine.setPosition(UserPosition(myId, 0.0, 0.0))
                joined = true
                if (channel.serverId.isNotBlank()) {
                    runCatching { repository.joinRoster(session.endpoint, token, channel.id, channel.serverId, myId) }
                }
                refreshRoster()
            } catch (error: Exception) {
                failure = error.message ?: "The call could not be joined."
            } finally { joining = false }
        }
    }

    fun leave() {
        scope.launch {
            val token = state.auth.freshToken()
            lol.mony.harmony.voice.CallService.stop(context)
            engine.leave()
            joined = false
            if (token != null) runCatching { repository.leaveRoster(session.endpoint, token, channel.id, myId) }
            refreshRoster()
            onClose()
        }
    }

    val connected = callState as? CallState.Connected
    val inCall = joined && connected != null
    // The bar says what this screen is and what state the call is in. Its second line is the
    // one thing a person needs before deciding anything: who is already here, or why they
    // cannot join.
    //
    // The whole screen is an opaque surface of its own. It is opened *over* the channel list --
    // `ChannelList` draws it inside its own column, and the frame's bar is under both -- so a
    // screen that drew no background let the channel list, the app bar and the space bar show
    // through it, and the call's controls floated over a list of channel names. A call is a
    // place, not a sheet: it gets a background and covers what is under it.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize().testTag("voice-screen"),
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Column {
                        Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge)
                        Text(
                            when {
                                !configLoaded -> "Checking voice…"
                                config?.enabled != true -> "Voice is not available on this instance"
                                config?.mode == "p2p" -> "Peer-to-peer calls are not supported by this client"
                                inCall -> rosterSummary(connected, myId)
                                roster.isNotEmpty() -> rosterSummary(null, myId)
                                else -> "Voice channel"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (joined) leave()
                        else { lol.mony.harmony.voice.CallService.stop(context); scope.launch { engine.leave() }; onClose() }
                    }) { Icon(Icons.Default.Close, "Close") }
                },
                actions = {
                    // Spatial audio is a property of being in a call, so it is offered only there.
                    // Offering it in the lobby is a control for something that cannot be heard yet.
                    if (inCall) IconButton(onClick = { spatialOpen = true }) { Icon(Icons.Default.SurroundSound, "Spatial audio") }
                },
            )
            val banner = failure
            if (banner != null) ErrorBanner(banner) { failure = null }
            val notice = permissionNotice
            if (notice != null) ErrorBanner(notice) { permissionNotice = null }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    joining -> LoadingBlock("Joining the call")
                    callState is CallState.Connecting -> LoadingBlock("Connecting")
                    callState is CallState.Ended -> CallEnded(
                        reason = (callState as CallState.Ended).reason,
                        onRejoin = { join() },
                        onLeave = { onClose() },
                    )
                    inCall -> CallStage(
                        connected = connected,
                        roster = roster,
                        people = people,
                        myId = myId,
                        positions = positions,
                        spatial = spatial,
                        baseUrl = session.endpoint.baseUrl,
                        screenShares = screens,
                        cameras = remoteVideo,
                        engine = engine,
                    )
                    else -> JoinLobby(
                        channel = channel,
                        config = config,
                        loaded = configLoaded,
                        roster = roster,
                        people = people,
                        baseUrl = session.endpoint.baseUrl,
                        microphoneGranted = microphoneGranted,
                        onJoin = {
                            if (!microphoneGranted) microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            join()
                        },
                    )
                }
            }

            if (inCall) {
                CallControls(
                    controls = controls,
                    microphoneGranted = microphoneGranted,
                    onMicrophone = {
                        scope.launch {
                            if (!microphoneGranted) {
                                pendingCamera = false
                                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else engine.setMicrophone(!controls.microphoneOn).onFailure { failure = it.message }
                        }
                    },
                    onDeafen = {
                        scope.launch { engine.setDeafened(!controls.deafened).onFailure { failure = it.message } }
                    },
                    onCamera = {
                        scope.launch {
                            if (!controls.cameraOn && !cameraGranted) cameraLauncher.launch(Manifest.permission.CAMERA)
                            else engine.setCamera(!controls.cameraOn).onFailure { failure = it.message }
                        }
                    },
                    onShareScreen = {
                        if (controls.screenSharing) scope.launch { engine.setScreenSharing(false) }
                        else {
                            val manager = context.getSystemService(MediaProjectionManager::class.java)
                            val intent = manager?.createScreenCaptureIntent()
                            if (intent != null) projectionLauncher.launch(intent)
                            else failure = "This device cannot share its screen."
                        }
                    },
                    onSpeaker = { engine.setSpeaker(!controls.speakerOn) },
                    onLeave = { leave() },
                )
            }
        }
    }
    if (spatialOpen) {
        SpatialAudioSheet(spatial, onDismiss = { spatialOpen = false }, onChange = { engine.setSpatialSettings(it) })
    }
}

/*
 * The pieces below are `internal` rather than `private` so a render test can draw them with
 * fixture rows: the call itself needs a LiveKit room and a real audio device, which a JVM test
 * cannot have, but the lobby, a person's row, the control bar and the ended state are all plain
 * composables over plain data. That is what lets this screen have page evidence at all.
 */

/**
 * The bar's second line: who is in the call, counted, or what the roster says before joining.
 *
 * A count is what a person needs before deciding to join -- "3 in the call" answers it, a list of
 * names does not fit on the bar. The names are on the stage below, where there is room.
 */
internal fun rosterSummary(connected: CallState.Connected?, myId: String): String {
    val others = (connected?.participants.orEmpty()).count { !it.isLocal }
    return when {
        connected == null -> "Voice channel"
        others == 0 -> "You are the only one here"
        others == 1 -> "1 other person in the call"
        else -> "$others people in the call"
    }
}

/**
 * What a person sees before they join: who is already here, what this instance's voice does, and
 * one button.
 *
 * This used to be a centred paragraph and a Join button, which answered "why can't I join" but
 * not "is anyone in there, and will they hear me". The people already in the channel are the
 * reason to join, so they are the body of the screen; the reason *not* to join is a notice on the
 * surface, and the button is the only filled thing on it.
 */
@Composable
internal fun JoinLobby(
    channel: Channel,
    config: lol.mony.harmony.data.model.VoiceConfig?,
    loaded: Boolean,
    roster: List<VoiceParticipant>,
    people: Map<String, lol.mony.harmony.data.model.Profile>,
    baseUrl: String,
    microphoneGranted: Boolean,
    onJoin: () -> Unit,
) {
    val joinable = loaded && config?.supportsThisClient == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The channel's own mark, so the lobby is this channel's and not a generic prompt.
        Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.VolumeUp, null, Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(
            channel.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 24.dp, end = 24.dp),
        )
        Text(
            when {
                !loaded -> "Checking whether this instance offers voice…"
                config?.enabled != true -> "This instance has no voice server configured, so there is nothing to join."
                config?.mode == "p2p" -> "This instance runs peer-to-peer calls, which this client cannot join yet."
                roster.isEmpty() -> "Nobody is here yet. Joining puts you in the channel so others can find you."
                else -> "Join to hear them. Your microphone is only published once you allow it."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 32.dp, end = 32.dp),
        )

        if (roster.isNotEmpty()) {
            SectionHeader(
                "In the channel",
                supporting = if (roster.size == 1) "1 person" else "${roster.size} people",
            )
            Column(Modifier.padding(horizontal = HarmonySize.gutter)) {
                roster.forEach { participant ->
                    val person = people[participant.userId]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(person?.shownName ?: "Someone", person?.avatarUrl, size = 36.dp, baseUrl = baseUrl)
                        Text(
                            person?.shownName ?: "Someone",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (participant.isMuted) {
                            Icon(Icons.Default.MicOff, "Muted", Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                        if (participant.isVideoEnabled) {
                            Icon(Icons.Default.Videocam, "Camera on", Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (joinable) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onJoin,
                shape = HarmonyShape.pill,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(52.dp)
                    .testTag("call-join"),
            ) {
                Icon(Icons.Default.Headphones, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Join the call", style = MaterialTheme.typography.titleSmall)
            }
            // Saying what will be published before it is published. A person who has not
            // granted the microphone joins listen-only, and the screen says so here rather
            // than surprising them with silence mid-conversation.
            Text(
                if (microphoneGranted) "Your microphone is off until you unmute."
                else "Without microphone access you can listen, but nobody will hear you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 32.dp, end = 32.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The call itself: what is being shared, who is talking, and who else is here.
 *
 * A shared screen or a camera is the thing being watched, so it takes the top and the roster
 * sits under it. When nobody has a camera on, the roster *is* the screen: a call with four
 * people in it and no video is a list of people, and drawing it as a small list under an empty
 * stage wastes the window.
 */
@Composable
private fun CallStage(
    connected: CallState.Connected,
    roster: List<VoiceParticipant>,
    people: Map<String, lol.mony.harmony.data.model.Profile>,
    myId: String,
    positions: Map<String, UserPosition>,
    spatial: SpatialAudioSettings,
    baseUrl: String,
    screenShares: Map<String, VideoTrack>,
    cameras: Map<String, VideoTrack>,
    engine: VoiceEngine,
) {
    val participants = connected.participants
    val shared = screenShares.entries.firstOrNull()
    val hasVideo = shared != null || cameras.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        if (hasVideo) {
            if (shared != null) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxWidth()) {
                    VideoTile(
                        shared.value,
                        "Shared screen from " + (people[shared.key]?.shownName ?: shared.key),
                        Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                        engine,
                    )
                }
            }
            val others = cameras.entries.toList()
            if (others.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(others, key = { it.key }) { (identity, track) ->
                        VideoTile(
                            track,
                            people[identity]?.shownName ?: identity,
                            Modifier.width(200.dp).height(130.dp),
                            engine,
                        )
                    }
                }
            }
        }
        // One header for both cases: with video it sits under the tiles, without video the
        // roster is the whole screen. Written once so the two cannot drift.
        SectionHeader("In the call", supporting = if (participants.size == 1) "1 person" else "${participants.size} people")
        if (participants.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Joining the room…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("call-roster"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(participants, key = { it.identity }) { participant ->
                    CallParticipantRow(
                        participant = participant,
                        person = people[participant.identity],
                        baseUrl = baseUrl,
                        mix = if (spatial.enabled && !participant.isLocal)
                            SpatialAudio.mix(positions[myId], positions[participant.identity], spatial) else null,
                    )
                }
            }
        }
    }
}

/**
 * One person in the call.
 *
 * Speaking is the state that matters most while a call is running, so it is drawn as a ring
 * around the face rather than a dot in a corner: the eye finds a person, not a pixel. Muted and
 * camera are badges on the face, which is where the person is looking.
 */
@Composable
internal fun CallParticipantRow(
    participant: CallParticipant,
    person: lol.mony.harmony.data.model.Profile?,
    baseUrl: String,
    mix: lol.mony.harmony.data.voice.SpatialMix?,
) {
    val border = if (participant.isSpeaking) MaterialTheme.colorScheme.primary
        else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (participant.isSpeaking) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent,
                HarmonyShape.medium,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.background(border, CircleShape).padding(2.dp)) {
                Avatar(person?.shownName ?: participant.name, person?.avatarUrl, size = 40.dp, baseUrl = baseUrl)
            }
            // The badge sits on the face rather than in a column of its own: a call is a group
            // of people, and a person's state belongs to their picture.
            val muted = participant.isMuted || participant.isDeafened
            if (muted) {
                Box(
                    Modifier.size(18.dp).background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.MicOff, "Muted", Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onError) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                participant.name + (if (participant.isLocal) " (you)" else ""),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mix != null) {
                Text(
                    SpatialAudio.describe(mix) + " · " + String.format(java.util.Locale.ROOT, "%.0f%%", mix.gain * 100),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (participant.isSharingScreen) Icon(Icons.Default.ScreenShare, "Sharing screen", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (participant.isCameraOn) Icon(Icons.Default.Videocam, "Camera on", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The call's controls, as one docked surface at the bottom.
 *
 * Leave is separated from the four toggles and is the only one that is not a toggle, because
 * leaving is the one action here that cannot be undone by pressing it again. A toggle shows its
 * state by being filled when it is *on* -- which for Mute means the microphone is live -- so the
 * label says what pressing it does ("Mute", not "Microphone").
 */
@Composable
internal fun CallControls(
    controls: lol.mony.harmony.data.voice.CallControls,
    microphoneGranted: Boolean,
    onMicrophone: () -> Unit,
    onDeafen: () -> Unit,
    onCamera: () -> Unit,
    onShareScreen: () -> Unit,
    onSpeaker: () -> Unit,
    onLeave: () -> Unit,
) {
    Surface(color = LocalHarmonyColors.current.surfaceRaised, tonalElevation = 3.dp) {
        // Five toggles with labels under them do not fit a 400dp phone at a 48dp touch target:
        // the labels collided ("Share screen" ran into "Earpiece"). They scroll instead, which
        // keeps every target full size -- shrinking them to fit would trade a visible label for
        // a target a thumb misses.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                // 12dp, not 20: at 20 the five toggles overran the width and the last one was
                // clipped by the pinned Leave button, which reads as a broken bar rather than as
                // a scroll. The trailing spacer lets the last label scroll clear of Leave.
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallToggle(
                    icon = if (controls.microphoneOn && microphoneGranted) Icons.Default.Mic else Icons.Default.MicOff,
                    label = if (controls.microphoneOn) "Mute" else "Unmute",
                    on = controls.microphoneOn && microphoneGranted,
                    tag = "call-mic",
                    onClick = onMicrophone,
                )
                CallToggle(
                    icon = if (controls.deafened) Icons.Default.HeadsetOff else Icons.Default.Headset,
                    label = if (controls.deafened) "Undeafen" else "Deafen",
                    on = !controls.deafened,
                    tag = "call-deafen",
                    onClick = onDeafen,
                )
                CallToggle(
                    icon = if (controls.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (controls.cameraOn) "Stop video" else "Start video",
                    on = controls.cameraOn,
                    tag = "call-camera",
                    onClick = onCamera,
                )
                CallToggle(
                    icon = Icons.Default.ScreenShare,
                    label = if (controls.screenSharing) "Stop sharing" else "Share screen",
                    on = controls.screenSharing,
                    tag = "call-screen",
                    onClick = onShareScreen,
                )
                CallToggle(
                    icon = if (controls.speakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    label = if (controls.speakerOn) "Earpiece" else "Speaker",
                    on = controls.speakerOn,
                    tag = "call-speaker",
                    onClick = onSpeaker,
                )
                Spacer(Modifier.width(4.dp))
            }
            // Leave sits outside the scrolling group: the toggles may scroll on a narrow phone,
            // but the one control a person reaches for in a hurry must never be off the edge of
            // the bar. It is also the only error-toned circle here, so it cannot be mistaken for
            // a toggle while a thumb is moving.
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = onLeave,
                    modifier = Modifier.size(48.dp).testTag("call-leave"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Icon(Icons.Default.CallEnd, "Leave the call", Modifier.size(22.dp)) }
                Text("Leave", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * A call that the server ended, with the two things a person might do about it.
 *
 * It used to be a sentence and a Rejoin button, which left leaving the call to the system back
 * gesture. Both are here now, and the reason is drawn on a surface so it reads as the state of
 * the call rather than as a message from the app.
 */
@Composable
internal fun CallEnded(reason: String, onRejoin: () -> Unit, onLeave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.CallEnd, null, Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer) }
        Text(
            "The call ended",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRejoin,
            shape = HarmonyShape.pill,
            modifier = Modifier.padding(top = 24.dp).testTag("call-rejoin"),
        ) { Text("Rejoin") }
        TextButton(onClick = onLeave, modifier = Modifier.padding(top = 4.dp)) { Text("Leave") }
    }
}

@Composable
private fun CallToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // "Off" is drawn as an outlined circle, not as a tonal one. This theme steps each
        // `surfaceContainer*` tier by about 2% lightness, so a filled "off" button two tiers
        // above its own bar still measured as nothing in the capture: the first attempt showed
        // "Share screen" as a flat glyph beside filled circles. A hairline outline is the state,
        // and it reads at any tonal step the account's look happens to produce.
        val onColors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        val offColors = IconButtonDefaults.filledIconButtonColors(
            containerColor = LocalHarmonyColors.current.surfaceRaised,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .testTag(tag)
                .then(
                    if (on) Modifier
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                ),
            colors = if (on) onColors else offColors,
        ) { Icon(icon, label, Modifier.size(20.dp)) }
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VideoTile(track: VideoTrack, label: String, modifier: Modifier, engine: VoiceEngine) {
    Box(modifier.background(Color.Black, RoundedCornerShape(12.dp))) {
        AndroidView(
            factory = { context ->
                TextureViewRenderer(context).also { renderer ->
                    // A texture renderer with the default GL context: the track draws into it
                    // and the view composites it, so a remote camera or screen really appears.
                    renderer.init(engine.eglContext, null)
                    track.addRenderer(renderer)
                }
            },
            onRelease = { renderer -> track.removeRenderer(renderer); renderer.release() },
            modifier = Modifier.fillMaxSize().testTag("call-video"),
        )
        Text(label, Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LoadingBlock(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(label, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun SpatialAudioSheet(settings: SpatialAudioSettings, onDismiss: () -> Unit, onChange: (SpatialAudioSettings) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = HarmonySize.gutterLarge)) {
            Text("Spatial audio", style = MaterialTheme.typography.titleLarge)
            Text(
                "People further away sound quieter and off to one side. Drag someone in the room to move them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            // The switch is a row with its own explanation, so "off" is a stated state rather
            // than an empty panel below it.
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Spatial audio", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.enabled) "On: where people are changes how you hear them"
                        else "Off: everyone sounds equally close",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { onChange(settings.copy(enabled = it)) },
                    modifier = Modifier.testTag("spatial-enabled"),
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            // The model and the range only mean anything while it is on, so they are shown
            // disabled rather than hidden: a person can see what turning it on would give them.
            val dim = if (settings.enabled) 1f else 0.38f
            Column(Modifier.alpha(dim)) {
                SectionHeader("Distance model", supporting = "How quickly someone fades with distance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lol.mony.harmony.data.voice.DistanceModel.entries.forEach { model ->
                        FilterChip(
                            selected = settings.distanceModel == model,
                            enabled = settings.enabled,
                            onClick = { onChange(settings.copy(distanceModel = model)) },
                            label = { Text(model.wire.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                SectionHeader("Hearing range", supporting = "Past this, someone is silent")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = settings.maxDistance.toFloat(),
                        enabled = settings.enabled,
                        onValueChange = { onChange(settings.copy(maxDistance = it.toDouble())) },
                        valueRange = 50f..600f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format(java.util.Locale.ROOT, "%.0f", settings.maxDistance),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
