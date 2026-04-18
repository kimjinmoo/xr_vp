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
 * jcifs-ng 라이브러리를 사용하여 SMB2/3 프로토콜을 지원함.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var inputStream: InputStream? = null
    private var randomAccessFile: SmbRandomAccessFile? = null
    private var bytesToRead: Long = 0

    /**
     * SmbRandomAccessFile을 InputStream으로 변환해주는 내부 어댑터 클래스.
     */
    private class SmbInputStream(private val raf: SmbRandomAccessFile) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (raf.read(b) <= 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun close() = raf.close()
    }

    /**
     * jcifs-ng 설정을 위한 컨텍스트 생성.
     * 8K 스트리밍 성능 극대화를 위해 버퍼 및 타임아웃을 공격적으로 설정함.
     * 
     * @return 설정된 CIFSContext 객체.
     */
    private fun createCifsContext(): CIFSContext {
        val prop = Properties()
        // 8K 고대역폭 대응을 위한 SMB 최적화 설정
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false")
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        prop.setProperty("jcifs.smb.client.dfs.disabled", "true")
        
        // 네트워크 읽기/쓰기 버퍼 대폭 확장 (16MB) - 초고비트레이트 대응
        prop.setProperty("jcifs.smb.client.rcv_buf_size", "16777216") 
        prop.setProperty("jcifs.smb.client.snd_buf_size", "16777216")
        // SMB2/3 프로토콜에서의 최대 읽기/쓰기 크기 상향
        prop.setProperty("jcifs.smb.client.smb2.maxRead", "16777216")
        prop.setProperty("jcifs.smb.client.smb2.maxWrite", "16777216")
        // 동시 요청 버퍼 수 증가
        prop.setProperty("jcifs.smb.client.maxBuffers", "128")
        
        prop.setProperty("jcifs.smb.client.connTimeout", "10000")     // 연결 타임아웃 10초
        prop.setProperty("jcifs.smb.client.responseTimeout", "30000") // 응답 타임아웃 30초
        
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    private var dataSpec: DataSpec? = null

    /**
     * 데이터 소스를 오픈함. URI에서 인증 정보를 추출하고 SMB 파일 스트림을 준비함.
     * 
     * @param dataSpec 읽을 데이터의 명세.
     * @return 읽기 가능한 데이터의 총 길이.
     * @throws IOException SMB 연결 또는 파일 접근 실패 시 발생.
     */
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

                // uri.host가 null인 경우(예: smb:///192.168.x.x/...)를 대비하여 authority나 path에서 추출 시도
                var host = uri?.host ?: ""
                val port = if (uri?.port != -1) ":${uri?.port}" else ""

                val fullEncodedPath = uri?.encodedPath ?: ""
                val fullDecodedPath = uri?.path ?: ""
                
                // host가 비어있다면 malformed URI일 가능성이 높음 (smb:///host/path 등)
                // 이 경우 path의 첫 번째 세그먼트가 호스트일 수 있음.
                if (host.isEmpty()) {
                    val segments = fullEncodedPath.split("/").filter { it.isNotEmpty() }
                    if (segments.isNotEmpty()) {
                        host = segments[0]
                    }
                }

                val lastSlashIndex = fullEncodedPath.lastIndexOf('/')

                if (lastSlashIndex >= 0) {
                    val parentEncodedPath = fullEncodedPath.substring(0, lastSlashIndex + 1)
                    // 파일명은 디코딩된 원본 이름을 사용해야 jcifs-ng가 올바르게 인식함
                    val fileName = fullDecodedPath.substringAfterLast('/')
                    
                    // 호스트가 중복 포함되지 않도록 체크 (부모 경로는 여전히 인코딩된 상태 유지)
                    val parentUrl = if (parentEncodedPath.startsWith("/$host/")) {
                        "smb://$host$port${parentEncodedPath.substring(host.length + 1)}"
                    } else {
                        "smb://$host$port$parentEncodedPath"
                    }
                    
                    val parentFile = SmbFile(parentUrl, context)
                    smbFile = SmbFile(parentFile, fileName)
                } else {
                    // 전체 경로를 사용할 경우 jcifs-ng가 URL 객체로 파싱하므로 인코딩된 경로를 사용
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
                    if (segments.isNotEmpty()) {
                        host = segments[0]
                    }
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
                throw IOException("File does not exist: ${smbFile.canonicalPath}")
            }
            
            val fileLength = smbFile.length()
            
            // SmbRandomAccessFile을 사용하여 물리적 seek 수행 (기존 skip 방식 대비 압도적 빠름)
            val raf = SmbRandomAccessFile(smbFile, "r")
            randomAccessFile = raf
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position)
            }

            // JNI 및 네트워크 호출 오버헤드를 줄이기 위해 2MB 수준의 완충 버퍼 적용
            inputStream = BufferedInputStream(SmbInputStream(raf), 2 * 1024 * 1024)

            bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - dataSpec.position
            }
            
            transferStarted(dataSpec)
            bytesToRead
        } catch (e: Exception) {
            this.dataSpec = null
            throw IOException("SMB Open Error: ${e.message}", e)
        }
    }

    /**
     * 데이터를 읽어 버퍼에 채움.
     * 
     * @param buffer 데이터를 저장할 버퍼.
     * @param offset 버퍼의 시작 오프셋.
     * @param length 읽을 최대 길이.
     * @return 실제 읽은 바이트 수, 또는 입력 끝인 경우 C.RESULT_END_OF_INPUT.
     * @throws IOException 데이터 읽기 실패 시 발생.
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
        
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            bytesToRead -= read
        }
        bytesTransferred(read)
        return read
    }

    /**
     * 현재 오픈된 데이터의 URI를 반환함.
     * 
     * @return 현재 URI, 오픈되지 않은 경우 null.
     */
    override fun getUri(): Uri? = uri

    /**
     * 데이터 소스를 닫고 리소스를 해제함.
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
 * SmbDataSource 인스턴스를 생성하기 위한 팩토리 클래스.
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
