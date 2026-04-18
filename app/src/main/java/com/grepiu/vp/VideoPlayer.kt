package com.grepiu.vp

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * ExoPlayer를 사용하여 비디오를 재생하는 메인 컴포저블.
 * 몰입 모드(VR) 로직을 제거하고 표준 2D 패널 환경에 최적화됨.
 */
@SuppressLint("RestrictedApi")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    strings: UiStrings,
    onResizeWindowRequest: (Int, Int) -> Unit = { _, _ -> },
    onToggleFullscreen: () -> Unit = {},
    onClose: () -> Unit = {},
    isFullscreen: Boolean = false,
    playerViewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val exoPlayer = playerViewModel.getOrCreatePlayer(context)
    
    val isPlaying = playerViewModel.isPlaying
    val duration = playerViewModel.duration
    val currentPosition = playerViewModel.currentPosition
    val bufferedPosition = playerViewModel.bufferedPosition
    val videoWidth = playerViewModel.videoWidth
    val videoHeight = playerViewModel.videoHeight
    val isBuffering = playerViewModel.isBuffering
    
    var playbackError by remember { mutableStateOf<String?>(null) }
    var isFirstFrameReady by remember { mutableStateOf(false) }
    
    val resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { isFirstFrameReady = true }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("VP_FATAL", "재생 중 치명적 오류 발생 (리소스 해제): ${error.errorCodeName}", error)
                playbackError = error.localizedMessage ?: error.errorCodeName
                // 에러 발생 시 즉시 플레이어를 해제하여 로그 폭주 및 리소스 낭비를 차단함
                playerViewModel.releasePlayer()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onResizeWindowRequest(videoSize.width, videoSize.height)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            isFirstFrameReady = false
            playerViewModel.prepareVideo(context, videoUri)
        }
    }

    // 재생 중이 아니더라도 버퍼링 상태 등 프로그레스를 주기적으로 업데이트함 (0.1초 주기)
    // 에러 발생 시에는 업데이트를 중단하여 로그 폭주 및 불필요한 리렌더링 방지
    LaunchedEffect(Unit, playbackError) {
        while (playbackError == null) {
            playerViewModel.updateProgress()
            delay(100)
        }
    }

    var volume by remember { mutableStateOf(1.0f) }
    var showVolumeBar by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, showVolumeBar, showInfo, isPlaying) {
        if (controlsVisible && !showVolumeBar && !showInfo && isPlaying) {
            delay(5000)
            controlsVisible = false
        }
    }

    SideEffect { exoPlayer.volume = volume }

    Box(modifier = modifier.fillMaxSize()) {
        if (videoUri != null && playbackError == null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controlsVisible = !controlsVisible
                    if (!controlsVisible) showVolumeBar = false
                },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        val view = LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView
                        view.useController = false
                        view.resizeMode = resizeMode
                        view.keepScreenOn = true
                        view.player = exoPlayer
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
                    }
                )

                // 영상 준비 중이거나 버퍼링 중일 때 로딩 바 표시
                if (!isFirstFrameReady || isBuffering) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp
                        )
                    }
                }

                AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 상단 닫기 버튼 추가
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(48.dp).background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.close, tint = Color.White)
                        }

                        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp)) {
                            PlayerControls(
                                exoPlayer = exoPlayer,
                                isPlaying = isPlaying,
                                duration = duration,
                                currentPosition = currentPosition,
                                bufferedPosition = bufferedPosition,
                                volume = volume,
                                showVolumeBar = showVolumeBar,
                                isFullscreen = isFullscreen,
                                onVolumeChange = { volume = it },
                                onToggleVolumeBar = { showVolumeBar = !showVolumeBar },
                                onFullscreenToggle = onToggleFullscreen,
                                onInfoShow = { showInfo = true }
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(strings.noVideo, color = Color.White)
            }
        }
    }

    if (showInfo) VideoInfoDialog(exoPlayer = exoPlayer, videoUri = videoUri, playerViewModel = playerViewModel, strings = strings, onDismiss = { showInfo = false })
    
    if (playbackError != null) {
        AlertDialog(
            onDismissRequest = { 
                playbackError = null 
                onClose()
            },
            title = { Text(strings.playbackError) },
            text = { Text(strings.playbackErrorPrefix + playbackError!!) },
            confirmButton = { 
                TextButton(onClick = { 
                    playbackError = null 
                    onClose()
                }) { Text(strings.confirm) } 
            }
        )
    }
}

