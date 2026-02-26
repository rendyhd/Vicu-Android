package com.rendyhd.vicu.ui.screens.setup

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.OidcResult
import com.rendyhd.vicu.auth.PasswordLoginHandler
import com.rendyhd.vicu.auth.PasswordLoginResult
import com.rendyhd.vicu.data.remote.api.ApiTokenRequestDto
import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import com.rendyhd.vicu.domain.model.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class SetupStep {
    ServerUrl,
    AuthMethodPicker,
    PasswordLogin,
    ApiTokenEntry,
    OidcInProgress,
    ProjectSelection,
}

data class SetupUiState(
    val step: SetupStep = SetupStep.ServerUrl,
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val localAuthEnabled: Boolean = false,
    val oidcProviders: List<OidcProviderDto> = emptyList(),
    val username: String = "",
    val password: String = "",
    val totpPasscode: String = "",
    val showTotpField: Boolean = false,
    val apiToken: String = "",
    val selectedProvider: OidcProviderDto? = null,
    val projects: List<Project> = emptyList(),
    val selectedProjectId: Long? = null,
    val setupComplete: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val apiService: dagger.Lazy<VikunjaApiService>,
    private val authManager: AuthManager,
    private val baseUrlHolder: BaseUrlHolder,
    private val passwordLoginHandler: PasswordLoginHandler,
    private val oidcHandler: OidcHandler,
) : ViewModel() {

    companion object {
        private const val TAG = "SetupViewModel"
    }

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url, error = null) }
    }

    fun updateUsername(username: String) {
        _uiState.update { it.copy(username = username, error = null) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun updateTotpPasscode(passcode: String) {
        _uiState.update { it.copy(totpPasscode = passcode, error = null) }
    }

    fun updateApiToken(token: String) {
        _uiState.update { it.copy(apiToken = token, error = null) }
    }

    fun selectProject(projectId: Long) {
        _uiState.update { it.copy(selectedProjectId = projectId) }
    }

    fun discoverServer() {
        val url = _uiState.value.serverUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a server URL") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                baseUrlHolder.baseUrl = normalized
                val info = apiService.get().getServerInfo()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverUrl = normalized,
                        localAuthEnabled = info.auth.local.enabled,
                        oidcProviders = if (info.auth.openidConnect.enabled) info.auth.openidConnect.providers else emptyList(),
                        step = SetupStep.AuthMethodPicker,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server discovery failed", e)
                baseUrlHolder.baseUrl = ""
                _uiState.update {
                    it.copy(isLoading = false, error = "Could not connect to server: ${e.localizedMessage}")
                }
            }
        }
    }

    fun selectPasswordLogin() {
        _uiState.update {
            it.copy(step = SetupStep.PasswordLogin, error = null, showTotpField = false, totpPasscode = "")
        }
    }

    fun selectApiTokenEntry() {
        _uiState.update { it.copy(step = SetupStep.ApiTokenEntry, error = null) }
    }

    fun selectOidcProvider(provider: OidcProviderDto) {
        _uiState.update { it.copy(selectedProvider = provider, error = null) }
    }

    fun getOidcAuthIntent(): Intent? {
        val provider = _uiState.value.selectedProvider ?: return null
        val url = _uiState.value.serverUrl
        return try {
            _uiState.update { it.copy(step = SetupStep.OidcInProgress) }
            oidcHandler.buildAuthIntent(provider, url)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to start OIDC: ${e.localizedMessage}", step = SetupStep.AuthMethodPicker) }
            null
        }
    }

    fun handleOidcResult(intent: Intent?) {
        if (intent == null) {
            _uiState.update { it.copy(error = "OIDC was cancelled", step = SetupStep.AuthMethodPicker) }
            return
        }
        val provider = _uiState.value.selectedProvider ?: return
        val url = _uiState.value.serverUrl

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = oidcHandler.handleCallback(intent, provider, url)) {
                is OidcResult.Success -> {
                    authManager.onLoginSuccess(result.token, "oidc", url, provider.key)
                    createBackupApiToken()
                    fetchProjectsForSelection()
                }
                is OidcResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message, step = SetupStep.AuthMethodPicker)
                    }
                }
            }
        }
    }

    fun submitPasswordLogin() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Please enter username and password") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val totp = if (state.showTotpField) state.totpPasscode else null
            when (val result = passwordLoginHandler.login(state.username, state.password, totp)) {
                is PasswordLoginResult.Success -> {
                    authManager.onLoginSuccess(result.token, "password", state.serverUrl)
                    createBackupApiToken()
                    fetchProjectsForSelection()
                }
                is PasswordLoginResult.NeedsTOTP -> {
                    _uiState.update { it.copy(isLoading = false, showTotpField = true, error = null) }
                }
                is PasswordLoginResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    fun submitApiToken() {
        val token = _uiState.value.apiToken.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(error = "Please enter an API token") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                authManager.onApiTokenLogin(token, _uiState.value.serverUrl)
                // Validate the token by fetching current user
                apiService.get().getCurrentUser()
                fetchProjectsForSelection()
            } catch (e: Exception) {
                authManager.logout()
                _uiState.update { it.copy(isLoading = false, error = "Invalid API token: ${e.localizedMessage}") }
            }
        }
    }

    fun goBack() {
        _uiState.update { state ->
            when (state.step) {
                SetupStep.AuthMethodPicker -> state.copy(step = SetupStep.ServerUrl, error = null)
                SetupStep.PasswordLogin -> state.copy(step = SetupStep.AuthMethodPicker, error = null, showTotpField = false)
                SetupStep.ApiTokenEntry -> state.copy(step = SetupStep.AuthMethodPicker, error = null)
                SetupStep.OidcInProgress -> state.copy(step = SetupStep.AuthMethodPicker, error = null)
                SetupStep.ProjectSelection -> state.copy(step = SetupStep.AuthMethodPicker, error = null)
                else -> state
            }
        }
    }

    fun confirmSetup() {
        val projectId = _uiState.value.selectedProjectId ?: return
        viewModelScope.launch {
            authManager.onInboxProjectSelected(projectId)
            _uiState.update { it.copy(setupComplete = true) }
        }
    }

    private suspend fun fetchProjectsForSelection() {
        try {
            val dtos = apiService.get().getAllProjects()
            val projects = dtos.map { dto ->
                Project(
                    id = dto.id,
                    title = dto.title,
                    parentProjectId = dto.parentProjectId,
                )
            }
            // Show only top-level projects for inbox selection
            val topLevel = projects.filter { it.parentProjectId == 0L }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    step = SetupStep.ProjectSelection,
                    projects = topLevel,
                    selectedProjectId = topLevel.firstOrNull()?.id,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = "Failed to fetch projects: ${e.localizedMessage}") }
        }
    }

    private suspend fun createBackupApiToken() {
        try {
            val expiry = Instant.now().plusSeconds(365L * 24 * 60 * 60)
            val expiryStr = DateTimeFormatter.ISO_INSTANT.format(expiry)
            val request = ApiTokenRequestDto(
                title = "Vicu Android Backup",
                expiresAt = expiryStr,
            )
            val response = apiService.get().createApiToken(request)
            if (response.token.isNotBlank()) {
                authManager.onApiTokenSaved(response.token, expiry.epochSecond)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backup API token creation failed (non-fatal)", e)
        }
    }
}
