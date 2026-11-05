package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.lemyst.enfusion.nav.EnfusionParameterInfoHandler
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Parameter info, driven through the handler as the IDE drives it: find the call, update for the
 * caret, render each overload.
 *
 * Each overload renders as its parameter text with the current parameter in `[brackets]`, prefixed
 * with `~` when it is greyed out.
 */
class EnfusionParameterInfoTest : BasePlatformTestCase() {

    private val handler = EnfusionParameterInfoHandler()

    private fun hint(text: String): List<String>? {
        myFixture.configureByText("test.es", text)
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val argList = handler.findElementForParameterInfo(create) ?: return null
        val update = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file, create.itemsToShow)
        handler.updateParameterInfo(argList, update)
        return create.itemsToShow.orEmpty().map { item ->
            val ui = MockParameterInfoUIContext(argList)
            ui.currentParameterIndex = update.currentParameter
            handler.updateUI(item as EnMethodDeclMixin, ui)
            val shown = ui.text
            val highlighted = if (ui.highlightStart >= 0) {
                shown.substring(0, ui.highlightStart) + "[" +
                    shown.substring(ui.highlightStart, ui.highlightEnd) + "]" + shown.substring(ui.highlightEnd)
            } else {
                shown
            }
            (if (ui.isTextDisabled) "~" else "") + highlighted
        }
    }

    private fun sender() = myFixture.addFileToProject(
        "sender.es",
        """
        class Sender
        {
        	void Send(int channel) {}
        	void Send(int channel, string data, bool reliable = true) {}
        	void Flush() {}
        }
        """.trimIndent(),
    )

    /** Every overload is listed, even those the reference would narrow away, with the first parameter current. */
    fun testAllOverloadsAtAnEmptyCall() {
        sender()
        assertSameElements(
            hint("class A { void F(Sender s) { s.Send(<caret>); } }")!!,
            "[int channel]",
            "[int channel], string data, bool reliable = true",
        )
    }

    /** The highlight follows the commas; an overload too short for the argument is greyed out. */
    fun testCurrentParameterFollowsTheCaret() {
        sender()
        assertSameElements(
            hint("class A { void F(Sender s) { s.Send(1, \"x\"<caret>); } }")!!,
            "~int channel",
            "int channel, [string data], bool reliable = true",
        )
    }

    fun testNoParameters() {
        sender()
        assertSameElements(hint("class A { void F(Sender s) { s.Flush(<caret>); } }")!!, "<no parameters>")
    }

    /** Free functions, which most of the engine API is. */
    fun testFreeFunction() {
        myFixture.addFileToProject("proto.es", "proto void PrintFormat(string fmt, int level = 0);")
        assertSameElements(hint("class A { void F() { PrintFormat(<caret>); } }")!!, "[string fmt], int level = 0")
    }

    /** A method of the enclosing class, reached through a `modded class` layer. */
    fun testMethodOfTheEnclosingClassLayers() {
        myFixture.addFileToProject("base.es", "class MissionServer { void Kick(string id, string reason) {} }")
        assertSameElements(
            hint("modded class MissionServer { void F() { Kick(\"a\", <caret>); } }")!!,
            "string id, [string reason]",
        )
    }

    /** An override redeclared by a layer is the same signature, shown once. */
    fun testOverrideAcrossLayersShownOnce() {
        myFixture.addFileToProject(
            "layers.es",
            "class Unit { void Hit(float damage) {} }\nmodded class Unit { override void Hit(float damage) {} }",
        )
        assertSameElements(hint("class A { void F(Unit u) { u.Hit(<caret>); } }")!!, "[float damage]")
    }

    fun testConstructorOfNew() {
        myFixture.addFileToProject("zone.es", "class Zone { void Zone(vector center, float radius) {} }")
        assertSameElements(
            hint("class A { void F() { Zone z = new Zone(\"0 0 0\", <caret>); } }")!!,
            "vector center, [float radius]",
        )
    }

    /** The innermost call wins, and a finished inner call hands back to the outer one. */
    fun testNestedCalls() {
        myFixture.addFileToProject(
            "calls.es",
            "class Calls { int Inner(string key) {} void Outer(int value, bool flag) {} }",
        )
        assertSameElements(
            hint("class A { void F(Calls c) { c.Outer(c.Inner(<caret>)); } }")!!,
            "[string key]",
        )
        assertSameElements(
            hint("class A { void F(Calls c) { c.Outer(c.Inner(\"k\"), <caret>); } }")!!,
            "int value, [bool flag]",
        )
    }

    /** Before the `(` or after the `)` the call is not being typed. */
    fun testNoHintOutsideTheParentheses() {
        sender()
        assertNull(hint("class A { void F(Sender s) { s.Send<caret>(1); } }"))
        assertNull(hint("class A { void F(Sender s) { s.Send(1)<caret>; } }"))
    }

    /** An attribute's argument list has no method behind it. */
    fun testNoHintInAnAttribute() {
        assertNull(hint("class A { [Obsolete(<caret>\"old\")] void F() {} }"))
    }

    /** Records what the mock ignores: whether the handler asked for the hint to close. */
    private class RecordingUpdateContext(
        editor: com.intellij.openapi.editor.Editor,
        file: com.intellij.psi.PsiFile,
        items: Array<Any>?,
    ) : MockUpdateParameterInfoContext(editor, file, items) {
        var removed = false
        override fun removeHint() {
            removed = true
        }
    }

    private fun openHint(text: String): Pair<RecordingUpdateContext, com.intellij.psi.PsiElement> {
        myFixture.configureByText("test.es", text)
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val argList = handler.findElementForParameterInfo(create)!!
        val update = RecordingUpdateContext(myFixture.editor, myFixture.file, create.itemsToShow)
        handler.updateParameterInfo(argList, update)
        return update to argList
    }

    private fun updateAtCaret(update: RecordingUpdateContext): Boolean {
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val argList = handler.findElementForUpdatingParameterInfo(update) ?: return false
        handler.updateParameterInfo(argList, update)
        return true
    }

    /**
     * Typing reparses the call and replaces the argument list the hint was opened on with an equal
     * one. That is still the same call, so the hint stays and follows the caret.
     */
    fun testHintSurvivesTypingArguments() {
        sender()
        val (update, _) = openHint("class A { void F(Sender s) { s.Send(<caret>); } }")
        myFixture.type("1, ")
        assertTrue(updateAtCaret(update))
        assertFalse("the hint must stay open while the call is typed", update.removed)
        assertEquals(1, update.currentParameter)
    }

    /** A stray character breaks the call's parse for a moment; the `(` still places the caret. */
    fun testHintSurvivesAMomentarilyBrokenCall() {
        sender()
        val (update, _) = openHint("class A { void F(Sender s) { s.Send(1, <caret>); } }")
        myFixture.type(";")
        assertTrue("the call is still found with `;` typed in it", updateAtCaret(update))
        assertFalse(update.removed)
        assertEquals(1, update.currentParameter)
        // Backspace, removing the stray `;` again.
        myFixture.type("\b")
        assertTrue(updateAtCaret(update))
        assertFalse(update.removed)
    }

    /** Moving into another call closes this hint. */
    fun testMovingToAnotherCallClosesTheHint() {
        sender()
        val (update, _) = openHint("class A { void F(Sender s) { s.Send(<caret>1); s.Send(2); } }")
        val second = myFixture.editor.document.text.lastIndexOf("(2") + 1
        myFixture.editor.caretModel.moveToOffset(second)
        assertTrue(updateAtCaret(update))
        assertTrue(update.removed)
    }

    /** A parenthesised argument is not a call: the hint stays on the call around it. */
    fun testParenthesisedArgumentKeepsTheOuterCall() {
        sender()
        assertSameElements(
            hint("class A { void F(Sender s) { s.Send(1, (a + <caret>)); } }")!!,
            "~int channel",
            "int channel, [string data], bool reliable = true",
        )
    }

    /** `Class` is the implicit root: a class that names no superclass still has `Cast`. */
    fun testCastThroughTheImplicitRoot() {
        myFixture.addFileToProject(
            "root.es",
            "class Class { proto static Class Cast(Class from); }\nclass Managed {}\nclass Widget : Managed {}\nclass ButtonWidget : Widget {}",
        )
        assertSameElements(hint("class A { void F() { Widget.Cast(<caret>); } }")!!, "[Class from]")
        assertSameElements(hint("class A { void F() { ButtonWidget.Cast(<caret>); } }")!!, "[Class from]")
    }
}
