package io.github.lemyst.enfusion.objfmt.preview

/**
 * One widget of a `.layout`, reduced to what the preview draws. Built from PSI by [LayoutSpecs];
 * this file uses no IntelliJ API, so the layout maths is tested on hand-written trees.
 */
class WidgetSpec(
    /** `PanelWidgetClass`, `TextWidgetClass`, ... */
    val type: String,
    val name: String?,
    /** Raw property values by lower-case key, quotes stripped: `"size" -> ["0.5", "32"]`. */
    val properties: Map<String, List<String>>,
    val children: List<WidgetSpec>,
    /** Where the widget's declaration starts and ends in the file, to go between preview and text. */
    val offset: Int = -1,
    val endOffset: Int = -1,
) {
    fun values(key: String): List<String> = properties[key.lowercase()].orEmpty()
    fun value(key: String): String? = values(key).firstOrNull()
    fun number(key: String, index: Int, default: Double): Double = values(key).getOrNull(index)?.toDoubleOrNull() ?: default
    fun flag(key: String, default: Boolean): Boolean = value(key)?.let { it != "0" } ?: default

    val visible: Boolean get() = flag("visible", true)

    /** The text a text-like widget shows, if any. */
    val text: String? get() = value("text")

    /** `color r g b a`, each 0 to 1, as ARGB; null when not set. */
    val color: Int?
        get() {
            val c = values("color")
            if (c.size < 3) return null
            fun channel(i: Int, default: Double) = ((c.getOrNull(i)?.toDoubleOrNull() ?: default).coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            return (channel(3, 1.0) shl 24) or (channel(0, 1.0) shl 16) or (channel(1, 1.0) shl 8) or channel(2, 1.0)
        }
}

data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun contains(px: Double, py: Double): Boolean = px >= x && py >= y && px < x + w && py < y + h
}

/** A widget and where it lands, in screen pixels, children after their parent (paint order). */
class Placed(val spec: WidgetSpec, val rect: Rect, val depth: Int, val hidden: Boolean)

/**
 * Where each widget of a layout lands on a screen of a given size.
 *
 * The rules, as the vanilla layouts under `P:\gui\layouts` use them:
 * - `size w h` and `position x y` are pixels when `hexactsize` / `vexactsize` (and `hexactpos` /
 *   `vexactpos`) are 1, and fractions of the parent's size when 0. All four default to 0: of the
 *   widgets that leave one out, nearly all give a fraction.
 * - `halign` is `left_ref` (the default), `center_ref` or `right_ref`, and `valign` `top_ref`,
 *   `center_ref` or `bottom_ref`. The position is an offset from that edge or from the centre, and
 *   a right or bottom offset counts inwards. The rare forms without `_ref` mean the same here.
 * - A `GridSpacerWidgetClass` places its children itself, in `Rows` × `Columns` equal cells, row
 *   by row; a `WrapSpacerWidgetClass` flows them left to right and wraps. Their own positions are
 *   ignored there, as the engine ignores them. A `visible 0` child takes no slot: that is how
 *   vanilla drops a button from a menu (`day_z_ingamemenu`'s `restartbtn`). Shown with *Hidden
 *   widgets*, it is drawn where it would go, without moving the others.
 * - A wrap spacer with `"Size To Content H"` / `"Size To Content V"` takes the width / height of
 *   what it holds, then is aligned like any widget with that size. So a bottom-aligned menu column
 *   grows upwards, as `day_z_ingamemenu`'s `top` spacer (declared 48 high, holding 124) does.
 *
 * Everything else the engine does (`Padding` and `Margin` on spacers, `Size To Content` on other
 * widgets, scaling, `fixaspect`) is left out: the preview shows the arrangement, not a pixel-exact
 * frame.
 */
object LayoutGeometry {

    fun layout(roots: List<WidgetSpec>, width: Double, height: Double): List<Placed> {
        val out = ArrayList<Placed>()
        val screen = Rect(0.0, 0.0, width, height)
        for (root in roots) place(root, screen, null, 0, false, out)
        return out
    }

    /** Where [spec] lands inside [parent], by its own position, size and alignment. */
    fun rectOf(spec: WidgetSpec, parent: Rect): Rect =
        aligned(spec, parent, length(spec, "size", 0, "hexactsize", parent.w, default = 1.0), length(spec, "size", 1, "vexactsize", parent.h, default = 1.0))

    /** [spec] given the size [w] × [h], placed by its own position and alignment inside [parent]. */
    private fun aligned(spec: WidgetSpec, parent: Rect, w: Double, h: Double): Rect {
        val dx = length(spec, "position", 0, "hexactpos", parent.w, default = 0.0)
        val dy = length(spec, "position", 1, "vexactpos", parent.h, default = 0.0)
        val x = when (spec.value("halign")?.removeSuffix("_ref")) {
            "center" -> parent.x + (parent.w - w) / 2 + dx
            "right" -> parent.x + parent.w - w - dx
            else -> parent.x + dx
        }
        val y = when (spec.value("valign")?.removeSuffix("_ref")) {
            "center" -> parent.y + (parent.h - h) / 2 + dy
            "bottom" -> parent.y + parent.h - h - dy
            else -> parent.y + dy
        }
        return Rect(x, y, w, h)
    }

