package com.grepiu.vp

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * ExoPlayer를 사용하여 비디오를 재생하는 메인 컴포저블.
 * 180도 VR 및 초고해상도(8K) 대응 로직 포함.
 */
@SuppressLint("RestrictedApi")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    onControllerVisibilityChanged: (Boolean) -> Unit = {},
    onResizeWindowRequest: (Int, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    
    // MARK: - 상태 관리
    var isVrMode by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableStateOf(1920) }
    var videoHeight by remember { mutableStateOf(1080) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentDecoderName by remember { mutableStateOf("Unknown") }
    var duration by remember { mutableStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    
    // 비디오 크기 모드는 기본 FIT으로 고정 (창 크기 조절로 대응)
    val resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

    // 180도 VR 스테레오 모드 감지 (해상도 비율 기반)
    val stereoMode = remember(videoWidth, videoHeight) {
        when {
            // 가로가 세로보다 현저히 길면 Side-by-Side (SBS)
            videoWidth > videoHeight * 1.5f -> StereoMode.SideBySide
            // 세로가 가로와 비슷하거나 더 길면 Top-Bottom (TB) 가능성 (안드로이드 XR 기본은 SBS 선호)
            else -> StereoMode.TopBottom
        }
    }

    // MARK: - ExoPlayer 초기화
    val exoPlayer = remember<ExoPlayer>(isVrMode) {
        val customMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (decoders.isEmpty()) return@MediaCodecSelector emptyList()

            decoders.sortedBy { decoder ->
                val name = decoder.name.lowercase()
                when {
                    name.contains("ffmpeg") || name.contains("jellyfin") -> 0
                    name.contains("vpx") || name.contains("av1") -> 1
                    name.contains("google") || name.contains("c2.android") || name.contains("goldfish") -> 3
                    else -> 2
                }
            }
        }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                try {
                    val clazz = Class.forName("org.jellyfin.media3.decoder.ffmpeg.FfmpegVideoRenderer")
                    val constructor = clazz.getConstructor(Long::class.java, Handler::class.java, VideoRendererEventListener::class.java, Int::class.java)
                    out.add(constructor.newInstance(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) as Renderer)
                } catch (e: Exception) {}
                
                super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setMediaCodecSelector(customMediaCodecSelector)
            setEnableDecoderFallback(true)
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30000, 60000, 1000, 2000).build())
            .build().apply {
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        currentDecoderName = decoderName
                    }
                })

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            duration = contentDuration
                            playbackError = null
                        }
                        isBuffering = playbackState == Player.STATE_BUFFERING
                    }
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            videoWidth = videoSize.width
                            videoHeight = videoSize.height
                            Log.d("VP_DEBUG", "Video Size Detected: ${videoWidth}x${videoHeight}")
                            
                            // 2D 모드일 경우 영상 비율에 맞춰 자동으로 창 크기 조절 요청
                            if (!isVrMode) {
                                Log.d("VP_DEBUG", "Auto-resizing window for 2D video")
                                onResizeWindowRequest(videoSize.width, videoSize.height)
                            }
                            
                            // 180 VR 자동 감지: 가로가 세로보다 1.8배 이상 길면 SBS VR로 간주하여 자동 전환
                            if (!isVrMode && videoSize.width >= videoSize.height * 1.8f) {
                                isVrMode = true
                                Log.d("VP_DEBUG", "180 VR Detected automatically (Ratio: ${videoSize.width.toFloat()/videoSize.height})")
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = "재생 중 오류가 발생했습니다 (${PlaybackException.getErrorCodeName(error.errorCode)}): ${error.localizedMessage}"
                    }
                })
            }
    }

    // MARK: - 로직 실행
    var currentPosition by remember { mutableStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }
    var volume by remember { mutableStateOf(1.0f) }
    var showVolumeBar by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    val videoAspectRatio = remember(videoWidth, videoHeight) {
        if (videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 1.77f
    }

    LaunchedEffect(videoUri, isVrMode) {
        if (videoUri == null) return@LaunchedEffect
        
        // 이미 해당 URI가 재생 중이라면 다시 prepare하지 않음 (창 크기 변경 등 단순 재구성 시 재생 유지)
        val currentMediaItem = exoPlayer.currentMediaItem
        if (currentMediaItem?.localConfiguration?.uri == videoUri) {
            return@LaunchedEffect
        }

        val dataSourceFactory = if (videoUri.scheme?.lowercase() == "smb") SmbDataSourceFactory() else DefaultDataSource.Factory(context)
        val mediaSource = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(videoUri))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(isPlaying, isDragging) {
        while (isPlaying && !isDragging) {
            currentPosition = exoPlayer.currentPosition
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, showVolumeBar, showInfo, isPlaying, isDragging) {
        if (controlsVisible && !showVolumeBar && !showInfo && isPlaying && !isDragging) {
            delay(5000)
            controlsVisible = false
        }
    }

    SideEffect { exoPlayer.volume = volume }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // MARK: - UI 렌더링
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            controlsVisible = !controlsVisible
            if (!controlsVisible) showVolumeBar = false
        },
        contentAlignment = Alignment.Center
    ) {
        if (videoUri != null) {
            if (isVrMode) {
                // 180도 VR 모드: 공간 패널을 사용자를 감싸는 듯한 큰 크기로 배치
                Subspace {
                    // 180 VR은 정면을 가득 채워야 하므로 너비를 크게 설정 (약 4미터 너비 효과)
                    val spatialWidth = 4.dp
                    val spatialHeight = (4 / (videoAspectRatio / 2)).dp // SBS 기준 실제 한쪽 눈 해상도 비율
                    
                    SpatialExternalSurface(
                        modifier = SubspaceModifier
                            .offset(z = (-2.5).dp) // 사용자와의 거리 조절
                            .width(spatialWidth)
                            .height(spatialHeight),
                        stereoMode = stereoMode // SBS 또는 TB 자동 적용
                    ) {
                        onSurfaceCreated { surface -> if (surface.isValid) exoPlayer.setVideoSurface(surface) }
                        onSurfaceDestroyed { exoPlayer.clearVideoSurface() }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { ctx ->
                            val view = LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView
                            view.player = exoPlayer
                            view.useController = false
                            view.setBackgroundColor(android.graphics.Color.BLACK)
                            view.setShutterBackgroundColor(android.graphics.Color.BLACK)
                            view.resizeMode = resizeMode
                            view
                        },
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        update = { view -> 
                            if (view.player != exoPlayer) view.player = exoPlayer 
                            view.setBackgroundColor(android.graphics.Color.BLACK)
                        },
                        onRelease = { view -> view.player = null; exoPlayer.clearVideoSurface() }
                    )
                }
            }
        } else {
            Text("No Video Selected", color = Color.White)
        }

        if (playbackError != null) {
            AlertDialog(onDismissRequest = { playbackError = null }, title = { Text("Playback Error") }, text = { Text(playbackError!!) }, confirmButton = { TextButton(onClick = { playbackError = null }) { Text("확인") } })
        }

        if (isBuffering) {
            CircularProgressIndicator(color = Color.White)
        }

        // 컨트롤 오버레이
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 12.dp).clickable(enabled = true, onClick = {})) {
                    val sliderValue = if (isDragging) dragPosition else if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
                    Slider(value = sliderValue, onValueChange = { isDragging = true; dragPosition = it; currentPosition = (it * duration).toLong() }, onValueChangeFinished = { exoPlayer.seekTo((dragPosition * duration).toLong()); isDragging = false }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { exoPlayer.seekTo(maxOf(0, exoPlayer.currentPosition - 10000)) }) { Icon(imageVector = Icons.Default.Replay10, contentDescription = null, tint = Color.White) }
                            IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) { Icon(imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                            IconButton(onClick = { exoPlayer.seekTo(minOf(duration, exoPlayer.currentPosition + 10000)) }) { Icon(imageVector = Icons.Default.Forward10, contentDescription = null, tint = Color.White) }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "${formatTime(if (isDragging) (dragPosition * duration).toLong() else currentPosition)} / ${formatTime(duration)}", style = MaterialTheme.typography.bodySmall, color = Color.White)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AnimatedVisibility(visible = showVolumeBar) { Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.width(100.dp).padding(end = 8.dp)) }
                                IconButton(onClick = { showVolumeBar = !showVolumeBar }) { Icon(imageVector = if (volume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = null, tint = Color.White) }
                            }
                            // Window Fit 버튼 (영상의 해상도에 맞춰 창 크기 조절 요청)
                            IconButton(onClick = { 
                                onResizeWindowRequest(videoWidth, videoHeight)
                            }) { Icon(imageVector = Icons.Default.FitScreen, contentDescription = "Fit Window to Video", tint = Color.White) }
                            IconButton(onClick = { isVrMode = !isVrMode }) { Icon(imageVector = Icons.Default.ViewInAr, contentDescription = null, tint = if (isVrMode) MaterialTheme.colorScheme.primary else Color.White) }
                            IconButton(onClick = { showInfo = true }) { Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color.White) }
                        }
                    }
                }
            }
        }

        if (showInfo) VideoInfoDialog(exoPlayer = exoPlayer, videoUri = videoUri, decoderName = currentDecoderName, onDismiss = { showInfo = false })
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = maxOf(0, ms / 1000)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@OptIn(UnstableApi::class)
@Composable
fun VideoInfoDialog(exoPlayer: ExoPlayer, videoUri: Uri?, decoderName: String, onDismiss: () -> Unit) {
    val videoFormat = exoPlayer.videoFormat
    val audioFormat = exoPlayer.audioFormat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video Information") },
        text = {
            Column {
                InfoRow("URI", videoUri?.toString() ?: "Unknown")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Video Track", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                InfoRow("Decoder", decoderName)
                InfoRow("Resolution", "${videoFormat?.width ?: 0} x ${videoFormat?.height ?: 0}")
                InfoRow("Codec", videoFormat?.sampleMimeType ?: "Unknown")
                InfoRow("Frame Rate", "${videoFormat?.frameRate ?: 0f} fps")
                InfoRow("Bitrate", "${(videoFormat?.bitrate ?: 0) / 1000} kbps")
                Spacer(modifier = Modifier.height(16.dp))
                Text("Audio Track", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                InfoRow("Codec", audioFormat?.sampleMimeType ?: "Unknown")
                InfoRow("Channels", "${audioFormat?.channelCount ?: 0}")
                InfoRow("Sample Rate", "${audioFormat?.sampleRate ?: 0} Hz")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
