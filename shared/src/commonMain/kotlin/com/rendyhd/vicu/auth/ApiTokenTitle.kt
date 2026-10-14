package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.ApiTokenDto
import kotlin.random.Random

/**
 * Naming rules for the backup API token an install creates on the Vikunja server.
 *
 * Titles look like `Vicu — Samsung SM-S918B [a1b2c3]`. The bracketed suffix is a random id
 * generated once per install, so cleanup of stale tokens only ever touches tokens created by
 * this install. Two phones of the same model get different suffixes and no longer delete each
 * other's tokens.
 *
 * Tokens created before the suffix existed carry the bare device title (`Vicu — Samsung
 * SM-S918B`). Their owner cannot be told apart from another phone of the same model, so they
 * never match [isOwnedByInstall] and are left alone.
 */
internal object ApiTokenTitle {
    private const val APP_PREFIX = "Vicu"
    private const val INSTALL_ID_LENGTH = 6
    private const val HEX_DIGITS = "0123456789abcdef"

    fun newInstallId(random: Random = Random.Default): String =
        buildString(INSTALL_ID_LENGTH) {
            repeat(INSTALL_ID_LENGTH) { append(HEX_DIGITS[random.nextInt(HEX_DIGITS.length)]) }
        }

    fun isValidInstallId(id: String): Boolean =
        id.length == INSTALL_ID_LENGTH && id.all { it in HEX_DIGITS }

    /** [deviceTitle] is the platform's device title, for example `Vicu — Pixel 8`. */
    fun build(deviceTitle: String, installId: String): String = "$deviceTitle ${suffix(installId)}"

    fun isOwnedByInstall(title: String, installId: String): Boolean =
        isValidInstallId(installId) && title.startsWith(APP_PREFIX) && title.endsWith(suffix(installId))

    /** Ids of this install's tokens other than [keepId]: the stale ones to delete. */
    fun siblingIds(tokens: List<ApiTokenDto>, installId: String, keepId: Long): List<Long> =
        tokens
            .filter { it.id != keepId && isOwnedByInstall(it.title, installId) }
            .map { it.id }

    private fun suffix(installId: String): String = "[$installId]"
}
