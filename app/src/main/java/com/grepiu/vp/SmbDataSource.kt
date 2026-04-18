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
     */
    private fun createCifsContext(): CIFSContext {
        val prop = Properties()
        prop.setProperty("jcifs.smb.client.enableSMB2", "true")
        prop.setProperty("jcifs.smb.client.disableSMB1", "false")
        prop.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        prop.setProperty("jcifs.smb.client.dfs.disabled", "true")
        prop.setProperty("jcifs.smb.client.rcv_buf_size", "8388608") 
        prop.setProperty("jcifs.smb.client.snd_buf_size", "1048576")
        prop.setProperty("jcifs.smb.client.smb2.maxRead", "4194304")
        prop.setProperty("jcifs.smb.client.smb2.maxWrite", "1048576")
        prop.setProperty("jcifs.smb.client.tcpNoDelay", "true")
        prop.setProperty("jcifs.smb.client.maxBuffers", "8")
        prop.setProperty("jcifs.smb.client.connTimeout", "10000")
        prop.setProperty("jcifs.smb.client.responseTimeout", "30000")
        
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
