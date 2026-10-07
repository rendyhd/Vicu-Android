package com.rendyhd.vicu.ui.screens.setup

import com.rendyhd.vicu.ui.rememberOidcLauncher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.auth.isTotpPasscodeComplete
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.setupComplete) {
        if (state.setupComplete) onSetupComplete()
    }

    val oidcLauncher = rememberOidcLauncher { code, callbackState, error ->
        viewModel.handleOidcCallback(code, callbackState, error)
    }

    // Back steps through the setup instead of leaving the app mid-way; on the first step, and
    // once signed in (only the Inbox choice is left), it is the system's.
    BackHandler(enabled = state.canGoBack) { viewModel.goBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp),
    ) {
        if (state.canGoBack) {
            IconButton(onClick = { viewModel.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        AnimatedContent(
            targetState = state.step,
            label = "setup_step",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { step ->
            when (step) {
                SetupStep.ServerUrl -> CenteredScrollingStep {
                    ServerUrlStep(
                        url = state.serverUrl,
                        isLoading = state.isLoading,
                        error = state.error,
                        showCleartextWarning = state.showCleartextWarning,
                        onUrlChange = viewModel::updateServerUrl,
                        onContinue = viewModel::discoverServer,
                    )
                }
                SetupStep.AuthMethodPicker -> CenteredScrollingStep {
                    AuthMethodPickerStep(
                        localAuthEnabled = state.localAuthEnabled,
                        oidcProviders = state.oidcProviders,
                        error = state.error,
                        showCleartextWarning = state.showCleartextWarning,
                        onSelectPassword = viewModel::selectPasswordLogin,
                        onSelectApiToken = viewModel::selectApiTokenEntry,
                        onSelectOidc = { provider ->
                            viewModel.selectOidcProvider(provider)
                            val params = viewModel.getOidcAuthParams()
                            if (params != null) {
                                oidcLauncher(params)
                            }
                        },
                    )
                }
                SetupStep.PasswordLogin -> CenteredScrollingStep {
                    PasswordLoginStep(
                        username = state.username,
                        password = state.password,
                        totpPasscode = state.totpPasscode,
                        showTotpField = state.showTotpField,
                        isLoading = state.isLoading,
                        error = state.error,
                        showCleartextWarning = state.showCleartextWarning,
                        onUsernameChange = viewModel::updateUsername,
                        onPasswordChange = viewModel::updatePassword,
                        onTotpChange = viewModel::updateTotpPasscode,
                        onSubmit = viewModel::submitPasswordLogin,
                    )
                }
                SetupStep.OidcTotp -> CenteredScrollingStep {
                    TotpStep(
                        passcode = state.totpPasscode,
                        isLoading = state.isLoading,
                        error = state.error,
                        onPasscodeChange = viewModel::updateTotpPasscode,
                        onSubmit = {
                            val params = viewModel.retryOidcWithTotp()
                            if (params != null) {
                                oidcLauncher(params)
                            }
                        },
                    )
                }
                SetupStep.ApiTokenEntry -> CenteredScrollingStep {
                    ApiTokenEntryStep(
                        apiToken = state.apiToken,
                        isLoading = state.isLoading,
                        error = state.error,
                        showCleartextWarning = state.showCleartextWarning,
                        onTokenChange = viewModel::updateApiToken,
                        onSubmit = viewModel::submitApiToken,
                    )
                }
                SetupStep.OidcInProgress -> OidcInProgressStep()
                // The list scrolls itself, so this step is not wrapped in a scrolling column.
                SetupStep.ProjectSelection -> ProjectSelectionStep(
                    projects = state.projects,
                    selectedProjectId = state.selectedProjectId,
                    error = state.error,
                    onSelectProject = viewModel::selectProject,
                    onConfirm = viewModel::confirmSetup,
                )
            }
        }
    }

    state.discardPrompt?.let { prompt ->
        DiscardOnSignInDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmDiscardAndSignIn,
            onCancel = viewModel::cancelDiscard,
        )
    }
}

/**
 * A step that is centred when it fits and scrolls when it does not (a small screen, the keyboard
 * open). The scroll sits inside the insets the screen already applies.
 */
@Composable
private fun CenteredScrollingStep(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/** Shown for a plain http:// address to a host outside the user's network. Warns; never blocks. */
@Composable
private fun CleartextWarning(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(
                text = "This address is not encrypted (http://). Your password or token would cross " +
                    "the network in plain text. Use https:// unless this server is only reachable " +
                    "on your own network.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A masked text field with a toggle to reveal what was typed or pasted. */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = keyboardActions,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide ${label.lowercase()}" else "Show ${label.lowercase()}",
                )
            }
        },
        enabled = enabled,
    )
}

