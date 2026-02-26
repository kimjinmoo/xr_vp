package com.grepiu.vp

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.Properties

data class SmbServer(val name: String, val ip: String)
data class SmbItem(val name: String, val path: String, val isDirectory: Boolean)

class SmbService {
    private fun createBaseContext(): CIFSContext {
        val prop = Properties()
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false")
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    private var currentAuthContext: CIFSContext? = null

    suspend fun updateCredentials(username: String, password: String, domain: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val auth = NtlmPasswordAuthenticator(domain, username, password)
            currentAuthContext = createBaseContext().withCredentials(auth)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun disconnect() {
        currentAuthContext = null
    }

    // 현재 설정된 인증 정보를 기반으로 경로 생성
    fun getAuthenticatedUri(serverIp: String, username: String, password: String, filePath: String): Uri {
        val userInfo = if (username.isNotBlank()) {
            "$username:$password@"
        } else ""
        val cleanPath = if (filePath.startsWith("/")) filePath.substring(1) else filePath
        return Uri.parse("smb://$userInfo$serverIp/$cleanPath")
    }

    suspend fun scanNetwork(subnet: String): List<SmbServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<SmbServer>()
        for (i in 1..254) {
            val host = "$subnet.$i"
            try {
                val address = InetAddress.getByName(host)
                if (address.isReachable(100)) {
                    servers.add(SmbServer(address.hostName, host))
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        servers
    }

    private val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts")

    suspend fun listFiles(path: String): List<SmbItem> = withContext(Dispatchers.IO) {
        try {
            val context = currentAuthContext ?: createBaseContext()
            val smbFile = SmbFile(path, context)
            smbFile.listFiles()
                .filter { it.isDirectory || videoExtensions.contains(it.name.substringAfterLast(".", "").lowercase()) }
                .map {
                    SmbItem(it.name, it.path, it.isDirectory)
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
