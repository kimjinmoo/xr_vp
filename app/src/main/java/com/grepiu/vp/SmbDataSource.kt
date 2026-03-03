package com.grepiu.vp

import android.net.Uri
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
     * SMB2 활성화 및 성능 최적화를 위한 프로퍼티를 설정함.
     * 
     * @return 설정된 CIFSContext 객체.
     */
    private fun createCifsContext(): CIFSContext {
        val prop = Properties()
        // 현대적인 SMB 설정을 위한 프로퍼티 추가
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false") // 레거시 호환성을 위해 유지
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    /**
     * 데이터 소스를 오픈함. URI에서 인증 정보를 추출하고 SMB 파일 스트림을 준비함.
     * 
     * @param dataSpec 읽을 데이터의 명세.
     * @return 읽기 가능한 데이터의 총 길이.
     * @throws IOException SMB 연결 또는 파일 접근 실패 시 발생.
     */
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        return try {
            // URI에서 사용자 정보(아이디:비밀번호) 추출 및 디코딩
            val userInfo = uri?.userInfo
            val context: CIFSContext
            val cleanUrl: String
            
            if (userInfo != null) {
                val userPass = URLDecoder.decode(userInfo, "UTF-8").split(":", limit = 2)
                val username = userPass[0]
                val password = if (userPass.size > 1) userPass[1] else ""
                
                val auth = NtlmPasswordAuthenticator(null, username, password)
                context = createCifsContext().withCredentials(auth)
                // 인증 정보를 제외한 순수 호스트 정보로 URL 재구성
                cleanUrl = "smb://${uri?.host}${if (uri?.port != -1) ":${uri?.port}" else ""}${uri?.path}"
            } else {
                context = createCifsContext()
                cleanUrl = uri.toString()
            }

            val smbFile = SmbFile(cleanUrl, context)
            if (!smbFile.exists()) {
                throw IOException("File does not exist: $cleanUrl")
            }
            
            val fileLength = smbFile.length()
            val rawInputStream = smbFile.openInputStream()
            
            // 데이터 분석(Sniffing) 시에는 skip이 매우 빈번하게 일어납니다.
            // 효율적인 skip을 위해 원본 스트림에서 먼저 처리합니다.
            if (dataSpec.position > 0) {
                var totalSkipped = 0L
                while (totalSkipped < dataSpec.position) {
                    val skipped = rawInputStream.skip(dataSpec.position - totalSkipped)
                    if (skipped <= 0) {
                        // skip이 0을 반환하면 실제로 읽어서 버리는 방식으로 대응
                        val tempBuffer = ByteArray(min(4096, (dataSpec.position - totalSkipped).toInt()))
                        val read = rawInputStream.read(tempBuffer)
                        if (read == -1) break
                        totalSkipped += read
                    } else {
                        totalSkipped += skipped
                    }
                }
            }

            // 고해상도 비디오 스트리밍 성능을 위해 1MB 버퍼 적용
            inputStream = java.io.BufferedInputStream(rawInputStream, 1024 * 1024)

            bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - dataSpec.position
            }
            
            transferStarted(dataSpec)
            bytesToRead
        } catch (e: Exception) {
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
        val read = inputStream?.read(buffer, offset, bytesToReadThisTime) ?: -1
        
        if (read == -1) {
            if (bytesToRead != C.LENGTH_UNSET.toLong() && bytesToRead > 0) {
                throw IOException("Unexpected EOF: expected $bytesToRead more bytes")
            }
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
            if (uri != null) {
                uri = null
                transferEnded()
            }
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
