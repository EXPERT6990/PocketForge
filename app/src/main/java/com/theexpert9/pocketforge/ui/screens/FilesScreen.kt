package com.theexpert9.pocketforge.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theexpert9.pocketforge.data.sandbox.GrepMatch
import com.theexpert9.pocketforge.data.sandbox.WorkspaceFileNode
import com.theexpert9.pocketforge.ui.components.CodeSyntaxHighlighter
import com.theexpert9.pocketforge.ui.theme.JetBrainsMonoFontFamily
import com.theexpert9.pocketforge.ui.theme.LocalIdeColors
import com.theexpert9.pocketforge.viewmodel.OpenFileEditorState
import com.theexpert9.pocketforge.viewmodel.PocketForgeUiState

@Composable
fun FilesScreen(
    uiState: PocketForgeUiState,
    onOpenFile: (String) -> Unit,
    onUpdateEditor: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onCloseEditor: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onGrepQueryChange: (String) -> Unit,
    onAskAiAboutFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val openEditor = uiState.openEditor
    if (openEditor != null) {
        BackHandler(onBack = onCloseEditor)
        CodeEditorView(
            editor = openEditor,
            onContentChange = onUpdateEditor,
            onSave = onSaveEditor,
            onClose = onCloseEditor,
            onAskAi = { onAskAiAboutFile(openEditor.relativePath) },
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val ideColors = LocalIdeColors.current
    var showGrepMode by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    // Zero-permission Android SAF file importer into isolated workspace
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val displayName = queryFileName(context, uri) ?: "imported_${System.currentTimeMillis() % 1000}.txt"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                onCreateFile(displayName, content)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Isolated Sandbox Banner + Controls
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, ideColors.hairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Isolated storage",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "sandbox://workspaces/${uiState.activeWorkspace?.rootDirName ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { showNewFileDialog = true },
                            modifier = Modifier.testTag("new_file_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New file", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(
                            onClick = { showNewFolderDialog = true },
                            modifier = Modifier.testTag("new_folder_button")
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", tint = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(
                            onClick = { importLauncher.launch(arrayOf("text/*", "application/json", "*/*")) },
                            modifier = Modifier.testTag("import_file_button")
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Import file via SAF", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showGrepMode,
                        onClick = { showGrepMode = false },
                        label = { Text("File Tree (${uiState.workspaceFiles.count { !it.isDirectory }})") },
                        modifier = Modifier.testTag("files_tree_mode_chip")
                    )
                    FilterChip(
                        selected = showGrepMode,
                        onClick = { showGrepMode = true },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Grep Search") },
                        modifier = Modifier.testTag("files_grep_mode_chip")
                    )
                }
            }
        }

        if (showGrepMode) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                OutlinedTextField(
                    value = uiState.grepQuery,
                    onValueChange = onGrepQueryChange,
                    label = { Text("Regex / Text Pattern") },
                    placeholder = { Text("e.g., isExpired|AuthToken") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workspace_grep_input")
                )
                Spacer(Modifier.height(10.dp))
                if (uiState.grepQuery.isBlank()) {
                    Text(
                        text = "Search across your isolated workspace without opening full files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (uiState.grepResults.isEmpty()) {
                    Text(
                        text = "No matches for '${uiState.grepQuery}'.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.grepResults) { hit ->
                            GrepHitCard(hit = hit, onClick = { onOpenFile(hit.relativePath) })
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.workspaceFiles, key = { it.relativePath }) { node ->
                    FileNodeRow(
                        node = node,
                        onOpen = { if (!node.isDirectory) onOpenFile(node.relativePath) },
                        onAskAi = { onAskAiAboutFile(node.relativePath) },
                        onDelete = { onDeleteFile(node.relativePath) }
                    )
                }
            }
        }
    }

    if (showNewFileDialog) {
        CreatePathDialog(
            title = "New Workspace File",
            label = "Relative Path (e.g., src/Utils.kt)",
            onConfirm = { path -> onCreateFile(path, "") },
            onDismiss = { showNewFileDialog = false }
        )
    }

    if (showNewFolderDialog) {
        CreatePathDialog(
            title = "New Folder",
            label = "Folder Path (e.g., src/models)",
            onConfirm = onCreateFolder,
            onDismiss = { showNewFolderDialog = false }
        )
    }
}

@Composable
private fun FileNodeRow(
    node: WorkspaceFileNode,
    onOpen: () -> Unit,
    onAskAi: () -> Unit,
    onDelete: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, ideColors.hairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 14).dp)
            .clickable(enabled = !node.isDirectory, onClick = onOpen)
            .testTag("file_node_${node.relativePath.replace('/', '_')}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = if (node.isDirectory) "Directory" else "File",
                tint = if (node.isDirectory) ideColors.warningText else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = if (node.isDirectory) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!node.isDirectory) {
                    Text(
                        text = "${node.lineCount} lines · ${node.sizeBytes} B",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!node.isDirectory) {
                IconButton(onClick = onAskAi) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Ask AI about ${node.name}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete ${node.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun GrepHitCard(hit: GrepMatch, onClick: () -> Unit) {
    val ideColors = LocalIdeColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, ideColors.hairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "${hit.relativePath}:${hit.lineNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = hit.lineText.trim(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CodeEditorView(
    editor: OpenFileEditorState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onAskAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ideColors = LocalIdeColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    var isEditing by remember { mutableStateOf(false) }
    val lines = remember(editor.content) { editor.content.lines() }

    Column(modifier = modifier.fillMaxSize()) {
        // Editor Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, ideColors.hairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("close_editor_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to files")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = editor.relativePath + if (editor.isDirty) " •" else "",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${lines.size} lines · ${if (isEditing) "Edit Mode" else "Syntax View"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { isEditing = !isEditing },
                    modifier = Modifier.testTag("toggle_editor_mode_button")
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Syntax preview" else "Edit code",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = onAskAi,
                    modifier = Modifier.testTag("ask_ai_editor_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Ask AI about file", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = onSave,
                    enabled = editor.isDirty,
                    modifier = Modifier.testTag("save_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save file",
                        tint = if (editor.isDirty) ideColors.diffAddText else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Code Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ideColors.terminalBg)
        ) {
            if (isEditing) {
                BasicTextField(
                    value = editor.content,
                    onValueChange = onContentChange,
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp)
                        .testTag("code_editor_textfield")
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp)
                ) {
                    // Gutter Line Numbers
                    Column(
                        modifier = Modifier
                            .padding(start = 10.dp, end = 10.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        lines.indices.forEach { idx ->
                            Text(
                                text = "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 20.sp
                                ),
                                color = ideColors.commentSyntax
                            )
                        }
                    }
                    // Highlighted Code Lines
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 16.dp)
                    ) {
                        val highlighted = remember(editor.content) {
                            CodeSyntaxHighlighter.highlight(editor.content, ideColors, primaryColor)
                        }
                        Text(
                            text = highlighted,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.5.sp,
                                lineHeight = 20.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatePathDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (path.isNotBlank()) onConfirm(path.trim())
                    onDismiss()
                }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun queryFileName(context: android.content.Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}
