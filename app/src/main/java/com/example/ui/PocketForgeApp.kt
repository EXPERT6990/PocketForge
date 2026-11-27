package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ModelQuickPickerSheet
import com.example.ui.components.NewWorkspaceDialog
import com.example.ui.components.WorkspaceSwitcherSheet
import com.example.ui.screens.AgentScreen
import com.example.ui.screens.ConfigureScreen
import com.example.ui.screens.FilesScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.screens.WebCompanionScreen
import com.example.ui.theme.LocalIdeColors
import com.example.viewmodel.MainTab
import com.example.viewmodel.PocketForgeUiState
import com.example.viewmodel.PocketForgeViewModel

@Composable
fun PocketForgeApp(
    viewModel: PocketForgeViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ideColors = LocalIdeColors.current

    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showNewWorkspaceDialog by remember { mutableStateOf(false) }

    if (uiState.activeTab != MainTab.AGENT && uiState.openEditor == null) {
        BackHandler {
            viewModel.selectTab(MainTab.AGENT)
        }
    }

    if (uiState.isBooting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Initializing Isolated Sandbox…", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ideColors.ambientGlow, MaterialTheme.colorScheme.background)
                )
            )
    ) {
        val isExpanded = maxWidth >= 840.dp

        if (isExpanded) {
            // Adaptive Tablet / Foldable Layout: NavigationRail + Split Workspace Pane
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Spacer(Modifier.height(12.dp))
                    NAV_ITEMS.forEach { item ->
                        val selected = uiState.activeTab == item.tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.selectTab(item.tab) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("nav_${item.tab.name.lowercase()}")
                        )
                    }
                }
                VerticalDivider(color = ideColors.hairlineBorder)
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    IdeTopBar(
                        uiState = uiState,
                        onOpenWorkspaces = { showWorkspaceSheet = true },
                        onOpenModels = { showModelSheet = true }
                    )
                    StatusBannerBar(uiState = uiState, onDismiss = viewModel::dismissBanner)
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Left pane always keeps Agent interactive on Expanded screens
                        AgentScreen(
                            uiState = uiState,
                            onDraftChange = viewModel::updateDraftPrompt,
                            onSendPrompt = viewModel::sendAgentPrompt,
                            onStopAgent = viewModel::stopAgent,
                            onSelectSession = viewModel::selectSession,
                            onCreateSession = { viewModel.createSession() },
                            onClearSession = viewModel::clearActiveSession,
                            onUndoSnapshot = viewModel::revertSnapshot,
                            modifier = Modifier.weight(1f)
                        )
                        VerticalDivider(color = ideColors.hairlineBorder)
                        // Right supporting pane switches between Files, Terminal, and Configure
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            when (uiState.activeTab) {
                                MainTab.WEB_AI -> WebCompanionScreen(
                                    uiState = uiState,
                                    onSelectProvider = viewModel::selectWebProvider,
                                    onBuildTreeContext = viewModel::buildTreeContextForWeb,
                                    onBuildGrepContext = viewModel::buildGrepContextForWeb,
                                    onBuildFileRangeContext = viewModel::buildFileRangeContextForWeb,
                                    onPreviewPatch = viewModel::previewClipboardPatch,
                                    onApplyPatch = viewModel::applyClipboardPatch,
                                    onNotify = viewModel::showInfoBanner
                                )
                                MainTab.TERMINAL -> TerminalScreen(
                                    uiState = uiState,
                                    onRunCommand = viewModel::executeTerminalCommand,
                                    onClearTerminal = viewModel::clearTerminal,
                                    onDebugWithAi = viewModel::sendTerminalEntryToAgent
                                )
                                MainTab.CONFIGURE -> ConfigureScreen(
                                    uiState = uiState,
                                    hasApiKey = viewModel::hasApiKey,
                                    maskedApiKey = viewModel::maskedApiKey,
                                    onSyncOpenCode = viewModel::syncOpenCodeCatalog,
                                    onCatalogSearchChange = viewModel::updateCatalogSearch,
                                    onActivateCatalogModel = viewModel::activateCatalogModel,
                                    onSaveProvider = viewModel::saveProvider,
                                    onDeleteProvider = viewModel::deleteCustomProvider,
                                    onTestProvider = viewModel::testProvider,
                                    onSelectPromptProfile = viewModel::selectPromptProfileForWorkspace,
                                    onSavePromptProfile = viewModel::savePromptProfile,
                                    onDeletePromptProfile = viewModel::deletePromptProfile
                                )
                                else -> FilesScreen(
                                    uiState = uiState,
                                    onOpenFile = viewModel::openFile,
                                    onUpdateEditor = viewModel::updateEditorContent,
                                    onSaveEditor = viewModel::saveOpenFile,
                                    onCloseEditor = viewModel::closeEditor,
                                    onCreateFile = viewModel::createNewFile,
                                    onCreateFolder = viewModel::createNewFolder,
                                    onDeleteFile = viewModel::deleteFile,
                                    onGrepQueryChange = viewModel::runWorkspaceGrep,
                                    onAskAiAboutFile = viewModel::askAiAboutFile
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Compact Phone Layout: Edge-to-Edge Scaffold + M3 NavigationBar
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        IdeTopBar(
                            uiState = uiState,
                            onOpenWorkspaces = { showWorkspaceSheet = true },
                            onOpenModels = { showModelSheet = true }
                        )
                        StatusBannerBar(uiState = uiState, onDismiss = viewModel::dismissBanner)
                    }
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = uiState.activeTab == item.tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("nav_${item.tab.name.lowercase()}")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                ) {
                    when (uiState.activeTab) {
                        MainTab.AGENT -> AgentScreen(
                            uiState = uiState,
                            onDraftChange = viewModel::updateDraftPrompt,
                            onSendPrompt = viewModel::sendAgentPrompt,
                            onStopAgent = viewModel::stopAgent,
                            onSelectSession = viewModel::selectSession,
                            onCreateSession = { viewModel.createSession() },
                            onClearSession = viewModel::clearActiveSession,
                            onUndoSnapshot = viewModel::revertSnapshot
                        )
                        MainTab.WEB_AI -> WebCompanionScreen(
                            uiState = uiState,
                            onSelectProvider = viewModel::selectWebProvider,
                            onBuildTreeContext = viewModel::buildTreeContextForWeb,
                            onBuildGrepContext = viewModel::buildGrepContextForWeb,
                            onBuildFileRangeContext = viewModel::buildFileRangeContextForWeb,
                            onPreviewPatch = viewModel::previewClipboardPatch,
                            onApplyPatch = viewModel::applyClipboardPatch,
                            onNotify = viewModel::showInfoBanner
                        )
                        MainTab.FILES -> FilesScreen(
                            uiState = uiState,
                            onOpenFile = viewModel::openFile,
                            onUpdateEditor = viewModel::updateEditorContent,
                            onSaveEditor = viewModel::saveOpenFile,
                            onCloseEditor = viewModel::closeEditor,
                            onCreateFile = viewModel::createNewFile,
                            onCreateFolder = viewModel::createNewFolder,
                            onDeleteFile = viewModel::deleteFile,
                            onGrepQueryChange = viewModel::runWorkspaceGrep,
                            onAskAiAboutFile = viewModel::askAiAboutFile
                        )
                        MainTab.TERMINAL -> TerminalScreen(
                            uiState = uiState,
                            onRunCommand = viewModel::executeTerminalCommand,
                            onClearTerminal = viewModel::clearTerminal,
                            onDebugWithAi = viewModel::sendTerminalEntryToAgent
                        )
                        MainTab.CONFIGURE -> ConfigureScreen(
                            uiState = uiState,
                            hasApiKey = viewModel::hasApiKey,
                            maskedApiKey = viewModel::maskedApiKey,
                            onSyncOpenCode = viewModel::syncOpenCodeCatalog,
                            onCatalogSearchChange = viewModel::updateCatalogSearch,
                            onActivateCatalogModel = viewModel::activateCatalogModel,
                            onSaveProvider = viewModel::saveProvider,
                            onDeleteProvider = viewModel::deleteCustomProvider,
                            onTestProvider = viewModel::testProvider,
                            onSelectPromptProfile = viewModel::selectPromptProfileForWorkspace,
                            onSavePromptProfile = viewModel::savePromptProfile,
                            onDeletePromptProfile = viewModel::deletePromptProfile
                        )
                    }
                }
            }
        }
    }

    if (showWorkspaceSheet) {
        WorkspaceSwitcherSheet(
            workspaces = uiState.workspaces,
            activeWorkspaceId = uiState.activeWorkspaceId,
            onSelectWorkspace = viewModel::selectWorkspace,
            onCreateWorkspaceClick = { showNewWorkspaceDialog = true },
            onDeleteWorkspace = viewModel::deleteWorkspace,
            onDismiss = { showWorkspaceSheet = false }
        )
    }

    if (showModelSheet) {
        ModelQuickPickerSheet(
            providers = uiState.providers,
            catalogModels = uiState.catalogModels,
            activeProviderId = uiState.activeSession?.providerId,
            activeModelId = uiState.activeSession?.modelId,
            hasKey = viewModel::hasApiKey,
            onSelectModel = viewModel::selectModelForActiveSession,
            onOpenConfigure = { viewModel.selectTab(MainTab.CONFIGURE) },
            onDismiss = { showModelSheet = false }
        )
    }

    if (showNewWorkspaceDialog) {
        NewWorkspaceDialog(
            onCreate = viewModel::createWorkspace,
            onDismiss = { showNewWorkspaceDialog = false }
        )
    }
}

