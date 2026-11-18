package io.github.lemyst.enfusion.colors

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTokenType
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.objfmt.psi.impl.EnObjPropertyMixin
import io.github.lemyst.enfusion.psi.EnfusionTypes
import java.awt.Color

/**
 * A swatch in the gutter beside each colour, and the IDE's colour picker on a click, writing the
 * colour back in the notation it was read from:
 *
 * - **Layouts:** `color r g b a` and the quoted `"text color"`, `"shadow color"`, … properties.
 *   The channels are the colour's own fractions of 255, as the game draws them (see [EnColorMath]).
 * - **`.styles`:** `Color="4294967295"`, an ARGB integer.
 * - **Script:** `ARGB(a, r, g, b)` and `ARGBF(a, r, g, b)` with literal colour channels (the
 *   alpha may be any expression), and the `0xAARRGGBB` initializer of a `const int` named for a
 *   colour (`COLOR_RED`).
 * - **Procedural textures**, in configs and script strings: `#(argb,8,8,3)color(r,g,b,a,co)`.
 *
 * The swatch sits on the first token of each notation, which is a leaf, as the platform expects.
 */
class EnColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? = EnColorNotations.find(element)?.color

    /**
     * The picker calls this for every change, always with the element its swatch was made for. A
     * write that replaces that very token (a `.styles` value, a hex constant, a string) leaves it
     * invalid for the next call, so it is found again where it started. Every write lands at or
     * after the swatch's own start, so that offset does not move while the picker is open.
     */
    override fun setColorTo(element: PsiElement, color: Color) {
        val anchor = if (element.isValid) {
            val file = element.containingFile ?: return
            Anchor(file, element.textRange.startOffset).also { element.putUserData(ANCHOR, it) }
        } else {
            element.getUserData(ANCHOR) ?: return
        }
        val file = anchor.file.takeIf { it.isValid } ?: return
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return
        documents.doPostponedOperationsAndUnblockDocument(document)
        documents.commitDocument(document)
        val leaf = if (element.isValid) element else file.findElementAt(anchor.offset) ?: return
        val found = EnColorNotations.find(leaf) ?: return
        val range = found.range
        document.replaceString(range.startOffset, range.endOffset, found.write(color))
        documents.commitDocument(document)
    }

    private class Anchor(val file: PsiFile, val offset: Int)

    private companion object {
        val ANCHOR = Key.create<Anchor>("enfusion.color.anchor")
    }
}

/** A colour read from the text, where it was written, and how to write another in its place. */
internal class EnFoundColor(val color: Color, val range: com.intellij.openapi.util.TextRange, val write: (Color) -> String)

internal object EnColorNotations {

    fun find(element: PsiElement): EnFoundColor? {
        if (element.firstChild != null) return null
        val type = element.node?.elementType ?: return null
        return when (type) {
            EnfusionObjectTypes.IDENT, EnfusionObjectTypes.STRING -> layoutProperty(element)
            EnfusionTypes.IDENT -> scriptArgb(element)
            EnfusionTypes.INT_LITERAL -> scriptHexConstant(element)
            EnfusionTypes.STRING, EnfusionConfigTypes.STRING -> procedural(element)
            XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN -> stylesColor(element)
            else -> null
        }
    }

    // --- layouts -------------------------------------------------------------------------------

    private fun layoutProperty(key: PsiElement): EnFoundColor? {
        val property = key.parent as? EnObjPropertyMixin ?: return null
        if (property.firstChild !== key) return null
        val name = property.getKey()?.lowercase() ?: return null
        if (name != "color" && !name.endsWith(" color")) return null
        val values = property.values()
        if (values.size !in 3..4) return null
        val channels = values.map { it.text.toDoubleOrNull() ?: return null }
        val color = EnColorMath.fromChannels(channels[0], channels[1], channels[2], channels.getOrElse(3) { 1.0 })
        val range = com.intellij.openapi.util.TextRange(values.first().textRange.startOffset, values.last().textRange.endOffset)
        return EnFoundColor(color, range) { EnColorMath.toChannelText(it, values.size == 4) }
    }

    // --- .styles -------------------------------------------------------------------------------

