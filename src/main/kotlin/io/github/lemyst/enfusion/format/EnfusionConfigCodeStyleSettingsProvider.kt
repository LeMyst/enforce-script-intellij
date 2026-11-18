package io.github.lemyst.enfusion.format

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.lemyst.enfusion.cfg.EnfusionConfigLanguage

/**
 * *Settings → Editor → Code Style → Enforce Config*, defaulting to **tabs**: every vanilla config
 * on `P:\DZ` is tab-indented, and the platform's default of spaces would turn the first
 * Ctrl+Alt+L into a diff touching every line.
 *
 * Only *Tabs and Indents*: the formatter has no spacing, wrapping or blank-line rules, and an
 * empty tab would advertise settings that do nothing.
 */
class EnfusionConfigCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = EnfusionConfigLanguage

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.USE_TAB_CHARACTER = true
        indentOptions.SMART_TABS = false
        indentOptions.TAB_SIZE = 4
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
    }

    override fun getCodeSample(settingsType: SettingsType): String = CODE_SAMPLE

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "Enforce Config") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(EnfusionConfigLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                    }
                }
        }

    private companion object {
        val CODE_SAMPLE = """
            class CfgPatches
            {
            	class MyMod_Scripts
            	{
            		units[]={};
            		requiredAddons[]=
            		{
            			"DZ_Data",
            			"DZ_Scripts"
            		};
            	};
            };
            class CfgVehicles
            {
            	class Inventory_Base;
            	class MyMod_Item: Inventory_Base
            	{
            		scope=2;
            		displayName="${'$'}STR_MyMod_Item";
            	};
            };
        """.trimIndent()
    }
}
