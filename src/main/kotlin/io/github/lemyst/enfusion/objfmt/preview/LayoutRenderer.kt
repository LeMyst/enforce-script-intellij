package io.github.lemyst.enfusion.objfmt.preview

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Draws placed widgets onto an image. No IntelliJ API: the pictures come from a function.
 *
 * Every widget gets a thin outline, so the arrangement is visible even where nothing is drawn;
 * colours, images and text come on top as the widget type suggests. Unknown types are labelled
 * boxes. Hidden widgets are drawn dashed only when asked for.
 */
object LayoutRenderer {

    private val TEXT_TYPES = setOf(
        "TextWidgetClass", "RichTextWidgetClass", "MultilineTextWidgetClass", "ButtonWidgetClass",
        "EditBoxWidgetClass", "MultilineEditBoxWidgetClass", "CheckBoxWidgetClass",
    )
    private val FILLED_TYPES = setOf("PanelWidgetClass", "ButtonWidgetClass", "ProgressBarWidgetClass", "SliderWidgetClass")

    fun render(
        placed: List<Placed>,
        width: Int,
        height: Int,
        showHidden: Boolean,
        selected: WidgetSpec?,
        image: (WidgetSpec, Int) -> BufferedImage?,
    ): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color(0x2B, 0x2D, 0x30)
            g.fillRect(0, 0, width, height)
            for (p in placed) {
                if (p.hidden && !showHidden) continue
                draw(g, p, image)
            }
            selected?.let { s -> placed.firstOrNull { it.spec === s }?.let { highlight(g, it) } }
        } finally {
            g.dispose()
        }
        return out
    }

    private fun draw(g: Graphics2D, p: Placed, image: (WidgetSpec, Int) -> BufferedImage?) {
        val r = p.rect
        val x = r.x.toInt()
        val y = r.y.toInt()
        val w = r.w.toInt().coerceAtLeast(1)
        val h = r.h.toInt().coerceAtLeast(1)
        val spec = p.spec
        val alpha = if (p.hidden) 0.35f else 1f
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        val color = spec.color
        when {
            spec.type == "ImageWidgetClass" -> {
                val picture = image(spec, maxOf(w, h))
                if (picture != null) {
                    g.drawImage(picture, x, y, w, h, null)
                } else if (color != null) {
                    fill(g, color, x, y, w, h)
                }
            }

            spec.type in FILLED_TYPES && color != null -> fill(g, color, x, y, w, h)
        }
        if (spec.type in TEXT_TYPES) spec.text?.takeIf { it.isNotBlank() }?.let { text(g, spec, it, x, y, w, h, color) }
        g.color = if (p.hidden) Color(255, 255, 255, 60) else Color(255, 255, 255, 45)
        g.stroke = if (p.hidden) BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 3f), 0f) else BasicStroke(1f)
        g.drawRect(x, y, w - 1, h - 1)
        g.composite = AlphaComposite.SrcOver
    }

    private fun fill(g: Graphics2D, argb: Int, x: Int, y: Int, w: Int, h: Int) {
        g.color = Color(argb, true)
        g.fillRect(x, y, w, h)
    }

    /** Text sized from the widget's height (the engine's fonts are not available), aligned as `text halign` says. */
    private fun text(g: Graphics2D, spec: WidgetSpec, text: String, x: Int, y: Int, w: Int, h: Int, color: Int?) {
        // A stringtable key shows as itself: the preview has no language to pick a translation in.
        val shown = text
        val size = (h * 0.6).toInt().coerceIn(8, 40)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, size)
        val metrics = g.fontMetrics
        val textWidth = metrics.stringWidth(shown)
        val align = spec.value("text halign") ?: spec.value("text_halign")
        val tx = when (align) {
            "center" -> x + (w - textWidth) / 2
            "right" -> x + w - textWidth
            else -> x
        }
        val ty = y + (h - metrics.height) / 2 + metrics.ascent
        val clip = g.clip
        g.clipRect(x, y, w, h)
        g.color = if (spec.type == "ButtonWidgetClass" || color == null) Color.WHITE else Color(color or (0xFF shl 24), true)
        g.drawString(shown, tx, ty)
        g.clip = clip
    }

    private fun highlight(g: Graphics2D, p: Placed) {
        g.color = Color(0x4D, 0xA3, 0xFF)
        g.stroke = BasicStroke(2f)
        g.drawRect(p.rect.x.toInt(), p.rect.y.toInt(), p.rect.w.toInt().coerceAtLeast(1) - 1, p.rect.h.toInt().coerceAtLeast(1) - 1)
    }
}
