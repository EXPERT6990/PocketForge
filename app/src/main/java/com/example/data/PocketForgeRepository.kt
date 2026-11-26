package com.example.data

import com.example.data.ai.AgentRunProgress
import com.example.data.ai.AutonomousAgentOrchestrator
import com.example.data.ai.HttpAiProviderClient
import com.example.data.ai.OpenCodeCatalogService
import com.example.data.ai.OpenCodeSyncResult
import com.example.data.db.CatalogModelEntity
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.db.DefaultSeeds
import com.example.data.db.FileSnapshotEntity
import com.example.data.db.PocketForgeDatabase
import com.example.data.db.PromptProfileEntity
import com.example.data.db.ProviderConfigEntity
import com.example.data.db.TerminalEntryEntity
import com.example.data.db.WorkspaceEntity
import com.example.data.sandbox.GrepMatch
import com.example.data.sandbox.SandboxedTerminalEngine
import com.example.data.sandbox.TerminalCommandResult
import com.example.data.sandbox.WorkspaceFileNode
import com.example.data.sandbox.WorkspaceSandbox
import com.example.data.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class PocketForgeRepository(
    private val db: PocketForgeDatabase,
    private val sandbox: WorkspaceSandbox,
    private val terminalEngine: SandboxedTerminalEngine,
    private val secretVault: SecretVault,
    private val catalogService: OpenCodeCatalogService,
    private val httpClient: HttpAiProviderClient,
    private val orchestrator: AutonomousAgentOrchestrator
) {
    val workspaces: Flow<List<WorkspaceEntity>> = db.workspaceDao().observeAll()
    val providers: Flow<List<ProviderConfigEntity>> = db.providerDao().observeAll()
    val catalogModels: Flow<List<CatalogModelEntity>> = db.catalogModelDao().observeAll()
    val promptProfiles: Flow<List<PromptProfileEntity>> = db.promptProfileDao().observeAll()

    fun observeSessions(workspaceId: String): Flow<List<ChatSessionEntity>> =
        db.sessionDao().observeForWorkspace(workspaceId)

    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>> =
        db.messageDao().observeForSession(sessionId)

    fun observeSnapshots(workspaceId: String): Flow<List<FileSnapshotEntity>> =
        db.snapshotDao().observeForWorkspace(workspaceId)

    fun observeTerminal(workspaceId: String): Flow<List<TerminalEntryEntity>> =
        db.terminalDao().observeForWorkspace(workspaceId)

    suspend fun ensureSeeded(): Pair<WorkspaceEntity, ChatSessionEntity> = withContext(Dispatchers.IO) {
        if (db.providerDao().getAllOnce().isEmpty()) {
            db.providerDao().upsertAll(DefaultSeeds.defaultProviders())
        }
        if (db.catalogModelDao().count() == 0) {
            db.catalogModelDao().upsertAll(DefaultSeeds.defaultCatalogModels())
        }
        if (db.promptProfileDao().getDefault() == null) {
            db.promptProfileDao().upsertAll(DefaultSeeds.defaultPromptProfiles())
        }

        val existingWorkspaces = db.workspaceDao().getAllOnce()
        val activeWorkspace = if (existingWorkspaces.isEmpty()) {
            createWorkspace(
                name = "AuthToken Service",
                description = "Kotlin starter workspace with a seeded expiry bug for autonomous debugging.",
                template = WorkspaceSandbox.TEMPLATE_KOTLIN
            ).first
        } else {
            existingWorkspaces.first()
        }

        val existingSessions = db.sessionDao().getForWorkspaceOnce(activeWorkspace.id)
        val activeSession = if (existingSessions.isEmpty()) {
            createSession(activeWorkspace.id, "Session 1")
        } else {
            existingSessions.first()
        }

        activeWorkspace to activeSession
    }

    suspend fun createWorkspace(
        name: String,
        description: String,
        template: String
    ): Pair<WorkspaceEntity, ChatSessionEntity> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "ws" }
        val rootDirName = "${slug}_$id"
        val defaultProfileId = db.promptProfileDao().getDefault()?.id ?: DefaultSeeds.PROFILE_AUTONOMOUS_PATCHER

        sandbox.seedTemplate(rootDirName, template)

        val workspace = WorkspaceEntity(
            id = id,
            name = name.trim().ifBlank { "Workspace $id" },
            description = description.trim().ifBlank { "Isolated local workspace ($template)" },
            template = template,
            rootDirName = rootDirName,
            activePromptProfileId = defaultProfileId
        )
        db.workspaceDao().upsert(workspace)
        val session = createSession(workspace.id, "Main Session")
        workspace to session
    }

    suspend fun deleteWorkspace(workspace: WorkspaceEntity) = withContext(Dispatchers.IO) {
        sandbox.deleteWorkspaceDirectory(workspace.rootDirName)
        db.workspaceDao().deleteById(workspace.id)
    }

    suspend fun createSession(
        workspaceId: String,
        title: String,
        providerId: String = DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
        modelId: String = DefaultSeeds.DEFAULT_GEMINI_MODEL
    ): ChatSessionEntity = withContext(Dispatchers.IO) {
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            title = title.trim().ifBlank { "Session" },
            providerId = providerId,
            modelId = modelId
        )
        db.sessionDao().upsert(session)
        db.workspaceDao().touch(workspaceId)
        session
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        db.sessionDao().deleteById(sessionId)
    }

    suspend fun clearSessionMessages(sessionId: String) = withContext(Dispatchers.IO) {
        db.messageDao().clearSession(sessionId)
    }

    suspend fun updateSessionModel(sessionId: String, providerId: String, modelId: String) =
        withContext(Dispatchers.IO) {
            db.sessionDao().updateModel(sessionId, providerId, modelId)
        }

    suspend fun updateWorkspacePromptProfile(workspaceId: String, profileId: String) =
        withContext(Dispatchers.IO) {
            db.workspaceDao().updatePromptProfile(workspaceId, profileId)
        }

    suspend fun runAgentPrompt(
        workspace: WorkspaceEntity,
        session: ChatSessionEntity,
        userPrompt: String,
        onProgress: (AgentRunProgress) -> Unit,
        onWorkspaceMutated: () -> Unit
    ) {
        val provider = db.providerDao().getById(session.providerId)
            ?: db.providerDao().getById(DefaultSeeds.PROVIDER_FIREBASE_GEMINI)
            ?: DefaultSeeds.defaultProviders().first()
        val profile = db.promptProfileDao().getById(workspace.activePromptProfileId)
            ?: db.promptProfileDao().getDefault()
            ?: DefaultSeeds.defaultPromptProfiles().first()

        // Auto-title fresh sessions from first user prompt
        val existingMsgs = db.messageDao().getForSessionOnce(session.id)
        if (existingMsgs.isEmpty()) {
            val shortTitle = userPrompt.trim().replace('\n', ' ').take(38).ifBlank { session.title }
            db.sessionDao().updateTitle(session.id, shortTitle)
        }

        orchestrator.runUserPrompt(
            workspace = workspace,
            sessionId = session.id,
            provider = provider,
            modelId = session.modelId,
            profile = profile,
            userPrompt = userPrompt,
            onProgress = onProgress,
            onWorkspaceFilesMutated = onWorkspaceMutated
        )
        db.workspaceDao().touch(workspace.id)
    }

    // --- File System & Snapshots ---

    suspend fun listFiles(rootDirName: String): List<WorkspaceFileNode> = withContext(Dispatchers.IO) {
        sandbox.listFlatNodes(rootDirName)
    }

    suspend fun countFiles(rootDirName: String): Int = withContext(Dispatchers.IO) {
        sandbox.countFiles(rootDirName)
    }

    suspend fun readFile(rootDirName: String, relativePath: String): String = withContext(Dispatchers.IO) {
        sandbox.readFullFile(rootDirName, relativePath)
    }

    suspend fun saveFileWithSnapshot(
        workspace: WorkspaceEntity,
        relativePath: String,
        newContent: String
    ): FileSnapshotEntity = withContext(Dispatchers.IO) {
        val result = sandbox.writeFile(workspace.rootDirName, relativePath, newContent)
        val snap = FileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspace.id,
            relativePath = result.relativePath,
            previousContent = result.previousContent,
            newContent = result.newContent
        )
        db.snapshotDao().upsert(snap)
        db.workspaceDao().touch(workspace.id)
        snap
    }

    suspend fun createFolder(workspace: WorkspaceEntity, relativePath: String) = withContext(Dispatchers.IO) {
        sandbox.createDirectory(workspace.rootDirName, relativePath)
        db.workspaceDao().touch(workspace.id)
    }

    suspend fun deleteFileWithSnapshot(workspace: WorkspaceEntity, relativePath: String) =
        withContext(Dispatchers.IO) {
            val result = sandbox.deleteFile(workspace.rootDirName, relativePath)
            val snap = FileSnapshotEntity(
                id = UUID.randomUUID().toString(),
                workspaceId = workspace.id,
                relativePath = result.relativePath,
                previousContent = result.previousContent,
                newContent = null
            )
            db.snapshotDao().upsert(snap)
            db.workspaceDao().touch(workspace.id)
        }

    suspend fun grepWorkspace(rootDirName: String, query: String): List<GrepMatch> =
        withContext(Dispatchers.IO) {
            sandbox.grepSearch(rootDirName, query, ".", contextLines = 1, maxResults = 60)
        }

    suspend fun revertSnapshot(workspace: WorkspaceEntity, snapshotId: String): String =
        withContext(Dispatchers.IO) {
            val snap = db.snapshotDao().getById(snapshotId)
                ?: throw IllegalStateException("Snapshot not found.")
            sandbox.restoreSnapshot(workspace.rootDirName, snap.relativePath, snap.previousContent)
            db.snapshotDao().setReverted(snapshotId, true)
            db.workspaceDao().touch(workspace.id)
            if (snap.previousContent == null) {
                "Reverted: removed newly created ${snap.relativePath}"
            } else {
                "Restored ${snap.relativePath} to pre-edit snapshot"
            }
        }

    // --- Sandboxed Terminal ---

    suspend fun runTerminalCommand(
        workspace: WorkspaceEntity,
        currentWorkingDir: String,
        command: String
    ): TerminalCommandResult = withContext(Dispatchers.IO) {
        val result = terminalEngine.execute(workspace.rootDirName, currentWorkingDir, command)
        if (result.clearRequested) {
            db.terminalDao().clearWorkspace(workspace.id)
        } else if (command.isNotBlank()) {
            db.terminalDao().insert(
                TerminalEntryEntity(
                    id = UUID.randomUUID().toString(),
                    workspaceId = workspace.id,
                    workingDir = currentWorkingDir.ifBlank { "~" },
                    command = command,
                    output = result.output,
                    exitCode = result.exitCode
                )
            )
        }
        result
    }

    suspend fun clearTerminal(workspaceId: String) = withContext(Dispatchers.IO) {
        db.terminalDao().clearWorkspace(workspaceId)
    }

    // --- Providers, OpenCode Catalog & Encrypted Secrets ---

    suspend fun syncOpenCodeCatalog(): OpenCodeSyncResult = withContext(Dispatchers.IO) {
        val result = catalogService.fetchCatalog()
        db.catalogModelDao().clearAll()
        db.catalogModelDao().upsertAll(result.models)
        result
    }

    suspend fun saveProviderConfig(config: ProviderConfigEntity, newApiKey: String?) =
        withContext(Dispatchers.IO) {
            val alias = if (config.apiKeyAlias.isBlank() && config.protocol != com.example.data.db.ProviderProtocol.FIREBASE_GEMINI) {
                "key_${config.id}"
            } else {
                config.apiKeyAlias
            }
            val updated = config.copy(apiKeyAlias = alias)
            db.providerDao().upsert(updated)
            if (newApiKey != null && alias.isNotBlank()) {
                secretVault.saveSecret(alias, newApiKey)
            }
        }

    suspend fun importFromOpenCodeCatalog(model: CatalogModelEntity): ProviderConfigEntity =
        withContext(Dispatchers.IO) {
            val existing = db.providerDao().getById(model.providerId)
            val config = existing?.copy(selectedModelId = model.modelId, enabled = true)
                ?: ProviderConfigEntity(
                    id = model.providerId,
                    displayName = model.providerName,
                    protocol = model.protocolHint,
                    baseUrl = model.providerApiUrl,
                    selectedModelId = model.modelId,
                    apiKeyAlias = if (model.providerId == DefaultSeeds.PROVIDER_FIREBASE_GEMINI) "" else "key_${model.providerId}",
                    enabled = true,
                    isCustom = true,
                    description = "Imported from OpenCode (models.dev) catalog."
                )
            db.providerDao().upsert(config)
            config
        }

    suspend fun deleteCustomProvider(providerId: String, apiKeyAlias: String) = withContext(Dispatchers.IO) {
        if (apiKeyAlias.isNotBlank()) secretVault.deleteSecret(apiKeyAlias)
        db.providerDao().deleteCustomById(providerId)
    }

    fun hasApiKey(alias: String): Boolean = alias.isNotBlank() && secretVault.hasSecret(alias)

    fun maskedApiKey(alias: String): String? = if (alias.isNotBlank()) secretVault.getMaskedPreview(alias) else null

    suspend fun testProviderConnection(config: ProviderConfigEntity): String {
        val key = if (config.apiKeyAlias.isNotBlank()) secretVault.getSecret(config.apiKeyAlias) else null
        return httpClient.testConnection(config, key)
    }

    // --- Prompt Profiles ---

    suspend fun savePromptProfile(profile: PromptProfileEntity) = withContext(Dispatchers.IO) {
        db.promptProfileDao().upsert(profile)
    }

    suspend fun deletePromptProfile(profileId: String) = withContext(Dispatchers.IO) {
        db.promptProfileDao().deleteNonDefault(profileId)
    }
}
