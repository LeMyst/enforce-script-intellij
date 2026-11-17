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

    /** A picture of a style's frame, and the size it has in layout pixels (its imageset `Size`). */
    class Picture(val image: BufferedImage, val width: Double, val height: Double)

    /** The pictures of a style's frame in the state drawn, by piece. */
    class Frame(val pieces: Map<WidgetStyles.Piece, Picture>)

    private val TEXT_TYPES = setOf(
        "TextWidgetClass", "RichTextWidgetClass", "MultilineTextWidgetClass", "ButtonWidgetClass",
        "EditBoxWidgetClass", "MultilineEditBoxWidgetClass", "CheckBoxWidgetClass", "ThreeStateCheckboxWidgetClass",
    )
    private val CHECK_BOXES = setOf("CheckBoxWidgetClass", "ThreeStateCheckboxWidgetClass")

    /** Workbench's scroll bar, as it draws `day_z_server_browser_new`'s list. */
    private val SCROLL_BAR = Color(0xE0, 0x33, 0x2E)
    private const val SCROLL_BAR_WIDTH = 15.0
    /**
     * Filled with their `color`. Not buttons: a button's colour tints its style's pictures, and
     * vanilla writes `color 1 0 0 1` on plain-looking dialog buttons and `color 1 1 1 1` on the
     * digital lock's keys, which came out as red and white blocks.
     */
    private val FILLED_TYPES = setOf("PanelWidgetClass", "ProgressBarWidgetClass", "SliderWidgetClass")

    /** The widgets whose style is drawn as a frame of pictures ([WidgetStyles]). */
    private val STYLED_TYPES = setOf("PanelWidgetClass", "ButtonWidgetClass", "WindowWidgetClass", "SmartPanelWidgetClass")

    /** Styles that draw nothing, so a panel wearing one is not filled whatever its colour. */
    private val EMPTY_STYLES = setOf("blank", "empty", "emptyhighlight")
    private val SCREEN = Color(0x2B, 0x2D, 0x30)

    /** The whole layout as a picture at 1:1, for tests and exports. */
    fun render(
        placed: List<Placed>,
        width: Int,
        height: Int,
        showHidden: Boolean,
        selected: WidgetSpec?,
        text: (WidgetSpec) -> String? = { it.text },
        frame: (WidgetSpec) -> Frame? = { null },
        image: (WidgetSpec, Int) -> BufferedImage?,
    ): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            paint(g, placed, width, height, 1.0, showHidden, selected, text, frame, image)
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
        /** The frame a widget's style draws, already tinted; null keeps the plain colour. */
        frame: (WidgetSpec) -> Frame? = { null },
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
            draw(g, p, pixel, scale, text, frame, image)
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

    private fun draw(
        g: Graphics2D,
        p: Placed,
        pixel: Float,
        scale: Double,
        shown: (WidgetSpec) -> String?,
        frameOf: (WidgetSpec) -> Frame?,
        image: (WidgetSpec, Int) -> BufferedImage?,
    ) {
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

            // The style's frame, where its pictures were found; otherwise the plain colour below.
            spec.type in STYLED_TYPES && frameOf(spec)?.let { drawFrame(g, it, r) } == true -> Unit

            spec.type in FILLED_TYPES && color != null && spec.value("style")?.lowercase() !in EMPTY_STYLES -> fill(g, color, r)

            // A window is filled only by a colourable style (`dialog_input_password`'s dialog says
            // `style Colorable`); `day_z_map`'s root window is `rover_sim_black`, tinted pink.
            spec.type == "WindowWidgetClass" && color != null && spec.value("style")?.contains("colorable", ignoreCase = true) == true -> fill(g, color, r)
        }
        val textBox = controls(g, spec, r, pixel)
        if (spec.type in TEXT_TYPES) shown(spec)?.takeIf { it.isNotBlank() }?.let { text(g, spec, it, textBox, color) }
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

    /**
     * Draws [frame] into [r], each piece stretched to its place ([WidgetStyles.layout]). False
     * when no piece has a place, so that the widget falls back to its colour.
     */
    private fun drawFrame(g: Graphics2D, frame: Frame, r: Rect): Boolean {
        val places = WidgetStyles.layout(r, frame.pieces.mapValues { it.value.width to it.value.height })
        if (places.isEmpty()) return false
        for ((piece, place) in places) {
            val picture = frame.pieces.getValue(piece).image
            val before = g.transform
            g.translate(place.x, place.y)
            g.scale(place.w / picture.width, place.h / picture.height)
            g.drawImage(picture, 0, 0, null)
            g.transform = before
        }
        return true
    }

    /** The outline drawn half a line inside the box, so that neighbouring boxes do not overdraw each other. */
    private fun inset(r: Rect, by: Double) = Rectangle2D.Double(r.x + by, r.y + by, maxOf(0.0, r.w - 2 * by), maxOf(0.0, r.h - 2 * by))

    private fun fill(g: Graphics2D, argb: Int, r: Rect) {
        g.color = Color(argb, true)
        g.fill(r.shape())
    }

    /** Text sized from the widget's height (the engine's fonts are not available), aligned as `text halign` says. */
    /**
     * The parts of a control that are not its children, drawn plainly: a check box's box, a combo
     * box's arrows, a scroll widget's bars (`"Scrollbar V"`, `"Scrollbar V Left"`, `"Scrollbar H"`;
     * a server browser always has the vertical one). Returns where the control's text goes: after
     * a check box's box, else the whole box.
     */
    private fun controls(g: Graphics2D, spec: WidgetSpec, r: Rect, pixel: Float): Rect {
        when (spec.type) {
            in CHECK_BOXES -> {
                val side = (r.h * 0.75).coerceAtMost(r.w)
                val box = Rect(r.x + r.h * 0.1, r.y + (r.h - side) / 2, side, side)
                g.color = Color(0x1E, 0x1E, 0x1E)
                g.fill(box.shape())
                g.color = Color(0x6F, 0xB3, 0xC9)
                g.stroke = BasicStroke(maxOf(pixel, (side / 10).toFloat()))
                g.draw(inset(box, side / 20))
                val start = box.x + side + r.h * 0.15
                return Rect(start, r.y, maxOf(0.0, r.x + r.w - start), r.h)
            }

            "XComboBoxWidgetClass" -> {
                g.color = Color.WHITE
                val arrow = r.h * 0.3
                val cy = r.y + r.h / 2
                g.stroke = BasicStroke(maxOf(pixel, (r.h / 14).toFloat()))
                for ((tip, back) in listOf(r.x + arrow to r.x + 2 * arrow, r.x + r.w - arrow to r.x + r.w - 2 * arrow)) {
                    val path = java.awt.geom.Path2D.Double()
                    path.moveTo(back, cy - arrow / 2)
                    path.lineTo(tip, cy)
                    path.lineTo(back, cy + arrow / 2)
                    g.draw(path)
                }
            }

            "ServerBrowserWidgetClass", "ScrollWidgetClass" -> {
                g.color = SCROLL_BAR
                val bar = SCROLL_BAR_WIDTH.coerceAtMost(r.w / 2)
                val inset = 4.0
                if (spec.type == "ServerBrowserWidgetClass" || spec.flag("Scrollbar V", false)) {
                    val x = if (spec.flag("Scrollbar V Left", false)) r.x else r.x + r.w - bar
                    g.fill(Rectangle2D.Double(x, r.y + inset, bar, maxOf(0.0, r.h - 2 * inset)))
                }
                if (spec.flag("Scrollbar H", false)) {
                    g.fill(Rectangle2D.Double(r.x + inset, r.y + r.h - bar, maxOf(0.0, r.w - 2 * inset), bar))
                }
            }
        }
        return r
    }

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
     * (1 by default: vanilla writes every value from 0 to 0.9, never 1). See [fontSize] for how it
     * becomes a font. The fonts themselves are not Enfusion's.
     */
    internal fun textSize(spec: WidgetSpec, r: Rect): Double =
        if (exactText(spec)) {
            spec.number("exact text size", 0, DEFAULT_EXACT_TEXT_SIZE)
        } else {
            r.h * spec.number("text_proportion", 0, 1.0)
        }

    /**
     * Whether the text size is `"exact text size"` rather than a share of the box: when it says
     * `"exact text" 1`, and when its box follows its text (`"size to text v"`) and it has a size to
     * use: a size from the box would grow the box, which grows the size. `dialog_input_password`'s
     * `MessageText` says `"exact text" 0`, `"exact text size" 22` and is sized to its text.
     */
    private fun exactText(spec: WidgetSpec): Boolean =
        spec.flag("exact text", false) || (spec.flag("size to text v", false) && spec.value("exact text size") != null)

    /**
     * Not 22, which vanilla writes 698 times, so is not what Workbench leaves out. 14 is what
     * `day_z_server_browser_new`'s `ServerCountText` (`"exact text" 1`, no size) looks like there.
     */
    private const val DEFAULT_EXACT_TEXT_SIZE = 14.0

    /**
     * The IDE font size, in layout pixels, for [textSize]. An `"exact text size"` is the font's em:
     * `day_z_connection_dialogue`'s description, 20, and the in-game menu's modded warning, 20, are
     * that size in Workbench. A proportional size is the height of a whole line: sized by em, the
     * in-game menu's server name overflowed the box Workbench fits it in. [perEm] is the line height
     * of a one-pixel font.
     */
    internal fun fontSize(spec: WidgetSpec, r: Rect, perEm: Double): Double =
        if (exactText(spec)) textSize(spec, r) else textSize(spec, r) / perEm

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
     * times, `left`, never `top`. A button's own text and a check box's label are centred
     * vertically, a button's also across unless it says otherwise (`HostColumnBtn`'s
     * `text_halign left`). `text_offset` moves it.
     */
    private fun text(g: Graphics2D, spec: WidgetSpec, text: String, r: Rect, color: Int?) {
        val reference = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(100f)
        val perEm = g.getFontMetrics(reference).let { (it.ascent + it.descent) / 100.0 }
        g.font = reference.deriveFont(fontSize(spec, r, perEm).toFloat().coerceAtLeast(1f))
        val metrics = g.fontMetrics
        val line = (metrics.ascent + metrics.descent).toDouble()
        val measure = { s: String -> metrics.getStringBounds(s, g).width }
        val button = spec.type == "ButtonWidgetClass"
        val lines = if (spec.flag("wrap", false)) wrap(text, r.w, measure) else text.split('\n')
        val halign = spec.value("text halign") ?: spec.value("text_halign") ?: if (button) "center" else null
        val valign = spec.value("text valign") ?: spec.value("text_valign") ?: if (button || spec.type in CHECK_BOXES) "center" else null
        val dx = spec.number("text_offset", 0, 0.0)
        val dy = spec.number("text_offset", 1, 0.0)
        val block = lines.size * line
        val top = when (valign) {
            "center" -> r.y + (r.h - block) / 2
            "bottom" -> r.y + r.h - block
            else -> r.y
        }
        val clip = g.clip
        // Text sized to its text is laid out from its key, before translation, so its box can be
        // a line short of the translated text: drawn past the box rather than cut off.
        if (!spec.flag("size to text v", false)) g.clip(r.shape())
        g.color = if (button || color == null) Color.WHITE else Color(color or (0xFF shl 24), true)
        lines.forEachIndexed { i, row ->
            val w = measure(row)
            val tx = when (halign) {
                "center" -> r.x + (r.w - w) / 2
                "right" -> r.x + r.w - w
                else -> r.x
            }
            g.drawString(row, (tx + dx).toFloat(), (top + dy + i * line + metrics.ascent).toFloat())
        }
        g.clip = clip
    }

    private fun highlight(g: Graphics2D, p: Placed, pixel: Float) {
        g.color = Color(0x4D, 0xA3, 0xFF)
        g.stroke = BasicStroke(2 * pixel)
        g.draw(inset(p.rect, pixel.toDouble()))
    }
}
