package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.objfmt.preview.LayoutGeometry
import io.github.lemyst.enfusion.objfmt.preview.LayoutImages
import io.github.lemyst.enfusion.objfmt.preview.LayoutRenderer
import io.github.lemyst.enfusion.objfmt.preview.Rect
import io.github.lemyst.enfusion.objfmt.preview.WidgetSpec
import io.github.lemyst.enfusion.objfmt.preview.WidgetStyles
import io.github.lemyst.enfusion.objfmt.preview.WidgetStyles.Piece
import junit.framework.TestCase
import java.awt.image.BufferedImage

/** Widget styles: reading a `.styles` file, laying out a nine-piece frame, and drawing it. Hand-written fixtures only. */
class EnfusionWidgetStylesTest : TestCase() {

    private val styles = """
        <WidgetStyles>
            <Widget Name="PanelWidget">
                <Style Name="Framed" Font="" ImageSet="my_set" Color="4294967295">
                    <State Name="Normal">
                        <Item Name="LeftTop" Image="Corner" />
                        <Item Name="Top" Image="" />
                        <Item Name="Center" Image="Middle" />
                    </State>
                    <State Name="Disabled">
                        <Item Name="Center" Image="Grey" />
                    </State>
                </Style>
                <Style Name="blank" Font="" ImageSet="my_set" Color="4294967295">
                    <State Name="Normal" />
                </Style>
            </Widget>
            <Widget Name="ButtonWidget">
                <Style Name="Default" Font="" ImageSet="other_set" Color="4294967295">
                    <State Name="Normal">
                        <Item Name="Center" Image="Plain" />
                        <Item Name="Highlight" Image="NotAPiece" />
                    </State>
                </Style>
            </Widget>
        </WidgetStyles>
    """.trimIndent()

    fun testReadsStylesByWidgetAndState() {
        val sheet = WidgetStyles.parse(styles)
        val framed = sheet.styleOf("PanelWidgetClass", "framed")!!
        assertEquals("my_set", framed.imageSet)
        assertEquals("an empty Image is no piece", mapOf(Piece.LEFT_TOP to "Corner", Piece.CENTER to "Middle"), framed.frame())
        assertEquals(mapOf(Piece.CENTER to "Grey"), framed.frame("Disabled"))
        assertTrue("a style with an empty state draws nothing", sheet.styleOf("PanelWidgetClass", "blank")!!.frame().isEmpty())
        assertEquals("no style is the class's Default", mapOf(Piece.CENTER to "Plain"), sheet.styleOf("ButtonWidgetClass", null)!!.frame())
        assertNull("a panel has no Default", sheet.styleOf("PanelWidgetClass", null))
        assertNull(sheet.styleOf("PanelWidgetClass", "nope"))
        assertNull(sheet.styleOf("TextWidgetClass", "framed"))
    }

    fun testFirstSheetWinsOnTheSameName() {
        val mine = WidgetStyles.parse(
            """<WidgetStyles><Widget Name="PanelWidget"><Style Name="Framed" ImageSet="mine"></Style>""" +
                """<Style Name="Extra" ImageSet="mine"></Style></Widget></WidgetStyles>""",
        )
        val merged = mine.plus(WidgetStyles.parse(styles))
        assertEquals("mine", merged.styleOf("PanelWidgetClass", "Framed")!!.imageSet)
        assertNotNull(merged.styleOf("PanelWidgetClass", "Extra"))
        assertNotNull(merged.styleOf("PanelWidgetClass", "blank"))
        assertNotNull(merged.styleOf("ButtonWidgetClass", "Default"))
    }

    fun testMalformedFileGivesWhatItCan() {
        val sheet = WidgetStyles.parse("""<Widget Name="PanelWidget"><Style Name="A" ImageSet="s"><State Name="Normal"><Item Name="Center" Image="C" />""")
        assertEquals(mapOf(Piece.CENTER to "C"), sheet.styleOf("PanelWidgetClass", "A")!!.frame())
        assertTrue(WidgetStyles.parse("not xml at all").widgets.isEmpty())
    }

    fun testCornersKeepTheirSizeAndEdgesStretch() {
        val all = Piece.entries.associateWith { 10.0 to 10.0 } + mapOf(Piece.LEFT to (6.0 to 1.0), Piece.TOP to (1.0 to 4.0))
        val places = WidgetStyles.layout(Rect(100.0, 50.0, 200.0, 80.0), all)
        assertEquals(Rect(100.0, 50.0, 10.0, 10.0), places[Piece.LEFT_TOP])
        assertEquals("the top row is as tall as its tallest piece", Rect(110.0, 50.0, 180.0, 10.0), places[Piece.TOP])
        assertEquals(Rect(290.0, 50.0, 10.0, 10.0), places[Piece.RIGHT_TOP])
        assertEquals(Rect(110.0, 60.0, 180.0, 60.0), places[Piece.CENTER])
        assertEquals("the left column as wide as its widest piece", Rect(100.0, 60.0, 10.0, 60.0), places[Piece.LEFT])
        assertEquals(Rect(290.0, 120.0, 10.0, 10.0), places[Piece.RIGHT_BOTTOM])
    }

    fun testCentreAloneFillsTheBoxAndCornersShrinkInASmallBox() {
        assertEquals(mapOf(Piece.CENTER to Rect(0.0, 0.0, 40.0, 20.0)), WidgetStyles.layout(Rect(0.0, 0.0, 40.0, 20.0), mapOf(Piece.CENTER to (2.0 to 2.0))))
        val small = WidgetStyles.layout(Rect(0.0, 0.0, 10.0, 10.0), mapOf(Piece.LEFT_TOP to (10.0 to 10.0), Piece.RIGHT_BOTTOM to (10.0 to 10.0)))
        assertEquals(Rect(0.0, 0.0, 5.0, 5.0), small[Piece.LEFT_TOP])
        assertEquals(Rect(5.0, 5.0, 5.0, 5.0), small[Piece.RIGHT_BOTTOM])
    }

    fun testTintMultipliesEveryChannel() {
        val white = solid(0xFFFFFFFF.toInt())
        assertEquals(0x80FF0000.toInt(), LayoutImages.tint(white, 0x80FF0000.toInt()).getRGB(0, 0))
    }

    /** A styled panel draws its frame instead of its colour; one with no frame keeps the colour. */
    fun testFrameReplacesThePlainColour() {
        val panel = WidgetSpec("PanelWidgetClass", "p", mapOf("color" to listOf("1", "0", "0", "1"), "style" to listOf("Framed")), emptyList(), -1)
        val placed = LayoutGeometry.layout(listOf(panel), 10.0, 10.0)
        val frame = LayoutRenderer.Frame(mapOf(Piece.CENTER to LayoutRenderer.Picture(solid(0xFF00FF00.toInt()), 1.0, 1.0)))
        val framed = LayoutRenderer.render(placed, 10, 10, false, null, frame = { frame }) { _, _ -> null }
        assertEquals(0xFF00FF00.toInt(), framed.getRGB(5, 5))
        val plain = LayoutRenderer.render(placed, 10, 10, false, null) { _, _ -> null }
        assertEquals(0xFFFF0000.toInt(), plain.getRGB(5, 5))
    }

    private fun solid(argb: Int): BufferedImage =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, argb) }
}
