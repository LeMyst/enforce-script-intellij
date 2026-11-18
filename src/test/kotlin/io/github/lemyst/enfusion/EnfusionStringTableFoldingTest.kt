package io.github.lemyst.enfusion

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.stringtable.EnStringTableFoldingBuilder
import io.github.lemyst.enfusion.stringtable.EnStringTableFoldingSettings

/** A stringtable key folds to its text: in script, layouts and configs, in the chosen language. */
class EnfusionStringTableFoldingTest : BasePlatformTestCase() {

    private val builder = EnStringTableFoldingBuilder()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "MyMod/LanguageCore/stringtable.csv",
            "\"Language\",\"original\",\"english\",\"french\"\n" +
                "\"STR_MYMOD_INVITE\",\"Invite to party\",\"Invite to party\",\"Inviter dans le groupe\"\n" +
                "\"STR_MYMOD_ONLY_ORIGINAL\",\"Only original\",\"\",\"\"\n" +
                "\"menu_add\",\"Add\",\"Add\",\"Ajouter\"\n" +
                "\"STR_MYMOD_LONG\",\"${"word ".repeat(30).trim()}\",\"\",\"\"\n",
        )
    }

    override fun tearDown() {
        try {
            PropertiesComponent.getInstance().unsetValue(LANGUAGE)
            EnStringTableFoldingSettings.instance().state.foldKeys = true
        } finally {
            super.tearDown()
        }
    }

    /** Each fold as `folded text <- placeholder`. */
    private fun folds(name: String, text: String): List<String> {
        val file = myFixture.configureByText(name, text)
        val document = myFixture.editor.document
        return builder.buildFoldRegions(file, document, false).map {
            "${document.getText(it.range)} <- ${it.placeholderText}"
        }
    }

    fun testScriptStrings() {
        assertEquals(
            listOf(
                "\"#STR_MYMOD_INVITE\" <- \"Invite to party\"",
                "\"STR_MYMOD_INVITE\" <- \"Invite to party\"",
                "\"#menu_add\" <- \"Add\"",
            ),
            folds("a.es", "class A\n{\n\tvoid F()\n\t{\n\t\tShow(\"#STR_MYMOD_INVITE\");\n\t\tSend(\"STR_MYMOD_INVITE\");\n\t\tShow(\"#menu_add\");\n\t\tShow(\"#STR_NO_ROW\");\n\t\tShow(\"plain\");\n\t}\n}"),
        )
    }

    /** The key is matched ignoring case, as the game does. */
    fun testCaseOfTheKey() {
        assertEquals(listOf("\"#str_mymod_invite\" <- \"Invite to party\""), folds("a.es", "string s = \"#str_mymod_invite\";"))
    }

    fun testLayoutAndConfigValues() {
        assertEquals(
            listOf("\"#STR_MYMOD_INVITE\" <- \"Invite to party\""),
            folds("a.layout", "TextWidgetClass Label {\n text \"#STR_MYMOD_INVITE\"\n}\n"),
        )
        assertEquals(
            listOf("\"\$STR_MYMOD_INVITE\" <- \"Invite to party\""),
            folds("config.cpp", "class CfgVehicles\n{\n\tclass A\n\t{\n\t\tdisplayName=\"\$STR_MYMOD_INVITE\";\n\t};\n};"),
        )
    }

    fun testLanguageOfTheLayoutPreviewWithFallback() {
        PropertiesComponent.getInstance().setValue(LANGUAGE, "french")
        assertEquals(
            listOf("\"#STR_MYMOD_INVITE\" <- \"Inviter dans le groupe\"", "\"#STR_MYMOD_ONLY_ORIGINAL\" <- \"Only original\""),
            folds("a.es", "string a = \"#STR_MYMOD_INVITE\";\nstring b = \"#STR_MYMOD_ONLY_ORIGINAL\";"),
        )
    }

    fun testLongTextIsCut() {
        val fold = folds("a.es", "string a = \"#STR_MYMOD_LONG\";").single()
        val placeholder = fold.substringAfter(" <- ")
        assertTrue(placeholder, placeholder.endsWith("…\""))
        assertTrue(placeholder, placeholder.length <= 62)
    }

    fun testOptionOff() {
        EnStringTableFoldingSettings.instance().state.foldKeys = false
        assertEquals(emptyList<String>(), folds("a.es", "string a = \"#STR_MYMOD_INVITE\";"))
    }

    fun testCollapsedByDefault() {
        val file = myFixture.configureByText("a.es", "string a = \"#STR_MYMOD_INVITE\";")
        val region = builder.buildFoldRegions(file, myFixture.editor.document, false).single()
        assertTrue(builder.isCollapsedByDefault(region.element))
    }

    private companion object {
        const val LANGUAGE = "enfusion.layoutPreview.language"
    }
}