    private fun stylesColor(token: PsiElement): EnFoundColor? {
        if (token.containingFile?.name?.endsWith(".styles", ignoreCase = true) != true) return null
        val attribute = PsiTreeUtil.getParentOfType(token, XmlAttribute::class.java) ?: return null
        if (!attribute.name.equals("Color", ignoreCase = true)) return null
        val argb = token.text.trim().toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL } ?: return null
        return EnFoundColor(Color(argb.toInt(), true), token.textRange) { (it.rgb.toLong() and 0xFFFFFFFFL).toString() }
    }

    // --- script --------------------------------------------------------------------------------

    /** `ARGB(255, 242, 38, 19)` and `ARGBF(1, 0.95, 0.15, 0.07)`: the callee's name carries it. */
    private fun scriptArgb(ident: PsiElement): EnFoundColor? {
        val name = ident.text
        if (name != "ARGB" && name != "ARGBF") return null
        val ref = ident.parent?.takeIf { it.node.elementType === EnfusionTypes.REF_EXPR } ?: return null
        val call = ref.parent?.takeIf { it.node.elementType === EnfusionTypes.CALL_EXPR && it.firstChild === ref } ?: return null
        val argList = call.node.findChildByType(EnfusionTypes.ARG_LIST)?.psi ?: return null
        val args = argList.children.filter { it !is PsiWhiteSpace }
        if (args.size != 4) return null
        val rgb = args.drop(1).map { arg -> signedNumber(arg) ?: return null }
        // A fade computes the alpha at run time (`ARGB(m_Alpha, 242, 199, 68)`), yet the colour is
        // still known. It is shown opaque, and the picker writes the three channels around the
        // alpha expression, which stays as written.
        val alpha = signedNumber(args[0])
        val first = if (alpha != null) args[0] else args[1]
        val range = com.intellij.openapi.util.TextRange(first.textRange.startOffset, args.last().textRange.endOffset)
        return if (name == "ARGB") {
            val (r, g, b) = rgb.map { it.toInt().coerceIn(0, 255) }
            val a = alpha?.toInt()?.coerceIn(0, 255) ?: 255
            EnFoundColor(Color(r, g, b, a), range) { picked ->
                listOfNotNull(picked.alpha.takeIf { alpha != null }, picked.red, picked.green, picked.blue).joinToString(", ")
            }
        } else {
            val (r, g, b) = rgb.map { it.coerceIn(0.0, 1.0).toFloat() }
            val a = alpha?.coerceIn(0.0, 1.0)?.toFloat() ?: 1f
            EnFoundColor(Color(r, g, b, a), range) { picked ->
                listOfNotNull(picked.alpha.takeIf { alpha != null }, picked.red, picked.green, picked.blue)
                    .joinToString(", ") { c -> EnColorMath.decimal(c / 255.0, 2) }
            }
        }
    }

    private fun signedNumber(arg: PsiElement): Double? = when (arg.node.elementType) {
        EnfusionTypes.LITERAL_EXPR -> arg.text.removeSuffix("f").toDoubleOrNull()
        EnfusionTypes.UNARY_EXPR -> null
        else -> null
    }

    /**
     * `const int COLOR_RED = 0xFFF22613;`. Only a constant named for a colour, and only the eight
     * digit form: a flag or a mask in hex would otherwise get a swatch too.
     */
    private fun scriptHexConstant(literal: PsiElement): EnFoundColor? {
        val text = literal.text
        if (!HEX_COLOR.matches(text)) return null
        val declarator = literal.parent?.parent?.takeIf { it.node.elementType === EnfusionTypes.VAR_DECLARATOR } ?: return null
        val declaration = declarator.parent ?: return null
        if (declaration.node.findChildByType(EnfusionTypes.CONST) == null) return null
        val name = declarator.node.findChildByType(EnfusionTypes.IDENT)?.text ?: return null
        if (!COLOR_NAME.containsMatchIn(name)) return null
        val argb = text.substring(2).toLong(16).toInt()
        val upper = text[1] == 'X'
        return EnFoundColor(Color(argb, true), literal.textRange) {
            val hex = String.format("%08X", it.rgb.toLong() and 0xFFFFFFFFL)
            (if (upper) "0X" else "0x") + hex
        }
    }

    // --- procedural textures -------------------------------------------------------------------

    /** `"#(argb,8,8,3)color(0.5,0.5,0.5,1,co)"`, in a config value or a script string. */
    private fun procedural(string: PsiElement): EnFoundColor? {
        val text = string.text
        val match = PROCEDURAL.find(text) ?: return null
        val channels = match.groupValues.subList(1, 5).map { it.toDoubleOrNull() ?: return null }
        val (r, g, b, a) = channels.map { it.coerceIn(0.0, 1.0).toFloat() }
        val group = match.groups[1]!!.range.first
        val end = match.groups[4]!!.range.last + 1
        val start = string.textRange.startOffset
        return EnFoundColor(Color(r, g, b, a), com.intellij.openapi.util.TextRange(start + group, start + end)) {
            listOf(it.red, it.green, it.blue, it.alpha).joinToString(",") { c -> EnColorMath.decimal(c / 255.0, 3) }
        }
    }

    private val HEX_COLOR = Regex("0[xX][0-9A-Fa-f]{8}")
    private val COLOR_NAME = Regex("colou?r", RegexOption.IGNORE_CASE)
    private const val NUMBER = """\s*(-?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\s*"""
    private val PROCEDURAL = Regex("""#\(argb,[^)]*\)color\($NUMBER,$NUMBER,$NUMBER,$NUMBER(?:,|\))""", RegexOption.IGNORE_CASE)
}

/** Colour arithmetic, with no IntelliJ API. */
internal object EnColorMath {

    /**
     * Layout channels, 0 to 1: each the colour's own fraction of 255, with no gamma. Measured in
     * game on 2026-11-18: an `ImageWidget` with `color 0.141 0.141 0.141 1` was drawn `#242424`,
     * the same as one given `ARGB(255, 36, 36, 36)` from script. Workbench's editor shows `0.141`
     * as `#696969`, as if it were linear, but the game does not, and the swatch shows what the
     * game draws.
     */
    fun fromChannels(r: Double, g: Double, b: Double, a: Double): Color =
        Color(byte(r), byte(g), byte(b), byte(a))

    /** `0.141 0.141 0.141 1`, back from a picked colour, three decimals as vanilla writes them. */
    fun toChannelText(color: Color, withAlpha: Boolean): String {
        val channels = mutableListOf(color.red / 255.0, color.green / 255.0, color.blue / 255.0)
        if (withAlpha) channels += color.alpha / 255.0
        return channels.joinToString(" ") { decimal(it, 3) }
    }

    /** `0.5`, `1`, `0.141`: at most [places] decimals, trailing zeros dropped. */
    fun decimal(value: Double, places: Int): String {
        val text = String.format(java.util.Locale.ROOT, "%.${places}f", value)
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    private fun byte(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
}
