package io.github.lemyst.enfusion.refs

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.InputNameIndex

/**
 * A keybind action name, resolving into the `Inputs.xml` that declares it.
 *
 * Matched by content, not by call shape, and that is forced by how the codebase is written: the name
 * is never passed as a literal. `PartyMissionGameplay` calls
 * `GetUApi().GetInputByName(BR_PARTY_INPUT_MENU)`, so the only literal in the file is the
 * initializer of a `const string` sitting in a constants file with no enclosing call at all. A
 * provider keyed on `GetInputByName` would match nothing in this mod.
 *
 * The `UA` prefix is the engine's own convention for action names and is what makes content matching
 * safe here.
 */
class EnInputNameReference(element: PsiElement, rangeInElement: com.intellij.openapi.util.TextRange) :
    PsiPolyVariantReferenceBase<PsiElement>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
        val name = value
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val targets = ArrayList<PsiElement>()
        for (file in FileBasedIndex.getInstance().getContainingFiles(InputNameIndex.NAME, name, scope)) {
            val xml = psiManager.findFile(file) as? XmlFile ?: continue
            declarationIn(xml, name)?.let { targets += it }
        }
        return PsiElementResolveResult.createResults(targets)
    }

    private companion object {

        /**
         * The `<input>` under `<actions>` — the declaration.
         *
         * The same name also appears under `<sorting>` (which orders the options screen) and under
         * `<preset>` (which assigns a default key). Those are uses, not declarations, and landing on
         * one of them would put the cursor on a key binding rather than on the action.
         */
        private fun declarationIn(xml: XmlFile, name: String): PsiElement? {
            for (tag in PsiTreeUtil.findChildrenOfType(xml, XmlTag::class.java)) {
                if (tag.name != "input") continue
                if (tag.getAttributeValue("name") != name) continue
                if (tag.parentTag?.name != "actions") continue
                return tag.getAttribute("name")?.valueElement ?: tag
            }
            return null
        }
    }
}

/** Attaches [EnInputNameReference] to any literal shaped like an action name. */
class EnInputNameReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val content = EnStringLiterals.content(element) ?: return PsiReference.EMPTY_ARRAY
        if (!ACTION_NAME.matches(content)) return PsiReference.EMPTY_ARRAY
        val range = EnStringLiterals.contentRange(element) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(EnInputNameReference(element, range))
    }

    private companion object {
        /** At least one character after `UA`, so the bare string `UA` is not treated as an action. */
        private val ACTION_NAME = Regex("^UA[A-Za-z0-9_]+$")
    }
}
