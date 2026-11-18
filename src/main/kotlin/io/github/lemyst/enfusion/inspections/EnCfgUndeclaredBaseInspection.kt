package io.github.lemyst.enfusion.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigFile
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin

/**
 * *Base class not declared in this scope*: `class MyApple: Apple {}` in a `CfgVehicles` with no
 * `class Apple;` before it.
 *
 * The IDE resolves `Apple` through every indexed config, so Ctrl+Click works and nothing looks
 * wrong; Addon Builder then refuses the file, *Undefined base class 'Apple'*, because CfgConvert
 * binarises one `config.cpp` at a time and looks the base up in that file alone. The rules here are
 * CfgConvert's, each one checked by binarising a test config with it:
 *
 * - a class of that name, full or external (`class Apple;`), declared **before** the derived class
 *   in the same body; one declared after it is not seen;
 * - else, the same in each enclosing body, before the enclosing class, up to the file's own top
 *   level; a sibling section (`CfgWeapons`) is never searched;
 * - else a member inherited by an enclosing class from its base, the base's own members included:
 *   that is what makes `class DamageSystem: DamageSystem` work inside `class MyApple: Apple` when
 *   `Apple` declares a `DamageSystem`, and fail when `Apple` is only `class Apple;`;
 * - names ignore case.
 *
 * Gated, as the other inspections are: only in a `config.cpp`, and only when the base resolves to
 * a class in some indexed config. A base found nowhere may be a dependency outside the roots, a
 * different problem.
 */
class EnCfgUndeclaredBaseInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file
        if (file !is EnfusionConfigFile || !file.name.equals("config.cpp", ignoreCase = true)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val cls = element as? EnCfgClassMixin ?: return
                val base = cls.getSuperName() ?: return
                val identifier = cls.superIdentifier() ?: return
                if (EnCfgScope.visibleBase(cls, base) != null) return
                if (DumbService.isDumb(cls.project) || !resolvesSomewhere(identifier)) return
                val fix = if (EnCfgScope.canDeclareIn(cls)) AddExternalDeclarationFix(base) else null
                holder.registerProblem(
                    identifier,
                    "Base class '$base' is not declared before this class: Addon Builder reports it as undefined",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    *listOfNotNull(fix).toTypedArray(),
                )
            }
        }
    }

    /** The super clause's reference resolves to at least one class in the indexed configs. */
    private fun resolvesSomewhere(identifier: PsiElement): Boolean {
        val reference = identifier.parent?.reference as? PsiPolyVariantReference ?: return false
        return reference.multiResolve(false).isNotEmpty()
    }
}

/** CfgConvert's lookup of a base class name, within one file. */
internal object EnCfgScope {

    private const val MAX_DEPTH = 32

    /** The declaration [cls]'s base [name] binds to, or null when CfgConvert would not find one. */
    fun visibleBase(cls: EnCfgClassMixin, name: String): EnCfgClassMixin? = lookup(cls, name, 0, HashSet())

    /**
     * Walks out from [from]: the body holding it, before it; then the members its enclosing class
     * inherits; then the next body out, before that class, and so on to the file.
     */
    private fun lookup(from: PsiElement, name: String, depth: Int, visiting: MutableSet<EnCfgClassMixin>): EnCfgClassMixin? {
        if (depth > MAX_DEPTH) return null
        var current: PsiElement = from
        while (true) {
            val owner = current.parent ?: return null
            declaredBefore(owner, current, name)?.let { return it }
            if (owner is PsiFile) return null
            val ownerClass = owner as? EnCfgClassMixin ?: return null
            inherited(ownerClass, name, depth + 1, visiting)?.let { return it }
            current = ownerClass
        }
    }

    /** A class named [name] among [owner]'s direct children, declared before [before]. */
    private fun declaredBefore(owner: PsiElement, before: PsiElement, name: String): EnCfgClassMixin? =
        PsiTreeUtil.getChildrenOfTypeAsList(owner, EnCfgClassMixin::class.java)
            .lastOrNull { it !== before && it.textRange.startOffset < before.textRange.startOffset && it.name.equals(name, ignoreCase = true) }

    /** A class named [name] among the members [cls] inherits: its base's, then that base's base's. */
    private fun inherited(cls: EnCfgClassMixin, name: String, depth: Int, visiting: MutableSet<EnCfgClassMixin>): EnCfgClassMixin? {
        if (depth > MAX_DEPTH || !visiting.add(cls)) return null
        try {
            val baseName = cls.getSuperName() ?: return null
            var base: EnCfgClassMixin? = lookup(cls, baseName, depth + 1, visiting)
            var steps = 0
            while (base != null && steps++ < MAX_DEPTH) {
                PsiTreeUtil.getChildrenOfTypeAsList(base, EnCfgClassMixin::class.java)
                    .lastOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
                val next = base.getSuperName() ?: return null
                if (!visiting.add(base)) return null
                base = lookup(base, next, depth + 1, visiting)
            }
            return null
        } finally {
            visiting.remove(cls)
        }
    }

    /**
     * Where the quick fix may add `class Base;`: only directly in a top-level section such as
     * `CfgVehicles`. Deeper, a same-named external declaration would shadow the member the class
     * meant to inherit, which is a different mistake.
     */
    fun canDeclareIn(cls: EnCfgClassMixin): Boolean {
        val section = cls.enclosingClass() ?: return false
        return section.enclosingClass() == null && !section.isForwardDeclaration()
    }
}

/**
 * Adds `class Base;` at the top of the section, after the external declarations already there,
 * with the indentation of the section's members.
 */
private class AddExternalDeclarationFix(private val base: String) : LocalQuickFix {

    override fun getName(): String = "Add 'class $base;'"

    override fun getFamilyName(): String = "Add the missing external class declaration"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val cls = PsiTreeUtil.getParentOfType(descriptor.psiElement, EnCfgClassMixin::class.java) ?: return
        // A fix applied to a whole file may already have added this one for another class.
        if (EnCfgScope.visibleBase(cls, base) != null) return
        val section = cls.enclosingClass() ?: return
        val file = cls.containingFile ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        documents.doPostponedOperationsAndUnblockDocument(document)

        // After the external declarations that open the section, else right after its `{`.
        val members = PsiTreeUtil.getChildrenOfTypeAsList(section, EnCfgClassMixin::class.java)
        val lastExternal = members.takeWhile { it.isForwardDeclaration() }.lastOrNull()
        val offset = lastExternal?.textRange?.endOffset
            ?: section.node.findChildByType(EnfusionConfigTypes.LBRACE)?.textRange?.endOffset
            ?: return
        // The members' own indentation, read off the line of the first one after the insertion.
        val sample = (lastExternal ?: cls).textRange.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(sample))
        val indent = document.charsSequence.subSequence(lineStart, sample).takeWhile { it == ' ' || it == '\t' }
        document.insertString(offset, "\n${indent}class $base;")
        documents.commitDocument(document)
    }
}
