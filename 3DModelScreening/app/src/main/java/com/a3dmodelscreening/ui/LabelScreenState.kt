package com.a3dmodelscreening.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.a3dmodelscreening.domain.model.ModelLabel

/**
 * Mutable per-frame projection output for a single label. Reusing one instance
 * per label (instead of emitting a new list every frame) means the frame loop
 * only writes a few primitive fields, and only the overlay Canvas - which reads
 * them during its draw phase - gets invalidated, not the rest of the composition.
 */
@Stable
class LabelScreenState(val label: ModelLabel) {
    var screenX by mutableFloatStateOf(0f)
    var screenY by mutableFloatStateOf(0f)
    var isVisible by mutableStateOf(false)
}
