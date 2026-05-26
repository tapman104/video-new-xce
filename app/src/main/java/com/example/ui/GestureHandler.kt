@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

// ─── Android / Java ───────────────────────────────────────────────────────────
import android.content.Context
import android.media.AudioManager

// ─── Compose gestures / pointer input ─────────────────────────────────────────
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// ─── Media3 ───────────────────────────────────────────────────────────────────
import androidx.media3.exoplayer.ExoPlayer

// ─────────────────────────────────────────────────────────────────────────────
// videoGestures
//
// Gesture model
// ═════════════
// Two modes are active at any time: NORMAL and SUBTITLE_EDIT.
//
// NORMAL MODE  (isSubtitleEditActive == false)
//   • Single tap         → toggle controls
//   • Double tap L/R     → seek ±10 s
//   • Long press         → 2× speed while held
//   • Vertical drag L    → brightness
//   • Vertical drag R    → volume
//   • Single-finger drag (zoomed) → pan video
//   • Wide pinch         → zoom + pan
//   • Narrow pinch       → (ignored unless finger lands on subtitle)
//   • Tap on subtitle    → enter SUBTITLE_EDIT mode
//
// SUBTITLE_EDIT MODE  (isSubtitleEditActive == true)
//   • Vertical drag      → move subtitle up / down
//   • Pinch (any spread) → resize subtitle text (10–36 sp)
//   • Tap anywhere else  → exit SUBTITLE_EDIT mode, return to NORMAL
//   • All other gestures are suppressed while edit mode is active
//
// isSubtitleEditActiveState is a MutableState<Boolean> owned by the caller
// (e.g. VideoPlayerViewModel or the composable) so the subtitle UI layer can
// read it directly to show/hide its edit affordance (glow / border / handle).
//
// Lock behaviour
// ══════════════
//   getIsLocked  — lambda so pinch block always sees the live value.
//   getShowControls — same pattern; fixes tap-to-show-controls when hidden.
// ─────────────────────────────────────────────────────────────────────────────

