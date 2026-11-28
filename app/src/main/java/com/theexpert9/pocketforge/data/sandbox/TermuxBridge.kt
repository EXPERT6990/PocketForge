package com.theexpert9.pocketforge.data.sandbox

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class TermuxEnvironmentStatus(
    val isInstalled: Boolean = false,
    val hasRunCommandPermission: Boolean = false,
    val isPermissionDeclaredByTermux: Boolean = false,
    val isEnabled: Boolean = false,
    val versionName: String? = null
) {
    val isReady: Boolean get() = isInstalled && hasRunCommandPermission && isEnabled
}

/**
 * Optional Termux bridge using Termux's official RUN_COMMAND service API.
 * Basic workspace commands stay inside PocketForge's fast isolated shell, while
 * compiler/build commands (python3, javac, java, g++, clang, node, npm, git, gradle, etc.)
 * delegate to Termux when installed and permitted.
 */
class TermuxBridge(
    private val context: Context,
    private val sandbox: WorkspaceSandbox
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val requestCodeCounter = AtomicInteger(4000)

    fun isTermuxInstalled(): Boolean = getTermuxVersionName() != null

    fun getTermuxVersionName(): String? {
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            info.versionName ?: "installed"
        }.getOrNull()
    }

    fun isRunCommandPermissionDeclared(): Boolean {
        return runCatching {
            context.packageManager.getPermissionInfo(PERMISSION_RUN_COMMAND, 0)
            true
        }.getOrDefault(false)
    }

    fun hasRunCommandPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isEnabled(): Boolean {
        return if (prefs.contains(KEY_ENABLED)) {
            prefs.getBoolean(KEY_ENABLED, true)
        } else {
            isTermuxInstalled()
        }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getStatus(): TermuxEnvironmentStatus {
        val version = getTermuxVersionName()
        val installed = version != null
        val declared = if (installed) isRunCommandPermissionDeclared() else false
        val granted = hasRunCommandPermission()
        val enabled = isEnabled()
        Log.d(
            TAG,
            "Termux status: installed=$installed (ver=$version), permDeclared=$declared, granted=$granted, enabled=$enabled"
        )
        return TermuxEnvironmentStatus(
            isInstalled = installed,
            hasRunCommandPermission = granted,
            isPermissionDeclaredByTermux = declared,
            isEnabled = enabled,
            versionName = version
        )
    }

    /**
     * Returns true when the command should be routed to Termux instead of the built-in shell.
     * Basic POSIX inspection/file commands stay in PocketForge's sandbox for instant latency.
     */
    fun shouldDelegateToTermux(rawCommand: String): Boolean {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("termux ", ignoreCase = true)) return true

        val firstToken = trimmed
            .substringBefore("&&")
            .substringBefore("|")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.lowercase()
            .orEmpty()

        if (firstToken in SANDBOX_BUILTIN_COMMANDS) {
            // If chained with a compiler command (e.g. "javac Main.java && java Main"), delegate whole line.
            if (trimmed.contains("&&") || trimmed.contains("|")) {
                val segments = trimmed.split(Regex("&&|\\|"))
                    .map { it.trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty() }
                return segments.any { it.isNotEmpty() && it !in SANDBOX_BUILTIN_COMMANDS }
            }
            return false
        }
        return firstToken.isNotEmpty()
    }

    suspend fun executeCommand(
        rootDirName: String,
        currentWorkingDir: String,
        rawCommand: String,
        timeoutMs: Long = 45_000L
    ): TerminalCommandResult = withContext(Dispatchers.IO) {
        val cleanCmd = rawCommand.trim().removePrefix("termux ").removePrefix("TERMUX ").trim()
        if (cleanCmd.isEmpty()) {
            return@withContext TerminalCommandResult("", 0, currentWorkingDir)
        }

        val status = getStatus()
        if (!status.isInstalled) {
            return@withContext TerminalCommandResult(
                output = "Termux is not installed. Install Termux (F-Droid / GitHub) to run '$cleanCmd', or use built-in workspace commands (type 'help').",
                exitCode = 127,
                newWorkingDir = currentWorkingDir
            )
        }
        if (!status.hasRunCommandPermission) {
            return@withContext TerminalCommandResult(
                output = "Termux RUN_COMMAND permission is not granted. Open Configure > System & Logcat to grant Termux access.",
                exitCode = 126,
                newWorkingDir = currentWorkingDir
            )
        }

        val syncPrefix = buildWorkspaceSyncScript(rootDirName, currentWorkingDir)
        val fullScript = buildString {
            append("export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"\n")
            append(syncPrefix)
            append("\n")
            append(cleanCmd)
        }

        val action = "${context.packageName}.TERMUX_RESULT_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<TerminalCommandResult>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val resultBundle = intent?.getBundleExtra("result")
                if (resultBundle == null) {
                    deferred.complete(
                        TerminalCommandResult(
                            output = "Termux returned an empty result bundle.",
                            exitCode = 1,
                            newWorkingDir = currentWorkingDir
                        )
                    )
                    return
                }
                val stdout = resultBundle.getString("stdout").orEmpty()
                val stderr = resultBundle.getString("stderr").orEmpty()
                val exitCode = resultBundle.getInt("exitCode", 0)
                val errCode = resultBundle.getInt("err", 0)
                val errmsg = resultBundle.getString("errmsg").orEmpty()

                val combined = buildString {
                    if (stdout.isNotBlank()) append(stdout.trimEnd())
                    if (stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(stderr.trimEnd())
                    }
                    if (errCode != 0 && errmsg.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("[Termux Error $errCode] $errmsg")
                    }
                }.ifBlank {
                    if (exitCode == 0) "(Termux command completed with no output)" else "(Termux exited with code $exitCode)"
                }

                deferred.complete(
                    TerminalCommandResult(
                        output = "[termux] $combined",
                        exitCode = if (errCode != 0 && exitCode == 0) errCode else exitCode,
                        newWorkingDir = currentWorkingDir
                    )
                )
            }
        }

        val filter = IntentFilter(action)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        try {
            val resultIntent = Intent(action).setPackage(context.packageName)
            val pendingFlags = PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCodeCounter.incrementAndGet(),
                resultIntent,
                pendingFlags
            )

            val serviceIntent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", fullScript))
                putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            Log.d(TAG, "Dispatching Termux RUN_COMMAND: $cleanCmd")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (sec: SecurityException) {
                Log.e(TAG, "Termux RUN_COMMAND SecurityException", sec)
                return@withContext TerminalCommandResult(
                    output = "Termux blocked external command execution (${sec.localizedMessage}).\n" +
                        "Fix: 1) Grant Termux permission in Configure > System & Logcat.\n" +
                        "2) In Termux run: echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings",
                    exitCode = 126,
                    newWorkingDir = currentWorkingDir
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Termux RunCommandService", e)
                return@withContext TerminalCommandResult(
                    output = "Could not reach Termux service (${e.localizedMessage}). Open Termux once to wake it and ensure allow-external-apps=true is set in ~/.termux/termux.properties.",
                    exitCode = 1,
                    newWorkingDir = currentWorkingDir
                )
            }

            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: TerminalCommandResult(
                    output = "Termux command timed out after ${timeoutMs / 1000}s.\n" +
                        "Verify `~/.termux/termux.properties` contains `allow-external-apps=true` (then run `termux-reload-settings` in Termux).",
                    exitCode = 124,
                    newWorkingDir = currentWorkingDir
                )
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Stages the active workspace's source files into Termux's `~/.pocketforge_ws/<rootDirName>`
     * so Termux compilers (python3, javac, g++, node, gradle) can build and run project files
     * without requiring broad Android storage permissions.
     */
    private fun buildWorkspaceSyncScript(rootDirName: String, currentWorkingDir: String): String {
        val safeRootSlug = rootDirName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeCwd = currentWorkingDir.trim('/').replace("'", "")
        val targetBase = "\$HOME/.pocketforge_ws/$safeRootSlug"

        val rootDir = sandbox.getWorkspaceRoot(rootDirName)
        val sb = StringBuilder()
        sb.append("mkdir -p \"$targetBase\"\n")

        var totalBytes = 0
        val files = rootDir.walkTopDown()
            .filter { it.isFile && it.length() <= MAX_SINGLE_FILE_BYTES }
            .sortedBy { it.path }
            .toList()

        for (file in files) {
            if (totalBytes >= MAX_SYNC_PAYLOAD_BYTES) break
            val rel = file.relativeTo(rootDir).path.replace('\\', '/')
            if (rel.contains('\'') || rel.contains("..")) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            totalBytes += bytes.size
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val parentRel = File(rel).parent?.replace('\\', '/')
            if (!parentRel.isNullOrBlank()) {
                sb.append("mkdir -p \"$targetBase/$parentRel\"\n")
            }
            sb.append("printf '%s' '$b64' | base64 -d > \"$targetBase/$rel\"\n")
        }

        if (safeCwd.isNotBlank()) {
            sb.append("mkdir -p \"$targetBase/$safeCwd\" && cd \"$targetBase/$safeCwd\"")
        } else {
            sb.append("cd \"$targetBase\"")
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "TermuxBridge"
        private const val PREFS_NAME = "pocketforge_termux_prefs"
        private const val KEY_ENABLED = "termux_bridge_enabled"

        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val MAX_SINGLE_FILE_BYTES = 128 * 1024
        private const val MAX_SYNC_PAYLOAD_BYTES = 512 * 1024

        val SANDBOX_BUILTIN_COMMANDS = setOf(
            "clear", "help", "pwd", "cd", "ls", "tree", "cat", "nl",
            "head", "tail", "grep", "rg", "find", "wc", "stat",
            "mkdir", "touch", "cp", "mv", "rm", "diff", "echo", "jsonlint", "env"
        )

        /**
         * Opens Android App Info for PocketForge so the user can toggle
         * Permissions -> Additional permissions -> Run commands in Termux environment.
         */
        fun openAppPermissionSettings(context: Context): Boolean {
            return runCatching {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
                true
            }.onFailure { err ->
                Log.e(TAG, "Failed to open Android App Settings for Termux permission", err)
            }.getOrDefault(false)
        }
    }
}
