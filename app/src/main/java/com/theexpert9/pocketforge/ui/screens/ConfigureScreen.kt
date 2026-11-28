package com.theexpert9.pocketforge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.theexpert9.pocketforge.data.ai.AiResiliencePolicy
import com.theexpert9.pocketforge.data.db.CatalogModelEntity
import com.theexpert9.pocketforge.data.db.PromptProfileEntity
import com.theexpert9.pocketforge.data.db.ProviderConfigEntity
import com.theexpert9.pocketforge.data.db.ProviderProtocol
import com.theexpert9.pocketforge.data.db.WorkspaceToolName
import com.theexpert9.pocketforge.getFirebaseDiagnosticStatus
import com.theexpert9.pocketforge.ui.theme.JetBrainsMonoFontFamily
import com.theexpert9.pocketforge.ui.theme.LocalIdeColors
import com.theexpert9.pocketforge.util.BrowserLauncher
import com.theexpert9.pocketforge.viewmodel.PocketForgeUiState
import java.util.UUID
import kotlin.math.roundToInt

private enum class ConfigureSubTab {
    PROVIDERS,
    PROMPT_STUDIO,
    SYSTEM_LOGCAT
}

@Composable
fun ConfigureScreen(
    uiState: PocketForgeUiState,
    hasApiKey: (String) -> Boolean,
    maskedApiKey: (String) -> String?,
    onSyncOpenCode: () -> Unit,
    onCatalogSearchChange: (String) -> Unit,
    onActivateCatalogModel: (CatalogModelEntity) -> Unit,
    onSaveProvider: (ProviderConfigEntity, String?) -> Unit,
    onDeleteProvider: (ProviderConfigEntity) -> Unit,
    onTestProvider: (ProviderConfigEntity) -> Unit,
    onSelectPromptProfile: (String) -> Unit,
    onSavePromptProfile: (PromptProfileEntity) -> Unit,
    onDeletePromptProfile: (String) -> Unit,
    onRefreshTermuxStatus: () -> Unit,
    onSetTermuxEnabled: (Boolean) -> Unit,
    onTestTermux: () -> Unit,
    onRefreshLogcat: () -> Unit,
    onClearLogcat: () -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ideColors = LocalIdeColors.current
    var activeSubTab by remember { mutableStateOf(ConfigureSubTab.PROVIDERS) }
    var editingProvider by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var creatingCustomProvider by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Sub-Tab Selector
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, ideColors.hairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeSubTab == ConfigureSubTab.PROVIDERS,
                    onClick = { activeSubTab = ConfigureSubTab.PROVIDERS },
                    leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Providers") },
                    modifier = Modifier.testTag("configure_providers_tab")
                )
                FilterChip(
                    selected = activeSubTab == ConfigureSubTab.PROMPT_STUDIO,
                    onClick = { activeSubTab = ConfigureSubTab.PROMPT_STUDIO },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Prompt & Tools") },
                    modifier = Modifier.testTag("configure_prompt_studio_tab")
                )
                FilterChip(
                    selected = activeSubTab == ConfigureSubTab.SYSTEM_LOGCAT,
                    onClick = { activeSubTab = ConfigureSubTab.SYSTEM_LOGCAT },
                    leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("System & Logcat") },
                    modifier = Modifier.testTag("configure_system_logcat_tab")
                )
            }
        }

        when (activeSubTab) {
            ConfigureSubTab.PROVIDERS -> ProvidersAndOpenCodePane(
                uiState = uiState,
                hasApiKey = hasApiKey,
                maskedApiKey = maskedApiKey,
                onSyncOpenCode = onSyncOpenCode,
                onCatalogSearchChange = onCatalogSearchChange,
                onActivateCatalogModel = onActivateCatalogModel,
                onEditProvider = { editingProvider = it },
                onAddCustomProvider = { creatingCustomProvider = true },
                onDeleteProvider = onDeleteProvider,
                onTestProvider = onTestProvider
            )
            ConfigureSubTab.PROMPT_STUDIO -> PromptStudioPane(
                profiles = uiState.promptProfiles,
                activeProfile = uiState.activePromptProfile,
                onSelectProfile = onSelectPromptProfile,
                onSaveProfile = onSavePromptProfile,
                onDeleteProfile = onDeletePromptProfile
            )
            ConfigureSubTab.SYSTEM_LOGCAT -> ConfigureSystemPane(
                uiState = uiState,
                onRefreshTermuxStatus = onRefreshTermuxStatus,
                onSetTermuxEnabled = onSetTermuxEnabled,
                onTestTermux = onTestTermux,
                onRefreshLogcat = onRefreshLogcat,
                onClearLogcat = onClearLogcat,
                onNotify = onNotify
            )
        }
    }

    editingProvider?.let { provider ->
        ProviderEditorDialog(
            initial = provider,
            existingMaskedKey = maskedApiKey(provider.apiKeyAlias),
            isNew = false,
            onSave = { cfg, key ->
                onSaveProvider(cfg, key)
                editingProvider = null
            },
            onDismiss = { editingProvider = null }
        )
    }

    if (creatingCustomProvider) {
        val blankCustom = ProviderConfigEntity(
            id = "custom_${UUID.randomUUID().toString().take(6)}",
            displayName = "Custom OpenCode Endpoint",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            selectedModelId = "gpt-4.1",
            apiKeyAlias = "",
            enabled = true,
            isCustom = true,
            description = "User-defined OpenAI-compatible or Anthropic provider."
        )
        ProviderEditorDialog(
            initial = blankCustom,
            existingMaskedKey = null,
            isNew = true,
            onSave = { cfg, key ->
                onSaveProvider(cfg, key)
                creatingCustomProvider = false
            },
            onDismiss = { creatingCustomProvider = false }
        )
    }
}