fun Modifier.videoGestures(
    exoPlayer: ExoPlayer,
    viewModel: VideoPlayerViewModel,
    state: VideoPlayerState,
    brightnessState: MutableState<Float?>,
    volumeState: MutableState<Float?>,
    subtitleBottomFractionState: MutableState<Float>,
    subtitleTextSizeState: MutableState<Float>,
    isSubtitleEditActiveState: MutableState<Boolean>,  // NEW — drives subtitle glow UI
    isPinchingState: MutableState<Boolean>,
    zoomScaleState: MutableState<Float>,
    panXState: MutableState<Float>,
    panYState: MutableState<Float>,
    videoWidth: Float,
    videoHeight: Float,
    onDoubleTapSeek: (isRight: Boolean) -> Unit,
    context: Context,
    getShowControls: () -> Boolean,
    getIsLocked: () -> Boolean
): Modifier = this

    // ── Block 1: Pinch dispatcher ──────────────────────────────────────────────
    // Two-finger gestures are committed at first contact and never switch.
    //
    // SUBTITLE_EDIT mode:
    //   All pinches → subtitle resize (spread size is irrelevant once editing).
    //
    // NORMAL mode:
    //   Wide spread  (> 25% screen width) → zoom + pan
    //   Narrow spread near subtitle       → subtitle resize + enter edit mode
    //   Narrow spread elsewhere           → discarded
    .pointerInput(state.videoUri) {
        awaitEachGesture {
            if (getIsLocked()) return@awaitEachGesture
            awaitFirstDown(requireUnconsumed = false)

            var isPinch = false
            try {
                withTimeout(150) {
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            isPinch = true
                            break
                        }
                    } while (event.changes.any { it.pressed })
                }
            } catch (e: PointerEventTimeoutCancellationException) {
                // timeout — not a pinch
            }

            if (!isPinch) return@awaitEachGesture

            isPinchingState.value = true
            try {
                val firstEvent  = awaitPointerEvent()
                val initTouches = firstEvent.changes.filter { it.pressed }
                val initialDist = if (initTouches.size >= 2)
                    (initTouches[0].position - initTouches[1].position).getDistance() else 0f

                val hitboxHalfPx    = with(density) { 24.dp.toPx() }
                val subtitleCenterY = size.height * (1f - subtitleBottomFractionState.value)

                when {
                    // ── In subtitle edit mode: any pinch resizes subtitle ──────
                    isSubtitleEditActiveState.value -> {
                        handleSubtitlePinch(subtitleTextSizeState = subtitleTextSizeState)
                    }

                    // ── Wide spread → zoom + pan ───────────────────────────────
                    initialDist > size.width * 0.25f -> {
                        handleZoomPinch(
                            zoomScaleState = zoomScaleState,
                            panXState      = panXState,
                            panYState      = panYState,
                            videoWidth     = videoWidth,
                            videoHeight    = videoHeight
                        )
                        viewModel.triggerInteraction()
                    }

                    // ── Narrow spread on subtitle → resize + enter edit mode ──
                    initTouches.any { t ->
                        kotlin.math.abs(t.position.y - subtitleCenterY) <= hitboxHalfPx
                    } -> {
                        isSubtitleEditActiveState.value = true
                        handleSubtitlePinch(subtitleTextSizeState = subtitleTextSizeState)
                    }

                    // ── Narrow spread elsewhere → discard ─────────────────────
                    else -> { /* no-op */ }
                }
            } finally {
                isPinchingState.value = false
            }
        }
    }

    // ── Block 2: Tap / drag dispatcher ────────────────────────────────────────
    // Classifies each gesture as: zoom-pan, horizontal-drag (discard),
    // long-press, vertical-drag, or tap/double-tap — then delegates.
    //
    // Subtitle-edit mode changes the routing for taps and vertical drags:
    //   • Tap on subtitle → enter edit mode (or stay in it)
    //   • Tap off subtitle → exit edit mode (no controls toggle while editing)
    //   • Vertical drag in edit mode → subtitle move (no brightness/volume)
    .pointerInput(state.videoUri) {
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis

        var currentBrightness = 0.5f
        var currentVolume     = 0.5f

        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)

                // ── Lock gate ─────────────────────────────────────────────────
                if (getIsLocked()) {
                    down.consume()
                    var ev: PointerEvent
                    do {
                        ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    return@awaitEachGesture
                }

                // ── Yield to pinch block if pinch is already active ───────────
                if (isPinchingState.value) {
                    down.consume()
                    var ev: PointerEvent
                    do {
                        ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    return@awaitEachGesture
                }

                if (down.isConsumed) return@awaitEachGesture
                down.consume()

                // ── Pre-compute subtitle hit ──────────────────────────────────
                // Evaluated once from down.position so every routing branch below
                // can use the same boolean without repeating the math.
                val hitboxHalfPx    = with(density) { 24.dp.toPx() }
                val subtitleCenterY = size.height * (1f - subtitleBottomFractionState.value)
                val isSubtitleTouch = kotlin.math.abs(down.position.y - subtitleCenterY) <= hitboxHalfPx

                var drag: PointerInputChange? = null
                var isLongPress = false

                try {
                    drag = withTimeout(longPressTimeout) {
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    }
                } catch (e: PointerEventTimeoutCancellationException) {
                    if (!isPinchingState.value) isLongPress = true
                }

                if (isPinchingState.value) return@awaitEachGesture

                // ── Route: zoomed-in pan (overrides everything) ───────────────
                if (drag != null && zoomScaleState.value > 1.01f) {
                    handlePanWhenZoomed(
                        drag           = drag,
                        zoomScaleState = zoomScaleState,
                        panXState      = panXState,
                        panYState      = panYState
                    )
                    viewModel.triggerInteraction()
                    return@awaitEachGesture
                }

                // ── Route: discard horizontal drag ────────────────────────────
                if (drag != null) {
                    val dx = kotlin.math.abs(drag.position.x - down.position.x)
                    val dy = kotlin.math.abs(drag.position.y - down.position.y)
                    if (dx >= dy) {
                        var ev: PointerEvent
                        do { ev = awaitPointerEvent() } while (ev.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                }

                // ── Route: long press → 2× speed ─────────────────────────────
                // Suppressed while in subtitle-edit mode (long press on subtitle
                // is an unlikely accident, and 2× would be disorienting).
                if (isLongPress && !isSubtitleEditActiveState.value) {
                    handleLongPress(viewModel = viewModel)
                    return@awaitEachGesture
                }

                // ── Route: vertical drag ──────────────────────────────────────
                if (drag != null) {
                    when {
                        // In edit mode OR finger started on subtitle → move subtitle
                        isSubtitleEditActiveState.value || isSubtitleTouch -> {
                            // Entering via isSubtitleTouch (not already in edit mode)
                            // implicitly activates edit mode so the UI glow appears.
                            isSubtitleEditActiveState.value = true
                            handleSubtitleDrag(
                                drag                        = drag,
                                subtitleBottomFractionState = subtitleBottomFractionState
                            )
                            viewModel.triggerInteraction()
                        }

                        // Normal mode, finger not on subtitle → brightness / volume
                        else -> {
                            handleBrightnessVolumeDrag(
                                drag                = drag,
                                context             = context,
                                brightnessState     = brightnessState,
                                volumeState         = volumeState,
                                currentBrightness   = currentBrightness,
                                currentVolume       = currentVolume,
                                onBrightnessUpdated = { currentBrightness = it },
                                onVolumeUpdated     = { currentVolume = it }
                            )
                            viewModel.triggerInteraction()
                        }
                    }
                    return@awaitEachGesture
                }

                // ── Route: tap ────────────────────────────────────────────────
                // Tap logic differs by mode:
                //
                // NORMAL mode
                //   Tap on subtitle  → enter SUBTITLE_EDIT mode (no controls toggle)
                //   Tap off subtitle → standard single/double-tap (seek or controls)
                //
                // SUBTITLE_EDIT mode
                //   Tap on subtitle  → stay in edit mode (user is still editing)
                //   Tap off subtitle → exit edit mode, suppress controls toggle
                //     (tapping away is a deliberate "done", not a controls toggle)
                if (!isPinchingState.value && drag == null) {
                    when {
                        // ── Tap ON subtitle ───────────────────────────────────
                        isSubtitleTouch -> {
                            // Enter (or stay in) edit mode. Do not toggle controls.
                            isSubtitleEditActiveState.value = true
                        }

                        // ── Tap OFF subtitle while in edit mode ───────────────
                        isSubtitleEditActiveState.value -> {
                            // Exit edit mode. Intentionally do not pass through to
                            // controls toggle — the tap was "dismiss edit", not
                            // "show controls". User can tap again for controls.
                            isSubtitleEditActiveState.value = false
                        }

                        // ── Normal tap / double-tap ───────────────────────────
                        else -> {
                            handleTapOrDoubleTap(
                                exoPlayer        = exoPlayer,
                                viewModel        = viewModel,
                                getShowControls  = getShowControls,
                                isPinchingState  = isPinchingState,
                                doubleTapTimeout = doubleTapTimeout,
                                onDoubleTapSeek  = onDoubleTapSeek
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Private gesture handlers
// Each function owns exactly one gesture arm.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Narrow-spread pinch on the subtitle: scales subtitle text between 10 sp and 36 sp.
 * Caller is responsible for entering subtitle-edit mode before invoking.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleSubtitlePinch(
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
            subtitleTextSizeState.value = (subtitleTextSizeState.value * zoom).coerceIn(10f, 36f)
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
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleZoomPinch(
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
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handlePanWhenZoomed(
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
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleLongPress(
    viewModel: VideoPlayerViewModel
) {
    viewModel.setPlaybackSpeed(2f)
    viewModel.triggerInteraction()
    try {
        waitForUpOrCancellation()
    } finally {
        viewModel.setPlaybackSpeed(1f)
        viewModel.triggerInteraction()
    }
}

/**
 * Vertical drag that began on (or within subtitle-edit mode from) the subtitle row.
 * Moves the subtitle up or down. subtitleBottomFraction is measured from the bottom.
 * Caller has already confirmed edit-mode or hitbox before invoking.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleSubtitleDrag(
    drag:                        PointerInputChange,
    subtitleBottomFractionState: MutableState<Float>
) {
    verticalDrag(drag.id) { change ->
        if (size.height > 0) {
            val delta = (change.position.y - change.previousPosition.y) / size.height
            if (delta.isFinite()) {
                val newFrac = subtitleBottomFractionState.value - delta
                if (newFrac.isFinite()) {
                    subtitleBottomFractionState.value = newFrac.coerceIn(0.02f, 0.5f)
                }
            }
        }
        change.consume()
    }
}

/**
 * Vertical drag outside subtitle-edit mode: adjusts brightness (left half)
 * or system volume (right half). Reads current system values at drag start
 * so the gesture feels continuous. Nulls out states on lift so the
 * BrightnessVolumeIndicator overlay dismisses.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleBrightnessVolumeDrag(
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
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleTapOrDoubleTap(
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
            exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
            onDoubleTapSeek(false)
        } else {
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