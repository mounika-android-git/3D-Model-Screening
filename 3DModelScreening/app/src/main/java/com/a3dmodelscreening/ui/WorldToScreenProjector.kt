package com.a3dmodelscreening.ui

import com.google.android.filament.Camera
import com.google.android.filament.TransformManager
import com.google.android.filament.Viewport

/**
 * Projects a world-space point through a Filament camera's view/projection
 * matrices into pixel coordinates of its view's viewport.
 *
 * All scratch buffers are allocated once and reused across frames/labels -
 * this is called for every label on every frame, so it must not allocate.
 */
class WorldToScreenProjector {
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = DoubleArray(16)
    private val worldTransform = FloatArray(16)
    private val viewPoint = DoubleArray(4)
    private val clipPoint = DoubleArray(4)

    /** Reads the entity's current world position and projects it. Returns false if behind the camera. */
    fun project(
        camera: Camera,
        transformManager: TransformManager,
        entity: Int,
        viewport: Viewport,
        out: LabelScreenState
    ): Boolean {
        val transformInstance = transformManager.getInstance(entity)
        if (transformInstance == 0) return false
        transformManager.getWorldTransform(transformInstance, worldTransform)
        val wx = worldTransform[12].toDouble()
        val wy = worldTransform[13].toDouble()
        val wz = worldTransform[14].toDouble()

        camera.getViewMatrix(viewMatrix)
        camera.getProjectionMatrix(projectionMatrix)

        multiply(viewMatrix, wx, wy, wz, viewPoint)
        multiply(projectionMatrix, viewPoint[0], viewPoint[1], viewPoint[2], clipPoint)

        val w = clipPoint[3]
        if (w <= 0.0) return false

        val ndcX = clipPoint[0] / w
        val ndcY = clipPoint[1] / w
        out.screenX = ((ndcX * 0.5 + 0.5) * viewport.width).toFloat()
        out.screenY = ((1.0 - (ndcY * 0.5 + 0.5)) * viewport.height).toFloat()
        return true
    }

    private fun multiply(m: FloatArray, x: Double, y: Double, z: Double, out: DoubleArray) {
        for (i in 0..3) {
            out[i] = m[i] * x + m[4 + i] * y + m[8 + i] * z + m[12 + i]
        }
    }

    private fun multiply(m: DoubleArray, x: Double, y: Double, z: Double, out: DoubleArray) {
        for (i in 0..3) {
            out[i] = m[i] * x + m[4 + i] * y + m[8 + i] * z + m[12 + i]
        }
    }
}
