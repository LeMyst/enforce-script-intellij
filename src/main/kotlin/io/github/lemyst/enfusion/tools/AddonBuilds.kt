package io.github.lemyst.enfusion.tools

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import io.github.lemyst.enfusion.tools.detect.AddonBuild
import java.nio.file.Path

/** One addon's saved Addon Builder options, keyed by its folder. */
@Tag("addon")
class AddonBuildState : BaseState() {
    var source by string()
    var destination by string()
    var prefix by string()
    var signKey by string()
    var packOnly by property(false)
    var clear by property(true)
    var temp by string()
    var include by string()
    var exclude by string()

    fun toBuild(): AddonBuild? {
        val source = path(source) ?: return null
        val destination = path(destination) ?: return null
        return AddonBuild(
            source = source,
            destination = destination,
            prefix = prefix?.takeIf { it.isNotBlank() },
            signKey = path(signKey),
            packOnly = packOnly,
            clear = clear,
            temp = path(temp),
            include = path(include),
            exclude = path(exclude),
        )
    }

    private fun path(value: String?): Path? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
}

class AddonBuildsState : BaseState() {
    var addons by list<AddonBuildState>()

    fun put(entry: AddonBuildState) {
        addons.removeIf { it.source == entry.source }
        addons.add(entry)
        incrementModificationCount()
    }
}

/**
 * The Addon Builder options of each addon of the project, saved in `.idea/enfusion-builds.xml`:
 * they belong to the project and are worth sharing with it (output folder, prefix). The signing key
 * is a path only; the key itself never leaves its file.
 */
@Service(Service.Level.PROJECT)
@State(name = "EnfusionAddonBuilds", storages = [Storage("enfusion-builds.xml")])
class AddonBuilds : SimplePersistentStateComponent<AddonBuildsState>(AddonBuildsState()) {

    fun get(source: Path): AddonBuildState? = state.addons.firstOrNull { it.source == source.toString() }

    fun save(entry: AddonBuildState) = state.put(entry)

    fun all(): List<AddonBuildState> = state.addons.toList()

    companion object {
        fun getInstance(project: Project): AddonBuilds = project.service()
    }
}
