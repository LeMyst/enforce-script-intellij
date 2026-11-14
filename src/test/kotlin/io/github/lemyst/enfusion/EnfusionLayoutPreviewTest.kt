package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.preview.LayoutGeometry
import io.github.lemyst.enfusion.objfmt.preview.LayoutRenderer
import io.github.lemyst.enfusion.objfmt.preview.LayoutSpecs
import io.github.lemyst.enfusion.objfmt.preview.Rect
import io.github.lemyst.enfusion.objfmt.preview.WidgetSpec

/** The layout preview: the maths on hand-written specs, and specs read from a hand-written layout. */
class EnfusionLayoutPreviewTest : BasePlatformTestCase() {

    private fun widget(type: String, vararg props: Pair<String, String>, children: List<WidgetSpec> = emptyList()) =
        WidgetSpec(type, null, props.associate { it.first.lowercase() to it.second.split(' ') }, children)

    private val screen = Rect(0.0, 0.0, 1000.0, 500.0)

    fun testProportionalAndExactSizes() {
        val half = widget("PanelWidgetClass", "size" to "0.5 0.5")
        assertEquals(Rect(0.0, 0.0, 500.0, 250.0), LayoutGeometry.rectOf(half, screen))
        val pixels = widget("PanelWidgetClass", "size" to "200 100", "hexactsize" to "1", "vexactsize" to "1")
        assertEquals(Rect(0.0, 0.0, 200.0, 100.0), LayoutGeometry.rectOf(pixels, screen))
        // Nothing set: the whole parent.
        assertEquals(screen, LayoutGeometry.rectOf(widget("FrameWidgetClass"), screen))
    }

    fun testAlignmentAndOffsets() {
        val base = arrayOf("size" to "100 50", "hexactsize" to "1", "vexactsize" to "1", "position" to "10 20", "hexactpos" to "1", "vexactpos" to "1")
        assertEquals(Rect(10.0, 20.0, 100.0, 50.0), LayoutGeometry.rectOf(widget("PanelWidgetClass", *base), screen))
        assertEquals(
            Rect(1000.0 - 100 - 10, 500.0 - 50 - 20, 100.0, 50.0),
            LayoutGeometry.rectOf(widget("PanelWidgetClass", *base, "halign" to "right_ref", "valign" to "bottom_ref"), screen),
        )
        assertEquals(
            Rect(450.0 + 10, 225.0 + 20, 100.0, 50.0),
            LayoutGeometry.rectOf(widget("PanelWidgetClass", *base, "halign" to "center_ref", "valign" to "center_ref"), screen),
        )
        val proportional = widget("PanelWidgetClass", "size" to "0.1 0.1", "position" to "0.1 0.2")
        assertEquals(Rect(100.0, 100.0, 100.0, 50.0), LayoutGeometry.rectOf(proportional, screen))
    }

    fun testChildrenAreRelativeToTheirParent() {
        val child = widget("TextWidgetClass", "size" to "0.5 1", "halign" to "right_ref")
        val parent = widget("FrameWidgetClass", "size" to "400 200", "hexactsize" to "1", "vexactsize" to "1", "position" to "100 50", "hexactpos" to "1", "vexactpos" to "1", children = listOf(child))
        val placed = LayoutGeometry.layout(listOf(parent), 1000.0, 500.0)
        assertEquals(listOf(Rect(100.0, 50.0, 400.0, 200.0), Rect(300.0, 50.0, 200.0, 200.0)), placed.map { it.rect })
        assertEquals(listOf(0, 1), placed.map { it.depth })
    }

    fun testHiddenParentHidesItsChildren() {
        val placed = LayoutGeometry.layout(
            listOf(widget("FrameWidgetClass", "visible" to "0", children = listOf(widget("PanelWidgetClass")))),
            100.0, 100.0,
        )
        assertTrue(placed.all { it.hidden })
        assertNull(LayoutGeometry.hit(placed, 50.0, 50.0, includeHidden = false))
        assertNotNull(LayoutGeometry.hit(placed, 50.0, 50.0, includeHidden = true))
    }

    fun testGridSpacerPlacesChildrenInCells() {
        val cells = (0 until 4).map { widget("ImageWidgetClass", "position" to "0.9 0.9") }
        val grid = widget("GridSpacerWidgetClass", "Columns" to "2", "Rows" to "2", children = cells)
        val placed = LayoutGeometry.layout(listOf(grid), 200.0, 100.0)
        assertEquals(
            listOf(Rect(0.0, 0.0, 100.0, 50.0), Rect(100.0, 0.0, 100.0, 50.0), Rect(0.0, 50.0, 100.0, 50.0), Rect(100.0, 50.0, 100.0, 50.0)),
            placed.drop(1).map { it.rect },
        )
    }

    fun testWrapSpacerFlowsAndWraps() {
        val items = (0 until 3).map { widget("PanelWidgetClass", "size" to "80 30", "hexactsize" to "1", "vexactsize" to "1") }
        val wrap = widget("WrapSpacerWidgetClass", "size" to "200 100", "hexactsize" to "1", "vexactsize" to "1", children = items)
        val placed = LayoutGeometry.layout(listOf(wrap), 1000.0, 500.0)
        assertEquals(
            listOf(Rect(0.0, 0.0, 80.0, 30.0), Rect(80.0, 0.0, 80.0, 30.0), Rect(0.0, 30.0, 80.0, 30.0)),
            placed.drop(1).map { it.rect },
        )
    }

    fun testColor() {
        assertEquals(0x80FF0000.toInt(), widget("PanelWidgetClass", "color" to "1 0 0 0.502").color)
        assertNull(widget("PanelWidgetClass").color)
    }

    fun testSpecsFromALayoutFile() {
        val file = myFixture.configureByText(
            "hud.layout",
            """
            FrameWidgetClass Root {
             size 1 1
             {
              TextWidgetClass Title {
               position 10 10
               hexactpos 1
               vexactpos 1
               size 200 40
               hexactsize 1
               vexactsize 1
               text "#STR_MOD_TITLE"
               "text halign" center
              }
              ImageWidgetClass Icon {
               visible 0
               image0 "set:my_gui image:Missing"
              }
             }
            }
            """.trimIndent(),
        )
        val roots = LayoutSpecs.of(file)
        assertEquals(listOf("FrameWidgetClass"), roots.map { it.type })
        val (title, icon) = roots[0].children
        assertEquals("Title", title.name)
        assertEquals("#STR_MOD_TITLE", title.text)
        assertEquals("center", title.value("text halign"))
        assertFalse(icon.visible)
        assertTrue(title.offset in 0 until title.endOffset)

        val placed = LayoutGeometry.layout(roots, 1920.0, 1080.0)
        assertEquals(Rect(10.0, 10.0, 200.0, 40.0), placed[1].rect)
        assertSame(title, LayoutGeometry.at(placed, file.text.indexOf("#STR_MOD_TITLE"))?.spec)
        // An image that does not resolve draws nothing, and rendering still succeeds.
        assertNull(LayoutSpecs.image(file, icon, 64))
        val picture = LayoutRenderer.render(placed, 1920, 1080, showHidden = true, selected = title) { spec, side -> LayoutSpecs.image(file, spec, side) }
        assertEquals(1920, picture.width)
    }
}
