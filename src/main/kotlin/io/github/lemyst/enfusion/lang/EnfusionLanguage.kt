package io.github.lemyst.enfusion.lang

import com.intellij.lang.Language

/** Kotlin objects already expose a static `INSTANCE` field, which is what Java code references. */
object EnfusionLanguage : Language("EnfusionScript") {
    override fun getDisplayName(): String = "Enforce Script"
}
