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

/**
 * SMB 서버 정보 데이터 클래스.
 * 
 * @property name 서버의 이름 또는 호스트명.
 * @property ip 서버의 IP 주소.
 */
data class SmbServer(val name: String, val ip: String)

/**
 * SMB 서버 내의 개별 파일 또는 폴더 아이템 정보 데이터 클래스.
 * 
 * @property name 파일 또는 폴더 명.
 * @property path 전체 SMB 경로.
 * @property isDirectory 디렉토리 여부.
 */
data class SmbItem(val name: String, val path: String, val isDirectory: Boolean)

/**
 * SMB 네트워크 연동을 위한 백엔드 서비스 클래스.
 * 네트워크 스캔, 파일 목록 조회, 인증 관리를 수행함.
 */
class SmbService {
    
    /**
     * jcifs-ng 기본 설정을 생성함.
     * 
     * @return 생성된 CIFSContext.
     */
    private fun createBaseContext(): CIFSContext {
        val prop = Properties()
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false")
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        // DFS 기능을 비활성화하여 'The network name cannot be found' 오류 방지
        prop.setProperty("jcifs.smb.client.dfs.disabled", "true")
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    private var currentAuthContext: CIFSContext? = null

    /**
     * 지정된 사용자 정보로 인증 컨텍스트를 업데이트함.
     * 
     * @param username 사용자 아이디.
     * @param password 사용자 비밀번호.
     * @param domain 도메인 (기본값 null).
     * @return 인증 성공 여부.
     */
    suspend fun updateCredentials(username: String, password: String, domain: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val auth = NtlmPasswordAuthenticator(domain, username, password)
            currentAuthContext = createBaseContext().withCredentials(auth)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 현재 인증 정보를 해제함.
     */
    fun disconnect() {
        currentAuthContext = null
    }

    /**
     * 현재 인증 정보를 포함하여 ExoPlayer에서 사용할 수 있는 전체 SMB URI를 생성함.
     * 
     * @param serverIp 서버 IP.
     * @param username 사용자 이름.
     * @param password 비밀번호.
     * @param filePath 파일 경로.
     * @return 인증 정보가 포함된 Uri.
     */
    fun getAuthenticatedUri(serverIp: String, username: String, password: String, filePath: String): Uri {
        val builder = Uri.Builder()
            .scheme("smb")
        
        // 사용자 이름과 비밀번호를 각각 인코딩하여 authority 구성
        if (username.isNotBlank()) {
            val encodedUser = Uri.encode(username)
            val encodedPass = Uri.encode(password)
            builder.encodedAuthority("$encodedUser:$encodedPass@$serverIp")
        } else {
            builder.authority(serverIp)
        }
        
        // 경로의 각 세그먼트를 분리하여 추가함으로써 자동 인코딩 보장
        val segments = filePath.split("/").filter { it.isNotEmpty() }
        segments.forEach { builder.appendPath(it) }
        
        return builder.build()
    }

    /**
     * 지정된 서브넷 내의 SMB 서버들을 스캔함.
     * 
     * @param subnet 스캔할 서브넷 (예: "192.168.0").
     * @return 발견된 서버 목록.
     */
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
                // 스캔 중 오류는 무시함
            }
        }
        servers
    }

    // 재생 가능한 비디오 확장자 목록
    private val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts")

    /**
     * 지정된 경로의 파일 및 폴더 목록을 조회함. 폴더와 비디오 파일만 필터링하여 반환.
     * 
     * @param path 조회할 SMB 경로.
     * @return 아이템 목록.
     */
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
