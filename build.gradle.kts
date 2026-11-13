import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.grammarkit") version "2023.3.0.4"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// 21, not newer: the oldest supported IDE (2025.2, see `sinceBuild`) runs on JBR 21, and a class
// file newer than its runtime fails to load with UnsupportedClassVersionError -- which the Plugin
// Verifier does NOT detect. `verifyPluginProjectConfiguration` does, and must stay clean.
kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // `intellijIdea(...)`, not `create(IC, ...)`: JetBrains merged Community and Ultimate into a
        // single distribution at 2025.3, so `IC` is no longer a published type at all -- which is
        // also why the old `ideaIC-<version>.win.zip` artifacts stop existing at that version.
        //
        // `platformVersion` is the OLDEST supported release, not the newest: compiling against it
        // is what guarantees no newer API slips in, and `verifyPlugin` then proves the newer IDEs.
        intellijIdea(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    // One settings page; building the searchable-options index is pure cost here.
    buildSearchableOptions = false

    pluginConfiguration {
        // The ID is permanent once published; the display name is not. The name avoids the
        // "DayZ" and "Enfusion" trademarks, and the description still says DayZ, which is what
        // Marketplace search matches on.
        id = "io.github.lemyst.enfusionscript"
        name = "Enforce Script"
        version = providers.gradleProperty("pluginVersion")
        // Required: the verifier rejects a descriptor without one, before running any compatibility
        // check at all. Marketplace renders it as HTML.
        description = """
            Language support for <b>Enforce Script</b>, the scripting language of DayZ mods.
            <p>
            Navigation-first, because the language makes finding things hard: there is no
            <code>#include</code> and no namespaces, so every symbol lives in one flat global
            namespace assembled from whole directories, spread across a mod, the ~3,000-file vanilla
            corpus and every dependency mod.
            <ul>
              <li>Code completion across every <code>modded class</code> layer and the superclass
                  chain, answered by the same type inference as Go to Declaration</li>
              <li>Parameter info on every overload a call may mean, with default values</li>
              <li>Go to Declaration on types, members, locals and free functions, across every
                  <code>modded class</code> layer</li>
              <li>Go to Declaration from string literals &mdash; widget names into
                  <code>.layout</code> files, stringtable keys into <code>stringtable.csv</code>,
                  imageset references, RPC and timer dispatch names, keybind actions</li>
              <li>Go to Class / Go to Symbol, structure view, find usages, type hierarchy, rename,
                  folding including <code>#ifdef</code> regions</li>
              <li>Inspections for mistakes the engine only reports at build time, such as a
                  missing <code>override</code> or a dispatch string naming no method</li>
              <li>A real PSI language for <code>.layout</code> / <code>.imageset</code> and for
                  <code>config.cpp</code> / <code>mod.cpp</code></li>
              <li>Indexes the vanilla script tree and dependency mod sources automatically</li>
            </ul>
            <p>
            Unofficial community project, not affiliated with or endorsed by Bohemia Interactive.
            DayZ and Enfusion are trademarks of Bohemia Interactive a.s.
        """.trimIndent()
        changeNotes = """
            <ul>
              <li><b>Code completion.</b> After a dot: the receiver's members across every
                  <code>modded class</code> layer and the superclass chain. Elsewhere: locals,
                  class members, free functions, globals and types in scope. Keywords are offered
                  where they fit the position. The popup opens on <code>.</code> by itself.</li>
              <li><b>Parameter info</b> (<kbd>Ctrl+P</kbd>, and on its own after <code>(</code>):
                  every overload a call may mean, with the current parameter highlighted and
                  default values shown.</li>
              <li><code>Cast</code>, <code>CastTo</code> and <code>ClassName</code> now resolve on
                  classes that name no superclass, since <code>Class</code> is their implicit root.</li>
              <li>Typing <code>"</code> now inserts the closing quote. A string left unclosed is
                  reported as <i>Missing closing quote</i>, instead of every string showing a
                  parse error until it was closed.</li>
              <li>Errors thrown by the plugin can be reported from the IDE's <i>Internal Errors</i>
                  dialog, using <i>Report to third-party plugin</i>.</li>
              <li>A project opened in Safe Mode no longer uses the script roots saved in its
                  <code>.idea</code> folder, which a cloned repository can supply. Trust the
                  project and reopen it to enable them.</li>
              <li>Files with deeply nested <code>#ifdef</code> blocks no longer stall parsing and
                  indexing. Some nestings used to take minutes or longer, even in a small file.</li>
              <li>A block comment left without <code>*/</code> now runs to the end of the file and
                  is reported as <i>Unclosed comment</i>, in scripts, layouts and configs. Files
                  with many such comments no longer slow highlighting and indexing.</li>
              <li>Long member chains and <code>auto</code> locals no longer stall highlighting.
                  A local whose initializer reads the local itself, such as
                  <code>auto a = a.b;</code>, no longer crashes it either.</li>
              <li>When several methods are offered to choose from, such as the layers of a
                  <code>modded class</code> behind the overridden-method gutter icon, each line now
                  names its class and the file it comes from.</li>
              <li>Looking up types and members is much faster in projects with many mods. The
                  <kbd>Ctrl</kbd>+hover underline on a chain such as
                  <code>GetGame().GetPlayer().GetIdentity()</code> now appears at once on every
                  link, instead of after a delay that grew along the chain.</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "Myst"
            url = "https://github.com/LeMyst/enforce-script-intellij"
        }
        ideaVersion {
            // Must agree with `platformVersion` (gradle.properties) and with the JVM target above.
            // `untilBuild` stays open; the verifier is what proves each newer release.
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }

    // Both blocks read environment variables and are inert when they are unset: `signPlugin` is
    // skipped without a certificate, and only `publishPlugin` needs the token. The first release
    // must be uploaded by hand on plugins.jetbrains.com regardless; see AGENTS.md.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // `recommended()` works now that the Gradle plugin is current. It was pinned to explicit
    // versions for a while because the old plugin (2.2.1) predated JetBrains merging Community and
    // Ultimate at 2025.3: it only knew the retired `ideaIC-<version>.win.zip` naming, so every
    // recent IDE looked like it had no binary and the task died before verifying anything. That was
    // stale tooling, not a JetBrains problem.
    pluginVerification { ides { recommended() } }
}

// `runIde` opens the oldest supported IDE, because that is what the build compiles against.
// `runIdeLatest` opens the newest release, so manual checks cover both ends of the range.
intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            version = providers.gradleProperty("latestPlatformVersion")
        }
    }
}

grammarKit {
    jflexRelease.set("1.10.18")
    grammarKitRelease.set("2023.3.4")
}

// `tasks.register(...)` rather than the `by tasks.registering(...)` delegate: the delegate form is
// deprecated as of Gradle 9.6 and the Kotlin DSL compiles that deprecation as an *error*, not a
// warning, so the build simply stops.
val generateEnfusionLexer = tasks.register<GenerateLexerTask>("generateEnfusionLexer") {
    sourceFile.set(file("src/main/grammars/EnfusionLexer.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/gen/io/github/lemyst/enfusion/lexer"))
    purgeOldFiles.set(true)
}

val generateEnfusionParser = tasks.register<GenerateParserTask>("generateEnfusionParser") {
    sourceFile.set(file("src/main/grammars/Enfusion.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/gen"))
    pathToParser.set("io/github/lemyst/enfusion/parser/EnfusionParser.java")
    pathToPsiRoot.set("io/github/lemyst/enfusion/psi")
    purgeOldFiles.set(true)
}

// The `.layout` / `.imageset` object format. One lexer and grammar serve both: they are the same
// format, differing only in which class names appear.
val generateEnfusionObjectLexer = tasks.register<GenerateLexerTask>("generateEnfusionObjectLexer") {
    sourceFile.set(file("src/main/grammars/EnfusionObjectLexer.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/gen/io/github/lemyst/enfusion/objfmt/lexer"))
    purgeOldFiles.set(true)
}

val generateEnfusionObjectParser = tasks.register<GenerateParserTask>("generateEnfusionObjectParser") {
    sourceFile.set(file("src/main/grammars/EnfusionObject.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/gen"))
    pathToParser.set("io/github/lemyst/enfusion/objfmt/parser/EnfusionObjectParser.java")
    pathToPsiRoot.set("io/github/lemyst/enfusion/objfmt/psi")
    purgeOldFiles.set(true)
}

// The `config.cpp` / `mod.cpp` config format. A third language, and the simplest of the three: the
// whole corpus contains no `#include`, `#ifdef` or `enum`, so unlike the script side there is no
// preprocessing lexer.
val generateEnfusionConfigLexer = tasks.register<GenerateLexerTask>("generateEnfusionConfigLexer") {
    sourceFile.set(file("src/main/grammars/EnfusionConfigLexer.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/gen/io/github/lemyst/enfusion/cfg/lexer"))
    purgeOldFiles.set(true)
}

val generateEnfusionConfigParser = tasks.register<GenerateParserTask>("generateEnfusionConfigParser") {
    sourceFile.set(file("src/main/grammars/EnfusionConfig.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/gen"))
    pathToParser.set("io/github/lemyst/enfusion/cfg/parser/EnfusionConfigParser.java")
    pathToPsiRoot.set("io/github/lemyst/enfusion/cfg/psi")
    purgeOldFiles.set(true)
}

val generateSources = listOf(
    generateEnfusionLexer,
    generateEnfusionParser,
    generateEnfusionObjectLexer,
    generateEnfusionObjectParser,
    generateEnfusionConfigLexer,
    generateEnfusionConfigParser,
)

sourceSets["main"].java.srcDirs("src/gen")

tasks.withType<KotlinCompile>().configureEach { dependsOn(generateSources) }
tasks.withType<JavaCompile>().configureEach { dependsOn(generateSources) }

// The GPL requires every distributed copy to carry the license text, and the Marketplace zip is a
// distributed copy. Shipping it in the jar's META-INF satisfies that without a separate file.
tasks.processResources {
    from("LICENSE") { into("META-INF") }
}

tasks.test {
    maxHeapSize = "4g"
    systemProperty(
        "enfusion.corpus.root",
        providers.gradleProperty("enfusionCorpusRoot").orNull ?: "",
    )
    // `-PenfusionCorpusLimit=300` samples the corpus instead of walking all 2,811 files.
    systemProperty(
        "enfusion.corpus.limit",
        providers.gradleProperty("enfusionCorpusLimit").orNull ?: "",
    )
    systemProperty(
        "enfusion.layout.roots",
        providers.gradleProperty("enfusionLayoutRoots").orNull ?: "",
    )
    systemProperty(
        "enfusion.config.roots",
        providers.gradleProperty("enfusionConfigRoots").orNull ?: "",
    )
    systemProperty(
        "enfusion.pbo.roots",
        providers.gradleProperty("enfusionPboRoots").orNull ?: "",
    )
    // The corpus tests report progress and throughput as they run; without this Gradle
    // buffers stdout until the test method returns, which makes a long run unobservable.
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
