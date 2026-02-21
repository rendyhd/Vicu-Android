package com.rendyhd.vicu.ui.screens.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.setupComplete) {
        if (state.setupComplete) onSetupComplete()
    }

    val oidcLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.handleOidcResult(result.data)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Back button for non-first steps
        if (state.step != SetupStep.ServerUrl) {
            IconButton(onClick = { viewModel.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedContent(
            targetState = state.step,
            label = "setup_step",
        ) { step ->
            when (step) {
                SetupStep.ServerUrl -> ServerUrlStep(
                    url = state.serverUrl,
                    isLoading = state.isLoading,
                    error = state.error,
                    onUrlChange = viewModel::updateServerUrl,
                    onContinue = viewModel::discoverServer,
                )
                SetupStep.AuthMethodPicker -> AuthMethodPickerStep(
                    localAuthEnabled = state.localAuthEnabled,
                    oidcProviders = state.oidcProviders,
                    error = state.error,
                    onSelectPassword = viewModel::selectPasswordLogin,
                    onSelectApiToken = viewModel::selectApiTokenEntry,
                    onSelectOidc = { provider ->
                        viewModel.selectOidcProvider(provider)
                        val intent = viewModel.getOidcAuthIntent()
                        if (intent != null) {
                            oidcLauncher.launch(intent)
                        }
                    },
                )
                SetupStep.PasswordLogin -> PasswordLoginStep(
                    username = state.username,
                    password = state.password,
                    totpPasscode = state.totpPasscode,
                    showTotpField = state.showTotpField,
                    isLoading = state.isLoading,
                    error = state.error,
                    onUsernameChange = viewModel::updateUsername,
                    onPasswordChange = viewModel::updatePassword,
                    onTotpChange = viewModel::updateTotpPasscode,
                    onSubmit = viewModel::submitPasswordLogin,
                )
                SetupStep.ApiTokenEntry -> ApiTokenEntryStep(
                    apiToken = state.apiToken,
                    isLoading = state.isLoading,
                    error = state.error,
                    onTokenChange = viewModel::updateApiToken,
                    onSubmit = viewModel::submitApiToken,
                )
                SetupStep.OidcInProgress -> OidcInProgressStep()
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
}

@Composable
private fun ServerUrlStep(
    url: String,
    isLoading: Boolean,
    error: String?,
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
            placeholder = { Text("https://vikunja.example.com") },
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
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            enabled = !isLoading,
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && url.isNotBlank(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Continue")
        }
    }
}

@Composable
private fun AuthMethodPickerStep(
    localAuthEnabled: Boolean,
    oidcProviders: List<com.rendyhd.vicu.data.remote.api.OidcProviderDto>,
    error: String?,
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

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            enabled = !isLoading,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (showTotpField) ImeAction.Next else ImeAction.Go,
            ),
            keyboardActions = if (!showTotpField) KeyboardActions(onGo = { onSubmit() }) else KeyboardActions.Default,
            enabled = !isLoading,
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
            )
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Sign In")
        }
    }
}

@Composable
private fun ApiTokenEntryStep(
    apiToken: String,
    isLoading: Boolean,
    error: String?,
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

        OutlinedTextField(
            value = apiToken,
            onValueChange = onTokenChange,
            label = { Text("API Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
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
        modifier = Modifier.fillMaxWidth(),
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
