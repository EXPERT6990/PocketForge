package com.theexpert9.pocketforge.util

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogcatEntry(
    val id: Int,
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String
)

/**
 * Reads Android logcat entries for PocketForge's own PID without requiring READ_LOGS permission.
 */
object LogcatReader {

    private val TIME_LINE_REGEX = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEFA])/([^\s(]+)\s*\(\s*\d+\):\s*(.*)$"""
    )

    suspend fun readAppLogs(maxLines: Int = 350): List<LogcatEntry> = withContext(Dispatchers.IO) {
        val pid = Process.myPid().toString()
        val rawLines = runCatching {
            val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            text.lines()
        }.getOrElse { err ->
            return@withContext listOf(
                LogcatEntry(
                    id = 0,
                    timestamp = "",
                    level = "E",
                    tag = "LogcatReader",
                    message = "Unable to read logcat: ${err.localizedMessage ?: err.javaClass.simpleName}",
                    raw = "E/LogcatReader: ${err.localizedMessage}"
                )
            )
        }

        val filtered = rawLines
            .filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }
            .takeLast(maxLines)

        filtered.mapIndexed { idx, line -> parseLine(idx, line) }
    }

    suspend fun clearAppLogs(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder("logcat", "-c").start()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    internal fun parseLine(id: Int, line: String): LogcatEntry {
        val match = TIME_LINE_REGEX.matchEntire(line)
        return if (match != null) {
            LogcatEntry(
                id = id,
                timestamp = match.groupValues[1],
                level = match.groupValues[2],
                tag = match.groupValues[3].trim(),
                message = match.groupValues[4],
                raw = line
            )
        } else {
            LogcatEntry(
                id = id,
                timestamp = "",
                level = "I",
                tag = "System",
                message = line,
                raw = line
            )
        }
    }
}
