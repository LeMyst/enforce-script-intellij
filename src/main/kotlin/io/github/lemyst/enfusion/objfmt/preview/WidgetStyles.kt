package io.github.lemyst.enfusion.objfmt.preview

/**
 * The widget styles of the `.styles` files under `gui/looknfeel` (vanilla: `dayzwidgets.styles`), which the game
 * draws a widget with: a widget's `style` names one of the styles of its class, and each state of a
 * style is a frame of nine imageset pictures, tinted by the widget's `color`. No IntelliJ API.
 *
 * ```
 * <WidgetStyles>
 *     <Widget Name="PanelWidget">
 *         <Style Name="rover_sim_black" Font="" ImageSet="rover_imageset" Color="4294967295">
 *             <State Name="Normal">
 *                 <Item Name="LeftTop" Image="SimBlackLeftTop" />
 *                 …
 * ```
 *
 * Read with a tag scanner, not an XML parser: the file is a few fixed elements with quoted
 * attributes, a mod's file may be malformed, and a parser would bring DTDs and entities in for
 * nothing.
 */
object WidgetStyles {

    /** The nine pieces of a frame, as `Item Name` says them. Other items (a progress bar's `Bar…`) are not drawn. */
    enum class Piece(val itemName: String) {
        LEFT_TOP("LeftTop"), TOP("Top"), RIGHT_TOP("RightTop"),
        LEFT("Left"), CENTER("Center"), RIGHT("Right"),
        LEFT_BOTTOM("LeftBottom"), BOTTOM("Bottom"), RIGHT_BOTTOM("RightBottom");

        companion object {
            fun of(itemName: String): Piece? = entries.firstOrNull { it.itemName.equals(itemName, ignoreCase = true) }
        }
    }

    /**
     * A style: its imageset, the pictures of each state by piece (states by lower-case name), and
     * its own `Color`, an ARGB integer, which tints a widget that sets no `color`: vanilla's are
     * white (`4294967295`) or transparent (`0`, and three blues with no alpha). `Colorable` is white
     * pixels with `Color="0"`, so `day_z_hud`'s `QuickbarGrid`, wearing it with no colour, shows
     * nothing in Workbench rather than a white bar.
     */
    class Style(val name: String, val imageSet: String, val states: Map<String, Map<Piece, String>>, val color: Int? = null) {
        /** The pieces of [state] that name a picture; empty when the style draws nothing in it. */
        fun frame(state: String = "Normal"): Map<Piece, String> = states[state.lowercase()].orEmpty()
    }

    /** Styles by widget (`PanelWidget`), then by style name, both lower case. */
    class Sheet(val widgets: Map<String, Map<String, Style>>) {

        /**
         * The style a widget of [type] (`PanelWidgetClass`) wears: the one [style] names, or its
         * class's `Default` when the widget names none. Null when neither exists, as for a panel
         * with no style, which then keeps its plain colour.
         */
        fun styleOf(type: String, style: String?): Style? {
            val styles = widgets[type.removeSuffix("Class").lowercase()] ?: return null
            return styles[(style?.takeIf { it.isNotBlank() } ?: "Default").lowercase()]
        }

        /** This sheet with [other]'s styles added where this one has none of the same name. */
        fun plus(other: Sheet): Sheet {
            val merged = LinkedHashMap<String, Map<String, Style>>()
            for (widget in widgets.keys + other.widgets.keys) {
                merged[widget] = other.widgets[widget].orEmpty() + widgets[widget].orEmpty()
            }
            return Sheet(merged)
        }

        companion object {
            val EMPTY = Sheet(emptyMap())
        }
    }

