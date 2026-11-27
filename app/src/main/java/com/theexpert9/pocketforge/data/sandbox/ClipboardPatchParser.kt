package com.theexpert9.pocketforge.data.sandbox

import com.theexpert9.pocketforge.data.ai.ToolCallEnvelopeParser
import com.theexpert9.pocketforge.data.db.WorkspaceToolName
import org.json.JSONObject

enum class ClipboardPatchKind {
    SEARCH_REPLACE,
    FULL_WRITE,
    RAW_SNIPPET
}

data class ClipboardPatchProposal(
    val relativePath: String?,
    val kind: ClipboardPatchKind,
    val searchBlock: String = "",
    val replaceOrContent: String,
    val summary: String
)

/**
 * Parses AI code responses copied from Gemini, ChatGPT, or Claude web subscriptions
 * into safe, structured workspace patch proposals.
 */
object ClipboardPatchParser {

    private val SEARCH_REPLACE_REGEX = Regex(
        pattern = """(?mi)^(?:FILE|PATH)\s*:\s*`?([^\s`\n]+)`?\s*\n<<<SEARCH\s*\n([\s\S]*?)\n===\s*\n([\s\S]*?)\n>>>REPLACE""",
    )

    private val FILE_FENCED_REGEX = Regex(
        pattern = """(?mi)^(?:FILE|PATH)\s*:\s*`?([^\s`\n]+)`?\s*\n```[a-zA-Z0-9_+-]*\s*\n([\s\S]*?)\n```""",
    )

    private val GENERIC_FENCED_REGEX = Regex(
        pattern = """```([a-zA-Z0-9_+-]*)\s*\n([\s\S]*?)\n```"""
    )

    fun parse(clipboardText: String): List<ClipboardPatchProposal> {
        val trimmed = clipboardText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val proposals = mutableListOf<ClipboardPatchProposal>()

        // 1. Check for PocketForge <tool_call> JSON blocks (patch_file / write_file)
        val extractedTools = ToolCallEnvelopeParser.extract(trimmed).toolCalls
        for (call in extractedTools) {
            val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: continue
            val path = args.optString("path").trim()
            if (path.isBlank()) continue
            when (call.name) {
                WorkspaceToolName.PATCH_FILE -> {
                    val search = args.optString("search_block")
                    val replace = args.optString("replace_block")
                    if (search.isNotEmpty()) {
                        proposals += ClipboardPatchProposal(
                            relativePath = path,
                            kind = ClipboardPatchKind.SEARCH_REPLACE,
                            searchBlock = search,
                            replaceOrContent = replace,
                            summary = "Patch $path (search/replace)"
                        )
                    }
                }
                WorkspaceToolName.WRITE_FILE -> {
                    val content = args.optString("content")
                    proposals += ClipboardPatchProposal(
                        relativePath = path,
                        kind = ClipboardPatchKind.FULL_WRITE,
                        replaceOrContent = content,
                        summary = "Write $path (${content.lines().size} lines)"
                    )
                }
            }
        }
        if (proposals.isNotEmpty()) return proposals

        // 2. Check for FILE: path + <<<SEARCH / === / >>>REPLACE blocks
        for (match in SEARCH_REPLACE_REGEX.findAll(trimmed)) {
            val path = sanitizePath(match.groupValues[1])
            val search = match.groupValues[2]
            val replace = match.groupValues[3]
            if (path.isNotBlank() && search.isNotEmpty()) {
                proposals += ClipboardPatchProposal(
                    relativePath = path,
                    kind = ClipboardPatchKind.SEARCH_REPLACE,
                    searchBlock = search,
                    replaceOrContent = replace,
                    summary = "Patch $path (search/replace)"
                )
            }
        }
        if (proposals.isNotEmpty()) return proposals

        // 3. Check for FILE: path + fenced code block
        for (match in FILE_FENCED_REGEX.findAll(trimmed)) {
            val path = sanitizePath(match.groupValues[1])
            val code = match.groupValues[2]
            if (path.isNotBlank()) {
                proposals += ClipboardPatchProposal(
                    relativePath = path,
                    kind = ClipboardPatchKind.FULL_WRITE,
                    replaceOrContent = code,
                    summary = "Write $path (${code.lines().size} lines)"
                )
            }
        }
        if (proposals.isNotEmpty()) return proposals

        // 4. Fallback: first fenced code block or raw snippet (user selects destination file)
        val firstFence = GENERIC_FENCED_REGEX.find(trimmed)
        val snippet = firstFence?.groupValues?.getOrNull(2) ?: trimmed
        val firstLinePathHint = extractCommentPathHint(snippet)
        proposals += ClipboardPatchProposal(
            relativePath = firstLinePathHint,
            kind = if (firstLinePathHint != null) ClipboardPatchKind.FULL_WRITE else ClipboardPatchKind.RAW_SNIPPET,
            replaceOrContent = snippet,
            summary = if (firstLinePathHint != null) {
                "Write $firstLinePathHint (${snippet.lines().size} lines)"
            } else {
                "Raw snippet (${snippet.lines().size} lines — choose target file)"
            }
        )
        return proposals
    }

    fun buildContextPromptWrapper(
        workspaceName: String,
        contextTitle: String,
        contextPayload: String
    ): String {
        return buildString {
            appendLine("=== POCKETFORGE ISOLATED WORKSPACE CONTEXT ===")
            appendLine("Workspace: $workspaceName")
            appendLine("Slice: $contextTitle")
            appendLine()
            appendLine(contextPayload.trimEnd())
            appendLine()
            appendLine("=== RESPONSE FORMAT FOR ONE-TAP APPLY IN POCKETFORGE ===")
            appendLine("When proposing edits, format each file change using:")
            appendLine("FILE: relative/path/to/file.ext")
            appendLine("<<<SEARCH")
            appendLine("exact existing lines to replace")
            appendLine("===")
            appendLine("replacement lines")
            appendLine(">>>REPLACE")
            appendLine("(Or `FILE: relative/path/to/file.ext` followed by a fenced code block for new/full files.)")
        }
    }

    private fun sanitizePath(raw: String): String =
        raw.trim().removePrefix("./").removePrefix("/").trim('`', '"', '\'')

    private fun extractCommentPathHint(code: String): String? {
        val firstLine = code.lineSequence().firstOrNull()?.trim().orEmpty()
        val match = Regex("""^(?://|#|--|/\*)\s*(?:FILE|PATH)\s*:\s*([^\s*]+)""", RegexOption.IGNORE_CASE)
            .find(firstLine)
        return match?.groupValues?.getOrNull(1)?.let { sanitizePath(it) }?.takeIf { it.isNotBlank() }
    }
}
