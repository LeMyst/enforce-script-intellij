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

    /** The overlap of the two, empty (zero-sized) when they do not meet. */
    fun intersect(other: Rect): Rect {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + w, other.x + other.w)
        val bottom = minOf(y + h, other.y + other.h)
        return Rect(left, top, maxOf(0.0, right - left), maxOf(0.0, bottom - top))
    }
}

/**
 * A widget and where it lands, in screen pixels, children after their parent (paint order).
 * [clip] is the area its ancestors let it draw in: the screen, narrowed by every ancestor that
 * clips its children.
 */
class Placed(val spec: WidgetSpec, val rect: Rect, val depth: Int, val hidden: Boolean, val clip: Rect)

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
 * - `clipchildren` defaults to 1, as Workbench shows it: a widget's children are cut off at its
 *   edges. Vanilla writes `clipchildren 0` thousands of times to turn it off.
 *
 * - `fixaspect` shapes the widget's box, not its picture ([fixedAspect]): vanilla sets it on a
 *   `FrameWidgetClass` too, which has no picture. An image is stretched into the box.
 *
 * Everything else the engine does (`Padding` and `Margin` on wrap spacers, `Size To Content` on other
 * widgets, scaling) is left out: the preview shows the arrangement, not a pixel-exact frame.
 */
object LayoutGeometry {

    fun layout(roots: List<WidgetSpec>, width: Double, height: Double): List<Placed> {
        val out = ArrayList<Placed>()
        val screen = Rect(0.0, 0.0, width, height)
        for (root in roots) place(root, screen, null, 0, false, screen, out)
        byPriority(out, 0, 0)
        return out
    }

    /**
     * Puts the subtrees of the widgets at [depth] from [from] on in drawing order: by `priority`,
     * lowest first, and in declaration order among equals. `dialog_input_password` declares its
     * full-screen `Background` picture after the dialog window and shows the window on top, because
     * the window says `priority 1`. Only the drawing order changes, never where anything goes.
     */
    private fun byPriority(out: MutableList<Placed>, from: Int, depth: Int) {
        val subtrees = ArrayList<List<Placed>>()
        var i = from
        while (i < out.size) {
            var end = i + 1
            while (end < out.size && out[end].depth > depth) end++
            subtrees += out.subList(i, end).toList()
            i = end
        }
        if (subtrees.size > 1 && subtrees.any { priority(it[0].spec) != priority(subtrees[0][0].spec) }) {
            val sorted = subtrees.sortedBy { priority(it[0].spec) }.flatten()
            for (k in sorted.indices) out[from + k] = sorted[k]
        }
    }

    private fun priority(spec: WidgetSpec) = spec.number("priority", 0, 0.0)

    /** Where [spec] lands inside [parent], by its own position, size and alignment. */
    fun rectOf(spec: WidgetSpec, parent: Rect): Rect {
        val (w, h) = fixedAspect(spec, parent)
            ?: (length(spec, "size", 0, "hexactsize", parent.w, default = 1.0) to length(spec, "size", 1, "vexactsize", parent.h, default = 1.0))
        return aligned(spec, parent, w, h)
    }

