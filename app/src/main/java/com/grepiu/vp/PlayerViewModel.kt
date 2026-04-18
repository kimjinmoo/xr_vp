package com.grepiu.vp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.TrackSelection
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DataSpec

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.VideoSize
import java.util.concurrent.atomic.AtomicLong

/**
 * 비디오 플레이어의 인스턴스와 재생 상태를 관리하는 ViewModel.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    
    var exoPlayer: ExoPlayer? = null
        private set

    private var currentUri: Uri? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // 실시간 대역폭 측정을 위한 수동 카운터
    private val totalBytesTransferred = AtomicLong(0)
    private var lastMeasuredBytes = 0L
    private var lastMeasuredTime = 0L

    init {
        val wifiManager = application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(lockMode, "VP_WIFI_LOCK")
    }

    var isPlaying by mutableStateOf(false)
    var duration by mutableLongStateOf(0L)
    var currentPosition by mutableLongStateOf(0L)
    var bufferedPosition by mutableLongStateOf(0L)
    var currentBandwidth by mutableLongStateOf(0L)
    var currentHeapUsage by mutableLongStateOf(0L)
    var maxHeapMemory by mutableLongStateOf(0L)
    var videoWidth by mutableStateOf(1920)
    var videoHeight by mutableStateOf(1080)
    var isBuffering by mutableStateOf(false)

    // 전송 바이트를 실시간으로 누적하는 리스너
    private val manualTransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            totalBytesTransferred.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            val customMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                decoders.sortedBy { decoder ->
                    val name = decoder.name.lowercase()
                    when {
                        name.contains("ffmpeg") || name.contains("jellyfin") -> 0
                        name.contains("vpx") || name.contains("av1") -> 1
                        else -> 2
                    }
                }
            }

            val renderersFactory = DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                setMediaCodecSelector(customMediaCodecSelector)
            }

            // BandwidthMeter 설정
            val meter = DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(200_000_000L)
                .build()
            this.bandwidthMeter = meter

            val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

            // 8K(AV1/HEVC) 초고화질 재생을 위한 대용량 버퍼 및 메모리 최적화 설정
            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    60_000,   // 최소 버퍼 (1분)
                    180_000,  // 최대 버퍼 (3분): 8K 영상의 안정적 파이프라이닝 보장
                    15_000,   // 최초 재생 시작을 위한 최소 버퍼 (15초): 8K 데이터 확보 필요
                    30_000    // 리버퍼링 후 재생 시작 버퍼 (30초): 끊김 방지 우선
                )
                .setTargetBufferBytes(1536 * 1024 * 1024) // 1.5GB 메모리 할당 (8K 스트리밍 필수)
                .setPrioritizeTimeOverSizeThresholds(false) // 8K에서는 데이터 크기 임계치도 중요함
                .setBackBuffer(15_000, true) // 15초 백버퍼 유지
                .build()

            exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setBandwidthMeter(meter)
                .setLoadControl(loadControl)
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) { this@PlayerViewModel.isPlaying = playing }
                        override fun onPlaybackStateChanged(state: Int) {
                            this@PlayerViewModel.isBuffering = state == Player.STATE_BUFFERING
                            if (state == Player.STATE_READY) {
                                this@PlayerViewModel.duration = contentDuration
                                this@PlayerViewModel.bufferedPosition = bufferedPosition
                            }
                        }
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                this@PlayerViewModel.videoWidth = videoSize.width
                                this@PlayerViewModel.videoHeight = videoSize.height
                            }
                        }
                    })
                }
            
            lastMeasuredBytes = 0L
            lastMeasuredTime = System.currentTimeMillis()
            totalBytesTransferred.set(0)
        }
        return exoPlayer!!
    }

    /**
     * UI 스레드에서 주기적으로 호출됨 (약 500ms 주기를 권장)
     */
    fun updateProgress() {
        val now = System.currentTimeMillis()
        exoPlayer?.let {
            currentPosition = it.currentPosition
            bufferedPosition = it.bufferedPosition
            val runtime = Runtime.getRuntime()
            currentHeapUsage = runtime.totalMemory() - runtime.freeMemory()
            maxHeapMemory = runtime.maxMemory()
            
            if (currentPosition % 5000 < 100) {
                Log.d("VP_MEMORY", "Heap: ${currentHeapUsage / (1024 * 1024)} MB / ${maxHeapMemory / (1024 * 1024)} MB")
            }

            if (it.isPlaying) {
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        }

        // 실시간 초당 전송 속도 계산 (Bits per second)
        val currentTotalBytes = totalBytesTransferred.get()
        val timeDiff = now - lastMeasuredTime
        if (timeDiff >= 500) { // 500ms 주기로 샘플링
            val byteDiff = currentTotalBytes - lastMeasuredBytes
            if (byteDiff >= 0) {
                val measuredBps = (byteDiff * 8000) / timeDiff
                
                // 속도 표시가 튀는 현상을 방지하기 위해 지수 이동 평균(EMA) 적용
                // 새 측정값에 40% 가중치, 기존 값에 60% 가중치를 주어 부드럽게 표시
                if (this.currentBandwidth == 0L) {
                    this.currentBandwidth = measuredBps
                } else {
                    this.currentBandwidth = ((this.currentBandwidth * 0.6) + (measuredBps * 0.4)).toLong()
                }
                
                Log.d("VP_NET", "Smooth Speed: ${this.currentBandwidth / 1_000_000.0} Mbps")
            }
            lastMeasuredBytes = currentTotalBytes
            lastMeasuredTime = now
        }
    }

    fun prepareVideo(context: Context, uri: Uri, force: Boolean = false) {
        val player = getOrCreatePlayer(context)
        if (!force && currentUri == uri && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
            return
        }
        Log.d("VP_DEBUG", "Preparing Video: ${Uri.decode(uri.toString())} (force=$force)")
        currentUri = uri
        
        totalBytesTransferred.set(0)
        lastMeasuredBytes = 0
        lastMeasuredTime = System.currentTimeMillis()
        currentBandwidth = 0

        player.stop()
        player.clearMediaItems()

        val dataSourceFactory = if (uri.scheme?.lowercase() == "smb") {
            SmbDataSourceFactory(manualTransferListener, bandwidthMeter)
        } else {
            DefaultDataSource.Factory(context)
        }
        
        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))
            
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    fun releasePlayer() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        exoPlayer = null
        currentUri = null
        totalBytesTransferred.set(0)
        Log.d("VP_DEBUG", "Player released and nullified")
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
        wifiLock = null
    }
}
