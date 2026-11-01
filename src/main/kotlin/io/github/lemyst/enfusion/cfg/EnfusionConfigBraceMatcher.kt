package io.github.lemyst.enfusion.cfg

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes

/**
 * Braces are structural — a class body or an array — so they get the block treatment. The `[]` pair
 * only ever appears empty, as the marker in `files[] =`, so it is matched but not treated as a block.
 */
class EnfusionConfigBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(
        lbraceType: IElementType,
        contextType: IElementType?,
    ): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int =
        openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(EnfusionConfigTypes.LBRACE, EnfusionConfigTypes.RBRACE, true),
            BracePair(EnfusionConfigTypes.LBRACKET, EnfusionConfigTypes.RBRACKET, false),
        )
    }
}
