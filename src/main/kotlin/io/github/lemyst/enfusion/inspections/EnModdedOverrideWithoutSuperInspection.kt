package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverrideResolution
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * An `override` in a `modded class` whose body never calls `super.<SameName>(…)`.
 *
 * In a `modded class`, `super` is the previous layer: other mods' layers, then vanilla. Skipping it
 * drops all of them. The mod compiles and works alone, and breaks next to other mods: a
 * `SetActions` without `super.SetActions()` removes every other action from the item, and an
 * `OnStoreLoad` without it desynchronises the persistence stream for every other layer.
 *
 * Syntactic on purpose: one `super` call anywhere in the body, in any branch, is enough. Plain
 * subclasses are never reported, since replacing behaviour there is ordinary.
 */
class EnModdedOverrideWithoutSuperInspection : LocalInspectionTool() {

    /** Value-returning methods are usually intended replacements: `return false;`. Off by default. */
    @JvmField
    var reportValueReturning: Boolean = false

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.checkbox("reportValueReturning", "Also report methods that return a value"),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val method = element as? EnMethodDeclMixin ?: return
                if (!method.isOverride() || method.isStatic() || method.isProto()) return
                val name = method.name ?: return
                val body = method.node.findChildByType(EnfusionTypes.BLOCK_STMT)?.psi ?: return
                val owner = PsiTreeUtil.getParentOfType(method, EnClassDeclMixin::class.java) ?: return
                if (!owner.isModded()) return
                val isVoid = EnSuperCalls.isVoid(method)
                if (!isVoid && !reportValueReturning && name !in EnSuperCalls.ALWAYS_CALL_SUPER) return
                if (EnSuperCalls.callsSuper(body, name)) return
                if (DumbService.isDumb(method.project)) return
                // Silent unless what it overrides is in hand: a base the plugin never indexed is
                // no evidence of anything.
                if (!EnSuperCalls.overridesSomething(method)) return
                val fix = when {
                    isVoid -> AddSuperCallFix()
                    EnSuperCalls.returnsBool(method) -> AddSuperCallFix()
                    else -> null
                }
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    "'$name' overrides a method in a modded class without calling 'super.$name', " +
                        "which drops vanilla's and every other mod's '$name'",
                    ProblemHighlightType.WEAK_WARNING,
                    *listOfNotNull(fix).toTypedArray(),
                )
            }
        }
}

internal object EnSuperCalls {

    /**
     * Methods where skipping `super` is wrong whatever they return: persistence, action
     * registration, initialisation, teardown and network callbacks. `OnStoreLoad` returns `bool`
     * and is still always one of them.
     */
    val ALWAYS_CALL_SUPER = setOf(
        "OnStoreSave", "OnStoreLoad", "SetActions", "EEInit", "EEDelete", "OnRPC",
        "OnVariablesSynchronized", "OnInit",
    )

    fun isVoid(method: EnMethodDeclMixin): Boolean =
        method.getReturnTypeText()?.let(EnTypeInference::baseTypeName) == "void"

    fun returnsBool(method: EnMethodDeclMixin): Boolean =
        method.getReturnTypeText()?.let(EnTypeInference::baseTypeName) == "bool"

    /** `super.Name` anywhere in [body], called or not: a method reference passed on counts too. */
    fun callsSuper(body: PsiElement, name: String): Boolean {
        var found = false
        PsiTreeUtil.processElements(body) { element ->
            if (element.node?.elementType === EnfusionTypes.MEMBER_EXPR &&
                element.firstChild?.node?.elementType === EnfusionTypes.SUPER_EXPR &&
                element.node.findChildByType(EnfusionTypes.IDENT)?.text == name
            ) {
                found = true
            }
            !found
        }
        return found
    }

    /** A same-named method with as many parameters, in another layer or up the chain. */
    fun overridesSomething(method: EnMethodDeclMixin): Boolean {
        val arity = parameterNames(method).size
        return EnOverrideResolution.superDeclarationsOf(method).any { parameterNames(it).size == arity }
    }

    fun parameterNames(method: EnMethodDeclMixin): List<String> {
        val list = method.node.findChildByType(EnfusionTypes.PARAM_LIST)?.psi ?: return emptyList()
        return list.children.filter { it.node.elementType === EnfusionTypes.PARAM }
            .map { (it as? EnNamedElement)?.name.orEmpty() }
    }
}

/**
 * Inserts the call as the method's first statement. A `bool` method keeps the shape vanilla's
 * persistence uses, `if (!super.OnStoreLoad(ctx, version)) return false;`, so a layer below that
 * failed to read stops this one too.
 */
private class AddSuperCallFix : LocalQuickFix {

    override fun getFamilyName(): String = "Add super call"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = PsiTreeUtil.getParentOfType(descriptor.psiElement, EnMethodDeclMixin::class.java, false) ?: return
        val name = method.name ?: return
        val body = method.node.findChildByType(EnfusionTypes.BLOCK_STMT) ?: return
        val brace = body.findChildByType(EnfusionTypes.LBRACE) ?: return
        val call = "super.$name(${EnSuperCalls.parameterNames(method).joinToString(", ")})"
        val statement = if (EnSuperCalls.isVoid(method)) "$call;" else "if (!$call)\nreturn false;\n"
        val file = method.containingFile
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        documents.doPostponedOperationsAndUnblockDocument(document)
        val offset = brace.textRange.endOffset
        // `{}` on one line, or `{ Print(); }`: without a line break the `}` or the old statement
        // would end up after the call, `super.SetActions();}`.
        val restOfLine = document.charsSequence.subSequence(offset, document.textLength).takeWhile { it != '\n' }
        val closeLine = !statement.endsWith("\n") && restOfLine.isNotBlank()
        val inserted = "\n$statement" + if (closeLine) "\n" else ""
        document.insertString(offset, inserted)
        documents.commitDocument(document)
        // Up to the line after the call, which holds the `}` once it was moved down.
        val end = minOf(offset + inserted.length + if (closeLine) 1 else 0, document.textLength)
        CodeStyleManager.getInstance(project).adjustLineIndent(file, TextRange(offset, end))
    }
}
