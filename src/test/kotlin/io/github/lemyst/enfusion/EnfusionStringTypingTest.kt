package io.github.lemyst.enfusion

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Typing a string: the quote pairs, and only a string left unclosed is reported. */
class EnfusionStringTypingTest : BasePlatformTestCase() {

    private fun errors(): List<String> =
        myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }

    fun testQuotePairsInsideACall() {
        myFixture.configureByText("test.es", "class A { void F() { CreateObject(\"Apple\", <caret>); } }")
        myFixture.type("\"")
        myFixture.checkResult("class A { void F() { CreateObject(\"Apple\", \"<caret>\"); } }")
        myFixture.type("1 2 1\"")
        myFixture.checkResult("class A { void F() { CreateObject(\"Apple\", \"1 2 1\"<caret>); } }")
        assertEmpty(errors())
    }

    fun testUnclosedStringIsReportedOnTheString() {
        myFixture.configureByText("test.es", "class A\n{\n\tvoid F()\n\t{\n\t\tPrint(\"open);\n\t}\n}")
        assertEquals(listOf("Missing closing quote"), errors().filter { it == "Missing closing quote" })
    }

    /** An escaped quote does not close a string; an escaped backslash before the quote does not stop it. */
    fun testEscapesAreRespected() {
        myFixture.configureByText("test.es", "class A\n{\n\tvoid F()\n\t{\n\t\tPrint(\"a\\\");\n\t}\n}")
        assertContainsElements(errors(), "Missing closing quote")
        myFixture.configureByText("test.es", "class A { void F() { Print(\"a\\\\\"); } }")
        assertDoesntContain(errors(), "Missing closing quote")
    }
}
