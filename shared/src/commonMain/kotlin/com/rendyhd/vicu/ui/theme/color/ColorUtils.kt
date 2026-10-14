/*
 * Copyright 2021 Google LLC (the algorithms)
 * Port to Kotlin for Vicu, 2026, of parts of @material/material-color-utilities 0.4.0
 * (utils/color_utils and utils/math_utils): only what Blend.harmonize needs.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rendyhd.vicu.ui.theme.color

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** ARGB colour helpers (packed Int, 0xAARRGGBB) and the L*, XYZ and linear RGB conversions. */
internal object ColorUtils {
    private val SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478),
    )

    val WHITE_POINT_D65 = doubleArrayOf(95.047, 100.0, 108.883)

    fun argbFromRgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)

    fun argbFromLinrgb(linrgb: DoubleArray): Int =
        argbFromRgb(delinearized(linrgb[0]), delinearized(linrgb[1]), delinearized(linrgb[2]))

    fun redFromArgb(argb: Int): Int = (argb shr 16) and 255

    fun greenFromArgb(argb: Int): Int = (argb shr 8) and 255

    fun blueFromArgb(argb: Int): Int = argb and 255

    fun xyzFromArgb(argb: Int): DoubleArray {
        val r = linearized(redFromArgb(argb))
        val g = linearized(greenFromArgb(argb))
        val b = linearized(blueFromArgb(argb))
        return MathUtils.matrixMultiply(doubleArrayOf(r, g, b), SRGB_TO_XYZ)
    }

    fun argbFromLstar(lstar: Double): Int {
        val component = delinearized(yFromLstar(lstar))
        return argbFromRgb(component, component, component)
    }

    fun lstarFromArgb(argb: Int): Double {
        val y = xyzFromArgb(argb)[1]
        return 116.0 * labF(y / 100.0) - 16.0
    }

    fun yFromLstar(lstar: Double): Double = 100.0 * labInvf((lstar + 16.0) / 116.0)

    fun linearized(rgbComponent: Int): Double {
        val normalized = rgbComponent / 255.0
        return if (normalized <= 0.040449936) {
            normalized / 12.92 * 100.0
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4) * 100.0
        }
    }

    fun delinearized(rgbComponent: Double): Int {
        val normalized = rgbComponent / 100.0
        val delinearized = if (normalized <= 0.0031308) {
            normalized * 12.92
        } else {
            1.055 * normalized.pow(1.0 / 2.4) - 0.055
        }
        // JavaScript's Math.round rounds halves up, which floor(x + 0.5) reproduces.
        return MathUtils.clampInt(0, 255, floor(delinearized * 255.0 + 0.5).toInt())
    }

    private fun labF(t: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        return if (t > e) t.pow(1.0 / 3.0) else (kappa * t + 16) / 116
    }

    private fun labInvf(ft: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val ft3 = ft * ft * ft
        return if (ft3 > e) ft3 else (116 * ft - 16) / kappa
    }
}

internal object MathUtils {
    fun signum(num: Double): Double = if (num < 0) -1.0 else if (num == 0.0) 0.0 else 1.0

    fun lerp(start: Double, stop: Double, amount: Double): Double = (1.0 - amount) * start + amount * stop

    fun clampInt(min: Int, max: Int, input: Int): Int = if (input < min) min else if (input > max) max else input

    fun sanitizeDegreesDouble(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) d += 360.0
        return d
    }

    fun rotationDirection(from: Double, to: Double): Double {
        val increasingDifference = sanitizeDegreesDouble(to - from)
        return if (increasingDifference <= 180.0) 1.0 else -1.0
    }

    fun differenceDegrees(a: Double, b: Double): Double = 180.0 - abs(abs(a - b) - 180.0)

    fun matrixMultiply(row: DoubleArray, matrix: Array<DoubleArray>): DoubleArray {
        val a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2]
        val b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2]
        val c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2]
        return doubleArrayOf(a, b, c)
    }
}
