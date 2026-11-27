package com.theexpert9.pocketforge.data.ai

import android.content.Context
import android.util.Log
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.ensureFirebaseInitialized
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Built-in Gemini provider backed by Firebase AI Logic (`Firebase.ai`) and App Check.
 * Requires zero client-side API keys and streams responses via `generateContentStream()`.
 */
class GeminiFirebaseClient(
    private val appContext: Context
) : AiProviderClient {

    override suspend fun generateTurn(
        config: ProviderConfigEntity,
        modelId: String,
        apiKey: String?,
        systemInstruction: String,
        history: List<AgentConversationTurn>,
        enabledTools: List<AgentToolDefinition>,
        onPartialText: (String) -> Unit
    ): AgentStepResponse {
        val firebaseApp = ensureFirebaseInitialized(appContext)
            ?: throw IllegalStateException(
                "Built-in Gemini is unavailable because FirebaseApp could not initialize in this build. " +
                    "Verify google-services.json is present or switch to Web AI / Gemini (AI Studio Key) in Configure."
            )

        val safeModelId = modelId.ifBlank { "gemini-3.8-flash" }
        val model = Firebase.ai(app = firebaseApp).generativeModel(
            modelName = safeModelId,
            generationConfig = generationConfig {
                temperature = 0.25f
                maxOutputTokens = 4096
            },
            systemInstruction = content {
                text(systemInstruction)
            }
        )

        val contents = history.map { turn ->
            val role = if (turn.role == "assistant") "model" else "user"
            val body = if (turn.role == "tool") {
                "[TOOL_RESULT tool=${turn.toolName ?: "workspace"}]\n${turn.text}\n[/TOOL_RESULT]"
            } else {
                turn.text
            }
            content(role = role) {
                text(body)
            }
        }

        val accumulated = StringBuilder()
        try {
            withTimeout(60_000L) {
                model.generateContentStream(contents).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        val display = ToolCallEnvelopeParser.stripPartialToolMarkup(accumulated.toString())
                        if (display.isNotEmpty()) {
                            onPartialText(display)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini Firebase AI stream timed out for model=$safeModelId", e)
            throw IllegalStateException("Gemini request timed out after 60 seconds. Please retry.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Firebase AI generation failed for model=$safeModelId", e)
            throw IllegalStateException(
                "Gemini ($safeModelId) error: ${e.localizedMessage ?: "Generation failed."}",
                e
            )
        }

        val fullText = accumulated.toString()
        if (fullText.isBlank()) {
            throw IllegalStateException("Gemini ($safeModelId) returned an empty response.")
        }
        return ToolCallEnvelopeParser.extract(fullText)
    }

    companion object {
        private const val TAG = "GeminiFirebaseClient"
    }
}
