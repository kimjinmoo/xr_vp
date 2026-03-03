package com.grepiu.vp

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset

/**
 * ExoPlayer를 사용하여 비디오를 재생하는 메인 컴포저블.
 * 2D 뷰와 Android XR을 사용한 공간 뷰(Spatial View)를 모두 지원함.
 *
 * @param videoUri 재생할 비디오의 URI. null인 경우 대기 상태 표시.
 * @param modifier 레이아웃 수정을 위한 Modifier.
 */
@SuppressLint("RestrictedApi")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isVrMode by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var currentDecoderName by remember { mutableStateOf("Unknown") }

    // 에뮬레이터 여부 확인: 하드웨어 가속 코덱의 불안정성을 피하기 위해 사용됨
    val isEmulator = remember {
        val model = android.os.Build.MODEL
        val product = android.os.Build.PRODUCT
        val hardware = android.os.Build.HARDWARE
        val fingerPrint = android.os.Build.FINGERPRINT
        fingerPrint.contains("generic") ||
                fingerPrint.contains("unknown") ||
                model.contains("google_sdk") ||
                model.contains("Emulator") ||
                model.contains("Android SDK built for x86") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                product.contains("sdk_gphone")
    }

    // MARK: - ExoPlayer 초기화
    val exoPlayer = remember(isVrMode) {
        // 커스텀 미디어 코덱 선택기: 에뮬레이터와 실기기에 따른 코덱 우선순위 조정
        val customMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)

            if (decoders.isEmpty()) return@MediaCodecSelector emptyList()

            if (isEmulator) {
                // 에뮬레이터: 하드웨어 가속(goldfish, c2) 대신 Google 소프트웨어 코덱 우선 시도
                val filtered = decoders.filter { it.name.lowercase().contains("google") }
                if (filtered.isNotEmpty()) {
                    Log.d("VP_DEBUG", "Emulator: Using software decoder for $mimeType: ${filtered[0].name}")
                    filtered
                } else {
                    Log.w("VP_DEBUG", "Emulator: No 'google' software decoder found for $mimeType. Falling back to default.")
                    decoders
                }
            } else {
                // 실기기: 하드웨어 가속(제조사 코덱) 우선
                decoders.sortedByDescending { decoder ->
                    val name = decoder.name.lowercase()
                    when {
                        !name.contains("google") && !name.contains("c2.android") -> 3
                        name.contains("c2.android") -> 2
                        name.contains("google") -> 1
                        else -> 0
                    }
                }
            }
        }

        // 렌더러 팩토리 설정: 에뮬레이터에서는 시스템 부하를 줄이기 위해 확장 코덱(libs)을 비활성화
        val extensionMode = if (isEmulator) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(extensionMode)
            .setMediaCodecSelector(customMediaCodecSelector)
            .setEnableDecoderFallback(true)

        Log.d("VP_DEBUG", "Creating new ExoPlayer (isVrMode: $isVrMode, isEmulator: $isEmulator)")

        // 버퍼링 정책 설정: 네트워크 스트리밍(SMB) 대응을 위해 조정됨
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                // 에뮬레이터 환경에서 지원하지 않는 프레임 레이트 전환 호출 차단
                if (isEmulator) {
                    setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                }
                
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val cause = error.cause?.message ?: error.message
                        Log.e("VP_DEBUG", "Player Error: ${error.errorCodeName}", error)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateStr = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d("VP_DEBUG", "Playback State Changed: $stateStr")
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        currentDecoderName = decoderName
                        Log.d("VP_DEBUG", "Video Decoder Initialized: $decoderName")
                    }
                })
            }
    }

    var playbackPosition by remember { mutableStateOf(0L) }
    var playWhenReadyState by remember { mutableStateOf(true) }

    // MARK: - 리소스 관리 및 위치 보존
    // 모드 전환(2D <-> VR) 시 이전 플레이어의 재생 정보를 저장하고 리소스를 해제함
    DisposableEffect(isVrMode) {
        onDispose {
            playbackPosition = exoPlayer.currentPosition
            playWhenReadyState = exoPlayer.playWhenReady
            Log.d("VP_DEBUG", "Disposing player for mode switch at pos: $playbackPosition")
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // 비디오 URI나 모드가 변경될 때 플레이어 준비 수행
    LaunchedEffect(videoUri, isVrMode) {
        if (videoUri == null) {
            currentDecoderName = "None"
            return@LaunchedEffect
        }

        val dataSourceFactory = if (videoUri.scheme?.lowercase() == "smb") {
            SmbDataSourceFactory()
        } else {
            DefaultDataSource.Factory(context)
        }

        val extension = videoUri.path?.substringAfterLast('.', "")?.lowercase()
        val mimeType = when (extension) {
            "mp4" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .createMediaSource(mediaItem)
        
        exoPlayer.setMediaSource(mediaSource)
        if (playbackPosition > 0L) {
            exoPlayer.seekTo(playbackPosition)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReadyState
    }

    // MARK: - UI 렌더링
    Box(modifier = modifier) {
        if (videoUri == null) {
            // 비디오 미선택 시 대기 화면
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("No Video Selected", color = Color.White)
            }
        } else {
            if (isVrMode) {
                // Android XR 공간 서피스 렌더링
                Subspace {
                    SpatialExternalSurface(
                        modifier = SubspaceModifier.offset(z = (-2).dp), 
                        stereoMode = StereoMode.SideBySide
                    ) {
                        onSurfaceCreated { surface ->
                            if (surface.isValid) {
                                Log.d("VP_DEBUG", "VR Surface Created")
                                exoPlayer.setVideoSurface(surface)
                            }
                        }
                        onSurfaceDestroyed {
                            exoPlayer.clearVideoSurface()
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            } else {
                // 표준 2D AndroidView 렌더링
                AndroidView(
                    factory = { ctx ->
                        val view = LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView
                        view.player = exoPlayer
                        view.useController = true
                        Log.d("VP_DEBUG", "2D AndroidView (SurfaceView) Factory")
                        view
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view -> 
                        if (view.player != exoPlayer) {
                            view.player = exoPlayer
                        }
                    },
                    onRelease = { view ->
                        view.player = null
                        exoPlayer.clearVideoSurface()
                    }
                )
            }
        }

        // MARK: - 컨트롤 버튼 (정보 보기, VR 전환)
        if (videoUri != null) {
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                FilledTonalIconButton(onClick = { showInfo = true }) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Video Info", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { 
                    Log.d("VP_DEBUG", "Mode switch requested")
                    isVrMode = !isVrMode 
                }) {
                    Icon(imageVector = Icons.Default.ViewInAr, contentDescription = "Toggle VR 180", 
                        tint = if (isVrMode) MaterialTheme.colorScheme.primary else Color.White)
                }
            }
        }

        if (showInfo) {
            VideoInfoDialog(exoPlayer = exoPlayer, videoUri = videoUri, decoderName = currentDecoderName, onDismiss = { showInfo = false })
        }
    }
}

/**
 * 현재 재생 중인 비디오 및 오디오의 기술 상세 정보를 표시하는 다이얼로그.
 * 
 * @param exoPlayer 현재 재생 중인 플레이어 인스턴스.
 * @param videoUri 재생 중인 비디오의 URI.
 * @param decoderName 현재 사용 중인 디코더 이름.
 * @param onDismiss 다이얼로그 닫기 이벤트 핸들러.
 */
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

/**
 * 다이얼로그 내의 개별 정보 행을 렌더링하는 컴포저블.
 * 
 * @param label 정보의 이름 (예: "URI", "Codec").
 * @param value 정보의 값.
 */
@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
