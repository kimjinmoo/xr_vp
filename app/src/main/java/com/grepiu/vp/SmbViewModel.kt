package com.grepiu.vp

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address

/**
 * SMB 브라우징 및 서버 관리를 담당하는 ViewModel.
 * 서버 접속 정보 저장, 파일 목록 조회, 탐색 상태 및 자동 스캔 기능을 관리함.
 * 
 * @param application 안드로이드 애플리케이션 컨텍스트.
 */
class SmbViewModel(application: Application) : AndroidViewModel(application) {
    /** SMB 네트워크 작업을 수행하는 서비스 객체. */
    private val smbService = SmbService()
    
    /** 마지막 접속 정보 및 서버 목록을 저장하기 위한 SharedPreferences. */
    private val prefs = application.getSharedPreferences("smb_prefs", Context.MODE_PRIVATE)

    // MARK: - UI 상태 변수 (입력 폼)
    
    /** 현재 입력된 서버 IP 주소. */
    var serverIp by mutableStateOf("")
    
    /** 현재 입력된 사용자 아이디. */
    var username by mutableStateOf("")
    
    /** 현재 입력된 사용자 비밀번호. */
    var password by mutableStateOf("")
    
    /** 현재 입력된 서버의 별칭. */
    var serverName by mutableStateOf("")
    
    /** 익명 접속 모드 활성화 여부. */
    var isAnonymous by mutableStateOf(false)

    // MARK: - UI 상태 변수 (연결 및 브라우징)

    /** 서버 연결 성공 여부. */
    var isConnected by mutableStateOf(false)
        private set
    
    /** 현재 경로의 파일 및 폴더 목록. */
    var items by mutableStateOf<List<SmbItem>>(emptyList())
    
    /** 현재 탐색 중인 SMB URI 경로. */
    var currentPath by mutableStateOf("")
    
    /** 네트워크 로딩 중인지 여부. */
    var isLoading by mutableStateOf(false)
    
    /** UI에 표시될 에러 메시지. */
    var errorMessage by mutableStateOf<String?>(null)

    /** 저장된 서버 정보 목록. */
    var savedServers by mutableStateOf<List<SmbServer>>(emptyList())
        private set

    /** 자동 스캔으로 발견된 서버 목록. */
    var discoveredServers by mutableStateOf<List<SmbServer>>(emptyList())
        private set

    /** 서버 연결 폼 표시 여부. */
    var showForm by mutableStateOf(false)

    /** 현재 정보 수정(Edit) 모드인지 여부. */
    var isEditMode by mutableStateOf(false)
    
    /** 현재 수정 중인 서버 객체. */
    var editingServer: SmbServer? = null

    /** 네트워크 스캔 중인지 여부. */
    var isScanning by mutableStateOf(false)
        private set

    init {
        loadSavedServers()
        // 저장된 서버가 없으면 연결 폼을 기본으로 보여줌
        if (savedServers.isEmpty()) showForm = true
    }

    /**
     * 저장소에서 등록된 서버 목록을 불러옴.
     */
    private fun loadSavedServers() {
        val serverCount = prefs.getInt("server_count", 0)
        val servers = mutableListOf<SmbServer>()
        for (i in 0 until serverCount) {
            val name = prefs.getString("server_name_$i", "") ?: ""
            val ip = prefs.getString("server_ip_$i", "") ?: ""
            val user = prefs.getString("server_user_$i", "") ?: ""
            val pass = prefs.getString("server_pass_$i", "") ?: ""
            val anonymous = prefs.getBoolean("server_anon_$i", false)
            if (ip.isNotBlank()) {
                servers.add(SmbServer(name, ip, user, pass, anonymous))
            }
        }
        savedServers = servers
    }

    /**
     * 서버 목록을 영구 저장소에 저장함.
     * 
     * @param servers 저장할 서버 목록.
     */
    private fun saveServersToPrefs(servers: List<SmbServer>) {
        prefs.edit().apply {
            clear() // 기존 인덱스 정리
            putInt("server_count", servers.size)
            servers.forEachIndexed { i, server ->
                putString("server_name_$i", server.name)
                putString("server_ip_$i", server.ip)
                putString("server_user_$i", server.user)
                putString("server_pass_$i", server.pass)
                putBoolean("server_anon_$i", server.isAnonymous)
            }
            apply()
        }
        savedServers = servers
    }

