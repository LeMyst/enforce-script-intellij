package io.github.lemyst.enfusion.format

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.lemyst.enfusion.lang.EnfusionLanguage

/**
 * Puts EnfusionScript in Settings | Editor | Code Style, and — the part that matters — defaults it
 * to **tabs**.
 *
 * Not a preference: vanilla is tab-indented without exception (3,776 tab-indented lines in
 * `dayzgame.c`, zero space-indented). The platform default is spaces, so without this the first
 * Ctrl+Alt+L on any file would silently convert its whole indentation and produce a diff touching
 * every line.
 */
class EnfusionLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = EnfusionLanguage

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun createCustomSettings(settings: CodeStyleSettings): CustomCodeStyleSettings =
        EnfusionCodeStyleSettings(settings)

    /**
     * The one real option, shown under *Wrapping and Braces* — where the C-family plugins put the
     * same choice, and the only settings type this panel offers that is not about spacing or blank
     * lines.
     */
    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType != SettingsType.WRAPPING_AND_BRACES_SETTINGS) return
        consumer.showCustomOption(
            EnfusionCodeStyleSettings::class.java,
            "PREPROCESSOR_AT_FIRST_COLUMN",
            "Keep #ifdef / #else / #endif at the first column",
            "Preprocessor directives",
        )
    }

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions,
    ) {
        indentOptions.USE_TAB_CHARACTER = true
        indentOptions.SMART_TABS = false
        indentOptions.TAB_SIZE = 4
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        // No BRACE_STYLE here on purpose. Vanilla is Allman without exception, but the formatter
        // emits no wrapping rules, so setting it would be inert -- and a setting that does nothing
        // is worse than none, since it reads as a promise that braces get managed.
    }

    override fun getCodeSample(settingsType: SettingsType): String = CODE_SAMPLE

    /**
     * *Tabs and Indents*, plus *Wrapping and Braces* — and only because the latter now has exactly
     * one rule behind it.
     *
     * The default panel also adds Spaces and Blank Lines. Those stay absent for the reason they were
     * removed: the formatter emits no spacing or blank-line rules at all, and an empty tab is worse
     * than a missing one because it advertises settings that would silently do nothing.
     */
    override fun createConfigurable(
        baseSettings: CodeStyleSettings,
        modelSettings: CodeStyleSettings,
    ): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "Enforce Script") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : com.intellij.application.options.TabbedLanguageCodeStylePanel(
                    EnfusionLanguage, currentSettings, settings,
                ) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                        addWrappingAndBracesTab(settings)
                    }
                }
        }

    private companion object {
        val CODE_SAMPLE = """
            class BattleRoyaleState
            {
            	protected int m_PlayerCount;

            	void OnPlayerCountChanged(int count)
            	{
            		m_PlayerCount = count;
            #ifdef SERVER
            		if (count > 0)
            		{
            			Activate();
            		}
            #endif
            	}
            }
        """.trimIndent()
    }
}
