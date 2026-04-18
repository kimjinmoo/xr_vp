package com.grepiu.vp

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

/**
 * SMB 서버 접속 정보를 담는 데이터 클래스.
 * 
 * @property name 서버의 별칭 또는 호스트명.
 * @property ip 서버의 IP 주소.
 * @property user 접속 사용자 아이디.
 * @property pass 접속 비밀번호.
 * @property isAnonymous 익명 접속 여부.
 */
data class SmbServer(
    val name: String,
    val ip: String,
    val user: String = "",
    val pass: String = "",
    val isAnonymous: Boolean = false
)

/**
 * SMB 서버 내의 개별 파일 또는 폴더 아이템 정보를 담는 데이터 클래스.
 * 
 * @property name 파일 또는 폴더의 실제 이름.
 * @property path 전체 SMB URI 경로.
 * @property isDirectory 디렉토리(폴더) 여부.
 */
data class SmbItem(val name: String, val path: String, val isDirectory: Boolean)

/**
 * SMB 네트워크 연동을 위한 백엔드 서비스 클래스.
 * jcifs-ng 라이브러리를 래핑하여 네트워크 스캔, 파일 목록 조회, 인증 컨텍스트 관리를 수행함.
 */
class SmbService {
    
    /**
     * jcifs-ng의 기본 설정(SMB2 지원, DFS 비활성 등)을 포함한 컨텍스트를 생성함.
     * 
     * @return 초기화된 CIFSContext 객체.
     */
    private fun createBaseContext(): CIFSContext {
        val prop = Properties()
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false")
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        prop.setProperty("jcifs.smb.client.dfs.disabled", "true")
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    /** 현재 활성화된 인증 컨텍스트. */
    private var currentAuthContext: CIFSContext? = null

    /**
     * 사용자 자격 증명을 기반으로 인증 컨텍스트를 업데이트함.
     * 
     * @param username 사용자 아이디.
     * @param password 사용자 비밀번호.
     * @param domain 도메인 이름 (기본값 null).
     * @return 인증 설정 성공 여부.
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
     * 익명(Guest) 계정으로 인증 컨텍스트를 업데이트함.
     * 
     * @return 인증 설정 성공 여부.
     */
    suspend fun updateAnonymousCredentials(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val auth = NtlmPasswordAuthenticator(null, null, null)
            currentAuthContext = createBaseContext().withCredentials(auth)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 현재 인증 정보를 초기화하여 연결을 해제함.
     */
    fun disconnect() {
        currentAuthContext = null
    }

    /**
     * 인증 정보가 포함된 ExoPlayer용 전체 SMB URI를 생성함.
     * 
     * @param serverIp 서버 IP 주소.
     * @param username 인코딩할 사용자 이름.
     * @param password 인코딩할 비밀번호.
     * @param filePath 서버 내 파일 상대 경로.
     * @return 인증 정보가 URL에 포함된 Uri 객체.
     */
    fun getAuthenticatedUri(serverIp: String, username: String, password: String, filePath: String): Uri {
        val builder = Uri.Builder().scheme("smb")
        if (username.isNotBlank()) {
            val encodedUser = Uri.encode(username)
            val encodedPass = Uri.encode(password)
            builder.encodedAuthority("$encodedUser:$encodedPass@$serverIp")
        } else {
            builder.authority(serverIp)
        }
        val segments = filePath.split("/").filter { it.isNotEmpty() }
        segments.forEach { builder.appendPath(it) }
        return builder.build()
    }

    /**
     * 지정된 서브넷 내에서 활성화된 SMB 서버들을 병렬로 스캔함.
     * 445번 포트 확인을 통해 실제 SMB 서비스를 제공하는 기기만 선별함.
     * 
     * @param subnet 스캔할 C-Class 서브넷 (예: "192.168.0").
     * @return 응답이 있는 서버들의 [SmbServer] 리스트.
     */
    suspend fun scanNetwork(subnet: String): List<SmbServer> = withContext(Dispatchers.IO) {
        (1..254).map { i ->
            async {
                val host = "$subnet.$i"
                try {
                    // SMB 포트(445)에 접속 시도하여 실제 서비스 확인 (타임아웃 200ms)
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, 445), 200)
                    val address = socket.inetAddress
                    socket.close()
                    SmbServer(address.hostName, host)
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** 지원하는 비디오 확장자 목록. */
    private val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts")

    /**
     * 지정된 SMB 경로 내의 파일 및 폴더 목록을 조회함.
     * 
     * @param path 조회할 SMB URI 경로.
     * @return [SmbItem] 객체 리스트.
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