    /**
     * The size a proportional `size` takes under `fixaspect`, or null when it does not apply. The two
     * numbers then give only a ratio: `size 1.6 0.9` and `size 0.16 0.09` are both 16:9, and both are
     * vanilla's full-screen backgrounds (`day_z_ingamemenu`'s vignette, `loading.layout`'s
     * `ImageBackground`).
     * - `outside`: the smallest box of that ratio covering the parent. Checked in Workbench at
     *   1920 x 1080: `size 1 1` is 1920 x 1920, overflowing above and below; `size 1 0.5` is
     *   2160 x 1080.
     * - `inside`: the largest box of that ratio inside the parent (`BackPanelWidget`, 1.6 x 0.9, a
     *   frame laid over the whole inspect screen).
     * - `fixwidth` / `fixheight`: that side as usual, the other from the ratio. Not checked in
     *   Workbench; vanilla nearly always writes these on pixel sizes, where they change nothing.
     *
     * A size in pixels already has its ratio, so it is left alone, as is a mixed one.
     */
    private fun fixedAspect(spec: WidgetSpec, parent: Rect): Pair<Double, Double>? {
        val mode = spec.value("fixaspect")?.lowercase() ?: return null
        if (spec.flag("hexactsize", false) || spec.flag("vexactsize", false)) return null
        val fw = spec.number("size", 0, 1.0)
        val fh = spec.number("size", 1, 1.0)
        if (fw <= 0 || fh <= 0 || parent.w <= 0 || parent.h <= 0) return null
        val ratio = fw / fh
        val wider = parent.w / parent.h > ratio
        // 1.6 / 0.9 is not exactly 16:9 in doubles; the parent's own size, not 1920.0000000000002.
        if (mode == "outside" || mode == "inside") {
            if (Math.abs(parent.w / parent.h - ratio) < 1e-9 * ratio) return parent.w to parent.h
        }
        return when (mode) {
            "outside" -> if (wider) parent.w to parent.w / ratio else parent.h * ratio to parent.h
            "inside" -> if (wider) parent.h * ratio to parent.h else parent.w to parent.w / ratio
            "fixwidth" -> (fw * parent.w).let { it to it / ratio }
            "fixheight" -> (fh * parent.h).let { it * ratio to it }
            else -> null
        }
    }

    /** [spec] given the size [w] × [h], placed by its own position and alignment inside [parent]. */
    private fun aligned(spec: WidgetSpec, parent: Rect, w: Double, h: Double): Rect {
        val dx = length(spec, "position", 0, "hexactpos", parent.w, default = 0.0)
        val dy = length(spec, "position", 1, "vexactpos", parent.h, default = 0.0)
        // Only the `_ref` values align: `day_z_server_browser_new`'s `ServerCountText` says
        // `valign bottom`, the one such value in vanilla, and Workbench keeps it at the top.
        val x = when (spec.value("halign")) {
            "center_ref" -> parent.x + (parent.w - w) / 2 + dx
            "right_ref" -> parent.x + parent.w - w - dx
            else -> parent.x + dx
        }
        val y = when (spec.value("valign")) {
            "center_ref" -> parent.y + (parent.h - h) / 2 + dy
            "bottom_ref" -> parent.y + parent.h - h - dy
            else -> parent.y + dy
        }
        return Rect(x, y, w, h)
    }

    private fun length(spec: WidgetSpec, key: String, index: Int, exactKey: String, parentLength: Double, default: Double): Double {
        val value = spec.number(key, index, default)
        return if (spec.flag(exactKey, false)) value else value * parentLength
    }

    private fun place(spec: WidgetSpec, parent: Rect, slot: Rect?, depth: Int, hiddenAbove: Boolean, clip: Rect, out: MutableList<Placed>) {
        val declared = slot ?: rectOf(spec, parent)
        val rect = sizedToContent(spec, parent, declared, slot != null)
        val hidden = hiddenAbove || !spec.visible
        out += Placed(spec, rect, depth, hidden, clip)
        val children = out.size
        val inner = if (spec.flag("clipchildren", true)) clip.intersect(rect) else clip
        when (spec.type) {
            "GridSpacerWidgetClass" -> grid(spec, rect, declared, depth, hidden, inner, out)
            "WrapSpacerWidgetClass" -> wrap(spec, rect, depth, hidden, inner, out)
            else -> spec.children.forEach { place(it, rect, null, depth + 1, hidden, inner, out) }
        }
        byPriority(out, children, depth + 1)
    }

    /** [declared] is the spacer's size before *Size To Content*, which its proportional children are measured against. */
    private fun grid(spec: WidgetSpec, rect: Rect, declared: Rect, depth: Int, hidden: Boolean, clip: Rect, out: MutableList<Placed>) {
        val g = Grid.of(spec, declared)
        var i = 0
        for (child in spec.children) {
            val cell = g.cell(rect, i % g.columns.size, i / g.columns.size)
            place(child, cell, cell, depth + 1, hidden, clip, out)
            if (child.visible) i++
        }
    }

