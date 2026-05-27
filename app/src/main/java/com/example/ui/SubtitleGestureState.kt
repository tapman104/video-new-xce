@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ui

import androidx.compose.runtime.MutableState

data class SubtitleGestureState(
    val bottomFractionState: MutableState<Float>,
    val textSizeState: MutableState<Float>,
    val isEditActiveState: MutableState<Boolean>
)