    /**
     * 현재 기기의 로컬 IP 주소를 가져옴 (WiFi/Ethernet 대응).
     */
    private fun getLocalIpAddress(): String? {
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(activeNetwork)
        
        linkProperties?.linkAddresses?.forEach { linkAddress ->
            val addr = linkAddress.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress
            }
        }
        return null
    }

    /**
     * 현재 로컬 네트워크의 서브넷을 자동으로 감지하여 SMB 서버를 병렬 스캔함.
     */
    fun startNetworkScan() {
        val ipString = getLocalIpAddress() ?: return
        val subnet = ipString.substringBeforeLast(".")

        isScanning = true
        errorMessage = null
        viewModelScope.launch {
            try {
                // 병렬 스캔 시작 (SmbService에서 445 포트 체크 수행)
                discoveredServers = smbService.scanNetwork(subnet)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * 스캔된 서버 또는 폼에 입력된 정보를 서버 목록에 추가하거나 업데이트함.
     */
    fun addCurrentServer() {
        if (serverIp.isBlank()) return
        val newName = if (serverName.isBlank()) serverIp else serverName
        val newServer = SmbServer(newName, serverIp, username, password, isAnonymous)
        
        val updatedList = if (isEditMode && editingServer != null) {
            // 수정 모드: 해당 객체 교체
            savedServers.map { if (it == editingServer) newServer else it }
        } else {
            // 추가 모드: 동일 IP 제거 후 새 객체 추가
            savedServers.filter { it.ip != serverIp } + newServer
        }
        
        saveServersToPrefs(updatedList)
        resetForm()
    }

    /**
     * 특정 서버 정보를 수정하기 위해 폼으로 데이터를 로드함.
     * 
     * @param server 수정할 서버 정보.
     */
    fun editServer(server: SmbServer) {
        serverIp = server.ip
        username = server.user
        password = server.pass
        serverName = server.name
        isAnonymous = server.isAnonymous
        editingServer = server
        isEditMode = true
        showForm = true
    }

    /**
     * 입력 폼을 초기화하고 숨김 처리함.
     */
    fun resetForm() {
        serverIp = ""
        username = ""
        password = ""
        serverName = ""
        isAnonymous = false
        editingServer = null
        isEditMode = false
        showForm = false
        discoveredServers = emptyList() // 스캔 결과 초기화
    }

    /**
     * 서버 목록에서 특정 서버를 삭제함.
     * 
     * @param server 삭제할 서버 정보.
     */
    fun removeServer(server: SmbServer) {
        val updatedList = savedServers.filter { it != server }
        saveServersToPrefs(updatedList)
    }

    /**
     * 저장된 서버 또는 스캔된 서버를 선택하여 즉시 연결을 시도함.
     * 
     * @param server 연결할 서버 정보.
     * @param strings 현지화된 에러 메시지 세트.
     */
    fun selectServer(server: SmbServer, strings: UiStrings?) {
        serverIp = server.ip
        username = server.user
        password = server.pass
        serverName = server.name
        isAnonymous = server.isAnonymous
        connect(strings)
    }

    // MARK: - 이벤트 핸들러
    
    fun onIpChange(newIp: String) { serverIp = newIp }
    fun onUserChange(newUser: String) { username = newUser }
    fun onPassChange(newPass: String) { password = newPass }
    fun onNameChange(newName: String) { serverName = newName }
    fun onAnonymousChange(anon: Boolean) { isAnonymous = anon }

    /**
     * SMB 서버에 접속을 시도함.
     * 
     * @param strings 현지화된 에러 메시지 세트. null일 경우 기본 메시지 사용.
     */
    fun connect(strings: UiStrings?) {
        if (serverIp.isBlank()) {
            errorMessage = strings?.errorIpRequired ?: "IP Address is required"
            return
        }
        errorMessage = null
        isLoading = true
        viewModelScope.launch {
            try {
                // 초고비트레이트 대응을 위해 30초의 넉넉한 연결 타임아웃 부여
                val success = withTimeout(30000L) {
                    if (isAnonymous) {
                        smbService.updateAnonymousCredentials()
                    } else {
                        smbService.updateCredentials(username, password)
                    }
                }
                
                if (success) {
                    currentPath = "smb://$serverIp/"
                    val result = smbService.listFiles(currentPath)
                    if (result.isEmpty() && !currentPath.endsWith("/")) {
                        errorMessage = strings?.errorEmptyFolder ?: "Failed to connect or folder empty"
                        smbService.disconnect()
                    } else {
                        items = result
                        isConnected = true
                        addCurrentServer()
                    }
                } else {
                    errorMessage = strings?.errorAuthFailed ?: "Authentication failed"
                }
            } catch (e: TimeoutCancellationException) {
                errorMessage = strings?.errorConnectionTimeout ?: "Connection timed out (30s)"
                smbService.disconnect()
            } catch (e: Exception) {
                errorMessage = (strings?.errorConnectionError ?: "Connection error: ") + (e.message ?: "")
                smbService.disconnect()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 현재 서버 연결을 명시적으로 종료함.
     */
    fun disconnect() {
        viewModelScope.launch {
            isLoading = true
            try {
                smbService.disconnect()
                items = emptyList()
                currentPath = ""
                isConnected = false
                errorMessage = null
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 하위 폴더로 진입하여 파일 목록을 갱신함.
     * 
     * @param item 선택된 디렉토리 항목.
     */
    fun navigateTo(item: SmbItem) {
        if (item.isDirectory) {
            currentPath = item.path
            isLoading = true
            viewModelScope.launch {
                items = smbService.listFiles(currentPath)
                isLoading = false
            }
        }
    }
    
    /**
     * 선택된 아이템의 스트리밍용 인증 URI를 생성함.
     * 
     * @param item 선택된 파일 항목.
     * @return ExoPlayer에서 사용 가능한 인증 정보 포함 Uri.
     */
    fun getFileUri(item: SmbItem): Uri {
        val prefix = "smb://$serverIp"
        val smbPath = if (item.path.startsWith(prefix)) {
            item.path.substring(prefix.length)
        } else {
            val uri = Uri.parse(item.path)
            (uri.path ?: "") + (uri.fragment?.let { "#$it" } ?: "")
        }
        val (finalUser, finalPass) = if (isAnonymous) "" to "" else username to password
        return smbService.getAuthenticatedUri(serverIp, finalUser, finalPass, smbPath)
    }

    /**
     * 상위 폴더로 이동함. 최상위일 경우 연결 해제.
     */
    fun goBack() {
        val lastSlash = currentPath.dropLast(1).lastIndexOf('/')
        if (lastSlash > 5) {
            currentPath = currentPath.substring(0, lastSlash + 1)
            isLoading = true
            viewModelScope.launch {
                items = smbService.listFiles(currentPath)
                isLoading = false
            }
        } else {
            disconnect()
        }
    }
}
