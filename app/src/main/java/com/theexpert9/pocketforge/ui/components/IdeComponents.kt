package com.theexpert9.pocketforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theexpert9.pocketforge.data.ai.AiResiliencePolicy
import com.theexpert9.pocketforge.data.db.CatalogModelEntity
import com.theexpert9.pocketforge.data.db.ChatMessageEntity
import com.theexpert9.pocketforge.data.db.FileSnapshotEntity
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import com.theexpert9.pocketforge.data.db.ToolStatus
import com.theexpert9.pocketforge.data.db.WorkspaceEntity
import com.theexpert9.pocketforge.data.db.WorkspaceToolName
import com.theexpert9.pocketforge.data.sandbox.WorkspaceSandbox
import com.theexpert9.pocketforge.ui.theme.IdeExtendedColors
import com.theexpert9.pocketforge.ui.theme.JetBrainsMonoFontFamily
import com.theexpert9.pocketforge.ui.theme.LocalIdeColors

object CodeSyntaxHighlighter {
    private val KEYWORDS = setOf(
        "package", "import", "class", "data", "sealed", "interface", "object", "fun",
        "val", "var", "return", "if", "else", "when", "for", "while", "try", "catch",
        "finally", "throw", "private", "public", "internal", "override", "suspend",
        "def", "from", "as", "async", "await", "const", "let", "function", "export",
        "true", "false", "null", "None", "self", "this", "in", "is"
    )

    private val TOKEN_REGEX = Regex(
        """(//[^\n]*|#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)"""
    )

    fun highlight(code: String, colors: IdeExtendedColors, primaryColor: Color): AnnotatedString {
        return buildAnnotatedString {
            var cursor = 0
            for (match in TOKEN_REGEX.findAll(code)) {
                val range = match.range
                if (range.first > cursor) {
                    append(code.substring(cursor, range.first))
                }
                val token = match.value
                val style = when {
                    token.startsWith("//") || token.startsWith("#") -> SpanStyle(color = colors.commentSyntax)
                    token.startsWith("\"") || token.startsWith("'") -> SpanStyle(color = colors.stringSyntax)
                    token.first().isDigit() -> SpanStyle(color = colors.numberSyntax)
                    token in KEYWORDS -> SpanStyle(color = colors.keywordSyntax, fontWeight = FontWeight.SemiBold)
                    token.first().isUpperCase() -> SpanStyle(color = primaryColor)
                    else -> null
                }
                if (style != null) {
                    withStyle(style) { append(token) }
                } else {
                    append(token)
                }
                cursor = range.last + 1
            }
            if (cursor < code.length) {
                append(code.substring(cursor))
            }
        }
    }
}

