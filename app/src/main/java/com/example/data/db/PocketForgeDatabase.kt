package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkspaceEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        FileSnapshotEntity::class,
        ProviderConfigEntity::class,
        CatalogModelEntity::class,
        PromptProfileEntity::class,
        TerminalEntryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PocketForgeDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun providerDao(): ProviderDao
    abstract fun catalogModelDao(): CatalogModelDao
    abstract fun promptProfileDao(): PromptProfileDao
    abstract fun terminalDao(): TerminalDao

    companion object {
        @Volatile
        private var instance: PocketForgeDatabase? = null

        fun getInstance(context: Context): PocketForgeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketForgeDatabase::class.java,
                    "pocketforge_workspace.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

object DefaultSeeds {
    const val PROVIDER_FIREBASE_GEMINI = "firebase_gemini"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
    const val PROFILE_AUTONOMOUS_PATCHER = "profile_autonomous_patcher"
    const val PROFILE_DEEP_DEBUGGER = "profile_deep_debugger"
    const val PROFILE_TOKEN_ARCHITECT = "profile_token_architect"

    fun defaultProviders(): List<ProviderConfigEntity> = listOf(
        ProviderConfigEntity(
            id = PROVIDER_FIREBASE_GEMINI,
            displayName = "Gemini (Built-in)",
            protocol = ProviderProtocol.FIREBASE_GEMINI,
            baseUrl = "firebase://ai-logic",
            selectedModelId = DEFAULT_GEMINI_MODEL,
            apiKeyAlias = "",
            enabled = true,
            isCustom = false,
            description = "Zero-config Firebase AI Logic with App Check. No API key required."
        ),
        ProviderConfigEntity(
            id = "openai",
            displayName = "OpenAI",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            selectedModelId = "gpt-4.1",
            apiKeyAlias = "key_openai",
            enabled = true,
            isCustom = false,
            description = "OpenAI GPT-4.1, o3, and GPT-4o coding models."
        ),
        ProviderConfigEntity(
            id = "anthropic",
            displayName = "Anthropic",
            protocol = ProviderProtocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            selectedModelId = "claude-sonnet-4-20250514",
            apiKeyAlias = "key_anthropic",
            enabled = true,
            isCustom = false,
            description = "Anthropic Claude Messages API with native coding tools."
        ),
        ProviderConfigEntity(
            id = "openrouter",
            displayName = "OpenRouter (OpenCode)",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1",
            selectedModelId = "anthropic/claude-sonnet-4",
            apiKeyAlias = "key_openrouter",
            enabled = true,
            isCustom = false,
            description = "Unified gateway to 200+ models from the OpenCode ecosystem."
        ),
        ProviderConfigEntity(
            id = "deepseek",
            displayName = "DeepSeek",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepseek.com/v1",
            selectedModelId = "deepseek-chat",
            apiKeyAlias = "key_deepseek",
            enabled = true,
            isCustom = false,
            description = "DeepSeek-V3 and DeepSeek-R1 OpenAI-compatible API."
        ),
        ProviderConfigEntity(
            id = "groq",
            displayName = "Groq",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1",
            selectedModelId = "llama-3.3-70b-versatile",
            apiKeyAlias = "key_groq",
            enabled = true,
            isCustom = false,
            description = "Ultra-low-latency LPU inference for open coding models."
        ),
        ProviderConfigEntity(
            id = "ollama",
            displayName = "Ollama / Local Server",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "http://10.0.2.2:11434/v1",
            selectedModelId = "qwen2.5-coder:14b",
            apiKeyAlias = "key_ollama",
            enabled = true,
            isCustom = false,
            description = "Connect to local or LAN OpenAI-compatible inference servers."
        )
    )

    fun defaultCatalogModels(): List<CatalogModelEntity> = listOf(
        // Built-in Firebase AI Logic Gemini models
        CatalogModelEntity(
            compositeId = "$PROVIDER_FIREBASE_GEMINI:gemini-3.8-flash",
            providerId = PROVIDER_FIREBASE_GEMINI,
            providerName = "Gemini (Built-in)",
            providerApiUrl = "firebase://ai-logic",
            modelId = "gemini-3.8-flash",
            modelName = "Gemini 3.8 Flash (Default)",
            contextLimit = 1_048_576,
            supportsTools = true,
            supportsReasoning = true,
            protocolHint = ProviderProtocol.FIREBASE_GEMINI
        ),
        CatalogModelEntity(
            compositeId = "$PROVIDER_FIREBASE_GEMINI:gemini-3.5-flash-lite",
            providerId = PROVIDER_FIREBASE_GEMINI,
            providerName = "Gemini (Built-in)",
            providerApiUrl = "firebase://ai-logic",
            modelId = "gemini-3.5-flash-lite",
            modelName = "Gemini 3.5 Flash-Lite (Ultra Fast)",
            contextLimit = 1_048_576,
            supportsTools = true,
            supportsReasoning = false,
            protocolHint = ProviderProtocol.FIREBASE_GEMINI
        ),
        CatalogModelEntity(
            compositeId = "$PROVIDER_FIREBASE_GEMINI:gemini-4-argon",
            providerId = PROVIDER_FIREBASE_GEMINI,
            providerName = "Gemini (Built-in)",
            providerApiUrl = "firebase://ai-logic",
            modelId = "gemini-4-argon",
            modelName = "Gemini 4 Argon (Deep Reasoning)",
            contextLimit = 1_048_576,
            supportsTools = true,
            supportsReasoning = true,
            protocolHint = ProviderProtocol.FIREBASE_GEMINI
        ),
        // Curated offline OpenCode fallback entries before live sync
        CatalogModelEntity(
            compositeId = "openai:gpt-4.1",
            providerId = "openai",
            providerName = "OpenAI",
            providerApiUrl = "https://api.openai.com/v1",
            modelId = "gpt-4.1",
            modelName = "GPT-4.1",
            contextLimit = 1_047_576,
            supportsTools = true,
            supportsReasoning = false,
            protocolHint = ProviderProtocol.OPENAI_COMPATIBLE
        ),
        CatalogModelEntity(
            compositeId = "openai:o4-mini",
            providerId = "openai",
            providerName = "OpenAI",
            providerApiUrl = "https://api.openai.com/v1",
            modelId = "o4-mini",
            modelName = "o4-mini",
            contextLimit = 200_000,
            supportsTools = true,
            supportsReasoning = true,
            protocolHint = ProviderProtocol.OPENAI_COMPATIBLE
        ),
        CatalogModelEntity(
            compositeId = "anthropic:claude-sonnet-4-20250514",
            providerId = "anthropic",
            providerName = "Anthropic",
            providerApiUrl = "https://api.anthropic.com/v1",
            modelId = "claude-sonnet-4-20250514",
            modelName = "Claude Sonnet 4",
            contextLimit = 200_000,
            supportsTools = true,
            supportsReasoning = true,
            protocolHint = ProviderProtocol.ANTHROPIC
        ),
        CatalogModelEntity(
            compositeId = "deepseek:deepseek-chat",
            providerId = "deepseek",
            providerName = "DeepSeek",
            providerApiUrl = "https://api.deepseek.com/v1",
            modelId = "deepseek-chat",
            modelName = "DeepSeek V3",
            contextLimit = 128_000,
            supportsTools = true,
            supportsReasoning = false,
            protocolHint = ProviderProtocol.OPENAI_COMPATIBLE
        ),
        CatalogModelEntity(
            compositeId = "deepseek:deepseek-reasoner",
            providerId = "deepseek",
            providerName = "DeepSeek",
            providerApiUrl = "https://api.deepseek.com/v1",
            modelId = "deepseek-reasoner",
            modelName = "DeepSeek R1",
            contextLimit = 128_000,
            supportsTools = true,
            supportsReasoning = true,
            protocolHint = ProviderProtocol.OPENAI_COMPATIBLE
        ),
        CatalogModelEntity(
            compositeId = "openrouter:qwen/qwen-2.5-coder-32b-instruct",
            providerId = "openrouter",
            providerName = "OpenRouter (OpenCode)",
            providerApiUrl = "https://openrouter.ai/api/v1",
            modelId = "qwen/qwen-2.5-coder-32b-instruct",
            modelName = "Qwen 2.5 Coder 32B",
            contextLimit = 131_072,
            supportsTools = true,
            supportsReasoning = false,
            protocolHint = ProviderProtocol.OPENAI_COMPATIBLE
        ),
        CatalogModelEntity(
            compositeId = "groq:llama-3.3-70b-versatile",
            providerId = "groq",
            providerName = "Groq",
            providerApiUrl = "https://api.groq.com/openai/v1",
            modelId = "llama-3.3-70b-versatile",
            modelName = "Llama 3.3 70B Versatile",
            contextLimit = 128_000,
            supportsTools = true,
            supportsReasoning = false,
            protocolHint = ProviderProtocol.OPENAI_COMPATIBLE
        )
    )

    fun defaultPromptProfiles(): List<PromptProfileEntity> = listOf(
        PromptProfileEntity(
            id = PROFILE_AUTONOMOUS_PATCHER,
            name = "Autonomous Patcher",
            systemPrompt = "You are PocketForge, an autonomous senior software engineer operating inside an isolated on-device workspace. Inspect code with precision tools, verify bugs directly in files, and apply minimal surgical patches.",
            toolAccessRules = "1. Never ask the user to paste files—discover them with list_tree or find_files.\n2. NEVER read entire large files blindly. Always locate symbols or lines first using grep_search or search_symbols, then inspect only the targeted window with read_file_range.\n3. Prefer patch_file over write_file when modifying existing files so edits stay minimal and reviewable.\n4. After editing, verify your changes with grep_search or run_command.",
            responseFormatRules = "Be concise and technical. Cite exact file paths and line numbers (e.g., src/Auth.kt:42). Summarize what changed and how you verified it.",
            enabledToolsCsv = WorkspaceToolName.ALL.joinToString(","),
            maxAutonomousSteps = 8,
            maxReadLines = 120,
            autonomousMode = true,
            isDefault = true
        ),
        PromptProfileEntity(
            id = PROFILE_DEEP_DEBUGGER,
            name = "Deep Debugger",
            systemPrompt = "You are PocketForge in Root-Cause Debugger mode. Reproduce hypotheses by grepping call sites, tracing edge cases across files, and inspecting exact line ranges before patching.",
            toolAccessRules = "1. Map the project with list_tree first.\n2. Use grep_search and search_symbols to trace every caller and callee.\n3. Read narrow 40-80 line windows around failure points.\n4. Explain the root cause clearly before applying patch_file.",
            responseFormatRules = "Structure final output as: Root Cause -> Exact File/Line -> Fix Applied -> Verification Command.",
            enabledToolsCsv = WorkspaceToolName.ALL.joinToString(","),
            maxAutonomousSteps = 10,
            maxReadLines = 90,
            autonomousMode = true,
            isDefault = false
        ),
        PromptProfileEntity(
            id = PROFILE_TOKEN_ARCHITECT,
            name = "Token-Saver Inspector",
            systemPrompt = "You are PocketForge in ultra-low-token inspection mode. Answer architecture and code questions strictly using grep_search, search_symbols, list_tree, and tiny read_file_range slices without modifying files.",
            toolAccessRules = "1. Strictly avoid reading more than 60 lines per call.\n2. Rely on grep_search with context_lines=2 to gather facts fast.\n3. Do not mutate files unless explicitly requested.",
            responseFormatRules = "Return tight bullet points with file:line citations and zero filler prose.",
            enabledToolsCsv = listOf(
                WorkspaceToolName.LIST_TREE,
                WorkspaceToolName.FIND_FILES,
                WorkspaceToolName.GREP_SEARCH,
                WorkspaceToolName.SEARCH_SYMBOLS,
                WorkspaceToolName.READ_FILE_RANGE,
                WorkspaceToolName.RUN_COMMAND
            ).joinToString(","),
            maxAutonomousSteps = 6,
            maxReadLines = 60,
            autonomousMode = true,
            isDefault = false
        )
    )
}
