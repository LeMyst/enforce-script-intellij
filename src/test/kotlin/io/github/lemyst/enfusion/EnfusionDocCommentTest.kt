package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.doc.EnDocComment
import io.github.lemyst.enfusion.doc.EnDocValues
import junit.framework.TestCase

/** The Doxygen subset vanilla writes, parsed from plain strings. */
class EnfusionDocCommentTest : TestCase() {

    fun testBlockWithBriefParamsAndReturn() {
        val doc = EnDocComment.parse(
            listOf(
                """
                /**
                \brief Returns a random \p int number between and min [inclusive] and max [exclusive].
                	\param min \p int Range starts [inclusive]
                	\param max \p int Range ends [exclusive]
                	\return \p int - Random number in range
                	@code
                		Print( Math.RandomInt(0, 1) );
                	@endcode
                */
                """.trimIndent(),
            ),
        )
        assertEquals(
            "Returns a random <code>int</code> number between and min [inclusive] and max [exclusive]." +
                "<p><pre><code>Print( Math.RandomInt(0, 1) );</code></pre>",
            doc.description,
        )
        assertEquals(listOf("min", "max"), doc.params.map { it.first })
        assertEquals("<code>int</code> Range starts [inclusive]", doc.paramText("min"))
        // The example follows `\return`, and still goes with the description.
        assertEquals("<code>int</code> - Random number in range", doc.returns)
    }

    fun testStarredBlockAndBlankLineEndsParam() {
        val doc = EnDocComment.parse(
            listOf(
                """
                /*!
                 * \param x the value
                 *
                 * More about the method.
                 */
                """.trimIndent(),
            ),
        )
        assertEquals("the value", doc.paramText("x"))
        assertEquals("More about the method.", doc.description)
    }

    fun testConsecutiveLineCommentsJoin() {
        val doc = EnDocComment.parse(listOf("//! Sets the speed", "//! of the vehicle."))
        assertEquals("Sets the speed of the vehicle.", doc.description)
    }

    fun testTrailingFormAndSlashRuns() {
        assertEquals("number of items", EnDocComment.parse(listOf("//!< number of items")).description)
        assertEquals("Sound is moveable", EnDocComment.parse(listOf("////!Sound is moveable")).description)
        assertTrue(EnDocComment.parse(listOf("///////////////")).isEmpty)
    }

    fun testGroupingCommandsAreDropped() {
        val doc = EnDocComment.parse(
            listOf("/**\n\\fn int Foo(int a)\n\\defgroup Things Things\n@{\nDoes foo.\n@}\n*/"),
        )
        assertEquals("Does foo.", doc.description)
    }

    fun testNotesAndWarnings() {
        val doc = EnDocComment.parse(listOf("/**\nDoes it.\n\\note Only on the server.\n\\warning Slow.\n*/"))
        assertEquals("Does it.", doc.description)
        assertEquals(listOf("Note" to "Only on the server.", "Warning" to "Slow."), doc.notes)
    }

    /** A comment from a library file is text, never markup. */
    fun testHtmlIsEscaped() {
        val doc = EnDocComment.parse(listOf("//! a <b>bold</b> & \"quoted\" claim"))
        assertEquals("a &lt;b&gt;bold&lt;/b&gt; &amp; &quot;quoted&quot; claim", doc.description)
    }

    fun testUnknownCommandStaysText() {
        assertEquals(
            "Loads from \\Entities\\ folder",
            EnDocComment.parse(listOf("//! Loads from \\Entities\\ folder")).description,
        )
    }

    fun testIntValues() {
        assertEquals("5", EnDocValues.describeInt(5, emptyList()))
        assertEquals("1060 (0x424) = A | B", EnDocValues.describeInt(1060, listOf("A", "B")))
        assertEquals("-1 (0xFFFFFFFF)", EnDocValues.describeInt(0xFFFFFFFFL, emptyList()))
    }
}
