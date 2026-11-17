package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.preview.FontFiles
import io.github.lemyst.enfusion.objfmt.preview.LayoutGeometry
import io.github.lemyst.enfusion.objfmt.preview.LayoutImages
import io.github.lemyst.enfusion.objfmt.preview.LayoutRenderer
import io.github.lemyst.enfusion.objfmt.preview.LayoutSpecs
import io.github.lemyst.enfusion.objfmt.preview.LayoutTranslations
import io.github.lemyst.enfusion.objfmt.preview.Rect
import io.github.lemyst.enfusion.objfmt.preview.WidgetSpec
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private fun row(height: Int, visible: Boolean = true) = widget(
        "ButtonWidgetClass", "size" to "1 $height", "hexactsize" to "0", "vexactsize" to "1", "visible" to if (visible) "1" else "0",
    )

    /** As `day_z_ingamemenu`'s `restartbtn`: a hidden child takes no room, the next one closes up. */
    fun testHiddenChildOfAWrapSpacerTakesNoSlot() {
        val wrap = widget("WrapSpacerWidgetClass", "size" to "100 200", "hexactsize" to "1", "vexactsize" to "1", children = listOf(row(38), row(38, visible = false), row(38)))
        val rects = LayoutGeometry.layout(listOf(wrap), 1000.0, 500.0).drop(1).map { it.rect }
        assertEquals(listOf(Rect(0.0, 0.0, 100.0, 38.0), Rect(0.0, 38.0, 100.0, 38.0), Rect(0.0, 38.0, 100.0, 38.0)), rects)
    }

    fun testHiddenChildOfAGridSpacerTakesNoCell() {
        val cells = listOf(true, false, true).map { widget("ImageWidgetClass", "visible" to if (it) "1" else "0") }
        val grid = widget("GridSpacerWidgetClass", "Columns" to "1", children = cells)
        val rects = LayoutGeometry.layout(listOf(grid), 100.0, 100.0).drop(1).map { it.rect }
        assertEquals(listOf(Rect(0.0, 0.0, 100.0, 50.0), Rect(0.0, 50.0, 100.0, 50.0), Rect(0.0, 50.0, 100.0, 50.0)), rects)
    }

    /** As `day_z_ingamemenu`'s `top` spacer: declared 48 high, bottom-aligned, holding 5 + 38 + 38 + 5. */
    fun testSizeToContentGrowsABottomAlignedSpacerUpwards() {
        val wrap = widget(
            "WrapSpacerWidgetClass", "size" to "1 48", "hexactsize" to "0", "vexactsize" to "1", "valign" to "bottom_ref",
            "Size To Content V" to "1", children = listOf(row(5), row(38), row(38, visible = false), row(38), row(5)),
        )
        val placed = LayoutGeometry.layout(listOf(wrap), 400.0, 500.0)
        assertEquals(Rect(0.0, 500.0 - 86.0, 400.0, 86.0), placed[0].rect)
        assertEquals("the last row ends at the screen's bottom edge", 500.0, placed.last().rect.let { it.y + it.h })
    }

    /** `fixaspect` with a proportional size: the numbers give a ratio, fitted to or covering the parent. */
    fun testFixAspect() {
        val hd = Rect(0.0, 0.0, 1920.0, 1080.0)
        fun box(mode: String, w: String, h: String, vararg more: Pair<String, String>) =
            LayoutGeometry.rectOf(widget("ImageWidgetClass", "size" to "$w $h", "fixaspect" to mode, *more), hd)
        // The vanilla vignette: 16:9 covering a 16:9 screen is the screen, nudged by its pixel position.
        assertEquals(
            Rect(0.688, -0.688, 1920.0, 1080.0),
            box("outside", "1.6", "0.9", "position" to "0.688 -0.688", "hexactpos" to "1", "vexactpos" to "1", "valign" to "center_ref"),
        )
        assertEquals("the size only gives the ratio", Rect(0.0, 0.0, 1920.0, 1080.0), box("outside", "0.16", "0.09"))
        // The two cases checked in Workbench.
        assertEquals(Rect(0.0, -420.0, 1920.0, 1920.0), box("outside", "1", "1", "valign" to "center_ref"))
        assertEquals(Rect(0.0, 0.0, 2160.0, 1080.0), box("outside", "1", "0.5"))
        assertEquals(Rect(420.0, 0.0, 1080.0, 1080.0), box("inside", "1", "1", "halign" to "center_ref"))
        // fixwidth derives the width (from the height), fixheight the height (from the width).
        assertEquals(Rect(0.0, 0.0, 540.0, 270.0), box("fixwidth", "0.5", "0.25"))
        assertEquals(Rect(0.0, 0.0, 1920.0, 960.0), box("fixheight", "1", "0.5"))
        assertEquals(Rect(0.0, 0.0, 960.0, 540.0), box("none", "0.5", "0.5"))
        val pixels = widget("ImageWidgetClass", "size" to "444 100", "hexactsize" to "1", "vexactsize" to "1", "fixaspect" to "outside")
        assertEquals("a pixel size already has its ratio", Rect(0.0, 0.0, 444.0, 100.0), LayoutGeometry.rectOf(pixels, hd))
    }

    fun testChildrenAreClippedUnlessTheParentSaysNot() {
        val child = widget("PanelWidgetClass", "size" to "300 300", "hexactsize" to "1", "vexactsize" to "1")
        val clipping = widget("FrameWidgetClass", "size" to "100 100", "hexactsize" to "1", "vexactsize" to "1", children = listOf(child))
        val placed = LayoutGeometry.layout(listOf(clipping), 1000.0, 500.0)
        assertEquals(Rect(0.0, 0.0, 100.0, 100.0), placed[1].clip)
        assertNull("cut off past the parent", LayoutGeometry.hit(placed, 200.0, 200.0, includeHidden = false))

        val open = widget("FrameWidgetClass", "size" to "100 100", "hexactsize" to "1", "vexactsize" to "1", "clipchildren" to "0", children = listOf(child))
        val unclipped = LayoutGeometry.layout(listOf(open), 1000.0, 500.0)
        assertEquals(Rect(0.0, 0.0, 1000.0, 500.0), unclipped[1].clip)
        assertSame(child, LayoutGeometry.hit(unclipped, 200.0, 200.0, includeHidden = false)?.spec)
    }

    /**
     * `day_z_ingamemenu`'s `play_panel_root`: `content_valign bottom` stacks the lines from the bottom
     * edge upwards, so its first child (`bottom`: Continue, Respawn) is lowest and its last (`top`:
     * Exit, Feedback, Options) highest, as Workbench draws it.
     */
    fun testContentValignBottomStacksUpwards() {
        val first = widget("PanelWidgetClass", "size" to "1 90", "vexactsize" to "1")
        val separator = widget("PanelWidgetClass", "size" to "1 2", "vexactsize" to "1")
        val last = widget("PanelWidgetClass", "size" to "1 124", "vexactsize" to "1")
        val column = widget(
            "WrapSpacerWidgetClass", "size" to "400 400", "hexactsize" to "1", "vexactsize" to "1", "content_valign" to "bottom",
            children = listOf(first, separator, last),
        )
        val rects = LayoutGeometry.layout(listOf(column), 1000.0, 500.0).associate { it.spec to it.rect }
        assertEquals(Rect(0.0, 310.0, 400.0, 90.0), rects[first])
        assertEquals(Rect(0.0, 308.0, 400.0, 2.0), rects[separator])
        assertEquals(Rect(0.0, 184.0, 400.0, 124.0), rects[last])
    }

    fun testContentAlignment() {
        val item = { widget("PanelWidgetClass", "size" to "40 20", "hexactsize" to "1", "vexactsize" to "1") }
        fun first(vararg props: Pair<String, String>): Rect {
            val box = widget("WrapSpacerWidgetClass", "size" to "100 100", "hexactsize" to "1", "vexactsize" to "1", *props, children = listOf(item(), item(), item()))
            return LayoutGeometry.layout(listOf(box), 1000.0, 500.0)[1].rect
        }
        assertEquals(Rect(0.0, 0.0, 40.0, 20.0), first())
        assertEquals("80 wide line centred in 100", Rect(10.0, 30.0, 40.0, 20.0), first("content_halign" to "center", "content_valign" to "center"))
        assertEquals("the first child at the right end of its line", Rect(60.0, 0.0, 40.0, 20.0), first("content_halign" to "right"))
    }

    fun testContentHalignRightFillsLinesFromTheRight() {
        // day_z_hud's BadgesPanel: content_halign right, the first badge declared drawn rightmost in
        // Workbench. Its VoiceLevelsPanel: no content_halign, its levels left to right, although
        // they say right_ref too.
        fun badge(w: Int) = widget("ImageWidgetClass", "size" to "$w 35", "hexactsize" to "1", "vexactsize" to "1", "halign" to "right_ref")
        val wetness = badge(35)
        val fracture = badge(35)
        val stomach = badge(52)
        val panel = widget(
            "WrapSpacerWidgetClass", "size" to "300 79", "hexactsize" to "1", "vexactsize" to "1", "content_halign" to "right",
            children = listOf(wetness, fracture, stomach),
        )
        val placed = LayoutGeometry.layout(listOf(panel), 1000.0, 500.0).associate { it.spec to it.rect }
        assertEquals(Rect(265.0, 0.0, 35.0, 35.0), placed[wetness])
        assertEquals(Rect(230.0, 0.0, 35.0, 35.0), placed[fracture])
        assertEquals(Rect(178.0, 0.0, 52.0, 35.0), placed[stomach])

        val whisper = badge(9)
        val talk = badge(9)
        val levels = widget("WrapSpacerWidgetClass", "size" to "40 31", "hexactsize" to "1", "vexactsize" to "1", children = listOf(whisper, talk))
        val voice = LayoutGeometry.layout(listOf(levels), 1000.0, 500.0).associate { it.spec to it.rect }
        assertEquals(0.0, voice.getValue(whisper).x)
        assertEquals(9.0, voice.getValue(talk).x)
    }

    fun testRotationTurnsAboutTheCentreByDefault() {
        // day_z_hud's voice levels: turned by 180 with no pivot, still in their boxes in Workbench.
        val picture = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        picture.setRGB(0, 0, 0xFFFF0000.toInt())
        picture.setRGB(1, 0, 0xFF0000FF.toInt())
        fun drawn(vararg more: Pair<String, String>): BufferedImage {
            val turned = widget("ImageWidgetClass", "size" to "20 10", "position" to "10 10", "hexactsize" to "1", "vexactsize" to "1",
                "hexactpos" to "1", "vexactpos" to "1", "rotation" to "0 0 180", *more)
            return LayoutRenderer.render(LayoutGeometry.layout(listOf(turned), 40.0, 30.0), 40, 30, showHidden = false, selected = null) { _, _ -> picture }
        }
        val centred = drawn()
        assertEquals("the blue half now on the left", 0xFF0000FF.toInt(), centred.getRGB(13, 15))
        assertEquals(0xFFFF0000.toInt(), centred.getRGB(26, 15))
        // About its top left corner, it lands above and left of its box.
        assertEquals(0xFFFF0000.toInt(), drawn("pivot" to "0 0").getRGB(8, 5))
    }

    fun testHiddenChildNeverMovesTheOthers() {
        val wide = widget("PanelWidgetClass", "size" to "90 20", "hexactsize" to "1", "vexactsize" to "1", "visible" to "0")
        val small = { widget("PanelWidgetClass", "size" to "30 20", "hexactsize" to "1", "vexactsize" to "1") }
        val a = small()
        val b = small()
        val box = widget("WrapSpacerWidgetClass", "size" to "100 100", "hexactsize" to "1", "vexactsize" to "1", children = listOf(a, wide, b))
        val rects = LayoutGeometry.layout(listOf(box), 1000.0, 500.0).associate { it.spec to it.rect }
        assertEquals(Rect(30.0, 0.0, 30.0, 20.0), rects[b])
        assertEquals("drawn where it would wrap to", Rect(0.0, 20.0, 90.0, 20.0), rects[wide])
    }

    fun testWithoutSizeToContentASpacerKeepsItsSize() {
        val wrap = widget("WrapSpacerWidgetClass", "size" to "1 48", "vexactsize" to "1", "valign" to "bottom_ref", children = listOf(row(38), row(38)))
        assertEquals(Rect(0.0, 452.0, 400.0, 48.0), LayoutGeometry.layout(listOf(wrap), 400.0, 500.0)[0].rect)
    }

    fun testColor() {
        assertEquals(0x80FF0000.toInt(), widget("PanelWidgetClass", "color" to "1 0 0 0.502").color)
        assertNull(widget("PanelWidgetClass").color)
        // Linear, shown in sRGB: the colour Workbench gives a dialog button with these values.
        assertEquals(0xFF696969.toInt(), widget("ButtonWidgetClass", "color" to "0.141 0.141 0.141 1").color)
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

    /** A white, opaque 4 x 1 picture, and a mask rising 0, 1/3, 2/3, 1 from left to right. */
    private val white = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB).apply { for (x in 0 until 4) setRGB(x, 0, -1) }
    private val ramp = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB).apply {
        for (x in 0 until 4) { val v = x * 255 / 3; setRGB(x, 0, (0xFF shl 24) or (v shl 16) or (v shl 8) or v) }
    }

    private fun alphas(image: BufferedImage) = (0 until image.width).map { image.getRGB(it, 0) ushr 24 }

    fun testImageIsTintedByItsColor() {
        val out = LayoutImages.finish(white, widget("ImageWidgetClass", "color" to "1 0 0 0.5"), null)
        assertEquals(0x80FF0000.toInt(), out.getRGB(0, 0))
        assertEquals("the cached original is left alone", -1, white.getRGB(0, 0))
    }

    fun testMaskShowsWhatIsBelowTheProgress() {
        val out = LayoutImages.finish(white, widget("ImageWidgetClass", "progress" to "0.5"), ramp)
        assertEquals(listOf(255, 255, 0, 0), alphas(out))
        val none = LayoutImages.finish(white, widget("ImageWidgetClass", "progress" to "0"), ramp)
        assertEquals(listOf(0, 0, 0, 0), alphas(none))
    }

    fun testFlipMirrorsTheMaskedPicture() {
        val out = LayoutImages.finish(white, widget("ImageWidgetClass", "progress" to "0.5", "flip u" to "1"), ramp)
        assertEquals("the red line: the same mask, flipped, covers the end", listOf(0, 0, 255, 255), alphas(out))
    }

    fun testWidgetWithAMissingPictureIsNotFilled() {
        val dial = widget("ImageWidgetClass", "color" to "1 0 0 1", "imageTexture" to "{0123456789ABCDEF}Gui/textures/none.edds", "size" to "1 1")
        val plain = widget("ImageWidgetClass", "color" to "1 0 0 1", "image0" to "", "size" to "1 1")
        assertFalse(LayoutSpecs.hasImage(plain))
        val picture = { spec: WidgetSpec -> LayoutRenderer.render(LayoutGeometry.layout(listOf(spec), 10.0, 10.0), 10, 10, false, null) { _, _ -> null } }
        assertFalse("not found: outline only, no red block", picture(dial).getRGB(5, 5) == 0xFFFF0000.toInt())
        assertEquals("no picture at all: a tinted quad", 0xFFFF0000.toInt(), picture(plain).getRGB(5, 5))
    }

    fun testExactTextIsTheEmAndProportionalTextTheLine() {
        val box = Rect(0.0, 0.0, 444.0, 20.0)
        assertEquals(20.0, LayoutRenderer.fontSize(widget("TextWidgetClass", "exact text" to "1", "exact text size" to "20"), box, 1.15))
        assertEquals(20.0 / 1.15, LayoutRenderer.fontSize(widget("TextWidgetClass"), box, 1.15), 1e-9)
    }

    fun testGridCountsProportionalChildrenAgainstItsDeclaredSize() {
        // day_z_connection_dialogue: a 0.015-high separator between rows sized to content.
        val line = widget("PanelWidgetClass", "size" to "0.85 0.015")
        val grid = widget("GridSpacerWidgetClass", "size" to "0.45 0.3", "Size To Content V" to "1", "Columns" to "1", children = listOf(line))
        val placed = LayoutGeometry.layout(listOf(grid), 1920.0, 1080.0).associate { it.spec to it.rect }
        assertEquals(0.015 * 0.3 * 1080, placed.getValue(line).h, 1e-9)
    }

    fun testRowsAloneGiveTheColumns() {
        val keys = (1..12).map { widget("ButtonWidgetClass", "size" to "50 40", "hexactsize" to "1", "vexactsize" to "1") }
        val keypad = widget("GridSpacerWidgetClass", "Rows" to "4", "Size To Content H" to "1", "Size To Content V" to "1", children = keys)
        val placed = LayoutGeometry.layout(listOf(keypad), 1920.0, 1080.0).associate { it.spec to it.rect }
        assertEquals(Rect(0.0, 0.0, 150.0, 160.0), placed[keypad])
        assertEquals("the fourth key starts the second row", Rect(0.0, 40.0, 50.0, 40.0), placed[keys[3]])
    }

    fun testButtonsAndBlankPanelsAreNotFilled() {
        fun centre(spec: WidgetSpec) = LayoutRenderer.render(LayoutGeometry.layout(listOf(spec), 10.0, 10.0), 10, 10, false, null) { _, _ -> null }.getRGB(5, 5)
        assertFalse(centre(widget("ButtonWidgetClass", "color" to "1 0 0 1")) == 0xFFFF0000.toInt())
        assertFalse(centre(widget("PanelWidgetClass", "color" to "1 0 0 1", "style" to "blank")) == 0xFFFF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), centre(widget("PanelWidgetClass", "color" to "1 0 0 1", "style" to "rover_sim_colorable")))
    }

    fun testOnlyRefValuesAlign() {
        val parent = Rect(0.0, 0.0, 1000.0, 500.0)
        val plain = widget("TextWidgetClass", "size" to "100 20", "hexactsize" to "1", "vexactsize" to "1", "position" to "0 0.6", "valign" to "bottom")
        assertEquals("valign bottom is not bottom_ref", 300.0, LayoutGeometry.rectOf(plain, parent).y)
    }

    fun testKeysAreCaseInsensitiveInTopLevelStringtables() {
        myFixture.addFileToProject(
            "stringtable.csv",
            """
            "Language","original","english","french",
            "str_dialog_label","LABEL","LABEL","ÉTIQUETTE",
            """.trimIndent(),
        )
        val table = LayoutTranslations.of(project, setOf("STR_Dialog_Label"))
        assertEquals("ÉTIQUETTE", table.show("#STR_Dialog_Label", "french"))
    }

    fun testTextSize() {
        val box = Rect(0.0, 0.0, 444.0, 100.0)
        assertEquals(20.0, LayoutRenderer.textSize(widget("MultilineTextWidgetClass", "exact text" to "1", "exact text size" to "20", "text_proportion" to "0.5"), box))
        assertEquals(50.0, LayoutRenderer.textSize(widget("TextWidgetClass", "exact text" to "0", "text_proportion" to "0.5"), box))
        assertEquals(100.0, LayoutRenderer.textSize(widget("TextWidgetClass"), box))
        assertEquals("exact without a size", 14.0, LayoutRenderer.textSize(widget("TextWidgetClass", "exact text" to "1"), box))
        val sizedToText = widget("MultilineTextWidgetClass", "exact text" to "0", "exact text size" to "22", "size to text v" to "1")
        assertEquals("sized to its text: the exact size, not the box", 22.0, LayoutRenderer.textSize(sizedToText, box))
    }

    fun testWrapBreaksAtSpaces() {
        val measure = { line: String -> line.length * 10.0 }
        assertEquals(listOf("one two", "three", "four"), LayoutRenderer.wrap("one two three four", 75.0, measure))
        assertEquals("a long word keeps its own line", listOf("a", "extraordinary", "b"), LayoutRenderer.wrap("a extraordinary b", 50.0, measure))
        assertEquals(listOf("first", "second"), LayoutRenderer.wrap("first\nsecond", 500.0, measure))
    }

    fun testClickPicksTheSmallestWidgetUnderThePointer() {
        // The car HUD: a needle, then a shadow declared after it and covering it.
        val needle = widget("ImageWidgetClass", "size" to "18 111", "hexactsize" to "1", "vexactsize" to "1")
        val shadow = widget("ImageWidgetClass", "size" to "1.2 1.2")
        val panel = widget("PanelWidgetClass", "size" to "300 200", "hexactsize" to "1", "vexactsize" to "1", "clipchildren" to "0", children = listOf(needle, shadow))
        val placed = LayoutGeometry.layout(listOf(panel), 1000.0, 500.0)
        assertSame(needle, LayoutGeometry.hit(placed, 5.0, 5.0, includeHidden = false)?.spec)
        assertEquals("then larger and larger", listOf(needle, panel, shadow), LayoutGeometry.hits(placed, 5.0, 5.0, includeHidden = false).map { it.spec })
    }

    fun testGridSpacerSizedToContent() {
        // day_z_map's legend, reduced: a 145 x 142 compass beside a 256-wide column, in a grid
        // declared 600 x 250 that fits its content, with padding and margins.
        fun px(w: Int, h: Int, vararg more: Pair<String, String>) =
            widget("PanelWidgetClass", "size" to "$w $h", "hexactsize" to "1", "vexactsize" to "1", *more)
        val compass = px(145, 142)
        val column = widget(
            "GridSpacerWidgetClass", "size" to "145 142", "hexactsize" to "1", "vexactsize" to "1",
            "Size To Content H" to "1", "Size To Content V" to "1", "Columns" to "1", "Rows" to "2",
            children = listOf(px(256, 20), px(256, 20)),
        )
        val grid = widget(
            "GridSpacerWidgetClass", "size" to "600 250", "hexactsize" to "1", "vexactsize" to "1",
            "Size To Content H" to "1", "Size To Content V" to "1", "Columns" to "2", "Rows" to "1",
            "Padding" to "5", "Margin" to "4", children = listOf(compass, column),
        )
        val placed = LayoutGeometry.layout(listOf(grid), 1920.0, 1080.0).associate { it.spec to it.rect }
        // The margin inside the edges, the padding between the cells.
        assertEquals(Rect(0.0, 0.0, 4 + 145 + 5 + 256 + 4.0, 4 + 142 + 4.0), placed[grid])
        assertEquals(Rect(4.0, 4.0, 145.0, 142.0), placed[compass])
        assertEquals(Rect(154.0, 4.0, 256.0, 40.0), placed[column])
    }

    fun testGridMarginAtTheEdgesPaddingBetweenCells() {
        // day_z_underground_trigger_editor_popup_dialog, reduced, as Workbench draws it at
        // 1920 x 1080: a dialog half the screen wide with Margin 10 and Padding 20, sized to its
        // content, holding a message line and a spacer of two buttons sized to its width, with
        // Margin 0 and Padding 20.
        val message = widget("PanelWidgetClass", "size" to "1 36", "vexactsize" to "1")
        val ok = widget("ButtonWidgetClass", "size" to "0.3 1")
        val cancel = widget("ButtonWidgetClass", "size" to "0.3 1")
        val buttons = widget(
            "GridSpacerWidgetClass", "size" to "0.8 0.1", "Padding" to "20", "Margin" to "0",
            "Size To Content H" to "1", "Columns" to "2", "Rows" to "1", children = listOf(ok, cancel),
        )
        val dialog = widget(
            "GridSpacerWidgetClass", "size" to "0.5 0.3", "halign" to "center_ref", "valign" to "center_ref",
            "Padding" to "20", "Margin" to "10", "Size To Content H" to "1", "Size To Content V" to "1",
            "Columns" to "1", "Rows" to "2", children = listOf(message, buttons),
        )
        val placed = LayoutGeometry.layout(listOf(dialog), 1920.0, 1080.0).associate { it.spec to it.rect }
        fun assertRect(expected: Rect, actual: Rect?) {
            val a = actual!!
            for ((e, v) in listOf(expected.x to a.x, expected.y to a.y, expected.w to a.w, expected.h to a.h)) assertEquals("$expected vs $a", e, v, 1e-9)
        }
        val d = placed.getValue(dialog)
        // The room inside the dialog: 960 less two margins wide, 324 less two margins and the
        // padding between its rows high. Workbench: the dialog 960 wide, the message 940.
        val row = 0.1 * (324 - 2 * 10 - 20)
        assertRect(Rect(480.0, (1080 - (10 + 36 + 20 + row + 10)) / 2, 960.0, 10 + 36 + 20 + row + 10), d)
        assertRect(Rect(d.x + 10, d.y + 10, 940.0, 36.0), placed[message])
        // 0.3 of the buttons' room, 0.8 x 940 less the 20 between them: 220 in Workbench, 20 apart,
        // the first against the spacer's edge.
        val button = 0.3 * (0.8 * 940 - 20)
        assertRect(Rect(d.x + 10, d.y + 66, button, row), placed[ok])
        assertRect(Rect(d.x + 10 + button + 20, d.y + 66, button, row), placed[cancel])
        assertRect(Rect(d.x + 10, d.y + 66, 2 * button + 20, row), placed[buttons])
    }

    fun testExactTextWithoutSizeTakesItsFont() {
        // day_z_underground_trigger_editor_popup_dialog's message: "exact text" 1, no size, in a
        // font whose file gives 24 pixels and a 36-pixel line; Workbench draws it 36 high.
        fun message(font: FontFiles.Metrics?) = WidgetSpec(
            "RichTextWidgetClass", null,
            mapOf("text" to listOf("test"), "exact text" to listOf("1"), "size to text v" to listOf("1"), "size" to listOf("1", "0.3")),
            emptyList(), font = font,
        )
        val sdf = message(FontFiles.Metrics(24.0, 36.0))
        assertEquals(24.0, LayoutRenderer.textSize(sdf, screen))
        assertEquals(36.0, LayoutGeometry.layout(listOf(sdf), 1920.0, 1080.0).single().rect.h, 1e-9)
        // A bitmap font's file gives only its size.
        assertEquals(16 * 1.4, LayoutGeometry.layout(listOf(message(FontFiles.Metrics(16.0, null))), 1920.0, 1080.0).single().rect.h, 1e-9)
        assertEquals("no font", 14.0, LayoutRenderer.textSize(message(null), screen))
    }

    fun testFontFileMetrics() {
        fun bigEndian(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
        fun chunk(tag: String, body: ByteArray) =
            tag.toByteArray(Charsets.US_ASCII) + bigEndian(body.size) + body + ByteArray(body.size % 2)
        fun head(name: String, size: Int, line: Int?): ByteArray {
            val n = (name + "\u0000").toByteArray(Charsets.US_ASCII)
            val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(n.size).put(n).putInt(size)
            if (line != null) b.putInt(0).put(2).putShort(line.toShort()).putFloat(size * 0.8f).putFloat(size.toFloat())
            return b.array().copyOf(b.position())
        }
        fun font(version: String, vararg chunks: ByteArray): ByteArray {
            val body = chunks.fold(version.toByteArray(Charsets.US_ASCII)) { all, c -> all + c }
            return "FORM".toByteArray(Charsets.US_ASCII) + bigEndian(body.size) + body
        }

        val sdf = FontFiles.read(font("FNT5", chunk("HEAD", head("Test24", 24, 36)), chunk("GLPS", ByteArray(4))))!!
        assertEquals(24.0, sdf.size)
        assertEquals(36.0, sdf.lineHeight)
        // A bitmap font: its HEAD after its glyphs (an odd length, padded), with no line height.
        val bitmap = FontFiles.read(font("FNT3", chunk("GLPS", ByteArray(3)), chunk("HEAD", head("Test", 14, null))))!!
        assertEquals(14.0, bitmap.size)
        assertNull(bitmap.lineHeight)
        assertNull(FontFiles.read("not a font at all".toByteArray()))
        assertNull("cut short", FontFiles.read(font("FNT5", chunk("HEAD", head("Test24", 24, 36)).copyOf(10))))

        assertEquals(24.0, FontFiles.sizeInName("gui/fonts/sdf_MetronBook24"))
        assertEquals(16.0, FontFiles.sizeInName("gui/fonts/Metron16"))
        assertNull("a family, sized by the widget", FontFiles.sizeInName("gui/fonts/Metron"))
    }

    fun testGridAddsRowsAndTextSizedToItsText() {
        // day_z_respawn_dialogue's tooltip: Rows 2, three children, the last a body whose text is
        // declared one pixel tall and sized to its text.
        val header = widget("PanelWidgetClass", "size" to "1 36", "vexactsize" to "1")
        val line = widget("PanelWidgetClass", "size" to "1 2", "vexactsize" to "1")
        val content = widget(
            "RichTextWidgetClass", "size" to "1 1", "vexactsize" to "1", "text" to "this is details",
            "exact text" to "1", "exact text size" to "16", "size to text v" to "1", "wrap" to "1",
        )
        val body = widget("GridSpacerWidgetClass", "Margin" to "15", "Size To Content V" to "1", "Columns" to "1", "Rows" to "1", children = listOf(content))
        val tooltip = widget(
            "GridSpacerWidgetClass", "size" to "0.2 1", "Size To Content V" to "1", "Columns" to "1", "Rows" to "2",
            children = listOf(header, line, body),
        )
        val placed = LayoutGeometry.layout(listOf(tooltip), 1920.0, 1080.0).associate { it.spec to it.rect }
        assertEquals(16 * 1.4, placed.getValue(content).h, 1e-9)
        assertEquals(15.0, placed.getValue(content).y - placed.getValue(body).y)
        assertEquals("a third row for the third child", 36 + 2 + (15 + 16 * 1.4 + 15), placed.getValue(tooltip).h, 1e-9)
    }

    fun testHigherPriorityIsDrawnOnTop() {
        // dialog_input_password: the window, priority 1, then a full-screen background without one.
        val button = widget("ButtonWidgetClass", "size" to "130 40", "hexactsize" to "1", "vexactsize" to "1")
        val window = widget("WindowWidgetClass", "size" to "540 223", "hexactsize" to "1", "vexactsize" to "1", "priority" to "1", children = listOf(button))
        val background = widget("ImageWidgetClass")
        val root = widget("WindowWidgetClass", children = listOf(window, background))
        val placed = LayoutGeometry.layout(listOf(root), 1920.0, 1080.0)
        assertEquals("the window and its button after the background", listOf(root, background, window, button), placed.map { it.spec })
        assertEquals(Rect(0.0, 0.0, 540.0, 223.0), placed.first { it.spec === window }.rect)
    }

    fun testKeysShownInTheChosenLanguage() {
        myFixture.addFileToProject(
            "stringtable.csv",
            """
            "Language","original","english","french",
            "STR_MOD_TITLE","Title","Title","Titre",
            "STR_MOD_UNTRANSLATED","Only original","","",
            """.trimIndent(),
        )
        val table = LayoutTranslations.of(project, setOf("STR_MOD_TITLE", "STR_MOD_UNTRANSLATED", "STR_MOD_MISSING"))
        assertEquals(listOf("original", "english", "french"), table.languages)
        assertEquals("Titre", table.show("#STR_MOD_TITLE", "french"))
        assertEquals("Title", table.show("#STR_MOD_TITLE", "english"))
        assertEquals("#STR_MOD_TITLE", table.show("#STR_MOD_TITLE", null))
        assertEquals("an empty translation falls back to original", "Only original", table.show("#STR_MOD_UNTRANSLATED", "french"))
        assertEquals("an unknown key stays a key", "#STR_MOD_MISSING", table.show("#STR_MOD_MISSING", "french"))
        assertEquals("plain text is left alone", "Hello", table.show("Hello", "french"))
    }
}
