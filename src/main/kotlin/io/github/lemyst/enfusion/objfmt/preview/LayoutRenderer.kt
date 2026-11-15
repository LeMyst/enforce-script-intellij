package io.github.lemyst.enfusion.objfmt.preview

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/**
 * Draws placed widgets. No IntelliJ API: the pictures come from a function.
 *
 * Every widget gets a thin outline, so the arrangement is visible even where nothing is drawn;
 * colours, images and text come on top as the widget type suggests. Unknown types are labelled
 * boxes. Hidden widgets are drawn dashed only when asked for.
 *
 * [paint] draws in layout pixels onto a graphics already scaled by `scale`, so the canvas can zoom
 * without first drawing the whole screen into a picture and shrinking it: shrunk, a 3840 × 2160
 * layout lost its one-pixel outlines. Lines are therefore `1 / scale` wide, one screen pixel at any
 * zoom.
 */
object LayoutRenderer {

    private val TEXT_TYPES = setOf(
        "TextWidgetClass", "RichTextWidgetClass", "MultilineTextWidgetClass", "ButtonWidgetClass",
        "EditBoxWidgetClass", "MultilineEditBoxWidgetClass", "CheckBoxWidgetClass",
    )
    private val FILLED_TYPES = setOf("PanelWidgetClass", "ButtonWidgetClass", "ProgressBarWidgetClass", "SliderWidgetClass")
    private val SCREEN = Color(0x2B, 0x2D, 0x30)

    /** The whole layout as a picture at 1:1, for tests and exports. */
    fun render(
        placed: List<Placed>,
        width: Int,
        height: Int,
        showHidden: Boolean,
        selected: WidgetSpec?,
        text: (WidgetSpec) -> String? = { it.text },
        image: (WidgetSpec, Int) -> BufferedImage?,
    ): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            paint(g, placed, width, height, 1.0, showHidden, selected, text, image)
        } finally {
            g.dispose()
        }
        return out
    }

    /** Draws onto [g], whose transform already maps layout pixels to the screen at [scale]. */
    fun paint(
        g: Graphics2D,
        placed: List<Placed>,
        width: Int,
        height: Int,
        scale: Double,
        showHidden: Boolean,
        selected: WidgetSpec?,
        /** What a widget's `text` shows: the preview's chosen language turns `#key`s into translations. */
        text: (WidgetSpec) -> String? = { it.text },
        image: (WidgetSpec, Int) -> BufferedImage?,
    ) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val pixel = (1.0 / scale).toFloat()
        g.color = SCREEN
        g.fill(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
        val clip = g.clip
        for (p in placed) {
            if (p.hidden && !showHidden) continue
            g.clip(p.clip.shape())
            draw(g, p, pixel, scale, text, image)
            g.clip = clip
        }
        // The screen's edge, so that it stays visible when zoomed out or panned.
        g.color = Color(255, 255, 255, 70)
        g.stroke = BasicStroke(pixel)
        g.draw(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
        // Unclipped: the selection shows the widget's whole box, even the part its parent cuts off.
        selected?.let { s -> placed.firstOrNull { it.spec === s }?.let { highlight(g, it, pixel) } }
    }

    private fun Rect.shape() = Rectangle2D.Double(x, y, w, h)

    private fun draw(g: Graphics2D, p: Placed, pixel: Float, scale: Double, shown: (WidgetSpec) -> String?, image: (WidgetSpec, Int) -> BufferedImage?) {
        val r = p.rect
        val spec = p.spec
        val alpha = if (p.hidden) 0.35f else 1f
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        val color = spec.color
        when {
            spec.type == "ImageWidgetClass" -> {
                val picture = image(spec, (maxOf(r.w, r.h) * scale).toInt().coerceAtLeast(1))
                if (picture != null) {
                    val at = LayoutGeometry.imageRect(r, picture.width, picture.height, spec.value("fixaspect"))
                    val saved = g.transform
                    g.translate(at.x, at.y)
                    g.scale(at.w / picture.width, at.h / picture.height)
                    g.drawImage(picture, 0, 0, null)
                    g.transform = saved
                } else if (color != null) {
                    fill(g, color, r)
                }
            }

            spec.type in FILLED_TYPES && color != null -> fill(g, color, r)
        }
        if (spec.type in TEXT_TYPES) shown(spec)?.takeIf { it.isNotBlank() }?.let { text(g, spec, it, r, color) }
        g.color = if (p.hidden) Color(255, 255, 255, 70) else Color(255, 255, 255, 55)
        g.stroke = if (p.hidden) {
            BasicStroke(pixel, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4 * pixel, 3 * pixel), 0f)
        } else {
            BasicStroke(pixel)
        }
        g.draw(inset(r, pixel / 2.0))
        g.composite = AlphaComposite.SrcOver
    }

    /** The outline drawn half a line inside the box, so that neighbouring boxes do not overdraw each other. */
    private fun inset(r: Rect, by: Double) = Rectangle2D.Double(r.x + by, r.y + by, maxOf(0.0, r.w - 2 * by), maxOf(0.0, r.h - 2 * by))

    private fun fill(g: Graphics2D, argb: Int, r: Rect) {
        g.color = Color(argb, true)
        g.fill(r.shape())
    }

    /** Text sized from the widget's height (the engine's fonts are not available), aligned as `text halign` says. */
    private fun text(g: Graphics2D, spec: WidgetSpec, text: String, r: Rect, color: Int?) {
        val size = (r.h * 0.6).toFloat().coerceIn(8f, 40f)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size)
        val metrics = g.fontMetrics
        val textWidth = metrics.getStringBounds(text, g).width
        val tx = when (spec.value("text halign") ?: spec.value("text_halign")) {
            "center" -> r.x + (r.w - textWidth) / 2
            "right" -> r.x + r.w - textWidth
            else -> r.x
        }
        val ty = r.y + (r.h - metrics.height) / 2 + metrics.ascent
        val clip = g.clip
        g.clip(r.shape())
        g.color = if (spec.type == "ButtonWidgetClass" || color == null) Color.WHITE else Color(color or (0xFF shl 24), true)
        g.drawString(text, tx.toFloat(), ty.toFloat())
        g.clip = clip
    }

    private fun highlight(g: Graphics2D, p: Placed, pixel: Float) {
        g.color = Color(0x4D, 0xA3, 0xFF)
        g.stroke = BasicStroke(2 * pixel)
        g.draw(inset(p.rect, pixel.toDouble()))
    }
}
