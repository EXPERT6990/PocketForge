package com.example.data.sandbox

import android.content.Context
import java.io.File
import java.io.IOException

data class WorkspaceFileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lineCount: Int,
    val depth: Int
)

data class GrepMatch(
    val relativePath: String,
    val lineNumber: Int,
    val lineText: String,
    val contextBefore: List<String>,
    val contextAfter: List<String>
)

data class FileMutationResult(
    val relativePath: String,
    val previousContent: String?,
    val newContent: String?,
    val diffPreview: String,
    val summary: String
)

/**
 * Isolated internal-storage workspace filesystem with strict canonical path jail.
 * Every workspace lives under `context.filesDir/workspaces/<rootDirName>`.
 */
class WorkspaceSandbox(context: Context) {
    private val workspacesRoot: File = File(context.filesDir, "workspaces").apply { mkdirs() }

    fun getWorkspaceRoot(rootDirName: String): File {
        val safeName = rootDirName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "default" }
        return File(workspacesRoot, safeName).apply { mkdirs() }
    }

    fun resolveSafe(rootDirName: String, relativePath: String): File {
        val root = getWorkspaceRoot(rootDirName)
        val rootCanonical = root.canonicalPath
        val cleaned = relativePath.trim().removePrefix("/").removePrefix("./")
        val target = if (cleaned.isEmpty() || cleaned == ".") root else File(root, cleaned)
        val targetCanonical = target.canonicalPath
        if (targetCanonical != rootCanonical && !targetCanonical.startsWith("$rootCanonical${File.separator}")) {
            throw SecurityException("Access denied: '$relativePath' escapes isolated workspace sandbox.")
        }
        return target
    }

    fun deleteWorkspaceDirectory(rootDirName: String) {
        val root = getWorkspaceRoot(rootDirName)
        root.deleteRecursively()
    }

    fun countFiles(rootDirName: String): Int {
        val root = getWorkspaceRoot(rootDirName)
        return root.walkTopDown().count { it.isFile }
    }

    fun listFlatNodes(rootDirName: String): List<WorkspaceFileNode> {
        val root = getWorkspaceRoot(rootDirName)
        val result = mutableListOf<WorkspaceFileNode>()

        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (child in children) {
                val rel = child.relativeTo(root).path.replace('\\', '/')
                val lines = if (child.isFile && child.length() < 512_000) {
                    runCatching { child.useLines { it.count() } }.getOrDefault(0)
                } else 0
                result += WorkspaceFileNode(
                    name = child.name,
                    relativePath = rel,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    lineCount = lines,
                    depth = depth
                )
                if (child.isDirectory) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return result
    }

    fun listTree(rootDirName: String, relativePath: String = ".", maxDepth: Int = 4): String {
        val root = getWorkspaceRoot(rootDirName)
        val startDir = resolveSafe(rootDirName, relativePath)
        if (!startDir.exists()) return "Path not found: $relativePath"
        if (startDir.isFile) return "${startDir.name} (${startDir.length()} bytes)"

        val sb = StringBuilder()
        val displayRoot = if (startDir.canonicalPath == root.canonicalPath) "." else relativePath
        sb.appendLine("$displayRoot/")
        var entriesCount = 0

        fun buildTree(dir: File, prefix: String, depth: Int) {
            if (depth > maxDepth || entriesCount > 200) return
            val children = dir.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            children.forEachIndexed { index, child ->
                if (entriesCount >= 200) {
                    if (entriesCount == 200) sb.appendLine("$prefix└── ... (truncated at 200 entries)")
                    entriesCount++
                    return
                }
                entriesCount++
                val isLast = index == children.lastIndex
                val connector = if (isLast) "└── " else "├── "
                if (child.isDirectory) {
                    sb.appendLine("$prefix$connector${child.name}/")
                    buildTree(child, prefix + if (isLast) "    " else "│   ", depth + 1)
                } else {
                    val lines = runCatching { child.useLines { it.count() } }.getOrDefault(0)
                    sb.appendLine("$prefix$connector${child.name} (${lines}L, ${child.length()}B)")
                }
            }
        }

        buildTree(startDir, "", 1)
        if (entriesCount == 0) sb.appendLine("  (empty workspace)")
        return sb.toString().trimEnd()
    }

    fun findFiles(rootDirName: String, globPattern: String): List<String> {
        val root = getWorkspaceRoot(rootDirName)
        val regex = globToRegex(globPattern.ifBlank { "*" })
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filter { path -> regex.matches(path) || regex.matches(path.substringAfterLast('/')) }
            .sorted()
            .take(100)
            .toList()
    }

    fun grepSearch(
        rootDirName: String,
        pattern: String,
        relativePath: String = ".",
        contextLines: Int = 1,
        maxResults: Int = 40,
        ignoreCase: Boolean = true
    ): List<GrepMatch> {
        if (pattern.isBlank()) return emptyList()
        val root = getWorkspaceRoot(rootDirName)
        val target = resolveSafe(rootDirName, relativePath)
        if (!target.exists()) return emptyList()

        val regex = try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (_: Exception) {
            Regex(Regex.escape(pattern), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }

        val matches = mutableListOf<GrepMatch>()
        val files = if (target.isFile) sequenceOf(target) else target.walkTopDown().filter { it.isFile }
        val safeContext = contextLines.coerceIn(0, 4)

        for (file in files) {
            if (matches.size >= maxResults) break
            if (file.length() > 1_024_000) continue
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
            val rel = file.relativeTo(root).path.replace('\\', '/')
            for (i in lines.indices) {
                if (matches.size >= maxResults) break
                if (regex.containsMatchIn(lines[i])) {
                    val beforeStart = (i - safeContext).coerceAtLeast(0)
                    val afterEnd = (i + safeContext).coerceAtMost(lines.lastIndex)
                    matches += GrepMatch(
                        relativePath = rel,
                        lineNumber = i + 1,
                        lineText = lines[i],
                        contextBefore = if (beforeStart < i) lines.subList(beforeStart, i) else emptyList(),
                        contextAfter = if (i < afterEnd) lines.subList(i + 1, afterEnd + 1) else emptyList()
                    )
                }
            }
        }
        return matches
    }

    fun searchSymbols(rootDirName: String, query: String): List<GrepMatch> {
        val symbolRegex =
            """^\s*(public|private|internal|protected|export|async|suspend|open|data|sealed)?\s*(fun|class|interface|object|def|function|const|struct|type|enum)\s+[A-Za-z0-9_]*${Regex.escape(query.trim())}[A-Za-z0-9_]*"""
        return grepSearch(
            rootDirName = rootDirName,
            pattern = symbolRegex,
            relativePath = ".",
            contextLines = 0,
            maxResults = 40,
            ignoreCase = true
        )
    }

    fun readFileRange(
        rootDirName: String,
        relativePath: String,
        startLine: Int = 1,
        endLine: Int = 120,
        maxLinesCap: Int = 120
    ): String {
        val file = resolveSafe(rootDirName, relativePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("File not found: $relativePath")
        }
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return "File '$relativePath' is empty (0 lines)."

        val safeStart = startLine.coerceIn(1, lines.size)
        val requestedEnd = endLine.coerceIn(safeStart, lines.size)
        val cappedEnd = minOf(requestedEnd, safeStart + maxLinesCap.coerceAtLeast(10) - 1, lines.size)

        val sb = StringBuilder()
        sb.appendLine("FILE: $relativePath (lines $safeStart-$cappedEnd of ${lines.size})")
        for (lineNum in safeStart..cappedEnd) {
            sb.appendLine("${lineNum.toString().padStart(4)} | ${lines[lineNum - 1]}")
        }
        if (cappedEnd < lines.size) {
            sb.appendLine("... (${lines.size - cappedEnd} more lines available; call read_file_range with start_line=${cappedEnd + 1})")
        }
        return sb.toString().trimEnd()
    }

    fun readFullFile(rootDirName: String, relativePath: String): String {
        val file = resolveSafe(rootDirName, relativePath)
        if (!file.exists() || !file.isFile) throw IOException("File not found: $relativePath")
        return file.readText(Charsets.UTF_8)
    }

    fun writeFile(rootDirName: String, relativePath: String, content: String): FileMutationResult {
        val file = resolveSafe(rootDirName, relativePath)
        val previous = if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else null
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        val diff = buildUnifiedDiff(previous ?: "", content, relativePath)
        val action = if (previous == null) "Created" else "Updated"
        val lineCount = content.lines().size
        return FileMutationResult(
            relativePath = relativePath,
            previousContent = previous,
            newContent = content,
            diffPreview = diff,
            summary = "$action $relativePath ($lineCount lines)"
        )
    }

    fun patchFile(
        rootDirName: String,
        relativePath: String,
        searchBlock: String,
        replaceBlock: String
    ): FileMutationResult {
        val file = resolveSafe(rootDirName, relativePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("Cannot patch missing file: $relativePath")
        }
        val previous = file.readText(Charsets.UTF_8)
        if (searchBlock.isEmpty()) {
            throw IllegalArgumentException("search_block must not be empty.")
        }
        val occurrences = Regex.escape(searchBlock).toRegex().findAll(previous).count()
        if (occurrences == 0) {
            throw IllegalArgumentException(
                "search_block not found in $relativePath. Call read_file_range first to verify exact text."
            )
        }
        if (occurrences > 1) {
            throw IllegalArgumentException(
                "search_block matched $occurrences locations in $relativePath. Include more surrounding lines for a unique match."
            )
        }
        val updated = previous.replaceFirst(searchBlock, replaceBlock)
        file.writeText(updated, Charsets.UTF_8)
        val diff = buildUnifiedDiff(previous, updated, relativePath)
        return FileMutationResult(
            relativePath = relativePath,
            previousContent = previous,
            newContent = updated,
            diffPreview = diff,
            summary = "Patched $relativePath (${updated.lines().size} lines)"
        )
    }

    fun deleteFile(rootDirName: String, relativePath: String): FileMutationResult {
        val file = resolveSafe(rootDirName, relativePath)
        if (!file.exists()) throw IOException("Path not found: $relativePath")
        val previous = if (file.isFile) file.readText(Charsets.UTF_8) else null
        val deleted = file.deleteRecursively()
        if (!deleted) throw IOException("Failed to delete $relativePath")
        val diff = if (previous != null) buildUnifiedDiff(previous, "", relativePath) else "- deleted directory $relativePath"
        return FileMutationResult(
            relativePath = relativePath,
            previousContent = previous,
            newContent = null,
            diffPreview = diff,
            summary = "Deleted $relativePath"
        )
    }

    fun createDirectory(rootDirName: String, relativePath: String) {
        val dir = resolveSafe(rootDirName, relativePath)
        dir.mkdirs()
    }

    fun restoreSnapshot(rootDirName: String, relativePath: String, previousContent: String?) {
        val file = resolveSafe(rootDirName, relativePath)
        if (previousContent == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(previousContent, Charsets.UTF_8)
        }
    }

    fun buildUnifiedDiff(oldText: String, newText: String, path: String, maxDiffLines: Int = 48): String {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val newLines = if (newText.isEmpty()) emptyList() else newText.lines()

        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var oldEnd = oldLines.lastIndex
        var newEnd = newLines.lastIndex
        while (oldEnd >= prefix && newEnd >= prefix && oldLines[oldEnd] == newLines[newEnd]) {
            oldEnd--
            newEnd--
        }

        val sb = StringBuilder()
        sb.appendLine("--- a/$path")
        sb.appendLine("+++ b/$path")
        val contextBeforeStart = (prefix - 2).coerceAtLeast(0)
        sb.appendLine("@@ -${prefix + 1},${(oldEnd - prefix + 1).coerceAtLeast(0)} +${prefix + 1},${(newEnd - prefix + 1).coerceAtLeast(0)} @@")

        var emitted = 0
        for (i in contextBeforeStart until prefix) {
            sb.appendLine("  ${oldLines[i]}")
            emitted++
        }
        for (i in prefix..oldEnd) {
            if (emitted++ >= maxDiffLines) {
                sb.appendLine("... (diff truncated)")
                return sb.toString().trimEnd()
            }
            sb.appendLine("- ${oldLines[i]}")
        }
        for (i in prefix..newEnd) {
            if (emitted++ >= maxDiffLines) {
                sb.appendLine("... (diff truncated)")
                return sb.toString().trimEnd()
            }
            sb.appendLine("+ ${newLines[i]}")
        }
        val contextAfterEnd = minOf(newLines.lastIndex, newEnd + 2)
        for (i in (newEnd + 1)..contextAfterEnd) {
            if (emitted++ >= maxDiffLines) break
            sb.appendLine("  ${newLines[i]}")
        }
        return sb.toString().trimEnd()
    }

    fun seedTemplate(rootDirName: String, template: String) {
        when (template) {
            TEMPLATE_KOTLIN -> {
                writeFile(
                    rootDirName,
                    "README.md",
                    """
                    # AuthToken Service (Kotlin)
                    Isolated PocketForge starter workspace.
                    Try asking the Agent:
                    - "Search where token expiry is checked and fix the off-by-one bug."
                    - "Add unit test cases in tests/TokenValidatorTest.kt"
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "src/AuthToken.kt",
                    """
                    package demo.auth

                    data class AuthToken(
                        val subject: String,
                        val issuedAtEpochSec: Long,
                        val ttlSeconds: Long,
                        val scopes: List<String> = emptyList()
                    ) {
                        fun expiresAt(): Long = issuedAtEpochSec + ttlSeconds
                    }
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "src/TokenValidator.kt",
                    """
                    package demo.auth

                    class TokenValidator(private val clockSkewSec: Long = 30L) {
                        // BUG: uses '>' instead of '<=' causing expired tokens to be treated as valid
                        fun isExpired(token: AuthToken, nowEpochSec: Long): Boolean {
                            val effectiveExpiry = token.expiresAt() + clockSkewSec
                            return effectiveExpiry > nowEpochSec
                        }

                        fun hasScope(token: AuthToken, requiredScope: String): Boolean {
                            return token.scopes.any { it.equals(requiredScope, ignoreCase = true) }
                        }
                    }
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "config/workspace.json",
                    """
                    {
                      "project": "auth-token-service",
                      "language": "kotlin",
                      "strictMode": true,
                      "maxTokenTtlSeconds": 3600
                    }
                    """.trimIndent()
                )
            }
            TEMPLATE_PYTHON -> {
                writeFile(
                    rootDirName,
                    "README.md",
                    """
                    # Log Analyzer CLI (Python)
                    Isolated PocketForge Python starter project.
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "app/parser.py",
                    """
                    import re
                    from collections import Counter

                    LOG_PATTERN = re.compile(r"^(INFO|WARN|ERROR)\s+([a-zA-Z0-9_.-]+):\s+(.*)$")

                    def summarize_logs(lines: list[str]) -> dict[str, int]:
                        counts = Counter()
                        for line in lines:
                            match = LOG_PATTERN.match(line.strip())
                            if match:
                                level = match.group(1)
                                counts[level] += 1
                        return dict(counts)
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "app/main.py",
                    """
                    from parser import summarize_logs

                    SAMPLE = [
                        "INFO api.server: Booted on port 8080",
                        "WARN auth.guard: Slow token refresh",
                        "ERROR db.pool: Connection timeout after 5000ms"
                    ]

                    if __name__ == "__main__":
                        print(summarize_logs(SAMPLE))
                    """.trimIndent()
                )
            }
            TEMPLATE_WEB -> {
                writeFile(
                    rootDirName,
                    "index.html",
                    """
                    <!doctype html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8" />
                      <title>PocketForge Web Starter</title>
                      <link rel="stylesheet" href="styles.css" />
                    </head>
                    <body>
                      <main id="app">
                        <h1>Task Tracker</h1>
                        <ul id="list"></ul>
                      </main>
                      <script src="app.js"></script>
                    </body>
                    </html>
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "app.js",
                    """
                    const tasks = [
                      { id: 1, title: "Configure workspace tools", done: true },
                      { id: 2, title: "Test grep_search and patch_file", done: false }
                    ];

                    function renderTasks() {
                      const list = document.getElementById("list");
                      list.innerHTML = tasks
                        .map((t) => "<li>" + (t.done ? "[x] " : "[ ] ") + t.title + "</li>")
                        .join("");
                    }

                    renderTasks();
                    """.trimIndent()
                )
                writeFile(
                    rootDirName,
                    "styles.css",
                    """
                    body {
                      font-family: system-ui, sans-serif;
                      background: #0b0f17;
                      color: #f1f5f9;
                      padding: 24px;
                    }
                    """.trimIndent()
                )
            }
            else -> {
                writeFile(
                    rootDirName,
                    "README.md",
                    "# New Isolated Workspace\n\nReady for PocketForge AI tools and terminal commands.\n"
                )
            }
        }
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> sb.append("\\$ch")
                else -> sb.append(ch)
            }
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    companion object {
        const val TEMPLATE_KOTLIN = "Kotlin Service"
        const val TEMPLATE_PYTHON = "Python CLI"
        const val TEMPLATE_WEB = "Web App"
        const val TEMPLATE_BLANK = "Blank"
    }
}
