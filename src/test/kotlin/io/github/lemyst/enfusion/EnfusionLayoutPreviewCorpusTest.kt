package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.preview.LayoutGeometry
import io.github.lemyst.enfusion.objfmt.preview.LayoutRenderer
import io.github.lemyst.enfusion.objfmt.preview.LayoutSpecs
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
}
