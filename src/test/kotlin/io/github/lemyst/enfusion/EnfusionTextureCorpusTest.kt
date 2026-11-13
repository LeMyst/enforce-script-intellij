package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.texture.format.TextureFormatException
import io.github.lemyst.enfusion.texture.format.TextureReader
import io.github.lemyst.enfusion.texture.format.UnsupportedTextureException
import junit.framework.TestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Every `.edds` and `.paa` under the texture roots either decodes or says its format is not
 * supported. Nothing may be reported as corrupt: the formats are documented only by community
 * tools, so a real file failing here means the decoder is wrong, not the file.
 *
 * `P:\DZ` holds about 40,000 `.paa` files, so each root is sampled evenly down to
 * `enfusionCorpusLimit` files (2,000 by default). The breakdown by format it prints is the evidence
 * for which formats are worth decoding.
 *
 * Skips when no root is present.
 */
class EnfusionTextureCorpusTest : TestCase() {

    fun testTextureCorpusDecodes() {
        val roots = System.getProperty("enfusion.texture.roots").orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && File(it).isDirectory }
        if (roots.isEmpty()) {
            log("no texture roots present, skipping")
            return
        }
        val limit = System.getProperty("enfusion.corpus.limit")?.toIntOrNull() ?: 2000

        val paths = roots.flatMap { root -> sample(texturesUnder(Paths.get(root)), limit) }
        log("decoding ${paths.size} textures from ${roots.joinToString(", ")}")

        val formats = sortedMapOf<String, Int>()
        val unsupported = sortedMapOf<String, Int>()
        val failures = mutableListOf<String>()
        var decodedMips = 0
        val t0 = System.nanoTime()

        for (path in paths) {
            try {
                val texture = TextureReader.read(Files.readAllBytes(path))
                val key = "${texture.container} ${texture.formatName} (${texture.compression})"
                formats.merge(key, 1, Int::plus)
                if (!texture.isSupported) {
                    unsupported.merge(texture.formatName, 1, Int::plus)
                    continue
                }
                // Every mip of anything up to 2048², which covers LZ4 and LZO on long streams; for
                // larger ones, the top mip the viewer opens on and the smallest.
                val levels = if (texture.width.toLong() * texture.height <= 2048L * 2048L) {
                    texture.mips.indices.toList()
                } else {
                    listOf(texture.levelWithin(4096L * 4096L), texture.mips.lastIndex)
                }
                for (level in levels.distinct()) {
                    val image = texture.decode(level)
                    assertEquals(texture.mips[level].width, image.width)
                    decodedMips++
                }
            } catch (e: UnsupportedTextureException) {
                unsupported.merge(e.format, 1, Int::plus)
            } catch (e: TextureFormatException) {
                failures += "$path  ${e.message}"
            }
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        log("decoded $decodedMips mips of ${paths.size} textures in ${ms}ms")
        formats.forEach { (format, count) -> log("  $count  $format") }
        if (unsupported.isNotEmpty()) log("unsupported: $unsupported")

        if (failures.isNotEmpty()) {
            log("FAILURES (${failures.size}), first 40:")
            failures.take(40).forEach { log("  $it") }
            fail("${failures.size} texture corpus failures - see log above")
        }
    }

    private fun texturesUnder(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream.filter { path ->
                val name = path.toString()
                !name.replace('\\', '/').contains("/.claude/") &&
                    (name.endsWith(".edds", ignoreCase = true) || name.endsWith(".paa", ignoreCase = true))
            }.toList()
        }.sorted()

    /** Every n-th file, so a sample spreads across the whole tree rather than its first folder. */
    private fun sample(paths: List<Path>, limit: Int): List<Path> {
        if (paths.size <= limit) return paths
        val step = paths.size.toDouble() / limit
        return (0 until limit).map { paths[(it * step).toInt()] }
    }

    private fun log(message: String) {
        println("[texture] $message")
        System.out.flush()
    }
}
