package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * A name declared twice in one method body.
 *
 * EnfusionScript has no block scoping for this purpose: a method is one flat scope, so
 * `if (a) { int found; } else { int found; }` is *"Variable 'found' is already declared"* even
 * though the two branches can never both run. Every C-family habit says otherwise, which is exactly
 * why this is worth flagging in the editor rather than at build time.
 *
 * Two constructs are deliberately **not** counted as declarations here, because measurement against
 * the vanilla corpus shows the compiler accepts them: the `for` initialiser and the `foreach`
 * variable each own their loop's scope, so repeating `for (int i = 0; ...)` — which vanilla does
 * constantly — is legal.
 */
class EnDuplicateLocalInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val method = element as? EnMethodDeclMixin ?: return
                val seen = HashMap<String, PsiElement>()
                for (declaration in declarationsIn(method)) {
                    val name = declaration.name ?: continue
                    val first = seen.putIfAbsent(name, declaration) ?: continue
                    holder.registerProblem(
                        declaration.nameIdentifier ?: declaration,
                        "'$name' is already declared in this method, on line ${lineOf(first)}",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    )
                }
            }
        }

    private companion object {

        /** Parameters and method-scope locals, in source order. */
        fun declarationsIn(method: EnMethodDeclMixin): List<EnNamedElement> =
            PsiTreeUtil.findChildrenOfType(method, EnNamedElement::class.java).filter {
                when (it.node?.elementType) {
                    EnfusionTypes.PARAM -> true
                    EnfusionTypes.VAR_DECLARATOR -> !isLoopScoped(it)
                    else -> false
                }
            }

        /** A declarator belonging to a `for` initialiser, which scopes to its own loop. */
        fun isLoopScoped(declaration: PsiElement): Boolean {
            var node = declaration.node?.treeParent
            while (node != null) {
                when (node.elementType) {
                    EnfusionTypes.FOR_STMT -> return true
                    EnfusionTypes.BLOCK_STMT, EnfusionTypes.METHOD_DECL -> return false
                    else -> node = node.treeParent
                }
            }
            return false
        }

        fun lineOf(element: PsiElement): Int {
            val file = element.containingFile ?: return 0
            val text = file.viewProvider.document?.charsSequence ?: return 0
            return text.subSequence(0, element.textOffset).count { it == '\n' } + 1
        }
    }
}
