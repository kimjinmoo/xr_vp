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
import androidx.compose.foundation.layout.size
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
import androidx.media3.common.Format
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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset

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

    val exoPlayer = remember {
        // 디코더 선택 로직 강화 및 진단 로그
        val customMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)

            // Logcat에서 확인할 수 있도록 Log.d 사용
            Log.d("VP_DEBUG", "Searching decoders for $mimeType")
            decoders.forEach { Log.d("VP_DEBUG", "Available decoder: ${it.name}") }

            if (decoders.isEmpty()) return@MediaCodecSelector emptyList()

            decoders.sortedBy { decoder ->
                val name = decoder.name.lowercase()
                when {
                    name.contains("goldfish") -> 3
                    name.contains("c2.android") -> 2
                    name.contains("google") -> 1
                    else -> 0
                }
            }
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector(customMediaCodecSelector)
            .setEnableDecoderFallback(true)

        Log.d("VP_DEBUG", "RenderersFactory initialized with ExtensionMode: PREFER")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 100000, 1500, 3000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val cause = error.cause?.message ?: error.message
                        Log.e("VP_DEBUG", "Player Error: ${error.errorCodeName}", error)
                        Toast.makeText(context, "Playback Error: ${error.errorCodeName}\n$cause", Toast.LENGTH_LONG).show()
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        currentDecoderName = decoderName
                        Log.d("VP_DEBUG", "Decoder initialized: $decoderName")
                    }
                })
            }
    }

    LaunchedEffect(videoUri) {
        if (videoUri == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
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
            "ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        // 추출기 설정 최적화
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setFragmentedMp4ExtractorFlags(
                FragmentedMp4Extractor.FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME
            )
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        val mediaSource = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
            .createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        if (videoUri == null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("No Video Selected", color = Color.White)
            }
        } else {
            if (isVrMode) {
                Subspace {
                    SpatialExternalSurface(modifier = SubspaceModifier.offset(z = (-2).dp), stereoMode = StereoMode.SideBySide) {
                        onSurfaceCreated { surface ->
                            exoPlayer.setVideoSurface(surface)
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            } else {
                AndroidView(
                    factory = { ctx ->
                        val view = LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView
                        view.player = exoPlayer
                        view.useController = true
                        view
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view -> view.player = exoPlayer }
                )
            }
        }

        if (videoUri != null) {
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                FilledTonalIconButton(onClick = { showInfo = true }) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Video Info", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { isVrMode = !isVrMode }) {
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
