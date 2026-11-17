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
import com.intellij.util.ProcessingContext
import io.github.lemyst.enfusion.index.StringTables

/**
 * A localization key, matched by its own content rather than by where it appears.
 *
 * Content matching is not a shortcut here, it is the only thing that works. Client script writes
 * `SetText("#STR_BR_WIN")`, layouts write `text "#STR_BR_WIN"`, and server script ships the **bare**
 * key with no `#` sigil through a notification RPC for the client to localize — three unrelated
 * call shapes, and the server form has no enclosing function to key off at all.
 *
 * The case of a key does not matter, as in the game ([StringTables]).
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

                // A key genuinely can live in more than one stringtable (a mod may have one per
                // addon), so every match is offered rather than the first winning.
                val targets = StringTables.rowsOf(project, key).map { StringTableEntry(project, it.file, key, it.line) }
                return PsiElementResolveResult.createResults(targets)
            }
        }
    }
}

/**
 * Attaches [StringTableKeyReference] to any literal whose content is a stringtable key:
 *
 * - `#` and a name, the form the engine localises in script and layouts. Not only `#STR_…`: vanilla
 *   writes `"#menu_add"` and `"#main_menu_continue"`, and of its 1,305 `#` literals with no `STR_`,
 *   1,283 name a row of the vanilla stringtable.
 * - a bare `STR_…`, the form server script sends for the client to localise. In upper case only:
 *   with no sigil, `"str_…"` is as likely to be any other string, and vanilla has none.
 * - `$STR_…`, with [dollar], the form of a config's `displayName`; vanilla's configs have 4,144.
 *   Its `STR_` in any case, as with `#`: vanilla also writes `"#str_upgrade_torch_fuel"`.
 *
 * The sigil is left outside the reference range: it is a marker telling the engine to localise,
 * not part of the key, and excluding it keeps [PsiPolyVariantReferenceBase.getValue] equal to the
 * CSV column without any further trimming.
 */
class StringTableKeyReferenceProvider(private val dollar: Boolean = false) : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val content = EnStringLiterals.content(element) ?: return PsiReference.EMPTY_ARRAY
        val start = keyStart(content, dollar) ?: return PsiReference.EMPTY_ARRAY
        val range = EnStringLiterals.subRange(element, start, content.length)
            ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(StringTableKeyReference(element, range))
    }

    companion object {
        private val HASH = Regex("^#[A-Za-z][A-Za-z0-9_]*$")
        private val BARE = Regex("^STR_[A-Za-z0-9_]+$")
        private val DOLLAR = Regex("^[$]STR_[A-Za-z0-9_]+$", RegexOption.IGNORE_CASE)

        /** Where the key starts in [content], past its sigil, or null when [content] is no key. */
        fun keyStart(content: String, dollar: Boolean): Int? = when {
            HASH.matches(content) -> 1
            BARE.matches(content) -> 0
            dollar && DOLLAR.matches(content) -> 1
            else -> null
        }

        /**
         * Whether a missing row for [key] is a mistake worth reporting: only a `STR_…` key. A `#`
         * literal naming anything else is most likely a key, but not surely, and the inspection
         * stays quiet when unsure.
         */
        fun isSurelyKey(key: String): Boolean = key.startsWith("STR_", ignoreCase = true)
    }
}
