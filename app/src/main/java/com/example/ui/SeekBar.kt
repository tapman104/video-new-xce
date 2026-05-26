package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@Composable
fun VoraSeekBar(
    exoPlayer: ExoPlayer,
    showControls: Boolean,
    onInteract: () -> Unit
) {
    var rawCurrentPosition by remember { mutableLongStateOf(exoPlayer.currentPosition) }
    var totalDuration by remember { mutableLongStateOf(exoPlayer.duration) }
    var bufferedPosition by remember { mutableLongStateOf(exoPlayer.bufferedPosition) }
    
    var isDragging by remember { mutableStateOf(false) }
    var dragProgressFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!isDragging) {
                rawCurrentPosition = exoPlayer.currentPosition
            }
            totalDuration = exoPlayer.duration
            bufferedPosition = exoPlayer.bufferedPosition
            // Full rate only while playing AND controls are visible (canvas is on screen).
            // Hidden controls + playing → 1 s is plenty; paused → 1 s regardless.
            delay(if (exoPlayer.isPlaying && showControls) 200L else 1000L)
        }
    }

    val progressFraction = if (isDragging) {
        dragProgressFraction
    } else {
        if (totalDuration > 0) {
            (rawCurrentPosition.toFloat() / totalDuration).coerceIn(0f, 1f)
        } else 0f
    }

    val bufferFraction = if (totalDuration > 0) {
        (bufferedPosition.toFloat() / totalDuration).coerceIn(0f, 1f)
    } else 0f

    val displayedPosition = if (isDragging) {
        (dragProgressFraction * totalDuration).toLong()
    } else {
        rawCurrentPosition
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 3.dp,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "trackHeight"
    )

    val thumbSize by animateDpAsState(
        targetValue = if (isDragging) 16.dp else 10.dp,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "thumbSize"
    )

    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTimeInternal(displayedPosition),
            color = Color.White,
            fontSize = 11.sp
        )
        
        Spacer(modifier = Modifier.width(8.dp))

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(totalDuration) {
                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown()
                            down.consume()
                            isDragging = true
                            
                            val canvasWidth = size.width.toFloat()
                            var dragFraction = if (canvasWidth > 0f) (down.position.x / canvasWidth).coerceIn(0f, 1f) else 0f
                            dragProgressFraction = dragFraction
                            onInteract()

                            horizontalDrag(down.id) { change ->
                                change.consume()
                                dragFraction = if (canvasWidth > 0f) (change.position.x / canvasWidth).coerceIn(0f, 1f) else 0f
                                dragProgressFraction = dragFraction
                            }
                            // exoPlayer.duration is -1 until prepared; skip seek silently if not ready.
                            if (totalDuration > 0) {
                                exoPlayer.seekTo((dragFraction * totalDuration).toLong())
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val centerY = canvasHeight / 2f
            val trackHeightPx = trackHeight.toPx()
            val thumbRadiusPx = thumbSize.toPx() / 2f
            
            // Background track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.2f),
                topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                size = Size(canvasWidth, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
            )

            // Buffer track
            if (bufferFraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                    size = Size(canvasWidth * bufferFraction, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
                )
            }

            // Progress track
            if (progressFraction > 0f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                    size = Size(canvasWidth * progressFraction, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
                )
            }

            // Thumb shadow
            val shadowRadiusPx = thumbRadiusPx + 2.dp.toPx()
            val thumbX = (canvasWidth * progressFraction).coerceIn(0f, canvasWidth)
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = shadowRadiusPx,
                center = Offset(thumbX, centerY)
            )

            // Thumb
            drawCircle(
                color = Color.White,
                radius = thumbRadiusPx,
                center = Offset(thumbX, centerY)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatTimeInternal(totalDuration),
            color = Color.White,
            fontSize = 11.sp
        )
    }
}

private fun formatTimeInternal(milliseconds: Long): String {
    if (milliseconds <= 0) return "00:00"
    val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