    /**
     * A grid spacer's column widths and row heights. `Columns` x `Rows` cells, with more rows when
     * the visible children need them (`day_z_respawn_dialogue`'s tooltip declares `Rows 2` and
     * stacks three), `Padding` inside the spacer's edges and `Margin` round every cell, the outer
     * edges included: the tooltip body's `Margin 15` insets its text by 15 pixels in Workbench. An axis with `Size To Content` takes each column (row) as wide (tall) as its widest
     * (tallest) child, as Workbench draws `day_z_map`'s legend: a 145-pixel compass beside the
     * coordinates, not each in half of the 600 pixels the legend declares. A proportional child
     * counts with its share of the size the spacer declares: `day_z_connection_dialogue`'s red
     * separator, `0.015` high, is a few pixels in Workbench, not nothing. Without the flag, the room
     * is shared equally.
     */
    private class Grid(val columns: DoubleArray, val rows: DoubleArray, val padding: Double, val margin: Double) {
        val width: Double get() = columns.sum() + margin * (columns.size + 1) + 2 * padding
        val height: Double get() = rows.sum() + margin * (rows.size + 1) + 2 * padding

        fun cell(rect: Rect, column: Int, row: Int): Rect {
            val c = column.coerceAtMost(columns.size - 1)
            val r = row.coerceAtMost(rows.size - 1)
            var x = rect.x + padding + margin
            for (k in 0 until c) x += columns[k] + margin
            var y = rect.y + padding + margin
            for (k in 0 until r) y += rows[k] + margin
            return Rect(x, y, columns[c], if (row < rows.size) rows[r] else 0.0)
        }

        companion object {
            fun of(spec: WidgetSpec, rect: Rect): Grid {
                val padding = spec.number("Padding", 0, 0.0)
                val margin = spec.number("Margin", 0, 0.0)
                val shown = spec.children.filter { it.visible }
                // `Rows` alone gives the columns: `day_z_digital_lock`'s keypad says `Rows 4` and
                // shows its twelve keys three to a row.
                val declaredRows = spec.number("Rows", 0, 0.0).toInt()
                val columnCount = if (spec.value("Columns") == null && declaredRows > 0) {
                    maxOf(1, Math.ceil(shown.size / declaredRows.toDouble()).toInt())
                } else {
                    spec.number("Columns", 0, 1.0).toInt().coerceAtLeast(1)
                }
                val needed = Math.ceil(shown.size / columnCount.toDouble()).toInt()
                val rowCount = maxOf(spec.number("Rows", 0, needed.toDouble()).toInt(), needed, 1)
                val shared = { length: Double, count: Int -> DoubleArray(count) { ((length - 2 * padding - margin * (count + 1)) / count).coerceAtLeast(0.0) } }
                val columns = shared(rect.w, columnCount)
                val rows = shared(rect.h, rowCount)
                val fitW = spec.flag("Size To Content H", false)
                val fitH = spec.flag("Size To Content V", false)
                if (fitW || fitH) {
                    if (fitW) columns.fill(0.0)
                    if (fitH) rows.fill(0.0)
                    shown.forEachIndexed { i, child ->
                        val c = i % columnCount
                        val r = i / columnCount
                        val size = sizedToContent(child, rect, rectOf(child, rect), inSlot = true)
                        if (fitW && c < columnCount) columns[c] = maxOf(columns[c], size.w)
                        if (fitH && r < rowCount) rows[r] = maxOf(rows[r], size.h)
                    }
                }
                return Grid(columns, rows, padding, margin)
            }

        }
    }

    private fun wrap(spec: WidgetSpec, rect: Rect, depth: Int, hidden: Boolean, clip: Rect, out: MutableList<Placed>) {
        for ((child, slot) in flow(spec, rect)) place(child, rect, slot, depth + 1, hidden, clip, out)
    }

