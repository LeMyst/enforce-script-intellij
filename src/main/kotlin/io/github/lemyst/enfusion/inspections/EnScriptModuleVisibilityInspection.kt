package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnIdentifierReference
import io.github.lemyst.enfusion.resolve.EnMemberReference
import io.github.lemyst.enfusion.resolve.EnScriptModules
import io.github.lemyst.enfusion.resolve.EnTypeInference
import io.github.lemyst.enfusion.resolve.EnTypeReference
import io.github.lemyst.enfusion.resolve.ScriptModule

/**
 * A name that resolves only to declarations in a later script module than the file using it:
 * `PlayerBase` (`4_World`) named from `3_Game`. The game refuses such a file with *Can't compile
 * "Game" script module*, and only when it is launched.
 *
 * Quiet whenever it cannot be sure:
 * - a file in no known module is never checked;
 * - one visible candidate is enough. A class declared in `3_Game` and modded in `4_World` resolves
 *   to both layers, so naming it from `3_Game` is fine; a member that only the `4_World` layer adds
 *   resolves to that layer alone, and is reported;
 * - a candidate in no known module, such as a bundled stub, counts as visible;
 * - a `proto` declaration is not checked, since the engine's own classes are known everywhere.
 *
 * Covers type names, bare identifiers (globals, functions, fields of the enclosing class) and
 * members after a dot, and the name of a `modded class` whose class only a later module declares:
 * a layer can only modify a class its own module already sees.
 *
 * Such a misplaced layer does not compile, so it does not make its class visible either. Otherwise
 * one stray `modded class PlayerBase` in `3_Game` would silence every `PlayerBase` report there.
 */
class EnScriptModuleVisibilityInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        if (DumbService.isDumb(project)) return PsiElementVisitor.EMPTY_VISITOR
        val module = EnScriptModules.moduleOf(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        if (module == ScriptModule.MISSION) return PsiElementVisitor.EMPTY_VISITOR // sees everything
        val origin = holder.file.virtualFile

        // Per class name: the earliest module declaring it without `modded`, or null when it is
        // declared nowhere or anywhere outside a known module (then nothing is claimed).
        val baseModules = HashMap<String, ScriptModule?>()
        fun baseModule(name: String): ScriptModule? = baseModules.getOrPut(name) {
            val modules = EnTypeInference.classesNamed(name, project)
                .filter { !it.isModded() }
                .map { cls -> cls.containingFile?.virtualFile?.let { EnScriptModules.moduleOf(it, project) } ?: return@getOrPut null }
            modules.minByOrNull { it.ordinal }
        }

        /**
         * Where [target] really becomes usable. A `modded class` layer, or a member of one, sitting
         * in a module that cannot see the class it modifies is counted from that class's module.
         */
        fun effectiveModule(target: PsiElement, declaredIn: ScriptModule): ScriptModule {
            val layer = (target as? EnClassDeclMixin ?: PsiTreeUtil.getParentOfType(target, EnClassDeclMixin::class.java))
                ?.takeIf { it.isModded() } ?: return declaredIn
            val base = layer.name?.let(::baseModule) ?: return declaredIn
            return if (declaredIn.sees(base)) declaredIn else base
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is EnClassDeclMixin && element.isModded()) {
                    val name = element.name ?: return
                    val base = baseModule(name) ?: return
                    if (!module.sees(base)) {
                        holder.registerProblem(
                            element.nameIdentifier ?: element,
                            "'$name' is declared in ${base.label} and is not visible from ${module.label}",
                        )
                    }
                    return
                }
                val references = element.references
                if (references.isEmpty()) return
                // A `proto` declaration may name a later module's class: vanilla
                // `1_core/proto/proto.c` returns `Object` (declared in `3_Game`) from
                // `Link.Get()`. Those are engine classes, registered before any script compiles.
                if (PsiTreeUtil.getParentOfType(element, EnMethodDeclMixin::class.java)?.isProto() == true) return
                for (reference in references) {
                    if (reference !is EnTypeReference && reference !is EnIdentifierReference && reference !is EnMemberReference) continue
                    val targets = (reference as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element }
                    if (targets.isEmpty()) continue
                    var earliest: ScriptModule? = null
                    var visible = false
                    for (target in targets) {
                        val file = target.containingFile?.virtualFile
                        if (file == null || file == origin) {
                            visible = true
                            break
                        }
                        val declaredIn = EnScriptModules.moduleOf(file, project)?.let { effectiveModule(target, it) }
                        if (declaredIn == null || module.sees(declaredIn)) {
                            visible = true
                            break
                        }
                        if (earliest == null || declaredIn.ordinal < earliest.ordinal) earliest = declaredIn
                    }
                    if (visible || earliest == null) continue
                    holder.registerProblem(
                        element,
                        "'${reference.canonicalText}' is declared in ${earliest.label} and is not visible from ${module.label}",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        reference.rangeInElement,
                    )
                }
            }
        }
    }
}
