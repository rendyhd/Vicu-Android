package com.rendyhd.vicu.ui.screens.setup

import com.rendyhd.vicu.data.repository.SyncedPreferences
import com.rendyhd.vicu.util.Logger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AccountSession
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.LoginDataAction
import com.rendyhd.vicu.auth.LoginPlan
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.OidcSignInMode
import com.rendyhd.vicu.auth.OidcResult
import com.rendyhd.vicu.auth.PasswordLoginHandler
import com.rendyhd.vicu.auth.PasswordLoginResult
import com.rendyhd.vicu.auth.isTotpPasscodeComplete
import com.rendyhd.vicu.auth.sanitizeTotpPasscode
import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.auth.PlatformAuthHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class SetupStep {
    ServerUrl,
    AuthMethodPicker,
    PasswordLogin,
    OidcTotp,
    ApiTokenEntry,
    /** OIDC sign-in in the system browser, for a provider that signs in with a passkey. */
    OidcBrowser,
    OidcInProgress,
    ProjectSelection,
}

data class SetupUiState(
    val step: SetupStep = SetupStep.ServerUrl,
    /** Empty on a fresh install (the field then shows Vikunja Cloud as its hint); the stored URL on re-authentication. */
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
    /** Where the OIDC sign-in runs: the in-app web view, or the browser ([SetupStep.OidcBrowser]). */
    val oidcMode: OidcSignInMode = OidcSignInMode.WebView,
    /** The web view met a passkey prompt, so the browser step says why it is there. */
    val passkeyPrompted: Boolean = false,
    val projects: List<Project> = emptyList(),
    val selectedProjectId: Long? = null,
    val setupComplete: Boolean = false,
    /** Set when signing in would discard queued changes of a different account; needs a decision. */
    val discardPrompt: DiscardPrompt? = null,
) {
    /** The address is plain http:// to a host outside the user's own network: warn, do not block. */
    val showCleartextWarning: Boolean get() = isCleartextToRemoteHost(serverUrl)

    /**
     * Whether Back (the arrow or the system gesture) steps back. Not from the first step, not once
     * the sign-in has gone through (the method picker is behind us then), and not while a request
     * is running, except the SSO wait, which the user has to be able to leave.
     */
    val canGoBack: Boolean
        get() = when (step) {
            SetupStep.ServerUrl, SetupStep.ProjectSelection -> false
            SetupStep.OidcInProgress -> true
            else -> !isLoading
        }
}

data class DiscardPrompt(val unsyncedChanges: Int)

