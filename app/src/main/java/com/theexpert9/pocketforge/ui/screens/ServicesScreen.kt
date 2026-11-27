package com.theexpert9.pocketforge.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theexpert9.pocketforge.data.auth.GoogleProfileState
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import com.theexpert9.pocketforge.data.sandbox.ClipboardPatchParser
import com.theexpert9.pocketforge.data.sandbox.ClipboardPatchProposal
import com.theexpert9.pocketforge.getFirebaseDiagnosticStatus
import com.theexpert9.pocketforge.ui.theme.JetBrainsMonoFontFamily
import com.theexpert9.pocketforge.ui.theme.LocalIdeColors
import com.theexpert9.pocketforge.util.BrowserLauncher
import com.theexpert9.pocketforge.viewmodel.PocketForgeUiState
import com.theexpert9.pocketforge.viewmodel.ServiceVerifyState
import kotlinx.coroutines.launch

@Composable
fun ServicesScreen(
    uiState: PocketForgeUiState,
    hasApiKey: (String) -> Boolean,
    maskedApiKey: (String) -> String?,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onSaveProvider: (ProviderConfigEntity, String?, Boolean) -> Unit,
    onActivateProvider: (ProviderConfigEntity) -> Unit,
    onVerifyProvider: (ProviderConfigEntity) -> Unit,
    onDisconnectProvider: (ProviderConfigEntity) -> Unit,
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
    val firebaseStatus = remember(context) { getFirebaseDiagnosticStatus(context) }

    var connectingProvider by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var pendingBrowserRedirect by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showContextSheet by remember { mutableStateOf(false) }
    var pendingPatchProposals by remember { mutableStateOf<List<ClipboardPatchProposal>>(emptyList()) }

    val (connectedServices, availableServices) = remember(uiState.providers, uiState.keyVaultRevision) {
        uiState.providers.partition { provider ->
            provider.protocol == ProviderProtocol.FIREBASE_GEMINI ||
                provider.id == "ollama" ||
                hasApiKey(provider.apiKeyAlias)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // 1. Native In-App Google Sign-In Profile Hero Card
            item(key = "google_account_hero") {
                GoogleProfileAuthCard(
                    profile = uiState.googleProfile,
                    onSignIn = onGoogleSignIn,
                    onSignOut = onGoogleSignOut
                )
            }

            // 2. Signed-In / Connected AI Services
            item(key = "connected_header") {
                SectionHeaderRow(
                    title = "Connected & Active Services",
                    badge = "${connectedServices.size} READY",
                    isConnected = true
                )
            }

            items(connectedServices, key = { "conn_${it.id}" }) { provider ->
                ServiceAuthCard(
                    provider = provider,
                    isConnected = true,
                    isActiveSessionProvider = uiState.activeSession?.providerId == provider.id,
                    maskedKey = maskedApiKey(provider.apiKeyAlias),
                    firebaseStatus = if (provider.protocol == ProviderProtocol.FIREBASE_GEMINI) firebaseStatus else null,
                    verifyState = uiState.serviceVerificationMap[provider.id] ?: ServiceVerifyState.Idle,
                    onActivate = { onActivateProvider(provider) },
                    onVerify = { onVerifyProvider(provider) },
                    onConnectOrManage = { connectingProvider = provider },
                    onDisconnect = { onDisconnectProvider(provider) },
                    onOpenBrowserPortal = { label, url -> pendingBrowserRedirect = label to url }
                )
            }

            // 3. Available AI Services to Connect
            if (availableServices.isNotEmpty()) {
                item(key = "available_header") {
                    Spacer(Modifier.height(4.dp))
                    SectionHeaderRow(
                        title = "Available AI Services",
                        badge = "${availableServices.size} AVAILABLE",
                        isConnected = false
                    )
                }

                items(availableServices, key = { "avail_${it.id}" }) { provider ->
                    ServiceAuthCard(
                        provider = provider,
                        isConnected = false,
                        isActiveSessionProvider = uiState.activeSession?.providerId == provider.id,
                        maskedKey = null,
                        firebaseStatus = null,
                        verifyState = uiState.serviceVerificationMap[provider.id] ?: ServiceVerifyState.Idle,
                        onActivate = { onActivateProvider(provider) },
                        onVerify = { onVerifyProvider(provider) },
                        onConnectOrManage = { connectingProvider = provider },
                        onDisconnect = { onDisconnectProvider(provider) },
                        onOpenBrowserPortal = { label, url -> pendingBrowserRedirect = label to url }
                    )
                }
            }

            // 4. Browser-Forced Web AI Portals (Never Hijacks to Gemini Android App)
            item(key = "browser_portals_card") {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, ideColors.hairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Browser-Forced Web AI Portals",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = "Opens strictly inside Chrome or your web browser (never redirects into the Gemini Android app) so you can use personal web sessions with Copy Context & Apply Patch below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { pendingBrowserRedirect = "Gemini Web" to "https://gemini.google.com/app" },
                                modifier = Modifier.heightIn(min = 44.dp).testTag("portal_gemini_web")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Gemini Web")
                            }
                            OutlinedButton(
                                onClick = { pendingBrowserRedirect = "ChatGPT Web" to "https://chatgpt.com" },
                                modifier = Modifier.heightIn(min = 44.dp).testTag("portal_chatgpt_web")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("ChatGPT Web")
                            }
                            OutlinedButton(
                                onClick = { pendingBrowserRedirect = "Claude Web" to "https://claude.ai" },
                                modifier = Modifier.heightIn(min = 44.dp).testTag("portal_claude_web")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Claude Web")
                            }
                        }
                    }
                }
            }
        }

        // Docked Workspace Clipboard Bridge Bar
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
                        .testTag("services_copy_context_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Context", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = {
                        val clipText = readClipboardText(context)
                        if (clipText.isNullOrBlank()) {
                            onNotify("Clipboard is empty. Copy a code reply from your AI chat first.")
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
                        .testTag("services_apply_patch_button")
                ) {
                    Icon(Icons.Default.ContentPasteGo, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apply Patch", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    connectingProvider?.let { provider ->
        ServiceQuickConnectDialog(
            provider = provider,
            existingMaskedKey = maskedApiKey(provider.apiKeyAlias),
            portalInfo = providerKeyPortal(provider.id),
            onOpenPortal = { label, url ->
                pendingBrowserRedirect = label to url
            },
            onSave = { updated, newKey, setActive ->
                onSaveProvider(updated, newKey, setActive)
                connectingProvider = null
            },
            onDismiss = { connectingProvider = null }
        )
    }

    pendingBrowserRedirect?.let { (title, url) ->
        WebSignInPrivacyDialog(
            providerTitle = title,
            targetUrl = url,
            onConfirmProceed = {
                pendingBrowserRedirect = null
                BrowserLauncher.openInBrowser(context, url).onFailure { err ->
                    onNotify("Could not open browser: ${err.localizedMessage}")
                }
            },
            onDismiss = { pendingBrowserRedirect = null }
        )
    }

    if (showContextSheet) {
        CopyWorkspaceContextSheet(
            uiState = uiState,
            onCopyTree = {
                scope.launch {
                    val text = onBuildTreeContext() ?: return@launch
                    copyToClipboard(context, "PocketForge Tree", text)
                    onNotify("Copied workspace tree + patch instructions to clipboard.")
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
                    pendingPatchProposals = pendingPatchProposals.drop(1)
                }
            },
            onDismiss = { pendingPatchProposals = emptyList() }
        )
    }
}

@Composable
private fun GoogleProfileAuthCard(
    profile: GoogleProfileState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (profile.isSignedIn) ideColors.diffAddText.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (profile.isSignedIn) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (profile.isSignedIn) {
                            val initial = (profile.displayName?.firstOrNull() ?: 'G').uppercaseChar()
                            Text(
                                text = initial.toString(),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "PocketForge Google profile",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (profile.isSignedIn) profile.displayName ?: "Google Account" else "PocketForge Account",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (profile.isSignedIn) profile.email.orEmpty() else "Native in-app Google Sign-In",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = if (profile.isSignedIn) ideColors.diffAddBg else MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = if (profile.isSignedIn) "SIGNED IN" else "LOCAL",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (profile.isSignedIn) ideColors.diffAddText else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = if (profile.isSignedIn) {
                    "Authenticated in-app via Android Credential Manager. All project files and API keys remain private and encrypted on your device."
                } else {
                    "Sign in natively with Google using Android's in-app sheet, or connect your AI service keys below. All workspaces stay strictly on your device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (profile.authError != null) {
                Text(
                    text = profile.authError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (profile.isSignedIn) {
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("google_sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out of Google")
                }
            } else {
                Button(
                    onClick = onSignIn,
                    enabled = !profile.isAuthenticating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("google_sign_in_button")
                ) {
                    if (profile.isAuthenticating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Opening Google Sign-In…")
                    } else {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    badge: String,
    isConnected: Boolean
) {
    val ideColors = LocalIdeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Surface(
            shape = CircleShape,
            color = if (isConnected) ideColors.diffAddBg else MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (isConnected) ideColors.diffAddText else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ServiceAuthCard(
    provider: ProviderConfigEntity,
    isConnected: Boolean,
    isActiveSessionProvider: Boolean,
    maskedKey: String?,
    firebaseStatus: String?,
    verifyState: ServiceVerifyState,
    onActivate: () -> Unit,
    onVerify: () -> Unit,
    onConnectOrManage: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenBrowserPortal: (String, String) -> Unit
) {
    val ideColors = LocalIdeColors.current
    val isBuiltIn = provider.protocol == ProviderProtocol.FIREBASE_GEMINI
    val isLocal = provider.id == "ollama"
    val portal = remember(provider.id) { providerKeyPortal(provider.id) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (isActiveSessionProvider) 1.5.dp else 1.dp,
            color = when {
                isActiveSessionProvider -> MaterialTheme.colorScheme.primary
                isConnected -> ideColors.diffAddText.copy(alpha = 0.35f)
                else -> ideColors.hairlineBorder
            }
        ),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = provider.selectedModelId,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val statusText = when {
                    isActiveSessionProvider -> "ACTIVE"
                    isBuiltIn -> "BUILT-IN"
                    isConnected -> "CONNECTED"
                    else -> "AVAILABLE"
                }
                val badgeBg = when {
                    isActiveSessionProvider -> MaterialTheme.colorScheme.primaryContainer
                    isConnected -> ideColors.diffAddBg
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                val badgeFg = when {
                    isActiveSessionProvider -> MaterialTheme.colorScheme.primary
                    isConnected -> ideColors.diffAddText
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(shape = CircleShape, color = badgeBg) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (firebaseStatus != null) {
                Text(
                    text = "Firebase AI Logic · $firebaseStatus",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = ideColors.diffAddText
                )
            } else if (maskedKey != null) {
                Text(
                    text = "Encrypted Key: $maskedKey",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (provider.description.isNotBlank()) {
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (verifyState) {
                is ServiceVerifyState.Verified -> {
                    Text(
                        text = "✓ ${verifyState.detail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ideColors.diffAddText
                    )
                }
                is ServiceVerifyState.Failed -> {
                    Text(
                        text = "✕ ${verifyState.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> Unit
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isConnected) {
                    if (!isActiveSessionProvider) {
                        Button(
                            onClick = onActivate,
                            modifier = Modifier
                                .heightIn(min = 42.dp)
                                .testTag("service_activate_${provider.id}")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Use Active")
                        }
                    }
                    OutlinedButton(
                        onClick = onVerify,
                        enabled = verifyState !is ServiceVerifyState.Checking,
                        modifier = Modifier.heightIn(min = 42.dp)
                    ) {
                        if (verifyState is ServiceVerifyState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text("Verify")
                    }
                    if (!isBuiltIn) {
                        TextButton(onClick = onConnectOrManage) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Manage")
                        }
                    }
                    if (!isBuiltIn && !isLocal) {
                        TextButton(onClick = onDisconnect) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = "Disconnect service",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Disconnect", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Button(
                        onClick = onConnectOrManage,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("service_connect_${provider.id}")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Connect Key")
                    }
                    if (portal != null) {
                        OutlinedButton(
                            onClick = { onOpenBrowserPortal(portal.first, portal.second) },
                            modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Get Key in Browser")
                        }
                    }
                }
            }
        }
    }
}

private fun providerKeyPortal(providerId: String): Pair<String, String>? = when (providerId) {
    "google_ai_studio" -> "Google AI Studio" to "https://aistudio.google.com/apikey"
    "openai" -> "OpenAI Platform" to "https://platform.openai.com/api-keys"
    "anthropic" -> "Anthropic Console" to "https://console.anthropic.com/settings/keys"
    "openrouter" -> "OpenRouter" to "https://openrouter.ai/keys"
    "deepseek" -> "DeepSeek Platform" to "https://platform.deepseek.com/api_keys"
    "groq" -> "Groq Console" to "https://console.groq.com/keys"
    else -> null
}
