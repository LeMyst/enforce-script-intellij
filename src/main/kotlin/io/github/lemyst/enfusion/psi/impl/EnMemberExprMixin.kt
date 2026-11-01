package io.github.lemyst.enfusion.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.resolve.EnMemberReference

/**
 * `expr.member` — the member half carries the reference; the qualifier carries its own.
 *
 * `Math.PI2` therefore has two independent references: `Math` resolves to the class, `PI2` to the
 * constant. Ctrl+Click does the right thing wherever the caret sits, and Find Usages on the
 * constant's declaration finds this site, which it could not before a reference pointed at it.
 */
abstract class EnMemberExprMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    override fun getReference(): PsiReference? {
        // The member name is the IDENT after the dot, never the qualifier's own identifier.
        val dot = node.findChildByType(EnfusionTypes.DOT) ?: return null
        var child = dot.treeNext
        while (child != null) {
            if (child.elementType === EnfusionTypes.IDENT) {
                return EnMemberReference(this, child.textRange.shiftLeft(node.startOffset))
            }
            child = child.treeNext
        }
        return null
    }
}
