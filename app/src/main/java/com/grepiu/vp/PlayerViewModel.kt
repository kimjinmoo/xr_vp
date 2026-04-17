package com.grepiu.vp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.VideoSize

/**
 * 비디오 플레이어의 인스턴스와 상태를 관리하는 뷰모델.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    
    var exoPlayer: ExoPlayer? = null
        private set

    private var currentUri: Uri? = null

    // UI 동기화를 위한 상태들
    var isPlaying by mutableStateOf(false)
    var duration by mutableLongStateOf(0L)
    var currentPosition by mutableLongStateOf(0L)
    var videoWidth by mutableStateOf(1920)
    var videoHeight by mutableStateOf(1080)
    var isBuffering by mutableStateOf(false)

    /**
     * ExoPlayer를 초기화하거나 기존 인스턴스를 반환함.
     */
    fun getOrCreatePlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            // ... (기존 초기화 로직 유지)
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

            exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            30000,  // 최소 버퍼 30초
                            120000, // 최대 버퍼 120초
                            5000,   // 재생 시작을 위한 최소 버퍼 5초
                            10000   // 버퍼링 후 재개 시 최소 버퍼 10초
                        )
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                )
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) { this@PlayerViewModel.isPlaying = playing }
                        override fun onPlaybackStateChanged(state: Int) {
                            this@PlayerViewModel.isBuffering = state == Player.STATE_BUFFERING
                            if (state == Player.STATE_READY) this@PlayerViewModel.duration = contentDuration
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

    /**
     * 재생 위치를 업데이트함 (주기적 호출용)
     */
    fun updateProgress() {
        exoPlayer?.let {
            currentPosition = it.currentPosition
        }
    }


    /**
     * 새로운 영상을 준비함. 
     * @param force 강제 재준비 여부 (모드 전환 시 사용)
     */
    fun prepareVideo(context: Context, uri: Uri, force: Boolean = false) {
        val player = getOrCreatePlayer(context)
        
        // force가 false일 때만 동일 URI/상태 체크 수행
        if (!force && currentUri == uri && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
            return
        }

        Log.d("VP_DEBUG", "Preparing Video: ${Uri.decode(uri.toString())} (force=$force)")
        currentUri = uri
        
        // 기존 상태 초기화
        player.stop()
        player.clearMediaItems()
        
        val dataSourceFactory = if (uri.scheme?.lowercase() == "smb") SmbDataSourceFactory() else DefaultDataSource.Factory(context)
        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))
            
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onCleared() {
        super.onCleared()
        exoPlayer?.release()
        exoPlayer = null
    }
}
