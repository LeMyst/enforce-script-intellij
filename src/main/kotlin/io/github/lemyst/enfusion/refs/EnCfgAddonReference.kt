package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgValueMixin
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes

/**
 * An addon named in `requiredAddons[]`, resolved to the `CfgPatches` class that declares it.
 *
 * This is the mod load order, and it is otherwise invisible: the relationship exists only as a string
 * matching a class name in a different addon's `config.cpp`, with nothing on either side pointing at
 * the other. Mods end up documenting their load order in prose precisely because there was no
 * way to follow it in an editor.
 */
class EnCfgAddonReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
        val name = value
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val scope = GlobalSearchScope.allScope(project)
        val psiManager = PsiManager.getInstance(project)
        val targets = ArrayList<PsiElement>()
        for (file in EnfusionConfigIndexes.findFiles(EnfusionConfigIndexes.ADDONS, name, scope)) {
            val psiFile = psiManager.findFile(file) ?: continue
            PsiTreeUtil.findChildrenOfType(psiFile, EnCfgClassMixin::class.java)
                .filterTo(targets) { it.name == name && EnfusionConfigIndexes.isAddonDeclaration(it) }
        }
        return PsiElementResolveResult.createResults(targets)
    }

    /** Every addon the project knows of, offered inside the quotes. */
    override fun getVariants(): Array<Any> {
        val project = element.project
        if (DumbService.isDumb(project)) return emptyArray()
        val names = ArrayList<String>()
        com.intellij.util.indexing.FileBasedIndex.getInstance()
            .processAllKeys(EnfusionConfigIndexes.ADDONS, { names += it; true }, project)
        return names.toTypedArray()
    }
}

/**
 * Attaches [EnCfgAddonReference] to each string inside `requiredAddons[]`.
 *
 * Keyed on the property name rather than on content, unlike the stringtable and imageset providers:
 * an addon name is an ordinary identifier-shaped string with nothing distinctive about it, so only
 * its position says what it is.
 *
 * `dependencies[]` is deliberately excluded even though it looks identical. Its entries are engine
 * module names — `"Game"`, `"World"`, `"Mission"` — not addons, and nothing declares them.
 */
class EnCfgAddonReferenceProvider : PsiReferenceProvider() {

    private companion object {
        const val REQUIRED_ADDONS = "requiredAddons"
    }

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as? EnCfgValueMixin ?: return PsiReference.EMPTY_ARRAY
        val property = value.owningProperty() ?: return PsiReference.EMPTY_ARRAY
        if (!REQUIRED_ADDONS.equals(property.getKey(), ignoreCase = true)) return PsiReference.EMPTY_ARRAY
        val range = EnStringLiterals.contentRange(element) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(EnCfgAddonReference(element, range))
    }
}
