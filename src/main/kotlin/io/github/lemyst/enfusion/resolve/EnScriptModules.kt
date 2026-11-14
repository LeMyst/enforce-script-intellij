package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes

/**
 * The five script modules, compiled in this order. Each sees its own declarations and those of the
 * modules before it, never those after: a `3_Game` file naming `PlayerBase`, declared in
 * `4_World`, stops the game with *Can't compile "Game" script module*.
 */
enum class ScriptModule(val label: String, val folder: String, val configClass: String) {
    CORE("1_Core", "1_core", "engineScriptModule"),
    GAME_LIB("2_GameLib", "2_gamelib", "gameLibScriptModule"),
    GAME("3_Game", "3_game", "gameScriptModule"),
    WORLD("4_World", "4_world", "worldScriptModule"),
    MISSION("5_Mission", "5_mission", "missionScriptModule");

    /** Whether code in this module can use a declaration from [other]. */
    fun sees(other: ScriptModule): Boolean = other.ordinal <= ordinal

    companion object {
        fun ofFolder(name: String): ScriptModule? = entries.firstOrNull { it.folder.equals(name, ignoreCase = true) }
        fun ofConfigClass(name: String): ScriptModule? = entries.firstOrNull { it.configClass.equals(name, ignoreCase = true) }
    }
}

/**
 * Which script module a file is compiled into.
 *
 * The reliable source is the addon's `config.cpp`: `CfgMods` → *mod* → `defs` →
 * `gameScriptModule` → `files[]` lists the folders of each module. Folder names alone are only a
 * convention, since mods use paths such as `Scripts/Client/3_Game`, so the config wins wherever it
 * names a folder. The vanilla tree declares no `defs` at all (the engine knows its own folders),
 * and there the standard folder names (`scripts/3_game`) are what is left.
 *
 * A file neither places is in no known module, and callers must then stay quiet.
 */
object EnScriptModules {

    private const val CFG_MODS = "CfgMods"
    private const val DEFS = "defs"
    private const val FILES = "files"

    fun moduleOf(element: PsiElement): ScriptModule? {
        val file = element.containingFile?.originalFile?.virtualFile ?: return null
        return moduleOf(file, element.project)
    }

    fun moduleOf(file: VirtualFile, project: Project): ScriptModule? {
        val declared = declaredFolders(project)
        var dir = file.parent
        while (dir != null) {
            declared[dir]?.let { return it }
            ScriptModule.ofFolder(dir.name)?.let { return it }
            dir = dir.parent
        }
        return null
    }

    /** Every folder a `files[]` entry of a module class names, with that module. */
    private fun declaredFolders(project: Project): Map<VirtualFile, ScriptModule> {
        if (DumbService.isDumb(project)) return emptyMap()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(collect(project), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun collect(project: Project): Map<VirtualFile, ScriptModule> {
        val psiManager = PsiManager.getInstance(project)
        val result = HashMap<VirtualFile, ScriptModule>()
        val scope = GlobalSearchScope.allScope(project)
        for (file in EnfusionConfigIndexes.findFiles(EnfusionConfigIndexes.CLASSES, CFG_MODS, scope)) {
            val psiFile = psiManager.findFile(file) ?: continue
            val configDir = file.parent ?: continue
            for (cls in PsiTreeUtil.findChildrenOfType(psiFile, EnCfgClassMixin::class.java)) {
                val module = cls.name?.let(ScriptModule::ofConfigClass) ?: continue
                val defs = cls.enclosingClass() ?: continue
                if (!defs.name.equals(DEFS, ignoreCase = true)) continue
                if (generateSequence(defs) { it.enclosingClass() }.none { it.name == CFG_MODS }) continue
                val paths = cls.propertyValue(FILES)?.stringValues() ?: continue
                for (path in paths) {
                    val folder = locate(configDir, path) ?: continue
                    // A folder listed for two modules is compiled into the first; the lower one is
                    // the one that decides what it can see.
                    result.merge(folder, module) { a, b -> if (a.ordinal <= b.ordinal) a else b }
                }
            }
        }
        return result
    }

    /**
     * The folder a `files[]` path names. The path is written from the work drive
     * (`MyMod/Scripts/3_Game`) while the config sits somewhere inside that tree
     * (`<checkout>/Scripts/config.cpp`), so the longest suffix of the path that exists under the
     * config's folder or one of its parents is taken.
     */
    internal fun locate(configDir: VirtualFile, path: String): VirtualFile? {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        for (take in segments.size downTo 1) {
            val suffix = segments.takeLast(take).joinToString("/")
            var base: VirtualFile? = configDir
            var depth = 0
            while (base != null && depth <= MAX_PARENT_DEPTH) {
                val found = base.findFileByRelativePath(suffix)
                if (found != null && found.isDirectory) return found
                base = base.parent
                depth++
            }
        }
        return null
    }

    /** How far above `config.cpp` a path's root may be: `MyMod/Scripts/config.cpp` is two levels. */
    private const val MAX_PARENT_DEPTH = 4
}
