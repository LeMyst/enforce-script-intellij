package io.github.lemyst.enfusion

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.doc.EnDocLinks
import io.github.lemyst.enfusion.doc.EnfusionDocumentationProvider
import io.github.lemyst.enfusion.psi.EnNamedElement

/**
 * Quick Documentation, asked for the way the IDE asks: the element at the caret when it resolves
 * to one declaration, or the expression holding the reference when it resolves to several.
 */
class EnfusionQuickDocTest : BasePlatformTestCase() {

    private val provider = EnfusionDocumentationProvider()

    private fun docAt(text: String): String {
        myFixture.configureByText("test.es", text)
        val offset = myFixture.caretOffset
        val util = TargetElementUtil.getInstance()
        val element = util.findTargetElement(myFixture.editor, util.allAccepted, offset)
            ?: TargetElementUtil.findReference(myFixture.editor, offset)?.element
        assertNotNull("nothing at the caret", element)
        val doc = provider.generateDoc(element, myFixture.file.findElementAt(offset))
        assertNotNull("no documentation", doc)
        return doc!!
    }

    private fun mathFile() = myFixture.addFileToProject(
        "math.es",
        """
        class Math
        {
        	/**
        	\brief Returns a random \p int between min and max.
        		\param min Range starts
        		\param max Range ends
        		\return \p int - Random number
        	*/
        	static proto int RandomInt(int min, int max);

        	//! Pi
        	static const float PI = 3.14159;
        }
        """.trimIndent(),
    )

    fun testProtoMethodAtCall() {
        mathFile()
        val doc = docAt("class A { void F() { int r = Math.Random<caret>Int(0, 10); } }")
        assertTrue(doc, doc.contains("static proto int RandomInt(int min, int max)"))
        assertTrue(doc, doc.contains("Returns a random <code>int</code> between min and max."))
        assertTrue(doc, doc.contains("<code>min</code> &ndash; Range starts"))
        assertTrue(doc, doc.contains("Returns:"))
        assertTrue(doc, doc.contains("Class:"))
    }

    fun testDocumentationAtTheDeclarationItself() {
        val doc = docAt(
            """
            class A
            {
            	//! Counts the things.
            	//! Twice.
            	int Cou<caret>nt() { return 0; }
            }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("Counts the things. Twice."))
    }

    fun testTrailingComment() {
        val doc = docAt(
            """
            class A
            {
            	int m_Count; //!< number of items
            	int m_Other;
            	void F() { m_Cou<caret>nt = 1; }
            }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("number of items"))
    }

    /** Vanilla documents most constants with a plain `//` on their line; a method's is not taken. */
    fun testPlainTrailingCommentOnAConstantOnly() {
        val doc = docAt(
            """
            const int ECE_TRACE = 4; // trace under entity when being placed
            class X
            {
            	void G() // todo: remove
            	{
            	}
            	void F() { int v = ECE_TR<caret>ACE; }
            }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("trace under entity when being placed"))
        val method = findNamed("G")
        assertFalse(provider.generateDoc(method, null)!!.contains("todo"))
    }

    /** The comment trailing one field is not the next field's, and a blank line ends a comment. */
    fun testNoBorrowedComments() {
        myFixture.configureByText(
            "test.es",
            """
            //! Licence header

            class A
            {
            	int m_Count; //!< number of items
            	int m_Other;
            }
            """.trimIndent(),
        )
        val other = findNamed("m_Other")
        val doc = provider.generateDoc(other, null)!!
        assertFalse(doc, doc.contains("number of items"))
        val cls = findNamed("A")
        assertFalse(provider.generateDoc(cls, null)!!.contains("Licence"))
    }

    /** A `modded class` override rarely repeats the comment; the original's is shown. */
    fun testLayerFallsBackToOriginalComment() {
        myFixture.addFileToProject(
            "vanilla.es",
            """
            class Item
            {
            	//! Called once the item is created.
            	void EEInit() {}
            }
            """.trimIndent(),
        )
        val doc = docAt(
            """
            modded class Item
            {
            	override void EEIn<caret>it() { super.EEInit(); }
            }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("Called once the item is created."))
        assertTrue(doc, doc.contains("<i>From "))
        assertTrue(doc, doc.contains("Other layers:"))
    }

