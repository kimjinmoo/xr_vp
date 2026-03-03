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

/**
 * 앱 내에서 이동 가능한 주요 화면 목적지 정의.
 */
enum class AppDestination {
    PLAYER, SMB
}

/**
 * 앱의 진입점이 되는 메인 액티비티.
 * 전체적인 네비게이션 흐름과 2D/Spatial 모드 전환을 관리함.
 */
class MainActivity : ComponentActivity() {

    /**
     * 액티비티 생성 시 호출되어 UI를 초기화함.
     * 
     * @param savedInstanceState 저장된 인스턴스 상태.
     */
    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VPTheme {
                // 앱의 전역 상태 관리 (영상 URI 및 현재 화면)
                var videoUri by remember { mutableStateOf<Uri?>(null) }
                var currentDestination by remember { mutableStateOf(AppDestination.PLAYER) }
                
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

                // MARK: - 모드에 따른 루트 컨텐츠 분기
                if (isSpatialUiEnabled) {
                    // 공간(Spatial) UI 모드일 때의 레이아웃
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
                    // 표준 2D 모드일 때의 레이아웃
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

/**
 * SMB 서버 브라우징 화면의 메인 컨테이너.
 * 
 * @param onFileSelected 파일이 선택되었을 때의 콜백.
 * @param modifier 레이아웃 수정을 위한 Modifier.
 * @param viewModel SMB 데이터 관리를 위한 뷰모델.
 */
@Composable
fun SmbBrowserContent(
    onFileSelected: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SmbViewModel = viewModel()
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // 상단 바: 연결 상태 및 경로 표시
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

        // 중앙 영역: 연결 폼 또는 파일 목록
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

/**
 * SMB 서버 접속을 위한 입력 폼.
 * 
 * @param serverIp 서버 IP 주소.
 * @param username 사용자 아이디.
 * @param password 사용자 비밀번호.
 * @param errorMessage 오류 메시지.
 * @param isLoading 로딩 중 여부.
 * @param onIpChange IP 변경 시의 콜백.
 * @param onUserChange 사용자 아이디 변경 시의 콜백.
 * @param onPassChange 비밀번호 변경 시의 콜백.
 * @param onConnect 연결 버튼 클릭 시의 콜백.
 * @param modifier 레이아웃 수정을 위한 Modifier.
 */
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

/**
 * SMB 파일 및 폴더 목록을 표시하는 리스트.
 * 
 * @param items 표시할 아이템 목록.
 * @param isLoading 로딩 중 여부.
 * @param onItemClick 아이템 클릭 시의 콜백.
 * @param onBack 뒤로 가기 버튼 클릭 시의 콜백.
 */
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

/**
 * 공간(Spatial) UI를 위한 컨텐츠 래퍼. SpatialPanel을 사용하여 3D 공간에 배치됨.
 * 
 * @param videoUri 재생할 비디오 URI.
 * @param currentDestination 현재 네비게이션 목적지.
 * @param onRequestHomeSpaceMode 홈 공간 모드 요청 콜백.
 * @param onOpenFile 파일 열기 요청 콜백.
 * @param onDestinationChange 네비게이션 목적지 변경 콜백.
 * @param onFileSelected 파일 선택 시의 콜백.
 */
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

/**
 * 표준 2D UI를 위한 컨텐츠 래퍼.
 * 
 * @param videoUri 재생할 비디오 URI.
 * @param currentDestination 현재 네비게이션 목적지.
 * @param onRequestFullSpaceMode 전체 공간 모드 요청 콜백.
 * @param onOpenFile 파일 열기 요청 콜백.
 * @param onDestinationChange 네비게이션 목적지 변경 콜백.
 * @param onFileSelected 파일 선택 시의 콜백.
 */
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
        // 공간 모드로 전환하기 위한 버튼 (공간 캡슐 기능 활성화 시 표시)
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
 * 앱의 실제 네비게이션 구조와 메인 컨텐츠를 담고 있는 컴포저블.
 * 
 * @param videoUri 재생할 비디오 URI.
 * @param currentDestination 현재 네비게이션 목적지.
 * @param onOpenFile 파일 열기 요청 콜백.
 * @param onDestinationChange 네비게이션 목적지 변경 콜백.
 * @param onFileSelected 파일 선택 시의 콜백.
 * @param modifier 레이아웃 수정을 위한 Modifier.
 */
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
        // 선택된 네비게이션 목적지에 따른 화면 전환
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

/**
 * 전체 공간 모드(Full Space Mode)로 전환하는 아이콘 버튼.
 * 
 * @param onClick 버튼 클릭 시의 콜백.
 * @param modifier 레이아웃 수정을 위한 Modifier.
 */
@Composable
fun FullSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_full_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_full_space_mode)
        )
    }
}

/**
 * 홈 공간 모드(Home Space Mode)로 전환하는 아이콘 버튼.
 * 
 * @param onClick 버튼 클릭 시의 콜백.
 * @param modifier 레이아웃 수정을 위한 Modifier.
 */
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

/**
 * 2D 컨텐츠 화면에 대한 미리보기 컴포저블.
 */
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

/**
 * 전체 공간 모드 전환 버튼에 대한 미리보기 컴포저블.
 */
@Preview(showBackground = true)
@Composable
fun FullSpaceModeButtonPreview() {
    VPTheme {
        FullSpaceModeIconButton(onClick = {})
    }
}

/**
 * 홈 공간 모드 전환 버튼에 대한 미리보기 컴포저블.
 */
@PreviewLightDark
@Composable
fun HomeSpaceModeButtonPreview() {
    VPTheme {
        HomeSpaceModeIconButton(onClick = {})
    }
}
