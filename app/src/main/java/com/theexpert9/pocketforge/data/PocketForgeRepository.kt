package com.theexpert9.pocketforge.data

import com.theexpert9.pocketforge.data.ai.AgentRunProgress
import com.theexpert9.pocketforge.data.ai.AutonomousAgentOrchestrator
import com.theexpert9.pocketforge.data.ai.HttpAiProviderClient
import com.theexpert9.pocketforge.data.ai.OpenCodeCatalogService
import com.theexpert9.pocketforge.data.ai.OpenCodeSyncResult
import com.theexpert9.pocketforge.data.db.CatalogModelEntity
import com.theexpert9.pocketforge.data.db.ChatMessageEntity
import com.theexpert9.pocketforge.data.db.ChatSessionEntity
import com.theexpert9.pocketforge.data.db.DefaultSeeds
import com.theexpert9.pocketforge.data.db.FileSnapshotEntity
import com.theexpert9.pocketforge.data.db.PocketForgeDatabase
import com.theexpert9.pocketforge.data.db.PromptProfileEntity
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.TerminalEntryEntity
import com.theexpert9.pocketforge.data.db.WorkspaceEntity
import com.theexpert9.pocketforge.data.sandbox.GrepMatch
import com.theexpert9.pocketforge.data.sandbox.SandboxedTerminalEngine
import com.theexpert9.pocketforge.data.sandbox.TerminalCommandResult
import com.theexpert9.pocketforge.data.sandbox.TermuxBridge
import com.theexpert9.pocketforge.data.sandbox.TermuxEnvironmentStatus
import com.theexpert9.pocketforge.data.sandbox.WorkspaceFileNode
import com.theexpert9.pocketforge.data.sandbox.WorkspaceSandbox
import com.theexpert9.pocketforge.data.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class PocketForgeRepository(
    private val db: PocketForgeDatabase,
    private val sandbox: WorkspaceSandbox,
    private val terminalEngine: SandboxedTerminalEngine,
    private val termuxBridge: TermuxBridge,
    private val secretVault: SecretVault,
    private val catalogService: OpenCodeCatalogService,
    private val httpClient: HttpAiProviderClient,
    private val orchestrator: AutonomousAgentOrchestrator
) {
    val workspaces: Flow<List<WorkspaceEntity>> = db.workspaceDao().observeAll()
    val providers: Flow<List<ProviderConfigEntity>> = db.providerDao().observeAll()
        .map { DefaultSeeds.sortProviders(it) }
    val catalogModels: Flow<List<CatalogModelEntity>> = db.catalogModelDao().observeAll()
        .map { DefaultSeeds.sortCatalogModels(it) }
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
        val existingProviders = db.providerDao().getAllOnce().map { it.id }.toSet()
        val missingDefaults = DefaultSeeds.defaultProviders().filter { it.id !in existingProviders }
        if (missingDefaults.isNotEmpty()) {
            db.providerDao().upsertAll(missingDefaults)
        }
        if (db.catalogModelDao().count() == 0) {
            db.catalogModelDao().upsertAll(DefaultSeeds.defaultCatalogModels())
        } else {
            // Migrate existing installs: move subscription-only Gemini 4 Argon from built-in to AI Studio Key only
            db.catalogModelDao().deleteByCompositeId("${DefaultSeeds.PROVIDER_FIREBASE_GEMINI}:gemini-4-argon")
            DefaultSeeds.defaultCatalogModels()
                .filter { it.providerId == "google_ai_studio" || it.providerId == DefaultSeeds.PROVIDER_FIREBASE_GEMINI }
                .forEach { db.catalogModelDao().upsert(it) }
        }
        db.sessionDao().migrateModelForProvider(
            providerId = DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
            oldModelId = "gemini-4-argon",
            newModelId = DefaultSeeds.DEFAULT_GEMINI_MODEL
        )

        val defaultProfile = db.promptProfileDao().getDefault()
        if (defaultProfile == null) {
            db.promptProfileDao().upsertAll(DefaultSeeds.defaultPromptProfiles())
        } else if (defaultProfile.id == DefaultSeeds.PROFILE_AUTONOMOUS_PATCHER &&
            defaultProfile.toolAccessRules.contains("Never ask the user to paste files")
        ) {
            // Upgrade legacy verbose default prompt to the lean token-efficient v1.0.1 prompt
            val leanDefault = DefaultSeeds.defaultPromptProfiles().first()
            db.promptProfileDao().upsert(leanDefault)
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
        appendUserMessage: Boolean = true,
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
        if (appendUserMessage) {
            val existingMsgs = db.messageDao().getForSessionOnce(session.id)
            if (existingMsgs.isEmpty()) {
                val shortTitle = userPrompt.trim().replace('\n', ' ').take(38).ifBlank { session.title }
                db.sessionDao().updateTitle(session.id, shortTitle)
            }
        }

        orchestrator.runUserPrompt(
            workspace = workspace,
            sessionId = session.id,
            provider = provider,
            modelId = session.modelId,
            profile = profile,
            userPrompt = userPrompt,
            appendUserMessage = appendUserMessage,
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

    // --- Sandboxed Terminal & Optional Termux Bridge ---

    fun getTermuxStatus(): TermuxEnvironmentStatus = termuxBridge.getStatus()

    fun setTermuxEnabled(enabled: Boolean): TermuxEnvironmentStatus {
        termuxBridge.setEnabled(enabled)
        return termuxBridge.getStatus()
    }

    suspend fun testTermuxBridge(workspace: WorkspaceEntity): TerminalCommandResult =
        termuxBridge.executeCommand(
            rootDirName = workspace.rootDirName,
            currentWorkingDir = "",
            rawCommand = "echo \"Termux OK\" && which python3 java javac g++ node git 2>/dev/null || true",
            timeoutMs = 20_000L
        )

    suspend fun runTerminalCommand(
        workspace: WorkspaceEntity,
        currentWorkingDir: String,
        command: String
    ): TerminalCommandResult = withContext(Dispatchers.IO) {
        val status = termuxBridge.getStatus()
        val delegate = termuxBridge.shouldDelegateToTermux(command)
        val result = when {
            delegate && (status.isReady || command.trim().startsWith("termux ", ignoreCase = true)) -> {
                termuxBridge.executeCommand(workspace.rootDirName, currentWorkingDir, command)
            }
            else -> {
                val local = terminalEngine.execute(workspace.rootDirName, currentWorkingDir, command)
                if (local.exitCode == 127 && delegate && status.isInstalled && !status.isReady) {
                    val hint = if (!status.hasRunCommandPermission) {
                        "Tip: Termux is installed! Grant Termux RUN_COMMAND permission in Configure > System & Logcat to run compilers/build tools."
                    } else {
                        "Tip: Enable Termux Bridge in Configure > System & Logcat to run this command with Termux."
                    }
                    local.copy(output = "${local.output}\n$hint")
                } else {
                    local
                }
            }
        }
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
            val alias = if (config.apiKeyAlias.isBlank() && config.protocol != com.theexpert9.pocketforge.data.db.ProviderProtocol.FIREBASE_GEMINI) {
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

    suspend fun disconnectProviderApiKey(apiKeyAlias: String) = withContext(Dispatchers.IO) {
        if (apiKeyAlias.isNotBlank()) {
            secretVault.deleteSecret(apiKeyAlias)
        }
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

    // --- Web Companion Context & Clipboard Patch Bridge ---

    suspend fun buildTreeContextForWeb(workspace: WorkspaceEntity): String = withContext(Dispatchers.IO) {
        val tree = sandbox.listTree(workspace.rootDirName, ".", maxDepth = 5)
        com.theexpert9.pocketforge.data.sandbox.ClipboardPatchParser.buildContextPromptWrapper(
            workspaceName = workspace.name,
            contextTitle = "Workspace File Tree",
            contextPayload = tree
        )
    }

    suspend fun buildGrepContextForWeb(workspace: WorkspaceEntity, pattern: String): String =
        withContext(Dispatchers.IO) {
            val matches = sandbox.grepSearch(workspace.rootDirName, pattern, ".", contextLines = 2, maxResults = 40)
            val payload = if (matches.isEmpty()) {
                "No matches found for pattern '$pattern'."
            } else {
                matches.joinToString("\n\n") { m ->
                    buildString {
                        m.contextBefore.forEachIndexed { idx, line ->
                            appendLine("${m.relativePath}:${m.lineNumber - (m.contextBefore.size - idx)}- $line")
                        }
                        append("${m.relativePath}:${m.lineNumber}: ${m.lineText}")
                        m.contextAfter.forEachIndexed { idx, line ->
                            append("\n${m.relativePath}:${m.lineNumber + idx + 1}- $line")
                        }
                    }
                }
            }
            com.theexpert9.pocketforge.data.sandbox.ClipboardPatchParser.buildContextPromptWrapper(
                workspaceName = workspace.name,
                contextTitle = "Grep Search ('$pattern' -> ${matches.size} matches)",
                contextPayload = payload
            )
        }

    suspend fun buildFileRangeContextForWeb(
        workspace: WorkspaceEntity,
        relativePath: String,
        startLine: Int,
        endLine: Int
    ): String = withContext(Dispatchers.IO) {
        val slice = sandbox.readFileRange(
            rootDirName = workspace.rootDirName,
            relativePath = relativePath,
            startLine = startLine,
            endLine = endLine,
            maxLinesCap = 250
        )
        com.theexpert9.pocketforge.data.sandbox.ClipboardPatchParser.buildContextPromptWrapper(
            workspaceName = workspace.name,
            contextTitle = "File Range $relativePath (L$startLine-L$endLine)",
            contextPayload = slice
        )
    }

    suspend fun previewClipboardPatch(
        workspace: WorkspaceEntity,
        proposal: com.theexpert9.pocketforge.data.sandbox.ClipboardPatchProposal,
        targetPathOverride: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val relPath = targetPathOverride.trim()
            if (relPath.isEmpty()) throw IllegalArgumentException("Please enter a destination file path.")
            val file = sandbox.resolveSafe(workspace.rootDirName, relPath)
            val existing = if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else ""
            val proposedNew = when (proposal.kind) {
                com.theexpert9.pocketforge.data.sandbox.ClipboardPatchKind.SEARCH_REPLACE -> {
                    if (!file.exists()) throw IllegalStateException("File '$relPath' does not exist for search/replace.")
                    if (!existing.contains(proposal.searchBlock)) {
                        throw IllegalArgumentException("Search block not found in $relPath.")
                    }
                    existing.replaceFirst(proposal.searchBlock, proposal.replaceOrContent)
                }
                else -> proposal.replaceOrContent
            }
            sandbox.buildUnifiedDiff(existing, proposedNew, relPath)
        }
    }

    suspend fun applyClipboardPatch(
        workspace: WorkspaceEntity,
        proposal: com.theexpert9.pocketforge.data.sandbox.ClipboardPatchProposal,
        targetPathOverride: String
    ): String = withContext(Dispatchers.IO) {
        val relPath = targetPathOverride.trim()
        if (relPath.isEmpty()) throw IllegalArgumentException("Please enter a destination file path.")
        val result = when (proposal.kind) {
            com.theexpert9.pocketforge.data.sandbox.ClipboardPatchKind.SEARCH_REPLACE -> {
                sandbox.patchFile(workspace.rootDirName, relPath, proposal.searchBlock, proposal.replaceOrContent)
            }
            else -> {
                sandbox.writeFile(workspace.rootDirName, relPath, proposal.replaceOrContent)
            }
        }
        val snap = FileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspace.id,
            relativePath = result.relativePath,
            previousContent = result.previousContent,
            newContent = result.newContent
        )
        db.snapshotDao().upsert(snap)
        db.workspaceDao().touch(workspace.id)
        result.summary
    }
}
