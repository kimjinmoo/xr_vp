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
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.TrackSelection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.VideoSize

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

            val meter = DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(200_000_000L)
                .build()
            this.bandwidthMeter = meter

            val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

            val baseLoadControl = DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(30000, 120000, 2500, 5000)
                .setTargetBufferBytes(256 * 1024 * 1024)
                .setBackBuffer(10000, true)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setBandwidthMeter(meter)
                .setLoadControl(baseLoadControl)
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
        }
        return exoPlayer!!
    }

    fun updateProgress() {
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
        bandwidthMeter?.let { currentBandwidth = it.bitrateEstimate }
    }

    fun prepareVideo(context: Context, uri: Uri, force: Boolean = false) {
        val player = getOrCreatePlayer(context)
        if (!force && currentUri == uri && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
            return
        }
        Log.d("VP_DEBUG", "Preparing Video: ${Uri.decode(uri.toString())} (force=$force)")
        currentUri = uri
        player.stop()
        player.clearMediaItems()
        val dataSourceFactory = if (uri.scheme?.lowercase() == "smb") SmbDataSourceFactory() else DefaultDataSource.Factory(context)
        val mediaSource = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * 플레이어 리소스를 완전히 해제하고 인스턴스를 초기화함.
     * 치명적 에러 발생 시 또는 화면 종료 시 호출됨.
     */
    fun releasePlayer() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        exoPlayer = null
        currentUri = null
        Log.d("VP_DEBUG", "Player released and nullified")
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
        wifiLock = null
    }
}
