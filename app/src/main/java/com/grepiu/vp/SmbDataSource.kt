package com.grepiu.vp

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
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
 * jcifs-ng 라이브러리를 사용하여 SMB2/3 프로토콜을 지원하며, 8K 스트리밍 성능 극대화를 위한 초고속 파이프라이닝이 적용됨.
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
     * 8K 초고화질 스트리밍을 위해 처리량(Throughput)과 안정성을 모두 극대화함.
     * 
     * @return 설정된 CIFSContext 객체.
     */
    private fun createCifsContext(): CIFSContext {
        val prop = Properties()
        // 1. 프로토콜 기본 최적화
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "true") 
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        prop.setProperty("jcifs.smb.client.dfs.disabled", "true")
        
        // 2. 네트워크 윈도우 및 버퍼 확장 (16MB)
        prop.setProperty("jcifs.smb.client.rcv_buf_size", "16777216") 
        prop.setProperty("jcifs.smb.client.snd_buf_size", "1048576")
        
        // 3. 8K 대역폭 확보를 위한 읽기 블록 확장 (8MB)
        prop.setProperty("jcifs.smb.client.smb2.maxRead", "8388608")
        prop.setProperty("jcifs.smb.client.smb2.maxWrite", "1048576")
        
        // 4. 파이프라이닝 극대화: 동시 요청 버퍼 수를 64개로 늘려 네트워크 레이턴시 극복
        // 64개 * 8MB 요청 구조로 고속 네트워크 대역폭을 완전히 점유함
        prop.setProperty("jcifs.smb.client.maxBuffers", "64")
        
        // 5. 실시간성 확보 및 지연 최소화
        prop.setProperty("jcifs.smb.client.tcpNoDelay", "true")
        prop.setProperty("jcifs.smb.client.useBatching", "true")
        
        // 6. 불규칙한 속도 방지를 위한 세션 안정성 설정
        prop.setProperty("jcifs.smb.client.connTimeout", "10000")      // 10초 연결 타임아웃
        prop.setProperty("jcifs.smb.client.responseTimeout", "30000")  // 30초 응답 대기
        prop.setProperty("jcifs.smb.client.sessionTimeout", "60000")   // 60초 세션 유지
        
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    private class SmbInputStream(private val raf: SmbRandomAccessFile) : InputStream() {
        private val singleByteBuf = ByteArray(1)
        override fun read(): Int = if (raf.read(singleByteBuf) <= 0) -1 else singleByteBuf[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun close() = raf.close()
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        return try {
            val encodedUserInfo = uri?.encodedUserInfo
            val context: CIFSContext
            val smbFile: SmbFile
            
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
            val raf = SmbRandomAccessFile(smbFile, "r")
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position)
            }

            // 8MB 읽기 블록에 맞춘 고성능 스트림 버퍼 적용
            inputStream = BufferedInputStream(SmbInputStream(raf), 8 * 1024 * 1024)

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

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            inputStream?.close()
        } catch (e: Exception) {
            // 무시
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
 * 다중 리스너 지원을 위해 생성자에서 가변 인자로 리스너들을 받음.
 */
@UnstableApi
class SmbDataSourceFactory(
    private vararg val listeners: TransferListener?
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val dataSource = SmbDataSource()
        listeners.forEach { listener ->
            listener?.let { dataSource.addTransferListener(it) }
        }
        return dataSource
    }
}
