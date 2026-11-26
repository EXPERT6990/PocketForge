package com.example.data.sandbox

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TerminalCommandResult(
    val output: String,
    val exitCode: Int,
    val newWorkingDir: String,
    val clearRequested: Boolean = false
)

/**
 * Play-policy-compliant, deterministic POSIX-style command engine strictly confined
 * to a workspace's isolated directory (`filesDir/workspaces/<rootDirName>`).
 */
class SandboxedTerminalEngine(private val sandbox: WorkspaceSandbox) {

    fun execute(
        rootDirName: String,
        currentWorkingDir: String,
        rawCommand: String
    ): TerminalCommandResult {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            return TerminalCommandResult("", 0, currentWorkingDir)
        }

        // Support simple chained commands with &&
        if (trimmed.contains("&&")) {
            val segments = trimmed.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
            var cwd = currentWorkingDir
            val outputs = mutableListOf<String>()
            for (segment in segments) {
                val res = executeSingle(rootDirName, cwd, segment)
                cwd = res.newWorkingDir
                if (res.output.isNotEmpty()) outputs += res.output
                if (res.exitCode != 0) {
                    return TerminalCommandResult(outputs.joinToString("\n"), res.exitCode, cwd)
                }
            }
            return TerminalCommandResult(outputs.joinToString("\n"), 0, cwd)
        }

