package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.StringTableIndex

/**
 * A localization key, matched by its own content rather than by where it appears.
 *
 * Content matching is not a shortcut here, it is the only thing that works. Client script writes
 * `SetText("#STR_BR_WIN")`, layouts write `text "#STR_BR_WIN"`, and server script ships the **bare**
 * key with no `#` sigil through a notification RPC for the client to localize — three unrelated
 * call shapes, and the server form has no enclosing function to key off at all.
 */
class StringTableKeyReference(element: PsiElement, rangeInElement: TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)
            .resolveWithCaching(this, RESOLVER, /* needToPreventRecursion = */ false, incompleteCode)

    private companion object {

        private val RESOLVER = object : ResolveCache.PolyVariantResolver<StringTableKeyReference> {

            override fun resolve(
                ref: StringTableKeyReference,
                incompleteCode: Boolean,
            ): Array<ResolveResult> {
                val project = ref.element.project
                // Go to Declaration is DumbAware, so this runs during indexing.
                if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

                val key = ref.value
                if (key.isEmpty()) return ResolveResult.EMPTY_ARRAY

                // allScope, not resolveScope: a SyntheticLibrary has no OrderEntry, so a module
                // scope would exclude the vanilla `languagecore` root entirely.
                val scope = GlobalSearchScope.allScope(project)
                val index = FileBasedIndex.getInstance()

                val targets = ArrayList<PsiElement>()
                for (file in index.getContainingFiles(StringTableIndex.NAME, key, scope)) {
                    // A key genuinely can live in more than one stringtable — this mod has three,
                    // one per addon — so every match is offered rather than the first winning.
                    for (line in index.getValues(StringTableIndex.NAME, key, GlobalSearchScope.fileScope(project, file))) {
                        targets += StringTableEntry(project, file, key, line)
                    }
                }
                return PsiElementResolveResult.createResults(targets)
            }
        }
    }
}

/**
 * Attaches [StringTableKeyReference] to any literal whose content is a stringtable key.
 *
 * The leading `#` is left outside the reference range: it is a marker telling the engine's own
 * widget code to localize, not part of the key, and excluding it keeps [PsiPolyVariantReferenceBase.getValue]
 * equal to the CSV column without any further trimming.
 */
class StringTableKeyReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val content = EnStringLiterals.content(element) ?: return PsiReference.EMPTY_ARRAY
        if (!KEY.matches(content)) return PsiReference.EMPTY_ARRAY
        val start = if (content.startsWith('#')) 1 else 0
        val range = EnStringLiterals.subRange(element, start, content.length)
            ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(StringTableKeyReference(element, range))
    }

    private companion object {
        private val KEY = Regex("^#?STR_[A-Za-z0-9_]+$")
    }
}
