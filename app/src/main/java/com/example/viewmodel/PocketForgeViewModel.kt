package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.PocketForgeRepository
import com.example.data.ai.AgentRunProgress
import com.example.data.db.CatalogModelEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.db.FileSnapshotEntity
import com.example.data.db.PromptProfileEntity
import com.example.data.db.ProviderConfigEntity
import com.example.data.db.TerminalEntryEntity
import com.example.data.db.WorkspaceEntity
import com.example.data.sandbox.GrepMatch
import com.example.data.sandbox.WorkspaceFileNode
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
    FILES,
    TERMINAL,
    CONFIGURE
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
    val isSyncingCatalog: Boolean = false,
    val catalogSearchQuery: String = "",
    val testingProviderId: String? = null,
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
                grepResults = emptyList()
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
        _uiState.update { it.copy(activeSessionId = session.id) }
    }

    fun createSession(title: String = "") {
        val ws = _uiState.value.activeWorkspace ?: return
        val currentSession = _uiState.value.activeSession
        viewModelScope.launch {
            val nextNum = _uiState.value.sessions.size + 1
            val session = repo.createSession(
                workspaceId = ws.id,
                title = title.ifBlank { "Session $nextNum" },
                providerId = currentSession?.providerId ?: com.example.data.db.DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
                modelId = currentSession?.modelId ?: com.example.data.db.DefaultSeeds.DEFAULT_GEMINI_MODEL
            )
            selectedSessionIdFlow.value = session.id
            _uiState.update { it.copy(activeSessionId = session.id) }
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

    fun selectPromptProfileForWorkspace(profileId: String) {
        val ws = _uiState.value.activeWorkspace ?: return
        viewModelScope.launch {
            repo.updateWorkspacePromptProfile(ws.id, profileId)
        }
    }

    // --- Autonomous Agent ---

    fun sendAgentPrompt(overridePrompt: String? = null) {
        val prompt = (overridePrompt ?: _uiState.value.draftPrompt).trim()
        if (prompt.isEmpty() || _uiState.value.isAgentRunning) return
        val ws = _uiState.value.activeWorkspace ?: return
        val session = _uiState.value.activeSession ?: return

        _uiState.update {
            it.copy(
                draftPrompt = if (overridePrompt == null) "" else it.draftPrompt,
                isAgentRunning = true,
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
                showError(e.localizedMessage ?: "Agent run failed.")
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

    // --- Providers, OpenCode Catalog & Prompt Studio ---

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
                val needsKey = provider.protocol != com.example.data.db.ProviderProtocol.FIREBASE_GEMINI &&
                    !repo.hasApiKey(provider.apiKeyAlias) &&
                    !provider.baseUrl.contains("11434")
                val note = if (needsKey) {
                    "Selected ${model.modelName}. Add your ${provider.displayName} API key below to start chatting."
                } else {
                    "Active model switched to ${model.modelName} (${provider.displayName})."
                }
                _uiState.update { it.copy(banner = StatusBanner(note)) }
            } catch (e: Exception) {
                showError("Failed to select model: ${e.localizedMessage}")
            }
        }
    }

    fun saveProvider(config: ProviderConfigEntity, newApiKey: String?) {
        viewModelScope.launch {
            try {
                repo.saveProviderConfig(config, newApiKey)
                _uiState.update {
                    it.copy(banner = StatusBanner("Saved ${config.displayName} configuration (key encrypted locally)."))
                }
            } catch (e: Exception) {
                showError("Could not save provider: ${e.localizedMessage}")
            }
        }
    }

    fun deleteCustomProvider(config: ProviderConfigEntity) {
        viewModelScope.launch {
            repo.deleteCustomProvider(config.id, config.apiKeyAlias)
            _uiState.update { it.copy(banner = StatusBanner("Removed ${config.displayName}")) }
        }
    }

    fun testProvider(config: ProviderConfigEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(testingProviderId = config.id) }
            try {
                val result = repo.testProviderConnection(config)
                _uiState.update {
                    it.copy(testingProviderId = null, banner = StatusBanner(result))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        testingProviderId = null,
                        banner = StatusBanner("${config.displayName}: ${e.localizedMessage}", isError = true)
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
