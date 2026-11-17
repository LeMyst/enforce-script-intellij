package io.github.lemyst.enfusion.texture.format

/**
 * Places sprites on an atlas, MaxRects with the best short side fit: each sprite goes into the
 * free rectangle it fills most tightly. The atlas is the smallest power-of-two size that holds them
 * all, trying sizes by area, square first. No IntelliJ API.
 *
 * Deterministic: sprites are placed largest first, ties broken by name, and every choice between
 * equal candidates goes to the first, so the same sprites always land in the same places.
 *
 * [padding] pixels are kept on every side of each sprite, inside the atlas, so that filtering and
 * the smaller mips do not blend neighbours: no sprite's picture touches another's or the edge.
 */
object AtlasPacker {

    class Sprite(val name: String, val width: Int, val height: Int)

    /** Where [sprite]'s picture goes: its top-left corner, padding excluded. */
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
        val widest = order.maxOf { it.width + 2 * padding }
        val tallest = order.maxOf { it.height + 2 * padding }
        if (widest > maxSide || tallest > maxSide) {
            val big = order.first { it.width + 2 * padding > maxSide || it.height + 2 * padding > maxSide }
            throw DoesNotFit("${big.name} (${big.width} × ${big.height}) is larger than $maxSide pixels with its padding")
        }
        val area = order.sumOf { (it.width + 2L * padding) * (it.height + 2L * padding) }
        for ((w, h) in sizes(maxSide)) {
            if (w < widest || h < tallest || w.toLong() * h < area) continue
            place(order, padding, w, h)?.let { return Atlas(w, h, it) }
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
            val w = sprite.width + 2 * padding
            val h = sprite.height + 2 * padding
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
            placed += Placement(sprite, at.x + padding, at.y + padding)
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
