package com.example.data.ai

import android.util.Log
import com.example.data.db.CatalogModelEntity
import com.example.data.db.DefaultSeeds
import com.example.data.db.ProviderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OpenCodeSyncResult(
    val models: List<CatalogModelEntity>,
    val providerCount: Int
)

/**
 * Fetches OpenCode's live provider and model catalog directly from Models.dev (`https://models.dev/api.json`).
 */
class OpenCodeCatalogService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetchCatalog(): OpenCodeSyncResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MODELS_DEV_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "PocketForge-Android/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("OpenCode catalog sync failed (HTTP ${response.code}).")
            }

            val root = JSONObject(raw)
            val parsedModels = mutableListOf<CatalogModelEntity>()
            // Always preserve built-in zero-config Firebase Gemini models at the top
            parsedModels += DefaultSeeds.defaultCatalogModels()
                .filter { it.providerId == DefaultSeeds.PROVIDER_FIREBASE_GEMINI }

            val providerKeys = root.keys()
            var providersAdded = 1
            while (providerKeys.hasNext()) {
                val providerId = providerKeys.next()
                val providerObj = root.optJSONObject(providerId) ?: continue
                val providerName = providerObj.optString("name", providerId)
                val apiUrl = resolveProviderApiUrl(providerId, providerObj.optString("api", ""))
                val protocol = if (providerId.equals("anthropic", ignoreCase = true)) {
                    ProviderProtocol.ANTHROPIC
                } else {
                    ProviderProtocol.OPENAI_COMPATIBLE
                }

                val modelsObj = providerObj.optJSONObject("models") ?: continue
                val modelKeys = modelsObj.keys()
                var countForProvider = 0
                while (modelKeys.hasNext() && countForProvider < MAX_MODELS_PER_PROVIDER) {
                    val modelKey = modelKeys.next()
                    val modelObj = modelsObj.optJSONObject(modelKey) ?: continue
                    val modelId = modelObj.optString("id", modelKey)
                    val modelName = modelObj.optString("name", modelId)
                    val limitObj = modelObj.optJSONObject("limit")
                    val contextLimit = limitObj?.optInt("context", 128_000) ?: 128_000
                    val supportsTools = modelObj.optBoolean("tool_call", true)
                    val supportsReasoning = modelObj.optBoolean("reasoning", false)

                    parsedModels += CatalogModelEntity(
                        compositeId = "$providerId:$modelId",
                        providerId = providerId,
                        providerName = providerName,
                        providerApiUrl = apiUrl,
                        modelId = modelId,
                        modelName = modelName,
                        contextLimit = contextLimit,
                        supportsTools = supportsTools,
                        supportsReasoning = supportsReasoning,
                        protocolHint = protocol
                    )
                    countForProvider++
                }
                if (countForProvider > 0) providersAdded++
            }

            Log.d(TAG, "Synced ${parsedModels.size} models across $providersAdded providers from OpenCode/models.dev")
            OpenCodeSyncResult(models = parsedModels, providerCount = providersAdded)
        }
    }

    private fun resolveProviderApiUrl(providerId: String, rawApi: String): String {
        if (rawApi.startsWith("http://") || rawApi.startsWith("https://")) return rawApi.trimEnd('/')
        return when (providerId.lowercase()) {
            "openai" -> "https://api.openai.com/v1"
            "anthropic" -> "https://api.anthropic.com/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "deepseek" -> "https://api.deepseek.com/v1"
            "groq" -> "https://api.groq.com/openai/v1"
            "xai" -> "https://api.x.ai/v1"
            "mistral" -> "https://api.mistral.ai/v1"
            "togetherai", "together" -> "https://api.together.xyz/v1"
            "fireworks-ai", "fireworks" -> "https://api.fireworks.ai/inference/v1"
            "google" -> "https://generativelanguage.googleapis.com/v1beta/openai"
            else -> "https://openrouter.ai/api/v1"
        }
    }

    companion object {
        private const val TAG = "OpenCodeCatalogService"
        private const val MODELS_DEV_URL = "https://models.dev/api.json"
        private const val MAX_MODELS_PER_PROVIDER = 25
    }
}
