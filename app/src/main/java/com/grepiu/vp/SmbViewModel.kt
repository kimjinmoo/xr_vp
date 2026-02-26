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

class SmbViewModel(application: Application) : AndroidViewModel(application) {
    private val smbService = SmbService()
    private val prefs = application.getSharedPreferences("smb_prefs", Context.MODE_PRIVATE)

    var serverIp by mutableStateOf(prefs.getString("last_ip", "") ?: "")
    var username by mutableStateOf(prefs.getString("last_user", "") ?: "")
    var password by mutableStateOf(prefs.getString("last_pass", "") ?: "")

    var isConnected by mutableStateOf(false)
        private set
    
    var items by mutableStateOf<List<SmbItem>>(emptyList())
    var currentPath by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    fun onIpChange(newIp: String) { serverIp = newIp }
    fun onUserChange(newUser: String) { username = newUser }
    fun onPassChange(newPass: String) { password = newPass }

    fun connect() {
        errorMessage = null
        isLoading = true
        viewModelScope.launch {
            try {
                val success = withTimeout(30000L) { // 30 seconds timeout
                    smbService.updateCredentials(username, password)
                }
                
                if (success) {
                    // Save credentials
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

    fun disconnect() {
        smbService.disconnect()
        isConnected = false
        items = emptyList()
        currentPath = ""
    }

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
    
    fun getFileUri(item: SmbItem): Uri {
        // SMB 인증 정보가 포함된 Uri 생성
        val smbPath = Uri.parse(item.path).path ?: ""
        return smbService.getAuthenticatedUri(serverIp, username, password, smbPath)
    }

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
