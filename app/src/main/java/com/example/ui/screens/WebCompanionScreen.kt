package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.sandbox.ClipboardPatchParser
import com.example.data.sandbox.ClipboardPatchProposal
import com.example.ui.components.DiffPreviewBlock
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors
import com.example.viewmodel.PocketForgeUiState
import com.example.viewmodel.WebCompanionProvider
import kotlinx.coroutines.launch

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
                text = "Private Web Sign-In ($providerTitle)",
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
                    text = "Destination: $targetUrl",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmProceed,
                modifier = Modifier.testTag("confirm_web_signin_redirect")
            ) {
                Text("Continue to Web")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebCompanionScreen(
    uiState: PocketForgeUiState,
    onSelectProvider: (WebCompanionProvider) -> Unit,
    onBuildTreeContext: suspend () -> String?,
    onBuildGrepContext: suspend (String) -> String?,
    onBuildFileRangeContext: suspend (String, Int, Int) -> String?,
    onPreviewPatch: suspend (ClipboardPatchProposal, String) -> Result<String>,
    onApplyPatch: (ClipboardPatchProposal, String, () -> Unit) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ideColors = LocalIdeColors.current
    val scope = rememberCoroutineScope()
    val webBgColor = MaterialTheme.colorScheme.background.toArgb()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentLoadedUrl by remember { mutableStateOf(uiState.selectedWebProvider.url) }

    var showContextSheet by remember { mutableStateOf(false) }
    var pendingPatchProposals by remember { mutableStateOf<List<ClipboardPatchProposal>>(emptyList()) }
    var pendingExternalRedirectUrl by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPrivacyInfoOnly by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    LaunchedEffect(uiState.selectedWebProvider) {
        val target = uiState.selectedWebProvider.url
        if (webViewRef != null && currentLoadedUrl != target) {
            currentLoadedUrl = target
            webViewRef?.loadUrl(target)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Provider Switcher + Browser Controls
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, ideColors.hairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WebCompanionProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = uiState.selectedWebProvider == provider,
                                onClick = { onSelectProvider(provider) },
                                label = { Text(provider.displayName) },
                                modifier = Modifier.testTag("web_provider_${provider.name.lowercase()}")
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Web back",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload page",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                pendingExternalRedirectUrl =
                                    uiState.selectedWebProvider.displayName to (webViewRef?.url ?: uiState.selectedWebProvider.url)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("open_web_in_browser_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in browser for sign-in",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Compact On-Device Privacy Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPrivacyInfoOnly = true }
                        .padding(top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Local-only privacy guarantee",
                        tint = ideColors.diffAddText,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Private device-only workspace · Tap Open-in-Browser if sign-in requires Google/OAuth",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }

        // Embedded Subscription WebView
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(webBgColor)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val host = uri.host.orEmpty().lowercase()
                                // Google OAuth explicitly blocks embedded WebViews; offer safe browser sign-in with privacy note.
                                if (host.contains("accounts.google.com")) {
                                    pendingExternalRedirectUrl =
                                        uiState.selectedWebProvider.displayName to uri.toString()
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                canGoBack = view?.canGoBack() == true
                                if (!url.isNullOrBlank()) currentLoadedUrl = url
                            }
                        }
                        loadUrl(uiState.selectedWebProvider.url)
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Docked Workspace Bridge Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, ideColors.hairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showContextSheet = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("web_copy_context_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Context", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = {
                        val clipText = readClipboardText(context)
                        if (clipText.isNullOrBlank()) {
                            onNotify("Clipboard is empty. Copy a code reply from ${uiState.selectedWebProvider.displayName} first.")
                        } else {
                            val parsed = ClipboardPatchParser.parse(clipText)
                            if (parsed.isEmpty()) {
                                onNotify("No code or patch block found in clipboard.")
                            } else {
                                pendingPatchProposals = parsed
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("web_apply_patch_button")
                ) {
                    Icon(Icons.Default.ContentPasteGo, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apply Patch", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    if (showContextSheet) {
        CopyWorkspaceContextSheet(
            uiState = uiState,
            onCopyTree = {
                scope.launch {
                    val text = onBuildTreeContext() ?: return@launch
                    copyToClipboard(context, "PocketForge Tree", text)
                    onNotify("Copied workspace tree + patch format instructions to clipboard.")
                    showContextSheet = false
                }
            },
            onCopyGrep = { pattern ->
                scope.launch {
                    val text = onBuildGrepContext(pattern) ?: return@launch
                    copyToClipboard(context, "PocketForge Grep", text)
                    onNotify("Copied grep results for '$pattern' to clipboard.")
                    showContextSheet = false
                }
            },
            onCopyFileRange = { path, start, end ->
                scope.launch {
                    val text = onBuildFileRangeContext(path, start, end) ?: return@launch
                    copyToClipboard(context, "PocketForge File Slice", text)
                    onNotify("Copied $path (L$start-L$end) to clipboard.")
                    showContextSheet = false
                }
            },
            onDismiss = { showContextSheet = false }
        )
    }

    if (pendingPatchProposals.isNotEmpty()) {
        ApplyClipboardPatchDialog(
            proposals = pendingPatchProposals,
            workspaceFiles = uiState.workspaceFiles.filter { !it.isDirectory }.map { it.relativePath },
            onPreview = onPreviewPatch,
            onConfirmApply = { proposal, targetPath ->
                onApplyPatch(proposal, targetPath) {
                    val remaining = pendingPatchProposals.drop(1)
                    pendingPatchProposals = remaining
                }
            },
            onDismiss = { pendingPatchProposals = emptyList() }
        )
    }

    pendingExternalRedirectUrl?.let { (title, url) ->
        WebSignInPrivacyDialog(
            providerTitle = title,
            targetUrl = url,
            onConfirmProceed = {
                pendingExternalRedirectUrl = null
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    onNotify("Could not open browser: ${it.localizedMessage}")
                }
            },
            onDismiss = { pendingExternalRedirectUrl = null }
        )
    }

    if (showPrivacyInfoOnly) {
        AlertDialog(
            onDismissRequest = { showPrivacyInfoOnly = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Account & Data Privacy") },
            text = { Text(WEB_SIGN_IN_PRIVACY_NOTE, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { showPrivacyInfoOnly = false }) { Text("Got It") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyWorkspaceContextSheet(
    uiState: PocketForgeUiState,
    onCopyTree: () -> Unit,
    onCopyGrep: (String) -> Unit,
    onCopyFileRange: (String, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val files = remember(uiState.workspaceFiles) {
        uiState.workspaceFiles.filter { !it.isDirectory }.map { it.relativePath }
    }
    var mode by remember { mutableIntStateOf(0) } // 0 = Tree, 1 = Grep, 2 = File Range
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
            Text("Copy Token-Efficient Workspace Context", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Includes PocketForge patch-format instructions so replies can be applied in one tap.",
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
private fun ApplyClipboardPatchDialog(
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

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
