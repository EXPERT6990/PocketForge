package com.theexpert9.pocketforge

import com.theexpert9.pocketforge.data.ai.AiResiliencePolicy
import com.theexpert9.pocketforge.data.db.DefaultSeeds
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import com.theexpert9.pocketforge.data.sandbox.ClipboardPatchKind
import com.theexpert9.pocketforge.data.sandbox.ClipboardPatchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketForgeCoreTest {

    @Test
    fun clipboardPatchParser_extractsSearchReplaceBlock() {
        val reply = """
            Here is the fix:
            FILE: src/TokenValidator.kt
            <<<SEARCH
            return effectiveExpiry > nowEpochSec
            ===
            return effectiveExpiry <= nowEpochSec
            >>>REPLACE
        """.trimIndent()

        val proposals = ClipboardPatchParser.parse(reply)
        assertEquals(1, proposals.size)
        assertEquals("src/TokenValidator.kt", proposals[0].relativePath)
        assertEquals(ClipboardPatchKind.SEARCH_REPLACE, proposals[0].kind)
        assertEquals("return effectiveExpiry > nowEpochSec", proposals[0].searchBlock)
        assertEquals("return effectiveExpiry <= nowEpochSec", proposals[0].replaceOrContent)
    }

    @Test
    fun clipboardPatchParser_extractsFencedFileBlock() {
        val reply = """
            FILE: src/Greeting.kt
            ```kotlin
            fun greet(name: String) = "Hello, ${'$'}name"
            ```
        """.trimIndent()

        val proposals = ClipboardPatchParser.parse(reply)
        assertEquals(1, proposals.size)
        assertEquals("src/Greeting.kt", proposals[0].relativePath)
        assertEquals(ClipboardPatchKind.FULL_WRITE, proposals[0].kind)
        assertTrue(proposals[0].replaceOrContent.contains("fun greet"))
    }

    @Test
    fun clipboardPatchParser_buildsContextWrapperWithPatchInstructions() {
        val wrapped = ClipboardPatchParser.buildContextPromptWrapper(
            workspaceName = "DemoApp",
            contextTitle = "Tree",
            contextPayload = "src/Main.kt"
        )
        assertTrue(wrapped.contains("Workspace: DemoApp"))
        assertTrue(wrapped.contains("<<<SEARCH"))
        assertTrue(wrapped.contains(">>>REPLACE"))
        assertTrue(wrapped.contains("src/Main.kt"))
    }

    @Test
    fun aiResiliencePolicy_classifies503And429AsTransient() {
        assertTrue(
            AiResiliencePolicy.isTransientOverloadError(
                IllegalStateException("Gemini (AI Studio Key) HTTP 503: This model is currently experiencing high demand.")
            )
        )
        assertTrue(
            AiResiliencePolicy.isTransientOverloadError(
                RuntimeException("HTTP 429 RESOURCE_EXHAUSTED")
            )
        )
        assertFalse(
            AiResiliencePolicy.isTransientOverloadError(
                IllegalStateException("OpenAI HTTP 401: Invalid API key provided.")
            )
        )
    }

    @Test
    fun aiResiliencePolicy_buildsGeminiFallbackChainWithBuiltInBridge() {
        val aiStudio = ProviderConfigEntity(
            id = "google_ai_studio",
            displayName = "Gemini (AI Studio Key)",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            selectedModelId = "gemini-3-flash-preview",
            apiKeyAlias = "key_google_ai_studio"
        )

        val chain = AiResiliencePolicy.buildAttemptChain(
            primaryProvider = aiStudio,
            requestedModelId = "gemini-3-flash-preview",
            firebaseBuiltInAvailable = true
        )

        assertEquals("gemini-3-flash-preview", chain[0].modelId)
        assertTrue(chain.any { it.modelId == "gemini-3.5-flash-lite" && it.providerId == "google_ai_studio" })
        assertTrue(chain.any { it.providerId == DefaultSeeds.PROVIDER_FIREBASE_GEMINI && it.modelId == DefaultSeeds.DEFAULT_GEMINI_MODEL })
    }
}
