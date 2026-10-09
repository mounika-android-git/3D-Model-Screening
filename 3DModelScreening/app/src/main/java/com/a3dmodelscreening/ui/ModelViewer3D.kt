package com.a3dmodelscreening.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import com.a3dmodelscreening.domain.model.ModelLabel
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.SurfaceType
import org.json.JSONObject

private const val LABEL_OFFSET_X = 24f
private const val LABEL_OFFSET_Y = -24f
private val LABEL_BASE_TEXT_SIZE = 14.sp
private val LABEL_MIN_TEXT_SIZE = 9.sp
private val LABEL_MAX_TEXT_SIZE = 20.sp
private const val LABEL_EDGE_MARGIN = 8f
private const val LABEL_WIDTH_FRACTION = 0.55f
private const val LABEL_MAX_STACK_ATTEMPTS = 16

/**
 * Owns the 3D rendering responsibility for a single model instance:
 * GLB loading, node creation, transform (rotation/zoom), part labels, and cleanup.
 * Engine/modelLoader are passed in and shared across every model instance on screen
 * (the expensive Filament GPU context is created once); only the ModelInstance
 * (loaded geometry) and this composable's own SceneView surface are per-instance.
 */
@Composable
fun ModelViewer3D(
    modelPath: String,
    rotationX: Float,
    rotationY: Float,
    zoomScale: Float,
    labelsVisible: Boolean,
    engine: Engine,
    modelLoader: ModelLoader,
    modifier: Modifier = Modifier
) {
    val modelInstance = rememberModelInstance(modelLoader, modelPath)
    val cameraNode = rememberCameraNode(engine)

    // Extracted once per loaded model (keyed on the FilamentInstance identity), not every frame:
    // walk the instance's entities, keep only the ones with a usable extras.prop.
    val labels: List<ModelLabel> = remember(modelInstance) {
        val instance = modelInstance
        if (instance == null) {
            emptyList()
        } else {
            val asset = instance.asset
            instance.entities.toList().mapNotNull { entity ->
                val extrasJson = asset.getExtras(entity)
                val propText = extrasJson?.let { runCatching { JSONObject(it).optString("prop") }.getOrNull() }
                if (propText.isNullOrBlank()) {
                    null
                } else {
                    ModelLabel(id = "$modelPath#$entity", text = propText, entity = entity)
                }
            }
        }
    }
    val labelScreenStates: List<LabelScreenState> = remember(labels) { labels.map { LabelScreenState(it) } }
    val projector = remember { WorldToScreenProjector() }
    val renderInvalidator = rememberRenderInvalidator()

    // SceneView renders on demand and parks once the scene settles. Toggling labels on
    // is not a change the library can see (no node transform, no DSL content change), so
    // without this the onFrame projection below would simply never run and labels would
    // stay invisible until some unrelated gesture woke the render loop.
    LaunchedEffect(labelsVisible) {
        if (labelsVisible) renderInvalidator.requestRender()
    }

    Box(modifier = modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            // Default SurfaceType.Surface is a SurfaceView, which composites behind the normal
            // View/Compose layer and ignores Compose's clip() - that's why its square corners
            // poked out past the container's rounded border. TextureSurface is a regular View
            // and respects clipping like any other Compose content.
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            // The container/rotation/zoom gestures are owned by ModelContainer's own
            // pointerInput; the library's built-in orbit/pan/zoom camera manipulator would
            // otherwise consume the same touches and fight it, causing flicker and
            // misread gestures.
            cameraManipulator = null,
            renderInvalidator = renderInvalidator,
            onFrame = {
                if (labelsVisible && labelScreenStates.isNotEmpty()) {
                    val camera = engine.getCameraComponent(cameraNode.entity)
                    val viewport = cameraNode.viewport
                    if (camera != null && viewport != null) {
                        val transformManager = engine.transformManager
                        for (state in labelScreenStates) {
                            state.isVisible = projector.project(
                                camera = camera,
                                transformManager = transformManager,
                                entity = state.label.entity,
                                viewport = viewport,
                                out = state
                            ) && state.screenX in 0f..viewport.width.toFloat() &&
                                state.screenY in 0f..viewport.height.toFloat()
                        }
                    }
                }
            }
        ) {
            modelInstance?.let { instance ->
                // ModelNode's own `scale` param is ignored whenever `scaleToUnits` is set (the
                // library computes scale from the bounding box instead - see its KDoc), which is
                // why zoomScale never reached the model. A wrapping plain Node applies zoom as a
                // parent-space multiplier on top of that auto-fit scale instead.
                Node(scale = Float3(zoomScale, zoomScale, zoomScale)) {
                    ModelNode(
                        modelInstance = instance,
                        autoAnimate = true,
                        scaleToUnits = 1.0f,
                        centerOrigin = Float3(0f, 0f, 0f),
                        rotation = Float3(rotationX, rotationY, 0f)
                    )
                }
            }
        }

        if (labelsVisible) {
            LabelOverlay(labelScreenStates, zoomScale)
        }
    }
}

