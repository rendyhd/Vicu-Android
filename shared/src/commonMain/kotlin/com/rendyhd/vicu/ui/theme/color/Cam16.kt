/*
 * Copyright 2021 Google LLC (the algorithms)
 * Port to Kotlin for Vicu, 2026, of parts of @material/material-color-utilities 0.4.0
 * (hct/cam16 and hct/viewing_conditions): the default viewing conditions and the
 * sRGB to CAM16 direction, which is all Blend.harmonize needs.
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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** The CAM16 viewing conditions; only [DEFAULT] (sRGB, D65, background L* 50, average surround) is used. */
internal class ViewingConditions private constructor(
    val n: Double,
    val aw: Double,
    val nbb: Double,
    val ncb: Double,
    val c: Double,
    val nc: Double,
    val rgbD: DoubleArray,
    val fl: Double,
    val fLRoot: Double,
    val z: Double,
) {
    companion object {
        val DEFAULT: ViewingConditions = make()

        private fun make(): ViewingConditions {
            val whitePoint = ColorUtils.WHITE_POINT_D65
            val adaptingLuminance = (200.0 / PI) * ColorUtils.yFromLstar(50.0) / 100.0
            val backgroundLstar = 50.0
            val surround = 2.0
            val xyz = whitePoint
            val rW = xyz[0] * 0.401288 + xyz[1] * 0.650173 + xyz[2] * -0.051461
            val gW = xyz[0] * -0.250268 + xyz[1] * 1.204414 + xyz[2] * 0.045854
            val bW = xyz[0] * -0.002079 + xyz[1] * 0.048952 + xyz[2] * 0.953127
            val f = 0.8 + surround / 10.0
            val c = if (f >= 0.9) {
                MathUtils.lerp(0.59, 0.69, (f - 0.9) * 10.0)
            } else {
                MathUtils.lerp(0.525, 0.59, (f - 0.8) * 10.0)
            }
            var d = f * (1.0 - (1.0 / 3.6) * exp((-adaptingLuminance - 42.0) / 92.0))
            d = if (d > 1.0) 1.0 else if (d < 0.0) 0.0 else d
            val nc = f
            val rgbD = doubleArrayOf(
                d * (100.0 / rW) + 1.0 - d,
                d * (100.0 / gW) + 1.0 - d,
                d * (100.0 / bW) + 1.0 - d,
            )
            val k = 1.0 / (5.0 * adaptingLuminance + 1.0)
            val k4 = k * k * k * k
            val k4F = 1.0 - k4
            val fl = k4 * adaptingLuminance + 0.1 * k4F * k4F * cbrt(5.0 * adaptingLuminance)
            val n = ColorUtils.yFromLstar(backgroundLstar) / whitePoint[1]
            val z = 1.48 + sqrt(n)
            val nbb = 0.725 / n.pow(0.2)
            val ncb = nbb
            val rgbAFactors = doubleArrayOf(
                ((fl * rgbD[0] * rW) / 100.0).pow(0.42),
                ((fl * rgbD[1] * gW) / 100.0).pow(0.42),
                ((fl * rgbD[2] * bW) / 100.0).pow(0.42),
            )
            val rgbA = doubleArrayOf(
                (400.0 * rgbAFactors[0]) / (rgbAFactors[0] + 27.13),
                (400.0 * rgbAFactors[1]) / (rgbAFactors[1] + 27.13),
                (400.0 * rgbAFactors[2]) / (rgbAFactors[2] + 27.13),
            )
            val aw = (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb
            return ViewingConditions(n, aw, nbb, ncb, c, nc, rgbD, fl, fl.pow(0.25), z)
        }
    }
}

/** The hue and chroma of an sRGB colour in CAM16 under the default viewing conditions. */
internal class Cam16(val hue: Double, val chroma: Double) {
    companion object {
        fun fromInt(argb: Int): Cam16 {
            val vc = ViewingConditions.DEFAULT
            val redL = ColorUtils.linearized(ColorUtils.redFromArgb(argb))
            val greenL = ColorUtils.linearized(ColorUtils.greenFromArgb(argb))
            val blueL = ColorUtils.linearized(ColorUtils.blueFromArgb(argb))
            val x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL
            val y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL
            val z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL

            val rC = 0.401288 * x + 0.650173 * y - 0.051461 * z
            val gC = -0.250268 * x + 1.204414 * y + 0.045854 * z
            val bC = -0.002079 * x + 0.048952 * y + 0.953127 * z

            val rD = vc.rgbD[0] * rC
            val gD = vc.rgbD[1] * gC
            val bD = vc.rgbD[2] * bC

            val rAF = ((vc.fl * abs(rD)) / 100.0).pow(0.42)
            val gAF = ((vc.fl * abs(gD)) / 100.0).pow(0.42)
            val bAF = ((vc.fl * abs(bD)) / 100.0).pow(0.42)
            val rA = (MathUtils.signum(rD) * 400.0 * rAF) / (rAF + 27.13)
            val gA = (MathUtils.signum(gD) * 400.0 * gAF) / (gAF + 27.13)
            val bA = (MathUtils.signum(bD) * 400.0 * bAF) / (bAF + 27.13)

            val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
            val b = (rA + gA - 2.0 * bA) / 9.0
            val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
            val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0

            val atanDegrees = (atan2(b, a) * 180.0) / PI
            val hue = MathUtils.sanitizeDegreesDouble(atanDegrees)

            val ac = p2 * vc.nbb
            val j = 100.0 * (ac / vc.aw).pow(vc.c * vc.z)

            val huePrime = if (hue < 20.14) hue + 360 else hue
            val eHue = 0.25 * (cos((huePrime * PI) / 180.0 + 2.0) + 3.8)
            val p1 = (50000.0 / 13.0) * eHue * vc.nc * vc.ncb
            val t = (p1 * sqrt(a * a + b * b)) / (u + 0.305)
            val alpha = t.pow(0.9) * (1.64 - 0.29.pow(vc.n)).pow(0.73)
            val chroma = alpha * sqrt(j / 100.0)
            return Cam16(hue, chroma)
        }
    }
}
