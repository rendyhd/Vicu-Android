package com.rendyhd.vicu.util.pro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Pro's four layers (docs/cross-app-semantics-v1.md section 21.1). */
enum class ProLayer(val key: String) {
    AVAILABILITY("availability"),
    PROMISES("promises"),
    PORTFOLIO("portfolio"),
    POWER("power"),
}

/** Every layer is on while Pro is on, until the person turns one off. */
data class ProLayers(
    val availability: Boolean = true,
    val promises: Boolean = true,
    val portfolio: Boolean = true,
    val power: Boolean = true,
) {
    fun isOn(layer: ProLayer): Boolean = when (layer) {
        ProLayer.AVAILABILITY -> availability
        ProLayer.PROMISES -> promises
        ProLayer.PORTFOLIO -> portfolio
        ProLayer.POWER -> power
    }

    companion object {
        val DEFAULT = ProLayers()
    }
}

/** The device settings Pro reads (the desktop's `AppConfig`, here the behaviour preferences). */
data class ProSwitches(
    val proEnabled: Boolean = false,
    val proLayers: ProLayers = ProLayers.DEFAULT,
    val standaloneMode: Boolean = false,
) {
    companion object {
        /** From the stored keys `pro_enabled`, `pro_layers` and `standalone_mode`; only `true` turns a switch on. */
        fun read(config: JsonObject): ProSwitches = ProSwitches(
            proEnabled = config["pro_enabled"].asFlag() == true,
            proLayers = ProSettings.readLayers(config["pro_layers"]),
            standaloneMode = config["standalone_mode"].asFlag() == true,
        )
    }
}

/** A project as the area and focus rules see it. */
data class AreaProject(val id: Long, val parentProjectId: Long? = null)

/**
 * Pro's switch, layers and area (docs/cross-app-semantics-v1.md section 21.1), the same as the
 * desktop's `src/shared/pro/settings.ts`. The switch and the layers are per device; the area is the
 * synced preference `pro.area`. Off is today's Vicu: nothing here may change what a device without
 * Pro shows.
 */
object ProSettings {
    /** The synced key of the area: the project categories promises and the portfolio apply in. */
    const val AREA_KEY = "pro.area"

    /** The layers as stored: a missing or unreadable layer is on. */
    fun readLayers(value: JsonElement?): ProLayers {
        val stored = value as? JsonObject ?: return ProLayers.DEFAULT
        fun on(layer: ProLayer) = stored[layer.key].asFlag() != false
        return ProLayers(on(ProLayer.AVAILABILITY), on(ProLayer.PROMISES), on(ProLayer.PORTFOLIO), on(ProLayer.POWER))
    }

    /**
     * Pro is on: the switch is on and the device is not in standalone mode (Pro needs a server).
     * With a [layer], that layer is on too.
     */
    fun isEnabled(config: ProSwitches?, layer: ProLayer? = null): Boolean {
        if (config == null || !config.proEnabled || config.standaloneMode) return false
        return layer?.let(config.proLayers::isOn) ?: true
    }

    /** The category ids of the area; none (or an unreadable value) means every project. */
    fun readArea(value: JsonElement?): List<String> {
        val array = value as? JsonArray ?: return emptyList()
        val ids = mutableListOf<String>()
        for (entry in array) {
            val id = entry.asText()
            if (id != null && id.isNotEmpty() && id !in ids) ids.add(id)
        }
        return ids
    }

    /**
     * The top-level ancestor of a project (itself when top-level), walking the parents through the
     * projects given; a parent not given ends the walk. Cycles end the walk too.
     */
    fun topLevelProjectId(projectId: Long, projects: Map<Long, AreaProject>): Long {
        var current = projectId
        val seen = mutableSetOf<Long>()
        while (true) {
            seen.add(current)
            val parent = projects[current]?.parentProjectId
            if (parent == null || parent <= 0 || parent !in projects || parent in seen) return current
            current = parent
        }
    }

    /**
     * Whether a project is in the area: the area is empty, or the category of the project's
     * top-level ancestor (`category.project.<id>`, section 13) is one of the area's categories.
     */
    fun inArea(
        projectId: Long,
        area: List<String>,
        projects: Map<Long, AreaProject>,
        categoryOfProject: (Long) -> String?,
    ): Boolean {
        if (area.isEmpty()) return true
        val category = categoryOfProject(topLevelProjectId(projectId, projects))
        return category != null && category in area
    }
}
