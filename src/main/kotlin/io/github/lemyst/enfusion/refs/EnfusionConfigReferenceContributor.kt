package io.github.lemyst.enfusion.refs

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgValueMixin

/**
 * References on the values inside a `config.cpp`.
 *
 * Three of the four providers are the existing ones, reused unchanged, and that is most of the reason
 * this language was cheap to add: a config carries exactly the string kinds script and layouts do.
 * Vanilla writes `displayName="#STR_CfgShoulder0"` and
 * `ghostIcon="set:dayz_inventory image:shoulderleft"`, and a mod writes
 * `inputs="BattleRoyale/Data/Inputs.xml"` and `picture="…/Mod_Logo.paa"`. Because those three
 * match on *content* rather than on call shape, they transplant to a third language with no
 * modification at all.
 *
 * Only the addon reference is new, because an addon name has no distinctive content — only its
 * position inside `requiredAddons[]` says what it is.
 */
class EnfusionConfigReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val value = psiElement(EnCfgValueMixin::class.java)
        registrar.registerReferenceProvider(value, EnCfgAddonReferenceProvider())
        registrar.registerReferenceProvider(value, StringTableKeyReferenceProvider())
        registrar.registerReferenceProvider(value, EnImageSetReferenceProvider())
        registrar.registerReferenceProvider(value, EnAssetPathReferenceProvider())
    }
}

/** The config-side counterpart of [EnStringLiteralManipulator]; see there for why one is required. */
class EnCfgValueManipulator : AbstractElementManipulator<EnCfgValueMixin>() {

    override fun getRangeInElement(element: EnCfgValueMixin): TextRange =
        EnStringLiterals.contentRange(element) ?: TextRange.from(0, element.textLength)

    override fun handleContentChange(
        element: EnCfgValueMixin,
        range: TextRange,
        newContent: String,
    ): EnCfgValueMixin {
        val updated = range.replace(element.text, newContent)
        val replacement = EnStringLiterals.createConfigValue(element.project, updated) ?: return element
        return element.replace(replacement) as EnCfgValueMixin
    }
}