@Composable
private fun ProvidersAndOpenCodePane(
    uiState: PocketForgeUiState,
    hasApiKey: (String) -> Boolean,
    maskedApiKey: (String) -> String?,
    onSyncOpenCode: () -> Unit,
    onCatalogSearchChange: (String) -> Unit,
    onActivateCatalogModel: (CatalogModelEntity) -> Unit,
    onEditProvider: (ProviderConfigEntity) -> Unit,
    onAddCustomProvider: () -> Unit,
    onDeleteProvider: (ProviderConfigEntity) -> Unit,
    onTestProvider: (ProviderConfigEntity) -> Unit
) {
    val context = LocalContext.current
    val ideColors = LocalIdeColors.current
    val firebaseStatus = remember(context) { getFirebaseDiagnosticStatus(context) }
    var pendingKeyPortal by remember { mutableStateOf<Pair<String, String>?>(null) }

    val filteredCatalog = remember(uiState.catalogModels, uiState.catalogSearchQuery) {
        val q = uiState.catalogSearchQuery.trim()
        val list = if (q.isEmpty()) uiState.catalogModels else uiState.catalogModels.filter {
            it.modelName.contains(q, ignoreCase = true) ||
                it.modelId.contains(q, ignoreCase = true) ||
                it.providerName.contains(q, ignoreCase = true) ||
                it.providerId.contains(q, ignoreCase = true)
        }
        list.take(60)
    }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Guided Quick Connect Official API Keys Strip
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guided API Key Setup (Gemini · OpenAI · Claude)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Open the official developer portal to grab a personal key, then paste it into the encrypted Keystore vault below.",
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
                            onClick = { pendingKeyPortal = "Google AI Studio (Gemini)" to "https://aistudio.google.com/apikey" },
                            modifier = Modifier.testTag("guided_key_gemini")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Gemini Key")
                        }
                        OutlinedButton(
                            onClick = { pendingKeyPortal = "OpenAI Platform" to "https://platform.openai.com/api-keys" },
                            modifier = Modifier.testTag("guided_key_openai")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("OpenAI Key")
                        }
                        OutlinedButton(
                            onClick = { pendingKeyPortal = "Anthropic Console" to "https://console.anthropic.com/settings/keys" },
                            modifier = Modifier.testTag("guided_key_claude")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Claude Key")
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Configured Providers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "API keys are AES-256 encrypted locally in Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onAddCustomProvider,
                    modifier = Modifier.testTag("add_custom_provider_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Custom")
                }
            }
        }

        items(uiState.providers, key = { it.id }) { provider ->
            val isBuiltIn = provider.protocol == ProviderProtocol.FIREBASE_GEMINI
            val hasKey = isBuiltIn || hasApiKey(provider.apiKeyAlias)
            val masked = maskedApiKey(provider.apiKeyAlias)
            val isTesting = uiState.testingProviderId == provider.id

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    1.dp,
                    if (uiState.activeSession?.providerId == provider.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else ideColors.hairlineBorder
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${provider.selectedModelId} · ${provider.baseUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (isBuiltIn || hasKey) ideColors.diffAddBg else ideColors.warningBg
                        ) {
                            Text(
                                text = if (isBuiltIn) "ZERO-CONFIG" else if (hasKey) "ENCRYPTED" else "NO KEY",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isBuiltIn || hasKey) ideColors.diffAddText else ideColors.warningText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    if (isBuiltIn) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Firebase AI Logic: $firebaseStatus",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = ideColors.diffAddText
                        )
                    } else if (masked != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Key: $masked",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (provider.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = provider.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isBuiltIn) {
                            OutlinedButton(
                                onClick = { onEditProvider(provider) },
                                modifier = Modifier.testTag("edit_provider_${provider.id}")
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (hasKey) "Edit Key & URL" else "Add API Key")
                            }
                        }
                        TextButton(
                            onClick = { onTestProvider(provider) },
                            enabled = !isTesting
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("Test")
                        }
                        if (provider.isCustom) {
                            IconButton(onClick = { onDeleteProvider(provider) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove custom provider", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // OpenCode Live Registry Section
        item {
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("OpenCode Provider Registry", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Sync live providers & models from models.dev/api.json (${uiState.catalogModels.size} cached)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onSyncOpenCode,
                            enabled = !uiState.isSyncingCatalog,
                            modifier = Modifier.testTag("sync_opencode_catalog_button")
                        ) {
                            if (uiState.isSyncingCatalog) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Sync")
                        }
                    }

                    OutlinedTextField(
                        value = uiState.catalogSearchQuery,
                        onValueChange = onCatalogSearchChange,
                        placeholder = { Text("Search GPT, Gemini, Claude, DeepSeek, Qwen, Groq…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("opencode_catalog_search")
                    )
                }
            }
        }

        items(filteredCatalog, key = { it.compositeId }) { model ->
            val activeSession = uiState.activeSession
            val isActive = activeSession?.providerId == model.providerId &&
                activeSession.modelId == model.modelId
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    1.dp,
                    if (isActive) MaterialTheme.colorScheme.primary else ideColors.hairlineBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onActivateCatalogModel(model) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = model.modelName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (AiResiliencePolicy.isSubscriptionRequiredModel(model.modelId)) {
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
                            text = "${model.providerName} · ${model.modelId} · ${model.contextLimit / 1000}K ctx",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onActivateCatalogModel(model) }) {
                        if (isActive) {
                            Icon(Icons.Default.Check, contentDescription = "Active", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Active")
                        } else {
                            Text("Use")
                        }
                    }
                }
            }
        }
    }

    pendingKeyPortal?.let { (portalName, url) ->
        WebSignInPrivacyDialog(
            providerTitle = portalName,
            targetUrl = url,
            onConfirmProceed = {
                pendingKeyPortal = null
                BrowserLauncher.openInBrowser(context, url)
            },
            onDismiss = { pendingKeyPortal = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptStudioPane(
    profiles: List<PromptProfileEntity>,
    activeProfile: PromptProfileEntity?,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (PromptProfileEntity) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    val ideColors = LocalIdeColors.current
    var name by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var toolAccessRules by remember { mutableStateOf("") }
    var responseFormatRules by remember { mutableStateOf("") }
    var enabledTools by remember { mutableStateOf(WorkspaceToolName.ALL.toSet()) }
    var maxSteps by remember { mutableFloatStateOf(8f) }
    var maxReadLines by remember { mutableFloatStateOf(120f) }

    LaunchedEffect(activeProfile?.id) {
        if (activeProfile != null) {
            name = activeProfile.name
            systemPrompt = activeProfile.systemPrompt
            toolAccessRules = activeProfile.toolAccessRules
            responseFormatRules = activeProfile.responseFormatRules
            enabledTools = activeProfile.enabledToolSet()
            maxSteps = activeProfile.maxAutonomousSteps.toFloat()
            maxReadLines = activeProfile.maxReadLines.toFloat()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text("Active Prompt & Tool Profile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                profiles.forEach { prof ->
                    FilterChip(
                        selected = prof.id == activeProfile?.id,
                        onClick = { onSelectProfile(prof.id) },
                        label = { Text(prof.name) }
                    )
                }
                OutlinedButton(
                    onClick = {
                        val copy = PromptProfileEntity(
                            id = "profile_${UUID.randomUUID().toString().take(6)}",
                            name = "Custom Profile ${profiles.size + 1}",
                            systemPrompt = systemPrompt.ifBlank { "You are a precise coding assistant." },
                            toolAccessRules = toolAccessRules,
                            responseFormatRules = responseFormatRules,
                            enabledToolsCsv = enabledTools.joinToString(","),
                            maxAutonomousSteps = maxSteps.roundToInt(),
                            maxReadLines = maxReadLines.roundToInt(),
                            autonomousMode = true,
                            isDefault = false
                        )
                        onSaveProfile(copy)
                        onSelectProfile(copy.id)
                    },
                    modifier = Modifier.testTag("new_prompt_profile_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clone")
                }
            }
        }

        if (activeProfile != null) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Role & Persona") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = toolAccessRules,
                    onValueChange = { toolAccessRules = it },
                    label = { Text("File Access & Search Strategy (No Whole-File Reads)") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tool_access_rules_input")
                )
            }
            item {
                OutlinedTextField(
                    value = responseFormatRules,
                    onValueChange = { responseFormatRules = it },
                    label = { Text("Response Format Rules") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, ideColors.hairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enabled Autonomous Workspace Tools", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            WorkspaceToolName.ALL.forEach { toolName ->
                                val selected = toolName in enabledTools
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        enabledTools = if (selected) enabledTools - toolName else enabledTools + toolName
                                    },
                                    label = {
                                        Text(
                                            text = toolName,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Max Autonomous Steps per Turn: ${maxSteps.roundToInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Slider(
                            value = maxSteps,
                            onValueChange = { maxSteps = it },
                            valueRange = 1f..15f,
                            steps = 13
                        )

                        Text(
                            text = "Max Lines Returned per read_file_range: ${maxReadLines.roundToInt()} lines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Slider(
                            value = maxReadLines,
                            onValueChange = { maxReadLines = it },
                            valueRange = 40f..300f,
                            steps = 12
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveProfile(
                                activeProfile.copy(
                                    name = name.ifBlank { activeProfile.name },
                                    systemPrompt = systemPrompt,
                                    toolAccessRules = toolAccessRules,
                                    responseFormatRules = responseFormatRules,
                                    enabledToolsCsv = enabledTools.joinToString(","),
                                    maxAutonomousSteps = maxSteps.roundToInt(),
                                    maxReadLines = maxReadLines.roundToInt()
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_prompt_profile_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Prompt & Tool Config")
                    }
                    if (!activeProfile.isDefault) {
                        OutlinedButton(onClick = { onDeleteProfile(activeProfile.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete profile")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    initial: ProviderConfigEntity,
    existingMaskedKey: String?,
    isNew: Boolean,
    onSave: (ProviderConfigEntity, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf(initial.displayName) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var modelId by remember { mutableStateOf(initial.selectedModelId) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var apiKey by remember { mutableStateOf("") }
    var pendingPortalRedirect by remember { mutableStateOf<Pair<String, String>?>(null) }

    val officialKeyPortal = remember(initial.id) {
        when (initial.id) {
            "google_ai_studio" -> "Google AI Studio" to "https://aistudio.google.com/apikey"
            "openai" -> "OpenAI Platform" to "https://platform.openai.com/api-keys"
            "anthropic" -> "Anthropic Console" to "https://console.anthropic.com/settings/keys"
            "openrouter" -> "OpenRouter Keys" to "https://openrouter.ai/keys"
            else -> null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (isNew) "Add Custom Provider" else "Configure ${initial.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isNew) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Provider Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = protocol == ProviderProtocol.OPENAI_COMPATIBLE,
                            onClick = { protocol = ProviderProtocol.OPENAI_COMPATIBLE },
                            label = { Text("OpenAI-Compatible") }
                        )
                        FilterChip(
                            selected = protocol == ProviderProtocol.ANTHROPIC,
                            onClick = { protocol = ProviderProtocol.ANTHROPIC },
                            label = { Text("Anthropic") }
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base API URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Default Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(
                            if (existingMaskedKey != null) "Replace API Key ($existingMaskedKey)"
                            else "API Key (encrypted via Android Keystore)"
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val pasted = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
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
                        .testTag("provider_api_key_input")
                )
                if (officialKeyPortal != null) {
                    TextButton(
                        onClick = { pendingPortalRedirect = officialKeyPortal }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Get Official Key from ${officialKeyPortal.first}")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = initial.copy(
                        displayName = displayName.trim().ifBlank { initial.displayName },
                        baseUrl = baseUrl.trim().ifBlank { initial.baseUrl },
                        selectedModelId = modelId.trim().ifBlank { initial.selectedModelId },
                        protocol = protocol
                    )
                    onSave(updated, apiKey.takeIf { it.isNotBlank() })
                },
                modifier = Modifier.testTag("save_provider_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    pendingPortalRedirect?.let { (portalName, url) ->
        WebSignInPrivacyDialog(
            providerTitle = portalName,
            targetUrl = url,
            onConfirmProceed = {
                pendingPortalRedirect = null
                BrowserLauncher.openInBrowser(context, url)
            },
            onDismiss = { pendingPortalRedirect = null }
        )
    }
}
