package io.github.lemyst.enfusion.feedback

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.SystemInfo
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Where users report bugs and ask for features: the GitHub issue tracker.
 *
 * A bug report opens GitHub's new-issue form with the plugin, IDE and OS versions already filled in,
 * because those are the first questions asked on almost every report and the ones users most often
 * leave out. Exceptions do not come here: they go to the Marketplace through the `errorHandler` in
 * `plugin.xml`, which needs no GitHub account and keeps stack traces private.
 */
object FeedbackLinks {

    /** The same repository as the vendor URL in `build.gradle.kts`. */
    const val REPOSITORY = "https://github.com/LeMyst/enforce-script-intellij"
    const val ISSUES = "$REPOSITORY/issues"

    internal data class Environment(val plugin: String, val ide: String, val os: String)

    internal fun environment(): Environment {
        val app = ApplicationInfo.getInstance()
        return Environment(
            plugin = pluginVersion(),
            ide = "${app.fullApplicationName} (build ${app.build.asString()})",
            os = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} (${SystemInfo.OS_ARCH})",
        )
    }

    /** Written by `processResources` in `build.gradle.kts`; see the comment there. */
    internal fun pluginVersion(): String =
        FeedbackLinks::class.java.getResourceAsStream("version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?: "unknown"

    internal fun bugReport(environment: Environment): String = newIssue(
        label = "bug",
        body = """
            |**What happened**
            |
            |
            |**What you expected**
            |
            |
            |**Steps to reproduce** (a few lines of script or a file layout, if it helps)
            |
            |
            |---
            |${environmentLines(environment)}
        """.trimMargin(),
    )

    internal fun featureRequest(environment: Environment): String = newIssue(
        label = "enhancement",
        body = """
            |**What you would like**
            |
            |
            |**Why** (what you are trying to do in your mod)
            |
            |
            |---
            |Plugin: ${environment.plugin}
        """.trimMargin(),
    )

    private fun environmentLines(environment: Environment) =
        "Plugin: ${environment.plugin}\nIDE: ${environment.ide}\nOS: ${environment.os}"

    /**
     * GitHub ignores `labels` for someone who cannot triage the repository, so the label is a
     * convenience for the author's own reports and harmless for everyone else.
     */
    private fun newIssue(label: String, body: String) = "$ISSUES/new?labels=$label&body=${encode(body)}"

    /** `URLEncoder` is form encoding; `%20` rather than `+` keeps spaces intact in any decoder. */
    private fun encode(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
