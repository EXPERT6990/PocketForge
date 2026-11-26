package com.example.data

import android.content.Context
import com.example.data.ai.AutonomousAgentOrchestrator
import com.example.data.ai.GeminiFirebaseClient
import com.example.data.ai.HttpAiProviderClient
import com.example.data.ai.OpenCodeCatalogService
import com.example.data.db.PocketForgeDatabase
import com.example.data.sandbox.SandboxedTerminalEngine
import com.example.data.sandbox.WorkspaceSandbox
import com.example.data.security.SecretVault

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: PocketForgeDatabase by lazy { PocketForgeDatabase.getInstance(appContext) }
    val secretVault: SecretVault by lazy { SecretVault(appContext) }
    val sandbox: WorkspaceSandbox by lazy { WorkspaceSandbox(appContext) }
    val terminalEngine: SandboxedTerminalEngine by lazy { SandboxedTerminalEngine(sandbox) }
    val geminiClient: GeminiFirebaseClient by lazy { GeminiFirebaseClient() }
    val httpClient: HttpAiProviderClient by lazy { HttpAiProviderClient() }
    val catalogService: OpenCodeCatalogService by lazy { OpenCodeCatalogService() }

    val orchestrator: AutonomousAgentOrchestrator by lazy {
        AutonomousAgentOrchestrator(
            geminiClient = geminiClient,
            httpClient = httpClient,
            sandbox = sandbox,
            terminalEngine = terminalEngine,
            secretVault = secretVault,
            messageDao = database.messageDao(),
            snapshotDao = database.snapshotDao(),
            sessionDao = database.sessionDao()
        )
    }

    val repository: PocketForgeRepository by lazy {
        PocketForgeRepository(
            db = database,
            sandbox = sandbox,
            terminalEngine = terminalEngine,
            secretVault = secretVault,
            catalogService = catalogService,
            httpClient = httpClient,
            orchestrator = orchestrator
        )
    }
}
