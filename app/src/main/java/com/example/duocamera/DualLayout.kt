package com.example.duocamera

import androidx.camera.core.CompositionSettings

enum class CaptureMode { PHOTO, VIDEO }

/** How the two camera images are arranged, for both photos and videos. */
enum class DualLayout(val labelRes: Int) {
    PIP(R.string.layout_pip),
    TOP_BOTTOM(R.string.layout_top_bottom),
    SIDE_BY_SIDE(R.string.layout_side_by_side);

    fun next(): DualLayout = entries[(ordinal + 1) % entries.size]
}

/**
 * Where each camera goes in a dual *video*. CameraX draws both cameras into one
 * frame using these settings, then rotates the result to portrait.
 *
 * Coordinates: offsets are normalized (-1..1, centre = 0, +x right, +y up) and scale
 * is relative to the full frame. Because rotation happens AFTER composition, these
 * values are in the camera sensor's landscape frame: an X offset becomes a vertical
 * shift in the portrait video and a Y offset becomes a horizontal one.
 *
 * The split layouts use a uniform 0.5 scale so nobody gets stretched, which leaves
 * black bars around each half. These are the values to tune on a real phone: if the
 * halves land in the wrong order or orientation, swap the signs or swap X and Y.
 */
object VideoComposition {

    /** Returns (main camera settings, inset camera settings). Main is drawn first, inset on top. */
    fun forLayout(layout: DualLayout): Pair<CompositionSettings, CompositionSettings> =
        when (layout) {
            // Values from Google's CameraX picture-in-picture sample: main fills the frame,
            // inset at 1/3 size in a corner.
            DualLayout.PIP ->
                settings(0f, 0f, 1f) to settings(2 / 3f - 0.1f, -2 / 3f + 0.1f, 1 / 3f)
            // Landscape left/right halves -> portrait top/bottom after rotation.
            DualLayout.TOP_BOTTOM ->
                settings(-0.5f, 0f, 0.5f) to settings(0.5f, 0f, 0.5f)
            // Landscape top/bottom halves -> portrait left/right after rotation.
            DualLayout.SIDE_BY_SIDE ->
                settings(0f, 0.5f, 0.5f) to settings(0f, -0.5f, 0.5f)
        }

    private fun settings(offsetX: Float, offsetY: Float, scale: Float): CompositionSettings =
        CompositionSettings.Builder()
            .setAlpha(1f)
            .setOffset(offsetX, offsetY)
            .setScale(scale, scale)
            .build()
}
