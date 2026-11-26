package com.example.data.ai

import android.util.Log
import com.example.data.db.ChatMessageEntity
import com.example.data.db.FileSnapshotEntity
import com.example.data.db.MessageDao
import com.example.data.db.MessageRole
import com.example.data.db.PromptProfileEntity
import com.example.data.db.ProviderConfigEntity
import com.example.data.db.ProviderProtocol
import com.example.data.db.SessionDao
import com.example.data.db.SnapshotDao
import com.example.data.db.ToolStatus
import com.example.data.db.WorkspaceEntity
import com.example.data.db.WorkspaceToolName
import com.example.data.sandbox.SandboxedTerminalEngine
import com.example.data.sandbox.WorkspaceSandbox
import com.example.data.security.SecretVault
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.UUID

data class AgentRunProgress(
    val stepIndex: Int,
    val maxSteps: Int,
    val activeToolName: String?,
    val streamingText: String
)

/**
 * Multi-step autonomous coding agent loop.
 * Executes precision file-search, line-range inspection, auto-applied surgical patches
 * (with automatic FileSnapshot rollback records), and sandboxed terminal commands.
 */
class AutonomousAgentOrchestrator(
    private val geminiClient: GeminiFirebaseClient,
    private val httpClient: HttpAiProviderClient,
    private val sandbox: WorkspaceSandbox,
    private val terminalEngine: SandboxedTerminalEngine,
    private val secretVault: SecretVault,
    private val messageDao: MessageDao,
    private val snapshotDao: SnapshotDao,
    private val sessionDao: SessionDao
) {

    suspend fun runUserPrompt(
        workspace: WorkspaceEntity,
        sessionId: String,
        provider: ProviderConfigEntity,
        modelId: String,
        profile: PromptProfileEntity,
        userPrompt: String,
        onProgress: (AgentRunProgress) -> Unit,
        onWorkspaceFilesMutated: () -> Unit
    ) {
        val now = System.currentTimeMillis()
        messageDao.upsert(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.USER,
                content = userPrompt,
                timestamp = now
            )
        )

        val enabledTools = WorkspaceToolRegistry.filterEnabled(profile.enabledToolSet())
        val systemInstruction = buildSystemPrompt(workspace, profile, enabledTools)
        val client: AiProviderClient = when (provider.protocol) {
            ProviderProtocol.FIREBASE_GEMINI -> geminiClient
            else -> httpClient
        }
        val apiKey = if (provider.apiKeyAlias.isNotBlank()) secretVault.getSecret(provider.apiKeyAlias) else null

        val conversation = buildInitialHistory(sessionId)
        val maxSteps = profile.maxAutonomousSteps.coerceIn(1, 15)
        var currentWorkingDir = ""
        var stepsExecuted = 0

        for (step in 1..maxSteps) {
            onProgress(
                AgentRunProgress(
                    stepIndex = step,
                    maxSteps = maxSteps,
                    activeToolName = null,
                    streamingText = ""
                )
            )

            val response = client.generateTurn(
                config = provider,
                modelId = modelId,
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                history = conversation,
                enabledTools = enabledTools,
                onPartialText = { partial ->
                    onProgress(
                        AgentRunProgress(
                            stepIndex = step,
                            maxSteps = maxSteps,
                            activeToolName = null,
                            streamingText = partial
                        )
                    )
                }
            )

            if (response.toolCalls.isEmpty()) {
                val finalBody = response.visibleText.ifBlank { "Done." }
                messageDao.upsert(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = finalBody,
                        timestamp = System.currentTimeMillis()
                    )
                )
                if (stepsExecuted > 0) {
                    sessionDao.incrementSteps(sessionId, stepsExecuted)
                }
                return
            }

            // Record assistant's reasoning / commentary before executing autonomous tools
            if (response.visibleText.isNotBlank()) {
                messageDao.upsert(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = response.visibleText,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            val assistantHistoryNote = buildString {
                if (response.visibleText.isNotBlank()) appendLine(response.visibleText)
                response.toolCalls.forEach { call ->
                    appendLine("<tool_call>{\"tool\":\"${call.name}\",\"args\":${call.argumentsJson}}</tool_call>")
                }
            }.trim()
            conversation += AgentConversationTurn(role = "assistant", text = assistantHistoryNote)

            for (toolCall in response.toolCalls) {
                stepsExecuted++
                onProgress(
                    AgentRunProgress(
                        stepIndex = step,
                        maxSteps = maxSteps,
                        activeToolName = toolCall.name,
                        streamingText = ""
                    )
                )

                val toolMessageId = UUID.randomUUID().toString()
                messageDao.upsert(
                    ChatMessageEntity(
                        id = toolMessageId,
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = "Running ${toolCall.name}…",
                        toolName = toolCall.name,
                        toolArgsJson = toolCall.argumentsJson,
                        toolStatus = ToolStatus.RUNNING,
                        timestamp = System.currentTimeMillis()
                    )
                )

                val execution = executeSingleTool(
                    workspace = workspace,
                    profile = profile,
                    toolCall = toolCall,
                    currentWorkingDir = currentWorkingDir
                )
                currentWorkingDir = execution.updatedWorkingDir

                var snapshotId: String? = null
                if (execution.snapshot != null) {
                    snapshotId = execution.snapshot.id
                    snapshotDao.upsert(execution.snapshot)
                    onWorkspaceFilesMutated()
                } else if (toolCall.name == WorkspaceToolName.RUN_COMMAND) {
                    onWorkspaceFilesMutated()
                }

                messageDao.upsert(
                    ChatMessageEntity(
                        id = toolMessageId,
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = execution.summary,
                        toolName = toolCall.name,
                        toolArgsJson = toolCall.argumentsJson,
                        toolResult = execution.output,
                        toolStatus = if (execution.isError) ToolStatus.ERROR else ToolStatus.SUCCESS,
                        diffPreview = execution.diffPreview,
                        snapshotId = snapshotId,
                        timestamp = System.currentTimeMillis()
                    )
                )

                conversation += AgentConversationTurn(
                    role = "tool",
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    text = execution.output.take(6_000)
                )
            }
        }

        // Reached maxAutonomousSteps
        sessionDao.incrementSteps(sessionId, stepsExecuted)
        messageDao.upsert(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "Reached the autonomous step cap ($maxSteps steps). Review the tool trace above or ask me to continue.",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private data class ToolExecOutcome(
        val summary: String,
        val output: String,
        val isError: Boolean,
        val diffPreview: String? = null,
        val snapshot: FileSnapshotEntity? = null,
        val updatedWorkingDir: String
    )

    private fun executeSingleTool(
        workspace: WorkspaceEntity,
        profile: PromptProfileEntity,
        toolCall: AgentToolCall,
        currentWorkingDir: String
    ): ToolExecOutcome {
        val enabledSet = profile.enabledToolSet()
        if (toolCall.name !in enabledSet) {
            return ToolExecOutcome(
                summary = "Tool '${toolCall.name}' is disabled in active Prompt Profile.",
                output = "Permission denied: tool '${toolCall.name}' is disabled in '${profile.name}'.",
                isError = true,
                updatedWorkingDir = currentWorkingDir
            )
        }

        return try {
            val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrElse { JSONObject() }
            when (toolCall.name) {
                WorkspaceToolName.LIST_TREE -> {
                    val path = args.optString("path", ".")
                    val depth = args.optInt("max_depth", 4).coerceIn(1, 6)
                    val out = sandbox.listTree(workspace.rootDirName, path, depth)
                    ToolExecOutcome("Listed tree at $path", out, false, updatedWorkingDir = currentWorkingDir)
                }
                WorkspaceToolName.FIND_FILES -> {
                    val glob = args.optString("glob", "*")
                    val files = sandbox.findFiles(workspace.rootDirName, glob)
                    val out = if (files.isEmpty()) "No files matching '$glob'" else files.joinToString("\n")
                    ToolExecOutcome("Found ${files.size} files for '$glob'", out, false, updatedWorkingDir = currentWorkingDir)
                }
                WorkspaceToolName.GREP_SEARCH -> {
                    val pattern = args.optString("pattern")
                    val path = args.optString("path", ".")
                    val ctx = args.optInt("context_lines", 1).coerceIn(0, 3)
                    val maxRes = args.optInt("max_results", 30).coerceIn(1, 80)
                    val matches = sandbox.grepSearch(workspace.rootDirName, pattern, path, ctx, maxRes)
                    val out = if (matches.isEmpty()) {
                        "No grep matches for '$pattern' in '$path'."
                    } else {
                        matches.joinToString("\n") { m ->
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
                    ToolExecOutcome("grep '$pattern' -> ${matches.size} matches", out, false, updatedWorkingDir = currentWorkingDir)
                }
                WorkspaceToolName.SEARCH_SYMBOLS -> {
                    val query = args.optString("query")
                    val matches = sandbox.searchSymbols(workspace.rootDirName, query)
                    val out = if (matches.isEmpty()) {
                        "No symbols found matching '$query'."
                    } else {
                        matches.joinToString("\n") { "${it.relativePath}:${it.lineNumber}: ${it.lineText.trim()}" }
                    }
                    ToolExecOutcome("Symbols '$query' -> ${matches.size} hits", out, false, updatedWorkingDir = currentWorkingDir)
                }
                WorkspaceToolName.READ_FILE_RANGE -> {
                    val path = args.optString("path")
                    val start = args.optInt("start_line", 1)
                    val end = args.optInt("end_line", start + profile.maxReadLines - 1)
                    val out = sandbox.readFileRange(
                        rootDirName = workspace.rootDirName,
                        relativePath = path,
                        startLine = start,
                        endLine = end,
                        maxLinesCap = profile.maxReadLines
                    )
                    ToolExecOutcome("Read $path (L$start-L$end)", out, false, updatedWorkingDir = currentWorkingDir)
                }
                WorkspaceToolName.PATCH_FILE -> {
                    val path = args.optString("path")
                    val searchBlock = args.optString("search_block")
                    val replaceBlock = args.optString("replace_block")
                    val res = sandbox.patchFile(workspace.rootDirName, path, searchBlock, replaceBlock)
                    val snap = FileSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        workspaceId = workspace.id,
                        relativePath = res.relativePath,
                        previousContent = res.previousContent,
                        newContent = res.newContent
                    )
                    ToolExecOutcome(
                        summary = res.summary,
                        output = "${res.summary}\n${res.diffPreview}",
                        isError = false,
                        diffPreview = res.diffPreview,
                        snapshot = snap,
                        updatedWorkingDir = currentWorkingDir
                    )
                }
                WorkspaceToolName.WRITE_FILE -> {
                    val path = args.optString("path")
                    val content = args.optString("content")
                    val res = sandbox.writeFile(workspace.rootDirName, path, content)
                    val snap = FileSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        workspaceId = workspace.id,
                        relativePath = res.relativePath,
                        previousContent = res.previousContent,
                        newContent = res.newContent
                    )
                    ToolExecOutcome(
                        summary = res.summary,
                        output = "${res.summary}\n${res.diffPreview}",
                        isError = false,
                        diffPreview = res.diffPreview,
                        snapshot = snap,
                        updatedWorkingDir = currentWorkingDir
                    )
                }
                WorkspaceToolName.DELETE_FILE -> {
                    val path = args.optString("path")
                    val res = sandbox.deleteFile(workspace.rootDirName, path)
                    val snap = FileSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        workspaceId = workspace.id,
                        relativePath = res.relativePath,
                        previousContent = res.previousContent,
                        newContent = null
                    )
                    ToolExecOutcome(
                        summary = res.summary,
                        output = res.summary,
                        isError = false,
                        diffPreview = res.diffPreview,
                        snapshot = snap,
                        updatedWorkingDir = currentWorkingDir
                    )
                }
                WorkspaceToolName.RUN_COMMAND -> {
                    val cmd = args.optString("command")
                    val res = terminalEngine.execute(workspace.rootDirName, currentWorkingDir, cmd)
                    val out = buildString {
                        appendLine("$ $cmd (exit ${res.exitCode})")
                        if (res.output.isNotBlank()) append(res.output)
                    }.trim()
                    ToolExecOutcome(
                        summary = "$ $cmd (exit ${res.exitCode})",
                        output = out,
                        isError = res.exitCode != 0,
                        updatedWorkingDir = res.newWorkingDir
                    )
                }
                else -> ToolExecOutcome(
                    summary = "Unknown tool '${toolCall.name}'",
                    output = "Error: unknown workspace tool '${toolCall.name}'.",
                    isError = true,
                    updatedWorkingDir = currentWorkingDir
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool ${toolCall.name} failed", e)
            ToolExecOutcome(
                summary = "${toolCall.name} failed: ${e.message ?: "Error"}",
                output = "Tool Error (${toolCall.name}): ${e.message ?: "Unknown error"}",
                isError = true,
                updatedWorkingDir = currentWorkingDir
            )
        }
    }

    private suspend fun buildInitialHistory(sessionId: String): MutableList<AgentConversationTurn> {
        val recent = messageDao.getForSessionOnce(sessionId).takeLast(24)
        val turns = mutableListOf<AgentConversationTurn>()
        for (msg in recent) {
            when (msg.role) {
                MessageRole.USER -> turns += AgentConversationTurn("user", msg.content)
                MessageRole.ASSISTANT -> turns += AgentConversationTurn("assistant", msg.content)
                MessageRole.TOOL -> turns += AgentConversationTurn(
                    role = "tool",
                    toolName = msg.toolName,
                    text = (msg.toolResult ?: msg.content).take(2_500)
                )
            }
        }
        return turns
    }

    private fun buildSystemPrompt(
        workspace: WorkspaceEntity,
        profile: PromptProfileEntity,
        enabledTools: List<AgentToolDefinition>
    ): String {
        val topTree = sandbox.listTree(workspace.rootDirName, ".", maxDepth = 2)
        val toolCatalog = enabledTools.joinToString("\n") { it.toPromptLine() }
        return buildString {
            appendLine(profile.systemPrompt)
            appendLine()
            appendLine("=== ISOLATED WORKSPACE CONTEXT ===")
            appendLine("Workspace Name: ${workspace.name}")
            appendLine("Template: ${workspace.template}")
            appendLine("Max Lines Per read_file_range Call: ${profile.maxReadLines}")
            appendLine("Current Top-Level Tree:")
            appendLine(topTree)
            appendLine()
            appendLine("=== FILE ACCESS & TOKEN EFFICIENCY RULES ===")
            appendLine(profile.toolAccessRules)
            appendLine()
            appendLine("=== AVAILABLE WORKSPACE TOOLS ===")
            appendLine(toolCatalog.ifBlank { "(No tools enabled)" })
            appendLine()
            appendLine("=== TOOL INVOCATION PROTOCOL ===")
            appendLine("To invoke a tool, output one or more blocks using this exact format (valid JSON inside):")
            appendLine("<tool_call>{\"tool\":\"grep_search\",\"args\":{\"pattern\":\"isExpired\",\"path\":\".\",\"context_lines\":1}}</tool_call>")
            appendLine("Wait for [TOOL_RESULT] before assuming file contents. When done, respond normally without <tool_call> tags.")
            appendLine()
            appendLine("=== RESPONSE FORMAT ===")
            appendLine(profile.responseFormatRules)
        }
    }

    companion object {
        private const val TAG = "AutonomousOrchestrator"
    }
}
