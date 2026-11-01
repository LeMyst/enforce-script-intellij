package io.github.lemyst.enfusion.cfg.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.refs.EnCfgSuperReference

/**
 * `: Parent`, and the reference it carries.
 *
 * The reference lives on the clause rather than on the identifier because a Grammar-Kit *token* has
 * no PSI class of its own to hang one from, so its range is the identifier's offset within the
 * clause. That also keeps the colon out of the highlighted, renamed and Ctrl+Clickable region.
 */
abstract class EnCfgSuperClauseMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    override fun getReference(): PsiReference? {
        val identifier = node.findChildByType(EnfusionConfigTypes.IDENT) ?: return null
        val start = identifier.startOffset - node.startOffset
        return EnCfgSuperReference(this, TextRange(start, start + identifier.textLength))
    }

    override fun getReferences(): Array<PsiReference> =
        reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}