        return executeSingle(rootDirName, currentWorkingDir, trimmed)
    }

    private fun executeSingle(
        rootDirName: String,
        currentWorkingDir: String,
        commandLine: String
    ): TerminalCommandResult {
        return try {
            val (coreCmd, redirectTarget, appendMode) = parseRedirection(commandLine)
            val tokens = tokenize(coreCmd)
            if (tokens.isEmpty()) return TerminalCommandResult("", 0, currentWorkingDir)

            val cmd = tokens.first()
            val args = tokens.drop(1)

            val result = when (cmd) {
                "clear" -> TerminalCommandResult("", 0, currentWorkingDir, clearRequested = true)
                "help" -> TerminalCommandResult(HELP_TEXT, 0, currentWorkingDir)
                "pwd" -> TerminalCommandResult(
                    if (currentWorkingDir.isEmpty()) "/workspace" else "/workspace/$currentWorkingDir",
                    0,
                    currentWorkingDir
                )
                "cd" -> handleCd(rootDirName, currentWorkingDir, args)
                "ls" -> handleLs(rootDirName, currentWorkingDir, args)
                "tree" -> handleTree(rootDirName, currentWorkingDir, args)
                "cat" -> handleCat(rootDirName, currentWorkingDir, args)
                "nl" -> handleNl(rootDirName, currentWorkingDir, args)
                "head" -> handleHeadTail(rootDirName, currentWorkingDir, args, isHead = true)
                "tail" -> handleHeadTail(rootDirName, currentWorkingDir, args, isHead = false)
                "grep", "rg" -> handleGrep(rootDirName, currentWorkingDir, args)
                "find" -> handleFind(rootDirName, currentWorkingDir, args)
                "wc" -> handleWc(rootDirName, currentWorkingDir, args)
                "stat" -> handleStat(rootDirName, currentWorkingDir, args)
                "mkdir" -> handleMkdir(rootDirName, currentWorkingDir, args)
                "touch" -> handleTouch(rootDirName, currentWorkingDir, args)
                "cp" -> handleCp(rootDirName, currentWorkingDir, args)
                "mv" -> handleMv(rootDirName, currentWorkingDir, args)
                "rm" -> handleRm(rootDirName, currentWorkingDir, args)
                "diff" -> handleDiff(rootDirName, currentWorkingDir, args)
                "echo" -> TerminalCommandResult(args.joinToString(" "), 0, currentWorkingDir)
                "jsonlint" -> handleJsonLint(rootDirName, currentWorkingDir, args)
                "env" -> TerminalCommandResult(
                    "SANDBOX_MODE=isolated\nWORKSPACE_ROOT=/workspace\nPWD=/workspace/${currentWorkingDir}\nSHELL=pocketforge-sh",
                    0,
                    currentWorkingDir
                )
                else -> TerminalCommandResult(
                    "pocketforge-sh: unknown command '$cmd'. Type 'help' to see available workspace commands.",
                    127,
                    currentWorkingDir
                )
            }

            if (redirectTarget != null && result.exitCode == 0) {
                val targetRel = resolveRelativeToCwd(currentWorkingDir, redirectTarget)
                val targetFile = sandbox.resolveSafe(rootDirName, targetRel)
                targetFile.parentFile?.mkdirs()
                if (appendMode && targetFile.exists()) {
                    targetFile.appendText("${result.output}\n", Charsets.UTF_8)
                } else {
                    targetFile.writeText("${result.output}\n", Charsets.UTF_8)
                }
                TerminalCommandResult("Wrote output to $targetRel", 0, result.newWorkingDir)
            } else {
                result
            }
        } catch (e: SecurityException) {
            TerminalCommandResult("Permission denied: ${e.message}", 1, currentWorkingDir)
        } catch (e: Exception) {
            TerminalCommandResult("Error: ${e.message ?: "Command failed"}", 1, currentWorkingDir)
        }
    }

    private fun handleCd(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val targetArg = args.firstOrNull() ?: ""
        if (targetArg.isEmpty() || targetArg == "/" || targetArg == "~") {
            return TerminalCommandResult("", 0, "")
        }
        val rel = resolveRelativeToCwd(cwd, targetArg)
        val dir = sandbox.resolveSafe(rootDirName, rel)
        if (!dir.exists() || !dir.isDirectory) {
            return TerminalCommandResult("cd: no such directory: $targetArg", 1, cwd)
        }
        val root = sandbox.getWorkspaceRoot(rootDirName)
        val normalized = if (dir.canonicalPath == root.canonicalPath) {
            ""
        } else {
            dir.canonicalFile.relativeTo(root.canonicalFile).path.replace('\\', '/')
        }
        return TerminalCommandResult("", 0, normalized)
    }

    private fun handleLs(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val longFormat = args.any { it.startsWith("-") && it.contains("l") }
        val pathArg = args.firstOrNull { !it.startsWith("-") } ?: "."
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val target = sandbox.resolveSafe(rootDirName, rel)
        if (!target.exists()) return TerminalCommandResult("ls: cannot access '$pathArg': No such file or directory", 1, cwd)
        if (target.isFile) {
            return TerminalCommandResult(formatLsEntry(target, longFormat), 0, cwd)
        }
        val children = target.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        if (children.isEmpty()) return TerminalCommandResult("(empty)", 0, cwd)
        val out = children.joinToString("\n") { formatLsEntry(it, longFormat) }
        return TerminalCommandResult(out, 0, cwd)
    }

    private fun formatLsEntry(file: File, longFormat: Boolean): String {
        val displayName = if (file.isDirectory) "${file.name}/" else file.name
        if (!longFormat) return displayName
        val type = if (file.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val size = file.length().toString().padStart(7)
        return "$type  $size  $displayName"
    }

    private fun handleTree(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pathArg = args.firstOrNull { !it.startsWith("-") && it.toIntOrNull() == null } ?: "."
        val depth = args.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: 4
        val rel = resolveRelativeToCwd(cwd, pathArg)
        return TerminalCommandResult(sandbox.listTree(rootDirName, rel, depth.coerceIn(1, 8)), 0, cwd)
    }

    private fun handleCat(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pathArg = args.firstOrNull() ?: return TerminalCommandResult("Usage: cat <file>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val text = sandbox.readFullFile(rootDirName, rel)
        val capped = if (text.length > 16_000) "${text.take(16_000)}\n... (truncated; use head/tail or read_file_range)" else text
        return TerminalCommandResult(capped, 0, cwd)
    }

    private fun handleNl(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pathArg = args.firstOrNull() ?: return TerminalCommandResult("Usage: nl <file>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val out = sandbox.readFileRange(rootDirName, rel, 1, 200, maxLinesCap = 200)
        return TerminalCommandResult(out, 0, cwd)
    }

    private fun handleHeadTail(
        rootDirName: String,
        cwd: String,
        args: List<String>,
        isHead: Boolean
    ): TerminalCommandResult {
        var count = 20
        var pathArg: String? = null
        var i = 0
        while (i < args.size) {
            if (args[i] == "-n" && i + 1 < args.size) {
                count = args[i + 1].toIntOrNull()?.coerceIn(1, 300) ?: 20
                i += 2
            } else if (!args[i].startsWith("-")) {
                pathArg = args[i]
                i++
            } else {
                i++
            }
        }
        val targetPath = pathArg ?: return TerminalCommandResult("Usage: ${if (isHead) "head" else "tail"} [-n N] <file>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, targetPath)
        val lines = sandbox.readFullFile(rootDirName, rel).lines()
        val slice = if (isHead) lines.take(count) else lines.takeLast(count)
        return TerminalCommandResult(slice.joinToString("\n"), 0, cwd)
    }

    private fun handleGrep(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val ignoreCase = args.any { it.startsWith("-") && it.contains("i") }
        val nonFlags = args.filter { !it.startsWith("-") }
        val pattern = nonFlags.firstOrNull()
            ?: return TerminalCommandResult("Usage: grep [-i] [-rn] <pattern> [path]", 1, cwd)
        val pathArg = nonFlags.getOrNull(1) ?: "."
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val matches = sandbox.grepSearch(
            rootDirName = rootDirName,
            pattern = pattern,
            relativePath = rel,
            contextLines = 0,
            maxResults = 60,
            ignoreCase = ignoreCase
        )
        if (matches.isEmpty()) return TerminalCommandResult("No matches for '$pattern'", 1, cwd)
        val out = matches.joinToString("\n") { "${it.relativePath}:${it.lineNumber}: ${it.lineText}" }
        return TerminalCommandResult(out, 0, cwd)
    }

    private fun handleFind(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pattern = args.lastOrNull { !it.startsWith("-") } ?: "*"
        val files = sandbox.findFiles(rootDirName, pattern)
        return if (files.isEmpty()) {
            TerminalCommandResult("No files matched '$pattern'", 1, cwd)
        } else {
            TerminalCommandResult(files.joinToString("\n"), 0, cwd)
        }
    }

    private fun handleWc(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pathArg = args.firstOrNull { !it.startsWith("-") }
            ?: return TerminalCommandResult("Usage: wc <file>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val text = sandbox.readFullFile(rootDirName, rel)
        val lines = if (text.isEmpty()) 0 else text.lines().size
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return TerminalCommandResult("$lines lines  $words words  $bytes bytes  $rel", 0, cwd)
    }

    private fun handleStat(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pathArg = args.firstOrNull() ?: return TerminalCommandResult("Usage: stat <path>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val file = sandbox.resolveSafe(rootDirName, rel)
        if (!file.exists()) return TerminalCommandResult("stat: cannot stat '$pathArg': No such file", 1, cwd)
        val kind = if (file.isDirectory) "Directory" else "Regular File"
        return TerminalCommandResult(
            "Path: $rel\nType: $kind\nSize: ${file.length()} bytes\nModified: ${file.lastModified()}",
            0,
            cwd
        )
    }

    private fun handleMkdir(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val target = args.lastOrNull { !it.startsWith("-") }
            ?: return TerminalCommandResult("Usage: mkdir <dir>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, target)
        sandbox.createDirectory(rootDirName, rel)
        return TerminalCommandResult("Created directory $rel/", 0, cwd)
    }

    private fun handleTouch(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val target = args.firstOrNull() ?: return TerminalCommandResult("Usage: touch <file>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, target)
        val file = sandbox.resolveSafe(rootDirName, rel)
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("", Charsets.UTF_8) else file.setLastModified(System.currentTimeMillis())
        return TerminalCommandResult("Touched $rel", 0, cwd)
    }

    private fun handleCp(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        if (args.size < 2) return TerminalCommandResult("Usage: cp <source> <dest>", 1, cwd)
        val src = sandbox.resolveSafe(rootDirName, resolveRelativeToCwd(cwd, args[0]))
        val dst = sandbox.resolveSafe(rootDirName, resolveRelativeToCwd(cwd, args[1]))
        if (!src.exists()) return TerminalCommandResult("cp: source not found: ${args[0]}", 1, cwd)
        dst.parentFile?.mkdirs()
        src.copyRecursively(dst, overwrite = true)
        return TerminalCommandResult("Copied ${args[0]} -> ${args[1]}", 0, cwd)
    }

    private fun handleMv(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        if (args.size < 2) return TerminalCommandResult("Usage: mv <source> <dest>", 1, cwd)
        val src = sandbox.resolveSafe(rootDirName, resolveRelativeToCwd(cwd, args[0]))
        val dst = sandbox.resolveSafe(rootDirName, resolveRelativeToCwd(cwd, args[1]))
        if (!src.exists()) return TerminalCommandResult("mv: source not found: ${args[0]}", 1, cwd)
        dst.parentFile?.mkdirs()
        src.copyRecursively(dst, overwrite = true)
        src.deleteRecursively()
        return TerminalCommandResult("Moved ${args[0]} -> ${args[1]}", 0, cwd)
    }

    private fun handleRm(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val target = args.lastOrNull { !it.startsWith("-") }
            ?: return TerminalCommandResult("Usage: rm [-r] <path>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, target)
        if (rel.isEmpty() || rel == ".") {
            return TerminalCommandResult("rm: refusing to delete workspace root", 1, cwd)
        }
        sandbox.deleteFile(rootDirName, rel)
        return TerminalCommandResult("Removed $rel", 0, cwd)
    }

    private fun handleDiff(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        if (args.size < 2) return TerminalCommandResult("Usage: diff <file1> <file2>", 1, cwd)
        val left = sandbox.readFullFile(rootDirName, resolveRelativeToCwd(cwd, args[0]))
        val right = sandbox.readFullFile(rootDirName, resolveRelativeToCwd(cwd, args[1]))
        if (left == right) return TerminalCommandResult("Files are identical.", 0, cwd)
        return TerminalCommandResult(sandbox.buildUnifiedDiff(left, right, args[1]), 0, cwd)
    }

    private fun handleJsonLint(rootDirName: String, cwd: String, args: List<String>): TerminalCommandResult {
        val pathArg = args.firstOrNull() ?: return TerminalCommandResult("Usage: jsonlint <file.json>", 1, cwd)
        val rel = resolveRelativeToCwd(cwd, pathArg)
        val text = sandbox.readFullFile(rootDirName, rel).trim()
        return try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            TerminalCommandResult("Valid JSON: $rel", 0, cwd)
        } catch (e: Exception) {
            TerminalCommandResult("Invalid JSON in $rel: ${e.message}", 1, cwd)
        }
    }

    private fun resolveRelativeToCwd(cwd: String, arg: String): String {
        val trimmed = arg.trim()
        if (trimmed.startsWith("/")) return trimmed.removePrefix("/")
        return if (cwd.isBlank()) trimmed else "$cwd/$trimmed"
    }

    private fun parseRedirection(cmd: String): Triple<String, String?, Boolean> {
        val appendIdx = cmd.indexOf(">>")
        if (appendIdx > 0) {
            return Triple(cmd.substring(0, appendIdx).trim(), cmd.substring(appendIdx + 2).trim(), true)
        }
        val overwriteIdx = cmd.indexOf('>')
        if (overwriteIdx > 0) {
            return Triple(cmd.substring(0, overwriteIdx).trim(), cmd.substring(overwriteIdx + 1).trim(), false)
        }
        return Triple(cmd, null, false)
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (ch in input) {
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    companion object {
        private val HELP_TEXT = """
            PocketForge Sandboxed Workspace Shell
            Commands (strictly isolated to current workspace):
              ls [-la] [path]         List directory entries
              tree [path] [depth]     Display project directory tree
              cd <dir> / pwd          Navigate inside workspace sandbox
              grep [-i] <pat> [path]  Search regex matches without opening whole files
              find [glob]             Find files matching glob (e.g., *.kt)
              cat / nl <file>         Print file (nl adds line numbers)
              head / tail [-n N] <f>  Inspect top or bottom lines of a file
              wc / stat <file>        Line/word/byte count or file metadata
              mkdir / touch / cp / mv Create, copy, or move files inside workspace
              rm [-r] <path>          Delete a workspace file or folder
              diff <f1> <f2>          Compare two files with unified diff
              jsonlint <file.json>    Validate JSON syntax
              echo <text> [> file]    Print text or write/append (>>) to a file
              env / clear / help      Shell environment and controls
        """.trimIndent()
    }
}
