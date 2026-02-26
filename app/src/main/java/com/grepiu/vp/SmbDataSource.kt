package com.grepiu.vp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.Properties
import kotlin.math.min

@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var inputStream: InputStream? = null
    private var bytesToRead: Long = 0

    private fun createCifsContext(): CIFSContext {
        val prop = Properties()
        // 현대적인 SMB 설정을 위한 프로퍼티 추가
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false") // 호환성을 위해 유지
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        
        val config = PropertyConfiguration(prop)
        return BaseContext(config)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        return try {
            val userInfo = uri?.userInfo
            val context: CIFSContext
            val cleanUrl: String
            
            if (userInfo != null) {
                val userPass = URLDecoder.decode(userInfo, "UTF-8").split(":", limit = 2)
                val username = userPass[0]
                val password = if (userPass.size > 1) userPass[1] else ""
                
                val auth = NtlmPasswordAuthenticator(null, username, password)
                context = createCifsContext().withCredentials(auth)
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

            // Sniffing을 위해 초기 버퍼는 작게, 이후 읽기에는 크게 작동하도록 
            // 1MB BufferedInputStream 적용
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

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            inputStream?.close()
        } catch (e: Exception) {
            // Ignore close errors
        } finally {
            inputStream = null
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }
}

@UnstableApi
class SmbDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SmbDataSource()
    }
}
