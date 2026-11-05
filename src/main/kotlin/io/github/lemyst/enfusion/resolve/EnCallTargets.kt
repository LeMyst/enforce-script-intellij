package io.github.lemyst.enfusion.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Every overload a call's argument list could be meant for, for parameter info.
 *
 * Not the references' answer. Those narrow by argument count ([EnOverloadFilter]), which is right for
 * Ctrl+Click on a finished call and wrong while the call is still being typed: at `Send(` there are
 * no arguments yet, and every overload is a candidate the hint must show. So this walks the same
 * scopes the references do, by name, and keeps every method it finds.
 */
object EnCallTargets {

    /** The methods [argList] may be passing arguments to: a call's callee, or `new X(...)`'s constructors. */
    fun overloadsOf(argList: PsiElement): List<EnMethodDeclMixin> {
        val owner = argList.parent ?: return emptyList()
        val candidates = when (owner.node?.elementType) {
            EnfusionTypes.CALL_EXPR -> calleeCandidates(firstChild(owner))
            EnfusionTypes.NEW_EXPR -> constructorsOf(owner)
            else -> emptyList()
        }
        // A `modded class` layer that overrides a method redeclares it with the same parameters, and
        // the hint should show that signature once, not once per layer.
        return candidates.filterIsInstance<EnMethodDeclMixin>()
            .distinctBy { it.getParameterListText() }
    }

    private fun calleeCandidates(callee: PsiElement?): List<PsiElement> {
        val project = callee?.project ?: return emptyList()
        return when (callee.node?.elementType) {
            EnfusionTypes.MEMBER_EXPR -> {
                val name = memberName(callee) ?: return emptyList()
                val qualifier = firstChild(callee)
                if (qualifier?.node?.elementType === EnfusionTypes.SUPER_EXPR) {
                    val owner = PsiTreeUtil.getParentOfType(callee, EnClassDeclMixin::class.java)
                        ?: return emptyList()
                    return EnTypeInference.findSuperMembers(owner, name, project)
                }
                val out = LinkedHashSet<PsiElement>()
                val visited = HashSet<String>()
                for (owner in EnTypeInference.classesOf(qualifier, project)) {
                    EnTypeInference.findMembers(owner, name, project, out, visited = visited)
                }
                out.toList()
            }

            // A bare call: a method of the enclosing class first, then a free function, the order
            // the identifier reference resolves in.
            EnfusionTypes.REF_EXPR -> {
                val name = callee.node.findChildByType(EnfusionTypes.IDENT)?.text ?: return emptyList()
                val owner = PsiTreeUtil.getParentOfType(callee, EnClassDeclMixin::class.java)
                val members = owner?.let { EnTypeInference.findMembers(it, name, project) }.orEmpty()
                    .filter { it is EnMethodDeclMixin && !it.isConstructor() && !it.isDestructor() }
                members.ifEmpty { EnTypeInference.fileScopeDeclarationsNamed(name, project) }
            }

            else -> emptyList()
        }
    }

    /**
     * The constructors of the class `new X(...)` names, across its `modded class` layers. A class
     * that declares none is built without arguments, so there is nothing to show.
     */
    private fun constructorsOf(newExpr: PsiElement): List<PsiElement> {
        val typeRef = newExpr.node.findChildByType(EnfusionTypes.TYPE_REF)?.psi ?: return emptyList()
        val name = EnTypeInference.baseTypeName(typeRef.text) ?: return emptyList()
        return EnTypeInference.classesNamed(name, newExpr.project).flatMap { layer ->
            val body = layer.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi ?: return@flatMap emptyList()
            body.children.filter { it is EnMethodDeclMixin && it.isConstructor() }
        }
    }

    /** The member name in `a.b`: the identifier after the dot, never the qualifier's own. */
    private fun memberName(memberExpr: PsiElement): String? {
        var child = memberExpr.node.findChildByType(EnfusionTypes.DOT)?.treeNext
        while (child != null) {
            if (child.elementType === EnfusionTypes.IDENT) return child.text
            child = child.treeNext
        }
        return null
    }

    private fun firstChild(element: PsiElement): PsiElement? =
        element.children.firstOrNull { it !is PsiWhiteSpace }
}