@Composable
private fun IdeTopBar(
    uiState: PocketForgeUiState,
    onOpenWorkspaces: () -> Unit,
    onOpenModels: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, ideColors.hairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Workspace Switcher Pill
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, ideColors.hairlineBorder),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = 40.dp)
                    .clickable(onClick = onOpenWorkspaces)
                    .testTag("workspace_switcher_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderSpecial,
                        contentDescription = "Switch workspace",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = uiState.activeWorkspace?.name ?: "PocketForge",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Active Model Pill
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable(onClick = onOpenModels)
                    .testTag("model_picker_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Select AI model",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = uiState.activeSession?.modelId?.substringAfterLast('/')?.take(18) ?: "gemini-3.8-flash",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBannerBar(
    uiState: PocketForgeUiState,
    onDismiss: () -> Unit
) {
    val ideColors = LocalIdeColors.current
    val banner = uiState.banner
    AnimatedVisibility(visible = banner != null) {
        if (banner != null) {
            val bg = if (banner.isError) ideColors.diffRemoveBg else MaterialTheme.colorScheme.primaryContainer
            val fg = if (banner.isError) ideColors.diffRemoveText else MaterialTheme.colorScheme.primary
            Surface(
                color = bg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = banner.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = fg,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss notification",
                            tint = fg,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class NavItem(
    val tab: MainTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val NAV_ITEMS = listOf(
    NavItem(MainTab.AGENT, "Agent", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    NavItem(MainTab.WEB_AI, "Web AI", Icons.Filled.Language, Icons.Outlined.Language),
    NavItem(MainTab.FILES, "Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    NavItem(MainTab.TERMINAL, "Terminal", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem(MainTab.CONFIGURE, "Configure", Icons.Filled.Tune, Icons.Outlined.Tune)
)
