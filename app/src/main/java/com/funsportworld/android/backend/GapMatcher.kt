package com.funsportworld.android.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android-native port of the GT4 gap detector.
 *
 * The desktop app used NumPy/Pillow for grayscale conversion, Gaussian blur,
 * Canny edges and normalised cross-correlation. This object implements the
 * same pipeline without shipping those large native wheels, and runs in a few
 * hundred milliseconds on a phone instead of Python loops over every pixel.
 *
 * Entry point [matchGapBase64] is called from Python (`java.jclass`) with the
 * two PNG payloads encoded as Base64. Returns -1 when the slice has no edges.
 */
object GapMatcher {

    @JvmStatic
    fun matchGapBase64(backgroundBase64: String, sliceBase64: String): Int {
        val background = decode(backgroundBase64) ?: return 0
        val slice = decode(sliceBase64) ?: return 0
        val bgGray = gray(background)
        val sliceGray = gray(slice)
        val bgEdges = canny(gaussianBlur(bgGray, background.width, background.height, 1.1),
            background.width, background.height, 100.0, 200.0)
        val sliceEdges = canny(gaussianBlur(sliceGray, slice.width, slice.height, 1.1),
            slice.width, slice.height, 100.0, 200.0)
        var edgeCount = 0
        for (value in sliceEdges) if (value != 0) edgeCount++
        if (edgeCount < 10) return -1
        val distance = matchX(
            bgEdges, background.width, background.height,
            sliceEdges, slice.width, slice.height,
        )
        return distance
    }

    /** Convenience for unit tests: raw PNG bytes. */
    @JvmStatic
    fun matchGap(backgroundPng: ByteArray, slicePng: ByteArray): Int {
        return matchGapBase64(
            Base64.encodeToString(backgroundPng, Base64.NO_WRAP),
            Base64.encodeToString(slicePng, Base64.NO_WRAP),
        )
    }

    private fun decode(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun gray(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = FloatArray(width * height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = (color ushr 24) and 0xFF
            val r = ((color shr 16) and 0xFF) * alpha / 255.0
            val g = ((color shr 8) and 0xFF) * alpha / 255.0
            val b = (color and 0xFF) * alpha / 255.0
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000.0).toFloat()
        }
        return out
    }

