@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

// ─── Android / Java ───────────────────────────────────────────────────────────
import android.content.Context

// ─── Compose gestures / pointer input ─────────────────────────────────────────
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

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
//   • Pinch (any spread) → resize subtitle text (3%–12% of view height)
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
    subtitleState: SubtitleGestureState,
    isPinchingState: MutableState<Boolean>,
    zoomScaleState: MutableState<Float>,
    panXState: MutableState<Float>,
    panYState: MutableState<Float>,
    videoWidth: Float,
    videoHeight: Float,
    subtitleTextSize: Float,
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

                val hitboxHalfPx    = (subtitleState.textSizeState.value * size.height) / 2f
                val subtitleCenterY = size.height * (1f - subtitleState.bottomFractionState.value)

                when {
                    // ── In subtitle edit mode: any pinch resizes subtitle ──────
                    subtitleState.isEditActiveState.value -> {
                        handleSubtitlePinch(subtitleTextSizeState = subtitleState.textSizeState)
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
                        t.position.x in 0f..size.width.toFloat() &&
                        kotlin.math.abs(t.position.y - subtitleCenterY) <= hitboxHalfPx
                    } -> {
                        subtitleState.isEditActiveState.value = true
                        handleSubtitlePinch(subtitleTextSizeState = subtitleState.textSizeState)
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
                    } while (ev.changes.any { it.pressed })

                    viewModel.setControlsVisible(true)
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
                val hitboxHalfPx    = (subtitleState.textSizeState.value * size.height) / 2f
                val subtitleCenterY = size.height * (1f - subtitleState.bottomFractionState.value)
                val isSubtitleTouch = down.position.x in 0f..size.width.toFloat() &&
                    kotlin.math.abs(down.position.y - subtitleCenterY) <= hitboxHalfPx

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
                if (isLongPress && !subtitleState.isEditActiveState.value) {
                    handleLongPress(viewModel = viewModel)
                    return@awaitEachGesture
                }

                // ── Route: vertical drag ──────────────────────────────────────
                if (drag != null) {
                    when {
                        // In edit mode OR finger started on subtitle → move subtitle.
                        // Do NOT activate edit mode here — only a confirmed tap does that.
                        subtitleState.isEditActiveState.value || isSubtitleTouch -> {
                            handleSubtitleDrag(
                                drag                        = drag,
                                subtitleBottomFractionState = subtitleState.bottomFractionState
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
                            subtitleState.isEditActiveState.value = true
                        }

                        // ── Tap OFF subtitle while in edit mode ───────────────────────
                        subtitleState.isEditActiveState.value -> {
                            // Exit edit mode. Intentionally do not pass through to
                            // controls toggle — the tap was "dismiss edit", not
                            // "show controls". User can tap again for controls.
                            subtitleState.isEditActiveState.value = false
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
