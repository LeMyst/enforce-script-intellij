package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Keywords, offered only where the position can take them. */
class EnfusionKeywordCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("test.es", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testStatementStart() {
        val offered = complete("class A { int F() { <caret> } }")
        assertContainsElements(offered, "if", "foreach", "return", "break", "ref", "auto", "new", "this", "int")
        // Members and file-scope declarations never start a statement.
        assertDoesntContain(offered, "override", "class", "else", "case", "void", "null")
    }

    fun testElseOnlyAfterAnIfWithoutOne() {
        assertContainsElements(complete("class A { void F() { if (a) {} <caret> } }"), "else")
        assertDoesntContain(complete("class A { void F() { if (a) {} else {} <caret> } }"), "else")
        assertDoesntContain(complete("class A { void F() { Foo(); <caret> } }"), "else")
    }

    fun testCaseAndDefaultOnlyInSwitch() {
        assertContainsElements(complete("class A { void F() { switch (a) { case 1: Foo(); <caret> } } }"), "case", "default")
        assertDoesntContain(complete("class A { void F() { <caret> } }"), "case", "default")
    }

    fun testExpressionPosition() {
        val offered = complete("class A { void F() { Foo(<caret>); } }")
        assertContainsElements(offered, "null", "true", "false", "this", "new", "vector")
        assertDoesntContain(offered, "if", "return", "ref", "void")
    }

    /** A lone identifier in a parameter list or class body does not parse; tokens still place it. */
    fun testParameterAndMemberPositions() {
        val parameter = complete("class A { void F(int a, <caret>) {} }")
        assertContainsElements(parameter, "out", "inout", "notnull", "int")
        assertDoesntContain(parameter, "void", "override", "class")

        val member = complete("class A\n{\n\tvoid F() {}\n\t<caret>\n\tint m_X;\n}")
        assertContainsElements(member, "static", "override", "proto", "void", "int")
        assertDoesntContain(member, "class", "enum", "if", "return")
    }

    fun testFileScope() {
        val offered = complete("class A {}\n<caret>")
        assertContainsElements(offered, "class", "modded", "enum", "typedef", "void")
        assertDoesntContain(offered, "override", "if", "return")
    }

    fun testAfterModdedOnlyClass() {
        complete("modded cl<caret>")
        myFixture.checkResult("modded class <caret>")
    }

    fun testAfterClassNameOnlyExtends() {
        complete("class A ext<caret>\n{\n}")
        myFixture.checkResult("class A extends <caret>\n{\n}")
    }

    /** No keyword where a name is being declared, after a dot, or inside an enum. */
    fun testNoKeywordsWhereNoneFit() {
        assertEmpty(complete("class A { int <caret>; }").filter { it in setOf("if", "int", "static") })
        assertEmpty(complete("class A { void F() { a.<caret> } }"))
        assertEmpty(complete("enum E\n{\n<caret>\n}"))
    }

    /** A primitive is a keyword and also a class vanilla declares: it is offered once. */
    fun testPrimitiveOfferedOnce() {
        myFixture.addFileToProject("proto.es", "class int {} class integer_helper {}")
        val offered = complete("class A { void F() { in<caret> } }")
        assertEquals(1, offered.count { it == "int" })
    }

    /** Each caret sits before a line break: a space already after it would be reused, not doubled. */
    fun testKeywordInsertsSpaceWhenSomethingFollows() {
        complete("class A { void F() { forea<caret>\n} }")
        myFixture.checkResult("class A { void F() { foreach <caret>\n} }")
    }

    /** `return` in a void method is followed by `;`, so it takes no space; with a value, it does. */
    fun testReturnSpacingFollowsReturnType() {
        complete("class A { void F() { retu<caret>\n} }")
        myFixture.checkResult("class A { void F() { return<caret>\n} }")

        complete("class A { int F() { retu<caret>\n} }")
        myFixture.checkResult("class A { int F() { return <caret>\n} }")
    }
}
