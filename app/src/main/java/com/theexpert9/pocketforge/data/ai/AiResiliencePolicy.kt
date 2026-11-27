package com.theexpert9.pocketforge.data.ai

import com.theexpert9.pocketforge.data.db.DefaultSeeds
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import java.io.IOException
import java.net.SocketTimeoutException

data class ModelAttemptTarget(
    val providerId: String,
    val modelId: String,
    val displayLabel: String
)

/**
 * Classifies transient server-overload errors (HTTP 503, 429, 529, UNAVAILABLE, high demand)
 * and builds deterministic exponential-backoff & Gemini model fallback ladders so autonomous
 * workspace runs never lose progress mid-task.
 */
object AiResiliencePolicy {

    val RETRY_BACKOFF_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L)

    fun isTransientOverloadError(error: Throwable): Boolean {
        val chainMessages = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()

        if (error is SocketTimeoutException || error is IOException) {
            return true
        }

        val transientMarkers = listOf(
            "http 503",
            " 503",
            "503:",
            "\"code\":503",
            "\"code\": 503",
            "http 429",
            " 429",
            "429:",
            "http 529",
            "http 502",
            "http 504",
            "service unavailable",
            "unavailable",
            "high demand",
            "overloaded",
            "temporarily unavailable",
            "resource_exhausted",
            "rate limit",
            "too many requests",
            "try again later"
        )
        return transientMarkers.any { marker -> chainMessages.contains(marker) }
    }

    fun shortReasonLabel(error: Throwable): String {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return when {
            msg.contains("503") || msg.contains("high demand") || msg.contains("unavailable") -> "HTTP 503 High Demand"
            msg.contains("429") || msg.contains("rate limit") || msg.contains("resource_exhausted") -> "HTTP 429 Rate Limit"
            msg.contains("overloaded") || msg.contains("529") -> "Server Overloaded"
            msg.contains("timed out") || error is SocketTimeoutException -> "Network Timeout"
            else -> "Temporary Service Error"
        }
    }

    /**
     * Builds the ordered list of (providerId, modelId) targets to attempt.
     * Primary target is always first; Gemini providers get automatic fallback across
     * supported Gemini 3.x / 4 tiers and can bridge from overloaded AI Studio API keys
     * to Built-in Firebase Gemini seamlessly.
     */
    fun buildAttemptChain(
        primaryProvider: ProviderConfigEntity,
        requestedModelId: String,
        firebaseBuiltInAvailable: Boolean
    ): List<ModelAttemptTarget> {
        val cleanModel = requestedModelId.trim().ifBlank { primaryProvider.selectedModelId }
        val chain = mutableListOf(
            ModelAttemptTarget(
                providerId = primaryProvider.id,
                modelId = cleanModel,
                displayLabel = "$cleanModel (${primaryProvider.displayName})"
            )
        )

        val isGeminiProvider = primaryProvider.protocol == ProviderProtocol.FIREBASE_GEMINI ||
            primaryProvider.id == "google_ai_studio" ||
            primaryProvider.baseUrl.contains("generativelanguage.googleapis.com", ignoreCase = true) ||
            cleanModel.startsWith("gemini-", ignoreCase = true)

        if (!isGeminiProvider) {
            return chain
        }

        if (primaryProvider.protocol == ProviderProtocol.FIREBASE_GEMINI) {
            val firebaseFallbacks = listOf(
                DefaultSeeds.DEFAULT_GEMINI_MODEL,
                "gemini-3.5-flash-lite",
                "gemini-4-argon"
            )
            firebaseFallbacks.forEach { fallbackModel ->
                if (!fallbackModel.equals(cleanModel, ignoreCase = true)) {
                    chain += ModelAttemptTarget(
                        providerId = DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
                        modelId = fallbackModel,
                        displayLabel = "$fallbackModel (Gemini Built-in)"
                    )
                }
            }
        } else {
            // Same API-key provider fallback to ultra-fast high-capacity Gemini 3.5 Flash-Lite
            val apiFallbacks = listOf("gemini-3.5-flash-lite", DefaultSeeds.DEFAULT_GEMINI_MODEL)
            apiFallbacks.forEach { fallbackModel ->
                if (!fallbackModel.equals(cleanModel, ignoreCase = true)) {
                    chain += ModelAttemptTarget(
                        providerId = primaryProvider.id,
                        modelId = fallbackModel,
                        displayLabel = "$fallbackModel (${primaryProvider.displayName})"
                    )
                }
            }
            // Final automatic bridge to zero-config Built-in Firebase Gemini if available
            if (firebaseBuiltInAvailable) {
                chain += ModelAttemptTarget(
                    providerId = DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
                    modelId = DefaultSeeds.DEFAULT_GEMINI_MODEL,
                    displayLabel = "${DefaultSeeds.DEFAULT_GEMINI_MODEL} (Gemini Built-in)"
                )
                chain += ModelAttemptTarget(
                    providerId = DefaultSeeds.PROVIDER_FIREBASE_GEMINI,
                    modelId = "gemini-3.5-flash-lite",
                    displayLabel = "gemini-3.5-flash-lite (Gemini Built-in)"
                )
            }
        }

        return chain.distinctBy { "${it.providerId}:${it.modelId}" }
    }
}
