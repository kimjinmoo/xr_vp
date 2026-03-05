package com.grepiu.vp

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Dvr
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import com.grepiu.vp.ui.theme.VPTheme

/**
 * 앱 내에서 이동 가능한 주요 화면 목적지 정의.
 */
enum class AppDestination {
    PLAYER, LOCAL, SMB, DLNA, LICENSES, SETTINGS
}

/**
 * 지원하는 언어 정의.
 */
enum class AppLanguage {
    KOREAN, ENGLISH
}

/**
 * 지원하는 테마 모드 정의.
 */
enum class AppThemeMode {
    AUTO, LIGHT, DARK
}

/**
 * 앱의 진입점이 되는 메인 액티비티.
 * 몰입 모드 관련 로직을 제거하고 패널 기반 UI에 집중함.
 */
class MainActivity : ComponentActivity() {

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsManager = SettingsManager(this)

        setContent {
            var currentLanguage by remember { mutableStateOf(settingsManager.getLanguage()) }
            var currentThemeMode by remember { mutableStateOf(settingsManager.getThemeMode()) }
            
            val darkTheme = when (currentThemeMode) {
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
                AppThemeMode.AUTO -> isSystemInDarkTheme()
            }

            VPTheme(darkTheme = darkTheme) {
                var videoUri by remember { mutableStateOf<Uri?>(null) }
                var currentDestination by remember { mutableStateOf(AppDestination.PLAYER) }
                var isNavigationExpanded by remember { mutableStateOf(false) }
                var isFullscreen by remember { mutableStateOf(false) }
                
                val strings = if (currentLanguage == AppLanguage.KOREAN) KoreanStrings else EnglishStrings
                
                var panelWidth by remember { mutableStateOf(1280.dp) }
                var panelHeight by remember { mutableStateOf(800.dp) }

                val view = LocalView.current
                val window = (view.context as? android.app.Activity)?.window
                
                // 시스템 바 제어 (풀스크린 모드 대응)
                LaunchedEffect(isFullscreen) {
                    window?.let {
                        val controller = WindowCompat.getInsetsController(it, view)
                        if (isFullscreen) {
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        } else {
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let { 
                        videoUri = it 
                        currentDestination = AppDestination.PLAYER
                    }
                }

                val isSpatialUiEnabled = LocalSpatialCapabilities.current.isSpatialUiEnabled

                val onResizeWindow: (Int, Int) -> Unit = remember {
                    { width, height ->
                        if (width > 0 && height > 0) {
                            val aspectRatio = width.toFloat() / height.toFloat()
                            panelWidth = 1280.dp
                            panelHeight = (1280f / aspectRatio).dp
                        }
                    }
                }

                if (isSpatialUiEnabled) {
                    Subspace {
                        SpatialPanel(SubspaceModifier.width(panelWidth).height(panelHeight)) {
                            Surface(shape = RoundedCornerShape(28.dp)) {
                                MainContent(
                                    videoUri = videoUri,
                                    currentDestination = currentDestination,
                                    isNavigationExpanded = isNavigationExpanded,
                                    isFullscreen = false,
                                    strings = strings,
                                    currentLanguage = currentLanguage,
                                    onLanguageChange = { 
                                        currentLanguage = it
                                        settingsManager.setLanguage(it)
                                    },
                                    currentThemeMode = currentThemeMode,
                                    onThemeChange = { 
                                        currentThemeMode = it
                                        settingsManager.setThemeMode(it)
                                    },
                                    onToggleFullscreen = {},
                                    onOpenFile = { launcher.launch("video/*") },
                                    onDestinationChange = { currentDestination = it },
                                    onFileSelected = { 
                                        videoUri = it
                                        currentDestination = AppDestination.PLAYER
                                    },
                                    onToggleNavigation = { isNavigationExpanded = !isNavigationExpanded },
                                    onResizeWindow = onResizeWindow,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainContent(
                            videoUri = videoUri,
                            currentDestination = currentDestination,
                            isNavigationExpanded = isNavigationExpanded,
                            isFullscreen = isFullscreen,
                            strings = strings,
                            currentLanguage = currentLanguage,
                            onLanguageChange = { 
                                currentLanguage = it
                                settingsManager.setLanguage(it)
                            },
                            currentThemeMode = currentThemeMode,
                            onThemeChange = { 
                                currentThemeMode = it
                                settingsManager.setThemeMode(it)
                            },
                            onToggleFullscreen = { isFullscreen = !isFullscreen },
                            onOpenFile = { launcher.launch("video/*") },
                            onDestinationChange = { currentDestination = it },
                            onFileSelected = { 
                                videoUri = it
                                currentDestination = AppDestination.PLAYER
                            },
                            onToggleNavigation = { isNavigationExpanded = !isNavigationExpanded },
                            onResizeWindow = onResizeWindow,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 앱의 실제 네비게이션 구조와 메인 컨텐츠.
 */
@Composable
fun MainContent(
    videoUri: Uri?,
    currentDestination: AppDestination,
    isNavigationExpanded: Boolean,
    isFullscreen: Boolean,
    strings: UiStrings,
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    currentThemeMode: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onToggleNavigation: () -> Unit,
    onResizeWindow: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    smbViewModel: SmbViewModel = viewModel(),
    dlnaViewModel: DlnaViewModel = viewModel(),
    playerViewModel: PlayerViewModel = viewModel()
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .then(if (!isFullscreen) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
    ) {
        if (!isFullscreen) {
            val sidebarWidth by animateDpAsState(if (isNavigationExpanded) 200.dp else 80.dp, label = "sidebarWidth")
            
            Column(
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onToggleNavigation,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isNavigationExpanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                            contentDescription = if (isNavigationExpanded) "Collapse" else "Expand",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                NavigationItem(
                    icon = Icons.Default.PlayArrow,
                    label = strings.menuPlayer,
                    selected = currentDestination == AppDestination.PLAYER,
                    expanded = isNavigationExpanded,
                    onClick = { onDestinationChange(AppDestination.PLAYER) }
                )
                
                NavigationItem(
                    icon = Icons.Default.Add,
                    label = strings.menuLocal,
                    selected = currentDestination == AppDestination.LOCAL,
                    expanded = isNavigationExpanded,
                    onClick = { onDestinationChange(AppDestination.LOCAL) }
                )
                
                NavigationItem(
                    icon = Icons.Default.Storage,
                    label = strings.menuSmb,
                    selected = currentDestination == AppDestination.SMB,
                    expanded = isNavigationExpanded,
                    onClick = { onDestinationChange(AppDestination.SMB) }
                )

                NavigationItem(
                    icon = Icons.AutoMirrored.Filled.Dvr,
                    label = strings.menuDlna,
                    selected = currentDestination == AppDestination.DLNA,
                    expanded = isNavigationExpanded,
                    onClick = { onDestinationChange(AppDestination.DLNA) }
                )

                Spacer(modifier = Modifier.weight(1f))

                NavigationItem(
                    icon = Icons.Default.Settings,
                    label = strings.menuSettings,
                    selected = currentDestination == AppDestination.SETTINGS,
                    expanded = isNavigationExpanded,
                    onClick = { onDestinationChange(AppDestination.SETTINGS) }
                )

                NavigationItem(
                    icon = Icons.Default.Info,
                    label = strings.menuLicenses,
                    selected = currentDestination == AppDestination.LICENSES,
                    expanded = isNavigationExpanded,
                    onClick = { onDestinationChange(AppDestination.LICENSES) }
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentDestination) {
                AppDestination.PLAYER -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        VideoPlayer(
                            videoUri = videoUri,
                            strings = strings,
                            onResizeWindowRequest = onResizeWindow,
                            onToggleFullscreen = onToggleFullscreen,
                            isFullscreen = isFullscreen,
                            modifier = Modifier.fillMaxSize(),
                            playerViewModel = playerViewModel
                        )
                    }
                }
                AppDestination.LOCAL -> {
                    LocalBrowserContent(strings = strings, onOpenFile = onOpenFile, modifier = Modifier.fillMaxSize())
                }
                AppDestination.SMB -> {
                    SmbBrowserContent(
                        strings = strings,
                        onFileSelected = onFileSelected, 
                        modifier = Modifier.fillMaxSize(),
                        viewModel = smbViewModel
                    )
                }
                AppDestination.DLNA -> {
                    DlnaBrowserContent(
                        strings = strings,
                        onFileSelected = onFileSelected, 
                        modifier = Modifier.fillMaxSize(),
                        viewModel = dlnaViewModel
                    )
                }
                AppDestination.LICENSES -> {
                    LicenseContent(strings = strings, modifier = Modifier.fillMaxSize())
                }
                AppDestination.SETTINGS -> {
                    SettingsContent(
                        strings = strings,
                        currentLanguage = currentLanguage,
                        onLanguageChange = onLanguageChange,
                        currentThemeMode = currentThemeMode,
                        onThemeChange = onThemeChange,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsContent(
    strings: UiStrings,
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    currentThemeMode: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    val versionDisplayName = packageInfo?.versionName ?: "1.0.0"

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            text = strings.settings,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.language,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onLanguageChange(AppLanguage.KOREAN) }) {
                            RadioButton(selected = currentLanguage == AppLanguage.KOREAN, onClick = { onLanguageChange(AppLanguage.KOREAN) })
                            Text(strings.korean, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onLanguageChange(AppLanguage.ENGLISH) }) {
                            RadioButton(selected = currentLanguage == AppLanguage.ENGLISH, onClick = { onLanguageChange(AppLanguage.ENGLISH) })
                            Text(strings.english, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.theme,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onThemeChange(AppThemeMode.AUTO) }) {
                            RadioButton(selected = currentThemeMode == AppThemeMode.AUTO, onClick = { onThemeChange(AppThemeMode.AUTO) })
                            Text(strings.autoMode, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onThemeChange(AppThemeMode.LIGHT) }) {
                            RadioButton(selected = currentThemeMode == AppThemeMode.LIGHT, onClick = { onThemeChange(AppThemeMode.LIGHT) })
                            Text(strings.lightMode, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onThemeChange(AppThemeMode.DARK) }) {
                            RadioButton(selected = currentThemeMode == AppThemeMode.DARK, onClick = { onThemeChange(AppThemeMode.DARK) })
                            Text(strings.darkMode, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.applicationInfo,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(strings.appVersion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(versionDisplayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                            
                            Button(
                                onClick = { 
                                    Toast.makeText(context, strings.upToDate, Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(strings.checkForUpdates)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LicenseContent(strings: UiStrings, modifier: Modifier = Modifier) {
    val licenses = listOf(
        Triple(
            "AndroidX Media3 (ExoPlayer)", 
            "Apache License 2.0",
            "This software is provided under the Apache 2.0 license. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0"
        ),
        Triple(
            "jcifs-ng", 
            "GNU Lesser General Public License v2.1",
            "This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License. Source code is available upon request or at the project's official repository."
        ),
        Triple(
            "jUPnP", 
            "GNU Lesser General Public License v2.1",
            "This project is a fork of Cling and is licensed under LGPL v2.1. The source code for this library can be found at the official OpenHAB GitHub repository."
        ),
        Triple(
            "Jetpack Compose / Material 3", 
            "Apache License 2.0",
            "Copyright (C) 2022 The Android Open Source Project. Licensed under the Apache License, Version 2.0."
        ),
        Triple(
            "Android XR SceneCore", 
            "Android SDK License",
            "Usage of this SDK is governed by the Android Software Development Kit License Agreement."
        )
    )

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = strings.menuLicenses,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items = licenses) { (name, license, detail) ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = license,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmbBrowserContent(
    strings: UiStrings,
    onFileSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SmbViewModel
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (!viewModel.isConnected) strings.smbNetwork else strings.fileExplorer,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                    )
                if (viewModel.isConnected) {
                    Text(
                        text = viewModel.currentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (viewModel.isConnected) {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back, tint = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { viewModel.disconnect() }) {
                        Text(strings.disconnect, color = MaterialTheme.colorScheme.error)
                    }
                } else if (viewModel.showForm && viewModel.savedServers.isNotEmpty()) {
                    TextButton(onClick = { viewModel.showForm = false }) {
                        Text(strings.savedServers)
                    }
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!viewModel.isConnected) {
                if (viewModel.showForm) {
                    SmbConnectForm(
                        strings = strings,
                        serverName = viewModel.serverName,
                        serverIp = viewModel.serverIp,
                        username = viewModel.username,
                        password = viewModel.password,
                        errorMessage = viewModel.errorMessage,
                        isLoading = viewModel.isLoading,
                        onNameChange = viewModel::onNameChange,
                        onIpChange = viewModel::onIpChange,
                        onUserChange = viewModel::onUserChange,
                        onPassChange = viewModel::onPassChange,
                        onConnect = viewModel::connect,
                        onSave = viewModel::addCurrentServer,
                        modifier = Modifier.fillMaxWidth(0.7f).zIndex(1f)
                    )
                } else {
                    SmbServerList(
                        strings = strings,
                        servers = viewModel.savedServers,
                        onServerClick = { viewModel.selectServer(it) },
                        onDeleteClick = { viewModel.removeServer(it) },
                        onAddNew = { viewModel.showForm = true }
                    )
                }
            } else {
                SmbFileList(
                    strings = strings,
                    items = viewModel.items,
                    isLoading = viewModel.isLoading,
                    onItemClick = { item ->
                        if (item.isDirectory) {
                            viewModel.navigateTo(item)
                        } else {
                            onFileSelected(viewModel.getFileUri(item))
                        }
                    }
                )
            }

            // 전체 화면 로딩 오버레이 추가 (연결 중이거나 브라우징 중일 때)
            if (viewModel.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable(enabled = true, onClick = {}), // 터치 차단
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun SmbServerList(
    strings: UiStrings,
    servers: List<SmbServer>,
    onServerClick: (SmbServer) -> Unit,
    onDeleteClick: (SmbServer) -> Unit,
    onAddNew: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.savedServers, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Button(onClick = onAddNew, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.addNew)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items = servers) { server ->
                OutlinedCard(
                    onClick = { onServerClick(server) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(server.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onDeleteClick(server) }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmbConnectForm(
    strings: UiStrings,
    serverName: String,
    serverIp: String,
    username: String,
    password: String,
    errorMessage: String?,
    isLoading: Boolean,
    onNameChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPassChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                strings.connectToServer,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedTextField(
                value = serverName,
                onValueChange = onNameChange,
                label = { Text(strings.serverNameLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = serverIp,
                onValueChange = onIpChange,
                label = { Text(strings.serverIp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUserChange,
                    label = { Text(strings.user) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassChange,
                    label = { Text(strings.password) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            if (errorMessage != null) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = serverIp.isNotBlank()
                ) {
                    Text(strings.save)
                }
                Button(
                    onClick = onConnect, 
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = serverIp.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text(strings.connectNow)
                }
            }
        }
    }
}

@Composable
fun SmbFileList(
    strings: UiStrings,
    items: List<SmbItem>,
    isLoading: Boolean,
    onItemClick: (SmbItem) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
        }
        
        if (items.isEmpty() && !isLoading) {
            Text(strings.noVideosFound, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(items = items) { item ->
                var lastClickTime by remember { mutableLongStateOf(0L) }
                Surface(
                    onClick = { 
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime > 500L) {
                            lastClickTime = currentTime
                            onItemClick(item)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = if (item.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow
                        val iconColor = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(iconColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, null, tint = iconColor, modifier = Modifier.size(28.dp))
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (item.isDirectory) strings.folder else strings.videoFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 64.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun DlnaBrowserContent(
    strings: UiStrings,
    onFileSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DlnaViewModel
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (viewModel.currentDevice == null) strings.mediaServers else strings.dlnaExplorer,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                if (viewModel.currentDevice != null) {
                    Text(
                        text = viewModel.currentDevice?.name ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (viewModel.currentDevice != null) {
                IconButton(onClick = { viewModel.goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.back, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (viewModel.currentDevice == null) {
                DlnaDeviceList(
                    strings = strings,
                    devices = viewModel.devices,
                    onDeviceClick = { viewModel.selectDevice(it) }
                )
            } else {
                DlnaItemList(
                    strings = strings,
                    items = viewModel.items,
                    isLoading = viewModel.isLoading,
                    onItemClick = { item ->
                        if (item.isContainer) {
                            viewModel.onItemClick(item)
                        } else {
                            item.uri?.let { onFileSelected(it) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun DlnaDeviceList(
    strings: UiStrings,
    devices: List<DlnaDevice>,
    onDeviceClick: (DlnaDevice) -> Unit
) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(strings.searchServers, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = devices) { device ->
                OutlinedCard(
                    onClick = { onDeviceClick(device) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(device.udn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DlnaItemList(
    strings: UiStrings,
    items: List<DlnaItem>,
    isLoading: Boolean,
    onItemClick: (DlnaItem) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Alignment.Center.run { Modifier.align(this) }, color = MaterialTheme.colorScheme.primary)
        }
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = items) { item ->
                var lastClickTime by remember { mutableLongStateOf(0L) }
                Surface(
                    onClick = { 
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime > 500L) {
                            lastClickTime = currentTime
                            onItemClick(item)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = if (item.isContainer) Icons.Default.Folder else Icons.Default.PlayArrow
                        val iconColor = if (item.isContainer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(iconColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, null, tint = iconColor, modifier = Modifier.size(28.dp))
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = if (item.isContainer) strings.folder else strings.mediaItem,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 64.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun LocalBrowserContent(
    strings: UiStrings,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onOpenFile,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.height(56.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.openFile)
        }
    }
}

@Composable
fun NavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
            if (expanded) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = contentColor)
            }
        }
    }
}
