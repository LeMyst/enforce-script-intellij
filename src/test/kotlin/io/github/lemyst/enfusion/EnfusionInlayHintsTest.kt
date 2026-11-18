package io.github.lemyst.enfusion

import com.intellij.codeInsight.hints.HintInfo
import com.intellij.codeInsight.hints.InlayParameterHintsExtension
import com.intellij.codeInsight.hints.filtering.MatcherConstructor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.hints.EnAutoTypeHints
import io.github.lemyst.enfusion.hints.EnParameterNameHintsProvider
import io.github.lemyst.enfusion.lang.EnfusionLanguage
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Inlay hints: parameter names before literal arguments, and the type of an `auto` variable.
 * Each hint reads `text@word`, the word being the source text right after the hint.
 */
class EnfusionInlayHintsTest : BasePlatformTestCase() {

    private val provider = EnParameterNameHintsProvider()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "api.es",
            """
            class Foo {}
            class Queue
            {
            	void CallLater(func fn, int delay = 0, bool repeat = false) {}
            	void SetSpeed(float speed) {}
            	void Send(int channel) {}
            	void Send(int port) {}
            	void Move(float x, float y) {}
            }
            class Box
            {
            	void Box(int width, string label) {}
            }
            class Math
            {
            	static float Clamp(float value, float min, float max) { return value; }
            }
            """.trimIndent(),
        )
    }

    private fun parameterHints(body: String): List<String> {
        myFixture.configureByText("test.es", "class T\n{\n\tvoid F(Queue q)\n\t{\n\t\t$body\n\t}\n\tvoid Refresh() {}\n}")
        val text = myFixture.file.text
        return argLists().flatMap { provider.getParameterHints(it) }.map { hint ->
            "${hint.text}@" + text.substring(hint.offset).takeWhile { it != ',' && it != ')' }
        }
    }

    private fun argLists(): List<PsiElement> =
        PsiTreeUtil.collectElements(myFixture.file) { it.node?.elementType === EnfusionTypes.ARG_LIST }.toList()

    fun testLiteralArgumentsGetTheirParameterName() {
        assertEquals(listOf("delay@1000", "repeat@false"), parameterHints("q.CallLater(Refresh, 1000, false);"))
    }

    fun testNegativeNumbersAndStringsCount() {
        assertEquals(listOf("x@-1.5", "y@2"), parameterHints("q.Move(-1.5, 2);"))
        assertEquals(listOf("width@3", "label@\"lid\""), parameterHints("Box b = new Box(3, \"lid\");"))
    }

    fun testVariablesGetNone() {
        assertEquals(emptyList<String>(), parameterHints("float x; q.Move(x, x);"))
    }

    /** Two overloads with different names at the position: a hint would be a guess. */
    fun testOverloadsThatDisagreeGetNone() {
        assertEquals(emptyList<String>(), parameterHints("q.Send(5);"))
    }

    fun testOneParameterSetterGetsNone() {
        assertEquals(emptyList<String>(), parameterHints("q.SetSpeed(5);"))
    }

    fun testHintInfoAndDefaultExcludeList() {
        parameterHints("float v = Math.Clamp(5, 0, 10);")
        val info = provider.getHintInfo(argLists().single()) as HintInfo.MethodInfo
        assertEquals("Math.Clamp", info.fullyQualifiedName)
        val excluded = provider.defaultBlackList.mapNotNull { MatcherConstructor.createMatcher(it) }
        assertTrue(excluded.any { it.isMatching(info.fullyQualifiedName, info.paramNames) })
        assertFalse(excluded.any { it.isMatching("Queue.CallLater", listOf("fn", "delay", "repeat")) })
    }

    fun testRegisteredForTheLanguage() {
        assertTrue(InlayParameterHintsExtension.forLanguage(EnfusionLanguage) is EnParameterNameHintsProvider)
    }

    /** The settings page loads its preview from this path, named after the file type's extension. */
    fun testSettingsPreviewIsAResource() {
        assertEquals("es", EnfusionLanguage.associatedFileType?.defaultExtension)
        val preview = provider.javaClass.classLoader.getResource("inlayProviders/Parameters/preview.es")
        assertNotNull(preview)
        assertTrue(preview!!.readText().contains("CallLater(Run, 1000, false, item)"))
    }

    private fun typeHints(body: String): List<String> {
        myFixture.addFileToProject("p.es", "class Param1<Class T1> {}\nclass array<Class T> {}")
        myFixture.configureByText("test.es", "class T\n{\n\tvoid F(array<Foo> list)\n\t{\n\t\t$body\n\t}\n}")
        val text = myFixture.file.text
        return PsiTreeUtil.collectElements(myFixture.file) { true }.mapNotNull { EnAutoTypeHints.hintFor(it) }.map { hint ->
            "${text.substring(0, hint.offset).takeLastWhile { it.isLetterOrDigit() || it == '_' }}${hint.text}"
        }
    }

    fun testAutoFromAnInitializer() {
        assertEquals(listOf("p: Param1<Foo>"), typeHints("auto p = new Param1<Foo>();"))
    }

    fun testAutoInForeach() {
        assertEquals(listOf("item: Foo"), typeHints("foreach (auto item : list) {}"))
    }

    fun testWrittenTypesAndFailedInferenceGetNone() {
        assertEquals(emptyList<String>(), typeHints("Foo f = new Foo(); auto u = Unknown();"))
    }
}
