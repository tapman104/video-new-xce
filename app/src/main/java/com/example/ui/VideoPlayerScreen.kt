@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

// ─── Android / Java ───────────────────────────────────────────────────────────
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout

// ─── Activity / Compose runtime ───────────────────────────────────────────────
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// ─── Lifecycle ────────────────────────────────────────────────────────────────
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ─── Window / insets ──────────────────────────────────────────────────────────
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// ─── Media3 ───────────────────────────────────────────────────────────────────
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

// ─── Coroutines ───────────────────────────────────────────────────────────────
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

// ─── BuildConfig ─────────────────────────────────────────────────────────────────────────────
import com.example.BuildConfig

private const val SHOW_DEBUG_OVERLAY = false

// ─────────────────────────────────────────────────────────────────────────────
// Extension
// ─────────────────────────────────────────────────────────────────────────────

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ─────────────────────────────────────────────────────────────────────────────
// VideoPlayerScreen
// Responsibilities: state ownership, ExoPlayer init, UI layout
// All effects live in rememberVideoPlayerController() below.
// UI composables live in PlayerControls.kt
// Seek bar lives in SeekBar.kt
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun VideoPlayerScreen(viewModel: VideoPlayerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    // ── ExoPlayer ─────────────────────────────────────────────────────────────
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs                      */ 5_000,
                        /* maxBufferMs                      */ 20_000,
                        /* bufferForPlaybackMs              */ 1_500,
                        /* bufferForPlaybackAfterRebufferMs */ 3_000
                    )
                    .build()
            )
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    // ── Local gesture / render states ─────────────────────────────────────────
    // NOT in ViewModel — gesture updates never trigger full-screen recomposition.
    val brightnessState             = remember { mutableStateOf<Float?>(null) }
    val volumeState                 = remember { mutableStateOf<Float?>(null) }
    val subtitleState               = SubtitleGestureState(
        bottomFractionState = remember { mutableStateOf(state.subtitleBottomFraction) },
        textSizeState       = remember { mutableStateOf(state.subtitleTextSize) },
        isEditActiveState   = remember { mutableStateOf(false) }
    )
    val isPinchingState             = remember { mutableStateOf(false) }
    val zoomScaleState              = remember { mutableStateOf(1f) }
    val panXState                   = remember { mutableStateOf(0f) }
    val panYState                   = remember { mutableStateOf(0f) }

    // Delegated vars so AndroidView update lambda can read them without .value
    var subtitleBottomFraction by subtitleState.bottomFractionState
    var subtitleTextSize       by subtitleState.textSizeState

    // Updated by Player.Listener inside rememberVideoPlayerController
    var videoWidth  by remember { mutableStateOf(0f) }
    var videoHeight by remember { mutableStateOf(0f) }

    // ── Gesture UI state ──────────────────────────────────────────────────────
    var doubleTapSeekRight     by remember { mutableStateOf<Boolean?>(null) }
    var doubleTapSeekTime      by remember { mutableStateOf(0L) }
    var showAudioTrackSheet    by remember { mutableStateOf(false) }
    var showSubtitleTrackSheet by remember { mutableStateOf(false) }

    // ── File picker ───────────────────────────────────────────────────────────
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { VoraLog.player("takePersistableUriPermission failed: ${e.message}") }
            var displayName: String? = null
            try {
                context.contentResolver.query(
                    it,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) displayName = cursor.getString(0)
                }
            } catch (e: Exception) { VoraLog.player("contentResolver.query failed: ${e.message}") }
            viewModel.onVideoSelected(it, displayName ?: it.lastPathSegment ?: "Unknown")
        }
    }

    // ── All effects ───────────────────────────────────────────────────────────
    rememberVideoPlayerController(
        viewModel                   = viewModel,
        state                       = state,
        exoPlayer                   = exoPlayer,
        context                     = context,
        lifecycleOwner              = lifecycleOwner,
        brightnessState             = brightnessState,
        volumeState                 = volumeState,
        subtitleTextSizeState       = subtitleState.textSizeState,
        subtitleBottomFractionState = subtitleState.bottomFractionState,
        zoomScaleState              = zoomScaleState,
        panXState                   = panXState,
        panYState                   = panYState,
        onVideoSizeChanged          = { w, h ->
            videoWidth  = w
            videoHeight = h
        }
    )

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.videoUri == null) {
            EmptyState(onPickFile = { launcher.launch(arrayOf("video/*")) })

        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX          = zoomScaleState.value
                        scaleY          = zoomScaleState.value
                        translationX    = panXState.value
                        translationY    = panYState.value
                        transformOrigin = TransformOrigin.Center
                    }
                    .videoGestures(
                        exoPlayer                   = exoPlayer,
                        viewModel                   = viewModel,
                        state                       = state,
                        brightnessState             = brightnessState,
                        volumeState                 = volumeState,
                        subtitleState               = subtitleState,
                        isPinchingState             = isPinchingState,
                        zoomScaleState              = zoomScaleState,
                        panXState                   = panXState,
                        panYState                   = panYState,
                        videoWidth                  = videoWidth,
                        videoHeight                 = videoHeight,
                        onDoubleTapSeek             = { isRight ->
                            doubleTapSeekRight = isRight
                            doubleTapSeekTime  = System.currentTimeMillis()
                            viewModel.triggerInteraction()
                        },
                        context         = context,
                        getShowControls = { state.showControls },
                        getIsLocked     = { state.isLocked }   // live read — fixes stale lock state
                    )
            ) {
                // ── Video surface ──────────────────────────────────────────────
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player        = exoPlayer
                            useController = false
                            keepScreenOn  = true
                            subtitleView?.apply {
                                setApplyEmbeddedStyles(false)
                                setApplyEmbeddedFontSizes(false)
                                setStyle(
                                    androidx.media3.ui.CaptionStyleCompat(
                                        android.graphics.Color.WHITE,
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Color.TRANSPARENT,
                                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                        android.graphics.Color.BLACK,
                                        null
                                    )
                                )
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { v ->
                        val screenAspect = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
                        val videoAspect  = if (videoHeight > 0f) (videoWidth / videoHeight) else 1f

                        v.resizeMode = when (state.resizeMode) {
                            ResizeMode.FIT  -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            ResizeMode.FILL -> {
                                if (videoAspect > screenAspect) AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                            }
                            ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                        v.subtitleView?.setBottomPaddingFraction(subtitleBottomFraction)
                        v.subtitleView?.setFractionalTextSize(subtitleTextSize)
                    },
                    onRelease = { v -> v.player = null },
                    modifier  = Modifier.fillMaxSize()
                )

                // ── Buffering spinner ──────────────────────────────────────────
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier    = Modifier.align(Alignment.Center).size(56.dp),
                        color       = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
                }

                // ── Player controls overlay ────────────────────────────────────
                AnimatedVisibility(
                    visible = state.showControls,
                    enter   = fadeIn(tween(200)),
                    exit    = fadeOut(tween(200))
                ) {
                    PlayerControls(
                        isPlaying            = state.isPlaying,
                        isLocked             = state.isLocked,
                        fileName             = state.fileName,
                        resizeMode           = state.resizeMode,
                        orientationMode      = state.orientationMode,
                        playbackSpeed        = state.playbackSpeed,
                        exoPlayer            = exoPlayer,
                        onTogglePlay         = { viewModel.togglePlayPause() },
                        onPickFile           = { launcher.launch(arrayOf("video/*")) },
                        onToggleLock         = { viewModel.toggleLock(); viewModel.triggerInteraction() },
                        onCycleResizeMode    = { viewModel.cycleResizeMode(); viewModel.triggerInteraction() },
                        onCycleOrientationMode = { viewModel.cycleOrientationMode(); viewModel.triggerInteraction() },
                        onSetPlaybackSpeed   = { viewModel.setPlaybackSpeed(it); viewModel.triggerInteraction() },
                        onInteract           = { viewModel.triggerInteraction() },
                        onShowAudioTracks    = { showAudioTrackSheet = true; viewModel.triggerInteraction() },
                        onShowSubtitleTracks = { showSubtitleTrackSheet = true; viewModel.triggerInteraction() },
                        showControls         = state.showControls
                    )
                }

                // ── Subtitle Edit Highlight ────────────────────────────────────
                if (subtitleState.isEditActiveState.value) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.15f)
                            .padding(bottom = (subtitleBottomFraction * configuration.screenHeightDp).dp)
                            .padding(horizontal = 48.dp)
                            .background(Color.Transparent)
                            .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    )
                }

                // ── Track sheets ───────────────────────────────────────────────
                if (showAudioTrackSheet) {
                    AudioTrackSheet(
                        exoPlayer        = exoPlayer,
                        onDismissRequest = { showAudioTrackSheet = false; viewModel.triggerInteraction() }
                    )
                }
                if (showSubtitleTrackSheet) {
                    SubtitleTrackSheet(
                        exoPlayer        = exoPlayer,
                        onDismissRequest = { showSubtitleTrackSheet = false; viewModel.triggerInteraction() }
                    )
                }

                // ── Transient overlays ─────────────────────────────────────────
                DoubleTapSeekOverlay(doubleTapSeekRight, doubleTapSeekTime)
                BrightnessVolumeIndicator(brightnessState, volumeState)
                ResizeModeIndicator(state.resizeMode, state.resizeModeIndicator)
                FastForwardBadge(state.playbackSpeed)
                if (BuildConfig.DEBUG && SHOW_DEBUG_OVERLAY) {
                    val debugInfo by viewModel.debugInfo.collectAsStateWithLifecycle()
                    Text(
                        text = debugInfo.lastGestureEvent,
                        color = Color.Yellow,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp)
                    )
                }
            }
        }
    }

    // Back: subtitle edit -> resize reset -> finish
    BackHandler {
        if (subtitleState.isEditActiveState.value) {
            subtitleState.isEditActiveState.value = false
        } else if (state.resizeMode != ResizeMode.FIT) {
            viewModel.setResizeMode(ResizeMode.FIT)
        } else {
            val activity = context.findActivity()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                activity.finish()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// rememberVideoPlayerController
//
// Houses ALL side effects for VideoPlayerScreen. This is a plain @Composable
// function (not a class) that calls LaunchedEffect / DisposableEffect internally.
//
// Effect index (for stack-trace orientation):
//   1.  Fullscreen + per-video state reset   (LaunchedEffect: videoUri)
//   2.  Lifecycle pause / resume             (DisposableEffect: lifecycleOwner)
//   3.  Release player + save position       (DisposableEffect: Unit)
//   4.  Load video + restore position        (LaunchedEffect: videoUri)
//   5.  Buffering state + video size         (DisposableEffect: exoPlayer)
//   6.  Play / pause sync                    (LaunchedEffect: isPlaying, videoUri)
//   7.  Playback speed sync                  (LaunchedEffect: playbackSpeed)
//   8.  Volume IPC sync                      (LaunchedEffect: Unit / snapshotFlow)
//   9.  Brightness IPC sync                  (LaunchedEffect: Unit / snapshotFlow)
//   10. Subtitle text-size → ViewModel       (LaunchedEffect: Unit / snapshotFlow)
//   11. Subtitle bottom-fraction → ViewModel (LaunchedEffect: Unit / snapshotFlow)
//   12. Auto-hide controls                   (LaunchedEffect: showControls, isPlaying, …)
//   13. Orientation                          (LaunchedEffect: orientationMode)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberVideoPlayerController(
    viewModel:                   VideoPlayerViewModel,
    state:                       VideoPlayerState,
    exoPlayer:                   ExoPlayer,
    context:                     Context,
    lifecycleOwner:              LifecycleOwner,
    brightnessState:             MutableState<Float?>,
    volumeState:                 MutableState<Float?>,
    subtitleTextSizeState:       MutableState<Float>,
    subtitleBottomFractionState: MutableState<Float>,
    zoomScaleState:              MutableState<Float>,
    panXState:                   MutableState<Float>,
    panYState:                   MutableState<Float>,
    onVideoSizeChanged:          (width: Float, height: Float) -> Unit
) {
    rememberFullscreenEffect(state, subtitleTextSizeState, subtitleBottomFractionState, zoomScaleState, panXState, panYState, context)
    rememberLifecycleEffect(lifecycleOwner, exoPlayer, viewModel)
    rememberPlayerReleaseEffect(state, viewModel, exoPlayer)
    rememberVideoLoadEffect(state, viewModel, exoPlayer)
    rememberPlayerListenerEffect(exoPlayer, viewModel, context, onVideoSizeChanged)
    rememberPlaybackSyncEffect(state, exoPlayer)
    rememberIpcSyncEffects(state, exoPlayer, context, volumeState, brightnessState)
    rememberSubtitleSyncEffects(subtitleTextSizeState, subtitleBottomFractionState, viewModel)
    rememberAutoHideEffect(state, viewModel)
    rememberOrientationEffect(state, context)
}

// ─────────────────────────────────────────────────────────────────────────────
// Effect sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberFullscreenEffect(
    state:                       VideoPlayerState,
    subtitleTextSizeState:       MutableState<Float>,
    subtitleBottomFractionState: MutableState<Float>,
    zoomScaleState:              MutableState<Float>,
    panXState:                   MutableState<Float>,
    panYState:                   MutableState<Float>,
    context:                     Context
) {
    // ── Effect 1: Fullscreen + per-video local state reset ────────────────────
    LaunchedEffect(state.videoUri) {
        VoraLog.effect("Effect 1: fullscreen + per-video state reset")
        subtitleTextSizeState.value       = state.subtitleTextSize
        subtitleBottomFractionState.value = state.subtitleBottomFraction
        zoomScaleState.value = 1f
        panXState.value      = 0f
        panYState.value      = 0f
        val activity   = context.findActivity() ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (state.videoUri != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun rememberLifecycleEffect(
    lifecycleOwner: LifecycleOwner,
    exoPlayer:      ExoPlayer,
    viewModel:      VideoPlayerViewModel
) {
    // ── Effect 2: Lifecycle — pause on background, resume on foreground ───────
    // state.isPlaying does NOT change on backgrounding, so reading ViewModel
    // directly on ON_RESUME is the correct pattern.
    DisposableEffect(lifecycleOwner) {
        VoraLog.effect("Effect 2: lifecycle pause/resume")
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE  -> {
                    viewModel.uiState.value.videoUri?.let { uri ->
                        viewModel.savePosition(uri.toString(), exoPlayer.currentPosition)
                    }
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (viewModel.uiState.value.isPlaying) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun rememberPlayerReleaseEffect(
    state:     VideoPlayerState,
    viewModel: VideoPlayerViewModel,
    exoPlayer: ExoPlayer
) {
    // ── Effect 3: Save position + release player on composable exit ───────────
    DisposableEffect(Unit) {
        VoraLog.effect("Effect 3: release player + save position")
        onDispose {
            state.videoUri?.let {
                viewModel.savePosition(it.toString(), exoPlayer.currentPosition)
            }
            exoPlayer.release()
        }
    }
}

@Composable
private fun rememberVideoLoadEffect(
    state:     VideoPlayerState,
    viewModel: VideoPlayerViewModel,
    exoPlayer: ExoPlayer
) {
    // ── Effect 4: Load video + restore last playback position ─────────────────
    LaunchedEffect(state.videoUri) {
        VoraLog.effect("Effect 4: load video + restore position")
        state.videoUri?.let { uri ->
            val lastPosition = viewModel.getLastPosition(uri.toString())
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("en")
                .setPreferredTextLanguage("en")
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                .build()
            exoPlayer.prepare()
            exoPlayer.seekTo(lastPosition)
            exoPlayer.playWhenReady = state.isPlaying
        }
    }
}

@Composable
private fun rememberPlayerListenerEffect(
    exoPlayer:          ExoPlayer,
    viewModel:          VideoPlayerViewModel,
    context:            Context,
    onVideoSizeChanged: (width: Float, height: Float) -> Unit
) {
    // ── Effect 5: Buffering indicator + auto-orientation from video dimensions ─
    DisposableEffect(exoPlayer) {
        VoraLog.effect("Effect 5: buffering + video size listener")
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                viewModel.setBuffering(playbackState == Player.STATE_BUFFERING)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                onVideoSizeChanged(videoSize.width.toFloat(), videoSize.height.toFloat())
                if (videoSize.width > 0 && videoSize.height > 0
                    && !viewModel.uiState.value.isOrientationManuallySet
                ) {
                    val activity = context.findActivity()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        try {
                            activity.requestedOrientation =
                                if (videoSize.width > videoSize.height)
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } catch (e: Exception) { VoraLog.player("requestedOrientation failed: ${e.message}") }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
}

@Composable
private fun rememberPlaybackSyncEffect(
    state:     VideoPlayerState,
    exoPlayer: ExoPlayer
) {
    // ── Effect 6: Play / pause sync ───────────────────────────────────────────
    LaunchedEffect(state.isPlaying, state.videoUri) {
        VoraLog.effect("Effect 6: play/pause sync")
        if (state.videoUri != null) {
            if (state.isPlaying) exoPlayer.play() else exoPlayer.pause()
        }
    }
}

@Composable
private fun rememberIpcSyncEffects(
    state:           VideoPlayerState,
    exoPlayer:       ExoPlayer,
    context:         Context,
    volumeState:     MutableState<Float?>,
    brightnessState: MutableState<Float?>
) {
    // ── Effect 7: Playback speed sync ─────────────────────────────────────────
    LaunchedEffect(state.playbackSpeed) {
        VoraLog.effect("Effect 7: playback speed sync")
        exoPlayer.setPlaybackSpeed(state.playbackSpeed)
    }

    // ── Effect 8: Volume IPC sync ─────────────────────────────────────────────
    // conflate() + lastWriteTime guard prevent ANR on fast drags.
    LaunchedEffect("volumeSync") {
        VoraLog.effect("Effect 8: volume IPC sync")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume    = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var lastWriteTime = 0L
        snapshotFlow { volumeState.value }
            .conflate()
            .collect { volume ->
                if (volume != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastWriteTime >= 150) {
                        lastWriteTime = now
                        val index = (volume * maxVolume + 0.5f).toInt().coerceIn(0, maxVolume)
                        try {
                            audioManager.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC, index, 0
                            )
                        } catch (e: Exception) { VoraLog.player("setStreamVolume failed: ${e.message}") }
                    }
                }
            }
    }

    // ── Effect 9: Brightness IPC sync ─────────────────────────────────────────
    LaunchedEffect("brightnessSync") {
        VoraLog.effect("Effect 9: brightness IPC sync")
        var lastWriteTime = 0L
        snapshotFlow { brightnessState.value }
            .conflate()
            .collect { brightness ->
                if (brightness != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastWriteTime >= 150) {
                        lastWriteTime = now
                        val window = context.findActivity()?.window ?: return@collect
                        try {
                            val lp = window.attributes
                            lp.screenBrightness = brightness.coerceIn(0f, 1f)
                            window.attributes   = lp
                        } catch (e: Exception) { VoraLog.player("screenBrightness failed: ${e.message}") }
                    }
                }
            }
    }
}

@Composable
private fun rememberSubtitleSyncEffects(
    subtitleTextSizeState:       MutableState<Float>,
    subtitleBottomFractionState: MutableState<Float>,
    viewModel:                   VideoPlayerViewModel
) {
    // ── Effect 10: Subtitle text size → ViewModel (config-change survival) ────
    LaunchedEffect("subtitleSizeSync") {
        VoraLog.effect("Effect 10: subtitle text size sync")
        snapshotFlow { subtitleTextSizeState.value }
            .conflate()
            .collect { viewModel.setSubtitleTextSize(it) }
    }

    // ── Effect 11: Subtitle bottom fraction → ViewModel ───────────────────────
    LaunchedEffect("subtitleFractionSync") {
        VoraLog.effect("Effect 11: subtitle bottom fraction sync")
        snapshotFlow { subtitleBottomFractionState.value }
            .conflate()
            .collect { viewModel.setSubtitleBottomFraction(it) }
    }
}

@Composable
private fun rememberAutoHideEffect(
    state:     VideoPlayerState,
    viewModel: VideoPlayerViewModel
) {
    // ── Effect 12: Auto-hide controls after 3 s of inactivity ────────────────
    LaunchedEffect(state.showControls, state.isPlaying, state.isLocked, state.lastInteractionTime) {
        VoraLog.effect("Effect 12: auto-hide controls")
        if (state.showControls && state.isPlaying) {
            delay(3000)
            val current = viewModel.uiState.value
            if (current.isPlaying && current.showControls) {
                viewModel.setControlsVisible(false)
            }
        }
    }
}

@Composable
private fun rememberOrientationEffect(
    state:   VideoPlayerState,
    context: Context
) {
    // ── Effect 13: Manual orientation change ──────────────────────────────────
    LaunchedEffect(state.orientationMode) {
        VoraLog.effect("Effect 13: orientation change")
        if (!state.isOrientationManuallySet) return@LaunchedEffect
        val activity = context.findActivity() ?: return@LaunchedEffect
        if (activity.isFinishing || activity.isDestroyed) return@LaunchedEffect
        try {
            activity.requestedOrientation = when (state.orientationMode) {
                OrientationMode.SYSTEM    -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationMode.SENSOR    -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } catch (e: Exception) { VoraLog.player("requestedOrientation failed: ${e.message}") }
    }
}