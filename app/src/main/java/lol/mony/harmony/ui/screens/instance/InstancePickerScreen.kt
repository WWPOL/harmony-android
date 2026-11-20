package lol.mony.harmony.ui.screens.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.mony.harmony.data.api.Endpoint
import lol.mony.harmony.ui.components.HarmonyWordmark
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import lol.mony.harmony.data.api.Http

/**
 * Choose the instance to connect to, as the native Harmony clients ask on first launch.
 *
 * The app has to learn two things about an instance: where it is and its anon key. Public
 * instances serve both at `/api/federation/instance-info` - the same file the web client's build reads - so a
 * person types a domain and the app discovers the key. A bare host gets `https://`; anything
 * already carrying a scheme is used as given.
 *
 * This is the signed-out page's sibling, and it is deliberately the same page: a tinted band
 * carrying the wordmark and the question, the input inside the app's reading column, a refusal on
 * an `errorContainer` surface rather than as red text under the field, and one filled button. A
 * person who has just installed the app meets this screen before any other, so it is the one that
 * sets the app's shape in their mind; it used to be a centred column of a wordmark, a heading, a
 * paragraph, a field and a button, with the paragraph doing the work a page's structure should.
 */
@Composable
fun InstancePickerScreen(
    initialUrl: String?,
    onUse: (Endpoint, String?) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var url by remember { mutableStateOf(initialUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discoveredName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun discoverThenUse(target: String) {
        if (busy || target.isBlank()) return
        busy = true
        error = null
        discoveredName = null
        scope.launch {
            when (val found = discoverInstance(target)) {
                is Discovery.Failed -> error = found.message
                is Discovery.Found -> {
                    discoveredName = found.name
                    busy = false
                    onUse(found.endpoint, found.name)
                    return@launch
                }
            }
            busy = false
        }
    }

    InstancePickerPage(
        url = url,
        busy = busy,
        error = error,
        onUrlChange = { url = it; error = null },
        onConnect = { discoverThenUse(url.trim()) },
        onCancel = onCancel,
    )
}

/**
 * The page itself, without the discovery.
 *
 * The state is the caller's so a render test can draw the refused state: a capture of a screen
 * that can only be reached by typing a bad address into a network call is a capture nobody can
 * take. What is left here is layout, which is exactly what the evidence is for.
 */
@Composable
internal fun InstancePickerPage(
    url: String,
    busy: Boolean,
    error: String?,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // The band carries the one thing this page is about. It is edge to edge and its
            // contents are not, which is the same rule the sign-in band follows, so the two
            // signed-out pages line up when a person moves between them.
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier
                            .widthIn(max = HarmonySize.readingMaxWidth)
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 32.dp),
                    ) {
                        HarmonyWordmark()
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "Choose an instance",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.semantics { heading() },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Public,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "Harmony is federated",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // The form keeps the reading column the band's contents use, so a field is never a
            // thousand pixels wide on a tablet.
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = HarmonySize.readingMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            ) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = "Which server do you use?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Enter the address of the instance you use - for example har.mony.lol - " +
                        "and sign in there. The app reads its address and key from the instance itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("Instance address") },
                    placeholder = { Text("har.mony.lol") },
                    singleLine = true,
                    enabled = !busy,
                    shape = HarmonyShape.large,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onConnect() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("instance-url"),
                )

                // The refusal is a surface, not a line of red text: under a field, red text reads
                // as that field's supporting text, and this message is about the address as a
                // whole (wrong host, no Harmony there, not HTTPS) as often as about its syntax.
                val refusal = error
                if (refusal != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = HarmonyShape.medium,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                text = refusal,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).testTag("instance-error"),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onConnect,
                    enabled = !busy && url.isNotBlank(),
                    shape = HarmonyShape.pill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("instance-connect"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Connect", fontSize = 16.sp)
                    }
                }
                if (onCancel != null) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                    ) { Text("Cancel") }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** What an instance's `/config.json` says about itself. */
sealed interface Discovery {
    data class Found(val endpoint: Endpoint, val name: String?) : Discovery
    data class Failed(val message: String) : Discovery
}

/** Native-client discovery uses the same federation endpoint as the official picker. */
@kotlinx.serialization.Serializable
private data class InstanceConfiguration(
    val name: String? = null,
    val supabaseUrl: String,
    val supabaseAnonKey: String,
)

suspend fun discoverInstance(raw: String): Discovery = withContext(Dispatchers.IO) {
    val address = raw.trim()
    val url = (if (address.contains("://")) address else "https://$address").toHttpUrlOrNull() ?: return@withContext Discovery.Failed("Enter a valid instance address.")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        return@withContext Discovery.Failed("Use an instance address without a password, query or fragment.")
    }
    val loopback = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
    if (!url.isHttps && !(lol.mony.harmony.BuildConfig.DEBUG && loopback)) {
        return@withContext Discovery.Failed("Use HTTPS to protect your account.")
    }
    val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    try {
        Http.client.newCall(Request.Builder().url("$origin/api/federation/instance-info")
            .header("Accept", "application/json").get().build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext Discovery.Failed(
                "The instance answered ${response.code}. Check that this is a Harmony instance.")
            val config = lol.mony.harmony.data.model.HarmonyJson.decodeFromString(
                InstanceConfiguration.serializer(), response.body.string())
            val service = config.supabaseUrl.toHttpUrlOrNull()
                ?: return@withContext Discovery.Failed("The instance returned an invalid service address.")
            if ((!service.isHttps && !(lol.mony.harmony.BuildConfig.DEBUG &&
                    service.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) ||
                config.supabaseAnonKey.isBlank()) {
                return@withContext Discovery.Failed("The instance did not publish a secure usable configuration.")
            }
            Discovery.Found(Endpoint(service.toString().trimEnd('/'), config.supabaseAnonKey, origin), config.name)
        }
    } catch (error: Exception) {
        Discovery.Failed("Could not connect. Check the address and your network connection.")
    }
}
