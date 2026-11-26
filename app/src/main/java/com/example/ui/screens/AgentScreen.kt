package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ai.AgentRunProgress
import com.example.data.db.ChatMessageEntity
import com.example.data.db.ChatSessionEntity
import com.example.data.db.FileSnapshotEntity
import com.example.data.db.MessageRole
import com.example.ui.components.CodeSyntaxHighlighter
import com.example.ui.components.ToolTraceCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors
import com.example.viewmodel.PocketForgeUiState

@Composable
fun AgentScreen(
    uiState: PocketForgeUiState,
    onDraftChange: (String) -> Unit,
    onSendPrompt: (String?) -> Unit,
    onStopAgent: () -> Unit,
    onSelectSession: (ChatSessionEntity) -> Unit,
    onCreateSession: () -> Unit,
    onClearSession: () -> Unit,
    onUndoSnapshot: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ideColors = LocalIdeColors.current
    val listState = rememberLazyListState()
    val snapshotMap = remember(uiState.snapshots) { uiState.snapshots.associateBy { it.id } }

    LaunchedEffect(uiState.messages.size, uiState.agentProgress?.streamingText) {
        val target = uiState.messages.size + if (uiState.isAgentRunning) 1 else 0
        if (target > 0) {
            listState.animateScrollToItem(target - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Persistent Session Bar
        SessionStripBar(
            sessions = uiState.sessions,
            activeSessionId = uiState.activeSessionId,
            activeProfileName = uiState.activePromptProfile?.name ?: "Autonomous",
            onSelectSession = onSelectSession,
            onCreateSession = onCreateSession,
            onClearSession = onClearSession
        )

        // Live autonomous progress bar
        if (uiState.isAgentRunning) {
            val progress = uiState.agentProgress
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusLabel = when {
                        progress?.activeToolName != null -> "Executing ${progress.activeToolName}…"
                        else -> "Autonomous Step ${progress?.stepIndex ?: 1}/${progress?.maxSteps ?: 8}"
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = uiState.activeSession?.modelId ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
        }

        // Chat Timeline or Hero Empty State
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (uiState.messages.isEmpty() && !uiState.isAgentRunning) {
                AgentHeroEmptyState(
                    workspaceName = uiState.activeWorkspace?.name ?: "Workspace",
                    fileCount = uiState.workspaceFiles.count { !it.isDirectory },
                    onQuickPrompt = { prompt -> onSendPrompt(prompt) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.messages, key = { it.id }) { msg ->
                        when (msg.role) {
                            MessageRole.USER -> UserMessageBubble(msg)
                            MessageRole.TOOL -> ToolTraceCard(
                                message = msg,
                                snapshot = msg.snapshotId?.let { snapshotMap[it] },
                                onUndoSnapshot = onUndoSnapshot
                            )
                            else -> AssistantMessageCard(msg.content)
                        }
                    }

                    if (uiState.isAgentRunning) {
                        val streaming = uiState.agentProgress?.streamingText.orEmpty()
                        if (streaming.isNotBlank()) {
                            item(key = "streaming_preview") {
                                AssistantMessageCard(
                                    content = streaming,
                                    isStreaming = true
                                )
                            }
                        }
                    }
                }
            }
        }

        // Prompt Composer Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, ideColors.hairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = uiState.draftPrompt,
                    onValueChange = onDraftChange,
                    placeholder = {
                        Text(
                            "Ask AI to grep, inspect ranges, patch files, or run commands…",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = ideColors.hairlineBorder,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agent_prompt_input")
                )
                Spacer(Modifier.width(8.dp))
                if (uiState.isAgentRunning) {
                    FilledIconButton(
                        onClick = onStopAgent,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("stop_agent_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop agent")
                    }
                } else {
                    FilledIconButton(
                        onClick = { onSendPrompt(null) },
                        enabled = uiState.draftPrompt.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_prompt_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStripBar(
    sessions: List<ChatSessionEntity>,
    activeSessionId: String?,
    activeProfileName: String,
    onSelectSession: (ChatSessionEntity) -> Unit,
    onCreateSession: () -> Unit,
    onClearSession: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sessions.forEach { session ->
                val active = session.id == activeSessionId
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else ideColors.hairlineBorder
                    ),
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        .clickable { onSelectSession(session) }
                        .testTag("session_tab_${session.id.take(6)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .clickable(onClick = onCreateSession)
                    .testTag("new_session_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New persistent session",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("Session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = activeProfileName.take(14),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        IconButton(
            onClick = onClearSession,
            modifier = Modifier
                .size(36.dp)
                .testTag("clear_session_button")
        ) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Clear session messages",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AgentHeroEmptyState(
    workspaceName: String,
    fileCount: Int,
    onQuickPrompt: (String) -> Unit
) {
    val ideColors = LocalIdeColors.current
    val starterPrompts = listOf(
        "Map the workspace with list_tree and find any bugs using grep_search + read_file_range, then patch them.",
        "Find where token expiry is checked and fix the logic bug without reading unrelated files.",
        "Search all symbols in this workspace and write a concise architecture summary.",
        "Run jsonlint on config/workspace.json and inspect its settings."
    )

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ideColors.hairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(172.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.img_workspace_hero_1795689583668),
                        contentDescription = "PocketForge workspace illustration",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Autonomous agent",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "AUTONOMOUS SANDBOX READY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = workspaceName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "$fileCount isolated files · Targeted grep, line-range reads, surgical patches & undo snapshots",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "QUICK WORKSPACE TASKS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(starterPrompts) { prompt ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ideColors.hairlineBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQuickPrompt(prompt) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ">_",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(msg: ChatMessageEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun AssistantMessageCard(
    content: String,
    isStreaming: Boolean = false
) {
    val ideColors = LocalIdeColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val segments = remember(content) { splitCodeBlocks(content) }

    Surface(
        shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (isStreaming) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else ideColors.hairlineBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isStreaming) "POCKETFORGE · STREAMING" else "POCKETFORGE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            segments.forEach { seg ->
                if (seg.isCode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ideColors.terminalBg)
                            .padding(10.dp)
                    ) {
                        if (seg.language.isNotBlank()) {
                            Text(
                                text = seg.language.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                text = CodeSyntaxHighlighter.highlight(seg.text, ideColors, primaryColor),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                } else if (seg.text.isNotBlank()) {
                    Text(
                        text = seg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private data class MarkdownSegment(
    val text: String,
    val isCode: Boolean,
    val language: String = ""
)

private fun splitCodeBlocks(raw: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val regex = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
    var cursor = 0
    for (m in regex.findAll(raw)) {
        if (m.range.first > cursor) {
            result += MarkdownSegment(raw.substring(cursor, m.range.first).trim(), isCode = false)
        }
        result += MarkdownSegment(
            text = m.groupValues[2].trimEnd(),
            isCode = true,
            language = m.groupValues[1]
        )
        cursor = m.range.last + 1
    }
    if (cursor < raw.length) {
        result += MarkdownSegment(raw.substring(cursor).trim(), isCode = false)
    }
    return result.ifEmpty { listOf(MarkdownSegment(raw, isCode = false)) }
}
