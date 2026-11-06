package io.github.lemyst.enfusion.resolve

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * The named declarations under an element, in document order, without entering method bodies.
 *
 * Type and member lookups used to collect every named element of a whole file or class body and
 * filter afterwards. Nearly all of a script is method bodies, and nothing a lookup by type or member
 * name can match lives inside one: classes, enums, typedefs, methods, fields and enum constants sit at
 * file scope or directly in a class body. Walking every local and expression made resolving one link
 * of `GetGame().GetPlayer()` take half a second against vanilla and a few mods, a dozen
 * `modded class DayZGame` files at a time.
 */
internal object EnDeclarationWalk {

    /**
     * Calls [visit] on each [EnNamedElement] under [root], [root] itself excluded, in document order.
     * A method is visited but not entered. A class body is entered only when [intoClassBodies] is
     * set: the language has no nested classes, so a lookup for classes never needs to.
     */
    fun forEachNamed(root: PsiElement, intoClassBodies: Boolean, visit: (EnNamedElement) -> Unit) {
        // An explicit stack rather than recursion: the depth of a file's tree is its author's choice.
        val stack = ArrayDeque<ASTNode>()
        pushChildren(root.node ?: return, stack)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            (node.psi as? EnNamedElement)?.let(visit)
            val type = node.elementType
            if (type === EnfusionTypes.METHOD_DECL) continue
            if (type === EnfusionTypes.CLASS_BODY && !intoClassBodies) continue
            pushChildren(node, stack)
        }
    }

    /** Pushed last child first, so the stack pops them in document order. */
    private fun pushChildren(node: ASTNode, stack: ArrayDeque<ASTNode>) {
        var child = node.lastChildNode
        while (child != null) {
            stack.addLast(child)
            child = child.treePrev
        }
    }
}
