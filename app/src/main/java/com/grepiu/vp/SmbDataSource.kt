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
    private var bytesToRead: Long = 0

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
        
        // 네트워크 읽기/쓰기 버퍼 대폭 확장 (기본값 대비 8배~16배)
        prop.setProperty("jcifs.smb.client.rcv_buf_size", "1048576") // 1MB 수신 버퍼
        prop.setProperty("jcifs.smb.client.snd_buf_size", "1048576") // 1MB 송신 버퍼
        prop.setProperty("jcifs.smb.client.connTimeout", "5000")     // 연결 타임아웃 5초
        prop.setProperty("jcifs.smb.client.responseTimeout", "10000") // 응답 타임아웃 10초
        
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
            // ... (인증 처리 로직 생략)
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

                val host = uri?.host ?: ""
                val port = if (uri?.port != -1) ":${uri?.port}" else ""

                val fullPath = uri?.path ?: ""
                val lastSlashIndex = fullPath.lastIndexOf('/')

                if (lastSlashIndex >= 0) {
                    val parentPath = fullPath.substring(0, lastSlashIndex + 1)
                    val fileName = fullPath.substring(lastSlashIndex + 1)
                    val parentUrl = "smb://$host$port$parentPath"
                    val parentFile = SmbFile(parentUrl, context)
                    smbFile = SmbFile(parentFile, fileName)
                } else {
                    smbFile = SmbFile("smb://$host$port$fullPath", context)
                }
            } else {
                context = createCifsContext()
                val fullPath = uri?.path ?: ""
                val lastSlashIndex = fullPath.lastIndexOf('/')
                val host = uri?.host ?: ""
                val port = if (uri?.port != -1) ":${uri?.port}" else ""

                if (lastSlashIndex >= 0) {
                    val parentPath = fullPath.substring(0, lastSlashIndex + 1)
                    val fileName = fullPath.substring(lastSlashIndex + 1)
                    val parentFile = SmbFile("smb://$host$port$parentPath", context)
                    smbFile = SmbFile(parentFile, fileName)
                } else {
                    smbFile = SmbFile(uri.toString(), context)
                }
            }

            if (!smbFile.exists()) {
                throw IOException("File does not exist: ${smbFile.canonicalPath}")
            }
            
            val fileLength = smbFile.length()
            val rawInputStream = smbFile.openInputStream()
            
            if (dataSpec.position > 0) {
                var totalSkipped = 0L
                while (totalSkipped < dataSpec.position) {
                    val skipped = rawInputStream.skip(dataSpec.position - totalSkipped)
                    if (skipped <= 0) {
                        val tempBuffer = ByteArray(min(8192, (dataSpec.position - totalSkipped).toInt()))
                        val read = rawInputStream.read(tempBuffer)
                        if (read == -1) break
                        totalSkipped += read
                    } else {
                        totalSkipped += skipped
                    }
                }
            }

            // 8K 영상 대응을 위해 자바 I/O 버퍼를 8MB로 확장
            inputStream = java.io.BufferedInputStream(rawInputStream, 8 * 1024 * 1024)

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
