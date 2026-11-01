package io.github.lemyst.enfusion.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.lemyst.enfusion.lang.EnfusionIcons
import javax.swing.Icon

class EnfusionColorSettingsPage : ColorSettingsPage {

    override fun getIcon(): Icon = EnfusionIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = EnfusionSyntaxHighlighter()

    override fun getDisplayName(): String = "Enforce Script"

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null

    /** Every construct here is real corpus syntax, so the preview doubles as a lexer smoke test. */
    override fun getDemoText(): String = """
        //! Super root of all classes in Enforce script
        /** Doxygen block: \brief demo */
        #ifdef SERVER
        modded class BattleRoyaleServer extends BattleRoyaleBase
        {
            protected static const int MAX_PLAYERS = 0x40;
            protected ref array<ref array<float>> m_Polygons;
            private float m_Ratio = 0.75f;
            static const vector UP = "0 1 0";

            override void OnPlayerTick(PlayerBase player, float delta)
            {
                foreach (int i, PlayerBase p : GetPlayers())
                {
                    if (p && !p.IsAlive()) continue;
                    p.DecreaseHealthCoef(delta * m_Ratio);
                }

                switch (m_State)
                {
                    case "lobby": break;
                    default: super.OnPlayerTick(player, delta);
                }
            }
        }
        #endif
    """.trimIndent()

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", EnfusionSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Primitive type", EnfusionSyntaxHighlighter.TYPE),
            AttributesDescriptor("Identifier", EnfusionSyntaxHighlighter.IDENT),
            AttributesDescriptor("String", EnfusionSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", EnfusionSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comments//Line comment", EnfusionSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", EnfusionSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Doc comment", EnfusionSyntaxHighlighter.DOC_COMMENT),
            AttributesDescriptor("Preprocessor directive", EnfusionSyntaxHighlighter.PP_DIRECTIVE),
            AttributesDescriptor("Inactive preprocessor branch", EnfusionSyntaxHighlighter.INACTIVE_CODE),
            AttributesDescriptor("Operator", EnfusionSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Braces and operators//Braces", EnfusionSyntaxHighlighter.BRACES),
            AttributesDescriptor("Braces and operators//Parentheses", EnfusionSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Braces and operators//Brackets", EnfusionSyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Braces and operators//Semicolon", EnfusionSyntaxHighlighter.SEMICOLON),
            AttributesDescriptor("Braces and operators//Comma", EnfusionSyntaxHighlighter.COMMA),
            AttributesDescriptor("Braces and operators//Dot", EnfusionSyntaxHighlighter.DOT),
            AttributesDescriptor("Bad character", EnfusionSyntaxHighlighter.BAD_CHARACTER),
        )
    }
}
