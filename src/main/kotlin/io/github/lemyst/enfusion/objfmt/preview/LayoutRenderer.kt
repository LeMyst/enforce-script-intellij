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
        val saved = g.transform
        rotate(g, spec, r)
        when {
            spec.type == "ImageWidgetClass" -> {
                val picture = image(spec, (maxOf(r.w, r.h) * scale).toInt().coerceAtLeast(1))
                if (picture != null) {
                    // Stretched to the box: `fixaspect` has already shaped the box itself.
                    val before = g.transform
                    g.translate(r.x, r.y)
                    g.scale(r.w / picture.width, r.h / picture.height)
                    g.drawImage(picture, 0, 0, null)
                    g.transform = before
                } else if (color != null && !LayoutSpecs.hasImage(spec)) {
                    // A widget with no picture is a tinted white quad in the game. One whose picture
                    // was not found shows only its outline: filling it with the tint, as before,
                    // turned every dial of the car HUD into a solid grey or red block.
                    fill(g, color, r)
                }
            }

            spec.type in FILLED_TYPES && color != null -> fill(g, color, r)
        }
        if (spec.type in TEXT_TYPES) shown(spec)?.takeIf { it.isNotBlank() }?.let { text(g, spec, it, r, color) }
        g.transform = saved
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
    /**
     * `rotation x y z` turns the widget's picture and text by `z` degrees about its `pivot` (fractions
     * of its box, 0 0 by default), negative being anticlockwise: the car HUD's needles are drawn
     * upright and turned, `RPMPointer` by -90 to point left. The outline stays upright, as in
     * Workbench, and children are not turned.
     */
    private fun rotate(g: Graphics2D, spec: WidgetSpec, r: Rect) {
        val degrees = spec.number("rotation", 2, 0.0)
        if (degrees == 0.0) return
        g.rotate(Math.toRadians(degrees), r.x + spec.number("pivot", 0, 0.0) * r.w, r.y + spec.number("pivot", 1, 0.0) * r.h)
    }

    /**
     * The font size a text widget asks for, in layout pixels: `"exact text size"` when it says
     * `"exact text" 1` (1,262 vanilla widgets do), else its box's height times `text_proportion`
     * (1 by default: vanilla writes every value from 0 to 0.9, never 1). It is the height of a line,
     * not the font's em: sized by em, the IDE's font came out an eighth too big, and the in-game
     * menu's server name overflowed its box where Workbench fits it. The fonts themselves are not
     * Enfusion's.
     */
    internal fun textSize(spec: WidgetSpec, r: Rect): Double =
        if (spec.flag("exact text", false)) {
            spec.number("exact text size", 0, 22.0)
        } else {
            r.h * spec.number("text_proportion", 0, 1.0)
        }

    /** [text] broken into lines no wider than [width] by [measure], at spaces; a word wider than that keeps its own line. */
    internal fun wrap(text: String, width: Double, measure: (String) -> Double): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && measure(candidate) > width) {
                    lines += line
                    line = word
                } else {
                    line = candidate
                }
            }
            lines += line
        }
        return lines
    }

    /**
     * The text at [textSize], wrapped to the box when it says `wrap 1`, placed by `"text halign"` and
     * `"text valign"`, top left by default: vanilla only ever writes `center`, `right` and, five
     * times, `left`, never `top`. A button's own text is centred both ways.
     */
    private fun text(g: Graphics2D, spec: WidgetSpec, text: String, r: Rect, color: Int?) {
        val reference = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(100f)
        val perEm = g.getFontMetrics(reference).let { (it.ascent + it.descent) / 100.0 }
        g.font = reference.deriveFont((textSize(spec, r) / perEm).toFloat().coerceAtLeast(1f))
        val metrics = g.fontMetrics
        val line = (metrics.ascent + metrics.descent).toDouble()
        val measure = { s: String -> metrics.getStringBounds(s, g).width }
        val button = spec.type == "ButtonWidgetClass"
        val lines = if (spec.flag("wrap", false)) wrap(text, r.w, measure) else text.split('\n')
        val halign = if (button) "center" else spec.value("text halign") ?: spec.value("text_halign")
        val valign = if (button) "center" else spec.value("text valign") ?: spec.value("text_valign")
        val block = lines.size * line
        val top = when (valign) {
            "center" -> r.y + (r.h - block) / 2
            "bottom" -> r.y + r.h - block
            else -> r.y
        }
        val clip = g.clip
        g.clip(r.shape())
        g.color = if (button || color == null) Color.WHITE else Color(color or (0xFF shl 24), true)
        lines.forEachIndexed { i, row ->
            val w = measure(row)
            val tx = when (halign) {
                "center" -> r.x + (r.w - w) / 2
                "right" -> r.x + r.w - w
                else -> r.x
            }
            g.drawString(row, tx.toFloat(), (top + i * line + metrics.ascent).toFloat())
        }
        g.clip = clip
    }

    private fun highlight(g: Graphics2D, p: Placed, pixel: Float) {
        g.color = Color(0x4D, 0xA3, 0xFF)
        g.stroke = BasicStroke(2 * pixel)
        g.draw(inset(p.rect, pixel.toDouble()))
    }
}