    private fun length(spec: WidgetSpec, key: String, index: Int, exactKey: String, parentLength: Double, default: Double): Double {
        val value = spec.number(key, index, default)
        return if (spec.flag(exactKey, false)) value else value * parentLength
    }

    private fun place(spec: WidgetSpec, parent: Rect, slot: Rect?, depth: Int, hiddenAbove: Boolean, out: MutableList<Placed>) {
        val rect = sizedToContent(spec, parent, slot ?: rectOf(spec, parent), slot != null)
        val hidden = hiddenAbove || !spec.visible
        out += Placed(spec, rect, depth, hidden)
        when (spec.type) {
            "GridSpacerWidgetClass" -> grid(spec, rect, depth, hidden, out)
            "WrapSpacerWidgetClass" -> wrap(spec, rect, depth, hidden, out)
            else -> spec.children.forEach { place(it, rect, null, depth + 1, hidden, out) }
        }
    }

    private fun grid(spec: WidgetSpec, rect: Rect, depth: Int, hidden: Boolean, out: MutableList<Placed>) {
        val columns = spec.number("Columns", 0, 1.0).toInt().coerceAtLeast(1)
        val shown = spec.children.count { it.visible }
        val rows = spec.number("Rows", 0, Math.ceil(shown / columns.toDouble())).toInt().coerceAtLeast(1)
        val cw = rect.w / columns
        val ch = rect.h / rows
        var i = 0
        for (child in spec.children) {
            val cell = Rect(rect.x + (i % columns) * cw, rect.y + (i / columns) * ch, cw, ch)
            place(child, cell, cell, depth + 1, hidden, out)
            if (child.visible) i++
        }
    }

    private fun wrap(spec: WidgetSpec, rect: Rect, depth: Int, hidden: Boolean, out: MutableList<Placed>) {
        for ((child, slot) in flow(spec, rect)) place(child, rect, slot, depth + 1, hidden, out)
    }

    /**
     * A wrap spacer's children with their slots, left to right, wrapping at [rect]'s width. A hidden
     * child gets the slot it would have, but the next child takes the same one.
     */
    private fun flow(spec: WidgetSpec, rect: Rect): List<Pair<WidgetSpec, Rect>> {
        val out = ArrayList<Pair<WidgetSpec, Rect>>(spec.children.size)
        var x = rect.x
        var y = rect.y
        var line = 0.0
        for (child in spec.children) {
            val size = rectOf(child, rect)
            val wraps = x > rect.x && x + size.w > rect.x + rect.w + 0.5
            val slot = if (wraps) Rect(rect.x, y + line, size.w, size.h) else Rect(x, y, size.w, size.h)
            out += child to slot
            if (!child.visible) continue
            if (wraps) {
                x = rect.x
                y += line
                line = 0.0
            }
            x += size.w
            line = maxOf(line, size.h)
        }
        return out
    }

    /**
     * [rect] shrunk or grown to what a wrap spacer holds, on each axis its `Size To Content` flag
     * asks for, then aligned again with that size. A spacer placed in another spacer's slot keeps
     * the slot's corner: its position is the outer spacer's to choose.
     */
    private fun sizedToContent(spec: WidgetSpec, parent: Rect, rect: Rect, inSlot: Boolean): Rect {
        if (spec.type != "WrapSpacerWidgetClass") return rect
        val fitW = spec.flag("Size To Content H", false)
        val fitH = spec.flag("Size To Content V", false)
        if (!fitW && !fitH) return rect
        val slots = flow(spec, rect).filter { it.first.visible }.map { it.second }
        if (slots.isEmpty()) return rect
        val w = if (fitW) slots.maxOf { it.x + it.w } - rect.x else rect.w
        val h = if (fitH) slots.maxOf { it.y + it.h } - rect.y else rect.h
        return if (inSlot) Rect(rect.x, rect.y, w, h) else aligned(spec, parent, w, h)
    }

    /** The innermost widget whose declaration holds [offset], for going from the text to the preview. */
    fun at(placed: List<Placed>, offset: Int): Placed? =
        placed.filter { offset >= it.spec.offset && offset < it.spec.endOffset }.maxByOrNull { it.depth }

    /** The innermost visible widget under a point, for going from the preview to the text. */
    fun hit(placed: List<Placed>, px: Double, py: Double, includeHidden: Boolean): Placed? =
        placed.lastOrNull { (includeHidden || !it.hidden) && it.rect.contains(px, py) }
}