    private val TAG = Regex("""<\s*(/?)\s*(Widget|Style|State|Item)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""(\w+)\s*=\s*"([^"]*)"""")

    fun parse(text: String): Sheet {
        val widgets = LinkedHashMap<String, LinkedHashMap<String, Style>>()
        var widget: LinkedHashMap<String, Style>? = null
        var styleName: String? = null
        var imageSet = ""
        var color: Int? = null
        var states = LinkedHashMap<String, Map<Piece, String>>()
        var state: String? = null
        var pieces = LinkedHashMap<Piece, String>()

        fun closeState() {
            state?.let { states[it] = pieces }
            state = null
            pieces = LinkedHashMap()
        }

        fun closeStyle() {
            closeState()
            val name = styleName
            if (name != null) widget?.putIfAbsent(name.lowercase(), Style(name, imageSet, states, color))
            styleName = null
            states = LinkedHashMap()
        }

        for (tag in TAG.findAll(text)) {
            val closing = tag.groupValues[1].isNotEmpty()
            val element = tag.groupValues[2].lowercase()
            val body = tag.groupValues[3]
            val attributes = ATTRIBUTE.findAll(body).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val selfClosing = body.trimEnd().endsWith("/")
            when (element) {
                "widget" -> {
                    closeStyle()
                    widget = if (closing) null else attributes["name"]?.let { widgets.getOrPut(it.lowercase()) { LinkedHashMap() } }
                }

                "style" -> {
                    closeStyle()
                    if (!closing) {
                        styleName = attributes["name"]
                        imageSet = attributes["imageset"].orEmpty()
                        color = attributes["color"]?.trim()?.toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL }?.toInt()
                        if (selfClosing) closeStyle()
                    }
                }

                "state" -> {
                    closeState()
                    if (!closing && !selfClosing) state = attributes["name"]?.lowercase()
                }

                "item" -> if (!closing && state != null) {
                    val piece = attributes["name"]?.let(Piece::of)
                    val image = attributes["image"]?.trim().orEmpty()
                    if (piece != null && image.isNotEmpty()) pieces[piece] = image
                }
            }
        }
        closeStyle()
        return Sheet(widgets)
    }

    /**
     * Where each piece of a frame goes in [box], given each picture's size in layout pixels. The
     * corners keep their size; the top and bottom stretch across, the sides down, the centre both.
     * The columns are as wide as their widest picture, the rows as tall as their tallest. A box too
     * small for its corners shrinks them in proportion, as nothing can be negative.
     */
    fun layout(box: Rect, sizes: Map<Piece, Pair<Double, Double>>): Map<Piece, Rect> {
        fun width(vararg pieces: Piece) = pieces.maxOf { sizes[it]?.first ?: 0.0 }
        fun height(vararg pieces: Piece) = pieces.maxOf { sizes[it]?.second ?: 0.0 }
        var left = width(Piece.LEFT_TOP, Piece.LEFT, Piece.LEFT_BOTTOM)
        var right = width(Piece.RIGHT_TOP, Piece.RIGHT, Piece.RIGHT_BOTTOM)
        var top = height(Piece.LEFT_TOP, Piece.TOP, Piece.RIGHT_TOP)
        var bottom = height(Piece.LEFT_BOTTOM, Piece.BOTTOM, Piece.RIGHT_BOTTOM)
        if (left + right > box.w && left + right > 0) {
            val k = box.w / (left + right)
            left *= k
            right *= k
        }
        if (top + bottom > box.h && top + bottom > 0) {
            val k = box.h / (top + bottom)
            top *= k
            bottom *= k
        }
        val innerW = box.w - left - right
        val innerH = box.h - top - bottom
        val x0 = box.x
        val x1 = box.x + left
        val x2 = box.x + box.w - right
        val y0 = box.y
        val y1 = box.y + top
        val y2 = box.y + box.h - bottom
        val all = mapOf(
            Piece.LEFT_TOP to Rect(x0, y0, left, top),
            Piece.TOP to Rect(x1, y0, innerW, top),
            Piece.RIGHT_TOP to Rect(x2, y0, right, top),
            Piece.LEFT to Rect(x0, y1, left, innerH),
            Piece.CENTER to Rect(x1, y1, innerW, innerH),
            Piece.RIGHT to Rect(x2, y1, right, innerH),
            Piece.LEFT_BOTTOM to Rect(x0, y2, left, bottom),
            Piece.BOTTOM to Rect(x1, y2, innerW, bottom),
            Piece.RIGHT_BOTTOM to Rect(x2, y2, right, bottom),
        )
        return all.filterKeys { it in sizes }.filterValues { it.w > 0 && it.h > 0 }
    }
}