@Composable
fun DiffPreviewBlock(
    diffText: String,
    modifier: Modifier = Modifier
) {
    val ideColors = LocalIdeColors.current
    val lines = remember(diffText) { diffText.lines() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ideColors.terminalBg)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        lines.forEach { line ->
            val (bg, fg) = when {
                line.startsWith("+ ") || line.startsWith("+++") -> ideColors.diffAddBg to ideColors.diffAddText
                line.startsWith("- ") || line.startsWith("---") -> ideColors.diffRemoveBg to ideColors.diffRemoveText
                line.startsWith("@@") -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
                else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = line.ifEmpty { " " },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                ),
                color = fg,
                modifier = Modifier
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun ToolTraceCard(
    message: ChatMessageEntity,
    snapshot: FileSnapshotEntity?,
    onUndoSnapshot: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ideColors = LocalIdeColors.current
    var expanded by remember(message.id) { mutableStateOf(message.diffPreview != null) }

    val statusColor = when (message.toolStatus) {
        ToolStatus.RUNNING -> ideColors.warningText
        ToolStatus.ERROR -> ideColors.diffRemoveText
        else -> ideColors.diffAddText
    }
    val toolIcon = when (message.toolName) {
        WorkspaceToolName.GREP_SEARCH, WorkspaceToolName.SEARCH_SYMBOLS, WorkspaceToolName.FIND_FILES -> Icons.Default.Search
        WorkspaceToolName.RUN_COMMAND -> Icons.Default.Terminal
        else -> Icons.Default.Build
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, ideColors.hairlineBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (message.toolStatus == ToolStatus.RUNNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = statusColor
                        )
                    } else {
                        Icon(
                            imageVector = toolIcon,
                            contentDescription = "Tool icon",
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message.toolName ?: "tool",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(6.dp))
                        val statusIcon = when (message.toolStatus) {
                            ToolStatus.ERROR -> Icons.Default.Error
                            ToolStatus.SUCCESS -> Icons.Default.CheckCircle
                            else -> null
                        }
                        if (statusIcon != null) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = message.toolStatus,
                                tint = statusColor,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (snapshot != null && !snapshot.reverted) {
                    TextButton(
                        onClick = { onUndoSnapshot(snapshot.id) },
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("undo_snapshot_${snapshot.id.take(6)}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo file edit",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Undo", style = MaterialTheme.typography.labelLarge)
                    }
                } else if (snapshot != null && snapshot.reverted) {
                    Text(
                        text = "REVERTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = ideColors.warningText,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse tool output" else "Expand tool output",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (!message.toolArgsJson.isNullOrBlank() && message.toolArgsJson != "{}") {
                        Text(
                            text = "ARGS: ${message.toolArgsJson}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (!message.diffPreview.isNullOrBlank()) {
                        DiffPreviewBlock(diffText = message.diffPreview)
                    } else if (!message.toolResult.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ideColors.terminalBg)
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                text = message.toolResult.take(1_600),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSwitcherSheet(
    workspaces: List<WorkspaceEntity>,
    activeWorkspaceId: String?,
    onSelectWorkspace: (WorkspaceEntity) -> Unit,
    onCreateWorkspaceClick: () -> Unit,
    onDeleteWorkspace: (WorkspaceEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Isolated Workspaces", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Sandboxed local storage",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Private on-device sandbox per project",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = {
                        onDismiss()
                        onCreateWorkspaceClick()
                    },
                    modifier = Modifier.testTag("new_workspace_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New workspace", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }

            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                items(workspaces, key = { it.id }) { ws ->
                    val isActive = ws.id == activeWorkspaceId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary else ideColors.hairlineBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectWorkspace(ws)
                                onDismiss()
                            }
                            .testTag("workspace_card_${ws.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = "Workspace folder",
                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ws.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${ws.template} · /workspaces/${ws.rootDirName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (workspaces.size > 1) {
                                IconButton(
                                    onClick = { onDeleteWorkspace(ws) },
                                    modifier = Modifier.testTag("delete_workspace_${ws.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete workspace",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelQuickPickerSheet(
    providers: List<ProviderConfigEntity>,
    catalogModels: List<CatalogModelEntity>,
    activeProviderId: String?,
    activeModelId: String?,
    hasKey: (String) -> Boolean,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onOpenConfigure: () -> Unit,
    onDismiss: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Switch AI Provider & Model", style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenConfigure()
                    },
                    modifier = Modifier.testTag("manage_providers_shortcut")
                ) {
                    Text("Configure")
                }
            }
            Spacer(Modifier.height(8.dp))

            val grouped = remember(providers, catalogModels) {
                providers.map { p ->
                    val models = catalogModels.filter { it.providerId == p.id }
                        .ifEmpty {
                            listOf(
                                CatalogModelEntity(
                                    compositeId = "${p.id}:${p.selectedModelId}",
                                    providerId = p.id,
                                    providerName = p.displayName,
                                    providerApiUrl = p.baseUrl,
                                    modelId = p.selectedModelId,
                                    modelName = p.selectedModelId,
                                    contextLimit = 128_000,
                                    supportsTools = true,
                                    supportsReasoning = false,
                                    protocolHint = p.protocol
                                )
                            )
                        }
                    p to models.take(6)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                items(grouped, key = { it.first.id }) { (provider, models) ->
                    val ready = provider.protocol == ProviderProtocol.FIREBASE_GEMINI ||
                        provider.id == "ollama" ||
                        hasKey(provider.apiKeyAlias)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, ideColors.hairlineBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = provider.displayName,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = if (ready) ideColors.diffAddBg else ideColors.warningBg
                                ) {
                                    Text(
                                        text = if (provider.protocol == ProviderProtocol.FIREBASE_GEMINI) "BUILT-IN"
                                        else if (ready) "KEY SAVED"
                                        else "NEEDS KEY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (ready) ideColors.diffAddText else ideColors.warningText,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            models.forEach { m ->
                                val selected = provider.id == activeProviderId && m.modelId == activeModelId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            onSelectModel(provider.id, m.modelId)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = m.modelName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (AiResiliencePolicy.isSubscriptionRequiredModel(m.modelId)) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = ideColors.warningBg
                                                ) {
                                                    Text(
                                                        text = "NEEDS SUBSCRIPTION",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        color = ideColors.warningText,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = m.modelId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected model",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun NewWorkspaceDialog(
    onCreate: (name: String, description: String, template: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val templates = listOf(
        WorkspaceSandbox.TEMPLATE_KOTLIN,
        WorkspaceSandbox.TEMPLATE_PYTHON,
        WorkspaceSandbox.TEMPLATE_WEB,
        WorkspaceSandbox.TEMPLATE_BLANK
    )
    var selectedTemplate by remember { mutableStateOf(templates.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("New Isolated Workspace", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Workspace Name") },
                    placeholder = { Text("e.g., PaymentAPI or ScraperBot") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workspace_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Starter Template", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    templates.forEach { tpl ->
                        FilterChip(
                            selected = selectedTemplate == tpl,
                            onClick = { selectedTemplate = tpl },
                            label = { Text(tpl) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.ifBlank { "Project ${System.currentTimeMillis() % 1000}" },
                        description,
                        selectedTemplate
                    )
                    onDismiss()
                },
                modifier = Modifier.testTag("confirm_create_workspace")
            ) {
                Text("Create Sandbox")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
