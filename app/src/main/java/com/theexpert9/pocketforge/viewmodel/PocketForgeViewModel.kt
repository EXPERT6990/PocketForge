package com.theexpert9.pocketforge.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theexpert9.pocketforge.data.PocketForgeRepository
import com.theexpert9.pocketforge.data.ai.AgentRunProgress
import com.theexpert9.pocketforge.data.auth.GoogleAuthManager
import com.theexpert9.pocketforge.data.auth.GoogleProfileState
import com.theexpert9.pocketforge.data.auth.GoogleSignInOutcome
import com.theexpert9.pocketforge.data.db.CatalogModelEntity
import com.theexpert9.pocketforge.data.db.ChatMessageEntity
import com.theexpert9.pocketforge.data.db.ChatSessionEntity
import com.theexpert9.pocketforge.data.db.DefaultSeeds
import com.theexpert9.pocketforge.data.db.FileSnapshotEntity
import com.theexpert9.pocketforge.data.db.PromptProfileEntity
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import com.theexpert9.pocketforge.data.db.TerminalEntryEntity
import com.theexpert9.pocketforge.data.db.WorkspaceEntity
import com.theexpert9.pocketforge.data.sandbox.ClipboardPatchProposal
import com.theexpert9.pocketforge.data.sandbox.GrepMatch
import com.theexpert9.pocketforge.data.sandbox.WorkspaceFileNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab {
    AGENT,
    SERVICES,
    FILES,
    TERMINAL,
    CONFIGURE
}

sealed interface ServiceVerifyState {
    data object Idle : ServiceVerifyState
    data object Checking : ServiceVerifyState
    data class Verified(val detail: String) : ServiceVerifyState
    data class Failed(val reason: String) : ServiceVerifyState
}

data class StatusBanner(
    val message: String,
    val isError: Boolean = false
)

data class OpenFileEditorState(
    val relativePath: String,
    val content: String,
    val savedContent: String
) {
    val isDirty: Boolean get() = content != savedContent
}

