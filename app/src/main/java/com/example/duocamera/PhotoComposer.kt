package com.example.duocamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Combines the main and inset photos into one image, matching the on-screen layout. */
object PhotoComposer {

    private const val MAX_EDGE = 2560

    fun compose(main: Bitmap, inset: Bitmap, layout: DualLayout): Bitmap {
        // The saved photo keeps the main camera's aspect ratio (3:4 portrait).
        val scale = min(1f, MAX_EDGE.toFloat() / max(main.width, main.height))
        val w = (main.width * scale).roundToInt()
        val h = (main.height * scale).roundToInt()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat()
        val hf = h.toFloat()

        when (layout) {
            DualLayout.PIP -> {
                drawCenterCrop(canvas, main, RectF(0f, 0f, wf, hf), paint)
                // Inset: 32% of the width, 3:4, top-right corner — same as the viewfinder.
                val margin = wf * 0.04f
                val pipW = wf * 0.32f
                val pipH = pipW * 4f / 3f
                val rect = RectF(wf - margin - pipW, margin, wf - margin, margin + pipH)
                val radius = pipW * 0.08f
                val border = max(3f, wf * 0.006f)

                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val borderRect = RectF(rect).apply { inset(-border, -border) }
                canvas.drawRoundRect(borderRect, radius + border, radius + border, borderPaint)

                canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
                drawCenterCrop(canvas, inset, rect, paint)
                canvas.restore()
            }
            DualLayout.TOP_BOTTOM -> {
                drawCenterCrop(canvas, main, RectF(0f, 0f, wf, hf / 2f), paint)
                drawCenterCrop(canvas, inset, RectF(0f, hf / 2f, wf, hf), paint)
                drawDivider(canvas, 0f, hf / 2f, wf, hf / 2f, wf)
            }
            DualLayout.SIDE_BY_SIDE -> {
                drawCenterCrop(canvas, main, RectF(0f, 0f, wf / 2f, hf), paint)
                drawCenterCrop(canvas, inset, RectF(wf / 2f, 0f, wf, hf), paint)
                drawDivider(canvas, wf / 2f, 0f, wf / 2f, hf, wf)
            }
        }
        return out
    }

    /** Rotates a camera frame upright and mirrors selfies so they match the preview. */
    fun upright(source: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirror) return source
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result != source) source.recycle()
        return result
    }

    /** Draws [bmp] to fill [dst], cropping the edges instead of stretching. */
    private fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, dst: RectF, paint: Paint) {
        val dstAspect = dst.width() / dst.height()
        val srcAspect = bmp.width.toFloat() / bmp.height
        val src = if (srcAspect > dstAspect) {
            val cropW = bmp.height * dstAspect
            val left = (bmp.width - cropW) / 2f
            Rect(left.roundToInt(), 0, (left + cropW).roundToInt(), bmp.height)
        } else {
            val cropH = bmp.width / dstAspect
            val top = (bmp.height - cropH) / 2f
            Rect(0, top.roundToInt(), bmp.width, (top + cropH).roundToInt())
        }
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    private fun drawDivider(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = max(2f, width * 0.004f)
        }
        canvas.drawLine(x1, y1, x2, y2, p)
    }
}
