package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverrideResolution
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * The two halves of the `override` rule, which the engine enforces at build time and nothing else
 * checks before then.
 *
 * Redeclaring an inherited or layered method without `override` is *"Function 'X' is already
 * defined"*, and writing `override` where nothing is overridden is *"No base function to override"*.
 * Both are trivially detectable from the same resolution the gutter marker already performs, and
 * both otherwise cost a full build to discover.
 */
private object EnOverrideCheck {

    /**
     * Super declarations that would actually collide with [method] — same name *and* same parameter
     * count.
     *
     * Arity is the guard against the one shape that looks identical and is perfectly legal:
     * `void Foo(int)` in the base and `void Foo(string)` in the subclass are an overload pair, and
     * the subclass one neither needs nor accepts `override`. Comparing counts rather than types is
     * deliberate — the plugin does not resolve parameter types well enough to tell `Man` from
     * `PlayerBase`, and a false *negative* here costs nothing while a false positive would put a
     * warning on correct code.
     */
    fun collidingSupers(method: EnMethodDeclMixin): List<EnMethodDeclMixin> {
        val arity = parameterCount(method)
        return EnOverrideResolution.superDeclarationsOf(method).filter { parameterCount(it) == arity }
    }

    fun parameterCount(method: EnMethodDeclMixin): Int {
        val list = method.node.findChildByType(EnfusionTypes.PARAM_LIST) ?: return 0
        return list.getChildren(null).count { it.elementType === EnfusionTypes.PARAM }
    }

    /** Methods this inspection has no business judging. */
    fun isExempt(method: EnMethodDeclMixin): Boolean =
        method.isProto() || method.isConstructor() || method.isDestructor() || method.name == null

    /**
     * Whether the plugin can actually see what sits above this method.
     *
     * Without this gate every method in a class whose parent is a type the plugin never indexed —
     * a dependency mod the user has not added as a script root — would be reported as overriding
     * nothing. The inspection only speaks when it has the parent in hand.
     */
    fun parentIsVisible(method: EnMethodDeclMixin): Boolean {
        val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java) ?: return false
        val project = owner.project
        val superName = owner.getSuperName()?.let(EnTypeInference::baseTypeName)
        if (superName != null) return EnTypeInference.classesNamed(superName, project).isNotEmpty()
        // A layer's parent is the declaration of the same name that is not itself a layer.
        if (!owner.isModded()) return false
        return EnTypeInference.classesNamed(owner.name, project).any { it !== owner && !it.isModded() }
    }
}

class EnMissingOverrideInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val method = element as? EnMethodDeclMixin ?: return
                if (method.isOverride() || EnOverrideCheck.isExempt(method)) return
                if (DumbService.isDumb(element.project)) return
                if (EnOverrideCheck.collidingSupers(method).isEmpty()) return
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    "'${method.name}' redeclares an inherited method without 'override'",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    AddOverrideFix(),
                )
            }
        }
}

class EnRedundantOverrideInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val method = element as? EnMethodDeclMixin ?: return
                if (!method.isOverride() || EnOverrideCheck.isExempt(method)) return
                if (DumbService.isDumb(element.project)) return
                if (!EnOverrideCheck.parentIsVisible(method)) return
                if (EnOverrideCheck.collidingSupers(method).isNotEmpty()) return
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    "'${method.name}' is marked 'override' but no base declaration matches it",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    RemoveOverrideFix(),
                )
            }
        }
}

/**
 * Both fixes rewrite the method's modifier list by reparsing the whole declaration.
 *
 * Editing the modifier tokens in place would be less disruptive, but `override` may sit anywhere
 * among `static`, `protected` and an attribute list, and there is no canonical position to insert
 * at. Reparsing sidesteps the question and cannot produce a malformed tree.
 */
private abstract class EnMethodModifierFix : LocalQuickFix {

    protected abstract fun rewrite(text: String): String

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = PsiTreeUtil.getParentOfType(descriptor.psiElement, EnMethodDeclMixin::class.java) ?: return
        val replacement = createMethod(project, rewrite(method.text)) ?: return
        method.replace(replacement)
    }

    private fun createMethod(project: Project, text: String): EnMethodDeclMixin? {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("_fix.es", EnfusionFileType, "class _Fix\n{\n$text\n}\n")
        return PsiTreeUtil.findChildOfType(file, EnMethodDeclMixin::class.java)
    }
}

private class AddOverrideFix : EnMethodModifierFix() {
    override fun getFamilyName(): String = "Add 'override'"
    override fun rewrite(text: String): String = "override $text"
}

private class RemoveOverrideFix : EnMethodModifierFix() {
    override fun getFamilyName(): String = "Remove 'override'"
    override fun rewrite(text: String): String =
        text.replaceFirst(Regex("\\boverride\\s+"), "")
}