    /** A name every layer declares resolves to all of them; the original is documented. */
    fun testAmbiguousReferenceDocumentsTheOriginal() {
        myFixture.addFileToProject("vanilla.es", "class Item\n{\n\t//! The original.\n\tvoid Use() {}\n}")
        myFixture.addFileToProject("mod.es", "modded class Item { override void Use() {} }")
        val doc = docAt("class B { void F(Item i) { i.U<caret>se(); } }")
        assertTrue(doc, doc.contains("The original."))
    }

    fun testClassChainAndLayers() {
        myFixture.addFileToProject("base.es", "class Base {}\nclass Middle : Base {}")
        myFixture.addFileToProject("mod.es", "modded class Leaf {}")
        val doc = docAt("/** A leaf. */\nclass Le<caret>af extends Middle {}")
        assertTrue(doc, doc.contains("A leaf."))
        assertTrue(doc, doc.contains("Extends:"))
        assertTrue(doc, Regex("Middle</a> &rarr; <a [^>]*>Base</a> &rarr; ").containsMatchIn(doc) ||
            Regex("Middle</a> &rarr; <a [^>]*>Base</a>").containsMatchIn(doc))
        assertTrue(doc, doc.contains("Modded by:"))
    }

    fun testEnumConstantValues() {
        val doc = docAt(
            """
            enum E
            {
            	A,
            	B,
            	C = 8,
            	D
            }
            class X { void F() { int v = E.<caret>D; } }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("E.D"))
        assertTrue(doc, doc.contains("Value:"))
        assertTrue(doc, doc.contains("9"))
    }

    fun testFlagConstantShowsItsFlags() {
        val doc = docAt(
            """
            const int ECE_A = 0x04;
            const int ECE_B = 0x20;
            const int ECE_C = 0x400;
            const int ECE_ALL = ECE_A | ECE_B | ECE_C;
            class X { void F() { int v = ECE_A<caret>LL; } }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("1060 (0x424) = ECE_A | ECE_B | ECE_C"))
    }

    fun testStringConstant() {
        val doc = docAt(
            """
            const string PREFIX = "MyMod/";
            const string LAYOUT = PREFIX + "gui/hud.layout";
            class X { void F() { string s = LAY<caret>OUT; } }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("&quot;MyMod/gui/hud.layout&quot;"))
    }

    fun testAutoLocalShowsTheInferredType() {
        myFixture.addFileToProject("p.es", "class Param1<Class T1> {}\nclass Foo {}")
        val doc = docAt("class X { void F() { auto p = new Param1<Foo>(); p<caret>; } }")
        assertTrue(doc, doc.contains("Type:"))
        assertTrue(doc, doc.contains("Param1&lt;Foo&gt;"))
        assertTrue(doc, doc.contains("(inferred)"))
    }

    fun testParameterShowsItsParamText() {
        val doc = docAt(
            """
            class X
            {
            	/**
            	\param speed How fast, in m/s
            	*/
            	void Go(float speed) { float s = spe<caret>ed; }
            }
            """.trimIndent(),
        )
        assertTrue(doc, doc.contains("How fast, in m/s"))
        assertTrue(doc, doc.contains("Parameter of:"))
    }

    fun testQuickNavigateInfo() {
        mathFile()
        myFixture.configureByText("test.es", "class A {}")
        val method = findNamed("RandomInt")
        val info = provider.getQuickNavigateInfo(method, null)!!
        assertTrue(info, info.contains("Math<br>"))
        assertTrue(info, info.contains("static proto int RandomInt(int min, int max)"))
    }

    fun testLinksRoundTrip() {
        mathFile()
        myFixture.configureByText("test.es", "class A {}")
        val method = findNamed("RandomInt")
        val link = EnDocLinks.declaration(method)!!
        assertSame(method, EnDocLinks.resolve(PsiManager.getInstance(project), link))
    }

    /** The IDE finds the provider through the language, so the registration is what makes it run. */
    fun testRegisteredForTheLanguage() {
        val providers = com.intellij.lang.LanguageDocumentation.INSTANCE
            .allForLanguage(io.github.lemyst.enfusion.lang.EnfusionLanguage)
        assertTrue(providers.toString(), providers.any { it is EnfusionDocumentationProvider })
    }

    private fun findNamed(name: String): EnNamedElement {
        val files = listOfNotNull(myFixture.file) +
            listOfNotNull(myFixture.findFileInTempDir("math.es")?.let { PsiManager.getInstance(project).findFile(it) })
        for (file in files) {
            com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, EnNamedElement::class.java)
                .firstOrNull { it.name == name }?.let { return it }
        }
        error("no declaration named $name")
    }
}
