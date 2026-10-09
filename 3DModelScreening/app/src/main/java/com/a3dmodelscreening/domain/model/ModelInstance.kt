package com.a3dmodelscreening.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp

data class ModelInstance(
    val id: String,
    val displayName: String,
    val modelPath: String,
    val position: Offset,
    val size: Dp,
    val interactionMode: Boolean = false,
    val labelsVisible: Boolean = false
)
