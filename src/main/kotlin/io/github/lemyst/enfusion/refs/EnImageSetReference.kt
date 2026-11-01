package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.ID
import io.github.lemyst.enfusion.index.EnfusionObjectIndexes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjDeclMixin

/**
 * `"set:battleroyale_gui image:DayZBRLogo_White"` — one literal naming two different things.
 *
 * This is the case that made [com.intellij.psi.ContributedReferenceHost] worth wiring up: the set
 * and the image need separate ranges and separate targets, and a single `getReference()` cannot
 * express that.
 *
 * The set is addressed by the **`Name` property inside the imageset**, not by its file name. In this
 * mod the file is `dayzbr_gui.imageset` while the name is `battleroyale_gui`, so resolving by
 * filename would find nothing at all.
 */
class EnImageSetReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val index: ID<String, Void>,
    private val className: String,
    private val restrictTo: () -> Collection<PsiFile>,
) : PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
        val name = value
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

        // Scope first: an image name is only meaningful inside the set that declares it, so when the
        // set resolved, its file is the only place worth searching. Falling back to the global index
        // keeps a typo'd or unresolvable set from taking the image reference down with it.
        val scoped = restrictTo().flatMap { declarationsNamed(it, name) }
        if (scoped.isNotEmpty()) return PsiElementResolveResult.createResults(scoped)

        return PsiElementResolveResult.createResults(searchGlobally(project, name))
    }

    override fun getVariants(): Array<Any> {
        if (DumbService.isDumb(element.project)) return emptyArray()
        return restrictTo()
            .flatMap { PsiTreeUtil.findChildrenOfType(it, EnObjDeclMixin::class.java) }
            .filter { it.getClassName() == className }
            .mapNotNull { it.declaredName() }
            .distinct()
            .toTypedArray()
    }

    private fun searchGlobally(project: Project, name: String): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        return EnfusionObjectIndexes.findFiles(index, name, scope)
            .mapNotNull { psiManager.findFile(it) }
            .flatMap { declarationsNamed(it, name) }
    }

    private fun declarationsNamed(file: PsiFile, name: String): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(file, EnObjDeclMixin::class.java)
            .filter { it.getClassName() == className && it.declaredName() == name }
}

/**
 * Splits `set:<name> image:<name>` into its two references.
 *
 * Attached to script literals and to layout values alike — `image0 "set:dayz_gui image:DayZLogo"` is
 * as common inside a `.layout` as in a `.c` file, and content matching makes one provider serve both.
 */
class EnImageSetReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val content = EnStringLiterals.content(element) ?: return PsiReference.EMPTY_ARRAY
        val match = PATTERN.find(content) ?: return PsiReference.EMPTY_ARRAY

        val setGroup = match.groups[1] ?: return PsiReference.EMPTY_ARRAY
        val setRange = EnStringLiterals.subRange(element, setGroup.range.first, setGroup.range.last + 1)
            ?: return PsiReference.EMPTY_ARRAY

        val setReference = EnImageSetReference(
            element,
            setRange,
            EnfusionObjectIndexes.IMAGE_SETS,
            EnfusionObjectIndexes.IMAGE_SET_CLASS,
        ) { emptyList() }

        // `"set:dayz_gui image:"` is a real thing in this codebase — the image is filled in from
        // script — so an empty second half yields one reference rather than none.
        val imageGroup = match.groups[2]
        if (imageGroup == null || imageGroup.value.isEmpty()) return arrayOf(setReference)

        val imageRange = EnStringLiterals.subRange(element, imageGroup.range.first, imageGroup.range.last + 1)
            ?: return arrayOf(setReference)

        val imageReference = EnImageSetReference(
            element,
            imageRange,
            EnfusionObjectIndexes.IMAGES,
            EnfusionObjectIndexes.IMAGE_SET_DEF_CLASS,
        ) {
            // Resolved lazily so the set lookup happens at most once, and only when the image
            // reference is actually used.
            setReference.multiResolve(false).mapNotNull { it.element?.containingFile }.distinct()
        }

        return arrayOf(setReference, imageReference)
    }

    private companion object {
        /** The image half may be empty; the set half may not. */
        private val PATTERN = Regex("""set:(\S+)\s+image:(\S*)""")
    }
}