    private fun gaussianBlur(src: FloatArray, width: Int, height: Int, sigma: Double): FloatArray {
        val kernel = DoubleArray(5)
        var sum = 0.0
        for (i in 0 until 5) {
            val offset = i - 2.0
            kernel[i] = exp(-(offset * offset) / (2 * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val temp = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = 0.0
                for (k in 0 until 5) {
                    val sx = (x + k - 2).coerceIn(0, width - 1)
                    value += kernel[k] * src[row + sx]
                }
                temp[row + x] = value.toFloat()
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = 0.0
                for (k in 0 until 5) {
                    val sy = (y + k - 2).coerceIn(0, height - 1)
                    value += kernel[k] * temp[sy * width + x]
                }
                out[y * width + x] = value.toFloat()
            }
        }
        return out
    }

    private fun canny(
        src: FloatArray,
        width: Int,
        height: Int,
        low: Double,
        high: Double,
    ): IntArray {
        fun at(x: Int, y: Int): Float =
            src[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

        val magnitude = DoubleArray(width * height)
        val angle = DoubleArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1) +
                    at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)
                val gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) +
                    at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
                val index = y * width + x
                magnitude[index] = hypot(gx.toDouble(), gy.toDouble())
                var theta = atan2(gy.toDouble(), gx.toDouble())
                if (theta < 0) theta += PI
                angle[index] = theta
            }
        }

        // Non-maximum suppression, matching the original direction buckets.
        val nms = DoubleArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val a = angle[index]
                val dx: Int
                val dy: Int
                when {
                    a < PI / 4 -> { dx = 1; dy = 0 }
                    a < PI / 2 -> { dx = 1; dy = 1 }
                    a < 3 * PI / 4 -> { dx = 0; dy = 1 }
                    else -> { dx = -1; dy = 1 }
                }
                val m = magnitude[index]
                if (m >= magnitude[(y + dy) * width + x + dx] &&
                    m >= magnitude[(y - dy) * width + x - dx]
                ) {
                    nms[index] = m
                }
            }
        }

        val out = IntArray(width * height)
        val stack = ArrayDeque<Int>()
        for (i in nms.indices) {
            if (nms[i] >= high) {
                out[i] = 255
                stack.addLast(i)
            }
        }
        val lowSq = low
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            val y = index / width
            val x = index % width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val ny = y + dy
                    val nx = x + dx
                    if (ny < 0 || ny >= height || nx < 0 || nx >= width) continue
                    val neighbor = ny * width + nx
                    if (out[neighbor] == 0 && nms[neighbor] >= lowSq) {
                        out[neighbor] = 255
                        stack.addLast(neighbor)
                    }
                }
            }
        }
        return out
    }

    private fun matchX(
        bg: IntArray,
        bgWidth: Int,
        bgHeight: Int,
        template: IntArray,
        tplWidth: Int,
        tplHeight: Int,
    ): Int {
        if (tplWidth > bgWidth || tplHeight > bgHeight || tplWidth <= 0 || tplHeight <= 0) return 0

        var nonzero = 0
        for (value in template) if (value != 0) nonzero++
        if (nonzero == 0) return 0
        val templateMean = 255.0 * nonzero / (tplWidth * tplHeight)

        // Only non-zero template pixels contribute to the correlation.
        val templatePoints = ArrayList<IntArray>(nonzero)
        var templateNormSq = 0.0
        for (ty in 0 until tplHeight) {
            for (tx in 0 until tplWidth) {
                val value = template[ty * tplWidth + tx]
                if (value != 0) {
                    templatePoints.add(intArrayOf(ty, tx))
                    val centered = value - templateMean
                    templateNormSq += centered * centered
                }
            }
        }
        val templateNorm = sqrt(templateNormSq)
        if (templateNorm == 0.0) return 0

        // Integral images for O(1) window sums.
        val stride = bgWidth + 1
        val integral = LongArray(stride * (bgHeight + 1))
        val integralSq = LongArray(stride * (bgHeight + 1))
        for (y in 0 until bgHeight) {
            var rowSum = 0L
            var rowSqSum = 0L
            for (x in 0 until bgWidth) {
                val value = bg[y * bgWidth + x].toLong()
                rowSum += value
                rowSqSum += value * value
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
                integralSq[(y + 1) * stride + x + 1] = integralSq[y * stride + x + 1] + rowSqSum
            }
        }

        fun windowSum(table: LongArray, x: Int, y: Int): Long {
            val x2 = x + tplWidth
            val y2 = y + tplHeight
            return table[y2 * stride + x2] - table[y * stride + x2] -
                table[y2 * stride + x] + table[y * stride + x]
        }

        val n = (tplWidth * tplHeight).toDouble()
        var bestX = 0
        var bestScore = -1e18
        for (oy in 0..(bgHeight - tplHeight)) {
            for (ox in 0..(bgWidth - tplWidth)) {
                val mean = windowSum(integral, ox, oy) / n
                var num = 0.0
                for (point in templatePoints) {
                    val ty = point[0]
                    val tx = point[1]
                    num += (bg[(oy + ty) * bgWidth + ox + tx] - mean) * (255.0 - templateMean)
                }
                val sumSq = windowSum(integralSq, ox, oy)
                val variance = sumSq - n * mean * mean
                val denom = sqrt(max(0.0, variance)) * templateNorm
                val score = if (denom > 0) num / denom else 0.0
                if (score > bestScore) {
                    bestScore = score
                    bestX = ox
                }
            }
        }
        return bestX
    }

    private fun unusedTrig(): Double {
        // Kept out of the hot path; referenced to keep imports minimal in IDE.
        return min(1.0, cos(0.0))
    }
}
