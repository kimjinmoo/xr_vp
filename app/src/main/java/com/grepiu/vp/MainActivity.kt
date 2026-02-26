package com.grepiu.vp

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
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

enum class AppDestination {
    PLAYER, SMB
}

class MainActivity : ComponentActivity() {

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VPTheme {
                var videoUri by remember { mutableStateOf<Uri?>(null) }
                var currentDestination by remember { mutableStateOf(AppDestination.PLAYER) }
                
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

                if (isSpatialUiEnabled) {
                    Subspace {
                        MySpatialContent(
                            videoUri = videoUri,
                            currentDestination = currentDestination,
                            onRequestHomeSpaceMode = { session?.scene?.requestHomeSpaceMode() },
                            onOpenFile = { launcher.launch("video/*") },
                            onDestinationChange = { currentDestination = it },
                            onFileSelected = { 
                                videoUri = it
                                currentDestination = AppDestination.PLAYER
                            }
                        )
                    }
                } else {
                    My2DContent(
                        videoUri = videoUri,
                        currentDestination = currentDestination,
                        onRequestFullSpaceMode = { session?.scene?.requestFullSpaceMode() },
                        onOpenFile = { launcher.launch("video/*") },
                        onDestinationChange = { currentDestination = it },
                        onFileSelected = { 
                            videoUri = it
                            currentDestination = AppDestination.PLAYER
                        }
                    )
                }
            }
        }
    }
}

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

@SuppressLint("RestrictedApi")
@Composable
fun MySpatialContent(
    videoUri: Uri?,
    currentDestination: AppDestination,
    onRequestHomeSpaceMode: () -> Unit,
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    SpatialPanel(SubspaceModifier.width(1280.dp).height(800.dp)) {
        Surface(shape = RoundedCornerShape(28.dp)) {
            MainContent(
                videoUri = videoUri,
                currentDestination = currentDestination,
                onOpenFile = onOpenFile,
                onDestinationChange = onDestinationChange,
                onFileSelected = onFileSelected,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun My2DContent(
    videoUri: Uri?,
    currentDestination: AppDestination,
    onRequestFullSpaceMode: () -> Unit,
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MainContent(
            videoUri = videoUri,
            currentDestination = currentDestination,
            onOpenFile = onOpenFile,
            onDestinationChange = onDestinationChange,
            onFileSelected = onFileSelected,
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

@Composable
fun MainContent(
    videoUri: Uri?,
    currentDestination: AppDestination,
    onOpenFile: () -> Unit,
    onDestinationChange: (AppDestination) -> Unit,
    onFileSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            item(
                selected = currentDestination == AppDestination.PLAYER,
                onClick = { onDestinationChange(AppDestination.PLAYER) },
                icon = { Icon(Icons.Default.Home, null) },
                label = { Text("Player") }
            )
            item(
                selected = false,
                onClick = onOpenFile,
                icon = { Icon(Icons.Default.Add, null) },
                label = { Text("Local") }
            )
            item(
                selected = currentDestination == AppDestination.SMB,
                onClick = { onDestinationChange(AppDestination.SMB) },
                icon = { Icon(Icons.Default.Storage, null) },
                label = { Text("SMB") }
            )
        }
    ) {
        when (currentDestination) {
            AppDestination.PLAYER -> {
                VideoPlayer(
                    videoUri = videoUri,
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppDestination.SMB -> {
                SmbBrowserContent(
                    onFileSelected = onFileSelected,
                    modifier = Modifier.fillMaxSize()
                )
            }
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

@PreviewLightDark
@Composable
fun My2dContentPreview() {
    VPTheme {
        My2DContent(
            videoUri = null,
            currentDestination = AppDestination.PLAYER,
            onRequestFullSpaceMode = {},
            onOpenFile = {},
            onDestinationChange = {},
            onFileSelected = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FullSpaceModeButtonPreview() {
    VPTheme {
        FullSpaceModeIconButton(onClick = {})
    }
}

@PreviewLightDark
@Composable
fun HomeSpaceModeButtonPreview() {
    VPTheme {
        HomeSpaceModeIconButton(onClick = {})
    }
}