class SetupViewModel(
    private val apiService: VikunjaApiService,
    private val authManager: AuthManager,
    private val baseUrlHolder: BaseUrlHolder,
    private val passwordLoginHandler: PasswordLoginHandler,
    private val oidcHandler: OidcHandler,
    private val accountSession: AccountSession,
    private val platformAuthHooks: PlatformAuthHooks,
    private val syncStaleness: SyncStaleness,
    private val repositoryHooks: PlatformRepositoryHooks,
    /** The Inbox the account's other devices chose is preselected, and this choice reaches them. */
    private val syncedPreferences: SyncedPreferences? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "SetupViewModel"
        private const val DEFAULT_SERVER_URL = "https://app.vikunja.cloud"
    }

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        // Signing in again (an expired session, a new account on the same server) starts from the
        // address already on record. Anything typed in the meantime wins.
        viewModelScope.launch {
            val stored = authManager.getVikunjaUrl()?.trim().orEmpty()
            if (stored.isNotEmpty()) {
                _uiState.update { if (it.serverUrl.isBlank()) it.copy(serverUrl = stored) else it }
            }
        }
    }

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
        _uiState.update { it.copy(totpPasscode = sanitizeTotpPasscode(passcode), error = null) }
    }

    fun updateApiToken(token: String) {
        _uiState.update { it.copy(apiToken = token, error = null) }
    }

    fun selectProject(projectId: Long) {
        _uiState.update { it.copy(selectedProjectId = projectId) }
    }

    fun discoverServer() {
        val url = _uiState.value.serverUrl.trim().ifBlank { DEFAULT_SERVER_URL }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val normalized = normalizeServerAddress(url)
                baseUrlHolder.baseUrl = normalized
                val info = apiService.getServerInfo()
                if (!isSupportedVikunjaVersion(info.version)) {
                    throw IllegalStateException(
                        "Vikunja 2.4.0 or newer is required (server reports ${info.version.ifBlank { "an unknown version" }})",
                    )
                }
                authManager.storeServerIsV2(true)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverUrl = normalized,
                        localAuthEnabled = info.auth.local.enabled,
                        oidcProviders = if (info.auth.openidConnect.enabled) info.auth.openidConnect.providers.orEmpty() else emptyList(),
                        step = SetupStep.AuthMethodPicker,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Server discovery failed", e)
                baseUrlHolder.baseUrl = ""
                _uiState.update {
                    it.copy(isLoading = false, error = "Could not connect to server: ${e.message}")
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

    /** A provider card of the picker: signs in in the app's web view. */
    fun selectOidcProvider(provider: OidcProviderDto) {
        _uiState.update {
            it.copy(
                selectedProvider = provider,
                oidcMode = OidcSignInMode.WebView,
                passkeyPrompted = false,
                totpPasscode = "",
                error = null,
            )
        }
    }

    /** Shows the browser sign-in; [passkeyPrompted] when the web view met a passkey prompt. */
    fun showOidcBrowser(provider: OidcProviderDto, passkeyPrompted: Boolean = false) {
        _uiState.update {
            it.copy(
                step = SetupStep.OidcBrowser,
                selectedProvider = provider,
                oidcMode = OidcSignInMode.Browser,
                passkeyPrompted = passkeyPrompted,
                totpPasscode = "",
                isLoading = false,
                error = null,
            )
        }
    }

    fun getOidcAuthParams(): OidcHandler.AuthParams? {
        val state = _uiState.value
        val provider = state.selectedProvider ?: return null
        return try {
            _uiState.update { it.copy(step = SetupStep.OidcInProgress, error = null) }
            oidcHandler.prepareAuthParams(provider, state.serverUrl, state.oidcMode)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to start OIDC: ${e.message}", step = oidcStartStep(it)) }
            null
        }
    }

    /** Where an OIDC attempt goes back to: the browser step it was started from, or the picker. */
    private fun oidcStartStep(state: SetupUiState): SetupStep =
        if (state.oidcMode == OidcSignInMode.Browser) SetupStep.OidcBrowser else SetupStep.AuthMethodPicker

    fun retryOidcWithTotp(): OidcHandler.AuthParams? {
        if (!isTotpPasscodeComplete(_uiState.value.totpPasscode)) {
            _uiState.update { it.copy(error = "Enter the 6-digit code from your authenticator app") }
            return null
        }
        return getOidcAuthParams()
    }

    fun handleOidcCallback(
        code: String?,
        state: String?,
        error: String?,
    ) {
        val provider = _uiState.value.selectedProvider ?: return
        val url = _uiState.value.serverUrl
        val totpPasscode = _uiState.value.totpPasscode.takeIf(::isTotpPasscodeComplete)

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = oidcHandler.handleCallbackResult(code, state, error, provider, url, totpPasscode)) {
                is OidcResult.Success -> verifyAndSignIn(
                    serverUrl = url,
                    token = result.token,
                    failurePrefix = "Could not verify your account",
                    createBackupToken = true,
                ) {
                    authManager.onLoginSuccess(result.token, "oidc", url, provider.key, result.refreshToken)
                }
                is OidcResult.NeedsTOTP -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            step = SetupStep.OidcTotp,
                            totpPasscode = "",
                            error = if (totpPasscode == null) null else result.message ?: "That code was not accepted",
                        )
                    }
                }
                is OidcResult.PasskeyUnavailable -> showOidcBrowser(provider, passkeyPrompted = true)
                is OidcResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message, step = oidcStartStep(it))
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
        if (state.showTotpField && !isTotpPasscodeComplete(state.totpPasscode)) {
            _uiState.update { it.copy(error = "Enter the 6-digit code from your authenticator app") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val totp = if (state.showTotpField) state.totpPasscode else null
            when (val result = passwordLoginHandler.login(state.username, state.password, totp)) {
                is PasswordLoginResult.Success -> verifyAndSignIn(
                    serverUrl = state.serverUrl,
                    token = result.token,
                    failurePrefix = "Could not verify your account",
                    createBackupToken = true,
                ) {
                    authManager.onLoginSuccess(result.token, "password", state.serverUrl, refreshToken = result.refreshToken)
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
            val serverUrl = _uiState.value.serverUrl
            // The token is checked against the server before any stored credential or local data
            // is touched, so a mistyped token changes nothing (it used to wipe the data and sign
            // the user out).
            verifyAndSignIn(
                serverUrl = serverUrl,
                token = token,
                failurePrefix = "Invalid API token",
                createBackupToken = false,
            ) {
                authManager.onApiTokenLogin(token, serverUrl)
            }
        }
    }

    /**
     * Verifies [token] with the server, decides what to do with local data, and signs in.
     *
     * Nothing stored is touched until the server has accepted the credentials. Local data is
     * wiped only when they belong to a different account than the one on record; if that would
     * discard queued changes the user is asked first (see [confirmDiscardAndSignIn]). A
     * re-login of the same account keeps the offline queue and routine history.
     */
    private suspend fun verifyAndSignIn(
        serverUrl: String,
        token: String,
        failurePrefix: String,
        createBackupToken: Boolean,
        storeCredentials: suspend () -> Unit,
    ) {
        val plan = try {
            accountSession.plan(accountSession.verify(serverUrl, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Could not verify the account", e)
            _uiState.update { it.copy(isLoading = false, error = "$failurePrefix: ${e.message}") }
            return
        }

        if (plan.action == LoginDataAction.WIPE_ALL && plan.unsyncedActionsLost > 0) {
            pendingLogin = PendingLogin(plan, createBackupToken, storeCredentials)
            _uiState.update {
                it.copy(isLoading = false, discardPrompt = DiscardPrompt(plan.unsyncedActionsLost))
            }
            return
        }
        completeSignIn(plan, createBackupToken, storeCredentials)
    }

    private class PendingLogin(
        val plan: LoginPlan,
        val createBackupToken: Boolean,
        val storeCredentials: suspend () -> Unit,
    )

    private var pendingLogin: PendingLogin? = null

    /** The user accepted losing the previous account's queued changes. */
    fun confirmDiscardAndSignIn() {
        val pending = pendingLogin ?: return
        pendingLogin = null
        viewModelScope.launch {
            _uiState.update { it.copy(discardPrompt = null, isLoading = true, error = null) }
            completeSignIn(pending.plan, pending.createBackupToken, pending.storeCredentials)
        }
    }

    /** The user kept the previous account's queued changes; nothing was touched. */
    fun cancelDiscard() {
        pendingLogin = null
        _uiState.update { it.copy(discardPrompt = null, isLoading = false) }
    }

    private suspend fun completeSignIn(
        plan: LoginPlan,
        createBackupToken: Boolean,
        storeCredentials: suspend () -> Unit,
    ) {
        try {
            // Wipe, credentials and identity are one step: cancelling between them would leave
            // an emptied database with the old account still signed in.
            withContext(NonCancellable) {
                accountSession.apply(plan)
                storeCredentials()
                accountSession.recordIdentity(plan.identity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Sign-in failed after verification", e)
            _uiState.update { it.copy(isLoading = false, error = "Sign-in failed: ${e.message}") }
            return
        }
        _uiState.update { it.copy(password = "", totpPasscode = "", apiToken = "") }
        if (createBackupToken) createBackupApiToken()
        fetchProjectsForSelection()
    }

    fun goBack() {
        _uiState.update { state ->
            if (!state.canGoBack) return@update state
            when (state.step) {
                SetupStep.AuthMethodPicker -> state.copy(step = SetupStep.ServerUrl, error = null)
                SetupStep.PasswordLogin -> state.copy(step = SetupStep.AuthMethodPicker, error = null, showTotpField = false)
                SetupStep.OidcTotp -> state.copy(step = oidcStartStep(state), error = null, totpPasscode = "")
                SetupStep.ApiTokenEntry -> state.copy(step = SetupStep.AuthMethodPicker, error = null)
                SetupStep.OidcBrowser -> state.copy(
                    step = SetupStep.AuthMethodPicker,
                    error = null,
                    oidcMode = OidcSignInMode.WebView,
                    passkeyPrompted = false,
                )
                SetupStep.OidcInProgress -> state.copy(step = oidcStartStep(state), error = null, isLoading = false)
                // Signed in already: there is no method left to pick, only the Inbox to choose.
                SetupStep.ServerUrl, SetupStep.ProjectSelection -> state
            }
        }
    }

    fun confirmSetup() {
        val projectId = _uiState.value.selectedProjectId ?: return
        viewModelScope.launch {
            syncedPreferences?.chooseInbox(projectId) ?: authManager.onInboxProjectSelected(projectId)
            // Whatever the cache holds is not this account's yet: the screens opening next must
            // refresh, and a sync starts now instead of waiting for the first screen to ask.
            syncStaleness.reset()
            _uiState.update { it.copy(setupComplete = true) }
            platformAuthHooks.updateWidgets()
            platformAuthHooks.scheduleRefresh()
            repositoryHooks.triggerSync()
        }
    }

    /** The synced Inbox after one settings sync, else Vikunja's default project; null when neither. */
    private suspend fun suggestedInbox(): Long? {
        val synced = try {
            syncedPreferences?.let { prefs ->
                prefs.sync()
                prefs.syncedInboxProjectId()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read the synced settings: ${e.message}")
            null
        }
        if (synced != null) return synced
        return try {
            apiService.getCurrentUser().settings?.defaultProjectId?.takeIf { it > 0L }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchProjectsForSelection() {
        try {
            val dtos = apiService.getAllProjects()
            val projects = dtos.map { dto ->
                Project(
                    id = dto.id,
                    title = dto.title,
                    parentProjectId = dto.parentProjectId,
                )
            }
            // The Inbox another device of this account chose (cross-app semantics 11.4), else the
            // default project of the Vikunja account, else a project called Inbox.
            val suggested = suggestedInbox()?.let { id -> projects.firstOrNull { it.id == id } }
            // Show only top-level projects for inbox selection, and the suggested one wherever it is.
            val topLevel = projects.filter { it.parentProjectId == 0L || it.id == suggested?.id }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    step = SetupStep.ProjectSelection,
                    projects = topLevel,
                    selectedProjectId = suggested?.id
                        ?: (topLevel.firstOrNull { it.title.equals("Inbox", ignoreCase = true) } ?: topLevel.firstOrNull())?.id,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = "Failed to fetch projects: ${e.message}") }
        }
    }

    private suspend fun createBackupApiToken() {
        // Delegate to AuthManager so the /routes-expansion logic lives in one place.
        // AuthManager logs both success and failure to AuthDebugLog; if creation fails here,
        // AuthManager.ensureBackupApiToken() will retry on the next app launch.
        val ok = authManager.createBackupApiToken()
        if (ok) {
            Logger.i(TAG, "Backup API token created successfully during setup")
        } else {
            Logger.w(TAG, "Backup API token creation failed during setup — AuthManager will retry on next launch")
        }
    }
}

internal fun isSupportedVikunjaVersion(version: String): Boolean {
    val parts = version
        .trim()
        .trimStart('v', 'V')
        .split('.')
        .map { it.substringBefore('-').toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    return major > 2 || (major == 2 && minor >= 4)
}

/**
 * The server address as typed in Setup, made into the address the app adds `/api/v2/` to: a
 * scheme when none was typed (https), and without trailing slashes or a trailing `/api/v1`,
 * `/api/v2` or `/api`, which people copy from the API documentation or another client.
 */
internal fun normalizeServerAddress(input: String): String {
    val trimmed = input.trim()
    val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
    var address = withScheme.trimEnd('/')
    for (suffix in listOf("/api/v1", "/api/v2", "/api")) {
        if (address.endsWith(suffix, ignoreCase = true)) {
            address = address.dropLast(suffix.length).trimEnd('/')
            break
        }
    }
    return address
}
