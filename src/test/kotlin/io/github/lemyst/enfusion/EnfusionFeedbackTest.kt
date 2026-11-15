package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.feedback.FeedbackLinks
import io.github.lemyst.enfusion.feedback.FeedbackNotice
import junit.framework.TestCase
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** The early-stage notice's schedule, and the pre-filled GitHub issue links. */
class EnfusionFeedbackTest : TestCase() {

    private val environment = FeedbackLinks.Environment(
        plugin = "0.2.0",
        ide = "IntelliJ IDEA 2025.2 (build IU-252.1)",
        os = "Windows 11 10.0 (amd64)",
    )

    fun testNoticeIsDueUntilDismissedAndWaitsForTheReminder() {
        assertTrue("never shown", FeedbackNotice.isDue(dismissed = false, nextReminder = 0, now = 1_000))
        assertFalse("snoozed", FeedbackNotice.isDue(dismissed = false, nextReminder = 2_000, now = 1_000))
        assertTrue("snooze over", FeedbackNotice.isDue(dismissed = false, nextReminder = 2_000, now = 2_000))
        assertFalse("dismissed", FeedbackNotice.isDue(dismissed = true, nextReminder = 0, now = 1_000))
    }

    fun testTheBuildWritesThePluginVersion() {
        val version = FeedbackLinks.pluginVersion()
        assertTrue(version, version.matches(Regex("""\d+\.\d+\.\d+.*""")))
    }

    fun testBugReportCarriesTheVersionsAndTheBugLabel() {
        val url = FeedbackLinks.bugReport(environment)
        assertTrue(url, url.startsWith("${FeedbackLinks.ISSUES}/new?labels=bug&body="))
        assertFalse("spaces must not be form-encoded", url.contains('+'))
        val body = body(url)
        assertTrue(body, body.contains("Plugin: 0.2.0\nIDE: IntelliJ IDEA 2025.2 (build IU-252.1)\nOS: Windows 11 10.0 (amd64)"))
        assertTrue(body, body.contains("**Steps to reproduce**"))
    }

    fun testFeatureRequestCarriesThePluginVersionOnly() {
        val url = FeedbackLinks.featureRequest(environment)
        assertTrue(url, url.startsWith("${FeedbackLinks.ISSUES}/new?labels=enhancement&body="))
        val body = body(url)
        assertTrue(body, body.contains("Plugin: 0.2.0"))
        assertFalse(body, body.contains("IDE:"))
    }

    fun testCharactersThatEndAQueryParameterAreEncoded() {
        val url = FeedbackLinks.bugReport(environment.copy(ide = "A&B=C#D"))
        assertTrue(body(url), body(url).contains("IDE: A&B=C#D"))
    }

    private fun body(url: String) =
        URLDecoder.decode(url.substringAfter("&body="), StandardCharsets.UTF_8).also {
            assertFalse("the body is the last parameter", url.substringAfter("&body=").contains('&'))
        }
}