/**
 * Lightweight 2D overlay: a single Canvas draws every label's connector line and text,
 * instead of one Composable per label. Reading the per-label mutable state inside the
 * draw phase means only this Canvas redraws when positions change each frame - the rest
 * of the UI tree does not recompose.
 */
@Composable
private fun LabelOverlay(labelScreenStates: List<LabelScreenState>, zoomScale: Float) {
    val textPaint = remember {
        Paint().apply {
            color = Color.White.toArgb()
            isAntiAlias = true
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Text size follows the same zoomScale that already drives the model's own scale
        // (see the Node wrapper above) - no separate zoom system. Clamped so labels stay
        // readable at extreme zoom-out and don't get huge at extreme zoom-in.
        val textSizePx = (LABEL_BASE_TEXT_SIZE.toPx() * zoomScale)
            .coerceIn(LABEL_MIN_TEXT_SIZE.toPx(), LABEL_MAX_TEXT_SIZE.toPx())
        textPaint.textSize = textSizePx
        val lineHeight = textSizePx * 1.2f
        val maxLabelWidth = (size.width * LABEL_WIDTH_FRACTION).coerceAtLeast(textSizePx * 3f)
        val maxX = (size.width - LABEL_EDGE_MARGIN).coerceAtLeast(LABEL_EDGE_MARGIN)
        val maxY = (size.height - LABEL_EDGE_MARGIN).coerceAtLeast(LABEL_EDGE_MARGIN)

        // Anchors (screenX/screenY) are correct and independent per label - the overlap is
        // purely in the 2D text placement, since every label starts at the same fixed
        // anchor-relative offset. Declutter greedily: push a label's text down past any
        // already-placed label it would cover. List order is stable across frames (entity
        // extraction order), so labels don't jump around relative to each other while the
        // model moves - only the shared anchor positions do.
        val placedRects = ArrayList<Rect>(labelScreenStates.size)
        for (state in labelScreenStates) {
            if (!state.isVisible) continue
            val anchor = Offset(state.screenX, state.screenY)
            val lines = wrapLabelText(state.label.text, textPaint, maxLabelWidth)
            val blockWidth = lines.maxOf { textPaint.measureText(it) }
            val blockHeight = textSizePx + (lines.size - 1) * lineHeight

            var textX = state.screenX + LABEL_OFFSET_X
            var firstBaselineY = state.screenY + LABEL_OFFSET_Y

            // Keep the whole wrapped block inside the container without moving the anchor -
            // shift it back in by exactly the amount it overflows on any side.
            val right = textX + blockWidth
            if (right > maxX) textX -= (right - maxX)
            if (textX < LABEL_EDGE_MARGIN) textX = LABEL_EDGE_MARGIN
            val top = firstBaselineY - textSizePx
            val bottom = top + blockHeight
            if (bottom > maxY) firstBaselineY -= (bottom - maxY)
            if (firstBaselineY - textSizePx < LABEL_EDGE_MARGIN) firstBaselineY = LABEL_EDGE_MARGIN + textSizePx

            var attempts = 0
            while (attempts < LABEL_MAX_STACK_ATTEMPTS &&
                placedRects.any {
                    it.overlaps(
                        Rect(textX, firstBaselineY - textSizePx, textX + blockWidth, firstBaselineY - textSizePx + blockHeight)
                    )
                }
            ) {
                firstBaselineY += lineHeight
                attempts++
            }
            if (firstBaselineY - textSizePx + blockHeight > maxY) {
                firstBaselineY = maxY - blockHeight + textSizePx
            }
            placedRects.add(
                Rect(textX, firstBaselineY - textSizePx, textX + blockWidth, firstBaselineY - textSizePx + blockHeight)
            )

            drawCircle(color = Color.White, radius = 6f, center = anchor)
            drawLine(
                color = Color.White,
                start = anchor,
                end = Offset(textX, firstBaselineY),
                strokeWidth = 2f
            )
            lines.forEachIndexed { index, line ->
                drawContext.canvas.nativeCanvas.drawText(
                    line,
                    textX,
                    firstBaselineY + index * lineHeight,
                    textPaint
                )
            }
        }
    }
}

/** Greedy word-wrap so a label's text never grows wider than [maxWidth]. */
private fun wrapLabelText(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (paint.measureText(text) <= maxWidth) return listOf(text)
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isEmpty() || paint.measureText(candidate) <= maxWidth) {
            current = StringBuilder(candidate)
        } else {
            lines.add(current.toString())
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines.add(current.toString())
    return lines
}
