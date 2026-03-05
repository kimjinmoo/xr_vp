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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
    isFullscreen: Boolean = false,
    playerViewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val exoPlayer = playerViewModel.getOrCreatePlayer(context)
    
    val isPlaying = playerViewModel.isPlaying
    val duration = playerViewModel.duration
    val currentPosition = playerViewModel.currentPosition
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
                playbackError = "재생 중 오류가 발생했습니다: ${error.localizedMessage}"
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

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            playerViewModel.updateProgress()
            delay(500)
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
        if (videoUri != null) {
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
                if (videoUri != null && (!isFirstFrameReady || isBuffering)) {
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
                        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp)) {
                            PlayerControls(
                                exoPlayer = exoPlayer,
                                isPlaying = isPlaying,
                                duration = duration,
                                currentPosition = currentPosition,
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

    if (showInfo) VideoInfoDialog(exoPlayer = exoPlayer, videoUri = videoUri, onDismiss = { showInfo = false })
    
    if (playbackError != null) {
        AlertDialog(onDismissRequest = { playbackError = null }, title = { Text(strings.playbackError) }, text = { Text(playbackError!!) }, confirmButton = { TextButton(onClick = { playbackError = null }) { Text("확인") } })
    }
}

@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    duration: Long,
    currentPosition: Long,
    volume: Float,
    showVolumeBar: Boolean,
    isFullscreen: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleVolumeBar: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onInfoShow: () -> Unit
) {
    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    
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
                drawLine(color = Color.White.copy(alpha = 0.2f), start = Offset(0f, centerY), end = Offset(width, centerY), strokeWidth = 3.dp.toPx())
                drawLine(color = Color.Red, start = Offset(0f, centerY), end = Offset(width * progress, centerY), strokeWidth = 3.dp.toPx())
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
                    IconButton(onClick = onToggleVolumeBar) { Icon(if (volume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null, tint = Color.White) }
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
fun VideoInfoDialog(exoPlayer: ExoPlayer, videoUri: Uri?, onDismiss: () -> Unit) {
    val videoFormat = exoPlayer.videoFormat
    val audioFormat = exoPlayer.audioFormat
    val fileName = remember(videoUri) { videoUri?.lastPathSegment ?: "Unknown File" }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.8f).padding(16.dp),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Media Information", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("VIDEO TRACK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Resolution", "${videoFormat?.width ?: 0} x ${videoFormat?.height ?: 0}")
                        InfoRow("Codec", videoFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "Unknown")
                        InfoRow("Frame Rate", "${videoFormat?.frameRate ?: 0f} fps")
                        InfoRow("Bitrate", "${(videoFormat?.bitrate ?: 0) / 1000} kbps")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("AUDIO TRACK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Codec", audioFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "Unknown")
                        InfoRow("Channels", "${audioFormat?.channelCount ?: 0} ch")
                        InfoRow("Sample Rate", "${(audioFormat?.sampleRate ?: 0) / 1000} kHz")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("Close") } }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}
