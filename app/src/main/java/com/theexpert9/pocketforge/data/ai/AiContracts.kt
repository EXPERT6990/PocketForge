package com.theexpert9.pocketforge.data.ai

import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.WorkspaceToolName
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AgentToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val params: List<AgentToolParam>
) {
    fun toOpenAiJson(): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        params.forEach { param ->
            props.put(
                param.name,
                JSONObject()
                    .put("type", param.type)
                    .put("description", param.description)
            )
            if (param.required) req.put(param.name)
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", props)
                            .put("required", req)
                    )
            )
    }

    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        params.forEach { param ->
            props.put(
                param.name,
                JSONObject()
                    .put("type", param.type)
                    .put("description", param.description)
            )
            if (param.required) req.put(param.name)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", req)
            )
    }

    fun toPromptLine(): String {
        val sig = params.joinToString(", ") {
            val opt = if (it.required) "" else "?"
            "${it.name}$opt: ${it.type}"
        }
        return "- $name($sig): $description"
    }
}

data class AgentToolCall(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val argumentsJson: String
)

data class AgentConversationTurn(
    val role: String, // "user", "assistant", "tool"
    val text: String,
    val toolCallId: String? = null,
    val toolName: String? = null
)

data class AgentStepResponse(
    val visibleText: String,
    val toolCalls: List<AgentToolCall>
)

interface AiProviderClient {
    suspend fun generateTurn(
        config: ProviderConfigEntity,
        modelId: String,
        apiKey: String?,
        systemInstruction: String,
        history: List<AgentConversationTurn>,
        enabledTools: List<AgentToolDefinition>,
        onPartialText: (String) -> Unit
    ): AgentStepResponse
}

object WorkspaceToolRegistry {
    val allTools: List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = WorkspaceToolName.LIST_TREE,
            description = "List workspace folder structure with line counts and byte sizes.",
            params = listOf(
                AgentToolParam("path", "string", "Relative path inside workspace (default '.')", required = false),
                AgentToolParam("max_depth", "integer", "Max directory depth 1..6 (default 4)", required = false)
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.FIND_FILES,
            description = "Find files matching a glob pattern like '*.kt' or '*Token*'.",
            params = listOf(
                AgentToolParam("glob", "string", "Glob pattern to match file paths or names")
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.GREP_SEARCH,
            description = "Search regex pattern across files returning file:line snippets without reading whole files.",
            params = listOf(
                AgentToolParam("pattern", "string", "Regex or text pattern to locate"),
                AgentToolParam("path", "string", "Relative path or '.' (default '.')", required = false),
                AgentToolParam("context_lines", "integer", "Lines of context around matches 0..3 (default 1)", required = false),
                AgentToolParam("max_results", "integer", "Max matches to return (default 30)", required = false)
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.SEARCH_SYMBOLS,
            description = "Find function, class, interface, or variable declarations matching query.",
            params = listOf(
                AgentToolParam("query", "string", "Symbol name or substring")
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.READ_FILE_RANGE,
            description = "Read a targeted line window of a file with line numbers.",
            params = listOf(
                AgentToolParam("path", "string", "Relative file path inside workspace"),
                AgentToolParam("start_line", "integer", "1-based start line number (default 1)", required = false),
                AgentToolParam("end_line", "integer", "1-based inclusive end line number (default 120)", required = false)
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.PATCH_FILE,
            description = "Replace an exact unique block of text inside an existing file (auto-creates undo snapshot).",
            params = listOf(
                AgentToolParam("path", "string", "Relative file path inside workspace"),
                AgentToolParam("search_block", "string", "Exact existing text block to replace"),
                AgentToolParam("replace_block", "string", "Replacement text block")
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.WRITE_FILE,
            description = "Create a new file or overwrite an entire file inside the workspace (auto-creates undo snapshot).",
            params = listOf(
                AgentToolParam("path", "string", "Relative file path inside workspace"),
                AgentToolParam("content", "string", "Complete UTF-8 file content")
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.DELETE_FILE,
            description = "Delete a file or directory inside the workspace (auto-creates undo snapshot).",
            params = listOf(
                AgentToolParam("path", "string", "Relative path to delete")
            )
        ),
        AgentToolDefinition(
            name = WorkspaceToolName.RUN_COMMAND,
            description = "Execute a sandboxed workspace shell command (ls, tree, grep, find, wc, diff, jsonlint, mkdir, etc.).",
            params = listOf(
                AgentToolParam("command", "string", "Command string to run inside workspace sandbox")
            )
        )
    )

    fun filterEnabled(enabledNames: Set<String>): List<AgentToolDefinition> =
        allTools.filter { it.name in enabledNames }
}

/**
 * Parses `<tool_call>{"tool":"...","args":{...}}</tool_call>` blocks emitted by models
 * operating under PocketForge's customizable prompt protocol.
 */
object ToolCallEnvelopeParser {
    private val TOOL_BLOCK_REGEX = Regex(
        pattern = "<tool_call>\\s*(\\{[\\s\\S]*?\\})\\s*</tool_call>",
        option = RegexOption.IGNORE_CASE
    )

    fun extract(rawText: String): AgentStepResponse {
        val calls = mutableListOf<AgentToolCall>()
        val matches = TOOL_BLOCK_REGEX.findAll(rawText).toList()
        for (match in matches) {
            val jsonCandidate = match.groupValues[1].trim()
            runCatching {
                val obj = JSONObject(jsonCandidate)
                val name = obj.optString("tool").ifBlank { obj.optString("name") }
                if (name.isNotBlank()) {
                    val argsObj = obj.optJSONObject("args")
                        ?: obj.optJSONObject("arguments")
                        ?: JSONObject()
                    calls += AgentToolCall(
                        name = name.trim(),
                        argumentsJson = argsObj.toString()
                    )
                }
            }
        }
        val cleanedText = TOOL_BLOCK_REGEX.replace(rawText, "").trim()
        return AgentStepResponse(visibleText = cleanedText, toolCalls = calls)
    }

    fun stripPartialToolMarkup(streamingText: String): String {
        val openIdx = streamingText.indexOf("<tool_call>", ignoreCase = true)
        val withoutClosed = TOOL_BLOCK_REGEX.replace(streamingText, "")
        return if (openIdx >= 0 && withoutClosed.contains("<tool_call>", ignoreCase = true)) {
            withoutClosed.substringBefore("<tool_call>").trim()
        } else {
            withoutClosed.trim()
        }
    }
}
