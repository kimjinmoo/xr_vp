package com.grepiu.vp

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.scene
import com.grepiu.vp.ui.theme.VPTheme

/**
 * 앱 내에서 이동 가능한 주요 화면 목적지 정의.
 */
enum class AppDestination {
    PLAYER, LOCAL, SMB
}

/**
 * 앱의 진입점이 되는 메인 액티비티.
 */
class MainActivity : ComponentActivity() {

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VPTheme {
                var videoUri by remember { mutableStateOf<Uri?>(null) }
                var currentDestination by remember { mutableStateOf(AppDestination.PLAYER) }
                var isNavigationExpanded by remember { mutableStateOf(false) }
                
                var panelWidth by remember { mutableStateOf(1280.dp) }
                var panelHeight by remember { mutableStateOf(800.dp) }

                // 로컬 파일 선택을 위한 런처
                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let { 
                        videoUri = it 
                        currentDestination = AppDestination.PLAYER
                    }
                }

                val session = LocalSession.current
                val isSpatialUiEnabled = LocalSpatialCapabilities.current.isSpatialUiEnabled

                // 창 크기 조절 핸들러
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
                        MySpatialContent(
                            videoUri = videoUri,
                            currentDestination = currentDestination,
                            isNavigationExpanded = isNavigationExpanded,
                            panelWidth = panelWidth,
                            panelHeight = panelHeight,
                            onRequestHomeSpaceMode = { session?.scene?.requestHomeSpaceMode() },
                            onOpenFile = { launcher.launch("video/*") },
                            onDestinationChange = { currentDestination = it },
                            onFileSelected = { 
                                videoUri = it
                                currentDestination = AppDestination.PLAYER
                            },
                            onToggleNavigation = { isNavigationExpanded = !isNavigationExpanded },
                            onResizeWindow = onResizeWindow
                        )
                    }
                } else {
                    My2DContent(
                        videoUri = videoUri,
                        currentDestination = currentDestination,
                        isNavigationExpanded = isNavigationExpanded,
                        onRequestFullSpaceMode = { session?.scene?.requestFullSpaceMode() },
                        onOpenFile = { launcher.launch("video/*") },
                        onDestinationChange = { currentDestination = it },
                        onFileSelected = { 
                            videoUri = it
                            currentDestination = AppDestination.PLAYER
                        },
                        onToggleNavigation = { isNavigationExpanded = !isNavigationExpanded },
                        onResizeWindow = onResizeWindow
                    )
                }
            }
        }
    }
}

/**
 * 공간(Spatial) UI를 위한 컨텐츠 래퍼.
 */