    /** One line of a wrap spacer: its children with their sizes, and the line's extent. */
    private class Line(val items: MutableList<Pair<WidgetSpec, Rect>> = ArrayList(), var w: Double = 0.0, var h: Double = 0.0)

    /**
     * A wrap spacer's children cut into lines, left to right, wrapping at [rect]'s width. A hidden
     * child goes where it would be (at the start of the next line if it would wrap) without taking
     * any room. The rects hold sizes only; [flow] positions them.
     */
    private fun lines(spec: WidgetSpec, rect: Rect): List<Line> {
        val lines = arrayListOf(Line())
        // Hidden children that would wrap, waiting for the line they would start.
        val waiting = ArrayList<Pair<WidgetSpec, Rect>>()
        for (child in spec.children) {
            // Measured after Size To Content: a nested spacer takes the room it grows to, not the
            // room it declares (day_z_ingamemenu's `bottom` declares 84 and holds 180).
            val size = sizedToContent(child, rect, rectOf(child, rect), inSlot = true)
            var line = lines.last()
            val wraps = line.w > 0 && line.w + size.w > rect.w + 0.5
            if (!child.visible) {
                if (wraps) waiting += child to Rect(0.0, 0.0, size.w, size.h) else line.items += child to Rect(line.w, 0.0, size.w, size.h)
                continue
            }
            // Only visible children decide the lines: a hidden one never moves the others.
            if (wraps) {
                line = Line(ArrayList(waiting))
                lines += line
                waiting.clear()
            }
            line.items += child to Rect(line.w, 0.0, size.w, size.h)
            line.w += size.w
            line.h = maxOf(line.h, size.h)
        }
        if (waiting.isNotEmpty()) lines += Line(waiting)
        return lines.filter { it.items.isNotEmpty() }
    }

    /**
     * A wrap spacer's children with their slots. `content_halign` (`left`, `center`, `right`) places
     * each line across the spacer. `content_valign` places the block of lines: `top` (the default)
     * and `center` in their order, but `bottom` stacks them from the bottom edge upwards, the first
     * line lowest. That last one is how Workbench draws `day_z_ingamemenu`: its `play_panel_root`
     * holds `bottom` (Continue, Respawn), the red separator, then `top` (Exit, Feedback, Options),
     * and shows `top` above the separator above `bottom`.
     */
    private fun flow(spec: WidgetSpec, rect: Rect): List<Pair<WidgetSpec, Rect>> {
        val lines = lines(spec, rect)
        val total = lines.sumOf { it.h }
        val across = when (spec.value("content_halign")?.removeSuffix("_ref")) {
            "center" -> 0.5
            "right" -> 1.0
            else -> 0.0
        }
        val valign = spec.value("content_valign")?.removeSuffix("_ref")
        val out = ArrayList<Pair<WidgetSpec, Rect>>(spec.children.size)
        var y = when (valign) {
            "center" -> rect.y + (rect.h - total) / 2
            "bottom" -> rect.y + rect.h
            else -> rect.y
        }
        for (line in lines) {
            if (valign == "bottom") y -= line.h
            val x = rect.x + (rect.w - line.w) * across
            for ((child, r) in line.items) out += child to Rect(x + r.x, y, r.w, r.h)
            if (valign != "bottom") y += line.h
        }
        return out
    }

