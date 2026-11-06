package io.github.lemyst.enfusion

import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * Shapes on which type inference used to take exponential time or overflow the stack. Inference
 * runs during highlighting, through inspections that are on by default, so any of these in a file
 * froze the editor that opened it. Each case is timed out well above what it now takes.
 */
class EnfusionInferenceCostTest : BasePlatformTestCase() {

    /** The outermost [type] expression enclosing the caret, within its statement. */
    private fun exprAtCaret(type: IElementType): PsiElement {
        var element: PsiElement? = myFixture.file.findElementAt(myFixture.caretOffset)
        var found: PsiElement? = null
        while (element != null && element.node?.elementType !== EnfusionTypes.EXPR_STMT) {
            if (element.node?.elementType === type) found = element
            element = element.parent
        }
        return found ?: error("no $type at the caret")
    }

    private fun classNamesAtCaret(type: IElementType): List<String?> =
        EnTypeInference.classesOf(exprAtCaret(type), project).map { it.name }

    private fun withinSeconds(seconds: Long, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
        assertTrue("took $elapsed s", elapsed < seconds)
    }

    /** Three of these took 12 s; each name resolves to every one of them, each inferred again. */
    fun testManySelfReferencingAutoLocals() = withinSeconds(5) {
        myFixture.configureByText("a.es", "class A { void F() { ${"auto a = a; ".repeat(50)}Use(<caret>a); } }")
        assertEmpty(classNamesAtCaret(EnfusionTypes.REF_EXPR))
    }

    /** One line overflowed the stack: resolving `b` re-entered inference for `a` with no end. */
    fun testAutoLocalReadingItsOwnMember() = withinSeconds(5) {
        myFixture.configureByText("b.es", "class B { B b; }\nclass A { void F() { auto a = a.b; Use(<caret>a); } }")
        assertEmpty(classNamesAtCaret(EnfusionTypes.REF_EXPR))
    }

    fun testManyAutoLocalsReadingMembers() = withinSeconds(5) {
        myFixture.configureByText("c.es", "class B { B b; }\nclass A { void F() { ${"auto a = a.b; ".repeat(50)}Use(<caret>a); } }")
        assertEmpty(classNamesAtCaret(EnfusionTypes.REF_EXPR))
    }

    /** Twelve links took 7 s, each inferred once per use of it; the answer must still arrive. */
    fun testLongMemberChain() = withinSeconds(5) {
        myFixture.configureByText("d.es", "class B { B b; }\nclass A { void F() { B x; Use(<caret>x${".b".repeat(30)}); } }")
        assertEquals(listOf("B"), classNamesAtCaret(EnfusionTypes.MEMBER_EXPR))
    }

    /** Beyond the nesting cap the tail resolves to nothing, rather than overflowing the stack. */
    fun testPathologicalChainIsCutOffNotOverflowed() = withinSeconds(10) {
        myFixture.configureByText("e.es", "class B { B b; }\nclass A { void F() { B x; Use(<caret>x${".b".repeat(3000)}); } }")
        assertEmpty(classNamesAtCaret(EnfusionTypes.MEMBER_EXPR))
    }

    /** The memo must not change answers: a chain through `auto` and a generic accessor. */
    fun testAutoThroughAChainStillResolves() {
        myFixture.configureByText(
            "f.es",
            """
            class array<Class T> { proto T Get(int n); }
            class Zone { Zone Next() { return null; } }
            class A
            {
            	array<ref Zone> m_Zones;
            	void F()
            	{
            		auto z = m_Zones.Get(0).Next();
            		auto y = z.Next();
            		Use(<caret>y);
            	}
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Zone"), classNamesAtCaret(EnfusionTypes.REF_EXPR))
    }
}
