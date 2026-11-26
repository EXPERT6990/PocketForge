package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object MessageRole {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
    const val TOOL = "TOOL"
    const val SYSTEM = "SYSTEM"
}

object ToolStatus {
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val ERROR = "ERROR"
}

object ProviderProtocol {
    const val FIREBASE_GEMINI = "FIREBASE_GEMINI"
    const val OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE"
    const val ANTHROPIC = "ANTHROPIC"
}

object WorkspaceToolName {
    const val LIST_TREE = "list_tree"
    const val FIND_FILES = "find_files"
    const val GREP_SEARCH = "grep_search"
    const val SEARCH_SYMBOLS = "search_symbols"
    const val READ_FILE_RANGE = "read_file_range"
    const val PATCH_FILE = "patch_file"
    const val WRITE_FILE = "write_file"
    const val DELETE_FILE = "delete_file"
    const val RUN_COMMAND = "run_command"

    val ALL = listOf(
        LIST_TREE,
        FIND_FILES,
        GREP_SEARCH,
        SEARCH_SYMBOLS,
        READ_FILE_RANGE,
        PATCH_FILE,
        WRITE_FILE,
        DELETE_FILE,
        RUN_COMMAND
    )
}

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val template: String,
    val rootDirName: String,
    val activePromptProfileId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_sessions",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workspaceId")]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val title: String,
    val providerId: String,
    val modelId: String,
    val totalToolSteps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolResult: String? = null,
    val toolStatus: String? = null,
    val diffPreview: String? = null,
    val snapshotId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "file_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workspaceId")]
)
data class FileSnapshotEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val relativePath: String,
    val previousContent: String?,
    val newContent: String?,
    val reverted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val selectedModelId: String,
    val apiKeyAlias: String,
    val enabled: Boolean = true,
    val isCustom: Boolean = false,
    val description: String = ""
)

@Entity(tableName = "catalog_models")
data class CatalogModelEntity(
    @PrimaryKey val compositeId: String,
    val providerId: String,
    val providerName: String,
    val providerApiUrl: String,
    val modelId: String,
    val modelName: String,
    val contextLimit: Int,
    val supportsTools: Boolean,
    val supportsReasoning: Boolean,
    val protocolHint: String
)

@Entity(tableName = "prompt_profiles")
data class PromptProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val toolAccessRules: String,
    val responseFormatRules: String,
    val enabledToolsCsv: String,
    val maxAutonomousSteps: Int = 8,
    val maxReadLines: Int = 120,
    val autonomousMode: Boolean = true,
    val isDefault: Boolean = false
) {
    fun enabledToolSet(): Set<String> =
        enabledToolsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

@Entity(
    tableName = "terminal_entries",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workspaceId")]
)
data class TerminalEntryEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val workingDir: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)
