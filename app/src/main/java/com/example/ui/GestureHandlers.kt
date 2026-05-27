@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

// ─── Android / Java ───────────────────────────────────────────────────────────
import android.content.Context
import android.media.AudioManager

// ─── Compose gestures / pointer input ─────────────────────────────────────────
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange

// ─── Media3 ───────────────────────────────────────────────────────────────────
import androidx.media3.exoplayer.ExoPlayer

// ─────────────────────────────────────────────────────────────────────────────
// Private gesture handlers
// Each function owns exactly one gesture arm.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Narrow-spread pinch on the subtitle: scales subtitle text between 3% and 12% of view height.
 * Caller is responsible for entering subtitle-edit mode before invoking.
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleSubtitlePinch(
    subtitleTextSizeState: MutableState<Float>
) {
    var event: PointerEvent
    do {
        event = awaitPointerEvent()
        val touches = event.changes.filter { it.pressed }
        if (touches.size >= 2) {
            val t0 = touches[0]
            val t1 = touches[1]
            val currentDist = (t0.position - t1.position).getDistance()
            val prevDist    = (t0.previousPosition - t1.previousPosition).getDistance()
            var zoom = if (prevDist > 0f) (currentDist / prevDist) else 1f
            if (!zoom.isFinite() || zoom.isNaN()) zoom = 1f
            subtitleTextSizeState.value = (subtitleTextSizeState.value * zoom).coerceIn(0.03f, 0.12f)
            event.changes.forEach { if (it.pressed) it.consume() }
        } else if (touches.size == 1) {
            event.changes.forEach { if (it.pressed) it.consume() }
        }
    } while (event.changes.any { it.pressed })
}

