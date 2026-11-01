package io.github.lemyst.enfusion.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class EnfusionSettingsState : BaseState() {
    /**
     * Script roots indexed for navigation, in priority order. Typically the vanilla DayZ script
     * tree plus any dependency mod checkouts.
     */
    var scriptRoots by list<String>()

    /** Set once auto-detection has run, so a user who removes every root is not overruled. */
    var autoDetected by property(false)

    /**
     * Replaces the root list and marks the state modified.
     *
     * Lives here rather than in the service because `incrementModificationCount()` is `protected` on
     * [BaseState] -- reachable from a subclass, not from a caller. The public alternative,
     * `intIncrementModificationCount()`, is `@ApiStatus.Internal`, and the JetBrains Plugin Verifier
     * flags it as such.
     */
    fun replaceRoots(value: List<String>) {
        scriptRoots.clear()
        scriptRoots.addAll(value)
        incrementModificationCount()
    }
}

@Service(Service.Level.PROJECT)
@State(name = "EnfusionSettings", storages = [Storage("enfusion.xml")])
class EnfusionProjectSettings :
    SimplePersistentStateComponent<EnfusionSettingsState>(EnfusionSettingsState()) {

    var roots: List<String>
        get() = state.scriptRoots.toList()
        set(value) {
            state.replaceRoots(value)
        }

    companion object {
        fun getInstance(project: Project): EnfusionProjectSettings = project.service()
    }
}
