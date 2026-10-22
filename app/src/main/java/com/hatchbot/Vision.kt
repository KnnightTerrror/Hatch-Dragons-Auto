package com.hatchbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Finds template pictures on a screenshot. Templates are crops of full-size screenshots from the
 * same phone; both sides are shrunk by [SCALE] before matching to keep it fast.
 */
class Vision(context: Context, names: Set<String>) {
    private val templates: Map<String, Mat>

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        templates = names.associateWith { name ->
            val bitmap = context.assets.open("bot/templates/$name.png").use { BitmapFactory.decodeStream(it) }
            toMat(bitmap).also { bitmap.recycle() }
        }
    }

    fun has(name: String) = name in templates

    /** Converts a screenshot for matching. The caller must release() the result. */
    fun prepare(screenshot: Bitmap): Mat = toMat(screenshot)

    fun find(screen: Mat, name: String, threshold: Double): PointF? =
        findAll(screen, name, threshold, 1).firstOrNull()

    /** Centers of up to [max] places where [name] appears, best match first, in full-size screen pixels. */
    fun findAll(screen: Mat, name: String, threshold: Double, max: Int): List<PointF> {
        val template = templates[name] ?: return emptyList()
        if (template.cols() > screen.cols() || template.rows() > screen.rows()) return emptyList()

        val scores = Mat()
        Imgproc.matchTemplate(screen, template, scores, Imgproc.TM_CCOEFF_NORMED)
        val w = template.cols().toDouble()
        val h = template.rows().toDouble()
        val hits = mutableListOf<PointF>()
        while (hits.size < max) {
            val best = Core.minMaxLoc(scores)
            if (best.maxVal < threshold) break
            val x = best.maxLoc.x
            val y = best.maxLoc.y
            hits += PointF(((x + w / 2) / SCALE).toFloat(), ((y + h / 2) / SCALE).toFloat())
            // Blank out this spot so the next pass finds a different one.
            Imgproc.rectangle(scores, Point(x - w / 2, y - h / 2), Point(x + w / 2, y + h / 2), Scalar(-1.0), -1)
        }
        scores.release()
        return hits
    }

    private fun toMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), SCALE, SCALE, Imgproc.INTER_AREA)
        rgb.release()
        return small
    }

    companion object {
        const val SCALE = 0.5
    }
}
