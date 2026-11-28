package com.theexpert9.pocketforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theexpert9.pocketforge.BuildConfig
import com.theexpert9.pocketforge.data.sandbox.TermuxBridge
import com.theexpert9.pocketforge.getFirebaseDiagnosticStatus
import com.theexpert9.pocketforge.ui.theme.JetBrainsMonoFontFamily
import com.theexpert9.pocketforge.ui.theme.LocalIdeColors
import com.theexpert9.pocketforge.viewmodel.PocketForgeUiState

@Composable
fun ConfigureSystemPane(
    uiState: PocketForgeUiState,
    onRefreshTermuxStatus: () -> Unit,
    onSetTermuxEnabled: (Boolean) -> Unit,
    onTestTermux: () -> Unit,
    onRefreshLogcat: () -> Unit,
    onClearLogcat: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val ideColors = LocalIdeColors.current
    val firebaseStatus = remember(context) { getFirebaseDiagnosticStatus(context) }

    var selectedLevel by remember { mutableStateOf("ALL") }
    var selectedTag by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    val termuxPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onRefreshTermuxStatus()
        onNotify(
            if (granted) "Termux RUN_COMMAND permission granted."
            else "Termux RUN_COMMAND permission denied. You can enable it in Android App Settings."
        )
    }

    LaunchedEffect(Unit) {
        onRefreshTermuxStatus()
        onRefreshLogcat()
    }

    val filteredLogs = remember(uiState.logcatEntries, selectedLevel, selectedTag, searchQuery) {
        val q = searchQuery.trim()
        uiState.logcatEntries.filter { entry ->
            val levelOk = when (selectedLevel) {
                "ALL" -> true
                "E" -> entry.level == "E" || entry.level == "F" || entry.level == "A"
                "W" -> entry.level == "W" || entry.level == "E" || entry.level == "F"
                "D" -> entry.level == "D" || entry.level == "I"
                else -> true
            }
            val tagOk = selectedTag == "All" || entry.tag.contains(selectedTag, ignoreCase = true)
            val queryOk = q.isEmpty() || entry.raw.contains(q, ignoreCase = true)
            levelOk && tagOk && queryOk
        }.asReversed()
    }

    val termux = uiState.termuxStatus

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. App Info & Build Metadata Card
        item(key = "app_info_card") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ideColors.hairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "PocketForge",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = "App ID: ${BuildConfig.APPLICATION_ID} · Build ${if (BuildConfig.DEBUG) "Debug" else "Release"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Firebase: $firebaseStatus",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = ideColors.diffAddText
                    )
                }
            }
        }

        // 2. Termux Toolchain Compatibility Card
        item(key = "termux_compat_card") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    1.dp,
                    if (termux.isReady) ideColors.diffAddText.copy(alpha = 0.5f) else ideColors.hairlineBorder
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = if (termux.isReady) ideColors.diffAddText else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Termux Toolchain Bridge",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (termux.isInstalled) {
                                        "Detected com.termux (${termux.versionName ?: "installed"})"
                                    } else {
                                        "Termux not detected — using built-in sandbox shell"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val (badgeText, badgeBg, badgeFg) = when {
                            termux.isReady -> Triple("ACTIVE", ideColors.diffAddBg, ideColors.diffAddText)
                            termux.isInstalled && !termux.hasRunCommandPermission -> Triple(
                                "NEEDS PERM",
                                ideColors.warningBg,
                                ideColors.warningText
                            )
                            termux.isInstalled -> Triple(
                                "OFF",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> Triple(
                                "UNAVAILABLE",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(shape = CircleShape, color = badgeBg) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = "Basic commands (ls, cat, grep, tree, diff) stay instant in PocketForge's isolated sandbox. Compilers & build tools (python3, java, javac, g++, clang, node, git, gradle) automatically run via Termux when enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (termux.isInstalled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Delegate build/compiler commands to Termux",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = termux.isEnabled,
                                onCheckedChange = onSetTermuxEnabled,
                                modifier = Modifier.testTag("termux_enable_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!termux.hasRunCommandPermission) {
                                Button(
                                    onClick = {
                                        termuxPermissionLauncher.launch(TermuxBridge.PERMISSION_RUN_COMMAND)
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .testTag("grant_termux_permission_button")
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Grant Permission")
                                }
                            }
                            OutlinedButton(
                                onClick = onTestTermux,
                                enabled = !uiState.isTestingTermux && termux.hasRunCommandPermission,
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .testTag("test_termux_bridge_button")
                            ) {
                                if (uiState.isTestingTermux) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(5.dp))
                                Text("Test Compilers")
                            }
                        }

                        Text(
                            text = "One-time Termux requirement: echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = ideColors.warningText
                        )

                        if (!uiState.termuxTestOutput.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ideColors.terminalBg)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = uiState.termuxTestOutput,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        lineHeight = 16.sp
                                    ),
                                    color = ideColors.diffAddText
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. In-App Logcat Viewer Card
        item(key = "logcat_header_card") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ideColors.hairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "App Logcat Viewer",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${filteredLogs.size} lines shown (${uiState.logcatEntries.size} captured)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onRefreshLogcat,
                                modifier = Modifier.testTag("logcat_refresh_button")
                            ) {
                                if (uiState.isLoadingLogs) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh logcat")
                                }
                            }
                            IconButton(
                                onClick = {
                                    val dump = filteredLogs.asReversed().joinToString("\n") { it.raw }
                                    if (dump.isBlank()) {
                                        onNotify("No logcat lines to copy.")
                                    } else {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("PocketForge Logcat", dump))
                                        onNotify("Copied ${filteredLogs.size} logcat lines to clipboard.")
                                    }
                                },
                                modifier = Modifier.testTag("logcat_copy_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy filtered logs")
                            }
                            IconButton(
                                onClick = onClearLogcat,
                                modifier = Modifier.testTag("logcat_clear_button")
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logcat buffer")
                            }
                        }
                    }

                    // Level Filter Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ALL" to "All", "E" to "Errors", "W" to "Warn+", "D" to "Debug/Info").forEach { (key, label) ->
                            FilterChip(
                                selected = selectedLevel == key,
                                onClick = { selectedLevel = key },
                                label = { Text(label) }
                            )
                        }
                    }

                    // Tag Quick Filter Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Auth", "AppCheckInit", "GeminiFirebaseClient", "TermuxBridge", "AutonomousOrchestrator").forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { selectedTag = tag },
                                label = {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                    )
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter logcat text or tag…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("logcat_search_input")
                    )
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            item(key = "logcat_empty") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ideColors.terminalBg,
                    border = BorderStroke(1.dp, ideColors.hairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (uiState.isLoadingLogs) "Loading process logcat…" else "No logcat entries match the current filter. Tap Refresh after reproducing an action.",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(filteredLogs.take(150), key = { "log_${it.id}_${it.timestamp}" }) { entry ->
                val levelColor = when (entry.level) {
                    "E", "F", "A" -> ideColors.diffRemoveText
                    "W" -> ideColors.warningText
                    "D" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ideColors.terminalBg,
                    border = BorderStroke(1.dp, ideColors.hairlineBorder.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${entry.level}/${entry.tag}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = levelColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (entry.timestamp.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = entry.timestamp.substringAfter(' '),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
