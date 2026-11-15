package io.github.lemyst.enfusion.feedback

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A notice that the plugin is young, with a link to the issue tracker.
 *
 * It must not become a nag, so it is limited in four ways. It appears only in DayZ addon projects,
 * because [io.github.lemyst.enfusion.startup.EnfusionAutoDetect] calls it after its addon check. It
 * appears once per IDE session, however many projects open. Its state is application-level, so each
 * project does not ask again. And it has its own notification group, so it can be muted in
 * *Settings | Appearance & Behavior | Notifications* without muting the build and work-drive
 * notifications.
 */
object FeedbackNotice {

    /** Also the group's name in the Notifications settings, hence the wording. */
    const val GROUP = "Enforce Script feedback"

    private const val DISMISSED_KEY = "enfusion.feedback.dismissed"
    private const val NEXT_KEY = "enfusion.feedback.nextReminder"

    internal val REMIND_AFTER_MS = TimeUnit.DAYS.toMillis(7)

    private val shownThisSession = AtomicBoolean()

    internal fun isDue(dismissed: Boolean, nextReminder: Long, now: Long): Boolean = !dismissed && now >= nextReminder

    fun showIfDue(project: Project) {
        val properties = PropertiesComponent.getInstance()
        val now = System.currentTimeMillis()
        if (!isDue(properties.getBoolean(DISMISSED_KEY), properties.getLong(NEXT_KEY, 0), now)) return
        if (!shownThisSession.compareAndSet(false, true)) return

        // The next reminder is scheduled as soon as the notice is shown. Closing the balloon with its
        // X, or never looking at it, does not expire the notification, so without this an ignored
        // notice would come back on every start.
        properties.setValue(NEXT_KEY, (now + REMIND_AFTER_MS).toString())

        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(
                "Enforce Script is in an early stage",
                "Please report bugs and request features on GitHub. " +
                    "<i>Help | Report Enforce Script Issue</i> opens a report with your versions filled in.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Open GitHub issues") { BrowserUtil.browse(FeedbackLinks.ISSUES) })
            // The reminder is already scheduled; this only closes the notice.
            .addAction(NotificationAction.createSimpleExpiring("Remind me later") {})
            .addAction(
                NotificationAction.createSimpleExpiring("Don't show again") {
                    PropertiesComponent.getInstance().setValue(DISMISSED_KEY, true)
                },
            )
            .notify(project)
    }
}
