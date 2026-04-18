package com.grepiu.vp

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.Properties
import kotlin.math.min

/**
 * ExoPlayer가 SMB(Server Message Block) 네트워크 공유 파일에서 데이터를 스트리밍할 수 있게 해주는 커스텀 데이터 소스.
 * jcifs-ng 라이브러리를 사용하여 SMB2/3 프로토콜을 지원하며, 표준 힙 메모리 환경에 최적화됨.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    /** 현재 재생 중인 SMB 파일의 URI. */
    private var uri: Uri? = null
    
    /** 데이터 읽기에 사용되는 입력 스트림. */
    private var inputStream: InputStream? = null
    
    /** 남은 읽기 대상 바이트 수. */
    private var bytesToRead: Long = 0
    
    /** 현재 오픈된 데이터의 명세 정보. */
    private var dataSpec: DataSpec? = null

    /**
     * jcifs-ng 설정을 위한 인증 및 파이프라이닝 컨텍스트 생성.
     * 표준 힙 메모리 환경(non-largeHeap)에서 안정적인 처리량(Throughput)을 내도록 최적화됨.
     * 
     * @return 설정된 CIFSContext 객체.
     */
    private fun createCifsContext(): CIFSContext {
        val prop = Properties()
        // SMB 최적화 기본 설정
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false")
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        prop.setProperty("jcifs.smb.client.dfs.disabled", "true")
        
        // 표준 메모리 환경에 맞춰 수신 버퍼 조정 (8MB)
        prop.setProperty("jcifs.smb.client.rcv_buf_size", "8388608") 
        prop.setProperty("jcifs.smb.client.snd_buf_size", "1048576")
        // 단일 읽기 블록 크기를 4MB로 조정 (메모리 절약과 성능의 균형)
        prop.setProperty("jcifs.smb.client.smb2.maxRead", "4194304")
        prop.setProperty("jcifs.smb.client.smb2.maxWrite", "1048576")
        // TCP NoDelay 활성화로 패킷 응답 지연 최소화
        prop.setProperty("jcifs.smb.client.tcpNoDelay", "true")
        // 동시 요청 버퍼 수를 8개로 줄여 메모리 점유 최소화 (8 * 4MB = 32MB 점유)
        prop.setProperty("jcifs.smb.client.maxBuffers", "8")
        
        prop.setProperty("jcifs.smb.client.connTimeout", "10000")     // 연결 타임아웃 10초
        prop.setProperty("jcifs.smb.client.responseTimeout", "30000") // 응답 타임아웃 30초
        
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    /**
     * SmbRandomAccessFile을 표준 InputStream으로 변환해주는 내부 어댑터 클래스.
     * 1바이트 읽기 시의 오버헤드를 줄이기 위해 재사용 버퍼를 활용함.
     * 
     * @property raf 기반이 되는 SmbRandomAccessFile 객체.
     */
    private class SmbInputStream(private val raf: SmbRandomAccessFile) : InputStream() {
        private val singleByteBuf = ByteArray(1)
        
        /** 1바이트를 읽어 반환함. */
        override fun read(): Int = if (raf.read(singleByteBuf) <= 0) -1 else singleByteBuf[0].toInt() and 0xFF
        
        /** 지정된 바이트 배열로 데이터를 읽어들임. */
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        
        /** 스트림을 닫음. */
        override fun close() = raf.close()
    }

    /**
     * 데이터 소스를 오픈함. URI에서 인증 정보를 추출하고 SMB 파일 접근 권한을 획득함.
     * 
     * @param dataSpec 읽을 데이터의 위치 및 길이를 포함한 명세.
     * @return 실제로 읽기 가능한 데이터의 총 길이.
     * @throws IOException SMB 네트워크 오류 또는 파일 접근 거부 시 발생.
     */
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        return try {
            val encodedUserInfo = uri?.encodedUserInfo
            val context: CIFSContext
            val smbFile: SmbFile
            
            // 1. 인증 정보 처리
            if (!encodedUserInfo.isNullOrBlank()) {
                val decodedUserInfo = URLDecoder.decode(encodedUserInfo, "UTF-8")
                val userPass = decodedUserInfo.split(":", limit = 2)
                val username = userPass[0]
                val password = if (userPass.size > 1) userPass[1] else ""

                val auth = NtlmPasswordAuthenticator(null, username, password)
                context = createCifsContext().withCredentials(auth)

                var host = uri?.host ?: ""
                val port = if (uri?.port != -1) ":${uri?.port}" else ""
                val fullEncodedPath = uri?.encodedPath ?: ""
                val fullDecodedPath = uri?.path ?: ""
                
                if (host.isEmpty()) {
                    val segments = fullEncodedPath.split("/").filter { it.isNotEmpty() }
                    if (segments.isNotEmpty()) host = segments[0]
                }

                val lastSlashIndex = fullEncodedPath.lastIndexOf('/')
                if (lastSlashIndex >= 0) {
                    val parentEncodedPath = fullEncodedPath.substring(0, lastSlashIndex + 1)
                    val fileName = fullDecodedPath.substringAfterLast('/')
                    val parentUrl = if (parentEncodedPath.startsWith("/$host/")) {
                        "smb://$host$port${parentEncodedPath.substring(host.length + 1)}"
                    } else {
                        "smb://$host$port$parentEncodedPath"
                    }
                    val parentFile = SmbFile(parentUrl, context)
                    smbFile = SmbFile(parentFile, fileName)
                } else {
                    smbFile = SmbFile("smb://$host$port$fullEncodedPath", context)
                }
            } else {
                // 2. 익명 접속 처리
                context = createCifsContext()
                var host = uri?.host ?: ""
                val port = if (uri?.port != -1) ":${uri?.port}" else ""
                val fullEncodedPath = uri?.encodedPath ?: ""
                val fullDecodedPath = uri?.path ?: ""

                if (host.isEmpty()) {
                    val segments = fullEncodedPath.split("/").filter { it.isNotEmpty() }
                    if (segments.isNotEmpty()) host = segments[0]
                }

                val lastSlashIndex = fullEncodedPath.lastIndexOf('/')
                if (lastSlashIndex >= 0) {
                    val parentEncodedPath = fullEncodedPath.substring(0, lastSlashIndex + 1)
                    val fileName = fullDecodedPath.substringAfterLast('/')
                    val parentUrl = if (parentEncodedPath.startsWith("/$host/")) {
                        "smb://$host$port${parentEncodedPath.substring(host.length + 1)}"
                    } else {
                        "smb://$host$port$parentEncodedPath"
                    }
                    val parentFile = SmbFile(parentUrl, context)
                    smbFile = SmbFile(parentFile, fileName)
                } else {
                    smbFile = SmbFile("smb://$host$port$fullEncodedPath", context)
                }
            }

            if (!smbFile.exists()) {
                throw IOException("파일이 존재하지 않습니다: ${smbFile.canonicalPath}")
            }
            
            val fileLength = smbFile.length()
            
            // 3. 탐색(Seek) 최적화: SmbRandomAccessFile을 사용하여 즉각 이동
            val raf = SmbRandomAccessFile(smbFile, "r")
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position)
            }

            // 4. 처리량 최적화: 4MB 완충 버퍼로 네트워크 요청 횟수 최소화
            inputStream = BufferedInputStream(SmbInputStream(raf), 4 * 1024 * 1024)

            bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - dataSpec.position
            }
            
            transferStarted(dataSpec)
            bytesToRead
        } catch (e: Exception) {
            this.dataSpec = null
            throw IOException("SMB 오픈 오류: ${e.message}", e)
        }
    }

    /**
     * 데이터를 읽어 인자로 받은 버퍼에 채움.
     * 
     * @param buffer 데이터를 저장할 타겟 바이트 배열.
     * @param offset 버퍼의 시작 위치 오프셋.
     * @param length 읽어올 최대 바이트 수.
     * @return 실제 읽은 바이트 수, 또는 파일 끝에 도달한 경우 C.RESULT_END_OF_INPUT.
     * @throws IOException 읽기 작업 중 네트워크 오류 발생 시.
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead == 0L) return C.RESULT_END_OF_INPUT

        val bytesToReadThisTime = min(bytesToRead, length.toLong()).toInt()
        val read = try {
            inputStream?.read(buffer, offset, bytesToReadThisTime) ?: -1
        } catch (e: IOException) {
            throw e
        }
        
        if (read == -1) return C.RESULT_END_OF_INPUT

        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            bytesToRead -= read
        }
        bytesTransferred(read)
        return read
    }

    /**
     * 현재 열려있는 리소스의 URI를 반환함.
     * 
     * @return 현재 URI 객체.
     */
    override fun getUri(): Uri? = uri

    /**
     * 데이터 소스를 닫고 열려있는 모든 리소스를 해제함.
     */
    override fun close() {
        try {
            inputStream?.close()
        } catch (e: Exception) {
            // 종료 오류는 무시함
        } finally {
            inputStream = null
            if (dataSpec != null) {
                transferEnded()
                dataSpec = null
            }
            uri = null
        }
    }
}

/**
 * 최적화된 SmbDataSource 인스턴스를 생성하기 위한 팩토리 클래스.
 */
@UnstableApi
class SmbDataSourceFactory : DataSource.Factory {
    /**
     * 새로운 SmbDataSource 인스턴스를 생성함.
     * 
     * @return 생성된 DataSource 객체.
     */
    override fun createDataSource(): DataSource {
        return SmbDataSource()
    }
}
