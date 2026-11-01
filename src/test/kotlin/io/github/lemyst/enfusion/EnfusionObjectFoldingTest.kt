package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.objfmt.EnfusionObjectFoldingBuilder

/** Widget trees nest several levels deep; folding is what keeps a `.layout` readable at all. */
class EnfusionObjectFoldingTest : BasePlatformTestCase() {

    fun testEveryNestingLevelGetsItsOwnRegion() {
        myFixture.configureByText(
            "a.layout",
            """
            FrameWidgetClass PartyRoot {
             {
              PanelWidgetClass PartyRows {
               size 1 1
              }
             }
            }
            """.trimIndent(),
        )
        val document = myFixture.getDocument(myFixture.file)
        val descriptors = EnfusionObjectFoldingBuilder().buildFoldRegions(myFixture.file, document, false)
        // The outer objectDecl, the anonymous children childBlock, and the inner objectDecl.
        assertEquals(3, descriptors.size)
    }

    fun testSingleLineDeclarationDoesNotFold() {
        myFixture.configureByText("a.imageset", "ImageSetDefClass Foo { Name \"Foo\" }")
        val document = myFixture.getDocument(myFixture.file)
        val descriptors = EnfusionObjectFoldingBuilder().buildFoldRegions(myFixture.file, document, false)
        assertEquals(0, descriptors.size)
    }
}
