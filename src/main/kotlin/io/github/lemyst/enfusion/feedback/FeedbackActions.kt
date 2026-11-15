package io.github.lemyst.enfusion.feedback

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Help-menu entries for the issue tracker. They are the permanent way in: the startup notice is
 * gone once dismissed, and someone who hits a problem months later should not have to look for
 * the repository.
 */
class ReportIssueAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(FeedbackLinks.bugReport(FeedbackLinks.environment()))
}

class RequestFeatureAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(FeedbackLinks.featureRequest(FeedbackLinks.environment()))
}