data class PocketForgeUiState(
    val isBooting: Boolean = true,
    val activeTab: MainTab = MainTab.AGENT,
    val workspaces: List<WorkspaceEntity> = emptyList(),
    val activeWorkspaceId: String? = null,
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val snapshots: List<FileSnapshotEntity> = emptyList(),
    val providers: List<ProviderConfigEntity> = emptyList(),
    val catalogModels: List<CatalogModelEntity> = emptyList(),
    val promptProfiles: List<PromptProfileEntity> = emptyList(),
    val terminalEntries: List<TerminalEntryEntity> = emptyList(),
    val terminalWorkingDir: String = "",
    val workspaceFiles: List<WorkspaceFileNode> = emptyList(),
    val openEditor: OpenFileEditorState? = null,
    val grepQuery: String = "",
    val grepResults: List<GrepMatch> = emptyList(),
    val isAgentRunning: Boolean = false,
    val agentProgress: AgentRunProgress? = null,
    val lastAgentError: String? = null,
    val isSyncingCatalog: Boolean = false,
    val catalogSearchQuery: String = "",
    val testingProviderId: String? = null,
    val serviceVerificationMap: Map<String, ServiceVerifyState> = emptyMap(),
    val keyVaultRevision: Int = 0,
    val googleProfile: GoogleProfileState = GoogleProfileState(),
    val banner: StatusBanner? = null,
    val draftPrompt: String = ""
) {
    val activeWorkspace: WorkspaceEntity?
        get() = workspaces.firstOrNull { it.id == activeWorkspaceId } ?: workspaces.firstOrNull()

    val activeSession: ChatSessionEntity?
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()

    val activeProvider: ProviderConfigEntity?
        get() = providers.firstOrNull { it.id == activeSession?.providerId } ?: providers.firstOrNull()

    val activePromptProfile: PromptProfileEntity?
        get() = promptProfiles.firstOrNull { it.id == activeWorkspace?.activePromptProfileId }
            ?: promptProfiles.firstOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class PocketForgeViewModel(
    private val repo: PocketForgeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PocketForgeUiState())
    val uiState: StateFlow<PocketForgeUiState> = _uiState.asStateFlow()

    private val selectedWorkspaceIdFlow = MutableStateFlow<String?>(null)
    private val selectedSessionIdFlow = MutableStateFlow<String?>(null)
    private var activeAgentJob: Job? = null
    private var lastSubmittedPrompt: String? = null
    private var silentAuthAttempted = false

    init {
        observeGlobalStreams()
        observeWorkspaceScopedStreams()
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            try {
                val (ws, session) = repo.ensureSeeded()
                selectedWorkspaceIdFlow.value = ws.id
                selectedSessionIdFlow.value = session.id
                _uiState.update {
                    it.copy(
                        isBooting = false,
                        activeWorkspaceId = ws.id,
                        activeSessionId = session.id
                    )
                }
                refreshWorkspaceFiles(ws.rootDirName)
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                _uiState.update {
                    it.copy(
                        isBooting = false,
                        banner = StatusBanner("Failed to initialize workspace: ${e.localizedMessage}", isError = true)
                    )
                }
            }
        }
    }

    private fun observeGlobalStreams() {
        viewModelScope.launch {
            repo.workspaces.collectLatest { list ->
                _uiState.update { it.copy(workspaces = list) }
            }
        }
        viewModelScope.launch {
            repo.providers.collectLatest { list ->
                _uiState.update { it.copy(providers = list) }
            }
        }
        viewModelScope.launch {
            repo.catalogModels.collectLatest { list ->
                _uiState.update { it.copy(catalogModels = list) }
            }
        }
        viewModelScope.launch {
            repo.promptProfiles.collectLatest { list ->
                _uiState.update { it.copy(promptProfiles = list) }
            }
        }
    }

    private fun observeWorkspaceScopedStreams() {
        viewModelScope.launch {
            selectedWorkspaceIdFlow
                .flatMapLatest { wsId ->
                    if (wsId == null) flowOf(emptyList()) else repo.observeSessions(wsId)
                }
                .collectLatest { sessions ->
                    val currentId = selectedSessionIdFlow.value
                    val validId = sessions.firstOrNull { it.id == currentId }?.id ?: sessions.firstOrNull()?.id
                    if (validId != currentId) {
                        selectedSessionIdFlow.value = validId
                    }
                    _uiState.update {
                        it.copy(sessions = sessions, activeSessionId = validId)
                    }
                }
        }

        viewModelScope.launch {
            selectedSessionIdFlow
                .flatMapLatest { sessionId ->
                    if (sessionId == null) flowOf(emptyList()) else repo.observeMessages(sessionId)
                }
                .collectLatest { msgs ->
                    _uiState.update { it.copy(messages = msgs) }
                }
        }

        viewModelScope.launch {
            selectedWorkspaceIdFlow
                .flatMapLatest { wsId ->
                    if (wsId == null) flowOf(emptyList()) else repo.observeSnapshots(wsId)
                }
                .collectLatest { snaps ->
                    _uiState.update { it.copy(snapshots = snaps) }
                }
        }

        viewModelScope.launch {
            selectedWorkspaceIdFlow
                .flatMapLatest { wsId ->
                    if (wsId == null) flowOf(emptyList()) else repo.observeTerminal(wsId)
                }
                .collectLatest { entries ->
                    _uiState.update { it.copy(terminalEntries = entries) }
                }
        }
    }

    // --- Native Google Sign-In Profile (Credential Manager + Firebase Auth) ---

    fun syncGoogleAuthOnLaunch(context: Context) {
        val current = GoogleAuthManager.currentUser(context)
        if (current != null) {
            _uiState.update { it.copy(googleProfile = GoogleProfileState.fromFirebaseUser(current)) }
            return
        }
        if (silentAuthAttempted) return
        silentAuthAttempted = true
        viewModelScope.launch {
            val signedInUser = GoogleAuthManager.attemptAutoSignIn(context)
            if (signedInUser != null) {
                _uiState.update { it.copy(googleProfile = GoogleProfileState.fromFirebaseUser(signedInUser)) }
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        if (_uiState.value.googleProfile.isAuthenticating) return
        _uiState.update {
            it.copy(
                googleProfile = it.googleProfile.copy(isAuthenticating = true, authError = null)
            )
        }
        viewModelScope.launch {
            when (val outcome = GoogleAuthManager.signInWithGoogle(context)) {
                is GoogleSignInOutcome.Success -> {
                    val profile = GoogleProfileState.fromFirebaseUser(outcome.user)
                    _uiState.update {
                        it.copy(
                            googleProfile = profile,
                            banner = StatusBanner("Signed into PocketForge as ${profile.displayName ?: "Google User"}")
                        )
                    }
                }
                is GoogleSignInOutcome.Cancelled -> {
                    _uiState.update {
                        it.copy(
                            googleProfile = it.googleProfile.copy(isAuthenticating = false, authError = null)
                        )
                    }
                }
                is GoogleSignInOutcome.Error -> {
                    _uiState.update {
                        it.copy(
                            googleProfile = it.googleProfile.copy(
                                isAuthenticating = false,
                                authError = outcome.message
                            ),
                            banner = StatusBanner(outcome.message, isError = true)
                        )
                    }
                }
            }
        }
    }

    fun signOutGoogle(context: Context) {
        viewModelScope.launch {
            GoogleAuthManager.signOut(context)
            _uiState.update {
                it.copy(
                    googleProfile = GoogleProfileState(),
                    banner = StatusBanner("Signed out of Google account. Local workspaces remain intact on device.")
                )
            }
        }
    }

    // --- Navigation & UI State ---

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == MainTab.FILES) {
            _uiState.value.activeWorkspace?.let { refreshWorkspaceFiles(it.rootDirName) }
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(banner = null) }
    }

    fun updateDraftPrompt(text: String) {
        _uiState.update { it.copy(draftPrompt = text) }
    }

    // --- Workspace & Session Actions ---

    fun selectWorkspace(workspace: WorkspaceEntity) {
        stopAgent()
        selectedWorkspaceIdFlow.value = workspace.id
        _uiState.update {
            it.copy(
                activeWorkspaceId = workspace.id,
                terminalWorkingDir = "",
                openEditor = null,
                grepQuery = "",
                grepResults = emptyList(),
                lastAgentError = null
            )
        }
        refreshWorkspaceFiles(workspace.rootDirName)
    }

    fun createWorkspace(name: String, description: String, template: String) {
        viewModelScope.launch {
            try {
                val (ws, session) = repo.createWorkspace(name, description, template)
                selectedWorkspaceIdFlow.value = ws.id
                selectedSessionIdFlow.value = session.id
                _uiState.update {
                    it.copy(
                        activeWorkspaceId = ws.id,
                        activeSessionId = session.id,
                        terminalWorkingDir = "",
                        openEditor = null,
                        lastAgentError = null,
                        banner = StatusBanner("Created isolated workspace '${ws.name}'")
                    )
                }
                refreshWorkspaceFiles(ws.rootDirName)
            } catch (e: Exception) {
                showError("Could not create workspace: ${e.localizedMessage}")
            }
        }
    }

    fun deleteWorkspace(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            try {
                repo.deleteWorkspace(workspace)
                val (nextWs, nextSession) = repo.ensureSeeded()
                selectedWorkspaceIdFlow.value = nextWs.id
                selectedSessionIdFlow.value = nextSession.id
                _uiState.update {
                    it.copy(
                        activeWorkspaceId = nextWs.id,
                        activeSessionId = nextSession.id,
                        openEditor = null,
                        lastAgentError = null,
                        banner = StatusBanner("Deleted workspace '${workspace.name}'")
                    )
                }
                refreshWorkspaceFiles(nextWs.rootDirName)
            } catch (e: Exception) {
                showError("Failed to delete workspace: ${e.localizedMessage}")
            }
        }
    }

    fun selectSession(session: ChatSessionEntity) {
        stopAgent()
        selectedSessionIdFlow.value = session.id
        _uiState.update { it.copy(activeSessionId = session.id, lastAgentError = null) }
    }

    fun createSession(title: String = "") {
        val ws = _uiState.value.activeWorkspace ?: return
        val currentSession = _uiState.value.activeSession
        viewModelScope.launch {
            val nextNum = _uiState.value.sessions.size + 1
            val session = repo.createSession(
                workspaceId = ws.id,
                title = title.ifBlank { "Session $nextNum" },
                providerId = currentSession?.providerId ?: DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
                modelId = currentSession?.modelId ?: DefaultSeeds.DEFAULT_GEMINI_MODEL
            )
            selectedSessionIdFlow.value = session.id
            _uiState.update { it.copy(activeSessionId = session.id, lastAgentError = null) }
        }
    }

    fun deleteSession(session: ChatSessionEntity) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            repo.deleteSession(session.id)
            if (_uiState.value.sessions.size <= 1) {
                val fresh = repo.createSession(ws.id, "Session 1")
                selectedSessionIdFlow.value = fresh.id
            }
        }
    }

    fun clearActiveSession() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch {
            repo.clearSessionMessages(sessionId)
            _uiState.update { it.copy(lastAgentError = null) }
        }
    }

    fun selectModelForActiveSession(providerId: String, modelId: String) {
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            repo.updateSessionModel(session.id, providerId, modelId)
            _uiState.update {
                it.copy(banner = StatusBanner("Switched session model to $modelId"))
            }
        }
    }

    fun activateProviderAsCurrent(provider: ProviderConfigEntity) {
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            repo.updateSessionModel(session.id, provider.id, provider.selectedModelId)
            _uiState.update {
                it.copy(banner = StatusBanner("Active AI service set to ${provider.displayName} (${provider.selectedModelId})"))
            }
        }
    }

    fun selectPromptProfileForWorkspace(profileId: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            repo.updateWorkspacePromptProfile(ws.id, profileId)
        }
    }

    // --- Autonomous Agent (with Resilient 503 Retry & Resume) ---

    fun sendAgentPrompt(overridePrompt: String? = null) {
        val prompt = (overridePrompt ?: _uiState.value.draftPrompt).trim()
        if (prompt.isEmpty() || _uiState.value.isAgentRunning) return
        lastSubmittedPrompt = prompt
        launchAgentLoop(
            prompt = prompt,
            appendUserMessage = true,
            clearDraft = overridePrompt == null
        )
    }

    fun retryLastAgentTurn() {
        if (_uiState.value.isAgentRunning) return
        val fallbackPrompt = lastSubmittedPrompt
            ?: _uiState.value.messages.lastOrNull { it.role == com.theexpert9.pocketforge.data.db.MessageRole.USER }?.content
            ?: return
        launchAgentLoop(
            prompt = fallbackPrompt,
            appendUserMessage = false,
            clearDraft = false
        )
    }

    private fun launchAgentLoop(
        prompt: String,
        appendUserMessage: Boolean,
        clearDraft: Boolean
    ) {
        val ws = _uiState.value.activeWorkspace ?: return
        val session = _uiState.value.activeSession ?: return

        _uiState.update {
            it.copy(
                draftPrompt = if (clearDraft) "" else it.draftPrompt,
                isAgentRunning = true,
                lastAgentError = null,
                agentProgress = AgentRunProgress(1, it.activePromptProfile?.maxAutonomousSteps ?: 8, null, ""),
                banner = null
            )
        }

        activeAgentJob = viewModelScope.launch {
            try {
                repo.runAgentPrompt(
                    workspace = ws,
                    session = session,
                    userPrompt = prompt,
                    appendUserMessage = appendUserMessage,
                    onProgress = { progress ->
                        _uiState.update { it.copy(agentProgress = progress) }
                    },
                    onWorkspaceMutated = {
                        refreshWorkspaceFiles(ws.rootDirName)
                        reloadOpenEditorIfNeeded(ws.rootDirName)
                    }
                )
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(banner = StatusBanner("Agent run stopped."))
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                val message = e.localizedMessage ?: "Agent run failed."
                _uiState.update {
                    it.copy(
                        lastAgentError = message,
                        banner = StatusBanner(message, isError = true)
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(isAgentRunning = false, agentProgress = null)
                }
            }
        }
    }

    fun stopAgent() {
        activeAgentJob?.cancel()
        activeAgentJob = null
        _uiState.update { it.copy(isAgentRunning = false, agentProgress = null) }
    }

    fun revertSnapshot(snapshotId: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            try {
                val msg = repo.revertSnapshot(ws, snapshotId)
                refreshWorkspaceFiles(ws.rootDirName)
                reloadOpenEditorIfNeeded(ws.rootDirName)
                _uiState.update { it.copy(banner = StatusBanner(msg)) }
            } catch (e: Exception) {
                showError("Rollback failed: ${e.localizedMessage}")
            }
        }
    }

    // --- File Explorer & Editor ---

    fun refreshWorkspaceFiles(rootDirName: String? = _uiState.value.activeWorkspace?.rootDirName) {
        if (rootDirName == null) return
        viewModelScope.launch {
            runCatching { repo.listFiles(rootDirName) }
                .onSuccess { nodes -> _uiState.update { it.copy(workspaceFiles = nodes) } }
        }
    }

    fun openFile(relativePath: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            try {
                val text = repo.readFile(ws.rootDirName, relativePath)
                _uiState.update {
                    it.copy(
                        openEditor = OpenFileEditorState(
                            relativePath = relativePath,
                            content = text,
                            savedContent = text
                        )
                    )
                }
            } catch (e: Exception) {
                showError("Cannot open $relativePath: ${e.localizedMessage}")
            }
        }
    }

    fun updateEditorContent(newContent: String) {
        _uiState.update { state ->
            state.openEditor?.let { ed ->
                state.copy(openEditor = ed.copy(content = newContent))
            } ?: state
        }
    }

    fun saveOpenFile() {
        val ws = _uiState.value.activeWorkspace ?: return
        val editor = _uiState.value.openEditor ?: return
        viewModelScope.launch {
            try {
                repo.saveFileWithSnapshot(ws, editor.relativePath, editor.content)
                _uiState.update {
                    it.copy(
                        openEditor = editor.copy(savedContent = editor.content),
                        banner = StatusBanner("Saved ${editor.relativePath}")
                    )
                }
                refreshWorkspaceFiles(ws.rootDirName)
            } catch (e: Exception) {
                showError("Save failed: ${e.localizedMessage}")
            }
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(openEditor = null) }
    }

    fun createNewFile(relativePath: String, initialContent: String = "") {
        val ws = _uiState.value.activeWorkspace ?: return
        val clean = relativePath.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.saveFileWithSnapshot(ws, clean, initialContent)
                refreshWorkspaceFiles(ws.rootDirName)
                openFile(clean)
            } catch (e: Exception) {
                showError("Could not create file: ${e.localizedMessage}")
            }
        }
    }

    fun createNewFolder(relativePath: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        val clean = relativePath.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.createFolder(ws, clean)
                refreshWorkspaceFiles(ws.rootDirName)
                _uiState.update { it.copy(banner = StatusBanner("Created folder $clean/")) }
            } catch (e: Exception) {
                showError("Could not create folder: ${e.localizedMessage}")
            }
        }
    }

    fun deleteFile(relativePath: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            try {
                repo.deleteFileWithSnapshot(ws, relativePath)
                if (_uiState.value.openEditor?.relativePath == relativePath) {
                    _uiState.update { it.copy(openEditor = null) }
                }
                refreshWorkspaceFiles(ws.rootDirName)
                _uiState.update { it.copy(banner = StatusBanner("Deleted $relativePath")) }
            } catch (e: Exception) {
                showError("Delete failed: ${e.localizedMessage}")
            }
        }
    }

    fun runWorkspaceGrep(query: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        _uiState.update { it.copy(grepQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(grepResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            runCatching { repo.grepWorkspace(ws.rootDirName, query) }
                .onSuccess { hits -> _uiState.update { it.copy(grepResults = hits) } }
        }
    }

    fun askAiAboutFile(relativePath: String) {
        _uiState.update {
            it.copy(
                activeTab = MainTab.AGENT,
                draftPrompt = "Inspect $relativePath using read_file_range and review it for bugs or improvements."
            )
        }
    }

    private fun reloadOpenEditorIfNeeded(rootDirName: String) {
        val open = _uiState.value.openEditor ?: return
        if (open.isDirty) return
        viewModelScope.launch {
            runCatching { repo.readFile(rootDirName, open.relativePath) }
                .onSuccess { fresh ->
                    _uiState.update { state ->
                        if (state.openEditor?.relativePath == open.relativePath && !state.openEditor.isDirty) {
                            state.copy(openEditor = open.copy(content = fresh, savedContent = fresh))
                        } else state
                    }
                }
        }
    }

    // --- Sandboxed Terminal ---

    fun executeTerminalCommand(command: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        val cwd = _uiState.value.terminalWorkingDir
        viewModelScope.launch {
            val res = repo.runTerminalCommand(ws, cwd, command)
            _uiState.update { it.copy(terminalWorkingDir = res.newWorkingDir) }
            refreshWorkspaceFiles(ws.rootDirName)
        }
    }

    fun clearTerminal() {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch { repo.clearTerminal(ws.id) }
    }

    fun sendTerminalEntryToAgent(entry: TerminalEntryEntity) {
        val prompt = buildString {
            appendLine("Debug this workspace terminal output:")
            appendLine("Command: `${entry.command}` (exit ${entry.exitCode})")
            appendLine("Output:")
            appendLine(entry.output.take(1200))
        }
        _uiState.update {
            it.copy(activeTab = MainTab.AGENT, draftPrompt = prompt)
        }
    }

    // --- Providers, Services Hub, OpenCode Catalog & Prompt Studio ---

    fun updateCatalogSearch(query: String) {
        _uiState.update { it.copy(catalogSearchQuery = query) }
    }

    fun syncOpenCodeCatalog() {
        if (_uiState.value.isSyncingCatalog) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingCatalog = true) }
            try {
                val res = repo.syncOpenCodeCatalog()
                _uiState.update {
                    it.copy(
                        isSyncingCatalog = false,
                        banner = StatusBanner("Synced ${res.models.size} models across ${res.providerCount} OpenCode providers.")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenCode catalog sync failed", e)
                _uiState.update {
                    it.copy(
                        isSyncingCatalog = false,
                        banner = StatusBanner("Catalog sync error: ${e.localizedMessage}", isError = true)
                    )
                }
            }
        }
    }

    fun activateCatalogModel(model: CatalogModelEntity) {
        viewModelScope.launch {
            try {
                val provider = repo.importFromOpenCodeCatalog(model)
                _uiState.value.activeSession?.let { session ->
                    repo.updateSessionModel(session.id, provider.id, model.modelId)
                }
                val needsKey = provider.protocol != ProviderProtocol.FIREBASE_GEMINI &&
                    !repo.hasApiKey(provider.apiKeyAlias) &&
                    !provider.baseUrl.contains("11434")
                val note = if (needsKey) {
                    "Selected ${model.modelName}. Add your ${provider.displayName} API key in Services or Configure to start chatting."
                } else {
                    "Active model switched to ${model.modelName} (${provider.displayName})."
                }
                _uiState.update { it.copy(banner = StatusBanner(note)) }
            } catch (e: Exception) {
                showError("Failed to select model: ${e.localizedMessage}")
            }
        }
    }

    fun saveProvider(config: ProviderConfigEntity, newApiKey: String?, setActiveAfterSave: Boolean = false) {
        viewModelScope.launch {
            try {
                repo.saveProviderConfig(config, newApiKey)
                if (setActiveAfterSave) {
                    _uiState.value.activeSession?.let { session ->
                        repo.updateSessionModel(session.id, config.id, config.selectedModelId)
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        keyVaultRevision = state.keyVaultRevision + 1,
                        serviceVerificationMap = state.serviceVerificationMap - config.id,
                        banner = StatusBanner("Saved ${config.displayName} configuration (key encrypted locally).")
                    )
                }
            } catch (e: Exception) {
                showError("Could not save provider: ${e.localizedMessage}")
            }
        }
    }

    fun disconnectProviderKey(config: ProviderConfigEntity) {
        viewModelScope.launch {
            try {
                repo.disconnectProviderApiKey(config.apiKeyAlias)
                _uiState.update { state ->
                    state.copy(
                        keyVaultRevision = state.keyVaultRevision + 1,
                        serviceVerificationMap = state.serviceVerificationMap - config.id,
                        banner = StatusBanner("Disconnected ${config.displayName} API key from local Keystore.")
                    )
                }
            } catch (e: Exception) {
                showError("Could not disconnect ${config.displayName}: ${e.localizedMessage}")
            }
        }
    }

    fun deleteCustomProvider(config: ProviderConfigEntity) {
        viewModelScope.launch {
            repo.deleteCustomProvider(config.id, config.apiKeyAlias)
            _uiState.update { state ->
                state.copy(
                    keyVaultRevision = state.keyVaultRevision + 1,
                    serviceVerificationMap = state.serviceVerificationMap - config.id,
                    banner = StatusBanner("Removed ${config.displayName}")
                )
            }
        }
    }

    fun testProvider(config: ProviderConfigEntity) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    testingProviderId = config.id,
                    serviceVerificationMap = state.serviceVerificationMap + (config.id to ServiceVerifyState.Checking)
                )
            }
            try {
                val result = repo.testProviderConnection(config)
                _uiState.update { state ->
                    state.copy(
                        testingProviderId = null,
                        serviceVerificationMap = state.serviceVerificationMap + (config.id to ServiceVerifyState.Verified(result)),
                        banner = StatusBanner(result)
                    )
                }
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Verification failed"
                _uiState.update { state ->
                    state.copy(
                        testingProviderId = null,
                        serviceVerificationMap = state.serviceVerificationMap + (config.id to ServiceVerifyState.Failed(err)),
                        banner = StatusBanner("${config.displayName}: $err", isError = true)
                    )
                }
            }
        }
    }

    fun hasApiKey(alias: String): Boolean = repo.hasApiKey(alias)
    fun maskedApiKey(alias: String): String? = repo.maskedApiKey(alias)

    fun savePromptProfile(profile: PromptProfileEntity) {
        viewModelScope.launch {
            repo.savePromptProfile(profile)
            _uiState.update {
                it.copy(banner = StatusBanner("Saved prompt profile '${profile.name}'"))
            }
        }
    }

    fun deletePromptProfile(profileId: String) {
        viewModelScope.launch {
            repo.deletePromptProfile(profileId)
            _uiState.update { it.copy(banner = StatusBanner("Deleted custom prompt profile")) }
        }
    }

    // --- Workspace Clipboard Context & Patch Bridge ---

    fun showInfoBanner(message: String) {
        _uiState.update { it.copy(banner = StatusBanner(message, isError = false)) }
    }

    suspend fun buildTreeContextForWeb(): String? {
        val ws = _uiState.value.activeWorkspace ?: return null
        return repo.buildTreeContextForWeb(ws)
    }

    suspend fun buildGrepContextForWeb(pattern: String): String? {
        val ws = _uiState.value.activeWorkspace ?: return null
        return repo.buildGrepContextForWeb(ws, pattern)
    }

    suspend fun buildFileRangeContextForWeb(relativePath: String, startLine: Int, endLine: Int): String? {
        val ws = _uiState.value.activeWorkspace ?: return null
        return repo.buildFileRangeContextForWeb(ws, relativePath, startLine, endLine)
    }

    suspend fun previewClipboardPatch(
        proposal: ClipboardPatchProposal,
        targetPath: String
    ): Result<String> {
        val ws = _uiState.value.activeWorkspace
            ?: return Result.failure(IllegalStateException("No active workspace."))
        return repo.previewClipboardPatch(ws, proposal, targetPath)
    }

    fun applyClipboardPatch(
        proposal: ClipboardPatchProposal,
        targetPath: String,
        onApplied: () -> Unit = {}
    ) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            try {
                val summary = repo.applyClipboardPatch(ws, proposal, targetPath)
                refreshWorkspaceFiles(ws.rootDirName)
                reloadOpenEditorIfNeeded(ws.rootDirName)
                _uiState.update {
                    it.copy(banner = StatusBanner("Applied patch: $summary (Undo snapshot saved)"))
                }
                onApplied()
            } catch (e: Exception) {
                showError("Patch apply failed: ${e.localizedMessage}")
            }
        }
    }

    private fun showError(msg: String) {
        _uiState.update { it.copy(banner = StatusBanner(msg, isError = true)) }
    }

    companion object {
        private const val TAG = "PocketForgeViewModel"

        fun factory(repo: PocketForgeRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PocketForgeViewModel(repo) as T
                }
            }
    }
}