/**
 * Wide-spread pinch: continuous pinch-to-zoom + pan centred on the pinch midpoint.
 * Scale and pan persist after lifting fingers; reset only on new video.
 * Max zoom is clamped so content always covers the full screen (no black borders).
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleZoomPinch(
    zoomScaleState: MutableState<Float>,
    panXState:      MutableState<Float>,
    panYState:      MutableState<Float>,
    videoWidth:     Float,
    videoHeight:    Float
) {
    val w = size.width.toFloat()
    val h = size.height.toFloat()

    var event: PointerEvent
    do {
        event = awaitPointerEvent()
        val touches = event.changes.filter { it.pressed }

        if (touches.size >= 2) {
            val t0 = touches[0]
            val t1 = touches[1]
            val currentDist = (t0.position - t1.position).getDistance()
            val prevDist    = (t0.previousPosition - t1.previousPosition).getDistance()
            val ds = if (prevDist > 0f) currentDist / prevDist else 1f

            if (ds.isFinite() && ds > 0f) {
                val cx     = (t0.position.x         + t1.position.x)         / 2f
                val cy     = (t0.position.y         + t1.position.y)         / 2f
                val prevCx = (t0.previousPosition.x + t1.previousPosition.x) / 2f
                val prevCy = (t0.previousPosition.y + t1.previousPosition.y) / 2f

                val oldScale     = zoomScaleState.value
                val screenAspect = if (h > 0f) w / h else 1f
                val vAspect      = if (videoHeight > 0f && videoWidth > 0f) videoWidth / videoHeight else screenAspect
                val maxZoom      = when {
                    vAspect > screenAspect -> vAspect / screenAspect
                    vAspect < screenAspect -> screenAspect / vAspect
                    else                   -> 1f
                }.coerceAtLeast(1f)

                val newScale = (oldScale * ds).coerceIn(1f, maxZoom)
                val actualDs = if (oldScale > 0f) newScale / oldScale else 1f

                val newPanX = panXState.value + (cx - prevCx) + (cx - w / 2f) * oldScale * (1f - actualDs)
                val newPanY = panYState.value + (cy - prevCy) + (cy - h / 2f) * oldScale * (1f - actualDs)

                val maxPanX = w / 2f * (newScale - 1f)
                val maxPanY = h / 2f * (newScale - 1f)
                zoomScaleState.value = newScale
                panXState.value      = newPanX.coerceIn(-maxPanX, maxPanX)
                panYState.value      = newPanY.coerceIn(-maxPanY, maxPanY)
            }
            event.changes.forEach { if (it.pressed) it.consume() }
        } else if (touches.size == 1) {
            event.changes.forEach { if (it.pressed) it.consume() }
        }
    } while (event.changes.any { it.pressed })
    // Scale and pan intentionally NOT reset here — persists until pinched back to 1×.
}

/**
 * Single-finger drag while zoomed in: pans the video within clamped bounds.
 * Called only when zoomScaleState > 1.01f.
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handlePanWhenZoomed(
    drag:           PointerInputChange,
    zoomScaleState: MutableState<Float>,
    panXState:      MutableState<Float>,
    panYState:      MutableState<Float>
) {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    var panEv: PointerEvent
    do {
        panEv = awaitPointerEvent()
        val pressed = panEv.changes.filter { it.pressed }
        if (pressed.isNotEmpty()) {
            val c       = pressed.first()
            val scale   = zoomScaleState.value
            val maxPanX = w / 2f * (scale - 1f)
            val maxPanY = h / 2f * (scale - 1f)
            val dPanX   = c.position.x - c.previousPosition.x
            val dPanY   = c.position.y - c.previousPosition.y
            if (dPanX.isFinite() && dPanY.isFinite()) {
                panXState.value = (panXState.value + dPanX).coerceIn(-maxPanX, maxPanX)
                panYState.value = (panYState.value + dPanY).coerceIn(-maxPanY, maxPanY)
            }
            panEv.changes.forEach { it.consume() }
        }
    } while (panEv.changes.any { it.pressed })
}

/**
 * Long press: boosts playback to 2× for the duration of the press,
 * then restores 1× speed on finger lift or cancellation.
 * Not called while subtitle-edit mode is active.
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleLongPress(
    viewModel: VideoPlayerViewModel
) {
    viewModel.logGestureEvent("longPress:2x:start")
    viewModel.setLongPressSpeed(true)
    viewModel.setPlaybackSpeed(2f)
    viewModel.triggerInteraction()
    try {
        waitForUpOrCancellation()
    } finally {
        viewModel.setLongPressSpeed(false)
        viewModel.setPlaybackSpeed(1f)
        viewModel.logGestureEvent("longPress:1x:stop")
        viewModel.triggerInteraction()
    }
}

/**
 * Vertical drag that began on (or within subtitle-edit mode from) the subtitle row.
 * Moves the subtitle up or down. subtitleBottomFraction is measured from the bottom.
 * Caller has already confirmed edit-mode or hitbox before invoking.
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleSubtitleDrag(
    drag:                        PointerInputChange,
    subtitleBottomFractionState: MutableState<Float>
) {
    var pointerId = drag.id
    do {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
        if (change.pressed) {
            if (size.height > 0) {
                val deltaY = (change.position.y - change.previousPosition.y) / size.height
                if (deltaY.isFinite()) {
                    val newFrac = subtitleBottomFractionState.value - deltaY
                    subtitleBottomFractionState.value = newFrac.coerceIn(0.01f, 0.60f)
                }
            }
            change.consume()
        }
    } while (event.changes.any { it.pressed && it.id == pointerId })
}

/**
 * Vertical drag outside subtitle-edit mode: adjusts brightness (left half)
 * or system volume (right half). Reads current system values at drag start
 * so the gesture feels continuous. Nulls out states on lift so the
 * BrightnessVolumeIndicator overlay dismisses.
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleBrightnessVolumeDrag(
    drag:               PointerInputChange,
    context:            Context,
    brightnessState:    MutableState<Float?>,
    volumeState:        MutableState<Float?>,
    currentBrightness:  Float,
    currentVolume:      Float,
    onBrightnessUpdated: (Float) -> Unit,
    onVolumeUpdated:     (Float) -> Unit
) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var brightness = context.findActivity()?.window?.attributes?.screenBrightness
        ?.takeIf { it in 0f..1f } ?: currentBrightness
    var volume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol)
        .coerceIn(0f, 1f)

    verticalDrag(drag.id) { change ->
        val delta = -(change.position.y - change.previousPosition.y) / size.height
        change.consume()
        if (size.height > 0 && delta.isFinite()) {
            if (change.position.x < size.width / 2) {
                brightness += delta
                if (brightness.isFinite()) {
                    brightness = brightness.coerceIn(0f, 1f)
                    brightnessState.value = brightness
                    onBrightnessUpdated(brightness)
                }
            } else {
                volume += delta
                if (volume.isFinite()) {
                    volume = volume.coerceIn(0f, 1f)
                    volumeState.value = volume
                    onVolumeUpdated(volume)
                }
            }
        }
    }
    brightnessState.value = null
    volumeState.value     = null
}

/**
 * Single tap or double tap (only reached in NORMAL mode, off subtitle).
 *   Double tap left  → seek −10 s
 *   Double tap right → seek +10 s
 *   Single tap       → toggle controls visibility
 */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleTapOrDoubleTap(
    exoPlayer:        ExoPlayer,
    viewModel:        VideoPlayerViewModel,
    getShowControls:  () -> Boolean,
    isPinchingState:  MutableState<Boolean>,
    doubleTapTimeout: Long,
    onDoubleTapSeek:  (isRight: Boolean) -> Unit
) {
    try {
        val secondDown = withTimeout(doubleTapTimeout) {
            awaitFirstDown(requireUnconsumed = true)
        }
        if (secondDown.isConsumed) return
        secondDown.consume()

        if (secondDown.position.x < size.width / 2) {
            viewModel.logGestureEvent("doubleTap:left:-10s")
            exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
            onDoubleTapSeek(false)
        } else {
            viewModel.logGestureEvent("doubleTap:right:+10s")
            val dur = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
            exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(dur))
            onDoubleTapSeek(true)
        }
    } catch (e: PointerEventTimeoutCancellationException) {
        if (!isPinchingState.value) {
            viewModel.setControlsVisible(!getShowControls())
        }
    }
}
