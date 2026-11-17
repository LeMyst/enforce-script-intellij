package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.preview.FontFiles
import io.github.lemyst.enfusion.objfmt.preview.LayoutGeometry
import io.github.lemyst.enfusion.objfmt.preview.LayoutRenderer
import io.github.lemyst.enfusion.objfmt.preview.LayoutSpecs
import io.github.lemyst.enfusion.objfmt.preview.WidgetStyles
import java.io.File

/**
 * Every `.layout` under the layout roots previews without an exception: read into specs, laid out
 * at 1920 × 1080, and drawn. Images are not resolved here (the roots are not indexed in a fixture),
 * so this covers the tree and the maths, not the pictures.
 *
 * Skips when no root is present, like the other corpus tests.
 */
class EnfusionLayoutPreviewCorpusTest : BasePlatformTestCase() {

    fun testEveryLayoutPreviews() {
        val roots = System.getProperty("enfusion.layout.roots").orEmpty()
            .split(';').map { it.trim() }.filter { it.isNotEmpty() && File(it).isDirectory }
        if (roots.isEmpty()) {
            println("[layout-preview] no layout roots present, skipping")
            return
        }
        val files = roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension.equals("layout", ignoreCase = true) }.toList() }
        val failures = ArrayList<String>()
        var widgets = 0
        val t0 = System.nanoTime()
        for (layout in files) {
            try {
                val psi = myFixture.configureByText("probe.layout", layout.readText().replace("\r\n", "\n"))
                val placed = LayoutGeometry.layout(LayoutSpecs.of(psi), 1920.0, 1080.0)
                widgets += placed.size
                LayoutRenderer.render(placed, 480, 270, showHidden = true, selected = null) { _, _ -> null }
                System.getenv("ENFUSION_LAYOUT_PNG")?.let { dir ->
                    if (layout.name in System.getenv("ENFUSION_LAYOUT_NAMES").orEmpty().split(',')) {
                        javax.imageio.ImageIO.write(
                            LayoutRenderer.render(placed, 1920, 1080, showHidden = false, selected = null) { _, _ -> null },
                            "png", File(dir, layout.nameWithoutExtension + ".png"),
                        )
                    }
                }
            } catch (e: Exception) {
                failures += "${layout.path}: $e"
            }
        }
        println("[layout-preview] ${files.size} layouts, $widgets widgets, ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue("failed on ${failures.size}: ${failures.take(5)}", failures.isEmpty())
    }

    /**
     * Every `.fnt` font under the layout roots in the two versions [FontFiles] reads (`FNT3`, `FNT5`)
     * reads, with the size its name ends with when it has one, and a line height no shorter than
     * that size for each distance-field font (`FNT5`). Other versions are counted, not failed:
     * vanilla has one, `system.fnt` (`FNT2`), which a widget then sizes as if it named no font.
     */
    fun testEveryFontReads() {
        val roots = System.getProperty("enfusion.layout.roots").orEmpty()
            .split(';').map { it.trim() }.filter { it.isNotEmpty() && File(it).isDirectory }
        val files = roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension.equals("fnt", ignoreCase = true) }.toList() }
        if (files.isEmpty()) {
            println("[fonts] no .fnt file under the layout roots, skipping")
            return
        }
        val problems = ArrayList<String>()
        var lines = 0
        var others = 0
        for (file in files) {
            val bytes = file.readBytes()
            val version = if (bytes.size >= 12) String(bytes, 8, 4, Charsets.US_ASCII) else ""
            if (version != "FNT3" && version != "FNT5") {
                others++
                continue
            }
            val metrics = FontFiles.read(bytes)
            if (metrics == null) {
                problems += "${file.name}: not read"
                continue
            }
            FontFiles.sizeInName(file.nameWithoutExtension)?.let { if (it != metrics.size) problems += "${file.name}: size ${metrics.size}" }
            val line = metrics.lineHeight
            if (version == "FNT5" && (line == null || line < metrics.size)) problems += "${file.name}: line $line for size ${metrics.size}"
            if (line != null) lines++
        }
        println("[fonts] ${files.size} fonts, $lines with a line height, $others in another version")
        assertTrue("${problems.size} problems: ${problems.take(5)}", problems.isEmpty())
    }

    /**
     * Every `.styles` file under the layout roots reads into styles, and each style a layout there
     * names is found for its widget's class, or is one the game itself lacks (printed, not failed:
     * Workbench then draws nothing, as the preview does).
     */
    fun testEveryStylesFileReads() {
        val roots = System.getProperty("enfusion.layout.roots").orEmpty()
            .split(';').map { it.trim() }.filter { it.isNotEmpty() && File(it).isDirectory }
        val files = roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension.equals("styles", ignoreCase = true) }.toList() }
        if (files.isEmpty()) {
            println("[styles] no .styles file under the layout roots, skipping")
            return
        }
        val sheet = files.map { WidgetStyles.parse(it.readText()) }.fold(WidgetStyles.Sheet.EMPTY) { a, b -> a.plus(b) }
        val styles = sheet.widgets.values.sumOf { it.size }
        val framed = sheet.widgets.values.sumOf { widget -> widget.values.count { it.frame().isNotEmpty() } }
        println("[styles] ${files.size} files, ${sheet.widgets.size} widget classes, $styles styles, $framed with a Normal frame")
        assertTrue("no style read from ${files.map { it.name }}", styles > 0)

        // The styles the layouts name, against what the files declare.
        val used = HashMap<Pair<String, String>, Int>()
        val styleLine = Regex("""^\s*style\s+"?([A-Za-z0-9_]+)"?""", RegexOption.MULTILINE)
        val classLine = Regex("""^\s*(\w+WidgetClass)\b""", RegexOption.MULTILINE)
        for (layout in roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension.equals("layout", ignoreCase = true) }.toList() }) {
            val text = layout.readText()
            for (match in styleLine.findAll(text)) {
                val type = classLine.findAll(text.substring(0, match.range.first)).lastOrNull()?.groupValues?.get(1) ?: continue
                used.merge(type to match.groupValues[1], 1, Int::plus)
            }
        }
        val missing = used.filterKeys { (type, style) -> type.removeSuffix("Class").lowercase() in sheet.widgets && sheet.styleOf(type, style) == null }
        val found = used.size - missing.size
        println("[styles] layouts name ${used.size} (class, style) pairs; $found found, ${missing.size} not declared: ${missing.keys.take(10)}")
    }
}
