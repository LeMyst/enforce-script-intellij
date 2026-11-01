package io.github.lemyst.enfusion.psi

import com.intellij.psi.PsiNameIdentifierOwner

/**
 * Anything that introduces a name into EnfusionScript's single flat global namespace: a class, enum,
 * enum constant, typedef, method, field, parameter or `foreach` variable.
 *
 * The language has no packages, no imports and no scope operator, so a declaration's identity is
 * just its name plus the addon it came from. That is what makes a name-keyed index sufficient for
 * navigation, and it is why every declaration form implements this one interface.
 */
interface EnNamedElement : PsiNameIdentifierOwner
