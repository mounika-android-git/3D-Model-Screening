package com.a3dmodelscreening.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.a3dmodelscreening.domain.model.ModelInstance
import com.google.android.filament.Engine
import io.github.sceneview.loaders.ModelLoader
import kotlin.math.roundToInt

private const val ROTATION_SENSITIVITY = 0.5f
private const val MAX_PITCH_DEGREES = 80f
private const val MIN_SIZE_SCREEN_FRACTION = 0.25f
private const val MAX_SIZE_SCREEN_FRACTION = 0.95f
private const val MIN_SIZE_FLOOR_DP = 96f

// Icon row shrinks together with the container so all 3 buttons stay visible even at
// MIN_SIZE_FLOOR_DP, instead of overflowing/clipping the third icon at a fixed 48dp size.
private val ICON_BUTTON_MAX_SIZE = 40.dp
private val ICON_BUTTON_MIN_SIZE = 22.dp
private val ICON_MAX_SIZE = 24.dp
private val ICON_MIN_SIZE = 14.dp
private val ICON_SCALE_SIZE_RANGE = 96.dp..220.dp

/**
 * A single independently-controllable model on screen.
 * Gesture handling responsibility lives here: normal mode drags/resizes the
 * container; interaction mode rotates/zooms the 3D content instead.
 */
@Composable
fun ModelContainer(
    instance: ModelInstance,
    engine: Engine,
    modelLoader: ModelLoader,
    onMove: (id: String, newPosition: Offset) -> Unit,
    onResize: (id: String, newSize: Dp) -> Unit,
    onToggleInteraction: (id: String) -> Unit,
    onToggleLabels: (id: String) -> Unit,
    onClose: (id: String) -> Unit
) {
    var rotationX by remember(instance.id) { mutableFloatStateOf(0f) }
    var rotationY by remember(instance.id) { mutableFloatStateOf(0f) }
    var zoomScale by remember(instance.id) { mutableFloatStateOf(1f) }

    // Position/size are mirrored into local state during an active drag/pinch so every
    // pointer-move sample only recomposes this one container, not the whole instances list
    // in MainScreen (onMove/onResize are called just once, on gesture end, to commit).
    var localPosition by remember(instance.id) { mutableStateOf(instance.position) }
    var localSize by remember(instance.id) { mutableStateOf(instance.size) }

    // Gesture coroutine below runs across many recompositions without restarting
    // (keyed only on id/interactionMode), so it must read position/size through this
    // state instead of the composable's `instance` parameter, or it keeps acting on
    // whatever value was current the moment the coroutine first launched.
    val latestInstance by rememberUpdatedState(instance)

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val minSize = (screenWidthDp * MIN_SIZE_SCREEN_FRACTION).coerceAtLeast(MIN_SIZE_FLOOR_DP).dp
    val maxSize = (screenWidthDp * MAX_SIZE_SCREEN_FRACTION).dp

    // Icon row scales down linearly with container size so all 3 buttons stay visible even
    // at the minimum zoomed-out size, instead of clipping the third icon at a fixed 48dp.
    val sizeFraction = ((localSize - ICON_SCALE_SIZE_RANGE.start) /
        (ICON_SCALE_SIZE_RANGE.endInclusive - ICON_SCALE_SIZE_RANGE.start)).coerceIn(0f, 1f)
    val iconButtonSize = ICON_BUTTON_MIN_SIZE + (ICON_BUTTON_MAX_SIZE - ICON_BUTTON_MIN_SIZE) * sizeFraction
    val iconSize = ICON_MIN_SIZE + (ICON_MAX_SIZE - ICON_MIN_SIZE) * sizeFraction

    Box(
        modifier = Modifier
            .offset { IntOffset(localPosition.x.roundToInt(), localPosition.y.roundToInt()) }
            .size(localSize)
            // The 3D content is a SurfaceView under the hood - rounded border/background alone
            // only draw decoration, they don't clip child content, so the surface's square
            // corners poked out past the rounded border. Clipping the Box itself fixes that.
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .pointerInput(instance.id, instance.interactionMode) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Resync local mirrors at the start of every gesture in case the instance
                    // changed from elsewhere (e.g. another container's drag triggering the
                    // shared list update) since the last commit.
                    localPosition = latestInstance.position
                    localSize = latestInstance.size
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                if (instance.interactionMode) {
                                    zoomScale = (zoomScale * zoom).coerceIn(0.2f, 5f)
                                } else {
                                    // Mutate local state only while dragging - this recomposes
                                    // just this container per pointer-move sample instead of
                                    // hoisting to MainScreen's shared instances list (and
                                    // recomposing every container on screen) on every frame.
                                    localSize = (localSize.value * zoom)
                                        .coerceIn(minSize.value, maxSize.value).dp
                                }
                                pressed.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1) {
                            val change = pressed[0]
                            val drag = change.positionChange()
                            if (drag != Offset.Zero) {
                                if (instance.interactionMode) {
                                    rotationY += drag.x * ROTATION_SENSITIVITY
                                    rotationX = (rotationX - drag.y * ROTATION_SENSITIVITY)
                                        .coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES)
                                } else {
                                    localPosition += drag
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    // Commit once, on gesture end, instead of on every move/zoom sample.
                    if (!instance.interactionMode) {
                        onMove(instance.id, localPosition)
                        onResize(instance.id, localSize)
                    }
                }
            }
    ) {
        ModelViewer3D(
            modelPath = instance.modelPath,
            rotationX = rotationX,
            rotationY = rotationY,
            zoomScale = zoomScale,
            labelsVisible = instance.labelsVisible,
            engine = engine,
            modelLoader = modelLoader,
            modifier = Modifier.padding(bottom = 36.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onToggleInteraction(instance.id) }, modifier = Modifier.size(iconButtonSize)) {
                Icon(
                    imageVector = Icons.Default.Rotate90DegreesCcw,
                    contentDescription = "Toggle interaction mode",
                    modifier = Modifier.size(iconSize),
                    tint = if (instance.interactionMode) MaterialTheme.colorScheme.primary else Color.White
                )
            }
            IconButton(onClick = { onToggleLabels(instance.id) }, modifier = Modifier.size(iconButtonSize)) {
                Icon(
                    imageVector = Icons.Default.Label,
                    contentDescription = "Label",
                    modifier = Modifier.size(iconSize),
                    tint = if (instance.labelsVisible) MaterialTheme.colorScheme.primary else Color.White
                )
            }
            IconButton(onClick = { onClose(instance.id) }, modifier = Modifier.size(iconButtonSize)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(iconSize),
                    tint = Color.White
                )
            }
        }
    }
}
