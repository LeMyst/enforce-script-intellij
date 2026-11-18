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
import io.github.lemyst.enfusion.tools.detect.AddonBuilderCommand
import java.nio.file.Files
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
        val build = AddonBuild(
            source = source,
            destination = destination,
            prefix = prefix?.takeIf { it.isNotBlank() },
            signKey = path(signKey),
            packOnly = packOnly,
            clear = clear,
            temp = path(temp),
        )
        return AddonBuilderCommand.withListSettings(build, include, exclude)
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

    /**
     * The options saved for [source], or for another path to the same folder. The release build
     * lists an addon through the work drive (`P:\MyMod\Scripts`) while *Build PBO* may have saved
     * it under the project's path that `P:\MyMod` links to; both must find the same lists.
     */
    fun get(source: Path): AddonBuildState? =
        state.addons.firstOrNull { it.source == source.toString() }
            ?: state.addons.firstOrNull { sameFolder(it.source, source) }

    /** Saves [entry] in place of what was saved for its folder, under whichever path. */
    fun save(entry: AddonBuildState) {
        val folder = entry.source?.let { runCatching { Path.of(it) }.getOrNull() }
        if (folder != null) state.addons.removeIf { it.source != entry.source && sameFolder(it.source, folder) }
        state.put(entry)
    }

    private fun sameFolder(saved: String?, folder: Path): Boolean =
        saved != null && runCatching { Files.isSameFile(Path.of(saved), folder) }.getOrDefault(false)

    fun all(): List<AddonBuildState> = state.addons.toList()

    companion object {
        fun getInstance(project: Project): AddonBuilds = project.service()
    }
}
