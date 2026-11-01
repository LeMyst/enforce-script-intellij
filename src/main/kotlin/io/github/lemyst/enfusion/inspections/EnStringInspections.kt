package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.StringTableIndex
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.refs.EnMethodNameReference
import io.github.lemyst.enfusion.refs.StringTableKeyReference

/**
 * A dispatch string naming a method the target does not have.
 *
 * `AddTimer(5.0, this, "OnTick")` and `AddRPC(NS, "SetPlayerCount", this)` are calls the compiler
 * never checks: the method name is a string, so a typo or a since-renamed handler produces code
 * that builds and links and then silently does nothing at runtime. This is the single largest class
 * of bug the string references make visible.
 *
 * It only reports when the *target* resolved — `SendRPC` and `RemoveRPC` name no object, so there is
 * no class to check the name against and the inspection says nothing at all rather than guessing.
 */
class EnUnresolvedDispatchNameInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.node?.elementType !== EnfusionTypes.LITERAL_EXPR) return
                if (DumbService.isDumb(element.project)) return
                for (reference in element.references) {
                    if (reference !is EnMethodNameReference) continue
                    if (!reference.hasKnownTarget()) continue
                    if (reference.multiResolve(false).isNotEmpty()) continue
                    holder.registerProblem(
                        element,
                        "No method '${reference.value}' on the object this call dispatches to",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        reference.rangeInElement,
                    )
                }
            }
        }
}

/**
 * A `STR_…` key with no row in any stringtable.
 *
 * The engine's behaviour on a missing key is to display the key itself, so the mistake reaches the
 * player's screen as `STR_BR_WIN` in raw form rather than as an error anywhere a developer looks.
 *
 * Registered for no particular language, because the same key appears in both of this plugin's:
 * `SetText("#STR_BR_WIN")` in script and `text "#STR_BR_WIN"` in a layout. The visitor is cheap
 * enough for that to cost nothing in files belonging to neither — it looks at one element type.
 */
class EnUnresolvedStringTableKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        // With no stringtable indexed at all -- a project that has not had its roots configured --
        // every key in the file is unresolved and the inspection would be pure noise.
        if (DumbService.isDumb(project) || !hasAnyStringTable(project)) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                for (reference in element.references) {
                    if (reference !is StringTableKeyReference) continue
                    if (reference.multiResolve(false).isNotEmpty()) continue
                    holder.registerProblem(
                        element,
                        "No row for '${reference.value}' in any stringtable.csv",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        reference.rangeInElement,
                    )
                }
            }
        }
    }

    private fun hasAnyStringTable(project: com.intellij.openapi.project.Project): Boolean {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        var found = false
        index.processAllKeys(StringTableIndex.NAME, { key ->
            found = index.getContainingFiles(StringTableIndex.NAME, key, scope).isNotEmpty()
            !found
        }, project)
        return found
    }
}
