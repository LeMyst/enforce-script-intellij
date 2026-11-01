package io.github.lemyst.enfusion.cfg

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes

/**
 * The compile-time flags this project actually declares, read from `defines[]` in `config.cpp`.
 *
 * These are invisible to the script side otherwise. `DAYZ_BATTLEROYALE`, `BR_PARTY` and
 * `KILLFEED` gate real code in `.c` files and exist nowhere but a config array, so until now the
 * plugin had no way to know they were flags at all — and a mistyped `#ifdef` silently disables the
 * code it guards, with no error at build time or after.
 *
 * Only the *known* set is affected, never the *active* one. Making these authoritative would be a
 * regression: `SERVER` appears in no `defines[]` because the build sets it per-PBO, so treating the
 * harvested set as what is on would mark every file under `Scripts/Server` inactive.
 */
object EnfusionConfigDefines {

    /**
     * Scoped to `CfgMods` on purpose. That is where a mod declares its flags, and it also keeps the
     * search off the 207 vanilla configs, none of which declare one.
     */
    private const val CFG_MODS = "CfgMods"
    private const val DEFINES = "defines"

    fun harvest(project: Project): Set<String> {
        if (DumbService.isDumb(project)) return emptySet()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(collect(project), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun collect(project: Project): Set<String> {
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val found = LinkedHashSet<String>()
        for (file in EnfusionConfigIndexes.findFiles(EnfusionConfigIndexes.CLASSES, CFG_MODS, scope)) {
            val psiFile = psiManager.findFile(file) ?: continue
            for (cls in PsiTreeUtil.findChildrenOfType(psiFile, EnCfgClassMixin::class.java)) {
                if (cls.enclosingClass()?.name != CFG_MODS) continue
                val property = cls.propertyValue(DEFINES) ?: continue
                found += property.stringValues().filter { it.isNotBlank() }
            }
        }
        return found
    }
}
