package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.lang.EnfusionFoldingBuilder
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Every `{ .. }` block in the language -- class bodies, statement blocks, enum bodies, switch
 * bodies, array literals -- collapses to `{...}`, but only once it actually spans more than one
 * line: a one-line `{}` folding to the same three characters it already displays as would be noise.
 *
 * The same rule covers `#ifdef` regions, which fold whole rather than per `#else` arm.
 */
class EnfusionFoldingTest : BasePlatformTestCase() {

    private fun regionsIn(text: String): Array<com.intellij.lang.folding.FoldingDescriptor> {
        myFixture.configureByText("a.es", text)
        val document = myFixture.getDocument(myFixture.file)
        return EnfusionFoldingBuilder().buildFoldRegions(myFixture.file, document, false)
    }

    fun testMultiLineClassAndMethodBodiesFold() {
        val descriptors = regionsIn(
            """
            class Foo
            {
            	void Bar()
            	{
            		int x = 1;
            	}
            }
            """.trimIndent(),
        )
        // The class body and the method body -- both span more than one line.
        assertEquals(2, descriptors.size)
        assertTrue(descriptors.all { EnfusionFoldingBuilder().getPlaceholderText(it.element) == "{...}" })
    }

    fun testSingleLineBlocksDoNotFold() {
        val descriptors = regionsIn("class Foo { void Bar() {} }")
        assertEquals(0, descriptors.size)
    }

    fun testEnumBodyFolds() {
        val descriptors = regionsIn(
            """
            enum Foo
            {
            	A,
            	B,
            }
            """.trimIndent(),
        )
        assertEquals(1, descriptors.size)
    }

    fun testNestedBlocksEachGetTheirOwnRegion() {
        val descriptors = regionsIn(
            """
            class Foo
            {
            	void Bar()
            	{
            		if (true)
            		{
            			int x = 1;
            		}
            	}
            }
            """.trimIndent(),
        )
        // classBody, Bar()'s blockStmt, and the if's blockStmt -- three independent regions.
        assertEquals(3, descriptors.size)
    }

    // ---- preprocessor regions -------------------------------------------------------------------

    private val builder = EnfusionFoldingBuilder()

    private fun conditionalRegions(text: String) =
        regionsIn(text).filter { it.element.elementType === EnfusionTypes.PP_DIRECTIVE }

    /** The whole-file `#ifdef SERVER ... #endif` wrapper every server file in a real mod opens with. */
    fun testWholeFileConditionalWrapperFolds() {
        val regions = conditionalRegions(
            """
            #ifdef SERVER
            class Foo
            {
            	void Bar() {}
            }
            #endif
            """.trimIndent(),
        )
        assertEquals(1, regions.size)
        assertEquals("#ifdef SERVER ... #endif", builder.getPlaceholderText(regions[0].element))
        assertFalse(builder.isCollapsedByDefault(regions[0].element))
    }

    /** An `#else` arm does not get its own fold -- see the comment on `foldConditionalRegions`. */
    fun testRegionWithElseFoldsAsOneUnit() {
        val regions = conditionalRegions(
            """
            class Foo
            {
            	void Bar()
            	{
            #ifdef SERVER
            		int x = 1;
            #else
            		int y = 2;
            #endif
            	}
            }
            """.trimIndent(),
        )
        assertEquals(1, regions.size)
    }

    fun testNestedConditionalsEachFold() {
        val regions = conditionalRegions(
            """
            #ifdef SERVER
            class Foo
            {
            #ifdef DIAG
            	void Debug() {}
            #endif
            }
            #endif
            """.trimIndent(),
        )
        assertEquals(2, regions.size)
    }

    /** `#ifndef` opens a region exactly as `#ifdef` does -- client files use it throughout. */
    fun testIfndefFolds() {
        val regions = conditionalRegions(
            """
            #ifndef SERVER
            class Foo
            {
            }
            #endif
            """.trimIndent(),
        )
        assertEquals(1, regions.size)
        assertEquals("#ifndef SERVER ... #endif", builder.getPlaceholderText(regions[0].element))
    }

    /**
     * An empty region has nothing to hide.
     *
     * Note a conditional region is *never* single-line -- a directive runs to end of line, so
     * `#endif` is always on a later one. The brace rule ("starts and ends on different lines") would
     * therefore accept even this, which is why directives get their own stricter check.
     */
    fun testEmptyConditionalDoesNotFold() {
        assertEquals(0, conditionalRegions("#ifdef SERVER\n#endif").size)
    }

    /** An unmatched directive is ignored rather than guessed at. */
    fun testUnbalancedDirectivesProduceNoRegion() {
        assertEquals(0, conditionalRegions("#endif\nclass Foo\n{\n}\n").size)
        assertEquals(0, conditionalRegions("#ifdef SERVER\nclass Foo\n{\n}\n").size)
    }
}
