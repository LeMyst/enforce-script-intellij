package io.github.lemyst.enfusion.texture.format

/**
 * Places sprites on an atlas, MaxRects with the best short side fit: each sprite goes into the
 * free rectangle it fills most tightly. The atlas is the smallest power-of-two size that holds them
 * all, trying sizes by area, square first. No IntelliJ API.
 *
 * Deterministic: sprites are placed largest first, ties broken by name, and every choice between
 * equal candidates goes to the first, so the same sprites always land in the same places.
 *
 * [padding] is the gap between two sprites, not a border along the atlas edge, which sprites may
 * touch: DayZ atlases are packed tight, vanilla's images mostly 0 or 1 pixel apart (657 and 505
 * of them) and against the edge, mods' almost all 0. Each sprite is given [padding] more on its
 * right and bottom, in a bin [padding] larger than the atlas, which leaves exactly that gap
 * between neighbours and none at the edge.
 */
object AtlasPacker {

    class Sprite(val name: String, val width: Int, val height: Int)

    /** Where [sprite]'s picture goes: its top-left corner. */
    class Placement(val sprite: Sprite, val x: Int, val y: Int)

    class Atlas(val width: Int, val height: Int, val placements: List<Placement>)

    class DoesNotFit(message: String) : Exception(message)

    /** The atlas for [sprites], at most [maxSide] on a side. */
    fun pack(sprites: List<Sprite>, padding: Int, maxSide: Int): Atlas {
        require(padding >= 0 && maxSide > 0)
        if (sprites.isEmpty()) return Atlas(1, 1, emptyList())
        val order = sprites.sortedWith(
            compareByDescending<Sprite> { maxOf(it.width, it.height) }
                .thenByDescending { it.width * it.height }
                .thenBy { it.name },
        )
        val widest = order.maxOf { it.width }
        val tallest = order.maxOf { it.height }
        if (widest > maxSide || tallest > maxSide) {
            val big = order.first { it.width > maxSide || it.height > maxSide }
            throw DoesNotFit("${big.name} (${big.width} × ${big.height}) is larger than $maxSide pixels")
        }
        val area = order.sumOf { (it.width + padding.toLong()) * (it.height + padding.toLong()) }
        for ((w, h) in sizes(maxSide)) {
            if (w < widest || h < tallest || (w + padding.toLong()) * (h + padding) < area) continue
            place(order, padding, w + padding, h + padding)?.let { return Atlas(w, h, it) }
        }
        throw DoesNotFit("the ${sprites.size} images do not fit in $maxSide × $maxSide")
    }

    /** Power-of-two sizes up to [maxSide], smallest area first, square before wide before tall. */
    private fun sizes(maxSide: Int): List<Pair<Int, Int>> {
        val sides = generateSequence(1) { it * 2 }.takeWhile { it <= maxSide }.toList()
        return sides.flatMap { w -> sides.map { h -> w to h } }
            .filter { (w, h) -> maxOf(w, h) <= 2 * minOf(w, h) }
            .sortedWith(compareBy<Pair<Int, Int>> { it.first.toLong() * it.second }.thenBy { if (it.first == it.second) 0 else if (it.first > it.second) 1 else 2 })
    }

    private class Free(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(o: Free) = o.x >= x && o.y >= y && o.x + o.w <= x + w && o.y + o.h <= y + h
        fun intersects(o: Free) = o.x < x + w && o.x + o.w > x && o.y < y + h && o.y + o.h > y
    }

    private fun place(order: List<Sprite>, padding: Int, width: Int, height: Int): List<Placement>? {
        var free = listOf(Free(0, 0, width, height))
        val placed = ArrayList<Placement>(order.size)
        for (sprite in order) {
            val w = sprite.width + padding
            val h = sprite.height + padding
            var best: Free? = null
            var bestShort = Int.MAX_VALUE
            var bestLong = Int.MAX_VALUE
            for (f in free) {
                if (f.w < w || f.h < h) continue
                val short = minOf(f.w - w, f.h - h)
                val long = maxOf(f.w - w, f.h - h)
                if (short < bestShort || (short == bestShort && long < bestLong)) {
                    best = f
                    bestShort = short
                    bestLong = long
                }
            }
            val at = best ?: return null
            val used = Free(at.x, at.y, w, h)
            placed += Placement(sprite, at.x, at.y)
            free = split(free, used)
        }
        return placed
    }

    /** The free rectangles once [used] is taken: each one it cuts is split into up to four, and those inside another dropped. */
    private fun split(free: List<Free>, used: Free): List<Free> {
        val next = ArrayList<Free>()
        for (f in free) {
            if (!f.intersects(used)) {
                next += f
                continue
            }
            if (used.x > f.x) next += Free(f.x, f.y, used.x - f.x, f.h)
            if (used.x + used.w < f.x + f.w) next += Free(used.x + used.w, f.y, f.x + f.w - used.x - used.w, f.h)
            if (used.y > f.y) next += Free(f.x, f.y, f.w, used.y - f.y)
            if (used.y + used.h < f.y + f.h) next += Free(f.x, used.y + used.h, f.w, f.y + f.h - used.y - used.h)
        }
        return next.filterIndexed { i, a -> next.withIndex().none { (j, b) -> j != i && b.contains(a) && (!a.contains(b) || j < i) } }
    }
}
