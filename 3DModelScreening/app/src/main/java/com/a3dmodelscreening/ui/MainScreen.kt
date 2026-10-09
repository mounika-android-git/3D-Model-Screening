package com.a3dmodelscreening.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.a3dmodelscreening.domain.model.ModelInstance
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.rememberModelLoader
import java.util.UUID
import kotlin.random.Random

private val DEFAULT_SIZE = 220.dp
private val SPAWN_STEP = 40f
private const val DOODLE_SPACING = 96f

/** Result of attempting to stand up Filament's GPU engine once, up front. */
private sealed interface EngineInit {
    data class Ready(val engine: Engine) : EngineInit
    data object Unavailable : EngineInit
}

/**
 * Creates the Filament Engine with its own unshared EGL context (`Engine.create()`), wrapped
 * in try/catch so a device that can't stand up a GPU context falls back to a message instead
 * of crashing composition.
 *
 * `Filament.init()`/`Gltfio.init()` are what actually call `System.loadLibrary(...)` for
 * Filament's native libraries, inside a static initializer that only runs the first time
 * something touches those classes. SceneView's own helpers (`createEglContext()`) trigger this
 * as a side effect internally; calling `Engine.create()` directly here does not go through
 * that path, so it must be done explicitly - omitting it is what caused
 * `UnsatisfiedLinkError: No implementation found for ... Engine.nCreateBuilder()` (confirmed via
 * `nm` on the installed `.so`: the symbol is present on disk, it just was never loaded into the
 * process).
 */
@Composable
private fun rememberEngineInit(): EngineInit {
    val init = remember {
        try {
            // Same bootstrap order SceneView's own internal `filamentInit` uses.
            // Gltfio.init() also calls Filament.init() internally, but it's called explicitly
            // too for clarity.
            Gltfio.init()
            Filament.init()
            Utils.init()
            EngineInit.Ready(Engine.create())
        } catch (e: Throwable) {
            EngineInit.Unavailable
        }
    }
    DisposableEffect(init) {
        onDispose {
            if (init is EngineInit.Ready) {
                init.engine.destroy()
            }
        }
    }
    return init
}

/**
 * The single screen of the app. Owns the list of ModelInstance state and
 * mutates it by ID; rendering and gesture handling live in ModelContainer.
 */
@Composable
@Preview(showBackground = true)
fun MainScreen() {
    // One Engine/ModelLoader shared by every model instance: creating a Filament Engine
    // is the expensive part (GPU context, shader cache); each instance still gets its own
    // ModelInstance/SceneView surface since Compose needs independently positioned renders.
    when (val engineInit = rememberEngineInit()) {
        is EngineInit.Unavailable -> UnsupportedDeviceMessage()
        is EngineInit.Ready -> MainScreenContent(engineInit.engine)
    }
}

@Composable
private fun UnsupportedDeviceMessage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "3D rendering isn't supported on this device.",
            modifier = Modifier.wrapContentSize().padding(24.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun MainScreenContent(engine: Engine) {
    var instances by remember { mutableStateOf<List<ModelInstance>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    val modelLoader = rememberModelLoader(engine)

    fun updateInstance(id: String, transform: (ModelInstance) -> ModelInstance) {
        instances = instances.map { if (it.id == id) transform(it) else it }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showPicker = true }) {
                Text("Add Model")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            DoodleBackground()

            instances.forEach { instance ->
                key(instance.id) {
                    ModelContainer(
                        instance = instance,
                        engine = engine,
                        modelLoader = modelLoader,
                        onMove = { id, newPosition ->
                            updateInstance(id) { it.copy(position = newPosition) }
                        },
                        onResize = { id, newSize ->
                            updateInstance(id) { it.copy(size = newSize) }
                        },
                        onToggleInteraction = { id ->
                            updateInstance(id) { it.copy(interactionMode = !it.interactionMode) }
                        },
                        onToggleLabels = { id ->
                            updateInstance(id) { it.copy(labelsVisible = !it.labelsVisible) }
                        },
                        onClose = { id ->
                            instances = instances.filterNot { it.id == id }
                        }
                    )
                }
            }
        }
    }

    if (showPicker) {
        ModelPicker(
            onDismiss = { showPicker = false },
            onSelect = { entry ->
                val index = instances.size
                val offsetStep = SPAWN_STEP * (index % 5)
                instances = instances + ModelInstance(
                    id = UUID.randomUUID().toString(),
                    displayName = entry.displayName,
                    modelPath = entry.assetPath,
                    position = Offset(60f + offsetStep, 120f + offsetStep),
                    size = DEFAULT_SIZE,
                    interactionMode = false
                )
            }
        )
    }
}

/**
 * Subtle hand-drawn-style dots/sparkles/squiggles tiled across the screen, behind the model
 * containers. Fixed random seed so the pattern is stable across recompositions, computed once
 * per size change rather than redrawn with new positions every frame.
 */
@Composable
private fun DoodleBackground(modifier: Modifier = Modifier) {
    val dotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val random = Random(7)
        var shapeIndex = 0
        var y = DOODLE_SPACING / 2
        while (y < size.height) {
            var x = DOODLE_SPACING / 2
            while (x < size.width) {
                val cx = x + random.nextFloat() * 24f - 12f
                val cy = y + random.nextFloat() * 24f - 12f
                when (shapeIndex % 3) {
                    0 -> drawCircle(
                        color = dotColor,
                        radius = 10f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2f)
                    )
                    1 -> {
                        drawLine(lineColor, Offset(cx - 10f, cy), Offset(cx + 10f, cy), strokeWidth = 2f)
                        drawLine(lineColor, Offset(cx, cy - 10f), Offset(cx, cy + 10f), strokeWidth = 2f)
                    }
                    else -> {
                        val path = Path().apply {
                            moveTo(cx - 12f, cy + 6f)
                            quadraticBezierTo(cx, cy - 14f, cx + 12f, cy + 6f)
                        }
                        drawPath(path, color = lineColor, style = Stroke(width = 2f))
                    }
                }
                shapeIndex++
                x += DOODLE_SPACING
            }
            y += DOODLE_SPACING
        }
    }
}
