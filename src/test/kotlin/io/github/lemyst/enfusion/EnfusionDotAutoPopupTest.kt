package io.github.lemyst.enfusion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.completion.EnfusionDotAutoPopup

/**
 * The member popup is scheduled on `.` in code, and not on a dot typed in a string or comment.
 *
 * Checks the handler's decision rather than a popup on screen: the platform's auto-popup test base
 * ships with the Java plugin's test framework, which this build does not depend on.
 */
class EnfusionDotAutoPopupTest : BasePlatformTestCase() {

    private fun decisionFor(text: String): Result {
        val file = myFixture.configureByText("test.es", text)
        return EnfusionDotAutoPopup().checkAutoPopup('.', project, myFixture.editor, file)
    }

    fun testDotInCodeSchedulesThePopup() {
        assertEquals(Result.STOP, decisionFor("class A { void F(Sender s) { s<caret> } }"))
    }

    fun testDotInStringOrCommentDoesNot() {
        assertEquals(Result.CONTINUE, decisionFor("class A { string p = \"gui/layouts/a<caret>\"; }"))
        assertEquals(Result.CONTINUE, decisionFor("class A { // see notes<caret>\n}"))
    }
}