    /**
     * [rect] shrunk or grown to what a wrap or grid spacer holds, on each axis its `Size To Content` flag
     * asks for, then aligned again with that size. A spacer placed in another spacer's slot keeps
     * the slot's corner: its position is the outer spacer's to choose.
     */
    private fun sizedToContent(spec: WidgetSpec, parent: Rect, rect: Rect, inSlot: Boolean): Rect {
        if (sizedToText(spec, horizontal = true) || sizedToText(spec, horizontal = false)) {
            val (w, h) = textExtent(spec, rect)
            return if (inSlot) Rect(rect.x, rect.y, w, h) else aligned(spec, parent, w, h)
        }
        if (spec.type != "WrapSpacerWidgetClass" && spec.type != "GridSpacerWidgetClass") return rect
        val fitW = spec.flag("Size To Content H", false)
        val fitH = spec.flag("Size To Content V", false)
        if (!fitW && !fitH) return rect
        if (spec.type == "GridSpacerWidgetClass") {
            val g = Grid.of(spec, rect)
            val w = if (fitW) g.width else rect.w
            val h = if (fitH) g.height else rect.h
            return if (inSlot) Rect(rect.x, rect.y, w, h) else aligned(spec, parent, w, h)
        }
        val lines = lines(spec, rect).filter { line -> line.items.any { it.first.visible } }
        if (lines.isEmpty()) return rect
        val w = if (fitW) lines.maxOf { it.w } else rect.w
        val h = if (fitH) lines.sumOf { it.h } else rect.h
        return if (inSlot) Rect(rect.x, rect.y, w, h) else aligned(spec, parent, w, h)
    }

    private val TEXT_TYPES = setOf("TextWidgetClass", "MultilineTextWidgetClass", "RichTextWidgetClass")

    /** Whether a text widget sizes that side to its text (`"size to text h"` / `"size to text v"`); never the width of wrapped text. */
    private fun sizedToText(spec: WidgetSpec, horizontal: Boolean): Boolean =
        spec.type in TEXT_TYPES && !spec.text.isNullOrEmpty() &&
            if (horizontal) spec.flag("size to text h", false) && !spec.flag("wrap", false) else spec.flag("size to text v", false)

    /**
     * Roughly what a text widget's text measures, for `"size to text"`: lines 1.4 times the widget's
     * text size (`day_z_respawn_dialogue`'s 16-pixel details text gets a box about 22 high in
     * Workbench), letters half as wide on average. Layout has no fonts, and the text may yet be translated,
     * so this only needs to be about right: `day_z_respawn_dialogue`'s tooltip body is declared one
     * pixel tall and grows to its one line.
     */
    private fun textExtent(spec: WidgetSpec, rect: Rect): Pair<Double, Double> {
        val size = LayoutRenderer.textSize(spec, rect)
        val text = spec.text.orEmpty()
        val longest = text.split('\n').maxOf { it.length } * size * 0.5
        val w = if (sizedToText(spec, horizontal = true)) longest else rect.w
        val lines = if (spec.flag("wrap", false) && w > 0) {
            text.split('\n').sumOf { maxOf(1, Math.ceil(it.length * size * 0.5 / w).toInt()) }
        } else {
            text.count { it == '\n' } + 1
        }
        val h = if (sizedToText(spec, horizontal = false)) lines * size * 1.4 else rect.h
        return w to h
    }

    /** The innermost widget whose declaration holds [offset], for going from the text to the preview. */
    fun at(placed: List<Placed>, offset: Int): Placed? =
        placed.filter { offset >= it.spec.offset && offset < it.spec.endOffset }.maxByOrNull { it.depth }

    /**
     * Every widget under a point, smallest first, for going from the preview to the text; among
     * equal sizes, the one drawn last. Smallest rather than topmost: `day_z_hud_cars`'s shadow,
     * 1.2 times the panel and declared after the fuel and temperature dials, covers them, so the
     * topmost widget under a needle was always the shadow. A widget is only under the point where
     * its ancestors let it show.
     */
    fun hits(placed: List<Placed>, px: Double, py: Double, includeHidden: Boolean): List<Placed> =
        placed.withIndex()
            .filter { (_, p) -> (includeHidden || !p.hidden) && p.rect.contains(px, py) && p.clip.contains(px, py) }
            .sortedWith(compareBy<IndexedValue<Placed>>({ it.value.rect.w * it.value.rect.h }, { -it.index }))
            .map { it.value }

    /** The smallest widget under a point ([hits]). */
    fun hit(placed: List<Placed>, px: Double, py: Double, includeHidden: Boolean): Placed? =
        hits(placed, px, py, includeHidden).firstOrNull()
}
