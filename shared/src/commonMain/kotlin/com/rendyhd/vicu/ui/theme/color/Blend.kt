/*
 * Copyright 2021 Google LLC (the algorithm)
 * Port to Kotlin for Vicu, 2026, of @material/material-color-utilities 0.4.0 (blend/blend, harmonize
 * only, and hct/hct reduced to the hue, chroma and tone of an sRGB colour).
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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min

/** Hue, chroma and tone (CAM16 hue and chroma, L* tone) of an sRGB colour. */
internal class Hct private constructor(val hue: Double, val chroma: Double, val tone: Double, private val argb: Int) {
    fun toInt(): Int = argb

    companion object {
        fun from(hue: Double, chroma: Double, tone: Double): Hct = fromInt(HctSolver.solveToInt(hue, chroma, tone))

        fun fromInt(argb: Int): Hct {
            val cam = Cam16.fromInt(argb)
            return Hct(cam.hue, cam.chroma, ColorUtils.lstarFromArgb(argb), argb)
        }
    }
}

internal object Blend {
    /**
     * Shifts the hue of [designColor] towards [sourceColor] by half the hue difference, at most
     * 15 degrees, keeping chroma and tone (ARGB ints in and out).
     */
    fun harmonize(designColor: Int, sourceColor: Int): Int {
        val fromHct = Hct.fromInt(designColor)
        val toHct = Hct.fromInt(sourceColor)
        val differenceDegrees = MathUtils.differenceDegrees(fromHct.hue, toHct.hue)
        val rotationDegrees = min(differenceDegrees * 0.5, 15.0)
        val outputHue = MathUtils.sanitizeDegreesDouble(
            fromHct.hue + rotationDegrees * MathUtils.rotationDirection(fromHct.hue, toHct.hue),
        )
        return Hct.from(outputHue, fromHct.chroma, fromHct.tone).toInt()
    }

    /** [harmonize] for Compose colours; the alpha of [designColor] is kept (the maths is opaque). */
    fun harmonize(designColor: Color, sourceColor: Color): Color {
        val out = harmonize(designColor.toArgb(), sourceColor.toArgb())
        return Color(out).copy(alpha = designColor.alpha)
    }
}
