package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * A variable named after a type.
 *
 * The language has one flat global namespace and no scope operator, so a local called `map` does not
 * shadow `class map` the way it would in C++ — it collides with it, and every later use of the type
 * name inside that method resolves to the variable instead. The failure is at build time and the
 * message names the type, not the variable, which is why this costs so much longer to find than it
 * should.
 *
 * `map` is the one that actually bites, being both a container everyone uses and a perfectly natural
 * name for a variable holding one. `set`, `array` and `func` are the same shape.
 */
class EnTypeNameShadowedInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = element.node?.elementType
                if (type !== EnfusionTypes.PARAM && type !== EnfusionTypes.VAR_DECLARATOR &&
                    type !== EnfusionTypes.FOREACH_VAR
                ) {
                    return
                }
                val declaration = element as? EnNamedElement ?: return
                val name = declaration.name ?: return
                if (DumbService.isDumb(element.project)) return
                if (EnTypeInference.typesNamed(name, element.project).isEmpty()) return
                holder.registerProblem(
                    declaration.nameIdentifier ?: declaration,
                    "'$name' is also the name of a type; uses of that type in this scope will " +
                        "resolve to this variable instead",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }
        }
}
