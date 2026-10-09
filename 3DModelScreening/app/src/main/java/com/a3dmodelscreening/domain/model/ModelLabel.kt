package com.a3dmodelscreening.domain.model

/**
 * A part label extracted from a GLB node's `extras.prop`.
 * [entity] is the live Filament entity id backing the node, used every frame
 * to read the node's current world transform (not a cached translation) so the
 * label tracks the part through rotation/zoom.
 */
data class ModelLabel(
    val id: String,
    val text: String,
    val entity: Int
)
