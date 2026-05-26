package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PlaybackPosition
import com.example.data.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.pm.ActivityInfo
import com.example.BuildConfig

enum class ResizeMode {
    FIT, FILL, ZOOM
}

enum class OrientationMode {
    SYSTEM, SENSOR, LANDSCAPE
}

data class VideoPlayerState(
    val videoUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLocked: Boolean = false,
    val showControls: Boolean = true,
    val playbackSpeed: Float = 1f,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val orientationMode: OrientationMode = OrientationMode.SYSTEM,
    val lastInteractionTime: Long = 0L,
    val resizeModeIndicator: Boolean = false,
    val fileName: String? = null,
    val isOrientationManuallySet: Boolean = false,
    val subtitleTextSize: Float = 16f,
    val subtitleBottomFraction: Float = 0.08f
)

data class DebugInfo(
    val lastGestureEvent: String = "",
    val effectsFired: List<String> = emptyList()
)

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = VideoRepository(database.playbackDao())

    private val _uiState = MutableStateFlow(VideoPlayerState())
    val uiState: StateFlow<VideoPlayerState> = _uiState.asStateFlow()

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    fun onVideoSelected(uri: Uri, fileName: String) {
        VoraLog.vm("onVideoSelected: uri=$uri fileName=$fileName")
        _uiState.value = _uiState.value.copy(
            videoUri = uri,
            fileName = fileName,
            isPlaying = true,
            showControls = true,
            isLocked = false,
            isOrientationManuallySet = false,
            subtitleTextSize = 16f,
            subtitleBottomFraction = 0.08f
        )
    }

    suspend fun getLastPosition(filePath: String): Long {
        VoraLog.vm("getLastPosition: filePath=$filePath")
        return repository.getPosition(filePath)
    }

    fun savePosition(filePath: String, position: Long) {
        VoraLog.vm("savePosition: filePath=$filePath position=$position")
        viewModelScope.launch {
            repository.savePosition(filePath, position)
        }
    }

    fun togglePlayPause() {
        VoraLog.vm("togglePlayPause: isPlaying=${_uiState.value.isPlaying} isLocked=${_uiState.value.isLocked}")
        if (_uiState.value.isLocked) return
        _uiState.value = _uiState.value.copy(isPlaying = !_uiState.value.isPlaying)
    }

    fun setBuffering(buffering: Boolean) {
        VoraLog.vm("setBuffering: buffering=$buffering")
        _uiState.value = _uiState.value.copy(isBuffering = buffering)
    }

    fun toggleLock() {
        VoraLog.vm("toggleLock: isLocked=${_uiState.value.isLocked}")
        _uiState.value = _uiState.value.copy(isLocked = !_uiState.value.isLocked)
    }

    fun setControlsVisible(visible: Boolean) {
        VoraLog.vm("setControlsVisible: visible=$visible showControls=${_uiState.value.showControls}")
        _uiState.value = _uiState.value.copy(
            showControls = visible,
            lastInteractionTime = System.currentTimeMillis()
        )
    }

    fun triggerInteraction() {
        VoraLog.vm("triggerInteraction: showControls=${_uiState.value.showControls}")
        if (_uiState.value.showControls) {
            _uiState.value = _uiState.value.copy(lastInteractionTime = System.currentTimeMillis())
        }
    }

    fun setResizeMode(mode: ResizeMode) {
        VoraLog.vm("setResizeMode: mode=$mode")
        _uiState.value = _uiState.value.copy(resizeMode = mode)
    }

    fun cycleResizeMode() {
        VoraLog.vm("cycleResizeMode: resizeMode=${_uiState.value.resizeMode}")
        val nextMode = when (_uiState.value.resizeMode) {
            ResizeMode.FIT -> ResizeMode.FILL
            ResizeMode.FILL -> ResizeMode.ZOOM
            ResizeMode.ZOOM -> ResizeMode.FIT
        }
        _uiState.value = _uiState.value.copy(
            resizeMode = nextMode,
            resizeModeIndicator = true
        )
        viewModelScope.launch {
            delay(1500)
            if (_uiState.value.resizeMode == nextMode) {
                _uiState.value = _uiState.value.copy(resizeModeIndicator = false)
            }
        }
    }

    fun cycleOrientationMode() {
        VoraLog.vm("cycleOrientationMode: orientationMode=${_uiState.value.orientationMode}")
        val nextMode = when (_uiState.value.orientationMode) {
            OrientationMode.SYSTEM -> OrientationMode.SENSOR
            OrientationMode.SENSOR -> OrientationMode.LANDSCAPE
            OrientationMode.LANDSCAPE -> OrientationMode.SYSTEM
        }
        _uiState.value = _uiState.value.copy(
            orientationMode = nextMode,
            isOrientationManuallySet = true
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        VoraLog.vm("setPlaybackSpeed: speed=$speed")
        _uiState.value = _uiState.value.copy(playbackSpeed = speed)
    }

    fun setSubtitleTextSize(size: Float) {
        VoraLog.vm("setSubtitleTextSize: size=$size")
        _uiState.value = _uiState.value.copy(subtitleTextSize = size)
    }

    fun setSubtitleBottomFraction(fraction: Float) {
        VoraLog.vm("setSubtitleBottomFraction: fraction=$fraction")
        _uiState.value = _uiState.value.copy(subtitleBottomFraction = fraction)
    }

    fun logGestureEvent(event: String) {
        if (!BuildConfig.DEBUG) return
        _debugInfo.value = _debugInfo.value.copy(lastGestureEvent = event)
    }
}