@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    duration: Long,
    currentPosition: Long,
    bufferedPosition: Long,
    volume: Float,
    showVolumeBar: Boolean,
    isFullscreen: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleVolumeBar: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onInfoShow: () -> Unit
) {
    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    val bufferedProgress = if (duration > 0) bufferedPosition.toFloat() / duration.toFloat() else 0f
    
    Column {
        Box(
            modifier = Modifier.fillMaxWidth().height(24.dp)
                .pointerInput(duration) { 
                    detectTapGestures { offset -> 
                        if (duration > 0) {
                            val seekPos = (offset.x / size.width).coerceIn(0f, 1f) * duration
                            exoPlayer.seekTo(seekPos.toLong())
                        }
                    } 
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                val width = size.width
                val centerY = size.height / 2
                // 전체 트랙 (배경)
                drawLine(color = Color.White.copy(alpha = 0.2f), start = Offset(0f, centerY), end = Offset(width, centerY), strokeWidth = 3.dp.toPx())
                // 버퍼링 진행 트랙
                drawLine(color = Color.White.copy(alpha = 0.4f), start = Offset(0f, centerY), end = Offset(width * bufferedProgress, centerY), strokeWidth = 3.dp.toPx())
                // 현재 재생 진행 트랙
                drawLine(color = Color.Red, start = Offset(0f, centerY), end = Offset(width * progress, centerY), strokeWidth = 3.dp.toPx())
                // 재생 위치 노브 (Circle)
                drawCircle(color = Color.Red, radius = 6.dp.toPx(), center = Offset(width * progress, centerY))
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Text(text = "${formatTime(currentPosition)} / ${formatTime(duration)}", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                IconButton(onClick = { exoPlayer.seekTo(maxOf(0, exoPlayer.currentPosition - 10000)) }) { Icon(Icons.Default.Replay10, null, tint = Color.White) }
                IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                IconButton(onClick = { exoPlayer.seekTo(minOf(duration, exoPlayer.currentPosition + 10000)) }) { Icon(Icons.Default.Forward10, null, tint = Color.White) }
            }

            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(visible = showVolumeBar) { Slider(value = volume, onValueChange = onVolumeChange, modifier = Modifier.width(100.dp).padding(end = 8.dp)) }
                    IconButton(onClick = onToggleVolumeBar) { Icon(if (volume > 0) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null, tint = Color.White) }
                }
                IconButton(onClick = onFullscreenToggle) { Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null, tint = Color.White) }
                IconButton(onClick = onInfoShow) { Icon(Icons.Default.Info, null, tint = Color.White) }
            }
        }
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

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoInfoDialog(exoPlayer: ExoPlayer, videoUri: Uri?, playerViewModel: PlayerViewModel, strings: UiStrings, onDismiss: () -> Unit) {
    val videoFormat = exoPlayer.videoFormat
    val audioFormat = exoPlayer.audioFormat
    val fileName = remember(videoUri) { videoUri?.lastPathSegment ?: strings.unknownFile }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.8f).padding(16.dp),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(strings.mediaInfoTitle, style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.videoTrack, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow(strings.resolution, "${videoFormat?.width ?: 0} x ${videoFormat?.height ?: 0}")
                        InfoRow(strings.codec, videoFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: strings.unknownValue)
                        InfoRow(strings.frameRate, "${videoFormat?.frameRate ?: 0f} fps")
                        InfoRow(strings.bitrate, "${(videoFormat?.bitrate ?: 0) / 1000} kbps")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.audioTrack, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow(strings.codec, audioFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: strings.unknownValue)
                        InfoRow(strings.channels, "${audioFormat?.channelCount ?: 0} ch")
                        InfoRow(strings.sampleRate, "${(audioFormat?.sampleRate ?: 0) / 1000} kHz")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.networkCache, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow(strings.downloadSpeed, String.format(Locale.US, "%.2f Mbps", playerViewModel.currentBandwidth / 1_000_000f))
                        InfoRow(strings.bufferedSize, String.format(Locale.US, "%.2f MB", playerViewModel.bufferedPosition / (1024f * 1024f)))
                        InfoRow(strings.heapUsage, String.format(Locale.US, "%d / %d MB", playerViewModel.currentHeapUsage / (1024 * 1024), playerViewModel.maxHeapMemory / (1024 * 1024)))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text(strings.close) } }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}
