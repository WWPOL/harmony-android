package lol.mony.harmony.ui.screens.signin

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lol.mony.harmony.data.api.AuthApi
import lol.mony.harmony.ui.components.HarmonyWordmark
import lol.mony.harmony.ui.theme.HarmonyShape
import lol.mony.harmony.ui.theme.HarmonySize

/** Which form the auth screens show: signing in, creating an account, or resetting a password. */
enum class AuthFormMode { SignIn, Register, ForgotPassword }

/**
 * Everything a signed-out person can do on an instance: sign in, create an account, or ask for
 * a password reset. The web client splits these across `LoginView`, `RegisterView` and
 * `ResetPasswordView`; on a phone they are one screen with three states, which keeps the back
 * stack to the two places Android users expect (a form, and the way out of it).
 */
@Composable
fun AuthFormScreen(
    instanceName: String?,
    instanceDomain: String?,
    onSignIn: suspend (email: String, password: String) -> AuthApi.Result<Unit>,
    onRegister: suspend (email: String, password: String, username: String) -> AuthApi.Result<Unit>,
    onForgotPassword: suspend (email: String) -> AuthApi.Result<Unit>,
    onChangeInstance: () -> Unit,
) {
    var mode by remember { mutableStateOf(AuthFormMode.SignIn) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (mode) {
            AuthFormMode.SignIn -> SignInForm(
                instanceName = instanceName,
                instanceDomain = instanceDomain,
                onSignIn = onSignIn,
                onRegister = { mode = AuthFormMode.Register },
                onForgotPassword = { mode = AuthFormMode.ForgotPassword },
                onChangeInstance = onChangeInstance,
            )
            AuthFormMode.Register -> RegisterForm(
                instanceName = instanceName,
                onBack = { mode = AuthFormMode.SignIn },
                onSubmit = onRegister,
            )
            AuthFormMode.ForgotPassword -> ForgotPasswordForm(
                onBack = { mode = AuthFormMode.SignIn },
                onSubmit = onForgotPassword,
            )
        }
    }
}

@Composable
private fun SignInForm(
    instanceName: String?,
    instanceDomain: String?,
    onSignIn: suspend (String, String) -> AuthApi.Result<Unit>,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    onChangeInstance: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    fun submit() {
        if (busy || email.isBlank() || password.isBlank()) return
        busy = true
        error = null
        scope.launch {
            when (val result = onSignIn(email.trim(), password)) {
                is AuthApi.Result.Failed -> error = result.message
                is AuthApi.Result.Ok -> Unit
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // A tinted band carries the instance: this screen is the one place in the app where
        // "which server am I joining" is the question, so the answer is the largest thing on it
        // rather than a line of grey text under the wordmark. Its colour is the branded fallback
        // role, so a server that has not set an accent still gets a considered surface.
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            // The band is edge to edge; its contents are not. A Box does the centring, because
            // Surface's content is not a scope that reads `align` -- a modifier that silently
            // does nothing is how the first wide capture of this page came out left-aligned
            // while the form below it was centred.
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
                        text = instanceName ?: "Harmony",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (instanceDomain != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(
                                Icons.Outlined.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = instanceDomain,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    // Changing instance is a rare, deliberate act, so it sits on the band that
                    // names the instance instead of being the last of six links at the bottom.
                    // Its colour is the band's own content role: a primary-coloured label on a
                    // primary-container band is the same hue on the same hue, which reads as
                    // greyed-out text rather than as something to press.
                    TextButton(
                        onClick = onChangeInstance,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Use another instance", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // A field a thousand pixels wide is a field nobody wants to type into, so the form keeps
        // the same readable column every other page uses while the band above stays edge to edge.
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = HarmonySize.readingMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Sign in",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "with the account you use on this instance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                shape = HarmonyShape.large,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signin-email"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !busy,
                shape = HarmonyShape.large,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            imageVector = if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (reveal) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signin-password"),
            )

            // The failure is a surface rather than a line of red text, so it cannot be mistaken
            // for the supporting text of the field above it.
            if (error != null) {
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
                            text = error!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).testTag("signin-error"),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                shape = HarmonyShape.pill,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("signin-submit"),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Sign in", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Forgot your password?")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // "New to Harmony" and the licence are two different kinds of statement -- one is
            // the next step, the other is a legal notice -- so they are not two links in the
            // same stack any more.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("New here?", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Create an account on this instance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onRegister, shape = HarmonyShape.pill) { Text("Join") }
            }
            HorizontalDivider()
            // The app's own project, not the instance's: this is the client's source, and the
            // GPL asks that it be reachable from the interface.
            Text(
                text = "Brapmony, under the GPL v3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 24.dp)
                    .clickable { uri.openUri("https://github.com/WWPOL/harmony-android") },
            )
        }
    }
}

@Composable
private fun RegisterForm(
    instanceName: String?,
    onBack: () -> Unit,
    onSubmit: suspend (email: String, password: String, username: String) -> AuthApi.Result<Unit>,
) {
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val usernameLooksValid = username.matches(Regex("[a-z0-9_]{3,24}"))
    val passwordsMatch = password.isNotEmpty() && password == confirm
    val canSubmit = !busy && email.isNotBlank() && usernameLooksValid && password.length >= 8 && passwordsMatch

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            when (val result = onSubmit(email.trim(), password, username.trim())) {
                is AuthApi.Result.Failed -> error = result.message
                is AuthApi.Result.Ok -> done = true
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sign in")
        }
        Text(
            text = if (done) "Check your email" else "Create an account",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (done) {
                "We sent a confirmation link to ${email.trim()}. Open it, then sign in."
            } else {
                "On ${instanceName ?: "this instance"}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        if (!done) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("register-email"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(24); error = null },
                label = { Text("Username") },
                supportingText = { Text("3–24 lowercase letters, numbers or underscores") },
                isError = username.isNotEmpty() && !usernameLooksValid,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("register-username"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                supportingText = { Text("At least 8 characters") },
                isError = password.isNotEmpty() && password.length < 8,
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("register-password"),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = { Text("Confirm password") },
                isError = confirm.isNotEmpty() && !passwordsMatch,
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("register-confirm"),
            )

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("register-error"),
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("register-submit"),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Create account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ForgotPasswordForm(
    onBack: () -> Unit,
    onSubmit: suspend (email: String) -> AuthApi.Result<Unit>,
) {
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sign in")
        }
        Text(
            text = "Reset your password",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (sent) {
                "If an account exists for ${email.trim()}, a reset link is on its way."
            } else {
                "We will email you a link to choose a new password."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        if (!sent) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (email.isNotBlank() && !busy) {
                        busy = true
                        scope.launch {
                            when (val result = onSubmit(email.trim())) {
                                is AuthApi.Result.Failed -> error = result.message
                                is AuthApi.Result.Ok -> sent = true
                            }
                            busy = false
                        }
                    }
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reset-email"),
            )
            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (email.isNotBlank() && !busy) {
                        busy = true
                        scope.launch {
                            when (val result = onSubmit(email.trim())) {
                                is AuthApi.Result.Failed -> error = result.message
                                is AuthApi.Result.Ok -> sent = true
                            }
                            busy = false
                        }
                    }
                },
                enabled = !busy && email.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("reset-submit"),
            ) {
                Text("Send reset link", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