@Composable
private fun ServerUrlStep(
    url: String,
    isLoading: Boolean,
    error: String?,
    showCleartextWarning: Boolean,
    onUrlChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Welcome to Vicu",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Enter your Vikunja server URL to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("https://app.vikunja.cloud") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
            isError = error != null,
            supportingText = when {
                error != null -> ({ Text(error, color = MaterialTheme.colorScheme.error) })
                url.isBlank() -> ({ Text("Leave empty to use Vikunja Cloud") })
                else -> null
            },
            enabled = !isLoading,
        )
        if (showCleartextWarning) CleartextWarning()
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(16.dp))

        val signUpText = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append("Don't have an account? ")
            }
            withLink(LinkAnnotation.Url("https://vikunja.cloud/register")) {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                    append("Sign up")
                }
            }
        }
        Text(
            text = signUpText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun AuthMethodPickerStep(
    localAuthEnabled: Boolean,
    oidcProviders: List<com.rendyhd.vicu.data.remote.api.OidcProviderDto>,
    error: String?,
    showCleartextWarning: Boolean,
    onSelectPassword: () -> Unit,
    onSelectApiToken: () -> Unit,
    onSelectOidc: (com.rendyhd.vicu.data.remote.api.OidcProviderDto) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Sign In",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Choose how to authenticate with your server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (showCleartextWarning) CleartextWarning()

        // OIDC providers
        oidcProviders.forEach { provider ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectOidc(provider) },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Sign in with ${provider.name}", style = MaterialTheme.typography.titleSmall)
                        Text("OpenID Connect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Password login
        if (localAuthEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPassword() },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Sign in with password", style = MaterialTheme.typography.titleSmall)
                        Text("Username and password", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // API token
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectApiToken() },
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Key, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Use API token", style = MaterialTheme.typography.titleSmall)
                    Text("Manual token entry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PasswordLoginStep(
    username: String,
    password: String,
    totpPasscode: String,
    showTotpField: Boolean,
    isLoading: Boolean,
    error: String?,
    showCleartextWarning: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTotpChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Sign In",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (showCleartextWarning) CleartextWarning()

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            enabled = !isLoading,
        )
        SecretField(
            value = password,
            onValueChange = onPasswordChange,
            label = "Password",
            imeAction = if (showTotpField) ImeAction.Next else ImeAction.Go,
            keyboardActions = if (!showTotpField) KeyboardActions(onGo = { onSubmit() }) else KeyboardActions.Default,
            enabled = !isLoading,
            modifier = Modifier.semantics { contentType = ContentType.Password },
        )
        if (showTotpField) {
            OutlinedTextField(
                value = totpPasscode,
                onValueChange = onTotpChange,
                label = { Text("TOTP Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                enabled = !isLoading,
                placeholder = { Text("000000") },
            )
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading &&
                username.isNotBlank() &&
                password.isNotBlank() &&
                (!showTotpField || isTotpPasscodeComplete(totpPasscode)),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (showTotpField) "Verify" else "Sign In")
        }
    }
}

@Composable
private fun TotpStep(
    passcode: String,
    isLoading: Boolean,
    error: String?,
    onPasscodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Two-Factor Authentication",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Enter the 6-digit code from your authenticator app, then complete SSO once more.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = passcode,
            onValueChange = onPasscodeChange,
            label = { Text("Two-Factor Code") },
            placeholder = { Text("000000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            enabled = !isLoading,
        )
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && isTotpPasscodeComplete(passcode),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Continue with SSO")
        }
    }
}

@Composable
private fun ApiTokenEntryStep(
    apiToken: String,
    isLoading: Boolean,
    error: String?,
    showCleartextWarning: Boolean,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "API Token",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Paste an API token from your Vikunja settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (showCleartextWarning) CleartextWarning()

        SecretField(
            value = apiToken,
            onValueChange = onTokenChange,
            label = "API Token",
            imeAction = ImeAction.Go,
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            enabled = !isLoading,
        )
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && apiToken.isNotBlank(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Connect")
        }
    }
}

@Composable
private fun OidcInProgressStep() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Completing sign-in...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ProjectSelectionStep(
    projects: List<com.rendyhd.vicu.domain.model.Project>,
    selectedProjectId: Long?,
    error: String?,
    onSelectProject: (Long) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Select Inbox Project",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Choose which project to use as your Inbox.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(projects, key = { it.id }) { project ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProject(project.id) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = project.id == selectedProjectId,
                        onClick = { onSelectProject(project.id) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(project.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedProjectId != null,
        ) {
            Text("Complete Setup")
        }
    }
}
