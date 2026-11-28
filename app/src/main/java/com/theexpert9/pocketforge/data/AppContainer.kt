package com.theexpert9.pocketforge.data

import android.content.Context
import com.theexpert9.pocketforge.data.ai.AutonomousAgentOrchestrator
import com.theexpert9.pocketforge.data.ai.GeminiFirebaseClient
import com.theexpert9.pocketforge.data.ai.HttpAiProviderClient
import com.theexpert9.pocketforge.data.ai.OpenCodeCatalogService
import com.theexpert9.pocketforge.data.db.PocketForgeDatabase
import com.theexpert9.pocketforge.data.sandbox.SandboxedTerminalEngine
import com.theexpert9.pocketforge.data.sandbox.TermuxBridge
import com.theexpert9.pocketforge.data.sandbox.WorkspaceSandbox
import com.theexpert9.pocketforge.data.security.SecretVault

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: PocketForgeDatabase by lazy { PocketForgeDatabase.getInstance(appContext) }
    val secretVault: SecretVault by lazy { SecretVault(appContext) }
    val sandbox: WorkspaceSandbox by lazy { WorkspaceSandbox(appContext) }
    val terminalEngine: SandboxedTerminalEngine by lazy { SandboxedTerminalEngine(sandbox) }
    val termuxBridge: TermuxBridge by lazy { TermuxBridge(appContext, sandbox) }
    val geminiClient: GeminiFirebaseClient by lazy { GeminiFirebaseClient(appContext) }
    val httpClient: HttpAiProviderClient by lazy { HttpAiProviderClient() }
    val catalogService: OpenCodeCatalogService by lazy { OpenCodeCatalogService() }

    val orchestrator: AutonomousAgentOrchestrator by lazy {
        AutonomousAgentOrchestrator(
            geminiClient = geminiClient,
            httpClient = httpClient,
            sandbox = sandbox,
            terminalEngine = terminalEngine,
            secretVault = secretVault,
            providerDao = database.providerDao(),
            messageDao = database.messageDao(),
            snapshotDao = database.snapshotDao(),
            sessionDao = database.sessionDao(),
            termuxBridge = termuxBridge
        )
    }

    val repository: PocketForgeRepository by lazy {
        PocketForgeRepository(
            db = database,
            sandbox = sandbox,
            terminalEngine = terminalEngine,
            termuxBridge = termuxBridge,
            secretVault = secretVault,
            catalogService = catalogService,
            httpClient = httpClient,
            orchestrator = orchestrator
        )
    }
}
