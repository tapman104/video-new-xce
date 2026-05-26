@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ─────────────────────────────────────────────
// Empty State
// ─────────────────────────────────────────────

@Composable
fun EmptyState(onPickFile: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(120.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("Vora Player", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Simple. Powerful. Lightweight.", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onPickFile,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Video", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────
// Player Controls (top bar + center + bottom)
// ─────────────────────────────────────────────

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isLocked: Boolean,
    fileName: String?,
    resizeMode: ResizeMode,
    orientationMode: OrientationMode,
    playbackSpeed: Float,
    exoPlayer: ExoPlayer,
    onTogglePlay: () -> Unit,
    onPickFile: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onCycleOrientationMode: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onInteract: () -> Unit,
    onShowAudioTracks: () -> Unit,
    onShowSubtitleTracks: () -> Unit,
    showControls: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .statusBarsPadding()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "▶  ${fileName ?: "Vora Player"}",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onShowAudioTracks, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Audiotrack, contentDescription = "Audio Tracks",
                    tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onShowSubtitleTracks, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Subtitles, contentDescription = "Subtitle Tracks",
                    tint = Color.White, modifier = Modifier.size(20.dp))
            }
            var showSpeedMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showSpeedMenu = true; onInteract() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Speed, contentDescription = "Playback Speed",
                        tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    speeds.forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${speed}x",
                                    fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal,
                                    color = if (speed == playbackSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onSetPlaybackSpeed(speed)
                                showSpeedMenu = false
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock",
                    tint = if (isLocked) Color.Red else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (!isLocked) {

            // ── Center controls ───────────────────
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                IconButton(
                    onClick = {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                        onInteract()
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.Replay10, contentDescription = "-10s",
                        tint = Color.White, modifier = Modifier.size(36.dp))
                }

                // Plain play/pause — no background, no circle, just the icon
                IconButton(
                    onClick = { onTogglePlay(); onInteract() },
                    modifier = Modifier.size(88.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val dur = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
                        exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(dur))
                        onInteract()
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.Forward10, contentDescription = "+10s",
                        tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }

            // ── Bottom controls ───────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp)
                    .navigationBarsPadding()
            ) {
                VoraSeekBar(exoPlayer = exoPlayer, showControls = showControls, onInteract = onInteract)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCycleOrientationMode, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = when (orientationMode) {
                                OrientationMode.SYSTEM -> Icons.Default.ScreenRotationAlt
                                OrientationMode.SENSOR -> Icons.Default.ScreenRotation
                                OrientationMode.LANDSCAPE -> Icons.Default.StayCurrentLandscape
                            },
                            contentDescription = "Orientation Mode",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onCycleResizeMode, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = when (resizeMode) {
                                ResizeMode.FIT -> Icons.Default.AspectRatio
                                ResizeMode.FILL -> Icons.Default.Fullscreen
                                ResizeMode.ZOOM -> Icons.Default.ZoomIn
                            },
                            contentDescription = "Resize Mode",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

        } else {

            // ── Locked state ──────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(100.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onToggleLock) {
                    Icon(Icons.Default.Lock, contentDescription = "Locked",
                        tint = Color.Red, modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Overlays
// ─────────────────────────────────────────────

@Composable
fun BrightnessVolumeIndicator(brightnessState: State<Float?>, volumeState: State<Float?>) {
    val visible by remember { derivedStateOf { brightnessState.value != null || volumeState.value != null } }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
    ) {
        val isBrightness by remember { derivedStateOf { brightnessState.value != null } }
        val value by remember { derivedStateOf { brightnessState.value ?: volumeState.value ?: 0f } }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(24.dp)
        ) {
            val icon = if (isBrightness) Icons.Default.WbSunny else Icons.AutoMirrored.Filled.VolumeUp
            val label = if (isBrightness) "Brightness" else "Volume"

            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { value },
                modifier = Modifier.width(100.dp).height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Gray
            )
            val percentText by remember { derivedStateOf { "${(value * 100).roundToInt()}%" } }
            Text(percentText, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
fun ResizeModeIndicator(mode: ResizeMode, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when (mode) {
                        ResizeMode.FIT -> Icons.Default.AspectRatio
                        ResizeMode.FILL -> Icons.Default.Fullscreen
                        ResizeMode.ZOOM -> Icons.Default.ZoomIn
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = mode.name, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FastForwardBadge(playbackSpeed: Float) {
    AnimatedVisibility(
        visible = playbackSpeed > 1f,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 48.dp)
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val speedText = if (playbackSpeed == playbackSpeed.toInt().toFloat())
                        "${playbackSpeed.toInt()}x" else "${playbackSpeed}x"
                    Text(text = speedText, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.FastForward, contentDescription = "Fast Forward",
                        tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun DoubleTapSeekOverlay(seekRight: Boolean?, seekTime: Long) {
    if (seekRight == null) return
    var showRipple by remember { mutableStateOf(false) }

    LaunchedEffect(seekTime) {
        if (seekTime > 0) {
            showRipple = true
            delay(500)
            showRipple = false
        }
    }

    AnimatedVisibility(
        visible = showRipple,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (seekRight) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (seekRight) Icons.Default.Forward10 else Icons.Default.Replay10,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (seekRight) "+10s" else "-10s", color = Color.White)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Track Sheets
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(exoPlayer: ExoPlayer, onDismissRequest: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Live track listener — updates if tracks change while sheet is open
    var tracks by remember { mutableStateOf(exoPlayer.currentTracks) }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(newTracks: androidx.media3.common.Tracks) {
                tracks = newTracks
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    val audioGroups = remember(tracks) {
        tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Audio Tracks", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
            if (audioGroups.isEmpty()) {
                Text("No audio tracks available", modifier = Modifier.padding(8.dp))
            } else {
                val flattenedTracks = remember(audioGroups) {
                    audioGroups.flatMap { group -> (0 until group.length).map { group to it } }
                }
                androidx.compose.foundation.lazy.LazyColumn {
                    items(flattenedTracks.size) { index ->
                        val (group, trackIndex) = flattenedTracks[index]
                        val format = group.mediaTrackGroup.getFormat(trackIndex)
                        val isSelected = group.isTrackSelected(trackIndex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(
                                            androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                                        )
                                        .build()
                                    onDismissRequest()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(text = format.label ?: "Track ${trackIndex + 1}",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                Text(text = (format.language ?: "Unknown").uppercase(),
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleTrackSheet(exoPlayer: ExoPlayer, onDismissRequest: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tracks by remember { mutableStateOf(exoPlayer.currentTracks) }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(newTracks: androidx.media3.common.Tracks) {
                tracks = newTracks
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    val textGroups = remember(tracks) {
        tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
    }
    val isSubtitlesDisabled = remember(tracks) {
        !textGroups.any { group -> (0 until group.length).any { group.isTrackSelected(it) } }
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Subtitle Tracks", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
            if (textGroups.isEmpty()) {
                Text("No subtitle tracks available", modifier = Modifier.padding(8.dp))
            } else {
                val flattenedSubtitles = remember(textGroups) {
                    textGroups.flatMap { group -> (0 until group.length).map { group to it } }
                }
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                        .build()
                                    onDismissRequest()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSubtitlesDisabled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSubtitlesDisabled) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Off", fontWeight = if (isSubtitlesDisabled) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    items(flattenedSubtitles.size) { index ->
                        val (group, trackIndex) = flattenedSubtitles[index]
                        val format = group.mediaTrackGroup.getFormat(trackIndex)
                        val isSelected = group.isTrackSelected(trackIndex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(
                                            androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                                        )
                                        .build()
                                    onDismissRequest()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(text = format.label ?: "Track ${trackIndex + 1}",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                Text(text = (format.language ?: "Unknown").uppercase(),
                                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}