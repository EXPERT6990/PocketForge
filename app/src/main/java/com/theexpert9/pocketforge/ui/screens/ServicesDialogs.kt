package com.theexpert9.pocketforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.sandbox.ClipboardPatchProposal
import com.theexpert9.pocketforge.ui.components.DiffPreviewBlock
import com.theexpert9.pocketforge.ui.theme.JetBrainsMonoFontFamily
import com.theexpert9.pocketforge.viewmodel.PocketForgeUiState

const val WEB_SIGN_IN_PRIVACY_NOTE =
    "Your account will be used for the ai credit, but your information is private to you. " +
        "We do not accept data from users and all data will be located in your device"

@Composable
fun WebSignInPrivacyDialog(
    providerTitle: String,
    targetUrl: String,
    onConfirmProceed: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Privacy notice",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Private Browser Sign-In ($providerTitle)",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = WEB_SIGN_IN_PRIVACY_NOTE,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Text(
                    text = "Opens strictly in your web browser (bypasses native app redirects): $targetUrl",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmProceed,
                modifier = Modifier.testTag("confirm_web_signin_redirect")
            ) {
                Text("Open in Browser")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ServiceQuickConnectDialog(
    provider: ProviderConfigEntity,
    existingMaskedKey: String?,
    portalInfo: Pair<String, String>?,
    onOpenPortal: (String, String) -> Unit,
    onSave: (ProviderConfigEntity, String?, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(provider.selectedModelId) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Connect ${provider.displayName}", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Your API key is encrypted locally with AES-256-GCM inside Android Keystore and never leaves your device except to authenticate requests to ${provider.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(
                            if (existingMaskedKey != null) "Replace API Key ($existingMaskedKey)"
                            else "Paste ${provider.displayName} API Key"
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val pasted = readClipboardText(context)?.trim()
                                if (!pasted.isNullOrBlank()) apiKey = pasted
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste API key from clipboard")
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("service_connect_api_key_input")
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Default Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (portalInfo != null) {
                    TextButton(
                        onClick = { onOpenPortal(portalInfo.first, portalInfo.second) }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Get API Key in Browser (${portalInfo.first})")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = provider.copy(
                        selectedModelId = modelId.trim().ifBlank { provider.selectedModelId },
                        baseUrl = baseUrl.trim().ifBlank { provider.baseUrl }
                    )
                    onSave(updated, apiKey.trim().takeIf { it.isNotBlank() }, true)
                },
                modifier = Modifier.testTag("service_connect_save_button")
            ) {
                Text("Save & Activate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyWorkspaceContextSheet(
    uiState: PocketForgeUiState,
    onCopyTree: () -> Unit,
    onCopyGrep: (String) -> Unit,
    onCopyFileRange: (String, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val files = remember(uiState.workspaceFiles) {
        uiState.workspaceFiles.filter { !it.isDirectory }.map { it.relativePath }
    }
    var mode by remember { mutableIntStateOf(0) }
    var grepPattern by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf(files.firstOrNull().orEmpty()) }
    var startLine by remember { mutableStateOf("1") }
    var endLine by remember { mutableStateOf("100") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Copy Workspace Context for Browser AI", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Includes PocketForge patch-format instructions so browser replies apply cleanly in one tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Project Tree") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Grep Matches") })
                FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("File Lines") })
            }

            when (mode) {
                0 -> {
                    Button(
                        onClick = onCopyTree,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Copy Workspace Tree to Clipboard")
                    }
                }
                1 -> {
                    OutlinedTextField(
                        value = grepPattern,
                        onValueChange = { grepPattern = it },
                        label = { Text("Regex / Keyword to Grep") },
                        placeholder = { Text("e.g., isExpired") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { if (grepPattern.isNotBlank()) onCopyGrep(grepPattern.trim()) },
                        enabled = grepPattern.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Copy Matching Lines to Clipboard")
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        files.forEach { f ->
                            FilterChip(
                                selected = selectedFile == f,
                                onClick = { selectedFile = f },
                                label = { Text(f, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startLine,
                            onValueChange = { startLine = it.filter(Char::isDigit) },
                            label = { Text("Start Line") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endLine,
                            onValueChange = { endLine = it.filter(Char::isDigit) },
                            label = { Text("End Line") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            if (selectedFile.isNotBlank()) {
                                val s = startLine.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val e = endLine.toIntOrNull()?.coerceAtLeast(s) ?: (s + 99)
                                onCopyFileRange(selectedFile, s, e)
                            }
                        },
                        enabled = selectedFile.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Copy Line Range to Clipboard")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ApplyClipboardPatchDialog(
    proposals: List<ClipboardPatchProposal>,
    workspaceFiles: List<String>,
    onPreview: suspend (ClipboardPatchProposal, String) -> Result<String>,
    onConfirmApply: (ClipboardPatchProposal, String) -> Unit,
    onDismiss: () -> Unit
) {
    val active = proposals.first()
    var targetPath by remember(active) {
        mutableStateOf(active.relativePath ?: workspaceFiles.firstOrNull().orEmpty())
    }
    var diffText by remember(active) { mutableStateOf<String?>(null) }
    var previewError by remember(active) { mutableStateOf<String?>(null) }

    LaunchedEffect(active, targetPath) {
        if (targetPath.isBlank()) {
            diffText = null
            previewError = "Specify a relative file path inside the workspace."
        } else {
            val res = onPreview(active, targetPath)
            diffText = res.getOrNull()
            previewError = res.exceptionOrNull()?.localizedMessage
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Apply Patch (${proposals.size} queued)",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = active.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = targetPath,
                    onValueChange = { targetPath = it },
                    label = { Text("Target Workspace File") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (workspaceFiles.isNotEmpty() && active.relativePath == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        workspaceFiles.take(8).forEach { f ->
                            FilterChip(
                                selected = targetPath == f,
                                onClick = { targetPath = f },
                                label = { Text(f, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                if (previewError != null) {
                    Text(
                        text = previewError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (diffText != null) {
                    DiffPreviewBlock(diffText = diffText.orEmpty())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmApply(active, targetPath) },
                enabled = previewError == null && targetPath.isNotBlank(),
                modifier = Modifier.testTag("confirm_apply_clipboard_patch")
            ) {
                Text("Apply & Snapshot")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
