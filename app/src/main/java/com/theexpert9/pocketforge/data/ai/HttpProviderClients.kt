package com.theexpert9.pocketforge.data.ai

import android.util.Log
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real OkHttp client supporting OpenAI-compatible endpoints (OpenAI, OpenRouter, DeepSeek,
 * Groq, xAI, Ollama, custom OpenCode endpoints) and Anthropic Messages API.
 */
class HttpAiProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : AiProviderClient {

    override suspend fun generateTurn(
        config: ProviderConfigEntity,
        modelId: String,
        apiKey: String?,
        systemInstruction: String,
        history: List<AgentConversationTurn>,
        enabledTools: List<AgentToolDefinition>,
        onPartialText: (String) -> Unit
    ): AgentStepResponse = withContext(Dispatchers.IO) {
        val isLocalOllama = config.baseUrl.contains("11434") || config.id == "ollama"
        if (!isLocalOllama && apiKey.isNullOrBlank()) {
            throw IllegalStateException(
                "No API key configured for ${config.displayName}. Open Configure > Providers to add your encrypted key."
            )
        }
        try {
            withTimeout(60_000L) {
                when (config.protocol) {
                    ProviderProtocol.ANTHROPIC -> executeAnthropic(
                        config, modelId, apiKey.orEmpty(), systemInstruction, history, enabledTools, onPartialText
                    )
                    else -> executeOpenAiCompatible(
                        config, modelId, apiKey.orEmpty(), systemInstruction, history, enabledTools, onPartialText
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Provider ${config.id} timed out", e)
            throw IllegalStateException("${config.displayName} timed out after 60 seconds.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Provider ${config.id} failed", e)
            throw e
        }
    }

    suspend fun testConnection(config: ProviderConfigEntity, apiKey: String?): String =
        withContext(Dispatchers.IO) {
            if (config.protocol == ProviderProtocol.FIREBASE_GEMINI) {
                return@withContext "Built-in Gemini uses Firebase AI Logic + App Check (ready, no key required)."
            }
            val isLocalOllama = config.baseUrl.contains("11434") || config.id == "ollama"
            if (!isLocalOllama && apiKey.isNullOrBlank()) {
                throw IllegalStateException("Please enter an API key before testing ${config.displayName}.")
            }

            val base = config.baseUrl.trimEnd('/')
            val requestBuilder = if (config.protocol == ProviderProtocol.ANTHROPIC) {
                val payload = JSONObject()
                    .put("model", config.selectedModelId)
                    .put("max_tokens", 16)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", "Reply with OK.")
                        )
                    )
                Request.Builder()
                    .url("$base/messages")
                    .header("x-api-key", apiKey.orEmpty())
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            } else {
                val req = Request.Builder()
                    .url("$base/models")
                    .get()
                if (!apiKey.isNullOrBlank()) {
                    req.header("Authorization", "Bearer $apiKey")
                }
                req
            }

            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val snippet = body.take(240)
                    throw IllegalStateException("HTTP ${resp.code}: $snippet")
                }
                "Connected to ${config.displayName} (HTTP ${resp.code})."
            }
        }

    private fun executeOpenAiCompatible(
        config: ProviderConfigEntity,
        modelId: String,
        apiKey: String,
        systemInstruction: String,
        history: List<AgentConversationTurn>,
        enabledTools: List<AgentToolDefinition>,
        onPartialText: (String) -> Unit
    ): AgentStepResponse {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        history.forEach { turn ->
            when (turn.role) {
                "assistant" -> messages.put(JSONObject().put("role", "assistant").put("content", turn.text))
                "tool" -> messages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "[TOOL_RESULT tool=${turn.toolName ?: "workspace"}]\n${turn.text}\n[/TOOL_RESULT]")
                )
                else -> messages.put(JSONObject().put("role", "user").put("content", turn.text))
            }
        }

        val payload = JSONObject()
            .put("model", modelId)
            .put("messages", messages)
            .put("temperature", 0.25)

        if (enabledTools.isNotEmpty() && !config.baseUrl.contains("11434")) {
            val toolsArray = JSONArray()
            enabledTools.forEach { toolsArray.put(it.toOpenAiJson()) }
            payload.put("tools", toolsArray)
            payload.put("tool_choice", "auto")
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        if (base.contains("openrouter.ai")) {
            reqBuilder.header("HTTP-Referer", "https://pocketforge.local")
            reqBuilder.header("X-Title", "PocketForge Android IDE")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("${config.displayName} HTTP ${response.code}: ${rawBody.take(300)}")
            }
            val root = JSONObject(rawBody)
            val choices = root.optJSONArray("choices")
                ?: throw IllegalStateException("Malformed response from ${config.displayName}: missing choices.")
            if (choices.length() == 0) {
                throw IllegalStateException("${config.displayName} returned zero choices.")
            }
            val message = choices.getJSONObject(0).getJSONObject("message")
            val contentText = message.optString("content", "")

            val nativeCalls = mutableListOf<AgentToolCall>()
            val toolCallsArr = message.optJSONArray("tool_calls")
            if (toolCallsArr != null) {
                for (i in 0 until toolCallsArr.length()) {
                    val tc = toolCallsArr.getJSONObject(i)
                    val fn = tc.optJSONObject("function") ?: continue
                    val fnName = fn.optString("name")
                    val fnArgs = fn.optString("arguments", "{}")
                    if (fnName.isNotBlank()) {
                        nativeCalls += AgentToolCall(
                            id = tc.optString("id").ifBlank { "tc_$i" },
                            name = fnName,
                            argumentsJson = fnArgs
                        )
                    }
                }
            }

            if (nativeCalls.isNotEmpty()) {
                val cleaned = ToolCallEnvelopeParser.stripPartialToolMarkup(contentText)
                if (cleaned.isNotEmpty()) onPartialText(cleaned)
                return AgentStepResponse(visibleText = cleaned, toolCalls = nativeCalls)
            }

            val parsed = ToolCallEnvelopeParser.extract(contentText)
            if (parsed.visibleText.isNotEmpty()) onPartialText(parsed.visibleText)
            return parsed
        }
    }

    private fun executeAnthropic(
        config: ProviderConfigEntity,
        modelId: String,
        apiKey: String,
        systemInstruction: String,
        history: List<AgentConversationTurn>,
        enabledTools: List<AgentToolDefinition>,
        onPartialText: (String) -> Unit
    ): AgentStepResponse {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/messages")) base else "$base/messages"

        val messages = JSONArray()
        history.forEach { turn ->
            val role = if (turn.role == "assistant") "assistant" else "user"
            val text = if (turn.role == "tool") {
                "[TOOL_RESULT tool=${turn.toolName ?: "workspace"}]\n${turn.text}\n[/TOOL_RESULT]"
            } else {
                turn.text
            }
            messages.put(JSONObject().put("role", role).put("content", text))
        }

        val payload = JSONObject()
            .put("model", modelId)
            .put("max_tokens", 4096)
            .put("system", systemInstruction)
            .put("messages", messages)

        if (enabledTools.isNotEmpty()) {
            val toolsArr = JSONArray()
            enabledTools.forEach { toolsArr.put(it.toAnthropicJson()) }
            payload.put("tools", toolsArr)
        }

        val req = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Anthropic HTTP ${response.code}: ${rawBody.take(300)}")
            }
            val root = JSONObject(rawBody)
            val contentArray = root.optJSONArray("content") ?: JSONArray()
            val textBuilder = StringBuilder()
            val nativeCalls = mutableListOf<AgentToolCall>()

            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> textBuilder.append(block.optString("text"))
                    "tool_use" -> {
                        val name = block.optString("name")
                        val inputObj = block.optJSONObject("input") ?: JSONObject()
                        if (name.isNotBlank()) {
                            nativeCalls += AgentToolCall(
                                id = block.optString("id").ifBlank { "tu_$i" },
                                name = name,
                                argumentsJson = inputObj.toString()
                            )
                        }
                    }
                }
            }

            if (nativeCalls.isNotEmpty()) {
                val cleaned = textBuilder.toString().trim()
                if (cleaned.isNotEmpty()) onPartialText(cleaned)
                return AgentStepResponse(visibleText = cleaned, toolCalls = nativeCalls)
            }

            val parsed = ToolCallEnvelopeParser.extract(textBuilder.toString())
            if (parsed.visibleText.isNotEmpty()) onPartialText(parsed.visibleText)
            return parsed
        }
    }

    companion object {
        private const val TAG = "HttpAiProviderClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
