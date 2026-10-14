package com.rendyhd.vicu.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/** A spring as the token file gives it: damping ratio and stiffness (Compose `spring`). */
class VicuSpring(val damping: Float, val stiffness: Float) {
    fun <T> spec(): FiniteAnimationSpec<T> = spring(dampingRatio = damping, stiffness = stiffness)
}

/**
 * The Vicu motion tokens (test-fixtures/design-tokens-v1.json, "motion") and the six spec
 * functions of a Material 3 motion scheme. MotionScheme itself is internal in Compose
 * Multiplatform material3 1.9.0 (and MaterialTheme has no public motionScheme parameter), so
 * this object has the same function names but does not implement the interface or enter
 * MaterialTheme; components read it directly. DesignTokensTest compares every value with the
 * fixture. Docs: docs/design-system-v1.md, section 6. Every animation also needs a reduced
 * variant (system animator scale).
 */
object VicuMotion {
    // The six scheme slots ("motionScheme").
    val fastSpatial = VicuSpring(damping = 0.6f, stiffness = 800f)
    val defaultSpatial = VicuSpring(damping = 0.9f, stiffness = 700f)
    val slowSpatial = VicuSpring(damping = 0.9f, stiffness = 300f)
    val fastEffects = VicuSpring(damping = 1f, stiffness = 3800f)
    val defaultEffects = VicuSpring(damping = 1f, stiffness = 1600f)
    val slowEffects = VicuSpring(damping = 1f, stiffness = 800f)

    // Named motions: fade.fast, fade.base, move, pop use the scheme springs; moveExpressive is its own.
    val fadeFastMs = 150
    val fadeBaseMs = 240
    val moveMs = 320
    val moveExpressiveMs = 440
    val popMs = 360
    val fadeFast = fastEffects
    val fadeBase = defaultEffects
    val move = defaultSpatial
    val moveExpressive = VicuSpring(damping = 0.8f, stiffness = 380f)
    val pop = fastSpatial

    // Page change, list stagger and the drawn marks.
    val pageOutMs = 90
    val pageInMs = 210
    val pageRisePx = 6
    val staggerMs = 35
    val staggerMaxItems = 5
    val keyboardMoveMaxMs = 120
    val checkDrawMs = 220
    val strikeDrawMs = 240

    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = defaultSpatial.spec()
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = fastSpatial.spec()
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = slowSpatial.spec()
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = defaultEffects.spec()
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = fastEffects.spec()
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = slowEffects.spec()
}
