package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * `modded class X` where no `class X` is anywhere in sight.
 *
 * A layer is bound to what it patches by spelling alone — it states no `extends`, and the engine
 * silently treats an unmatched layer as an ordinary new class rather than refusing to compile. That
 * makes a typo in the class name the quietest possible failure: the mod builds, loads, and simply
 * does nothing.
 *
 * The report is a weak warning rather than a warning because the other cause is benign and common:
 * the base genuinely exists but lives in a dependency mod whose sources are not among the
 * configured script roots. The message says so, since that is the actionable half.
 */
class EnModdedClassWithoutBaseInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val cls = element as? EnClassDeclMixin ?: return
                if (!cls.isModded()) return
                if (DumbService.isDumb(element.project)) return
                val name = cls.name ?: return
                val base = EnTypeInference.classesNamed(name, element.project)
                    .any { it !== cls && !it.isModded() }
                if (base) return
                holder.registerProblem(
                    cls.nameIdentifier ?: cls,
                    "No 'class $name' is visible for this layer to patch — check the spelling, or " +
                        "add the mod that declares it as a script root",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }
        }
}
