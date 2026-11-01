// Auto-provisions the JDK the toolchain asks for. IntelliJ 2026.2 ships JBR 25 and its own classes
// are Java 25 bytecode, so the build needs a JDK 25 that is not installed on every machine --
// without this the build fails with "No matching toolchains found" instead of downloading one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "enforce-script-intellij"
