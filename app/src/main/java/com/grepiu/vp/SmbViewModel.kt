package com.grepiu.vp

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * SMB 브라우징 기능을 위한 ViewModel.
 * 입력 폼 상태, 연결 상태, 파일 목록을 관리함.
 * 
 * @param application 안드로이드 애플리케이션 컨텍스트.
 */
class SmbViewModel(application: Application) : AndroidViewModel(application) {
    private val smbService = SmbService()
    // 마지막 접속 정보를 저장하기 위한 SharedPreferences
    private val prefs = application.getSharedPreferences("smb_prefs", Context.MODE_PRIVATE)

    // MARK: - UI 상태 변수
    var serverIp by mutableStateOf(prefs.getString("last_ip", "") ?: "")
    var username by mutableStateOf(prefs.getString("last_user", "") ?: "")
    var password by mutableStateOf(prefs.getString("last_pass", "") ?: "")

    var isConnected by mutableStateOf(false)
        private set
    
    var items by mutableStateOf<List<SmbItem>>(emptyList())
    var currentPath by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    // MARK: - 이벤트 핸들러
    
    /**
     * IP 주소 변경 시 상태를 업데이트함.
     * 
     * @param newIp 변경된 IP 주소.
     */
    fun onIpChange(newIp: String) { serverIp = newIp }

    /**
     * 사용자 아이디 변경 시 상태를 업데이트함.
     * 
     * @param newUser 변경된 사용자 아이디.
     */
    fun onUserChange(newUser: String) { username = newUser }

    /**
     * 비밀번호 변경 시 상태를 업데이트함.
     * 
     * @param newPass 변경된 비밀번호.
     */
    fun onPassChange(newPass: String) { password = newPass }

    /**
     * SMB 서버에 접속을 시도함.
     * 성공 시 접속 정보를 저장하고 루트 파일 목록을 조회함.
     */
    fun connect() {
        errorMessage = null
        isLoading = true
        viewModelScope.launch {
            try {
                // 네트워크 지연을 고려하여 30초 타임아웃 설정
                val success = withTimeout(30000L) {
                    smbService.updateCredentials(username, password)
                }
                
                if (success) {
                    // 성공 시 접속 정보 영속화
                    prefs.edit().apply {
                        putString("last_ip", serverIp)
                        putString("last_user", username)
                        putString("last_pass", password)
                        apply()
                    }
                    
                    currentPath = "smb://$serverIp/"
                    val result = smbService.listFiles(currentPath)
                    if (result.isEmpty() && !currentPath.endsWith("/")) {
                        errorMessage = "Failed to connect or folder empty"
                        smbService.disconnect()
                    } else {
                        items = result
                        isConnected = true
                    }
                } else {
                    errorMessage = "Authentication failed"
                }
            } catch (e: TimeoutCancellationException) {
                errorMessage = "Connection timed out (30s)"
                smbService.disconnect()
            } catch (e: Exception) {
                errorMessage = "Connection error: ${e.message}"
                smbService.disconnect()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 서버 연결을 종료하고 상태를 초기화함.
     */
    fun disconnect() {
        smbService.disconnect()
        isConnected = false
        items = emptyList()
        currentPath = ""
    }

    /**
     * 하위 디렉토리로 이동하여 파일 목록을 갱신함.
     * 
     * @param item 선택된 디렉토리 아이템.
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
     * 특정 아이템에 대한 전체 인증 정보를 포함한 URI를 반환함.
     * 이 URI는 ExoPlayer가 직접 접근하는 데 사용됨.
     * 
     * @param item 선택된 파일 아이템.
     * @return 인증 정보가 포함된 Uri.
     */
    fun getFileUri(item: SmbItem): Uri {
        val smbPath = Uri.parse(item.path).path ?: ""
        return smbService.getAuthenticatedUri(serverIp, username, password, smbPath)
    }

    /**
     * 상위 디렉토리로 이동함. 최상위일 경우 연결을 해제함.
     */
    fun goBack() {
        val lastSlash = currentPath.dropLast(1).lastIndexOf('/')
        if (lastSlash > 5) { // "smb://" 이후의 경로가 있는지 확인
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