@SuppressLint("RestrictedApi")
@Composable
fun MySpatialContent(
    videoUri: Uri?,
    currentDestination: AppDestination,
    isNavigationExpanded: Boolean,
    panelWidth: Dp,
    panelHeight: Dp,
    onRequestHomeSpaceMode: () -> Unit,
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onToggleNavigation: () -> Unit,
    onResizeWindow: (Int, Int) -> Unit
) {
    SpatialPanel(SubspaceModifier.width(panelWidth).height(panelHeight)) {
        Surface(shape = RoundedCornerShape(28.dp)) {
            MainContent(
                videoUri = videoUri,
                currentDestination = currentDestination,
                isNavigationExpanded = isNavigationExpanded,
                onOpenFile = onOpenFile,
                onDestinationChange = onDestinationChange,
                onFileSelected = onFileSelected,
                onToggleNavigation = onToggleNavigation,
                onResizeWindow = onResizeWindow,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 표준 2D UI를 위한 컨텐츠 래퍼.
 */
@SuppressLint("RestrictedApi")
@Composable
fun My2DContent(
    videoUri: Uri?,
    currentDestination: AppDestination,
    isNavigationExpanded: Boolean,
    onRequestFullSpaceMode: () -> Unit,
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onToggleNavigation: () -> Unit,
    onResizeWindow: (Int, Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MainContent(
            videoUri = videoUri,
            currentDestination = currentDestination,
            isNavigationExpanded = isNavigationExpanded,
            onOpenFile = onOpenFile,
            onDestinationChange = onDestinationChange,
            onFileSelected = onFileSelected,
            onToggleNavigation = onToggleNavigation,
            onResizeWindow = onResizeWindow,
            modifier = Modifier.fillMaxSize()
        )
        if (!LocalInspectionMode.current && LocalSession.current != null) {
            FullSpaceModeIconButton(
                onClick = onRequestFullSpaceMode,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
            )
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
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onToggleNavigation: () -> Unit,
    onResizeWindow: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        val sidebarWidth by animateDpAsState(if (isNavigationExpanded) 180.dp else 64.dp, label = "sidebarWidth")
        
        Column(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 상단 토글 및 브랜드 영역
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = onToggleNavigation,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(if (isNavigationExpanded) 12.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(24.dp))
                        if (isNavigationExpanded) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("VP Player", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        }
                    }
                }
            }
            
            NavigationItem(
                icon = Icons.Default.Home,
                label = "Player",
                selected = currentDestination == AppDestination.PLAYER,
                expanded = isNavigationExpanded,
                onClick = { onDestinationChange(AppDestination.PLAYER) }
            )
            
            NavigationItem(
                icon = Icons.Default.Add,
                label = "Local",
                selected = currentDestination == AppDestination.LOCAL,
                expanded = isNavigationExpanded,
                onClick = { onDestinationChange(AppDestination.LOCAL) }
            )
            
            NavigationItem(
                icon = Icons.Default.Storage,
                label = "SMB",
                selected = currentDestination == AppDestination.SMB,
                expanded = isNavigationExpanded,
                onClick = { onDestinationChange(AppDestination.SMB) }
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentDestination) {
                AppDestination.PLAYER -> {
                    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        VideoPlayer(
                            videoUri = videoUri,
                            onResizeWindowRequest = onResizeWindow,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                AppDestination.LOCAL -> {
                    LocalBrowserContent(onOpenFile = onOpenFile, modifier = Modifier.fillMaxSize())
                }
                AppDestination.SMB -> {
                    SmbBrowserContent(onFileSelected = onFileSelected, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * 로컬 파일 선택을 위한 메인 컨텐츠 화면.
 */
@Composable
fun LocalBrowserContent(
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.VideoFile,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Play Local Video",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Select a video file from your device to start watching.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onOpenFile,
            modifier = Modifier.height(56.dp).width(200.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open File")
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
            if (expanded) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/**
 * SMB 서버 브라우징 화면.
 */
@Composable
fun SmbBrowserContent(
    onFileSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SmbViewModel = viewModel()
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (!viewModel.isConnected) "Connect to SMB Server"
                else "Browsing: ${viewModel.currentPath.takeLast(30)}",
                style = MaterialTheme.typography.headlineSmall
            )
            if (viewModel.isConnected) {
                TextButton(onClick = { viewModel.disconnect() }) {
                    Text("Disconnect")
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (!viewModel.isConnected) {
                SmbConnectForm(
                    serverIp = viewModel.serverIp,
                    username = viewModel.username,
                    password = viewModel.password,
                    errorMessage = viewModel.errorMessage,
                    isLoading = viewModel.isLoading,
                    onIpChange = viewModel::onIpChange,
                    onUserChange = viewModel::onUserChange,
                    onPassChange = viewModel::onPassChange,
                    onConnect = viewModel::connect,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                SmbFileList(
                    items = viewModel.items,
                    isLoading = viewModel.isLoading,
                    onItemClick = { item ->
                        if (item.isDirectory) {
                            viewModel.navigateTo(item)
                        } else {
                            onFileSelected(viewModel.getFileUri(item))
                        }
                    },
                    onBack = { viewModel.goBack() }
                )
            }
        }
    }
}

@Composable
fun SmbConnectForm(
    serverIp: String,
    username: String,
    password: String,
    errorMessage: String?,
    isLoading: Boolean,
    onIpChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPassChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(0.6f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(value = serverIp, onValueChange = onIpChange, label = { Text("Server IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = username, onValueChange = onUserChange, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = onPassChange, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onConnect, 
            modifier = Modifier.fillMaxWidth(),
            enabled = serverIp.isNotBlank() && !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            else Text("Connect")
        }
    }
}

@Composable
fun SmbFileList(
    items: List<SmbItem>,
    isLoading: Boolean,
    onItemClick: (SmbItem) -> Unit,
    onBack: () -> Unit
) {
    Column {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    leadingContent = { Icon(if (item.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow, null) },
                    modifier = Modifier.clickable { onItemClick(item) }
                )
            }
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Back")
        }
    }
}

@Composable
fun FullSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_full_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_full_space_mode)
        )
    }
}

@Composable
fun HomeSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_home_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_home_space_mode)
        )
    }
}

// MARK: - Previews

@PreviewLightDark
@Composable
fun My2dContentPreview() {
    VPTheme {
        My2DContent(
            videoUri = null,
            currentDestination = AppDestination.PLAYER,
            isNavigationExpanded = false,
            onRequestFullSpaceMode = {},
            onOpenFile = {},
            onDestinationChange = {},
            onFileSelected = {},
            onToggleNavigation = {},
            onResizeWindow = { _, _ -> }
        )
    }
}
