package io.github.lemyst.enfusion.nav

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin

abstract class EnfusionGotoContributorBase(
    private val indexId: ID<String, Void>,
    private val accepts: (PsiElement) -> Boolean,
) : ChooseByNameContributorEx {

    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?,
    ) {
        FileBasedIndex.getInstance().processAllKeys(indexId, processor, scope, filter)
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val project: Project = parameters.project
        val psiManager = PsiManager.getInstance(project)
        // The index narrows to a handful of files; only those are parsed.
        for (file in EnfusionIndexes.findFiles(indexId, name, parameters.searchScope)) {
            val psiFile = psiManager.findFile(file) ?: continue
            for (element in PsiTreeUtil.findChildrenOfType(psiFile, EnNamedElement::class.java)) {
                if (element.name != name || !accepts(element)) continue
                if (element is NavigationItem && !processor.process(element)) return
            }
        }
    }
}

/**
 * Go to Class (Ctrl+N) over classes, enums and typedefs.
 *
 * EnfusionScript has one flat global namespace, so there is no qualified name to report and no
 * package to separate on — the simple name *is* the fully qualified name.
 */
class EnfusionGotoClassContributor :
    EnfusionGotoContributorBase(EnfusionIndexes.TYPES, EnfusionIndexes::isTypeDeclaration),
    GotoClassContributor {

    override fun getQualifiedName(item: NavigationItem): String? = item.name

    override fun getQualifiedNameSeparator(): String? = null
}

/** Go to Symbol (Ctrl+Shift+Alt+N) over methods, fields, globals and enum constants. */
class EnfusionGotoSymbolContributor :
    EnfusionGotoContributorBase(EnfusionIndexes.SYMBOLS, EnfusionIndexes::isSymbolDeclaration)

/**
 * Config classes, in **Go to Symbol** rather than Go to Class.
 *
 * They are classes, but not the same kind of thing as an EnfusionScript class, and there are an
 * order of magnitude more of them — 78,000 across the surveyed corpus against a few thousand script
 * types. Merging them into `Ctrl+N` would make the shortcut that finds `BattleRoyaleServer` also
 * offer every weapon and every piece of clothing in the game. The broader net is the right place for
 * a broader set.
 */
class EnfusionConfigGotoSymbolContributor :
    EnfusionGotoContributorBase(EnfusionConfigIndexes.CLASSES, { it is EnCfgClassMixin })

/**
 * Puts the owning class in the breadcrumb/location column of the navigation bar and Go to Symbol
 * popup, which is what makes a bare method name like `OnPlayerTick` distinguishable when a dozen
 * classes declare one.
 */
internal fun owningClassName(element: PsiElement): String? =
    PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)?.name
